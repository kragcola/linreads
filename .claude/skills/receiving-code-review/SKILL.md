---
name: receiving-code-review
description: Use when receiving code review feedback, before implementing suggestions — requires technical verification, not performative agreement
---

# Code Review Reception

**Core principle:** Verify before implementing. Technical correctness over social comfort.

## The Response Pattern

```
1. READ:      Complete feedback without reacting
2. UNDERSTAND: Restate requirement in own words (or ask)
3. VERIFY:    Check against codebase reality
4. EVALUATE:  Technically sound for THIS codebase?
5. RESPOND:   Technical acknowledgment or reasoned pushback
6. IMPLEMENT: One item at a time, test each
```

## Forbidden Responses

❌ "You're absolutely right!"  
❌ "Great point!"  
❌ "Let me implement that now" (before verification)  

✅ Restate the technical requirement  
✅ Ask clarifying questions  
✅ Push back with technical reasoning if wrong  
✅ Just start working (actions > words)

## Unclear Feedback

If any item is unclear — STOP, clarify ALL unclear items before implementing anything.  
Partial understanding = wrong implementation.

## Push Back When

- Suggestion breaks existing functionality
- Reviewer lacks full context
- Violates YAGNI (grep codebase — if unused, say so)
- Conflicts with prior architectural decisions
- Technically incorrect for this stack

## Acknowledging Correct Feedback

✅ "Fixed. [Brief description]"  
✅ Just fix it — the code speaks  
❌ "Thanks for catching that!" (no gratitude expressions — just act)

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Performative agreement | State requirement or just act |
| Blind implementation | Verify against codebase first |
| Batch without testing | One at a time, test each |
| Avoiding pushback | Technical correctness > comfort |
