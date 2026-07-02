# MoonReader Handfeel Borrowing Backlog

> Date: 2026-07-02
> Scope: Android reader handfeel, especially EPUB/PAGED touch routing, temporary scroll, page turn, and curl/slide lifecycle
> Status: active backlog. The Android EPUB/PAGED P0/P1 touch-routing slice and AVD real-overlay smoke are implemented; remaining rows are still backlog or experiments.

## Source Evidence

This backlog is based on the 2026-07-01 source review recorded in
[epub-paged-scroll-curl-investigation-2026-07-01.md](epub-paged-scroll-curl-investigation-2026-07-01.md).

MoonReader checkpoints already verified:

- `ActivityTxt.doOnCurlTouch(...)`: focused JADX lines around `4481-4588`
- `ActivityTxt.isFlipSmallMiddleTap(...)`: focused JADX lines around `9525-9539`
- `ActivityTxt.isMiddleTap(...)`: focused JADX lines around `9761-9788`
- `ActivityTxt.getValueShot(true)`: focused JADX lines around `7911-7936`
- `ActivityTxt.pageScroll(int, boolean, boolean)`: focused JADX lines around `11612-11758`
- `ActivityTxt.enoughTurnMove(...)`: focused JADX lines around `6302-6304`
- `ActivityTxt.isShiftGesture(...)`: focused JADX lines around `9900-9918`
- `ActivityTxt.MySimpleGesture.onFling(...)`: focused JADX lines around `1901-1990`
- `ActivityTxt.acceptHorizontalFling(...)`: focused JADX lines around `2018-2055`
- `ActivityTxt.do_TapUp_Event(...)`: focused JADX lines around `5412-5468`
- `ActivityTxt.stopLongTap(...)` and `stopTouchEvent(...)`: focused JADX lines around `16307-16420`
- `ActivityTxt.flingPageScroll(...)`: focused JADX lines around `18970-18986`
- `A.clickArea(...)`: focused JADX lines around `2815-2885`; original decompiled source around `1971-2138`
- `A.set_default_Do_Events()` / `A.set_default_flip()`: focused JADX lines around `10346-10376` and `10462-10506`

Current LinReads state:

- Anchor/curl mismatch fix is already implemented in `EpubFlowView`.
- Page turns now snap to a canonical page anchor before target calculation or texture capture/reuse.
- Texture cache now includes page top px, not only page index.
- Cached turn bitmaps are treated as invalid if either bitmap has already been recycled or the cache pair is incomplete; pre-cache refreshes recycled same-key textures, commits a new pair only when both snapshots succeed, and GL turn paths recycle stale/partial cache state before snapshotting live textures.
- Pending pre-cache work is not allowed to commit while a GL/slide turn is active; it skips if `turnInFlight` becomes true before the posted snapshot runnable executes.
- Switching reading modes now recycles cached turn textures before repagination, so PAGED-mode front/revealed bitmaps cannot survive into a different geometry/mode.
- Page-turn snapshots now use the same `TextView.paddingTop`-aware page top/bottom clip as live PAGED rendering, so slide/GL/pre-cache textures do not expose a different half-line boundary from the on-screen page.
- `EpubPagedTouchZones` classifies PAGED touch starts into `PageTurn`, `CenterDead`, and `TemporaryScroll`.
- `EpubFlowView` now consumes the center-ring dead zone and only allows temporary scroll from the inner center box. Horizontal drags that start inside the inner center box are also consumed instead of starting curl/page turn, preserving MoonReader's "all center `1/3 x 1/3` starts are no-curl" invariant. MoonReader's decompiled boundary checks use strict `>` / `<`; LinReads intentionally treats exact center-box borders as part of the safe zones so a coordinate on the `1/5` border remains temporary-scroll and a coordinate on the `1/3` border remains no-curl center-dead instead of flipping.
- AVD `EpubFlowAnchorRuntimeSmokeTest` passed on `emulator-5554` for anchor normalization, MoonReader-style center zones including inner-center horizontal no-curl and temporary-scroll `ACTION_CANCEL` clip restore, MoonReader-style gesture thresholds, edge swipe page turn, right-side clean tap exactly one page, center clean tap toggling chrome exactly once, long-press drag no page flip, right-side link tap priority, rapid GL turn no double-advance, real `EpubCurlOverlay` creation/commit in SIMULATION mode, and reader-surface accessibility/keyboard actions.
- JVM `EpubFlowViewTest` now covers slide and GL host-path cancel/commit around normalized anchors, temporary-scroll `ACTION_CANCEL` re-arming paged clip without a tap-zone action, `ACTION_DOWN` alone no content movement, long-press drag no paged flip, center dead-zone long-press drag no paged flip/scroll, rapid slide/GL discrete turn no double-advance, drag-release no tap-zone after temporary scroll or center dead-zone drag, inner-center horizontal drag no page turn, and `ClickableSpan` link taps winning over paged tap zones; the GL host-path tests use a fake `EpubCurlTurnOverlay`, while AVD covers real overlay creation, discrete commit, rapid GL no double-advance, and right-side `ClickableSpan` taps suppressing paged NEXT zone in the reader route. Physical-device GL hand feel remains a separate audit item.

## Worth Copying Summary

Short answer: copy MoonReader's input arbitration and state invariants, not its decompiled code or every magic number.

