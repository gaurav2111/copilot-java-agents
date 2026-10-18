# Copilot agents for enterprise Java maintenance

Drop-in `.github/` customisation for GitHub Copilot (IntelliJ / VS Code / coding agent) covering Java version
upgrades, framework upgrades and migrations, vulnerability fixes, test coverage work, and documentation. **Gradle is the
default**; the same scripts and agents work on Maven repositories (the scripts detect `pom.xml`). Built around two ideas:

1. **Layered loading.** Only `copilot-instructions.md` is sent on every request. Everything else loads on demand:
   path-scoped instructions when matching files are touched, an agent when you pick it, a prompt when you run it.
2. **Deterministic first, LLM second.** OpenRewrite recipes and small scripts do the bulk mechanical work; the model
   only reads summaries and fixes residue. This is where most of the token saving comes from.

## Layout

```
.github/
  copilot-instructions.md      always on: stack, commands, hard rules, report format (keep < 60 lines)
  instructions/
    java.instructions.md       applyTo src/main/java      - production code conventions
    tests.instructions.md      applyTo src/test           - unit/IT conventions and hard rules
    build.instructions.md      applyTo gradle/toml/pom    - version hygiene, constraints, comments, no repos
    docs.instructions.md       applyTo *.md, docs/, puml  - documentation and diagram conventions
  agents/
    planner.agent.md           read-only plan + risks + hand-off prompt
    upgrader.agent.md          Java and framework VERSION upgrades
    security-fixer.agent.md    CVE triage/fix + SAST fixes with failing-first tests
    migrator.agent.md          API migrations (JUnit4->5, javax->jakarta, Log4j->SLF4J, ...)
    test-engineer.agent.md     unit tests, integration tests, coverage raising
    docs-writer.agent.md       diagrams (Mermaid), READMEs, API reference, runbooks, Javadoc, ADRs
  prompts/                     slash commands that pre-select the agent and carry per-run inputs
  hooks/
    hooks.json + guard.sh      preToolUse: blocks ALL git writes, installs/downloads, publish/deploy, recursive rm, infra
  AGENT-PROTOCOL.md            facts/decisions/plan/issues files, gates G0-G4, question format, never-do list
rewrite.yml                    composite OpenRewrite recipes named for your org
scripts/
  lib.sh                       build-tool detection (Gradle default, Maven fallback), shared helpers
  compile-errors.sh            compile -> distinct errors only, capped
  test-summary.sh              run tests -> counts + failing test names from JUnit XML
  dep-path.sh                  how one artifact reaches each project (Gradle dependencyInsight / Maven tree)
  dep-tree.sh                  dump every project's runtime tree into one file for CveTriage
  jdk-check.sh                 jdeps --jdk-internals, jdeprscan --for-removal, trouble-spot grep for an upgrade target
  rewrite.sh                   OpenRewrite dry run/apply without touching the build; prints patch summary
  rewrite-init.gradle          Gradle init script used by rewrite.sh (no build.gradle changes)
  CoverageGaps.java            JaCoCo XML (Gradle or Maven layout) -> ranked under-covered classes (java 11+, no build)
  CveTriage.java               scanner CSV + dependency tree (either format) -> cve | reach | resolved | strategy
```

## Testing the pack

`TESTING.md` is the step-by-step checklist for IntelliJ (four stages, expected result per step). The sibling folder
`../sample-app/` is a throwaway Gradle multi-project with the pack installed and every flaw the agents target: Java 17
bytecode without a toolchain, Spring Boot 2.7.18, four CVEs listed in `findings.csv`, a `javax.annotation` import, a
JUnit 4 + Hamcrest module, and an untested `PricingEngine`. All Stage 2 scripts have been run against it successfully.

## Setup (per repository)

