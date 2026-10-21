# OpenRewrite adoption plan

Status: draft for discussion, 2026-10-19. Owner: _fill in_. Next steps are listed in section 7; add yours there.

## 1. Goal

Use OpenRewrite for every code change that can be expressed as a rule, so that the Copilot agents only handle
judgment work. Three outcomes: upgrades and migrations become one recipe commit plus a small manual residue;
repositories stay in a known-good state between upgrades; upgrade planning starts from an inventory, not guesses.

## 2. Where we are today

| Item | State |
|---|---|
| Runner | `scripts/rewrite.sh` + `scripts/rewrite-init.gradle`: dry run by default, no build-file changes, per-module summary, loud failure. Maven path equivalent. |
| Recipes in use | upgrader (Java, Spring Boot, Hibernate, Jackson), migrator (JUnit, Mockito, Hamcrest, Jakarta, logging, Joda, Guava, HttpClient), security-fixer (dependency bumps) |
| Bundles | `rewrite.yml`: Java21Upgrade, Java21UpgradeMinimal, SpringBoot3Upgrade, TestStackModernise, SecurityHygiene (proprietary), BumpDependency, PostMigrationCleanup |
| Verified | Gradle init-script path on `examples/sample-app`: core recipe, JUnit 4 -> 5 bundle, Java 21 minimal bundle. Recipe names checked against the jars. |
| Verified at the office | First online run resolves plugin 7.39.0 and recipe artifacts through the corporate mirror (2026-10-19). |
| Not in use | Proprietary artifacts (`rewrite-java-security`, `io.moderne.recipe:*`), Moderne CLI, scheduled runs, cross-repository inventory |

## 3. Recipe tiers

Licence: A = Apache 2.0, M = Moderne Source Available (company may run on its own code; legal sign-off once),
P = Moderne Proprietary (subscription). Artifact group is `org.openrewrite.recipe` unless stated.

### Tier 1: hygiene, run repeatedly on every repository
| Recipe | Artifact | Lic. | What it fixes |
|---|---|---|---|
| `org.openrewrite.java.RemoveUnusedImports` | core | A | unused imports; skips files with unresolved types |
| `org.openrewrite.staticanalysis.CodeCleanup` | rewrite-static-analysis | M | 25 formatting-neutral cleanups |
| `org.openrewrite.java.logging.slf4j.Slf4jBestPractices` | rewrite-logging-frameworks | M | parameterised logging, logger declarations, exceptions logged with cause |
| `org.openrewrite.java.testing.junit5.JUnit5BestPractices` | rewrite-testing-frameworks | M | Jupiter idioms, JUnit 5.14 |
| `org.openrewrite.java.testing.assertj.Assertj` | rewrite-testing-frameworks | M | one assertion dialect |
| `org.openrewrite.java.testing.cleanup.TestsShouldNotBePublic` | rewrite-testing-frameworks | M | test visibility |
| `org.openrewrite.java.testing.testcontainers.TestContainersBestPractices` | rewrite-testing-frameworks | M | Testcontainers 2, explicit images |

### Tier 2: migrations, once per repository when due
| Recipe | Artifact | Lic. |
|---|---|---|
| `org.openrewrite.java.migrate.UpgradeToJava17` / `UpgradeToJava21` / `UpgradeToJava25` (includes syntax modernisation; use `com.<org>.Java21UpgradeMinimal` to exclude it) | rewrite-migrate-java | M |
| `org.openrewrite.java.migrate.jakarta.JavaxMigrationToJakarta` | rewrite-migrate-java | M |
| `org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_5`, `org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0` | rewrite-spring | M |
| `org.openrewrite.java.spring.framework.UpgradeSpringFramework_6_2` / `_7_0`; `security6.UpgradeSpringSecurity_6_5`, `security7.UpgradeSpringSecurity_7_0` | rewrite-spring | M |
| `org.openrewrite.hibernate.MigrateToHibernate66` / `70` / `72` | rewrite-hibernate | M |
| `org.openrewrite.java.jackson.UpgradeJackson_2_3` | rewrite-jackson | A |
| `org.openrewrite.java.testing.junit5.JUnit4to5Migration`, `junit6.JUnit5to6Migration`, `mockito.Mockito1to5Migration`, `hamcrest.MigrateHamcrestToAssertJ` | rewrite-testing-frameworks | M |
| `org.openrewrite.java.logging.slf4j.Log4jToSlf4j` / `Log4j2ToSlf4j1` / `JulToSlf4j` / `CommonsLogging1ToSlf4j1` | rewrite-logging-frameworks | M |
| `org.openrewrite.java.migrate.guava.NoGuava`, `org.openrewrite.java.joda.time.NoJodaTime` (rewrite-joda), `org.openrewrite.apache.httpclient5.UpgradeApacheHttpClient_5` (rewrite-apache) | as noted | M |
| `org.openrewrite.java.migrate.lombok.LombokBestPractices` | rewrite-migrate-java | M |

### Tier 3: dependency hygiene
| Recipe | Artifact | Lic. |
|---|---|---|
| `org.openrewrite.java.dependencies.UpgradeDependencyVersion` (direct and managed), `UpgradeTransitiveDependencyVersion` (adds constraint / dependencyManagement) | rewrite-java-dependencies | A |
| `org.openrewrite.java.dependencies.RemoveRedundantDependencies`, `ChangeDependency`, `RemoveDependency` | rewrite-java-dependencies | A |
| `org.openrewrite.maven.UpgradeParentVersion` | core | A |
| `org.openrewrite.java.logging.log4j.UpgradeLog4J2DependencyVersion` (Log4Shell class) | rewrite-logging-frameworks | M |

