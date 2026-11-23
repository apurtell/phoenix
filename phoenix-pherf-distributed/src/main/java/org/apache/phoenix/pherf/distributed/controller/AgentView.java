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

import org.apache.phoenix.pherf.distributed.protocol.Phase;

/** Represents an immutable point-in-time snapshot of a single agent record. */
public final class AgentView {
  /** Enumerates the operational states of an agent within the distributed fleet. */
  public enum State {
    IDLE,
    ASSIGNED,
    READY,
    LAUNCHED,
    ARMED,
    RUNNING,
    DONE,
    LEAVING,
    /**
     * Indicates that the agent failed its launched slot due to its own error; the agent holds no
     * slot and cannot take a new slot until a reset occurs.
     */
    FAULTED
  }

  private final String id;
  private final String incarnation;
  private final State state;
  private final Phase phase;
  private final String runId;
  private final int step;
  private final long registeredMs;
  private final long lastSeenMs;
  private final Long offsetMs;
  private final Long delayMs;
  private final String fault;

  AgentView(String id, String incarnation, State state, Phase phase, String runId, int step,
    long registeredMs, long lastSeenMs, Long offsetMs, Long delayMs, String fault) {
    this.id = id;
    this.incarnation = incarnation;
    this.state = state;
    this.phase = phase;
    this.runId = runId;
    this.step = step;
    this.registeredMs = registeredMs;
    this.lastSeenMs = lastSeenMs;
    this.offsetMs = offsetMs;
    this.delayMs = delayMs;
    this.fault = fault;
  }

  public String getId() {
    return id;
  }

  public String getIncarnation() {
    return incarnation;
  }

  public State getState() {
    return state;
  }

  /** Returns the execution phase that the agent most recently reported. */
  public Phase getPhase() {
    return phase;
  }

  public String getRunId() {
    return runId;
  }

  public int getStep() {
    return step;
  }

  public long getRegisteredMs() {
    return registeredMs;
  }

  public long getLastSeenMs() {
    return lastSeenMs;
  }

  /**
   * Returns the estimated clock offset in milliseconds, defined as the agent wall clock minus the
   * controller wall clock, or null if no estimate is available.
   */
  public Long getOffsetMs() {
    return offsetMs;
  }

  /**
   * Returns the round-trip delay of the clock offset estimate in milliseconds; the offset
   * estimation error is at most half of this delay.
   */
  public Long getDelayMs() {
    return delayMs;
  }

  /**
   * Returns the error message that placed the agent in a faulted state, or null when the agent is
   * not faulted; a reset clears this error.
   */
  public String getFault() {
    return fault;
  }

  @Override
  public String toString() {
    return id + " " + state + " " + phase + " " + runId + "/" + step;
  }
}
