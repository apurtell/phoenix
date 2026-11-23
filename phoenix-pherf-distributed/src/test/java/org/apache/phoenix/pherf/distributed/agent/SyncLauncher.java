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
package org.apache.phoenix.pherf.distributed.agent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Decorates a process launcher so that each launch call returns only after the child process exit
 * listener has completed execution. This synchronous behavior allows virtual-clock tests to observe
 * each real child process execution as a single discrete step, using a bounded process wait rather
 * than a thread sleep.
 */
public final class SyncLauncher implements Launcher {
  private static final long TIMEOUT_MS = 120000;
  private final Launcher delegate;
  private final List<List<String>> commands = new ArrayList<>();

  public SyncLauncher(Launcher delegate) {
    this.delegate = delegate;
  }

  @Override
  public Handle launch(List<String> args, File workDir, ExitListener listener) throws IOException {
    synchronized (commands) {
      commands.add(new ArrayList<>(args));
    }
    CountDownLatch exited = new CountDownLatch(1);
    Handle handle = delegate.launch(args, workDir, (status, lines) -> {
      try {
        listener.exited(status, lines);
      } finally {
        exited.countDown();
      }
    });
    try {
      if (!exited.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        handle.destroyForcibly();
        throw new IOException("the child did not exit in " + TIMEOUT_MS + " ms: " + args);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
    return handle;
  }

  /** Returns the command-line argument lists recorded for each launch in invocation order. */
  public List<List<String>> commands() {
    synchronized (commands) {
      return Collections.unmodifiableList(new ArrayList<>(commands));
    }
  }
}
