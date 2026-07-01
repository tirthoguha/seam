# Status — Seam

> Live at-a-glance. Last updated: 2026-07-01.

## Now
Idle. Milestone 1 (bootstrap the durable `.repoagent/` operating pack) is **complete and verified**.

## Health
- **Offline gate:** ✅ `mvn test` — 50 tests, 0 failures, BUILD SUCCESS (2026-07-01).
- **Invariant #1 (SDK containment):** ✅ `com.openai` imports only in `provider/openai/` + `config/OpenAiConfig.java`.
- **Live gate:** not run this session (`BackendConnectivityIT` self-skips; run explicitly when needed).

## Next
- **Merge `chore/repoagent-operating-pack` → `main`** (owner action) so the un-ignored, tracked
  pack takes effect on the default branch.
- Then pick from [`tasks.md`](tasks.md): **T2 automate docs-sync** or **T3 automate the invariant grep**.

## Blocked
None. (T1 resolved: un-ignored + committed on branch `chore/repoagent-operating-pack`, pending merge.)

## Note
`compose.openwebui.yaml` has a pre-existing uncommitted modification (not from this run) — left untouched.
