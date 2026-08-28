# Required API permissions

Least privilege throughout: the agent reads engineering data and writes exactly one thing — a Slack
message. It never writes to Jira or GitHub.

> Exact endpoint paths and scope names are verified against official vendor documentation at
> implementation time for each phase, not assumed from memory.

## Jira

**Auth (Cloud):** HTTP Basic with `JIRA_EMAIL` + `JIRA_API_TOKEN` (created at
`id.atlassian.com` → Security → API tokens). API token, never a password.

**Permissions:** a normal Jira user account with *Browse Projects* on the relevant projects is
sufficient. No admin rights. Read-only usage.

**Endpoints used (REST v3):**

| Purpose | Endpoint |
|---|---|
| Identify the current user | `GET /rest/api/3/myself` |
| Find in-progress work | JQL search endpoint (v3 search API) |
| Issue detail | `GET /rest/api/3/issue/{key}` |
| Comments | `GET /rest/api/3/issue/{key}/comment` |

Note: Atlassian has been migrating the Jira Cloud search endpoints — the exact search path and its
pagination model must be confirmed against current docs in Phase 1.

Descriptions and comments come back as ADF (Atlassian Document Format) on v3; the mapper flattens
ADF to plain text. Data Center/Server uses v2 with plain wiki markup instead.

## GitHub

**Auth:** fine-grained personal access token (`GITHUB_TOKEN`), scoped to the specific repositories
in play.

**Fine-grained token permissions (all read-only):**

| Permission | Access |
|---|---|
| Contents | Read |
| Metadata | Read |
| Pull requests | Read |
| Commit statuses | Read *(optional — CI state)* |

No write permissions. No org administration.

**Endpoints used:**

| Purpose | Endpoint |
|---|---|
| Find PRs by ticket key | Search API (`/search/issues`, `type:pr`) |
| PR detail | `GET /repos/{owner}/{repo}/pulls/{number}` |
| PR commits | `GET /repos/{owner}/{repo}/pulls/{number}/commits` |
| Changed files + patches | `GET /repos/{owner}/{repo}/pulls/{number}/files` |
| Reviews | `GET /repos/{owner}/{repo}/pulls/{number}/reviews` |
| Branches | `GET /repos/{owner}/{repo}/branches` |

**Rate limits:** the search API is substantially more restrictive than the core REST API, so search
results are cached per run and searches are batched per ticket rather than per repository.

## Slack

**Auth:** bot token (`SLACK_BOT_TOKEN`, `xoxb-…`) from a Slack app installed to the workspace.

**Bot token scopes:**

| Scope | Why |
|---|---|
| `chat:write` | Post the daily status |
| `im:write` | Open a DM channel for DM-to-self mode |
| `users:read` | Resolve the user ID for DMs |

The bot must be invited to any channel it posts to. No `channels:history`, no read scopes — the
agent does not read Slack in the MVP.

Create the app from [`slack/app-manifest.json`](../slack/app-manifest.json) — **Create New App →
From a manifest** — so the scope list is exactly the three above and nothing accumulates by
accident. Then **Install to Workspace** and copy the `xoxb-…` bot token.

No request URLs, no event subscriptions, no socket mode: the agent talks *to* Slack and Slack never
calls back. The approval buttons are handled by n8n's own webhook, not by this app.

## LLM provider

**Auth:** `OPENAI_API_KEY`.

Treated as an external data processor. See [SECURITY.md](SECURITY.md) for what is and is not sent,
and for the kill switch that disables external AI processing entirely.
