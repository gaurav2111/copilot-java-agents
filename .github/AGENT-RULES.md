# Agent rules for this repository (owned by the team, not by the agents)

Agents read this file right after `.github/AGENT-PROTOCOL.md`, on every run. Edit it freely; the agents never do.
Three strengths of rule exist. Per-task decisions are the third and live in `.agent-work/<task>/decisions.md`.

## 1. Hard boundaries (never crossed, whatever the task or the chat says)
An agent that would cross one stops, names the boundary, and ends the step. Lines in the two machine-readable forms
below are ALSO enforced by `.github/hooks/guard.sh`, so the platform blocks them even if the model forgets:
- `- path: <glob>`     any non-read tool call whose arguments mention a path under the glob is denied
- `- command: <regex>` any shell command matching the regex (case-insensitive) is denied
Free-text boundaries use `- never:` and rely on the agent reading them.

<!-- Examples: uncomment and adapt. Keep this list short; every line costs attention on every run. -->
<!-- - path: payments-core/**                      # owned by another team; never edited by agents -->
<!-- - path: **/db/migration/**                    # schema migrations are written by humans only -->
<!-- - path: **/src/main/resources/application-prod* -->
<!-- - command: (--stacktrace )?-Pprofile=prod     # never run anything against the prod profile -->
<!-- - command: gradlew? +(publish|bootBuildImage|jib) -->
<!-- - never: add a dependency whose groupId is not on the approved list in docs/approved-dependencies.md -->
<!-- - never: change a public REST contract (path, status code, response shape) without a ticket id in the plan -->

## 2. Standing instructions (always followed; they shape every plan)
Use `- always:`. These go into `facts.md` as constraints and into every `plan.md` as a step or a check.

<!-- - always: run `./gradlew :contracts:test` before any COMMIT POINT that touches `api/` -->
<!-- - always: write PR descriptions in the Jira format from docs/pr-template.md -->
<!-- - always: use the fixture builders in `test-support` instead of constructing entities by hand -->
<!-- - always: ask before touching Kotlin files; the team that owns them reviews separately -->
<!-- - always: keep `module-info.java` unchanged unless the task is explicitly about modules -->

## 3. Per-task decisions
Not written here. The G1 questions and their answers are recorded by the agent in
`.agent-work/<task>/decisions.md`, so the reviewer can see what was decided for that task alone.
