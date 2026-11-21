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

-- The schema and the data of the functional suite: a salted table with an
-- explicit UPDATE_CACHE_FREQUENCY, which prepare reads back into the
-- manifest, and a covered global index. The psql driver runs this file in
-- prepare, and restore drops the table and its index again.

CREATE TABLE IF NOT EXISTS PX_FUNCTIONAL.EVENTS (
  ID BIGINT NOT NULL PRIMARY KEY,
  KIND VARCHAR,
  AMOUNT INTEGER
) SALT_BUCKETS=4, UPDATE_CACHE_FREQUENCY=60000;
CREATE INDEX IF NOT EXISTS EVENTS_KIND ON PX_FUNCTIONAL.EVENTS (KIND) INCLUDE (AMOUNT);
UPSERT INTO PX_FUNCTIONAL.EVENTS VALUES (1, 'open', 10);
UPSERT INTO PX_FUNCTIONAL.EVENTS VALUES (2, 'open', 20);
UPSERT INTO PX_FUNCTIONAL.EVENTS VALUES (3, 'close', 30);
UPSERT INTO PX_FUNCTIONAL.EVENTS VALUES (4, 'close', 40);
UPSERT INTO PX_FUNCTIONAL.EVENTS VALUES (5, 'open', 50);
UPSERT INTO PX_FUNCTIONAL.EVENTS VALUES (6, 'move', 60);
UPSERT INTO PX_FUNCTIONAL.EVENTS VALUES (7, 'move', 70);
UPSERT INTO PX_FUNCTIONAL.EVENTS VALUES (8, 'open', 80);
