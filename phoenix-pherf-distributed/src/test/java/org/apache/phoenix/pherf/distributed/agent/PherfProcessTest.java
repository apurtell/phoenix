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
package org.apache.phoenix.pherf.distributed.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.distributed.Plans;
import org.apache.phoenix.pherf.distributed.clock.SystemClock;
import org.apache.phoenix.pherf.distributed.identity.DataPlaneIdentity;
import org.apache.phoenix.pherf.distributed.protocol.Assignment;
import org.apache.phoenix.pherf.distributed.protocol.FinalReport;
import org.apache.phoenix.pherf.distributed.protocol.Share;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.apache.phoenix.pherf.verdict.HealthVerdict;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a real Pherf run inside a child JVM using the real clock, driven through the agent
 * workload against a fake JDBC provider. Like AgentProcessTest, this test serves as a documented
 * exception to the rule against spawning processes in unit tests. Synchronization uses bounded
 * process waits rather than sleeps.
 */
public class PherfProcessTest {
  private static final Logger LOG = LoggerFactory.getLogger(PherfProcessTest.class);
  private static final long TIMEOUT_S = 120;

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  /**
   * Captures workload listener callbacks and aggregates reported interval sample counts by
   * operation and phase key.
   */
  private static final class Recorder implements Workload.Listener {
    final Map<String, Long> counts = new TreeMap<>();
    final List<String> ends = new ArrayList<>();
    final CountDownLatch prepared = new CountDownLatch(1);
    final CountDownLatch armed = new CountDownLatch(1);
    final CountDownLatch ended = new CountDownLatch(1);
    volatile List<String> errors;
    volatile long startedMs;
    volatile FinalReport report;

    @Override
    public void prepared(List<String> errors) {
      this.errors = errors;
      prepared.countDown();
    }

    @Override
    public void armed() {
      armed.countDown();
    }

    @Override
    public void started(long startedMs) {
      this.startedMs = startedMs;
    }

    @Override
    public synchronized void interval(String operation, String phase, long startMs, long endMs,
      Histogram histogram) {
      counts.merge(operation + "|" + phase, histogram.getTotalCount(), Long::sum);
    }

    @Override
    public synchronized void finished(FinalReport report) {
      this.report = report;
      ends.add("finished");
      ended.countDown();
    }

    @Override
    public synchronized void failed(String reason) {
      ends.add("failed " + reason);
      ended.countDown();
    }
  }

  private static Assignment assignment(Share share) throws Exception {
    Map<String, String> documents = new LinkedHashMap<>();
    documents.put("relational_scenario.xml", Plans.reference("relational_scenario.xml"));
    documents.put("relational_smoke_profile.xml", Plans.reference("relational_smoke_profile.xml"));
    return new Assignment("20260101T000000000Z-1", 0, DataPlaneIdentity.derive(1, 0, 0),
      "relational_scenario.xml", documents, Arrays.asList("-q", "-disableSchemaApply"), share,
      Collections.<String, String> emptyMap());
  }

