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
 * Captures the four wall-clock timestamps of a single control exchange in milliseconds. The sentMs
 * and receivedMs fields are recorded on the agent's wall clock when the request departs and when
 * the reply arrives, while rxMs and txMs are recorded on the controller's wall clock when the
 * request arrives and when the reply departs.
 */
public final class ClockSample {
  private long sentMs;
  private long rxMs;
  private long txMs;
  private long receivedMs;

  private ClockSample() {
  }

  public ClockSample(long sentMs, long rxMs, long txMs, long receivedMs) {
    this.sentMs = sentMs;
    this.rxMs = rxMs;
    this.txMs = txMs;
    this.receivedMs = receivedMs;
  }

  public long getSentMs() {
    return sentMs;
  }

  public long getRxMs() {
    return rxMs;
  }

  public long getTxMs() {
    return txMs;
  }

  public long getReceivedMs() {
    return receivedMs;
  }
}
