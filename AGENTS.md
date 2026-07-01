# AGENTS.md — OmniLLM repository supervisor contract

This file is the entry point for any agent working in this repository. It is the **supervisor
contract**: read it first, then the operating pack under [`.repoagent/`](.repoagent/). It was
bootstrapped from the protocol in
[`docs/agent-guidelines/repository-resident-architect.md`](docs/agent-guidelines/repository-resident-architect.md).

> This is durable, file-based state. Do not rely on chat memory — continue work by re-reading
> these files, not by assuming prior conversation context.

## What this repository is (one line)

A personal **proof-of-concept Spring Boot service** that chats with multiple LLM backends (OpenAI
cloud + local Docker Model Runner) through **one provider seam**, and now also re-exposes them
behind an **OpenAI-compatible `/v1` gateway**. Full classification + evidence:
[`.repoagent/repo_profile.md`](.repoagent/repo_profile.md).

## Operating loop

Work in this loop every run:

```
understand → classify → summarize → plan → scaffold/improve → verify → record → handoff → next
```

Default to **one supervisor (this contract) + skills**. Add a specialized subagent only with the
written justification required by the guideline (isolated context, author/verifier separation,
repeated work, parallelism, or a distinct domain). None are justified yet — see the agent registry
in [`.repoagent/operating_summary.md`](.repoagent/operating_summary.md).

## Hard architectural invariants (do not break without a decision record)

These come from the code and from [`CLAUDE.md`](CLAUDE.md). Breaking one is a bug, not a style
choice. Full detail + glossary: [`.repoagent/knowledge.md`](.repoagent/knowledge.md).

1. **Provider-seam containment.** `com.openai.*` imports are confined to `provider/openai/` and the
   composition root `config/OpenAiConfig.java`. Service, controllers, DTOs, and web must never
   import the SDK. (Verify: `grep -rl 'import com.openai' src/main/java` — only those two paths.)
2. **SSE framing.** Native stream sends one JSON object per token (`{"t":token}`) + a named `done`
   event; the `/v1` gateway sends `chat.completion.chunk` JSON + a literal `data: [DONE]`. Never
   emit raw token text (SSE strips a leading space).
3. **Fail-fast config.** `app.llm.*` binds to a `@Validated` immutable record (`LlmProperties`);
   missing default / empty backends / blank fields must stop boot, not error on first request.
4. **Shared stream executor.** SSE runs on `AsyncConfig.STREAM_EXECUTOR`. Never spawn per-request
   executors.
5. **Backend-agnostic core.** `ChatService` owns no provider-specific code; everything routes
   through `ChatProviderRegistry.get(name)`.

## Verification story (nothing is "done" until verified)

- `mvn test` — offline unit/web tests (`*Test`), the default gate. Must stay green.
- `mvn test -Dtest=BackendConnectivityIT -DfailIfNoTests=false` — live check, self-skips unreachable
  backends; run explicitly, never part of the default gate.
- Invariant grep (#1 above) for any change touching providers/config.
- Docs-sync check: if the API surface or backend table changes, update `CLAUDE.md`, `README.md`,
  and `web/index.html` in the same change (doc drift has already happened — see
  [`.repoagent/failures.md`](.repoagent/failures.md)).

## Self-improvement rule

Every meaningful success leaves at least one reusable asset (a skill, checklist, decision record,
or eval). Every repeated failure becomes a guardrail or eval. Record both; don't let lessons live
only in chat.

## Skill registry (executable)

- [`add-backend`](.claude/skills/add-backend/SKILL.md) — add or adjust an LLM backend behind the
  provider seam. This is the repository's defining recurring task.

Improvement candidates (not yet built) are tracked in [`.repoagent/tasks.md`](.repoagent/tasks.md).

## Where state lives

| File | Purpose |
|------|---------|
| `.repoagent/repo_profile.md` | Classification, evidence, personas, risks, recommended shape |
| `.repoagent/operating_summary.md` | Current agent-system architecture, milestone, guardrails, agent registry |
| `.repoagent/status.md` | Live at-a-glance: now / next / blocked |
| `.repoagent/tasks.md` | Backlog: next / blocked / improve / recurring |
| `.repoagent/knowledge.md` | Invariants, conventions, glossary |
| `.repoagent/decisions.md` | Decision log (why things are the way they are) |
| `.repoagent/failures.md` | Notable failures + the guardrails added in response |
| `.repoagent/handoff.md` | What changed, what remains, what's next |
