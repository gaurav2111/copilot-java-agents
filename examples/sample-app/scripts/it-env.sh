#!/usr/bin/env bash
# Facts for integration tests, without containers: what the developer machine and the module already provide.
# Usage: scripts/it-env.sh [module]
# Prints: test profiles and the connection properties they expect, whether those are set, whether the database host
# and the Pub/Sub emulator answer, gcloud presence, test substitutes on the test classpath, existing IT base classes
# and *IT classes, the integrationTest task. Reads files and opens TCP connections only; starts nothing.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2
MOD="${1:-.}"; [ -d "$MOD" ] || { echo "no such module directory: $MOD"; exit 2; }
reach() { # host port -> open | closed
  local h="$1" p="$2"
  if command -v nc >/dev/null 2>&1; then nc -z -w 2 "$h" "$p" >/dev/null 2>&1 && echo open || echo closed
  else (exec 3<>"/dev/tcp/$h/$p") >/dev/null 2>&1 && echo open || echo closed; fi
}
echo "IT ENV ${MOD}"
echo "== test profiles"
PROFILES=$(find "$MOD" -path '*/src/test/resources/*' \( -name 'application-*.yml' -o -name 'application-*.yaml' -o -name 'application-*.properties' \) 2>/dev/null | grep -v '/build/' | sort)
[ -z "$PROFILES" ] && echo "  none under src/test/resources (the base class will need one: application-test.yml)"
for f in $PROFILES; do
  echo "  $f"
  grep -nE 'datasource\.url|datasource:|url:|emulator-host|pubsub|flyway|liquibase|ddl-auto|credentials' "$f" | sed 's/^/      /' | cut -c1-140
done
echo "== connection values (env, never written by an agent)"
for v in TEST_DB_URL TEST_DB_USER TEST_DB_PASSWORD PUBSUB_EMULATOR_HOST; do
  if [ -n "${!v:-}" ]; then [ "$v" = TEST_DB_PASSWORD ] && echo "  $v set" || echo "  $v = ${!v}"; else echo "  $v not set"; fi
done
LOCAL=$(find "$MOD" -name 'application-test-local.*' -not -path '*/build/*' 2>/dev/null | head -1)
[ -n "$LOCAL" ] && echo "  local override file: $LOCAL $(git check-ignore -q "$LOCAL" 2>/dev/null && echo '(git-ignored)' || echo '(NOT git-ignored: must not hold a password)')"
echo "== database"
URL="${TEST_DB_URL:-}"
[ -z "$URL" ] && [ -n "$PROFILES$LOCAL" ] && URL=$(grep -hoE 'jdbc:[a-z0-9]+://[^ "'"'"']+' $PROFILES ${LOCAL:-} 2>/dev/null | head -1)
if [ -n "$URL" ]; then
  HP=$(echo "$URL" | sed -E 's#^jdbc:[a-z0-9]+://##; s#[/?].*##'); H="${HP%%:*}"; P="${HP##*:}"; [ "$H" = "$P" ] && P=5432
  echo "  url: $(echo "$URL" | sed -E 's#(//[^:/@]+):[^@/]+@#\1:***@#')"; echo "  host $H port $P: $(reach "$H" "$P")"
else echo "  no JDBC url in env or test profiles: the developer sets TEST_DB_URL (or application-test-local.yml) before an IT can run"; fi
echo "== pub/sub emulator"
EH="${PUBSUB_EMULATOR_HOST:-}"; [ -z "$EH" ] && [ -n "$PROFILES" ] && EH=$(grep -hoE 'emulator-host:[[:space:]]*[^[:space:]]+' $PROFILES 2>/dev/null | head -1 | sed -E 's/emulator-host:[[:space:]]*//; s/\$\{[^:}]+:([^}]*)\}/\1/')
EH="${EH:-localhost:8085}"; echo "  host ${EH}: $(reach "${EH%%:*}" "${EH##*:}") (start with: gcloud beta emulators pubsub start --host-port=${EH})"
if command -v gcloud >/dev/null 2>&1; then
  ROOT=$(gcloud info --format='value(installation.sdk_root)' 2>/dev/null); echo "  gcloud: present${ROOT:+ at $ROOT}; pubsub-emulator component: $([ -n "$ROOT" ] && [ -d "$ROOT/platform/pubsub-emulator" ] && echo installed || echo 'not found (gcloud components install pubsub-emulator, by the developer)')"
else echo "  gcloud: not on PATH"; fi
echo "== test substitutes on the test classpath (build files)"
BF=$(find "$MOD" -maxdepth 2 \( -name 'build.gradle' -o -name 'build.gradle.kts' -o -name 'pom.xml' \) -not -path '*/build/*' 2>/dev/null)
CAT=$(ls gradle/libs.versions.toml 2>/dev/null)
for dep in wiremock spring-kafka-test google-cloud-nio spring-security-test spring-boot-starter-test google-cloud-pubsub spring-cloud-gcp-starter-pubsub h2 testcontainers; do
  if grep -qs "$dep" $BF $CAT 2>/dev/null; then echo "  $dep: present$( [ "$dep" = testcontainers ] && echo ' (not used by this policy: no containers)' )$( [ "$dep" = h2 ] && echo ' (not used by this policy: real test database)' )"; else echo "  $dep: absent"; fi
done
echo "== existing integration tests"
BASES=$(grep -rlE '@SpringBootTest|@WebMvcTest|@DataJpaTest|@RestClientTest' $(find "$MOD" -path '*/src/test/java/*' -name '*.java' -o -path '*/src/integrationTest/java/*' -name '*.java' 2>/dev/null) 2>/dev/null | sort)
ABS=$(echo "$BASES" | xargs grep -lE 'abstract class' 2>/dev/null)
echo "  base classes: ${ABS:-none}" | tr '\n' ' '; echo
echo "  slice tests: $(echo "$BASES" | grep -c . ) file(s); *IT.java: $(find "$MOD" -name '*IT.java' -not -path '*/build/*' 2>/dev/null | wc -l | tr -d ' ')"
echo "  support packages: $(find "$MOD" -path '*/src/test/java/*' -type d -name 'support' -not -path '*/build/*' 2>/dev/null | tr '\n' ' ')"
if ls gradlew >/dev/null 2>&1; then echo "  integrationTest task: $(grep -rqsE 'integrationTest' $BF build.gradle build.gradle.kts buildSrc 2>/dev/null && echo declared || echo 'not declared (ITs run in the test task)')"; else echo "  failsafe: $(grep -qs failsafe $BF 2>/dev/null && echo declared || echo 'not declared')"; fi
echo "next: answer the per-system questions from .github/INTEGRATION-TEST-POLICY.md section 1; connection values are set by you, not by the agent."
