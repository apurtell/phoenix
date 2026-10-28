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
package org.apache.phoenix.vectorharness;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.Setting;
import org.apache.phoenix.vectorharness.result.RunStore;
import org.apache.phoenix.vectorharness.variant.Variants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.phoenix.thirdparty.org.apache.commons.cli.CommandLine;
import org.apache.phoenix.thirdparty.org.apache.commons.cli.DefaultParser;
import org.apache.phoenix.thirdparty.org.apache.commons.cli.HelpFormatter;
import org.apache.phoenix.thirdparty.org.apache.commons.cli.Option;
import org.apache.phoenix.thirdparty.org.apache.commons.cli.Options;
import org.apache.phoenix.thirdparty.org.apache.commons.cli.ParseException;

/**
 * The command line of the vector index harness. Each phase is a subcommand:
 *
 * <pre>
 * vector-harness load        --config &lt;profile&gt;   # generate or read the corpus, create and load tables
 * vector-harness build-index --config &lt;profile&gt;   # build the index variant, check plans
 * vector-harness truth       --config &lt;profile&gt;   # compute or validate the ground truth
 * vector-harness run         --config &lt;profile&gt;   # validity, frontier sweep, steady state
 * vector-harness report      --run-id &lt;id&gt; [--compare &lt;id&gt; ...]
 * </pre>
 *
 * Settings of the profile can be overridden with -Dkey=value. The entry point for embedding and
 * tests is {@link #run(String[], PrintStream, PrintStream)}, which returns the exit status instead
 * of exiting.
 */
public final class VectorHarness {

  private static final Logger LOG = LoggerFactory.getLogger(VectorHarness.class);

  public static final int OK = 0;
  public static final int FAILED = 1;
  public static final int USAGE = 2;

  static final String CLASSPATH_PREFIX = "classpath:";
  static final List<String> PHASES =
    Collections.unmodifiableList(Arrays.asList("load", "build-index", "truth", "run", "report"));

  private VectorHarness() {
  }

  public static void main(String[] args) {
    System.exit(run(args));
  }

  /** Runs a command line, printing to the standard streams; returns the exit status. */
  public static int run(String[] args) {
    return run(args, System.out, System.err);
  }

  static Options options() {
    Options o = new Options();
    o.addOption(Option.builder("c").longOpt("config").hasArg().argName("profile")
      .desc("Profile properties file, or classpath:<resource>").build());
    o.addOption(Option.builder("D").hasArgs().valueSeparator('=').argName("key=value")
      .desc("Override a setting of the profile").build());
    o.addOption(Option.builder().longOpt("url").hasArg().argName("jdbc-url")
      .desc("Phoenix JDBC URL; overrides " + Setting.JDBC_URL.key()).build());
    o.addOption(Option.builder().longOpt("run-id").hasArg().argName("id")
      .desc("run: the identifier of the run; report: the run to report, or latest").build());
    o.addOption(Option.builder().longOpt("compare").hasArgs().argName("id")
      .desc("report: runs to compare with the reported run").build());
    o.addOption(Option.builder().longOpt("force")
      .desc("report: compare runs that do not share corpus, panel, and parameters").build());
    o.addOption(Option.builder("h").longOpt("help").desc("Print this help").build());
    return o;
  }

