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
package org.apache.phoenix.pherf.distributed.protocol;

/**
 * Represents the long-poll control request sent by an agent. Because each message reports the full
 * status of the agent, the same request can be retransmitted with no other effect. The seq value
 * increases with each control request of an incarnation, and the controller ignores any heartbeat
 * that is not newer than the last one received so that a late copy has no effect.
 */
public final class Heartbeat extends AgentMessage {
  private long seq;
  private Status status;

  private Heartbeat() {
    super(MessageType.HEARTBEAT);
  }

  public Heartbeat(long seq, Status status) {
    this();
    this.seq = seq;
    this.status = status;
  }

  public long getSeq() {
    return seq;
  }

  public Status getStatus() {
    return status;
  }
}
