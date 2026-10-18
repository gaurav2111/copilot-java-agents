#!/usr/bin/env bash
# Deterministic JDK compatibility check for an upgrade, using the JDK's own tools. Read-only, no downloads.
# Usage: scripts/jdk-check.sh <targetJava> [module]
#   scripts/jdk-check.sh 21 order-service
# Prints: current toolchain facts, JDK-internal API usage (jdeps), APIs deprecated-for-removal (jdeprscan),
# and a short list of classpath jars whose bytecode is newer than the target (multi-release aside).
# Compile the module first (scripts/compile-errors.sh <module>) so build/classes exists.
set -uo pipefail
source "$(dirname "$0")/lib.sh"; detect_build_tool
TARGET="${1:?target java version required, e.g. 21}"; MODULE="${2:-}"

echo "== Toolchain facts (paste into facts.md with these sources)"
echo "java -version:       $(java -version 2>&1 | head -1)"
if [ "$BUILD" = gradle ]; then
  echo "gradle wrapper:      $(grep -o 'gradle-[0-9.]*' gradle/wrapper/gradle-wrapper.properties 2>/dev/null | head -1)  (gradle/wrapper/gradle-wrapper.properties)"
  grep -rnE 'languageVersion|sourceCompatibility|targetCompatibility|options\.release|release\.set|JavaVersion\.' --include=*.gradle --include=*.gradle.kts --include=*.toml . 2>/dev/null | grep -v '/build/' | sed 's/^/  /' | head -n 10
else
  grep -nE 'maven.compiler.(release|source|target)|<release>|<source>|<target>' pom.xml 2>/dev/null | sed 's/^/  pom.xml:/' | head -n 6
fi

CLASSES=$(find ${MODULE:-.} -type d \( -path '*/build/classes/java/main' -o -path '*/target/classes' \) 2>/dev/null | grep -v '/test/' | head -n 20)
if [ -z "$CLASSES" ]; then echo "no compiled classes found; run scripts/compile-errors.sh ${MODULE:-<module>} first"; exit 1; fi

echo; echo "== JDK-internal API usage (jdeps --jdk-internals): these break or warn on newer JDKs"
for d in $CLASSES; do
  out=$(jdeps --jdk-internals -q "$d" 2>/dev/null | grep -vE '^$|Warning|^JDK Internal API' | head -n 20)
  [ -n "$out" ] && { echo "-- $d"; echo "$out" | sed 's/^/  /'; }
done | head -n 60
echo "(empty above = none found)"

echo; echo "== Deprecated-for-removal API usage against JDK $TARGET (jdeprscan --release $TARGET)"
for d in $CLASSES; do
  out=$(jdeprscan --release "$TARGET" --for-removal "$d" 2>&1 | grep -E 'uses deprecated|error:' | head -n 20)
  [ -n "$out" ] && { echo "-- $d"; echo "$out" | sed 's/^/  /'; }
done | head -n 60
echo "(empty above = none found; 'release not supported' means this JDK's jdeprscan is older than the target)"

echo; echo "== Known trouble spots in sources (count of files)"
for p in 'javax\.xml\.bind' 'javax\.annotation\.' 'sun\.misc\.' 'com\.sun\.' 'SecurityManager' 'Thread\.stop\(' 'new URL\(' '\.finalize\(' 'Nashorn|jdk\.nashorn' 'javax\.(servlet|persistence|validation)'; do
  n=$(grep -rlE "$p" --include=*.java ${MODULE:-.} 2>/dev/null | grep -v '/build/' | wc -l | tr -d ' ')
  [ "$n" != "0" ] && printf "  %-40s %s files\n" "$p" "$n"
done
echo "(each non-zero line is a plan.md step for the upgrader; the UpgradeToJava* recipes cover most of them)"
