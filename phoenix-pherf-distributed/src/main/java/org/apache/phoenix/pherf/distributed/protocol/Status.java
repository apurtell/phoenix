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
 * This class represents the complete operational state of an agent. The runId and step fields
 * identify the current assignment, while finalSeq records the last frame sequence for a DONE
 * assignment and error captures the failure reason for a FAILED assignment.
 */
public final class Status {
  /** This constant represents the status of an agent that currently has no assignment. */
  public static final Status IDLE = new Status(Phase.IDLE, null, 0, 0, null);

  private Phase phase;
  private String runId;
  private int step;
  private long finalSeq;
  private String error;

  private Status() {
  }

  public Status(Phase phase, String runId, int step, long finalSeq, String error) {
    this.phase = phase;
    this.runId = runId;
    this.step = step;
    this.finalSeq = finalSeq;
    this.error = error;
  }

  public Phase getPhase() {
    return phase;
  }

  public String getRunId() {
    return runId;
  }

  public int getStep() {
    return step;
  }

  public long getFinalSeq() {
    return finalSeq;
  }

  public String getError() {
    return error;
  }
}
