# MoonReader vs LinReads Extreme Reading Comparison

Date: 2026-06-27

Purpose: define one repeatable extreme-reading plan for comparing Moon+ Reader
Pro and LinReads at the functional level. The comparison must use the same
files, the same device tier, the same launch route, and the same metrics before
claiming a quality or performance difference.

## Current Evidence State

| Area | LinReads | Moon+ Reader Pro |
| --- | --- | --- |
| App artifact | `dev-latest` is currently `Dev build #124`; the shared-corpus release rerun uses commit `6fa6d2b` / APK SHA-256 `29fa2db2e576e0ed98b0a25d989d9263f78bbc6758f5b43e20f18d4dbdfb6cd7`. | Local artifact exists as `moonreader-pro.apk`; manifest package is `com.flyersoft.moonreaderp`. |
| Shared file entry | LinReads `ACTION_VIEW` / `ACTION_SEND` is implemented and has repo-owned AVD ContentProvider smoke evidence, but real file-manager/share-sheet evidence is still missing. | `moonreader-unpacked/AndroidManifest.xml` exposes `ActivityMain` with `ACTION_VIEW` for `text/*`, `application/pdf`, `application/epub+zip`, `application/x-mobipocket-ebook`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document`, `application/x-chm`, `application/x-cbz`, and broad `file` / `content` path patterns. |
| Reader engine reference | LinReads current high-risk evidence is `PAGE-05` EPUB paged mode, `A-02` performance proxy, `A-03` accessibility proxy, SAF, and Calibre fake-server smoke. | Decompiled reference shows `ActivityTxt` is the reader activity, `MRTextView` is the custom text layout surface, `A.setLineSpace()` maps global line spacing into `MRTextView.setLineSpacing()`, and `PDFReader` is a separate `FrameLayout` path. |
| Black-box status | Shared-corpus AVD shell `ACTION_VIEW` release rerun exists on `Dev build #124`, covering EPUB/TXT/PDF cold open, LinReads EPUB paged sampling, and PDF page-turn. A separate debug/test APK instrumentation rerun covers A-02/A-03/SAF/Calibre simulation only. | Shared-corpus AVD shell `ACTION_VIEW` partial run exists on local `moonreader-pro.apk`, covering EPUB/TXT open, EPUB page taps, and a PDF crash. |

Evidence anchors already inspected:

- `moonreader-decompiled/sources/com/flyersoft/moonreaderp/ActivityTxt.java`: reader activity implements touch/click/long-click/TTS interfaces around line 197; gesture handlers are around `MyCurlGesture` / `MySimpleGesture`; font panel updates `A.fontSize`.
- `moonreader-decompiled/sources/com/flyersoft/staticlayout/MRTextView.java`: custom text reader view.
- `moonreader-decompiled/sources/com/flyersoft/tools/A.java`: `setLineSpace(MRTextView)` clamps `lineSpace` and calls `setLineSpacing`.
- `moonreader-decompiled/sources/com/flyersoft/books/PDFReader.java`: separate PDF/CBZ/DJVU layout holder.
- `/tmp/readflow-stage-verify-20260627-dev122/staged-verification-summary.md`: current LinReads staged AVD evidence.
- `/tmp/readflow-moonreader-linreads-compare-20260627/`: shared-corpus AVD partial comparison evidence from `emulator-5554`.
- `/tmp/readflow-dev124-release-compare-20260627/`: LinReads `Dev build #124` shared-corpus AVD release rerun plus debug/test APK staged simulation evidence.

## Shared Corpus

Generate the exact input files with:

```bash
python3 android/test-tools/extreme_reading_corpus.py --out /tmp/readflow-moonreader-linreads-corpus
```

Generated files:

