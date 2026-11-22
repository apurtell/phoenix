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
package org.apache.phoenix.pherf.validation;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.apache.phoenix.pherf.run.ChildJvm;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.pherf.validation.ValidationReport.Finding;
import org.apache.phoenix.pherf.validation.ValidationReport.Rule;

/**
 * Provides access to validation fixtures and constructs modified variants from them. Each variant
 * is produced by copying a clean fixture alongside its profile and applying targeted text
 * replacements. Because the test verifies the exact occurrence count for every replaced text
 * fragment, any variant that remains identical to the clean fixture fails immediately.
 */
final class ValidationFixtures {
  static final String CLEAN = "clean_cogen.xml";
  static final String CLEAN_PROFILE = "clean_cogen_profile.xml";
  static final String CLEAN_DESC = "clean_desc.xml";
  static final String DEFECTS = "defects.xml";
  static final String UNKNOWN_ELEMENT = "unknown_element.xml";
  static final String RELEASE = "release_attribute.xml";

  private ValidationFixtures() {
  }

  /** Returns the source directory that contains the validation fixtures. */
  static File dir() {
    return new File(ChildJvm.targetDir().getParentFile(), "src/test/resources/validation");
  }

  /** Resolves the absolute filesystem path for the named validation fixture. */
  static String file(String name) {
    return new File(dir(), name).getPath();
  }

  /**
   * Executes validation against a scenario file without supplying a schema file, matching the
   * behavior of the -disableSchemaApply option.
   */
  static ValidationReport validate(String path) throws Exception {
    // Reset the row count override in case an earlier test in this JVM left one configured.
    PhoenixUtil.setRowCountOverride(0);
    return ScenarioValidator.validate(path, null);
  }

  /** Filters the validation report and returns all findings recorded for the specified rule. */
  static List<Finding> findings(ValidationReport report, Rule rule) {
    List<Finding> found = new ArrayList<>();
    for (Finding finding : report.getFindings()) {
      if (finding.getRule() == rule) {
        found.add(finding);
      }
    }
    return found;
  }

  /** Extracts the rule identifiers for all findings in the order they appear in the report. */
  static List<String> rules(ValidationReport report) {
    List<String> ids = new ArrayList<>();
    for (Finding finding : report.getFindings()) {
      ids.add(finding.getRule().id());
    }
    return ids;
  }

  /**
   * Returns the 1-based line number of the first line in the fixture file that contains the
   * specified text.
   */
  static int line(String path, String text) throws IOException {
    List<String> lines = Files.readAllLines(new File(path).toPath(), StandardCharsets.UTF_8);
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(text)) {
        return i + 1;
      }
    }
    throw new AssertionError("No line has " + text + " in " + path);
  }

  /**
   * Represents a single text replacement operation in which each of the expected count of
   * occurrences of the original text is replaced with new text.
   */
  static final class Edit {
    final String file;
    final String from;
    final String to;
    final int count;

    Edit(String file, String from, String to, int count) {
      this.file = file;
      this.from = from;
      this.to = to;
      this.count = count;
    }
  }

  /** Creates a replacement specification expecting a single occurrence within the target file. */
  static Edit edit(String file, String from, String to) {
    return new Edit(file, from, to, 1);
  }

  /**
   * Copies a clean scenario fixture and the clean profile into the destination directory while
   * applying the specified edits, and returns the filesystem path to the copied scenario.
   */
  static String variant(File dir, String scenario, Edit... edits) throws IOException {
    List<String> names = new ArrayList<>();
    names.add(scenario);
    names.add(CLEAN_PROFILE);
    for (String name : names) {
      String text =
        new String(Files.readAllBytes(new File(dir(), name).toPath()), StandardCharsets.UTF_8);
      for (Edit edit : edits) {
        if (edit.file.equals(name)) {
          assertEquals("Occurrences of '" + edit.from + "' in " + name, edit.count,
            occurrences(text, edit.from));
          text = text.replace(edit.from, edit.to);
        }
      }
      Files.write(new File(dir, name).toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    return new File(dir, scenario).getPath();
  }

  private static int occurrences(String text, String part) {
    int count = 0;
    for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
      count++;
    }
    return count;
  }
}
