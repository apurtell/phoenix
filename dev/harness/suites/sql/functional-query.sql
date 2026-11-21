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

-- The measured statements of the functional suite: a count, a point query
-- and an aggregate through the global index, a range scan of the salted
-- table, and a write that a delete undoes. sql/functional-schema.sql fixes
-- the row counts, so a lost write or a stale index shows as a different
-- count.

SELECT COUNT(*) FROM PX_FUNCTIONAL.EVENTS;
SELECT ID, AMOUNT FROM PX_FUNCTIONAL.EVENTS WHERE KIND = 'close';
SELECT KIND, COUNT(*), SUM(AMOUNT) FROM PX_FUNCTIONAL.EVENTS GROUP BY KIND;
SELECT ID FROM PX_FUNCTIONAL.EVENTS WHERE ID BETWEEN 3 AND 6;
UPSERT INTO PX_FUNCTIONAL.EVENTS VALUES (9, 'open', 90);
SELECT COUNT(*) FROM PX_FUNCTIONAL.EVENTS WHERE KIND = 'open';
DELETE FROM PX_FUNCTIONAL.EVENTS WHERE ID = 9;
SELECT COUNT(*) FROM PX_FUNCTIONAL.EVENTS;
