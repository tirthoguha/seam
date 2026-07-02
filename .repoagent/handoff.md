# Handoff — Seam

> What changed, what remains, what's next. Last updated: 2026-07-02.

## What changed this run
**BYOK key layer** (decision D9), the first slice of the agents-in-Seam direction:
- `LlmProperties`: `api-key` optional per backend; unknown `default-backend` now fails at bind time;
  new `Backend.hasKey()`.
- `provider/`: new `BackendNotConfiguredException` (declared-but-keyless, → 503) and SDK-free
  `ProviderFactory` interface. Both registries became mutable: declared-names set +
  `register`/`deregister`/`isProvisioned`; `get()` distinguishes 400 (undeclared) from 503 (keyless).
- `service/BackendProvisioner`: owns runtime keys (in-memory, seeded from env/yml, never
  persisted/logged/echoed) and the single provisioning path; rebuilds/tears down providers on key
  set/clear.
- `config/OpenAiConfig`: registries start empty; all SDK construction behind the `ProviderFactory`
  bean (invariant #1 intact).
- `controller/AdminController`: `GET /admin/backends`, `PUT|DELETE /admin/backends/{name}/key`
  (unauthenticated — local PoC only). `GlobalExceptionHandler`: 503 mapping.
  `OpenAiCompatController.models()`: omits keyless backends.
- Config: `application.yml` openai `api-key` defaults empty (was `not-needed` hack);
  `compose.openwebui.yaml` likewise. Docker keeps its `docker` placeholder (Model Runner ignores it).
- Docs synced: `CLAUDE.md`, `README.md`, `AGENTS.md` invariant #3 wording,
  `.claude/skills/add-backend/SKILL.md`, `knowledge.md`, `decisions.md` (D9), `status.md`.
- Also this session: Open WebUI image pinned `main` → `v0.10.2` and stack redeployed (healthy).

## Verification performed
- `mvn test` → **61 tests, 0 failures, BUILD SUCCESS** (new: AdminControllerTest, BYOK lifecycle in
  OpenAiConfigTest, 503 mapping in ChatControllerTest, models-omission in OpenAiCompatControllerTest).
- Invariant grep → only `provider/openai/*` + `config/OpenAiConfig.java`.
- Live smoke (app booted with `OPENAI_API_KEY` unset): `/admin/backends` shows openai unconfigured;
  chat against it → 503 ProblemDetail with remedy; `PUT` key → configured, models listed; `DELETE`
  → gone again. App instance stopped afterwards.

## What remains / what's next
- **Owner: commit this change** (working tree, branch `chore/repoagent-operating-pack`) and merge
  the branch into `main`.
- **Agents-as-presets first cut** (design agreed in chat 2026-07-02, recorded intent in
  `status.md`): yml-defined `app.llm.agents.<name>` → resolver merges system prompt/tools/sampling
  into `ChatPrompt`; exposed via `/v1/models` as `agent:<name>`; identity `(tenant=default, name)`;
  single `model: "<backend>:<model>"` string in the definition; immutable versions + `@N` pinning
  when persistence arrives. Tool-execution loops and RAG/knowledge deliberately out of scope.
- T2 (docs-sync automation) / T3 (invariant grep as a test) still open in `tasks.md`.

## Blocked
None.

## Note for the next agent
The pack is the canonical memory. Re-enter via `AGENTS.md` → this pack. The admin key endpoints are
deliberately unauthenticated — do not deploy multi-user without adding auth + tenant scoping (D9).
