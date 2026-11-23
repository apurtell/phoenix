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
 * Represents a message exchanged over the agent channel. Every message carries the catalogue
 * version, its message type, and a trace identifier. The trace of a request is formatted as
 * {@code <agent>/<stream>/<n>}, and the corresponding reply echoes the same trace. A control reply
 * additionally carries rxMs, which records the controller wall-clock time when the request arrived,
 * and txMs, which records the time when the reply was sent. These fields are omitted on all other
 * messages.
 */
public abstract class Message {
  private int v = Protocol.VERSION;
  private String type;
  private String trace;
  private Long rxMs;
  private Long txMs;

  protected Message(MessageType type) {
    this.type = type.wire();
  }

  public int getV() {
    return v;
  }

  public MessageType type() {
    return MessageType.forWire(type);
  }

  public String getTrace() {
    return trace;
  }

  public void setTrace(String trace) {
    this.trace = trace;
  }

  public Long getRxMs() {
    return rxMs;
  }

  public Long getTxMs() {
    return txMs;
  }

  /**
   * Stamps a control reply with the controller timestamps recording when its corresponding request
   * was received and when the reply itself was transmitted.
   */
  public void setStamps(long rxMs, long txMs) {
    this.rxMs = rxMs;
    this.txMs = txMs;
  }
}
