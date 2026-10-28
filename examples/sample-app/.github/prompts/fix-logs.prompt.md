---
description: "Clean up logging in a module or the repository: remove noise, correct levels, sanitise PII and secrets, refactor messages to placeholders and stable text, fix config levels. Measured against the log sink when an export is given."
agent: log-engineer
argument-hint: "module or '.' | optional: sink export CSV | optional: price per GB | optional: target such as '30 percent' or '$40/day'"
---
Fix logging in `${input:scope:.}`${input:export:, using the sink export at ${input:export}}${input:target:. No numeric target; apply the policy}.

Do this, in order, and stop where it says:
1. Facts. Read `.github/LOGGING-POLICY.md`. Run `java scripts/LogAudit.java ${input:scope:.} 25 --csv build/log-statements.csv` and, if an export is given, `java scripts/LogVolume.java <export> build/log-statements.csv --price-per-gb ${input:price:0.50}`. Run `scripts/log-lint.sh` for the BLOCK and WARN counts. Record the summary lines in `facts.md`; do not open the CSVs.
2. G1. Show me: counts by level and flag, the top statements, the PII candidates, the config findings, and the measured top emitters if any. Ask, as numbered options, for the list of message texts that alerts or runbooks depend on, and confirm the sink price. WAIT.
3. Plan the work as themes, each its own COMMIT POINT, in this order: (a) config levels (framework packages and root in production profiles), (b) `scripts/rewrite.sh com.example.LoggingHygiene org.openrewrite.recipe:rewrite-logging-frameworks` for the mechanical fixes (placeholders, causes, loggers, System.out, guards) as a recipe-only commit, (c) PII and secrets removed or redacted, (d) levels corrected (INFO in loops and hot paths to DEBUG, entry/exit tracing removed or DEBUG, catch blocks to ERROR with cause or WARN when handled), (e) noise removed (duplicates merged, payload logs replaced by identifiers and sizes), (f) messages refactored to stable text with placeholders and the identifiers a support engineer needs. Show the plan and WAIT.
4. On go, per theme: edit, `scripts/compile-errors.sh`, `scripts/log-lint.sh` (no BLOCK), targeted tests, rerun `java scripts/LogAudit.java` and record the new counts in `plan.md`, COMMIT POINT. Preserved message texts are never changed; a level change on them needs a line in `decisions.md`.
5. Report: statements by level and flag before and after; config changes; top emitters addressed with bytes per day and $ per day each (measured from the export, or marked projected); PII removed; messages preserved; what remains in `issues.md` with an owner.

Removing a line needs a reason from the policy or from measured volume, never taste. Never change the logging framework; that is the migrator's job.