| File | Purpose |
| --- | --- |
| `rf-mr-extreme-20260627.epub` | EPUB 3 stress book with invisible spacer-only paragraphs, CJK micro paragraphs, mixed safe styles, long unspaced CJK, Latin micro paragraphs, tables, and short later spines. |
| `rf-mr-extreme-20260627-utf8.txt` | Long UTF-8 TXT with CJK hard-wrap lines, invisible spacer probes, emoji/grapheme probes, and near-tail anchors. |
| `rf-mr-extreme-20260627.pdf` | 20-page synthetic PDF with stable page markers and dense line blocks. |
| `manifest.json` | Machine-readable file list, expected markers, and evidence boundaries. |

The generated corpus is not committed as binary fixtures; regenerate it when
needed. The generator is deterministic in file names and marker structure, but
`manifest.json` records generation time.

## Device Tiers

| Tier | Allowed claims |
| --- | --- |
| AVD tablet-like `1600x2560` / density `320` | Automation stability, parser/layout regressions, relative smoke behavior within the same run. |
| Physical tablet | Reading ergonomics, real touch feel, human page-turn behavior, visible quality, performance claims users will feel. |
| Physical phone | Small viewport, OEM file entry, thermal/background variance. |

Never compare an AVD metric against a physical-device metric as if they are the
same evidence tier.

## Common Setup

For each app and each device tier:

1. Record device model, Android version, resolution, density, refresh rate if available, battery/thermal mode, and orientation.
2. Install the target app.
3. Clear app data before first-run tests.
4. Push the shared corpus to the same device folder, e.g. `/sdcard/Download/rfmr/`.
5. Open through the same route for both apps: first system file manager / DocumentsUI on physical devices; shell `ACTION_VIEW` only for AVD automation.
6. Capture `logcat -c` before each scenario and collect app-scoped fatal/ANR/OOM/security/file/recycled-bitmap lines after.
7. Capture screenshots and UI XML at first content, after mode changes, and at sampled pages.

## Scenario Matrix

| ID | Scenario | Required action | Primary metrics |
| --- | --- | --- | --- |
| S1 | Cold open from file entry | Clear app data, open EPUB/TXT/PDF through the same entry route. | `am start -W` total time, first visible marker, blocking dialogs, app-specific critical logcat lines. |
| S2 | EPUB invisible spacer / micro paragraph paging | Open `rf-mr-extreme-20260627.epub`, switch to paged mode when available, sample first 25 page advances. | Blank pages, one-short-block pages, markers per sampled page, unique markers seen, long/tail marker reachability. |
| S3 | Low-vision typography | Set largest practical font and high line spacing in each app, then repeat EPUB page sampling. | Page count growth, minimum markers per real content page, clipping/overlap, controls still reachable. |
| S4 | Scroll/paged mode anchor | Scroll to mid-book TXT/EPUB, switch reading mode if supported, return if supported. | Anchor drift, page/paragraph marker before/after, persisted progress after process restart. |
| S5 | Gesture and reading controls | Page forward/back, tap zones, toolbar show/hide, pinch font/zoom. | Gesture success rate, accidental toolbar activation, zoom/font persistence, visible feedback. |
| S6 | PDF open and page-turn | Open generated PDF, advance 10 pages, rotate once if supported. | First page time, PSS, gfx p90/p95, page marker correctness, bitmap/recycle crashes. |
| S7 | Search / TOC / annotation basics | Search known markers, add bookmark/highlight/note where supported, reopen. | Search hit accuracy, jump accuracy, note persistence, selection stability. |
| S8 | Accessibility smoke | Enable TalkBack, traverse library card, reader surface, toolbar, panels. | Spoken/focused labels, focus order, duplicate invisible nodes, actionable controls. |

## Metrics And Scoring

Each scenario gets both raw measurements and a 0-3 score. Raw numbers win over
scores when they disagree.

| Score | Meaning |
| --- | --- |
| 3 | Passes with strong evidence: no blocking UX, stable markers, no app-critical logs, and behavior remains usable at stress settings. |
| 2 | Functional but degraded: measurable jank, extra taps, mild layout drift, or noisy but non-fatal logs. |
| 1 | Barely usable: frequent blank/one-line pages, lost anchor, poor focus order, severe jank, or confusing controls. |
| 0 | Fails scenario: cannot open, crashes, blocks first read, loses content, or cannot complete the required action. |

