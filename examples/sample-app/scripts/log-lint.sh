#!/usr/bin/env bash
# Gate for log statements before a COMMIT POINT. Exits 2 when something must not ship, 1 on warnings only.
# Usage: scripts/log-lint.sh [path ...]          (default: files changed in the working tree + staged)
#   scripts/log-lint.sh                        # git diff against HEAD
#   scripts/log-lint.sh order-service/src/main  # a tree
set -uo pipefail
if [ $# -gt 0 ]; then FILES=$(find "$@" -name '*.java' 2>/dev/null); else FILES=$(git diff --name-only HEAD -- '*.java' 2>/dev/null; git diff --name-only --cached -- '*.java' 2>/dev/null); fi
FILES=$(echo "$FILES" | sort -u | grep -v '/src/test/' || true)
[ -z "$FILES" ] && { echo "LOG LINT: no Java files to check"; exit 0; }
BLOCK=0; WARN=0
PII='(?i)\b(password|passwd|pwd|secret|token|apikey|api_key|authorization|bearer|cookie|ssn|credit[_-]?card|cardnumber|cvv|iban|account[_-]?number)\b'
for f in $FILES; do
  [ -f "$f" ] || continue
  # 1. PII / secrets in log calls (block)
  grep -nP "\b(log|logger|LOG|LOGGER)\.(trace|debug|info|warn|error)\s*\(.*$PII" "$f" 2>/dev/null | sed "s|^|BLOCK pii-in-log $f:|" && BLOCK=1
  # 2. System.out / printStackTrace (block)
  grep -nE "System\.(out|err)\.print|\.printStackTrace\(" "$f" | sed "s|^|BLOCK sysout $f:|" && BLOCK=1
  # 3. string concatenation in a log call (warn; recipe-fixable)
  grep -nE "\b(log|logger|LOG|LOGGER)\.(trace|debug|info|warn|error)\s*\(\s*\"[^\"]*\"\s*\+" "$f" | sed "s|^|WARN concat $f:|" && WARN=1
  # 4. whole-object logging (warn)
  grep -nE "\b(log|logger|LOG|LOGGER)\.(info|warn|error)\s*\(.*\b(request|response|body|entity|payload|headers)\b" "$f" | grep -vE "\.(id|getId|size)\(" | sed "s|^|WARN payload $f:|" && WARN=1
  # 5. INFO+ inside an obvious loop on the same or previous line (warn)
  awk -v F="$f" 'prev ~ /\b(for|while)\s*\(|\.forEach\(/ && $0 ~ /\b(log|logger|LOG|LOGGER)\.(info|warn|error)\s*\(/ { print "WARN info-in-loop " F ":" NR ":" $0 } { prev=$0 }' "$f" && WARN=1
done
[ $BLOCK -eq 1 ] && { echo "LOG LINT: BLOCK (fix the BLOCK lines before the commit point)"; exit 2; }
[ $WARN -eq 1 ] && { echo "LOG LINT: warnings only"; exit 1; }
echo "LOG LINT: clean"; exit 0
