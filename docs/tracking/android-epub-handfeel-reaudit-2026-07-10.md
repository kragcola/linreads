# Android EPUB Handfeel Re-Audit - 2026-07-10

## Objective

Re-audit and improve the Android EPUB reader handfeel after several earlier
changes passed JVM/AVD checks without meeting the user's visible expectation.
The acceptance boundary is the rendered frame sequence around touch and mode
transitions, not only settled state, internal fields, or idle screenshots.

Physical-device verification is intentionally deferred by the user for this
iteration.

## Scope

- `android/render/epub`: EPUB flow layout, touch routing, viewport capture,
  conversion cover, slide, software curl, and GL texture ownership.
- `android/render/animate`: reader host and transition lifecycle where it owns
  a visible frame or page request.
- `android/features/reader`: open/restore/configuration ordering only where it
  can change the visible EPUB frame sequence.
- JVM and emulator-level visual evidence needed to prevent another false green.

Web and HarmonyOS behavior are not changed by this Android-specific audit.

## Recovered State

- Branch `main` is at `79ad83d9b9e963c0771ea093d49ce353a7cb9087`,
  matching `origin/main` when this audit started.
- The previous `dev-latest` OTA was already published and verified as Dev build
  `#198`; this task starts after that completed release.
- Existing root-level game/server files and `android/core/model/bin/` are
  unrelated untracked content and must remain excluded.
- Earlier evidence covers many state transitions and idle outcomes, but the
  project record repeatedly states that it is JVM/AVD evidence rather than
  physical handfeel evidence.

## Root-Cause Gate

No production fix may be made until the audit establishes:

1. the exact visible frame sequence before `ACTION_DOWN`, during the first
   interactive frame, and through settle/cancel;
2. which layer owns each frame: live content, frozen conversion cover, slide
   page shot, software curl, or GL texture;
3. a failing behavioral assertion based on rendered pixels or an equivalent
   externally visible contract;
4. whether the current multi-owner architecture is itself the root cause,
   rather than another isolated cache/alpha/timing defect.

## Working Hypotheses

- H1: previous tests asserted state and selected bitmaps but did not prove that
  the actual composited frame at touch time matched the immediately preceding
  frame.
- H2: live content, conversion cover, and page-turn overlays can each be valid
  independently while ownership transfer between them still produces a jump.
- H3: capture may include or exclude overlays differently from the on-screen
  composition, so a "correct" page shot can still differ from what the user saw.
- H4 (opposite hypothesis): the visible discontinuity is not a bitmap/cache bug;
  it comes from gesture thresholds, latency, or animation geometry even when
  every ownership transfer is pixel-identical.
- H5 (architecture challenge): separate live/frozen/slide/GL paths make visual
  continuity unverifiable by construction; a single authoritative composited
  outgoing frame may be required.

## Audit Findings

The root-cause gate is now satisfied. The earlier July 1 investigation already
identified competing `scrollY`, page-index, gesture, and bitmap state and called
for an explicit paged interaction FSM. The implementation instead accumulated
parallel booleans and async callbacks across dozens of follow-up commits. The
old assumption that individually correct fields imply a continuous visible
frame sequence is false.

Confirmed implementation defects:

1. The first MOVE that crosses the gesture threshold discards the displacement
   from DOWN to that MOVE. The deeper Android-runtime cause is `ViewGroup`
   interception: the crossing MOVE cancels the former child but is not replayed
   to the parent's `onTouchEvent()`. Tests that called `onTouchEvent()` directly
   hid this. The crossing MOVE must be applied before `onInterceptTouchEvent()`
   returns `true`.
2. A finger-tracked software slide is not included in `turnInFlight`, allowing
   reflow, pre-cache, or another navigation request to mutate geometry/textures
   while the finger owns the turn.
3. A prepared cross-chapter turn stores the outgoing bitmap but does not keep it
   visibly installed while the target chapter is hidden and awaiting layout or
   image decode. The reachable state is pending turn + alpha-zero live content
   + no visible outgoing owner.
4. The image scheduler is posted after the initial settle runnable. The first
   stability check can therefore observe no pending decodes before images have
   even been attached, reveal early, and pre-cache transparent placeholders.
   Image completion also fails to invalidate same-geometry cached textures.
5. Snapshot clipping backs off an oversized image line using `line > 0`, while
   live clipping uses `line > firstLine`; a non-first-page oversized image can
   disappear from a turn texture.
6. Conversion reveal fades the cover out while fading live content in. At the
   midpoint, source-over composition has only about 75% combined opacity, so
   text/paper visibly weaken before returning to full strength.
