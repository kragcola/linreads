# Maintenance Log

（逆时间序，最新在最上面）

---

## 2026-06-19

## 2026-06-19

### TXT 最小阅读链路（垂直切片，phase 2 起点）
- 依据 `docs/android-p2-txt-minimal-slice.md`，以 TXT 单格式打通「解析→引擎→View→reader→app」端到端
- `:render:api`（L2）：`ReaderEngine`/`ReadingMode`/`PagingKind`（线程契约）、`EngineDescriptor`/`ReaderEngineRegistry`/`NoEngineException`（按扩展名 resolve）、`PageTransitionHost`/`PageTransitionHostFactory`（§5.6）
- `:render:txt`（L3）：`TxtVirtualPagerEngine`（CONTINUOUS、RecyclerView、UTF-8 整读切段、滚动上报 progression、ByteOffset/Section locator）+ `TxtParagraphAdapter`（18sp/行高1.6）；最小版未上 FileChannel 64KB 流式 + ICU 编码探测
- `:render:animate`（L3）：`NoTransitionHost`（CONTINUOUS 直挂）+ `DefaultPageTransitionHostFactory`
- `:features:reader`（L6）：`ReaderIntent`（MVI 子集）+ `ReaderViewModel`（经 Registry resolve，不 import 具体引擎）+ `ReaderScreen`（AndroidView 挂 host）
- 空壳（满足 phase=2 resolve）：`:render:epub`/`:render:pdf`/`:render:md`/`:features:settings` 仅 build.gradle.kts
- `:core:model`：`BookFormat.fromExtension()` 补全（Registry 解析用）
- `:app`：Koin renderModule（TXT descriptor + Registry + HostFactory）+ ReaderViewModel 绑定 + 导航「打开示例 TXT」+ `assets/sample.txt` + copySampleToCache（assets→cacheDir→file:// uri 供 ContentResolver）
- **F9 工程决断**：phase1 app 不能引用 phase2 才在场的 `render:*`（否则 `-Preadflow.phase=1` 解析报 project not found）。解法：`:app` phase 条件 sourceSet `src/phase1`（foundation 版，零 render）/`src/phase2`（TXT slice 版），build.gradle.kts 按 `readflow.phase` 切 srcDir + 条件 render 依赖
- catalog 补 `recyclerview = 1.4.0`
- 验证：`-Preadflow.phase=2 clean :app:assembleDebug` → SUCCESSFUL（apk 22.3MB）；`-Preadflow.phase=1 clean :app:assembleDebug` → SUCCESSFUL（独立可构建未被破坏）；C1 render:txt 无 compose；C3 features:reader 仅依赖 render:api；model 纯度 grep 空；phase1 无 render import
- 影响范围：3 个新 render 模块 + 4 空壳 + features:reader + app phase 双 sourceSet；真机滚动验证待 AVD

