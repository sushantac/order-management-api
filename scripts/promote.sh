#!/bin/bash
# GitOps Promotion Script (Flexible: develop → any except prod)

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Valid environments
VALID_ENVS=("develop" "test" "uat" "staging" "prod")

# Promotion rules (source -> allowed targets)
declare -A PROMOTION_RULES=(
    # develop can go to ANY environment except prod
    ["develop"]="test|uat|staging"     # prod is NOT allowed here

    # Standard paths
    ["test"]="uat|staging|prod"        # test can go to uat, staging, or prod
    ["uat"]="staging|prod"             # uat can go to staging or prod
    ["staging"]="prod"                 # staging only goes to prod

    # Feature branches (optional)
    ["feature/*"]="develop|test|staging"  # Features can go to develop, test, or staging
)

# Promotion validation requirements
declare -A PROMOTION_VALIDATION=(
    ["develop->test"]="ci-tests"
    ["develop->uat"]="ci-tests|integration-tests"
    ["develop->staging"]="ci-tests|integration-tests|performance-tests"
    ["test->uat"]="integration-tests"
    ["test->staging"]="integration-tests|performance-tests"
    ["test->prod"]="integration-tests|performance-tests|security-scan"
    ["uat->staging"]="performance-tests"
    ["uat->prod"]="performance-tests|security-scan"
    ["staging->prod"]="security-scan|load-tests"
)

# Get validation requirements
get_validation_requirements() {
    local source=$1
    local target=$2
    local key="${source}->${target}"

    if [[ -n "${PROMOTION_VALIDATION[$key]}" ]]; then
        echo "${PROMOTION_VALIDATION[$key]}"
    else
        echo "basic-checks"
    fi
}

# Validate environment
validate_env() {
    local env=$1
    for valid in "${VALID_ENVS[@]}"; do
        if [[ "$valid" == "$env" ]]; then
            return 0
        fi
    done
    echo -e "${RED}❌ Invalid environment: $env${NC}"
    echo -e "Valid environments: ${VALID_ENVS[*]}"
    exit 1
}