| Tier | Worth copying | Status / decision |
| --- | --- | --- |
| Copy / already copied | Center `1/3 x 1/3` no-curl dead zone plus inner `1/5 x 1/5` temporary-scroll box | Implemented for Android EPUB/PAGED and covered by JVM + AVD tests. LinReads uses inclusive borders for the safe boxes; MoonReader source uses strict comparisons. |
| Copy / already copied | Normalize to a canonical page/line anchor before every page-turn snapshot or curl | Implemented; this is the key invariant that fixed texture/content mismatch. |
| Copy / already copied | Texture/cache identity must include the real anchor and a complete alive bitmap pair, not just page index | Implemented with page-top keyed texture cache; pre-cache refreshes recycled same-key textures, only commits complete front/revealed pairs, and stale, partial, or already recycled cache state is discarded before GL turns and live snapshots are used. |
| Copy / already copied | Reading-mode or geometry changes must invalidate turn textures | Implemented: mode changes recycle cached front/revealed bitmaps before repagination, preventing PAGED curl textures from surviving into SCROLL or a rebuilt layout. |
| Copy / already copied | Cache preparation must not race an active turn | Implemented: `preCachePageTextures()` and its posted runnable both skip while `turnInFlight` is true, so pending pre-cache work cannot commit front/revealed textures into an active GL/slide turn. |
| Copy / test-locked | Explicit selection/long-press wins over flip routing | LinReads already gates selection behind `GestureDetector.onLongPress`; JVM and AVD smoke now lock long-press drag in a page-turn zone so it does not trigger paged flip. |
| Copy / test-locked | Inline link taps win over paged tap zones | MoonReader's useful invariant is that interactive reading content must arbitrate before page-turn/chrome taps. LinReads now defers `EpubFlowView` clean-tap handling until child text dispatch finishes, so a `ClickableSpan` tap triggers the link without also firing `PREV/NEXT/MENU`; the child tap-consumed marker is left visible for the outer `ReaderTapContainer`. JVM and AVD reader-route smoke now both cover this path. |
| Copy / test-locked | Clean tap has exactly one owner | MoonReader's tap-up arbitration commits one tap action, not competing child/host actions. LinReads now has AVD reader-route coverage proving a right-side EPUB/PAGED clean tap advances exactly one page, not page two, and a center EPUB/PAGED clean tap opens chrome exactly once without moving the page anchor. |
| Copy / test-locked | Active-turn rapid input gate | MoonReader gates shifted/fling page turns with `pageScrollTime > 500ms` and tap-up after fling with `yFlingTime > 500ms`. LinReads' `turnInFlight` gate already blocks rapid slide and GL double-advance in JVM tests and real-overlay AVD smoke, so do not add a separate `500ms` cooldown unless post-settle physical tests expose a real gap. |
| Copy / test-locked | Drag-release must not become clean tap | MoonReader avoids treating post-fling / moved finger-up as tap via movement and `yFlingTime` gates. LinReads' `classified` gate now has JVM regressions proving temporary-scroll release and center dead-zone release do not trigger MENU/NEXT/PREV tap zones. |
| Copy / test-locked | Cancelled temporary scroll must restore paged clipping | Android can send `ACTION_CANCEL` when a parent/overlay/system steals the gesture. LinReads now treats cancelled PAGED temporary scroll like an aborted read gesture: snap to a clean line top, re-arm `pageClipActive`, report the top offset, clear gesture ownership, and do not fire a tap-zone. |
| Copy as a design option | User-configurable tap maps | MoonReader exposes top/bottom/left/right and 9-grid actions. LinReads should keep simple defaults; expose this only if phone/tablet/low-vision evidence shows one default cannot fit enough users. |
| Experiment only | Release-time no-snap after temporary scroll | MoonReader appears to allow free resting scroll and only normalizes before the next turn. LinReads currently snaps on release; A/B screenshots and transition videos are required before changing. |
| Do not copy blindly | `8dp` curl threshold, dense settings surface, ClickTip-style first-run prompts | These can increase accidental flips or add noise. Keep LinReads' cleaner interaction model unless evidence says otherwise. |

## Current Completion Ledger

This is the current status of "record first, then execute worthwhile borrowing" for the MoonReader EPUB/PAGED hand-feel work.

| Status | Items | Evidence / next gate |
| --- | --- | --- |
| Executed and test-locked | `ACTION_DOWN` alone no content movement, center `1/3 x 1/3` dead zone, inclusive safe borders for `1/3` and `1/5` center boxes, inner `1/5 x 1/5` temporary scroll, temporary-scroll cancel restores paged clip without tap-zone, inner-center horizontal drag no-curl/no-page-turn, center dead-zone long-press no flip/scroll, canonical pre-turn snap, canonical/clamped visual-top keyed texture cache, live/snapshot padding-aware page clip parity, clamped-final-page boundary reporting, complete/alive front/revealed cache pair, mode/reflow cache invalidation, active-turn pre-cache skip, GL pending retry, long-press/selection priority, inline-link priority, stale link-marker cleanup across gestures, clean-tap single owner, active-turn rapid gate, drag-release no-tap, keyboard/accessibility actions. | JVM `EpubPagedTouchZonesTest`, JVM `EpubFlowViewTest`, AVD `EpubFlowAnchorRuntimeSmokeTest`, full `:render:epub:testDebugUnitTest`, `:app:assembleDebug`, and recent AVD logcat grep are recorded below. |
| Recorded as future design option | Tap layout customization, screen-size-aware temporary-scroll hit box, and small settings preview for tap zones. | Requires phone/tablet/low-vision evidence. If implemented, start with 2-3 presets and test link/selection/TalkBack/keyboard/EPUB center temporary-scroll priority. |
| Explicitly not copied now | Full 9-grid actions, dense MoonReader settings surface, first-run ClickTip, exact `8dp` curl threshold, exact `500ms` cooldown, exact `flipSpeed` numbers, global `waitingForCahingShots()` blocking, line-text sampling, bitmap pooling. | Add only with reproduced evidence from the Physical / A-B Audit Protocol, not from source admiration alone. |
| Blocked by physical/A-B evidence | Release-time no-snap, post-settle cooldown, physical harism GL follow/cancel/commit feel, image-heavy EPUB GL cache memory/alias behavior, real phone/tablet tap-zone ergonomics. | Follow the Physical / A-B Audit Protocol. Current local device state is emulator-only, so AVD evidence remains runtime correctness, not hand-feel proof. |

## Borrow First

These are high-confidence ideas worth implementing before larger visual polish.

| Priority | Idea | Why Borrow | LinReads Direction | Proof Needed |
| --- | --- | --- | --- | --- |
| P0 | Conservative middle-zone routing | Prevents accidental curl/flip when the user touches the reading center | Implemented on Android EPUB/PAGED via `EpubPagedTouchZones`; broad middle-column temporary scroll removed; exact center-box borders are treated as safe in LinReads | Unit test and AVD center/edge gesture smoke passed |
| P0 | Normalize before every turn image | Keeps visible text, `currentPage`, and curl textures aligned | Implemented as `snapToNearestCanonicalPageAnchor(...)` before adjacent turn, slide, and GL curl | Anchor, slide cancel/commit, GL host-path cancel/commit, and AVD real-overlay commit tests passed |
| P0 | Cache by anchor, not just page index | Prevents stale bitmap reuse after reflow, free scroll, or clamped short final pages | Implemented with canonical/clamped visual-top keyed cache plus complete/alive bitmap-pair guards in pre-cache and GL turn paths; consider adding visible-line text sampling later only with evidence | Existing anchor/AVD smoke passed; cache lifecycle JVM tests passed; image-heavy physical/runtime smoke remains useful but is no longer blocking the current invariant |
| P1 | Center dead zone | The `1/3 x 1/3` center region can safely absorb ambiguous moves instead of flipping | Implemented: center ring consumes without moving content; only the inner box may temporary-scroll | AVD center-ring smoke passed |
| P1 | Clear cancel restore anchor | Cancelled curl should restore the normalized from-anchor, not arbitrary pre-snap scroll | Slide path and GL host callback path now have JVM regressions for cancel and commit from a mid-scroll anchor; real overlay discrete commit has AVD coverage | JVM slide + GL host-path cancel/commit tests passed; AVD real overlay creation/commit passed; physical-device GL feel still pending |
| P1 | Gesture threshold audit | MoonReader waits for clear intent before turning | Initial parameter table captured below; AVD now covers the current 20dp/40dp gate; keep deep audit open for fling/debounce/selection arbitration | Parameter table seed done; AVD gesture matrix passed |

## Gesture Threshold Seed

This is a source-backed starting table for the later deep audit, not the final tuning decision.

