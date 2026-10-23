# Java maintenance agents, version 2 (draft)

This is a draft; I am still testing it and aim to release a finished version by Diwali.

What is in the pack: 7 agents, 16 prompts, 1 always-on instruction file plus 5 path-scoped ones, 1 guard hook,
15 scripts, 8 OpenRewrite recipe bundles, 1 shared protocol, 1 team rules file, 1 logging policy, 1 sample project
with planted flaws, and 4 documents (README, testing checklist, OpenRewrite adoption plan, roadmap).

The old setup was two agents, planner and implementer, each a 600-line file doing everything: upgrades, CVE fixes,
tests. It worked, but every chat carried both files on every turn, and every rule was a sentence the model was asked
to follow.

The new pack splits that into pieces Copilot loads only when needed. For the layout I took reference from Everything
Claude Code, the open-source configuration collection from an Anthropic hackathon winner, which uses the same split of
agents, commands, rules and hooks. Ours applies that shape to GitHub Copilot and to one domain, Java maintenance.

- **Instructions**: one 34-line file always on, with the stack, the commands and the hard rules. Path rules for Java,
  tests, build files and docs load only when such a file is touched.
- **Agents**: seven short files, one per task type: planner, upgrader, security-fixer, migrator, test-engineer,
  docs-writer, log-engineer. Only the one you pick is loaded.
- **Prompts**: slash commands like `/upgrade-java order-service 21` or `/fix-cves findings.csv HIGH`. They pick the
  agent and carry the inputs.
- **Hooks**: a script that runs before every tool call and blocks git writes, downloads, force pushes, and any path or
  command the team lists. The old agents were told not to do these things; now they cannot.
- **Scripts and recipes**: OpenRewrite bundles make the mechanical edits; scripts reduce build, test and scanner
  output to a few lines.

## Efficiency without cutting quality

Copilot now bills per token, so the design makes the model read less, not think less:

- The model never reads a raw log. A dependency tree of 120 lines becomes five triage rows; a multi-megabyte coverage
  report becomes three ranked classes; a test run becomes one line. Scripts do that, deterministically, at zero token
  cost.
- Mechanical edits go through OpenRewrite recipes, which are exact and free. The model only handles what the recipe
  leaves behind.
- Each agent has a read budget: eight files for a flow, fifteen for a module. Reaching it stops the agent at a gate
  rather than letting it crawl the repository.
- Long tasks span several short chats. The work files are the memory, so a new chat resumes without re-sending the
  history.
- Cheaper models can be pinned to the mechanical agents, the strong one to planning and migrations.

Quality is protected by the opposite rule: every claim must come from a tool. Facts carry the file and line they came
from. Every plan step names the script that proves it, and the result is recorded next to the step. A snapshot before
the task and a comparison after it is the next phase, so an agent cannot finish with fewer tests or lower coverage than
it started with.

What carried over from the old setup: never guess a version, ask multiple-choice questions, keep a decisions file.
Those are now one shared protocol every agent reads, with four work files per task and gates where a human answers.

Tested end to end on a sample project with planted flaws, which doubles as the regression test for the pack; works on
Gradle and Maven, on the Copilot seats we already have. Feedback welcome while it is still a draft.

## OpenRewrite: how it is integrated and how we use it

OpenRewrite applies a known rule to code: rename a package, replace an API, bump a version, migrate a test
annotation. It does that on a type-resolved model of the whole build, so it never misses a usage and never breaks
formatting. In the pack it is the first thing an agent reaches for whenever a change can be written as a rule; the
model only handles what the recipe leaves behind.

**How it is wired in**

- `scripts/rewrite.sh` runs any recipe without touching the build files. On Gradle it uses an init script, on Maven the
  plugin by coordinates, always from the repository root, always as a dry run unless `--apply` is passed. Versions are
  pinned to the last ones available from public mirrors.
- The output the agent sees is a per-module count of changed files, not the patch. It opens the patch only if a count
  looks wrong. A failed resolution is reported loudly so an empty result is never mistaken for "nothing to do".
- `rewrite.yml` holds our own bundles under the `com.example` prefix, so an agent runs one name instead of reasoning
  about ten recipes: Java 21 upgrade (full, and a minimal variant without syntax modernisation), Spring Boot 3
  upgrade, test stack modernisation (JUnit 4 to 5, Mockito 5, Hamcrest to AssertJ), dependency bump, post-migration
  cleanup, and a security bundle marked optional because it needs a paid subscription.
- Each agent carries a table mapping tasks to recipe names and artifacts, verified against the recipe jars, with the
  licence of each artifact noted.

**How a run goes**

