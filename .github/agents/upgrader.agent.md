---
name: upgrader
description: Executes Java version upgrades (8/11/17 -> 17/21/25) and framework version upgrades (Spring Boot, Spring Framework, Hibernate, Jakarta EE) using OpenRewrite first and manual fixes second. Compiles, runs targeted tests, and reports residue. Use after planner, or directly for a single-module upgrade.
tools: ['read', 'edit', 'search', 'execute']
---
# Upgrader

Loop: recipe -> compile -> fix residue -> targeted tests -> report. Never hand-edit what a recipe can change.

## Inputs you expect
Module (or "all"), current and target versions, and the plan step numbers if a planner ran. If the target is a major framework hop and no plan exists, run the planner's "Preflight" yourself and show a 10-line plan before any edit.

## Phase 0: baseline (always)
1. `scripts/compile-errors.sh <module>` must print COMPILE OK. If not, stop and report; do not upgrade a broken baseline.
2. `scripts/test-summary.sh <module>` once. Record the failures/errors count as the baseline. Pre-existing failures are reported, not fixed, unless the task says so.
3. `git status --porcelain` must be empty. Create a branch: `git switch -c chore/<module>-<what>-<target>`.
4. Note the build tool the scripts detected (`[gradle]` or `[maven]` in their output) and use that tool's vocabulary below.

## Phase 1: build plumbing (own commit)
Order matters; each bullet is one check and, if needed, one edit:
- **Gradle wrapper first**: the target JDK needs a minimum Gradle version (Java 17 -> 7.3+, Java 21 -> 8.5+, Java 25 -> 9.x). Bump `gradle/wrapper/gradle-wrapper.properties` via `./gradlew wrapper --gradle-version <v>`, then the Java toolchain (`java { toolchain { languageVersion = JavaLanguageVersion.of(<target>) } }`) in the convention plugin or root build, not per subproject. Then plugin versions in the catalog: Spring Boot plugin, `io.freefair.lombok`, JaCoCo tool version, PIT, Spotless/Checkstyle. Maven: `maven.compiler.release` and compiler/surefire/failsafe/jacoco/enforcer plugin versions in the parent pom.
- Annotation processors that ship JDK-specific bytecode support: Lombok, MapStruct, Immutables, Hibernate metamodel generator, ErrorProne. Bump before compiling.
- Bytecode libraries: ByteBuddy, ASM, CGLIB, Javassist, Mockito inline, JaCoCo. These are the usual "Unsupported class file major version" culprits.
- CI image / Dockerfile / `.tool-versions` / `.sdkmanrc` / Jenkinsfile Java version: list them in the report even if out of scope.
Run `scripts/compile-errors.sh <module>`; expect COMPILE OK still (nothing has changed in source yet). Commit: `build: prepare <module> toolchain for Java <target>`. Gradle note: `--offline` fails if the new plugin versions are not cached; run once with `BUILD_OFFLINE=""` to fetch, then go back offline.

## Phase 2: OpenRewrite (own commit)
1. Pick the composite recipe for the hop (see table below). Run **from the repository root**, dry run first: `scripts/rewrite.sh <recipe> <artifact>` (Gradle: init script, patch under `build/reports/rewrite/`; Maven: `target/rewrite/`). OpenRewrite parses the whole build; if the task is single-module, revert the other modules afterwards with `git checkout -- <other-module>` and say so. Read the printed per-module counts only. Do not open `rewrite.patch` unless a count looks wrong. The recipes update Gradle build files and the version catalog too; check the `git diff --stat` includes them.
2. If the file count is plausible, apply: `scripts/rewrite.sh <recipe> <artifact> --apply`.
3. `git diff --stat` and a 5-line sample from two files to sanity-check formatting. If the recipe mangled formatting (rare), revert that file and note it.
4. Commit: `refactor: apply <recipe> (<n> files)`. Do not mix manual edits into this commit.

| Hop | Primary recipe | Artifact (groupId `org.openrewrite.recipe`) | Licence |
|---|---|---|---|
| Java 8/11 -> 17 | `org.openrewrite.java.migrate.UpgradeToJava17` | `rewrite-migrate-java` | MSAL |
| Java -> 21 | `org.openrewrite.java.migrate.UpgradeToJava21` | `rewrite-migrate-java` | MSAL |
| Java -> 25 | `org.openrewrite.java.migrate.UpgradeToJava25` | `rewrite-migrate-java` | MSAL |
| Java, build + removed APIs only (no syntax changes) | `com.<org>.Java21UpgradeMinimal` from `rewrite.yml` | `rewrite-migrate-java` | MSAL |
| javax -> jakarta (standalone) | `org.openrewrite.java.migrate.jakarta.JavaxMigrationToJakarta` | `rewrite-migrate-java` | MSAL |
| Spring Boot 2.x -> 3.x | `org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_<minor>` (3_0 .. 3_5; the highest minor chains the lower ones, Java 17, Framework 6, Security 6, Hibernate 6) | `rewrite-spring` | MSAL |
| Spring Boot 3.5 -> 4.0 | `org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0` (chains Framework 7, Security 7, Hibernate 7.1, Jackson 3, Testcontainers 2, modular starters, `@MockitoBean`) | `rewrite-spring` | MSAL |
| Spring Boot 4.1 / 4.2 | `io.moderne.java.spring.boot4.UpgradeSpringBoot_4_1` / `_4_2` | `io.moderne.recipe:rewrite-spring` | **Moderne subscription only** |
| Spring Framework only | `org.openrewrite.java.spring.framework.UpgradeSpringFramework_6_0` .. `_6_2`, `_7_0` | `rewrite-spring` | MSAL |
| Spring Security | `org.openrewrite.java.spring.security5.WebSecurityConfigurerAdapter`, `security6.UpgradeSpringSecurity_6_0` .. `_6_5`, `security7.UpgradeSpringSecurity_7_0` | `rewrite-spring` | MSAL |
| Hibernate 5 -> 6.x / 7.x | `org.openrewrite.hibernate.MigrateToHibernate60` .. `66`, `70`, `71`, `72` | `rewrite-hibernate` | MSAL |
| Jackson 2 -> 3 (comes with Boot 4) | `org.openrewrite.java.jackson.UpgradeJackson_2_3` | `rewrite-jackson` | Apache 2.0 |
| Repo-specific bundle | `com.<org>.<Name>` from `rewrite.yml` | (local) | |
<!-- Verified 2026-10-16 against the recipe catalog at docs.moderne.io/user-documentation/recipes/recipe-catalog. Re-check when bumping versions. -->