# Check if promotion is allowed
is_promotion_allowed() {
    local source=$1
    local target=$2

    # Special handling: prevent develop → prod
    if [[ "$source" == "develop" && "$target" == "prod" ]]; then
        return 1  # NOT allowed
    fi

    # Check if source is a feature branch
    if [[ "$source" == feature/* ]]; then
        local allowed_targets="${PROMOTION_RULES["feature/*"]}"
        if [[ "$allowed_targets" == *"$target"* ]]; then
            return 0
        fi
        return 1
    fi

    # Check standard branches
    local allowed_targets="${PROMOTION_RULES[$source]}"
    if [[ -z "$allowed_targets" ]]; then
        return 1
    fi

    if [[ "$allowed_targets" == *"$target"* ]]; then
        return 0
    fi

    return 1
}

# Get approval required
get_approval_required() {
    local target=$1
    case $target in
        "develop") echo "1 review" ;;
        "test") echo "CI passes" ;;
        "uat") echo "QA sign-off" ;;
        "staging") echo "Team lead approval" ;;
        "prod") echo "Change Board approval" ;;
        *) echo "Standard review" ;;
    esac
}

# Show promotion details
show_promotion_details() {
    local source=$1
    local target=$2
    local promotion_type=${3:-"Standard"}

    echo -e "${BLUE}📋 Promotion Details${NC}"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo -e "Source:     ${YELLOW}$source${NC}"
    echo -e "Target:     ${YELLOW}$target${NC}"
    echo -e "Type:       ${YELLOW}$promotion_type${NC}"
    echo -e "Validation: ${YELLOW}$(get_validation_requirements $source $target)${NC}"
    echo -e "Approval:   ${YELLOW}$(get_approval_required $target)${NC}"

    if [[ "$source" == "develop" && "$target" == "staging" ]]; then
        echo ""
        echo -e "${BLUE}💡 Note: Direct promotion from develop to staging${NC}"
        echo "   This bypasses test and uat environments."
        echo "   Ensure you've validated changes thoroughly."
    fi
    echo ""
}

# Execute promotion
execute_promotion() {
    local source=$1
    local target=$2

    echo -e "${BLUE}🚀 Promoting: $source → $target${NC}"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    # Checkout and update target
    git checkout $target
    git pull origin $target 2>/dev/null || echo "No remote, skipping pull"

    # Merge source into target
    echo -e "${BLUE}📦 Merging $source into $target...${NC}"
    git merge --no-ff $source -m "Promote: $source → $target"

    # Push to remote
    if git remote get-url origin >/dev/null 2>&1; then
        echo -e "${BLUE}📤 Pushing to remote...${NC}"
        git push origin $target
        echo -e "${GREEN}✅ Pushed to remote!${NC}"
    else
        echo -e "${YELLOW}⚠️  No remote configured. Skipping push.${NC}"
    fi

    echo ""
    echo -e "${GREEN}✅ Promotion complete: $source → $target${NC}"
}

# Create PR for promotion
create_promotion_pr() {
    local source=$1
    local target=$2

    if command -v gh &> /dev/null; then
        echo -e "${BLUE}📝 Creating GitHub PR for promotion...${NC}"

        local title="Promote: $source → $target"
        if [[ "$source" == "develop" && "$target" == "staging" ]]; then
            title="🚀 Direct Promotion: develop → staging"
        fi

        gh pr create \
            --base $target \
            --head $source \
            --title "$title" \
            --body "## Promotion: $source → $target

## 🎯 Reason for Promotion
- **Source:** $source
- **Target:** $target
- **Type:** ${3:-"Standard"}

## Changes
\`\`\`bash
git log $target..$source --oneline
\`\`\`

## Validation Required
- [ ] $(get_validation_requirements $source $target | tr '|' '\n' | sed 's/^/- /')
- [ ] Approved by $(get_approval_required $target)

## Next Steps
1. Review changes
2. Approve PR
3. Merge to complete promotion"

        echo -e "${GREEN}✅ PR created!${NC}"
    fi
}

# Main promotion flow
main() {
    local source=$1
    local target=$2
    local promotion_type=${3:-"Standard"}

    # Validate arguments
    if [[ -z "$source" ]] || [[ -z "$target" ]]; then
        echo -e "${RED}❌ Usage: ./scripts/promote.sh <source> <target> [type]${NC}"
        echo -e "Example: ./scripts/promote.sh develop staging \"Direct\""
        echo -e ""
        echo -e "Available promotions:"
        echo -e "  develop → test, uat, staging (prod NOT allowed)"
        echo -e "  test → uat, staging, prod"
        echo -e "  uat → staging, prod"
        echo -e "  staging → prod"
        echo -e "  feature/* → develop, test, staging"
        exit 1
    fi

    # Validate environments
    validate_env "$source"
    validate_env "$target"

    # Check if source is a feature branch
    if [[ "$source" == feature/* ]]; then
        if [[ "$target" != "develop" ]] && [[ "$target" != "test" ]] && [[ "$target" != "staging" ]]; then
            echo -e "${RED}❌ Feature branches can only promote to: develop, test, staging${NC}"
            exit 1
        fi
    else
        # Check if promotion is allowed
        if ! is_promotion_allowed "$source" "$target"; then
            echo -e "${RED}❌ Promotion from $source to $target is not allowed${NC}"
            if [[ "$source" == "develop" && "$target" == "prod" ]]; then
                echo -e "${RED}⚠️  develop → prod is blocked!${NC}"
                echo -e "   For production, use: develop → staging → prod"
            else
                echo -e "Allowed targets: ${PROMOTION_RULES[$source]}"
            fi
            exit 1
        fi
    fi

    # Show promotion details
    show_promotion_details "$source" "$target" "$promotion_type"

    # Special warning for direct promotion
    if [[ "$source" == "develop" && "$target" == "staging" ]]; then
        echo -e "${YELLOW}⚠️  You're promoting directly from develop to staging${NC}"
        echo "   This bypasses test and uat environments."
        echo "   Ensure you've validated changes thoroughly."
        echo ""
    fi

    # Confirm with user
    read -p "🔄 Proceed with promotion? (y/n): " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo -e "${YELLOW}❌ Promotion cancelled.${NC}"
        exit 0
    fi

    # Execute promotion
    execute_promotion "$source" "$target"

    # Create PR if available
    create_promotion_pr "$source" "$target" "$promotion_type"

    echo ""
    echo -e "${GREEN}🎉 Promotion workflow complete!${NC}"
}

# Run main function with all arguments
main "$@"