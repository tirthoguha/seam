# Operating summary — Seam agent system

> Current architecture of the repository-local agent system. Last updated: 2026-07-01.

## Current architecture

- **One supervisor:** the contract in [`../AGENTS.md`](../AGENTS.md), backed by this `.repoagent/`
  pack. Runs the loop: understand → classify → summarize → plan → scaffold/improve → verify →
  record → handoff → next.
- **No specialized subagents.** None justified yet (single domain, single operator, sequential
  work). See the agent registry below.
- **Skills** are the unit of reuse.

## Current milestone

**Milestone 1 — durable operating pack made real.** Status: **done (2026-07-01).**
The `.repoagent/` pack referenced throughout `AGENTS.md`/`CLAUDE.md` existed only as references;
the directory was empty. All eight files now exist, grounded in verified evidence.

Next milestone candidates live in [`tasks.md`](tasks.md).

## Current guardrails

1. **Provider-seam containment** — `grep -rl 'import com.openai' src/main/java` must return only
   `provider/openai/*` and `config/OpenAiConfig.java`.
2. **Green default gate** — `mvn test` (offline `*Test`) must stay green before any "done".
3. **Fail-fast config** — `LlmProperties` is `@Validated`; boot must fail on bad `app.llm.*`.
4. **SSE framing** — native `{"t":token}` + `done` event; `/v1` `chat.completion.chunk` + `[DONE]`.
5. **Docs-sync** — API/backend-table changes touch `CLAUDE.md`, `README.md`, `web/index.html`
   together.
6. **Offline-first** — default backend `docker`; no network/key needed for test or default boot.

Full detail: [`knowledge.md`](knowledge.md). Why these exist: [`decisions.md`](decisions.md) and
[`failures.md`](failures.md).

## Skill registry

| Skill | Purpose | Trigger | Verification |
|-------|---------|---------|--------------|
| [`add-backend`](../.claude/skills/add-backend/SKILL.md) | Add/adjust an LLM backend behind the seam (new `/v1` endpoint, runtime, model, or native backend) | User wants another backend/model/runtime | `mvn test` green + invariant grep + docs-sync |

Improvement candidates (not yet built): [`tasks.md`](tasks.md) → *improve*.

## Agent registry

| Agent | Purpose | Why it exists | Boundaries |
|-------|---------|---------------|------------|
| **repo supervisor** (`AGENTS.md`) | Operate, extend, verify, and record all work in the repo | Default single-supervisor shape; work is single-domain and sequential | Whole repo; must preserve invariants and keep this pack current |

No specialized agents. Add one only with written justification per `AGENTS.md` (isolated context,
author/verifier separation, repeated work, parallelism, or a distinct domain).

## Current constraints

Java 17 / Spring Boot 3.3.5 / openai-java 4.41.0. Offline-capable. Personal POC — optimize for
correctness, seam integrity, and doc truth over feature breadth.