Metric definitions:

| Metric | Collection |
| --- | --- |
| Cold open | `adb shell am start -W ...`; record `TotalTime` and first visible marker timestamp when available. |
| Memory | `adb shell dumpsys meminfo <package>` after first stable content and after page sampling. |
| Frame pacing | `adb shell dumpsys gfxinfo <package> framestats` or app-supported proxy; record p90/p95 and janky frames. |
| Page packing | Count expected marker occurrences in each sampled screenshot/XML/text dump. |
| Content reachability | Confirm marker families `RFMR-CJK`, `RFMR-LONG`, `RFMR-TAIL`, `RFMR-LATIN`, and `RFMR-SPINE` are reachable. |
| Accessibility | TalkBack settings snapshot plus manual speech/focus notes; UI XML alone is only proxy evidence. |

## Current LinReads Baseline

Current LinReads evidence before the new shared-corpus run covered the same
failure families:

| Area | Current evidence |
| --- | --- |
| EPUB invisible spacer release black-box | `Dev build #122`, SHA-256 `c39d5b015f3796ad012a565b77e8844ea37ee05af243c61dd4d88ede85d69e87`, AVD MediaStore `ACTION_VIEW`, first continuous screen had 27 real CJK fragments, first body paged counts `20/20/20/20/20/20/20/18`, app critical grep 0. |
| Performance proxy | A-02 TXT/EPUB/PDF first-paint proxy `8110/10416/6922ms`, total PSS `213402/250310/301655KB`, PDF `gfx_p90_ms=150`; AVD noisy proxy, keep `PARTIAL`. |
| Accessibility proxy | A-03 TalkBack settings-on instrumentation `OK (1 test)`, labels/actions covered, settings restored; no human speech/focus traversal claim. |
| SAF / Calibre simulation | SAF export/restore `OK`; Calibre grouped/failure fake-server tests `OK`; not real DocumentsUI or real Calibre LAN/auth. |

## Shared-Corpus AVD Partial Results

Run date: 2026-06-27. Device: only `emulator-5554`
(`sdk_gphone64_arm64`) was online, with tablet-like override `1600x2560` and
density `320`. Corpus was generated under
`/tmp/readflow-moonreader-linreads-corpus` and pushed to
`/sdcard/Download/rfmr/`. MediaStore URIs used by both apps:

| File | MediaStore URI |
| --- | --- |
| EPUB | `content://media/external/file/460` |
| TXT | `content://media/external/file/461` |
| PDF | `content://media/external/file/462` |

Important boundary: LinReads in this run was the already installed local debug
app from current HEAD `25ba892`, not a freshly downloaded release APK.
Moon+ Reader was the local `moonreader-pro.apk`. These are AVD shell
`ACTION_VIEW` results, not physical tablet, real file-manager, real TalkBack
speech, or production performance evidence.

### Moon+ Reader Pro

| Scenario | Evidence |
| --- | --- |
| EPUB `file://` entry | `file:///sdcard/Download/rfmr/rf-mr-extreme-20260627.epub` failed with `FileNotFoundException ... EACCES (Permission denied)`. |
| EPUB `content://` cold open | `am start -W` reached `ActivityTxt` with `TotalTime=1566ms` / `WaitTime=1611ms`, but first content was blocked by `ClickTip` (`Page Up` / `Options` / `Page Down`) and then by Android immersive-mode `Got it`. After dismissing both, screenshot showed `RFMR Chapter 1 Invisible Spacer Stress` and `RFMR-CJK-001..005`. |
| EPUB page taps | Ten right-zone taps produced visible screenshots with `RFMR-CJK-001..048`, `RFMR-MIX-001..068`, and no blank/one-line-page regression in the sampled window. A clean-log follow-up reached `RFMR-TAIL-001..023`. Clean 3-tap window: total PSS `73453KB`, gfx p90/p95 `81/81ms`, and no MoonReader fatal/ANR/OOM in the filtered log. |
| TXT cold open | `content://media/external/file/461`, `text/plain`, reached `ActivityTxt` with `TotalTime=1579ms`; screenshot showed `RFMR-TXT-000001..000020`. PSS `64820KB`; gfx p90/p95 `500/500ms`. Logcat contained `WindowLeaked` from `ActivityMain`, but no observed app death. |
| PDF cold open | `content://media/external/file/462`, `application/pdf`, initially reported `ActivityTxt` `TotalTime=1883ms`, then crashed back to launcher. Logcat recorded `FATAL EXCEPTION: GoldenBoot`, `NullPointerException: println needs a message`, `WINDOW DIED`, and `Process com.flyersoft.moonreaderp ... has died`; no process remained for meminfo. |

