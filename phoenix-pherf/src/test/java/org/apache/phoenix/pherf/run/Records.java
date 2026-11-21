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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Manages line-oriented execution records written by a child run and read back by the test suite.
 * The child process writes each record file into the directory specified by the
 * {@link #DIR_PROPERTY} system property.
 */
public final class Records {
  public static final String DIR_PROPERTY = "pherf.test.record.dir";
  public static final String CONNECTIONS = "connections.log";
  public static final String PROVIDER = "provider.log";
  public static final String SINK = "sink.log";
  public static final String OUTCOME = "outcome.log";
  private static final String MARK = "== invocation ";
  private static final Map<String, Writer> WRITERS = new HashMap<>();

  private Records() {
  }

  /**
   * Appends a single line to the named record file, or performs a no-op when no record directory
   * has been configured.
   */
  public static synchronized void append(String file, String line) {
    String dir = System.getProperty(DIR_PROPERTY);
    if (dir == null) {
      return;
    }
    try {
      Writer writer = WRITERS.get(file);
      if (writer == null) {
        writer = new OutputStreamWriter(new FileOutputStream(new File(dir, file), true),
          StandardCharsets.UTF_8);
        WRITERS.put(file, writer);
      }
      writer.write(line);
      writer.write('\n');
      writer.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Writes a delimiter that begins the section for invocation n in every record file that is
   * partitioned by invocation.
   */
  public static void mark(int n) {
    for (String file : new String[] { CONNECTIONS, PROVIDER, SINK }) {
      append(file, MARK + n);
    }
  }

  /** Formats the URL and sorted properties of a single connection into one record line. */
  public static String connectionLine(String url, Properties properties) {
    Map<String, String> sorted = new TreeMap<>();
    for (String key : properties.stringPropertyNames()) {
      sorted.put(key, properties.getProperty(key));
    }
    return url + "\t" + sorted;
  }

  /** Reads all lines from a record file, returning an empty list if the file does not exist. */
  public static List<String> read(File file) throws IOException {
    if (!file.exists()) {
      return Collections.emptyList();
    }
    return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
  }

  /** Partitions a record file into per-invocation line lists, preserving the invocation order. */
  public static List<List<String>> byInvocation(File file) throws IOException {
    List<List<String>> sections = new ArrayList<>();
    for (String line : read(file)) {
      if (line.startsWith(MARK)) {
        sections.add(new ArrayList<String>());
      } else if (!sections.isEmpty()) {
        sections.get(sections.size() - 1).add(line);
      }
    }
    return sections;
  }

  /** Reads all entries from a record file with its invocation boundary markers stripped out. */
  public static List<String> withoutMarks(File file) throws IOException {
    List<String> lines = new ArrayList<>();
    for (List<String> section : byInvocation(file)) {
      lines.addAll(section);
    }
    return lines;
  }
}
