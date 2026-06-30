# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **Operating contract:** Read [`AGENTS.md`](AGENTS.md) first — it is the repository supervisor
> contract (operating loop, architectural invariants, verification story, registries), backed by the
> durable operating pack in [`.repoagent/`](.repoagent/) (profile, status, tasks, knowledge,
> decisions, failures, handoff). This CLAUDE.md covers *what the code is and how to build/run it*;
> `AGENTS.md` covers *how to operate, change, and verify it durably*.

## What this is

A personal proof-of-concept (not a maintained product): one Spring Boot app that chats with **OpenAI cloud** and a **local model (Docker Model Runner)** through a single code path. Both backends speak the OpenAI-compatible `/v1` API, so one adapter built on the official `com.openai:openai-java` SDK serves either — only base URL, API key, and model id differ. All backends are configured at startup and selected per-request; no restart to switch.

## Commands

```bash
mvn spring-boot:run            # run on http://localhost:8080 (default backend = docker, works offline)
mvn test                       # unit/web tests — offline, fast (excludes *IT)
mvn -Dtest=ChatServiceTest test                  # single test class
mvn -Dtest=ChatServiceTest#methodName test       # single test method

# Live connectivity check (NOT run by `mvn test`; self-skips unreachable backends):
mvn test -Dtest=BackendConnectivityIT -DfailIfNoTests=false
OPENAI_API_KEY=sk-... mvn test -Dtest=BackendConnectivityIT -DfailIfNoTests=false   # + cloud

# Make OpenAI cloud the default backend (every backend stays callable regardless):
OPENAI_API_KEY=sk-... mvn spring-boot:run -Dspring-boot.run.arguments="--app.llm.default-backend=openai"
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=docker"   # docker profile flips default

# Container path (no local Maven/JDK needed — Dockerfile builds the jar):
docker compose up --build -d   # add --build again only when code changes

# Open WebUI on top of the OpenAI-compatible gateway (app + Open WebUI, one compose):
docker compose -f compose.openwebui.yaml up --build -d   # UI at http://localhost:3000
```

Naming convention: `*Test` = unit/web (run by default); `*IT` = live integration (excluded from `mvn test`, run explicitly).

## Architecture

The core idea is one provider seam that all backends pass through. Request → response flow:

`ChatController` → `ChatService` → `ChatProviderRegistry.get(backend)` → `OpenAiChatProvider` → OpenAI SDK.