| Gesture topic | MoonReader evidence | LinReads current | Borrowing decision |
| --- | --- | --- | --- |
| Horizontal shift turn | `isShiftGesture(...)`: horizontal distance `> 20dp`, vertical drift `< 40dp`, horizontal distance >= vertical distance, and not the small middle scroll box | `driveFlip(...)`: horizontal distance `> 20dp`, vertical drift `< 40dp`, horizontal distance >= vertical distance | Keep. AVD matrix now proves under-20dp horizontal drag does not turn, over-40dp cross-axis drift does not turn when present at classification, and clear horizontal/edge gestures still work through adjacent smoke. |
| Vertical shift turn | `isShiftGesture(...)`: vertical distance `> 20dp`, horizontal drift `< 40dp`, disabled when down event is in `isMiddleTap(...)` | `driveFlip(...)`: vertical distance `> 20dp`, horizontal drift `< 40dp`; center dead/temporary-scroll zones are filtered before `driveFlip(...)` | Keep. AVD matrix now proves under-20dp vertical drag does not turn and clear side-column vertical swipe turns one page to the next canonical top. |
| Curl enough move | `enoughTurnMove(...)`: `8dp` normally, `20dp` for opposite-touch movement | LinReads does not use this lower curl-specific threshold; it uses the stricter `20dp/40dp` turn gate for EPUB paged drag start | Do not copy blindly. Lower thresholds may feel more eager but risk accidental flips. |
| Clean tap tolerance | `doOnCurlTouch(...)`: menu/tap branch checks movement under `12px` before treating center area as a clean tap/menu action | LinReads delegates clean tap vs drag to `GestureDetector` plus `touchSlop`; page/menu tap zones remain width thirds | Audit later if tap jitter feels wrong on real devices. |
| Release commit | MoonReader curl/value paths settle inside their animation handlers after page image preparation and `pageScroll(...)` normalization | LinReads interactive slide commits when progress >= `0.5` or fling velocity exceeds `700dp/s`; cancel restores `curlFromPage` | Keep for now; new JVM tests pin cancel and commit around normalized anchors. |

## Deep Audit Round 1: Gesture Arbitration

This pass focused on ideas that affect perceived hand feel without requiring a broad animation rewrite.

| Area | MoonReader behavior | LinReads decision |
| --- | --- | --- |
| Fling debounce | `flingPageScroll(...)` ignores another page scroll until `pageScrollTime` is older than `500ms`; `isShiftGesture(...)` has the same `500ms` gate. | Copy the invariant, not the number: active turns must reject rapid second turns. LinReads now has JVM slide/GL no-double-advance regressions plus AVD real-overlay rapid GL smoke. A separate post-turn cooldown remains deferred until physical taps/flings show post-settle chaining. |
| Vertical scroll after fling | Tap-up path checks `SystemClock.elapsedRealtime() - yFlingTime > 500` before treating small movement as a tap. | Copy the arbitration invariant: a moved drag's UP is not a clean tap. JVM now locks temporary-scroll release and center dead-zone drag release so they do not trigger MENU/NEXT/PREV; true kinetic fling/post-settle tap suppression remains a physical-device audit item. |
| Horizontal fling | `acceptHorizontalFling(...)` requires a strong velocity around `600px/s`, dominance over the cross axis, and cross-axis drift under `40dp`; middle taps are excluded for vertical-shift mode. | Current LinReads `700dp/s` release commit and `20dp/40dp` gesture gate are close enough. Do not tune until physical hand-feel evidence exists. |
| Clean tap | `do_TapUp_Event(...)` drops repeat tap-up within `100ms`; normal tap-up also requires small movement (`10dp` path in `onTouch`, `12px` path in curl/menu branch) and press duration under `800ms`. | Keep using Android `GestureDetector` for now. Add tests if users report accidental chrome toggles after small drags. |
| Clean tap ownership | Tap-up actions are not allowed to double-commit through multiple owners. | AVD now proves right-side EPUB/PAGED clean tap turns exactly one page and center EPUB/PAGED clean tap opens chrome exactly once in the real reader route. Keep this invariant when changing `ReaderTapContainer` or `EpubFlowView` tap handling. |
| Long press / selection | `stopLongTap(...)` cancels long tap when movement exceeds about `10dp` or animation is active; once selection state is active, `stopTouchEvent(...)` consumes movement for text selection. Direct highlight only starts after larger movement (`24dp` horizontal or `16dp` vertical). | Copy the priority order, not the exact UI: selection/link/long-press must win over page turn. JVM and AVD smoke now lock long-press drag so it does not trigger paged flip; `EpubFlowView` link tap priority now has JVM coverage, while runtime/TalkBack link traversal remains in the device audit. |
| Link tap arbitration | MoonReader's touch pipeline separates content interactions from page-turn gestures before committing tap-up actions. | Copy the priority order: link activation wins over paged tap zones. LinReads now test-locks `ClickableSpan` taps in `EpubFlowView` so they do not also trigger `PREV/NEXT/MENU`; AVD also taps a visible link in the right-side NEXT zone and verifies the page does not advance. TalkBack link traversal remains part of PAGE-05 device audit. |
| Tap-map customization | `A.clickArea(...)` supports multiple tap modes and an optional 9-grid; defaults map top/left/volume-up to previous and bottom/right/volume-down to next. | Valuable later, but not a P0. LinReads should first prove its simpler thirds + center chrome model on phone/tablet/low-vision. |
| Animation speed | `flipSpeed` defaults to `30` normally and `20` on Kindle ROMs; MoonReader also changes animation family by settings. | Do not copy numbers. Use LinReads' current slide/GL durations until frame pacing and physical feel are measured. |

## Deep Audit Round 2: Page-Turn Image/Cache Lifecycle

This pass focused on page-turn screenshot/cache safety. It does not justify a broad rewrite.

Additional MoonReader source checkpoints:

- `ActivityTxt.createCachePageShots(...)`: original decompiled source around `3000-3044`
- `ActivityTxt.createCachePageShots2(...)`: original decompiled source around `3057-3096`
- `ActivityTxt.createCachePageShotsHandler(...)`: original decompiled source around `3100-3116`
- `ActivityTxt.get3dCurlShot_step2(...)`: original decompiled source around `6232-6288`
- `ActivityTxt.saveFlipShot(...)`: focused JADX lines around `22378-22430`
- `ActivityTxt.getPageShot(...)`: focused JADX lines around `19203-19239`
- `ActivityTxt.waitingForCahingShots(...)`: original decompiled source around `14807-14816`
- `A.clickArea(...)`: focused JADX lines around `2815-2970`
- `A.set_default_Do_Events()` / `A.set_default_flip()`: focused JADX lines around `10346-10376` and `10462-10506`
- `PrefControl.initGallery(...)`, `PrefControl.loadOptions(...)`, `PrefControl.setNineGridText(...)`, and `PrefControl.setTapZoneEnable(...)`: focused JADX lines around `155-205`, `255-345`, and `704-705`
- `PrefVisual` flip animation/speed wiring: focused JADX lines around `606-618`, `760-772`, and `1180-1188`
- `PrefMisc` edge/multitouch/touch behavior toggles: focused JADX lines around `1238-1245`, `1295-1303`, and `1862-1877`

