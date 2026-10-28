# Live scanner data for the security-fixer: MCP setup for Copilot in IntelliJ

Why: the agent works from an exported report. A finding whose fixed version was published after the export shows up
as "no fixed version", and a report older than a day may already be wrong. With an MCP server the agent can ask the
scanner directly, per artifact, while it triages. Two servers are relevant; set up the one that matches where your
findings come from. Nothing in the pack requires either; the offline path stays the default.

Status of what is written here: assembled from the vendors' documentation in October 2026, not yet run on a work
machine. The checklist at the end is what to verify first.

## 1. Where the Copilot plugin keeps MCP servers (JetBrains)

1. Click the Copilot icon in the IntelliJ status bar, then **Edit Settings**, then **Model Context Protocol**, then
   **Configure**. That opens the plugin's `mcp.json`.
2. Paste a `servers` block (see below), save. The plugin restarts the servers; if a tool does not appear in Agent
   mode, restart the IDE once.
3. MCP tools are available in **Agent mode** only, which is also the mode the pack's agents run in.
4. Tool calls go through the same hooks as shell commands; the guard denies nothing here because these tools are
   read-only, but `.github/AGENT-RULES.md` `- command:` lines do not apply to them. Prefer the read-only endpoints
   below so the agent cannot change anything on the scanner side.

## 2. JFrog Xray through the JFrog MCP Server

The JFrog Platform ships a remote MCP server. It is generally available on JFrog Cloud; on a self-managed platform it
is in beta and is deployed next to the platform with Helm or Docker Compose, so the first question for your Artifactory
admin is whether it is enabled at all.

**Admin side (once):** Platform Admin opens the platform, **Integrations**, **JFrog MCP Server**, **Set Up**, accepts
the terms and copies the endpoint, which is the platform URL plus `/mcp`, for example `https://company.jfrog.io/mcp`.
No trailing slash on the base URL.

**Client side (`mcp.json`):**

```json
{
  "servers": {
    "jfrog": {
      "url": "https://<your-platform-host>/mcp"
    }
  }
}
```

The vendor documents OAuth for this server: on first use a browser window asks you to authorise the client. The
documented clients are VS Code, Cursor, Kiro, Claude and Codex; the JetBrains Copilot plugin is not on that list, and
GitHub states that its own remote server cannot do OAuth from JetBrains. So the open question for your test is whether
the JetBrains plugin completes the JFrog OAuth flow. If it does not, try an access token header, which the plugin does
support:

```json
{
  "servers": {
    "jfrog": {
      "url": "https://<your-platform-host>/mcp",
      "requestInit": { "headers": { "Authorization": "Bearer <JFrog access token, scoped read-only>" } }
    }
  }
}
```

Whether the JFrog server accepts a bearer token instead of OAuth is not stated in its documentation; it is the second
thing to verify. Never put the token in a file that is committed; the plugin's `mcp.json` lives in your user profile.

