---
name: requesting-code-review
description: Use when completing tasks, implementing major features, or before merging to verify work meets requirements
---

# Requesting Code Review

**Core principle:** Review early, review often. Catch issues before they cascade.

## When to Request

**Mandatory:**
- After completing a major feature (any of the three platforms)
- Before merging to main
- After fixing a complex bug

**Optional but valuable:**
- When stuck (fresh perspective)
- After touching shared contracts in `shared/`

## How to Request

```bash
BASE_SHA=$(git rev-parse HEAD~1)   # or origin/main
HEAD_SHA=$(git rev-parse HEAD)
```

Dispatch a `general-purpose` subagent with:
- `DESCRIPTION`: What you built (e.g., "EPUB reader page-turn animation, Android")
- `PLAN_OR_REQUIREMENTS`: Acceptance criteria
- `BASE_SHA` / `HEAD_SHA`: Commit range

## Act on Feedback

- **Critical**: Fix immediately before continuing
- **Important**: Fix before proceeding to next feature
- **Minor**: Note for later, don't block

Push back with technical reasoning if reviewer is wrong.

## Never

- Skip review because "it's simple"
- Ignore Critical issues
- Proceed with unfixed Important issues
