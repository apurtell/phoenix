#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# HBase sources this file for each command.
# The entrypoint sets the role environment before the daemon starts, so this file sets little.
# The values here also apply to "docker exec" commands, which do not have the role environment.

# Hadoop jars come only from the HBase lib directory, so the Hadoop versions never mix.
export HBASE_DISABLE_HADOOP_CLASSPATH_LOOKUP=true
# ZooKeeper runs as its own container.
export HBASE_MANAGES_ZK=false
: "${JAVA_HOME:=/opt/jdk21}"
export JAVA_HOME
