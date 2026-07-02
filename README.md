# Seam Spring

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
| `docker` | `http://localhost:12434/engines/v1`   | `ai/gemma4:E2B`   | Docker Model Runner |
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
    docker model pull ai/gemma4:E2B                       # pull the default model
    ```
  - **and/or OpenAI cloud** — set `OPENAI_API_KEY`, or skip it entirely: the app boots without it
    (the `openai` backend just shows as unconfigured) and you can supply a key later, at runtime,
    without a restart (BYOK):
    ```bash
    curl -X PUT localhost:8080/admin/backends/openai/key \
      -H 'Content-Type: application/json' -d '{"apiKey":"sk-..."}'
    # GET /admin/backends shows per-backend status; DELETE .../key removes a key again.
    ```
    Keys set this way live in memory only (a restart falls back to the env var) and are never
    echoed back or logged.

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
{"backend":"docker","model":"ai/gemma4:E2B","reply":"..."}
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

Each token is one SSE event whose `data` is a small JSON object — `{"t":"<token>"}` — so whitespace
survives the SSE "strip one leading space" rule and a browser `EventSource` can read it directly. A
named `done` event (carrying `{backend, model}`) ends the turn; failures arrive as a named `error`
event.

```bash
curl -N "localhost:8080/chat/stream?message=Tell%20me%20a%20joke&backend=docker"
# data:{"t":"Why"}  data:{"t":" did"} ...  event:done data:{"backend":"docker","model":"ai/gemma4:E2B"}
```

A browser client:

```js
const es = new EventSource('http://localhost:8080/chat/stream?message=hi');
es.onmessage = e => append(JSON.parse(e.data).t);
es.addEventListener('done', () => es.close());
```

### OpenAI-compatible gateway (`/v1`) — for Open WebUI etc.

Seam also speaks the OpenAI API, so any OpenAI client can drive it while requests still flow
through the same backend-routing seam. The OpenAI `model` field doubles as the backend selector,
read as `<backend>:<model>` (a bare backend name uses that backend's default model).

```bash
curl -s localhost:8080/v1/models          # one entry per backend: docker:ai/gemma4:E2B, openai:gpt-4o-mini

curl -s localhost:8080/v1/chat/completions -H 'Content-Type: application/json' -d '{
  "model":"docker:ai/gemma4:E2B",
  "messages":[{"role":"system","content":"Be terse."},{"role":"user","content":"hi"}]
}'                                          # add "stream":true for SSE chat.completion.chunk + [DONE]
```

The gateway supports **OpenAI-style function/tool calling**: pass a `tools` array in your request and the model returns `tool_calls` with `finish_reason:"tool_calls"` — in both the blocking and streaming response. This works with both the Chat Completions and Responses API backends, so Open WebUI's native tool mode and other OpenAI-tool-aware clients work against either backend.

The request also honours **`tool_choice`** (`"auto"`/`"none"`/`"required"` or a `{"type":"function","function":{"name":…}}` forced call), the usual **sampling params** (`temperature`, `top_p`, `max_tokens`/`max_completion_tokens`, `stop`, `seed`, and — Responses-API models only — `reasoning_effort`), and **multimodal input** (a `content` array mixing `text` and `image_url` parts, including `data:` base64 uploads) — each forwarded to whatever the chosen backend's wire protocol supports.

> **Heads-up on the default local model (`ai/gemma4:E2B`).** It's a *reasoning* model — it spends tokens on an internal chain-of-thought before the answer, so **give it a generous `max_tokens` (≥ ~200)**. A tight cap (e.g. 60) gets fully consumed by reasoning and comes back with empty `content` and `finish_reason:length` — this affects plain chat, tool calls, forced `tool_choice`, **and vision** alike. With enough budget, `tool_choice` `"auto"` / `"required"` / forced-function and **vision (`image_url` input) all work locally** — gemma4:E2B correctly reads image colors and layout. One genuine gemma4-on-DMR quirk (reproduces direct-to-DMR, so it's the model/runtime, not Seam): `tool_choice:"none"` can leak a malformed tool-call string into `content` — Seam strips this via its app-side fallback parser. Cloud models (`gpt-4o-mini`, `gpt-5.5`) honour all `tool_choice` modes and vision server-side.

> **Which models actually emit `tool_calls`?** It depends on the model *and* the runtime, not on Seam — the gateway always forwards `tools`, but a model returns `tool_calls` only if its runtime parses them. Models **tested locally so far** (Docker Model Runner):
>
> | local model (DMR) | `tool_calls`? | note |
> |---|:--:|---|
> | `ai/gemma4:E2B` (default) | ✅ | native `tool_calls` — Gemma 4 has native function calling and this DMR build parses it |
> | `ai/qwen2.5`, `ai/llama3.2` | ✅ | native `tool_calls` (blocking + streaming) |
> | `ai/gemma3` | ❌ | emits a ` ```tool_code ` text block instead (older convention, not parsed) |
> | `ai/smollm2` | ❌ | no tool-calling training |
>
> To check any other model, pull it, POST a `tools` request to `/v1/chat/completions`, see whether `tool_calls` come back, then remove it. Cloud models (`gpt-4o-mini`, `gpt-5.5`) parse tool calls server-side, so they never hit a runtime-parser limitation.

