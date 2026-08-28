# Security considerations

This system reads a company's ticket data and proprietary source code and sends a derived subset to
an external LLM provider. That single fact drives most of what follows.

## Secrets

- All credentials come from environment variables or a secrets manager. Never from source, never
  from a committed file.
- `.env.example` is committed with empty values. `.env` is git-ignored and must never be committed.
- Tokens are masked in every log, exception message, and API error response. A dedicated redacting
  log converter enforces this rather than relying on discipline at each call site.
- Token rotation must not require a rebuild — configuration only.

## Data sent to the LLM

The default provider is **Ollama running locally**, which removes this risk rather than mitigating
it: prompts never leave the machine, so there is no external processor to vet and no employer policy
to satisfy.

The provider is a configuration value, so this is a decision you can reverse in either direction:

| `AI_PROVIDER` | Where the prompt goes | API key |
|---|---|---|
| `ollama` (default) | this machine | none |
| `anthropic` | Anthropic's API | `AI_API_KEY` |
| `openai` | OpenAI, or any OpenAI-compatible endpoint at `AI_BASE_URL` | `AI_API_KEY` |

**Switching to a hosted provider sends your employer's data to a third party.** The prompt contains
ticket keys, titles, descriptions and comments, branch names, pull request titles, and changed file
paths — and, if `AI_INCLUDE_DIFFS=true`, source code. Confirm your employer permits this before you
switch. The service does not ask, but it does say so: startup logs a warning naming the provider and
endpoint whenever reasoning happens off-machine, and refuses to start a hosted provider with no API
key rather than silently falling back to something else.

`LlmClient.isLocal` is the flag that carries this distinction through the code. It is false for both
hosted providers, and false for an OpenAI-compatible endpoint even when that endpoint is in fact on
localhost: for a URL the operator supplies, the safe assumption is that data leaves.

The controls below apply to every provider, and matter most for the hosted ones:

1. **Explicit and auditable.** The exact payload sent to the provider is assembled in one place
   (`EngineeringReasoningService`) and can be dumped for inspection in a local debug mode.
2. **Minimized.** Full diffs are not sent by default. The context carries file paths, change types,
   and additions/deletions counts; patch content is included only when `ai.include-diffs=true`, and
   even then it is truncated per file and in aggregate.
3. **Path-based exclusion.** Configurable deny-list for paths that must never leave the process —
   `.env*`, key/certificate material, credentials fixtures, and any repository or directory the user
   marks sensitive.
4. **Kill switch.** `ai.enabled=false` disables all external AI processing; the system falls back to
   a deterministic non-AI summary built from Jira and GitHub metadata alone.
5. **No training.** Use a provider tier with training-on-data disabled, and confirm your employer
   permits sending this data at all before Phase 4.

## Logging

Never logged: API tokens, Authorization headers, full diffs, source-code content, full Jira
descriptions in production.

Logged: correlation ID, ticket keys, repository and PR numbers, match confidence, timings, token
counts, error classes.

Log level for third-party HTTP clients stays at `INFO` or above in production — debug-level clients
dump full request bodies including credentials.

## API exposure

The service binds to loopback by default. Anything that can reach this API can read the company's
tickets and post to Slack as the user, so exposure and authentication are coupled at startup: if
`server.address` is anything other than a loopback address and `API_AUTH_TOKEN` is unset, the
application refuses to start rather than serving an open endpoint.

The token is compared in constant time, and a rejected credential is never echoed back or logged.
Health is the only unauthenticated route — a container needs to probe it without a secret — and it
reports whether dependencies are configured, never their values.

## Input validation

Jira and GitHub responses are untrusted input: absent fields, unexpected types, and hostile content
are all possible. Every response is validated and mapped defensively, and ticket text is never
interpolated into a prompt in a way that lets it act as instructions — prompt-injection via a Jira
comment or PR description is a real path into this system. The LLM's output is likewise validated
against a schema before anything acts on it.

## Persistence

PostgreSQL stores ticket summaries and engineering history — company data at rest on a personal
machine. Use a non-default password, keep the port bound to localhost, and be deliberate about
retention: a configurable purge horizon for stored diffs and summaries.

## Slack

Approval mode is the default and stays the default until the pipeline has proven itself. An
automated agent that posts to a team channel unsupervised will eventually post something wrong in
front of colleagues; the human approval step is the control that prevents it.
