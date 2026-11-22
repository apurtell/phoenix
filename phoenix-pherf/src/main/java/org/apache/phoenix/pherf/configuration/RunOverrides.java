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

import java.math.BigInteger;
import java.util.List;
import org.apache.phoenix.pherf.workload.QueryExecutor;

/**
 * Encapsulates the command-line launch overrides -runDurationMs, -threadCount, and -targetRate.
 * These overrides transform every query set in a run after configuration parsing completes,
 * including rate curves imported from an external workload profile. Pherf applies them before any
 * data loading begins, and leaves the configuration untouched when no overrides are supplied.
 * <ul>
 * <li>threadCount N: sets the concurrency of every query set to N, which governs both the closed
 * loop thread count and the open loop worker limit and queue bound. Data writers and the -mt flag
 * remain unchanged.</li>
 * <li>runDurationMs D: scales open loop stages so their durations sum to D while preserving the
 * relative profile shape; each stage is assigned floor(d * D / T), where d is the stage duration
 * and T is the total duration, and the final stage receives the remainder. A closed loop query set
 * has its executionDurationInMs set directly to D.</li>
 * <li>targetRate R: multiplies every rate and endRate in an open loop by R / peak, where peak is
 * the maximum rate across all stages, scaling the peak throughput to R while preserving any 0-rate
 * pause. A closed loop query set has no rate parameter.</li>
 * </ul>
 * Prior to scaling, the open loop validation check in the query executor inspects the stages so
 * that any malformed curve fails immediately with the diagnostic message produced by that check.
 */
public final class RunOverrides {
  private static final long MAX_DURATION_MS = Long.MAX_VALUE / 1000;
  private static final int MAX_THREADS = 999_999_999;

  private final Long runDurationMs;
  private final Integer threadCount;
  private final Double targetRate;

  private RunOverrides(Long runDurationMs, Integer threadCount, Double targetRate) {
    this.runDurationMs = runDurationMs;
    this.threadCount = threadCount;
    this.targetRate = targetRate;
  }

  /**
   * Parses and validates the raw command-line override option values, where a null value indicates
   * that the option was omitted.
   * @param querySets true when the run is configured to execute query sets (-q).
   * @throws IllegalArgumentException if an option value is invalid, with a diagnostic message that
   *                                  identifies the option name and the rejected value.
   */
  public static RunOverrides of(String runDurationMs, String threadCount, String targetRate,
    boolean querySets) {
    Long duration = null;
    if (runDurationMs != null) {
      duration = parseLong(runDurationMs);
      if (duration == null || duration < 1 || duration > MAX_DURATION_MS) {
        throw new IllegalArgumentException("-runDurationMs is not an integer from 1 to "
          + MAX_DURATION_MS + ": '" + runDurationMs + "'");
      }
    }
    Integer threads = null;
    if (threadCount != null) {
      Long value = parseLong(threadCount);
      if (value == null || value < 1 || value > MAX_THREADS) {
        throw new IllegalArgumentException(
          "-threadCount is not an integer from 1 to " + MAX_THREADS + ": '" + threadCount + "'");
      }
      threads = value.intValue();
    }
    Double rate = null;
    if (targetRate != null) {
      try {
        rate = Double.parseDouble(targetRate.trim());
      } catch (NumberFormatException e) {
        rate = null;
      }
      if (rate == null || !Double.isFinite(rate) || rate <= 0) {
        throw new IllegalArgumentException(
          "-targetRate is not a finite number of operations per second above 0: '" + targetRate
            + "'");
      }
    }
    RunOverrides overrides = new RunOverrides(duration, threads, rate);
    if (!overrides.isEmpty() && !querySets) {
      throw new IllegalArgumentException(
        overrides.names() + " needs -q, because the overrides change only query sets");
    }
    return overrides;
  }