### LinReads

| Scenario | Evidence |
| --- | --- |
| EPUB cold open | After `pm clear dev.readflow`, `content://media/external/file/460`, `application/epub+zip`, reached `MainActivity` with `TotalTime=1979ms`. XML and screenshot exposed `RFMR Chapter 1 Invisible Spacer Stress` and `RFMR-CJK-001..027` directly, with reader surface label `阅读内容，捏合调整字号`. PSS `146899KB`; gfx p90/p95 `250/550ms`; no app-level fatal/ANR/OOM/recycled-bitmap grep hit. |
| TXT cold open | `content://media/external/file/461`, `text/plain`, reached `MainActivity` with `TotalTime=2593ms`. XML and screenshot exposed `RFMR-TXT-000001..000028`. PSS `137682KB`; gfx p90/p95 `150/550ms`; no app-level fatal/ANR/OOM/recycled-bitmap grep hit. |
| PDF cold open | `content://media/external/file/462`, `application/pdf`, reached `MainActivity` with `TotalTime=2021ms`. Screenshot showed `RFMR PDF PAGE 001`; XML exposed `阅读内容，捏合缩放页面` and page content description `第 1 页，共 20 页`. PSS `170151KB`; gfx p90/p95 `450/600ms`; no app-level fatal/ANR/OOM/recycled-bitmap grep hit. |
| PDF page-turn | Three left swipes advanced from page 1 to page 4; contact sheet showed `RFMR-PDF-001`, `002`, `003`, and `004` pages. After page sampling, XML exposed `第 4 页，共 20 页`, PSS `168642KB`, gfx p90/p95 `113/121ms`, and no app-level fatal/ANR/OOM/recycled-bitmap grep hit. |

## LinReads Dev Build #124 AVD Release Follow-up

Run date: 2026-06-27. Device: `emulator-5554`, Android 16 / API 36,
tablet-like override `1600x2560` and density `320`. Release artifact:
`Dev build #124`, APK SHA-256
`29fa2db2e576e0ed98b0a25d989d9263f78bbc6758f5b43e20f18d4dbdfb6cd7`.
Fresh MediaStore URIs were EPUB `content://media/external/file/466`, TXT
`467`, and PDF `468`. Evidence root:
`/tmp/readflow-dev124-release-compare-20260627/`.

| Scenario | Evidence |
| --- | --- |
| EPUB release cold open | Clear-data `ACTION_VIEW` reached `MainActivity` with `TotalTime=1064ms` / `WaitTime=1069ms`; XML exposed `RFMR Chapter 1 Invisible Spacer Stress`, `RFMR-CJK-001..027`, and reader surface `阅读内容，捏合调整字号`; `blocked_dialog=False`; PSS `65183KB`. |
| EPUB release paged sampling | After visible `排版 -> 分页`, page 0 was the chapter title, pages 1-3 packed CJK micro paragraphs as `20/20/8`, page 4 was the next chapter title, and pages 5-25 covered `RFMR-MIX-001..053`. Some `pre` blocks were isolated as one content block per page, but the sampled CJK micro paragraphs did not regress to one sentence per page. After page sampling PSS was `56643KB`, gfx p90/p95 `97/105ms`; app-fatal/ANR/OOM/recycled-bitmap grep did not hit. |
| TXT release cold open | Clear-data `ACTION_VIEW` reached `MainActivity` with `TotalTime=904ms` / `WaitTime=916ms`; XML exposed `RFMR-TXT-000001..000028`, reader surface was present, and `blocked_dialog=False`. PSS `60306KB`; gfx p90/p95 `450/450ms`; filtered critical log only contained emulator/GMS noise, not a `dev.readflow` fatal. |
| PDF release cold open/page-turn | Clear-data `ACTION_VIEW` reached `MainActivity` with `TotalTime=1223ms` / `WaitTime=1226ms`; XML exposed `阅读内容，捏合缩放页面` and `第 1 页，共 20 页`. Three left swipes advanced to `第 4 页，共 20 页`. First-page PSS `90979KB`, after-page PSS `96976KB`, first-window gfx p90/p95 `300/350ms`, after-page gfx p90/p95 `133/150ms`; filtered critical log did not hit app fatal/ANR/OOM/recycled-bitmap. |

