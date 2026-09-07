# docs/not-asked — The Gap You Almost Missed

These are the materials that were *not explicitly requested* during the build
but that an expert-in-the-making (or the reviewer of this repo) would expect to
find. They turn the project from "a well-documented learning repo" into one
with **evidence, security artefacts, shareable architecture, operations
runbooks, and an execution path** — the things that separate "I studied it"
from "I can stand behind it".

| Pack | What it adds |
|---|---|
| [01-evidence-and-measurement-pack.md](01-evidence-and-measurement-pack.md) | Coverage & static-analysis plan, load-test runbook, per-PR changelog template — quantified, not anecdotal |
| [02-security-and-privacy-pack.md](02-security-and-privacy-pack.md) | STRIDE threat model, DPIA-style data-flow + PII inventory + lawful basis, security review checklist |
| [03-architecture-and-onboarding-pack.md](03-architecture-and-onboarding-pack.md) | Rendered C4 diagrams (Mermaid/PlantUML), as-built ADR register, 20-minute repo navigation map |
| [04-operations-and-demo-pack.md](04-operations-and-demo-pack.md) | Deploy / incident / rollback / backup-restore runbooks + a 10-minute guided interview demo script |
| [05-closure-execution-plan.md](05-closure-execution-plan.md) | The "execute everything" plan from `docs/next-time/06` adapted here, with an evidence log |

## How the packs relate to the rest of the docs
- **Reference** (what): `docs/business`, `docs/design`
- **Learning** (why): `docs/learnings`, `docs/interview-cheat-sheets`
- **Honesty & redo** (improve): `docs/good-and-bad`, `docs/next-time`
- **Mastery** (practice): `docs/others`
- **This folder** (prove it): evidence, security, diagrams, runbooks, execution

Use `05` as the "definition of done" for claiming this project is
production-credible, then let `01`'s numbers and `02`'s analysis speak for you.
