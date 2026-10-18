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
