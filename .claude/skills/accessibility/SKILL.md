---
name: accessibility
description: >
  WCAG 2.1/2.2 AA accessibility for Readflow. Auto-activates when touching: font size controls,
  contrast/color, night mode, screen reader support, keyboard navigation, ARIA, touch targets,
  or any reader UI. Use /accessibility for audits and remediation.
---

# Accessibility — Readflow

Reading is inherently an accessibility domain. Our users include people with low vision, dyslexia, motor impairments, and cognitive differences. WCAG AA is the floor, not the ceiling.

## The Four POUR Principles

**Perceivable** — content must be presentable in ways users can perceive  
**Operable** — UI must be operable by all input methods  
**Understandable** — content and operation must be understandable  
**Robust** — content must be interpretable by assistive technologies  

---

## Reader-specific requirements

### Typography (Perceivable)
- Body text ≥ 16px/sp/fp; user-adjustable via settings (range: 12–32px)
- Line height 1.6–1.8 (not fixed px — use relative units)
- Line width 45–75 chars; never allow overflow that causes horizontal scroll
- Text must reflow at 400% zoom without horizontal scroll (WCAG 1.4.10)
- `font-size` must respond to OS/browser text size preferences

### Color & Contrast (Perceivable)
- Normal text: contrast ≥ 4.5:1 against background (WCAG 1.4.3)
- Large text (≥18pt or ≥14pt bold): ≥ 3:1
- Night mode: `#E8E6E1` on `#1A1A1A` = ~7.2:1 ✅
- Day mode: `#2C2C2C` on `#FAFAF8` = ~14:1 ✅
- Never convey information by color alone (e.g., bookmark status needs icon + color)
- Support `prefers-color-scheme` (Web) / system dark mode (Android/HarmonyOS)

### Touch Targets (Operable)
- Minimum 44×44px (WCAG 2.5.5 AA); 48×48dp recommended for Android
- Page-turn tap zones: adequate padding around edges
- Progress bar scrubber: thumb ≥ 44px, track clickable area expanded beyond visual size

### Keyboard Navigation (Operable — Web)
- Page forward/back: `→` / `←` or `Space` / `Shift+Space`
- Go to library: `Escape`
- Open settings: `S`
- All interactive elements reachable by `Tab`; focus ring visible (not `outline: none`)
- No keyboard trap in modals (close via `Escape`)

### Screen Reader (Robust)
- **Android TalkBack**: `contentDescription` on all image/icon views; reading text exposed via `AccessibilityNodeInfo`
- **HarmonyOS ScreenReader**: `accessibilityDescription` on ArkUI components
- **Web VoiceOver/NVDA**: semantic HTML (`<article>`, `<nav>`, `<button>` not `<div>`); ARIA roles where HTML semantics insufficient
- Book title in page header: `aria-label="第3章 第12页 共245页"` (announce chapter + progress)
- Page-turn buttons: `aria-label="上一页"` / `aria-label="下一页"` (not just icons)

### Cognitive (Understandable)
- Reading progress shown as both percentage AND page numbers
- Settings changes take effect immediately with visual confirmation
- Error messages: specific cause + actionable suggestion (not "连接失败")

---

## Audit checklist

Run before declaring any UI feature complete:

- [ ] Contrast ≥ 4.5:1 for all text (verify with browser devtools or contrast checker)
- [ ] Touch targets ≥ 44px on all interactive elements
- [ ] Tab order logical (Web); TalkBack order correct (Android)
- [ ] All images/icons have text alternatives
- [ ] Font size respects OS/browser preference
- [ ] Dark mode renders correctly (no pure-black bg, no pure-white text)
- [ ] No `outline: none` without replacement focus indicator (Web)
- [ ] `prefers-reduced-motion`: disable/reduce page-turn animations when set

## Quick fixes

```css
/* Web — visible focus ring */
:focus-visible { outline: 2px solid #4A90E2; outline-offset: 2px; }

/* Web — respect reduced motion */
@media (prefers-reduced-motion: reduce) {
  .page-turn { transition: none; animation: none; }
}

/* Web — minimum touch target */
.reader-control { min-width: 44px; min-height: 44px; }
```

```kotlin
// Android — content description
IconButton(onClick = ::nextPage) {
    Icon(Icons.Default.ArrowForward, contentDescription = "下一页")
}
```
