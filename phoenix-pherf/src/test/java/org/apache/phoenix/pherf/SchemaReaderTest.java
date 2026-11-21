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
package org.apache.phoenix.pherf;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.phoenix.pherf.schema.SchemaReader;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SchemaReaderTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void testClasspathSchemaKeepsLineBreaks() throws Exception {
    SchemaReader reader = new SchemaReader(PhoenixUtil.create(), ".*datamodel/test_schema.sql");
    List<Path> paths = new ArrayList<>(reader.getResourceList());
    assertEquals(1, paths.size());
    Path p = paths.get(0);
    String text = reader.resourceToString(p);
    assertEquals(String.join("\n", Files.readAllLines(p)) + "\n", text);
  }

  @Test
  public void testSchemaByAbsolutePath() throws Exception {
    String sql = "-- comment\nCREATE TABLE IF NOT EXISTS PHERF.FS_T (A VARCHAR PRIMARY KEY)\n";
    File f = tmp.newFile("outside_cp_schema.sql");
    Files.write(f.toPath(), sql.getBytes(StandardCharsets.UTF_8));
    Path abs = f.toPath().toAbsolutePath();
    SchemaReader reader = new SchemaReader(PhoenixUtil.create(), abs.toString());
    assertEquals(Collections.singletonList(abs), new ArrayList<>(reader.getResourceList()));
    assertEquals(sql, reader.resourceToString(abs));
  }
}
