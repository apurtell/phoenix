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

import java.util.Map;

/**
 * Consumer interface that receives Pherf execution metrics. Because Pherf does not bundle a
 * built-in metrics backend, each sink implementation is responsible for forwarding recorded values
 * to its own telemetry system.
 * <p>
 * Implementations are registered on the class path via
 * META-INF/services/org.apache.phoenix.pherf.spi.MetricsSink, and Pherf invokes all discovered
 * sinks in class path order. Because metrics are recorded concurrently from multiple worker
 * threads, implementations must be thread-safe.
 * <p>
 * Each reported Pherf execution record emits two distinct metrics, KIND.elapsed_ms and KIND.rows.
 * The supported metric kinds and their associated tags are as follows:
 * <ul>
 * <li>query: represents a single query execution on the closed-loop path, carrying the tags
 * scenario, query, tenant, and outcome (which takes the value ok, timeout, or error).</li>
 * <li>load: represents a single row in the data load summary for either a data load or a scenario
 * DDL execution, carrying the tag table.</li>
 * <li>operation: represents a single multi-tenant operation, carrying the tags scenario, operation,
 * type, tenant, tenant_group, and status.</li>
 * </ul>
 * Any tag without a value is omitted from the tag map.
 */
public interface MetricsSink {
  void record(String name, Map<String, String> tags, long value);
}
