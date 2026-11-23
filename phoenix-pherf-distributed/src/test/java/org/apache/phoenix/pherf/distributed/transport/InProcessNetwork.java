/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.pherf.distributed.transport;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.phoenix.pherf.distributed.clock.VirtualClock;
import org.apache.phoenix.pherf.distributed.protocol.Ack;
import org.apache.phoenix.pherf.distributed.protocol.AgentMessage;
import org.apache.phoenix.pherf.distributed.protocol.Codec;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.protocol.ProtocolException;
import org.apache.phoenix.pherf.distributed.protocol.Rejected;

/**
 * Simulates an in-process network within a single thread driven by a virtual clock. Every message
 * is transmitted as JSON through the Codec, and drain() delivers queued messages in FIFO order,
 * while any message assigned a delay enters the delivery queue when its clock timer expires. When
 * an agent or an individual stream of an agent is frozen, it neither receives nor sends messages
 * until it is thawed. The active {@link Policy} determines the delivery delay or loss of each
 * message as well as any duplicate copies of a request. A broken stream fails its currently
 * in-flight call immediately and fails every subsequent call at once until the stream is restored.
 * The event log records every connection, request, copy, loss, failure, and reply along with its
 * timestamp, agent, and stream.
 */
public final class InProcessNetwork {
  /** Represents a single entry in the network event log. */
  public static final class Event {
    public final String kind;
    public final String agent;
    public final String stream;
    public final String json;
    public final long atMs;

    Event(String kind, String agent, String stream, String json, long atMs) {
      this.kind = kind;
      this.agent = agent;
      this.stream = stream;
      this.json = json;
      this.atMs = atMs;
    }

    @Override
    public String toString() {
      return kind + " " + agent + " " + stream + (json == null ? "" : " " + json);
    }
  }

  public static final String CONNECT = "connect";
  public static final String REQUEST = "request";
  public static final String REPLY = "reply";
  /**
   * Event kind recorded when a duplicate copy of a request reaches the controller; its reply is
   * lost.
   */
  public static final String COPY = "copy";
  /** Event kind recorded when a request or a reply is lost in transit. */
  public static final String DROP = "drop";
  /**
   * Event kind recorded when a request call fails on the agent side; the event payload holds the
   * serialized request.
   */
  public static final String FAIL = "fail";

  /** Defines the delivery timing, duplication, and failure fate of each message on the network. */
  public interface Policy {
    /**
     * Sentinel delay indicating that the message is lost and its call fails
     * {@link Protocol#IO_TIMEOUT_MS} after it was sent.
     */
    long DROP = -1;
    /**
     * Sentinel delay indicating that the message is lost and its call fails immediately, simulating
     * a reset connection.
     */
    long RESET = -2;

    /**
     * Returns the delivery delay of a single message in milliseconds (ms), or DROP or RESET, where
     * direction is REQUEST or REPLY.
     */
    long delay(String agent, String stream, String direction, Message message);

    /**
     * Returns the delivery delays for duplicate copies of a request; each copy is delivered to the
     * controller, and its resulting reply is discarded.
     */
    default long[] copies(String agent, String stream, Message request) {
      return new long[0];
    }

    /**
     * Returns the reply message delivered to the agent in place of the reply produced by the
     * controller.
     */
    default Message reply(String agent, String stream, Message reply) {
      return reply;
    }
  }

  private static final class Delivery {
    final String agent;
    final String stream;
    final Runnable action;

    Delivery(String agent, String stream, Runnable action) {
      this.agent = agent;
      this.stream = stream;
      this.action = action;
    }
  }

  /**
   * Tracks a single in-flight request and guarantees that its completion callback is invoked at
   * most once.
   */
  private final class Call<T> {
    final String agent;
    final String stream;
    final String json;
    final long sentMs = clock.millis();
    final Callback<T> callback;
    boolean done;

    Call(String agent, String stream, String json, Callback<T> callback) {
      this.agent = agent;
      this.stream = stream;
      this.json = json;
      this.callback = callback;
    }

    /**
     * Transitions this call to a failed state and schedules its failure callback through the
     * delivery queue.
     */
    void fail(String reason) {
      if (done) {
        return;
      }
      done = true;
      inFlight.remove(this);
      enqueue(agent, stream, () -> {
        events.add(new Event(FAIL, agent, stream, json, clock.millis()));
        callback.failed(new IOException(reason));
      });
    }
  }

