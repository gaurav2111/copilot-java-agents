# Report: upgrade build to Java 21 (sample-app) - assembled from facts.md, decisions.md, plan.md, issues.md

## 1. What changed
- `build.gradle.kts`: `options.release` 17 -> 21 in the root `subprojects {}` block (one line plus its comment). Applies to :common, :order-service, :legacy-reports (D1).
- No source changes. No wrapper change (8.14.2 already supports 21). No toolchain block added (protocol: no JDK downloads).
- Pack files in this repo copy: `scripts/compile-errors.sh`, `scripts/test-summary.sh`, `scripts/rewrite.sh`, `rewrite.yml` received fixes found during this run (see issues.md [pack] entries); in a real repository these would be a separate pack-maintenance change.

## 2. Recipes and scripts run
- `scripts/jdk-check.sh 21 order-service`: jdeps internal API use none; jdeprscan for-removal none; trouble spot `javax.annotation` x1 (compiles on 21 via Boot 2.7's jakarta.annotation-api 1.3.5; stays until the Boot 3 plan).
- `scripts/rewrite.sh com.example.Java21UpgradeMinimal org.openrewrite.recipe:rewrite-migrate-java`: validation clean, "would make no changes". COMMIT POINT 2 skipped.
- `scripts/compile-errors.sh`, `scripts/test-summary.sh` x3, `./gradlew --offline -q build`, 25 s `bootRun` smoke.

## 3. Manual fixes
- None in project code.

## 4. Verification evidence
| Check | Command | Result |
|---|---|---|
| compile, all projects | `scripts/compile-errors.sh` | COMPILE OK [gradle] |
| bytecode level | `javap -v ... \| grep "major version"` on OrderService, Money, ReportFormatter, OrderController | 65 (Java 21) each |
| tests | `scripts/test-summary.sh order-service` / `legacy-reports` / `common` | 2/2, 2/2, no test sources; equal to baseline |
| full build | `./gradlew --offline -q build` | exit 0 |
| smoke | `:order-service:bootRun` 25 s | "Started SampleApplication in 0.727 seconds", Tomcat 8080, no ERROR |

## 5. Open risks, debt, deferred (from issues.md)
- ByteBuddy 1.12.23 via Spring Boot 2.7.18 cannot create Mockito mocks on JDK 21. No test uses Mockito today; the first one that does will fail. Fix with the Spring Boot 3 upgrade or a `net.bytebuddy:byte-buddy` constraint. Owner: next person adding a mock.
- Four CVEs in the tree (commons-text 1.9, snakeyaml 1.30, jackson-databind 2.13.5, guava 30.1-jre): separate task `/fix-cves findings.csv`.
- :legacy-reports on JUnit 4 + Hamcrest: separate task `/migrate`.
- PricingEngine 8 % and OrderController 0 % line coverage: separate task `/raise-coverage`.
- Spring Boot 2.7.x is out of OSS support (not verified in this session; listed, not asserted).

## Commit points
- COMMIT POINT 1 (step 2): `build: compile all projects for Java 21` - **not yet committed** (D5). Tree: build.gradle.kts, plan.md, report.md, decisions.md, issues.md, scripts/*, rewrite.yml.
- COMMIT POINT 2 (step 3): skipped, recipe made no changes.

No files were changed outside the task scope and `.agent-work/`, except the pack fixes listed in section 1.
