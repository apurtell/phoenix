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
package org.apache.phoenix.pherf.analysis;

import static org.apache.phoenix.pherf.analysis.AnalysisFixtures.BUNDLE_JSON;
import static org.apache.phoenix.pherf.analysis.AnalysisFixtures.BUNDLE_XML;
import static org.apache.phoenix.pherf.analysis.AnalysisFixtures.QUERY;
import static org.apache.phoenix.pherf.analysis.AnalysisFixtures.QUERY_KEY;
import static org.apache.phoenix.pherf.analysis.AnalysisFixtures.replica;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Consumer;
import org.apache.phoenix.pherf.exception.AnalysisException;
import org.apache.phoenix.pherf.run.ChildJvm;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Verifies loading of the two replicate directory layouts and all replicate loader refusals. */
public class ReplicateTest {
  @Rule
  public final TemporaryFolder folder = new TemporaryFolder(ChildJvm.targetDir());

  private AnalysisFixtures.Replica fixture(String name) {
    return replica(name).latency(QUERY_KEY, 100, 1000).query(QUERY, null, 5, 5);
  }

  @Test
  public void testBundleLayout() throws Exception {
    File dir = fixture("r1").coGenerated().tags("sizing").writeBundle(folder.getRoot());
    Replicate replicate = Replicate.load(dir);
    assertEquals("r1", replicate.getName());
    assertEquals(Replicate.BUNDLE, replicate.getLayout());
    assertEquals(AnalysisFixtures.runId("r1"), replicate.getRunId());
    assertEquals(Replicate.MEASUREMENT, replicate.getRunClass());
    assertEquals(Collections.singletonList("sizing"), replicate.getContamination());
    assertEquals("PASS", replicate.getResult());
    assertEquals(BUNDLE_JSON, replicate.getLatencyReport());
    assertEquals(Collections.singletonList(BUNDLE_XML), replicate.getXmlResults());
    assertEquals(Collections.singletonList(AnalysisFixtures.SCENARIO), replicate.getCoGenerated());
  }

  @Test
  public void testBundleWithoutXml() throws Exception {
    File dir = fixture("r1").runClass(Replicate.VALIDATION).noXml().writeBundle(folder.getRoot());
    Replicate replicate = Replicate.load(dir);
    assertEquals(Replicate.VALIDATION, replicate.getRunClass());
    assertEquals(Collections.emptyList(), replicate.getContamination());
    assertEquals(Collections.emptyList(), replicate.getXmlResults());
    assertEquals(Collections.emptyList(), replicate.getCoGenerated());
  }

  @Test
  public void testBareLayout() throws Exception {
    File dir = fixture("b1").writeBare(folder.getRoot(), "lbl");
    // Files of other types present in the directory are ignored and not read by the loader.
    Files.write(new File(dir, "LATENCY_lbl.hlog").toPath(), new byte[] { 1 });
    Files.write(new File(dir, "VERDICT_lbl.tsv").toPath(), new byte[] { 1 });
    Replicate replicate = Replicate.load(dir);
    assertEquals(Replicate.BARE, replicate.getLayout());
    assertEquals(Replicate.UNDECLARED, replicate.getRunClass());
    assertNull(replicate.getRunId());
    assertNull(replicate.getResult());
    assertEquals("LATENCY_lbl.json", replicate.getLatencyReport());
    assertEquals(Collections.singletonList("RESULT_dm_lbl.xml"), replicate.getXmlResults());
    assertEquals(Collections.emptyList(), replicate.getCoGenerated());
  }

  @Test
  public void testBareWithoutXml() throws Exception {
    Replicate replicate = Replicate.load(fixture("b1").noXml().writeBare(folder.getRoot(), "x"));
    assertEquals(Collections.emptyList(), replicate.getXmlResults());
  }

  @Test
  public void testRefusesUnknownLatencyFormat() throws Exception {
    refuseLatency(json -> json.addProperty("format", "pherf-other"), "format");
    refuseLatency(json -> json.addProperty("version", 2), "version");
    refuseLatency(json -> json.getAsJsonObject("recorder").addProperty("significantDigits", 2),
      "recorder");
    refuseLatency(json -> json.getAsJsonObject("recorder").addProperty("highestUs", 3600000000L),
      "recorder");
    refuseLatency(json -> json.remove("histograms"), "histograms");
    refuseLatency(
      json -> json.getAsJsonArray("histograms").get(0).getAsJsonObject().remove("p99Us"), "p99Us");
  }