7. Existing runtime frame helpers call `View.draw()` and then manually re-draw
   the same private conversion cover. They do not capture the real window
   composition or slide/curl overlay. First-turn runtime tests inspect animator
   objects before a presented frame exists.

Independent review after the first Window-frame GREEN found unresolved P1
edges that block commit/push:

8. Image decode completion does not wake the reveal/pre-cache gate, so an image
   can finish while the chapter remains hidden until the 800ms safety timeout.
9. A pending cross-chapter turn does not gate a second navigation and is not
   bound to the expected adjacent chapter generation.
10. `EpubCurlOverlay.dismiss()` does not stop harism's internal animation/state;
    a stale observer can settle a later turn.
11. A first turn during conversion-cover fade copies the original opaque cover,
    not the partially faded Window composition the user just saw.
12. The interactive GL exception path does not consistently recycle owned page
    shots or dismiss an overlay that became active before throwing.
13. Committed Window evidence currently proves SLIDE only. GL, boundary-cover,
    and conversion-fade paths still need visible-frame coverage before release.

The architecture decision is resolved as architecture A. Every finger-tracked
`PageFlipStyle.SIMULATION` drag uses the software `PageCurlDrawable`; horizontal
and sidebar-vertical turns track their own axis. Tap/key discrete turns retain
harism GL. Chapter-boundary turns now use the same software curl with a prepared
adjacent-chapter page shot instead of falling back to a post-release discrete turn.
If the software page shot cannot be created, the gesture is rejected without
creating or prewarming a GL overlay. The abandoned interactive-GL, PixelCopy
verifier, and finger-hold watchdog experiments have been deleted.

## Cross-Chapter Continuity Continuation - 2026-07-11/12

The user selected AA for the remaining simulation boundary discontinuity:

- maintain independently owned forward and backward adjacent-chapter preview slots;
- use a hidden `EpubFlowView` with the live typography, Markwon image pipeline, and
  layout-stability gate to capture the target chapter's first/last landing page;
- keep the finger-driven software curl continuous when the preview is ready;
- if the finger reaches `UP` before a cold preview is ready, cancel immediately and
  never auto-turn after release;
- install the real target chapter only after commit, keep the transferred target page
  shot as the continuity owner until the target layout is stable, and publish the new
  locator once at that stable handoff.

Preview jobs, hidden render sessions, bitmap ownership, settings/rotation/mode
invalidation, internal-link navigation, close/dispose, and late image callbacks are
generation/token guarded. Discrete tap/key turns use the same preview transaction and
time out their waiting state after three seconds. Physical-device handfeel remains
deferred by the user; this continuation is validated at JVM/lint/build level only.

## Mature Renderer Source Audit - 2026-07-12

The user explicitly rejected real 3D as a requirement. Source-level comparison, rather
than README claims, found the following mature patterns:

- MoonReader keeps no-animation as the default and exposes both GL curl and lightweight
  horizontal page-strip/cover modes. Its lightweight modes update page positions from
  the same finger delta instead of rotating a rigid full-page slab.
- Current Legado defaults to Cover. Cover keeps the target page fixed, moves the current
  page with the finger, and adds an edge shadow; its optional Simulation renderer is a
  pure Canvas/Bezier/clipPath illusion rather than real 3D.
- FBReaderJ defaults to Slide with Cover semantics. Its Curl renderer is also a 2D
  Bezier/clipPath illusion and reuses two page bitmaps by swapping ownership.
- CoolReader defaults to `PAGE_ANIMATION_SLIDE2`. Its optional
  `PAGE_ANIMATION_PAPER` is the closest proven shortcut for LinReads: roughly 70% of
  the page remains flat, only the edge region (capped at 30%) is compressed as narrow
  strips using precomputed sine/arc-sine/source/destination tables, and cached highlight
  or shadow gradients create the paper-flex illusion.
- Readium Kotlin Toolkit and Librera prioritize continuous paging/fling physics and
  adjacent-page preparation rather than paper geometry. Readium's velocity tracking and
  spring-like settle are useful for release dynamics, but not as a curl renderer.

Therefore the previously selected architecture A remains fixed and TextureView/EGL
harism is removed from consideration. The remaining product decision inside architecture
A is A1 Cover (lightest and most direct) versus A2 CoolReader-PAPER-style local strip
bending (keeps a simulation identity without real 3D). The existing rigid whole-page
`Camera.rotateY(0..90)` renderer is not a mature equivalent of either choice and must not
be tuned further as the final implementation. The current split where finger drags use
software while taps/keys use harism GL must also end: whichever A1/A2 renderer is chosen
will own finger drag, tap/key settle, in-chapter turns, and cross-chapter turns. Existing
bidirectional cross-chapter previews remain reusable; in-chapter prewarming must be made
bidirectional so neither forward nor backward first MOVE captures full-screen bitmaps.

