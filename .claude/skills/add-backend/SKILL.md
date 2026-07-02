---
name: add-backend
description: Add or adjust an LLM backend in Seam. Use when the user wants to point at another OpenAI-compatible /v1 endpoint, add a model runtime (Ollama, vLLM, LM Studio, a second cloud key), change a backend's default model/base URL, or add a non-/v1 native backend. Keeps the provider seam intact and docs in sync.
---

# Add or adjust an Seam backend

Adding a backend is Seam's defining recurring task. There are two cases. **Decide which first.**

## Case A — OpenAI-compatible `/v1` backend (the common case)

Any runtime that speaks the OpenAI `/v1` API (another cloud key, Ollama, vLLM, LM Studio, a second
local model) needs **no Java code** — only config. The existing `OpenAiChatProvider` serves it.

1. Add an entry under `app.llm.backends.<name>` in `src/main/resources/application.yml`:
   ```yaml
   app:
     llm:
       backends:
         <name>:
           base-url: ${<NAME>_BASE_URL:http://...}/v1
           # Keyless-auth runtime (Ollama, Model Runner): use a non-blank placeholder so the backend
           # is configured at boot. Cloud backend: default to empty (${..._API_KEY:}) — it then boots
           # declared-but-unconfigured and a key can be supplied at runtime (BYOK):
           #   PUT /admin/backends/<name>/key  {"apiKey":"..."}
           api-key:  ${<NAME>_API_KEY:placeholder}
           model:    ${<NAME>_MODEL:<default-model>}
   ```
   Follow the existing `docker` / `openai` entries as templates. Use `${ENV:default}` placeholders.
2. `BackendProvisioner` provisions every declared backend from its current key (env/yml seed or
   runtime BYOK) through `OpenAiConfig`'s `ProviderFactory` — no wiring change needed for a new
   `/v1` backend.
3. The OpenAI-compat gateway will auto-advertise it at `GET /v1/models` as `<name>:<default-model>`.

## Case B — Native (non-`/v1`) backend

Only if the runtime does NOT speak the OpenAI `/v1` API. Then implement the seam:

1. Create `provider/<name>/<Name>ChatProvider.java` implementing `ChatProvider`
   (`name()`, `chat(ChatPrompt)`, `streamTokens(ChatPrompt, Consumer<String>)`).
2. **Keep SDK/HTTP details inside that class.** Translate to/from `ChatPrompt` / `ChatResult`. Wrap
   failures in `ChatProviderException`.
3. Register the instance under its backend name — either via `ChatProviderRegistry.register(...)`
   at startup or by teaching `BackendProvisioner`/`ProviderFactory` about the new flavour.
4. Controller, `ChatService`, and DTOs stay untouched.

## Invariants you must not break (see `.repoagent/knowledge.md`)

- **Seam containment:** `com.openai.*` imports stay in `provider/openai/` + `config/OpenAiConfig.java`
  only. A native backend must NOT import the OpenAI SDK.
- **Fail-fast config:** every backend needs non-blank `base-url` + `model` or boot fails (intended).
  `api-key` is optional by design: keyless = declared-but-unconfigured (503 until a key arrives via
  env or `PUT /admin/backends/<name>/key`).
- **No new executors** for streaming — reuse the shared `AsyncConfig.STREAM_EXECUTOR` via
  `ChatService.runStream`.

## Verify (do not mark done until these pass)

1. `grep -rl 'import com.openai' src/main/java` → only `provider/openai/` + `config/OpenAiConfig.java`.
2. `mvn test` → green (offline gate).
3. Live smoke (optional, needs the backend up):
   `mvn test -Dtest=BackendConnectivityIT -DfailIfNoTests=false` — self-skips if unreachable.
4. Manual: `GET /v1/models` lists the new backend; a `POST /chat` (or `/v1/chat/completions` with
   `model: "<name>:..."`) routes to it.

## Sync docs (required — doc drift is a logged failure, F1)

Update in the **same change**: the backend table in `CLAUDE.md`, `README.md`, and the backend
selector in `web/index.html` if present.

## Record

Add a one-line entry to `.repoagent/decisions.md` (why this backend) and update `.repoagent/status.md`.
