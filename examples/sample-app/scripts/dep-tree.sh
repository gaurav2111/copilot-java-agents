#!/usr/bin/env bash
# Dump the full runtime dependency tree of every module into ONE file for scripts/CveTriage.java.
# Usage: scripts/dep-tree.sh [outfile]
#   Gradle default: build/deptree.txt     Maven default: target/deptree.txt
# The agent never reads this file; CveTriage.java does.
set -uo pipefail
source "$(dirname "$0")/lib.sh"; detect_build_tool
OUT="${1:-$BUILD_DIR/deptree.txt}"
mkdir -p "$(dirname "$OUT")"; : > "$OUT"

if [ "$BUILD" = gradle ]; then
  CONF="${GRADLE_CONFIGURATION:-runtimeClasspath}"
  for P in $(gradle_projects); do
    echo "Project '$P'" >> "$OUT"
    # shellcheck disable=SC2086
    $BW -q $OFFLINE --console=plain "${P}:dependencies" --configuration "$CONF" 2>/dev/null >> "$OUT" || true
    echo >> "$OUT"
  done
else
  # shellcheck disable=SC2086
  $BW -q -B $OFFLINE dependency:tree -DoutputType=text -DoutputFile="$PWD/$OUT" -DappendOutput=true >/dev/null 2>&1 || true
fi
LINES=$(wc -l < "$OUT" | tr -d ' ')
echo "DEPTREE [$BUILD] -> $OUT ($LINES lines). Next: java scripts/CveTriage.java <findings.csv> $OUT"
