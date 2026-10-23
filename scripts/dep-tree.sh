#!/usr/bin/env bash
# Dump the full runtime dependency tree of every module into ONE file for scripts/CveTriage.java.
# Usage: scripts/dep-tree.sh [outfile] [--module <project>]
#   Gradle default: build/deptree.txt     Maven default: target/deptree.txt
#   --module limits the dump to one project (when a scanner report covers a single module's artifact).
# The agent never reads this file; CveTriage.java does.
set -uo pipefail
source "$(dirname "$0")/lib.sh"; detect_build_tool
OUT="$BUILD_DIR/deptree.txt"; ONLY=""
while [ $# -gt 0 ]; do case "$1" in --module) ONLY="$2"; shift 2;; *) OUT="$1"; shift;; esac; done
mkdir -p "$(dirname "$OUT")"; : > "$OUT"

if [ "$BUILD" = gradle ]; then
  CONF="${GRADLE_CONFIGURATION:-runtimeClasspath}"
  PROJECTS=$(gradle_projects); [ -n "$ONLY" ] && PROJECTS="$(gradle_path "$ONLY")"
  for P in $PROJECTS; do
    echo "Project '$P'" >> "$OUT"
    # shellcheck disable=SC2086
    $BW -q $OFFLINE --console=plain "${P}:dependencies" --configuration "$CONF" 2>/dev/null >> "$OUT" || true
    echo >> "$OUT"
  done
else
  # shellcheck disable=SC2086
  $BW -q -B $OFFLINE ${ONLY:+-pl "$ONLY"} dependency:tree -DoutputType=text -DoutputFile="$PWD/$OUT" -DappendOutput=true >/dev/null 2>&1 || true
fi
LINES=$(wc -l < "$OUT" | tr -d ' ')
echo "DEPTREE [$BUILD]${ONLY:+ (module $ONLY only)} -> $OUT ($LINES lines). Next: java scripts/CveTriage.java <findings.csv> $OUT"