### Phase 1 地基框架搭建（不实现功能）
- 依据 `docs/android-p1-foundation-plan.md`（同日新建的 P1 执行文档）落地 9 模块 + `:app` 骨架
- `build-logic/` composite build + 4 个 convention plugin：`ReadflowJvmLibrary`（core:model）/`ReadflowAndroidLibrary`（Layer1）/`ReadflowCompose`（core:ui）/`ReadflowFeature`（feature）；`settings.gradle.kts` 加 `includeBuild("build-logic")`；catalog 补 4 个 gradlePlugin 依赖项供插件 compileOnly
- `:core:model`（Layer0 纯 JVM）：`Locator`/`LocatorStrategy`、`ReaderState`、`ReadflowError`/`ReadflowException`、`ReadflowResult`、`BookMeta`/`BookFormat`/`DownloadStatus`/`ThemeMode`/`Offset`/`DownloadedAsset`、`LoadingState`/`TransitionType`、`ReadingProgress`/`Bookmark`（带同步元数据）、`InkAnchor`——全 `@Serializable`，纯度 grep 通过
- Layer1 五模块：`:core:calibre`（CalibreClient 迁出 app + CalibreRepository 接口）、`:core:database`（Room 5 表实体 + DAO 签名 + ReadflowDatabase，schema 导出）、`:core:prefs`（SettingsRepository 接口）、`:core:sync`（SyncBackend+NoOpSyncBackend+SyncManager）、`:extensions:api`（BookSource/Extension SPI/ReaderEventBus）
- `:core:ui`（Material3 ReadflowTheme，暖白/夜间色板）、`:features:library`（LibraryViewModel 空 state + LibraryScreen 占位）
- `:app`：ReadflowApplication（startKoin）+ Koin 模块骨架（NoOpSyncBackend/SyncManager/ReaderEventBus/LibraryViewModel）+ Navigation host（单占位路由）+ network_security_config.xml 占位（C2：不硬编码 baseUrl）；删 app 内旧 CalibreClient.kt
- 全部为纯数据/接口/空壳，无业务逻辑（R2/R3）；业务实装边界见 P1 文档 §6
- 验证：`-Preadflow.phase=1 :app:assembleDebug` → BUILD SUCCESSFUL（app-debug.apk 24.5MB）；model 纯度 grep 空；features:library 不依赖 render:*；phase=1 不含 phase2/3 模块、phase=2 正确纳入 render/reader
- 影响范围：`android/build-logic/`、9 个新模块、catalog、settings、app 组装层；IMPLEMENTATION GATE 已获用户「通过，执行」放行

### Android 工具链落地 + SDK 口径升 36
- 本机装 Android Studio 2026.1.1（build AI-261，外挂盘 `/Volumes/OmubotDisk/Applications/Android Studio.app`）+ SDK（外挂盘 `/Volumes/OmubotDisk/Applications/sdk`，Platform 36 / build-tools 36.1.0+37.0.0）
- 中文化：官方语言包（plugin 13710）最新仅到 build 242，不覆盖 261，保持英文 UI
- 因 SDK 只有 Platform 36，项目从 35 升 36：`app/build.gradle.kts` compileSdk/targetSdk 35→36；AGP 8.7.3→8.13.2（旧 AGP 不认 SDK36/build-tools37）；Gradle wrapper 8.11→8.14.3（AGP8.13 要求）；v4 §10.2 convention plugin 口径同步 35→36
- 修既有 scaffold 编译缺陷（非升级引入）：Manifest 引用未依赖的 `Theme.AppCompat.*` → 新建 `res/values/themes.xml` 定义 `Theme.LinReads`（系统 DeviceDefault，纯 Compose 不引 AppCompat）；补 `compileOptions` source/target=17 解决 JVM target 不一致
- 新建 `android/local.properties`（指外挂盘 SDK，已被 .gitignore 忽略）；JAVA_HOME 用 Studio 自带 JBR 21
- 验证：`./gradlew -Preadflow.phase=1 :app:assembleDebug` → BUILD SUCCESSFUL，产出 app-debug.apk（空壳）
- 影响范围：构建配置 + 1 资源文件 + 1 manifest 行 + 文档口径；尚未进 Phase 1 模块实装

### v4 架构最终审计 + 无争议项修补
- 对 `docs/android-architecture-v4.md` 做实装前最终审计：未发现颠覆性方向错误；路线（统一引擎接口、Hybrid 原生 View、去 WebView 原生重排、Locator 进度主键、PdfRenderer、MuPDF optional）与外部成熟实践一致
- 已修补（无取舍空间项）：
  - **B2 Locator 建模洞** — `LocatorStrategy` 从散文裸枚举名补为带 payload 的 `@Serializable sealed interface`（`Page`/`Section(spineIndex,elementIndex,charOffset)`/`ByteOffset`/`Unknown`），与 `InkAnchor.Text` 共坐标系；§3.3 纯度白名单补 `LocatorStrategy`/`LoadingState`
  - **M1 EngineStateStore 归属缺失** — 接口落 `:render:api`（Layer 2），实现落 `:app`；§3.1/§3.2 模块表补登记
  - **文档↔仓库漂移**：`settings.gradle.kts` 注释开关 → `readflow.phase` property 驱动 `phaseInclude()`（落地 P6/F9，phase1 含 `:core:ui`/`:core:database`/`:core:sync`）；`libs.versions.toml` 删 nanohttpd（落地 E1），加 jsoup；`app/build.gradle.kts` 注释从 WebView+epub-ts+nanohttpd 改原生重排+jsoup