## A2 PAPER Implementation - 2026-07-12

The user selected A2. `PageCurlDrawable` now implements CoolReader's three-phase local
PAPER geometry: a bend band capped at 30% of viewport width, approximately 5px
equal-angle strips, 1024-entry sine/arc-sine/source/destination lookup tables, and 16
prebuilt highlight/shade levels. The page body outside the moving band remains a 1:1
bitmap draw. MOVE updates primitive coordinates only; no Bitmap, Rect, Paint, Shader,
Path, Matrix, mesh, or 3D scene is created per frame.

All SIMULATION entry points now share that renderer: finger drag, tap/key discrete turn,
in-chapter, prepared cross-chapter, and adjacent-preview boundary turns. The flow engine
does not create a GL child; the obsolete EpubFlowView GL state machine, first-frame
timeout, conversion owner, and texture-copy seam were removed. Harism source remains
unreferenced for rollback during this no-physical-device iteration.

In-chapter prewarming now owns current + previous + next shots. Once direction is known,
the current and selected neighbour transfer to the drawable without copying and the
unused side is recycled. Discrete turns consume the same warmed slots, silently park the
target under the overlay, publish its locator only after settle, and restore the outgoing
canonical page on cancel. A failed outgoing boundary snapshot returns an already prepared
valid target preview to its slot instead of destroying it and forcing an async rerender.
The final review also closed the 30% bend-boundary flash and duplicate precache queue:
pending precache requests now coalesce, cache invalidation advances a generation so stale
posted work cannot publish old page shots, and failed posts release the pending state.
Phase-2 runtime tests no longer require the removed GL child; discrete/rapid, committed
Window, vertical, cold-first, and direction cases now assert the local PAPER renderer.

## Visual Acceptance Contract

- Capture controlled frames at: stable pre-touch, immediately after down,
  first drag/animation frame, release/cancel, and settled destination.
- Compare the full reader viewport and stable paper-only/content regions; do not
  rely solely on tags, alpha fields, page index, or bitmap object identity.
- A no-move `ACTION_DOWN` must not change visible pixels.
- The outgoing surface used by the first page-turn frame must equal the last
  composited frame visible before the gesture, within a documented renderer
  tolerance.
- Conversion and restored-open checks must inspect intermediate frames before
  idle, including an immediate first turn while a cover is visible.
- The suite must fail against the pre-fix implementation for the selected root
  cause before production code changes.

## Live Plan

- [x] Recover branch, release state, exclusions, and prior evidence boundary.
- [x] Start independent history, state-machine, and test-evidence audits.
- [x] Reconcile audit findings and select evidence-backed root causes.
- [x] Add a RED crossing-MOVE slice and a committed Window SLIDE frame sequence.
- [x] Apply and verify the first-MOVE ownership fix.
- [x] Run focused JVM tests, combined JVM/build gates, and full 28-test AVD pass.
- [x] Keep `PageFlipStyle.NONE` cross-chapter cuts opaque without starting an animator.
- [x] Resolve independent-review P1 lifecycle/coverage findings and apply user-selected
  1A/2A/3A rejection behavior.
- [x] Resolve the warmed interactive GL SurfaceView composition decision with user-selected architecture A.
- [x] Remove the interactive GL, PixelCopy verifier, and hold-watchdog experiment paths.
- [x] Run targeted and full JVM/AVD/build verification for architecture A.
- [x] Complete independent architecture, verification-scope, and runtime-evidence reviews.
- [x] Implement AA bidirectional adjacent-chapter previews and cancel-on-UP cold behavior.
- [x] Run the 365-test EPUB module, lint, debug APK, and local OTA build gates.
- [x] Complete the final independent AA lifecycle/integration review and resolve all P0/P1 findings.
- [x] Re-audit the reported rigid drag and frame hitch against MoonReader and mature open-source readers.
- [x] RED/GREEN forward cache ownership, release-velocity continuation, and steady-state drawable allocations.
- [x] Remove TextureView/EGL harism from the decision set after the user rejected real 3D.
- [x] Obtain the user's architecture-A visual decision: A2 CoolReader-PAPER local strip bending.
- [x] Prewarm both adjacent in-chapter directions; route drag/tap/key/in-chapter/cross-chapter through the selected 2D renderer; implement and verify it.
- [x] Commit, push, and verify the new OTA artifact after the new handfeel slice is complete.

## Risks / Decision Points

- Changing animation style, gesture thresholds, page direction, or the visible
  timing model is a product decision; pause and ask the user if evidence points
  to one of those rather than a correctness defect.
