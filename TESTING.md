# Testing the pack in IntelliJ with GitHub Copilot

Four stages, cheapest first. Each step has an expected result and what a failure means. Use `examples/sample-app/`
in this repository (it has every flaw the agents target: old Java target, Spring Boot 2.7, four CVEs, a JUnit 4 module,
an untested class) or any small internal Gradle repository with the pack copied in.

## Before you start
- Copy `.github/`, `scripts/`, `rewrite.yml` to the repo root (already done in `examples/sample-app/`). `chmod +x scripts/*.sh`.
  Windows: run the shell scripts from Git Bash or WSL; the two Java tools run anywhere with a JDK 11+.
- Fill the stack block at the top of `.github/copilot-instructions.md` (done for `examples/sample-app/`).
- Settings > Plugins > GitHub Copilot: note the version. Custom agents need 1.6.1+, hooks 1.10.0+.
- Work on a branch. Agents never run git write commands; you commit at the COMMIT POINTs they mark.

## Stage 0: setup check (5 min)
| # | Do | Expect | If not |
|---|---|---|---|
| 0 | Terminal: `scripts/setup-check.sh` (or `/setup` in Copilot) | `PASS` or `PASS WITH WARNINGS`, `SETUP-REPORT.md` written, a proposed stack block at its end | Each FAIL line says what to run or change. Offline cache misses are one-time `BUILD_OFFLINE=""` commands. |

## Stage 1: the files load (10 min)
| # | Do | Expect | If not |
|---|---|---|---|
| 1 | Copilot Chat > Agent mode > agent picker | `planner`, `upgrader`, `security-fixer`, `migrator`, `test-engineer`, `docs-writer` listed | Plugin < 1.6.1, or org policy "editor preview features" off. Ask the Copilot admin. |
| 2 | Type `/` in the chat box | `plan`, `upgrade-java`, `upgrade-spring-boot`, `fix-cves`, `fix-sast`, `add-tests`, `raise-coverage`, `add-integration-test`, `migrate`, `document`, `review-upgrade-pr`, `resume` | Files not under `.github/prompts/` at the repo root, or project opened at a parent folder. |
| 3 | Plain Agent mode: "what build commands should you use in this repo?" | Quotes the `scripts/` commands; References panel lists `copilot-instructions.md` | Always-on file not picked up: check "Enable custom instructions" in Copilot settings. |
| 4 | Open a file under `src/test`, ask "what are the test rules here?" | Layout/naming, unit, integration, hard rules sections; References lists `tests.instructions.md` only (plus the always-on file) | `applyTo` glob does not match the layout. |

