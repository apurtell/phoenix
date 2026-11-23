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
   * Records the error from a launched slot that failed due to this agent until a reset clears it,
   * or null when the agent is not faulted.
   */
  String fault;
  /**
   * Caches the encoded form of the last command sent so that a repeated command is transmitted at
   * most once per hold interval.
   */
  String lastCommand;
  /**
   * Indicates whether the most recent command was immediately retransmitted following a lost reply.
   */
  boolean resent;
  /**
   * Holds the pending long-poll callback along with its associated trace identifier and hold
   * expiration timer.
   */
  Callback<Message> held;
  String heldTrace;
  /** Records the controller timestamp at which the currently held long-poll request arrived. */
  long heldRxMs;
  Clock.Timer holdTimer;
  /** Maintains the clock offset estimator for this agent. */
  final ClockFilter clockFilter = new ClockFilter();

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

  /**
   * Returns true when the agent is available to accept a new slot; a faulted agent cannot accept a
   * new slot.
   */
  boolean free() {
    return binding == null && !leaving && fault == null && !reported.getPhase().active();
  }

  AgentView view() {
    AgentView.State state;
    if (leaving) {
      state = AgentView.State.LEAVING;
    } else if (binding != null) {
      state = AgentView.State.valueOf(binding.state.name());
    } else if (fault != null) {
      state = AgentView.State.FAULTED;
    } else {
      switch (reported.getPhase()) {
        case PREPARING:
          state = AgentView.State.ASSIGNED;
          break;
        case READY:
          state = AgentView.State.READY;
          break;
        case LAUNCHED:
          state = AgentView.State.LAUNCHED;
          break;
        case ARMED:
          state = AgentView.State.ARMED;
          break;
        case RUNNING:
          state = AgentView.State.RUNNING;
          break;
        default:
          state = AgentView.State.IDLE;
      }
    }
    long[] clock = clockFilter.best();
    return new AgentView(id, incarnation, state, reported.getPhase(), reported.getRunId(),
      reported.getStep(), registeredMs, lastSeenMs, clock == null ? null : clock[0],
      clock == null ? null : clock[1], fault);
  }
}