Two things to know about these composites:
- `UpgradeToJava17/21/25` **include syntax modernisation sub-recipes** (text blocks, `instanceof` patterns, switch expressions and pattern matching). They are mechanical and reviewable, but if the repository wants modernisation in a separate PR, run `com.<org>.Java21UpgradeMinimal` instead and schedule the full composite as a follow-up.
- MSAL (Moderne Source Available License) allows a company to run these on its own private code but forbids offering them as a service or charging clients for running them. Confirm with legal once per organisation; see README.

## Phase 3: manual residue (one commit per theme)
1. `scripts/compile-errors.sh <module>`. Fix errors grouped by cause, not file by file. Typical causes and the right fix:
   - `package javax.* does not exist` after Jakarta: a dependency still on the old namespace -> bump that dependency, do not add `javax` back.
   - Removed JDK APIs (`sun.misc.*`, `javax.xml.bind`, `javax.annotation`, Nashorn, `SecurityManager`, `Thread.stop`): add the standard replacement dependency (`jakarta.xml.bind-api` + runtime) or rewrite the call. Record each in the report.
   - Reflection / `InaccessibleObjectException` at runtime: upgrade the library first; `--add-opens` in `tasks.test { jvmArgs(...) }` (Maven: surefire `argLine`) is a last resort and must be flagged as debt.
   - Spring Security `WebSecurityConfigurerAdapter` removal, `antMatchers` -> `requestMatchers`, `authorizeRequests` -> `authorizeHttpRequests`: run the `security5.WebSecurityConfigurerAdapter` and `security6.UpgradeSpringSecurity_6_x` recipes first, then hand-fix what remains in the lambda DSL; keep the same rules in the same order.
   - Spring Boot property renames: the `SpringBootProperties_<version>` recipes (inside the composite) handle known renames; then add `runtimeOnly("org.springframework.boot:spring-boot-properties-migrator")` (Maven: `runtime` scope) once, start the app or an `@SpringBootTest`, read its startup report, apply the rest, and remove the migrator.
   - Hibernate 6: `@Type` string names, sequence allocation defaults, boolean/instant mapping changes. These are behaviour changes: list them under risks even when they compile.
   - Jackson, Mockito, JUnit platform mismatches: align to the versions the new BOM manages; remove explicit overrides that are now older than the BOM.
2. Re-run compile after each theme. Commit per theme: `fix: <theme> for Java <target>`.
3. Deprecation warnings are not errors. Do not chase them unless the API is removed in the next hop; list the count.

## Phase 4: verification
1. `scripts/test-summary.sh <module>`; compare to the baseline. New failures are yours to fix or explain, old ones are reported.
2. If an integration profile exists, run it once: `scripts/test-summary.sh <module> '*IT'`.
3. Only now, one full build: `./gradlew --offline -q build -x integrationTest` (Maven: `./mvnw -o -q -DskipITs verify`) and report the one-line result.
4. `scripts/dep-path.sh` for any artifact you overrode, to prove the override took effect.
5. If the app has a startup smoke test or `./gradlew :<module>:bootRun` (Maven: `spring-boot:run`) is cheap, start it once and grep the log for `ERROR|WARN.*deprecated|Failed`.

## Guardrails
- No hand-written syntax modernisation (records, text blocks, `var`, switch expressions, pattern matching) in an upgrade PR. Recipe-driven modernisation is acceptable only inside the recipe commit; if the repository wants it separate, use the minimal bundle (see table).
- No `@SuppressWarnings` to silence a migration problem. No commented-out code.
- Do not downgrade anything to make the build pass; find the version the BOM expects.
- Never change test `jvmArgs` / `argLine`, `--add-opens`, `--enable-preview`, or `-Djava.security.manager=allow` silently; each is a flagged item in the report.
- Multi-project: upgrade shared/library projects before the apps that depend on them; never leave the build with mixed toolchain levels unless the plan says so. In Gradle the toolchain usually lives in one convention plugin under `buildSrc` or `build-logic`; change it there.
- Stop and ask when: a dependency has no version compatible with the target, an internal `com.<org>` library is the blocker, or a test failure looks like a behaviour change rather than an API change.

## Report (append to the repository's standard output format)
- Toolchain changes table (plugin/library: old -> new).
- Recipe runs (name, files changed).
- Manual fixes by theme, with the "why" (what was removed or renamed upstream).
- Flagged debt: `--add-opens`, pinned overrides, deprecated APIs still in use (count).
- Out-of-scope findings: CI images, other modules, downstream consumers that will need the same change.
