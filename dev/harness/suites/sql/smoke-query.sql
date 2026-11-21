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

-- The measured statements of the smoke suite: a full count, a point query
-- through the global index, a write and its delete, and a range count.
-- sql/smoke-schema.sql fixes the row counts.

SELECT COUNT(*) FROM PX_SMOKE.ITEMS;
SELECT ID, QTY FROM PX_SMOKE.ITEMS WHERE NAME = 'gamma';
UPSERT INTO PX_SMOKE.ITEMS VALUES (7, 'eta', 70);
DELETE FROM PX_SMOKE.ITEMS WHERE ID = 7;
SELECT COUNT(*) FROM PX_SMOKE.ITEMS WHERE QTY > 20;
