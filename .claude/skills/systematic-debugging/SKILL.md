---
name: systematic-debugging
description: Use when encountering any bug, test failure, or unexpected behavior, before proposing fixes
---

# Systematic Debugging

**Core principle:** ALWAYS find root cause before attempting fixes. Symptom fixes are failure.

```
NO FIXES WITHOUT ROOT CAUSE INVESTIGATION FIRST
```

## The Four Phases

### Phase 1: Root Cause Investigation (MANDATORY before any fix)

1. Read error messages completely — stack traces, line numbers, error codes
2. Reproduce consistently — can you trigger it reliably?
3. Check recent changes — git diff, new deps, config changes
4. Gather evidence at each component boundary (log what enters/exits each layer)
5. Trace data flow backward — where does the bad value originate?

### Phase 2: Pattern Analysis

- Find working examples in the same codebase
- Compare working vs broken — list every difference, however small
- Read reference implementation COMPLETELY before applying a pattern

### Phase 3: Hypothesis and Testing

- State clearly: "I think X is the root cause because Y"
- Make the SMALLEST possible change to test the hypothesis
- One variable at a time — don't fix multiple things at once
- Didn't work? Form a NEW hypothesis, don't add more fixes on top

### Phase 4: Implementation

1. Create a failing test case first
2. Implement ONE fix at the root cause
3. Verify: test passes, no regressions

**If 3+ fixes have failed:** STOP. Question the architecture — don't attempt fix #4 without discussing with the user.

## Red Flags — STOP and return to Phase 1

| Thought | Reality |
|---------|---------|
| "Quick fix for now, investigate later" | Symptom fixes mask root causes |
| "Just try changing X and see" | Guessing creates new bugs |
| "It's probably X, let me fix that" | Seeing symptoms ≠ understanding root cause |
| "One more fix attempt" (after 2+) | 3+ failures = architectural problem |
| Each fix reveals new problem elsewhere | Wrong architecture, not wrong fix |

## Cross-platform debugging (readflow-specific)

When a bug appears on one platform but not others:
1. First confirm: does it reproduce on ALL three (Android/HarmonyOS/Web)?
2. If Web only: `npm run dev` + browser devtools console
3. If Android only: `adb logcat | grep -i readflow`
4. If HarmonyOS only: DevEco HiLog viewer
5. If all three: likely a shared data/logic bug → check `shared/` contracts first
