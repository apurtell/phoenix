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
package org.apache.phoenix.pherf.distributed.agent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.distributed.protocol.HistogramBlob;
import org.apache.phoenix.pherf.distributed.protocol.ProtocolException;
import org.apache.phoenix.pherf.latency.LatencyReport;

/**
 * Tails the interval latency log produced by Pherf during a workload run (see LATENCY.md, "Interval
 * export"). Each read operation parses and returns the intervals from newly completed lines while
 * buffering any trailing partial line until the next read. Interval time bounds are derived
 * directly from the decimal text representation on each line, preserving exact millisecond
 * precision.
 */
final class IntervalTail {
  private static final String BASE_TIME = "#[BaseTime: ";
  private static final String TAG = "Tag=";

  /** Encapsulates a single interval histogram recorded for a specific operation and phase. */
  static final class Interval {
    final String operation;
    final String phase;
    final long startMs;
    final long endMs;
    final Histogram histogram;

    Interval(String operation, String phase, long startMs, long endMs, Histogram histogram) {
      this.operation = operation;
      this.phase = phase;
      this.startMs = startMs;
      this.endMs = endMs;
      this.histogram = histogram;
    }
  }

  private final File file;
  private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
  private long position;
  private long lines;
  private Long baseMs;

  IntervalTail(File file) {
    this.file = file;
  }

  /**
   * Reads and returns the intervals parsed from lines completed since the previous read, or an
   * empty list if the underlying file does not yet exist.
   * @throws IOException              when the interval log file cannot be read.
   * @throws IllegalArgumentException when a line does not conform to the interval log format.
   */
  List<Interval> read() throws IOException {
    if (!file.isFile()) {
      return Collections.emptyList();
    }
    byte[] bytes;
    try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
      long length = in.length();
      if (length < position) {
        throw new IOException(file + " became shorter");
      }
      bytes = new byte[Math.toIntExact(length - position)];
      in.seek(position);
      in.readFully(bytes);
      position = length;
    }
    List<Interval> intervals = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] == '\n') {
        partial.write(bytes, start, i - start);
        String line = new String(partial.toByteArray(), StandardCharsets.UTF_8);
        partial.reset();
        start = i + 1;
        lines++;
        Interval interval =
          parse(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        if (interval != null) {
          intervals.add(interval);
        }
      }
    }
    partial.write(bytes, start, bytes.length - start);
    return intervals;
  }

  /** Parses a single log line into an interval record, or returns null if the line is a header. */
  private Interval parse(String line) {
    try {
      if (line.startsWith(BASE_TIME)) {
        String seconds = line.substring(BASE_TIME.length(), line.indexOf(' ', BASE_TIME.length()));
        baseMs = millis(seconds);
        return null;
      }
      if (line.isEmpty() || line.startsWith("#") || line.startsWith("\"StartTimestamp\"")) {
        return null;
      }
      if (!line.startsWith(TAG)) {
        throw new IllegalArgumentException("not an interval");
      }
      if (baseMs == null) {
        throw new IllegalArgumentException("an interval before the base time");
      }
      // Expected format: Tag=<tag>,<start s>,<length s>,<max>,<histogram>, where commas within the
      // tag are escaped.
      String[] fields = line.substring(TAG.length()).split(",", -1);
      if (fields.length != 5) {
        throw new IllegalArgumentException(fields.length + " fields, not 5");
      }
      String[] key = LatencyReport.parseTag(fields[0]);
      long startMs = baseMs + millis(fields[1]);
      long endMs = startMs + millis(fields[2]);
      Histogram histogram = HistogramBlob.decode(fields[4]);
      return new Interval(key[0], key[1], startMs, endMs, histogram);
    } catch (RuntimeException | ProtocolException e) {
      String text = line.length() > 80 ? line.substring(0, 80) + "..." : line;
      throw new IllegalArgumentException("line " + lines + ": " + e.getMessage() + ": " + text, e);
    }
  }

  /**
   * Converts a decimal timestamp in seconds with at most 3 decimal places into exact milliseconds
   * (ms).
   */
  private static long millis(String seconds) {
    return new BigDecimal(seconds.trim()).movePointRight(3).longValueExact();
  }
}
