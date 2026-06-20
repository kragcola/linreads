# LinReads Architecture Docs Tracker

_最后更新：2026-06-19_

Mode: `task`
Status: `active`
Owner: Codex

## Objective

把 LinReads 当前架构设计、审计结论、待办、Android Phase 1/2 实装状态和恢复路径整理成可跨会话接续的 tracker。2026-06-19 已获用户放行 Android Phase 1 地基与 TXT 最小阅读链路实装；跨平台 Web/HarmonyOS 实现仍需另行许可。

## Recovery Summary

- `AGENTS.md`: missing.
- `.workspace/agent-session-state.md`: missing.
- `docs/tracking/ACTIVE.md`: 已规范为短入口，并指向本 tracker；2026-06-19 更新为 Android TXT 最小阅读链路 objective。
- `docs/tracking/BACKLOG.md`: 记录 41 项历史发现与处理状态，是待办来源。
- 当前 `git status --short docs/tracking maintenance-log.md CLAUDE.md` 显示 `docs/tracking/`、`CLAUDE.md`、`maintenance-log.md` 尚未纳入 git 跟踪。
- 当前 wiki `docs/wiki/Active-Work.md` 仍停在 2026-06-18/HEAD `44f0a64`，没有反映 Phase 1 地基和 TXT slice，需要后续刷新。

## Source Of Truth

优先顺序：

1. `docs/android-architecture-v4.md`
2. `docs/android-p1-foundation-plan.md`
3. `docs/android-p2-txt-minimal-slice.md`
4. `docs/audit/root-cause-resolution-2026-06-18.md`
5. `docs/audit/decisions-2026-06-18.md`
6. `docs/android-architecture-v3.md`（历史/审计溯源）
7. `docs/tracking/BACKLOG.md`
8. `maintenance-log.md`

当前 Android 权威规范是 v4，P1/P2 执行状态以 P1/P2 plan 和 `maintenance-log.md` 2026-06-19 条目为准。不要从已废弃的 v1/v2 架构文档重新推导结论，除非它们与 v4 或 root-cause 决议发生矛盾时需要定位历史来源。

## Live Todo

- [x] 将 `docs/tracking/ACTIVE.md` 收短为恢复入口。
- [x] 创建 active tracker，承接 live todo、决策、验证、回滚、分区进度。
- [x] 在 tracker 中记录当前恢复证据和缺失文件。
- [x] 历史项：核验并统一 v3 阶段入口/追踪文档中的 `19` / `20` / `21` 模块说法；当时权威口径为 `21` 模块，历史审计/研究材料保留原文。
- [x] 历史项：收口架构入口；v4 发布后 Android 当前权威规范已升级为 `docs/android-architecture-v4.md`。
- [x] 刷新 `docs/architecture.md`、wiki 入口和平台页中旧 `epubjs`/`epublib`/Android 渲染状态描述；当前 Web 仍使用 epubjs 的事实保留，目标架构标注为 epub-ts。
- [x] 完成 Android v3 框架架构重审，按“用户使用轻量”而非“开发轻量/少模块”校准结论，记录在 `docs/audit/android-v3-framework-audit-2026-06-18.md`。
- [x] 用户已放行 Android Phase 1 地基 + TXT 最小阅读链路实装（2026-06-19）。
- [x] Phase 1 地基框架落地：`build-logic`、9 个模块、`:app`，`-Preadflow.phase=1 :app:assembleDebug` SUCCESSFUL。
- [x] Phase 2 TXT 垂直切片落地：`render:api`/`render:txt`/`render:animate`/`features:reader` + phase2 app wiring，`-Preadflow.phase=2 :app:assembleDebug` SUCCESSFUL。
- [ ] 刷新 `docs/wiki/Active-Work.md` / `docs/wiki/Home.md` / `docs/wiki/Development-Guide.md` 的 2026-06-18 旧状态与命令口径。
- [ ] 真机/AVD 验证 TXT 滚动 + progression 回报（待装 system-image）。
- [ ] 沿 P1 §6 边界推进业务：Calibre 拉取、Room 查询、本地导入、书库数据绑定、进度持久化。
- [ ] Web/HarmonyOS 实现仍需用户另行许可后再动。

