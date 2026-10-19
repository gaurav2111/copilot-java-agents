---
description: "Validate the agent pack against this repository: prerequisites, every script, stack detection; writes SETUP-REPORT.md and proposes the stack block"
argument-hint: "optional: --quick"
---
Run `scripts/setup-check.sh ${input:flags:}` from the repository root and read `SETUP-REPORT.md`.

Then: (1) show me the result line and the Fix list; (2) for each FAIL or WARN, say in one line whether it is a
one-time command I must run (downloads are mine to run, never yours), a build-file change for me to approve, or a
pack problem to report; (3) show the proposed stack block and ask whether to paste it into
`.github/copilot-instructions.md`. Do not change any file other than `copilot-instructions.md`, and only after my OK.
