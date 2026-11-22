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
package org.apache.phoenix.pherf.verdict;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Encapsulates the evaluation thresholds and input parameters for the health verdict, loaded from
 * the pherf.verdict.* keys in pherf.properties. When a configuration key is absent or empty, its
 * default value is applied; when a value is invalid, an error is raised that names the offending
 * key. The full contract is specified in phoenix-pherf/VERDICT.md.
 */
public final class VerdictSettings {
  public static final String PREFIX = "pherf.verdict.";
  public static final String MIN_PCT = PREFIX + "attainment.minPct";
  public static final String LAG_MAX_MS = PREFIX + "lag.maxMs";
  public static final String P99_MAX_MS = PREFIX + "tail.p99MaxMs";
  public static final String P999_MAX_MS = PREFIX + "tail.p999MaxMs";
  public static final String READ_SKEW_MAX = PREFIX + "readSkew.max";
  public static final String NOISY_HOST = PREFIX + "noisyHost";
  public static final String SERVER_JMX = PREFIX + "serverJmx";

  private final double attainmentMinPct;
  private final long lagMaxUs;
  private final long p99MaxUs;
  private final long p999MaxUs;
  private final double readSkewMax;
  private final boolean noisyHost;
  private final List<String> serverJmx;

  private VerdictSettings(Properties properties) {
    BigDecimal minPct = decimal(properties, MIN_PCT, "90");
    if (minPct.signum() <= 0 || minPct.compareTo(BigDecimal.valueOf(100)) > 0) {
      throw bad(MIN_PCT, properties, "is not in (0, 100]");
    }
    attainmentMinPct = minPct.doubleValue();
    lagMaxUs = micros(properties, LAG_MAX_MS, "1000", false);
    p99MaxUs = micros(properties, P99_MAX_MS, "1000", true);
    p999MaxUs = micros(properties, P999_MAX_MS, "5000", true);
    BigDecimal skew = decimal(properties, READ_SKEW_MAX, "1.5");
    if (skew.compareTo(BigDecimal.ONE) < 0) {
      throw bad(READ_SKEW_MAX, properties, "is less than 1");
    }
    readSkewMax = skew.doubleValue();
    String noisy = value(properties, NOISY_HOST, "false");
    if (!"true".equalsIgnoreCase(noisy) && !"false".equalsIgnoreCase(noisy)) {
      throw bad(NOISY_HOST, properties, "is not true or false");
    }
    noisyHost = Boolean.parseBoolean(noisy);
    serverJmx = urls(properties);
  }

  /**
   * Parses and constructs the verdict settings from the provided Pherf properties.
   * @throws IllegalArgumentException if any configured value is invalid; the exception message
   *                                  names the offending key.
   */
  public static VerdictSettings of(Properties properties) {
    return new VerdictSettings(properties);
  }

  /** Returns the minimum attainment threshold required to pass, expressed in percent. */
  public double getAttainmentMinPct() {
    return attainmentMinPct;
  }

  /** Returns the maximum peak lag threshold allowed to pass, measured in us. */
  public long getLagMaxUs() {
    return lagMaxUs;
  }

  /** Returns the maximum p99 latency threshold allowed to pass, measured in us. */
  public long getP99MaxUs() {
    return p99MaxUs;
  }

  /** Returns the maximum p99.9 latency threshold allowed to pass, measured in us. */
  public long getP999MaxUs() {
    return p999MaxUs;
  }

  /** Returns the maximum read skew threshold allowed to pass. */
  public double getReadSkewMax() {
    return readSkewMax;
  }

  /** Returns true when the execution harness flags the host environment as noisy. */
  public boolean isNoisyHost() {
    return noisyHost;
  }

  /**
   * Returns the RegionServer info server base URLs in their configured order, or an empty list when
   * no source is configured.
   */
  public List<String> getServerJmx() {
    return serverJmx;
  }

  private static String value(Properties properties, String key, String defaultValue) {
    String value = properties.getProperty(key);
    return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
  }

  private static IllegalArgumentException bad(String key, Properties properties, String why) {
    return new IllegalArgumentException(key + "=" + properties.getProperty(key) + " " + why);
  }

  private static BigDecimal decimal(Properties properties, String key, String defaultValue) {
    try {
      return new BigDecimal(value(properties, key, defaultValue));
    } catch (NumberFormatException e) {
      throw bad(key, properties, "is not a decimal number");
    }
  }

  /**
   * Converts a threshold bound from ms to us and truncates any fractional portion below 1 us. This
   * ensures that an integer measurement in us exceeds the converted result only when it strictly
   * exceeds the original bound in ms.
   */
  private static long micros(Properties properties, String key, String defaultValue,
    boolean positive) {
    BigDecimal ms = decimal(properties, key, defaultValue);
    if (positive ? ms.signum() <= 0 : ms.signum() < 0) {
      throw bad(key, properties, positive ? "is not above 0" : "is less than 0");
    }
    try {
      return ms.movePointRight(3).setScale(0, RoundingMode.FLOOR).longValueExact();
    } catch (ArithmeticException e) {
      throw bad(key, properties, "is too large");
    }
  }

  private static List<String> urls(Properties properties) {
    String value = value(properties, SERVER_JMX, "");
    if (value.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> urls = new ArrayList<>();
    for (String part : value.split(",", -1)) {
      String url = part.trim();
      URI uri;
      try {
        uri = new URI(url);
      } catch (Exception e) {
        throw bad(SERVER_JMX, properties, "has a URL that is not valid: '" + url + "'");
      }
      if (
        !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          || uri.getHost() == null
      ) {
        throw bad(SERVER_JMX, properties, "has a URL that is not http(s)://host: '" + url + "'");
      }
      if (urls.contains(url)) {
        throw bad(SERVER_JMX, properties, "has the URL '" + url + "' two times");
      }
      urls.add(url);
    }
    return Collections.unmodifiableList(urls);
  }
}