Same-run staged simulation, after installing the local debug/test APK, covered
current VERIFY/PARTIAL proxies only:

- `A02PerformanceRuntimeSmokeTest` = `OK (1 test)`: TXT/EPUB/PDF first-paint proxy `8514/9999/6799ms`, total PSS `225492/252237/303295KB`, PDF gfx p90 `200ms`; boundary remains AVD instrumentation noise, not a real-device benchmark.
- `A03AccessibilityRuntimeSmokeTest` single rerun = `OK (1 test)`: library card/menu, reader surface action, chrome, TOC/search/bookmark/annotation/font/theme labels; TalkBack settings remained `null/0/0`, so this is XML/action proxy evidence, not human TalkBack speech.
- `BackupSafUiRuntimeSmokeTest` single rerun = `OK (1 test)`: `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT` monitors, schema v1 ZIP, restore of progress/bookmark/annotation; still test ContentProvider/ActivityMonitor, not real DocumentsUI.
- `CalibreGroupedRuntimeSmokeTest` = `OK (2 tests)` and `CalibreDownloadFailureRuntimeSmokeTest` rerun = `OK (1 test)` via `127.0.0.1:18181` + `adb reverse`: explicit URL, cover/download events, offline open, remove download, and failure cleanup all passed. The first failure run was explained by grouped smoke intentionally shutting down the fake server before failure smoke started.

## Moon+ Reader Expected Strengths And Risks To Measure

These are hypotheses derived from local decompiled evidence, not black-box
results for unrun scenarios yet:

| Area | Hypothesis | Evidence basis |
| --- | --- | --- |
| Text layout maturity | Likely strong on TXT/EPUB continuous reading and gesture richness. | Custom `MRTextView`, `MyLayout`, `HtmlToSpannedConverter`, and long-standing `ActivityTxt` gesture paths. |
| Control richness | Likely richer than LinReads for tap/gesture/font/theme variants. | `ActivityTxt` has separate gesture detectors, font size coarse/fine seekbars, day/night actions, and many preference screens. |
| Architecture/testability | Likely weaker for deterministic regression isolation. | Research docs identify global static state and large activity/class surfaces. |
| PDF features | Likely richer PDF behavior, but implementation is vendor-library dependent. | `PDFReader` uses a separate PDF layout path and manifest includes Radaee UI activities. |
| First-run friction | Confirmed on AVD EPUB shell entry. | `content://` EPUB reached `ActivityTxt`, but first content was blocked by MoonReader `ClickTip` and then Android immersive-mode `Got it`. |

## Result Table Template

Fill this only with evidence from the exact shared corpus.

