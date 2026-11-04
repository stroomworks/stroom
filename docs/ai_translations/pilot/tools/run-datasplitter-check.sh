#!/usr/bin/env bash
# Checks a Data Splitter configuration against an input, without a Stroom node. Usage:
#   tools/run-datasplitter-check.sh CONFIG INPUT [EXPECTED_RECORDS] [--records-out FILE]
# It builds the Shapeshifter module's test classpath with Gradle (an init script, no build file is changed) and runs
# stroom.shapeshifter.ai.extraction.DataSplitterCheck. The first run is slow because Gradle compiles; later runs reuse
# a cached classpath. Needs a working ./gradlew (network on the first run). Exit status 0 means it compiled, raised no
# errors and matched the expected records if they were given.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../../.." && pwd)"
CACHE="${PILOT_CP_CACHE:-$ROOT/build/pilot-classpath.txt}"
if [ ! -s "$CACHE" ] || [ -n "${PILOT_REBUILD:-}" ]; then
  mkdir -p "$(dirname "$CACHE")"
  ( cd "$ROOT" && ./gradlew -q -I "$HERE/print-classpath.init.gradle" \
      :stroom-shapeshifter:stroom-shapeshifter-ai:printPilotClasspath 2>/dev/null \
      | grep '^PILOT_CLASSPATH=' | tail -1 | sed 's/^PILOT_CLASSPATH=//' ) > "$CACHE"
fi
[ -s "$CACHE" ] || { echo "Could not build the classpath. Is ./gradlew working?" >&2; exit 3; }
# Resolve relative file arguments against the caller's directory, as java is run from there.
exec java -cp "$(cat "$CACHE")" stroom.shapeshifter.ai.extraction.DataSplitterCheck "$@" 2> >(grep -v '^WARNING\|^SLF4J\|DEBUG' >&2)