| Area | MoonReader behavior | LinReads decision |
| --- | --- | --- |
| Screenshot-before-turn anchor | `getValueShot(true)` and curl/value paths call `pageScroll(...)` before page imagery is used, so bitmap capture follows the normalized visible line/page. | Already copied. Keep `snapToNearestCanonicalPageAnchor(...)` before slide/GL curl and never remove it for visual smoothness. |
| Cache identity | MoonReader reuses `tmpFlipShot2` only when `lastFlipScrollY == getFlipScrollY()` and the bitmap is alive; it also stores current/next line text during cache creation to recover after TXT reload shifts layout. | Partly copied. LinReads keys cache by page index plus page top px, rechecks page tops in the async `post` before snapshotting, refreshes pre-cache when same-key bitmaps are recycled, treats incomplete or recycled cached bitmap pairs as stale, and recycles cached turn textures when GL detects top-key mismatch before snapshotting live. Add line-text sampling only if image reflow or same-top/different-text evidence appears; otherwise it adds fragility to styled EPUB pages. |
| Cache-in-progress gate | MoonReader's `waitingForCahingShots()` blocks page turns while page shots are being prepared, with a 2.5s expiry. | Do not copy as a global blocking gate. LinReads pre-cache is opportunistic: if cache is absent or stale, turn paths snapshot live or fall back. Keep active-turn/reflow gates, not a user-visible wait for pre-cache. A pending pre-cache runnable now also skips if a turn becomes active before it executes, so cache preparation cannot commit into an active GL turn. |
| Reflow during turn | MoonReader can restore after a cache-time TXT reload by comparing line text near the cached line. | Addressed differently. LinReads defers async-image reflow while `turnInFlight` is true, recycles stale turn texture cache before rebuilding pagination, then repaginates/re-anchors by content offset. Future useful runtime smoke: image-heavy EPUB reflow after cached textures should still produce front/revealed textures from the new canonical anchors. |
| GL readiness / delayed turn | MoonReader keeps `curlDelayedMsg` and replays it after screenshots are ready. | Already copied in spirit. LinReads' `EpubCurlOverlay` retries a pending discrete GL turn until layout/GL page rects are ready or safety-cancels. |
| Bitmap memory safety | MoonReader aggressively recycles temp shots and uses lower-quality RGB_565 paths where suitable. | Already copied enough for this slice: LinReads recycles cached/overlay bitmaps, refreshes already-recycled cached textures during pre-cache, refuses incomplete or already-recycled cached texture pairs before GL turns, snapshots in RGB_565, catches `Throwable` around bitmap creation, and falls back when snapshots fail. Do not add more pooling until memory profiling says so. |

Round 2 decision: no broad rewrite and no MoonReader-style global blocking gate. The worthwhile borrow is the lifecycle invariant:

```text
turn image cache is valid only for the current normalized visual anchor;
cached bitmaps must still be alive;
front/revealed cache pairs must be complete;
cache preparation must never race reflow or an active turn;
if cache readiness is uncertain, turn paths must fall back rather than show stale texture.
```

Concrete follow-up when real image-heavy EPUB evidence is available:

- Add an image-heavy EPUB runtime smoke where page tops change after pre-cache, then start a GL turn and verify front/revealed textures come from the new canonical anchors.
- Add a physical tablet memory pass for repeated GL curls on image-heavy EPUB before considering bitmap pooling.
- Do not add MoonReader-style line-text sampling unless a same-top/different-content cache alias is reproduced.

## Deep Audit Round 3: Tap Maps and Settings Surface

This pass checks whether MoonReader's configurable tap zones are worth copying now. Decision: record the model, keep LinReads' simple default, and defer any user-facing customization until physical-device evidence shows the default cannot cover enough users.

| Area | MoonReader behavior | LinReads decision |
| --- | --- | --- |
| Tap-map model | `A.clickArea(...)` first checks optional 9-grid overrides (`do91`..`do99`) and then falls back to `tapMode` / `toggleTapMode` shapes. Defaults from `set_default_Do_Events()` are simpler than the full capability: top/left/volume-up map previous, bottom/right/volume-down map next, D-pad center maps menu, long tap maps selection/highlight behavior, and all 9-grid overrides default disabled (`15`). | Borrow the idea as a future design option, not as immediate UI. LinReads currently uses left third previous, middle chrome, right third next plus keyboard/accessibility actions; this is simpler and already test-locked. |
| Settings surface | `PrefControl` exposes a tap-zone gallery, separate top/bottom/left/right action spinners, a 9-grid editor, and a ClickTip-style visual zone preview. `PrefMisc` also exposes edge touch, multitouch, horizontal scroll, disable-move, and tilt-turn toggles. | Do not copy the settings density now. If customization becomes necessary, prefer one small "tap layout" control with 2-3 presets before exposing arbitrary per-cell actions. |
| Flip animation settings | `set_default_flip()` defaults `flipSpeed` to `30` normally and `20` on Kindle ROMs; `PrefVisual` exposes animation family and speed slider. `get3dFlipSpeed()` and value animation derive timings from `flipSpeed`. | Keep LinReads' current durations until physical frame pacing and hand feel say otherwise. Do not import MoonReader speed numbers without device evidence. |
| ClickTip / visual hints | `initClickTip(...)` captures a page screenshot and starts `ClickTip`; `PrefControl` can also open the same preview from control settings. | Do not copy first-run or blocking tips. A small settings preview may be acceptable later, but only inside settings and only if tap-map customization is added. |

Round 3 decision:

```text
copy the existence of configurable tap layouts as a later escape hatch;
do not copy the full MoonReader settings surface;
do not add 9-grid actions, edge-touch toggles, or flip-speed sliders without phone/tablet evidence;
keep accessibility, links, selection, and keyboard actions as non-negotiable owners.
```

Concrete follow-up if physical evidence asks for tap customization:

- Add a tiny domain-level tap-layout model with presets only:
  - `Classic`: left third previous, middle chrome, right third next.
  - `RightHand`: left third previous, right two thirds next, chrome from center tap/toolbar.
  - `CenterSafe`: current EPUB/PAGED center dead-zone behavior preserved.
- Add tests proving link taps, selection long-press, TalkBack actions, keyboard actions, and EPUB center temporary-scroll still win over any tap preset.
- Add the setting only after phone/tablet/low-vision A/B evidence, not as a speculative feature.

## Experiment Before Copying

These may improve feel, but need A/B evidence before becoming defaults.

| Idea | Risk | Experiment |
| --- | --- | --- |
| Release-time no-snap for temporary scroll | Resting page may show half-lines or cross-page seams | Compare current line-top snap vs MoonReader-style free rest; record screenshots and page-turn transition behavior |
| Exact `1/5 x 1/5` temporary-scroll box | Too hard to hit on phones, low-vision typography, or one-handed tablet use | Test screen-size-aware boxes: fixed `1/5`, min-dp floor, and user-configurable sensitivity |
| Edge-only curl start | May make horizontal turns feel less forgiving for users accustomed to center swipes | Compare edge zones, right-two-thirds tap zones, and center-swipe behavior |
| User-configurable touch map | More settings surface can make the reader feel heavier | Only expose if defaults cannot satisfy phone/tablet/low-vision cases |

### Release-Time No-Snap Decision

Decision on 2026-07-02: do not implement or A/B-enable MoonReader-style release-time no-snap yet.

Reasoning:

- Source evidence supports the invariant that MoonReader normalizes before page-turn imagery (`getValueShot(true)` -> `pageScroll(...)`), not that LinReads should immediately let temporary scroll rest freely.
- LinReads already copies the safety-critical part: every slide/GL turn normalizes to a canonical visual anchor before target selection and texture capture.
- Current local device state is emulator-only (`emulator-5554`), so we cannot judge whether no-snap feels smoother or distracting on a real phone/tablet.
- No-snap changes the resting reading surface and can expose half-lines or cross-page seams; that is a visible hand-feel tradeoff, not a pure state-machine fix.

