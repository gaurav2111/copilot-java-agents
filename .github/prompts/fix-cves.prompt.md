---
description: "Triage and fix vulnerable dependencies from a scanner report (Xray JSON or findings CSV): one flow command, grouped plan at G1, then bump / parent bump / constraint / exception with proof"
agent: security-fixer
argument-hint: "report (xray.json or findings.csv) | module the report covers (or 'all') | severity floor | extra flags such as --insecure"
---
Fix the findings in `${input:report:xray-report.json}` for module `${input:module:all}`. Severity floor: ${input:floor:HIGH}. Extra flags for the scripts: ${input:flags:none}.

Start with ONE command and read only the table it prints:
`scripts/cve-flow.sh ${input:report:xray-report.json} --min-severity ${input:floor:HIGH}` plus `--module ${input:module}` when a module is given and the extra flags if any.
Each row's verdict is the strategy: stale scan (rebuild and rescan, no change), override (find it with dep-path.sh, do not bump the BOM), BOM bump to the named release, parent bump for a transitive, direct bump, or no fixed version (exception with expiry). The `delta: policy` column applies the team's `- cve-delta` line from `.github/AGENT-RULES.md` (default `patch=apply, minor=ask, major=separate-task`): `apply` rows go into the plan without a question; `ask` rows are G1 questions; `separate-task` rows are never changed in this task, they go to `issues.md` with their facts (artifact, resolved, fix, BOM or parent hop) and a pointer to `/upgrade-spring-boot` or `/plan`. When a row offers both a BOM hop and an override with different classes, take the BOM hop if its action is `apply`; otherwise ask, because an override is debt.

If a scanner MCP tool is available in this chat (JFrog `catalog_*` / `xray_*`, or GitHub `list_dependabot_alerts`; see `docs/MCP-SETUP.md`), refresh the rows marked "no fixed version published" before G1: look the CVE or the package up live, fill the fix with source `xray-live` and today's date in `facts.md`, rerun BomFix for that row, and list the refreshed rows separately at G1. The report file itself is never edited.

Then follow the protocol: facts and questions at G1, wait; plan at G2, wait; one COMMIT POINT per BOM bump, per parent bump, or per direct bump, with `scripts/dep-path.sh` and `scripts/compile-errors.sh` after each; the whole build's tests at the end because a root BOM change touches every module. Finish with the before/after table and, when a rescan JSON is available, `java scripts/XrayToFindings.java <after.json> --diff <before.json>`.