- 留待用户决断（4 项，未改）：B1 Phase 1 范围与「无账号本地阅读闭环」矛盾、B3a totalProgression 全书分母 vs 惰性解析冲突、B3b 三端规范化纯文本收敛机制、M3 EPUB 分页是否锁定 Phase 2
- 影响范围：文档层 + 构建配置（settings/catalog/app build），无运行时 Kotlin 代码变更

### v4 四项决断落档（用户裁决）
- **决断 1（B 选项）**：构建 Phase 1 重定义为**基建期**（书库浏览 + 本地导入登记 + 最近阅读 + 进度本地存储，**不渲染正文**）；用户验收 Phase A「能打开本地书并继续阅读」下移到构建 Phase 2（reader + 引擎就位）。§2.2/§10.3/§11 Phase 1 措辞与结束态同步修正，消除「Phase 1 没有 reader/引擎却号称能阅读」的自相矛盾
- **决断 2（接受预扫）**：`totalProgression` 分母「全书纯文本总字符数」由 `openBook()` 一次性预扫得出——每个 spine 项只「解析→规范化→计 code point」产出轻量 per-spine 计数表（不留正文，几十 KB），计入「打开」预算；与惰性正文解析正交，「内存与书体积解耦」仍成立；计数表随 EngineStateStore 缓存，大书可后台补算回填。落 §7.1
- **决断 3（仅 Android，三端契约后置）**：当前只实装 Android，三端 `totalProgression` 收敛（< 0.5%、共享参考实现、对拍语料）暂不落地；口径先作 Android 端内部规范保证单设备换字号/重开稳定；待 Web/HarmonyOS 接入同步时再提为 `shared/` 共享实现并冻结契约。落 §7.1
- **决断 4（锁定连续滚动）**：EPUB 首发唯一形态 = `pagingKind=CONTINUOUS`（LazyColumn 连续滚动）；分页（per-page ComposeView over ViewPager2）独立成 Phase 2 gate，明确标注为全架构最高技术风险点、达不到 < 50ms 帧预算可推翻退回连续滚动，不与基础阅读链路绑定。落 §5.5
- 影响范围：文档层（§2.2/§5.5/§7.1/§10.3/§11），无运行时代码变更

---

## 2026-06-18

### Android v3 Framework Architecture Audit
- 新增并重审 `docs/audit/android-v3-framework-audit-2026-06-18.md`，从少人团队精品项目视角审计 `docs/android-architecture-v3.md`；重审后按用户反馈明确“轻量”指用户使用轻量，不是开发轻量或少建模块
- 结论：v3 方向正确，适合作为目标架构；21 模块、自研引擎和复杂内部实现均可接受，但必须补 `User-Light Architecture Contract`，确保复杂度不转嫁给用户
- 评分：19.3/28；强项为 Hybrid View、ReaderEngine、纯数据 ReaderState、Locator、离线优先；弱项为用户轻量契约、首开路径、安装包/按需能力边界、数据导出、权限矩阵、WebView 安全/许可证 gate
- 关键 P0/P1：缺少用户轻量架构契约；`ReadflowError` 不应携带可序列化 `Throwable`；首次使用路径应支持无账号本地阅读；`ReaderEngine.createView()` 与 `ViewPager2.PageTransformer` 宿主关系需定义；`BookSource.download()` 不宜返回 `java.io.File`；SyncBackend 缺少 LWW/Union 元数据和开放备份格式；WebView JS bridge 需安全 ADR；MuPDF 需 license/optional gate；权限与隐私矩阵缺失
- 建议验收顺序：无账号本地阅读闭环 → Calibre 作为可选书源 → EPUB/PDF/TXT 阅读质量闭环 → 数据出口与离线缓存 → Ink/TTS/OPDS/Sync/DOCX/CBZ 精品增强
- 影响范围：文档/审计层，无运行时代码变更

