---
description: "Add the logs a class, module or flow is missing, under the logging policy: boundary completion lines, business events, outbound call summaries, exception causes, correlation id. Never changes behaviour."
agent: log-engineer
argument-hint: "module | target (class list, package, or a flow in words) | optional: structured format (ecs|logstash|gelf)"
---
Add logging in module `${input:module}` to: ${input:target}.

Do this, in order, and stop where it says:
1. Read `.github/LOGGING-POLICY.md`. Run `java scripts/LogAudit.java <module> 15` so you know what already exists; do not add a line that duplicates one.
2. For each class in the target, list the boundaries and events the policy says must be logged and are missing: inbound completion (outcome, duration, identifiers), outbound calls (target, duration, status; WARN on retry or fallback), business events (INFO with the identifiers a support engineer searches for), catch blocks (cause attached or rethrown, never both). Show me that list as the plan and WAIT.
3. On go: one class per COMMIT POINT. Placeholders only, stable message text, no payloads, no PII, MDC for the correlation id at the boundary if the project has none. ${input:format:Keep the project's current output format.}
4. After each class: `scripts/log-lint.sh <files>` must be clean, `scripts/compile-errors.sh <module>` green, and one test for an ERROR path proving the cause is attached (ListAppender or OutputCaptureExtension), unless such a test exists.
5. Report: lines added per class and boundary, before/after counts from LogAudit, anything the policy forbade that I asked for.

Never change production behaviour, never add computation inside log arguments, never touch messages that alerts or runbooks depend on.