- Emulator pixel evidence can validate composition and ordering but cannot prove
  touch latency, panel response, or physical friction; those remain explicitly
  deferred.
- Do not add another timer-based reveal or cache-specific exception unless the
  frame ownership model proves it is the root cause.
- Architecture A intentionally rejects a finger drag when its software snapshot
  cannot be created. It does not create/prewarm GL on cold-first drag and does not
  fall back to a discrete automatic curl or temporary slide.
- Emulator committed-Window evidence validates frame ownership and continuity,
  but cannot prove touch latency, panel response, or physical friction.
- Non-blocking test-hardening follow-ups: make long-press fixture restoration
  exception-atomic, add a committed-Window MOVE comparison for the vertical case,
  and assert the retained GL frame is closer to the changed page than the old buffer.

## Test Ledger

| Time | Command / Experiment | Actual Result | Conclusion |
| --- | --- | --- | --- |
| 2026-07-10 | `git status --short --branch`; compare `HEAD` and `origin/main` | `main` matches `origin/main` at `79ad83d`; only known unrelated untracked files are present | The audit starts from the published baseline without pending Readflow edits. |
| 2026-07-10 | `:render:epub:testDebugUnitTest --tests EpubFlowViewTest` plus connected first-tap, ACTION_DOWN-paper, and complex-conversion runtime tests | BUILD SUCCESSFUL; 3/3 AVD tests passed on `readflow_test` API 36 | The existing suite remains green on the faulty baseline, confirming it is not a sufficient visible-frame oracle. |
| 2026-07-10 | Compare `UiAutomation.injectInputEvent()` against Android 16 InputDispatcher source; temporarily reset AVD from logical `1600x2560` to native `1080x2400` and rerun the Window test | Both display configurations failed at the same MOVE gate; framework source states injection uses logical display coordinates | The earlier physical-coordinate hypothesis is false; do not hardcode a 1.4815 transform. |
| 2026-07-10 | Direct `EpubFlowView.dispatchTouchEvent()` DOWN then one crossing MOVE; sample FSM | `classified=true`, `stealing=true`, `interactiveTurnState=NONE`, `slideDrawable=null`, DOWN capture took 236ms and `inSelectionMode=false` | Android `ViewGroup` consumed the crossing MOVE during interception; it was neither a routing nor long-press failure. |
| 2026-07-10 | JVM RED `EpubFlowViewTest.first threshold crossing move uses the full down displacement for interactive slide progress` using `onInterceptTouchEvent(MOVE)` | Failed because `slideDrawable` was null | The test now covers the real interception boundary instead of manually bypassing it with `onTouchEvent()`. |
| 2026-07-10 | Apply `applyClassifiedMove(ev)` in the intercepting MOVE, then rerun slide + temporary-scroll targeted JVM tests | BUILD SUCCESSFUL | The first crossing MOVE now applies the full DOWN displacement for both horizontal slide and vertical temporary scroll. |
| 2026-07-10 | `epubFlowSlideWindowFramesTrackFirstMoveAndCancelRuntime` using frame-commit callbacks and `UiAutomation.takeScreenshot(window)` | `OK (1 test)`; stable/down/settled PNGs have identical SHA-256; visual shift estimator: MOVE `880px`, first CANCEL frame `9px`, settled `0px` | Real Window composition proves no ACTION_DOWN change, full first-MOVE tracking, visible CANCEL rollback, and exact settle restoration. |
| 2026-07-10 | `./gradlew --no-daemon -Preadflow.phase=2 :render:epub:testDebugUnitTest :features:reader:testDebugUnitTest :render:animate:testDebugUnitTest :app:assembleDebug` | BUILD SUCCESSFUL | Combined JVM and build regression is green on the dirty audit implementation. |
| 2026-07-10 | Full AVD `EpubFlowAnchorRuntimeSmokeTest`; narrow fatal/OOM/recycled-bitmap/assertion/ANR logcat grep; input-state check | `OK (28 tests)` / `351.124s`; no critical log match; `<no displays touched>` | Current AVD regression is green, including Window SLIDE, GL cancel/direction, conversion, boundary, restore, gestures, links, and accessibility. This does not clear independent-review P1 gaps. |
| 2026-07-10 | Independent read-only review of all dirty Android EPUB changes | P1 findings: image-completion wakeup, pending-boundary navigation/generation, stale GL dismiss callback, fade-time opaque cover copy, GL exception cleanup, and missing committed Window coverage outside SLIDE | Commit/push/OTA remain blocked despite green tests. |
| 2026-07-11 | Resume interrupted full AVD `EpubFlowAnchorRuntimeSmokeTest` session `31570`; inspect `TestRunner` logcat, critical crash/OOM/ANR/recycled-bitmap matches, and `dumpsys input` | Run finished `32 tests, 1 failed, 0 ignored`; the only failure was `epubFlowUiModeChipScrollToPagedKeepsFrozenViewportCoverRuntime` observing conversion-cover `alpha=161` against a timing-sensitive `>=240` assertion. No fatal/OOM/ANR/recycled-bitmap match; `TouchStatesByDisplay: <no displays touched>` | Preserve this as a test-oracle/timing RED until the UI-click sampling contract is reconciled. The other 31 tests, including committed Window boundary, conversion first-turn, GL and retained-buffer cases, passed; the suite is not GREEN. |
| 2026-07-11 | RED/GREEN `EpubFlowViewTest.snapshotting a page recycles its allocated bitmap when drawing fails`; then full `EpubFlowViewTest` and `:render:epub:testDebugUnitTest` | RED captured the real allocated `360x120` target bitmap after a draw exception and found `isRecycled=false`; GREEN recycles the allocation in the `snapshotPageAt()` `Throwable` path. Targeted, class, and module tests passed; `git diff --check` clean | `snapshotPageAt()` now mirrors `snapshotViewport()` ownership: failed drawing cannot leak a full-page bitmap and amplify later OOM risk. This is a deterministic lifecycle fix, not a fallback-policy choice. |
| 2026-07-11 | Replace the retained-GL test's arbitrary post-action commit with an `alpha >= 1` pre-draw gate that registers the commit callback in the same traversal; compile/install and targeted AVD test | `Time: 17.046`, `OK (1 test)`. `changed-live` and `first-active` SHA-256 both `36ecc466...`; pixel `MAE=0`, `max=0`, `bad>8=0`. Negative control `old-live` SHA `2df4125...`; old vs first `MAE=14.11812544`, `bad>8=10.485587%` | The test now proves the first committed traversal with an active (`alpha=1`) warmed SurfaceView shows the new front texture, not an alpha-zero live frame or the retained old GL buffer. Presentation still uses the documented 16ms post-commit grace and is not claimed as panel-present proof. |
| 2026-07-11 | Reconcile the full-suite UI conversion failure: map cover alpha `161` onto the 120ms linear fade, correct the timing-sensitive `>=240` polling assertion, compile/install, and rerun the targeted UI-chip test | Alpha `161` is about 44ms into the intended fade; `>=240` allowed only about 7ms after fade start. The UI-route test now requires the cover to remain visibly contributing (`alpha > 0`) while retaining exact pre-chip cover pixels, parked target, and final cleanup assertions. Targeted AVD: `Time: 14.416`, `OK (1 test)`; no critical log match; no touched display remains | The 31/32 suite failure was a false RED caused by test-thread scheduling, not a product fade regression. The separate committed-Window conversion test remains the visual continuity oracle; product timing was not changed. |
| 2026-07-11 | Add engine integration RED `flow boundary snapshot failure never leaves the target hidden without a visible owner`; force both direct prepare and engine prepare through a throwing page-shot drawable | The test reaches the intended behavioral assertion and fails: `target chapter must not be installed hidden without a continuity owner`. After `prepareBoundaryPageTurn()` returns false, the engine installs chapter two while live alpha is zero and no conversion cover exists | This is a confirmed owner gap, not a hypothetical OOM concern. The assertion accepts either keeping the old live page or an atomic visible target/cover, so it does not preselect the pending fallback product decision. |
| 2026-07-11 | RED/GREEN `flow NONE turn crosses a short chapter boundary with an opaque owner throughout`; run the single engine test before and after removing the animation-only eligibility gate from continuity preparation | RED failed because chapter two was installed with live alpha `0` and no cover. GREEN: `BUILD SUCCESSFUL in 20s`; the target settles at alpha `1`, the cover retires, and `flipAnimator` remains null | `NONE` now uses the outgoing Window-equivalent page shot only as a temporary continuity owner while the target is hidden, then performs an atomic animation-free cut. This normal path does not choose the animated snapshot-failure fallback. |
| 2026-07-11 | Add diagnostic fields to `epubFlowSimulationWindowFramesTrackFirstMoveAndCancelRuntime`; repeat fresh instrumentation under load | Failure at CANCEL reported `overlayActive=false`, `alpha=0`, `mAnimate=false`, `mCurlState=0` before CANCEL was forwarded. A dedicated JVM RED proved the 5s safety callback remained armed during an accepted interactive DOWN | The apparent CANCEL render stall was first caused by the discrete/stalled-turn watchdog dismissing a legitimate finger hold. Accepted DOWN now suspends the watchdog and UP/CANCEL rearms it; targeted watchdog test and 17-test overlay class are GREEN. |
| 2026-07-11 | RED/GREEN first-frame handshake: require a second GL draw after the first renderer callback; stress committed-Window GL MOVE/CANCEL | JVM RED was `expected alpha 0 but was 1` after the first renderer callback; ticket/generation implementation made overlay JVM tests GREEN. Runtime pressure results remained mixed: black MOVE, no observed MOVE, and GREEN | The second draw proves the first `eglSwapBuffers` returned but does not prove SurfaceFlinger latched/composited the buffer. UI-vsync guessing is not an adequate presentation oracle. |
| 2026-07-11 | Gate GL reveal through `PixelCopy` of the most recently queued Surface buffer, then sample 8x8 non-black content and cross another UI frame; rerun committed-Window test | Overlay verifier false/retry/true and stale callback JVM tests GREEN. Runtime still failed with `interactive=GL`, `overlayActive=true`, `alpha=1`, `frameReady=true`, `pendingFrameTicket=false`, `curlState=2`, while the Window was black or pixel-identical to stable | Public Surface readiness and ViewRoot committed Window remain unsynchronized for this `GLSurfaceView` architecture. Three synchronization approaches failed; stop adding delays and obtain a product/architecture decision between software interactive fallback and TextureView/EGL refactor. |
| 2026-07-11 | Apply user-selected architecture A; remove interactive GL touch forwarding/state, PixelCopy verifier, and finger-hold watchdog paths | Finger drags use software curl on horizontal/vertical axes; tap/key turns retain discrete GL; chapter boundaries retain software `startFlip()`; cold-first drag leaves the GL overlay absent before and after the gesture | The committed View hierarchy now owns all finger-tracked frames, eliminating the unresolved `GLSurfaceView` composition boundary without a TextureView/EGL rewrite. |
| 2026-07-11 | Final JVM and module verification | `EpubFlowViewTest` 96/96, `EpubCurlOverlayTest` 14/14, full `:render:epub:testDebugUnitTest` GREEN | Software turn behavior, discrete GL lifecycle, reflow, image loading, and failure ownership are regression-covered. |
| 2026-07-11 | Targeted architecture-A AVD set: horizontal software curl committed Window, vertical software curl, cold-first drag, direction, real discrete GL commit, retained GL surface | `OK (6 tests)` / `80.185s` | Both software axes and retained discrete GL behavior are covered in one runtime gate. |
| 2026-07-11 | Full `EpubFlowAnchorRuntimeSmokeTest`; inspect critical logcat and `dumpsys input` | `OK (36 tests)` / `437.296s`; no fatal/ANR/OOM/recycled-bitmap/assertion match; `TouchStatesByDisplay: <no displays touched>` | The complete Android runtime regression and cleanup checks are GREEN on architecture A. |
| 2026-07-11 | Stress the horizontal committed-Window case and the tightened horizontal+cold-first pair | Horizontal Window 5/5 GREEN; tightened pair `OK (2 tests)` for 3 consecutive rounds | The final Window oracle is stable after isolating the test fixture from long-press selection timing. |
| 2026-07-11 | Diagnose intermittent Window classification failure with a forced post-DOWN delay | Delayed DOWN frame changed 6.1% of pixels and entered TextView selection timing; the two Window tests pass after temporarily disabling host long-press/selectable and restore those flags afterward | The flake was a screenshot-induced test-clock distortion, not a production first-MOVE loss. Independent long-press selection coverage remains active. |
| 2026-07-11 | Final combined Gradle gate: EPUB, Reader, Animate, AndroidTest Kotlin compile, and debug APK assembly | `BUILD SUCCESSFUL` | The production/test changes compile and pass all requested JVM/build gates together. |
| 2026-07-12 | Rerun the three prior failures after correcting the `NONE` test's old synchronous assumption and guarding flow-only internal-link routing with a live flow view | JUnit XML: `tests=3`, `failures=0`, `errors=0`, `skipped=0` | Cold boundary preview remains asynchronous by design; legacy Compose-link tests no longer enter a flow navigation surface that does not exist. |
| 2026-07-12 | `./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest --rerun-tasks --no-daemon`; aggregate all JUnit XML after final review fixes | 25 suites, 365 tests, 0 failures, 0 errors, 2 skipped; `EpubReflowEngineTest` 82/82 | AA gesture, discrete navigation, lifecycle, ownership, stable locator, GL invalidation, and `NONE` UP-cancel coverage are green at the full EPUB module gate. |
| 2026-07-12 | `:features:reader:testDebugUnitTest :render:animate:testDebugUnitTest --rerun-tasks` | Reader: 12 suites/80 tests; Animate: 3 suites/13 tests; 0 failures, 0 errors, 0 skipped | The reader host and retained animation module remain green with the AA EPUB integration. |
| 2026-07-12 | Final independent AA review, targeted RED/GREEN, and fix re-review | Initial review found two confirmed P1s plus one candidate: GL preview invalidation left a paused conversion owner; `NONE` swipe used discrete waiting and could auto-turn after UP; the locator candidate was disproved because animator cancel reaches the existing end/commit branch. Both confirmed REDs turned GREEN; re-review found no remaining P0/P1 | Preview invalidation now follows a unified GL abort path; every boundary drag, including `NONE`, obeys finger-owned UP-cancel semantics. P2 only suggests a table-driven backward/vertical NONE test. |
| 2026-07-12 | `:render:epub:lintDebug :app:assembleDebug :app:assembleOta`; verify ZIP and APK signature | Combined gate successful; lint 0 errors/8 warnings; OTA APK 9.4 MiB, SHA-256 `91244e127dc103e6845f4edb40f95ce83ad002b14d6ce3c49fd629a4b4ab5e8a`; ZIP clean and APK Signature Scheme v2 verifies with the LinReads Debug certificate | The exact debug and CI OTA variants build locally and the final package is structurally/signature valid. No device install was attempted. |
| 2026-07-12 | Trace `ReaderTapContainer -> EpubFlowView FSM -> PageCurlDrawable`; inspect MoonReader `ActivityTxt`/`NewCurl3D`; cross-check fixed Readium/android-PageFlip revisions | SIMULATION drag always uses the rigid software hinge; MoonReader keeps DOWN/MOVE/UP in one real mesh state, prepares page shots before MOVE, and settles from current pointer geometry. The current crossing MOVE also synchronously creates/draws two viewport bitmaps and release velocity is discarded after target selection | “Hard flip” is the selected renderer's mathematics, not a tap-zone/discrete fallback. Shader/interpolator tuning alone cannot turn it into a real curl; a renderer decision is required. |
| 2026-07-12 | Add REDs for warmed interactive cache transfer and velocity-sensitive settle; apply cache ownership, allocation-free drawable state, and Hermite velocity continuation; run full `:render:epub:testDebugUnitTest` | Both REDs failed on the baseline and passed after the implementation. Full JUnit aggregate: 367 tests, 0 failures, 0 errors, 2 skipped; `git diff --check` clean | The no-choice performance slice is regression-covered. Worktree remains uncommitted while the flexible renderer choice and backward prewarm are unresolved. |
| 2026-07-12 | A2 RED/GREEN sequence: discrete SIMULATION must use local renderer; 50% PAPER frame must keep a 1:1 flat body; boundary discrete must use the same renderer and transfer target ownership; backward warm drag and warmed discrete turn must not recapture live pages; discrete target must remain silent until settle | Every new assertion failed on the rigid/GL/forward-only baseline and passed after the local PAPER, unified routing, three-slot cache, and settle-publication changes | A2 is covered at geometry, routing, cache, ownership, and locator-transaction boundaries rather than only by renderer fields. |
| 2026-07-12 | Full `:render:epub:testDebugUnitTest`; aggregate JUnit XML | 366 tests, 0 failures, 0 errors, 2 skipped | Local PAPER, flow engine, boundary previews, ownership, conversion, pagination, images, links, and legacy unused overlay unit coverage are GREEN. |
| 2026-07-12 | `:render:epub:testDebugUnitTest :features:reader:testDebugUnitTest :render:animate:testDebugUnitTest :app:assembleDebug` | BUILD SUCCESSFUL; EPUB 366, Reader 80, Animate 13 | The A2 integration compiles through the reader host and produces the debug APK. |
| 2026-07-12 | `:render:epub:lintDebug :app:compileDebugAndroidTestKotlin --quiet`; `git diff --check` | Exit 0; diff check clean | Android lint and instrumentation source compilation are green. Runtime GL-specific instrumentation assertions still require migration before the next emulator suite; no physical-device handfeel claim is made. |
| 2026-07-12 | Independent A2 review RED/GREEN fixes and production re-review | Closed 3 P1 production findings (30% bend-boundary flash, boundary first-MOVE live capture, preview loss after outgoing capture failure) and 1 P2 duplicate-precache Bitmap leak; final re-review found no P0-P3 | Boundary drag now transfers warmed current ownership, failed outgoing capture restores the prepared target, and pending/generation prevents stale or duplicate page-shot publication. |
| 2026-07-12 | Migrate phase-2 AndroidTest away from removed GL renderer; run final combined phase-2 gate | Deleted pure GL retained-buffer/first-frame cases; discrete/rapid, committed Window, vertical, cold-first, and direction assertions use `PageCurlDrawable` and require no legacy GL child. EPUB 370/0/0/2, Reader 80/80, Animate 13/13; lint, AndroidTest Kotlin compile, and debug APK assembly `BUILD SUCCESSFUL` | Test sources now match A2 production architecture. Emulator/physical-device execution remains deferred; compile and JVM/build evidence do not claim panel-level handfeel. |
| 2026-07-12 | Run migrated A2 AVD gate; investigate one committed-Window CANCEL flake without relaxing pixel limits | Initial 6-test run had 1 visual failure (`rgbMae=2.7974`, bad pixels `6.119%`) after the test captured baseline before changing TextView selectable/long-click render state. Moving those test-only mutations before baseline fixed the oracle; strict committed-Window + cold-first pair passed 3 consecutive rounds (6/6), then the full A2 targeted set passed `OK (6 tests)` / `73.19s`. Narrow fatal/ANR/OOM/recycled-bitmap/assertion logcat grep was empty; `TouchStatesByDisplay: <no displays touched>` | The failure was a test-state mismatch, not accepted as a production regression or hidden by threshold changes. Stable and settled success frames are byte-identical; physical-device handfeel remains deferred. |
| 2026-07-12 | Final local OTA build and package verification before push | `:app:assembleOta` `BUILD SUCCESSFUL` in 2m22s; `app-ota.apk` is 9.4 MiB, SHA-256 `b1e76543fe7f2f4a9f3435dddbc96c5d3deaee1a437b6559c0f391a54ba032cd`; ZIP integrity passes and APK Signature Scheme v2 verifies with `CN=LinReads Debug, O=Dev, C=US` | Local package gate is green. Push to `main` will trigger `Android Dev Release`; remote `dev-latest` identity and asset still require post-push verification. |
| 2026-07-12 | Commit/push A2 and verify `Android Dev Release` run `29173101982` | Commit `4cee1a3e87f2899aabcf5f2ce5c555515b056b23` pushed to `main`; run #202 completed in 5m29s and published `Dev build #202`. Release body, run head SHA, branch, and APK DEX all contain `dev-202-4cee1a3e87f2899aabcf5f2ce5c555515b056b23`. Remote asset is 9,849,169 bytes with SHA-256 `23260e12250746abfb10d8054e208e8df2841aec6f0b85eef8435eda4a478900`, matching the GitHub digest; ZIP, package `dev.readflow`, v2 signature, and trusted certificate digest all verify | The A2 implementation is published through OTA. Physical-device handfeel remains intentionally deferred; package identity and integrity are verified. |

