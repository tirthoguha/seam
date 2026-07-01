# Repository profile — Seam

> Durable classification of what this repository is. Re-read this, don't rely on chat memory.
> Backs the one-liner in [`../AGENTS.md`](../AGENTS.md). Last verified: 2026-07-01.

## What it is (one line)

A personal **proof-of-concept Spring Boot service** that chats with multiple LLM backends
(OpenAI cloud + local Docker Model Runner) through **one provider seam**, and re-exposes them
behind an **OpenAI-compatible `/v1` gateway** (chat + embeddings + native tool calling).

## Classification

| Mode | Confidence | Evidence |
|------|-----------|----------|
| Software product — service/API | **High** | Spring Boot app, `pom.xml`, controllers exposing HTTP endpoints, 50 passing tests |
| Proof-of-concept (not maintained product) | **High** | Stated verbatim in `CLAUDE.md` ("A personal proof-of-concept, not a maintained product"); version `0.0.1-SNAPSHOT` |
| Agent-bootstrap repository | **Medium** | `AGENTS.md` + `docs/agent-guidelines/repository-resident-architect.md` establish a repo-resident architect contract; this pack is its substrate |
| Library/SDK | **No** | Not published/consumed as a dependency; it *uses* `com.openai:openai-java` |

## Stack & toolchain (verified)

- **Language/runtime:** Java 17
- **Framework:** Spring Boot 3.3.5 (`spring-boot-starter-web`, `-validation`, `-actuator`, `-test`)
- **Key dependency:** `com.openai:openai-java` 4.41.0 (the single SDK behind every backend)
- **Build:** Maven (`mvn spring-boot:run`, `mvn test`); Docker (`Dockerfile` builds the jar)
- **Deploy:** `compose.yaml` (app), `compose.openwebui.yaml` (app + Open WebUI on `:3000`)
- **Remote:** `github.com/tirthoguha/seam.git`
- **Group/artifact:** `com.tirthoguha.seam` / `seam` `0.0.1-SNAPSHOT`

## Architecture (the defining idea)

One **provider seam** all backends pass through. Two seams, mirrored:
- Chat: `ChatController` / `OpenAiCompatController` → `ChatService` → `ChatProviderRegistry.get(backend)`
  → `OpenAiChatProvider` *or* `OpenAiResponsesProvider` → openai-java SDK.
- Embeddings: `OpenAiCompatController` → `EmbeddingService` → `EmbeddingProviderRegistry.get(backend)`
  → `OpenAiEmbeddingProvider` → SDK.

Both cloud and local speak the OpenAI-compatible `/v1` API, so one adapter serves either — only
base URL, API key, and model id differ. All backends configured at startup, selected per-request
via `<backend>:<model>`; no restart to switch. Full invariants: [`knowledge.md`](knowledge.md).

## Personas

- **Owner/operator (Tirtho):** runs it locally, points it at new OpenAI-compatible runtimes,
  drives it from Open WebUI. Primary and effectively only user.
- **Any future agent** entering the repo: must operate via this pack + `AGENTS.md`.

## Recurring work patterns

1. **Add / adjust a backend** — the defining recurring task. Skill exists:
   [`add-backend`](../.claude/skills/add-backend/SKILL.md).
2. **Extend the tested-model support matrix** — download → test tool-calling → delete workflow
   (matrix in [`knowledge.md`](knowledge.md)).
3. **Grow the provider seam** — new provider-agnostic records (tools, sampling, multimodal have
   all landed this way) without leaking the SDK past the boundary.
4. **Keep docs in sync** — `CLAUDE.md` / `README.md` / `web/index.html` must move with the API
   surface (doc drift already happened — see [`failures.md`](failures.md)).

## Key constraints

- **SDK containment** — `com.openai.*` only in `provider/openai/` + `config/OpenAiConfig.java`.
- **Offline-first** — `docker` is the default backend; `mvn test` and default boot must work with
  no network and no API key.
- **Fail-fast config** — bad `app.llm.*` stops boot, never errors on first request.

## Key risks

- **Doc drift** — three docs describe the same surface; easy to update one and forget the others.
- **Model-dependent tool calling** — whether a model returns `tool_calls` is a model+runtime
  property, not Seam's; support matrix must stay honest.
- **No live gate in CI** — `BackendConnectivityIT` self-skips; correctness against real backends
  is only ever verified manually.
- **Single-author POC** — no external review; guardrails must live in files, not in a reviewer.

## Recommended agent operating shape

One **repository supervisor** (the `AGENTS.md` contract) + **skills**. No specialized subagents
justified yet — the work is sequential, single-domain, and single-operator. Revisit only if a
distinct verification-heavy or parallelizable workstream emerges.

## Recommended first milestone

**Make the durable operating pack real and true** (this bootstrap) so `AGENTS.md`'s promises are
backed by files. Achieved 2026-07-01. Next milestone: see [`tasks.md`](tasks.md).
