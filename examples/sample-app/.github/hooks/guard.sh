#!/usr/bin/env bash
# preToolUse guard for Copilot agent mode (Copilot hooks format: CLI, cloud agent, VS Code, JetBrains >= 1.10).
# Reads the tool call JSON from stdin, denies (exit 2) commands no maintenance agent should ever run.
# Verify on your plugin version: run a chat that asks the agent to `git push --force` and confirm it is blocked.
set -u
INPUT=$(cat)
# --- team rules: path boundaries apply to every non-read tool (edit, create, shell) ---
RULES=".github/AGENT-RULES.md"
if [ -f "$RULES" ] && ! echo "$INPUT" | grep -qiE '"toolName"\s*:\s*"(read|view|get|search|grep|glob|find|list|file_search|read_file|readFile|codebase)[^"]*"'; then
  while IFS= read -r line; do
    g="${line#- path: }"; g="${g%%#*}"; g="$(echo "$g" | sed 's/[[:space:]]*$//')"
    # glob -> regex: escape dots, ** -> .*, * -> [^/]*
    rx="$(printf '%s' "$g" | sed -e 's/\./\\./g' -e 's#\*\*#__DS__#g' -e 's#\*#[^/]*#g' -e 's#__DS__#.*#g')"
    if echo "$INPUT" | grep -qE -e "(^|[\"'[:space:]/=])${rx}"; then
      echo "BLOCKED by AGENT-RULES.md hard boundary (path: $g). Agents do not modify this path." >&2; exit 2
    fi
  done < <(grep -E '^- path: ' "$RULES")
fi

# Only inspect shell-type tools below; let everything else through.
echo "$INPUT" | grep -qiE '"toolName"\s*:\s*"(execute|bash|shell|powershell|run_in_terminal|runInTerminal)[^"]*"' || exit 0

DENY_PATTERNS=(
  # --- git: agents are read-only on git; the user commits at COMMIT POINTs (AGENT-PROTOCOL.md section 5)
  'git +(add|commit|switch|stash|rebase|merge|cherry-pick|tag|push|am|apply)( |$)'
  'git +checkout +(-b|-B|--orphan|\.)'
  'git +(reset +--hard|clean +-[a-z]*f|branch +-D)'
  # --- downloads and installs: never automatic
  '(^|[^a-zA-Z0-9_-])(brew|apt(-get)?|yum|dnf|choco|winget|scoop|sdk|jabba|asdf|pip3?|npm|npx) +(install|i|add|use)( |$)'
  '(^|[^a-zA-Z0-9_-])gradlew? +wrapper( |$)'
  '(^|[^a-zA-Z0-9_-])(wget +|curl +-[a-zA-Z]*[Oo])'
  'foojay|toolchainManagement'
  'mvn[^|]* +(deploy|release:)'            # publishing
  'gradle[^|]* +publish'
  'rm +-[a-z]*r[a-z]* +(/|~|\.\.|\$HOME)'  # recursive delete outside the tree
  'curl[^|]*\| *(ba)?sh'                   # pipe-to-shell installs
  'chmod +-R +777'
  'kubectl +(delete|apply)|helm +(install|upgrade|uninstall)|docker +push|terraform +(apply|destroy)'
  'nexus|artifactory.*(DELETE|--delete)'
)
for p in "${DENY_PATTERNS[@]}"; do
  if echo "$INPUT" | grep -qiE "$p"; then
    echo "BLOCKED by .github/hooks/guard.sh: matches '$p'. Maintenance agents may not run this; do it manually if intended." >&2
    exit 2
  fi
done

# --- team rules from .github/AGENT-RULES.md (section 1 machine-readable lines) ---
RULES=".github/AGENT-RULES.md"
if [ -f "$RULES" ]; then
  while IFS= read -r line; do
    case "$line" in
      "- command: "*)
        rx="${line#- command: }"; rx="${rx%%#*}"; rx="$(echo "$rx" | sed 's/[[:space:]]*$//')"
        if echo "$INPUT" | grep -qiE -e "$rx"; then
          echo "BLOCKED by AGENT-RULES.md hard boundary (command: $rx)." >&2; exit 2
        fi;;
    esac
  done < <(grep -E '^- command: ' "$RULES")
fi
exit 0
