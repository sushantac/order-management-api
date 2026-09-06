# PR Workflow Instructions

## Git Branch Strategy
We use GitOps branching:
- `develop`: Integration branch (default)
- `test`: Automated testing environment
- `uat`: User acceptance testing
- `staging`: Pre-production staging
- `prod`: Production

## PR Workflow
1. Create feature branch from `develop`
2. Generate code for the PR
3. Commit and push
4. Create GitHub PR using `gh pr create`
5. Wait for review
6. Merge to `develop`
7. Promote: `./scripts/promote.sh develop test`

## Branch Naming
- Feature: `feature/pr-01-project-setup`
- Pattern: `feature/pr-XX-description`

## Commit Messages
- Format: `feat(pr-XX): description`
- Body: What was learned, key decisions

## PR Creation
```bash
gh pr create \
  --title "PR #XX: Description" \
  --body-file .github/PULL_REQUEST_TEMPLATE.md \
  --base develop \
  --head feature/pr-XX-description \
  --label "learning, phase-XX"