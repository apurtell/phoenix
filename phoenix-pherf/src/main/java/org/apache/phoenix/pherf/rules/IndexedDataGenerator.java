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
package org.apache.phoenix.pherf.rules;

/**
 * Defines a data generator that computes each value as a pure function of the row index and the
 * column seed. When co-generation is active, generator classes implementing this interface produce
 * deterministic index-based values, whereas generator classes that do not implement it retain their
 * own value generation behavior.
 */
public interface IndexedDataGenerator {
  /**
   * Computes the generated data value for a single row.
   * @param index the global row index, which must be 0 or more.
   * @param seed  the deterministic seed for the target column.
   */
  DataValue valueAt(long index, long seed);
}
