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

import org.apache.phoenix.pherf.distributed.protocol.Ack;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Message;

/**
 * Represents the agent endpoint of the communication channel, which issues only outbound requests
 * to the controller across two distinct streams: control and telemetry. Each stream permits at most
 * one request in flight at a time, and each invocation returns before its callback is executed.
 */
public interface AgentTransport {
  /**
   * Transmits a control request to the controller. If the reply indicates a different controller
   * build or a bad request, the operation fails with a RejectedException.
   */
  void control(Message request, Callback<Message> callback);

  /**
   * Transmits a batch of telemetry intervals to the controller. A rejected reply, or any response
   * other than an acknowledgment (ack), fails with a RejectedException as described above.
   */
  void telemetry(Intervals batch, Callback<Ack> callback);

  void close();
}