  private static Long parseLong(String text) {
    try {
      return Long.parseLong(text.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String names() {
    StringBuilder names = new StringBuilder();
    for (Object[] option : new Object[][] { { "-runDurationMs", runDurationMs },
      { "-threadCount", threadCount }, { "-targetRate", targetRate } }) {
      if (option[1] != null) {
        names.append(names.length() == 0 ? "" : ", ").append(option[0]);
      }
    }
    return names.toString();
  }

  /** Returns true when no launch override options have been specified for the run. */
  public boolean isEmpty() {
    return runDurationMs == null && threadCount == null && targetRate == null;
  }

  /**
   * Applies the configured runtime overrides to every query set across the provided data models.
   * @throws IllegalArgumentException if -targetRate is specified when no query set defines an open
   *                                  loop, if the open loop validation check rejects a query set
   *                                  prior to scaling, or if duration scaling produces a stage
   *                                  shorter than 1 ms.
   */
  public void apply(List<DataModel> dataModels) {
    if (targetRate != null && !hasOpenLoop(dataModels)) {
      throw new IllegalArgumentException("-targetRate changes only open loop query sets, and the"
        + " run has none: '" + targetRate + "'");
    }
    for (DataModel dataModel : dataModels) {
      if (threadCount != null) {
        for (Scenario scenario : dataModel.getScenarios()) {
          for (QuerySet querySet : scenario.getQuerySet()) {
            querySet.setConcurrency(String.valueOf(threadCount));
          }
        }
      }
      if (runDurationMs == null && targetRate == null) {
        continue;
      }
      // Validate open loop stages before scaling so that scaling only operates on well-formed
      // stage definitions.
      QueryExecutor.checkOpenLoop(dataModel);
      for (Scenario scenario : dataModel.getScenarios()) {
        int position = 0;
        for (QuerySet querySet : scenario.getQuerySet()) {
          position++;
          OpenLoop openLoop = querySet.getOpenLoop();
          if (openLoop == null) {
            if (runDurationMs != null) {
              querySet.setExecutionDurationInMs(runDurationMs);
            }
          } else {
            String where = "querySet " + position + " of scenario " + scenario.getName();
            if (runDurationMs != null) {
              scaleDurations(openLoop.getStage(), where);
            }
            if (targetRate != null) {
              scaleRates(openLoop.getStage());
            }
          }
        }
      }
    }
  }

  private static boolean hasOpenLoop(List<DataModel> dataModels) {
    for (DataModel dataModel : dataModels) {
      for (Scenario scenario : dataModel.getScenarios()) {
        for (QuerySet querySet : scenario.getQuerySet()) {
          if (querySet.getOpenLoop() != null) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static double peak(List<OpenLoop.Stage> stages) {
    double peak = 0;
    for (OpenLoop.Stage stage : stages) {
      peak = Math.max(peak, stage.getRate());
      if (stage.getEndRate() != null) {
        peak = Math.max(peak, stage.getEndRate());
      }
    }
    return peak;
  }

  /**
   * Scales stage durations using exact arbitrary-precision integer arithmetic so that large total
   * durations neither overflow nor suffer from floating-point rounding errors.
   */
  private void scaleDurations(List<OpenLoop.Stage> stages, String where) {
    BigInteger total = BigInteger.ZERO;
    for (OpenLoop.Stage stage : stages) {
      total = total.add(BigInteger.valueOf(stage.getDurationMs()));
    }
    BigInteger target = BigInteger.valueOf(runDurationMs);
    long[] scaled = new long[stages.size()];
    long used = 0;
    for (int i = 0; i < scaled.length - 1; i++) {
      scaled[i] = BigInteger.valueOf(stages.get(i).getDurationMs()).multiply(target).divide(total)
        .longValue();
      used += scaled[i];
    }
    scaled[scaled.length - 1] = runDurationMs - used;
    for (int i = 0; i < scaled.length; i++) {
      if (scaled[i] < 1) {
        throw new IllegalArgumentException("-runDurationMs " + runDurationMs + " gives stage "
          + (i + 1) + " of the openLoop of " + where + " less than 1 ms");
      }
    }
    for (int i = 0; i < scaled.length; i++) {
      stages.get(i).setDurationMs(scaled[i]);
    }
  }

  private void scaleRates(List<OpenLoop.Stage> stages) {
    double peak = peak(stages);
    for (OpenLoop.Stage stage : stages) {
      stage.setRate(stage.getRate() / peak * targetRate);
      if (stage.getEndRate() != null) {
        stage.setEndRate(stage.getEndRate() / peak * targetRate);
      }
    }
  }
}
