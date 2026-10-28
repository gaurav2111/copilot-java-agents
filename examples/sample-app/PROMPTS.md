# Prompt reference: what to type, what runs underneath

One entry per prompt. "Type" is the line you enter in Copilot Chat (Agent mode, the prompt's agent gets selected by
the prompt itself). "Runs" lists the commands the agent executes for that prompt, so you can run them yourself first,
from the repository root, and compare. Every prompt stops at G1 (facts and questions), then at G2 (plan), then at each
COMMIT POINT, where you commit. Nothing below runs git or downloads anything.

Arguments after the prompt name are separated by `|` where the prompt has more than one; the order follows the
argument hint shown by the plugin. Leave one out and the plugin asks, or the default applies.

Before the first prompt in a repository:

```
scripts/setup-check.sh
```

---

## /setup
Validates the pack against this repository and writes `SETUP-REPORT.md`. Agents refuse to start without a passing, current report.

Type: `/setup` or `/setup --quick`

Runs:
```
scripts/setup-check.sh
scripts/setup-check.sh --quick
```

## /plan
Read-only plan for an upgrade, migration, CVE fix or coverage task. Nothing is edited.

Type: `/plan upgrade order-service to Java 21` or `/plan fix the CVEs in xray-report.json for billing`

Runs (which ones depends on the task):
```
scripts/dep-tree.sh
scripts/compile-errors.sh <module>
java scripts/CoverageGaps.java "**" <threshold> 15
java scripts/CveTriage.java <findings.csv> build/deptree.txt
```

## /upgrade-java
Upgrades a module to a target Java version: wrapper and toolchain facts, recipe first, residue second, tests and startup last.

Type: `/upgrade-java order-service 21`

Runs:
```
scripts/jdk-check.sh 21 order-service
scripts/rewrite.sh com.example.Java21Upgrade org.openrewrite.recipe:rewrite-migrate-java           # dry run
scripts/rewrite.sh com.example.Java21Upgrade org.openrewrite.recipe:rewrite-migrate-java --apply   # after your go
scripts/compile-errors.sh order-service
scripts/test-summary.sh order-service
scripts/test-summary.sh order-service '*IT'
./gradlew --offline -q build -x integrationTest        # whole build at the end (Maven: ./mvnw -o -q -DskipITs verify)
```
Smaller bundle when the full one changes too much: `com.example.Java21UpgradeMinimal`. Cleanup afterwards: `com.example.PostMigrationCleanup`.

## /upgrade-spring-boot
Upgrades Spring Boot for a module or the whole reactor; properties, Jakarta and test-slice changes by recipe, residue by hand.

Type: `/upgrade-spring-boot all 3.5` or `/upgrade-spring-boot billing 4.0`

Runs:
```
scripts/dep-path.sh org.springframework.boot:spring-boot
scripts/rewrite.sh com.example.SpringBoot3Upgrade org.openrewrite.recipe:rewrite-spring,org.openrewrite.recipe:rewrite-migrate-java
scripts/rewrite.sh com.example.SpringBoot3Upgrade org.openrewrite.recipe:rewrite-spring,org.openrewrite.recipe:rewrite-migrate-java --apply
scripts/compile-errors.sh <module>
scripts/test-summary.sh <module>
./gradlew :<module>:bootRun                            # startup check, stopped after the context is up
```
Boot 4 targets use the recipes named in the upgrader's table (`UpgradeSpringBoot_4_0`, Jackson 3); 4.1 and 4.2 composites need a Moderne subscription.

## /migrate
A framework or library migration, phased and recipe-first: JUnit 4 to 5, javax to jakarta, Log4j to SLF4J, Mockito, AssertJ.

Type: `/migrate legacy-core junit4 -> junit5`

Runs:
```
scripts/dep-path.sh junit:junit                        # where the old library comes from
scripts/rewrite.sh com.example.TestStackModernise org.openrewrite.recipe:rewrite-testing-frameworks
scripts/rewrite.sh com.example.TestStackModernise org.openrewrite.recipe:rewrite-testing-frameworks --apply
scripts/compile-errors.sh legacy-core
scripts/test-summary.sh legacy-core
./gradlew --offline -q :legacy-core:test :legacy-core:jacocoTestReport && java scripts/CoverageGaps.java "**" 60 20   # coverage must not drop
```

## /fix-cves
Scanner report in, grouped plan out, then one commit point per BOM bump, parent bump or direct bump, with proof.

Type: `/fix-cves xray-report.json | order-service | HIGH` or `/fix-cves findings.csv | all | CRITICAL | --insecure`

