---
description: "Resume an agent task from its .agent-work folder in a fresh chat or on a different model (reads the Handoff block, re-verifies, continues)"
argument-hint: ".agent-work/<folder>"
---
Resume the task in `${input:folder}`.

Follow `.github/AGENT-PROTOCOL.md` section 7: read the Handoff block at the end of `plan.md`, then the four files.
State in three lines where the task stands (last closed step and its result, next action, open questions). If the
model or client differs from the Session line, append "continued on <model> from step n" to `decisions.md` and update
the Session line. Re-run the verification command of the last closed step and show its one-line result; if it differs
from the recorded result, stop and show the difference. Then continue from the first `[ ]` or `[!]` step using the agent
named in `plan.md`, reading only what that step needs, and stop at the next gate.
