```bash
#!/bin/bash
PR_NUMBER=$1

if [ -z "$PR_NUMBER" ]; then
    echo "Usage: ./scripts/pr-review-checklist.sh <PR-number>"
    exit 1
fi

echo "📋 PR #${PR_NUMBER} Review Checklist"
echo "======================================"
echo ""

# Fetch PR details
gh pr view $PR_NUMBER --json title,headRefName,baseRefName,state,additions,deletions

echo ""
echo "📊 Files Changed:"
gh pr diff $PR_NUMBER --stat

echo ""
echo "✅ Review Checklist:"
echo "  [ ] Code compiles"
echo "  [ ] Tests pass"
echo "  [ ] No TODO comments left"
echo "  [ ] Comments explain design decisions"
echo "  [ ] Follows Java 21 conventions"
echo "  [ ] No sensitive data exposed"
echo "  [ ] Documentation updated"
echo "  [ ] All tests added/passed"

echo ""
echo "📝 Commands:"
echo "  # Approve PR"
echo "  gh pr review $PR_NUMBER --approve"
echo ""
echo "  # Request changes"
echo "  gh pr review $PR_NUMBER --request-changes"
echo ""
echo "  # Merge PR"
echo "  gh pr merge $PR_NUMBER --merge"