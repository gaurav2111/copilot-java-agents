# Issues found, out of scope for this task

- [medium] Not a git repository. The protocol's "clean tree, work branch" check cannot run. Owner: user. Suggested: `git init && git add -A && git commit -m "baseline"` before any agent edits.
- [high] Four known CVEs in the dependency tree (commons-text 1.9, snakeyaml 1.30, jackson-databind 2.13.5, guava 30.1-jre) - from findings.csv and build/deptree.txt. Separate task: /fix-cves.
- [medium] :legacy-reports is on JUnit 4 + Hamcrest. Separate task: /migrate.
- [low] PricingEngine 8% line coverage, OrderController 0% - from CoverageGaps. Separate task: /raise-coverage.
- [info] `options.release` is set once in the root `subprojects {}` block; a Java level change is therefore build-wide unless a per-project override is added (G1 question).
- [pack] rewrite.yml `Java21UpgradeMinimal` recipeList[7] rejected by the recipe validator ("refers to a recipe that doesn't exist"). Being verified against the jar and corrected in the master pack.
- [pack] rewrite.sh showed a stale rewrite.patch from a previous run when the current run produced none. Fixed: old patches and data tables are deleted before each run; validation errors are now echoed in a dedicated block.
