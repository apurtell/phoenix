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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.Setting;
import org.apache.phoenix.vectorharness.report.ReportGenerator;
import org.apache.phoenix.vectorharness.report.RunResult;
import org.apache.phoenix.vectorharness.result.RunStore;

/**
 * Generates single run summaries or multi run comparison reports, printing formatted tables and
 * writing CSV datasets to disk.
 */
public final class ReportPhase {

  /** Settings recorded by a run that can be reused for report generation without a profile. */
  static final Setting[] REPORT_SETTINGS = { Setting.REPORT_RECALL_TARGETS,
    Setting.REPORT_LATENCY_BUDGETS, Setting.RECALL_BOOTSTRAP_RESAMPLES, Setting.HOST_NOISE,
    Setting.REPORT_OPERATING_POINT_NOISE_BAND };

  private final HarnessConfig config;
  private final PrintStream out;
  private final boolean profileGiven;

  public ReportPhase(HarnessConfig config, PrintStream out) {
    this(config, out, true);
  }

  public ReportPhase(HarnessConfig config, PrintStream out, boolean profileGiven) {
    this.config = config;
    this.out = out;
    this.profileGiven = profileGiven;
  }

  /**
   * Generates and writes the report.
   * @param runId   the primary run ID, directory path, or "latest"
   * @param compare additional runs to compare against; empty for single-run summary
   * @param force   whether to allow comparing runs with differing configurations
   * @return the directory where the report was written
   */
  public Path run(String runId, List<String> compare, boolean force) throws IOException {
    RunStore store = RunStore.of(config);
    RunResult base = RunResult.read(store.resolveRun(runId));
    ReportGenerator gen =
      ReportGenerator.of(profileGiven ? config : reportConfig(config, base.settings()));
    List<RunResult> runs = new ArrayList<>();
    runs.add(base);
    for (String id : compare) {
      runs.add(RunResult.read(store.resolveRun(id)));
    }
    String text;
    Path dir;
    if (runs.size() == 1) {
      text = gen.summary(base);
      dir = base.dir().resolve("report");
    } else {
      text = gen.compare(runs, force);
      StringBuilder name = new StringBuilder(base.id());
      for (RunResult r : runs.subList(1, runs.size())) {
        name.append("_vs_").append(r.id());
      }
      String n = name.length() > 200 ? name.substring(0, 200) : name.toString();
      dir = store.root().resolve("reports").resolve(n);
    }
    gen.export(runs, dir);
    Files.write(dir.resolve("report.txt"), text.getBytes(StandardCharsets.UTF_8));
    out.print(text);
    out.println();
    out.println("report written to " + dir);
    return dir;
  }

  /** Combines recorded run settings with explicit configuration overrides. */
  static HarnessConfig reportConfig(HarnessConfig overrides, Map<String, String> recorded) {
    Properties p = new Properties();
    for (Setting s : REPORT_SETTINGS) {
      String v = overrides.isSet(s) ? overrides.raw(s) : recorded.get(s.key());
      if (v != null && !v.trim().isEmpty()) {
        p.setProperty(s.key(), v);
      }
    }
    return HarnessConfig.of("report", p);
  }
}
