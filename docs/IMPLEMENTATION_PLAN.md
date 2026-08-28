# Implementation plan

Each phase has a demonstrable deliverable and an exit criterion. A phase is not "done" until you can
run the command in *How to verify* and see the stated result. No phase starts before the previous
one is verified.

---

## Phase 0 — Architecture *(this phase)*

Repository scaffolding, architecture, plan, ADRs, `.env.example`. No application code.

**Exit criterion:** architecture approved.

---

## Phase 1 — Jira

Build the Gradle project (Kotlin + Spring Boot), the Jira client, and the first endpoint.

- Gradle wrapper, Spring Boot app skeleton, Docker Compose with PostgreSQL
- `JiraClient`: `getCurrentUser()`, `getInProgressIssues()`, `getIssue(key)`, `getIssueComments(key)`, `getLinkedIssues(key)`
- JQL: `assignee = currentUser() AND status = "In Progress" ORDER BY updated DESC` — the username is never hard-coded
- `JiraIssueMapper` → normalized `JiraIssue`
- WireMock tests against recorded fixtures; no live company API in tests

**Deliverable:** `GET /api/work/in-progress` returns normalized issues.

**How to verify:** `./gradlew test` green; `curl localhost:8080/api/work/in-progress` returns your real in-progress tickets.

---

## Phase 2 — GitHub

- `GitHubClient`: repo search, branches, PR search, PR detail, commits, changed files, reviews
- `GitHubActivityMatcher` implementing the ordered strategies in ARCHITECTURE.md §6
- `MatchConfidence` = `MATCHED | POSSIBLE_MATCH | NO_MATCH`, with the matched signal recorded
- Rate-limit awareness (GitHub search is far more restrictive than the core API)

**Deliverable:** ticket key → GitHub activity with confidence and evidence.

**How to verify:** unit tests cover each strategy and each negative case (`PLT-37070` must not match `PLT-3707`); a real ticket key resolves to the correct PR.

---

## Phase 3 — Engineering context

- `EngineeringContextAssembler` combining Jira + GitHub
- Diff summarization/truncation so context stays within a sane token budget
- Graceful degradation when GitHub is unavailable or nothing matches

**Deliverable:** `GET /api/work/context` returns a complete `EngineeringContext`.

**How to verify:** context for a real ticket contains the right PR, commits, and changed files, and a ticket with no PR returns `NO_MATCH` cleanly rather than throwing.

---

## Phase 4 — AI

- `LlmClient` interface + `OpenAiLlmClient`
- Prompts in `resources/prompts/`, versioned
- `EngineeringReasoningService` → `DailyWorkSummary`
- Strict JSON schema validation, one bounded retry, deterministic fallback
- Redaction/minimization of code before it leaves the process
- `ai.enabled=false` kill switch producing a non-AI summary

**Deliverable:** `POST /api/work/analyze` returns a validated `DailyWorkSummary`.

**How to verify:** tests replay canned LLM responses including malformed ones; a real run produces a summary whose claims are all traceable to the context.

---

## Phase 5 — Slack

- `SlackClient`: `sendMessage()`, `sendDirectMessage()`
- `DailyStatusFormatter` → concise, human-sounding message
- Approval mode: preview and send are separate endpoints; nothing posts automatically

**Deliverable:** `POST /api/slack/preview` then `POST /api/slack/send`.

**How to verify:** preview returns the message without posting; send posts to a private test channel.

---

## Phase 6 — n8n

- Documented + exported workflow: cron → analyze → preview → approval → send
- Retries, failure notification, configurable schedule
- No business logic in n8n

**Deliverable:** `docs/n8n-workflow.md` + exported workflow JSON.

**How to verify:** a manual trigger runs end-to-end and waits for approval before posting.

---

## Phase 7 — Agentic layer

Only after the MVP has run reliably for a sustained period. Introduce an orchestrator and
specialized agents **only** where independent reasoning, tool selection, or iterative investigation
is genuinely required — a plain API call stays a service.

---

## Decisions (confirmed 2026-08-24)

1. **Jira: Cloud.** Instance `your-company.atlassian.net`. REST API v3, HTTP Basic with
   email + API token. Descriptions and comments arrive as ADF and are flattened to plain text by the
   mapper. Ticket keys look like `ENG-267`.
2. **Active statuses: `In Progress` and `In Review` / `Code Review`.** Work whose PR is up and
   awaiting review is still reportable in stand-up. Configurable via
   `JIRA_IN_PROGRESS_STATUSES`; exact status names on the board still need confirming in Phase 1
   against `GET /rest/api/3/status`.
3. **LLM provider: undecided — employer policy pending.** Phases 1-3 involve no AI and no data
   leaving the machine, so they proceed now. Phase 4 does not start until this is settled. The
   `LlmClient` interface keeps the choice a configuration change rather than a rewrite.

## Observed from live data (2026-08-25)

Verified by running Phase 1 against the real instance. These change the Phase 2 design:

- **GitHub org is `acme`.** Evidenced by a PR link in ENG-80. Whether other orgs are in scope is
  unconfirmed.
- **Work crosses Jira projects.** ENG-80's code lives under an `MB` key
  (`PLT-3206`). Searching GitHub for `ENG-80` returns nothing while the work plainly exists, so the
  matcher cannot assume the Jira key and the branch/PR key are the same string.
- **Jira tickets sometimes link the PR directly** — ENG-80's description contains a full
  `github.com/.../pull/50` URL. This is a stated fact rather than an inference, making it the
  strongest available signal and the only one that resolves ENG-80. It becomes match strategy 0.
- **Acceptance criteria are not used on these tickets.** All four candidate custom fields are empty
  across the sampled issues, and bug tickets use an Environment/General template instead.
  `acceptanceCriteria` stays null, so Phase 4 must infer completion from the PR and comments — and
  say that it is doing so.
- **Descriptions can be absent entirely** (ENG-185), so context assembly cannot assume any text.

## Open questions remaining

1. **Cross-project keys**: how often does an ENG ticket's work land under a different project's key,
   as ENG-80 did? Determines how much weight strategy 0 needs to carry.
2. **GitHub**: is `acme` the only org in scope, and how many repos do you touch? Any repo that
   must never be sent to an LLM?
3. **Branch/PR conventions**: is `ENG-267` reliably present in branch names, PR titles, or commit
   messages? If the team is inconsistent, expect more `POSSIBLE_MATCH` and `NO_MATCH` outcomes.
4. **Slack**: post to a channel, or DM-to-self first? DM-to-self is the safer place to start.
