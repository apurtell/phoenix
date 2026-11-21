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
import org.apache.phoenix.pherf.result.DataModelResult;

/**
 * Extension interface that contributes custom key-value entries to the Pherf execution report.
 * Pherf serializes each entry into the XML report of a data model as NAME.KEY under the sections
 * element.
 * <p>
 * Implementations are registered on the class path via
 * META-INF/services/org.apache.phoenix.pherf.spi.ReportSection, and Pherf queries all discovered
 * implementations in class path order. When no implementation is registered, the generated report
 * remains unchanged.
 */
public interface ReportSection {
  /** Returns the prefix applied to all entry keys contributed by this report section. */
  String getName();

  /**
   * Returns the key-value entries to include in a single write of the report. Implementations must
   * not mutate the provided result object.
   */
  Map<String, String> getEntries(DataModelResult result);
}
