---
name: add-backend
description: Add or adjust an LLM backend in OmniLLM. Use when the user wants to point at another OpenAI-compatible /v1 endpoint, add a model runtime (Ollama, vLLM, LM Studio, a second cloud key), change a backend's default model/base URL, or add a non-/v1 native backend. Keeps the provider seam intact and docs in sync.
---

# Add or adjust an OmniLLM backend

Adding a backend is OmniLLM's defining recurring task. There are two cases. **Decide which first.**

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
           api-key:  ${<NAME>_API_KEY:placeholder}   # SDK needs non-blank even if the runtime ignores it
           model:    ${<NAME>_MODEL:<default-model>}
   ```
   Follow the existing `docker` / `openai` entries as templates. Use `${ENV:default}` placeholders.
2. `OpenAiConfig` builds one `OpenAIClient` + `OpenAiChatProvider` per configured backend and
   registers it in `ChatProviderRegistry` — confirm it iterates all backends (it should already), so
   no change is usually needed. If wiring is explicit per-backend, add the new one there.
3. The OpenAI-compat gateway will auto-advertise it at `GET /v1/models` as `<name>:<default-model>`.

## Case B — Native (non-`/v1`) backend

Only if the runtime does NOT speak the OpenAI `/v1` API. Then implement the seam:

1. Create `provider/<name>/<Name>ChatProvider.java` implementing `ChatProvider`
   (`name()`, `chat(ChatPrompt)`, `streamTokens(ChatPrompt, Consumer<String>)`).
2. **Keep SDK/HTTP details inside that class.** Translate to/from `ChatPrompt` / `ChatResult`. Wrap
   failures in `ChatProviderException`.
3. Register the instance in `OpenAiConfig` (or the registry builder) under its backend name.
4. Controller, `ChatService`, and DTOs stay untouched.

## Invariants you must not break (see `.repoagent/knowledge.md`)

- **Seam containment:** `com.openai.*` imports stay in `provider/openai/` + `config/OpenAiConfig.java`
  only. A native backend must NOT import the OpenAI SDK.
- **Fail-fast config:** every backend needs non-blank `base-url`, `api-key`, `model` or boot fails
  (that's intended).
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
