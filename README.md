# OmniLLM Spring — Spring Boot + official OpenAI Java SDK

> **Concept only.** This is a personal proof-of-concept built for demo and learning — not a
> maintained library or product. Expect rough edges; it's here to explore an idea, not to be
> depended on.

A demo/learning app exploring how to talk to OpenAI-compatible backends from Spring Boot.
It takes the leaner route for OpenAI-compatible backends: it uses the **official OpenAI Java SDK**
(`com.openai:openai-java`) and just swaps the base URL to chat with:

- **OpenAI cloud** (`api.openai.com`)
- **Docker Model Runner** (local `ai/gemma3`, `smollm2`, …)

The trick: both speak the **OpenAI-compatible `/v1` API**, so the *same* adapter works against
both — only the base URL, API key, and model id differ. **Both backends are configured at once**
and a request picks which one serves it (`"backend"` field), falling back to a configurable
default. So you can hit a local model for one call and OpenAI cloud for the next, no restart.

## Requirements

- **Java 17** — the build is pinned to 17 via a Maven toolchain, so it runs correctly even if your
  default `mvn` is on a newer JDK. This requires a matching `<jdk><version>17` entry in
  `~/.m2/toolchains.xml` pointing at a JDK 17 install (the build fails fast with a clear message if
  it's missing).
- Maven
- At least one backend reachable: Docker Model Runner enabled, or an OpenAI API key
  (the other returns errors only when actually called)

## Run

All backends are always available; the profile (or `app.llm.default-backend`) only chooses the
**default** backend for requests that don't name one.

```bash
# Default backend = Docker Model Runner (works offline, no key needed)
#    docker desktop enable model-runner --tcp 12434   # one-time; verify with `docker model status`
#    docker model pull ai/gemma3
mvn spring-boot:run

# Use OpenAI cloud as the default (needs a key)
OPENAI_API_KEY=sk-... mvn spring-boot:run -Dspring-boot.run.arguments="--app.llm.default-backend=openai"
```

Change the default backend ad hoc without a profile (every backend stays callable per-request):

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--app.llm.default-backend=docker"
```

## Endpoints

```bash
# Blocking — uses the default backend
curl -s localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Explain Docker Model Runner in one sentence."}'

# Pick the backend per request (offline now, online next call — no restart)
curl -s localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"hi","backend":"docker"}'
# -> {"backend":"docker","model":"ai/gemma3","reply":"..."}

# Override the backend's default model too
curl -s localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"hi","backend":"openai","model":"gpt-4o"}'

# Streaming (Server-Sent Events), also backend-selectable
curl -N "localhost:8080/chat/stream?message=Tell%20me%20a%20joke&backend=docker"
```

An unknown `backend` returns `400` with the list of configured backends; the response always
echoes the `backend` and `model` that actually served the request.

## How it maps to each backend

| `backend` name | base-url                              | default model   |
|----------------|---------------------------------------|-----------------|
| `openai`       | `https://api.openai.com/v1`           | `gpt-4o-mini`   |
| `docker`       | `http://localhost:12434/engines/v1`   | `ai/gemma3`     |

Both are declared under `app.llm.backends.*` in
[`application.yml`](src/main/resources/application.yml) and built **at once** —
[`OpenAiConfig`](src/main/java/com/tirthoguha/omnillm/config/OpenAiConfig.java) creates one client
per backend and registers them in a
[`ChatProviderRegistry`](src/main/java/com/tirthoguha/omnillm/provider/ChatProviderRegistry.java).
`app.llm.default-backend` (flipped by the `docker` profile) picks the fallback when a request omits
`backend`. Per-backend env overrides: `OPENAI_*`, `DMR_*` (see the yml).

### Connectivity check (offline + online)

[`BackendConnectivityIT`](src/test/java/com/tirthoguha/omnillm/BackendConnectivityIT.java) connects
to both and confirms a live reply from each — it's excluded from `mvn test` and each backend
self-skips when unavailable (DMR port down, or no `OPENAI_API_KEY`):

```bash
mvn test -Dtest=BackendConnectivityIT -DfailIfNoTests=false                  # offline backends
OPENAI_API_KEY=sk-... mvn test -Dtest=BackendConnectivityIT -DfailIfNoTests=false   # + online
```

