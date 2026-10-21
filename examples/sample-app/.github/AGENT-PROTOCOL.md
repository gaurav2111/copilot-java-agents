# Agent protocol (read once at the start of every agent run; ~2 minutes)

Every agent in `.github/agents/` follows this protocol. It exists so that nothing is guessed, every decision is on
record, and any task can be resumed in a fresh chat from four small files instead of a long history.

## 1. Facts come from three places only
1. **The repository**: build files (Groovy `build.gradle` or Kotlin `build.gradle.kts`, both are normal), version catalog or `ext {}` / `gradle.properties`, wrapper properties, CI files, source. Read them.
2. **Commands**: the scripts in `scripts/`, `java -version`, `./gradlew --version`, `jdeps`, `jdeprscan`, `git log`/`git status`/`git diff`. Run them.
3. **The user**: for anything the first two cannot answer (target version, scope, severity floor, business constraints, which of several valid strategies). Ask.
A fact that did not come from one of these does not exist. The words "assume", "likely", "typically", "should be" and
"must be verified" are not allowed in `facts.md`; if you are tempted to write them, that item is a question for the user.

## 1b. Team rules: `.github/AGENT-RULES.md`
Read it after this file, every run, if it exists. Hard boundaries (section 1) end a step when they would be crossed; say
which one. Standing instructions (section 2) become constraints in `facts.md` and steps or checks in `plan.md`. The
agent never edits that file; proposing a new rule goes to `issues.md`.

## 1c. Setup gate
`SETUP-REPORT.md` at the repository root must exist with result PASS or PASS WITH WARNINGS, and be newer than the
build files it validated (`settings.gradle*`, `build.gradle*`, `gradle/libs.versions.toml`, `pom.xml`). If it is
missing or stale, say "run `/setup` first" and stop; do not start a task on an unvalidated repository.

## 2. Working folder: `.agent-work/<yyyy-mm-dd>-<task-slug>/`
Create it at the start (`<task-slug>` = `upgrade-order-service-java21`, `cve-batch-oct`, `coverage-billing`). Four files, all Markdown, all short:

| File | Holds | Rules |
|---|---|---|
| `facts.md` | Every fact used, one line each: `fact - source (path or command) - value` | Append-only. A fact without a source is deleted. |
| `decisions.md` | Every choice point: question, options offered (with the recommended one marked), answer, who answered (user / agent-by-default-after-user-OK), one-line reason | Append-only. No decision is made silently; if the agent chose a default, that is written down too. |
| `plan.md` | Numbered steps with `[ ]` / `[x]` / `[!]` (blocked), each with: what, tool (recipe / script / manual), verification command and its last result, commit point marker; ends with the **Handoff** block (below) | Updated after **every** step. This file is the progress log and the resume point. |
| `issues.md` | Anything found but not in scope: pre-existing failures, debt, risks, out-of-scope modules, odd code, questions for owners. Severity, where, why it matters, suggested owner | Append-only. Nothing is silently dropped. |

The last section of `plan.md` is always a **Handoff** block, rewritten after every step so that a new chat, a different
model, or a colleague can continue without the history:
```
## Handoff
- Status: executing | waiting at G1/G2/G3 | DONE
- Last closed step: 3 - result: "<one-line verification result>"
- Next action: step 4 - "<exact command or edit>"
- Open questions: none | Q5 ...
- Session: model=<name as shown in the picker>, client=<IntelliJ Copilot x.y.z>, date=<yyyy-mm-dd>, by=<user>
```
At the end the standard report is assembled **from these files**, never written from memory. If the team does not want
them committed, add `.agent-work/` to `.gitignore`; by default they travel with the branch so the reviewer sees the reasoning.

