# EPUB Paged Scroll/Curl Investigation

> Date: 2026-07-01
> Scope: Android `:render:epub` paged mode, temporary scroll, slide/curl page-turn preview
> Status: investigation only. No product code changed in this pass.

## Problem

When the reader is in the middle of text, pressing/dragging to turn a page can show a page different from the page visible while the finger is not touching the screen.

The expected behavior is:

- In paged mode, a plain finger down must not change the real content layer.
- Temporary vertical scroll must exist only after the explicit "down then up" gesture.
- Once a page-turn gesture is triggered, temporary scroll must end immediately.
- Before the page-turn preview/curl starts, the reader must snap back to the nearest canonical paged anchor and then turn from that snapped page.

## Current LinReads Root Cause

The issue is not only a bitmap/curl drawing bug. The current implementation mixes four states that should be isolated:

- live `scrollY`
- `currentPage`
- temporary free-scroll position
- slide/curl front/revealed bitmaps

Relevant code:

- `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubFlowView.kt`
- `goToAdjacentPage()`
- `goToPageAnimated()`
- `beginInteractiveCurl()`
- `beginGlInteractiveCurl()`
- `snapToNearestLineTop()`
- `preCachePageTextures()`

Observed state flow:

1. `PAGED` mode allows middle-column vertical drag to enter `freeScrolling`.
2. `freeScrolling` directly changes `scrollY` and disables `pageClipActive`.
3. On release, `snapToNearestLineTop()` snaps to a line top, not necessarily to a canonical page anchor.
4. A later turn re-anchors `currentPage` from `paged.indexOfLast { it.topPx <= scrollY }`.
5. `beginInteractiveCurl()` snaps/snapshots the outgoing page, then silently parks real content on the target page beneath the overlay.
6. GL curl may use `cachedFrontBitmap` / `cachedRevealedBitmap` keyed by `currentPage`, but `currentPage` can be stale relative to the actually visible `scrollY` after temporary scroll.

Failure shape:

```text
visible live scrollY
  != currentPage
  != cached front/revealed bitmap pair
  != real content layer after target parking
```

That mismatch explains why the page under the finger can differ from the resting page.

## MoonReader Reference

MoonReader was checked from local sources and a fresh JADX pass with `--show-bad-code`:

- Local original: `moonreader-decompiled/sources/com/flyersoft/moonreaderp/ActivityTxt.java`
- Focused JADX output: `/tmp/moonreader-jadx-focus/sources/com/flyersoft/moonreaderp/ActivityTxt.java`

Important reference points:

- `ActivityTxt` is the main reader activity.
- `MRTextView` is the text layout surface.
- `ScrollView2` is the scroll container.
- `NewCurl3D` / `FlipImageView` are page-turn animation layers.

Concrete methods:

- `handleCurlMessage(Message)` at focused JADX line around `8066`
- `pageScroll(int, boolean, boolean)` around `11612`
- `getNextPageLine2(...)` and page line selection around `11739`
- `onTouch(View, MotionEvent)` around `20955`
- `getLineTopForPageTurn(int)` in original decompiled source around `6735`
- `txtScrollTo(int)` in original decompiled source around `14505`

Key MoonReader behavior:

1. It treats `ScrollView.scrollY + Layout line` as the primary reading anchor.
2. Page-down/page-up computes the next page from the current visible line, not from an independent page index.
3. Page turns normalize through `getLineTopForPageTurn(line)` and `txtScrollTo(y)`.
4. Curl touch DOWN prepares/locks the page-turn image path; MOVE/UP drives the already locked image set.
5. Cache creation saves the current scroll position and even samples the current/next line text, then verifies/restores if reload changed the visible anchor.

MoonReader's practical lesson:

```text
scrollY -> layout line -> canonical line top -> page turn image
```

not:

```text
currentPage + live scrollY + cached bitmap keys all competing
```

## Required Target State Machine

The LinReads paged reader should use an explicit state machine:

