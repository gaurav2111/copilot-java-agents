#!/usr/bin/env bash
# One-time (and after any build change) validation of the agent pack against THIS repository.
# Runs every script, checks every prerequisite, auto-detects the stack, and writes SETUP-REPORT.md.
# Agents refuse to start a task without a passing, current report (AGENT-PROTOCOL.md section G0).
# Never downloads anything: runs offline and tells you the one online command to run when something is not cached.
# Usage: scripts/setup-check.sh [--quick]     (--quick skips the test run and the OpenRewrite dry run)
set -uo pipefail
QUICK=0; [ "${1:-}" = "--quick" ] && QUICK=1
cd "$(dirname "$0")/.." || exit 2
source scripts/lib.sh
REPORT="SETUP-REPORT.md"
PASS=0; WARN=0; FAIL=0
ROWS=""
row() { # status name evidence
  case "$1" in PASS) PASS=$((PASS+1));; WARN) WARN=$((WARN+1));; FAIL) FAIL=$((FAIL+1));; esac
  ROWS+="| $1 | $2 | $(echo "$3" | tr '|' '/' | head -c 220) |"$'\n'
  printf "%-4s %-42s %s\n" "$1" "$2" "$(echo "$3" | head -c 110)"
}
have() { command -v "$1" >/dev/null 2>&1; }

echo "== Agent pack setup check ($(date "+%F %H:%M")) in $(pwd)"

# ---------- 1. environment ----------
if detect_build_tool 2>/dev/null; then row PASS "build tool detected" "$BUILD via $BW (override with BUILD_TOOL=)"; else row FAIL "build tool detected" "no settings.gradle(.kts), gradlew or pom.xml at repo root"; fi
if [ "$BUILD" = gradle ]; then
  [ -x ./gradlew ] && row PASS "gradle wrapper" "$(grep -o 'gradle-[0-9.]*' gradle/wrapper/gradle-wrapper.properties 2>/dev/null | head -1)" || row FAIL "gradle wrapper" "./gradlew missing or not executable (chmod +x gradlew)"
else
  [ -x ./mvnw ] && row PASS "maven wrapper" "./mvnw present" || row WARN "maven wrapper" "no ./mvnw; scripts fall back to mvn on PATH"
fi
for t in java javac javap jdeps git; do have "$t" && row PASS "tool on PATH: $t" "$($t --version 2>&1 | head -1 | head -c 80)" || row FAIL "tool on PATH: $t" "not found; JDK bin must be on PATH in the IDE terminal"; done
if have jdeprscan; then row PASS "tool on PATH: jdeprscan" "$(jdeprscan --version 2>&1 | head -1 | head -c 60)"
else JH=$(java -XshowSettings:properties -version 2>&1 | grep -oE 'java.home = .*' | sed 's/java.home = //'); [ -x "$JH/bin/jdeprscan" ] && row WARN "tool on PATH: jdeprscan" "present in $JH/bin but not on PATH; jdk-check.sh will find it via java.home" || row WARN "tool on PATH: jdeprscan" "not in this JDK (jlinked/minimal runtime?); jdk-check.sh skips the deprecation scan and says so"; fi
JV=$(java -version 2>&1 | grep -oE '"[0-9]+' | tr -d '"' | head -1); [ "${JV:-0}" -ge 11 ] && row PASS "JDK >= 11 for the Java helper tools" "java $JV" || row FAIL "JDK >= 11" "java $JV"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) row PASS "shell" "Git Bash / MSYS ($(uname -s))";; Linux|Darwin) row PASS "shell" "$(uname -s), bash $BASH_VERSION";; *) row WARN "shell" "$(uname -s): scripts need bash (Git Bash or WSL on Windows)";; esac
NONEXEC=$(find scripts .github/hooks -name '*.sh' ! -perm -u+x 2>/dev/null | tr '\n' ' '); [ -z "$NONEXEC" ] && row PASS "scripts executable" "all *.sh have +x" || row FAIL "scripts executable" "chmod +x $NONEXEC"
for f in .github/copilot-instructions.md .github/AGENT-PROTOCOL.md .github/hooks/hooks.json rewrite.yml; do [ -f "$f" ] && row PASS "file present: $f" "ok" || row FAIL "file present: $f" "missing"; done
[ -f .github/AGENT-RULES.md ] && row PASS "file present: .github/AGENT-RULES.md" "ok" || row WARN "file present: .github/AGENT-RULES.md" "optional; copy from the pack to add hard boundaries / standing instructions"
grep -q "<CURRENT_JAVA>\|<module-a>\|<VERSION>" .github/copilot-instructions.md 2>/dev/null && row WARN "stack block filled in" "placeholders remain in copilot-instructions.md; see proposed block at the end of $REPORT" || row PASS "stack block filled in" "no placeholders"
if printf '{"toolName":"execute","toolArgs":"git push --force origin main"}' | bash .github/hooks/guard.sh >/dev/null 2>&1; then row FAIL "guard hook blocks a force push" "guard.sh let it through"; else row PASS "guard hook blocks a force push" "exit 2 as expected"; fi
if printf '{"toolName":"execute","toolArgs":"./gradlew --offline -q build"}' | bash .github/hooks/guard.sh >/dev/null 2>&1; then row PASS "guard hook allows a normal build" "exit 0"; else row FAIL "guard hook allows a normal build" "guard.sh blocked a plain build command"; fi