## Stage 2: scripts run on the office machine (10 min, IntelliJ terminal, repo root)
| # | Command | Expect | If not |
|---|---|---|---|
| 5 | `scripts/compile-errors.sh order-service` | `COMPILE OK (order-service) [gradle]` | `[maven]` on a Gradle repo: set `BUILD_TOOL=gradle`. Offline failure: run once with `BUILD_OFFLINE=""`. |
| 6 | `scripts/test-summary.sh order-service` | one line `total=2 failures=0 errors=0 skipped=0` (sample project) | `NO TEST REPORTS FOUND`: results are not under `build/test-results`; report the path. |
| 7 | `scripts/dep-path.sh org.yaml:snakeyaml` | per-project `RESOLVED` lines (sample project: 1.30 in `:order-service`) and a MANAGED BY section naming the dependency-management plugin and the implied `spring-boot-dependencies:2.7.18` | Empty on Windows Git Bash: paste the raw `gradlew dependencyInsight` output. |
| 8 | `./gradlew --offline -q :order-service:test` then `java scripts/CoverageGaps.java "**" 70 10` | summary line + table; the sample project ranks `PricingEngine` and `OrderService` first | "no jacoco.xml found": XML report disabled (`reports.xml.required = true`). |
| 9 | `scripts/dep-tree.sh` then `java scripts/CveTriage.java findings.csv build/deptree.txt` | 5 rows: commons-text TRANSITIVE via `:common`, snakeyaml and jackson TRANSITIVE via Spring Boot, guava DIRECT, spring-web TRANSITIVE | Column mismatch with your scanner: send the CSV header row. |
| 10a | `java scripts/LogAudit.java . 12 --csv build/log-statements.csv` | 10 statements; top line `OrderService.java:35` flagged `LOOP,PII`; config findings name `org.hibernate.SQL` and `org.springframework.web` at DEBUG | Zero statements: the tool only scans `src/main/java`; check the path. |
| 10b | `java scripts/LogVolume.java log-export.csv build/log-statements.csv --price-per-gb 0.50 --top 8` | 4.6 GB/day, top emitter matched to `OrderService.java:35` with action "REMOVE or redact", Hibernate pattern unmatched with action "check config package levels" | Column mismatch with your sink export: send the header row. |
| 10c | `scripts/log-lint.sh order-service/src/main` | BLOCK on the `System.out` line, WARN on three concatenations, exit 2 | |
| 9d | `scripts/cve-flow.sh xray-sample.json --module order-service --delta patch=apply,minor=apply,major=ask` | the `delta: policy` column changes with the policy: `bcpkix` and `bcutil` become `apply`, Tomcat and SnakeYAML `ask`, Jackson `BOM major: ask / override minor: apply`; the `policy outcome` line counts them; without `--delta` the `- cve-delta:` line in AGENT-RULES.md is used and major hops read `OUTSIDE DELTA POLICY` | A row with a fix and a resolved version but delta `-`: the verdict text changed and the classifier missed it; report the verdict. |
| 9c | `scripts/cve-flow.sh xray-sample.json --module order-service` (needs the repository; `--insecure` behind a re-signing proxy) | one table with six rows: Tomcat and SnakeYAML with a major-hop verdict and an `ext[...]` override, Jackson with two CVEs collapsed into one row, `bcpkix` "bump directly to 1.78", `bcutil` "transitive via bcpkix: bump bcpkix 1.77 -> 1.78 (ships bcutil 1.78)" with the note that the parent is itself a finding, guava "no fixed version published"; report at `build/cve/flow-report.md` | A row whose verdict starts with "repository unreachable": set `REPO_URL` or the truststore. |
| 9b | `java scripts/BomFix.java org.yaml:snakeyaml 2.0` (needs the mirror; Central works for the sample) | detects `spring-boot-dependencies:2.7.18` from the Boot plugin, reports that it manages 1.30, that no 2.7.x patch manages 2.0, that the first release which does is a major hop (3.2.0, managing 2.2, about 260 other managed versions move), and names `ext['snakeyaml.version']` as the override; about 10 seconds | `no newer release`: the mirror does not serve `maven-metadata.xml` for the BOM; set `REPO_URL`. |
| 9a | `java scripts/XrayToFindings.java <xray.json> findings.csv --min-severity High` | three summary lines (records in, findings out, components, bumps, no fix, not applicable) and a CSV the triage accepts. An artifact the report lists at several versions shows a `collapsed` line: one row per artifact+CVE, newest version, highest fix (`--all-versions` keeps them apart) | Zero findings out: the key names differ; edit `keysFor`-style lookups at the top of `toFindings()` in the tool. |
| 10 | `scripts/jdk-check.sh 21 order-service` | toolchain facts, empty jdeps/jdeprscan sections, trouble spots: `javax\.annotation\.` 1 file | `jdeps: command not found`: JDK bin not on PATH in the IDE terminal. |

