# OmniLLM Spring

> **Concept only.** A personal proof-of-concept for demo and learning — not a maintained
> library or product. Expect rough edges.

One Spring Boot app that chats with **OpenAI cloud** *and* a **local model** through the
**same code path**. The trick: both speak the **OpenAI-compatible `/v1` API**, so a single
adapter (the official `com.openai:openai-java` SDK) works against either — only the base URL,
API key, and model id change.

Both backends are configured at startup. Each request picks which one answers it (a `"backend"`
field); if it doesn't, a configurable default is used. So you can hit a local model for one call
and OpenAI cloud for the next — no restart.

| backend  | base URL                              | default model | needs            |
|----------|---------------------------------------|---------------|------------------|
| `docker` | `http://localhost:12434/engines/v1`   | `ai/gemma3`   | Docker Model Runner |
| `openai` | `https://api.openai.com/v1`           | `gpt-4o-mini` | `OPENAI_API_KEY` |

---

## Prerequisites

- **Java 17** and **Maven** to run the app directly. (For the container path below you only
  need Docker — the image builds the jar itself.)

- **At least one backend** to talk to:
  - **Docker Model Runner** (local, free, offline) — enable it once and pull a model:
    ```bash
    docker desktop enable model-runner --tcp 12434   # exposes it on localhost:12434
    docker model status                              # verify it's up
    docker model pull ai/gemma3                       # pull the default model
    ```
  - **and/or OpenAI cloud** — set `OPENAI_API_KEY` (only needed when you actually call it).

---

## Quick start

**1. Start the app** (one terminal). Local model is the default backend, so this works offline:

```bash
mvn spring-boot:run
```

It listens on **http://localhost:8080**. Leave it running.

**2. Send a request** (another terminal):

```bash
curl -s localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Explain Docker Model Runner in one sentence."}'
```

Response echoes which backend and model actually served it:

```json
{"backend":"docker","model":"ai/gemma3","reply":"..."}
```

> If you get *connection refused*, the app isn't up yet (step 1) or nothing is on port 8080.
> If you get a `502`, the chosen backend is unreachable (Model Runner off, or no API key).

---

## Endpoints

### `POST /chat` — blocking

```bash
# Use the default backend
curl -s localhost:8080/chat -H 'Content-Type: application/json' \
  -d '{"message":"hi"}'

# Choose the backend per request (offline now, cloud next — no restart)
curl -s localhost:8080/chat -H 'Content-Type: application/json' \
  -d '{"message":"hi","backend":"docker"}'

# Override the model too
curl -s localhost:8080/chat -H 'Content-Type: application/json' \
  -d '{"message":"hi","backend":"openai","model":"gpt-4o"}'
```

Request fields: `message` (required), `backend` (optional), `model` (optional).
An unknown `backend` returns `400` with the list of configured backends.

### `GET /chat/stream` — Server-Sent Events

```bash
curl -N "localhost:8080/chat/stream?message=Tell%20me%20a%20joke&backend=docker"
```

### Health / metrics (Spring Boot Actuator)

```bash
curl -s localhost:8080/actuator/health
curl -s localhost:8080/actuator/metrics
```

---

## Choosing the default backend

Every backend is always callable per-request; the default only decides who answers requests that
*don't* name a `backend`.

```bash
# Make OpenAI cloud the default (needs a key)
OPENAI_API_KEY=sk-... mvn spring-boot:run \
  -Dspring-boot.run.arguments="--app.llm.default-backend=openai"

# Or via the `docker` profile (sets default-backend=docker)
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=docker"
```

Per-backend env overrides: `OPENAI_BASE_URL` / `OPENAI_API_KEY` / `OPENAI_MODEL`,
and `DMR_BASE_URL` / `DMR_MODEL`. See
[`application.yml`](src/main/resources/application.yml).

---

## Run with Docker Compose

The [`Dockerfile`](Dockerfile) builds the jar and packages it (multi-stage, JDK 17 → JRE 17),
so one command builds the image and starts it — no local Maven or JDK needed. The app runs in a
container and reaches **Docker Model Runner on the host** via its gateway DNS:

```bash
docker model pull ai/gemma3        # pull the model into the host's Model Runner (once)
docker compose up --build -d       # builds from the Dockerfile, then starts (compose.yaml)

curl -s localhost:8080/chat -H 'Content-Type: application/json' \
  -d '{"message":"hello from a container"}'
```

After the first build, `docker compose up -d` reuses the image; add `--build` again when code changes.
See [`compose.yaml`](compose.yaml) — it has the env vars to switch to OpenAI cloud instead.

> On a plain Docker Engine host (no Docker Desktop), install Model Runner as a plugin
> (`sudo apt-get install docker-model-plugin`) and verify against Docker's current docs —
> DMR-on-Docker-CE is still maturing.

---

## Testing

```bash
mvn test
```

The unit/web tests run offline (no backends needed). There's also a live connectivity check,
excluded from `mvn test`, that talks to real backends and self-skips any that are unavailable:

```bash
mvn test -Dtest=BackendConnectivityIT -DfailIfNoTests=false                       # offline backends only
OPENAI_API_KEY=sk-... mvn test -Dtest=BackendConnectivityIT -DfailIfNoTests=false # + OpenAI cloud
```

---

## How it works

Everything talks to backends through one seam — the
[`ChatProvider`](src/main/java/com/tirthoguha/omnillm/provider/ChatProvider.java) interface — with
provider-agnostic types ([`ChatPrompt`](src/main/java/com/tirthoguha/omnillm/provider/ChatPrompt.java)
/ [`ChatResult`](src/main/java/com/tirthoguha/omnillm/provider/ChatResult.java)) that keep the OpenAI
SDK from leaking past the boundary.

- The only class importing `com.openai.*` is
  [`OpenAiChatProvider`](src/main/java/com/tirthoguha/omnillm/provider/openai/OpenAiChatProvider.java),
  created once per backend (each with its own base-url/key client) and registered by name in the
  [`ChatProviderRegistry`](src/main/java/com/tirthoguha/omnillm/provider/ChatProviderRegistry.java).
  It wraps SDK failures in a stable `ChatProviderException`.
- [`ChatService`](src/main/java/com/tirthoguha/omnillm/service/ChatService.java) resolves backend →
  model → provider, and adapts the token stream onto SSE.
- [`ChatController`](src/main/java/com/tirthoguha/omnillm/controller/ChatController.java) validates
  input; failures and provider errors become RFC 7807 `ProblemDetail` responses via
  [`GlobalExceptionHandler`](src/main/java/com/tirthoguha/omnillm/web/GlobalExceptionHandler.java)
  (`400` validation / unknown backend, `502` upstream error, `500` otherwise).
- Config binds to an immutable, validated record
  ([`LlmProperties`](src/main/java/com/tirthoguha/omnillm/config/LlmProperties.java)) so the app
  fails fast on missing settings.

Adding a native (non-`/v1`) backend is one new `ChatProvider` dropped into the registry — nothing
else changes.
