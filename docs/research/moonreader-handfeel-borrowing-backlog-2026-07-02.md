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
- Page-turn snapshots now use the same `TextView.paddingTop`-aware page top/bottom clip as live PAGED rendering, and both live `EpubFlowView.draw(...)` and snapshots paint paper texture in viewport coordinates, so slide/GL/pre-cache textures do not expose a different half-line boundary or paper-grain phase from the on-screen page.
- `EpubPagedTouchZones` classifies PAGED touch starts into `PageTurn`, `CenterDead`, and `TemporaryScroll`.
- `EpubFlowView` now consumes the center-ring dead zone and only allows temporary scroll from the inner center box. Horizontal drags that start inside the inner center box are also consumed instead of starting curl/page turn, preserving MoonReader's "all center `1/3 x 1/3` starts are no-curl" invariant. MoonReader's decompiled boundary checks use strict `>` / `<`; LinReads intentionally treats exact center-box borders as part of the safe zones so a coordinate on the `1/5` border remains temporary-scroll and a coordinate on the `1/3` border remains no-curl center-dead instead of flipping.
- AVD `EpubFlowAnchorRuntimeSmokeTest` passed on `emulator-5554` for anchor normalization, MoonReader-style center zones including inner-center horizontal no-curl and temporary-scroll `ACTION_CANCEL` clip restore, MoonReader-style gesture thresholds, edge swipe page turn, right-side clean tap exactly one page, center clean tap toggling chrome exactly once, long-press drag no page flip, right-side link tap priority, rapid GL turn no double-advance, real `EpubCurlOverlay` creation/commit in SIMULATION mode, and reader-surface accessibility/keyboard actions.
- JVM `EpubFlowViewTest` now covers slide and GL host-path cancel/commit around normalized anchors, temporary-scroll `ACTION_CANCEL` re-arming paged clip without a tap-zone action, `ACTION_DOWN` alone no content movement, long-press drag no paged flip, center dead-zone long-press drag no paged flip/scroll, rapid slide/GL discrete turn no double-advance, drag-release no tap-zone after temporary scroll or center dead-zone drag, inner-center horizontal drag no page turn, first paged turn queued during initial layout settle then replayed through the normal animation path, static draw/snapshot paper background viewport anchoring, temporary snapshot restoration, and `ClickableSpan` link taps winning over paged tap zones; the GL host-path tests use a fake `EpubCurlTurnOverlay`, while AVD covers real overlay creation, discrete commit, rapid GL no double-advance, and right-side `ClickableSpan` taps suppressing paged NEXT zone in the reader route. Physical-device GL hand feel remains a separate audit item.

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

## Full Handfeel Dimension Matrix

This matrix applies the working definition of "hand feel" as the whole chain from finger down to a stable page. Each row is source-backed by the current worktree. When a MoonReader method is not decompiled enough to inspect directly, this matrix only uses the visible surrounding code or the earlier source checkpoint already listed above.

Decision labels:

- `Already backfilled`: LinReads already carries the useful invariant and has JVM/AVD evidence.
- `Future option`: worth keeping as a product escape hatch, but not a current implementation task.
- `Do not copy now`: the source detail exists, but copying it would add noise or risk without evidence.
- `Physical/A-B gate`: source is insufficient; a real phone/tablet or A/B capture must decide.

