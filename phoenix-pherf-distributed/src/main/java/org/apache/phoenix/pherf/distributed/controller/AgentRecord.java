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
package org.apache.phoenix.pherf.distributed.controller;

import org.apache.phoenix.pherf.distributed.clock.Clock;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.Status;
import org.apache.phoenix.pherf.distributed.transport.Callback;

/** Maintains the controller-side state for a single agent, keyed by its operational id. */
final class AgentRecord {
  final String id;
  final String incarnation;
  final long registeredMs;
  /**
   * Stores the epoch time of the most recent request received from the agent, used to populate the
   * agent view.
   */
  long lastSeenMs;
  /**
   * Stores the monotonic time of the most recent request received from the agent, used to evaluate
   * the liveness deadline.
   */
  long lastSeenMonoMs;
  /** Tracks the sequence number (seq) of the last heartbeat that the controller applied. */
  long heartbeatSeq;
  Status reported = Status.IDLE;
  /**
   * References the execution slot that the agent currently works on, or null when no slot is
   * assigned.
   */
  RunRecord.SlotRecord binding;
  /** Set to true when the desired operational state for this agent is shutdown. */
  boolean leaving;
  /**
   * Caches the encoded form of the last command sent to the agent so that a repeated command is
   * sent at most once per poll hold interval.
   */
  String lastCommand;
  /**
   * Holds the pending long-poll callback along with its associated trace identifier and hold
   * expiration timer.
   */
  Callback<Message> held;
  String heldTrace;
  Clock.Timer holdTimer;

  AgentRecord(String id, String incarnation, Clock clock) {
    this.id = id;
    this.incarnation = incarnation;
    this.registeredMs = clock.millis();
    seen(clock);
  }

  /**
   * Updates the last-seen timestamps using the current time to record an incoming request from the
   * agent now.
   */
  void seen(Clock clock) {
    lastSeenMs = clock.millis();
    lastSeenMonoMs = clock.monotonicMs();
  }

  /** Returns true when the agent is available to take a new slot assignment. */
  boolean free() {
    return binding == null && !leaving && !reported.getPhase().active();
  }

  AgentView view() {
    AgentView.State state;
    if (leaving) {
      state = AgentView.State.LEAVING;
    } else if (binding != null) {
      state = AgentView.State.valueOf(binding.state.name());
    } else {
      switch (reported.getPhase()) {
        case PREPARING:
          state = AgentView.State.ASSIGNED;
          break;
        case READY:
          state = AgentView.State.READY;
          break;
        case RUNNING:
          state = AgentView.State.RUNNING;
          break;
        default:
          state = AgentView.State.IDLE;
      }
    }
    return new AgentView(id, incarnation, state, reported.getPhase(), reported.getRunId(),
      reported.getStep(), registeredMs, lastSeenMs);
  }
}
