#!/usr/bin/env bash
# Run an OpenRewrite recipe WITHOUT modifying the build. Dry run by default; pass --apply to write changes.
# ALWAYS run from the repository root: the plugin parses the whole build and runs recipes once over all modules.
# Scope with preconditions in rewrite.yml, or revert out-of-scope modules afterwards with `git checkout -- <module>`.
#
# Usage: scripts/rewrite.sh <recipe> [recipeArtifact[,artifact2]] [--apply] [--module <dir>] [-- extra build args]
#   --module <dir>   keep only the changes under that directory (one module of a multi-module build). The recipe still
#                    parses the whole build; with --apply the filtered patch is applied to the working tree, nothing
#                    outside the module is touched, no revert needed afterwards.
#   scripts/rewrite.sh org.openrewrite.java.migrate.UpgradeToJava21 org.openrewrite.recipe:rewrite-migrate-java
#   scripts/rewrite.sh com.acme.SpringBoot3Upgrade org.openrewrite.recipe:rewrite-spring,org.openrewrite.recipe:rewrite-migrate-java --apply
#   scripts/rewrite.sh org.openrewrite.java.RemoveUnusedImports            # core recipe, no artifact needed
#
# Gradle (default): init script scripts/rewrite-init.gradle, patch at build/reports/rewrite/rewrite.patch.
# Maven: plugin invoked by coordinates, patch at <module>/target/rewrite/rewrite.patch.
# Versions: recipe artifacts stopped publishing to Maven Central / Plugin Portal in August 2026 (see README).
# Defaults below are the last public versions; override via env when your mirror proxies Code Genome.
set -uo pipefail
source "$(dirname "$0")/lib.sh"; detect_build_tool
RECIPE="${1:?recipe name required}"; shift
ARTIFACTS=""
if [ $# -gt 0 ] && [[ "$1" != --* ]]; then ARTIFACTS="$1"; shift; fi
GOAL="dryRun"; MODULE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --apply) GOAL="run"; shift;;
    --module) MODULE="${2:?--module needs a directory}"; MODULE="${MODULE%/}"; [ "$MODULE" = "." ] && MODULE=""; shift 2;;   # "." = whole build
    --) shift; break;;
    *) break;;
  esac
done
[ -n "$MODULE" ] && [ ! -d "$MODULE" ] && { echo "no such module directory: $MODULE"; exit 2; }
APPLY_FILTERED=0
if [ "$GOAL" = run ] && [ -n "$MODULE" ]; then GOAL="dryRun"; APPLY_FILTERED=1; fi   # dry run, then apply only the module's part

ARTIFACT_VERSION="${REWRITE_RECIPE_VERSION:-RELEASE}"      # RELEASE / latest.release = newest your mirror can see
COORDS=""
if [ -n "$ARTIFACTS" ]; then
  IFS=',' read -ra A <<< "$ARTIFACTS"
  for a in "${A[@]}"; do
    case "$a" in *:*:*) COORDS+="$a,";; *) COORDS+="$a:$ARTIFACT_VERSION,";; esac
  done
  COORDS="${COORDS%,}"
fi
[ "$BUILD" = gradle ] && COORDS="${COORDS//:RELEASE/:latest.release}"

# Remove patches and data tables from earlier runs so a "no changes" run is never misread as the previous run's result.
find . \( -path '*/build/reports/rewrite/rewrite.patch' -o -path '*/target/rewrite/rewrite.patch' \) -delete 2>/dev/null
find . \( -path '*/build/reports/rewrite/datatables' -o -path '*/target/rewrite/datatables' \) -type d -prune -exec rm -rf {} + 2>/dev/null
echo "openrewrite $GOAL [$BUILD] recipe=$RECIPE artifacts=${COORDS:-<built-in>}${MODULE:+ module=$MODULE}"
if [ "$BUILD" = gradle ]; then
  TASK="rewriteDryRun"; [ "$GOAL" = run ] && TASK="rewriteRun"
  PLUGIN_VERSION="${REWRITE_PLUGIN_VERSION:-7.39.0}"   # 7.40+ need rewrite-bom 8.91 which is Code Genome only
  export GRADLE_OPTS="${GRADLE_OPTS:--Xmx8g}"
  # shellcheck disable=SC2086
  $BW --console=plain $OFFLINE "$TASK" --init-script "$(dirname "$0")/rewrite-init.gradle" \
    -Drewrite.activeRecipe="$RECIPE" \
    ${COORDS:+-Drewrite.recipeArtifacts="$COORDS"} \
    -Drewrite.pluginVersion="$PLUGIN_VERSION" \
    ${REWRITE_PLUGIN_REPO:+-Drewrite.pluginRepo="$REWRITE_PLUGIN_REPO"} \
    -Drewrite.configFile="${REWRITE_CONFIG:-rewrite.yml}" \
    -Dorg.gradle.jvmargs=-Xmx8g \
    "$@" 2>&1 | tee /tmp/rewrite-last.log | grep -vE '^(Download|Progress|> Task)' | tail -n 80
  STATUS=${PIPESTATUS[0]}
  PATCHES=$(find . -path '*/build/reports/rewrite/rewrite.patch' -size +0 2>/dev/null)
  TABLES=$(find . -path '*/build/reports/rewrite/datatables/*' -name '*.csv' 2>/dev/null | head -n 20)
