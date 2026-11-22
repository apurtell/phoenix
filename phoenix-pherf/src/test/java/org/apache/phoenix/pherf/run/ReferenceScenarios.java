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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Defines the reference scenarios located in config/reference alongside the expected operation
 * counts produced by their smoke profiles. Sharing these canonical definitions across both unit
 * tests and the mini cluster test guarantees that their verification expectations remain aligned.
 * <p>
 * Each scenario populates {@link #rows} rows partitioned into blocks of {@link #blockSize} and
 * executes {@link #arrivals} operations across 1 worker: a point lookup reads 1 row, whereas a
 * range scan and a range delete each span one complete block.
 */
public final class ReferenceScenarios {
  public static final String LOOKUP = "lookup";
  public static final String SCAN = "rangeScan";
  public static final String DELETE = "rangeDelete";

  public static final ReferenceScenarios RELATIONAL = new ReferenceScenarios("relational",
    "reference_relational", "referenceRelational", "PHERF.REF_RELATIONAL", 50, 70, 20, 10);
  public static final ReferenceScenarios JSON = new ReferenceScenarios("json", "reference_json",
    "referenceJson", "PHERF.REF_JSON", 40, 30, 18, 12);
  public static final ReferenceScenarios BSON = new ReferenceScenarios("bson", "reference_bson",
    "referenceBson", "PHERF.REF_BSON", 25, 36, 12, 12);
  public static final List<ReferenceScenarios> ALL =
    Collections.unmodifiableList(Arrays.asList(RELATIONAL, JSON, BSON));

  /**
   * Identifies the scenario by its short name, which serves as both the file prefix and the run
   * label.
   */
  public final String name;
  public final String dataModel;
  public final String scenario;
  public final String table;
  public final long rows = 1000;
  public final int blockSize;
  /**
   * Specifies the total number of arrivals in the smoke profile, which equals the integral of its
   * rate curve.
   */
  public final long arrivals;
  public final long lookups;
  public final long scans;
  public final long deletes;
  /**
   * Specifies the number of rows remaining after the smoke run completes, because each delete
   * operation removes one block.
   */
  public final long rowsLeft;

  private ReferenceScenarios(String name, String dataModel, String scenario, String table,
    int blockSize, long lookups, long scans, long deletes) {
    this.name = name;
    this.dataModel = dataModel;
    this.scenario = scenario;
    this.table = table;
    this.blockSize = blockSize;
    this.lookups = lookups;
    this.scans = scans;
    this.deletes = deletes;
    this.arrivals = lookups + scans + deletes;
    this.rowsLeft = rows - deletes * blockSize;
  }

  /** Returns the directory containing the reference configuration files in the source tree. */
  public static File dir() {
    return new File(ChildJvm.targetDir().getParentFile(), "config/reference");
  }

  public File scenarioFile() {
    return new File(dir(), name + "_scenario.xml");
  }

  public File profileFile() {
    return new File(dir(), name + "_smoke_profile.xml");
  }

  /**
   * Returns the expected number of operations executed for the specified query id during the smoke
   * run.
   */
  public long operations(String queryId) {
    return queryId.equals(LOOKUP) ? lookups : queryId.equals(SCAN) ? scans : deletes;
  }

  /**
   * Returns the number of rows that a single operation of the specified query id reads or deletes.
   */
  public long rowsPerOperation(String queryId) {
    return queryId.equals(LOOKUP) ? 1 : blockSize;
  }
}
