# n8n workflow — daily status

n8n schedules the run and holds the approval gate. It contains no business logic: every decision
about what the status *says* is made in the Spring Boot service, where it can be tested.

## The workflow

```
Weekday morning (cron 0 7 * * 1-5)
        ↓
POST /api/slack/preview          ── builds the message, sends nothing
        ↓
Any active tickets?              ── a day with no tickets ends here silently
        ↓
Slack: ask me to approve         ── posts the draft to your DM, waits
        ↓
Approved?                        ── declining ends the run
        ↓
POST /api/slack/send             ── posts the text that was approved
```

The error branch on the preview step notifies you rather than failing silently, which is how a
scheduled job usually dies unnoticed.

`Post to Slack` deliberately sends `$('Build preview').first().json.message` — the exact text the
approval step showed you. It never rebuilds the message: the model is non-deterministic and the
tickets keep moving, so a rebuilt message is not the one you approved.

It has to be `.first()` rather than `.item`. `Ask me to approve` suspends the execution and resumes
it from a webhook, and the resumed item carries no pairing back to `Build preview`, so `.item`
silently resolves to nothing and the service rejects the empty message with a 400.

## Setup

### 1. Expose the service to the container

n8n runs in Docker and reaches the host through `host.docker.internal`. The service binds to
loopback by default, so it must be told to listen more widely — and it refuses to do that without
a token:

```bash
SERVER_ADDRESS=0.0.0.0
API_AUTH_TOKEN=<openssl rand -hex 32>
```

Anything that can reach this API can read your company's tickets and post to Slack as you. The
startup check exists to make sure that never happens by accident.

Both live in `.env`, alongside `SLACK_USER_ID`. `docker-compose.yml` passes `API_AUTH_TOKEN` into
the container as `AGENT_API_TOKEN`, so the two sides cannot drift apart — there is no second copy
to update.

```bash
set -a && source .env && set +a
./gradlew bootRun
```

### 2. Start n8n

```bash
docker compose up -d n8n
open http://localhost:5678
```

Compose interpolates the whole file even when you name one service, so an empty
`DATABASE_PASSWORD` fails this command with an error about `postgres`.

n8n asks for an owner account on first load. It is local to your machine; the credentials only
protect the workflow editor.

### 3. Import the workflow

**Workflows → Import from File** → `n8n/daily-status-workflow.json`.

### 4. Add a Slack credential

The two Slack nodes — `Ask me to approve` and `Tell me it failed` — need a credential inside n8n.
This is separate from the bot token the service uses: n8n messages *you*, the service posts the
status. The same bot token works for both.

Open `Ask me to approve` → **Credential to connect with** → **Create new** → paste the bot token.
`Tell me it failed` then reuses it.

### 5. Verify before scheduling

Run it with **Test workflow** and watch each node:

| Node | What a green run proves |
|---|---|
| `Build preview` | The token matched and the service reached Jira and GitHub |
| `Any active tickets?` | A day with no tickets stops here — this is correct, not a failure |
| `Ask me to approve` | Slack DM arrived with two buttons |
| `Post to Slack` | Only runs after you click **Send it** |

Click **Skip today** on the first run and confirm nothing is posted. Only then activate the cron.

If `Build preview` returns 401, `API_AUTH_TOKEN` was empty when the container started: fill it in
`.env` and `docker compose up -d n8n` to recreate. If it cannot connect at all, the service is
bound to loopback — check the startup log line naming the bind address.

`access to env vars denied` on any node means `N8N_BLOCK_ENV_ACCESS_IN_NODE` is not `"false"`.
n8n's documentation gives `false` as the default, but 2.x denies access unless it is set
explicitly, so `docker-compose.yml` sets it.

## Timing

A local model takes several minutes per ticket on a CPU — a 7B model 10-15, a 3B model around 5 —
so a four-ticket day can run for an hour. The preview step therefore uses a 90-minute timeout, and
the cron is set early enough that the draft is waiting when you start work. Latency is cheap here
precisely because nobody is watching the run.

If that feels slow, `AI_ENABLED=false` returns a deterministic summary in seconds — factual, and
duller.

## What belongs where

Put in n8n: the schedule, retries, the approval gate, failure notification.

Put in the service: anything that decides what the status says. If a rule needs a test, it does not
belong in a workflow node — see [ADR 0001](adr/0001-orchestration-split-n8n-and-spring-boot.md).
