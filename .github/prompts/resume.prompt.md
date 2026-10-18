---
description: "Resume an agent task from its .agent-work folder in a fresh chat (reads facts, decisions, plan, issues; continues at the next open step)"
argument-hint: ".agent-work/<folder> [agent name if not obvious from plan.md]"
---
Resume the task in `${input:folder}`.

Follow `.github/AGENT-PROTOCOL.md` section 7: read the four files in that folder, state in three lines where the task
stands (last closed step, next open step, open questions), re-run the verification command of the last closed step and
show its one-line result, then continue from the first `[ ]` or `[!]` step using the agent named in `plan.md`. Do not
re-read the codebase beyond what the next step needs. Stop at the next gate.
