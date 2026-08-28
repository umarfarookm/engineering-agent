# ADR 0003 — Ticket↔code matching is confidence-scored, never assumed

**Status:** Accepted — 2026-08-24

## Context

Linking a Jira ticket to GitHub activity relies on convention: a key in a branch name, a PR title, a
commit message. Conventions are followed inconsistently. A wrong link produces a confidently wrong
stand-up report — the worst possible failure for a tool whose entire value is trustworthiness.

## Decision

Matching returns `MATCHED`, `POSSIBLE_MATCH`, or `NO_MATCH` along with the evidence that produced it.
Only explicit ticket-key references (word-boundary matched) yield `MATCHED`. Heuristics — same
author, overlapping time window, similar text — yield at most `POSSIBLE_MATCH`. `NO_MATCH` is a
normal outcome that flows through to the summary as "no GitHub activity found".

## Consequences

Some real work goes unlinked when conventions were not followed, and the report says so instead of
silently omitting or inventing it. The summary can qualify uncertain links rather than presenting
them as fact. Confidence propagates into `DailyWorkSummary.confidence`, so a low-signal day is
visibly low-signal.