### `PAGED_LOCKED`

Normal paged reading.

- Real `scrollY` must be parked on a canonical page anchor.
- `currentPage`, visible page, progress anchor, and cached page textures must describe the same anchor.
- `ACTION_DOWN` must not move the real content layer.

### `TEMP_SCROLL_ARMED`

Entered only by the explicit temporary-scroll gesture: down then up.

- Ordinary center-column vertical drift is not enough.
- This is only a gate for entering temporary scroll.

### `TEMP_SCROLLING`

Temporary vertical scroll is active.

- `scrollY` may move.
- Page texture cache must be considered invalid or suspended.
- Page turn preview must not start directly from this state.

### `SNAP_PENDING`

Triggered immediately when a page-turn gesture starts from temporary scroll.

- Compute the nearest canonical paged anchor from live `scrollY`.
- Snap the real content layer to that anchor.
- Update `currentPage`.
- Rebuild or validate front/revealed textures from that same anchor.

### `TURNING`

Slide/curl is active.

- Real content may only be at from-anchor or target-anchor.
- Reflow, selection auto-scroll, temporary scroll, and progress callbacks must not mutate visible geometry mid-turn.
- On settle, only the committed final anchor reports progress.

## Implementation Constraints For Follow-up

Do not fix this by adding another snapshot fallback. The stable fix must make these invariants true:

- There is exactly one canonical paged anchor before a turn begins.
- `currentPage` is derived from that anchor, not from stale state.
- Front/revealed textures are keyed by canonical anchor, not only by page index.
- Any live `scrollY` that is not parked on a canonical anchor invalidates cached turn textures.
- Temporary scroll cannot survive into page-turn preview.
- A cancelled turn restores the from-anchor, not an arbitrary pre-snap `scrollY`.

## Suggested Regression Cases

Add tests or runtime smokes for:

1. Open EPUB in paged mode, go to mid-text, no finger: visible marker A.
2. Press down without crossing gesture threshold: still marker A.
3. Enter temporary scroll via down-then-up gesture, scroll to marker B.
4. Trigger next-page turn: first frame/curl front page must match snapped marker B page, not stale marker A or target marker C.
5. Cancel the turn below commit threshold: return to snapped marker B page.
6. Commit the turn: land on exactly next page from snapped marker B page.
7. Repeat with GL simulation, slide, and no animation.
8. Repeat after async image reflow changes layout height.

## Follow-up: MoonReader Gesture Correction

2026-07-01 follow-up rechecked the MoonReader touch path. This supersedes the earlier shorthand that
treated its temporary scroll trigger as a broad center column.

Source checkpoints:

- Focused JADX: `/tmp/moonreader-jadx-focus/sources/com/flyersoft/moonreaderp/ActivityTxt.java`
- `doOnCurlTouch(...)` around lines `4481-4588`
- `isFlipSmallMiddleTap(...)` around lines `9525-9539`
- `isMiddleTap(...)` around lines `9761-9788`
- `getValueShot(true)` around lines `7911-7936`
- `pageScroll(int, boolean, boolean)` around lines `11612-11758`

Corrected MoonReader behavior:

- `isMiddleTap` is the center `1/3 x 1/3` rectangle. DOWN there disables curl.
- `isFlipSmallMiddleTap` is a smaller center `1/5 x 1/5` rectangle.
- In the center `1/3 x 1/3`, MOVE returns `!isFlipSmallMiddleTap(...)` when it is not a horizontal
  page turn. That means only the small center `1/5 x 1/5` is allowed to pass through to native
  `ScrollView` movement; the surrounding center ring consumes the event and does not scroll.
- MoonReader does not appear to force a release-time snap-back in this path. Its important invariant is
  later: before value/curl page imagery is produced, `getValueShot(true)` calls `pageScroll(...)`, which
  normalizes through `getNextPageLine2(...)`, `getLineTopForPageTurn(...)`, and `txtScrollTo(...)`.

Practical takeaway:

