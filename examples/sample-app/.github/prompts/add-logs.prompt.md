---
description: "Add the logs the policy says are missing to a class, a module or a flow: boundary completion lines, business events, outbound call summaries, exception causes, correlation id"
agent: log-engineer
argument-hint: "module ClassA,ClassB   or   module 'flow description'"
---
Add logging to ${input:target} in `${input:module}`.

Phase 0 facts and audit first, so I can see what exists. At G1 confirm the boundaries you found and the structured
format in use. Then one COMMIT POINT per class or boundary, every line checked by `scripts/log-lint.sh`, placeholders
only, no payloads, no PII, message texts stable. Add or update one test for an ERROR path per class. Do not change
behaviour.
