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

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and formats durations in the profile syntax: a number followed by ms, s, m, h, or d. A
 * number without a unit is in seconds. A sequence of such terms, each with a unit, such as the
 * 1m30s that {@link #format} writes, is their sum.
 */
public final class Durations {

  /** One term of a compound duration: a number and a unit. */
  private static final Pattern TERM = Pattern.compile("(\\d+(?:\\.\\d*)?|\\.\\d+)\\s*([a-z]+)\\s*");

  private Durations() {
  }

  public static Duration parse(String text) {
    String t = text.trim().toLowerCase(Locale.ROOT);
    if (t.isEmpty()) {
      throw new IllegalArgumentException("Empty duration");
    }
    int end = t.length();
    while (end > 0 && Character.isLetter(t.charAt(end - 1))) {
      end--;
    }
    String number = t.substring(0, end).trim();
    String unit = t.substring(end);
    double value;
    try {
      value = Double.parseDouble(number);
    } catch (NumberFormatException e) {
      return parseCompound(text, t);
    }
    if (value < 0 || Double.isNaN(value) || Double.isInfinite(value)) {
      throw new IllegalArgumentException("Invalid duration: " + text);
    }
    return Duration.ofMillis(Math.round(value * unitMillis(unit, text)));
  }

  /** Parses a sequence of terms that each have a unit, such as 1h5m or 1m30.5s. */
  private static Duration parseCompound(String text, String t) {
    Matcher m = TERM.matcher(t);
    double millis = 0.0;
    int pos = 0;
    while (pos < t.length()) {
      m.region(pos, t.length());
      if (!m.lookingAt()) {
        throw new IllegalArgumentException("Invalid duration: " + text);
      }
      millis += Double.parseDouble(m.group(1)) * unitMillis(m.group(2), text);
      pos = m.end();
    }
    return Duration.ofMillis(Math.round(millis));
  }

  private static double unitMillis(String unit, String text) {
    switch (unit) {
      case "ms":
        return 1.0;
      case "":
      case "s":
      case "sec":
        return 1000.0;
      case "m":
      case "min":
        return 60_000.0;
      case "h":
        return 3_600_000.0;
      case "d":
        return 86_400_000.0;
      default:
        throw new IllegalArgumentException("Unknown duration unit in " + text);
    }
  }

  /**
   * Formats a duration compactly, such as 1m30s, 1.5s, or 250ms, to the millisecond, so that
   * {@link #parse} of the result returns the same duration.
   */
  public static String format(Duration d) {
    long ms = d.toMillis();
    if (ms < 1000) {
      return ms + "ms";
    }
    StringBuilder sb = new StringBuilder();
    long h = ms / 3_600_000;
    long m = (ms / 60_000) % 60;
    long secMillis = ms % 60_000;
    if (h > 0) {
      sb.append(h).append('h');
    }
    if (m > 0) {
      sb.append(m).append('m');
    }
    if (secMillis > 0 || sb.length() == 0) {
      sb.append(secMillis / 1000);
      long frac = secMillis % 1000;
      if (frac != 0) {
        String digits = String.format(Locale.ROOT, "%03d", frac);
        int len = digits.length();
        while (digits.charAt(len - 1) == '0') {
          len--;
        }
        sb.append('.').append(digits, 0, len);
      }
      sb.append('s');
    }
    return sb.toString();
  }
}
