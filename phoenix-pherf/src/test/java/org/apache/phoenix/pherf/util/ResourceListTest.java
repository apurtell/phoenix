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
package org.apache.phoenix.pherf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ResourceListTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void testMissingJarFileReturnsGracefully() {
    ResourceList rl = new ResourceList("foo");
    File missingFile = new File("abracadabraphoenix.txt");
    assertFalse("Did not expect a fake test file to actually exist", missingFile.exists());
    assertEquals(Collections.emptyList(),
      new ArrayList<>(rl.getResourcesFromJarFile(missingFile, Pattern.compile("pattern"))));
  }

  @Test
  public void testJarEntriesOutsideRootSkipped() throws Exception {
    File jar = tmp.newFile("resources.jar");
    try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(jar))) {
      for (String name : new String[] { "datamodel/", "datamodel/a.sql", "sql_files/b.sql",
        "synthesis/c.sql" }) {
        out.putNextEntry(new ZipEntry(name));
        out.closeEntry();
      }
    }
    ResourceList rl = new ResourceList("/datamodel");
    assertEquals(Collections.singletonList("/datamodel/a.sql"),
      toStrings(rl.getResourcesFromJarFile(jar, Pattern.compile(".*\\.sql"))));
  }

  @Test
  public void testSameNameInTwoClasspathDirectories() throws Exception {
    File d1 = tmp.newFolder("d1");
    File d2 = tmp.newFolder("d2");
    Path dup1 = write(d1, "datamodel/dup.sql");
    Path deep = write(d1, "datamodel/sub/deep.sql");
    write(d1, "other/x.sql");
    Path dup2 = write(d2, "datamodel/dup.sql");
    String saved = System.getProperty("java.class.path");
    try {
      System.setProperty("java.class.path", d1 + File.pathSeparator + d2);
      ResourceList rl = new ResourceList("/datamodel");
      assertEquals(new HashSet<>(Arrays.asList(dup1, deep, dup2)),
        new HashSet<>(rl.getResourceList(".*\\.sql")));
    } finally {
      System.setProperty("java.class.path", saved);
    }
  }

  @Test
  public void testDirectoryPatternMatchesJarEntryForm() throws Exception {
    File d1 = tmp.newFolder("d1");
    Path a = write(d1, "datamodel/sub/a.sql");
    String saved = System.getProperty("java.class.path");
    try {
      System.setProperty("java.class.path", d1.getPath());
      ResourceList rl = new ResourceList("/datamodel");
      assertEquals(Collections.singletonList(a),
        new ArrayList<>(rl.getResourceList("datamodel/sub/.*\\.sql")));
      assertTrue(rl.getResourceList("sub/.*\\.sql").isEmpty());
    } finally {
      System.setProperty("java.class.path", saved);
    }
  }

  @Test
  public void testDirectoryPatternMatchesAbsolutePath() throws Exception {
    File stage = tmp.newFolder("stage");
    Path staged = write(stage, "scenario/x.xml");
    File jar = tmp.newFile("scenario.jar");
    try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(jar))) {
      out.putNextEntry(new ZipEntry("scenario/x.xml"));
      out.closeEntry();
    }
    String saved = System.getProperty("java.class.path");
    try {
      System.setProperty("java.class.path", stage + File.pathSeparator + jar);
      ResourceList rl = new ResourceList("/scenario");
      assertEquals(Collections.singletonList(staged),
        new ArrayList<>(rl.getResourceList(".*\\Q/stage/scenario/x.xml\\E")));
    } finally {
      System.setProperty("java.class.path", saved);
    }
  }

  @Test
  public void testNonZipClasspathFileSkipped() throws Exception {
    File notZip = tmp.newFile("not_a.jar");
    Files.write(notZip.toPath(), "not a zip".getBytes(StandardCharsets.UTF_8));
    ResourceList rl = new ResourceList("/datamodel");
    assertTrue(rl.getResourcesFromJarFile(notZip, Pattern.compile(".*")).isEmpty());
  }

  @Test
  public void testUnreadableDirectorySkipped() throws Exception {
    File d = tmp.newFolder("d");
    write(d, "datamodel/x.sql");
    File root = new File(d, "datamodel");
    String saved = System.getProperty("java.class.path");
    try {
      Assume.assumeTrue(root.setReadable(false, false));
      Assume.assumeFalse(root.canRead());
      System.setProperty("java.class.path", d.getPath());
      ResourceList rl = new ResourceList("/datamodel");
      assertTrue(rl.getResourceList(".*\\.sql").isEmpty());
    } finally {
      System.setProperty("java.class.path", saved);
      root.setReadable(true, false);
    }
  }

  private static Path write(File dir, String name) throws Exception {
    File f = new File(dir, name);
    assertTrue(f.getParentFile().isDirectory() || f.getParentFile().mkdirs());
    Files.write(f.toPath(), "SELECT 1".getBytes(StandardCharsets.UTF_8));
    return f.toPath().toAbsolutePath();
  }

  private static List<String> toStrings(Collection<?> c) {
    List<String> out = new ArrayList<>();
    for (Object o : c) {
      out.add(o.toString());
    }
    return out;
  }
}
