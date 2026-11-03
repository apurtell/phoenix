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
# shellcheck shell=bash

# This file holds the helpers of the suite runner, the harness wrapper and the run checks.
# It needs no Docker CLI, so the run checks also work on a copy of the output tree.
# The file runs on macOS bash 3.2 and on bash 5.

if [ -n "${VHS_COMMON_LOADED:-}" ]; then
  return 0
fi
VHS_COMMON_LOADED=1

# Prints the number of seconds in a duration such as 90, 90s, 6m, 1h or 1m30s.
vhs_duration_s() {
  local s="$1" total=0 num="" c
  case "$s" in
    '' | *[!0-9hms]*) return 1 ;;
  esac
  case "$s" in
    *[0-9]) s="${s}s" ;;
  esac
  while [ -n "$s" ]; do
    c="${s%"${s#?}"}"
    s="${s#?}"
    case "$c" in
      [0-9]) num="$num$c" ;;
      h | m | s)
        [ -n "$num" ] || return 1
        case "$c" in
          h) total=$((total + num * 3600)) ;;
          m) total=$((total + num * 60)) ;;
          s) total=$((total + num)) ;;
        esac
        num=""
        ;;
    esac
  done
  echo "$total"
}

# Prints the number of MiB in a JVM size such as 6g, 512m or 2048k.
vhs_size_mib() {
  local s="$1" n
  n="${s%[gGmMkK]}"
  case "$n" in
    '' | *[!0-9]*) return 1 ;;
  esac
  case "$s" in
    *[gG]) echo $((n * 1024)) ;;
    *[mM]) echo "$n" ;;
    *[kK]) echo $(((n + 1023) / 1024)) ;;
    *) echo $(((n + 1048575) / 1048576)) ;;
  esac
}

# Prints a string as a run id: lower case, and only letters, digits, dot, dash and underscore.
vhs_run_id() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9._-' '_'
}

# Prints a string as a Phoenix identifier in the form that the harness uses for table names.
vhs_ident() {
  printf '%s' "$1" | tr '[:lower:]' '[:upper:]' | tr -c 'A-Z0-9' '_'
}

# Prints the last value of a key in a properties file. Prints nothing if the key is absent.
vhs_prop() {
  local file="$1" key="$2"
  [ -f "$file" ] || return 0
  awk -v k="$key" '
    /^[ \t]*[#!]/ { next }
    {
      line = $0
      sub(/^[ \t]+/, "", line)
      p = index(line, "=")
      if (p == 0) next
      name = substr(line, 1, p - 1)
      sub(/[ \t]+$/, "", name)
      if (name != k) next
      v = substr(line, p + 1)
      sub(/^[ \t]+/, "", v)
      sub(/[ \t\r]+$/, "", v)
      found = v
      have = 1
    }
    END { if (have) print found }
  ' "$file"
}

# Prints the lines of an argument file without comments and blank lines.
vhs_args_lines() {
  awk '{ sub(/\r$/, ""); sub(/^[ \t]+/, ""); sub(/[ \t]+$/, "") } /^#/ || $0 == "" { next } { print }' "$1"
}

# Converts a CSV file of the harness to tab-separated lines.
# The harness quotes a field that holds a comma or a quote, so a field split on commas is not enough.
# A line without quotes takes the fast path. A tab inside a value becomes a space.
vhs_csv() {
  awk '
    function csvsplit(s, out,    n, i, c, f, q, len) {
      delete out
      if (index(s, "\"") == 0) {
        return split(s, out, ",")
      }
      n = 0; f = ""; q = 0; len = length(s)
      for (i = 1; i <= len; i++) {
        c = substr(s, i, 1)
        if (q) {
          if (c == "\"") {
            if (substr(s, i + 1, 1) == "\"") { f = f "\""; i++ } else { q = 0 }
          } else {
            f = f c
          }
        } else if (c == "\"") {
          q = 1
        } else if (c == ",") {
          out[++n] = f; f = ""
        } else {
          f = f c
        }
      }
      out[++n] = f
      return n
    }
    {
      sub(/\r$/, "")
      n = csvsplit($0, a)
      line = ""
      for (i = 1; i <= n; i++) {
        v = a[i]
        gsub(/\t/, " ", v)
        line = (i == 1) ? v : line "\t" v
      }
      print line
    }
  ' "$1"
}

# The awk prologue that maps the header names of a converted CSV file to column numbers.
# shellcheck disable=SC2034,SC2016 # The sourcing scripts use the value. It is awk code, so the shell must not expand it.
VHS_HDR='NR == 1 { for (i = 1; i <= NF; i++) col[$i] = i; next }'

vhs_now_s() { date +%s; }
