# Android Comic Reader Research And Architecture

> Date: 2026-07-27
> Scope: LinReads Android local and remote comic reading
> Decision: ship a MuPDF-free CBZ reader first; stage webtoon, spreads, CBR and remote sources separately.

## Existing-project cross-audit

No Claude-authored comic research result exists under `.claude/`; that directory only contains
skills. The repository does contain older generic engine comparisons and the accepted
`docs/audit/adr-mupdf-license-2026-06-23.md`. The useful conclusions from those documents are:

- Android v4 is a native reader, so the older foliate-js/WebView recommendation is stale.
- MuPDF would add AGPL/commercial-license and native-package costs.
- The MuPDF ADR explicitly names a first-party ZIP image pager as the preferred CBZ route.

The Claude-result comparison requested for this task is therefore skipped; the existing MuPDF
ADR is used as the local architectural baseline.

## External projects checked

Official GitHub repository metadata and source trees were checked on 2026-07-27.

| Project | License/status | Relevant evidence | LinReads use |
|---|---|---|---|
| [Mihon](https://github.com/mihonapp/mihon) | Apache-2.0, active, about 22.2k stars | Separate pager/webtoon viewers, `ArchivePageLoader`, ComicInfo metadata and direction-aware navigation | Primary interaction and loader benchmark; concepts only, no source copy |
| [Komga](https://github.com/gotson/komga) | MIT, active | REST, OPDS v1/v2, multiple reader modes, whole-series downloads | Preferred shape for a future self-hosted remote comic catalog |
| [Suwayomi Server](https://github.com/Suwayomi/Suwayomi-Server) | MPL-2.0, active | Server-side manga-source execution, downloads and OPDS ecosystem | Arbitrary scraper/source execution belongs on an isolated server, not in the LinReads APK |
| [Kavita](https://github.com/Kareadita/Kavita) | GPL-3.0, active | Broad archive support and manga-oriented server/reader workflows | Product benchmark only; do not embed or copy GPL implementation |
| [Kotatsu](https://github.com/KotatsuApp/Kotatsu) | GPL-3.0, archived | CBZ and standard/webtoon reader precedent | Historical UX reference only; not a dependency base |
| [ZoomImage](https://github.com/panpf/zoomimage) | Apache-2.0, active | Android View zoom, pan, rotation and large-image subsampling | Adopted at pinned version 1.4.0; newer 1.6.0 metadata is incompatible with project Kotlin 2.1.10 |

## Architecture decision

### Stage 1: local CBZ, implemented

- `:render:cbz` owns ZIP indexing and page extraction; no MuPDF, WebView or native archive engine.
- Natural filename ordering covers common `1`, `2`, `10` page naming.
- ComicInfo `Manga=YesAndRightToLeft` controls ViewPager direction and physical edge tap mapping.
- The current page and up to two neighbours on each side are retained (five prepared pages at most).
  Navigation epochs cancel stale prefetch work, and extraction checks cancellation between IO blocks.
- ZoomImage performs viewport-scale rendering and large-image subsampling rather than decoding an
  entire high-resolution page into an app-owned bitmap cache. Its non-visible geometry drawable is
  strictly smaller than the source and EXIF-aware; tile fade is disabled, so page turns never expose
  a low-quality preview phase. Degenerate one-pixel images use a bounded direct-bitmap fallback.
- ZIP entry count, per-entry bytes, live extracted bytes, compression ratio and metadata size are
  bounded. Non-file sources are capped before ZIP indexing, and extraction rechecks the ratio against
  actual output bytes. Generated cache filenames ensure archive paths never reach the filesystem.
- Import, folder scan, Android share/open intents, cover extraction and engine registration use the
  same local-format capability contract.

Progress remains a stable `LocatorStrategy.Page(index,total)`. This also matches the existing
fixed-layout bookmark and ink-anchor model without inventing a comic-only persistence format.

### Stage 2: comic reading modes

Add per-book viewer preferences after Stage 1 runtime acceptance:

1. Vertical continuous/webtoon mode with recycler-backed page holders and viewport-neighbour tile
   prefetch, not one giant concatenated bitmap.
2. Single-page and two-page spread modes, including cover-page offset and landscape auto-spread.
3. Fit-width, fit-height and original-ratio policies. Preserve pan/zoom while on a page; reset it only
   after a settled page change.
4. Direction, mode and fit policy stored per book, with ComicInfo as the initial default rather than
   an immutable override.

### Stage 3: additional archives

- CBR/RAR/7z support must not silently add an unreviewed native or copyleft binary to the base APK.
- Prefer server-side conversion to CBZ for remote libraries. A local extractor requires a separate
  license, ABI, malformed-archive, package-size and device-memory ADR.
- Fixed-layout EPUB manga remains an EPUB-engine capability and should not be disguised as CBZ.

### Stage 4: remote comic catalogs

- Treat Komga/OPDS-PSE or a Suwayomi-style service as a remote `BookSource` adapter.
- Keep downloads, series/author grouping and multi-select in the existing library domain.
- Do not execute arbitrary website rules or third-party extension code inside the Android process.
  The server boundary provides update isolation, credential isolation and a smaller mobile attack
  surface.

## Self-audit

- **License:** shipped path is project code plus Apache-2.0 ZoomImage. No AGPL/GPL implementation or
  MuPDF binary enters the APK.
- **Performance:** bounded neighbour retention and tile decoding avoid full-book bitmap residency;
  stale extraction is cancellable. Real tablet frame pacing and memory remain release gates.
- **Correctness:** natural order, RTL metadata and physical input direction, stable page locators,
  invalid-image handling, decoder-failure publication and cache cleanup have focused tests.
- **Security:** ZIP paths are never materialized, and decompression budgets are enforced using actual
  bytes as well as declared metadata.
- **Accessibility:** pages expose current/total descriptions and failed-page text. Image OCR and panel
  alternatives are not claimed.
- **Known gaps:** webtoon, spreads, CBR/7z, ComicInfo page-role metadata and remote manga services are
  deliberately staged rather than hidden inside the MVP. Emulator evidence cannot replace final
  tablet checks for frame pacing, memory pressure, zoom ergonomics and TalkBack speech.

This decision optimizes for LinReads' current priorities: smooth local reading, predictable memory,
small licensing risk and reuse of the existing library/reader contracts.
