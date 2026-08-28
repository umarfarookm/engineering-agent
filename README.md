# AI Engineering Work Agent

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A personal engineering assistant that connects **Jira**, **GitHub**, **Slack**, and an **LLM** to
answer one question reliably: *what did I actually work on, and what's left?*

It reads the tickets assigned to you, finds the GitHub activity that corresponds to them, and
reasons over the combined evidence to produce a daily status — completed work, work in progress,
remaining work, blockers, and next steps.

The design goal is not eloquence, it is **not lying**. A stand-up report that confidently invents
finished work destroys trust in the whole system, so every claim is checked against the evidence
that produced it, unsupported claims are dropped, and the model is never the last word. When the
model cannot be trusted, or is switched off entirely, a deterministic summary is produced from the
evidence instead — duller, and always true.

The LLM runs locally through [Ollama](https://ollama.com) by default: no ticket text, commit
message or file path leaves your machine. See [docs/SECURITY.md](docs/SECURITY.md) for the full
data-flow, and the kill switch that disables AI processing altogether.

## Status

**Phases 0-6 complete — the MVP works end to end.** The service finds your active tickets, matches them to the pull requests
that belong to them, assembles the evidence, and reasons over it with a local model. Nothing leaves
your machine. See [ARCHITECTURE.md](ARCHITECTURE.md) and
[docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md).

## Shape of the system

```
n8n (schedule + orchestration)
      │
      ▼
Spring Boot agent service ──► Jira API
      │                   ──► GitHub API
      │                   ──► Slack API
      ▼
    LLM  ──► DailyWorkSummary (structured JSON)
      │
      ▼
 PostgreSQL (engineering history / memory)
```

n8n schedules and orchestrates. Spring Boot owns all domain logic, normalization, AI orchestration
and persistence. The LLM reasons over context the backend has already gathered — it does not go
fetch things on its own in the MVP.

## Roadmap

| Phase | Deliverable |
|-------|-------------|
| 0 | Architecture, plan, repo scaffolding ✅ |
| 1 | Jira client → `GET /api/work/in-progress` ✅ |
| 2 | GitHub matching with confidence scoring ✅ |
| 3 | `EngineeringContext` (Jira + GitHub combined) ✅ |
| 4 | LLM reasoning → validated `DailyWorkSummary` ✅ |
| 5 | Slack preview + approval + send ✅ |
| 6 | n8n scheduled workflow ✅ |
| 7 | Agentic layer (only once the MVP is stable) *(next)* |

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) — architecture, domain model, matching strategy, failure modes
- [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) — phase-by-phase plan with exit criteria
- [docs/INTEGRATION_PERMISSIONS.md](docs/INTEGRATION_PERMISSIONS.md) — API scopes and permissions required
- [docs/SECURITY.md](docs/SECURITY.md) — data handling, secrets, LLM data flow
- [docs/n8n-workflow.md](docs/n8n-workflow.md) — scheduling, approval gate, and setup
- [docs/adr/](docs/adr/) — architecture decision records
- [.env.example](.env.example) — required configuration

## Principles

Correctness > simplicity > maintainability > speed > sophistication.

No agent where a service will do. No Kubernetes, Kafka, Redis, or vector database until there is a
concrete requirement for one.

## Running locally

Requires a JDK (21 or later — 25 is fine; the build targets a Java 21 toolchain regardless) and
Docker. The Gradle wrapper fetches everything else.

```bash
cp .env.example .env      # fill in JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN
set -a && source .env && set +a
docker compose up -d      # PostgreSQL (not yet used by the app; persistence lands in a later phase)
./gradlew bootRun
```

Create a Jira API token at <https://id.atlassian.com/manage-profile/security/api-tokens>.

Before starting the app, check the credentials against Jira directly — this isolates a
credential problem from an application problem:

```bash
./scripts/check-jira.sh              # verify auth + list your active issues
./scripts/check-jira.sh ENG-267      # inspect one issue, and see where its acceptance criteria live
./scripts/check-jira.sh --fields     # list field ids, to find a custom acceptance-criteria field
```

```bash
curl localhost:8080/api/health
curl localhost:8080/api/work/in-progress
curl localhost:8080/api/work/issue/ENG-267
curl "localhost:8080/api/work/activity/ENG-80"          # matched GitHub work
curl "localhost:8080/api/work/context"                  # assembled evidence, all active tickets
curl "localhost:8080/api/work/context/ENG-80"

curl -X POST "localhost:8080/api/work/summary/ENG-185"  # reasoned summary, one ticket
curl -X POST "localhost:8080/api/work/analyze"          # every active ticket
```

### Slack

Posting is two steps on purpose. `preview` builds the message and sends nothing; `send` posts the
text it is given, so what reaches the channel is exactly what you read and approved.

```bash
curl -s -X POST localhost:8080/api/slack/preview | python3 -c "import json,sys;print(json.load(sys.stdin)['message'])"

curl -X POST localhost:8080/api/slack/send \
  -H 'Content-Type: application/json' \
  -d "$(python3 -c 'import json;print(json.dumps({"message": open("/tmp/status.txt").read()}))')"
```

Set `SLACK_USER_ID` to direct-message yourself rather than a channel — the safer place to start.
The bot needs `chat:write`, plus `im:write` for direct messages, and must be invited to any channel
it posts to.

### Scheduling

`docker compose up -d n8n`, then import `n8n/daily-status-workflow.json`. Full setup in
[docs/n8n-workflow.md](docs/n8n-workflow.md).

Exposing the API to the n8n container requires a token — the service refuses to start bound beyond
loopback without `API_AUTH_TOKEN`:

```bash
export SERVER_ADDRESS=0.0.0.0
export API_AUTH_TOKEN="$(openssl rand -hex 32)"
```

### AI

Summaries use a **local** model via [Ollama](https://ollama.com), so ticket text and code never
leave the machine.

```bash
ollama serve &
ollama pull llama3.2          # or a larger model; see below
export AI_ENABLED=true
```

Model choice matters more than usual here. A 3B model produces usable but flawed output — in
testing it described a merged pull request as still open. The validator catches and annotates such
contradictions, but a larger model avoids them. If you have the disk and patience, `qwen2.5:7b` or
`llama3.1:8b` are markedly better at this task. Expect several minutes per ticket on CPU either way.

Set `AI_ENABLED=false` to skip the model entirely and get a deterministic summary built from the
evidence.

GitHub needs `GITHUB_TOKEN` and `GITHUB_ORG`. A fine-grained token with read-only Contents,
Metadata and Pull requests is enough — see [docs/INTEGRATION_PERMISSIONS.md](docs/INTEGRATION_PERMISSIONS.md).

Run the tests — they use recorded fixtures and never contact a real Jira instance:

```bash
./gradlew test
```

## Licence

MIT — see [LICENSE](LICENSE).

The example data in the tests (`acme/*` repositories, `ENG-*` and `PLT-*` ticket keys, "Alex
Rivera") is fictional.