### Architecture Drift Cleanup
- 收口当前入口文档与 wiki 的口径漂移：`docs/architecture.md`、`docs/wiki/Architecture.md`、`Home.md`、`Platform-Web.md`、`Platform-HarmonyOS.md`、`Rendering-Engine.md`、`Development-Guide.md`、`Active-Work.md`、`Calibre-API.md`、`README.md`、`CLAUDE.md`
- 明确区分当前实现与目标架构：Web 当前仍使用 `epubjs`；Android v3 与后续 EPUB 工作目标为 `@likecoin/epub-ts`
- 移除当前入口中的旧 `epublib` / Android MuPDF-for-EPUB / 自研 AnimationEngine 目标描述，改为 v3 的 Hybrid View、WebView + epub-ts、PdfRenderer、TxtVirtualPager、ViewPager2 PageTransformer
- 修正 HarmonyOS 状态：`BookList.ets` 已有基础书单渲染，剩余为 Settings 持久化 baseUrl、封面、错误/空状态
- 修正 `BACKLOG.md` 中模块计数“统一为 19”的错误，权威口径为 21 模块；历史审计/研究材料保留原文
- 修正 README/wiki 对格式能力的表述：当前可用能力是 Web 基础 EPUB/PDF，MOBI/TXT/MD/DOCX 等按目标架构待实现
- 修正 `Platform-Android.md` deprecated banner：历史页保留，但明确当前权威规范是 Android Architecture v3
- 影响范围：文档/追踪层，无运行时代码变更

### Continuity Tracker Normalization
- 按 `omubot-continuity` 规范收口进度追踪：`docs/tracking/ACTIVE.md` 改为短入口，明确 `Mode: task`、当前 objective、active tracker 和 implementation gate
- 新增 `docs/tracking/linreads-architecture-docs-2026-06-18.md`，承接 live todo、决策、分区进度、验证、风险、回滚和不再重复调查项
- 记录当前文档漂移风险：历史追踪/文档中仍出现 19/20/21 模块说法，`docs/architecture.md` 仍有 epubjs/epublib 旧描述；后续按 tracker 的 Live Todo 收口
- 影响范围：文档/agent handoff 层，无运行时代码变更

### Codex Continuity Skill 迁移
- 从外置盘 Omubot 项目 `/Volumes/OmubotDisk/omubot` 定位并迁移 `omubot-continuity` skill，用于 Codex 上下文压缩/新会话后的任务恢复、tracker 维护、handoff 和 Test Ledger 纪律
- 源文件：`/Volumes/OmubotDisk/omubot/.claude/skills/omubot-continuity/SKILL.md`
- 目标：`/Users/kragcola/.codex/skills/omubot-continuity/SKILL.md`
- 已校验源/目标 `cmp` 一致；Codex 需重启后自动发现该 skill
- 未迁移 Omubot 项目内 `.codex/hooks.json` 与 `scripts/dev/codex-session-start.py`，因为它们依赖 Omubot 专属 `docs/project-info.md`、`docs/tracking/ACTIVE.md` 和 tracker 结构；如要给 LinReads 启用自动 SessionStart 恢复，需要单独做项目适配

### Codex Skill 对齐
- 修正 `.claude/skills/` 中面向人的 Readflow 命名残留：开发、EPUB、同步、无障碍、TDD、调试等说明统一称 **LinReads**
- 保留 `dev.readflow`、`adb logcat | grep -i readflow` 等当前 Android 包名/日志技术标识，未做代码级包名迁移
- 将 Claude 侧 9 个 skill 同步安装到 Codex：`linreads-dev`、`linreads-epub`、`linreads-sync`、`tdd`、`systematic-debugging`、`accessibility`、`design-audit`、`requesting-code-review`、`receiving-code-review`
- 影响范围：agent 行为层；无运行时代码变更。Codex 需重启后自动发现新 skill

