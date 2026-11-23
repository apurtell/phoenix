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

import java.util.ArrayList;
import java.util.List;

/**
 * This telemetry request carries the interval frames for a single assignment in sequence order. A
 * single batch contains at most {@link Protocol#MAX_FRAMES} frames.
 */
public final class Intervals extends AgentMessage {
  private String runId;
  private int step;
  private List<IntervalFrame> frames;

  private Intervals() {
    super(MessageType.INTERVALS);
  }

  public Intervals(String runId, int step, List<IntervalFrame> frames) {
    this();
    this.runId = runId;
    this.step = step;
    this.frames = new ArrayList<>(frames);
  }

  public String getRunId() {
    return runId;
  }

  public int getStep() {
    return step;
  }

  public List<IntervalFrame> getFrames() {
    return frames;
  }
}
