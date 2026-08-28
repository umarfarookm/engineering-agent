# Architecture

## 1. Purpose and scope

The system answers, for a single engineer, questions of the form *"what am I working on, what did I
finish, what's blocked, what do I say in stand-up?"* — grounded in Jira and GitHub evidence rather
than recollection.

The MVP is deliberately narrow: a scheduled daily job that produces one accurate status message.
Everything else (sprint reports, performance-review material, incident correlation) is a downstream
consequence of storing that daily output well, and is explicitly out of scope until the MVP works.

## 2. Component responsibilities

### n8n — orchestration only

- Cron trigger (configurable daily time)
- HTTP calls to the Spring Boot service
- Retry and backoff on transient failures
- Human-approval step before Slack posting
- Failure notification

n8n holds **no** business logic. If a rule needs a test, it belongs in Spring Boot.

### Spring Boot — the whole domain

- Jira, GitHub, Slack clients (isolated in `integration/`, never leaking vendor DTOs outward)
- Normalization of vendor payloads into internal domain models
- Jira↔GitHub matching and confidence scoring
- `EngineeringContext` assembly
- LLM orchestration, prompt management, response validation
- Persistence and history
- Security, redaction, correlation IDs

### LLM — reasoning, not retrieval

Given a fully-assembled `EngineeringContext`, the model classifies and summarizes. In the MVP it has
no tools and makes no API calls. This keeps the data sent to the provider auditable and the output
reproducible.

### PostgreSQL — memory

Tickets, PRs, commits, and generated summaries, keyed by user and date. Enables "what did I do last
month" without re-querying Jira/GitHub history.

## 3. Request flow (MVP)

```
n8n cron
  → POST /api/work/analyze                    (Spring Boot)
      → JiraClient.getInProgressIssues()      JQL: assignee = currentUser() AND status IN (configured active statuses)
      → for each issue:
          GitHubActivityMatcher.match(issueKey)
            → branch name    → PR title → PR body → commit messages
            → confidence: MATCHED | POSSIBLE_MATCH | NO_MATCH
          → PR details, commits, changed files, reviews
      → EngineeringContextAssembler → EngineeringContext
      → EngineeringReasoningService → LLM → DailyWorkSummary (JSON, schema-validated)
      → persist to PostgreSQL
  → POST /api/slack/preview                   → formatted message returned, not sent
  → human approval in n8n
  → POST /api/slack/send                      → chat.postMessage
```

## 4. Package structure

```
src/main/kotlin/com/example/engineeringagent/
  config/                 Spring configuration, HTTP clients, properties binding
  controller/             REST endpoints (thin; no logic)
  service/                Application services / use cases
  domain/                 Normalized domain models + value objects (no framework types)
  repository/             Spring Data JPA repositories
  integration/
    jira/                 JiraClient, Jira DTOs, JiraIssueMapper
    github/               GitHubClient, GitHub DTOs, matcher
    slack/                SlackClient, message blocks
    ai/                   LlmClient interface, OpenAiLlmClient, prompt loading
  agent/
    context/              EngineeringContextAssembler
    reasoning/            EngineeringReasoningService, response validation
    summary/              DailyStatusFormatter
  model/                  API request/response DTOs
  exception/              Domain exceptions + @ControllerAdvice
  security/               Auth filter, token redaction

src/main/resources/prompts/    Prompt templates, versioned, kept out of code
src/test/                      Mirrors main; fixtures under src/test/resources/fixtures/
```

The rule that matters: **`domain/` depends on nothing in `integration/`**. Vendor models stop at the
mapper boundary.

## 5. Domain model

Normalized internal models — not copies of the Jira/GitHub wire formats.

