# What Claude Code has, what the Copilot JetBrains plugin 1.9.1-251 can do, and what the pack should add

Date: 2026-11-04. Plugin 1.9.1-251 shipped 15 May 2026 for IntelliJ 2025.1 (the last line that still supports 2025.1;
from October 2026 the plugin needs 2025.2). Everything the GitHub changelog announced for JetBrains before that date
is in it: custom agents, sub-agents and the plan agent (GA, March 2026), agent hooks (public preview, March 2026),
MCP auto-approve (March), AGENTS.md and CLAUDE.md loading (March), global and per-category auto-approve (April), the
Copilot CLI agent inside the IDE with worktree isolation and the ask-question tool (public preview, 13 May 2026).
Not in it: Copilot memory (August 2026), assisted approvals (September), local sandboxes GA (October).

Two things from that list change what the pack can do: the **ask-question tool** (native multiple-choice questions
instead of numbered options in text) and the **Copilot CLI agent** (a second harness inside the IDE that runs the
full hook set, skills and worktrees). Business and Enterprise accounts need the "Editor preview features" policy on
for hooks and for the CLI agent.

## 1. Concept by concept

| Claude Code | Copilot in JetBrains 1.9.1 | Pack today | Add or change |
|---|---|---|---|
| `CLAUDE.md` at root and per directory, `@file` imports, `.claude/rules/*.md` with `paths` | `.github/copilot-instructions.md`, `.github/instructions/*.instructions.md` with `applyTo`, plus `AGENTS.md` and `CLAUDE.md` loaded from the workspace (nested files switchable in Settings > Customizations) | copilot-instructions.md, five instruction files | **A1.** Add a root `AGENTS.md` (pointer to the protocol and the scripts) and a root `CLAUDE.md` (same pointer) so the pack behaves the same under Copilot CLI, Codex and Claude Code. **A2.** Per-module `AGENTS.md` for module facts (the directory-level CLAUDE.md idea). |
| Hooks: 30+ events, JSON output (`permissionDecision`, `additionalContext`, `updatedInput`, `decision: block`), `Stop` as a finish gate, `SessionStart` context injection | Local harness: `userPromptSubmitted`, `preToolUse`, `postToolUse`, `errorOccurred` (preview). CLI agent: the whole CLI set, including `sessionStart`, `agentStop`, `subagentStop`, `postToolUseFailure`, `permissionRequest`, `preCompact`. Same `hooks.json`; event names in camelCase or PascalCase; unknown events ignored by the harness that lacks them | one `preToolUse` guard | **B1.** `postToolUse` on edit tools: test guard (a test file deleted, `@Disabled` added, assertion count dropped) and log-lint on touched files; stderr goes to the user. **B2.** `sessionStart` (CLI agent): print SETUP-REPORT status and the unfinished `.agent-work` Handoff as `additionalContext`, so `/resume` is automatic. **B3.** `agentStop` (CLI agent): the cannot-finish gate; `decision: block` while `plan.md` has an unticked step or the baseline compare is red. **B4.** `errorOccurred` and `userPromptSubmitted`: append one line to `.agent-work/<task>/audit.log` (the per-task record). All in the existing `hooks.json`; the local harness simply ignores B2 and B3. |
| Subagents in `.claude/agents/*.md` with `model`, `tools`, `permissionMode`, `skills`; Explore agent for cheap read-only fan-out | Custom agents in `.github/agents/*.agent.md` and `~/.copilot/agents`; sub-agents GA; `user-invocable`, `disable-model-invocation`, `model`, `tools`, `handoffs`, `argument-hint` in frontmatter (`tools` broke the plugin in an earlier version; `model` and `handoffs` unverified in JetBrains) | seven agents, no frontmatter beyond name and description | **C1.** `disable-model-invocation: true` on the six implementing agents so the main agent never picks `upgrader` on its own; planner stays selectable both ways. **C2.** A read-only `facts-collector` sub-agent that runs the fact scripts and returns their summary lines, the Explore pattern: the main agent delegates the Gradle runs and keeps its context small. **C3.** `handoffs` from planner to the implementer (button after the plan). **C4.** Re-test `tools:` and `model:` with a canary agent on 1.9.1 before using them; if `model` works, `facts-collector` and the recipe-residue loop go on the cheaper model. |
| `AskUserQuestion` tool | Ask-question tool, available in agent mode, custom agents and sub-agents since 13 May 2026 | numbered options in text (protocol section 4) | **D1.** Protocol section 4: use the ask-question tool when it exists, one question per gate, options as the tool's choices; fall back to the text form. Record the answer in `decisions.md` either way. |
| Skills: `.claude/skills/<name>/SKILL.md`, loaded on demand by description | `.github/skills/<name>/SKILL.md`, also `.claude/skills` and `.agents/skills`; documented for CLI, VS Code and cloud agent; JetBrains local harness not documented, CLI agent yes | none; procedures live inside the agent files | **E1.** Move the procedural halves of the agents into skills: `cve-flow`, `logging-cleanup`, `test-matrix`, `openrewrite-run`, `bom-fix`. Agents keep the protocol and the phase order; the skill carries the commands and the reading of their output. Agent files shrink, and the knowledge loads only in the task that needs it. Verify on 1.9.1 whether the local harness loads skills; under the CLI agent they load. |
| Plan mode: read-only exploration, plan approval, then edits | Built-in plan agent (GA) and our planner | planner agent, read-only by protocol | **F1.** Keep ours (it reads the build files and runs the scripts; the built-in one does not know them). Once `tools:` is confirmed, give the planner a read-only tool list so read-only becomes enforced, not promised. |
| `.claude/settings.json`: `permissions.allow/deny/ask`, sandbox, `env` | Settings > GitHub Copilot > Chat > Auto Approve: global, per-category defaults, command and file-edit rules, MCP server and tool auto-approve; enterprise managed settings; `cwd`, `env`, `timeoutSec` per hook | AGENT-RULES `- path:` and `- command:` lines enforced by the guard | **G1.** `docs/COPILOT-SETTINGS.md` with the recommended auto-approve rules: allow `scripts/*.sh`, `java scripts/*.java`, `./gradlew --offline`; never auto-approve git or anything outside the repo; MCP read-only tools only. **G2.** `env` block in `hooks.json` for `BUILD_OFFLINE`, `REPO_URL`, so hook commands and agent commands see the same settings. |
| Auto memory per project, `MEMORY.md` index | Copilot memory arrived in August 2026, not in 1.9.1 | `.agent-work/<task>/` files and `/resume` | **H1.** `scripts/agent-work-index.sh`: one table of tasks with status and last Handoff, so `/resume` and B2 find the right folder without listing directories. |
| Worktrees (`isolation: worktree`) | CLI agent isolation mode Worktree or Workspace | none | **I1.** PROMPTS.md: run `/upgrade-java`, `/upgrade-spring-boot`, `/migrate` and `/fix-cves` under the CLI agent in Worktree mode; the branch is untouched until you review. |
| Slash commands `.claude/commands/*.md` with `$ARGUMENTS` | Prompt files with `${input:...}` | 16 prompts | nothing to add |
| `PreCompact`, context-window pressure | Context window usage indicator in the chat panel; `preCompact` under the CLI agent | token discipline section, Handoff block | **J1.** `preCompact` hook (CLI agent): write the current Handoff block to `plan.md` before compaction, so nothing is lost when the context is summarised. |
| Status line, output styles, notifications | none | none | nothing |