1. Copy `.github/`, `rewrite.yml`, `scripts/` to the repo root. `chmod +x scripts/*.sh`. On Windows use Git Bash or WSL for the shell scripts; the two Java tools run anywhere.
2. Fill the `<!-- FILL IN -->` block in `copilot-instructions.md`: Java and framework versions, project map, test stack. Replace `com.<org>` in `rewrite.yml` and the agents with your group id.
3. Gradle prerequisites the scripts assume: wrapper present, JaCoCo XML report enabled (`tasks.jacocoTestReport { reports { xml.required = true } }`), integration tests in an `integrationTest` task if you have them, versions in `gradle/libs.versions.toml`. Maven: wrapper, JaCoCo plugin with `report` goal, failsafe for `*IT`.
4. Pin OpenRewrite versions via `REWRITE_PLUGIN_VERSION` / `REWRITE_RECIPE_VERSION` (defaults are the last public versions, see table below). The Gradle path uses `scripts/rewrite-init.gradle`, which must not collide with an existing `org.openrewrite.rewrite` plugin application in the build.
5. Make sure your artifact mirror (Nexus/Artifactory) proxies the Gradle Plugin Portal and Maven Central for `org.openrewrite.*`. OpenRewrite must also resolve **every** project dependency to parse sources; a module that only builds inside CI will not parse locally.
6. Open Copilot Chat in IntelliJ, switch to Agent mode, and check the mode picker lists `planner`, `upgrader`, ... If not, see "Client support" below.
7. Smoke test on a small module: `/plan upgrade <module> to Java 21`, read the plan, then switch to `upgrader`. For the docs agent: `/document :<module> | readme`.

## How a task flows (protocol in `.github/AGENT-PROTOCOL.md`)

Every agent run keeps four small files under `.agent-work/<date>-<task>/`: `facts.md` (every value with its source),
`decisions.md` (options offered, answer, who decided), `plan.md` (steps with status, verification result, COMMIT POINTs;
this is the progress log), `issues.md` (everything found but out of scope). Gates: G0 preflight read, G1 confirm facts
and ask numbered-option questions, G2 confirm plan, G3 each commit point, G4 report assembled from the files. Agents run
no git write commands and never download or install anything; the user commits at the commit points. A new chat continues
any task with `/resume .agent-work/<folder>`, which is also the token-efficient way to run long tasks.


```
/plan ...          -> planner: facts.md with sources, G1 questions, then plan.md; no edits
switch agent       -> upgrader | security-fixer | migrator | test-engineer, say "go" on the plan
                      (docs-writer is standalone: /document <subject> | <format>)
Phase 0 baseline   -> compile OK, tests counted, jdk-check facts, you confirm the work branch
recipe dry run     -> you read a per-module count, not a diff
apply              -> COMMIT POINT: you commit the mechanical change alone
residue by theme   -> one COMMIT POINT per cause, plan.md ticked each time
verify             -> targeted tests, then ONE full build
report             -> assembled from the four work files, pasted into the PR
long task          -> new chat, /resume .agent-work/<folder>
```

## Token discipline (what actually saves money)

| Lever | Where it is implemented |
|---|---|
| Always-on file is tiny | `copilot-instructions.md` under 60 lines; everything task-specific moved to agents/prompts |
| Model never reads raw build output | `scripts/compile-errors.sh`, `scripts/test-summary.sh`, `scripts/rewrite.sh` print summaries only |
| Model never reads raw reports | `CoverageGaps.java`, `CveTriage.java` pre-digest JaCoCo and scanner output into 15-30 lines |
| Docs are traced, not crawled | `docs-writer` discovers by `grep` (0 reads), then reads at most 8 files for a flow, 15 for a module, by line range |
| Bounded exploration | every agent has a read budget in Phase 0 and a "do not read outside the module" rule |
| Plan, confirm, execute | gates G1/G2/G3 in the protocol; questions batched with numbered options, one message |
| Resume from files, not history | `/resume` reads four small files; long tasks span several short chats |
| Mechanical edits bypass the model | OpenRewrite composites in `rewrite.yml`; agents hand-fix residue only |
| Targeted test runs | `:module:test --tests`, Maven `-pl -am -Dtest=`; whole build once at the end |
| One task per chat | stated in README and in every agent; history is re-sent on every turn |
| Cheaper model for mechanical agents | set `model:` in the agent frontmatter (see "Client support" for billing) |