### v3 架构文档发布
- 合并 v2 主文档 (2310行) + Addendum + 根因裁决 (12项) → **统一 v3 文档** (1791行, ~75KB)
- 11 章节：设计原则 / 模块地图 / 视图架构 / 渲染引擎 / Ink集成 / 数据层 / 扩展系统 / DI / Gradle / 迁移路径 / 裁决记录
- 19 项质量检查全部通过：21 模块/8 层一致、epub-ts 唯一引用、ViewPager2 替代自研引擎、Locator∈core:model、ReaderState 零 View 引用、BookSource Addendum 版本、7 渲染引擎、12 项裁决已记录
- v1/v2 文档标记废弃。Platform-Android.md 标记 DEPRECATED
- `docs/architecture.md` 指向 v3
- 影响范围：新增 1 文档 + 更新 4 文档

### 第三轮架构终审
- 三维并发审计（交叉校验 2/5 + 构建就绪度 WILL_FAIL + 一致性 3.4/5）
- 裁决：**有条件通过**。架构已就绪，但 Phase 1 范围必须缩减 75%（21→5 模块）
- 发现 8 处交叉矛盾（4 HIGH）：ReaderState 双定义冲突、BookSource 7 处签名差异、Locator 重复定义、core:model→render:api 循环依赖
- 构建必败：缺失 kotlinx-serialization-json、kotlin-serialization 插件、kotlin-jvm 插件、material-icons-extended
- 关键路径：14 步，Phase 1 预估 7-8 工作日，首个交付物为工作书库浏览器 APK
- 5 风险：架构过度设计、WebView 碎片化、多模块复杂性、epub-ts 兼容性、文档-实现鸿沟
- Round 1→3 评分演进：2.08→3.3→3.4。Go/No-Go 演进：NO→CONDITIONAL NO→CONDITIONAL GO
- 新增 `docs/audit/round3-audit-report-2026-06-18.md`：第三轮终审报告

### 修复执行 + 架构补全
- 执行 P0 构建阻塞修复：Gradle wrapper 生成 (8.11) + settings.gradle.kts + build.gradle.kts + gradle.properties + libs.versions.toml (46 依赖，2026 版本)
- 执行 P1 架构一致性修复：v2 文档 epublib→epub-ts 统一、模块计数 19→21 统一、ReaderRootLayout 路径修正、PDF API 26+ 统一
- 新增 `docs/android-architecture-v2-addendum.md`：补全 P2（6 类型定义：ReaderState/InkBrush/ExtensionSettings/DownloadStatus/SyncBackend/BookSource）+ P3（9 策略文档：离线/同步/渲染/性能/测试/安全/无障碍/大屏/KMP）
- 重命名 3 个 skill 目录：`readflow-*` → `linreads-*` + SKILL.md 内容更新
- 更新 CLAUDE.md：epub-ts 引擎说明、架构文档链接、lint 命令
- 更新 `docs/architecture.md`：指向 v2 架构文档
- BACKLOG 从 41→1 待办：仅剩 `ARCHITECTURE.md` 更新确认
- 影响范围：构建文件 (8 新建/修改) + 文档 (4 修改/新建) + skill 目录 (3 重命名)

### 项目改名 + EPUB 引擎新发现
- 项目名从 **readflow** 改名为 **LinReads**。批量更新 28 个文档文件（wiki/CLAUDE/README/audit/research）
- 发现 **`@likecoin/epub-ts`**：epubjs v0.3.93 的完全 TypeScript 重写，drop-in 替换（改一行 import），API 完全兼容
  - 性能：`locations.generate(1000)` 从 43 秒 → 159ms（270x 提升），包体积 57.5KB（vs epubjs 132.8KB）
  - BSD-2-Clause 许可，970+ 测试，活跃维护，支持不安全上下文（http:// 内网 nanohttpd 可用）
  - **这应该成为 LinReads 的默认 EPUB 引擎**
- 发现 **`Aryan-Raj3112/episteme`**（914 stars，AGPL-3.0）：Kotlin Multiplatform 阅读器（Android + Desktop）
  - 格式覆盖与 LinReads 高度重叠：PDF/EPUB/MOBI/FB2/DOCX/TXT/MD/HTML/Comics
  - PDF ink annotations 已实现。使用 PdfiumAndroidKt + libmobi + Jsoup + Flexmark
  - 离线优先，20 语言（含中文简体），F-Droid 可下载