## Verification Status

- Root cause: confirmed for the first crossing MOVE and the interactive `GLSurfaceView` composition boundary.
- Architecture: user-selected A2 implemented; all SIMULATION triggers use one local PAPER renderer and production no longer creates a GL child.
- Removed experiments: interactive GL state/touch forwarding, PixelCopy verifier, and finger-hold watchdog are absent.
- JVM regression: full EPUB 370 tests / 0 failures / 0 errors / 2 skipped; Reader 80/80; Animate 13/13; combined build GREEN.
- Emulator frame sequence: targeted architecture-A 6/6 and full runtime 36/36 GREEN; horizontal Window 5/5 plus tightened horizontal+cold-first 3 consecutive rounds GREEN.
- Runtime hygiene: critical logcat has no matching fatal/ANR/OOM/recycled-bitmap/assertion; no touched display remains.
- AA JVM regression: full EPUB module 365 tests, 0 failures, 0 errors, 2 skipped; Reader 80/80 and Animate 13/13; debug lint/build and local OTA build are green.
- Independent review: final AA lifecycle/integration review and fix re-review found no remaining P0/P1; the only P2 is optional table-driven backward/vertical `NONE` test coverage.
- Physical-device handfeel: deferred by user.
- A2 runtime boundary: AndroidTest sources use local PAPER/no-GL semantics; the six targeted migrated emulator cases pass, including strict committed-Window/cold-first checks after fixing their baseline-order test flake. Physical-device handfeel remains deferred by user.
- Independent A2 review: all 3 production P1 findings and the duplicate-precache P2 are closed; final production re-review found no P0-P3.
- Commit/push/OTA: A2 code commit `4cee1a3e87f2899aabcf5f2ce5c555515b056b23` is pushed to `main` and verified in `Dev build #202`; the mutable `dev-latest` asset identity, digest, ZIP, package, v2 signature, certificate, and embedded build tag all pass.
