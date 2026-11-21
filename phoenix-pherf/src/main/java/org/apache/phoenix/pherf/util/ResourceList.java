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
package org.apache.phoenix.pherf.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * list resources available from the classpath @ *
 */
public class ResourceList {
  private static final Logger LOGGER = LoggerFactory.getLogger(ResourceList.class);
  private final String rootResourceDir;

  public ResourceList(String rootResourceDir) {
    this.rootResourceDir = rootResourceDir;
  }

  /**
   * Resolves all resources matching the specified pattern. If the pattern directly identifies an
   * existing regular file on the local file system, that single file is returned; otherwise, the
   * search scans the configured resource root across all elements of the classpath.
   */
  public Collection<Path> getResourceList(final String pattern) throws Exception {
    File file = new File(pattern);
    if (file.isFile()) {
      return Collections.singletonList(file.toPath().toAbsolutePath());
    }
    return getResourcesPaths(Pattern.compile(pattern));
  }

  /**
   * Opens a stream for a resource path resolved by {@link #getResourceList(String)}. Paths that
   * reference regular files are read directly from the file system, whereas remaining paths are
   * loaded as classpath resources.
   */
  public static InputStream openResource(Path path) throws IOException {
    if (Files.isRegularFile(path)) {
      return Files.newInputStream(path);
    }
    InputStream in =
      ResourceList.class.getResourceAsStream(path.toString().replace(File.separatorChar, '/'));
    if (in == null) {
      throw new FileNotFoundException("Could not open resource: " + path);
    }
    return in;
  }

  /**
   * Scans the resource root of every classpath element to locate all resources that match the
   * target pattern.
   * @return This method returns the matching resource paths in natural sorted order, containing at
   *         most one entry for each distinct path.
   */
  private Collection<Path> getResourcesPaths(final Pattern pattern) {
    final String classPath = System.getProperty("java.class.path", ".");
    Set<Path> paths = new TreeSet<>();
    for (final String element : classPath.split(Pattern.quote(File.pathSeparator))) {
      if (StringUtils.isBlank(element)) {
        continue;
      }
      final File file = new File(element);
      if (file.isDirectory()) {
        File root = new File(file, rootResourceDir);
        if (root.isDirectory()) {
          paths.addAll(getResourcesFromDirectory(root, getRootPrefix(), pattern));
        }
      } else {
        paths.addAll(getResourcesFromJarFile(file, pattern));
      }
    }
    return paths;
  }

  /**
   * Normalizes the configured resource root directory into the relative prefix format used by JAR
   * archive entries within a classpath element.
   */
  private String getRootPrefix() {
    return StringUtils.removeStart(rootResourceDir, "/") + "/";
  }

  // Visible for testing
  Collection<Path> getResourcesFromJarFile(final File file, final Pattern pattern) {
    final List<Path> retVal = new ArrayList<>();
    final String rootPrefix = getRootPrefix();
    try (ZipFile zf = new ZipFile(file)) {
      final Enumeration<? extends ZipEntry> e = zf.entries();
      while (e.hasMoreElements()) {
        final ZipEntry ze = e.nextElement();
        final String fileName = ze.getName();
        if (ze.isDirectory() || !fileName.startsWith(rootPrefix)) {
          continue;
        }
        final boolean accept = pattern.matcher(fileName).matches();
        LOGGER.trace("fileName:" + fileName);
        LOGGER.trace("File:" + file.toString());
        LOGGER.trace("Match:" + accept);
        if (accept) {
          LOGGER.trace("Adding File from Jar: " + fileName);
          retVal.add(Paths.get("/" + fileName));
        }
      }
    } catch (final IOException e) {
      // Treat missing classpath entries or files that are not valid ZIP archives as containing no
      // discoverable resources.
      LOGGER.debug("Skipping class path element " + file + ": " + e);
      return Collections.emptyList();
    }
    return retVal;
  }

  /**
   * Recursively collects all files beneath a directory that match the given pattern. A candidate
   * file is accepted when the pattern matches either its classpath-relative path, formatted as a
   * JAR entry name, or its absolute file system path.
   */
  private Collection<Path> getResourcesFromDirectory(final File directory, final String name,
    final Pattern pattern) {
    final List<Path> retval = new ArrayList<>();
    final File[] fileList = directory.listFiles();
    if (fileList == null) {
      LOGGER.warn("Could not list directory, skipping: " + directory);
      return retval;
    }
    for (final File file : fileList) {
      final String fileName = name + file.getName();
      if (file.isDirectory()) {
        retval.addAll(getResourcesFromDirectory(file, fileName + "/", pattern));
      } else {
        final boolean accept =
          pattern.matcher(fileName).matches() || pattern.matcher(file.getAbsolutePath()).matches();
        if (accept) {
          LOGGER.debug("Adding File from directory: " + file);
          retval.add(file.toPath().toAbsolutePath());
        }
      }
    }
    return retval;
  }
}
