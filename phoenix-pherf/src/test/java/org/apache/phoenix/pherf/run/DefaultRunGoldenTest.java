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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies that a default Pherf execution configured with neither extensions nor group properties
 * produces the exact same CSV and XML result files and establishes the same JDBC connections as the
 * baseline golden files captured prior to introducing the extension points. The test drives Pherf
 * through the CLI using a fixed label, a constant clock, and an empty database holding no data.
 */
public class DefaultRunGoldenTest {
  private static final String TEST = DefaultRunGoldenTest.class.getSimpleName();
  private static final String DIR = "default-run/";

  @Rule
  public TemporaryFolder folder = new TemporaryFolder(ChildJvm.targetDir());

  @Test
  public void testDefaultRunMatchesGolden() throws Exception {
    ChildJvm child = new ChildJvm(folder.getRoot());
    String scenario = new File(ChildJvm.scenarioDir(), "test_tenant_binding.xml").getPath();
    child.runCli(Collections.singletonList(ChildJvm.args("-l", "-q", "-z", "golden", "-label",
      "golden", "-writerThreadSize", "2", "-disableSchemaApply", "-scenarioFile", scenario)));

    File results = new File(child.work(), "RESULTS");
    String[] names = results.list();
    Arrays.sort(names);
    Map<String, String> outputs = new LinkedHashMap<>();
    outputs.put("files.golden", String.join("\n", names) + "\n");
    for (String name : names) {
      outputs.put(name + ".golden",
        new String(Files.readAllBytes(new File(results, name).toPath()), StandardCharsets.UTF_8));
    }
    List<String> connections = Records.withoutMarks(child.record(Records.CONNECTIONS));
    Collections.sort(connections);
    outputs.put("connections.golden", String.join("\n", connections) + "\n");

    // We capture all outputs before asserting any matches so that a single test execution
    // generates the complete set of new golden files.
    for (Map.Entry<String, String> output : outputs.entrySet()) {
      Golden.capture(TEST, DIR + output.getKey(), output.getValue());
    }
    for (Map.Entry<String, String> output : outputs.entrySet()) {
      Golden.assertMatches(TEST, DIR + output.getKey(), output.getValue());
    }
  }

}
