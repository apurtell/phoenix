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
import org.apache.phoenix.pherf.distributed.protocol.Ack;
import org.apache.phoenix.pherf.distributed.protocol.AgentMessage;
import org.apache.phoenix.pherf.distributed.protocol.Codec;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.protocol.ProtocolException;
import org.apache.phoenix.pherf.distributed.protocol.Rejected;

/**
 * Simulates a network within a single thread. Every message is transmitted as JSON through the
 * Codec, and drain() delivers queued messages in FIFO order. When an agent or a specific stream of
 * an agent is frozen, it neither receives nor sends messages until it is thawed. The log records
 * every connection, request, and reply along with its associated agent and stream.
 */
public final class InProcessNetwork {
  /** Represents a single entry in the network event log. */
  public static final class Event {
    public final String kind;
    public final String agent;
    public final String stream;
    public final String json;

    Event(String kind, String agent, String stream, String json) {
      this.kind = kind;
      this.agent = agent;
      this.stream = stream;
      this.json = json;
    }

    @Override
    public String toString() {
      return kind + " " + agent + " " + stream + (json == null ? "" : " " + json);
    }
  }

  public static final String CONNECT = "connect";
  public static final String REQUEST = "request";
  public static final String REPLY = "reply";

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

  private final Map<String, AgentChannel> listeners = new LinkedHashMap<>();
  private final ArrayDeque<Delivery> queue = new ArrayDeque<>();
  private final Set<String> frozen = new HashSet<>();
  private final List<Event> events = new ArrayList<>();

  /** Registers a channel so that it is reachable at the specified endpoint. */
  public void listen(String endpoint, AgentChannel channel) {
    listeners.put(endpoint, channel);
  }

  /**
   * Connects an agent to the specified endpoint and returns its transport, which opens each stream
   * upon its first use.
   */
  public AgentTransport connect(String agent, String endpoint) {
    AgentChannel channel = listeners.get(endpoint);
    if (channel == null) {
      throw new IllegalArgumentException("nothing listens at " + endpoint);
    }
    return new Link(agent, channel);
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
   * Delivers queued messages in order until no deliverable messages remain for any agent that is
   * not frozen.
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

  private static Message decodeReply(String json) {
    try {
      return Codec.decode(json);
    } catch (ProtocolException e) {
      throw new AssertionError("the controller sent a bad reply: " + json, e);
    }
  }

  private final class Link implements AgentTransport {
    private final String agent;
    private final AgentChannel channel;
    private final Set<String> open = new HashSet<>();

    Link(String agent, AgentChannel channel) {
      this.agent = agent;
      this.channel = channel;
    }

    private void open(String stream) {
      if (open.add(stream)) {
        events.add(new Event(CONNECT, agent, stream, null));
      }
    }

    @Override
    public void control(Message request, Callback<Message> callback) {
      open(Protocol.CONTROL);
      String json = Codec.encode(request);
      enqueue(agent, Protocol.CONTROL, () -> {
        events.add(new Event(REQUEST, agent, Protocol.CONTROL, json));
        AgentMessage decoded;
        try {
          decoded = ChannelRequests.control(json);
        } catch (ProtocolException e) {
          reject(Protocol.CONTROL, e, callback);
          return;
        }
        channel.control(decoded, new Callback<Message>() {
          @Override
          public void completed(Message reply) {
            String replyJson = Codec.encode(reply);
            enqueue(agent, Protocol.CONTROL, () -> {
              events.add(new Event(REPLY, agent, Protocol.CONTROL, replyJson));
              callback.completed(decodeReply(replyJson));
            });
          }

          @Override
          public void failed(IOException e) {
            enqueue(agent, Protocol.CONTROL, () -> callback.failed(e));
          }
        });
      });
    }

    @Override
    public void telemetry(Intervals batch, Callback<Ack> callback) {
      open(Protocol.TELEMETRY);
      String json = Codec.encode(batch);
      enqueue(agent, Protocol.TELEMETRY, () -> {
        events.add(new Event(REQUEST, agent, Protocol.TELEMETRY, json));
        Intervals decoded;
        try {
          decoded = ChannelRequests.telemetry(json);
        } catch (ProtocolException e) {
          reject(Protocol.TELEMETRY, e, callback);
          return;
        }
        String replyJson = Codec.encode(channel.telemetry(decoded));
        enqueue(agent, Protocol.TELEMETRY, () -> {
          events.add(new Event(REPLY, agent, Protocol.TELEMETRY, replyJson));
          Message reply = decodeReply(replyJson);
          if (reply instanceof Rejected) {
            callback.failed(new RejectedException(((Rejected) reply).getReason()));
          } else {
            callback.completed((Ack) reply);
          }
        });
      });
    }

    /**
     * Replicates how the HTTP adapter responds to a bad request by returning a 400 status with a
     * rejected message.
     */
    private void reject(String stream, ProtocolException e, Callback<?> callback) {
      String replyJson = Codec.encode(new Rejected(e.getMessage()));
      enqueue(agent, stream, () -> {
        events.add(new Event(REPLY, agent, stream, replyJson));
        callback.failed(new RejectedException(e.getMessage()));
      });
    }

    @Override
    public void close() {
      open.clear();
    }
  }
}