# ---------- 2. detect the stack (facts with sources) ----------
echo "== detecting stack"
if [ "$BUILD" = gradle ]; then
  PROJECTS=$(gradle_projects | tr '\n' ' ')
  [ -n "$PROJECTS" ] && row PASS "projects listed" "$PROJECTS" || row FAIL "projects listed" "gradle projects returned nothing (is the daemon/offline cache OK?)"
  JAVA_LEVEL=$(grep -rhoE 'languageVersion[^0-9]*([0-9]+)|release(\.set\(| = )([0-9]+)|sourceCompatibility[^0-9]*([0-9]+)' --include=*.gradle --include=*.gradle.kts --include=*.toml . 2>/dev/null | grep -oE '[0-9]+$' | sort -u | tr '\n' ',')
  JAVA_SRC=$(grep -rlE 'languageVersion|options\.release|sourceCompatibility' --include=*.gradle --include=*.gradle.kts . 2>/dev/null | grep -v /build/ | head -2 | tr '\n' ' ')
  BOOT=$(grep -rhoE 'spring-boot[^=]*=\s*"[0-9.]+"|org\.springframework\.boot[^0-9]*([0-9]+\.[0-9]+\.[0-9]+)' --include=*.toml --include=*.gradle --include=*.gradle.kts . 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | sort -u | tr '\n' ',')
  CATALOG=$([ -f gradle/libs.versions.toml ] && echo "gradle/libs.versions.toml" || echo "none (versions in ext{} / gradle.properties / build files)")
  TESTLIBS=$(grep -rhoE 'junit-jupiter|junit:junit|mockito|assertj|hamcrest|testcontainers|wiremock|spring-boot-starter-test|kotest' --include=*.gradle --include=*.gradle.kts --include=*.toml . 2>/dev/null | sort -u | tr '\n' ' ')
  ITTASK=$(grep -rlE 'integrationTest|register\("it"|"itest"' --include=*.gradle --include=*.gradle.kts . 2>/dev/null | grep -v /build/ | head -1)
  JACOCO_XML=$(grep -rlE 'xml\.required|xml\.isEnabled|xml\.enabled' --include=*.gradle --include=*.gradle.kts . 2>/dev/null | grep -v /build/ | head -1)