Runs:
```
scripts/cve-flow.sh xray-report.json --module order-service --min-severity HIGH
scripts/cve-flow.sh xray-report.json --module order-service --min-severity HIGH --delta patch=apply,minor=apply,major=ask   # policy override for one run
scripts/dep-path.sh <group:artifact>                   # proof line after each change
scripts/compile-errors.sh <module>
scripts/test-summary.sh <module>                       # every module when a root BOM moved
java scripts/XrayToFindings.java after.json --diff before.json     # closes the loop on the next export
```
Pieces of the flow, for one artifact: `java scripts/XrayToFindings.java <report.json> findings.csv --min-severity HIGH`,
`scripts/dep-tree.sh build/deptree.txt --module order-service`, `java scripts/CveTriage.java findings.csv build/deptree.txt`,
`java scripts/BomFix.java <group:artifact> <fix> --module order-service`, `java scripts/BomFix.java <child> <fix> --via <parent:g:a:v>`.
The delta policy comes from the `- cve-delta:` line in `.github/AGENT-RULES.md`. Live scanner lookups need an MCP server: `docs/MCP-SETUP.md`.

## /fix-sast
Code-level security findings in named classes, each with a failing-first test.

Type: `/fix-sast SQL injection in ReportRepository, OrderQuery` or `/fix-sast sast-export.json`

Runs:
```
scripts/rewrite.sh com.example.SecurityHygiene org.openrewrite.recipe:rewrite-java-security        # mechanical fixes first
scripts/test-summary.sh <module> <TestClass>
scripts/compile-errors.sh <module>
```

## /add-tests
Unit tests for named classes, in the module's existing test style.

Type: `/add-tests order-service PricingEngine,OrderService`

Runs:
```
scripts/test-summary.sh order-service PricingEngineTest
./gradlew --offline -q :order-service:test :order-service:jacocoTestReport
java scripts/CoverageGaps.java "**" 100 50 | grep PricingEngine
```

## /unit-tests
Complete unit tests: every public method against every applicable input and behaviour case, tracked by the case matrix.

Type: `/unit-tests order-service | OrderController` or `/unit-tests order-service | OrderService#cancel` or `/unit-tests billing | com.acme.billing.tax` or `/unit-tests billing | all | parameterized | 5`

Runs:
```
java scripts/TestMatrix.java order-service OrderController          # before
java scripts/CoverageGaps.java "**" 100 30                          # ordering for a package or 'all', when a JaCoCo report exists
scripts/test-summary.sh order-service OrderControllerTest
java scripts/TestMatrix.java order-service OrderController --all    # after; n/a lines in the test class count as resolved
scripts/test-summary.sh order-service                               # after each batch
```
Case keys: `.github/TEST-CASES.md`.

## /raise-coverage
Raises a module's line coverage to a target, riskiest under-covered classes first.

Type: `/raise-coverage billing 75 6`

Runs:
```
./gradlew --offline -q :billing:test :billing:jacocoTestReport      # Maven: ./mvnw -o -q -pl billing -am verify -DskipITs
java scripts/CoverageGaps.java "**" 75 15
scripts/test-summary.sh billing <TestClass>
java scripts/CoverageGaps.java "**" 100 50 | grep <ClassName>
./gradlew --offline -q :billing:pitest -Ppitest.targetClasses=<fqcn> -Ppitest.targetTests=<fqcn>Test   # only if PIT is configured
```

## /add-integration-test
One integration test for a business flow, narrowest Spring slice, Testcontainers or WireMock.

Type: `/add-integration-test order-service 'POST /orders with an out-of-stock item returns 409'`

Runs:
```
scripts/test-summary.sh order-service 'OrderPlacementIT'
scripts/test-summary.sh order-service '*IT'
```

## /add-logs
Adds the logs a class, package or flow is missing under the logging policy. Never changes behaviour.

Type: `/add-logs order-service | OrderController, OrderService.cancel` or `/add-logs order-service | order cancellation flow | ecs`

Runs:
```
java scripts/LogAudit.java order-service 15
scripts/log-lint.sh order-service/src/main/java/com/acme/order/OrderController.java
scripts/compile-errors.sh order-service
scripts/test-summary.sh order-service <TestClass>
```

## /fix-logs
Cleans up existing logging: config levels, recipe pass, PII, levels, noise, messages; one commit point per theme.

Type: `/fix-logs order-service | log-export.csv | 0.50` or `/fix-logs . | | | 30 percent`

