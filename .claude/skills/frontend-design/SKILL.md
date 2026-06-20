---
name: frontend-design
description: Guidance for distinctive, intentional visual design when building new UI or reshaping an existing one. Helps with aesthetic direction, typography, and making choices that don't read as templated defaults.
license: Sourced from anthropics/claude-code plugins/frontend-design (2026-06-19).
---

# Frontend Design

Approach this as the design lead at a small studio known for giving every client a visual identity that could not be mistaken for anyone else's. This client has already rejected proposals that felt templated, and is paying for a distinctive point of view: make deliberate, opinionated choices about palette, typography, and layout that are specific to this brief, and take one real aesthetic risk you can justify.

## Ground it in the subject

If the brief does not pin down what the product or subject is, pin it yourself before designing: name one concrete subject, its audience, and the page's single job, and state your choice. Use any memory of the human's preferences or context as a hint. The subject's own world — its materials, instruments, artifacts, and vernacular — is where distinctive choices come from. Build with the brief's real content and subject matter throughout.

## Design principles

For web designs, the hero is a thesis. Open with the most characteristic thing in the subject's world, in whatever form makes sense. Be deliberate: a big number with a small label + gradient accent is the template answer; only use if truly best.

Typography carries the personality of the page. Pair display and body faces deliberately — not the same families you'd reach for on any other project — and set a clear type scale with intentional weights, widths, spacing. Make the type treatment a memorable part of the design, not a neutral delivery vehicle.

Structure is information. Structural devices (numbering, eyebrows, dividers, labels) should encode something true about the content, not decorate it. Numbered markers (01/02/03) are only appropriate if the content actually is a sequence.

Leverage motion deliberately. An orchestrated moment usually lands harder than scattered effects. Sometimes less is more — extra animation contributes to the AI-generated feeling.

Match complexity to the vision. Maximalist directions need elaborate execution; minimal directions need precision in spacing, type, detail. Elegance is executing the chosen vision well.

Consider written content carefully. Copy can make a design feel as templated as the design itself.

## Process: brainstorm, explore, plan, critique, build, critique again

For calibration: AI-generated design right now clusters around three looks:
1. a warm cream background (near #F4F1EA) with a high-contrast serif display and a terracotta accent;
2. a near-black background with a single bright acid-green or vermilion accent;
3. a broadsheet-style layout with hairline rules, zero border-radius, and dense newspaper-like columns.

All three are legitimate for some briefs, but they are defaults rather than choices, and appear regardless of subject. Where the brief pins a direction, follow it exactly. Where it leaves an axis free, don't spend that freedom on one of these defaults.

Work in two passes. First, brainstorm a short design plan: a compact token system with color, type, layout, signature.
- Color: 4–6 named hex values.
- Type: typefaces for 2+ roles (a characterful display face used with restraint, a complementary body face, a utility face for captions/data if needed).
- Layout: a layout concept via one-sentence prose + ASCII wireframes.
- Signature: the single unique element this page will be remembered by.

Then review the plan against the brief before building: if any part reads like the generic default you'd produce for any similar page, revise it and say what you changed and why. Only after confirming relative uniqueness, write code following the revised plan exactly.

Watch CSS selector specificity — type-based (.section) and element-based (.cta) selectors can cancel each other out, especially paddings/margins between sections.

## Restraint and self-critique

Spend your boldness in one place. Let the signature element be the one memorable thing; keep everything around it quiet and disciplined; cut any decoration that doesn't serve the brief. Build to a quality floor without announcing it: responsive to mobile, visible keyboard focus, reduced motion respected. Critique your own work as you build. Chanel's advice: before leaving the house, remove one accessory.

## More on writing in design

Words appear to make a design easier to understand and use — design material, not decoration. Write from the end user's side of the screen: name things by what people control and recognize, not how the system is built. Active voice as default ("Save changes," not "Submit"). An action keeps the same name through the whole flow. Treat failure and emptiness as moments for direction, not mood — an empty screen is an invitation to act. Keep the register conversational, sentence case, no filler, tone matched to brand and audience. Let each element do exactly one job.