## 3. Gates (hard stops where the agent waits for the user)
```
G0  Preflight read     -> facts.md has every value the task needs, with sources, or open questions listed
G1  Confirm facts      -> show facts + open questions as ONE message; ask the questions (format below); WAIT
G2  Plan               -> plan.md written; show it; WAIT for "go", "go with changes", or edits
G3  Each commit point  -> show git diff --stat, the verification results, and the suggested commit message; WAIT
G4  Report             -> assembled from the four files
```
An agent that reaches G1 with zero open questions still stops at G1 and says so; the user confirms the facts in one word.
Never skip a gate because the task "looks simple". Never merge G1 and G2 into one message.

## 4. How to ask (so the user can answer with one keystroke)
Batch all open questions into one message, at most 5, each in this shape:

```
Q1. Target Java version?            (facts.md: current is 11, from gradle/libs.versions.toml:3)
    1) 17   2) 21  <- recommended: current LTS supported by Spring Boot 3.x   3) 25   4) other
Q2. Scope?
    1) :order-service only  <- recommended: it is the only module on Java 11   2) whole build   3) other
```
Rules: numbered options, one recommended option with a one-line reason, always an "other" option, no question whose
answer is already in `facts.md`. The user answers `1: 2, 2: 1`. In VS Code the `vscode/askQuestions` tool may render
these as buttons; the numbered text form works in every client, including JetBrains. Record each answer in `decisions.md`
before doing anything else.

## 5. What an agent never does
- **No git write commands.** No `git add`, `commit`, `switch`, `checkout -b`, `stash`, `rebase`, `merge`, `push`, `tag`. Read-only git (`status`, `log`, `diff`, `rev-parse`, `shortlog`) is fine. Instead the agent writes `COMMIT POINT: <suggested message>` into `plan.md` and the chat, and the user commits. (Enforced by `.github/hooks/guard.sh`.)
- **No downloads or installs.** No JDKs, SDKs, Gradle distributions, CLIs, package-manager installs, `curl | sh`. Gradle toolchain auto-provisioning (foojay) is not enabled by the agent. If a build needs something not present, the agent writes the exact command for the user into `issues.md` and stops. Builds run offline by default; the user runs the one online fetch when asked.
- **No hard-coded values.** Versions go in the catalog / parent properties; environment-specific values go in configuration; test data uses fixtures; no literal hostnames, credentials, account ids, or customer-like data anywhere, including docs.
- **No silent defaults.** Choosing a default is allowed only after the user has approved "use recommended defaults where I did not answer" at G1, and each default is still written to `decisions.md`.
- **No scope creep.** Something found outside the task goes to `issues.md`, not into the change.

## 6. Verification is a command, not a sentence
Every step in `plan.md` names the command that proves it and records the command's one-line result when the step is
closed. Preferred: `scripts/compile-errors.sh`, `scripts/test-summary.sh`, `scripts/dep-path.sh`, `scripts/jdk-check.sh`,
`java scripts/CoverageGaps.java`, `java scripts/CveTriage.java`. "It compiles" without the script line is not a result.

## 7. Resuming, new chats, model changes
- **Start of every agent run**: look for `.agent-work/*/plan.md` whose Handoff status is not DONE. If one exists and the
  user's request matches it or the request is empty, offer to resume it before anything else; if several exist, list them.
- **Resume** (`/resume <folder>`, or the offer above): read the Handoff block first, then the four files; re-run the
  verification command of the last `[x]` step to confirm the tree still matches (if it does not, stop and show the
  difference); continue at the first `[ ]` or `[!]` step. Do not re-read the codebase beyond what the next step needs.
- **Model or client change**: if the model or client in the Handoff block differs from the current one, append a line to
  `decisions.md` ("continued on <model> from step n") and update the Session line. Verification does not change: the
  same commands prove the same steps whatever the model.
- **Long tasks** are meant to span several short chats: two or three steps per chat, then resume. The files are the
  memory; the chat is disposable.

## 8. Token discipline inside this protocol
- `facts.md` is for values, not file contents. Never paste a build file into it; paste the line.
- Read budgets in each agent file still apply. Reaching a budget is a reason to stop at a gate, not to keep reading.
- Scripts first, raw output never. If a script's summary is not enough, say which extra line you need and why.
