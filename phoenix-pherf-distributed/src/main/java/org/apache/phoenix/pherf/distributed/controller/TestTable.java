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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.phoenix.pherf.distributed.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the controller's tests as a layer above the run engine. Each test encapsulates a single
 * plan, and each run of the test represents one execution of that plan. A test allows at most one
 * RUNNING run at any time, ensuring that two runs of the same plan never write to the same keys
 * concurrently.
 * <p>
 * Each test persists its state under tests/&lt;id&gt;/ within the run directory: plan.json stores
 * the submitted plan as posted and is written once, while test.json stores the {@link TestView}.
 * The test.json file serves as the commit point for the test: it is written after plan.json before
 * test creation returns, and is updated after each run submission and when the test ends. Because a
 * run is reachable only through a test that lists it in test.json, a submission whose test.json
 * write fails cancels the newly submitted run. A new table instance restores each test from these
 * files.
 * <p>
 * Lock acquisition always proceeds from this table to the controller, and the controller never
 * invokes this table.
 */
public final class TestTable {
  /**
   * Matches a test identifier, which combines the UTC creation timestamp and the sequential test
   * number.
   */
  public static final Pattern ID = Pattern.compile("t-[0-9]{8}T[0-9]{9}Z-([0-9]+)");
  static final String TESTS = "tests";
  static final String PLAN = "plan.json";
  static final String TEST = "test.json";
  /** Defines the cancellation reason recorded on a run that is cancelled when its test ends. */
  static final String TEST_ENDED = "cancelled: the test ended";
  private static final Logger LOG = LoggerFactory.getLogger(TestTable.class);
  private static final Gson GSON =
    new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  private final Controller controller;
  private final Clock clock;
  private final File dir;
  private final Map<String, Test> tests = new LinkedHashMap<>();
  private long count;

  /**
   * Initializes the table and restores any existing tests from the controller's run directory; a
   * fresh directory contains none.
   */
  public TestTable(Controller controller, Clock clock) {
    this.controller = controller;
    this.clock = clock;
    this.dir = new File(controller.runDirectory(), TESTS);
    restore();
  }

  /**
   * Creates and persists a new test for the given execution plan.
   * @throws IllegalArgumentException when {@link RunSpec#parse} rejects the plan
   * @throws IOException              when plan.json or test.json cannot be written to disk
   */
  public synchronized TestView create(String plan) throws IOException {
    RunSpec spec = RunSpec.parse(plan);
    long nowMs = clock.millis();
    String id = "t-" + Controller.STAMP.format(Instant.ofEpochMilli(nowMs)) + "-" + ++count;
    File testDir = new File(dir, id);
    RunBundle.createDirectories(dir.toPath());
    Files.createDirectory(testDir.toPath());
    RunBundle.forceDirectory(dir.toPath());
    RunBundle.writeAtomically(new File(testDir, PLAN), plan);
    Test test = new Test(id, nowMs, spec);
    write(test.view());
    tests.put(id, test);
    LOG.info("test {} created with {} steps", id, spec.getSteps().size());
    return test.view();
  }

  /** Returns the current view of the test, or null if the identifier is unknown to this table. */
  public synchronized TestView test(String id) {
    Test test = tests.get(id);
    return test == null ? null : test.view();
  }

  /**
   * Submits and starts a new run executing the plan associated with the specified test.
   * @return the view of the newly created run, or null if the test is unknown to this table
   * @throws IllegalStateException when the test has already ended, already has a RUNNING run, or
   *                               when step 0 has too few free agents available
   * @throws IOException           when test.json cannot be updated to record the new run, in which
   *                               case the run is transitioned to CANCELLED and omitted from the
   *                               test
   */
  public synchronized RunView submit(String id) throws IOException {
    Test test = tests.get(id);
    if (test == null) {
      return null;
    }
    if (test.state == TestView.State.ENDED) {
      throw new IllegalStateException("test " + id + " has ended");
    }
    String active = active(test);
    if (active != null) {
      throw new IllegalStateException("test " + id + " has an active run " + active);
    }
    String runId = controller.submit(test.spec);
    test.runs.add(runId);
    try {
      write(test.view());
    } catch (IOException e) {
      // Keep in-memory state consistent with on-disk state so the run does not remain active
      // without being recorded under its test.
      test.runs.remove(runId);
      try {
        controller.cancel(runId, "cancelled: test.json cannot list the run");
      } catch (IllegalStateException ended) {
        // The run already terminated on its own after submission, leaving nothing to cancel.
      }
      throw new IOException("run " + runId + " is cancelled: " + e.getMessage(), e);
    }
    return controller.run(runId);
  }

