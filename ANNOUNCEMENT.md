# Java maintenance agents, version 2 (draft)

This is a draft; I am still testing it and aim to release a finished version by Diwali.

What is in the pack: 6 agents, 13 prompts, 1 always-on instruction file plus 4 path-scoped ones, 1 guard hook,
9 scripts, 7 OpenRewrite recipe bundles, 1 shared protocol, 1 team rules file, 1 sample project with planted flaws,
and 4 documents (README, testing checklist, OpenRewrite adoption plan, roadmap).

The old setup was two agents, planner and implementer, each a 600-line file doing everything: upgrades, CVE fixes,
tests. It worked, but every chat carried both files on every turn, and every rule was a sentence the model was asked
to follow.

The new pack splits that into pieces Copilot loads only when needed. For the layout I took reference from Everything
Claude Code, the open-source configuration collection from an Anthropic hackathon winner, which uses the same split of
agents, commands, rules and hooks. Ours applies that shape to GitHub Copilot and to one domain, Java maintenance.

- **Instructions**: one 34-line file always on, with the stack, the commands and the hard rules. Path rules for Java,
  tests, build files and docs load only when such a file is touched.
- **Agents**: six short files, one per task type: planner, upgrader, security-fixer, migrator, test-engineer,
  docs-writer. Only the one you pick is loaded.
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
