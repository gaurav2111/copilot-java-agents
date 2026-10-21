---
description: "Triage and fix a list of vulnerable dependencies from a scanner export"
agent: security-fixer
argument-hint: "path to findings CSV, optional severity floor"
---
Fix the findings in `${input:csv:findings.csv}` for `${input:scope:the whole repository}`. Severity floor: ${input:floor:HIGH}.

Run Phase 0 triage and show me the grouped plan (artifact, strategy, target version). Wait for my confirmation before any edit. Then apply one group per commit and finish with the before/after triage table.
