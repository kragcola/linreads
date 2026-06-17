---
name: design-audit
description: "Run technical quality checks across accessibility, performance, responsive design, theming, and anti-patterns. Generate a scored report with P0-P3 severity ratings. Report-only — documents issues for other skills to fix. Use when: audit the design, check accessibility, technical review, performance audit, a11y check."
---

# Design Audit

Systematic **technical** quality checks → scored report. Don't fix — document for other skills to address.

## 5 Dimensions (score each 0–4)

### 1. Accessibility
- Contrast ratios < 4.5:1 (normal) / < 3:1 (large text)
- Missing ARIA: interactive elements without roles, labels, states
- Keyboard: missing focus indicators, illogical tab order
- Semantic HTML: heading hierarchy, landmarks, divs-as-buttons
- Forms: inputs without labels, poor error messaging

**Score**: 0=Inaccessible · 1=Major gaps · 2=Partial · 3=WCAG AA mostly · 4=WCAG AA fully

### 2. Performance
- Layout thrashing in scroll/animation loops
- Expensive animations (layout properties, unbounded blur/filter)
- Missing lazy loading for book covers/content
- Unnecessary re-renders in reader view

**Score**: 0=Severe · 1=Major · 2=Partial · 3=Mostly optimized · 4=Fast and lean

### 3. Responsive Design
- Fixed widths breaking on small screens
- Touch targets < 44×44px (critical for reader controls)
- Horizontal scroll on narrow viewports

**Score**: 0=Desktop-only · 1=Major failures · 2=Works with rough edges · 3=Minor issues · 4=Fluid

### 4. Theming
- Hard-coded colors not using CSS variables / theme tokens
- Broken dark/night mode or missing variants
- Pure black (#000000) background (harsh on eyes — use #1A1A1A)

**Score**: 0=No theming · 1=Minimal · 2=Inconsistent · 3=Good with gaps · 4=Full token system

### 5. Anti-Patterns (reader-specific)
- Inter/Roboto for long-form reading body text
- Pure white (#FFFFFF) background (use #FAFAF8)
- No line-height control (must be 1.6–1.8 for reading)
- Generic "AI look" gradients, emoji substituting icons

**Score**: 0=Multiple critical · 1=3-4 tells · 2=1-2 tells · 3=Mostly clean · 4=Intentional

---

## Report Format

```
### Audit Health Score
| Dimension       | Score | Key Finding |
|-----------------|-------|-------------|
| Accessibility   | ?/4   |             |
| Performance     | ?/4   |             |
| Responsive      | ?/4   |             |
| Theming         | ?/4   |             |
| Anti-Patterns   | ?/4   |             |
| **Total**       | ?/20  |             |

Rating: 18-20 Excellent · 14-17 Good · 10-13 Acceptable · 6-9 Poor · 0-5 Critical

### Findings by Severity
[P0] Critical — must fix before shipping
[P1] Important — fix before next release
[P2] Minor — address when convenient
[P3] Polish — nice to have

For each finding: Location · Category · Impact · Recommendation
```