| Handfeel dimension | MoonReader details found | LinReads current evidence | Backfill decision |
| --- | --- | --- | --- |
| 1. Input ownership | `ActivityTxt.stopTouchEvent(...)` initializes per-gesture state on DOWN, routes text selection/highlight moves before generic motion, and consumes active selection gestures (`ActivityTxt.java:14194-14304`). `doOnCurlTouch(...)` refuses curl while pre-show/layout/speaking/PDF-reflow/caching/flipping states are active, rejects middle taps at DOWN, and checks URL/bookmark/menu branches before curl UP settles (`ActivityTxt.java:4234-4340`). `A.clickArea(...)` resolves optional 9-grid overrides first, then a single `tapMode` result (`A.java:1971-2103`). | `EpubFlowView.dispatchTouchEvent(...)` defers a clean-tap action until child dispatch finishes, then suppresses the page tap if the text child reported an interactive tap (`EpubFlowView.kt:1139-1159`). Long-press marks selection ownership before the host steals drags (`EpubFlowView.kt:255-268`, `1161-1205`). `ReaderTapContainer` also checks `consumeReaderInteractiveTapReport()` before outer tap zones (`ReaderScreen.kt:1144-1193`). | Already backfilled for Android EPUB/PAGED and the reader host. Keep this as a non-negotiable contract for future engines: link, selection, TalkBack, keyboard, and active overlays must win before page/chrome taps. No new code now. |
| 2. Start hit feel | MoonReader excludes the center `1/3 x 1/3` from curl via `isMiddleTap(...)`, using both current and down coordinates (`ActivityTxt.java:8839-8867`). It marks the inner `1/5 x 1/5` as small-middle via `isFlipSmallMiddleTap(...)` (`ActivityTxt.java:8712-8726`). Shift turns need clear intent: about `20dp` dominant movement and `<40dp` cross-axis drift, with a `500ms` page-scroll gate (`ActivityTxt.java:8982-8999`). Curl-specific enough-move is lower (`8dp`, or `20dp` opposite-touch) (`ActivityTxt.java:5800-5802`). | `EpubPagedTouchZones` implements `TemporaryScroll`, `CenterDead`, and `PageTurn`, using inner `1/5`, outer `1/3`, and a deliberate `0.5px` inclusive edge tolerance (`EpubPagedTouchZone.kt:9-40`). `EpubFlowView` uses `20dp/40dp` flip gates (`EpubFlowView.kt:75-78`, `1316-1340`). Host tap zones remain left/middle/right thirds (`ReaderTapZone.kt:11-31`). | Already backfilled for EPUB/PAGED. Do not copy MoonReader's exact strict border behavior or `8dp` curl eagerness now. Future option: screen-size-aware temporary-scroll hit-box or 2-3 tap-layout presets, only after phone/tablet/low-vision evidence. |
| 3. Response latency | MoonReader starts curl preparation from `ACTION_DOWN` when allowed (`ActivityTxt.java:4245-4263`), pre-caches page shots after settle (`ActivityTxt.java:3000-3044`), can replay delayed curl/value messages after shots are ready (`ActivityTxt.java:6278-6285`, `7260-7285`), and blocks new curl while cache work is active for up to about 2.5s (`ActivityTxt.java:14807-14816`). | LinReads pre-caches current/next textures when parked on a canonical page and skips if a turn is active (`EpubFlowView.kt:155-200`). GL discrete turns retry until overlay dimensions are ready (`EpubCurlOverlay.kt:163-193`). Turn paths fall back to live snapshots or plain page moves instead of showing stale textures (`EpubFlowView.kt:717-754`, `810-872`). | Already backfilled in the safer form: pre-warm textures and retry GL readiness, but do not add MoonReader-style global `waitingForCahingShots()` blocking. Physical/A-B gate remains for image-heavy EPUB latency. |
| 4. Tracking continuity | MoonReader sends real motion through curl/gesture handlers after ownership is established (`ActivityTxt.java:4292-4304`), moves only after enough intent (`ActivityTxt.java:4282-4290`, `5800-5802`), and can split page bitmaps for dual/3D curl continuity (`ActivityTxt.java:6232-6264`). Pinch font-size cancels curl/flip visuals before resizing (`ActivityTxt.java:9597-9641`). | LinReads keeps host ownership and forwards the saved real DOWN plus later MOVE/UP into the GL overlay for SIMULATION (`EpubFlowView.kt:757-807`, `1260-1268`; `EpubCurlOverlay.kt:196-200`). Slide mode tracks with host transforms and commits/cancels by progress/velocity. Inner-center temporary scroll is continuous until release, while center-ring drags are consumed without moving content (`EpubFlowView.kt:1183-1258`). | Already backfilled for the state model. Physical/A-B gate remains for actual harism GL follow/cancel/commit feel and frame pacing on phone/tablet. |
| 5. Visual consistency | MoonReader normalizes before value/curl imagery by calling `pageScroll(...)` before shot use (`ActivityTxt.java:6265-6276`, `7260-7285`). It reuses page shots only when `lastFlipScrollY == getFlipScrollY()` and bitmaps are alive (`ActivityTxt.java:3007-3018`, `19182-19203`). `getPageShot(...)` calls `A.setBackgroundImage(canvas)` before drawing `contentLay`, while normal views use the same `A.setBackgroundImage(view)` path; small bitmap backgrounds are tiled by `A.setBackgroundDrawable(Canvas, Drawable)` in canvas/view coordinates (`ActivityTxt.java:16327-16369`; `A.java:7438-7505`). | LinReads snaps to a canonical page anchor before adjacent turns and animated turns (`EpubFlowView.kt:600-606`, `645-696`). Texture cache identity includes from/target page and top px, and requires complete alive bitmap pairs (`EpubFlowView.kt:148-200`, `717-742`). `EpubFlowView.draw(...)`, `snapshotViewport()`, and `snapshotPageAt(...)` now all paint paper background in viewport coordinates before translating content, then use padding-aware content clip boundaries (`EpubFlowView.kt:325-337`, `810-900`). The EPUB flow host and outer `ReaderTapContainer` also install fresh `readerPaperBackground(...)` drawables, so slide/ViewPager/GL transparent or exposed regions reveal the same paper texture instead of a flat parent colour. Initial reveal alpha is forced complete before the first turn, pre-cache skips hidden reveal layers, and an early first paged turn requested before initial pagination exists is queued then replayed through the normal animation path after settle. Restored chapters stay alpha-hidden until the first layout/reflow settle window before fading in. Native ScrollView scrollbars are disabled so restore positioning does not expose Android's scroll affordance. Flow `SelectionAwareTextView` is now hosted with `MATCH_PARENT` width, so StaticLayout wrapping, page snapshots, and live content all measure against the same viewport column. | Already backfilled/test-locked, including paper-texture parity behind turn layers, static/snapshot paper phase parity, first-turn reveal/queue guard, restored-open settle reveal, and viewport-width text measurement. Do not add line-text sampling or bitmap pooling unless image-heavy EPUB evidence reproduces same-top/different-content alias, stale texture, OOM, or allocation pressure. |
| 6. State consistency | MoonReader's cache path stores `cacheScrollY`, restores it after next-shot capture, and for TXT reload searches nearby lines by cached line text (`ActivityTxt.java:3024-3091`). It saves progress and read progress after normalized page movement (`ActivityTxt.java:6265-6276`, `7278-7285`). `A.getLastDisplayLine(...)` computes page bottoms from page height, line top, and border/page-break guards instead of arbitrary scroll edges (`A.java:4514-4544`; `MRTextView.java:1414-1454`). `resetFlipCache(...)` clears scroll-keyed flip cache and GL visibility when invalidated (`ActivityTxt.java:19030-19051`). MoonReader's settings surface applies spacing changes from explicit value callbacks and compares old/new values before some reset/restart work (`PrefVisual.java:276-290`, `1398-1410`; `ActivityTxt.java:20551-20578`, `20600-20628`). KOReader also separates page-mode and rolling-mode location identity: `ReaderBookmark:getCurrentPageNumber()` returns a page in paging mode but an xpointer in rolling mode, and `gotoBookmark(...)` dispatches `GotoPage` vs `GotoXPointer` directly (`readerbookmark.lua:505-508`, `600-605`). | LinReads updates `currentPage`, re-arms `pageClipActive`, and reports top offset only at committed points (`EpubFlowView.kt:645-669`, `1260-1274`). Async image reflow is coalesced, deferred during `turnInFlight`, then re-paginates and re-anchors by content offset while recycling stale textures (`EpubFlowView.kt:277-315`). During initial restore, reflow uses the pending restore offset while still hidden, then reveals after the debounce. Direct URI opens now synchronously switch the reader UI to Loading and clear the previous engine surface before the new engine parses/restores. Book-id opens now resolve initial remote/local progress before exposing `Loaded`: persisted progress keeps its original `updatedAt/deviceId` for sync comparison, a remote winner is persisted and selected as the display locator, and the engine receives a single final `goTo` before the reader surface appears. Temporary-scroll release in PAGED now settles to the nearest paginator-produced canonical page anchor, not an arbitrary line top, so complex image/heading/widow/orphan pages re-enter the same page model as tap/keyboard turns. Flow open, cross-spine go-to, rebuild, and SCROLL/PAGED switches now preserve paragraph-local `charOffset` instead of restoring to the paragraph head. SCROLL->PAGED now uses synchronous anchored mode switching, no longer relies on a posted correction, and the anchored switch settles to the nearest canonical page anchor while ordinary direct go-to remains floor-to-containing-page. Current-value typography/settings replays now short-circuit before rebuilding, so `watchSettings` cannot hide/reveal the visible page when line spacing or font choice has not changed. Mode/reflow/cache lifecycle tests are recorded in `EpubFlowViewTest`, the direct-open and initial-remote-sync regressions are recorded in `ReaderSavedStateHandleTest`, and the paragraph-offset, no-posted-jump, and no-op typography replay regressions are recorded in `EpubReflowEngineTest`. | Already backfilled for EPUB/PAGED. Future product task: preserve the same "one canonical locator owner" invariant if paged animation/tap-zone behavior expands to TXT/MD/PDF. |
| 7. Fault tolerance and recovery | MoonReader cancels long-tap when animation is active or movement exceeds about `10dp` (`ActivityTxt.java:14183-14192`), logs ACTION_CANCEL for highlight state and clears long-tap state (`ActivityTxt.java:8734-8773`), expires cache waiting after about 2.5s (`ActivityTxt.java:14807-14816`), and catches bitmap creation/draw failures in `getPageShot(...)` (`ActivityTxt.java:16327-16369`). | LinReads handles temporary-scroll `ACTION_CANCEL` by settling back to a canonical paged anchor, reporting offset, re-arming clip, and clearing gesture ownership (`EpubFlowView.kt:1289-1295`). Snapshot creation catches `Throwable` and falls back (`EpubFlowView.kt:810-872`). GL overlay has a 5s safety dismiss so `turnInFlight` cannot stay stuck forever (`EpubCurlOverlay.kt:139-151`). | Already backfilled/test-locked. Keep adding failure-path tests when touching gesture ownership, snapshot cache, GL overlay, or async reflow. |
| 8. Rhythm and animation | MoonReader uses `pageScrollTime > 500ms` gates for shift/fling page turns (`ActivityTxt.java:8982-8999`, `16090-16107`), animation family and speed are configurable (`PrefVisual.java:633-669`), and defaults set `flipSpeed` to `30` normally or `20` on Kindle ROMs (`A.java:8044-8051`). `get3dFlipSpeed()` derives duration from `flipSpeed` (`ActivityTxt.java:6291-6293`). | LinReads has a unified active-turn gate across slide, live GL, and overlay-active states (`EpubFlowView.kt:119-128`, `677-688`). Discrete slide and GL durations are fixed at `280ms` and `420ms` for now (`EpubFlowView.kt:130-135`); release commit uses progress or `700dp/s` velocity (`EpubFlowView.kt:245`, `1260-1268`). | Copy the rhythm invariant, not MoonReader's numbers. Do not add an exact `500ms` cooldown, flip-speed slider, or MoonReader duration formulas until physical post-settle rapid input and frame-pacing evidence justify them. |
| 9. Device ergonomics | MoonReader exposes many escape hatches: tap-zone gallery and 9-grid editor (`PrefControl.java:157-177`, `235-345`, `570-705`), edge/multitouch/horizontal/disable-move/tilt settings (`PrefMisc.java:1232-1293`), edge tap exclusion (`ActivityTxt.java:8629-8631`), and pinch font-size (`ActivityTxt.java:9597-9641`). Defaults are more restrained: 9-grid disabled, tilt/multitouch off, horizontal turns on, tablet dual page on (`A.java:7849-8051`). | LinReads host supports pinch font-size or zoom preview, clean-tap slop/duration checks, keyboard paging, and accessibility scroll/click actions (`ReaderScreen.kt:1049-1247`; `ReaderTapZone.kt:34-48`). EPUB/PAGED center safe zones are test-locked, but physical phone/tablet ergonomics are still emulator-only. | Future option, not immediate code: if evidence shows one default fails, add 2-3 tap-layout presets and maybe a small settings preview. Do not copy dense 9-grid actions, tilt, edge toggles, ClickTip, or a speed slider now. Physical/A-B gate required. |

