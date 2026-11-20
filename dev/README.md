<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Developer tools

This directory contains the engineering and operational tooling that supports the project alongside
the Maven build, covering pre-commit validation, release preparation, code coverage analysis, IDE
configuration, and the cluster and performance test harness. None of the contents are packaged into
a release artifact, and no Java code resides here: because no Maven module encompasses `dev/`, any
Java file placed beneath this directory would remain uncompiled source.

## Test and performance harness

* `harness/` — Provides the cluster and performance test harness. Within this framework,
  `px-cluster` provisions a multi-role Phoenix cluster, `px-suite` and `px-run` execute test suites
  and individual tests, and the measurement toolkit gathers runtime telemetry. Because every
  command interacts with the cluster through a single unified substrate contract, test suites run
  without modification across every backend: Docker Compose today, and Kubernetes once its backend
  is introduced. Direct invocation of a container runtime is restricted exclusively to the backend.
  Begin with `harness/README.md` for an architectural overview, and consult
  `harness/docs/backends.md` for the substrate contract reference and the specification of the
  rendered deployment directory.
* `code-coverage/run-coverage.sh` — Executes the Maven build under the `coverage` profile,
  aggregates the resulting JaCoCo coverage report, and optionally publishes the analysis to a
  SonarQube server. Refer to `code-coverage/README.md` for usage details.

## Pre-commit checking

* `test-patch.sh` — Executes the pre-commit verification pipeline against a candidate patch. Its
  operational parameters reside in `test-patch.properties`, which enforces constraints such as the
  100 column line length limit and a zero-tolerance policy for release-audit warnings.
* `smart-apply-patch.sh` — Resolves and applies a patch sourced from a JIRA attachment, a remote
  URL, or standard input.
* `phoenix-personality.sh` — Defines the Apache Yetus personality for Phoenix, configuring the
  active plug-ins, the target module set, and the build flags that govern Yetus execution.
* `jenkins_precommit_jira_yetus.sh`, `jenkins_precommit_github_yetus.sh` — Serve as the two CI
  entry points into Apache Yetus, handling patches attached to JIRA issues and changes submitted as
  GitHub pull requests respectively.
* `jenkinsEnv.sh` — Exports the toolchain paths required by a Jenkins build agent.
* `docker/Dockerfile.yetus`, `docker/Dockerfile.multibranch` — Define the container images that
  provide the build environments for pre-commit verification and multibranch pipeline jobs.
* `gather_machine_environment.sh` — Captures installed software versions and host state on a build
  machine so that failure reports preserve a complete record of the environment that produced them.
* `rebuild_hbase.sh` — Rebuilds HBase locally against the Hadoop 3 profile to satisfy build
  configurations that require a locally installed HBase snapshot before Phoenix can compile.
* `cache-apache-project-artifact.sh` — Retrieves and caches an upstream Apache artifact for reuse
  across builds.

## Release preparation

* `create-release/` — Houses the automated release pipeline. `do-release-docker.sh` serves as the
  primary entry point and executes the workflow inside a container, while `do-release.sh`,
  `release-build.sh`, and `release-util.sh` implement the tag, publish-dist, and publish-release
  stages. Refer to `create-release/README.txt` for details.
* `make_rc.sh` — Builds a release candidate directly from a local working tree.
* `phoenix-vote.sh` — Validates a release candidate by verifying cryptographic checksums and
  signatures, running the release audit, building from source, and executing the unit test suite.
* `release_files/LICENSE`, `release_files/NOTICE` — Provide the license and notice documents
  bundled into a binary distribution.
* `misc_utils/git_jira_fix_version_check.py` — Audits the git commit history against the JIRA fix
  version metadata for a target release. Refer to `misc_utils/README.md` for usage instructions.

## Code style and IDE settings

* `PhoenixCodeTemplate.xml` — Defines the Eclipse code formatter profile enforced during the build
  by `spotless:apply`.
* `phoenix.importorder` — Specifies the package import ordering enforced by the code formatter.
* `eclipse_prefs_phoenix.epf` — Provides the baseline Eclipse workspace preferences for Phoenix
  development.