- **`ChatProvider`** (`provider/`) is the single abstraction. Requests and responses use provider-agnostic records — `ChatPrompt` / `ChatResult` — so the OpenAI SDK never leaks past the provider boundary. **`com.openai.*` imports are confined to two places: `OpenAiChatProvider` (the adapter) and `config/OpenAiConfig` (the composition root that builds one `OpenAIClient` per backend).** Service, controllers, DTOs, and web must never import the SDK. (Verify: `grep -rl 'import com.openai' src/main/java` → only those two paths.) The provider wraps SDK failures in a stable `ChatProviderException`.
- **`ChatProviderRegistry`** holds one provider instance per configured backend, keyed by name, for the app's lifetime. This is what makes "all backends available at once" real — `get(name)` selects per call. `OpenAiConfig` builds the registry: one `OpenAIClient` per backend (each with its own base-url/key), one `OpenAiChatProvider` wrapping it.
- **`ChatService`** owns no provider-specific code. It resolves effective backend (request override → else configured default) and effective model (request override → else that backend's default model), delegates to the registry, and adapts the provider's token stream onto SSE.
- **`ChatController`** exposes `POST /chat` (blocking) and `GET /chat/stream` (SSE). Validation failures and provider errors become RFC 7807 `ProblemDetail` responses via `GlobalExceptionHandler` (400 = validation / unknown backend, 502 = upstream provider error, 500 = other).
- **`OpenAiCompatController`** exposes an OpenAI-compatible gateway — `GET /v1/models`, `POST /v1/chat/completions` (blocking JSON or SSE `chat.completion.chunk` + `[DONE]`), and `POST /v1/embeddings` — so OpenAI clients like Open WebUI can drive both chat and RAG/document embeddings. It reuses `ChatService` / `EmbeddingService` (so per-backend routing is preserved) and reads the OpenAI `model` field as `<backend>:<model>`. `ChatPrompt` carries a full message list (system/user/assistant) for this multi-turn path; the single-message constructor keeps `/chat` terse.
- **Embedding seam (mirrors chat).** `EmbeddingProvider` / `EmbeddingPrompt` / `EmbeddingResult` are the embedding-side analogue of `ChatProvider`; `OpenAiEmbeddingProvider` wraps the same per-backend `OpenAIClient`. `EmbeddingProviderRegistry` holds a provider only for backends with an `embedding-model` configured (not every backend can embed), and `EmbeddingService` resolves backend + embedding model. `<backend>:<model>` is the entire local-vs-cloud switch — `docker:<embed-model>` runs on Model Runner, `openai:text-embedding-3-small` on cloud. Caveat: embedding choice is *sticky* — switching the embedding backend changes vector dimensions and forces an Open WebUI re-index.
- **Chat wire protocol per backend.** A backend's `api` is `chat` (Chat Completions, default) or `responses` (OpenAI **Responses API**, for newer models like `gpt-5.5`). `OpenAiConfig` registers an `OpenAiChatProvider` or an `OpenAiResponsesProvider` accordingly; both implement `ChatProvider`, so `ChatService`/controllers are unaware which protocol a backend speaks. Enable with `--app.llm.backends.openai.api=responses` (env `OPENAI_API=responses`) + a Responses-capable model.
- **`LlmProperties`** binds `app.llm.*` as an immutable, `@Validated` record so the app **fails fast at startup** (missing default, empty backend map, or blank field stops boot rather than erroring on first request). Enabled via `@EnableConfigurationProperties` on `OmniLlmApplication`. Defaults come from `application.yml` `${ENV:default}` placeholders.

**Adding a non-`/v1` (native) backend:** implement `ChatProvider` and register the instance in `OpenAiConfig`/the registry. Nothing else changes — controller, service, and DTOs stay untouched.

## Backend configuration

Defined in `application.yml` under `app.llm.backends.<name>`; the `docker` Spring profile (`application-docker.yml`) only flips `default-backend`. Two backends ship by default:

| backend  | base URL                            | chat model    | embedding model        | chat env overrides                                  |
|----------|-------------------------------------|---------------|------------------------|-----------------------------------------------------|
| `docker` | `http://localhost:12434/engines/v1` | `ai/gemma3`   | `ai/mxbai-embed-large` | `DMR_BASE_URL`, `DMR_MODEL`, `DMR_EMBED_MODEL`       |
| `openai` | `https://api.openai.com/v1`         | `gpt-4o-mini` | `text-embedding-3-small` | `OPENAI_BASE_URL`, `OPENAI_API_KEY`, `OPENAI_MODEL`, `OPENAI_EMBED_MODEL`, `OPENAI_API` |

Default backend: `DEFAULT_BACKEND` env or `--app.llm.default-backend`. The SDK requires a non-blank API key even for local runtimes (`docker` backend uses the placeholder `docker`, which Model Runner ignores). `embedding-model` is optional per backend (a backend without one can't serve `/v1/embeddings`); for local embeddings, `docker model pull ai/mxbai-embed-large` first (override `DMR_EMBED_MODEL` if your runtime uses a different id). Set `OPENAI_API=responses` (+ a Responses-capable `OPENAI_MODEL`, e.g. `gpt-5.5`) to drive the `openai` backend through the Responses API.

## Implementation notes

- **SSE token framing:** each token is sent as a JSON object `{"t": token}`, not raw text — this survives SSE's "strip one leading space" rule that would otherwise drop whitespace. Streams end with a named `done` event (so the client closes instead of EventSource auto-reconnecting and re-running the request) or a named `error` event. Clients use plain `EventSource` + `JSON.parse(e.data)`.
- **Stream threading:** SSE streams run on the shared `ThreadPoolTaskExecutor` bean from `AsyncConfig` (qualifier `AsyncConfig.STREAM_EXECUTOR`), which is bounded and drained on shutdown — do not spawn per-request executors.
- **CORS** (`CorsConfig`) is intentionally narrow: only `/chat/**`, only local origins + `null` (for `file://` demo pages). It exists for the local demo UI, not as a general allow policy.
- **Web UI:** `web/index.html` is a standalone single-file chat client (Alpine.js + marked, CDN) kept deliberately out of the backend (not in `resources/static`); it talks to the API cross-origin, which is why `CorsConfig` exists.
