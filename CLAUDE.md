# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
```

Naming convention: `*Test` = unit/web (run by default); `*IT` = live integration (excluded from `mvn test`, run explicitly).

## Architecture

The core idea is one provider seam that all backends pass through. Request → response flow:

`ChatController` → `ChatService` → `ChatProviderRegistry.get(backend)` → `OpenAiChatProvider` → OpenAI SDK.

- **`ChatProvider`** (`provider/`) is the single abstraction. Requests and responses use provider-agnostic records — `ChatPrompt` / `ChatResult` — so the OpenAI SDK never leaks past the provider boundary. **`OpenAiChatProvider` is the only class that imports `com.openai.*`**; it wraps SDK failures in a stable `ChatProviderException`.
- **`ChatProviderRegistry`** holds one provider instance per configured backend, keyed by name, for the app's lifetime. This is what makes "all backends available at once" real — `get(name)` selects per call. `OpenAiConfig` builds the registry: one `OpenAIClient` per backend (each with its own base-url/key), one `OpenAiChatProvider` wrapping it.
- **`ChatService`** owns no provider-specific code. It resolves effective backend (request override → else configured default) and effective model (request override → else that backend's default model), delegates to the registry, and adapts the provider's token stream onto SSE.
- **`ChatController`** exposes `POST /chat` (blocking) and `GET /chat/stream` (SSE). Validation failures and provider errors become RFC 7807 `ProblemDetail` responses via `GlobalExceptionHandler` (400 = validation / unknown backend, 502 = upstream provider error, 500 = other).
- **`LlmProperties`** binds `app.llm.*` as an immutable, `@Validated` record so the app **fails fast at startup** (missing default, empty backend map, or blank field stops boot rather than erroring on first request). Enabled via `@EnableConfigurationProperties` on `OmniLlmApplication`. Defaults come from `application.yml` `${ENV:default}` placeholders.

**Adding a non-`/v1` (native) backend:** implement `ChatProvider` and register the instance in `OpenAiConfig`/the registry. Nothing else changes — controller, service, and DTOs stay untouched.

## Backend configuration

Defined in `application.yml` under `app.llm.backends.<name>`; the `docker` Spring profile (`application-docker.yml`) only flips `default-backend`. Two backends ship by default:

| backend  | base URL                            | default model  | env overrides                                  |
|----------|-------------------------------------|----------------|------------------------------------------------|
| `docker` | `http://localhost:12434/engines/v1` | `ai/gemma3`     | `DMR_BASE_URL`, `DMR_MODEL`                     |
| `openai` | `https://api.openai.com/v1`         | `gpt-4o-mini`   | `OPENAI_BASE_URL`, `OPENAI_API_KEY`, `OPENAI_MODEL` |

Default backend: `DEFAULT_BACKEND` env or `--app.llm.default-backend`. The SDK requires a non-blank API key even for local runtimes (`docker` backend uses the placeholder `docker`, which Model Runner ignores).

## Implementation notes

- **SSE token framing:** each token is sent as a JSON object `{"t": token}`, not raw text — this survives SSE's "strip one leading space" rule that would otherwise drop whitespace. Streams end with a named `done` event (so the client closes instead of EventSource auto-reconnecting and re-running the request) or a named `error` event. Clients use plain `EventSource` + `JSON.parse(e.data)`.
- **Stream threading:** SSE streams run on the shared `ThreadPoolTaskExecutor` bean from `AsyncConfig` (qualifier `AsyncConfig.STREAM_EXECUTOR`), which is bounded and drained on shutdown — do not spawn per-request executors.
- **CORS** (`CorsConfig`) is intentionally narrow: only `/chat/**`, only local origins + `null` (for `file://` demo pages). It exists for the local demo UI, not as a general allow policy.
- **Web UI:** `web/index.html` is a standalone single-file chat client (Alpine.js + marked, CDN) kept deliberately out of the backend (not in `resources/static`); it talks to the API cross-origin, which is why `CorsConfig` exists.
