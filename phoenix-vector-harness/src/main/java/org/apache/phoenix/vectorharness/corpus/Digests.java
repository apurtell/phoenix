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
package org.apache.phoenix.vectorharness.corpus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/** SHA-256 helpers for corpus fingerprints. */
public final class Digests {

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private Digests() {
  }

  public static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  public static String hex(byte[] bytes) {
    char[] out = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      out[2 * i] = HEX[(bytes[i] >> 4) & 0xf];
      out[2 * i + 1] = HEX[bytes[i] & 0xf];
    }
    return new String(out);
  }

  public static void update(MessageDigest md, String s) {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    update(md, b.length);
    md.update(b);
  }

  public static void update(MessageDigest md, long v) {
    ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    bb.putLong(v);
    md.update(bb.array());
  }

  public static void update(MessageDigest md, float[] v) {
    ByteBuffer bb = ByteBuffer.allocate(4 * v.length + 4).order(ByteOrder.LITTLE_ENDIAN);
    bb.putInt(v.length);
    for (float f : v) {
      bb.putInt(Float.floatToIntBits(f));
    }
    md.update(bb.array());
  }

  public static void update(MessageDigest md, Map<String, String> params) {
    update(md, params.size());
    for (Map.Entry<String, String> e : new java.util.TreeMap<>(params).entrySet()) {
      update(md, e.getKey());
      update(md, e.getValue());
    }
  }

  public static void update(MessageDigest md, DocAttributes a) {
    update(md, a.ordinal());
    update(md, a.tenantId());
    update(md, a.docId());
    update(md, a.source());
    update(md, a.category());
    update(md, a.aclTag());
    update(md, a.createdAtMillis());
    update(md, a.body());
  }

  /** Adds the first {@code limit} bytes of a file to a digest, or the whole file if negative. */
  public static void updateFile(MessageDigest md, Path file, long limit) throws IOException {
    byte[] buf = new byte[1 << 16];
    long remaining = limit < 0 ? Long.MAX_VALUE : limit;
    try (InputStream in = Files.newInputStream(file)) {
      while (remaining > 0) {
        int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
        if (n < 0) {
          break;
        }
        md.update(buf, 0, n);
        remaining -= n;
      }
    }
  }
}
