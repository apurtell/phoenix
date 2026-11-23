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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import org.apache.phoenix.pherf.distributed.clock.Clock;
import org.apache.phoenix.pherf.distributed.protocol.Assignment;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.protocol.Share;
import org.apache.phoenix.pherf.validation.ScenarioValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes the Pherf workload for an agent. Pherf always runs in an isolated child JVM rather than
 * inside the agent JVM, spawning one child process for each validation during preparation and one
 * child process for each launch. Process isolation prevents Pherf's static state from leaking
 * across runs, ensures an exit within Pherf cannot terminate the agent, allows cancellation to stop
 * active work, and prevents a heavy workload from starving the agent heartbeat.
 * <p>
 * Preparation writes the assigned slice and the overlay pherf.properties into the work directory
 * and runs Pherf's built-in offline validation (-validate) against the slice using the run options.
 * It then sequentially validates each later-step slice included in the assignment, running one
 * child process at a time in its own subdirectory under {@link #LATER}, and reports the collected
 * errors across all slices. Launching the workload immediately starts the run child process and
 * tails its interval log. The child process warms up and prints an armed marker when it holds at
 * the Pherf start gate. Starting the workload writes the start time to the child process's standard
 * input (stdin), which opens the gate at that time and prints a started marker with that timestamp.
 * Cancellation sends SIGTERM and escalates to SIGKILL after {@link Protocol#STOP_GRACE_MS}. Each
 * lifecycle stage notifies the listener only after its child process has exited, ensuring the agent
 * never transitions to idle while a child remains alive.
 * <p>
 * Listener callbacks always execute on the clock thread without holding the workload lock, because
 * the agent runtime invokes the workload while holding its own lock; this serialization also
 * preserves the order of reported intervals. Child process exits and timer events mutate workload
 * state exclusively while holding the workload lock.
 */
public final class PherfWorkload implements Workload, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(PherfWorkload.class);
  /**
   * Names the subdirectory that stores the assigned documents. This directory is added to the class
   * path of the child process.
   */
  static final String SLICE = "slice";
  /**
   * Names the subdirectory that stores the child's pherf.properties file. This directory appears
   * first on the child's class path.
   */
  static final String OVERLAY = "overlay";
  /**
   * Names the parent directory for later-step slices validated during preparation, with one
   * subdirectory allocated per slice.
   */
  static final String LATER = "later";
  /**
   * Names the results directory for the child process. Pherf writes its HDR histogram files to
   * RESULTS_HDR.
   */
  static final String RESULTS = "RESULTS";
  /**
   * Defines the interval export period in milliseconds for the child process as well as the polling
   * period for reading its interval log.
   */
  static final long INTERVAL_MS = 1000;
  static final String VALIDATE_LABEL = "validate";
  static final String RUN_LABEL = "run";
  private static final String PROPERTIES = "pherf.properties";

  private enum Stage {
    VALIDATING,
    PREPARED,
    RUNNING,
    ENDED
  }

  /**
   * Encapsulates the mutable runtime state for a single assignment. All fields except the interval
   * log tail are guarded by the enclosing workload lock.
   */
  private static final class Job {
    final Assignment assignment;
    Workload.Listener listener;
    Stage stage = Stage.VALIDATING;
    boolean cancelled;
    /**
     * Tracks the sequence numbers of the most recent child process launch and of the most recent
     * launch whose child process has exited.
     */
    long launches;
    long exitedLaunch;
    Launcher.Handle child;
    /** Holds the active timer used to periodically poll the child process's interval log. */
    Clock.Timer timer;
    Clock.Timer kill;
    /** Reads the child's interval log; accessed exclusively on the clock thread. */
    IntervalTail tail;
    /**
     * Records a malformed interval log error, which the run reports once its child process has
     * exited.
     */
    String failure;

    Job(Assignment assignment, Workload.Listener listener) {
      this.assignment = assignment;
      this.listener = listener;
    }

    /**
     * Returns true if the child process from the most recent launch has already exited, or if no
     * child process has been launched yet.
     */
    boolean exited() {
      return exitedLaunch == launches;
    }
  }

  private final File workDir;
  private final String zookeeper;
  private final Properties shipped;
  private final Launcher launcher;
  private final Clock clock;
  private Job job;

  /**
   * @param workDir           the working directory assigned to this agent, which is emptied at the
   *                          beginning of each preparation phase.
   * @param zookeeper         the value passed for the -z option of each child process.
   * @param shippedProperties the baseline pherf.properties loaded from the agent class path.
   */
  public PherfWorkload(File workDir, String zookeeper, Properties shippedProperties,
    Launcher launcher, Clock clock) {
    this.workDir = workDir.getAbsoluteFile();
    this.zookeeper = zookeeper;
    this.shipped = new Properties();
    this.shipped.putAll(shippedProperties);
    this.launcher = launcher;
    this.clock = clock;
  }

  @Override
  public void prepare(Assignment assignment, Listener listener) {
    Job j = new Job(assignment, listener);
    synchronized (this) {
      stopForcibly(job);
      job = j;
    }
    List<Assignment> slices = new ArrayList<>();
    slices.add(assignment);
    slices.addAll(assignment.getValidations());
    validate(j, slices, 0, new ArrayList<>());
  }

  /**
   * Materializes slice i on disk and validates it in a child process. The agent's primary slice is
   * processed first and remains in the work directory for the subsequent start phase, whereas any
   * error from a later slice is prefixed with its step and slot.
   */
  private void validate(Job j, List<Assignment> slices, int i, List<String> errors) {
    Assignment slice = slices.get(i);
    int slot = slice.getIdentity().getSlot();
    File dir = i == 0 ? workDir : new File(new File(workDir, LATER), slice.getStep() + "-" + slot);
    String prefix = i == 0 ? "" : "step " + slice.getStep() + " slot " + slot + ": ";
    try {
      writeSlice(slice, dir);
    } catch (IOException | RuntimeException e) {
      LOG.warn("cannot write the slice of run {} step {} slot {}", slice.getRunId(),
        slice.getStep(), slot, e);
      errors.add(prefix + "cannot write the slice: " + e);
      next(j, slices, i, errors);
      return;
    }
    launch(j, args(slice, dir, true), dir, (status, lines) -> {
      for (String error : validated(dir, status, lines)) {
        errors.add(prefix + error);
      }
      next(j, slices, i, errors);
    }, error -> {
      errors.add(prefix + "cannot launch pherf -validate: " + error);
      next(j, slices, i, errors);
    });
  }

  /**
   * Advances preparation after slice i completes by either validating the next slice in the list or
   * finishing the preparation phase. Cancellation terminates the sequence immediately.
   */
  private void next(Job j, List<Assignment> slices, int i, List<String> errors) {
    boolean more;
    synchronized (this) {
      more = i + 1 < slices.size() && !j.cancelled && job == j;
    }
    if (more) {
      validate(j, slices, i + 1, errors);
    } else {
      prepared(j, errors);
    }
  }

  /** Immediately launches the run child process and begins periodic polling of its interval log. */
  @Override
  public void launch(Assignment assignment, Listener listener) {
    Job j;
    synchronized (this) {
      j = job;
      if (
        j == null || j.stage != Stage.PREPARED || j.cancelled
          || !j.assignment.getRunId().equals(assignment.getRunId())
          || j.assignment.getStep() != assignment.getStep()
      ) {
        clock.schedule(0, () -> listener.failed("run " + assignment.getRunId() + " step "
          + assignment.getStep() + " has no prepared slice"));
        return;
      }
      j.listener = listener;
      j.stage = Stage.RUNNING;
      j.tail = new IntervalTail(
        new File(workDir, RESULTS + "_HDR/LATENCY_" + RUN_LABEL + "_intervals.hlog"));
    }
    LOG.info("pherf launches run {} step {}", j.assignment.getRunId(), j.assignment.getStep());
    launch(j, args(j.assignment, workDir, false), workDir, new Launcher.ExitListener() {
      @Override
      public void exited(int status, List<String> lastLines) {
        ran(j, status, lastLines);
      }

      @Override
      public void armed() {
        marker(j, () -> j.listener.armed());
      }

      @Override
      public void started(long startedMs) {
        marker(j, () -> j.listener.started(startedMs));
      }
    }, error -> {
      synchronized (this) {
        j.stage = Stage.ENDED;
      }
      clock.schedule(0, () -> j.listener.failed("cannot launch pherf: " + error));
    });
    synchronized (this) {
      if (j.stage == Stage.RUNNING && !j.exited()) {
        j.timer = clock.schedule(INTERVAL_MS, () -> poll(j));
      }
    }
  }

  /**
   * Dispatches a lifecycle marker emitted by the run child process to the listener on the clock
   * thread.
   */
  private void marker(Job j, Runnable call) {
    synchronized (this) {
      if (job != j || j.stage != Stage.RUNNING || j.cancelled) {
        return;
      }
    }
    clock.schedule(0, call);
  }

  /**
   * Sends the scheduled start time to the armed run child process. The write executes outside the
   * workload lock because the child process can exit during the operation. A failed write
   * terminates the child process and causes the run to fail once the child has exited.
   */
  @Override
  public void start(String runId, long startAtMs) {
    Job j;
    Launcher.Handle child;
    synchronized (this) {
      j = job;
      if (
        j == null || j.stage != Stage.RUNNING || j.cancelled || j.child == null
          || !j.assignment.getRunId().equals(runId)
      ) {
        return;
      }
      child = j.child;
    }
    try {
      child.send(Long.toString(startAtMs));
    } catch (IOException e) {
      LOG.warn("cannot send the start time to the pherf child of run {}: {}", runId, e.toString());
      synchronized (this) {
        if (j.failure == null) {
          j.failure = "cannot send the start time: " + e;
          terminate(j);
        }
      }
    }
  }

  @Override
  public void cancel(String runId) {
    synchronized (this) {
      Job j = job;
      if (j == null || j.cancelled || !j.assignment.getRunId().equals(runId)) {
        return;
      }
      j.cancelled = true;
      switch (j.stage) {
        case VALIDATING:
        case RUNNING:
          // The child process exit callback reports the completion of the active stage, including
          // when the child is holding at its start gate.
          terminate(j);
          break;
        default:
          // No child or timer is active, so the agent transitions to idle immediately.
          break;
      }
    }
  }

  /**
   * Forcibly terminates any live child process immediately. The agent invokes this method before
   * halting.
   */
  @Override
  public synchronized void close() {
    stopForcibly(job);
  }

  private void stopForcibly(Job j) {
    if (j == null) {
      return;
    }
    for (Clock.Timer timer : new Clock.Timer[] { j.timer, j.kill }) {
      if (timer != null) {
        timer.cancel();
      }
    }
    if (j.child != null) {
      j.child.destroyForcibly();
    }
  }

  /**
   * Initiates child termination while holding the workload lock by sending SIGTERM immediately and
   * scheduling a SIGKILL fallback if the child remains alive after the grace period.
   */
  private void terminate(Job j) {
    if (j.child == null || j.kill != null) {
      return;
    }
    j.child.destroy();
    j.kill = clock.schedule(Protocol.STOP_GRACE_MS, () -> {
      synchronized (this) {
        if (j.child != null) {
          LOG.warn("the pherf child of run {} is still alive after {} ms; killing it",
            j.assignment.getRunId(), Protocol.STOP_GRACE_MS);
          j.child.destroyForcibly();
        }
      }
    });
  }

  /**
   * Launches a child process in the specified working directory. The exit listener is invoked only
   * after the job state has been updated to record the child's exit, and any launch failure
   * immediately terminates the current stage with the corresponding error.
   */
  private void launch(Job j, List<String> args, File dir, Launcher.ExitListener exit,
    Consumer<String> failed) {
    long launch;
    synchronized (this) {
      launch = ++j.launches;
    }
    Launcher.Handle handle;
    try {
      handle = launcher.launch(args, dir, new Launcher.ExitListener() {
        @Override
        public void exited(int status, List<String> lastLines) {
          synchronized (PherfWorkload.this) {
            PherfWorkload.this.exited(j, launch);
          }
          exit.exited(status, lastLines);
        }

        @Override
        public void armed() {
          exit.armed();
        }

        @Override
        public void started(long startedMs) {
          exit.started(startedMs);
        }
      });
    } catch (IOException | RuntimeException e) {
      LOG.warn("cannot launch pherf for run {}", j.assignment.getRunId(), e);
      synchronized (this) {
        j.exitedLaunch = launch;
      }
      failed.accept(e.getMessage());
      return;
    }
    synchronized (this) {
      // A short-lived child may exit before launch returns, and a subsequent child may already be
      // running.
      if (j.exitedLaunch < launch) {
        j.child = handle;
        if (j.cancelled || j.failure != null) {
          terminate(j);
        }
      }
    }
  }

  private void prepared(Job j, List<String> errors) {
    synchronized (this) {
      if (j.stage != Stage.VALIDATING) {
        return;
      }
      j.stage = Stage.PREPARED;
    }
    clock.schedule(0, () -> j.listener.prepared(errors));
  }

  /**
   * Extracts the validation errors produced by a -validate child process that executed in the given
   * directory.
   */
  private static List<String> validated(File dir, int status, List<String> lines) {
    if (status == 0) {
      return Collections.emptyList();
    }
    if (status == ScenarioValidator.EXIT_CODE) {
      return findings(dir);
    }
    return Collections
      .singletonList("pherf -validate exited with " + status + ": " + String.join(" | ", lines));
  }

  /**
   * Records under the workload lock that the child process for the specified launch has exited and
   * cancels any pending kill timer.
   */
  private void exited(Job j, long launch) {
    j.exitedLaunch = launch;
    j.child = null;
    if (j.kill != null) {
      j.kill.cancel();
      j.kill = null;
    }
  }

  /**
   * Parses the child's JSON validation report and formats one error string for each reported
   * finding.
   */
  private static List<String> findings(File dir) {
    File report = new File(new File(dir, RESULTS), "VALIDATION_" + VALIDATE_LABEL + ".json");
    try {
      JsonObject json = JsonParser
        .parseString(new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8))
        .getAsJsonObject();
      List<String> errors = new ArrayList<>();
      for (JsonElement element : json.getAsJsonArray("findings")) {
        // Format the text to match ValidationReport.Finding: rule file[:line] where: message.
        JsonObject finding = element.getAsJsonObject();
        errors.add(finding.get("rule").getAsString() + " " + finding.get("file").getAsString()
          + (finding.has("line") ? ":" + finding.get("line").getAsInt() : "") + " "
          + finding.get("where").getAsString() + ": " + finding.get("message").getAsString());
      }
      if (errors.isEmpty()) {
        throw new IllegalArgumentException("the report has no finding");
      }
      return errors;
    } catch (IOException | RuntimeException e) {
      return Collections.singletonList("pherf -validate exited with " + ScenarioValidator.EXIT_CODE
        + " and gave no valid report: " + e.getMessage());
    }
  }

  /**
   * Executes the poll timer callback on the clock thread to drain newly written intervals and
   * reschedule polling while the run remains active.
   */
  private void poll(Job j) {
    synchronized (this) {
      if (j.stage != Stage.RUNNING || j.exited()) {
        return;
      }
    }
    drain(j);
    synchronized (this) {
      if (j.stage == Stage.RUNNING && !j.exited() && j.failure == null) {
        j.timer = clock.schedule(INTERVAL_MS, () -> poll(j));
      }
    }
  }

  /**
   * Reads newly appended entries from the interval log on the clock thread and forwards them to the
   * workload listener.
   */
  private void drain(Job j) {
    synchronized (this) {
      if (j.cancelled || j.failure != null) {
        return;
      }
    }
    List<IntervalTail.Interval> intervals;
    try {
      intervals = j.tail.read();
    } catch (IOException | IllegalArgumentException e) {
      LOG.warn("run {}: bad interval log: {}", j.assignment.getRunId(), e.getMessage());
      synchronized (this) {
        j.failure = "bad interval log: " + e.getMessage();
        terminate(j);
      }
      return;
    }
    for (IntervalTail.Interval interval : intervals) {
      j.listener.interval(interval.operation, interval.phase, interval.startMs, interval.endMs,
        interval.histogram);
    }
  }

  /**
   * Handles the exit of the run child process on the launcher thread by cancelling the poll timer
   * and scheduling final completion on the clock thread.
   */
  private void ran(Job j, int status, List<String> lines) {
    synchronized (this) {
      if (j.timer != null) {
        j.timer.cancel();
      }
      if (j.stage != Stage.RUNNING) {
        return;
      }
    }
    clock.schedule(0, () -> finish(j, status, lines));
  }

  /**
   * Completes a run on the clock thread by performing a final drain of the interval log and
   * notifying the listener whether the workload finished or failed.
   */
  private void finish(Job j, int status, List<String> lines) {
    drain(j);
    boolean cancelled;
    String failure;
    synchronized (this) {
      if (j.stage != Stage.RUNNING) {
        return;
      }
      j.stage = Stage.ENDED;
      cancelled = j.cancelled;
      failure = j.failure;
    }
    if (cancelled || failure == null && status == 0) {
      j.listener.finished();
    } else if (failure != null) {
      j.listener.failed(failure);
    } else {
      j.listener.failed("pherf exited with " + status + ": " + String.join(" | ", lines));
    }
  }

  /**
   * Assembles the Pherf command-line arguments for a child process by starting with the step
   * options and appending the options controlled by the agent.
   */
  private List<String> args(Assignment assignment, File dir, boolean validate) {
    List<String> args = new ArrayList<>(assignment.getArgs());
    args.add("-scenarioFile");
    args.add(new File(new File(dir, SLICE), assignment.getScenario()).getPath());
    args.add("-label");
    args.add(validate ? VALIDATE_LABEL : RUN_LABEL);
    args.add("-z");
    args.add(zookeeper);
    Share share = assignment.getShare();
    Object[][] options =
      { { "-runDurationMs", share.getDurationMs() }, { "-threadCount", share.getThreads() },
        { "-targetRate", share.getRate() }, { "-rowCountOverride", share.getRows() } };
    for (Object[] option : options) {
      if (option[1] != null) {
        args.add((String) option[0]);
        args.add(option[1].toString());
      }
    }
    if (validate) {
      args.add("-validate");
    }
    return args;
  }

  /**
   * Clears all existing contents from the target directory and writes the assigned documents along
   * with the overlay pherf.properties file into it.
   */
  private void writeSlice(Assignment assignment, File dir) throws IOException {
    Files.createDirectories(dir.toPath());
    Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
        throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path path, IOException e) throws IOException {
        if (e != null) {
          throw e;
        }
        if (!path.equals(dir.toPath())) {
          Files.delete(path);
        }
        return FileVisitResult.CONTINUE;
      }
    });
    File slice = new File(dir, SLICE);
    Files.createDirectories(slice.toPath());
    for (Map.Entry<String, String> document : assignment.getDocuments().entrySet()) {
      // The controller validates document names as well; each name must refer to a single plain
      // file inside the slice directory.
      if (!Assignment.DOCUMENT_NAME.matcher(document.getKey()).matches()) {
        throw new IllegalArgumentException("not a document name: " + document.getKey());
      }
      Files.write(new File(slice, document.getKey()).toPath(),
        document.getValue().getBytes(StandardCharsets.UTF_8));
    }
    // Apply properties from lowest to highest precedence: the shipped file first, followed by the
    // assignment properties (step overrides run-level), and finally the agent-managed keys.
    Properties properties = new Properties();
    properties.putAll(shipped);
    properties.putAll(assignment.getProperties());
    properties.setProperty(Assignment.RESULTS_DIR_PROPERTY, new File(dir, RESULTS).getPath());
    properties.setProperty(Assignment.INTERVAL_PROPERTY, Long.toString(INTERVAL_MS));
    File overlay = new File(dir, OVERLAY);
    Files.createDirectories(overlay.toPath());
    try (OutputStream out = new FileOutputStream(new File(overlay, PROPERTIES))) {
      properties.store(out, "run " + assignment.getRunId() + " step " + assignment.getStep()
        + " slot " + assignment.getIdentity().getSlot());
    }
  }
}