## Decisions

- Active work 模式为 `task`，不是 `bug`；因此没有 Test Ledger。
- `ACTIVE.md` 只保留恢复所需的最短状态，详细信息放入本 tracker。
- 当前默认权威架构为 `docs/android-architecture-v4.md`；v1/v2/v3 仅作为历史背景或审计溯源。
- P0 构建阻塞项按 `BACKLOG.md` 现状视为已处理，但实现阶段仍需用真实构建验证。
- 模块数当前权威口径为 v4 的 22 模块；历史审计/研究文档中的 19/20/21 作为当时状态保留。
- EPUB 口径区分“当前实现”和“目标架构”：Web 当前仍是 epubjs；Android v4 已裁决为原生重排（jsoup→AnnotatedString，去 WebView/epub-ts/CFI）。
- 用户侧轻量已进入 v4 `User-Light Architecture Contract`；当前实现应继续避免把引擎选择、账号、Calibre 配置、危险权限等复杂度泄露给用户。
- `:app` 使用 phase 条件 sourceSet：`src/phase1` 零 render 依赖，`src/phase2` 绑定 TXT slice，避免 phase1 解析 phase2-only 模块失败。

## Per-Section Progress

| Section | Status | Evidence |
| --- | --- | --- |
| Project rename | Done | `ACTIVE.md` and `maintenance-log.md` record LinReads rename. |
| Architecture audit | Done | v1/v2/Round 3 audit docs exist under `docs/audit/`. |
| v3 architecture | Done | `docs/android-architecture-v3.md` exists and `docs/architecture.md` points to it. |
| Backlog consolidation | Done | `BACKLOG.md` module-count wording now points to 21 modules. |
| Doc drift cleanup | Done | `README.md`, `CLAUDE.md`, `docs/architecture.md`, and current wiki pages distinguish current epubjs from target epub-ts and remove old epublib/MuPDF-for-EPUB target wording. |
| Android v3 framework audit | Done | `docs/audit/android-v3-framework-audit-2026-06-18.md` redefines lightness as user-facing lightness, not reduced developer complexity; it recommends a `User-Light Architecture Contract` before implementation. |
| Continuity tracking | In progress | `ACTIVE.md` now names this active tracker. |
| Android v4 architecture | Done | `docs/android-architecture-v4.md` is current authority and records native EPUB reflow, 22 modules, phase includes, and User-Light contract. |
| Phase 1 foundation | Done | `android/build-logic/`, 9 modules, `:app`; maintenance log records `-Preadflow.phase=1 :app:assembleDebug` SUCCESSFUL. |
| Phase 2 TXT slice | Done | `:render:api`, `:render:txt`, `:render:animate`, `:features:reader`, phase2 app wiring; maintenance log records `-Preadflow.phase=2 :app:assembleDebug` SUCCESSFUL. |
| Wiki current-state sync | Pending | `docs/wiki/Active-Work.md` still says 2026-06-18 and Android TXT ❌. |
| Device verification | Pending | AVD/system-image not installed yet; TXT scrolling/progression still needs runtime check. |

## Verification

Commands already run during tracker normalization:

```bash
sed -n '1,220p' /Users/kragcola/.codex/skills/omubot-continuity/SKILL.md
sed -n '1,220p' docs/tracking/ACTIVE.md
sed -n '1,280p' docs/tracking/BACKLOG.md
test -f AGENTS.md && sed -n '1,180p' AGENTS.md || printf 'AGENTS.md: MISSING\n'
test -f .workspace/agent-session-state.md && sed -n '1,180p' .workspace/agent-session-state.md || printf '.workspace/agent-session-state.md: MISSING\n'
git status --short docs/tracking maintenance-log.md CLAUDE.md
rg -n "19 个模块|20 模块|20个模块|21 个模块|21个模块|模块数|模块计数" docs README.md CLAUDE.md .claude/skills -g '!references/**'
```