- 已 clone `references/readbooks-v2-android`（MIT）：Readium Kotlin 3.1.2 + 离线下载 + 阅读会话追踪的完整参考实现
- 影响范围：文档层改名 + 新增参考项目

### 架构定夺 + 信息搜寻
- 用户 5 项决策：epubjs（默认）+ foliate-js（备选）、保持 20 模块细粒度、Phase 1 不做笔写（B 方案预留）、C 智能缓存 + A 手动下载、A 自建同步 + B WebDAV 兜底（Phase 1 预留）
- 广度搜索发现：
  - 直接对标项目 `fbaldhagen/readbooks-v2-android`（MIT）：Readium Kotlin Toolkit 3.1.2、完全离线、Clean Architecture
  - 同步服务 `szaffarano/korrosync`（MIT, Rust）：5 端点、Docker 部署、v0.4.0 生产可用
  - EPUB 引擎备选 `@asteasolutions/epub-reader`（npm, 2026-02）+ `intity/epub-js`
  - 文字锚定 `tilgovi/dom-anchor-text-position`（MIT）：W3C TextPositionSelector JS 实现
  - 多策略锚定 `@net7/annotator`（TypeScript）+ Hypothesis fuzzy anchoring
- 新增 `docs/audit/decisions-2026-06-18.md`：完整决策 + 20 项修复清单 + 新参考汇总
- 影响范围：文档层，修复留待下一轮

### v2 架构全方位审计
- 五维度并发审计 v2 架构文档：一致性 3/5、完备性 3/5、可行性 3/5、对标 competitive、缺口 4/5
- 综合评分 **3.3/5**，状态管理 + 手写笔批注 best-in-class
- 发现 4 项严重问题：离线书籍缓存缺失、同步后端未定义、Gradle 构建将失败（nanohttpd 缺失 + maven.ghostscript.com 未声明）、foliate-js 与 Android WebView 安全冲突
- 发现 9 处内部矛盾：模块计数偏差、Locator 重复定义、ExtensionContext 跨层依赖违规、ReaderRootLayout 位置冲突、PDF API 等级不一致等
- 发现 8 项 v2 新引入问题：foliate-js vs epubjs 二选一矛盾、模块过度拆分、离线优先语义降级等
- 最小修复集：8 项必须在编码前完成，预计 2-3 工作日
- 新增 `docs/audit/v2-audit-report-2026-06-18.md`：完整 v2 审计报告（299 行）

### 优化架构 v2
- 基于全部审计发现 + 前沿项目搜索，生成优化架构 v2 文档（`docs/android-architecture-v2.md`，2310 行）
- 四大设计维度并发生成：模块化构建（20 个 Gradle 模块 + convention plugins）、混合视图 + 渲染契约（ReaderEngine 接口 v2）、数据层 + 共享契约（Locator + LinReadsError + JSON Schema 生成）、扩展系统 + DI（Extension SPI + ServiceLoader + BookSource + ReaderEventBus + Koin multi-bind）
- 关键变更：EPUB 改 WebView、动画改用 ViewPager2、文档渲染基元从 Bitmap 改为 View、错误从 String? 改为 sealed hierarchy、书源从具体类改为接口
- 附录 A：v1 vs v2 20 项关键决策对照表
- 附录 B：~60 个关键文件路径汇总
- 状态：架构定稿，所有 4 个 BLOCKER + 6 个 HIGH + 5 个 MEDIUM 项已消解

### 自研引擎必要性评估
- 逐格式对照成熟引擎覆盖度，分析自研 vs 现有方案的 tradeoff
- 结论：**LinReads 不需要自研渲染引擎**。唯一需要自研的是 TxtVirtualPager（~300 行），因为 TXT 大文件阅读没有成熟开源 Android 方案
- 发现 foliate-js（MIT，Readest 21k stars 在用）是 epubjs 的实质升级：支持 EPUB+MOBI+FB2+CBZ+PDF 6 种格式，内置 CFI/overlayer SVG/TTS/搜索/OPDS
- Moon+ Reader MRTextView 分析：~8000 行自研引擎代码，EPUB 部分是过度工程（2012 年 WebView 质量差，现在不成立），TXT 部分方向正确但实现过重
- 前沿国内 App C++/Skia 引擎（微信读书/掌阅/知乎盐言）：LinReads 不需要——那是出版级需求，需要 5-10 人年 C++ 投入
- LinReads 总自研代码：~300 行。对比 Moon+ Reader ~12,000 行。许可风险：零（全部开源/系统）
- 新增 `docs/research/engine-make-vs-buy.md`：完整评估文档
- v2 架构 `render:epub` 模块从 epubjs 改为 foliate-js

