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
package org.apache.phoenix.pherf.run;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Validates test output against a reference golden file located under the "golden" test resource
 * directory. Leading lines prefixed with '#' in a golden file contain the license header and are
 * stripped prior to comparison. Both the expected and actual content are normalized to use LF line
 * endings with wall-clock timestamps masked out. Every comparison also persists the normalized
 * output to target/golden-actual/TEST/NAME so that an updated golden baseline can be captured
 * directly from the test run.
 */
public final class Golden {
  private static final String CSV_TIME = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}";
  private static final String XML_TIME = "startTime=\"[^\"]*\"";

  private Golden() {
  }

  /**
   * Formats a cell value for golden file representation, substituting "(empty)" for an empty string
   * so that no line in a golden file terminates with trailing whitespace.
   */
  public static String cell(String value) {
    return "".equals(value) ? "(empty)" : value;
  }

  /** Verifies that the normalized actual output matches the expected golden file content. */
  public static void assertMatches(String test, String name, String actual)
    throws IOException, URISyntaxException {
    String normalized = capture(test, name, actual);
    assertEquals("Output differs from golden/" + name, golden(name), normalized);
  }

  /** Normalizes the output, writes it to the capture file, and returns the normalized text. */
  public static String capture(String test, String name, String actual) throws IOException {
    String normalized = normalize(actual);
    File file = new File(ChildJvm.targetDir(), "golden-actual/" + test + "/" + name);
    Files.createDirectories(file.getParentFile().toPath());
    Files.write(file.toPath(), normalized.getBytes(StandardCharsets.UTF_8));
    return normalized;
  }

  /** Returns the normalized lines of a golden file with its leading license header removed. */
  public static List<String> lines(String name) throws IOException, URISyntaxException {
    String text = golden(name);
    return text.isEmpty() ? Collections.<String> emptyList() : Arrays.asList(text.split("\n"));
  }

  private static String golden(String name) throws IOException, URISyntaxException {
    URL url = Golden.class.getResource("/golden/" + name);
    assertNotNull("No golden/" + name + " on the class path", url);
    String text =
      normalize(new String(Files.readAllBytes(Paths.get(url.toURI())), StandardCharsets.UTF_8));
    while (text.startsWith("#")) {
      int end = text.indexOf('\n');
      text = end < 0 ? "" : text.substring(end + 1);
    }
    return text;
  }

  private static String normalize(String text) {
    return text.replace("\r\n", "\n").replaceAll(CSV_TIME, "<TIME>").replaceAll(XML_TIME,
      "startTime=\"<TIME>\"");
  }
}