Implementation remains intentionally unchanged: PAGED temporary-scroll release snaps to a clean line top and re-arms clipping. Revisit only through the Release-time no-snap A/B gate below.

## Physical / A-B Audit Protocol

Current local device state on 2026-07-02: `adb devices -l` only reports `emulator-5554` (`sdk_gphone64_arm64`). No physical-phone or physical-tablet hand-feel claim is valid yet.

Run this protocol before changing release-time snap, post-settle cooldown, hit-box size, tap-map customization, or animation speed.

| Gate | Required evidence | Change allowed only if |
| --- | --- | --- |
| Phone hand feel | One physical phone, portrait and landscape, EPUB/PAGED with SLIDE and SIMULATION. Record screen video for edge swipe, edge tap, center tap, inner-center temporary scroll, center-ring drag, long-press selection, and inline link tap. Pull logcat after the run. | Users can reliably hit the intended zones without accidental flips; link/selection/chrome ownership remains single; no crash/OOM/recycled-bitmap signatures. |
| Tablet hand feel | One physical tablet or foldable/tablet-size screen, portrait and landscape. Repeat the phone matrix and add one-handed right-side reading. | Current `1/5 x 1/5` temporary-scroll box is not too small; center ring does not feel like a broken area; edge turns remain discoverable. |
| Release-time no-snap A/B | Same book and page on LinReads current line-snap behavior vs a local experimental no-snap build. Capture resting screenshot after temporary scroll, first frame of the next turn, cancel settle, and commit settle. | No-snap visibly improves continuity and does not leave distracting half-lines, cross-page seams, or progress/texture mismatch. If uncertain, keep current snap. |
| Post-settle rapid input | Physical rapid tap/fling sequence immediately after slide and GL settle. Record whether a second request chains unintentionally. | Add a separate cooldown only if `turnInFlight` is insufficient after settle and the issue reproduces physically. Do not add a speculative `500ms` gate. |
| Image-heavy EPUB GL cache | EPUB with full-page and inline images; repeated SLIDE/SIMULATION turns after image load/reflow. Record video plus logcat and memory snapshot if possible. | Add line-text/content sampling or bitmap pooling only if same-top/different-content alias, stale texture, OOM, or repeated allocation pressure is reproduced. |
| Tap layout customization | Phone, tablet, and low-vision users try current thirds + center dead-zone defaults. Include TalkBack on at least once. | Add presets only if the default demonstrably fails a user group. Start with 2-3 presets, not arbitrary 9-grid actions. |

Evidence bundle for each run:

- Device model, Android version, resolution/density, app build SHA/version.
- Book file name and whether it is normal text, image-heavy, or low-vision typography.
- Video filenames and short observed result.
- `adb logcat -d` filtered for `FATAL EXCEPTION|ANR in dev.readflow|OutOfMemoryError|recycled bitmap|Unable to find reader surface|AssertionError|FAILURES!!!|Process: dev.readflow`.
- Decision: `keep current`, `run another A/B`, or `implement narrow change`.

Capture helper:

```bash
python3 android/test-tools/moonreader_handfeel_capture.py \
  --adb /Volumes/OmubotDisk/Applications/sdk/platform-tools/adb \
  --serial <device-serial> \
  --device-label phone-portrait \
  --gate phone-handfeel \
  --book rf-mr-extreme-20260627.epub \
  --out /tmp/readflow-moonreader-handfeel
```

The helper creates a timestamped evidence directory with `manifest.json`, `NOTES.md`, before/after
screenshots, a short screen recording, `adb devices -l`, device properties, and filtered critical
logcat. Use `--dry-run` to verify the bundle shape without touching adb. This helper does not decide
the outcome; the decision line in `NOTES.md` must still be filled from observed phone/tablet behavior.
It also accepts `--preset phone|tablet|ab` to fill common gate defaults without retyping device
labels. Explicit CLI values still win if provided.

Helper validation on 2026-07-02:

- Unit dry-run: `python3 -m unittest android/test-tools/test_moonreader_handfeel_capture.py` = `OK`.
- Tool-suite regression: `python3 -m unittest discover android/test-tools` = `OK (4 tests)`, covering the hand-feel helper plus the existing extreme-corpus and fake-Calibre helpers.
- ADB smoke on `emulator-5554`: `python3 android/test-tools/moonreader_handfeel_capture.py --adb /Volumes/OmubotDisk/Applications/sdk/platform-tools/adb --serial emulator-5554 --out /tmp/readflow-handfeel-capture-avd --device-label emulator-portrait --gate helper-smoke --book rf-mr-extreme-20260627.epub --record-seconds 2` created `/private/tmp/readflow-handfeel-capture-avd/20260702-085400-emulator-portrait-helper-smoke`.
- The smoke bundle contains `manifest.json`, `NOTES.md`, `adb-devices.txt`, before/after screenshots, `screenrecord.mp4`, and `logcat-critical.txt`; filtered critical logcat was empty. This validates the capture path only and is not physical hand-feel evidence.

## Do Not Copy

These are explicitly not goals.

- Do not copy decompiled implementation code. Borrow behavior and state-machine lessons only.
- Do not copy blocking first-run tips or intrusive ClickTip-style prompts.
- Do not weaken text selection, links, TalkBack, or keyboard actions to match MoonReader quirks.
- Do not remove LinReads' canonical pre-turn snap; it is the safety invariant that fixed the curl mismatch.
- Do not call AVD gesture evidence "real hand feel". Physical phone/tablet validation remains separate.

## Execution Order

1. [x] Add a pure hit-zone classifier for PAGED touch routing.
2. [x] Add RED tests for MoonReader-inspired zones:
   - edge zones can start page turn.
   - center `1/3 x 1/3` disables curl.
   - inner center box can temporary-scroll.
   - center ring consumes ambiguous drag without scrolling/flipping.
3. [x] Wire `EpubFlowView` to the classifier without changing anchor/curl invariants.
4. [x] Run targeted JVM tests and existing EPUB unit tests.
5. [x] Add/extend AVD smoke for real harism GL overlay creation and discrete commit. Center ring, inner temporary scroll, edge turn, JVM slide cancel/commit, JVM GL host-path cancel/commit, and AVD real overlay commit are covered. True physical-device hand feel remains deferred.
6. [x] Seed the gesture threshold audit table from MoonReader and LinReads source, then cover the current 20dp/40dp gate with AVD gesture matrix smoke.
7. [x] Lock long-press/selection priority with JVM and AVD smoke: long-press drag in a page-turn zone must not trigger paged flip.
8. [x] Lock rapid active-turn behavior: rapid discrete slide/GL requests while a turn is active must not double-advance or restart a real GL overlay.
9. [x] Lock drag-release no-tap behavior: temporary-scroll and center dead-zone drag releases must not trigger MENU/NEXT/PREV.
10. [x] Lock inline link priority in `EpubFlowView`: a `ClickableSpan` tap must activate the link without also triggering paged tap zones.
11. [x] Lock center clean tap ownership in the real reader route: one center tap opens chrome once and does not move the page anchor.
12. [x] Audit MoonReader page-turn screenshot/cache lifecycle and record LinReads decisions.
13. [x] Lock recycled-cache behavior: already-recycled cached bitmaps must be ignored before GL turns, then live snapshots should be used.
14. [x] Lock pre-cache liveness behavior: same-key cached textures must be refreshed if either bitmap is already recycled.
15. [x] Lock partial-cache behavior: an incomplete front/revealed cache pair must be treated as stale before GL turns.
16. [x] Lock active-turn pre-cache behavior: a pending pre-cache runnable must not commit front/revealed textures while GL/slide turn ownership is active.
17. [x] Lock inner-center horizontal drag behavior: a drag that starts in the inner center box must not curl/page-turn.
18. [x] Lock `ACTION_DOWN` behavior: a plain press must not move content, flip, or fire tap zones.
19. [x] Lock center dead-zone long-press drag behavior: long-press ownership must still prevent flip/scroll.
20. [x] Decide whether to experiment with release-time no-snap: deferred until the Physical / A-B Audit Protocol produces phone/tablet video evidence. Current implementation keeps release-time line snap.

