/*
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/
/*
  The schema of the B side of fleet-ab.suite. It is px_fleet_schema.sql with
  one change, the factor of the A/B: UPDATE_CACHE_FREQUENCY is 0 (ALWAYS), so
  each statement of the client reads the table metadata from
  SYSTEM.CATALOG again. Pherf joins the lines of this file into one
  statement, so the file holds one statement and block comments only. The
  pherf-distributed driver drops the table in restore.
*/
CREATE TABLE IF NOT EXISTS PX_PHERF.FLEET (
  K1 BIGINT NOT NULL PRIMARY KEY,
  NAME VARCHAR,
  QTY INTEGER
) UPDATE_CACHE_FREQUENCY=0
