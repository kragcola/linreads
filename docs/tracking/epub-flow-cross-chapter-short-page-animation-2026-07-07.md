# EPUB Flow Cross-Chapter / Short-Page Animation - 2026-07-07

## Objective

Fix EPUB flow reader so page-turn animation still triggers when turning across chapter boundaries and when the current/target page is short. Verify on Android, commit, push, and publish OTA.

## Live Plan

- [x] Recover current repo state and prior workflow rules.
- [x] Confirm root cause from code, tests, and runtime behavior.
- [x] Add focused regression tests for boundary and short-page animation.
- [x] Implement minimal fix with performance review.
- [x] Run unit/build/emulator verification.
- [x] Build local OTA APK.
- [ ] Commit, push, and confirm OTA workflow.

## Current Evidence

- Local HEAD before this task: `98f13be Fix EPUB illustration rendering` on `main`, matching `origin/main`.
- Existing untracked unrelated files: `android/core/model/bin/`, `dh-no-fake-players/`, `reasonix.toml`.
- Explorer 1 finding: `EpubFlowView.goToAdjacentPage()` only animates when target page is inside current chapter; target page out of bounds returns `false`, then `EpubReflowEngine.advanceFlowPage()` calls `loadFlowChapter(...)` directly. That skips `goToPageAnimated()` / `startFlip()`. Short chapters or short final pages usually hit the same boundary branch.
- Self-review: the failing path is not image rendering or clamped short-page math. `goToAdjacentPage()` correctly reports a chapter boundary; the bug is that the engine treats that `false` as an instant chapter load instead of carrying the outgoing viewport shot into the target chapter reveal.
- Fix: `EpubFlowView.prepareBoundaryPageTurn(delta)` captures the outgoing page shot at the canonical/clamped page anchor before the engine loads the adjacent spine. After `setChapter()` settles and reveals the target chapter, `consumePendingBoundaryPageTurn()` snapshots the incoming target viewport and starts the existing page-turn animator.
- Follow-up while validating: full AVD flow suite exposed an existing runtime-only mismatch where SCROLL->PAGED conversion cover was positioned in viewport coordinates but Android `ViewOverlay` draws in scroll/content coordinates. Fixed `positionConversionSnapshot()` to use `scrollY..scrollY+height`, and adjusted runtime test capture to sample the covered frame while the cover is still installed.

## Test Ledger

| Time | Command / Check | Result | Conclusion |
| --- | --- | --- | --- |
| 2026-07-07 | Parallel code exploration of EPUB flow turn chain | Cross-chapter branch changes chapter directly without animation | Strong root-cause candidate |
| 2026-07-07 | `./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.flow next across short chapter boundary starts slide animation"` before fix | FAILED: next spine loaded, but no page-turn animator | RED confirmed boundary branch bypasses animation |
| 2026-07-07 | Same targeted test after fix | SUCCESS | GREEN: short-chapter cross-spine next consumes captured page shot and starts the slide animator |
| 2026-07-07 | `./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest` | SUCCESS | EPUB JVM/Robolectric regression suite green |
| 2026-07-07 | `./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :features:reader:testDebugUnitTest :render:animate:testDebugUnitTest :app:assembleDebug` | SUCCESS | Reader/animation integration and debug APK build green |
| 2026-07-07 | `./gradlew -Preadflow.phase=2 :app:compileDebugAndroidTestKotlin :app:installDebug :app:installDebugAndroidTest` | SUCCESS | Instrumentation APK compiled and installed on `emulator-5554` |
| 2026-07-07 | `adb shell am instrument -w -e class dev.readflow.page05.EpubFlowAnchorRuntimeSmokeTest#epubFlowShortChapterBoundaryRightTapStartsSlideAnimationRuntime ...` | OK (1 test) | Short chapter boundary right tap reaches next chapter and starts slide animator on AVD |
| 2026-07-07 | Related AVD conversion checks: `epubFlowComplexScrollToPagedModeSwitchUsesExactFrozenViewportRuntime`, `epubFlowScrollToPagedModeSwitchKeepsFrozenViewportCoverRuntime`, `epubFlowUiModeChipScrollToPagedKeepsFrozenViewportCoverRuntime` | OK after conversion overlay/test-capture fixes | Conversion frozen-cover path remains protected while validating boundary animation |
| 2026-07-07 | Full AVD `dev.readflow.page05.EpubFlowAnchorRuntimeSmokeTest` | OK (22 tests), 318.838s | Flow first-tap, boundary, conversion, restore, GL, center-zone, link, keyboard/accessibility runtime checks green |
| 2026-07-07 | `adb logcat -d | rg -i "FATAL EXCEPTION|AndroidRuntime|OutOfMemory|recycled bitmap|AssertionError|\\bANR\\b"` after full AVD suite | Only instrumentation VM start/exit lines | No app crash/OOM/ANR/recycled-bitmap/assertion logs |
| 2026-07-07 | `./gradlew -Preadflow.phase=2 :app:assembleOta` | BUILD SUCCESSFUL; `android/app/build/outputs/apk/ota/app-ota.apk` = 9.4M, sha256 `e8926349f793f351142db58a91a22a8440a623502629e302761b34291f2d5cb8` | Local OTA artifact built before commit/push |
| 2026-07-07 | `.github/workflows/android-release.yml` | `push` to `main` runs `./gradlew -Preadflow.phase=2 :app:assembleOta` and publishes `android/app/build/outputs/apk/ota/app-ota.apk` to `dev-latest` | GitHub OTA path confirmed from workflow code |

## Risks / Constraints

- `setChapter()` cancels overlays and hides content until layout stabilizes; boundary animation must not fight initial reveal.
- Incoming chapter may contain async images; snapshot timing must not capture an empty/placeholder page.
- Short final pages can be clamped by `maxScroll`; page anchor handling must stay stable.