else
  PLUGIN_VERSION="${REWRITE_PLUGIN_VERSION:-6.46.1}"
  export MAVEN_OPTS="${MAVEN_OPTS:--Xmx8g -XX:+UseParallelGC}"
  # shellcheck disable=SC2086
  $BW -B ${REWRITE_UPDATE:-} "org.openrewrite.maven:rewrite-maven-plugin:${PLUGIN_VERSION}:${GOAL}" \
    -Drewrite.activeRecipes="$RECIPE" \
    ${COORDS:+-Drewrite.recipeArtifactCoordinates="$COORDS"} \
    -Drewrite.exportDatatables=true \
    -Drewrite.configLocation="${REWRITE_CONFIG:-rewrite.yml}" \
    "$@" 2>&1 | tee /tmp/rewrite-last.log | grep -vE '^\[INFO\] (Downloading|Downloaded|Progress)' | tail -n 80
  STATUS=${PIPESTATUS[0]}
  PATCHES=$(find . -path '*/target/rewrite/rewrite.patch' -size +0 2>/dev/null)
  TABLES=$(find . -path '*/target/rewrite/datatables/*' -name '*.csv' 2>/dev/null | head -n 20)
fi

if grep -q "Recipe validation error" /tmp/rewrite-last.log 2>/dev/null; then
  echo "--- RECIPE VALIDATION ERRORS (the run continued but those recipes did nothing):"
  grep "Recipe validation error" /tmp/rewrite-last.log | sed 's/^/    /' | head -n 10
fi
if [ "$STATUS" -ne 0 ]; then
  echo "--- OPENREWRITE BUILD FAILED (exit $STATUS). Nothing was evaluated. Usual causes: plugin/recipe artifact not"
  echo "    resolvable offline (ask the user to run once with BUILD_OFFLINE=\"\"), mirror missing org.openrewrite, or an"
  echo "    unresolvable project dependency. Fix resolution first; do not interpret an empty patch as 'no changes'."
  exit "$STATUS"
fi
if [ "$GOAL" = "dryRun" ]; then
  echo "--- patch summary (read this, not the whole patch):"
  [ -z "$PATCHES" ] && echo "no changes proposed"
  for p in $PATCHES; do
    files=$(grep -c '^diff --git' "$p"); add=$(grep -c '^+[^+]' "$p"); del=$(grep -c '^-[^-]' "$p")
    echo "$p  files=$files +$add -$del"
    grep '^diff --git' "$p" | sed 's#^diff --git a/##; s# b/.*##' | awk -F/ '{print ($0 ~ /\//) ? $1 : "(root)"}' | sort | uniq -c | sort -rn | head -n 15
    echo "  (per top-level module; list files with: grep '^diff --git' $p)"
    grep -q 'Unable to download POM\|Failed to parse or resolve\|~~(' "$p" && echo "  WARNING: unresolved types/POMs in patch markers; recipe matches may be incomplete"
    if [ -n "$MODULE" ]; then
      inside=$(grep -c "^diff --git a/$MODULE/" "$p"); echo "  in module $MODULE: $inside file(s); outside (ignored): $((files - inside))"
    fi
  done
  if [ "$APPLY_FILTERED" -eq 1 ]; then
    applied=0
    for p in $PATCHES; do
      f="${p%.patch}-$(echo "$MODULE" | tr '/' '_').patch"
      # keep only the hunks whose file is under the module; a unified diff is split at each 'diff --git' header
      awk -v M="$MODULE/" '/^diff --git /{keep = index($0, "diff --git a/" M) == 1} keep' "$p" > "$f"
      [ -s "$f" ] || continue
      if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then git apply --whitespace=nowarn "$f"; else patch -p1 -s < "$f"; fi \
        && { echo "--- applied to $MODULE: $(grep -c '^diff --git' "$f") file(s) from $f"; applied=1; } \
        || { echo "--- APPLY FAILED for $f (working tree may be partially changed; inspect it)"; exit 1; }
    done
    [ "$applied" -eq 0 ] && echo "--- nothing to apply under $MODULE"
  fi
fi
echo "--- data tables (CSV) if any:"; echo "${TABLES:-none}"
exit "$STATUS"