## 2. What to do in which order

1. **D1, C1, A1** (one afternoon, no new tooling): native questions, no accidental agent selection, cross-tool instruction files.
2. **B1 and B4** (one day): the test guard and the audit log on events the local harness already fires. Needs the preview policy on; everything else in the pack still works when it is off.
3. **G1, I1, H1** (half a day): settings guide, worktree advice, task index.
4. **B2, B3, J1** (one day, activates only under the CLI agent): session start context, the cannot-finish gate, pre-compaction handoff. Test with the CLI agent on the office machine first; B3 is the one feature from the roadmap's Phase 1 that a hook can deliver today.
5. **C2, C4, E1, F1** (two to three days, each gated on a canary test): the facts sub-agent, `tools` and `model` in frontmatter, skills, enforced read-only planner.

## 3. What a canary test looks like (for C4, E1, F1)

A throwaway agent `canary.agent.md` with `tools: ['read', 'search']` and `model: <a cheap model>`, and a prompt that
asks it to read `settings.gradle.kts` and then to edit a file. Expected on a plugin that honours the fields: it reads,
reports the edit is not allowed, and the model name in the chat header is the cheap one. If it cannot read at all, the
plugin still breaks on `tools:` and the line stays out of the real agents, as today. A second canary for skills: a
`.github/skills/canary/SKILL.md` whose description says "use when asked for the canary word"; ask for the canary word
in agent mode and see whether the skill's content appears in the answer.

## 4. Sources

- GitHub changelog, major agentic capabilities in JetBrains (custom agents, sub-agents, plan agent GA; hooks preview; AGENTS.md and CLAUDE.md loading; MCP auto-approve), 11 March 2026: https://github.blog/changelog/2026-03-11-major-agentic-capabilities-improvements-in-github-copilot-for-jetbrains-ides/
- GitHub changelog, inline agent mode and auto-approve settings, 24 April 2026: https://github.blog/changelog/2026-04-24-inline-agent-mode-in-preview-and-more-in-github-copilot-for-jetbrains-ides
- GitHub changelog, Copilot CLI agent and unified sessions in JetBrains (worktree isolation, ask-question tool, global agents), 13 May 2026: https://github.blog/changelog/2026-05-13-introducing-copilot-cli-agent-and-unified-sessions-view-in-github-copilot-for-jetbrains-ides/
- JetBrains Marketplace, plugin 1.9.1-251 (15 May 2026): https://plugins.jetbrains.com/plugin/17718-github-copilot--your-ai-pair-programmer/versions/stable/1048241
- GitHub changelog, end of support for IDE 2025.1, 10 October 2026: https://github.blog/changelog/2026-10-10-new-controls-and-chat-improvements-in-copilot-for-jetbrains/
- GitHub docs, hooks concepts and reference (events, JSON fields, exit codes): https://docs.github.com/en/copilot/concepts/agents/hooks and https://docs.github.com/en/copilot/reference/hooks-reference
- GitHub docs, custom agents configuration (frontmatter properties): https://docs.github.com/en/copilot/reference/custom-agents-configuration
- GitHub docs, agent skills: https://docs.github.com/en/copilot/how-tos/use-copilot-agents/cloud-agent/create-skills
- Claude Code hooks reference: https://code.claude.com/docs/en/hooks