## Running on a server

The app depends on *"some OpenAI-compatible `/v1` endpoint"*, not on any specific runtime — so
there's nothing Docker-Desktop-specific about it. On a headless Linux server you have a menu, and
the Java code never changes; only `app.llm.base-url` (and the model id) does.

**Docker Model Runner** is a great local-dev choice. On a laptop, enable it from the CLI — no GUI
hunting — with `docker desktop enable model-runner --tcp 12434` (`docker model status` to verify).
On a server with plain Docker Engine you install it as a plugin instead
(`sudo apt-get install docker-model-plugin`; verify the current steps against Docker's docs, as
DMR-on-Docker-CE is still maturing). How a deployed app reaches it depends on where the app runs:

| App location                     | base-url                                          |
|----------------------------------|---------------------------------------------------|
| On the host, alongside DMR       | `http://localhost:12434/engines/v1` (TCP enabled) |
| In a container, same Docker host | `http://model-runner.docker.internal/engines/v1`  |

The compose example below runs the app as a container talking to **Docker Model Runner** on the
same Docker host — the app reaches the engine via its gateway DNS, so there's no host networking or
extra model service to manage:

```yaml
# compose.yaml — app talking to Docker Model Runner on the same host
services:
  app:
    image: omnillm:latest            # build once: `mvn spring-boot:build-image`
    ports:
      - "8080:8080"
    environment:
      DEFAULT_BACKEND: docker
      DMR_BASE_URL: http://model-runner.docker.internal/engines/v1
      DMR_MODEL: ai/gemma3
```

```bash
docker model pull ai/gemma3          # pull into the host's Model Runner
docker compose up -d
curl -s localhost:8080/chat -H 'Content-Type: application/json' \
  -d '{"message":"hello from a server"}'
```

To use **OpenAI cloud** instead — no extra service, just flip the default and pass a key:

```yaml
    environment:
      DEFAULT_BACKEND: openai
      OPENAI_API_KEY: sk-...
```

## Architecture

The app talks to backends only through a single seam, the
[`ChatProvider`](src/main/java/com/tirthoguha/omnillm/provider/ChatProvider.java) interface.
Its provider-agnostic types —
[`ChatPrompt`](src/main/java/com/tirthoguha/omnillm/provider/ChatPrompt.java) /
[`ChatResult`](src/main/java/com/tirthoguha/omnillm/provider/ChatResult.java) — keep the OpenAI
SDK from leaking past the boundary. The only class that imports `com.openai.*` is
[`OpenAiChatProvider`](src/main/java/com/tirthoguha/omnillm/provider/openai/OpenAiChatProvider.java),
instantiated once per backend (each with its own base-url/key client) and registered by name in the
[`ChatProviderRegistry`](src/main/java/com/tirthoguha/omnillm/provider/ChatProviderRegistry.java);
it wraps SDK failures in a stable `ChatProviderException`. Adding a native (non-`/v1`) backend is
one new `ChatProvider` placed into the registry — nothing else changes.

- **[`ChatService`](src/main/java/com/tirthoguha/omnillm/service/ChatService.java)** orchestrates
  (resolve backend → resolve model → delegate to the registry's provider) and adapts the provider's
  token stream onto SSE using a managed `ThreadPoolTaskExecutor`
  ([`AsyncConfig`](src/main/java/com/tirthoguha/omnillm/config/AsyncConfig.java)).
- **[`ChatController`](src/main/java/com/tirthoguha/omnillm/controller/ChatController.java)**
  validates input (`@Valid` / `@NotBlank`); failures and provider errors become RFC 7807
  `ProblemDetail` responses via
  [`GlobalExceptionHandler`](src/main/java/com/tirthoguha/omnillm/web/GlobalExceptionHandler.java)
  (`400` for validation / unknown backend, `502` for upstream backend errors, `500` otherwise).
- **Config** is bound to an immutable, validated record
  ([`LlmProperties`](src/main/java/com/tirthoguha/omnillm/config/LlmProperties.java)) so the app
  fails fast on missing/blank settings.

## Operational endpoints

Spring Boot Actuator is enabled (`health`, `info`, `metrics`):

```bash
curl -s localhost:8080/actuator/health
curl -s localhost:8080/actuator/metrics
```
