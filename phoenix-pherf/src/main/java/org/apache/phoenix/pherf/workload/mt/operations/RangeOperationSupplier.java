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
package org.apache.phoenix.pherf.workload.mt.operations;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.Query;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.TenantGroup;
import org.apache.phoenix.pherf.latency.Micros;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.pherf.workload.mt.generators.TenantOperationInfo;
import org.apache.phoenix.util.EnvironmentEdgeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.phoenix.thirdparty.com.google.common.base.Function;
import org.apache.phoenix.thirdparty.com.google.common.base.Preconditions;

/**
 * Supplies an execution {@link Function} that processes a SCAN or DELETE {@link QueryOperation}.
 * Statement placeholders are bound using the scenario data generation rules; because both bounds of
 * a range statement receive the same generated rule value, the statement reads or deletes the rows
 * matching that single value. A SCAN operation returns the count of rows read, whereas a DELETE
 * operation commits its transaction and returns the count of rows removed. Connection resolution
 * follows standard query operation semantics, using a global connection for the global tenant group
 * or a global query, and a tenant-specific connection otherwise.
 */
public class RangeOperationSupplier extends BaseOperationSupplier {
  private static final Logger LOGGER = LoggerFactory.getLogger(RangeOperationSupplier.class);

  public RangeOperationSupplier(PhoenixUtil phoenixUtil, DataModel model, Scenario scenario) {
    super(phoenixUtil, model, scenario);
  }

  @Override
  public Function<TenantOperationInfo, OperationStats> get() {
    return new Function<TenantOperationInfo, OperationStats>() {

      @Override
      public OperationStats apply(final TenantOperationInfo input) {
        Preconditions.checkNotNull(input);
        final Query query = ((QueryOperation) input.getOperation()).getQuery();
        final String tenantGroup = input.getTenantGroupId();
        final boolean isTenantGroupGlobal =
          (tenantGroup.compareTo(TenantGroup.DEFAULT_GLOBAL_ID) == 0);
        final String tenantId =
          isTenantGroupGlobal || query.isUseGlobalConnection() ? null : input.getTenantId();
        final String opName = String.format("%s:%s:%s:%s:%s", input.getScenarioName(),
          input.getTableName(), input.getOperationGroupId(), tenantGroup, input.getTenantId());

        long startTime = 0;
        int status = 0;
        long rows = 0;
        long elapsedMicros = 0;
        try (Connection connection = phoenixUtil.getConnection(tenantId)) {
          String sql;
          // Synchronize on the shared rules applier because it is not thread-safe across handlers.
          synchronized (rulesApplier) {
            sql = query.getDynamicStatement(rulesApplier, scenario);
          }
          LOGGER.debug("Operation " + opName + " executing " + sql);
          startTime = EnvironmentEdgeManager.currentTimeMillis();
          long startMicros = Micros.now();
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (input.getOperation().getType() == Operation.OperationType.DELETE) {
              rows = statement.executeUpdate();
              connection.commit();
            } else {
              try (ResultSet rs = statement.executeQuery()) {
                boolean isSelectCountStatement = sql.toUpperCase().contains("COUNT(");
                Pair<Long, Long> r =
                  phoenixUtil.getResults(query, rs, opName, isSelectCountStatement, startTime);
                rows = r.getFirst();
              }
            }
            elapsedMicros = Micros.now() - startMicros;
          }
        } catch (Exception e) {
          LOGGER.error("Operation " + opName + " failed with exception ", e);
          status = -1;
        }
        return new OperationStats(input, startTime, status, rows, elapsedMicros);
      }
    };
  }
}