## Acceptance Gates

Any implementation must prove:

- `ACTION_DOWN` alone does not move real content.
- Center-ring drag does not flip, does not scroll, and does not break selection/long-press.
- Inner center drag can temporary-scroll in PAGED mode.
- Inner center horizontal drag does not curl, page-turn, or move content.
- Starting any turn from a temporary scroll first snaps to one canonical page anchor.
- Front/revealed textures match the snapped anchor and target anchor.
- Pre-cache treats same-key recycled bitmaps as a miss and refreshes live textures.
- Pre-cache never leaves a partial front/revealed pair behind if one snapshot fails.
- Pending pre-cache work does not commit new textures while a GL/slide turn is active.
- Cached turn bitmaps that are incomplete, already recycled, or stale by page/top key are discarded before GL snapshot/turn.
- Switching out of PAGED mode recycles cached turn textures before repagination.
- Cancel restores the snapped from-anchor.
- Commit lands on exactly the adjacent page from the snapped anchor.
- A rapid second turn request while slide/GL is active does not advance to the page after next.
- Releasing a temporary-scroll or center dead-zone drag is never treated as a clean tap.
- A clean right-side tap advances exactly one page in the full reader route.
- A clean center tap toggles chrome exactly once in the full reader route and does not move the page anchor.
- Inline text/link activation is not weakened by paged tap zones; a link tap must not also fire `PREV/NEXT/MENU`.
- Existing keyboard, accessibility scroll actions, and tap-zone page turning still work.

Current evidence, 2026-07-02:

