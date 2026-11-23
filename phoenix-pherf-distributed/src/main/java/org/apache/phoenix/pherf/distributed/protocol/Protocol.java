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

import org.slf4j.MDC;

/**
 * Defines the fixed protocol parameters for the agent communication channel and its associated
 * logging context. Because the controller and the agents are always deployed from a single build,
 * these values are defined as compile-time constants rather than configurable settings.
 */
public final class Protocol {
  /**
   * Specifies the version number of the message catalogue. A message reader rejects any payload
   * that declares a different version value.
   */
  public static final int VERSION = 1;

  /**
   * Defines the base context path of the agent listener. Only the two paths declared below are
   * served by this listener.
   */
  public static final String CHANNEL_CONTEXT = "/v1/channel/";
  public static final String CONTROL = "control";
  public static final String TELEMETRY = "telemetry";
  public static final String CONTROL_PATH = CHANNEL_CONTEXT + CONTROL;
  public static final String TELEMETRY_PATH = CHANNEL_CONTEXT + TELEMETRY;

  /** Specifies the default port used by the agent listener. */
  public static final int AGENT_PORT = 8790;

  /**
   * Specifies the maximum duration, in milliseconds, that the controller holds open a poll request
   * when no command is immediately available.
   */
  public static final long HOLD_MS = 1000;
  /**
   * Defines the liveness deadline in milliseconds; the controller evicts an agent whenever the
   * elapsed time now - lastSeen exceeds this threshold.
   */
  public static final long DEADLINE_MS = 10000;
  /** Specifies the execution period, in milliseconds, at which the reaper task runs. */
  public static final long REAP_PERIOD_MS = 1000;
  /**
   * Specifies the delay, in milliseconds, that an agent waits before retransmitting a failed
   * request.
   */
  public static final long RETRY_MS = 1000;
  /**
   * Specifies the connection and read timeout, in milliseconds, for an agent request. This value is
   * greater than the poll hold duration and less than the liveness deadline so that a single lost
   * reply is retransmitted before either side evicts the other.
   */
  public static final int IO_TIMEOUT_MS = 5000;
  /**
   * Defines the maximum duration, in milliseconds, allowed for an agent to complete an orderly
   * stop.
   */
  public static final long STOP_GRACE_MS = 5000;
  /**
   * Defines the lead time, in milliseconds, of a start epoch ahead of the moment the controller
   * establishes it, in addition to the largest round-trip delay among the agents assigned to the
   * step. This margin accommodates start delivery jitter as well as one reset request and its retry
   * after {@link #RETRY_MS}. A lost reply that waits for {@link #IO_TIMEOUT_MS} arrives late, and
   * the resulting delay is reflected in the skew of the step.
   */
  public static final long START_LEAD_MS = 2000;
  /**
   * Defines the upper bound on the start skew of a step; the fleet verdict fails any measurement
   * step whose start skew bound exceeds this threshold.
   */
  public static final long MAX_START_SKEW_MS = 50;
  /**
   * Specifies the number of most recent valid clock samples used to estimate the clock offset of an
   * agent.
   */
  public static final int CLOCK_SAMPLES = 8;
  /** Defines the maximum number of interval frames permitted in a single telemetry batch. */
  public static final int MAX_FRAMES = 256;
  /** Defines the maximum permitted payload size, in bytes, for any request or reply body. */
  public static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

  /**
   * Names the HTTP header used to propagate the trace identifier across a request and its
   * corresponding reply.
   */
  public static final String TRACE_HEADER = "X-Pherf-Trace";
  /** Specifies the logging context key used to record the operational id of an agent. */
  public static final String MDC_KEY = "pherf.agent";

  private Protocol() {
  }

  /**
   * Executes the supplied step with the agent's operational id bound in the logging context, and
   * restores the previous context state upon completion.
   */
  public static void withAgent(String id, Runnable step) {
    String previous = MDC.get(MDC_KEY);
    MDC.put(MDC_KEY, id);
    try {
      step.run();
    } finally {
      if (previous == null) {
        MDC.remove(MDC_KEY);
      } else {
        MDC.put(MDC_KEY, previous);
      }
    }
  }
}
