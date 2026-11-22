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
package org.apache.phoenix.pherf.configuration;

import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.annotation.XmlAttribute;

/**
 * Encapsulates the open-loop arrival schedule configured for a query set. When a query set includes
 * this element, its queries are dispatched at the target arrival rates defined by the configured
 * stages rather than running in a closed loop. The concurrency of the enclosing query set defines
 * both the maximum number of worker threads and the capacity bound of the dispatch queue. Each
 * scheduled arrival executes the next query in the set, cycling through them in declared order.
 * <p>
 * The sequence of stages defines an arrival rate curve expressed in operations per second. A stage
 * that omits endRate maintains a constant flat rate, whereas a stage that specifies endRate
 * interpolates the arrival rate linearly from rate to endRate across its duration. A stage with a
 * rate of 0 represents a pause in arrivals.
 * <p>
 * Any attribute omitted from the XML configuration remains null on this object. The effective
 * defaults are an arrival mode of UNIFORM, no maximum lag limit, and a lag policy of LATE.
 */
public class OpenLoop {
  /** Defines how operation arrival timestamps are distributed along the configured rate curve. */
  public enum Arrival {
    /** Places arrivals at uniform unit steps of the cumulative expected arrival count. */
    UNIFORM,
    /** Generates arrivals using a seeded non-homogeneous Poisson process over the rate curve. */
    POISSON
  }

  /** Specifies how a worker handles an operation whose lag exceeds maxLagMs when dequeued. */
  public enum LagPolicy {
    /** Executes the overdue operation and records it in the late operation count. */
    LATE,
    /** Skips execution of the overdue operation and records it in the dropped operation count. */
    DROP
  }

  private Arrival arrival;
  private Long seed;
  private Long maxLagMs;
  private LagPolicy lagPolicy;
  private List<Stage> stage = new ArrayList<>();

  @XmlAttribute
  public Arrival getArrival() {
    return arrival;
  }

  public void setArrival(Arrival arrival) {
    this.arrival = arrival;
  }

  /** Returns the random seed used to generate Poisson arrivals, which POISSON mode requires. */
  @XmlAttribute
  public Long getSeed() {
    return seed;
  }

  public void setSeed(Long seed) {
    this.seed = seed;
  }

  /**
   * Returns the maximum tolerated scheduling lag, measured from the intended dispatch timestamp
   * until a worker dequeues the operation for execution.
   */
  @XmlAttribute
  public Long getMaxLagMs() {
    return maxLagMs;
  }

  public void setMaxLagMs(Long maxLagMs) {
    this.maxLagMs = maxLagMs;
  }

  /** Returns the policy applied to overdue operations; configuring DROP requires maxLagMs. */
  @XmlAttribute
  public LagPolicy getLagPolicy() {
    return lagPolicy;
  }

  public void setLagPolicy(LagPolicy lagPolicy) {
    this.lagPolicy = lagPolicy;
  }

  /** Returns the sequence of schedule stages in chronological order. */
  public List<Stage> getStage() {
    return stage;
  }

  public void setStage(List<Stage> stage) {
    this.stage = stage;
  }

  /** Represents a single contiguous time segment of the open-loop arrival rate curve. */
  public static class Stage {
    private long durationMs;
    private Double rate;
    private Double endRate;

    /** Returns the duration of this stage in ms, which must be a positive value. */
    @XmlAttribute
    public long getDurationMs() {
      return durationMs;
    }

    public void setDurationMs(long durationMs) {
      this.durationMs = durationMs;
    }

    /**
     * Returns the required target arrival rate at the start of this stage, in operations per
     * second.
     */
    @XmlAttribute
    public Double getRate() {
      return rate;
    }

    public void setRate(Double rate) {
      this.rate = rate;
    }

    /**
     * Returns the target arrival rate at the end of this stage, or null to maintain a flat rate.
     */
    @XmlAttribute
    public Double getEndRate() {
      return endRate;
    }

    public void setEndRate(Double endRate) {
      this.endRate = endRate;
    }
  }
}
