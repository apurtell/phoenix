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
package org.apache.phoenix.pherf.distributed.transport.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.apache.phoenix.pherf.distributed.Simulation;
import org.apache.phoenix.pherf.distributed.clock.VirtualClock;
import org.apache.phoenix.pherf.distributed.controller.Controller;
import org.apache.phoenix.pherf.distributed.protocol.Ack;
import org.apache.phoenix.pherf.distributed.protocol.AgentMessage;
import org.apache.phoenix.pherf.distributed.protocol.Codec;
import org.apache.phoenix.pherf.distributed.protocol.Heartbeat;
import org.apache.phoenix.pherf.distributed.protocol.IntervalFrame;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.MessageType;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.protocol.Register;
import org.apache.phoenix.pherf.distributed.protocol.Rejected;
import org.apache.phoenix.pherf.distributed.protocol.Status;
import org.junit.Test;

/**
 * Verifies the HTTP status codes and trace header propagation for each type of agent channel
 * request.
 */
public class ChannelHandlerTest {
  private final Controller controller =
    new Controller(new VirtualClock(Simulation.START_MS), Simulation.runDir());
  private final ChannelHandler handler = new ChannelHandler(controller);

  private static String json(AgentMessage message, String agent, String trace) {
    message.setAgent(agent);
    message.setIncarnation("i-1");
    message.setTrace(trace);
    return Codec.encode(message);
  }

  private FakeExchange exchange(String method, String path, String body) throws Exception {
    FakeExchange exchange = new FakeExchange(method, path, body);
    handler.handle(exchange);
    assertTrue(exchange.closed());
    return exchange;
  }

  private static Message reply(FakeExchange exchange) throws Exception {
    assertEquals("application/json; charset=utf-8",
      exchange.getResponseHeaders().getFirst("Content-Type"));
    return Codec.decode(exchange.responseText());
  }

  private static void assertRejected(FakeExchange exchange, String reason) throws Exception {
    assertEquals(400, exchange.getResponseCode());
    Message message = reply(exchange);
    assertEquals(MessageType.REJECTED, message.type());
    assertTrue(((Rejected) message).getReason(), ((Rejected) message).getReason().contains(reason));
  }

  @Test
  public void controlRequestGetsItsReplyAndTrace() throws Exception {
    FakeExchange exchange =
      exchange("POST", Protocol.CONTROL_PATH, json(new Register(), "a1", "a1/control/1"));
    assertEquals(200, exchange.getResponseCode());
    assertEquals("a1/control/1", exchange.getResponseHeaders().getFirst(Protocol.TRACE_HEADER));
    Message reply = reply(exchange);
    assertEquals(MessageType.REGISTERED, reply.type());
    assertEquals("a1/control/1", reply.getTrace());
    assertEquals(exchange.responseText().getBytes("UTF-8").length, exchange.responseLength());
    assertEquals(1, controller.agents().size());
    // An unrecognized agent that sends a request to the controller is instructed to register.
    exchange = exchange("POST", Protocol.CONTROL_PATH,
      json(new Heartbeat(1, Status.IDLE, null), "b2", "b2/control/1"));
    assertEquals(200, exchange.getResponseCode());
    assertEquals(MessageType.REREGISTER, reply(exchange).type());
  }

  @Test
  public void telemetryRequestGetsAnAck() throws Exception {
    FakeExchange exchange = exchange("POST", Protocol.TELEMETRY_PATH, json(
      new Intervals("r1", 0, Collections.<IntervalFrame> emptyList()), "a1", "a1/telemetry/1"));
    assertEquals(200, exchange.getResponseCode());
    assertEquals("a1/telemetry/1", exchange.getResponseHeaders().getFirst(Protocol.TRACE_HEADER));
    Ack ack = (Ack) reply(exchange);
    assertEquals("r1", ack.getRunId());
    assertEquals(0, ack.getHeldSeq());
  }

  @Test
  public void badRequestsAreRejected() throws Exception {
    assertRejected(exchange("POST", Protocol.CONTROL_PATH, "{not json"), "bad JSON");
    assertRejected(exchange("POST", Protocol.CONTROL_PATH, "{\"v\":2,\"type\":\"register\"}"),
      "unsupported protocol version 2");
    assertRejected(exchange("POST", Protocol.CONTROL_PATH, "{\"v\":1,\"type\":\"teleport\"}"),
      "unknown message type");
    assertRejected(
      exchange("POST", Protocol.CONTROL_PATH, json(new Register(), "bad id!", "x/control/1")),
      "operational id");
    assertRejected(
      exchange("POST", Protocol.TELEMETRY_PATH, json(new Register(), "a1", "a1/telemetry/1")),
      "not a telemetry request");
    assertRejected(
      exchange("POST", Protocol.CONTROL_PATH, json(
        new Intervals("r1", 0, Collections.<IntervalFrame> emptyList()), "a1", "a1/control/1")),
      "not a control request");
    assertRejected(exchange("POST", Protocol.CONTROL_PATH,
      json(new Heartbeat(0, Status.IDLE, null), "a1", "a1/control/1")), "heartbeat has no seq");
    assertTrue(controller.agents().isEmpty());
  }

  @Test
  public void otherMethodsPathsAndSizesAreRefused() throws Exception {
    FakeExchange get = exchange("GET", Protocol.CONTROL_PATH, "");
    assertEquals(405, get.getResponseCode());
    assertEquals("POST", get.getResponseHeaders().getFirst("Allow"));
    assertEquals(404, exchange("POST", "/v1/channel/other", "{}").getResponseCode());
    byte[] large = new byte[Protocol.MAX_BODY_BYTES + 1];
    FakeExchange big = new FakeExchange("POST", Protocol.CONTROL_PATH, large);
    handler.handle(big);
    assertEquals(413, big.getResponseCode());
    assertNull(big.getResponseHeaders().getFirst(Protocol.TRACE_HEADER));
  }
}
