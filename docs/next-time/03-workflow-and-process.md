# 03 — Workflow & Process Runbook

How the re-run is actually executed: git rules, PR mechanics, scheduling,
guards, and the data to record for the final retrospective.

## 1. Repo topology
- `develop` = integration (default), protected: no direct pushes, required CI.
- Env branches `test`, `uat`, `staging`, `prod` mirror the promotion ladder.
- One feature branch per learning PR: `feature/pr-XX-short-name`.

## 2. The pre-push guard (install once)
Add a hook (`hooks/pre-push`) and wire it:
```bash
#!/usr/bin/env bash
# Refuse direct pushes to develop/prod/env branches from a feature context.
branch="$(git symbolic-ref --short HEAD)"
while read -r _local _local_sha _remote _remote_sha; do
  case "$_remote" in
    refs/heads/develop|refs/heads/main|refs/heads/prod|refs/heads/test|refs/heads/uat|refs/heads/staging)
      if [ "$branch" != "$_remote_ref_ok" ]; then
        echo "BLOCKED: never push '$branch' directly to '$_remote'. Use a PR."
        exit 1
      fi ;;
  esac
done
exit 0
```
Practical rule that prevents the first-run mistake: **all pushes to `develop`
happen only via `gh pr merge` or an explicit docs-after-merge step** - never
`git push origin develop` from a feature branch. If you must push doc updates
to develop post-merge, do it while ON develop and after confirming with
`git branch --show-current`.

## 3. GitHub protection
- Branch protection on `develop`: require PR + CI checks + linear/merge commit;
  "include administrators".
- Require status checks = the CI workflow `test` job.
- Keep `main`/`prod` protected for the promotion ladder (GitOps flow).

## 4. Daily loop (per PR)
```
1. git checkout develop && git pull --ff-only origin develop
2. git checkout -b feature/pr-XX-name
3. implement + tests (see 01/05)
4. targeted run → full suite → record: <tests run, failures, elapsed>
5. README section + ADRs
6. git add -A && git commit -m "feat(pr-XX): ..."
7. git push -u origin feature/pr-XX-name   # guard active
8. gh pr create --base develop ...         # body per 02 template
9. merge: gh pr merge <n> --merge --delete-branch   (off-peak, DoD green)
10. update README status line + push docs commit on develop (verify branch)
```

## 5. Scheduling & cost windows (Australia/Sydney)
- **Peak (no work at all)**: weekdays 11:00-14:00 and 16:00-20:00.
- Off-peak otherwise, plus all weekend.
- If a request lands in peak: reply that work is deferred to the next
  off-peak window and stop. If off-peak work is interrupted by peak start:
  finish only the in-flight command.
- Record per-PR wall-clock + token spend so the retrospective is data-driven.

## 6. Evidence logging (for the retrospective)
Maintain `docs/next-time/RUN-LOG.md` (add one row per PR):
| PR | date | tests | full suite result | branch-guard ok | CI link | infra evidence | decisions/ADRs |
Keep it updated in the same commit as the PR merge.

## 7. Promotion (after each PR, or batched)
- Manual, owned by the user: `./scripts/promote.sh develop test` etc.
- Never auto-promote past `test` without a human gate. `prod` only from `main`.

## 8. Merge hygiene
- Merge commits for the learning history (`--merge`), not squash-only, so the
  per-PR story is preserved in history.
- Delete the feature branch after merge (auto with `--delete-branch`).

## 9. Doc-only pushes to develop
The only allowed direct-to-develop push is a small doc/status update made
while the current branch IS `develop`, after a merge, and it must be
`docs:` commit. Everything else goes through a PR.

## 10. Failure response playbook
- Compile/test failure → fix on the feature branch; never "fix forward" on
  develop.
- Wrong-branch commit (should not happen - guard) → STOP, create branch at
  HEAD, reset develop to origin, push feature, resume from step 6. Log it in
  RUN-LOG as a process incident.
- Flaky timing test → replace with Awaitility; do not lengthen sleeps.
- Ambiguous requirement → write the decision as an ADR in the PR, don't guess
  silently.