### 终审：架构开放度与便捷度
- 四维度并发审计（扩展开放度/构建便利度/接口契约/集成匹配度），综合得分 **2.08/5**
- 结论：**架构目前不具备实施就绪条件**，距可实施状态需 4-6.5 工作日阻断项修复
- 三个结构性矛盾：(1) Compose Bitmap ↔ View Ink 互斥 (2) "零 WebView" ↔ EPUB/笔写需求冲突 (3) 共享契约零强制执行
- 5 个做对的设计：MVI 方向正确、ReaderEngine 抽象、Calibre 集成、多格式覆盖、Ink 锚定研究深度
- 便利基线清单：4 BLOCKER + 6 HIGH + 5 MEDIUM，预估总投入 12-19 工作日可完成全部修复
- 新增 `docs/audit/final-audit-report-2026-06-18.md`：完整终审报告（286 行）
- 影响范围：判定阶段（明确在修复完成前不应写功能代码）

### EPUB 手写笔迹锚定技术调研
- 调研核心问题：reflow EPUB 上手写笔迹如何锚定到文本内容，使其在字号/排版变化后不丢失
- 调研范围：W3C EPUB Annotations 1.0 (2026-05)、Microsoft 专利 US7218783B2、SpaceInk (UIST 2019)、ACM 论文 "Reflowing Digital Ink Annotations" (2003)
- 商业产品分析：Kindle Scribe (Sticky Notes + Active Canvas)、Apple Books (SVG→HTML position:absolute)、BOOX (页面坐标、不锚定文字)、Kobo/reMarkable/Supernote
- 结论：行业无完全自由的 reflow EPUB 笔写方案。所有产品都做了取舍——Kindle Scribe 限制手写形式，BOOX 牺牲重排安全
- 推荐 LinReads 采用双模式策略：PDF 页面级自由书写 + EPUB Sticky Note（文本锚定）
- 给出 4 条技术路径的详细分析（路径 A-D）和 Android WebView + JS Bridge + Native Ink 的具体实现方案
- 新增 `docs/research/ink-anchoring-research.md`：完整调研文档

### 笔写+阅读架构断裂分析
- 追踪「阅读 + 跟手笔写」五个核心场景，发现当前架构无法支持此组合需求
- 发现 3 个架构矛盾：Compose Bitmap ↔ View ink 互斥、ToggleInk ↔ 自动路由矛盾、"零 WebView" ↔ ink 宿主缺失
- 定位最难问题：reflow EPUB 上笔迹锚定——行业无成熟开源方案（GoodNotes 只做 PDF，Apple Books 用浮动图片嵌入 HTML）
- 提出修正方案：hybrid View+Compose 架构、EPUB 改 WebView、笔迹双锚定模型（Page/Text）、ReaderState 重构
- 放弃"零 WebView"原则——对 EPUB 格式，WebView 是正确的渲染路径
- 新增 `docs/wiki/Ink-Architecture-Gap.md`：完整断裂分析 + 修正方案
- 影响范围：架构设计层（需在编码前修正）

### 成熟阅读器架构对比审计
- 对标三个成熟项目拆解架构：KOReader（C++/Lua, 13年）、Mihon/Komikku（Kotlin/Compose, 5年）、Readium（Swift/Kotlin SDK, 9年）
- 逐维度对比：格式注册/渲染路径/状态管理/插件系统/进度定位/动画翻页/数据持久化
- 提出 3 个关键设计重审建议：EPUB 改走 WebView（非 MuPDF bitmap）、进度模型采用 Readium Locator、动画先用 HorizontalPager 再逐步替换自研
- 提取 7 个可立即采纳的模式（P0: 渲染路径+Locator; P1: Interactor+格式注册表+双向预取; P2: 轻量插件+sidecar 导出）
- 新增 `docs/wiki/Architecture-Comparison.md`：完整对比报告含评分矩阵
- 影响范围：文档层，无代码改动

