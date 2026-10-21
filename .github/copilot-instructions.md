# Repository instructions (always loaded - keep this file under 60 lines)

## Stack
<!-- FILL IN per repository. One line each. Delete lines that do not apply. -->
- Build: Gradle multi-project, wrapper `./gradlew`, version catalog `gradle/libs.versions.toml`. Java <CURRENT_JAVA> (target <TARGET_JAVA>). Spring Boot <VERSION>.
  (Maven repositories: same scripts work; they detect `pom.xml` and use `./mvnw`.)
- Tests: JUnit 5, Mockito, AssertJ. Integration tests end in `*IT` and run in the `integrationTest` task (Maven: failsafe). Coverage: JaCoCo with XML report enabled.
- Projects: `:module-a` (API), `:module-b` (domain), `:module-c` (persistence), `:module-d` (batch). See `MODULES.md` if present.
- Internal libraries: `com.example.*` artifacts come from the corporate Nexus; never replace them with public equivalents.

## Commands (use these, never raw Gradle/Maven output; all run from the repo root)
- Compile and show only errors: `scripts/compile-errors.sh <module>`
- Run tests and show only failures: `scripts/test-summary.sh <module> [TestClassPattern]`
- Dependency reach of an artifact: `scripts/dep-path.sh <groupId>:<artifactId> [module]`
- OpenRewrite dry run / apply (from the repo root only): `scripts/rewrite.sh <recipe> <recipeArtifact> [--apply]`
- Full dependency tree for CVE triage: `scripts/dep-tree.sh` then `java scripts/CveTriage.java <findings.csv> build/deptree.txt`
- Coverage gaps: `java scripts/CoverageGaps.java "**" 70 15` after `./gradlew --offline -q :<module>:test :<module>:jacocoTestReport`
- Always offline (`--offline` / `-o`) and per project (`:<module>:task`; Maven `-pl <module> -am`). Run the whole build at most once, at the end.

## Rules that apply to every task
- Agent runs follow `.github/AGENT-PROTOCOL.md`: facts with sources, four working files under `.agent-work/<task>/`, gates G0-G4, numbered-option questions.
- No git write commands (the user commits at the COMMIT POINTs you mark). No downloads or installs of JDKs, SDKs, Gradle distributions or tools; write the needed command into `issues.md` and stop.
- Work in the module the task names. Do not open or edit files outside it unless a compile error or test failure names them.
- Before the first edit of any multi-step task, write a numbered plan and stop for confirmation.
- Never add a dependency, plugin, or repository without saying so explicitly in the plan.
- Never change production code to make a test pass. Never delete, `@Disabled`, or weaken a failing assertion. Report it instead.
- Never commit secrets, credentials, internal hostnames, or real customer data, including in test fixtures.
- Keep each change set reviewable: one concern per branch or PR. Mechanical refactors (formatting, syntax modernisation) go in their own PR.
- Preserve public API signatures and behaviour unless the task is explicitly a breaking change.
- Deterministic tools first: if an OpenRewrite recipe or script can make the change, run it and only hand-fix the residue.
- When unsure about a version compatibility question, check the dependency tree and release notes rather than guessing.

## Output format for every completed task
1. What changed (files and why, grouped by module).
2. Recipes or scripts run, with their summary lines.
3. Manual fixes applied and why they were necessary.
4. Verification evidence: exact commands run and their one-line results.
5. Open risks, assumptions, and anything deferred.
