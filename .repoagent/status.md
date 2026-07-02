# Status — Seam

> Live at-a-glance. Last updated: 2026-07-02.

## Now
**BYOK key layer landed** (see [decisions.md](decisions.md) D9): `api-key` optional per backend, app
boots with zero cloud keys, runtime key set/rotate/remove via `/admin/backends/<name>/key`,
declared-vs-provisioned registries, 503 `ProblemDetail` for keyless backends. First slice of the
agents-in-Seam direction (agents = named versioned presets; design discussed 2026-07-02, not yet built).

## Health
- **Offline gate:** ✅ `mvn test` — 61 tests, 0 failures, BUILD SUCCESS (2026-07-02).
- **Invariant #1 (SDK containment):** ✅ `com.openai` imports only in `provider/openai/` + `config/OpenAiConfig.java`.
- **Live gate:** ✅ manual BYOK lifecycle smoke-tested against a running app (keyless boot → 503 →
  PUT key → models appear → DELETE → gone). `BackendConnectivityIT` unchanged (self-skips).
- Open WebUI pinned to `v0.10.2` in `compose.openwebui.yaml` (was floating `main`), 2026-07-02.

## Next
- **Commit the BYOK change** (owner action — working tree holds it uncommitted).
- **Merge `chore/repoagent-operating-pack` → `main`** (owner action, still pending).
- Then: **agents-as-presets first cut** (yml-defined `app.llm.agents.<name>`, exposed via
  `/v1/models` as `agent:<name>`; versioning + tenancy per the agreed design), or pick from
  [`tasks.md`](tasks.md) T2/T3.

## Blocked
None.