### Backfill Register From The Matrix

- Immediate code backfill: none. The source-backed P0/P1 invariants worth copying are already present for Android EPUB/PAGED or intentionally deferred behind device evidence.
- Keep as future LinReads design options: tap-layout presets, screen-size-aware temporary-scroll hit box, small settings-only tap-zone preview, and extending the same ownership/locator invariants to other paged engines if they gain richer gestures.
- Explicitly do not copy now: exact `8dp` curl threshold, exact `500ms` cooldown, full 9-grid action editor, dense MoonReader settings surface, first-run ClickTip, global cache-wait blocking, flip-speed slider/formulas, line-text sampling, and bitmap pooling.
- Physical/A-B gates still required: release-time no-snap, post-settle cooldown, real harism GL follow/cancel/commit feel, image-heavy EPUB cache alias/memory behavior, and real phone/tablet/low-vision tap-zone ergonomics.

## Parameter-Level Handfeel Audit

The previous matrix records ownership and invariants. This section records the numeric parameters behind those behaviors, because perceived hand feel changes when hit boxes, movement thresholds, timing, animation duration, typography, and edge affordances change.

### Gesture Geometry And Hit Zones

| Parameter | MoonReader value / source | LinReads value / source | Backfill decision |
| --- | --- | --- | --- |
| Center no-curl box | Strict center `1/3 x 1/3`; both current touch and down touch must be inside (`ActivityTxt.isMiddleTap(...)`, `ActivityTxt.java:8839-8867`). | Center-dead box uses center `1/3 x 1/3`, but borders are inclusive with `0.5px` tolerance (`EpubPagedTouchZone.kt:9-40`). Non-divisible viewport boundaries are tested (`EpubPagedTouchZonesTest.kt:60-100`). | Already backfilled with an intentional LinReads deviation: inclusive borders feel safer than MoonReader's strict `>` / `<` edge. Keep. |
| Inner temporary-scroll box | Strict center `1/5 x 1/5`; if movement is disabled, it falls back to the full middle box (`ActivityTxt.isFlipSmallMiddleTap(...)`, `ActivityTxt.java:8712-8726`). | Inner center `1/5 x 1/5` maps to `TemporaryScroll`; center ring maps to `CenterDead` (`EpubPagedTouchZone.kt:9-18`). Boundary tests cover `120/180` on a `300px` width and `240/360` on `600px` height (`EpubPagedTouchZonesTest.kt:24-58`). | Already backfilled. Physical gate remains for whether `1/5` is too small on real phones/tablets or low-vision typography. |
| Default clean tap map | `A.clickArea(...)` is one action result. Optional 9-grid overrides use thirds first; fallback `tapMode` variants include thirds and `4/9` / `5/9` shapes (`A.java:1971-2103`). Defaults set top/left/volume-up to previous, bottom/right/volume-down to next, D-pad center to menu, and all 9-grid cells disabled (`A.java:7849-7886`). | Outer reader uses left `<1/3` previous, middle `<=2/3` chrome, right next (`ReaderTapZone.kt:11-31`); EPUB internal tap uses the same thirds (`EpubFlowView.kt:1360-1365`). | Keep LinReads simpler default. Future option: 2-3 presets only if real ergonomics evidence shows thirds fail. Do not copy full 9-grid. |
| Edge affordance size | Edge swipe area is `20dp` for PDF reflow/no-flow, `40dp` on phone, `50dp` tablet, `60dp` large tablet (`ActivityTxt.getEdgeSize()`, `ActivityTxt.java:6574-6583`). | LinReads has no dedicated edge-only control band for reader settings; page turn starts outside the center safe zones and still uses `20dp/40dp` movement gates. | Do not add edge settings now. Use these numbers as A/B seeds if phone/tablet evidence says edge-only curl or edge controls are needed. |
| Edge touch suppression | Optional `disableEdgeTouch` uses user-saved `ledge/redge/tedge/bedge` widths and rejects edge taps unless an active flip/curl should finish (`ActivityTxt.java:3453-3468`, `8629-8631`, `17061-17118`). Default `disableEdgeTouch = false` (`A.java:8011-8020`). | No equivalent user-facing edge dead strip. Center zones protect reading content; system gesture insets are not custom-modeled here. | Future option only. Do not add an edge dead-strip setting without real full-screen phone/tablet evidence. |

