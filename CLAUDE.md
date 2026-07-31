# CLAUDE.md

## Commands

| Platform | Task | Command |
|----------|------|---------|
| Web | Dev server | `cd web && npm run dev` |
| Web | Build | `cd web && npm run build` |
| Web | Type check | `cd web && npx tsc --noEmit` |
| Android | Local builds/tests | Disabled after 2026-07-31 handoff; use GitHub Actions/cloud build only |
| Android | Full regression / R8 / OTA build / test APKs | GitHub Actions only |
| Android | Push OTA update to tablet | `git push` → Actions builds → tablet notified automatically |
| HarmonyOS | Build | DevEco Studio → Build → Build Hap |

## Architecture

三端阅读器（Android · HarmonyOS · Web），通过 Calibre Content Server 局域网接入书库。

```
Calibre Content Server (LAN, default :8080)
              │ HTTP REST
  ┌───────────┼───────────────────┐
  │           │                   │
Web         Android           HarmonyOS
(React18    (Kotlin +         (ArkTS +
 + epubjs*)  Ktor client)      @ohos.net.http)
  │
/calibre proxy (Vite dev) / nginx (prod)
```

`*` Web 当前实现是 epubjs（WebView/浏览器 JS 引擎）。**Android v4 不复用此路线**：EPUB 走自研原生重排（jsoup 解析 → Compose AnnotatedString），去 WebView/epubjs/CFI（见 v4 §12.3 ADR-EPUB-Engine + `docs/audit/external-benchmark-audit-2026-06-19.md`）。三端仅在 `shared/api` 类型契约层对齐，不要求引擎层一致。

Key design choices:
- **Shared type contract** — `shared/api/calibre-contract.ts` 是三端共同的 Calibre API 类型定义；改 API shape 必须同步三端实现
- **Calibre proxy（Web）** — dev 阶段 Vite 将 `/calibre/*` 代理到 `VITE_CALIBRE_URL`，绕 CORS；Android/HarmonyOS 直连 LAN IP
- **格式能力** — Android base 支持 EPUB/PDF/TXT/MD/CBZ；DOCX 延期。EPUB 渲染：Web 用 epubjs，**Android 用原生重排（jsoup→AnnotatedString，去 WebView）**；PDF 使用系统 PdfRenderer；CBZ 使用第一方 ZIP 图片分页器
- **三端进度同步** — 待实现；策略设计见 `.claude/skills/linreads-sync/SKILL.md`
- **EPUB 渲染** — Web：epubjs（已知陷阱见 `.claude/skills/linreads-epub/SKILL.md`）。Android：自研原生重排（`ZipFile`+jsoup 解析 → Compose `AnnotatedString`，参考 Myne `EpubParser`），无 WebView/CFI，定位用 spine+章节内字符偏移+progression
- **引擎策略** — 复用成熟开源引擎（PdfRenderer/MuPDF/Markwon）；自研：TXT 大文件 TxtVirtualPager（~300行）+ Android EPUB 原生重排解析/渲染

## Platform Status

| 平台 | 书库列表 | EPUB 阅读 | PDF 阅读 | 进度同步 |
|------|---------|----------|---------|---------|
| Web | ✅ | ✅ 基础 | ✅ iframe | ❌ 待做 |
| Android | ✅ v4lite | ✅ 原生重排 | ✅ PdfRenderer | ⚠️ LWW骨架 |
| HarmonyOS | ✅ 基础列表 | ❌ 待做 | ❌ 待做 | ❌ 待做 |

Android v4lite：书库（本地导入 + Calibre LAN）、TXT/EPUB/PDF 三引擎、设置持久化、进度保存、OTA 更新。

## Skill 自动触发

涉及以下范围的任意改动或审计任务，**必须**先 invoke 对应 Skill，再开始具体工作。本规则覆盖模型默认的相关性判断，不依赖用户显式 `/skill-name`。

