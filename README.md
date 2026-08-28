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

Summaries default to a **local** model via [Ollama](https://ollama.com), so ticket text and code
never leave the machine.

```bash
ollama serve &
ollama pull qwen2.5:7b-instruct
export AI_ENABLED=true
```

The provider is configuration, not code:

| `AI_PROVIDER` | Where the prompt goes | Needs |
|---|---|---|
| `ollama` (default) | this machine | nothing |
| `anthropic` | Anthropic's API | `AI_API_KEY` |
| `openai` | OpenAI, or any OpenAI-compatible endpoint at `AI_BASE_URL` | `AI_API_KEY` |

A hosted provider sends your employer's ticket text, branch names and file paths to a third party.
Read [docs/SECURITY.md](docs/SECURITY.md) and confirm you are permitted to before switching. Startup
logs a warning naming the provider whenever reasoning happens off-machine, and refuses to start a
hosted provider with no API key rather than quietly falling back.

Model choice matters more than usual. A 3B model produces usable but flawed output — in testing it
described a merged pull request as still open, and reported the agent's own missing GitHub token as
a blocker on the developer's work. Both are handled now (the validator annotates the first, the
prompt renderer prevents the second), but a larger model needs less rescuing.

Measured on a 2018 Intel i7 with no GPU: **`qwen2.5:7b-instruct` takes ~11-12 minutes per ticket**,
`llama3.2:3b` about 5. A four-ticket day is therefore most of an hour, which is why the n8n preview
step allows 90 minutes. Latency is cheap when nobody is waiting — the cron runs at 07:00 and the
draft is there when you start work. A hosted provider returns in seconds.

Set `AI_ENABLED=false` to skip the model entirely and get a deterministic summary built from the
evidence. It is duller, always available, and never wrong about the evidence.

GitHub needs `GITHUB_TOKEN` and `GITHUB_ORG`. A fine-grained token with read-only Contents,
Metadata and Pull requests is enough — see [docs/INTEGRATION_PERMISSIONS.md](docs/INTEGRATION_PERMISSIONS.md).

Run the tests — they use recorded fixtures and never contact a real Jira instance:

```bash
./gradlew test
```

### Daily use without a scheduler

n8n earns its keep on an always-on host. On a laptop the 07:00 cron is partly fiction: it needs
Docker up, the service running, and the machine awake, and a scheduler that silently misses is
worse than none. For daily use, run the same two endpoints from a terminal instead:

```bash
./scripts/standup.sh              # preview, ask, send on y
./scripts/standup.sh --dry-run    # preview only, never sends
./scripts/standup.sh --yes        # no prompt, for a real cron
```

It needs only the Spring service — no Docker, no n8n. The approval gate is the `[y/N]` prompt, and
it sends the text it just showed you rather than regenerating it, for the same reason the workflow
does: a rebuilt message is not the one you approved.

`.env` fills in anything you have not already exported, so a one-off override works as expected:

```bash
AI_ENABLED=false ./scripts/standup.sh
```

### Restarting after a reboot

Nothing persists in the running processes, so a restart loses no state: the repository is on disk,
and n8n keeps its workflows and credentials in a Docker **volume**, which survives both a reboot and
`docker compose down`.

```bash
cd path/to/engineering-agent
open -a OrbStack                     # or Docker Desktop; wait for the daemon
set -a && source .env && set +a
docker compose up -d n8n
./gradlew bootRun
```

Two things catch people out:

**`source .env` must happen in the same shell as `bootRun`.** Exported variables do not survive
Ctrl+C into a new shell, and a stale export beats the file — a variable you cleared in `.env` keeps
its old value in a shell that sourced the earlier version. If the service behaves as though it has
configuration you have since changed, that is why. Open a new tab and source again.

**Start the Docker daemon before `docker compose`,** not merely the desktop app. Compose also
interpolates the whole file even when you name one service, so an empty `DATABASE_PASSWORD` fails
`docker compose up -d n8n` with an error about `postgres`.

Confirm all three layers, not just that the app started:

```bash
curl -s localhost:8080/api/health                       # jira/github/slack configured?
docker exec engineering-agent-n8n \
  wget -qO- "$AGENT_BASE_URL/api/health"                # can the container reach the host?
```

Ollama is only needed when `AI_ENABLED=true`; leave it stopped otherwise.

A **published** workflow needs the service and Docker running at 07:00. If the laptop is asleep, the
scheduled run fails and the workflow DMs you that it could not build the status — the error branch
working as intended, not a fault.

## Licence

MIT — see [LICENSE](LICENSE).

The example data in the tests (`acme/*` repositories, `ENG-*` and `PLT-*` ticket keys, "Alex
Rivera") is fictional.