**Tools the agent will use** (names from the server's tool reference; none of these write anything):

| Tool | Input | What the agent does with it |
|---|---|---|
| `catalog_vulnerabilities_get` | `cve_id` | For a row with no fixed version: affected packages and version ranges for the CVE, today. |
| `catalog_packages_versions_vulnerabilities` | `package_type: maven`, `package_name`, `package_version` | Confirms whether the version the build resolves is still listed as vulnerable (stale-scan check without a rescan). |
| `catalog_packages_list_versions` | `package_type: maven`, `package_name`, `vulnerability_status: invulnerable` | The smallest clean version above the resolved one, when the report had none. |
| `xray_artifact_get_summary` | artifact `paths` or `checksums` | Security summary of a built artifact in Artifactory (the thing Xray scanned). |
| `xray_sbom_search_impacted_resources` | `vulnerability` or `package_name` + `package_version` | Which other builds and artifacts carry the same finding; useful for the "one BOM bump closes N" argument. |
| `xray_artifact_security_status` | `repo`, `path` | Whether Xray has finished indexing the artifact the report came from. |

The package name format for Maven in the catalog tools (`group:artifact` or `group/artifact`) is not documented; the
first call will tell. Write the working form into `SETUP-REPORT.md` so the agent does not rediscover it every run.

## 3. GitHub Dependabot data through the GitHub MCP Server

Only relevant when the code lives on GitHub with Dependabot alerts enabled. The remote server needs a personal access
token in JetBrains (OAuth is not supported there). Use a fine-grained token with read access to Dependabot alerts
(classic scope `security_events`), nothing else, and the read-only Dependabot endpoint:

```json
{
  "servers": {
    "github-dependabot": {
      "url": "https://api.githubcopilot.com/mcp/x/dependabot/readonly",
      "requestInit": { "headers": { "Authorization": "Bearer <PAT>" } }
    }
  }
}
```

To combine toolsets on the base URL instead, add the header `X-MCP-Toolsets: dependabot,repos` and
`X-MCP-Readonly: true`. Tools: `list_dependabot_alerts` (owner, repo, severity, state) and `get_dependabot_alert`
(owner, repo, alertNumber). The alert carries the advisory, the vulnerable range and the first patched version, which
is the same fact Xray's `fixed_versions` carries.

## 4. What changes in the agent's behaviour

Nothing unless a tool is present. When one is, the security-fixer does this before G1, for the rows the flow marked
"no fixed version published" or "not in the dependency tree":

1. Ask the scanner for the CVE (or the package at the resolved version).
2. If a fixed version exists now, update the row: fix column filled, source `xray-live` (or `dependabot-live`) and the
   date, and rerun `java scripts/BomFix.java <g:a> <fix> --resolved <resolved>` for that row only.
3. Report the refreshed rows as a separate list at G1 ("fixed version published after the scan") so the reviewer knows
   these were not in the audited report.
4. Never change the report file. The report stays the artefact the security team saw; the live values go into
   `facts.md` with their source.

The flow's own table is unchanged; the agent appends a "live" column in its reply only for refreshed rows.

## 5. Checklist for the first run on the work machine

1. Does the JFrog MCP Server appear under Integrations for your platform, and is it enabled? If not, that is a ticket
   to the Artifactory admin and the rest waits.
2. Does the JetBrains plugin list the `jfrog` server as connected after saving `mcp.json`? With OAuth, did a browser
   window open? Without, did the bearer token work?
3. In Agent mode, ask: "call catalog_vulnerabilities_get for CVE-2024-30172 and show the raw result". Note the package
   name format used in the response.
4. Ask: "list invulnerable versions of maven package org.bouncycastle:bcpkix-jdk18on". If the name format is
   rejected, try `org.bouncycastle/bcpkix-jdk18on`.
5. Record both working forms in `SETUP-REPORT.md` under a new "MCP" line, then run `/fix-cves` on a report with a
   known no-fix row and confirm the agent refreshes it before G1 and labels it `xray-live`.

If step 2 fails in both forms, the JetBrains plugin cannot reach this server yet; the fallback is the GitHub Copilot
CLI, which supports OAuth for remote MCP servers, or waiting for the plugin. Note the plugin version in
`SETUP-REPORT.md` either way.

## Sources

- JFrog, Add the JFrog MCP Server to an MCP client: https://docs.jfrog.com/integrations/docs/add-the-jfrog-mcp-server-to-an-mcp-client
- JFrog MCP Server repository and tool reference: https://github.com/jfrog/jfrog-mcp-server (TOOLS.md)
- JFrog MCP Server installation (self-managed): https://docs.jfrog.com/installation/docs/mcp
- GitHub MCP Server, remote server toolsets and headers: https://github.com/github/github-mcp-server/blob/main/docs/remote-server.md
- GitHub MCP Server, JetBrains installation: https://github.com/github/github-mcp-server/blob/main/docs/installation-guides/install-other-copilot-ides.md
- GitHub changelog, dependency scanning with the GitHub MCP Server: https://github.blog/changelog/2026-05-05-dependency-scanning-with-github-mcp-server-is-in-public-preview/
