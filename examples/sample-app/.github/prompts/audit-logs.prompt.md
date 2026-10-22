---
description: "Inventory the log statements of a module or the repository: counts, noise and risk flags, top emitters, config findings; prices them if a log-sink export is given (read-only)"
agent: log-engineer
argument-hint: "module or '.'  [export.csv] [price per GB]"
---
Audit logging in `${input:scope:.}`${input:export: with the sink export at ${input:export}}.

Run Phase 0 only: the facts, `LogAudit`, `LogVolume` if an export is given, the lint counts. Show me the summary,
the top 25 statements with their flags, the PII candidates, and the config findings. Propose the three reduction
themes with the biggest estimated effect and the boundaries that have no logs at all. Change nothing.