Billing changed on 1 June 2026: all Copilot plans are now usage-based (GitHub AI Credits, 1 credit = $0.01, cost =
input + cached + output tokens at the model's rate; Business includes 1,900 credits per user per month, Enterprise 3,900,
pooled, no carry-over). So the bill is literally tokens, and the table above is the cost model. The JetBrains plugin
shows a per-turn credit indicator since June 2026; watch it on the first few runs to calibrate. Cheapest practical
models for mechanical loops at the time of writing: Claude Haiku 5.5, GPT-5 mini. Reasoning-heavy phases (planner,
migrator, integration tests): Claude Sonnet 5.x or GPT-5.4 class. Set `model:` per agent once you know which models
your enterprise has enabled; an unknown model name falls back to the default.

## OpenRewrite supply and licensing (verified 2026-10-16; read before the first run)

Three things changed in 2026 that an enterprise must settle before agents rely on recipes:

1. **Artifacts left Maven Central.** OpenRewrite stopped publishing to Central in August 2026 (Sonatype publishing
   limits). New releases come from the authenticated "Code Genome Project" repository
   `https://artifacts.codegenomeproject.org/maven`. Apache-2.0 recipes are available to any authenticated user; MSAL
   recipes are listed there as customers-only, while older MSAL versions remain on Central. Ask the Nexus/Artifactory
   team to add it as a proxy repository (HTTP Basic, token as password, ordered below internal repos and above Central;
   a 404 on the connection test is expected, verify with `.../org/openrewrite/rewrite-core/maven-metadata.xml`).
   `recipeArtifactCoordinates` resolve through `<repository>` entries in `settings.xml`, not `<pluginRepository>`.
   Until that exists, `scripts/rewrite.sh` defaults to the last Central versions, which work from a plain Central mirror.
2. **Licence tiers.** Check once with legal, then record the decision here.
   - Apache 2.0: core (`org.openrewrite:rewrite-*`, building blocks like `ChangeType`, `RemoveUnusedImports`,
     `UpgradeParentVersion`), Maven/Gradle plugins, `rewrite-java-dependencies` (`UpgradeDependencyVersion`,
     `UpgradeTransitiveDependencyVersion`, `ChangeDependency`, `DependencyInsight`), `rewrite-jackson`, `rewrite-quarkus`,
     `rewrite-micronaut`, `rewrite-third-party`.
   - Moderne Source Available License (MSAL): `rewrite-migrate-java`, `rewrite-spring`, `rewrite-testing-frameworks`,
     `rewrite-static-analysis`, `rewrite-logging-frameworks`, `rewrite-hibernate`, `rewrite-apache`, `rewrite-joda`.
     Companies may run them on their own private code; offering them as a service or charging clients to run them is
     prohibited. Consultancies working on a client's code need the client's own entitlement.
   - Moderne Proprietary (subscription): `rewrite-java-security` (which now also holds `DependencyVulnerabilityCheck`,
     `DependencyLicenseCheck`, `RemoveUnusedDependencies`), every `io.moderne.recipe:*` artifact (Spring Boot 4.1/4.2
     upgrades, "Moderne Edition" composites), and the Moderne CLI for private repositories.
   The agents are written so that everything essential works with Apache + MSAL. Proprietary recipes are marked as
   optional in `security-fixer` and `rewrite.yml`.
3. **Run from the reactor root.** The Maven plugin parses the whole multi-module reactor and runs recipes once over
   the combined model; `-pl <module>` gives a partial model and is unsupported. Scope with `preconditions` in
   `rewrite.yml`, or revert out-of-scope modules afterwards. Budget 8 GB heap for large reactors (`MAVEN_OPTS`). Every
   run rebuilds the model, so bundle several recipes into one composite instead of running them one by one. Unresolved
   dependencies silently reduce matches: all project dependencies must resolve from the developer's mirror.

Gradle (default here): `scripts/rewrite.sh` applies the plugin through `scripts/rewrite-init.gradle`, so no build file
changes; patch at `build/reports/rewrite/rewrite.patch`, data tables under `build/reports/rewrite/datatables/`. Init
scripts cannot read `gradle.properties`, so Code Genome credentials come from `CODE_GENOME_USERNAME` /
`CODE_GENOME_TOKEN` environment variables. If your `~/.gradle/init.d` already redirects repositories to the corporate
mirror, set `-Drewrite.pluginRepo=<mirror url>` via `REWRITE_PLUGIN_REPO` or edit the init script once.

