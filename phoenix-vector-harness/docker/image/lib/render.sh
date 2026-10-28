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

# This file renders the configuration directories of one container.
# The entrypoint calls it at each start, so a restart always gets fresh files.
# The output has three directories under the parent of VH_HADOOP_CONF:
#   hadoop  the Hadoop site files, the scheduler file, and the stock Hadoop log and metrics files
#   hbase   the server HBase site file, the HBase env, metrics and stock log files, plus core and hdfs site
#   client  the client HBase site file, plus core, hdfs, mapred and yarn site
#
# Only exported VH_* variables replace placeholders. Other dollar signs stay as they are.
# The render fails if a template uses a VH_* variable that is empty or not exported.
# The render also fails if any placeholder stays in the output.
# The new directories replace the old ones only after all checks pass.
#
# When sourced, the file defines vh_render. When executed, it runs vh_render.
# The caller must source ports.sh and roles.sh first.
# VH_CONF_SRC sets another template directory. The default is the conf directory of the image.

vh_render_log() { echo "render.sh: $*" >&2; }

# Prints the VH_* names that the templates use, one per line.
vh_render_names() {
  local src="$1"
  # The pattern matches a literal placeholder, so the shell must not expand it.
  # shellcheck disable=SC2016
  grep -ho '\${VH_[A-Z0-9_]*}' "$src"/*.tmpl | sed 's/^\${//; s/}$//' | sort -u
}

# Fails if a template variable is empty or not exported.
vh_render_check_vars() {
  local src="$1" name bad=""
  for name in $(vh_render_names "$src"); do
    if [ -z "$(printenv "$name" || true)" ]; then
      bad="$bad $name"
    fi
  done
  if [ -n "$bad" ]; then
    vh_render_log "empty or unexported template variables:$bad"
    return 1
  fi
}

# Renders one template file. The variable list limits envsubst to the exported VH_* names.
vh_render_file() {
  local tmpl="$1" out="$2" vars
  vars="$(compgen -e | grep '^VH_' | sed 's/^/${/; s/$/}/' | tr '\n' ' ')"
  [ -f "$tmpl" ] || { vh_render_log "template $tmpl is missing"; return 1; }
  envsubst "$vars" <"$tmpl" >"$out"
}

# Copies a stock file from a tarball configuration directory.
vh_render_stock() {
  local from="$1" to="$2"
  if [ ! -f "$from" ]; then
    vh_render_log "stock file $from is missing"
    return 1
  fi
  cp "$from" "$to"
}

# Builds the three directories in a work directory.
# The caller runs this function in a condition, where errexit does not apply.
# Each step therefore checks its own result.
vh_render_tree() {
  local src="$1" work="$2" t
  mkdir -p "$work/hadoop" "$work/hbase" "$work/client" || return 1

  for t in core-site hdfs-site yarn-site mapred-site; do
    vh_render_file "$src/$t.xml.tmpl" "$work/hadoop/$t.xml" || return 1
  done
  cp "$src/capacity-scheduler.xml" "$src/hadoop-env.sh" "$work/hadoop/" || return 1
  vh_render_stock "$VH_HADOOP_HOME/etc/hadoop/log4j.properties" \
    "$work/hadoop/log4j.properties" || return 1
  # The HTTP request log writes one console line for each health check. It logs only problems.
  printf '\n%s\n' 'log4j.logger.http.requests=WARN' >>"$work/hadoop/log4j.properties" || return 1
  vh_render_stock "$VH_HADOOP_HOME/etc/hadoop/hadoop-metrics2.properties" \
    "$work/hadoop/hadoop-metrics2.properties" || return 1

  vh_render_file "$src/hbase-site.xml.tmpl" "$work/hbase/hbase-site.xml" || return 1
  cp "$work/hadoop/core-site.xml" "$work/hadoop/hdfs-site.xml" "$work/hbase/" || return 1
  cp "$src/hbase-env.sh" "$src/hadoop-metrics2-hbase.properties" "$work/hbase/" || return 1
  vh_render_stock "$VH_HBASE_HOME/conf/log4j2.properties" \
    "$work/hbase/log4j2.properties" || return 1
  # The stock pattern keeps only the last 1000 characters of a message. That cuts the start of
  # long messages, so the daemons log the full message.
  sed 's/%\.1000m/%m/g' "$work/hbase/log4j2.properties" >"$work/hbase/log4j2.properties.tmp" &&
    mv "$work/hbase/log4j2.properties.tmp" "$work/hbase/log4j2.properties" || return 1

  vh_render_file "$src/hbase-client-site.xml.tmpl" "$work/client/hbase-site.xml" || return 1
  for t in core-site hdfs-site mapred-site yarn-site; do
    cp "$work/hadoop/$t.xml" "$work/client/" || return 1
  done
}

# Fails if a placeholder stays in the output.
vh_render_check_output() {
  local work="$1" left
  # The pattern matches a literal placeholder, so the shell must not expand it.
  # shellcheck disable=SC2016
  left="$(grep -rn '\${VH_' "$work" || true)"
  if [ -n "$left" ]; then
    vh_render_log "unrendered placeholders:"
    echo "$left" >&2
    return 1
  fi
}

# Replaces each output directory with its new version.
vh_render_install() {
  local work="$1" root="$2" d
  for d in hadoop hbase client; do
    rm -rf "${root:?}/.$d.old" || return 1
    if [ -e "$root/$d" ]; then
      mv "$root/$d" "$root/.$d.old" || return 1
    fi
    mv "$work/$d" "$root/$d" || return 1
    rm -rf "${root:?}/.$d.old"
  done
}

vh_render() {
  local src="${VH_CONF_SRC:-${VH_HOME:?render.sh needs roles.sh}/conf}"
  local root rc=0 work
  root="$(dirname "${VH_HADOOP_CONF:?render.sh needs roles.sh}")"
  if [ "$root/client" != "$VH_CLIENT_CONF" ] || [ "$root/hbase" != "$VH_HBASE_CONF" ]; then
    vh_render_log "VH_HADOOP_CONF, VH_HBASE_CONF and VH_CLIENT_CONF must share one parent"
    return 1
  fi
  vh_render_check_vars "$src" || return 1
  mkdir -p "$root"
  work="$(mktemp -d "$root/.render.XXXXXX")" || return 1
  if ! vh_render_tree "$src" "$work" || ! vh_render_check_output "$work" \
    || ! vh_render_install "$work" "$root"; then
    rc=1
  fi
  rm -rf "$work"
  if [ "$rc" -eq 0 ]; then
    vh_render_log "rendered $root/{hadoop,hbase,client} for $VH_INST"
  fi
  return "$rc"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  set -euo pipefail
  vh_render "$@"
fi