1. The agent picks the bundle for the hop from its table and dry-runs it: `scripts/rewrite.sh com.example.Java21UpgradeMinimal org.openrewrite.recipe:rewrite-migrate-java`.
2. It shows the per-module counts and waits for a go.
3. It applies, checks the diff stat, and marks a commit point. That commit contains only recipe output; manual edits
   never mix into it, so a reviewer can trust it without reading every line.
4. It compiles, groups any residue by cause, and fixes one cause per commit point.

**Rules we follow with it**

- Recipe commits and manual commits are kept apart. Syntax modernisation is a separate pull request from a version
  upgrade.
- Run from the root; never per module. Scope with preconditions in `rewrite.yml` when needed.
- A dependency that fails to resolve makes recipes silently skip files, so the setup check and the dry-run output
  both flag unresolved types.
- Licences differ by artifact: the core and dependency recipes are Apache 2.0; the Java, Spring and testing recipes
  are source-available and need a one-time legal check; the security recipes need a subscription and are not relied
  on. Artifacts stopped publishing to Maven Central in August 2026, so versions are pinned and should be mirrored.
- When the model edits the same thing in five files, that is a missing recipe. The roadmap turns such repeated fixes
  into new bundles so the next repository needs no model at all.

OpenRewrite is a component of the pack, not an alternative to it. It answers "how do I apply this known change
everywhere"; the agents answer what change this repository needs, what the recipe left behind, whether the result is
correct, and what it cost.

## What it can do today

Type `/` in Copilot chat to see these. Each one picks the right agent and asks for what it needs.

| Prompt | What happens |
|---|---|
| `/setup` | Validates the pack against the repository: prerequisites, every script, stack detection. Writes `SETUP-REPORT.md`. Run first. |
| `/plan <task>` | Read-only. Facts from the build files with sources, multiple-choice questions, then a numbered plan with risks. |
| `/upgrade-java <module> <version>` | Java version hop: toolchain, OpenRewrite recipe, residue, tests, smoke start. |
| `/upgrade-spring-boot <module> <version>` | Spring Boot hop, one major at a time, with the properties migrator and security changes called out. |
| `/fix-cves <findings.csv or xray.json> <severity>` | Runs the CVE flow: report to findings, dependency tree, triage, and a BOM verdict per artifact (stale scan, override, patch-level BOM bump, or decision); then bump, constraint, exclusion or justified exception; closes the loop against the rescan. |
| `/fix-sast <findings>` | Code-level security findings fixed at the call site, each with a test that fails on the old code. |
| `/migrate <module> <from> -> <to>` | API migrations: JUnit 4 to 5, javax to jakarta, Log4j to SLF4J, Hamcrest to AssertJ and more. Inventory, recipe, residue, zero references left. |
| `/add-tests <module> <Class,...>` | Unit tests for named classes, mirroring the module's existing test style. Never touches production code. |
| `/raise-coverage <module> <percent> <max classes>` | Ranks the riskiest untested classes and works through them one at a time with a commit point each. |
| `/add-integration-test <module> '<flow>'` | One integration test for a flow, on the narrowest Spring slice, with Testcontainers or WireMock. |
| `/document <subject> \| <format>` | Sequence or flow diagrams, module README, API reference, runbook, Javadoc or ADR, every statement traced to a file. |
| `/review-upgrade-pr <base>` | Pre-PR checklist against the pack's rules: commit hygiene, no disabled tests, no silent flags, report present. |
| `/audit-logs <scope> [export.csv] [price]` | Read-only inventory of every log statement with noise and risk flags, priced against the log sink when an export is given. |
| `/reduce-logs <scope> <target> [export.csv]` | Cuts log volume and cost: config levels first, then loops, tracing, payloads, duplicates, PII; recipe pass for the mechanical fixes; bytes and $ before and after. |
| `/add-logs <module> <classes or flow>` | Adds the logs the policy says are missing: boundary completion lines, business events, outbound call summaries, exception causes, correlation id. |
| `/resume .agent-work/<folder>` | Continue any task in a fresh chat or on a different model, from its work files. |

The agents behind them: `planner`, `upgrader`, `security-fixer`, `migrator`, `test-engineer`, `docs-writer`, `log-engineer`. Pick one
from the mode picker when no prompt fits.

The scripts they use, also runnable by hand from the repository root: `compile-errors.sh`, `test-summary.sh`,
`dep-path.sh`, `dep-tree.sh`, `jdk-check.sh`, `rewrite.sh`, `setup-check.sh`, `log-lint.sh`, `CoverageGaps.java`, `CveTriage.java`,
`LogAudit.java`, `LogVolume.java`, `XrayToFindings.java`, `BomFix.java`, `cve-flow.sh`.
