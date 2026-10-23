---
description: "Triage and fix a list of vulnerable dependencies from a scanner export"
agent: security-fixer
argument-hint: "path to findings CSV or Xray JSON, optional severity floor"
---
Fix the findings in `${input:csv:findings.csv}` for `${input:scope:the whole repository}`. Severity floor: ${input:floor:HIGH}.
Start with `scripts/cve-flow.sh ${input:csv:findings.csv} --min-severity ${input:floor:HIGH}` and read only the table it prints; it converts an Xray JSON itself.

Run Phase 0 triage and show me the grouped plan (artifact, strategy, target version). Wait for my confirmation before any edit. Then apply one group per commit and finish with the before/after triage table.