### Movement, Velocity, And Tap Classification

| Parameter | MoonReader value / source | LinReads value / source | Backfill decision |
| --- | --- | --- | --- |
| Drag classification slop | Curl enough-move is `8dp`, or `20dp` for opposite-touch movement (`ActivityTxt.enoughTurnMove(...)`, `ActivityTxt.java:5800-5802`). Shift paging uses `>20dp` dominant movement and `<40dp` cross-axis drift (`ActivityTxt.java:8982-8999`). | Host waits for Android `touchSlop`; actual page flip requires `20dp` dominance and `<40dp` cross-axis (`EpubFlowView.kt:75-78`, `1316-1340`). | Keep LinReads' stricter `20dp` start. Do not copy `8dp` curl eagerness until accidental-flip/slow-start evidence exists. |
| Fling velocity | Horizontal fling accepts about `600px/s`, axis dominance, and `<40dp` cross-axis drift (`ActivityTxt.acceptHorizontalFling(...)`, `ActivityTxt.java:1935-1979`). | Interactive release commit uses `700dp/s` (`EpubFlowView.kt:245`, `1260-1268`). | Close enough, but not proven physically. Keep current value; only tune with real video and logs. |
| Clean tap jitter | Curl/menu tap branch requires movement `<12px` on both axes and press duration `<800ms`; it also gates by `mTouchTimes <= 10` (`ActivityTxt.java:4312-4326`). | Outer host uses Android `touchSlop` and `ViewConfiguration.getLongPressTimeout()` as max tap duration (`ReaderScreen.kt:1056-1058`, `1155-1174`). EPUB internal clean tap is delegated through `GestureDetector.onSingleTapUp(...)` (`EpubFlowView.kt:255-268`). | Keep platform slop/timeout rather than MoonReader's raw `12px` / `800ms`. Add tests only if small drag releases become accidental taps. |
| Long-press cancellation | Long-tap event delay is `A.longTapInterval * 1000`; if touch starts in edge, delay is multiplied by `1.5` (`ActivityTxt.java:8734-8746`). Long tap cancels if animation is active or movement exceeds about `10dp` (`ActivityTxt.java:14183-14192`). Minimum configurable interval is `0.05s` (`PrefControl.java:660-674`). | LinReads uses Android `GestureDetector.onLongPress(...)` / system long-press timeout and does not expose custom interval (`EpubFlowView.kt:255-260`; `ReaderScreen.kt:1056-1058`). | Do not expose long-press interval now. Keep selection priority; tune only if selection feels late/early on device. |
| Highlight/direct-selection drag | MoonReader starts direct-highlight drag after `24dp` horizontal or `16dp` vertical movement while selection state is active (`ActivityTxt.java:14283-14295`). Note footnote hit tolerance uses `26dp` near text x (`ActivityTxt.java:8934-8938`). | LinReads selection ranges are handled by `SelectionAwareTextView` / EPUB selection code and tests; no MoonReader-style direct-highlight distance knobs are exposed in reader UI. | Record as future annotation tuning data. Do not backfill while PAGE-05 device selection remains partially gated. |

