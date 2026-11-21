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
package org.apache.phoenix.pherf.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import org.apache.phoenix.pherf.result.DataModelResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers and invokes registered SPI extensions. Each extension type is loaded once per JVM via
 * {@link ServiceLoader} using the current thread context class loader.
 * <p>
 * If a report section or metrics sink throws a RuntimeException, the failure is logged as a warning
 * and the benchmark run continues uninterrupted. Exceptions thrown by the connection provider are
 * propagated directly to the caller. If an extension registration fails to load, every access to
 * that extension interface throws an IllegalStateException.
 */
public final class Extensions {
  private static final Logger LOGGER = LoggerFactory.getLogger(Extensions.class);
  private static final Registry<ConnectionProvider> PROVIDERS =
    new Registry<>(ConnectionProvider.class);
  private static final Registry<ReportSection> SECTIONS = new Registry<>(ReportSection.class);
  private static final Registry<MetricsSink> SINKS = new Registry<>(MetricsSink.class);

  private Extensions() {
  }

  /**
   * Returns the registered connection provider, or {@link ConnectionProvider#DEFAULT} if no custom
   * provider is registered.
   * @throws IllegalStateException if more than one connection provider is registered.
   */
  public static ConnectionProvider connectionProvider() {
    List<ConnectionProvider> providers = PROVIDERS.get();
    if (providers.isEmpty()) {
      return ConnectionProvider.DEFAULT;
    }
    if (providers.size() > 1) {
      List<String> names = new ArrayList<>();
      for (ConnectionProvider provider : providers) {
        names.add(provider.getClass().getName());
      }
      throw new IllegalStateException(
        "More than one " + ConnectionProvider.class.getName() + " is registered: " + names);
    }
    return providers.get(0);
  }

  /**
   * Collects and returns the entries from all registered report sections formatted as NAME.KEY,
   * evaluated in class path order. Returns null when no report section is registered so that the
   * output report structure remains unchanged.
   */
  public static Map<String, String> reportEntries(DataModelResult result) {
    List<ReportSection> sections = SECTIONS.get();
    if (sections.isEmpty()) {
      return null;
    }
    Map<String, String> entries = new LinkedHashMap<>();
    for (ReportSection section : sections) {
      try {
        Map<String, String> sectionEntries = section.getEntries(result);
        if (sectionEntries != null) {
          for (Map.Entry<String, String> entry : sectionEntries.entrySet()) {
            entries.put(section.getName() + "." + entry.getKey(), entry.getValue());
          }
        }
      } catch (RuntimeException e) {
        LOGGER.warn("Report section " + section.getClass().getName() + " failed", e);
      }
    }
    return entries;
  }

  /**
   * Returns true if at least one metrics sink is registered. Callers should check this before
   * constructing metric tag maps so that runs without a registered sink avoid allocating tags.
   */
  public static boolean hasMetricsSinks() {
    return !SINKS.get().isEmpty();
  }

  /** Publishes both KIND.elapsed_ms and KIND.rows metrics to every registered metrics sink. */
  public static void record(String kind, Map<String, String> tags, long elapsedMs, long rows) {
    for (MetricsSink sink : SINKS.get()) {
      try {
        sink.record(kind + ".elapsed_ms", tags, elapsedMs);
        sink.record(kind + ".rows", tags, rows);
      } catch (RuntimeException e) {
        LOGGER.warn("Metrics sink " + sink.getClass().getName() + " failed", e);
      }
    }
  }

  /**
   * Builds a read-only tag map from alternating key and value pairs, omitting any pair that has a
   * null value.
   */
  public static Map<String, String> tags(String... keysAndValues) {
    Map<String, String> tags = new LinkedHashMap<>();
    for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
      if (keysAndValues[i + 1] != null) {
        tags.put(keysAndValues[i], keysAndValues[i + 1]);
      }
    }
    return Collections.unmodifiableMap(tags);
  }

  /**
   * Lazily loads and caches the implementations of a single extension interface on first use. Any
   * loading failure is retained so that subsequent lookups fail consistently without attempting to
   * reload the service providers.
   */
  static final class Registry<T> {
    private final Class<T> type;
    private volatile List<T> loaded;
    private ServiceConfigurationError failure;

    Registry(Class<T> type) {
      this.type = type;
    }

    List<T> get() {
      List<T> list = loaded;
      if (list == null) {
        synchronized (this) {
          list = loaded;
          if (list == null) {
            if (failure == null) {
              try {
                List<T> found = new ArrayList<>();
                for (T implementation : ServiceLoader.load(type)) {
                  found.add(implementation);
                }
                list = Collections.unmodifiableList(found);
                loaded = list;
                return list;
              } catch (ServiceConfigurationError e) {
                failure = e;
              }
            }
            throw new IllegalStateException(
              "Cannot load the registered " + type.getName() + " implementations", failure);
          }
        }
      }
      return list;
    }
  }
}
