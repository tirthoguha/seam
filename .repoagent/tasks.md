# Tasks — Seam

> Backlog: now / next / blocked / improve / recurring. Last updated: 2026-07-01.

## Now
- _(idle)_ — Milestone 1 (bootstrap the durable pack) is complete and verified.

## Done
- **T1 — Resolve the `.repoagent/` gitignore contradiction.** ✅ 2026-07-01. Owner chose
  un-ignore + commit. Removed `.repoagent/` from `.gitignore`; committed the pack on branch
  `chore/repoagent-operating-pack`. **Remaining:** merge that branch to `main` so it takes effect
  there (owner action).

## Next (candidate milestones — pick one when work resumes)
- **T2 — Automate the docs-sync guardrail (F1).** Nothing enforces the three-doc sync. Add a
  lightweight check (test or script) that fails when the backend table / endpoint list diverges.
  *Verification:* the check fails on an intentional drift, passes on `main`.
- **T3 — Automate the invariant grep.** Turn invariant #1 into an executable test (fail if
  `import com.openai` appears outside `provider/openai/` + `OpenAiConfig`). *Verification:* test
  goes red when a stray import is added.

## Blocked
- _(none)_

## Improve (candidate reusable assets, not yet built)
- **I1 — `extend-model-matrix` skill** wrapping the download→test→delete workflow in
  [`knowledge.md`](knowledge.md), emitting a matrix-row update. Justified once it's done ≥2 more times.
- **I2 — `verify-seam` skill/checklist** bundling the three mechanical gates (offline `mvn test`,
  invariant grep, docs-sync) into one runnable pass before any "done".

## Recurring
- **Add / adjust a backend** → skill [`add-backend`](../.claude/skills/add-backend/SKILL.md).
- **Before any "done":** offline `mvn test` green + invariant grep clean + docs-sync if API/table
  changed.
- **End of every meaningful run:** update [`status.md`](status.md) + [`handoff.md`](handoff.md).