### Timing, Animation, And Cache Rhythm

| Parameter | MoonReader value / source | LinReads value / source | Backfill decision |
| --- | --- | --- | --- |
| Rapid-turn gate | Shift and fling page turns require `pageScrollTime` older than `500ms` (`ActivityTxt.java:8982-8999`, `16090-16107`). | `turnInFlight` rejects new turns while slide, finger GL curl, or overlay settle is active (`EpubFlowView.kt:119-128`, `677-688`). No post-settle cooldown exists. | Copy invariant, not number. Add `500ms`-style cooldown only if physical post-settle rapid input reproduces chaining. |
| Curl/tap press duration | Curl/menu tap accepts press duration under `800ms` before settling tap/curl decisions (`ActivityTxt.java:4312-4314`). | Host tap duration is system long-press timeout (`ReaderScreen.kt:1056-1058`); internal tap uses `GestureDetector`. | Keep platform semantics. The MoonReader `800ms` is a clue, not a portable default. |
| Animation speed setting | `PrefVisual` exposes flip speed `0..50` (`PrefVisual.java:639-669`); defaults set `flipSpeed = 30`, or `20` on Kindle ROMs (`A.java:8044-8051`). `get3dFlipSpeed()` computes `(60 - flipSpeed) * 9` or `* 11` (`ActivityTxt.java:6291-6293`). | LinReads slide is `280ms`; discrete GL curl is `420ms`; both use `DecelerateInterpolator(1.6f)` for slide/cancel paths (`EpubFlowView.kt:130-135`, `925-958`). Interactive settle/cancel duration scales with remaining progress and clamps to `80..280ms` (`EpubFlowView.kt:1038-1052`). | Do not add a speed slider now. Current values are code-locked but not physical-handfeel proven; tune only through A/B capture. |
| Cache wait and expiry | `waitingForCahingShots()` blocks curl while cache work is active and clears the wait after `2500ms` (`ActivityTxt.java:14807-14816`). Cache reload line-text search scans up to `50` nearby lines (`ActivityTxt.java:3057-3089`). | LinReads pre-cache is opportunistic and skips during active turns; stale/incomplete pairs fall back to live snapshot (`EpubFlowView.kt:155-200`, `717-742`). Async reflow coalesces with `80ms` debounce (`EpubFlowView.kt:1369-1371`). GL overlay has a `5s` safety timeout (`EpubCurlOverlay.kt:139-151`). | Do not copy global cache wait or 50-line text search now. Keep fallback model; add content sampling only if image-heavy evidence reproduces aliasing. |
| Reveal/reflow masking | MoonReader has many small delayed handlers around content/curl, but the relevant source-backed cache gate is above. | LinReads chapter reveal fade is `120ms`; async-image reflow debounce is `80ms` (`EpubFlowView.kt:1369-1374`). | Keep. These are LinReads-specific smoothing parameters, not MoonReader borrow targets. |

