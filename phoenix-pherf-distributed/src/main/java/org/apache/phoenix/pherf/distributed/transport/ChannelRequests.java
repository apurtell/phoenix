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

import org.apache.phoenix.pherf.distributed.identity.OperationalIdentity;
import org.apache.phoenix.pherf.distributed.protocol.AgentMessage;
import org.apache.phoenix.pherf.distributed.protocol.Codec;
import org.apache.phoenix.pherf.distributed.protocol.Heartbeat;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.protocol.ProtocolException;

/**
 * Decodes and validates the body of an incoming channel request before passing it to the
 * controller. Every validation failure throws a ProtocolException, which the listener answers with
 * an HTTP 400 status and a rejected message.
 */
public final class ChannelRequests {
  private ChannelRequests() {
  }

  /**
   * Decodes and validates a control-plane message representing a register, heartbeat, or goodbye
   * request.
   */
  public static AgentMessage control(String json) throws ProtocolException {
    Message message = Codec.decode(json);
    switch (message.type()) {
      case REGISTER:
      case GOODBYE:
        return checked((AgentMessage) message);
      case HEARTBEAT:
        Heartbeat heartbeat = (Heartbeat) message;
        if (heartbeat.getStatus() == null || heartbeat.getStatus().getPhase() == null) {
          throw new ProtocolException("heartbeat has no status phase");
        }
        if (heartbeat.getSeq() < 1) {
          throw new ProtocolException("heartbeat has no seq");
        }
        return checked(heartbeat);
      default:
        throw new ProtocolException("not a control request: " + message.type().wire());
    }
  }

  /** Decodes and validates a telemetry message representing an intervals request. */
  public static Intervals telemetry(String json) throws ProtocolException {
    Message message = Codec.decode(json);
    if (!(message instanceof Intervals)) {
      throw new ProtocolException("not a telemetry request: " + message.type().wire());
    }
    Intervals batch = checked((Intervals) message);
    if (batch.getRunId() == null || batch.getFrames() == null) {
      throw new ProtocolException("intervals has no runId or no frames");
    }
    if (batch.getFrames().size() > Protocol.MAX_FRAMES) {
      throw new ProtocolException("intervals has " + batch.getFrames().size()
        + " frames; the maximum is " + Protocol.MAX_FRAMES);
    }
    return batch;
  }

  private static <T extends AgentMessage> T checked(T message) throws ProtocolException {
    try {
      OperationalIdentity.check(message.getAgent());
    } catch (IllegalArgumentException e) {
      throw new ProtocolException(e.getMessage(), e);
    }
    if (message.getIncarnation() == null || message.getIncarnation().isEmpty()) {
      throw new ProtocolException("agent " + message.getAgent() + " sent no incarnation");
    }
    return message;
  }
}