## Stage 3: agents behave (30 min)
| # | Do | Expect | Breach to report |
|---|---|---|---|
| 11 | New chat, `planner`: `/plan upgrade order-service to Java 21` | Tool steps visible (file reads, script runs). Creates `.agent-work/<date>-upgrade-order-service-java21/` with four files. `facts.md` lines each have a path or command. Stops at **G1** with facts and numbered questions (or "no open questions, confirm"). Only after your answer: `plan.md` written and shown. Ends with "no files were changed outside .agent-work/". `git status` shows only `.agent-work/`. | Any "assume"/"likely" in facts. Plan produced in the same message as G1. Any file outside `.agent-work/` changed. No tool steps at all (then the agent has no tools: report plugin version). |
| 12 | Same chat: "run `git switch -c test-branch`" | `BLOCKED by .github/hooks/guard.sh` | It runs, or asks you for permission: hooks inactive on this plugin version (note, continue). |
| 13 | New chat: `/document :order-service | readme` | Candidate file list with read budget, waits, then `order-service/README.md` with Mermaid diagrams, Sources / Not covered / Unverified sections, no `inventory.example.internal` value copied | More than 15 files read. Config values or hostnames in the doc. |
| 13c | `java scripts/TestMatrix.java order-service` then `java scripts/TestMatrix.java common Money` | summary line with classes, methods, applicable cases and covered count; per method `covered:` and `missing:` keys; `NO TEST CLASS:` list; constructors, record accessors and `main` absent. `OrderService.place` shows `covered: happy exception valid invalid`. | A `for(` or `if(` row, or an accessor row: the method regex regressed. |
| 13d | New chat: `/unit-tests order-service | PricingEngine` | Names the exemplar, prints the matrix summary and the missing keys for `total(Order, boolean)`, waits at G1 with the skip list; on go writes one `@ParameterizedTest` per key group and one `@Test` per behaviour key with the key in the method name, a `not applicable` comment block, runs only that class, reruns the matrix, COMMIT POINT with cases before/after. | A test asserting only `doesNotThrow`, a reflection call, or a production file touched: breach. |
| 14 | New chat: `/add-tests order-service PricingEngine` | Names the exemplar (`OrderServiceTest`), lists behaviours as test names, waits, writes `PricingEngineTest`, runs only it, reports before/after coverage, marks COMMIT POINT | Any edit under `src/main`. `@Disabled`. New test dependency. Mockito introduced (not in the exemplar). |
| 14a | New chat: `/fix-logs order-service | log-export.csv | 0.50` | G1 shows counts, top statements, the PII candidate, the two DEBUG config levels, the measured top emitters, and asks for the preserved message list. Plan has themes in order: config, recipe pass, PII, levels, noise, messages. On go: one COMMIT POINT per theme, LogAudit counts recorded after each, final bytes and $ before/after. | A message from your preserved list changed: breach. Any theme skipped the lint: breach. |
| 14b | New chat: `/add-logs order-service | OrderController` | Audit of the class first, then a list of missing boundary logs as the plan (completion line with outcome and duration, error path with cause), waits, then one COMMIT POINT with lint clean and an ERROR-path test. | Any edit outside the named class, or a payload or email logged: breach. |
| 15 | Watch the per-turn credit indicator during 13 and 14 | Note the numbers; this is the token baseline | |
| 15a | Add `- path: <some-module>/**` to `.github/AGENT-RULES.md`, ask any agent to edit a file there | Stops, names the boundary; the hook blocks the edit even if the agent tries | Agent edits anyway and no BLOCKED message: hooks inactive (see 12). |
| 15b | Close a chat mid-task, open a new one on a different model, `/resume .agent-work/<folder>` | Three-line status from the Handoff block, "continued on <model>" appended to decisions.md, last verification re-run, continues at the next step | |

## Stage 4: one real task end to end (an afternoon)
| # | Do | Expect |
|---|---|---|
| 16 | `scripts/rewrite.sh org.openrewrite.java.RemoveUnusedImports` | per-module count, "no changes proposed" on the sample project. Failure resolving `org.openrewrite:plugin`: mirror does not proxy the Gradle Plugin Portal (Nexus ticket). |
| 17 | New chat: `/fix-cves findings.csv HIGH` | Triage table, grouped plan at G1/G2, catalog edits with CVE comments, `dep-path` proof per group, COMMIT POINT per group, before/after table. commons-text and guava become catalog bumps; snakeyaml and jackson become "Spring Boot BOM bump" with a stop for approval (it is a 2.7 -> 3.x hop, so expect it to hand off to upgrader rather than do it). |
| 18 | New chat: `/migrate legacy-reports junit4 -> junit5` | Inventory (2 test files), coverage check, recipe dry run via `TestStackModernise`, apply, zero `org.junit.Test` / `org.hamcrest` references left, `tasks.test { useJUnitPlatform() }` |
| 19 | `/review-upgrade-pr main` after 17 or 18 | PASS/FAIL checklist with evidence lines |
| 20 | Long task: close the chat mid-way, new chat `/resume .agent-work/<folder>` | Three-line status, re-runs last verification, continues at the next open step |

## What to report
For anything that fails: the step number, the plugin version, the exact output or screenshot, and `git status` if files
changed. Raise it as an issue in this repository; most fixes are a wording change in one agent file or a path in one script.
