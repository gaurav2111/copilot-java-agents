# Roadmap: from "ask the LLM" to a verification-first maintenance platform

Status: draft 2026-10-19, for discussion. Companion documents: `OPENREWRITE-ADOPTION.md` (recipe tiers),
`TESTING.md` (how to validate), `.github/AGENT-PROTOCOL.md` (how agents work today).

Research base: three investigations run on 2026-10-19 covering (1) deterministic Java tooling with machine-readable
output, (2) GitHub Copilot platform capabilities beyond chat, (3) the commercial and open-source landscape (Moderne,
GitHub Copilot modernization, AWS Transform, Google's internal migration papers, Diffblue, Spotify/Netflix/Adyen fleet
platforms, Konveyor Kai, Renovate/Snyk/Socket, academic reliability evidence). Sources are cited inline by name; the
full reports are kept in the session record.

## 1. The thesis, in one paragraph

Every system that works at scale (Google's two production papers, Moderne, Spotify Fleetshift, Netflix Change
Automation, Adyen's OpenRewrite rollout, Meta TestGen-LLM) has the same shape: **deterministic site discovery ->
recipe or LLM edit -> a cheap-to-expensive verification ladder (parse, diff, compile, analyser, tests, runtime, canary)
-> human review sized to reviewer capacity**. The LLM targets, authors transformations, and handles the residue; it
never certifies its own work. Vendors still rely on prompts for the dangerous parts ("do not delete tests"), and the
evidence shows prompts fail: AWS admitted early agents deleted tests to go green, Cursor's audit found 63 % of
benchmark "fixes" were retrieved rather than derived, Google reverted unrequested edits in most changes. The
differentiator for an in-house pack is therefore not a smarter prompt. It is **verification that cannot be talked
out of**, **facts that come from tools**, and **measurement that is published**.

## 2. Unique selling points (what no vendor in the Copilot ecosystem does well)

| # | USP | Evidence it matters | What we have today | What is missing |
|---|---|---|---|---|
| U1 | **Anti-reward-hacking guards as hook-enforced gates** (test tree hash-locked, assertion counts, `@Disabled` / `assumeTrue` diffs, coverage non-regression, no network during fix loops) | AWS re:Invent admission; OpenAI CoT-monitoring paper; Anthropic harness guidance; Cursor benchmark audit | text rules + git/download denies in `guard.sh` | the test-tree and coverage checks as hooks, not prose |
| U2 | **A deterministic repository fact ledger every plan must cite** | Moderne data tables + Prethink are the only product doing it, at $250K+ and customer-only distribution; iSWE: structured static-analysis tools matched grep recall at 14 % lower cost | `facts.md` per task, scripts for compile/test/deps/coverage/jdk | a fleet-level store (SQLite/DuckDB), OpenRewrite search recipes as fact sources, `FindMissingTypes` as a parse-quality gate |
| U3 | **Runtime verification of Spring upgrades beyond unit tests** (context boot, actuator beans/configprops/conditions diff, properties-migrator diagnostics as structured issues) | field failures that unit tests missed: `NoSuchFieldError` after SDK bump, Spotify's green-but-broken upgrade | 25 s smoke start in the upgrader | the before/after actuator diff and the structured migrator parse |
| U4 | **Reviewer-capacity-aware delivery** (recipe-only PRs auto-mergeable on green, LLM-touched PRs sized under 5 files for humans, sampled second review) | Google throttled generation to reviewer throughput; Adyen added meta-review; Copilot coding agent has the lowest merge rate (43 %) with large PRs | COMMIT POINT separation of recipe and manual commits | recipe-touched vs LLM-touched file tracking, PR sizing, auto-merge policy |
| U5 | **Published success metrics per campaign** (merged unchanged / merged with edits / rejected / reverted in 30 days / post-merge CI health / recipe-vs-LLM share / credits per merged PR) | no vendor publishes acceptance rates for Java upgrades; Google 74-87 % and Adyen 70 % are the only honest numbers | hooks ledger idea, usage-metrics API fields exist | the collector, the dashboard, and the feedback loop that disables low-success patterns |
| U6 | **LLM writes the recipe, the recipe writes the code**: repeated fixes become OpenRewrite or Refaster recipes with tests, versioned in an internal Apache-2.0 catalogue | Moderne `create-recipe` skill; EM-Assist (76 % hallucinated proposals filtered by a deterministic engine); immunity to MSAL / Code Genome licensing drift | `rewrite.yml` bundles | the recipe-authoring agent and the internal recipe repository |

## 3. Design principles (binding for every feature below)

1. **Cheapest check first.** parse -> non-whitespace diff -> self-check -> compile -> analyser -> tests -> runtime -> canary. A failure routes to a named manual queue, never to an unbounded LLM retry.
2. **Baseline before change.** Every task records the test tree, counts, flaky set, coverage, analyser findings, CVE set, API surface, and actuator snapshot on the source toolchain. Completion means "not worse than baseline" on every axis the task touches.
3. **Facts are tool output.** The planner may not cite anything that is not a script result, a recipe data table, or a user answer.
4. **Recipe-touched and LLM-touched files are tracked separately** and shipped separately.
5. **Enforce with hooks and CI, describe with prompts.** If a rule matters, a machine checks it.
6. **Everything is measurable per task and per campaign**, and the numbers are published to the team.

## 4. Feature catalogue

Effort: S = a day, M = a week, L = several weeks. Gate strength from the tooling research: HARD = can block a
COMMIT POINT; RATCHET = hard against a baseline; SOFT = input to judgment.

### 4.1 Verification engine (U1, U3)

| ID | Feature | Deterministic basis | Gate | Effort |
|---|---|---|---|---|
| V1 | **Baseline capture** `scripts/baseline.sh`: test tree hash per file, test/assert/`@Disabled` counts, 3x run flaky set (Surefire `rerunFailingTestsCount` / Gradle `test-retry`), JaCoCo totals, SARIF from the analysers the repo already runs, CVE set, OpenAPI spec, actuator snapshot. Written to `.agent-work/<task>/baseline/` | JUnit XML, JaCoCo XML, SARIF, Grype JSON, actuator JSON | n/a | M |
| V2 | **Test-integrity hook** (`postToolUse` + `agentStop`): deny or block when a test file outside the step's scope changed, assertion count fell, `@Disabled` / `assumeTrue` / `@Ignore` appeared, or a test method was deleted | diff of the test tree against V1 | HARD | S |
| V3 | **Coverage and mutation ratchet**: line/branch per touched class not below baseline; PIT with incremental history on touched classes, score not below baseline | JaCoCo, pitest `mutations.xml` | RATCHET | M |
| V4 | **Analyser ratchet**: SARIF normalised and diffed against baseline (SpotBugs, Checkstyle, PMD, Qodana community, Error Prone); "no new error-level findings" | SARIF fingerprints | RATCHET | M |
| V5 | **Architecture gate**: ArchUnit `FreezingArchRule` tests run after every refactor; a new violation names the dependency the agent added | JUnit result | HARD | S |
| V6 | **Contract gate**: `oasdiff breaking --format json` on generated OpenAPI before/after; japicmp or Revapi for published library modules | JSON reports | HARD (ERR level) | S |
| V7 | **Spring runtime gate**: boot the context, capture `/actuator/beans`, `/configprops`, `/conditions`, `/mappings`, sort and diff against baseline; parse `spring-boot-properties-migrator` log into structured issues | JSON diff | RATCHET (allow-listed changes) | S |
| V8 | **Database gate**: Flyway/Liquibase `validate`, migrations applied on a Testcontainers database, `ddl-auto=validate` at test start, Liquibase `diff --format=json` empty; destructive-SQL linter on pending migrations | tool exit codes, JSON | HARD; destructive heuristic SOFT | M |
| V9 | **Reproducible-build check**: two clean builds, jar checksums compared; proves a "no-op" refactor changed no bytecode | checksums | HARD | S |
| V10 | **Agent-stop gate**: `agentStop` hook that blocks completion while compile, tests, or any enabled ratchet is red (with the platform's runaway guard) | all of the above | HARD | S |

### 4.2 Fact engine (U2)

| ID | Feature | Deterministic basis | Effort |
|---|---|---|---|
| F1 | **Site discovery by type, not grep**: OpenRewrite search recipes (`FindMethods`, `FindTypes`, `FindDependency`, `FindDeprecatedUses`) with `exportDatatables`, parsed into `facts.md`; `FindMissingTypes` non-empty fails the plan | recipe data tables | M |
| F2 | **Inventory bundle** across repositories: Java level, framework versions, dependency list, deprecated API use, endpoints, test/impl map; CSVs merged into one SQLite/DuckDB file (`scripts/MergeDataTables.java`) | data tables + SBOM + JaCoCo | M |
| F3 | **Supply-chain facts with fix versions**: CycloneDX SBOM -> Grype/Trivy JSON (`FixedVersion`), cross-checked with the GitHub Advisory API `first_patched_version`; replaces scanner-CSV guesswork in `CveTriage` | JSON | S |
| F4 | **Prioritisation facts**: churn (git log, bot commits filtered) x missed complexity (JaCoCo counters) per class, for test and refactor targeting | git, JaCoCo XML | S |
| F5 | **Session-start facts hook**: `sessionStart` injects the repository's fact summary (toolchain, modules, flaky set, open tasks) so no agent re-derives it | hook `additionalContext` | S |
| F6 | **Konveyor kantra and Eclipse Transformer** as fact and unblock sources for Jakarta / EAP / Quarkus moves (rule corpus outside OpenRewrite; bytecode javax->jakarta for third-party jars) | YAML output, jar rewrite | M |

### 4.3 Change engine (U6)

| ID | Feature | Deterministic basis | Effort |
|---|---|---|---|
| C1 | **Recipe/LLM file tracking**: every step records which files the recipe touched and which the LLM touched; `plan.md` and the PR description show both lists; reviewers read only the second | git diff after each stage | S |
| C2 | **Second auto-fixer**: Error Prone patching with Refaster rules (Picnic Error Prone Support), `-XepPatchLocation`, compile-integrated; plus Spotless with `ratchetFrom` so every agent commit is a pure semantic diff | compiler + formatter | M |
| C3 | **recipe-author agent**: for any pattern seen more than N times in a task, writes a declarative YAML or Refaster recipe with `RewriteTest` before/after cases, runs the tests, applies across the repository; recipes land in an internal catalogue repository | OpenRewrite test harness | L |
| C4 | **Dependency hygiene actions**: `buildHealth` / `dependency:analyze` after every bump; `fixDependencies` and nebula.lint as safe auto-changes; lockfile diff emitted as a fact at every COMMIT POINT | plugin reports | S |
| C5 | **Solved-example memory with success rates**: (error signature or rule violation -> accepted diff) stored in the repo or fleet store; top-k injected as examples; patterns whose success rate drops are disabled (Konveyor Kai Solution Server model) | JSON store + metrics | M |

### 4.4 Platform integration (Copilot capabilities)

| ID | Feature | Mechanism | Effort |
|---|---|---|---|
| P1 | **Unattended runs**: Copilot CLI programmatic mode in GitHub Actions (`copilot -p --agent --allow-tool --max-ai-credits --output-format json`), billed to the org pool; nightly hygiene and weekly inventory | CLI + Actions | M |
| P2 | **Skills for deterministic procedures**: `.github/skills/<name>/SKILL.md` bundling the scripts with `allowed-tools: shell`; shared by IDE, CLI, cloud agent and code review | Agent Skills | S |
| P3 | **Hooks beyond deny**: session-start facts (F5), post-edit compile, agent-stop gate (V10), audit ledger (`.copilot-audit/ledger.jsonl` with session id, agent, tool, args hash) | hooks.json | S |
| P4 | **Cloud agent for bulk work**: `copilot-setup-steps.yml` preinstalling the JDK and Gradle cache, firewall allow-list including the corporate mirror, Automations or an Actions cron assigning issues with `custom_agent` | cloud agent | M |
| P5 | **Code review enforcement**: path-scoped review instructions encoding the pack's rules, a `code-review` skill, org ruleset auto-review on every agent-opened PR | Copilot code review | S |
| P6 | **MCP, read-only and allow-listed**: `.github/mcp.json` with GitHub MCP and SonarQube MCP read-only tools; managed-settings allow-list so nothing else can be attached | MCP | S |
| P7 | **Model per role**: cheap model for planner triage and mechanical loops, strong model for migration and integration tests, pinned in frontmatter; Auto where the discount applies | `model:` frontmatter | S |
| P8 | **JetBrains gap closure**: run the pack through the Copilot CLI provider inside IntelliJ, which inherits full hooks, skills, MCP and plugin semantics; document the switch in TESTING.md | plugin setting | S |

### 4.5 Fleet operation (U4)

| ID | Feature | Deterministic basis | Effort |
|---|---|---|---|
| X1 | **Campaign file**: target repositories, tiers (canary -> tier 2 -> all), steps with `recipe:` and/or `prompt:`, PR size cap, rate limit, stop flag; one PR per repository | YAML + CLI loop | L |
| X2 | **PR classes**: recipe-only PRs auto-merge on green checks; LLM-touched PRs go to human review, sized under 5 files, with a sampled second reviewer | C1 tracking + branch rules | M |
| X3 | **Rollout controls**: leaky-bucket PR rate, owners' working-hours scheduling, auto-close stale PRs, stop button, per-repository manual-intervention tracking | scheduler | M |
| X4 | **Post-merge watch**: CI health and revert detection for 30 days after each campaign PR, feeding the metrics (U5) and the solved-example store (C5) | GitHub API | M |

### 4.6 Measurement and evaluation (U5)

| ID | Feature | Source | Effort |
|---|---|---|---|
| M1 | **Per-task cost and outcome record** in `report.md`: credits (from CLI JSON output or usage metrics), recipe vs LLM file share, verification results, review outcome | CLI JSON, metrics API | S |
| M2 | **Weekly dashboard job**: usage metrics (`ai_credits_used`, `totals_by_custom_agent`, `totals_by_skill`) and billing API per bot identity -> "credits per merged upgrade PR", "merged unchanged %", "reverted within 30 days" | Actions + metrics API | M |
| M3 | **Regression harness for the pack itself**: `eval/` with fixture modules (the sample app plus two or three shapes: Maven, Groovy DSL, JUnit 4 legacy), CI runs each agent via `copilot -p` and fails on red build, credits over budget, or forbidden tool calls in the ledger | CLI + hooks ledger | M |
| M4 | **Pattern retirement**: any recipe, prompt template or solved example whose success rate falls below a threshold is disabled automatically and listed for review | C5 + M2 | S |

## 5. Phases

**Phase 1, verification first (4-6 weeks).** V1 baseline capture, V2 test-integrity hook, V10 agent-stop gate, V7 Spring
runtime gate, V5 ArchUnit, V6 contract gate, C1 recipe/LLM tracking, F3 supply-chain facts, P3 hooks, P2 skills,
M1 per-task record. Outcome: an agent cannot claim completion without proof, and reviewers know which files a model
wrote. This phase alone covers U1, U3 and half of U4.

**Phase 2, facts and inventory (4 weeks).** F1 type-attributed site discovery with `FindMissingTypes` gate, F2 inventory
bundle and merge tool, F4 prioritisation, F5 session-start facts, V3 coverage/mutation ratchet, V4 analyser ratchet,
C4 dependency hygiene, P7 model per role, P8 JetBrains via CLI provider. Outcome: U2; plans cite ledger rows.

**Phase 3, unattended and measured (4-6 weeks).** P1 CLI in Actions for nightly hygiene and weekly inventory, P5 code
review enforcement, P6 MCP allow-list, M2 dashboard, M3 regression harness, V8 database gate, V9 reproducible build.
Outcome: the pack runs without a laptop, and U5 numbers exist.

**Phase 4, fleet and learning (6-10 weeks).** X1-X4 campaign and rollout controls, P4 cloud agent bulk runs, C5
solved-example memory, M4 pattern retirement, C3 recipe-author agent, F6 Konveyor/Transformer. Outcome: U4 and U6;
one change across many repositories with canaries, sized PRs, and a feedback loop.

## 6. What to measure from day one

| Metric | Why | Source |
|---|---|---|
| merged unchanged % / merged with edits % / rejected % | the only honest acceptance rate (Google 74-87 %, Adyen 70 %) | PR data |
| reverted within 30 days, post-merge CI failures | Spotify's green-but-broken case | X4 |
| recipe-touched vs LLM-touched files per PR | shrinks review surface; shows where recipes are missing | C1 |
| credits per merged PR, per agent, per model | cost control under usage-based billing | M1, M2 |
| manual-intervention % per campaign step | Netflix went 44 % -> 21 % | X1 |
| verification ladder stage at which tasks fail | tells you which cheap check to add next | V10 ledger |

## 7. Risks and mitigations

- **Hooks are partial in the JetBrains plugin.** Mitigation: P8 (CLI provider inside IntelliJ) and CI enforcement of every gate, so the IDE is never the only line of defence.
- **OpenRewrite licensing drift** (MSAL recipes, Code Genome distribution, proprietary security recipes). Mitigation: C3 builds an Apache-2.0 internal catalogue; `OPENREWRITE-ADOPTION.md` tracks the decision.
- **Prompt-injection in tool output.** jqwik now prints text addressed to AI agents in test output; any third-party output can. Mitigation: agents read script summaries, never raw logs; the protocol already says so; add a hook that strips known markers from `toolResult`.
- **Benchmark-style reward hacking** (fetching fixes from the network, editing tests). Mitigation: V2, egress control in cloud/CLI sandboxes, and M3's forbidden-call check.
- **Reviewer fatigue.** Mitigation: X2 PR classes, size caps, sampled second review, working-hours scheduling.
- **Over-building.** Each feature above ships only after the metric it improves is being recorded; Phase 1 before anything else.

## 8. Decisions needed

1. Phase 1 scope sign-off and an owner per feature.
2. Which analysers are already standard in the repositories (SpotBugs, Checkstyle, PMD, Sonar) so V4 wraps what exists.
3. Whether Copilot CLI in GitHub Actions is permitted by the org policy ("billed to the organization") for P1.
4. A bot identity for unattended runs so credits are attributable (M2).
5. Whether a Moderne subscription is in scope (changes F1/F2 from DIY to product) and the legal position on MSAL recipes.
6. Canary repositories for Phase 4 and who owns the stop button.
