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
    RUNNING,
    DONE,
    LEAVING
  }

  private final String id;
  private final String incarnation;
  private final State state;
  private final Phase phase;
  private final String runId;
  private final int step;
  private final long registeredMs;
  private final long lastSeenMs;

  AgentView(String id, String incarnation, State state, Phase phase, String runId, int step,
    long registeredMs, long lastSeenMs) {
    this.id = id;
    this.incarnation = incarnation;
    this.state = state;
    this.phase = phase;
    this.runId = runId;
    this.step = step;
    this.registeredMs = registeredMs;
    this.lastSeenMs = lastSeenMs;
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

  @Override
  public String toString() {
    return id + " " + state + " " + phase + " " + runId + "/" + step;
  }
}