## Versions to pin (Maven Central column works from a Central-only mirror; Code Genome column needs the proxy above)

| Artifact | Last on Maven Central (date) | Current on Code Genome |
|---|---|---|
| `org.openrewrite.maven:rewrite-maven-plugin` | 6.46.1 (2026-08-12; depends on `rewrite-bom` 8.89.0, on Central) | 6.50.0 |
| Gradle plugin `org.openrewrite.rewrite` | **7.39.0** usable from public repos (7.40.0 and 7.41.0 are on the Plugin Portal but need `rewrite-bom` 8.91.0, which is Code Genome only; verified 2026-10-17) | 7.44.1 |
| `org.openrewrite.recipe:rewrite-recipe-bom` | 3.38.0 (2026-08-26) | 3.41.0 |
| `rewrite-migrate-java` | 3.42.1 | 3.46.0 |
| `rewrite-spring` | 6.37.1 | 6.41.0 |
| `rewrite-testing-frameworks` | 3.44.0 | 3.49.0 |
| `rewrite-java-dependencies` | 1.60.2 | 1.64.0 |
| `rewrite-static-analysis` | 2.41.1 | 2.45.0 |
| `rewrite-logging-frameworks` | 3.32.0 | 3.34.1 |
| `rewrite-java-security` (proprietary) | 3.38.1 | 3.43.0 |
| `rewrite-hibernate` / `rewrite-jackson` / `rewrite-apache` / `rewrite-joda` | see Central metadata | 2.27.0 / 1.31.0 / 2.34.0 / 0.12.0 |

`scripts/rewrite.sh` uses `RELEASE` for recipe artifacts by default, which resolves to whatever your mirror can see.
For reproducible CI runs export `REWRITE_RECIPE_VERSION` or pass full `g:a:v` coordinates.

Recipe catalog moved from docs.openrewrite.org to `https://docs.moderne.io/user-documentation/recipes/recipe-catalog`;
running/reference docs (plugins, `rewrite.yml` syntax, FAQ) stay on docs.openrewrite.org.

## Client support (verified 2026-10-16 against docs.github.com, code.visualstudio.com and the JetBrains plugin changelog)

| Feature | VS Code | JetBrains plugin (latest 1.19.1, 2026-10-13) | Copilot coding agent (cloud) |
|---|---|---|---|
| `.github/copilot-instructions.md` | yes | yes (since 1.5.42) | yes |
| `.github/instructions/*.instructions.md` + `applyTo` | yes | yes (1.5.53; `applyTo` bug fixed 1.5.62, so require >= 1.5.62) | yes |
| `AGENTS.md` / `CLAUDE.md` | yes | yes (1.6.1); nested needs a setting | yes, nested |
| `.github/agents/*.agent.md` custom agents | yes | GA 1.6.1 (2026-03-10); docs still say "preview" | yes |
| `tools:` identifiers | VS Code names or GitHub aliases | **unreliable**: open bug microsoft/copilot-intellij-feedback#1573; use GitHub aliases (`read`, `edit`, `search`, `execute`, `agent`) or omit | GitHub aliases |
| `model:` in agent frontmatter | yes | yes | no |
| `handoffs:` | yes | not supported | ignored |
| `.github/prompts/*.prompt.md` | yes, `/name` | yes, type `/` + file name (1.5.55, GA 1.10.0) | no |
| `${input:...}` prompt variables | yes | documented for VS Code only; test once. The model usually understands the literal syntax if it is not expanded | n/a |
| `.github/hooks/hooks.json` (Copilot format) | yes | GA 1.10.0 (plugin notes; cheat sheet lags) | yes, bash only |
| MCP servers, Plan agent, subagents | yes | yes | MCP yes |

