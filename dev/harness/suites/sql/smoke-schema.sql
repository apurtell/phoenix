-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  See the NOTICE file distributed with
-- this work for additional information regarding copyright ownership.
-- The ASF licenses this file to You under the Apache License, Version 2.0
-- (the "License"); you may not use this file except in compliance with
-- the License.  You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- The schema and the data of the smoke suite. The psql driver runs this
-- file in prepare, and restore drops the table and its index again.

CREATE TABLE IF NOT EXISTS PX_SMOKE.ITEMS (
  ID BIGINT NOT NULL PRIMARY KEY,
  NAME VARCHAR,
  QTY INTEGER
);
CREATE INDEX IF NOT EXISTS ITEMS_NAME ON PX_SMOKE.ITEMS (NAME) INCLUDE (QTY);
UPSERT INTO PX_SMOKE.ITEMS VALUES (1, 'alpha', 10);
UPSERT INTO PX_SMOKE.ITEMS VALUES (2, 'beta', 20);
UPSERT INTO PX_SMOKE.ITEMS VALUES (3, 'gamma', 30);
UPSERT INTO PX_SMOKE.ITEMS VALUES (4, 'delta', 40);
UPSERT INTO PX_SMOKE.ITEMS VALUES (5, 'epsilon', 50);
UPSERT INTO PX_SMOKE.ITEMS VALUES (6, 'zeta', 60);
