#!/usr/bin/env bash
# Run tests for one module (optionally one test class/pattern) and print ONLY counts + failing test names.
# Usage: scripts/test-summary.sh <module> [TestClassPattern] [max-lines]
#   scripts/test-summary.sh order-service
#   scripts/test-summary.sh order-service OrderServiceTest
#   scripts/test-summary.sh order-service '*IT'          # integration tests (Gradle: task integrationTest if it exists)
# Reads JUnit XML reports, never console output.
set -uo pipefail
source "$(dirname "$0")/lib.sh"; detect_build_tool
MODULE="${1:?module required}"; PATTERN="${2:-}"; MAX="${3:-40}"

if [ "$BUILD" = gradle ]; then
  P="$(gradle_path "$MODULE")"; DIR="$(gradle_dir "$MODULE")"
  TASKS="${P}:test"
  # If the project declares an integrationTest task, include it when the pattern looks like an IT or no pattern given.
  if $BW -q "${P}:tasks" --all 2>/dev/null | grep -qE '^integrationTest'; then
    case "$PATTERN" in *IT*|"") TASKS="$TASKS ${P}:integrationTest";; esac
  fi
  FILTER=""; [ -n "$PATTERN" ] && FILTER="--tests *${PATTERN#\*}"
  # shellcheck disable=SC2086
  $BW -q $OFFLINE --console=plain --continue $TASKS $FILTER >/dev/null 2>&1
  STATUS=$?
  REPORTS=$(find "$DIR/build/test-results" -name 'TEST-*.xml' 2>/dev/null)
else
  TESTARG=""
  [ -n "$PATTERN" ] && TESTARG="-Dtest=$PATTERN -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=$PATTERN -Dfailsafe.failIfNoSpecifiedTests=false"
  # shellcheck disable=SC2086
  $BW -q -B $OFFLINE -pl "$MODULE" -am -DtrimStackTrace=true -Dsurefire.printSummary=false $TESTARG verify >/dev/null 2>&1
  STATUS=$?
  REPORTS=$(find "$MODULE" -path '*/target/*-reports/TEST-*.xml' 2>/dev/null)
fi

if [ -z "$REPORTS" ]; then
  SRC_DIR="$([ "$BUILD" = gradle ] && gradle_dir "$MODULE" || echo "$MODULE")/src/test"
  if [ ! -d "$SRC_DIR" ] || [ -z "$(find "$SRC_DIR" -name '*.java' -o -name '*.kt' 2>/dev/null | head -1)" ]; then
    echo "TESTS $MODULE [$BUILD]: no test sources under $SRC_DIR (exit $STATUS)"; exit 0
  fi
  echo "NO TEST REPORTS FOUND for $MODULE [$BUILD] (build exit $STATUS) although test sources exist. Run scripts/compile-errors.sh $MODULE first."
  exit 1
fi

T=0; F=0; E=0; S=0
for r in $REPORTS; do
  t=$(grep -o 'tests="[0-9]*"'    "$r" | head -1 | tr -dc '0-9'); T=$((T + ${t:-0}))
  f=$(grep -o 'failures="[0-9]*"' "$r" | head -1 | tr -dc '0-9'); F=$((F + ${f:-0}))
  e=$(grep -o 'errors="[0-9]*"'   "$r" | head -1 | tr -dc '0-9'); E=$((E + ${e:-0}))
  s=$(grep -o 'skipped="[0-9]*"'  "$r" | head -1 | tr -dc '0-9'); S=$((S + ${s:-0}))
done
echo "TESTS $MODULE${PATTERN:+ [$PATTERN]} [$BUILD]: total=$T failures=$F errors=$E skipped=$S (exit $STATUS)"

if [ $((F + E)) -gt 0 ]; then
  echo "--- failing tests (first $MAX lines):"
  for r in $REPORTS; do
    awk '
      /<testcase/ { match($0, /name="[^"]*"/); n=substr($0, RSTART+6, RLENGTH-7);
                    match($0, /classname="[^"]*"/); c=substr($0, RSTART+11, RLENGTH-12) }
      /<(failure|error)/ { match($0, /message="[^"]*"/); m=substr($0, RSTART+9, RLENGTH-10);
                           gsub(/&quot;/,"\"",m); gsub(/&lt;/,"<",m); gsub(/&gt;/,">",m); gsub(/&amp;/,"\\&",m); gsub(/&#10;/," ",m);
                           print c "#" n ": " substr(m, 1, 220) }
    ' "$r"
  done | sort -u | head -n "$MAX"
fi
exit $STATUS
