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
package org.apache.phoenix.pherf.distributed.clock;

import java.util.PriorityQueue;

/**
 * Provides a deterministic clock implementation whose time advances only when explicitly driven by
 * a test. Timers that become due execute synchronously on the calling thread, ordered first by
 * their target execution time and then by the order of their schedule calls. No background threads
 * execute and no real time elapses.
 */
public final class VirtualClock implements Clock {
  private final PriorityQueue<Task> tasks = new PriorityQueue<>();
  private long now;
  private long sequence;

  public VirtualClock(long startMs) {
    this.now = startMs;
  }

  @Override
  public long millis() {
    return now;
  }

  /** Returns the same virtual time as reported by millis(). */
  @Override
  public long monotonicMs() {
    return now;
  }

  @Override
  public Timer schedule(long delayMs, Runnable action) {
    Task task = new Task(now + Math.max(0, delayMs), sequence++, action);
    tasks.add(task);
    return task;
  }

  /**
   * Returns the scheduled execution time of the next upcoming timer, or null if no timer is
   * currently armed.
   */
  public Long nextDue() {
    Task head = tasks.peek();
    return head == null ? null : head.dueMs;
  }

  /**
   * Advances the virtual clock forward by the specified number of milliseconds and executes every
   * timer that becomes due.
   */
  public void advance(long ms) {
    advanceTo(now + ms);
  }

  /**
   * Advances the virtual clock to the specified target timestamp and executes every timer that
   * becomes due.
   */
  public void advanceTo(long timeMs) {
    if (timeMs < now) {
      throw new IllegalArgumentException("time goes back: " + timeMs + " < " + now);
    }
    Task head;
    while ((head = tasks.peek()) != null && head.dueMs <= timeMs) {
      tasks.poll();
      now = head.dueMs;
      head.action.run();
    }
    now = timeMs;
  }

  private final class Task implements Timer, Comparable<Task> {
    private final long dueMs;
    private final long order;
    private final Runnable action;

    Task(long dueMs, long order, Runnable action) {
      this.dueMs = dueMs;
      this.order = order;
      this.action = action;
    }

    @Override
    public void cancel() {
      tasks.remove(this);
    }

    @Override
    public int compareTo(Task other) {
      int byTime = Long.compare(dueMs, other.dueMs);
      return byTime != 0 ? byTime : Long.compare(order, other.order);
    }
  }
}