  private final VirtualClock clock;
  private final Map<String, AgentChannel> listeners = new LinkedHashMap<>();
  private final ArrayDeque<Delivery> queue = new ArrayDeque<>();
  private final Set<String> frozen = new HashSet<>();
  private final Set<String> broken = new HashSet<>();
  private final List<Call<?>> inFlight = new ArrayList<>();
  private final List<Event> events = new ArrayList<>();
  private Policy policy = (agent, stream, direction, message) -> 0;

  public InProcessNetwork(VirtualClock clock) {
    this.clock = clock;
  }

  /**
   * Binds a channel so that it is reachable at the specified endpoint; a subsequent invocation
   * replaces the bound channel to simulate a controller restart.
   */
  public void listen(String endpoint, AgentChannel channel) {
    listeners.put(endpoint, channel);
  }

  /**
   * Connects an agent to the specified endpoint and returns its transport, which opens each stream
   * upon its first use.
   */
  public AgentTransport connect(String agent, String endpoint) {
    if (!listeners.containsKey(endpoint)) {
      throw new IllegalArgumentException("nothing listens at " + endpoint);
    }
    return new Link(agent, endpoint);
  }

  public void policy(Policy policy) {
    this.policy = policy;
  }

  public List<Event> events() {
    return Collections.unmodifiableList(events);
  }

  /** Formats the network log as plain text, with one line per recorded event. */
  public List<String> log() {
    List<String> lines = new ArrayList<>();
    for (Event event : events) {
      lines.add(event.toString());
    }
    return lines;
  }

  /** Suspends delivery of all messages sent to and from the specified agent. */
  public void freeze(String agent) {
    frozen.add(agent);
  }

  /** Suspends delivery of all messages on a single stream of the specified agent. */
  public void freeze(String agent, String stream) {
    frozen.add(agent + "/" + stream);
  }

  public void thaw(String agent) {
    frozen.remove(agent);
  }

  public void thaw(String agent, String stream) {
    frozen.remove(agent + "/" + stream);
  }

  /**
   * Breaks the specified stream for an agent, immediately failing any currently in-flight call and
   * causing all subsequent calls on that stream to fail at once.
   */
  public void breakStream(String agent, String stream) {
    broken.add(agent + "/" + stream);
    for (Call<?> call : new ArrayList<>(inFlight)) {
      if (call.agent.equals(agent) && call.stream.equals(stream)) {
        call.fail("connection to the controller broken");
      }
    }
  }

  public void restore(String agent, String stream) {
    broken.remove(agent + "/" + stream);
  }

  /**
   * Delivers queued messages in order until no deliverable messages remain for any unfrozen agent
   * or stream.
   */
  public void drain() {
    Delivery next;
    while ((next = next()) != null) {
      next.action.run();
    }
  }

  private Delivery next() {
    Iterator<Delivery> it = queue.iterator();
    while (it.hasNext()) {
      Delivery delivery = it.next();
      if (
        !frozen.contains(delivery.agent) && !frozen.contains(delivery.agent + "/" + delivery.stream)
      ) {
        it.remove();
        return delivery;
      }
    }
    return null;
  }

  private void enqueue(String agent, String stream, Runnable action) {
    queue.add(new Delivery(agent, stream, action));
  }

  /**
   * Enqueues a message for delivery after its configured delay, or drops it and schedules a call
   * failure when the policy specifies message loss.
   */
  private void transmit(Call<?> call, long delay, String json, Runnable action) {
    if (delay == Policy.DROP || delay == Policy.RESET) {
      events.add(new Event(DROP, call.agent, call.stream, json, clock.millis()));
      long failAfter = delay == Policy.RESET
        ? 0
        : Math.max(0, call.sentMs + Protocol.IO_TIMEOUT_MS - clock.millis());
      clock.schedule(failAfter, () -> call.fail("read timed out"));
    } else if (delay == 0) {
      enqueue(call.agent, call.stream, action);
    } else {
      clock.schedule(delay, () -> enqueue(call.agent, call.stream, action));
    }
  }

  private static Message decodeReply(String json) {
    try {
      return Codec.decode(json);
    } catch (ProtocolException e) {
      throw new AssertionError("the controller sent a bad reply: " + json, e);
    }
  }