| Skill | 强制触发范围 |
|-------|------------|
| `android-native-dev` | `android/**` 任意代码改动；措辞含「Material Design/Compose/Gradle/构建/无障碍/触摸目标」；补充 linreads-dev 的通用 Android 标准层 |
| `linreads-dev` | `web/src/**`、`android/**`、`harmony/**`、`shared/**` 任意代码改动；任务措辞含「实现/开发/加/改/修/新增/重构」 |
| `linreads-epub` | `web/src/pages/Reader.tsx`、任何含 epubjs/epub-ts/CFI 的文件；措辞含「EPUB/epubjs/epub-ts/CFI/书签/章节/渲染/翻页」 |
| `linreads-sync` | `shared/**`、任何含 sync/progress 的文件；措辞含「进度同步/LWW/书签同步」 |
| `accessibility` | 任意 UI 文件改动（`.tsx`/`.ets`/`.kt` 含布局/样式）；措辞含「UI/界面/按钮/颜色/对比度/无障碍」 |
| `design-audit` | 措辞含「视觉/UX/设计/布局/样式/好看/审查」 |
| `tdd` | 措辞含「写测试/test/spec」或新增功能需要配套测试时 |
| `systematic-debugging` | 措辞含「bug/报错/错误/为什么/不工作/不对/排查」 |

例外（可跳过 skill）：单文件 typo 修正、纯 grep/Read 探索且不会触发 Edit、与上述范围完全无关的工作。

compact 后恢复：若 system-reminder 已注入对应 `### Skill:` 内容，视为已加载，**不再重复 invoke**。新会话首次触及上述范围必须 invoke 一次。

compact 后任务判定：system-reminder 注入的 `### Skill:` 段尾若带 `ARGUMENTS:` 字段，那是会话早期的历史入参，不是当前任务。以 compact summary 的 Primary Request and Intent 为准；当 ARGUMENTS 与 summary 冲突时，**忽略 ARGUMENTS**，如不确定主动向用户确认一次。

## Dev Testing — OTA 推送更新

### Build resource policy

- **2026-07-31 user override:** after the current handoff, do not run local Android Gradle builds, local Android unit tests, `assemble*`, `connected*`, or local test-APK generation. Treat GitHub Actions/cloud build as the only Android build/test artifact producer. ADB installs, device logs, UI XML, package state, and other non-build diagnostics remain allowed.
- Full Android regression suites, R8/minification, `:app:assembleOta`, and
  `:updater-helper:assembleOta` run in GitHub Actions only.
- Local Android verification is limited to non-build diagnostics and already-produced artifacts. Do not create new local Android APKs or test outputs.
- After pushing, monitor `.github/workflows/android-release.yml` through completion and verify the published `dev-latest` APK; do not duplicate that release build locally.

### Release lanes

1. **Local lane**: freeze one problem packet, review its exact diff/allowlist, then run static contract checks and device/log diagnostics that do not invoke Android local builds.
2. **Cloud lane**: make one scoped commit and let GitHub Actions own full regression, R8, dual-APK signing/building, publication, and byte verification. A failed run returns to one explicit root cause; do not stack speculative fixes.
3. **Device lane**: start only after CI and release metadata are verified. Bootstrap the production helper and helper-aware app once without clearing data, then validate the next OTA in one physical-device batch. Capture text logs, package/session state, version/signature, task stack, and library counts; the main agent does not load screenshots.

Each iteration freezes one acceptance matrix and one build identity. Collect all device findings before changing code, group them by root cause, and use those findings to design the next iteration. Documentation and tracker-only pushes do not publish Android builds; use `workflow_dispatch` when the same source revision needs a deliberate OTA acceptance bump.

Do not interleave local builds, emulator runs, ADB installs, and manual device checks between small edits. The order for an OTA packet is always static review/non-build diagnostics -> CI full/R8/publication -> one final device acceptance batch.

改完代码后，先检查工作树并只暂存本次文件，再推送到远端平板：

```bash
git status --short
git add <files changed for this release>
git commit -m "feat: ..."
git push
```

