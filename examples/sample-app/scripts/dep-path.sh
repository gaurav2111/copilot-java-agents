#!/usr/bin/env bash
# Show how a dependency reaches the build: direct or transitive, via which path, in which modules, resolved version.
# Usage: scripts/dep-path.sh <groupId>:<artifactId> [module]
#   scripts/dep-path.sh org.yaml:snakeyaml
#   scripts/dep-path.sh com.fasterxml.jackson.core:jackson-databind order-service
# Compact output so an agent can decide: bump direct / bump BOM or platform / constraint or dependencyManagement / exclusion.
set -uo pipefail
source "$(dirname "$0")/lib.sh"; detect_build_tool
GA="${1:?groupId:artifactId required}"; MODULE="${2:-}"
G="${GA%%:*}"; A="${GA#*:}"

if [ "$BUILD" = gradle ]; then
  CONF="${GRADLE_CONFIGURATION:-runtimeClasspath}"
  if [ -n "$MODULE" ]; then PROJECTS="$(gradle_path "$MODULE")"; else PROJECTS="$(gradle_projects)"; fi
  for P in $PROJECTS; do
    # dependencyInsight prints the resolved version, selection reason and every path to the artifact.
    # shellcheck disable=SC2086
    OUT=$($BW -q $OFFLINE --console=plain "${P}:dependencyInsight" --dependency "$A" --configuration "$CONF" 2>/dev/null \
          | grep -E "^$G:$A|^\\s*(\\\\|\\+)--- |Selection reasons|  - |constraint|conflict resolution|By ancestor|Forced" \
          | grep -vE "No dependencies matching")
    [ -z "$OUT" ] && continue
    echo "MODULE $P ($CONF)"
    # dependencyInsight prints the REVERSE path: the artifact first, then who requested it, up to the project.
    # So: RESOLVED = the version actually on the classpath; REQUESTED BY = the chain back to a direct dependency.
    echo "$OUT" | awk -v ga="$G:$A" '
      $0 ~ "^"ga { v=$0; sub(/ \(.*\)$/,"",v); if (!seen[v]++) print "  RESOLVED      " $0; next }
      /--- / { line=$0; gsub(/^[ |\\+-]+/,"",line); gsub(/ \(\*\)$/,"",line); if (!seen2[line]++) print "  REQUESTED BY  " line; next }
      /Selection reasons|  - |constraint|conflict|Forced|By ancestor/ { if (!seen3[$0]++) print "  " $0 }' | head -n 20
    echo "  (last REQUESTED BY line = the direct dependency to bump or constrain)"
  done
  echo "--- Version catalog / constraints mentioning $A:"
  grep -rn --include=libs.versions.toml --include=build.gradle --include=build.gradle.kts -E "$A" . 2>/dev/null | grep -v '/build/' | head -n 8
  # --- MANAGED BY: which mechanism decided the version (a BOM never appears in the requested-by tree) ---
  echo "--- MANAGED BY:"
  BUILD_FILES=$(find . -maxdepth 3 \( -name 'build.gradle' -o -name 'build.gradle.kts' -o -name 'settings.gradle' -o -name 'settings.gradle.kts' \) -not -path '*/build/*' 2>/dev/null)
  # shellcheck disable=SC2086
  BOMS=$(grep -hoE "mavenBom[ (]+['\"][^'\"]+['\"]|platform\(['\"][^'\"]+['\"]\)|enforcedPlatform\(['\"][^'\"]+['\"]\)" $BUILD_FILES 2>/dev/null | grep -oE "[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+" | sort -u)
  # shellcheck disable=SC2086
  DM_PLUGIN=$(grep -lE "io\.spring\.dependency-management|spring\.dependency\.management" $BUILD_FILES gradle/libs.versions.toml 2>/dev/null | head -1)
  BOOT_VER=$(grep -hoE "org\.springframework\.boot['\"]?\)?[^0-9]*version[ (=]*['\"]?[0-9]+\.[0-9]+\.[0-9]+" $BUILD_FILES 2>/dev/null | grep -oE "[0-9]+\.[0-9]+\.[0-9]+" | head -1)
  [ -z "$BOOT_VER" ] && BOOT_VER=$(grep -hoE "^spring-boot\s*=\s*['\"][0-9.]+" gradle/libs.versions.toml 2>/dev/null | grep -oE "[0-9]+\.[0-9]+\.[0-9]+" | head -1)
  REASONS=$(echo "$OUT" | grep -oiE "by constraint|selected by rule|forced|conflict resolution|by ancestor" | sort -u | tr '\n' ' ')
  [ -n "$REASONS" ] && echo "  selection reasons seen: $REASONS"
  if [ -n "$BOMS" ]; then echo "  BOMs declared in the build files (all of them; BomFix below says which one manages THIS artifact):"; echo "$BOMS" | sed 's/^/    /'; fi
  if [ -n "$DM_PLUGIN" ]; then
    echo "  mechanism: io.spring.dependency-management plugin (versions imposed by rule; BOM never shows in the tree)"
    [ -n "$BOOT_VER" ] && [ -z "$(echo "$BOMS" | grep spring-boot-dependencies)" ] && echo "  implied BOM: org.springframework.boot:spring-boot-dependencies:$BOOT_VER (imported automatically by the Spring Boot plugin)"
    echo "  full list: ./gradlew ${P:-}:dependencyManagement   (managed versions and imported BOMs)"
  elif echo "$REASONS" | grep -qi "constraint"; then echo "  mechanism: Gradle platform/constraint (see the (platform) entry in the path above)"
  else echo "  mechanism: none detected; the requested version wins (direct bump in the catalog)"; fi
  RES=$(echo "$OUT" | grep -oE "^$G:$A:[A-Za-z0-9.+-]+" | head -1 | awk -F: '{print $3}')
  echo "  next: java scripts/BomFix.java $GA <fixedVersion>${MODULE:+ --module $MODULE}${RES:+ --resolved $RES}   (BOM vs resolved: stale scan, override, or which BOM release fixes it)"
else
  PL=""; [ -n "$MODULE" ] && PL="-pl $MODULE"
  # shellcheck disable=SC2086
  $BW -B $OFFLINE $PL dependency:tree -Dincludes="$GA" -Dverbose=false 2>/dev/null \
    | awk '
        /Building /                    { mod=$0; sub(/^.*Building /,"",mod); sub(/ \[.*$/,"",mod); shown=0 }
        /^\[INFO\] [+\\|` -]*[a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+:/ {
          line=$0; sub(/^\[INFO\] /,"",line)
          depth=gsub(/[|+\\` -]{3}/,"",line)
          if (!shown) { print "MODULE " mod; shown=1 }
          kind = (depth==0) ? "DIRECT    " : "TRANSITIVE"
          print "  " kind " depth=" depth "  " line
        }'
  echo "--- Managed version (effective pom):"
  # shellcheck disable=SC2086
  $BW -B $OFFLINE $PL help:effective-pom 2>/dev/null \
    | grep -A3 "<artifactId>$A</artifactId>" | grep -E 'artifactId|version' | sed 's/^ *//' | paste - - | sort -u | head -n 5
fi