  private final class Link implements AgentTransport {
    private final String agent;
    private final String endpoint;
    private final Set<String> open = new HashSet<>();

    Link(String agent, String endpoint) {
      this.agent = agent;
      this.endpoint = endpoint;
    }

    private void open(String stream) {
      if (open.add(stream)) {
        events.add(new Event(CONNECT, agent, stream, null, clock.millis()));
      }
    }

    /**
     * Initiates a network call by failing it immediately if the target stream is broken, or by
     * transmitting the request along with any policy-configured duplicate copies.
     */
    private <T> void send(String stream, Message request, Callback<T> callback,
      Deliverer<T> deliverer) {
      open(stream);
      String json = Codec.encode(request);
      Call<T> call = new Call<>(agent, stream, json, callback);
      if (broken.contains(agent + "/" + stream)) {
        call.fail("connection refused");
        return;
      }
      inFlight.add(call);
      transmit(call, policy.delay(agent, stream, REQUEST, request), json,
        () -> deliverer.deliver(call, json, false));
      for (long copy : policy.copies(agent, stream, request)) {
        transmit(call, copy, json, () -> deliverer.deliver(call, json, true));
      }
    }

    /**
     * Transmits the reply for an in-flight call after its configured delay, or drops it upon policy
     * loss; the reply is ignored if the call has already completed or failed.
     */
    private <T> void answer(Call<T> call, Message reply, Replier<T> replier) {
      Message given = policy.reply(agent, call.stream, reply);
      String replyJson = Codec.encode(given);
      transmit(call, policy.delay(agent, call.stream, REPLY, given), replyJson, () -> {
        if (call.done) {
          return;
        }
        call.done = true;
        inFlight.remove(call);
        events.add(new Event(REPLY, agent, call.stream, replyJson, clock.millis()));
        replier.reply(call.callback, decodeReply(replyJson));
      });
    }

    @Override
    public void control(Message request, Callback<Message> callback) {
      send(Protocol.CONTROL, request, callback, (call, json, copy) -> {
        events.add(new Event(copy ? COPY : REQUEST, agent, Protocol.CONTROL, json, clock.millis()));
        AgentMessage decoded;
        try {
          decoded = ChannelRequests.control(json);
        } catch (ProtocolException e) {
          reject(call, e, copy);
          return;
        }
        listeners.get(endpoint).control(decoded, new Callback<Message>() {
          @Override
          public void completed(Message reply) {
            if (!copy) {
              answer(call, reply, Callback::completed);
            }
          }

          @Override
          public void failed(IOException e) {
            if (!copy) {
              call.fail(e.getMessage());
            }
          }
        });
      });
    }

    @Override
    public void telemetry(Intervals batch, Callback<Ack> callback) {
      send(Protocol.TELEMETRY, batch, callback, (call, json, copy) -> {
        events
          .add(new Event(copy ? COPY : REQUEST, agent, Protocol.TELEMETRY, json, clock.millis()));
        Intervals decoded;
        try {
          decoded = ChannelRequests.telemetry(json);
        } catch (ProtocolException e) {
          reject(call, e, copy);
          return;
        }
        Message reply = listeners.get(endpoint).telemetry(decoded);
        if (!copy) {
          answer(call, reply, (target, value) -> {
            if (value instanceof Rejected) {
              target.failed(new RejectedException(((Rejected) value).getReason()));
            } else {
              target.completed((Ack) value);
            }
          });
        }
      });
    }

    /**
     * Replicates the HTTP adapter response to a malformed request by returning a 400 status
     * carrying a rejected message.
     */
    private <T> void reject(Call<T> call, ProtocolException e, boolean copy) {
      if (copy) {
        return;
      }
      answer(call, new Rejected(e.getMessage()),
        (target, value) -> target.failed(new RejectedException(e.getMessage())));
    }

    @Override
    public void close() {
      open.clear();
    }
  }

  /** Delivers a serialized request, or a duplicate copy of it, to the bound controller channel. */
  private interface Deliverer<T> {
    void deliver(Call<T> call, String json, boolean copy);
  }

  /** Dispatches a decoded reply message to the completion callback of an in-flight call. */
  private interface Replier<T> {
    void reply(Callback<T> callback, Message reply);
  }
}