| Scenario | LinReads raw result | LinReads score | Moon+ raw result | Moon+ score | Winner / note |
| --- | --- | ---: | --- | ---: | --- |
| S1 EPUB cold open | Dev build #124 release: clear-data `content://` cold open `TotalTime=1064ms`; first XML exposes title and `RFMR-CJK-001..027`; PSS `65183KB`; no blocking dialog. Earlier local debug run was `1979ms` / PSS `146899KB`. | 2 | `file://` failed with EACCES. `content://` cold open reached `ActivityTxt` in `1566ms`, but `ClickTip` + immersive `Got it` blocked first content; after dismiss, screenshot exposed title and `RFMR-CJK-001..005`. | 2 | LinReads release has less first-read friction and strong XML exposure; Moon+ needs extra dismissals. |
| S1 TXT cold open | Dev build #124 release: `TotalTime=904ms`; XML exposes `RFMR-TXT-000001..000028`; PSS `60306KB`; no blocking dialog. Earlier local debug run was `2593ms` / PSS `137682KB`. | 2 | `TotalTime=1579ms`; screenshot exposes `RFMR-TXT-000001..000020`; PSS `64820KB`; `WindowLeaked` appears but no observed app death. | 2 | LinReads release wins cold-start in this rerun and exposes text nodes; Moon+ remains lower/noisier memory in the older window but with leak noise. |
| S1 PDF cold open | Dev build #124 release: `TotalTime=1223ms`; XML exposes `第 1 页，共 20 页`; PSS `90979KB`; no app-fatal grep hit. | 2 | `TotalTime=1883ms` then app crashes to launcher with `FATAL EXCEPTION: GoldenBoot` / `NullPointerException: println needs a message`; no process left. | 0 | LinReads wins this corpus PDF. |
| S2 EPUB stress paging | Dev build #124 release: visible `排版 -> 分页`; sampled pages 0-25. CJK micro paragraphs packed as `20/20/8`; mixed-style pages reached `RFMR-MIX-053`; no sampled CJK one-sentence-page regression. Some `pre` blocks remain isolated by style. | 2 | Ten page taps sampled `RFMR-CJK-001..048` then `RFMR-MIX-001..068`; clean follow-up reached `RFMR-TAIL-001..023`; no blank/one-line sampled page. | 2 | Partial tie on AVD. Moon+ reached tail markers sooner in its tap window; LinReads release now closes the prior same-corpus paged sampling gap. |
| S3 low-vision typography | pending shared-corpus run | - | pending | - | pending |
| S4 mode anchor | pending shared-corpus run | - | pending | - | pending |
| S5 gestures | pending shared-corpus run | - | pending | - | pending |
| S6 PDF page-turn | Dev build #124 release: three left swipes advanced from page 1 to page 4; XML exposed `第 4 页，共 20 页`, after-page PSS `96976KB`, gfx p90/p95 `133/150ms`, no app-fatal grep hit. | 2 | Cannot complete because S1 PDF crashes. | 0 | LinReads wins on this corpus. |
| S7 search/annotation | pending shared-corpus run | - | pending | - | pending |
| S8 TalkBack | Not run in this shared-corpus pass. XML proxy is strong for text/PDF page label, but this is not TalkBack speech evidence. | - | Not run in this shared-corpus pass. EPUB/TXT text is visible in screenshots but not exposed as standard text nodes in XML, so TalkBack risk remains unverified. | - | Requires real TalkBack traversal. |

## Next Execution Steps

1. Complete same-corpus S3/S4/S5/S7/S8 for both apps on AVD where feasible,
   keeping debug/test APK instrumentation separate from release black-box data.
2. Repeat the subset S1/S2/S3/S5/S6/S8 on the physical tablet after it is
   connected.
3. Run real TalkBack speech/focus traversal and real DocumentsUI/OEM file
   manager entry on physical devices; keep XML/action proxy evidence separate.
4. Keep AVD, physical tablet, TalkBack speech, and performance claims separate
   in the result table.

## Boundary

This document is the comparison protocol and current evidence map. It is not yet
the final comparison result because the shared-corpus run is still only an AVD
partial pass: S3/S4/S5/S7/S8 remain unrun as same-corpus cross-app scenarios,
Moon+ PDF crashes before page-turn testing, the staged LinReads A-02/A-03/SAF/
Calibre checks are debug/test APK simulations rather than release or real-device
proof, and no physical tablet or real TalkBack speech run is recorded.