### Typography, Layout Density, And Visual Handfeel

| Parameter | MoonReader value / source | LinReads value / source | Backfill decision |
| --- | --- | --- | --- |
| Default font size | `DEFAULT_FONT_SIZE = 15`; first-run theme sets non-tablet CJK to `17sp`, non-CJK to `15sp`, tablet to `18sp`; night tablet also `18sp` (`A.java:176`, `1548-1572`). | Default reader font size is `18sp` (`ReaderTypographyRange.kt:6-15`, `ReaderTypography.kt:9-18`, `ReaderState.kt:37`, `ThemeProfile.kt:8-16`). | No immediate change. MoonReader's lower phone default may be denser; LinReads chose more accessible default. Compare only with real low-vision/normal corpus screenshots. |
| Font size range | Static min/max are `10..100`, but many UI paths clamp differently (`A.java:931-932`). Pinch/edge resize clamps to `A.minFontSize..A.maxFontSize` (`ActivityTxt.java:4851-4883`, `9597-9641`). | Settings and pinch clamp to `12..32sp` (`ReaderTypography.kt:8-24`, `ReaderFontScale.kt:3-13`). | Keep LinReads range. MoonReader's very wide range is power-user surface; low-vision beyond `32sp` should be a separate accessibility product decision. |
| Line spacing | Default `lineSpace = 3`, applied as multiplier `(lineSpace / 10) + 1 = 1.3x`; range clamps `-5..20`, i.e. `0.5x..3.0x` (`A.java:7597-7607`, `8025-8029`). | Default `1.3x`, range `1.0..2.2x` now share `ReaderTypographyRange`; `ThemeProfile.validated(...)` clamps saved profiles to the same `1.0..2.2x` range (`ReaderTypographyRange.kt:6-15`, `ThemeProfile.kt:10-33`; `ThemeProfileTest.kt:63-66`). EPUB flow applies equivalent additive leading for text lines to avoid image-line inflation (`EpubReflowEngine.kt:414-428`). | Default matches MoonReader. Keep LinReads' narrower safe range; the settings/profile lower-bound drift is now fixed and test-locked. Do not broaden toward MoonReader's `0.5..3.0x` without accessibility/product evidence. |
| Paragraph spacing | Default `paragraphSpace = 7`; UI exposes paragraph space `0..200%` with step `10` and stores `i / 10` (`A.java:8025-8029`; `PrefVisual.java:277-285`, `1137-1140`). | EPUB flow uses a fixed blank-line separator `"\n\n"` between blocks (`EpubChapterFlow.kt:18-20`), with no paragraph-spacing slider. | Potential future typography setting, not immediate. Paragraph gap affects page density and hand feel; add only if broader typography settings are being designed. |
| Letter spacing / text scale | `fontSpace` clamps `-4..20` and maps to letterSpacing `fontSpace / 20`; `fontScale` clamps `-4..20` and maps to `1 + fontScale / 10` (`A.java:7528-7548`). | No equivalent user-facing letter-spacing or text-scale knobs. EPUB uses first-line indent and block styles, not global letter spacing. | Do not copy now. This belongs to advanced typography/accessibility, not the current handfeel slice. |
| Margins / page padding | Default side margins: tablet shortest side `/12`; large phone `26dp`; full-screen phone `24dp`; else `20dp`. Default top/bottom: tablet `50dp`; full-screen phone `30dp`; else `20dp`, with non-tablet top multiplied by `12/9`. Max margin is `200/300/400dp` phone/tablet/large-tablet (`A.java:7888-7981`, `1284-1289`, `7610-7636`). | EPUB flow uses fixed page padding `28dp` horizontal and `24dp` vertical; max line width/image width cap `680dp`; inline image max height `360dp`; inline image vertical padding `24dp` (`EpubReflowEngine.kt:394-412`, `2022-2034`). | Current LinReads padding is within MoonReader phone/tablet default neighborhood. Future option: device-size-aware margins if physical tablet/phone screenshots show density mismatch. |
| First-line indent and block indentation | MoonReader defaults `indentParagraph = isChinese` (`A.java:7983-8023`), exact indent implementation is outside this pass. | LinReads applies first-line indent of `2 x fontSizePx` for plain body paragraphs, plus `24dp` per indent level and `18dp` for blockquote (`EpubReflowEngine.kt:406-412`; `EpubFlowSpannable.kt:108-125`). | Already backfilled in spirit for CJK/body text. Keep; verify visual density by corpus screenshots, not source only. |
| Heading scale | Not parameter-audited in MoonReader source in this pass. | LinReads headings use relative sizes: H1 `1.5x`, H2 `1.3x`, H3 `1.15x` (`EpubFlowSpannable.kt:92-101`). | LinReads-native. No MoonReader comparison claim until a focused MoonReader style audit is done. |

