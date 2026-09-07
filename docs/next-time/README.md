# Redo the Journey: Better Blueprint (docs/next-time)

Everything needed to run the **Order Management API 35-PR learning journey again
with agents** - but better: no repeated mistakes, missing elements covered,
design decided up front, and every artifact actually executed.

This folder is the "refined control package". It upgrades the original
`.cline/spec.md`, `.cline/instructions.md` and `.cline/pr-workflow.md` into a
coherent, executable plan informed by the retrospective in
`docs/good-and-bad/` and the learning document in `docs/learnings/`.

## What changed vs the first run (summary)

| Area | First run | This blueprint |
|---|---|---|
| Spec | 35 concepts, some scoped ad hoc | 35 PRs, each with explicit **Definition of Done**, file map, and test rules (`01-refined-spec.md`) |
| Architecture | decided piecemeal, then drifted | decided **up front** as contracts + ADR seeds (`04`) |
| Security | guards retrofitted, inconsistent | **scope/endpoint matrix written before controllers**; all mutations guarded uniformly |
| Errors | catch-all made 404→500 | last-resort handler + explicit framework mappings + regression test |
| Idempotency | only order create | policy applied to **every unsafe write** from PR #20 onward |
| State rules | one string check | **transition maps** with tests |
| Branch discipline | two direct-to-develop commits | pre-push guard + branch protection + agent rules (`02`, `03`) |
| Layering | domain imported API DTOs | dependency rules from PR #1, ArchUnit test, package map (`04`) |
| Testing | sleeps, many contexts | isolated contexts + Awaitility + parallel-ready (`05`) |
| Infra artifacts | written, never run | **closure plan**: CI/image/k8s/k6 run before "done" (`06`) |
| Docs | retro after the fact | **per-PR docs updated in the same PR** + ADR log |

## Files

| File | Purpose |
|---|---|
| [01-refined-spec.md](01-refined-spec.md) | The refined 35-PR specification with definitions of done |
| [02-refined-instructions.md](02-refined-instructions.md) | Agent instructions (replaces `.cline/instructions.md`) |
| [03-workflow-and-process.md](03-workflow-and-process.md) | Git/PR/merge runbook, scheduling, guard rails |
| [04-architecture-and-standards.md](04-architecture-and-standards.md) | Up-front architecture contracts + ADR seeds + package map |
| [05-testing-and-quality-blueprint.md](05-testing-and-quality-blueprint.md) | Test strategy + empirical-gotcha checklist + quality gates |
| [06-closing-the-loop.md](06-closing-the-loop.md) | Execute-everything closure plan + final demo checklist |

## How to use on the re-run

1. **Bootstrap** (do once, before PR #1): copy `02`/`03`/`04`/`05` into your
   agent instruction set (e.g. as `.cline/instructions.md`), create the
   `docs/adr/` skeleton, and set up the pre-push guard + branch protection.
2. **Per PR** (repeat 35 times): read the PR's spec section → read the relevant
   architecture contract → implement → write/adjust tests → run targeted +
   full suite → update that PR's README + any ADR → open PR → merge (per
   `03`). The DoD box in `01` is the checklist for each PR.
3. **Closure** after PR #35: run `06` - every artifact must have been executed
   (CI green incl. Docker build, image runs, k8s applied, k6 numbers recorded,
   promtool clean), then write the final retrospective.

## Success criteria for the re-run

- **0 direct-to-`develop` feature commits** (guard enforced).
- **0 known defects from the first run** reappear (404→500, orphan-removal
  without cascade, `@Lazy` at the bean only, projection alias mismatch,
  unguarded write endpoints, catch-all security-403→500).
- Every endpoint has an explicit guard entry in the scope matrix from the PR
  that creates it.
- Every unsafe write is idempotent (single implementation reused).
- Every infra artifact referenced in docs has been **executed at least once**
  with evidence (CI run link, image build log, `kubectl apply` output, k6
  report).
- 35/35 PRs merged with green full-suite runs and per-PR README notes.

*Write the retrospective from data this time: guard violations, CI results,
test counts per PR, wall-clock cost per PR - then compare with
`docs/good-and-bad/`.*