### Android 端架构审计
- 独立审计 `docs/android-architecture.md` + `docs/wiki/Platform-Android.md`，三方对标：静读天下源码验证版 + 2025-2026 前沿架构（Mihon/Komikku/KOReader/Google 建议）+ 实际代码实现状态
- 发现 1 处内部设计矛盾（PDF 渲染方案 MuPDF vs PdfRenderer，已统一为 PdfRenderer）
- 发现 4 处设计与实现的严重滞后（构建工具链/Kotlin 版本/Compose Compiler/模块化）
- 新增 `docs/wiki/Android-Architecture-Audit.md`：完整审计报告含评分卡、修正清单、工作量估算（9-14 周单人全职）
- 修正：`docs/android-architecture.md` PDF 渲染策略表
- 影响范围：文档层，无代码改动

### 静读天下架构源码交叉验证
- 独立分析 `moonreader-decompiled/sources/com/flyersoft/` 核心包（~164K 行），逐项对照已有文档
- 发现并修正 3 处事实性错误：
  1. PDFReader 非 BaseEBook 子类（`extends FrameLayout`），走独立 RadaeePDF 渲染路径
  2. MyLayout 非 Android StaticLayout 子类（`public abstract class MyLayout` 无 extends），是 100% 自研布局引擎
  3. CHM 库为 34 个类（非 29）
- 补充 4 处遗漏细节：排版层完整继承链、CSS.java 体量（75KB）、staticlayout 包规模（51 文件）、ActivityTxt God Class（20,793 行）
- 新增 `docs/wiki/Architecture-Verification.md`：完整验证报告
- 修正文档：`docs/research/moonreader-analysis.md`、`docs/research/moonreader-architecture-review.md`
- 影响范围：文档层，无代码改动

### 项目 Wiki 创建
- 新增 `docs/wiki/`：14 页结构化项目 Wiki
  - 详见上方 2026-06-18 条目（同一条维护日志的上一个条目）
- 影响范围：文档层，无代码改动

---

## 2026-06-17

### 项目文档初始化
- 新增 `CLAUDE.md`：项目指令、命令速查、平台状态、skill 使用指南
- 新增 `docs/architecture.md`：系统架构 wiki
- 新增 `maintenance-log.md`（本文件）
- 新增 `docs/tracking/ACTIVE.md`：跨 agent 任务交接
- 影响范围：文档层，无代码改动
- 回滚：`git revert`（纯文档，无运行时影响）

### Skill 栈安装（commit `44f0a64`）
- 安装 9个 skill 到 `.claude/skills/`：
  - 层1（通用开发律条）：`systematic-debugging`、`tdd`、`requesting-code-review`、`receiving-code-review`
  - 层2（UI/UX）：`accessibility`、`design-audit`
  - 层3（LinReads 定制）：`linreads-dev`、`linreads-epub`、`linreads-sync`
- 来源：obra/superpowers（社区）+ mrKanoh（改写）+ tdimino（精简）+ 自写（LinReads-\*）
- 影响范围：`.claude/skills/`，仅 agent 行为，无运行时影响

---

## 2026-06（估算，来自 commit `5242e1d`）

### 初始三端脚手架
- Web：React18 + epubjs + Vite，书库列表 + EPUB/PDF 基础阅读，Calibre proxy 配置
- Android：Kotlin + Compose + Ktor，UI 骨架（书库/阅读/设置三 Tab）+ CalibreClient
- HarmonyOS：ArkTS + ArkUI + @ohos.net.http，书库页骨架 + CalibreService
- 共享：`shared/api/calibre-contract.ts` 三端类型契约，`shared/calibre/api.md` API 参考
- 状态：Web 端功能最完整；Android/HarmonyOS 为 scaffold，尚未连接真实数据