```text
temporary scroll may rest freely
  -> page turn starts
  -> normalize scrollY to a canonical line/page anchor
  -> capture/drive page-turn image
```

The transferable lesson is not the exact `1/5` number alone. It is the combination of a conservative
temporary-scroll hit target plus a hard "normalize before snapshot/curl" gate.

## Follow-up: LinReads Fix Evidence

After this investigation, `EpubFlowView` was updated so page turns snap to a canonical paged anchor
before calculating the target page or creating/reusing turn textures:

- `goToAdjacentPage(...)` calls `snapToNearestCanonicalPageAnchor(...)`.
- `beginInteractiveCurl(...)` and `beginGlInteractiveCurl(...)` call the same anchor snap before
  starting preview/curl.
- Cached front/revealed textures are keyed by both page index and page top (`cachedFromTopPx`,
  `cachedTargetTopPx`).
- `preCachePageTextures()` clears/skips cache when live `scrollY` is not parked at the canonical top.

Validation recorded on `emulator-5554`:

```bash
./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest
./gradlew -Preadflow.phase=2 :app:assembleDebug
./gradlew -Preadflow.phase=2 :app:compileDebugAndroidTestKotlin
./gradlew -Preadflow.phase=2 :app:installDebug :app:installDebugAndroidTest
adb -s emulator-5554 shell am instrument -w \
  -e class dev.readflow.page05.EpubFlowAnchorRuntimeSmokeTest \
  dev.readflow.test/androidx.test.runner.AndroidJUnitRunner
```

The targeted AVD smoke now passes as `OK (6 tests)`, covering canonical anchor normalization,
MoonReader-style center zones, MoonReader-style gesture thresholds, edge swipe page turn, long-press drag no page flip, rapid real-overlay GL no double-advance, and real `EpubCurlOverlay` creation/discrete GL
commit in SIMULATION mode. A clean-window logcat grep found no crash/ANR/OOM signatures.

The real-overlay smoke first failed because a lazy-created overlay could be asked to animate before it
had layout/GL page rects. The fix makes `CurlView.animatePageTurn(...)` report whether the animation
actually started, and `EpubCurlOverlay` retries the pending discrete turn on following frames until it
starts or the safety timeout cancels it. This is AVD runtime evidence, not physical-device finger/GL
visual proof.

The gesture-threshold follow-up covers the current LinReads `20dp` intent / `40dp` cross-axis gate:
under-threshold horizontal and vertical drags do not turn, classification-time over-40dp cross-axis
drift does not turn, and a clear side-column vertical swipe lands on the next canonical page top.

## Future Audit: MoonReader Feel Model

Do a separate deep audit of MoonReader hand-feel patterns before changing LinReads gesture ergonomics
again. Scope should include:

- tap zones and dead zones: edge turn zones, center menu zone, center `1/5 x 1/5` temporary-scroll gate,
  and what the surrounding center ring consumes.
- gesture classification thresholds: horizontal-vs-vertical dominance, cross-axis tolerance, fling
  thresholds, long-press/selection conflict handling, and repeated-tap debouncing.
- turn lifecycle: when it captures bitmaps, when it locks images, when it calls `pageScroll(...)`, how it
  restores/cancels, and how cache reload verifies visible anchors.
- scroll feel: whether LinReads should keep the current release-time line snap or switch closer to
  MoonReader's "rest freely, normalize only before turn" model.
- settings surface: whether the temporary-scroll hit area and page-turn zone split should be user
  configurable or simply tuned to safer defaults.

Candidate LinReads follow-up after audit: narrow PAGED temporary-scroll trigger from the current broad
middle column to a smaller center box, keep the canonical anchor snap before every page turn, and add
AVD/runtime tests for dead-zone consumption and edge/curl behavior.

## Current Status

This document now records the original root-cause investigation, the corrected MoonReader gesture
mechanism, the LinReads fix evidence, and a follow-up audit target for MoonReader hand-feel borrowing.
