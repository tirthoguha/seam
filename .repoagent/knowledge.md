# Knowledge — Seam

> Stable facts, invariants, conventions, glossary, and the tested-model matrix.
> Referenced by `CLAUDE.md` and `AGENTS.md`. Last verified: 2026-07-01.

## Hard architectural invariants

Breaking one is a bug, not a style choice. Do not break without a [decision record](decisions.md).

1. **Provider-seam containment.** `com.openai.*` imports live only in `provider/openai/` and the
   composition root `config/OpenAiConfig.java`. Service, controllers, DTOs, web must never import
   the SDK. Requests/responses use provider-agnostic records (`ChatPrompt`/`ChatResult` etc.).
   **Verify:** `grep -rl 'import com.openai' src/main/java` → only:
   - `config/OpenAiConfig.java`
   - `provider/openai/OpenAiChatProvider.java`
   - `provider/openai/OpenAiResponsesProvider.java`
   - `provider/openai/OpenAiEmbeddingProvider.java`
   - `provider/openai/OpenAiModels.java`
   (i.e. `provider/openai/` + `OpenAiConfig`. `CLAUDE.md`'s "two places" is shorthand for
   "the adapter package + the composition root".)
2. **SSE framing.** Native `/chat/stream`: one JSON object per token `{"t":token}` + named `done`
   event (or named `error`). `/v1/chat/completions`: standard `chat.completion.chunk` (content in
   `delta.content`, tool fragments in `delta.tool_calls`, final chunk carries `finish_reason`) +
   literal `data: [DONE]`. Never emit raw token text — SSE strips one leading space.
3. **Fail-fast config.** `app.llm.*` → `@Validated` immutable record `LlmProperties`
   (`@EnableConfigurationProperties` on `SeamApplication`). Missing default / empty backend map /
   blank field must stop boot, not error on first request.
4. **Shared stream executor.** SSE runs on `AsyncConfig.STREAM_EXECUTOR`
   (`ThreadPoolTaskExecutor`, bounded, drained on shutdown). Never spawn per-request executors.
5. **Backend-agnostic core.** `ChatService` owns no provider-specific code; all routing goes
   through `ChatProviderRegistry.get(name)`. Same for `EmbeddingService` / `EmbeddingProviderRegistry`.

## Conventions

- **Test naming:** `*Test` = offline unit/web, run by `mvn test`. `*IT` = live integration,
  **excluded** from `mvn test`, run explicitly, self-skips unreachable backends.
- **Model selector:** OpenAI `model` field is `<backend>:<model>` — the entire local-vs-cloud
  switch. `docker:<model>` runs on Model Runner, `openai:<model>` on cloud.
- **Wire protocol per backend:** `api: chat` (Chat Completions, default) or `api: responses`
  (Responses API, for newer models). `OpenAiConfig` picks the provider class; core is unaware.
- **Knob application:** each sampling/tool knob is applied only where the wire protocol supports
  it and silently ignored elsewhere (`stop`/`seed` = Chat-only; `reasoning_effort` = Responses-only).
- **Embedding stickiness:** switching embedding backend changes vector dimensions → forces an
  Open WebUI re-index. Treat embedding backend as sticky.

## Glossary

- **Provider seam** — the `ChatProvider`/`EmbeddingProvider` abstraction + agnostic records that
  keep the SDK out of the core.
- **Backend** — a configured runtime (`app.llm.backends.<name>`): base URL + key + model(s) + api.
  Ships with `docker` (local Model Runner) and `openai` (cloud).
- **Registry** — one provider instance per *provisioned* backend, keyed by name. Declared (in
  `app.llm.backends`) ≠ provisioned (currently holding an API key): declared-but-keyless → 503
  `BackendNotConfiguredException`; undeclared → 400 `IllegalArgumentException`.
- **BackendProvisioner / BYOK** — runtime key owner: seeds from env/yml, `PUT/DELETE
  /admin/backends/<name>/key` sets/rotates/removes without restart (providers rebuilt via
  `ProviderFactory`, the SDK-free construction seam implemented in `OpenAiConfig`). Keys are
  in-memory only, never persisted/logged/echoed.
- **Gateway** — `OpenAiCompatController`, the OpenAI-compatible `/v1` surface (models, chat
  completions, embeddings) so OpenAI clients (Open WebUI) can drive both chat and RAG.
- **Native tool calling** — gateway maps inbound `tools`/`tool_choice`/`role:"tool"` onto agnostic
  `ToolSpec`/`ToolChoice`/`ToolCall`, returns `tool_calls` + `finish_reason:"tool_calls"` + `usage`.

## Default backends

| backend | base URL | chat model | embedding model | api |
|---------|----------|------------|-----------------|-----|
| `docker` | `http://localhost:12434/engines/v1` | `ai/gemma4:E2B` | `ai/mxbai-embed-large` | `chat` |
| `openai` | `https://api.openai.com/v1` | `gpt-4o-mini` | `text-embedding-3-small` | `chat` (`responses` opt-in) |

Default backend: `DEFAULT_BACKEND` env / `--app.llm.default-backend` (docker profile flips to
`docker`). `api-key` is optional (BYOK): unset → declared-but-unconfigured, boots fine, `/v1/models`
omits it, 503 on use until a key arrives (env or `PUT /admin/backends/<name>/key`). Keyless-auth
local runtimes still need a non-blank placeholder to count as configured (`docker` uses the literal
`docker`, ignored by Model Runner).

## Tested-model support matrix (native tool calling)

Whether a model returns `tool_calls` is a **model + runtime** property, not Seam's — the gateway
always forwards `tools`; a model returns calls only if its runtime parses them.

| Model (Docker Model Runner) | Tool calls | Notes |
|-----------------------------|:----------:|-------|
| `gemma4:E2B` (default) | ✅ | Tested |
| `qwen2.5` | ✅ | Tested |
| `llama3.2` | ✅ | Tested |
| `gemma3` | ❌ | Older `tool_code` convention not parsed |

`ToolCallTextParser` exists to recover tool calls a runtime emits as text rather than structured
`tool_calls` (see `ToolCallTextParserTest`, 10 cases).

### Extend the matrix — download → test → delete

1. `docker model pull ai/<model>`
2. Run Seam, drive `/v1/chat/completions` with a `tools` array against `docker:<model>`; confirm
   `finish_reason:"tool_calls"` + a well-formed `tool_calls` payload.
3. Record ✅/❌ + notes in the table above.
4. `docker model rm ai/<model>` to reclaim disk if not keeping it.

## Verification surfaces

- `mvn test` — offline `*Test`, the default gate (currently 50 tests, green).
- `mvn test -Dtest=BackendConnectivityIT -DfailIfNoTests=false` — live, self-skipping; explicit only.
- Invariant grep (#1) for any provider/config change.
- Docs-sync check when the API surface or backend table changes.