- JVM `EpubPagedTouchZonesTest` covers inner center, center ring, inclusive `1/5` and `1/3` safe-zone boundaries, non-divisible viewport boundary rounding, middle-column non-center, and edge zones.
- JVM `EpubFlowViewTest` covers temporary-scroll-to-page-turn canonical anchor normalization, temporary-scroll `ACTION_CANCEL` re-arming paged clip without tap-zone, interactive slide cancel/commit, GL host-path interactive/discrete cancel/commit from a normalized anchor, `ACTION_DOWN` alone no content movement, long-press drag no paged flip, center dead-zone long-press drag no paged flip/scroll, rapid discrete slide/GL no double-advance while active, drag-release no tap-zone after temporary scroll or center dead-zone drag, inner-center horizontal drag no page turn, and inline `ClickableSpan` taps suppressing paged tap zones.
- AVD `EpubFlowAnchorRuntimeSmokeTest` covers canonical anchor normalization, `ACTION_DOWN` alone no content movement, center-ring no-scroll, inner-center temporary scroll and `ACTION_CANCEL` clip restore, inner-center horizontal no-curl/no-page-turn, inclusive `1/5` temporary-scroll boundary, inclusive `1/3` center-dead boundary, MoonReader-style threshold matrix, edge swipe page turn, right-side clean tap exactly one page, center clean tap toggling chrome exactly once, long-press drag no page flip including center dead-zone long-press drag no scroll/flip, right-side inline link tap no page flip, real `EpubCurlOverlay` creation/discrete GL commit, rapid real-overlay GL no double-advance in SIMULATION mode, and reader-surface accessibility/keyboard actions; the full class passed as `OK (10 tests)`.
- Logcat critical grep after the AVD run returned `NO_CRASH_SIGNATURES`.
- AVD RED/GREEN note: the first real-overlay smoke initially failed because the lazy-created overlay could be asked to animate before layout/GL page rects were ready. `CurlView.animatePageTurn(...)` now reports whether it actually started, and `EpubCurlOverlay` retries the pending discrete turn on later frames until it starts or safety-cancels.
- AVD threshold matrix note: the first cross-axis drift assertion was calibrated after observing that a multi-step drag can legitimately start a turn before later drift exceeds 40dp. The final matrix tests classification-time drift by dispatching that case in one MOVE.
- Reflow cache-invalidation follow-up: `EpubFlowViewTest.async reflow recycles stale turn texture cache before rebuilding pagination` first failed because `reflowRunnable` repaginated/re-anchored while old `cachedFrontBitmap` / `cachedRevealedBitmap` and old top keys remained in memory. `EpubFlowView` now recycles turn texture cache after `turnInFlight` is clear and before rebuilding pagination. Validation: targeted JVM test `SUCCESS`, full `EpubFlowViewTest` `SUCCESS`, and full `:render:epub:testDebugUnitTest` `SUCCESS`.
- GL cache-key mismatch follow-up: `EpubFlowViewTest.discrete gl turn recycles stale cached textures when top keys mismatch` first failed because the GL path correctly fell back to live snapshots but left old cached bitmaps in memory when page index matched and top keys did not. `EpubFlowView` now recycles cached turn textures before GL snapshotting whenever the cached page/top tuple does not match the current from/target anchors; the fake overlay verifies live viewport-sized textures, not the stale 1x1 cache, reach the turn. Validation: targeted JVM test `SUCCESS`, full `EpubFlowViewTest` `SUCCESS`, and full `:render:epub:testDebugUnitTest` `SUCCESS`.
- Clamped final-page texture follow-up: `EpubFlowViewTest.discrete gl turn snapshots clamped final page at canonical visual top` first failed because GL/cache snapshots used raw `pageTopPxAt(target)`, but a short final page is actually displayed at `ScrollView`'s clamped `maxScroll`. `EpubFlowView` now keys and captures GL/pre-cache turn textures by the canonical visual top used by `scrollToPage(...)`, so revealed textures for clamped final pages match the live committed page. Validation: targeted JVM test `SUCCESS`, full `EpubFlowViewTest` `SUCCESS`, full `:render:epub:testDebugUnitTest` `SUCCESS`, and `:app:assembleDebug` `SUCCESS`.
- Clamped final-page boundary guard: `EpubFlowViewTest.next page from clamped final page reports chapter boundary` was added after auditing pages whose raw `topPx` exceeds `maxScroll`; it proves `goToPage(final)` keeps the final page index while visually clamping to `maxScroll`, and the next-page request returns `false` for cross-spine advance instead of looping on the final page. Validation: targeted JVM test `SUCCESS`, full `EpubFlowViewTest` `SUCCESS`.
- Recycled-cache follow-up: `EpubFlowViewTest.discrete gl turn ignores recycled cached textures and snapshots live pages` first failed because page/top keys could match while `cachedFrontBitmap` / `cachedRevealedBitmap` had already been recycled. `EpubFlowView.recycleCachedTexturesIfStaleForTurn(...)` now also checks bitmap liveness, recycles that cache state, and lets GL use live viewport-sized snapshots. Validation: targeted JVM test `SUCCESS`, full `EpubFlowViewTest` `SUCCESS`, full `:render:epub:testDebugUnitTest` `SUCCESS`, `:app:assembleDebug` `SUCCESS`, and whitespace checks passed.
- Pre-cache liveness follow-up: `EpubFlowViewTest.precache refreshes recycled cached textures even when top keys match` first failed because `preCachePageTextures()` treated matching page/top keys as a cache hit even when both cached bitmaps were already recycled. `preCachePageTextures()` now only reuses the same cache tuple when both cached bitmaps are non-null and alive; otherwise it recycles the stale state and posts fresh live snapshots. Validation: targeted JVM test `SUCCESS`, full `EpubFlowViewTest` `SUCCESS`, full `:render:epub:testDebugUnitTest` `SUCCESS`, and `:app:assembleDebug` `SUCCESS`.
- Partial-cache follow-up: `EpubFlowViewTest.discrete gl turn treats partial cached textures as stale and snapshots live pages` first failed because the GL path considered a same-key cache valid when one bitmap was missing and the other was alive, allowing a live front texture to be paired with a stale 1x1 revealed texture. `recycleCachedTexturesIfStaleForTurn(...)` now requires both front and revealed bitmaps to exist and be alive; otherwise it recycles the partial cache state and snapshots both pages live. Validation: targeted JVM test `SUCCESS`, full `EpubFlowViewTest` `SUCCESS`, full `:render:epub:testDebugUnitTest` `SUCCESS`, and `:app:assembleDebug` `SUCCESS`.
- Mode-switch cache follow-up: `EpubFlowViewTest.switching out of paged mode recycles cached turn textures` first failed because `mode = SCROLL` repaginated while same-key cached `cachedFrontBitmap` / `cachedRevealedBitmap` and top keys remained in memory. `EpubFlowView.mode` now recycles cached turn textures before repagination whenever the mode changes, so old PAGED front/revealed textures cannot be reused after mode/geometry changes. Validation: targeted JVM test `SUCCESS`, full `EpubFlowViewTest` `SUCCESS`, full `:render:epub:testDebugUnitTest` `SUCCESS`, and `:app:assembleDebug` `SUCCESS`.
- Pre-cache atomic-pair follow-up: `EpubFlowViewTest.precache discards partial texture pair when revealed snapshot fails` first failed because `preCachePageTextures()` wrote `cachedFrontBitmap` before attempting the revealed snapshot; if the second snapshot failed, LinReads could leave a half-created cache pair until a later GL guard cleaned it up. `preCachePageTextures()` now snapshots front/revealed into local values, commits cache state only when both are non-null, and recycles/clears any partial result on failure. Validation: targeted JVM test `SUCCESS` and full `EpubFlowViewTest` `SUCCESS`.
- Active-turn pre-cache follow-up: `EpubFlowViewTest.pending precache does not commit while gl turn is active` first failed because a previously posted `preCachePageTextures()` runnable could execute after a GL turn started and write a new front/revealed cache pair while the overlay still owned the turn. `preCachePageTextures()` now returns immediately when `turnInFlight` is true, and the posted runnable rechecks the same gate before snapshotting or committing cache state. Validation: targeted JVM test `SUCCESS`, full `EpubFlowViewTest` `SUCCESS`, and full `:render:epub:testDebugUnitTest` `SUCCESS`.
- Latest AVD rerun after the clamped final-page texture follow-up: `:app:installDebug :app:installDebugAndroidTest` succeeded, then `EpubFlowAnchorRuntimeSmokeTest` on `emulator-5554` passed as `OK (10 tests)` in `120.36s`; a recent-window logcat grep for crash/ANR/OOM/recycled-bitmap/reader-surface/assertion/failure signatures returned no matches. This is still AVD runtime evidence, not physical hand-feel proof.
- Continuation audit rerun on 2026-07-02: `adb devices -l` still reported only `emulator-5554`; `git diff --check` was clean; `python3 -m py_compile android/test-tools/moonreader_handfeel_capture.py android/test-tools/test_moonreader_handfeel_capture.py` passed; `python3 -m unittest discover android/test-tools` = `OK (4 tests)`; targeted JVM `EpubPagedTouchZonesTest` + `EpubFlowViewTest` passed; full `:render:epub:testDebugUnitTest :app:assembleDebug` passed; `:app:installDebug :app:installDebugAndroidTest` passed; full AVD `EpubFlowAnchorRuntimeSmokeTest` passed as `OK (10 tests)` in `125.309s`; post-run logcat critical grep returned no matches. This confirms current implementation/runtime gates only; physical phone/tablet hand-feel remains unclaimed.
- Link-priority follow-up: `EpubFlowViewTest.link tap inside text wins over paged tap zone` first failed because the parent clean-tap path fired `PREV/NEXT/MENU` before child `ClickableSpan` dispatch completed. `EpubFlowView` now defers clean-tap handling until after `super.dispatchTouchEvent(...)` and only reads, without clearing, the child interactive-tap-consumed marker so the outer `ReaderTapContainer` can still suppress its own tap-zone. Validation after the fix: targeted JVM link test `SUCCESS`, full `EpubFlowViewTest` `SUCCESS`, full `:render:epub:testDebugUnitTest` `SUCCESS`, `:app:installDebug :app:installDebugAndroidTest` `SUCCESS`, targeted AVD `epubFlowLinkTapInPageTurnZoneDoesNotTriggerPagedFlipRuntime` = `OK (1 test)`, AVD full `EpubFlowAnchorRuntimeSmokeTest` on `emulator-5554` = `OK (7 tests)`, and logcat critical grep returned no crash signatures.
- Stale link-marker guard: `EpubFlowViewTest.stale link tap marker does not suppress next clean tap outside text` proves a previous `ClickableSpan` consumed marker cannot swallow a later clean tap if that later gesture lands outside the child text path; the next right-side tap still reaches `NEXT`. Validation: targeted JVM test `SUCCESS`, full `EpubFlowViewTest` `SUCCESS`.
- Clean-tap single-owner follow-up: added AVD `epubFlowRightTapTurnsExactlyOnePageRuntime` after auditing the possible `EpubFlowView` + outer `ReaderTapContainer` double-owner path. The test taps the right-side NEXT zone in the full reader route and verifies page index `1` plus the next canonical page top, not page `2`. Added `epubFlowCenterTapTogglesChromeExactlyOnceRuntime` to tap the real reader surface center and verify the bottom chrome appears while the page anchor stays at page `0`. Validation: both targeted AVD tests = `OK (1 test)`, the then-current full `EpubFlowAnchorRuntimeSmokeTest` = `OK (9 tests)`, and logcat critical grep returned no crash signatures. No implementation change was needed for the center-tap slice.
- Accessibility/keyboard preservation follow-up: added AVD `epubFlowReaderSurfaceAccessibilityAndKeyboardActionsRuntime` to the same EPUB/PAGED reader route. It verifies reader-surface actions `上一页`, `下一页`, and `显示或隐藏阅读工具栏`; `ACTION_SCROLL_FORWARD` advances to page 1 canonical top; `ACTION_SCROLL_BACKWARD` returns to page 0; `DPAD_RIGHT` advances one page; and `DPAD_CENTER` opens chrome without bypassing the paged touch-routing invariants. Validation: `:app:compileDebugAndroidTestKotlin :app:installDebug :app:installDebugAndroidTest` passed, full `EpubFlowAnchorRuntimeSmokeTest` on `emulator-5554` = `OK (10 tests)`, and logcat critical grep returned no crash signatures.
- Center-boundary safety follow-up: MoonReader `isMiddleTap(...)` / `isFlipSmallMiddleTap(...)` use strict `>` / `<`, but LinReads deliberately folds exact box-border coordinates into the safer zone rather than letting an edge pixel flip. The first AVD boundary attempt failed because `width / 3f` on a non-divisible viewport fell just outside the computed left boundary and flipped/scrolled to `scrollY=2315`. `EpubPagedTouchZones.isInsideCenteredBox(...)` now applies a `0.5px` boundary tolerance, `EpubPagedTouchZonesTest.center box boundaries remain safe with non divisible viewport sizes` first failed on the same class of rounding bug, then passed, and `EpubFlowAnchorRuntimeSmokeTest.epubFlowMoonReaderCenterZonesRuntime` now verifies `1/5` boundary temporary-scroll plus `1/3` boundary no-scroll/no-flip in the full reader route. Validation: targeted JVM RED then SUCCESS, full `EpubPagedTouchZonesTest` SUCCESS, full `:render:epub:testDebugUnitTest` SUCCESS, targeted AVD `epubFlowMoonReaderCenterZonesRuntime` RED then `OK (1 test)`, full AVD `EpubFlowAnchorRuntimeSmokeTest` `OK (10 tests)` / `116.058s`, recent logcat critical grep no matches, and `:app:assembleDebug` SUCCESS.
- Inner-center horizontal no-curl follow-up: MoonReader's useful invariant is that the whole center `1/3 x 1/3` start area disables curl; the inner `1/5 x 1/5` only adds vertical temporary-scroll permission, not horizontal page-turn permission. `EpubFlowViewTest.inner center horizontal drag is consumed without page turn` now locks that an inner-center horizontal drag does not fire `PREV/NEXT/MENU`, does not advance page index, and does not move `scrollY`; `EpubFlowAnchorRuntimeSmokeTest.epubFlowMoonReaderCenterZonesRuntime` now covers the same route in the app reader surface. Validation: targeted JVM test `SUCCESS`, `EpubFlowViewTest` class `SUCCESS`, full `:render:epub:testDebugUnitTest` `SUCCESS`, `:app:compileDebugAndroidTestKotlin :app:installDebug :app:installDebugAndroidTest` `SUCCESS`, targeted AVD `epubFlowMoonReaderCenterZonesRuntime` = `OK (1 test)` / `13.019s`, recent logcat critical grep no matches, `git diff --check` clean, and touched-file trailing whitespace scan clean. No product implementation change was needed because LinReads already consumed that gesture.
- `ACTION_DOWN` no-move follow-up: this locks the original investigation invariant that a plain press must not mutate the live content layer before intent is known. `EpubFlowViewTest.action down alone does not move paged content` now verifies a DOWN in the right page-turn zone does not fire `PREV/NEXT/MENU`, does not advance page index, and leaves `scrollY` parked; `EpubFlowAnchorRuntimeSmokeTest.epubFlowMoonReaderCenterZonesRuntime` now covers the same DOWN-only path in the app reader surface before the center-zone drags. Validation: targeted JVM test `SUCCESS`, `EpubFlowViewTest` class `SUCCESS`, full `:render:epub:testDebugUnitTest` `SUCCESS`, `:app:compileDebugAndroidTestKotlin :app:installDebug :app:installDebugAndroidTest` `SUCCESS`, targeted AVD `epubFlowMoonReaderCenterZonesRuntime` = `OK (1 test)` / `12.809s`. No product implementation change was needed because LinReads already deferred movement until drag/tap intent.
- Center dead-zone long-press follow-up: the center ring must consume ambiguous drags without weakening long-press/selection ownership. `EpubFlowViewTest.long press drag in center dead zone does not trigger paged flip or scroll` now verifies a long-press drag that starts in the center ring does not fire `PREV/NEXT/MENU`, does not advance page index, and leaves `scrollY` parked; `EpubFlowAnchorRuntimeSmokeTest.epubFlowLongPressDragDoesNotTriggerPagedFlipRuntime` now covers the same route in the app reader surface after the right-side long-press case. Validation: targeted JVM test `SUCCESS`, `EpubFlowViewTest` class `SUCCESS`, full `:render:epub:testDebugUnitTest` `SUCCESS`, `:app:compileDebugAndroidTestKotlin :app:installDebug :app:installDebugAndroidTest` `SUCCESS`, targeted AVD `epubFlowLongPressDragDoesNotTriggerPagedFlipRuntime` = `OK (1 test)` / `15.666s`. No product implementation change was needed because LinReads already lets long-press ownership win before center-zone drag classification.
- Temporary-scroll cancel follow-up: `EpubFlowViewTest.temporary scroll cancel re-arms paged clip without tap zone` first failed because a PAGED temporary scroll that received `ACTION_CANCEL` could leave `pageClipActive=false`. `EpubFlowView` now treats this as an aborted temporary-scroll gesture: snap to the nearest line top, report the top offset, re-arm paged clipping, clear gesture ownership, and avoid `PREV/NEXT/MENU`. Validation: targeted JVM test `SUCCESS`, `EpubFlowViewTest` class `SUCCESS`, full `:render:epub:testDebugUnitTest` `SUCCESS`, `:app:compileDebugAndroidTestKotlin :app:installDebugAndroidTest` `SUCCESS`, targeted AVD `epubFlowMoonReaderCenterZonesRuntime` = `OK (1 test)` / `12.454s`, recent logcat critical grep no matches, and `git diff --check` clean.

## Remaining Audit Gates

The source-backed deep audit is complete for the current Android EPUB/PAGED code slice:

- tap zone configuration and user settings: recorded in Round 3, deferred to optional presets only after evidence.
- horizontal-vs-vertical gesture thresholds: recorded in the threshold seed table and covered by AVD matrix smoke.
- selection and long-press arbitration: recorded in Round 1 and locked by JVM + AVD smoke.
- fling velocity and repeated-tap debounce: recorded in Round 1; active-turn gating is copied, exact cooldown numbers are deferred.
- page-turn image/cache lifecycle: recorded in Round 2 and locked by JVM cache lifecycle tests plus AVD real-overlay smoke.

The remaining work is not more source reading. It is physical/A-B evidence:

- phone/tablet harism GL follow/cancel/commit feel.
- release-time no-snap A/B.
- post-settle rapid input after animation settle.
- image-heavy EPUB GL cache memory/alias behavior.
- real ergonomics for the `1/5 x 1/5` temporary-scroll box and any future tap-layout presets.
- run `android/test-tools/moonreader_handfeel_capture.py` on each physical evidence pass so videos,
  screenshots, device metadata, and filtered logcat are collected consistently.