| Model | Key fields |
|---|---|
| `JiraIssue` | id, key, summary, description, status, assignee, priority, labels, comments, acceptanceCriteria, linkedIssues, updatedAt |
| `GitHubRepository` | owner, name, defaultBranch, url |
| `GitHubBranch` | name, repository, headSha, lastCommitAt |
| `GitHubPullRequest` | number, title, body, state, draft, author, reviewers, createdAt, updatedAt, mergedAt, url |
| `GitHubCommit` | sha, message, author, committedAt, url |
| `GitHubFileChange` | path, changeType, additions, deletions, patch (optional, redactable) |
| `PullRequestReview` | reviewer, state, submittedAt, comments |
| `EngineeringActivity` | user, ticketKey, date, repository, pullRequest, commits, summary |
| `DailyWorkSummary` | ticketKey, summary, completed[], inProgress[], remaining[], blockers[], nextSteps[], confidence, contextGaps[] |
| `EngineeringBlocker` | ticketKey, description, source, detectedAt, severity |

`acceptanceCriteria` is extracted heuristically from the Jira description/custom field; when it
cannot be found it is `null`, never guessed.

## 6. Jira ↔ GitHub matching

Ordered strategies, highest confidence first:

| # | Signal | Confidence |
|---|---|---|
| 0 | Explicit GitHub PR/commit URL in the Jira description or comments | `MATCHED` |
| 1 | Ticket key in branch name (`feature/ENG-267-...`) | `MATCHED` |
| 2 | Ticket key in PR title | `MATCHED` |
| 3 | Ticket key in PR body | `MATCHED` |
| 4 | Ticket key in commit messages | `MATCHED` |
| 5 | Jira development-panel / GitHub linked references | `MATCHED` |
| 6 | Ticket key reached through a *linked* issue rather than this one | `POSSIBLE_MATCH` |
| 7 | Search hit the key cannot be confirmed against any field | `POSSIBLE_MATCH` |
| — | none of the above | `NO_MATCH` |

Strategy 0 exists because tickets in this instance sometimes name the PR outright, and because work
on one ticket can land under another project's key — ENG-80's code sits under `PLT-3206`. Where a
ticket states its PR, that is a fact rather than an inference, and no heuristic is needed. It also
means the matcher must follow ticket keys other than the one it started from.

Two claims are easy to conflate and are kept apart: that a pull request belongs to key X, and that
key X is *this ticket's* work. Keys the issue names itself satisfy both. A key reached through a
linked issue satisfies only the first — a clone or related ticket is somebody's work, not
necessarily this one's — so it stays a possible match however unambiguous the branch name.

Finding a key and confirming one are also different operations. Discovery is strict
(`\b[A-Z][A-Z0-9]+-\d+\b`), because anything looser harvests version numbers as ticket keys.
Confirmation of an already-known key is lenient about case and separator, because GitHub rewrites
`feature/PLT-3206-monthly-email-reports` into the title "Feature/plt 3206 monthly email reports" —
the same reference in a shape strict matching misses. Both reject a longer number, so `ENG-267`
never matches `ENG-2670`. Multiple independent signals raise confidence; a single weak signal does not get
promoted. `NO_MATCH` is a valid, reportable outcome — the summary says "no GitHub activity found"
rather than inventing one.

## 6a. Target environment (confirmed)

Jira **Cloud** (`your-company.atlassian.net`), REST v3, ADF-formatted text. Active statuses
are `In Progress` and `In Review` / `Code Review`. The AI provider is undecided pending an employer
data-policy check, so `AI_ENABLED` defaults to `false` and Phases 1-3 run with no external AI at
all — see `docs/IMPLEMENTATION_PLAN.md`.

## 7. AI layer

**Input:** `EngineeringContext` — the Jira issue, its confirmed GitHub activity, an aggregate
`CodeChangeSummary` (counts, file paths, touched areas, commit subjects), a `ReviewState`, and an
explicit list of `gaps`.

Only *confirmed* matches contribute evidence; a possible match becomes a gap instead, so the model
can mention it without treating it as fact. Gaps are first-class for the same reason: a model given
a quietly incomplete picture fills the hole confidently, while one told "this ticket states no
acceptance criteria" can say that instead. Absent acceptance criteria, absent descriptions,
withheld diffs, truncated file lists, and an unreachable GitHub each produce a named gap.