### Device Controls And Ergonomic Parameters

| Parameter | MoonReader value / source | LinReads value / source | Backfill decision |
| --- | --- | --- | --- |
| Pinch font-size | Optional `mult_touch`; disabled for web/PDF/edge. Size change is roughly `(distanceDelta / 24dp)` sp, damped by `/10` for more than two pointers, and clamped to min/max (`ActivityTxt.java:9597-9641`). Default `mult_touch = false` (`A.java:7983-8023`). | Reader pinch font-size is enabled for non-PDF; it multiplies start size by scale factor and clamps to `12..32sp` (`ReaderScreen.kt:1058-1090`, `ReaderFontScale.kt:3-13`). PDF/zoom engines use zoom pinch `1..4x` (`ReaderZoomScale.kt:3-12`). | Keep LinReads multiplicative pinch. Do not copy MoonReader's distance/24dp step unless device users report pinch feels too fast/slow. |
| Edge brightness/font-size gestures | Brightness edge move threshold is `20` display-scaled px, velocity capped at `10000`, with rates `500/400`; font-size edge threshold is `40dp`, step is `1sp` for one pointer and `0.1` for two pointer/PDF zoom paths (`ActivityTxt.java:4800-4889`). Defaults: brightness edge on, font-size-at-slide off (`A.java:7983-8023`). | LinReads has no edge brightness/font-size gestures; typography is through settings and pinch. | Do not backfill now. These gestures compete with page turns and system edges; require explicit ergonomics evidence. |
| Shake/tilt sensitivity | `PrefControl` shake sensitivity clamps `1.0..10.0` with a `0..90` seekbar mapping; tilt is present but off by default (`PrefControl.java:384-396`; `A.java:7983-8023`). | No tilt/shake reader page-turn behavior. | Do not copy. It increases accidental input surface and is outside current reading handfeel scope. |
| Tap/keyboard defaults | MoonReader defaults volume up/down to previous/next, D-pad center menu, search key search, long tap selection/highlight (`A.java:7849-7886`). | LinReads maps D-pad left/page-up/volume-up previous, D-pad right/page-down/volume-down/space next, shift-space previous, D-pad center/enter chrome (`ReaderTapZone.kt:34-48`). Accessibility exposes scroll backward/forward and click (`ReaderScreen.kt:1204-1247`). | Already backfilled and slightly expanded. Keep accessibility/keyboard actions non-negotiable if tap presets are added. |