  @Test
  public void testRefusesMissingLatencyReport() throws Exception {
    File dir = fixture("r1").writeBundle(folder.getRoot());
    Files.delete(new File(dir, BUNDLE_JSON).toPath());
    refused(dir, "LATENCY_run.json");
  }

  @Test
  public void testRefusesTwoBareReports() throws Exception {
    File dir = fixture("b1").writeBare(folder.getRoot(), "one");
    Files.copy(new File(dir, "LATENCY_one.json").toPath(),
      new File(dir, "LATENCY_two.json").toPath());
    refused(dir, "LATENCY_one.json");
  }

  @Test
  public void testRefusesUnknownBundleVersionOrClass() throws Exception {
    refuseManifest(json -> json.addProperty("bundle_format_version", 2), "bundle_format_version");
    refuseManifest(json -> json.getAsJsonObject("run").addProperty("class", "benchmark"),
      "benchmark");
    refuseManifest(json -> json.getAsJsonObject("run").remove("id"), "run.id");
    refuseManifest(json -> json.getAsJsonObject("quiet_host").remove("tags"), "quiet_host.tags");
    // Encountering a JSON value of an unexpected type produces a structured refusal rather than
    // throwing an unchecked exception.
    refuseManifest(
      json -> json.getAsJsonObject("quiet_host").getAsJsonArray("tags").add(new JsonObject()),
      "Cannot analyze");
    refuseLatency(json -> json.getAsJsonArray("histograms").add(1), "Cannot analyze");
  }

  @Test
  public void testRefusesErrorBundle() throws Exception {
    refused(fixture("r1").result("ERROR").writeBundle(folder.getRoot()), "ERROR");
  }

  @Test
  public void testRefusesDirectoryThatIsNotAReplicate() throws Exception {
    File dir = folder.newFolder("notes");
    Files.write(new File(dir, "readme.txt").toPath(), "x".getBytes(StandardCharsets.UTF_8));
    refused(dir, "notes");
  }

  @Test
  public void testRefusesUnreadableXml() throws Exception {
    File dir = fixture("r1").writeBundle(folder.getRoot());
    Files.write(new File(dir, BUNDLE_XML).toPath(), "<nope".getBytes(StandardCharsets.UTF_8));
    refused(dir, "RESULT_dm_run.xml");
  }

  @Test
  public void testRefusesEmptySetAndBadSetCount() throws Exception {
    File out = folder.newFolder("out");
    File empty = folder.newFolder("empty");
    for (String[] dirs : Arrays.asList(new String[] { empty.getPath() }, new String[0],
      new String[] { empty.getPath(), empty.getPath(), empty.getPath() },
      new String[] { new File(folder.getRoot(), "absent").getPath() })) {
      try {
        Analysis.run(dirs, out, "x", System.out);
        fail("Expected a refusal: " + Arrays.toString(dirs));
      } catch (AnalysisException e) {
        assertEquals(e.getMessage(), 1, e.getExitCode());
      }
    }
    assertEquals(0, out.list().length);
  }

  private void refuseLatency(Consumer<JsonObject> change, String expected) throws Exception {
    File dir = fixture("r" + folder.getRoot().list().length).writeBundle(folder.getRoot());
    AnalysisFixtures.editJson(new File(dir, BUNDLE_JSON), change);
    refused(dir, expected);
  }

  private void refuseManifest(Consumer<JsonObject> change, String expected) throws Exception {
    File dir = fixture("m" + folder.getRoot().list().length).writeBundle(folder.getRoot());
    AnalysisFixtures.editJson(new File(dir, "manifest.json"), change);
    refused(dir, expected);
  }

  private static void refused(File dir, String expected) {
    try {
      Replicate.load(dir);
      fail("Expected a refusal of " + dir);
    } catch (AnalysisException e) {
      assertEquals(e.getMessage(), 1, e.getExitCode());
      assertTrue(e.getMessage(), e.getMessage().contains(dir.getName()));
      assertTrue(e.getMessage(), e.getMessage().contains(expected));
    }
  }
}