  @Test
  public void realPherfIntervalsMatchItsReport() throws Exception {
    File work = folder.newFolder("work");
    try (SystemClock clock = new SystemClock("pherf-process-test")) {
      PherfWorkload workload = new PherfWorkload(work, "process-zk", Children.shipped(),
        Children.launcher("process", folder.newFolder("overlay")), clock);
      Assignment assignment = assignment(new Share(500L, null, 100L, null));
      Recorder recorder = new Recorder();
      workload.prepare(assignment, recorder);
      assertTrue(recorder.prepared.await(TIMEOUT_S, TimeUnit.SECONDS));
      assertEquals(Collections.emptyList(), recorder.errors);
      // Launch the run child process so that it warms up and arms at the start gate, which then
      // opens at the scheduled start epoch.
      workload.launch(assignment, recorder);
      assertTrue(recorder.armed.await(TIMEOUT_S, TimeUnit.SECONDS));
      long epochMs = clock.millis() + 1500;
      workload.start(assignment.getRunId(), epochMs);
      assertTrue(recorder.ended.await(TIMEOUT_S, TimeUnit.SECONDS));
      assertEquals(Collections.singletonList("finished"), recorder.ends);
      assertTrue(recorder.startedMs + " < " + epochMs, recorder.startedMs >= epochMs);

      // Verify that the total count accumulated across forwarded intervals for each key matches
      // the corresponding aggregate count recorded in Pherf's own latency report.
      JsonObject report = JsonParser.parseString(
        new String(Files.readAllBytes(new File(work, "RESULTS_HDR/LATENCY_run.json").toPath()),
          StandardCharsets.UTF_8))
        .getAsJsonObject();
      Map<String, Long> expected = new TreeMap<>();
      for (JsonElement entry : report.getAsJsonArray("histograms")) {
        JsonObject histogram = entry.getAsJsonObject();
        expected.put(
          histogram.get("operation").getAsString() + "|" + histogram.get("phase").getAsString(),
          histogram.get("count").getAsLong());
      }
      assertTrue(expected.toString(), expected.size() >= 3);
      synchronized (recorder) {
        assertEquals(expected, recorder.counts);
      }
      // Verify that the final report emitted by the child process contains the same operation
      // counts keyed by log tag and records the same measurement time window.
      FinalReport finalReport = recorder.report;
      Map<String, Long> reported = new TreeMap<>();
      for (Map.Entry<String, Long> key : finalReport.getCounts().entrySet()) {
        String[] parsed = LatencyReport.parseTag(key.getKey());
        reported.put(parsed[0] + "|" + parsed[1], key.getValue());
      }
      assertEquals(expected, reported);
      assertEquals(report.get("startMs").getAsLong(), finalReport.getStartMs());
      assertEquals(report.get("endMs").getAsLong(), finalReport.getEndMs());
      assertEquals(1, finalReport.getSchedules().size());
      assertEquals(report.getAsJsonArray("schedules").get(0), finalReport.getSchedules().get(0));
      // Verify that the final report includes all five rows of the Pherf health verdict produced
      // by the child process.
      assertEquals(HealthVerdict.CHECKS.size(), finalReport.getVerdict().size());
      for (int i = 0; i < HealthVerdict.CHECKS.size(); i++) {
        assertTrue(finalReport.getVerdict().get(i),
          finalReport.getVerdict().get(i).startsWith(HealthVerdict.CHECKS.get(i) + "\t"));
      }
      // Running with -runDurationMs 500 and -targetRate 100 produces 50 scheduled arrivals,
      // each of which runs to completion.
      JsonArray schedules = report.getAsJsonArray("schedules");
      assertEquals(1, schedules.size());
      // The schedule must start at or after the epoch; because the upper bound depends on the
      // execution host, the test logs the observed offset.
      long scheduleStartMs = schedules.get(0).getAsJsonObject().get("startMs").getAsLong();
      assertTrue(scheduleStartMs + " < " + epochMs, scheduleStartMs >= epochMs);
      LOG.info("start gate opened {} ms and the schedule started {} ms after the epoch",
        recorder.startedMs - epochMs, scheduleStartMs - epochMs);
      assertEquals(50, schedules.get(0).getAsJsonObject().get("scheduled").getAsLong());
      assertEquals(50, schedules.get(0).getAsJsonObject().get("completed").getAsLong());
      List<String> jdbc =
        Files.readAllLines(new File(work, RecordingConnectionProvider.LOG).toPath());
      assertTrue(jdbc.toString(), jdbc.size() > 50);

      // Within the same harness, configuring a slice of 1 row for 2 closed-loop readers causes
      // Pherf's own validation check to fail with exit code 65.
      Recorder invalid = new Recorder();
      Map<String, String> documents = Collections.singletonMap("closed_loop_scenario.xml",
        Plans.resource("/fleet/closed_loop_scenario.xml").replace("${pherf.slot.offset}", "0"));
      workload.prepare(new Assignment("20260101T000000000Z-2", 0, DataPlaneIdentity.derive(1, 0, 0),
        "closed_loop_scenario.xml", documents, Arrays.asList("-q", "-disableSchemaApply"),
        new Share(null, null, null, 1L), Collections.<String, String> emptyMap()), invalid);
      assertTrue(invalid.prepared.await(TIMEOUT_S, TimeUnit.SECONDS));
      assertTrue(invalid.errors.toString(), !invalid.errors.isEmpty());
      assertTrue(invalid.errors.get(0), invalid.errors.get(0).startsWith("cogen.walk "));
      assertTrue(new File(work, "RESULTS/VALIDATION_validate.json").isFile());
      workload.close();
    }
  }
}
