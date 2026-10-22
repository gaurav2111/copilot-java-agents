# Decisions (append-only: question, options offered, answer, who decided, reason)

| # | Question | Options offered (recommended marked) | Answer | Decided by | Reason |
|---|---|---|---|---|---|
| D1 | Scope of the Java level change (release is set once in root `subprojects {}`) | 1) all three projects <- rec. 2) :order-service only via override 3) other | 1 | user (2026-10-17, G1) | one setting, one verification; an override would leave the build with mixed levels |
| D2 | Include Spring Boot 3.x upgrade in this plan? | 1) no, Java only <- rec. 2) chain Boot 3.5 3) other | 1 | user | one hop per PR; Boot 3.x gets its own plan (issues.md) |
| D3 | Recipe bundle | 1) `com.<org>.Java21UpgradeMinimal` <- rec. 2) full `UpgradeToJava21` 3) other | 1 | user | keep syntax modernisation out of the upgrade PR |
| D4 | No git repository | 1) user runs `git init` + baseline commit first <- rec. 2) proceed without VCS 3) other | 1 | user | protocol requires a clean tree and a work branch before any agent edit |
| D5 | COMMIT POINT 1 reached; user chose to commit manually, then said "continue" with the tree still uncommitted | 1) wait for commit <- rec. 2) continue, commit later | 2 | user | user's call; recorded so the reviewer knows step 2 and step 3 may land in one commit |
