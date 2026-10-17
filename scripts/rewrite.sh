#!/usr/bin/env bash
# Run an OpenRewrite recipe WITHOUT modifying the build. Dry run by default; pass --apply to write changes.
# ALWAYS run from the repository root: the plugin parses the whole build and runs recipes once over all modules.
# Scope with preconditions in rewrite.yml, or revert out-of-scope modules afterwards with `git checkout -- <module>`.
#
# Usage: scripts/rewrite.sh <recipe> [recipeArtifact[,artifact2]] [--apply] [-- extra build args]
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
GOAL="dryRun"
if [ "${1:-}" = "--apply" ]; then GOAL="run"; shift; fi
[ "${1:-}" = "--" ] && shift

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

echo "openrewrite $GOAL [$BUILD] recipe=$RECIPE artifacts=${COORDS:-<built-in>}"
if [ "$BUILD" = gradle ]; then
  TASK="rewriteDryRun"; [ "$GOAL" = run ] && TASK="rewriteRun"
  PLUGIN_VERSION="${REWRITE_PLUGIN_VERSION:-7.41.0}"
  export GRADLE_OPTS="${GRADLE_OPTS:--Xmx8g}"
  # shellcheck disable=SC2086
  $BW --console=plain $OFFLINE "$TASK" --init-script "$(dirname "$0")/rewrite-init.gradle" \
    -Drewrite.activeRecipe="$RECIPE" \
    ${COORDS:+-Drewrite.recipeArtifacts="$COORDS"} \
    -Drewrite.pluginVersion="$PLUGIN_VERSION" \
    ${REWRITE_PLUGIN_REPO:+-Drewrite.pluginRepo="$REWRITE_PLUGIN_REPO"} \
    -Drewrite.configFile="${REWRITE_CONFIG:-rewrite.yml}" \
    -Dorg.gradle.jvmargs=-Xmx8g \
    "$@" 2>&1 | grep -vE '^(Download|Progress|> Task)' | tail -n 80
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
    "$@" 2>&1 | grep -vE '^\[INFO\] (Downloading|Downloaded|Progress)' | tail -n 80
  STATUS=${PIPESTATUS[0]}
  PATCHES=$(find . -path '*/target/rewrite/rewrite.patch' -size +0 2>/dev/null)
  TABLES=$(find . -path '*/target/rewrite/datatables/*' -name '*.csv' 2>/dev/null | head -n 20)
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
  done
fi
echo "--- data tables (CSV) if any:"; echo "${TABLES:-none}"
exit "$STATUS"
