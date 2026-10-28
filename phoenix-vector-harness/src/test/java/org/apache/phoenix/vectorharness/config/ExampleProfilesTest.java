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
package org.apache.phoenix.vectorharness.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.Test;

/**
 * Checks that the example profiles shipped in the module's profiles directory are valid and use
 * only known settings, so that they keep working as settings evolve.
 */
public class ExampleProfilesTest {

  static List<Path> profiles() throws IOException {
    Path dir = Paths.get("profiles");
    List<Path> out = new ArrayList<>();
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.properties")) {
      for (Path p : ds) {
        out.add(p);
      }
    }
    Collections.sort(out);
    return out;
  }

  @Test
  public void testProfilesValidate() throws Exception {
    List<Path> profiles = profiles();
    assertTrue("no example profiles found", profiles.size() >= 5);
    for (Path p : profiles) {
      HarnessConfig c = HarnessConfig.load(p, new Properties());
      assertEquals(p + ": " + c.validate(), Collections.emptyList(), c.validate());
    }
  }

  @Test
  public void testProfilesUseKnownSettings() throws Exception {
    for (Path p : profiles()) {
      Properties props = new Properties();
      try (java.io.Reader r = Files.newBufferedReader(p)) {
        props.load(r);
      }
      for (String key : props.stringPropertyNames()) {
        boolean passthrough = key.startsWith("phoenix.") || key.startsWith("hbase.");
        assertTrue(p + " sets unknown setting " + key, passthrough || Setting.forKey(key) != null);
      }
    }
  }

  @Test
  public void testComparableSiftProfiles() throws Exception {
    HarnessConfig hnsw =
      HarnessConfig.load(Paths.get("profiles", "sift1m-hnsw-sq8.properties"), new Properties());
    HarnessConfig ivf =
      HarnessConfig.load(Paths.get("profiles", "sift1m-ivf.properties"), new Properties());
    for (Setting s : Setting.values()) {
      if (org.apache.phoenix.vectorharness.report.ReportGenerator.exempt(s.key())) {
        continue;
      }
      assertEquals(s.key(), hnsw.effectiveSettings().get(s.key()),
        ivf.effectiveSettings().get(s.key()));
    }
  }
}
