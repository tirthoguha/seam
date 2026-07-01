# Decisions — Seam

> Why things are the way they are. Append-only; supersede rather than delete. Last updated: 2026-07-01.

## D1 — One provider seam for every backend
**Decision:** All backends (cloud + local) go through a single `ChatProvider`/`EmbeddingProvider`
abstraction built on `com.openai:openai-java`; requests/responses are provider-agnostic records.
**Why:** Both cloud and local runtimes speak the OpenAI-compatible `/v1` API, so one adapter serves
either — only base URL, key, and model id differ. Keeps the SDK out of the core and makes
"all backends available at once, selected per request" real.
**Consequence:** Invariant #1 (SDK containment). Adding a non-`/v1` backend = implement
`ChatProvider` + register in `OpenAiConfig`; nothing else changes.

## D2 — Default backend is `docker` (offline-first)
**Decision:** Default backend is local Docker Model Runner; `mvn test` and default boot require no
network and no API key.
**Why:** Personal POC that must work offline; cloud is opt-in via `OPENAI_API_KEY` /
`--app.llm.default-backend=openai`.
**Consequence:** SDK's non-blank-key requirement satisfied with placeholder key `docker`.

## D3 — Two wire protocols behind one seam (`chat` + `responses`)
**Decision:** A backend's `api` is `chat` (Chat Completions) or `responses` (Responses API);
`OpenAiConfig` picks `OpenAiChatProvider` or `OpenAiResponsesProvider`, both `ChatProvider`.
**Why:** Newer models (e.g. `gpt-5.5`) need the Responses API; the seam hides the difference so
`ChatService`/controllers stay protocol-unaware.
**Consequence:** Knobs are applied only where the protocol supports them (`stop`/`seed` Chat-only;
`reasoning_effort` Responses-only), silently ignored elsewhere.

## D4 — OpenAI-compatible `/v1` gateway
**Decision:** `OpenAiCompatController` re-exposes Seam as an OpenAI-compatible API (models, chat
completions, embeddings) reusing `ChatService`/`EmbeddingService`, reading `model` as
`<backend>:<model>`.
**Why:** Lets standard OpenAI clients (Open WebUI) drive both chat and RAG/embeddings while
preserving per-backend routing.
**Consequence:** Gateway does native tool calling, `tool_choice`, sampling params, and multimodal —
all mapped onto agnostic seam records.

## D5 — Fail-fast config via `@Validated` record
**Decision:** `app.llm.*` binds to an immutable `@Validated` `LlmProperties`.
**Why:** A misconfigured backend should stop boot, not surface as a confusing first-request error.

## D6 — Docs live in three places, kept in sync deliberately
**Decision:** `CLAUDE.md`, `README.md`, and `web/index.html` all describe the surface; changes must
touch all three together.
**Why:** Doc drift already happened (see [`failures.md`](failures.md) F1). No CI enforces this, so
it is a standing operator guardrail.

## D7 — Single supervisor, no specialized subagents (yet)
**Decision:** Operate with the `AGENTS.md` supervisor + skills; no specialized agents.
**Why:** Work is single-domain, single-operator, sequential. `add-backend` covers the one recurring
task. The guideline requires written justification before adding agents; none applies yet.
**Revisit when:** a verification-heavy or genuinely parallel workstream emerges.

## D8 — Bootstrap the durable operating pack (2026-07-01)
**Decision:** Create the full `.repoagent/` pack that `AGENTS.md`/`CLAUDE.md` already reference but
which never existed on disk.
**Why:** The supervisor contract promised durable state; the directory was empty, so continuity
depended on chat memory — a direct violation of the filesystem-first rule.
**Consequence:** All eight pack files now exist, grounded in verified evidence (50 tests green,
invariant grep clean).