Consequences baked into these files: agents use only `name` and `description` (the `tools:` line was removed after a
JetBrains test showed the planner running with no tools and guessing the Java version; read-only behaviour is now
enforced in the agent text and verified by `git status`); prompts use
`agent:` (not the legacy `mode:`); no `handoffs:`; the hook is written in the Copilot `version: 1` format. `argument-hint`
in prompt files is VS Code-only and harmless elsewhere. Agent bodies stay far under the 30,000-character limit.

Enterprise admin note: while a JetBrains feature was in preview, Business/Enterprise tenants needed the "Editor preview
features" policy enabled. If agents or hooks do not appear, ask the Copilot admin before debugging the files.

### Overlap with "GitHub Copilot modernization" for Java

Microsoft ships a separate free IntelliJ plugin (id 28791, "GitHub Copilot modernization", GA, Windows and macOS, needs
IntelliJ 2024.3+ and a Copilot plan). It runs an analyse -> `plan.md` -> transform with build validation -> CVE check ->
`summary.md` flow for JDK 11/17/21/25, Spring Boot up to 4.0, Jakarta EE up to 11, and uses OpenRewrite underneath.
Where it is available, use it for the **first big hop** of a Java or Spring Boot upgrade and keep the `upgrader` agent
for the residue, multi-module sequencing, and anything the tool does not cover (internal libraries, Gradle Kotlin DSL,
Linux workstations). The two do not conflict: the modernization plugin reads `copilot-instructions.md` too.

Fallback if your JetBrains version does not show custom agents: keep the agent files (they cost nothing when not
loaded), and run the prompt files instead. Each prompt file names its agent; where that is unsupported, paste the
agent file's content as the first message of a new chat. Same instructions, one extra paste.

## Assumptions baked in (change if wrong)

- Gradle multi-project with wrapper and a version catalog (default). Maven multi-module with wrapper also works: scripts detect `pom.xml`; the agents name the Maven equivalent wherever the vocabulary differs. Set `BUILD_TOOL=maven` to force it when both files exist.
- Spring Boot application(s), JUnit 5 + Mockito + AssertJ, JaCoCo XML at `<module>/build/reports/jacoco/test/jacocoTestReport.xml` (Maven: `target/site/jacoco/jacoco.xml`), integration tests in an `integrationTest` task (Maven: failsafe).
- A corporate artifact mirror; no direct Central or Plugin Portal access (hence `--offline` / `-o` defaults and no `-U`). Set `BUILD_OFFLINE=""` for a run that needs downloads.
- Gradle Groovy or Kotlin DSL both work for the scripts; OpenRewrite recipes edit both. Note the vendor "Copilot modernization" plugin does not support the Kotlin DSL, these agents do.
- Scanner output can be exported as CSV with group, artifact, version, CVE, severity, fix version columns (names are matched case-insensitively, spaces ignored).

## Open questions for the repo owner

1. Gradle or Maven per repository, Groovy or Kotlin DSL, project count, and whether a convention plugin (`buildSrc` / `build-logic`) owns the toolchain and test configuration.
2. Exact current/target Java and Spring Boot versions (fills the stack block and picks the recipe hops).
3. Scanner in use and its export format (adjust `CveTriage` column mapping once).
4. Whether PIT is or can be added (test-engineer's quality gate uses it when present).
5. Whether `.github/` can be committed to every repo or must live in the org-level `.github` / `.github-private` repository (org agents go in `/agents/NAME.md` there; repo files override org files).
6. Which Copilot models are enabled; set `model:` per agent accordingly (planner strong, upgrader/security-fixer cheaper, test-engineer strong for ITs).
7. Nexus/Artifactory: can the Code Genome repository be proxied (token-based), or do we stay on the August-2026 Central versions? What heap can developer machines give OpenRewrite (8g default in `rewrite.sh`)?
8. Legal: confirm MSAL use on company code is acceptable, and whether a Moderne subscription exists (unlocks `DependencyVulnerabilityCheck`, security recipes, Spring Boot 4.1+ composites, and the CLI for private repos).
9. If the company prefers the vendor route: the free "GitHub Copilot modernization" IntelliJ plugin covers Java and Spring Boot upgrades end to end; these agents then handle residue, migrations, CVEs, and tests.