**OpenAI-compatible embeddings** (`POST /v1/embeddings`) route through the same `<backend>:<model>` selector — local on Docker Model Runner or on OpenAI cloud — so Open WebUI's RAG/document indexing works too:

```bash
docker model pull ai/mxbai-embed-large      # a local embedding model (once)
curl -s localhost:8080/v1/embeddings -H 'Content-Type: application/json' -d '{
  "model":"docker:ai/mxbai-embed-large",
  "input":"the quick brown fox"
}'                                           # or "openai:text-embedding-3-small" for cloud
```

> Embedding choice is **sticky**: switching the embedding backend changes vector dimensions and forces an Open WebUI re-index (unlike per-message chat switching).

**Run [Open WebUI](https://github.com/open-webui/open-webui) on top** — one command, two containers
(the app + Open WebUI, wired together), see [`compose.openwebui.yaml`](compose.openwebui.yaml):

```bash
docker model pull ai/gemma4:E2B                 # pull the local model into Docker Model Runner (once)
docker compose -f compose.openwebui.yaml up --build -d
open http://localhost:3000                 # then pick a model (see below) and chat
```

**Local vs cloud — pick from the model picker.** Open WebUI's model dropdown lists one entry per
backend:

- `docker:ai/gemma4:E2B` — the **local** model (Docker Model Runner), works offline, no key.
- `openai:gpt-4o-mini` — **OpenAI cloud**, needs a key (below).

**Keys go in a gitignored `.env`** next to the compose file (never commit them). Only needed for the
cloud backend (and, optionally, to speed up Open WebUI's first-boot model download):

```bash
# .env  (in the repo root)
OPENAI_API_KEY=sk-...     # enables the openai:gpt-4o-mini model; omit to stay local-only
HF_TOKEN=hf_...           # optional: lifts the HuggingFace rate limit on Open WebUI's first boot
```

No key? The app still boots — the `openai` models are simply omitted from the picker until you
either set the env var or push a key at runtime (`PUT /admin/backends/openai/key`, see above).

After editing `.env`, apply it (env-only change — no rebuild needed):

```bash
docker compose -f compose.openwebui.yaml up -d
```

Open WebUI's chats, settings, and uploads persist in the `open-webui` Docker volume across restarts
(`docker compose -f compose.openwebui.yaml down` keeps it; add `-v` only to wipe it). The gateway
lives in
[`OpenAiCompatController`](src/main/java/com/tirthoguha/seam/controller/OpenAiCompatController.java);
it reuses `ChatService`, so per-backend routing is preserved.

### Health / metrics (Spring Boot Actuator)

```bash
curl -s localhost:8080/actuator/health
curl -s localhost:8080/actuator/metrics
```

---

## Web UI

A single-file chat client lives in [`web/index.html`](web/index.html) — a *lite* stack
([Alpine.js](https://alpinejs.dev) + [marked](https://marked.js.org), both via CDN), deliberately
kept **out** of the backend. It has a backend selector, model override, a stream/blocking toggle,
live markdown rendering, and a per-reply `backend · model` badge.

Because the page runs on a different origin than the API, the app enables narrow CORS for the chat
endpoints (localhost origins + `file://`); see
[`CorsConfig`](src/main/java/com/tirthoguha/seam/config/CorsConfig.java). Start the app, then
open the page any way you like:

```bash
# simplest: open the file directly (apiBase defaults to http://localhost:8080, editable in the header)
open web/index.html

# or serve it (any static server works)
python3 -m http.server 5500 --directory web   # then visit http://localhost:5500
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

Per-backend env overrides: `OPENAI_BASE_URL` / `OPENAI_API_KEY` / `OPENAI_MODEL` /
`OPENAI_EMBED_MODEL` / `OPENAI_API`, and `DMR_BASE_URL` / `DMR_MODEL` / `DMR_EMBED_MODEL`. See
[`application.yml`](src/main/resources/application.yml).

**Chat wire protocol per backend.** A backend's `api` is `chat` (Chat Completions, the default) or
`responses` (OpenAI **Responses API**, for newer models like `gpt-5.5`). One provider seam hides which
a backend speaks, so tool calling / sampling / vision all work either way. Opt in with
`OPENAI_API=responses` (or `--app.llm.backends.openai.api=responses`) plus a Responses-capable
`OPENAI_MODEL`.

---

## Run with Docker Compose

The [`Dockerfile`](Dockerfile) builds the jar and packages it (multi-stage, JDK 17 → JRE 17),
so one command builds the image and starts it — no local Maven or JDK needed. The app runs in a
container and reaches **Docker Model Runner on the host** via its gateway DNS:

```bash
docker model pull ai/gemma4:E2B        # pull the model into the host's Model Runner (once)
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
[`ChatProvider`](src/main/java/com/tirthoguha/seam/provider/ChatProvider.java) interface — with
provider-agnostic types ([`ChatPrompt`](src/main/java/com/tirthoguha/seam/provider/ChatPrompt.java)
/ [`ChatResult`](src/main/java/com/tirthoguha/seam/provider/ChatResult.java)) that keep the OpenAI
SDK from leaking past the boundary.

- The only class importing `com.openai.*` is
  [`OpenAiChatProvider`](src/main/java/com/tirthoguha/seam/provider/openai/OpenAiChatProvider.java),
  created once per backend (each with its own base-url/key client) and registered by name in the
  [`ChatProviderRegistry`](src/main/java/com/tirthoguha/seam/provider/ChatProviderRegistry.java).
  It wraps SDK failures in a stable `ChatProviderException`.
- [`ChatService`](src/main/java/com/tirthoguha/seam/service/ChatService.java) resolves backend →
  model → provider, and adapts the token stream onto SSE.
- [`ChatController`](src/main/java/com/tirthoguha/seam/controller/ChatController.java) validates
  input; failures and provider errors become RFC 7807 `ProblemDetail` responses via
  [`GlobalExceptionHandler`](src/main/java/com/tirthoguha/seam/web/GlobalExceptionHandler.java)
  (`400` validation / unknown backend, `502` upstream error, `500` otherwise).
- Config binds to an immutable, validated record
  ([`LlmProperties`](src/main/java/com/tirthoguha/seam/config/LlmProperties.java)) so the app
  fails fast on missing settings.

Adding a native (non-`/v1`) backend is one new `ChatProvider` dropped into the registry — nothing
else changes.