自动流程：
1. `git push` → GitHub Actions 触发（见 [`.github/workflows/android-release.yml`](.github/workflows/android-release.yml)）
2. Actions 运行 `phase=2` 全量回归并构建经过 R8/minification 的 app/helper OTA APK
3. 先上传并下载校验 `app-ota-<versionCode>.apk` 与 `updater-helper-ota-<versionCode>.apk`，再刷新兼容旧客户端的固定资产，最后更新 [`dev-latest` Release](https://github.com/kragcola/linreads/releases/tag/dev-latest) 元数据
4. 平板切到前台后约 8 秒：弹出系统通知「LinReads 新版本可用」
5. 点通知 → 下载 → 系统安装器 → 完成

**平板首次配置（一次性）：**
- 设置 → 应用 → 特殊权限 → 安装未知来源应用 → 允许 LinReads
- 从同一条已验真的 Release 安装一次 `updater-helper-ota-<versionCode>.apk`；helper 不下载 APK，也不申请安装未知来源权限
- 无需 USB/ADB/开发者模式

**相关文件：**
- [`app/src/phase2/java/dev/readflow/updater/`](android/app/src/phase2/java/dev/readflow/updater/) — 更新检查/下载/安装逻辑
- `BuildConfig.GITHUB_REPO` = `kragcola/linreads`，`BUILD_TAG` 由 CI 注入

## Workflow

涉及 EPUB 渲染的改动：先读 `linreads-epub` SKILL。**Web 端**：检查 CFI 书签兼容性。**Android 端**：无 CFI（v4 ADR-EPUB-Engine，定位用 spine+charOffset+progression），改代码后在真实 EPUB 文件上验证（别只用合成数据）。

涉及进度同步的改动：先读 `linreads-sync` SKILL → 明确 LWW/Union 策略 → 离线优先（本地先写，后台同步）。

涉及三端共用类型改动：改 `shared/api/calibre-contract.ts` → 同步 `web/src/services/calibre.ts`、`android/.../CalibreClient.kt`、`harmony/.../CalibreService.ets`。

## Config

Web 端在 `web/.env.example` 里查看环境变量；复制为 `.env.local` 修改 `VITE_CALIBRE_URL`。

HarmonyOS 端 `CalibreService.ets` 里 `BASE_URL` 默认 `192.168.1.1:8080`，需通过 Settings 页持久化改写。

Android 端 `CalibreClient` 初始化时传 `baseUrl`，来源待接 Settings SharedPreferences。

## Architecture Docs

- **[Android Architecture v4](docs/android-architecture-v4.md)** — 当前权威规范（22 模块，8 层）；取代 v2/v3
- [全部审计报告](docs/audit/) — v1/v2/v3 终审 + 两轮 v3 框架审计
- [参考项目](references/) — readbooks-v2-android / episteme / epub-ts
- [历史审计台账](docs/tracking/BACKLOG.md) — 已消解的 v1/v2/v3 审计项（非实时 backlog；现网恢复见 [ACTIVE.md](docs/tracking/ACTIVE.md)）

## Language

Chinese: user-facing strings, UI labels. English: code, comments, commit messages, logs.

## Hybrid Subagent Routing

- Project routing is defined in `AGENTS.md`. Use `DeepSeek V4 Flash` first for bounded text-only source, log, metrics, structured-data, implementation, and targeted-test work.
- Route `GPT-5.6 Luna` only when the worker runtime exposes it and the task needs images/multimodal evidence, complex cross-module reasoning, difficult tool orchestration, or independent arbitration of conflicting/high-risk findings. Never send visual payloads to DeepSeek.
- Grok remains an explicit, authorized write-capable lane; it is not a reason to bypass the packet, conflict-domain ownership, cloud-only Android build policy, or Sol's final acceptance.
- In `Cost mode: cost-saving`, prefer DS/Grok to perform the concrete discovery, implementation, debugging, targeted verification, and self-review. The main Sol agent should avoid broad direct edits and may only handle architecture, conflict resolution, small integration glue, security-sensitive changes, and final acceptance unless the user explicitly authorizes an exception.

## Grok Delegated Execution

- Codex may dispatch bounded implementation, debugging, audit, and verification tasks to Grok from the repository root.
- Grok has workspace write permission, but Android local builds/tests are disabled after the 2026-07-31 handoff; Grok must use cloud-build evidence or non-build diagnostics and follow `AGENTS.md` for parallel ownership and handoff rules.
- Grok must invoke the same mandatory LinReads skills listed above before touching their trigger scopes.
- Grok must preserve the existing dirty worktree and must not commit, push, publish OTA builds, deploy, or contact external systems unless the delegated task explicitly authorizes it.
- Headless Grok tasks must return a final result with changed files and verification evidence; a tool-only or partial response is not completion.
