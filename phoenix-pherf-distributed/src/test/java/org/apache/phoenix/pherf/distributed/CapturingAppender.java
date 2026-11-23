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
package org.apache.phoenix.pherf.distributed;

import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

/**
 * A Log4j appender attached to the root logger that retains each emitted log event in memory.
 */
public final class CapturingAppender extends AbstractAppender {
  private final List<LogEvent> events = new ArrayList<>();

  private CapturingAppender() {
    super("capture", null, null, true, Property.EMPTY_ARRAY);
  }

  public static CapturingAppender attach() {
    CapturingAppender appender = new CapturingAppender();
    appender.start();
    LoggerContext context = (LoggerContext) LogManager.getContext(false);
    Configuration configuration = context.getConfiguration();
    configuration.addAppender(appender);
    configuration.getRootLogger().addAppender(appender, null, null);
    context.updateLoggers();
    return appender;
  }

  public void detach() {
    LoggerContext context = (LoggerContext) LogManager.getContext(false);
    context.getConfiguration().getRootLogger().removeAppender(getName());
    context.updateLoggers();
    stop();
  }

  @Override
  public synchronized void append(LogEvent event) {
    events.add(event.toImmutable());
  }

  /**
   * Returns the captured events from all loggers whose names start with the specified prefix.
   */
  public synchronized List<LogEvent> events(String loggerPrefix) {
    List<LogEvent> matching = new ArrayList<>();
    for (LogEvent event : events) {
      if (event.getLoggerName().startsWith(loggerPrefix)) {
        matching.add(event);
      }
    }
    return matching;
  }
}