Observed results at normalization time:

- `omubot-continuity` requires `ACTIVE.md` plus an active tracker for long-running/context-continuity work.
- `AGENTS.md` is missing.
- `.workspace/agent-session-state.md` is missing.
- `docs/tracking/ACTIVE.md` and `docs/tracking/BACKLOG.md` exist.
- Module-count references still drifted across historical/tracking docs; this was resolved for current entry/tracking docs during drift cleanup.

Commands run during drift cleanup:

```bash
rg -n "epubjs|epub-ts|epublib|19 个模块|20 个模块|21 个模块|尚未实现渲染|空文件/stub|全原生零 WebView|自研动画引擎|MuPDF \\(JNI\\)|硬编码为|BASE_URL" README.md CLAUDE.md docs/wiki docs/tracking docs/architecture.md -g '!docs/wiki/Android-Architecture-Audit.md' -g '!docs/wiki/Architecture-Comparison.md' -g '!docs/wiki/Ink-Architecture-Gap.md' -g '!docs/wiki/Research-MoonReader.md'
rg -n "尚未实现渲染|空文件/stub|全原生零 WebView|自研动画引擎|epublib 版本|MuPDF \\(JNI\\) \\| 待定|统一为 19|20 个模块|20 模块" README.md CLAUDE.md docs/architecture.md docs/wiki/Home.md docs/wiki/Architecture.md docs/wiki/Platform-Web.md docs/wiki/Platform-HarmonyOS.md docs/wiki/Rendering-Engine.md docs/wiki/Development-Guide.md docs/wiki/Active-Work.md docs/wiki/Calibre-API.md docs/tracking docs/wiki/_Sidebar.md maintenance-log.md
rg -n "epubjs|epub-ts|epublib|19 个模块|20 个模块|21 个模块|BASE_URL|Readflow|readflow-web|scaffold" README.md CLAUDE.md docs/architecture.md docs/wiki/Home.md docs/wiki/Architecture.md docs/wiki/Platform-Web.md docs/wiki/Platform-HarmonyOS.md docs/wiki/Rendering-Engine.md docs/wiki/Development-Guide.md docs/wiki/Active-Work.md docs/wiki/Calibre-API.md docs/wiki/_Sidebar.md docs/tracking maintenance-log.md
```

Observed cleanup result:

- Current docs now intentionally distinguish `epubjs` current Web implementation from `epub-ts` target architecture.
- `BACKLOG.md` no longer claims module count unified to 19.
- HarmonyOS docs no longer claim `BookList.ets` is an empty stub.
- Current entry/wiki/tracking docs no longer contain misleading target-state claims for `epublib`, Android MuPDF-for-EPUB, full-native-zero-WebView, self-built AnimationEngine, or “BookList empty stub”.
- README/wiki positioning now distinguishes current Web EPUB/PDF capability from target multi-format support.
- Remaining `epubjs`, `Readflow`, `BASE_URL`, and `scaffold` hits are either current implementation facts, explicit target-vs-current notes, or historical maintenance-log entries.

Commands run during user-light re-audit:

```bash
rg -n "v3-lite|直接按 21|少人团队实施|21 物理模块|物理模块.*过重|轻量性.*团队|开发轻量|少建模块|User-Light|用户轻量|用户使用轻量" docs/audit/android-v3-framework-audit-2026-06-18.md docs/tracking/linreads-architecture-docs-2026-06-18.md maintenance-log.md
git status --short docs/audit/android-v3-framework-audit-2026-06-18.md docs/tracking/linreads-architecture-docs-2026-06-18.md maintenance-log.md
wc -l docs/audit/android-v3-framework-audit-2026-06-18.md docs/tracking/linreads-architecture-docs-2026-06-18.md maintenance-log.md
```