  /**
   * Transitions the test to the ended state so that it accepts no further runs, and cancels any
   * currently RUNNING run. A test that has already ended remains unchanged.
   * @return the updated view of the test, or null if the test is unknown to this table
   * @throws IOException when test.json cannot be written, leaving the test state unchanged
   */
  public synchronized TestView end(String id) throws IOException {
    Test test = tests.get(id);
    if (test == null) {
      return null;
    }
    if (test.state == TestView.State.ENDED) {
      return test.view();
    }
    long nowMs = clock.millis();
    write(new TestView(id, TestView.State.ENDED, test.createdMs, nowMs, test.runs));
    test.state = TestView.State.ENDED;
    test.endedMs = nowMs;
    String active = active(test);
    if (active != null) {
      try {
        controller.cancel(active, TEST_ENDED);
      } catch (IllegalStateException e) {
        // The run already terminated on its own after the active check, leaving nothing to cancel.
      }
    }
    LOG.info("test {} ended", id);
    return test.view();
  }

  /**
   * Returns the identifier of the test's currently RUNNING run, or null if none is active. Only the
   * most recently submitted run can be in the RUNNING state.
   */
  private String active(Test test) {
    if (test.runs.isEmpty()) {
      return null;
    }
    String last = test.runs.get(test.runs.size() - 1);
    RunView run = controller.run(last);
    return run != null && run.getState() == RunView.State.RUNNING ? last : null;
  }

  private void write(TestView view) throws IOException {
    RunBundle.writeAtomically(new File(new File(dir, view.getId()), TEST),
      GSON.toJson(view) + "\n");
  }

  /**
   * Restores every test directory that contains a committed test.json file. Directories without
   * test.json are ignored because their creation call never completed and returned. Any test whose
   * metadata file cannot be read is skipped with a logged warning. If a test's plan file no longer
   * parses, the test is restored in the ENDED state so that its existing runs remain readable.
   * Subsequent test numbering continues above the highest sequence number observed on disk.
   */
  private void restore() {
    File[] children = dir.listFiles();
    if (children == null) {
      return;
    }
    Arrays.sort(children);
    for (File testDir : children) {
      Matcher id = ID.matcher(testDir.getName());
      if (!testDir.isDirectory() || !id.matches()) {
        continue;
      }
      count = Math.max(count, Long.parseLong(id.group(1)));
      File file = new File(testDir, TEST);
      if (!file.isFile()) {
        continue;
      }
      TestView view;
      try {
        view = GSON.fromJson(read(file), TestView.class);
      } catch (IOException | JsonParseException e) {
        LOG.warn("test {} is not restored: {} cannot be read: {}", testDir.getName(), file,
          e.getMessage());
        continue;
      }
      if (
        view == null || !testDir.getName().equals(view.getId()) || view.getState() == null
          || view.getRuns() == null
      ) {
        LOG.warn("test {} is not restored: {} holds no view of this test", testDir.getName(), file);
        continue;
      }
      RunSpec spec = null;
      File plan = new File(testDir, PLAN);
      try {
        spec = RunSpec.parse(read(plan));
      } catch (IOException | IllegalArgumentException e) {
        LOG.warn("test {} is ENDED: {} no longer parses: {}", view.getId(), plan, e.getMessage());
      }
      Test test = new Test(view.getId(), view.getCreatedMs(), spec);
      test.runs.addAll(view.getRuns());
      test.state = spec == null ? TestView.State.ENDED : view.getState();
      test.endedMs = view.getEndedMs();
      if (test.state == TestView.State.ENDED && test.endedMs == null) {
        test.endedMs = clock.millis();
      }
      tests.put(test.id, test);
    }
    if (!tests.isEmpty()) {
      LOG.info("restored {} tests from {}", tests.size(), dir);
    }
  }

  private static String read(File file) throws IOException {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }

  /** Holds the mutable in-memory state for a single test. */
  private static final class Test {
    final String id;
    final long createdMs;
    /** Holds the parsed execution plan, or null if the plan file no longer parses on restore. */
    final RunSpec spec;
    final List<String> runs = new ArrayList<>();
    TestView.State state = TestView.State.OPEN;
    Long endedMs;

    Test(String id, long createdMs, RunSpec spec) {
      this.id = id;
      this.createdMs = createdMs;
      this.spec = spec;
    }

    TestView view() {
      return new TestView(id, state, createdMs, endedMs, runs);
    }
  }
}