**Output:** strict JSON validated against a schema before it is trusted:

```json
{
  "ticketKey": "PLT-3707",
  "summary": "...",
  "completed": [], "inProgress": [], "remaining": [],
  "blockers": [], "nextSteps": [],
  "statusConsistency": "CONSISTENT | INCONSISTENT | UNKNOWN",
  "confidence": 0.0,
  "contextGaps": []
}
```

Rules encoded in the prompt and enforced in review:

- A commit is evidence that code changed, **not** that work is complete.
- Distinguish *changed* / *completed* / *attempted* / *planned*.
- Missing information is reported as `"Unknown"` or listed in `contextGaps` — never filled in.
- No facts that are not traceable to the supplied context.

Prompts live in `src/main/resources/prompts/`, versioned, one file per task. Invalid or
unparseable model output triggers one bounded retry, then a degraded summary with
`AI_UNAVAILABLE`.

Provider-agnostic by design: `LlmClient` is an interface; `OpenAiLlmClient` is the first
implementation.

## 7a. Slack delivery

Preview and send are separate endpoints, and `send` posts the text it is handed rather than
regenerating it. Regenerating would mean the message reaching the channel is not the one that was
approved — the model is non-deterministic and the underlying tickets move between calls.

Message formatting is deterministic rather than model-generated: the model decides what is true,
while how it reads should not vary run to run. Developer-facing caveats stay in the preview
response and out of the message; a note on every ticket trains readers to ignore all of them, so
only a caveat that changes how the message should be read is published.

Slack's Web API returns `200 OK` with `{"ok": false}` for application-level failures, so every
response body is inspected rather than trusting the status line.

## 8. API surface

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/health` | Liveness + dependency status |
| GET | `/api/work/in-progress` | Normalized in-progress Jira issues |
| GET | `/api/work/context` | Assembled `EngineeringContext` (debugging / inspection) |
| POST | `/api/work/analyze` | Full pipeline → `DailyWorkSummary`, persisted |
| POST | `/api/work/summary` | Summary for a date, from stored history |
| POST | `/api/slack/preview` | Formatted message, **not** sent |
| POST | `/api/slack/send` | Post approved message to Slack |

Localhost-only by default. A static bearer token guards the API the moment it is exposed to n8n
over anything other than loopback.

## 9. Failure handling

Partial results are the norm, not the exception. Each source degrades independently:

| State | Behaviour |
|---|---|
| `JIRA_UNAVAILABLE` | Hard fail — there is nothing to report without tickets |
| `GITHUB_UNAVAILABLE` | Continue; summary notes "GitHub information unavailable" |
| `AI_UNAVAILABLE` | Continue; return deterministic non-AI summary from raw context |
| `SLACK_UNAVAILABLE` | Summary is still persisted; posting retried by n8n |
| `NO_MATCHING_REPOSITORY` | Reported explicitly per ticket |
| `NO_ACTIVITY` | "No activity recorded today" |
| `INSUFFICIENT_CONTEXT` | Summary emitted with low confidence + populated `contextGaps` |

## 10. Observability

Every run carries a correlation ID (`engineering-analysis-2026-08-24-001`) propagated through MDC
into every log line and returned in API responses. Structured JSON logging. Logged stages: Jira
retrieval, GitHub matching (with confidence), AI call (token counts and latency, not content),
persistence, Slack delivery.

Never logged: API tokens, full diffs, source-code content. Instrumentation is kept
OpenTelemetry-shaped so Datadog/OTel can be attached later without refactoring.

## 11. Deliberate non-goals for the MVP

Kubernetes, Kafka, Redis, vector databases, MCP servers, multi-tenancy, a UI, autonomous Slack
posting without approval, and LLM tool-calling. Each of these is reconsidered only when a concrete
requirement appears.
