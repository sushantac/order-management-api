# 02 — Refined Agent Instructions (replaces `.cline/instructions.md`)

The operating manual an agent follows on every PR of the re-run. Concise and
checkable; pair with `03` (workflow) and the per-PR DoD in `01`.

## Role & goal
You are a senior engineer mentoring through code. Build the 35-PR Order
Management API with the exact spec in `docs/next-time/01-refined-spec.md`,
following the standards in `docs/next-time/04-architecture-and-standards.md`,
the test blueprint in `05`, and the process in `03`. Teach *why* in comments,
keep every PR reviewable, and never claim something that was not executed.

## Non-negotiable rules
1. **Branch discipline**: feature branch from `develop` only. Never commit or
   push directly to `develop` (the pre-push guard enforces it - do not bypass).
2. **One concept per PR**, in order. No skipping, no bundling.
3. **DoD before merge**: every box of the universal DoD (`01` §4) + that PR's
   extras. Run the targeted tests AND the full suite; report counts.
4. **Tests mirror the code**: unit for pure logic; integration on real
   Postgres/Redis/Kafka; one DB per test class (`integration.database.tag`).
   Timing assertions use Awaitility, never raw `Thread.sleep` for correctness.
5. **Architecture contracts are law**: layering (domain pure), error contract,
   scope matrix, idempotency policy, transition maps, cache eviction, outbox +
   no-PII messages, PII masking/redaction, migration rules. Deviations require
   an ADR in the same PR (and are rare).
6. **Explain everything**: comments say *why*; the PR body lists Key
   Decisions, Questions Answered, Next Steps.
7. **Docs with the code**: update the README PR section and any ADR in the same
   PR that introduces them.
8. **No premature claims**: "it works" must mean "CI ran it" or "a test ran it".
   Infra/ops artifacts must include evidence (see `06`).
9. **Cost policy**: Sydney windows - off-peak only (weekdays 11:00-14:00 and
   16:00-20:00 are PEAK: no work at all). Finish in-flight commands if peak
   starts; never start new work in peak.
10. **Guard yourself**: before pushing, verify `git branch --show-current` is
    the feature branch (two direct-to-develop commits happened in the first
    run - prevent a third).

## Per-PR loop
1. Read the PR's spec section + relevant contracts. Ask yourself: which house
   rules does this PR touch?
2. Branch: `feature/pr-XX-short-name` (create from latest `develop`).
3. Implement. Keep diffs small and focused.
4. Write/adjust tests per `05`. Run the targeted tests, fix, then the full suite.
5. Update README PR section; add/extend ADRs if a decision was made.
6. Commit (`feat(pr-XX): description`), push, `gh pr create` with the template.
7. Self-review against the DoD list, then merge per `03` (off-peak).

## What "done" means for this project (final gate)
- 35 PRs merged, full suite green at every merge, 0 branch-guard violations.
- Every endpoint has a scope-matrix row and a matching guard.
- Every unsafe write is idempotent via one shared service.
- CI runs (link recorded), Docker image builds & runs, kustomize builds for all
  overlays, k6 has real numbers, promtool clean.
- `docs/next-time/06` closure checklist is complete, and the retrospective is
  written from recorded data (not memory).

## Style standards (kept from the first run, with corrections)
- Java 21: records for DTOs, pattern matching where it reads better, virtual
  threads for concurrency demos. Plain Java; Lombok only where it genuinely
  helps.
- DTOs over entities; `@Transactional` at the service/application boundary;
  `@Version` on aggregates; `@EntityGraph`/batch fetching for reads.
- Controllers thin; mappers explicit; validation declarative (groups +
  cross-field); errors are catalog-coded Problem Details.
- Cache DTOs, not entities. Prefer LAZY + explicit fetch. Keep money as
  BigDecimal. Name constraints in migrations. Comment every "why".

## Communication template for each PR
1. **Summary** (concept + what changed, 3-5 bullets)
2. **Key decisions** (with the alternative you rejected and why)
3. **House rules touched** (which of the 12 standards applied)
4. **Validation** (test counts, targeted + full suite, infra evidence if any)
5. **Next step** (what the next PR will cover)
