# RUN-LOG — evidence for the re-run

Update this file in the same commit as each PR merge. The final retrospective
is written from these rows, not from memory.

## Per-PR record

| PR | Date | Branch | Tests run | Full suite | Elapsed | Guard ok | CI link | Infra evidence | New ADRs / decisions |
|---|---|---|---|---|---|---|---|---|---|
| 01 | | feature/pr-01-project-setup | | | | | | | |
| 02 | | ... | | | | | | | |
| ... | | | | | | | | | |
| 35 | | ... | | | | | | | |

## Process incidents

| Date | Incident | Root cause | Fix | Prevention |
|---|---|---|---|---|
| | (e.g. wrong-branch commit, flaky test, CI failure) | | | |

## Closure evidence links

| Item | Where recorded | URL/artifact |
|---|---|---|
| CI run (workflow) | | |
| docker build / compose smoke | | |
| kustomize build all overlays | | |
| k8s apply (minikube/kind) | | |
| k6 run report | | |
| Jaeger trace screenshot | | |
| promtool check | | |
| Scope-matrix walk on running app | | |
