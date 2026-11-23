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
 * An interval frame encapsulates a single interval histogram captured for a specific operation and
 * phase. The sequence number seq begins at 1 for each assignment. The recorder header carries the
 * actual parameters of the histogram, while the histogram field holds its {@link HistogramBlob}.
 */
public final class IntervalFrame {
  private long seq;
  private String operation;
  private String phase;
  private long startMs;
  private long endMs;
  private RecorderConfig recorder;
  private String histogram;

  private IntervalFrame() {
  }

  public IntervalFrame(long seq, String operation, String phase, long startMs, long endMs,
    RecorderConfig recorder, String histogram) {
    this.seq = seq;
    this.operation = operation;
    this.phase = phase;
    this.startMs = startMs;
    this.endMs = endMs;
    this.recorder = recorder;
    this.histogram = histogram;
  }

  public long getSeq() {
    return seq;
  }

  public String getOperation() {
    return operation;
  }

  public String getPhase() {
    return phase;
  }

  public long getStartMs() {
    return startMs;
  }

  public long getEndMs() {
    return endMs;
  }

  public RecorderConfig getRecorder() {
    return recorder;
  }

  public String getHistogram() {
    return histogram;
  }
}
