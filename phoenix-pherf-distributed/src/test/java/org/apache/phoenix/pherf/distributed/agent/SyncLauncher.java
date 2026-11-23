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
 * Decorates a process launcher so that each launch invocation returns only after the child process
 * has either armed at its start gate or completed its exit listener callback. Sending a line to an
 * armed child process likewise blocks until its exit listener has completed. This synchronous
 * behavior allows virtual-clock tests to observe each real child process execution as one or two
 * discrete steps, using bounded process waits rather than thread sleeps.
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
    CountDownLatch armedOrExited = new CountDownLatch(1);
    CountDownLatch exited = new CountDownLatch(1);
    Handle handle = delegate.launch(args, workDir, new ExitListener() {
      @Override
      public void armed() {
        listener.armed();
        armedOrExited.countDown();
      }

      @Override
      public void started(long startedMs) {
        listener.started(startedMs);
      }

      @Override
      public void exited(int status, List<String> lines) {
        try {
          listener.exited(status, lines);
        } finally {
          exited.countDown();
          armedOrExited.countDown();
        }
      }
    });
    await(armedOrExited, handle, args);
    return new Handle() {
      @Override
      public void send(String line) throws IOException {
        handle.send(line);
        await(exited, handle, args);
      }

      @Override
      public void destroy() {
        handle.destroy();
      }

      @Override
      public void destroyForcibly() {
        handle.destroyForcibly();
      }
    };
  }

  private static void await(CountDownLatch latch, Handle handle, List<String> args)
    throws IOException {
    try {
      if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        handle.destroyForcibly();
        throw new IOException(
          "the child did not reach its next step in " + TIMEOUT_MS + " ms: " + args);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  /** Returns the command-line argument lists recorded for each launch in invocation order. */
  public List<List<String>> commands() {
    synchronized (commands) {
      return Collections.unmodifiableList(new ArrayList<>(commands));
    }
  }
}
