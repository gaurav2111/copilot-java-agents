# Plan: upgrade build to Java 21 (all projects, per D1)

Status: DONE. Final COMMIT POINT open: the user has not yet committed steps 2-3 (D5). Agent for execution: upgrader. No files changed outside .agent-work/.

## Task
Raise the Java bytecode level of all three projects from 17 to 21, build-plumbing first, removed-API recipes second, manual residue third, with no syntax modernisation and no framework upgrade.

## Baseline (every value with its source, from facts.md)
- Java 17 (build.gradle.kts:16, `options.release.set(17)` in `subprojects {}`), no toolchain block
- Gradle wrapper 8.14.2 (gradle/wrapper/gradle-wrapper.properties); 8.5+ is enough for Java 21, so no wrapper change
- JDK installed: 21.0.12 (`java -version`); builds already run on 21, so no JDK download is involved
- Spring Boot 2.7.18, Guava 30.1-jre, commons-text 1.9, JUnit 4 in :legacy-reports (gradle/libs.versions.toml)
- 3 projects (settings.gradle.kts); tests: :order-service 2/2 green, :legacy-reports 2/2 green (scripts/test-summary.sh)
- jdeps internal API use: none; jdeprscan for-removal against 21: none; trouble spots: `javax.annotation.@PostConstruct` in OrderService.java:10 (scripts/jdk-check.sh 21)
- Not a git repository (git status)

## Scope
- In: build.gradle.kts (release level), the three projects' compiled sources, test runs, the JaCoCo tool version if it rejects class file 65
- Out (issues.md): Spring Boot 3.x, the four CVEs, JUnit 4 -> 5 migration, coverage work, any `javax` -> `jakarta` move (that belongs to the Boot 3 plan; `javax.annotation` compiles fine on Java 21 because Boot 2.7 ships it)

## Approach
0. [x] Prerequisite (user) - done 2026-10-17: git init, baseline commit, branch chore/java21; `git status --porcelain` empty: `git init && git add -A && git commit -m "baseline"` then `git switch -c chore/java21` - verifies with: `git status --porcelain` empty
1. [x] Baseline re-check - result: COMPILE OK [gradle]; order-service 2/2; legacy-reports 2/2 - tool: `scripts/compile-errors.sh`, `scripts/test-summary.sh order-service`, `scripts/test-summary.sh legacy-reports` - verifies with: COMPILE OK, 2/2 and 2/2
2. [x] Build plumbing - result: COMPILE OK [gradle]; OrderService.class major version 65; diff: build.gradle.kts 2 lines - tool: manual, one edit: `options.release.set(21)` in build.gradle.kts:16; JaCoCo 0.8.12 already supports class file 65, no change - verifies with: `scripts/compile-errors.sh` -> COMPILE OK [gradle]
   COMMIT POINT: `build: compile all projects for Java 21`
3. [x] Recipe pass - result: dry run valid after correcting recipeList[7]; 'Applying recipes would make no changes'; COMMIT POINT 2 skipped as planned - tool: `scripts/rewrite.sh com.example.Java21UpgradeMinimal org.openrewrite.recipe:rewrite-migrate-java` (dry run, read per-module counts; expected: 0-2 files, since jdeps/jdeprscan found nothing) then `--apply` if non-empty - verifies with: `git diff --stat`, `scripts/compile-errors.sh`
   COMMIT POINT: `refactor: apply Java21UpgradeMinimal (<n> files)` (skip if the dry run proposes nothing; record that in plan.md)
4. [x] Manual residue - result: none (COMPILE OK) - expected none; if compile errors appear, group by cause and fix one cause per COMMIT POINT - verifies with: `scripts/compile-errors.sh`
5. [x] Tests - result: order-service 2/2, legacy-reports 2/2, common no test sources; equal to baseline - tool: `scripts/test-summary.sh order-service`, `scripts/test-summary.sh legacy-reports` - verifies with: counts equal to baseline
6. [x] Full build once - result: `./gradlew --offline -q build` exit 0; OrderController.class major version 65 - tool: `./gradlew --offline -q build` - verifies with: exit 0
7. [x] Smoke start - result: 'Started SampleApplication in 0.727 seconds', Tomcat on 8080, no ERROR lines; stopped after 25 s - tool: `./gradlew --offline -q :order-service:bootRun` for 20 s, grep log for `ERROR|Failed` - verifies with: "Started SampleApplication" line
8. [x] Report - result: report.md written in this folder - assembled from facts/decisions/plan/issues

## Risks
- Mockito/ByteBuddy: Boot 2.7.18 manages ByteBuddy 1.12.23, which cannot create mocks on JDK 21 - low impact now (no test uses Mockito, facts.md) - mitigation: noted in issues.md for the first test that introduces a mock; fix is a `byte-buddy` constraint or the Boot 3 upgrade
- Runtime-only breaks: none expected; the app already runs on JDK 21 at build time - mitigation: step 7 smoke start
- Recipe touching build files unexpectedly (UpgradeBuildToJava21 may edit `release` or add a toolchain block) - medium - mitigation: dry run first; if it adds `toolchain {}`, reject that hunk (toolchains trigger JDK downloads, D4/protocol)
- CI image Java version: no CI files found (ls) - n/a for the sample; in a real repo this is a step
- Downstream consumers of :common compiled at 21 bytecode - none here - in a real repo, list consumers before step 2

## Estimated size
- files likely touched: 1-3; COMMIT POINTs: 1-2; PRs: 1

## Hand-off
Agent: upgrader
First prompt: "Execute .agent-work/2026-10-17-upgrade-order-service-java21/plan.md steps 1-8 for all projects, target Java 21, bundle com.example.Java21UpgradeMinimal, no modernisation. Stop at each COMMIT POINT. Step 0 is done."