else
  PROJECTS=$(grep -oE '<module>[^<]+' pom.xml 2>/dev/null | sed 's/<module>//' | tr '\n' ' '); [ -z "$PROJECTS" ] && PROJECTS="(single module)"
  row PASS "projects listed" "$PROJECTS"
  JAVA_LEVEL=$(grep -hoE 'maven.compiler.(release|source|target)>[0-9.]+' pom.xml 2>/dev/null | grep -oE '[0-9.]+$' | sort -u | tr '\n' ','); JAVA_SRC="pom.xml"
  BOOT=$(grep -A2 'spring-boot-starter-parent\|spring-boot-dependencies' pom.xml 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  CATALOG="parent pom <properties> / <dependencyManagement>"
  TESTLIBS=$(grep -hoE 'junit-jupiter|<artifactId>junit<|mockito|assertj|hamcrest|testcontainers|wiremock|spring-boot-starter-test' pom.xml */pom.xml 2>/dev/null | sort -u | tr '\n' ' ')
  ITTASK=$(grep -l 'failsafe' pom.xml */pom.xml 2>/dev/null | head -1); JACOCO_XML=$(grep -l 'jacoco' pom.xml */pom.xml 2>/dev/null | head -1)
fi
[ -n "$JAVA_LEVEL" ] && row PASS "java level detected" "${JAVA_LEVEL%,} (from $JAVA_SRC)" || row WARN "java level detected" "no explicit release/toolchain found; the build uses the running JDK ($JV)"
[ -n "$BOOT" ] && row PASS "spring boot version detected" "${BOOT%,}" || row WARN "spring boot version detected" "none found (not a Spring Boot build, or version set elsewhere)"
row PASS "version source" "$CATALOG"
[ -n "$TESTLIBS" ] && row PASS "test libraries detected" "$TESTLIBS" || row WARN "test libraries detected" "none found in build files"
[ -n "$ITTASK" ] && row PASS "integration test task" "configured in $ITTASK" || row WARN "integration test task" "none found; test-summary.sh will run only the test task; *IT classes run with unit tests"
[ -n "$JACOCO_XML" ] && row PASS "jacoco XML report" "configured in $JACOCO_XML" || row WARN "jacoco XML report" "not found; CoverageGaps.java needs reports { xml.required = true } (Gradle) or the jacoco report goal (Maven)"

# ---------- 3. run the scripts ----------
echo "== running scripts (offline)"
OUT=$(scripts/compile-errors.sh 2>&1); if echo "$OUT" | grep -q "COMPILE OK"; then row PASS "compile-errors.sh (all projects)" "$(echo "$OUT" | head -1)"; elif echo "$OUT" | grep -qiE "offline|No cached version|Could not resolve"; then row WARN "compile-errors.sh (all projects)" "offline cache incomplete: run once  BUILD_OFFLINE=\"\" scripts/compile-errors.sh  then rerun setup"; else row FAIL "compile-errors.sh (all projects)" "$(echo "$OUT" | head -3 | tr '\n' ' ')"; fi
# first project that actually has test sources
FIRST=""; for cand in $PROJECTS; do d="$( [ "$BUILD" = gradle ] && gradle_dir "$cand" || echo "$cand")"; [ -d "$d/src/test" ] && { FIRST="$cand"; break; }; done
if [ $QUICK -eq 0 ] && [ -n "$FIRST" ]; then
  P="${FIRST#:}"
  OUT=$(scripts/test-summary.sh "$P" 2>&1); if echo "$OUT" | grep -qE "^TESTS"; then row PASS "test-summary.sh ($P)" "$(echo "$OUT" | head -1)"; else row FAIL "test-summary.sh ($P)" "$(echo "$OUT" | head -2 | tr '\n' ' ')"; fi
  if [ -n "$(find . \( -path '*/build/reports/jacoco/*.xml' -o -path '*/target/site/jacoco/jacoco.xml' \) 2>/dev/null | head -1)" ]; then
    OUT=$(java scripts/CoverageGaps.java "**" 70 3 2>&1); echo "$OUT" | grep -q "COVERAGE SUMMARY" && row PASS "CoverageGaps.java" "$(echo "$OUT" | head -1)" || row FAIL "CoverageGaps.java" "$(echo "$OUT" | head -1)"
  else row WARN "CoverageGaps.java" "no JaCoCo XML report found after the test run; enable the XML report"; fi
else row WARN "test-summary.sh / CoverageGaps.java" "skipped (--quick, or no project with src/test found)"; fi
OUT=$(scripts/dep-tree.sh 2>&1); echo "$OUT" | grep -q "DEPTREE" && row PASS "dep-tree.sh" "$(echo "$OUT" | head -1)" || row FAIL "dep-tree.sh" "$(echo "$OUT" | head -1)"
if [ -f findings.csv ]; then OUT=$(java scripts/CveTriage.java findings.csv "$BUILD_DIR/deptree.txt" 2>&1); echo "$OUT" | grep -q "CVE TRIAGE" && row PASS "CveTriage.java (findings.csv)" "$(echo "$OUT" | head -1)" || row FAIL "CveTriage.java" "$(echo "$OUT" | head -1)"; else row WARN "CveTriage.java" "no findings.csv at the root; export one from the scanner (groupId,artifactId,version,cve,severity,fixVersion) to test"; fi
FIRSTDEP=$(grep -oE '[a-z0-9.-]+:[a-z0-9.-]+' "$BUILD_DIR/deptree.txt" 2>/dev/null | grep -v '^project' | head -1)
if [ -n "$FIRSTDEP" ]; then OUT=$(scripts/dep-path.sh "$FIRSTDEP" 2>&1); echo "$OUT" | grep -qE "RESOLVED|DIRECT|TRANSITIVE" && row PASS "dep-path.sh ($FIRSTDEP)" "$(echo "$OUT" | grep -m1 -E 'RESOLVED|DIRECT' | sed 's/^ *//')" || row FAIL "dep-path.sh" "$(echo "$OUT" | head -1)"; fi
TARGET=$(( ${JAVA_LEVEL%%,*} > 0 ? ${JAVA_LEVEL%%,*} : JV )); OUT=$(scripts/jdk-check.sh "$TARGET" 2>&1); echo "$OUT" | grep -q "Toolchain facts" && row PASS "jdk-check.sh $TARGET" "$(echo "$OUT" | grep -c 'files$') trouble-spot lines" || row FAIL "jdk-check.sh" "$(echo "$OUT" | head -1)"
if [ $QUICK -eq 0 ]; then
  OUT=$(scripts/rewrite.sh org.openrewrite.java.RemoveUnusedImports 2>&1)
  if echo "$OUT" | grep -qE "no changes proposed|files="; then row PASS "rewrite.sh (OpenRewrite, offline)" "$(echo "$OUT" | grep -m1 -E 'no changes proposed|files=')"
  elif echo "$OUT" | grep -qiE "offline|No cached version"; then row WARN "rewrite.sh (OpenRewrite)" "plugin not cached: run once  BUILD_OFFLINE=\"\" scripts/rewrite.sh org.openrewrite.java.RemoveUnusedImports  then rerun setup"
  elif echo "$OUT" | grep -qiE "Could not resolve|Could not find"; then row FAIL "rewrite.sh (OpenRewrite)" "mirror cannot serve org.openrewrite: $(echo "$OUT" | grep -m1 -iE 'Could not (resolve|find)' | head -c 120)"
  else row FAIL "rewrite.sh (OpenRewrite)" "$(echo "$OUT" | grep -m1 -iE 'FAILED|error' | head -c 150)"; fi
else row WARN "rewrite.sh" "skipped (--quick)"; fi

# ---------- 4. report ----------
STATUS="PASS"; [ $WARN -gt 0 ] && STATUS="PASS WITH WARNINGS"; [ $FAIL -gt 0 ] && STATUS="FAIL"
{
  echo "# Agent pack setup report"
  echo
  echo "Generated: $(date "+%F %H:%M")  |  Result: **$STATUS**  (pass $PASS, warn $WARN, fail $FAIL)  |  Build tool: $BUILD  |  Repo: $(basename "$(pwd)")"
  echo
  echo "Rerun \`scripts/setup-check.sh\` after any change to build files, wrapper, or the pack. Agents treat a report older than the build files as stale."
  echo
  echo "| Status | Check | Evidence |"; echo "|---|---|---|"; printf "%s" "$ROWS"
  echo
  echo "## Proposed stack block for .github/copilot-instructions.md (review, then paste over the FILL IN block)"
  echo
  echo '```'
  echo "- Build: $BUILD multi-project, wrapper $( [ "$BUILD" = gradle ] && echo './gradlew' || echo './mvnw'), versions in $CATALOG. Java ${JAVA_LEVEL%,}${JAVA_LEVEL:+ }(installed JDK $JV).${BOOT:+ Spring Boot ${BOOT%,}.}"
  IT_TXT="none configured"; [ -n "$ITTASK" ] && IT_TXT="task configured in $ITTASK"
  JX_TXT="(enable the XML report)"; [ -n "$JACOCO_XML" ] && JX_TXT="XML enabled"
  echo "- Tests: ${TESTLIBS:-<fill in>}. Integration tests: $IT_TXT. Coverage: JaCoCo $JX_TXT."
  echo "- Projects: $PROJECTS"
  echo "- Internal libraries: <fill in: internal groupIds and the mirror that serves them>"
  echo '```'
  echo
  echo "## Fix list (FAIL first, then WARN)"
  echo "$ROWS" | grep -E '^\| (FAIL|WARN) ' | sed 's/^| \([A-Z]*\) | \(.*\) | \(.*\) |$/- [\1] \2: \3/'
} > "$REPORT"
echo "== $STATUS (pass $PASS, warn $WARN, fail $FAIL) -> $REPORT"
[ $FAIL -eq 0 ]
