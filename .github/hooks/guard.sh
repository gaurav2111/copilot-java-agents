#!/usr/bin/env bash
# preToolUse guard for Copilot agent mode (Copilot hooks format: CLI, cloud agent, VS Code, JetBrains >= 1.10).
# Reads the tool call JSON from stdin, denies (exit 2) commands no maintenance agent should ever run.
# Verify on your plugin version: run a chat that asks the agent to `git push --force` and confirm it is blocked.
set -u
INPUT=$(cat)
# Only inspect shell-type tools; let everything else through.
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
exit 0