Observed re-audit result:

- Current audit/tracker/log no longer recommend `v3-lite` or reduced physical modules as the primary lightness strategy.
- Current audit defines lightness as user-facing installation, first-run, reading, configuration, data portability, and permissions burden.
- `docs/audit/android-v3-framework-audit-2026-06-18.md`, `docs/tracking/linreads-architecture-docs-2026-06-18.md`, and `maintenance-log.md` are still untracked in current git status.

Commands run during 2026-06-19 current-change tracking:

```bash
git status --short --branch
sed -n '1,220p' docs/tracking/ACTIVE.md
sed -n '1,320p' maintenance-log.md
sed -n '1,260p' docs/android-p1-foundation-plan.md
sed -n '1,280p' docs/android-p2-txt-minimal-slice.md
git diff --stat
git diff --name-status
git ls-files --others --exclude-standard | sed -n '1,240p'
sed -n '1,260p' android/render/api/src/main/kotlin/dev/readflow/render/api/ReaderEngine.kt
sed -n '1,320p' android/render/txt/src/main/kotlin/dev/readflow/render/txt/TxtVirtualPagerEngine.kt
sed -n '1,260p' android/features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderViewModel.kt
```

Observed 2026-06-19 current state:

- `docs/tracking/ACTIVE.md` and `maintenance-log.md` agree that the implementation gate has been opened for Android Phase 1 + TXT minimal slice.
- Tracked diff only shows a small part of the work; most implementation/doc files are untracked and must be included intentionally in any handoff/commit.
- Actual new code includes phase-specific app source sets, `ReaderEngine` contract, `ReaderEngineRegistry`, TXT `RecyclerView` engine, no-transition host, reader ViewModel/screen, core model/data/ui modules, Room schemas, build-logic plugins, and Gradle wrapper/settings/catalog.
- `docs/wiki/Active-Work.md`, `Home.md`, and `Development-Guide.md` still lag behind the 2026-06-19 implementation state.

## Risks

- Web code still uses `epubjs`; Web migration remains separate work.
- Android EPUB target is now v4 native reflow; do not reintroduce Android WebView/epub-ts/nanohttpd/CFI unless a new ADR explicitly overturns v4.
- Current TXT slice is deliberately minimal: UTF-8 whole-file read, paragraph split, continuous `RecyclerView`; FileChannel 64KB streaming, ICU charset detection, real import, chrome, search/TOC/selection, persistent progress are not done.
- Historical audit/research/deprecated pages still contain old terms by design; do not bulk rewrite history unless explicitly requested.
- A large portion of Android implementation and docs is untracked (`docs/`, `android/build-logic/`, `android/core/`, `android/render/`, `android/features/`, Gradle wrapper/settings/catalog, `maintenance-log.md`, `CLAUDE.md`, MoonReader references). A handoff or commit must include/exclude files intentionally.
- Generated/local files such as `.codegraph/`, `.reasonix/`, `.vscode/`, `.DS_Store`, and decompiled APK artifacts need explicit commit policy before staging.

## Rollback

This normalization is documentation-only. To roll back:

1. Restore the previous verbose `docs/tracking/ACTIVE.md` from git or backup.
2. Delete `docs/tracking/linreads-architecture-docs-2026-06-18.md`.
3. Remove the `Continuity Tracker Normalization` entry from `maintenance-log.md` if that entry has already been added.

## Do Not Re-Investigate

- Do not redo broad architecture audits from scratch; use v4 + P1/P2 execution docs + maintenance log for current state.
- Do not re-open the Android EPUB engine decision unless new evidence contradicts v4 native reflow.
- Do not re-add phase2 render dependencies to phase1 app wiring; keep phase-specific source sets unless v4 phase include design changes.
