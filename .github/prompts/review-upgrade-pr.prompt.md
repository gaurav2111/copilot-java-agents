---
description: "Review an upgrade/migration/security branch against the repo's rules before opening the PR (read-only)"
agent: planner
argument-hint: "base branch, e.g. main"
---
Review the current branch against `${input:base:main}` as a pre-PR check. Use `git diff --stat ${input:base}...HEAD` and `git log --oneline ${input:base}..HEAD`, then open only files whose diff you cannot judge from the stat.

Check and report as a checklist with PASS / FAIL / N/A and one line of evidence each:
1. One concern per commit; mechanical recipe commits separate from manual fixes.
2. No syntax modernisation mixed into an upgrade or migration.
3. Every build-file version change has a reason comment or `because(...)`; versions only in the catalog / parent, not duplicated in subprojects; no new repositories; no `resolutionStrategy.force`.
4. No `@Disabled`, weakened assertions, or deleted tests without a ticket in the message.
5. No `--add-opens`, `argLine`, or security-manager flags added silently.
6. No secrets, hostnames, or customer-like data in fixtures.
7. Report section present: recipes run, manual fixes with why, verification commands, open risks.
Do not fix anything. List what must change before the PR is opened.
