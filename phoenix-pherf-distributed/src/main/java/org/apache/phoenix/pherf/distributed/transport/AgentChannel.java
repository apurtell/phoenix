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

import org.apache.phoenix.pherf.distributed.protocol.AgentMessage;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Message;

/**
 * Defines the controller side of the communication channel, which passively responds to incoming
 * requests and never dials an agent directly. Both methods accept only requests that have already
 * been validated by {@link ChannelRequests}.
 */
public interface AgentChannel {
  /**
   * Responds to a control request exactly once, resolving either immediately, when a new command
   * becomes available, or when the hold period ends. The reply callback may be invoked on a
   * different thread.
   */
  void control(AgentMessage request, Callback<Message> reply);

  /**
   * Stages an incoming telemetry batch and responds immediately with either an acknowledgment (ack)
   * or a rejected status when the agent must stop, such as when the reported recorder configuration
   * does not match the pinned configuration.
   */
  Message telemetry(Intervals batch);
}
