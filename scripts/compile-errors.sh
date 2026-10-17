#!/usr/bin/env bash
# Compile main + test sources for one module (or everything) and print ONLY the distinct compiler errors, capped.
# Usage: scripts/compile-errors.sh [module] [max-lines]
#   scripts/compile-errors.sh                      # whole build
#   scripts/compile-errors.sh order-service        # Gradle :order-service / Maven -pl order-service -am
#   scripts/compile-errors.sh services/order 80    # nested Gradle project -> :services:order
# Why: agents must never read raw build output. This keeps the context small.
set -uo pipefail
source "$(dirname "$0")/lib.sh"; detect_build_tool
MODULE="${1:-}"; MAX="${2:-60}"

if [ "$BUILD" = gradle ]; then
  P="$(gradle_path "$MODULE")"
  # shellcheck disable=SC2086
  OUT=$($BW -q $OFFLINE --console=plain ${P}:compileJava ${P}:compileTestJava -x test 2>&1)
  STATUS=$?
  ERR_RE='\.java:[0-9]+: error:|^e: |error: |cannot find symbol|package .* does not exist|incompatible types|does not override'
else
  # shellcheck disable=SC2086
  OUT=$($BW -q -B $OFFLINE $(maven_pl "$MODULE") -DskipTests -Dmaven.javadoc.skip=true test-compile 2>&1)
  STATUS=$?
  ERR_RE='^\[ERROR\] .*\.java:\[[0-9]+,[0-9]+\]|error: |cannot find symbol|package .* does not exist|incompatible types|does not override'
fi

if [ $STATUS -eq 0 ]; then echo "COMPILE OK${MODULE:+ ($MODULE)} [$BUILD]"; exit 0; fi

echo "COMPILE FAILED${MODULE:+ ($MODULE)} [$BUILD] - distinct errors (max $MAX):"
echo "$OUT" | grep -E "$ERR_RE" \
  | sed -E 's#^\[ERROR\] ##; s#^.*/(src/(main|test)/java/)#\1#; s#^e: file://[^ ]*/(src/)#\1#' \
  | sort -u | head -n "$MAX"
TOTAL=$(echo "$OUT" | grep -cE '\.java:(\[)?[0-9]+' || true)
echo "--- total error lines: $TOTAL (showing up to $MAX distinct)"
# Non-compiler failures (plugin, dependency resolution)
echo "$OUT" | grep -E 'Could not resolve|Could not find|Failed to execute goal|Non-resolvable|BUILD FAILURE|FAILURE: Build failed|What went wrong' -A1 | grep -v '^--$' | sort -u | head -n 10
exit $STATUS