  static void usage(PrintStream out) {
    PrintWriter w = new PrintWriter(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8));
    new HelpFormatter().printHelp(w, 100,
      "vector-harness <" + String.join("|", PHASES) + "> [options]",
      "Functional and performance harness for Phoenix vector indexes.", options(), 2, 4,
      "Settings are documented in the README of phoenix-vector-harness.", true);
    w.flush();
  }

  /** Runs a command line; returns the exit status. */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    CommandLine cl;
    try {
      cl = new DefaultParser().parse(options(), args);
    } catch (ParseException e) {
      err.println(e.getMessage());
      usage(err);
      return USAGE;
    }
    if (cl.hasOption("help")) {
      usage(out);
      return OK;
    }
    List<String> rest = cl.getArgList();
    if (rest.size() != 1 || !PHASES.contains(rest.get(0).toLowerCase(Locale.ROOT))) {
      err.println(rest.isEmpty()
        ? "No phase given"
        : "Unknown phase or extra arguments: " + String.join(" ", rest));
      usage(err);
      return USAGE;
    }
    String phase = rest.get(0).toLowerCase(Locale.ROOT);
    try {
      HarnessConfig config = config(cl, phase);
      if (config == null) {
        err.println("The " + phase + " phase needs --config");
        usage(err);
        return USAGE;
      }
      switch (phase) {
        case "report":
          return report(cl, config, out, err);
        case "run":
          return runPhase(config, out);
        default:
          return contextPhase(phase, config, out);
      }
    } catch (IllegalArgumentException e) {
      err.println(e.getMessage());
      return USAGE;
    } catch (Exception e) {
      LOG.error("The {} phase failed", phase, e);
      err.println("The " + phase + " phase failed: " + e);
      return FAILED;
    }
  }

  /** Reads the profile with its overrides; null if a phase that needs one has none. */
  static HarnessConfig config(CommandLine cl, String phase) throws IOException {
    Properties overrides = cl.getOptionProperties("D");
    if (cl.hasOption("url")) {
      overrides.setProperty(Setting.JDBC_URL.key(), cl.getOptionValue("url"));
    }
    if (cl.hasOption("run-id") && !phase.equals("report")) {
      overrides.setProperty(Setting.RUN_ID.key(), cl.getOptionValue("run-id"));
    }
    String profile = cl.getOptionValue("config");
    if (profile == null) {
      return phase.equals("report") ? HarnessConfig.of("report", overrides) : null;
    }
    if (profile.startsWith(CLASSPATH_PREFIX)) {
      return HarnessConfig.loadResource(profile.substring(CLASSPATH_PREFIX.length()), overrides);
    }
    Path p = Paths.get(profile);
    if (!Files.exists(p)) {
      throw new IllegalArgumentException("No such profile: " + profile);
    }
    return HarnessConfig.load(p, overrides);
  }

  private static int contextPhase(String phase, HarnessConfig config, PrintStream out)
    throws Exception {
    config.validateOrThrow();
    try (HarnessContext ctx = HarnessContext.open(config)) {
      switch (phase) {
        case "load":
          new LoadPhase(ctx, out).run();
          break;
        case "build-index":
          new BuildIndexPhase(ctx, out).run();
          break;
        case "truth":
          new TruthPhase(ctx, out).run();
          break;
        default:
          throw new IllegalStateException(phase);
      }
    }
    return OK;
  }

  /**
   * Runs the run phase, repeated run.repetitions times. Repetitions after the first rebuild the
   * index first, so that the spread across repetitions includes the variance of the build. The runs
   * of a repeated session share an identifier with the suffix -rN. A streaming profile repeats only
   * with a snapshot, since the first repetition streams the rest of the corpus into the tables and
   * only the snapshot takes them back to the rows loaded before the build.
   */
  private static int runPhase(HarnessConfig config, PrintStream out) throws Exception {
    config.validateOrThrow();
    int repetitions = Math.max(1, config.repetitions());
    String problem = repetitionProblem(config);
    if (problem != null) {
      throw new IllegalArgumentException(problem);
    }
    if (repetitions == 1) {
      try (HarnessContext ctx = HarnessContext.open(config)) {
        new RunPhase(ctx, out, null).run();
      }
      return OK;
    }
    String base = config.runId() != null
      ? config.runId()
      : RunStore.newRunId(Variants.of(config.variant()).id(), config.profileName(), Instant.now());
    HarnessConfig named = config.with(Setting.RUN_ID, base);
    List<String> ids = new ArrayList<>();
    for (int r = 1; r <= repetitions; r++) {
      try (HarnessContext ctx = HarnessContext.open(named)) {
        if (r > 1) {
          out.printf(Locale.ROOT, "repetition %d of %d: rebuilding the index%n", r, repetitions);
          new BuildIndexPhase(ctx, out).run();
        }
        new RunPhase(ctx, out, "-r" + r).run();
        ids.add(base + "-r" + r);
      }
    }
    out.println("repetitions: " + String.join(" ", ids));
    return OK;
  }

  /** Returns why a profile cannot repeat its runs, or null if it can. */
  static String repetitionProblem(HarnessConfig config) {
    if (config.repetitions() > 1 && config.prebuildFraction() < 1.0 && !config.snapshot()) {
      return Setting.RUN_REPETITIONS.key() + "=" + config.repetitions() + " with "
        + Setting.INGEST_PREBUILD_FRACTION.key() + "=" + config.prebuildFraction() + " needs "
        + Setting.TABLE_SNAPSHOT.key() + "=true: without the snapshot the later repetitions"
        + " would build over the streamed rows and stream nothing";
    }
    return null;
  }

  private static int report(CommandLine cl, HarnessConfig config, PrintStream out, PrintStream err)
    throws IOException {
    String runId = cl.getOptionValue("run-id", "latest");
    String[] compare = cl.getOptionValues("compare");
    // Without a profile, the report takes its targets and budgets from the reported run.
    new ReportPhase(config, out, cl.hasOption("config")).run(runId,
      compare == null ? Collections.<String> emptyList() : Arrays.asList(compare),
      cl.hasOption("force"));
    return OK;
  }
}
