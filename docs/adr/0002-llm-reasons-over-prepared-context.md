# ADR 0002 — The LLM reasons over prepared context; it does not fetch

**Status:** Accepted — 2026-08-24

## Context

The obvious modern design gives the model tools and lets it investigate — search GitHub, pull the
diff, read comments — until it is satisfied.

## Decision

For the MVP the backend assembles a complete `EngineeringContext` first. The model receives it in a
single call and returns structured JSON. No tool calling, no autonomous retrieval.

## Consequences

The exact data leaving the process is known and auditable — which is what makes the security posture
in SECURITY.md enforceable at all. Runs are cheap, fast, and reproducible; failures are debuggable,
because the input is a value that can be captured and replayed in a test.

The cost is that the model cannot chase something the assembler did not think to gather. That
limitation is acceptable while the retrieval problem is well-defined (find the PR for this ticket),
and it is the trigger for Phase 7: when the context assembler needs branching, iterative
investigation to do its job, that is when an agent is justified — not before.