### Tier 4: inventory, read-only, produce data tables
| Recipe | Artifact | Lic. | Table |
|---|---|---|---|
| `org.openrewrite.java.search.FindDeprecatedUses` | core | A | deprecated API usages |
| `org.openrewrite.java.dependencies.DependencyInsight` (`groupIdPattern`, `artifactIdPattern`) | rewrite-java-dependencies | A | `DependenciesInUse` |
| `org.openrewrite.java.dependencies.DependencyList` | rewrite-java-dependencies | A | `DependencyListReport` |
| `org.openrewrite.java.search.FindMissingTypes` | core | A | parse-quality check: unresolved types |
| `org.openrewrite.java.migrate.search.*` (e.g. find Java version, find JVM internals) | rewrite-migrate-java | M | per recipe |

### Tier 5: proprietary, only with a Moderne subscription
`org.openrewrite.java.dependencies.DependencyVulnerabilityCheck` (GitHub advisory DB, auto-fix within a delta),
`DependencyLicenseCheck`, `RemoveUnusedDependencies`, `org.openrewrite.java.security.JavaSecurityBestPractices`,
`OwaspTopTen2025`, `org.openrewrite.java.security.search.Find*`, `io.moderne.java.spring.boot4.UpgradeSpringBoot_4_1`+.
Decision needed: is the subscription worth it for recurring security findings? (section 8)

## 4. Operating modes

1. **Task-driven** (exists): an agent maps a task to a recipe, dry-runs, shows counts, applies after approval, verifies. One recipe commit, never mixed with manual edits.
2. **Scheduled hygiene** (to build): CI job dry-runs the Tier 1 bundle on every repository weekly; opens a PR only when the patch is non-empty. Zero model cost; keeps repositories in the state the agents expect.
3. **Inventory** (to build): Tier 4 recipes with `exportDatatables`, run across repositories, CSVs merged into one table. Answers "which repositories still have X" before any upgrade is planned.

## 5. Governance rules

- Every recipe choice is a named bundle in `rewrite.yml` under the organisation's prefix; agents and CI run bundle names, not raw recipe lists.
- Artifact versions pinned (`REWRITE_RECIPE_VERSION`), bumped deliberately, re-verified on the reference repository.
- A bundle is dry-run on the reference repository and its patch reviewed before it is allowed in CI or in an agent table.
- Recipe commits contain only recipe output. Manual residue is a separate commit. Syntax modernisation is a separate PR from a version upgrade.
- Run from the repository root; never `-pl` / per-project. Scope with `preconditions` in `rewrite.yml` when needed.
- `FindMissingTypes` is run once per repository to confirm the parse is complete; unresolved types mean silent misses.
- Nothing from Tier 5 is referenced unless the subscription exists; the `SecurityHygiene` bundle stays marked optional.

## 6. Prerequisites

| Prerequisite | State | Owner |
|---|---|---|
| Mirror proxies Gradle Plugin Portal + Maven Central for `org.openrewrite` | works (first online run succeeded 2026-10-19) | |
| Mirror proxies Code Genome repository for post-August-2026 versions | not requested; pins stay on 7.39.0 / 6.46.1 and last Central recipe versions | |
| Legal sign-off for MSAL recipes on company code | open | |
| Decision on Moderne subscription (Tier 5) | open | |
| JaCoCo XML enabled, `integrationTest` task naming, catalog or `ext{}` conventions documented per repository | per repository | |

## 7. Work items (status: todo / doing / done; add yours below)

| # | Item | Status | Notes |
|---|---|---|---|
| 1 | Hygiene bundle `com.<org>.Hygiene` (Tier 1) in `rewrite.yml` + `scripts/hygiene.sh` (dry run, non-zero exit on non-empty patch) | todo | verify on `examples/sample-app` first |
| 2 | Inventory bundle `com.<org>.Inventory` (Tier 4) + `scripts/MergeDataTables.java` to combine CSVs across runs/repositories | todo | mirrors `CveTriage` approach |
| 3 | `/hygiene` and `/inventory` prompts; planner preflight reads the inventory table when present | todo | |
| 4 | Verify every Tier 2 recipe name in the agent tables against the jars (as done for the Java 21 bundle) | todo | one command, `unzip -l` on the cached jar |
| 5 | `RECIPES.md` one-pager for the team: tiers, licences, which bundle when, commit rule | todo | derived from this document |
| 6 | CI job definition for scheduled hygiene (Jenkins/GitHub Actions template, weekly, PR on non-empty patch) | todo | needs CI platform decision |
| 7 | Reference repository chosen for bundle verification | todo | `examples/sample-app` for now; pick one internal repo |
| 8 | Legal sign-off on MSAL use | todo | |
| 9 | Moderne subscription decision | todo | |
| 10 | _your next step_ | | |

## 8. Open decisions

1. Scheduled hygiene: weekly PR per repository, or one PR per quarter with everything? (weekly is smaller and safer)
2. Who reviews recipe PRs: the owning team, or a platform team that owns the bundles?
3. Moderne subscription: yes / no / trial on one repository.
4. Inventory storage: a sheet, a wiki page, or a small dashboard fed from the merged CSV.
5. Which repository becomes the reference for bundle verification.
