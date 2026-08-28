# ADR 0001 — Split orchestration (n8n) from domain logic (Spring Boot)

**Status:** Accepted — 2026-08-24

## Context

The workflow is a scheduled sequence of API calls with a human approval gate. n8n could express the
whole thing on its own; so could a Spring `@Scheduled` job.

## Decision

n8n owns scheduling, retries, the approval gate, and failure notification. Spring Boot owns every
piece of domain logic: normalization, matching, context assembly, AI orchestration, persistence.

## Consequences

The interesting logic — ticket↔PR matching, "is a commit the same as completed work" — lives in
tested, version-controlled, refactorable code. Workflow changes (time of day, notification target)
happen in n8n without a deploy.

The cost is two systems to run locally instead of one, and a network hop between them. Accepted:
logic buried in workflow-tool nodes is untestable and unreviewable, which is exactly where this
system's correctness risk sits.

Reconsider if n8n starts accumulating conditional nodes — that is the signal that logic has leaked
across the boundary.