### Parameter Backfill Summary

- Directly keep/test-lock: center `1/3`, inner `1/5`, inclusive LinReads borders, `20dp/40dp` drag gates, current `700dp/s` release commit, `1.3x` default line spacing plus `1.0..2.2x` settings/profile clamp parity, complete/alive texture-pair checks, and accessibility/keyboard mappings.
- Keep as A/B seeds, not defaults: `8dp` curl eagerness, `600px/s` fling, `12px` clean-tap jitter, `800ms` press cap, `500ms` rapid-turn gate, `0..50` flip-speed slider, `20/40/50/60dp` edge bands, device-size margins, and pinch `distance / 24dp` step.
- Future cleanup candidates inside LinReads: decide whether paragraph spacing should become an explicit typography parameter.
- Still blocked by physical evidence: whether current `1/5` hit box is too small, whether `280ms/420ms` feels right, whether a post-settle cooldown is needed, and whether phone/tablet margins/density should become adaptive.

## Current Completion Ledger

This is the current status of "record first, then execute worthwhile borrowing" for the MoonReader EPUB/PAGED hand-feel work.

| Status | Items | Evidence / next gate |
| --- | --- | --- |
| Executed and test-locked | `ACTION_DOWN` alone no content movement, center `1/3 x 1/3` dead zone, inclusive safe borders for `1/3` and `1/5` center boxes, inner `1/5 x 1/5` temporary scroll, temporary-scroll release/cancel restores a canonical paged anchor without tap-zone, SCROLL->PAGED anchored switch snaps to the nearest canonical page anchor, direct URI open clears the previous reader surface immediately, inner-center horizontal drag no-curl/no-page-turn, center dead-zone long-press no flip/scroll, canonical pre-turn snap, canonical/clamped visual-top keyed texture cache, live/snapshot padding-aware page clip parity, paper-texture parity behind turn layers and viewport-anchored snapshot background, restored-open settle-window reveal, paragraph-local flow anchor preservation across open/rebuild/SCROLL->PAGED, SCROLL->PAGED anchored switch without posted correction jump, current-value typography replay no visible rebuild, viewport-width flow text measurement, first-turn reveal guard, clamped-final-page boundary reporting, complete/alive front/revealed cache pair, mode/reflow cache invalidation, active-turn pre-cache skip, GL pending retry, long-press/selection priority, inline-link priority, stale link-marker cleanup across gestures, clean-tap single owner, active-turn rapid gate, drag-release no-tap, keyboard/accessibility actions. | JVM `EpubPagedTouchZonesTest`, JVM `EpubFlowViewTest`, JVM `ReaderSavedStateHandleTest.direct uri open immediately hides the previous reader surface`, JVM `EpubReflowEngineTest.flow view host keeps paper background behind page turn layers`, JVM `EpubReflowEngineTest.flow mode switch to paged preserves paragraph offset anchor`, JVM `EpubReflowEngineTest.flow mode switch to paged lands on target anchor without posted correction jump`, JVM `EpubReflowEngineTest.flow same typography setting updates do not hide visible content`, AVD `EpubFlowAnchorRuntimeSmokeTest`, full `:render:epub:testDebugUnitTest`, `:features:reader:testDebugUnitTest`, `:app:assembleDebug`, and recent AVD logcat grep are recorded below. |
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
