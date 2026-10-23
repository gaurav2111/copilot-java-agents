---
description: "Triage and fix vulnerable dependencies from a scanner report (Xray JSON or findings CSV): one flow command, grouped plan at G1, then bump / parent bump / constraint / exception with proof"
agent: security-fixer
argument-hint: "report (xray.json or findings.csv) | module the report covers (or 'all') | severity floor | extra flags such as --insecure"
---
Fix the findings in `${input:report:xray-report.json}` for module `${input:module:all}`. Severity floor: ${input:floor:HIGH}. Extra flags for the scripts: ${input:flags:none}.

Start with ONE command and read only the table it prints:
`scripts/cve-flow.sh ${input:report:xray-report.json} --min-severity ${input:floor:HIGH}` plus `--module ${input:module}` when a module is given and the extra flags if any.
Each row's verdict is the strategy: stale scan (rebuild and rescan, no change), override (find it with dep-path.sh, do not bump the BOM), BOM bump to the named release, parent bump for a transitive, direct bump, or no fixed version (exception with expiry). Rows marked "needs a decision" and anything that is not a patch-level hop are G1 questions.

Then follow the protocol: facts and questions at G1, wait; plan at G2, wait; one COMMIT POINT per BOM bump, per parent bump, or per direct bump, with `scripts/dep-path.sh` and `scripts/compile-errors.sh` after each; the whole build's tests at the end because a root BOM change touches every module. Finish with the before/after table and, when a rescan JSON is available, `java scripts/XrayToFindings.java <after.json> --diff <before.json>`.
