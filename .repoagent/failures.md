# Failures & guardrails — Seam

> Notable failures and the guardrails added in response. Referenced by `AGENTS.md`. Last updated: 2026-07-01.

## F1 — Doc drift across the three doc surfaces
**What happened:** The API surface / backend table was updated in one doc but not the others
(`CLAUDE.md`, `README.md`, `web/index.html` describe the same surface).
**Guardrail:** Docs-sync check in the verification story — any change to the API surface or backend
table must update all three in the same change. Recorded as invariant/guardrail in
[`operating_summary.md`](operating_summary.md) and decision [`D6`](decisions.md).

## F2 — Durable operating pack was missing (2026-07-01)
**What happened:** `AGENTS.md` and `CLAUDE.md` referenced a full `.repoagent/` pack (profile,
summary, status, tasks, knowledge, decisions, failures, handoff) as if it existed. The directory
was empty and nothing was ever committed there. Continuity depended entirely on chat memory,
violating the filesystem-first rule.
**Root cause found:** `.repoagent/` is gitignored (`.gitignore:9`), so even a pack created earlier
would never have been committed — and a `git clean`/fresh clone yields an empty directory. This
contradicts `AGENTS.md`, which is committed and treats the pack as durable-across-clones state.
**Guardrail:** Pack bootstrapped and grounded in verified evidence. Going forward: **never end a
meaningful run without updating `status.md` + `handoff.md`**; treat the pack as the canonical
memory, not chat. **Unresolved:** the gitignore-vs-`AGENTS.md` contradiction — see `tasks.md` T1
(owner decision required).

---

## Repeated-mistake watchlist (guard against these)

- **Leaking the SDK past the seam** — always re-run the invariant grep after touching
  `provider/`, `service/`, `controller/`, `dto/`, or `config/`.
- **Raw SSE token text** — never emit a token without its `{"t":...}` wrapper (native) or
  `chat.completion.chunk` frame (`/v1`); SSE strips a leading space.
- **Claiming a model supports tool calls without testing** — the support matrix in
  [`knowledge.md`](knowledge.md) is only as honest as the download→test→delete workflow behind it.
- **Marking work done without the offline gate** — `mvn test` green is the floor.
