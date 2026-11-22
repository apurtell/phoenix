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
package org.apache.phoenix.pherf.run;

import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.phoenix.pherf.Pherf;
import org.apache.phoenix.pherf.PherfConstants;

/**
 * Launches a child JVM that executes {@link ChildRun} with a clean static state and an isolated
 * class path. An overlay directory is prepended to the class path to supply the shipped
 * pherf.properties configuration alongside the test's service registrations. The designated root
 * directory houses the overlay, the child's working directory, its record files, and its captured
 * console output.
 */
public final class ChildJvm {
  private static final long TIMEOUT_SECONDS = 180;
  private static final int TAIL_LINES = 60;
  private final File root;
  private final List<String> systemProperties = new ArrayList<>();
  private final List<String> pherfProperties = new ArrayList<>();

  public ChildJvm(File root) {
    this.root = root;
  }

  /** Returns the build output directory for this module. */
  public static File targetDir() {
    try {
      File classes =
        new File(PherfConstants.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      return classes.getParentFile();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Returns the source directory containing the scenario test fixtures. */
  public static File scenarioDir() {
    return new File(targetDir().getParentFile(), "src/test/resources/scenario");
  }

  /** Returns the source directory containing the schema test fixtures. */
  public static File datamodelDir() {
    return new File(targetDir().getParentFile(), "src/test/resources/datamodel");
  }

  public File work() {
    return new File(root, "work");
  }

  public File records() {
    return new File(root, "records");
  }

  public File record(String name) {
    return new File(records(), name);
  }

  private File overlay() {
    return new File(root, "overlay");
  }

  /** Registers service provider implementations on the child JVM's class path. */
  public void register(Class<?> service, Class<?>... implementations) throws IOException {
    File dir = new File(overlay(), "META-INF/services");
    Files.createDirectories(dir.toPath());
    List<String> names = new ArrayList<>();
    for (Class<?> implementation : implementations) {
      names.add(implementation.getName());
    }
    Files.write(new File(dir, service.getName()).toPath(), names, StandardCharsets.UTF_8);
  }

  /** Configures a system property to be set in the child JVM. */
  public void systemProperty(String name, String value) {
    systemProperties.add("-D" + name + "=" + value);
  }

  /** Appends a configuration key and value to the child JVM's copy of pherf.properties. */
  public void pherfProperty(String key, String value) {
    pherfProperties.add(key + "=" + value);
  }

  /** Executes Pherf once for each provided set of arguments. */
  public void runCli(List<List<String>> invocations) throws Exception {
    List<String> args = new ArrayList<>();
    args.add("cli");
    for (List<String> invocation : invocations) {
      if (args.size() > 1) {
        args.add(ChildRun.NEXT);
      }
      args.addAll(invocation);
    }
    run(args);
  }

  /** Loads and executes each specified scenario fixture file in the child JVM. */
  public void runFixtures(List<String> paths) throws Exception {
    List<String> args = new ArrayList<>();
    args.add("fixtures");
    args.addAll(paths);
    run(args);
  }

  /**
   * Executes Pherf.main once in the child JVM without a fake driver and using the real clock,
   * returning the resulting process exit code. The console output produced by the child process is
   * written to {@link #out()}.
   */
  public int runMain(List<String> args) throws Exception {
    return start(Pherf.class.getName(), args);
  }

  /** Returns the file containing the console output of the most recently executed child JVM. */
  public File out() {
    return new File(root, "child.out");
  }

  private void run(List<String> args) throws Exception {
    File out = new File(root, "child.out");
    File err = new File(root, "child.err");
    int exitCode = start(ChildRun.class.getName(), args);
    if (exitCode != 0) {
      fail("Child JVM exit code " + exitCode + "\n" + Records.read(record(Records.OUTCOME)) + "\n"
        + tails(out, err));
    }
  }

  private int start(String mainClass, List<String> args) throws Exception {
    for (File dir : new File[] { work(), records(), overlay(), new File(root, "tmp") }) {
      Files.createDirectories(dir.toPath());
    }
    Files.copy(new File(targetDir(), "classes/" + PherfConstants.PHERF_PROPERTIES).toPath(),
      new File(overlay(), PherfConstants.PHERF_PROPERTIES).toPath(),
      StandardCopyOption.REPLACE_EXISTING);
    if (!pherfProperties.isEmpty()) {
      Files.write(new File(overlay(), PherfConstants.PHERF_PROPERTIES).toPath(),
        ("\n" + String.join("\n", pherfProperties) + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.APPEND);
    }

    List<String> command = new ArrayList<>();
    command.add(new File(System.getProperty("java.home"), "bin/java").getPath());
    for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
      if (!arg.contains("jdwp") && !arg.startsWith("-Djava.io.tmpdir=")) {
        command.add(arg);
      }
    }
    command.add("-Djava.io.tmpdir=" + new File(root, "tmp").getPath());
    command.add("-D" + Records.DIR_PROPERTY + "=" + records().getPath());
    command.addAll(systemProperties);
    command.add("-cp");
    command.add(overlay().getPath() + File.pathSeparator
      + System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
    command.add(mainClass);
    command.addAll(args);

    File out = new File(root, "child.out");
    File err = new File(root, "child.err");
    Process process =
      new ProcessBuilder(command).directory(work()).redirectOutput(out).redirectError(err).start();
    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly().waitFor();
      fail("Child JVM did not finish in " + TIMEOUT_SECONDS + " s\n" + tails(out, err));
    }
    return process.exitValue();
  }

  private static String tails(File... files) throws IOException {
    StringBuilder text = new StringBuilder();
    for (File file : files) {
      List<String> lines = Records.read(file);
      text.append("--- ").append(file.getName()).append('\n');
      for (String line : lines.subList(Math.max(0, lines.size() - TAIL_LINES), lines.size())) {
        text.append(line).append('\n');
      }
    }
    return text.toString();
  }

  /** Constructs a mutable argument list from the specified strings. */
  public static List<String> args(String... args) {
    return new ArrayList<>(Arrays.asList(args));
  }
}