Runs:
```
java scripts/LogAudit.java order-service 25 --csv build/log-statements.csv
java scripts/LogVolume.java log-export.csv build/log-statements.csv --price-per-gb 0.50
scripts/log-lint.sh order-service
scripts/rewrite.sh com.example.LoggingHygiene org.openrewrite.recipe:rewrite-logging-frameworks --module order-service
scripts/rewrite.sh com.example.LoggingHygiene org.openrewrite.recipe:rewrite-logging-frameworks --module order-service --apply
scripts/compile-errors.sh order-service
scripts/test-summary.sh order-service
java scripts/LogAudit.java order-service 25 --csv build/log-statements.csv       # after each theme
```
Policy and prices: `.github/LOGGING-POLICY.md`.

## /document
Documentation in the format you name: sequence, flowchart, ER or C4 diagram, README, API reference, runbook, Javadoc, ADR, plain-language summary.

Type: `/document order placement flow | sequence | new backend dev` or `/document :billing | readme` or `/document InvoiceCalculator | javadoc`

Runs (Javadoc format only):
```
./gradlew --offline -q :<module>:javadoc                # Maven: ./mvnw -o -q -pl <module> javadoc:javadoc
```
Other formats read code and write files; no build command.

## /review-upgrade-pr
Read-only review of an upgrade, migration or security branch against the repository's rules, before the PR.

Type: `/review-upgrade-pr main`

Runs:
```
scripts/compile-errors.sh
scripts/test-summary.sh <module>
scripts/dep-path.sh <group:artifact>                   # for every version that moved
```

## /resume
Continues a task from its `.agent-work` folder in a new chat or on another model.

Type: `/resume .agent-work/2026-10-28-fix-logs`

Runs: whatever the plan's next unticked step names; the agent re-runs that step's verification command before continuing.

---

## Scripts on their own

| Script | What it prints | Typical call |
|---|---|---|
| `scripts/setup-check.sh` | SETUP-REPORT.md: prerequisites, every script against this repo | `scripts/setup-check.sh` |
| `scripts/compile-errors.sh` | distinct compiler errors only | `scripts/compile-errors.sh order-service` |
| `scripts/test-summary.sh` | counts and failing test names from JUnit XML | `scripts/test-summary.sh order-service OrderServiceTest` |
| `scripts/dep-tree.sh` | runtime classpath tree to a file | `scripts/dep-tree.sh build/deptree.txt --module order-service` |
| `scripts/dep-path.sh` | who pulls an artifact, what manages it, the BomFix command | `scripts/dep-path.sh org.apache.tomcat.embed:tomcat-embed-core` |
| `scripts/jdk-check.sh` | jdeps and jdeprscan findings for a target JDK | `scripts/jdk-check.sh 21 order-service` |
| `scripts/rewrite.sh` | OpenRewrite dry run or apply, per-module patch summary | `scripts/rewrite.sh <recipe> <artifact> [--apply] [--module <dir>]` |
| `scripts/log-lint.sh` | BLOCK and WARN lines for log statements | `scripts/log-lint.sh order-service` |
| `scripts/cve-flow.sh` | report to verdict table with the delta policy column | `scripts/cve-flow.sh xray.json --module order-service --min-severity HIGH` |
| `java scripts/XrayToFindings.java` | Xray JSON to findings.csv, one row per artifact and CVE | `java scripts/XrayToFindings.java xray.json findings.csv --min-severity HIGH` |
| `java scripts/CveTriage.java` | direct or transitive, resolved version, strategy per CVE | `java scripts/CveTriage.java findings.csv build/deptree.txt` |
| `java scripts/BomFix.java` | resolved vs BOM-managed vs fix, hop size, override property | `java scripts/BomFix.java <g:a> <fix> --module order-service` |
| `java scripts/CoverageGaps.java` | ranked under-covered classes from JaCoCo XML | `java scripts/CoverageGaps.java "**" 70 15` |
| `java scripts/TestMatrix.java` | methods x cases x what tests mention | `java scripts/TestMatrix.java order-service OrderService` |
| `java scripts/LogAudit.java` | log statements by level and flag, config findings | `java scripts/LogAudit.java order-service 25 --csv build/log-statements.csv` |
| `java scripts/LogVolume.java` | GB and $ per day per statement from a sink export | `java scripts/LogVolume.java export.csv build/log-statements.csv --price-per-gb 0.50` |

Environment switches: `BUILD_OFFLINE=""` for one online build when a dependency is not cached; `REPO_URL`, `REPO_USER`,
`REPO_TOKEN` for BomFix behind a mirror; `REWRITE_PLUGIN_VERSION`, `REWRITE_RECIPE_VERSION`, `REWRITE_PLUGIN_REPO` for OpenRewrite.
