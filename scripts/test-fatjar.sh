#!/bin/bash
#
# Runs the unit tests against the packaged fat jar instead of the normal Maven classpath.
# `mvn test` sees every dependency's META-INF/services file; the fat jar keeps only what the assembly merges, so
# packaging bugs (missing Tika detectors/parsers, ...) only show up here. This is what runs on the cluster.
#
#   docker compose run --rm indexer scripts/test-fatjar.sh [TestClass ...]
#
# Runs automatically in the integration-test phase of `mvn verify` (after package). Standalone, build first with
# `mvn package -DskipTests`. JAR=path/to.jar tests another jar (e.g. to check that a broken jar is caught).
#
# Default: every test except HadoopFullPipelineTest, which needs hadoop-mapreduce-client-jobclient (scope "provided",
# so deliberately not in the jar).
#
set -euo pipefail

# Local Maven repository: /m2 inside the docker image, ~/.m2 elsewhere (override with M2_REPO)
REPO=${M2_REPO:-/m2/repository}
[ -d "$REPO" ] || REPO=$HOME/.m2/repository

JAR=${JAR:-target/image-search-indexing.jar}
[ -f "$JAR" ] || { echo "$JAR not found: run 'mvn package -DskipTests' first" >&2; exit 1; }

JUNIT=$(find "$REPO" -name 'junit-4*.jar' | head -1)
HAMCREST=$(find "$REPO" -name 'hamcrest-core-1.3.jar' | head -1)

if [ "$#" -gt 0 ]; then
  CLASSES="$*"
else
  CLASSES=$(cd target/test-classes && find . -name '*Test.class' ! -name 'HadoopFullPipelineTest.class' \
    | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort | tr '\n' ' ')
fi

# Jar first: main classes and all Tika/ICU resources come from it. target/classes is intentionally not on the classpath.
java -cp "$JAR:target/test-classes:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore $CLASSES
