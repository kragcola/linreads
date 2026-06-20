# LinReads v2 架构审计报告

**审计日期**: 2026-06-18
**审计范围**: v2 架构设计文档全量审计
**审计维度**: 一致性 (Consistency) / 完备性 (Completeness) / 可行性 (Feasibility) / 对标 (Benchmarking) / 缺口 (Gaps)
**审计语言**: 中文

---

## 1. 执行摘要

v2 架构设计文档在 **对标 (Benchmarking)** 维度表现最佳（competitive），在状态管理和手写笔批注两个子项达到 best-in-class。但 **一致性 (3/5)**、**完备性 (3/5)** 和 **可行性 (3/5)** 三个维度均暴露了阻止进入实现阶段的硬伤。

**v2 综合评分: 3.3 / 5**

**当前状态: 不建议直接进入实现阶段。** 至少需要完成 8 项关键修复（详见第 7 节 "最小修复集"）后方可开始 Phase 1 编码。修复预计耗时 2-3 个工作日，主要集中在：消除文档内部矛盾、补全缺失类型定义、解决 Gradle 构建阻塞项、以及明确离线书籍缓存策略。

v2 的正确方向值得肯定——混合视图架构、MVI 状态管理、Extension SPI、双 InkAnchor 策略都是 v1 无法企及的进步。但文档在细节严谨性上仍有大量工作要做：9 处内部矛盾、10+ 处缺失类型定义、Gradle 构建将直接失败的依赖缺失、以及将 "离线优先" 误解为仅指进度同步等，这些都会在实现阶段引发阻塞。

---

## 2. 五维评分卡

| 维度 | 评分 | 关键判断 |
|------|------|----------|
| **一致性** (Consistency) | 3/5 | 9 处内部矛盾，包括模块计数偏差、类型重复定义且冲突、文件路径矛盾、包名拼写错误、依赖链断裂。此类问题直接导致读者对文档其他未校验部分的信任度下降。 |
| **完备性** (Completeness) | 3/5 | 10+ 处关键类型未定义（InkBrush, ExtensionSettings, CalibreClient 等）；状态机、错误处理、生命周期、测试、安全、无障碍六个维度均存在系统性缺口。 |
| **可行性** (Feasibility) | 3/5 | Gradle 构建将因缺失仓库和依赖而失败；foliate-js 的安全模型与 Android WebView 存在根本性冲突；nanohttpd 已停维 10 年。但 convention plugin 和 ServiceLoader 设计正确可实现。 |
| **对标** (Benchmarking) | competitive | 状态管理 (MVI + StateFlow + EventBus + Hook) 和手写笔批注 (双 InkAnchor + 工具类型路由) 为 best-in-class。模块化和扩展系统为 competitive。渲染引擎多样性引入集成风险。 |
| **缺口** (Gaps) | 4/5 | 缺少离线书籍缓存（最关键的移动端阅读需求之一）、同步后端未定义、性能预算缺失、大屏/折叠屏适配未考虑、导航桥接方案未指定。KMP 策略被合理推迟。 |

---

## 3. v2 做对了什么

### 3.1 继承自 v1 的正确决策（在 v2 中保留）

- **三端共享类型契约** (`shared/api/calibre-contract.schema.json`) — JSON Schema 作为单一真源，CI 门禁强制校验，彻底解决了 B3（类型跨端漂移）。
- **Calibre Content Server 代理模式** — Web 端 Vite 代理、移动端直连 LAN IP，架构简洁有效。
- **格式优先级 EPUB > PDF > MOBI** — 合理的默认排序，与行业标准一致。

### 3.2 v2 新增的正确设计

1. **混合视图架构 (Hybrid View)** — FrameLayout 根容器 + View 文档层 + ComposeView UI 镀层。明确禁止 Compose 进行文档渲染（B1 已解决），避免了 v1 的 Bitmap→ImageBitmap 拷贝浪费和性能陷阱。

2. **ReaderEngine 插件化渲染引擎** — 11 方法/属性的统一接口 (`format`, `priority`, `supports()`, `open()`, `createView()`, `close()`, `goTo()`, `currentLocator`, `pageCount`, `setFontSize()`, `setMode()`)，返回 `View` 而非 `Bitmap`（B4 已解决）。Registry 按格式+优先级自动匹配，新增格式只需添加模块+实现。

3. **MVI 状态管理** — `sealed ReaderIntent → ReaderState` + `StateFlow` + `ReaderEventBus` + `ReaderHook`。相比 KOReader 的散落式事件模型和 Mihon 的简化 ScreenModel，v2 的状态架构在可测试性和可追踪性上达到 best-in-class。2 秒防抖写入进度显示了对性能的务实考量。

4. **Extension SPI 沙箱** — `ExtensionMeta`、`ExtensionScope`、`ExtensionContext`（仅暴露 `ReaderState` flow + EventBus + DataStore 命名空间）、`ReaderHook`（5 个生命周期拦截点）、`ReaderEventBus`（15 个事件类型）。直接防止了 Moon+ S1 反模式（Sync.java 1529 行 God Class 含 public static 可变状态）。ServiceLoader 发现 + 用户手动启用 = 懒加载，避免了 Mihon 的 ~2000 行 APK 加载基础设施。

5. **双 InkAnchor 手写笔批注** — Page-based（固定布局: PDF/CBZ/DOCX）+ Text-based（重排格式: EPUB/TXT/MD, 对齐 W3C EPUB Annotations 1.0）。工具类型路由（stylus→ink, finger→document, 无需模式切换）是行业标准做法。双 Canvas 架构（CanvasView 已完成笔画 + InProgressStrokesView 前端缓冲区 <10ms 延迟）符合 Android 官方 ink 示例。

6. **完善的数据层** — 4 表独立 Room schema (books, reading_progress, annotations, bookmarks)，含外键和 CASCADE 删除。H1 已解决：`LinReadsError` sealed class (6 子类型) + `LinReadsResult<T>` type alias 取代了 v1 的裸 `String?`。Locator 作为 JSON 字符串存储保留了 sealed hierarchy 的灵活性。

7. **Convention Plugin 分层强制** — 4 插件层级 (base library, compose, feature, render) 在 Gradle 编译期即禁止跨层依赖和 Compose 侵入渲染模块。这是比代码审查更可靠的架构约束手段。

8. **Codegen 类型生成** — quicktype 从 JSON Schema 生成 Kotlin + TypeScript，CI 校验生成文件与 schema 一致，防止手动维护时的类型漂移。

---

## 4. v2 仍然做错了什么（按严重程度排序）

### 🔴 严重 (阻断实现)

**S1 — 离线书籍缓存缺失（Offline Gap, critical）**
CLAUDE.md 原则 4 称"离线优先 (offline-first)"，但该术语被严重误用。v2 的"离线优先"仅指进度/书签同步（先写 Room，后台同步），完全不涉及书籍内容。没有书籍文件缓存机制、没有下载离线阅读功能、没有本地书籍存储策略。离开 LAN 后阅读完全不可用——这对移动阅读应用来说是一个关键功能缺失。`BookSource` 接口提及 `LocalFileBookSource` 但标注为 `TODO: implement`。Room 存储元数据但不存储书籍文件。

**S2 — 同步后端完全未定义（Sync Gap, underspecified）**
文档提及 "ReaderEventBus.BookProgressSaved 触发后台同步"，但从未指定：(1) 同步协议/传输格式、(2) 后端端点 URL 或 API、(3) Calibre Content Server 仅有读取端点、无写入/同步能力的情况下，进度/书签/批注如何同步。LWW 合并被概念性提及，但实现细节（用户 ID 分区、冲突解决算法、合并函数）完全缺失。**同步目的地不存在**——数据同步到哪里？这是最关键的架构缺口。

**S3 — Gradle 构建将直接失败（Feasibility, Gradle）**
两个致命缺失：
- `nanohttpd` 在整个 EPUB 引擎设计中引用但 **不在 `libs.versions.toml` 中**。Maven 坐标: `org.nanohttpd:nanohttpd:2.3.1`。
- `com.artifex.mupdf:fitz` 托管在 `maven.ghostscript.com`，但 `settings.gradle.kts` 仅声明 `google()` 和 `mavenCentral()`。任何依赖 mupdf 的模块构建将失败。

另有版本陈旧问题：Kotlin 2.1.10（2025 年 1 月发布，距今 17 个月）配合 AGP 8.8.2（2026 年 6 月 4 日）存在微妙的工具链不兼容风险；Markwon 4.6.2（2021 年 2 月，距今 5 年+）可能与现代 AGP/Gradle 不兼容。

**S4 — foliate-js 与 Android WebView 存在根本性安全冲突（Feasibility, Foliate）**
foliate-js README 明确声明安全的 EPUB 脚本处理"目前不可能"，因 blob: URL 同源问题。该库通过 blob: URL 提供 EPUB 内容，与父页面共享 origin，使 iframe sandbox 失效。作者声明 CSP 对安全"势在必行"，但通过 nanohttpd 提供的 WebView 中配置 CSP 非常复杂。此外：库明确标记为"不稳定"、设计目标为桌面浏览器（非 Android WebView）、字体反混淆需 Web Crypto API（仅 HTTPS 安全上下文可用，本地 nanohttpd HTTP 不满足）。已确认的 Chrome Android（issue #84：文本选择时页面布局偏移）和 Firefox Android（issue #79：捏合缩放破坏触摸翻页）bug 在 WebView 中未测试。

### 🟠 高 (应在实现前修复)

**H1 — Locator duplicated identically in two packages with incompatible annotations**
`dev.readflow.render.api.Locator` (ReaderEngine.kt, 不含 @Serializable) 和 `dev.readflow.core.model.Locator` (Locator.kt, 含 @Serializable)。这是两个**不等价**的类型。按架构分层原则，Locator 应仅存在于 `:core:model` (Layer 0)，`:render:api` 应 import 使用。

**H2 — ExtensionContext 对 ReaderState 的跨层依赖违反模块规则**
`ExtensionContext` 声明 `val readerState: StateFlow<ReaderState>`，其中 `ReaderState` 定义在 `:features:reader`。但 `:extensions:api` (Layer 1) 仅依赖 `:core:model`，不依赖 `:features:reader`。这是一个跨层依赖违规——ReaderState 必须移到 `:core:model`，或 ExtensionContext 必须使用类型无关 API。

**H3 — 缺失渲染模块对应声明的引擎**
Section 8.2 Koin DI 注册了 6 个引擎绑定 (EpubWebViewEngine, PdfRendererEngine, MuPdfDocxEngine, MuPdfCbzEngine, TxtVirtualPagerEngine, MarkwonEngine)，但模块树 (Section 2.1) 仅有 5 个渲染模块 (api, epub, pdf, txt, animate)。没有 `render:docx`、`render:cbz`、`render:md` 模块。Section 4.3 推测 DOCX/CBZ "可合并为一个 render:mupdf 模块"、MD "建议独立模块 render:md"——均未最终确定。

**H4 — Module count off-by-one throughout the document**
Section 2.1 标题声明 "20 个模块"，但模块树仅列 19 个条目。Section 9.3 `settings.gradle.kts` 也仅含 19 个模块。Phase 1 Step 3 称 "20 个子模块" 暗含 21 个模块总数（含 :app）——与 19 不符。此偏差影响所有模块级别的估算和描述。

**H5 — ReaderRootLayout location conflict**
Section 3.3 代码块头部放置于 `android/app/src/main/java/dev/LinReads/ui/reader/ReaderRootLayout.kt`（在 `:app` 模块中）。附录 B 放置于 `features/reader/.../ReaderRootLayout.kt`（在 `:features:reader` 模块中）。两者不可能同时正确。

**H6 — PDF API level 内部矛盾**
Section 4.3 PdfRendererEngine 规范称 "API 21+" (line 637)，但 Section 3.1 视图层级图 (line 213) 标注 PDF ImageView 为 "PdfRenderer (API 26+)"。构建配置 minSdk=26 覆盖了两者，但文档本身对最低 API 需求不一致。

**H7 — render:animate 依赖缺口**
`:render:animate` 提供 `CurlPageTransformer` (ViewPager2.PageTransformer)，但 `:features:reader` 未声明对其的依赖（Layer 6 表仅列 `:render:api`，不含 `:render:animate`）。ReaderState.transition 是 TransitionType enum（在 `:core:model`），但渲染过渡所需的实际 PageTransformer 实现位于 `:render:animate`，二者之间没有 DI 连接。

**H8 — 包名拼写错误（4 处）**
- Line 732 InkOverlay: `package no.dev.readflow.ink` — 多余 `no.` 前缀
- Line 1085 CalibreRepository: `package u/dev.readflow.core.calibre` — 多余 `u/` 前缀
- Line 1329 ExtensionLoader: `package Pages dev.readflow.extension` — 多余 `Pages ` 前缀
- Line 1493 ReadflowApplication: `package io dev.readflow` — 多余 `io` 前缀，缺少点号

### 🟡 中 (Phase 1 内应解决)

**M1 — 多个关键类型未定义**
`InkBrush`（InkOverlay.setBrush() 参数类型）、`ExtensionSettings`（ExtensionContext.settings 属性类型）、`BookMeta`（仅散文描述字段未完整列出）、`SearchResult`、`CalibreConfig`、`ThemeMode`、`TransitionType`、`CalibreClient` 类/接口签名、`LinReadsPreferences`、`AppSettings`——均被引用但从未定义。

**M2 — ReaderState 缺失关键字段**
无 `readingMode` 字段（即使 ReaderIntent.SetMode 存在且 ReadingMode enum 已定义）、无 `isUiVisible`（即使 ReaderIntent.ToggleUi 存在）、无 `bookId`/`bookUri`（配置变更或进程死亡后重新重建文档所必需）。`TransitionType` 未定义却被用作 `TransitionType.Slide` 默认值。

**M3 — Loading/Error 状态混合**
`isLoading: Boolean + error: LinReadsError?` 将 loading/loaded/error 三种状态合并为两个字段——`isLoading=true && error!=null` 语义模糊。应用独立的 `LoadingState` sealed class（Loading / Loaded(data) / Error(error)）。

**M4 — 错误处理不一致**
`BookSource` 接口不使用 `LinReadsResult<T>`——`search()` 直接返回 `SearchResult`、`getMetadata()` 直接返回 `BookMeta`（异常时抛异常）。`BookSourceRegistry.unifiedSearch()` 吞掉所有异常 `catch(e: Exception) { emptyList() }`。`ReaderEngine.open()` 返回 `Locator` 非 `LinReadsResult<Locator>`。无重试策略、无跨层错误传播规则、无全局异常处理器。

**M5 — 生命周期处理缺失**
无 SavedStateHandle 提及、无 onSaveInstanceState/onRestoreInstanceState 策略、无 WebView.saveState()/restoreState()（API 18 起已弃用）、无 ComposeView overlay 创建/销毁时机说明、无 lifecycle-aware flow 收集（repeatOnLifecycle / collectAsStateWithLifecycle）、无 ReaderEngine 挂起函数的调度器指定（open() 需在 IO 上执行重解析工作）。

**M6 — nanohttpd 停维 10 年**
最后一次发布为 2016 年 8 月的 2.3.1 版——约 10 年未维护。考虑 Ktor CIO 嵌入服务器替代（已是项目依赖），可减少依赖数量并利用现有 Ktor 知识。Ktor CIO 支持范围请求，对 EPUB 媒体资源有用。或直接 vendor nanohttpd 核心单文件（~3000 行 Java）入项目，消除外部依赖停维风险。

**M7 — MuPDF AGPL v3 许可证**
AGPL v3 要求完整对应源码向所有 APK 接收者开放（含任何与 MuPDF 链接的代码）。对开源个人项目可管理，但永久阻断未来闭源分发可能。应在 CLAUDE.md 中明确注明此许可证约束。

**M8 — 导航桥接方案未指定**
Navigation Compose 用于应用级导航，但 Reader 使用 View 层级（FrameLayout root）。Compose 导航目标与混合 View Reader 之间的过渡机制未定义。两种可能方案：(1) Reader 独立 Activity（打破单 Activity 模式），(2) ReaderScreen Composable 包裹 AndroidView{ReaderRootLayout}（保留单 Activity 但增加 Compose↔View 桥接复杂度）。二者均未选定或文档化。

### 🔵 低 (Phase 2 前应解决)

**L1 — 测试策略完全缺失**
文档中无测试章节、无按层级定义测试策略、无 fake/mock 示例、无测试目录结构、Turbine 库未在 libs.versions.toml 中（测试 Kotlin Flow/StateFlow 的标准库）、无 Room migration 测试、无 CI 测试脚本。

**L2 — 安全措施不完整**
WebView CSP 未定义（epubjs 在 WebView 中执行 JavaScript，但无脚本限制）、Calibre 密码仅注释 "encrypted" 但无 EncryptedSharedPreferences/Android Keystore/Tink 提及、`network_security_config.xml` 提及但内容未展示、`usesCleartextTraffic=true` 为全局而非 LAN 限定、无 SSL/TLS 证书固定策略、AnnotationEntity.strokeData 存为 ByteArray 无加密。

**L3 — 无障碍 (A11y) 完全未涉及**
无 TalkBack/屏幕阅读器兼容性讨论、无 contentDescription 要求、无系统字体缩放集成（仅手动 setFontSize(SP) 滑块）、无高对比度主题、无 48dp 最小触摸目标尺寸要求、无键盘导航支持、无墨水画布层的 accessibility 标签、无页面切换时应朗读内容说明。

**L4 — 大屏/折叠屏/多窗口未适配**
targetSdk=35 但无窗口尺寸类别、自适应布局、折叠屏姿势 API (Jetpack WindowManager)、分屏兼容性 (`android:resizeableActivity`)、拖拽进阅读器、或横屏/平板双页布局的设计。EPUB 引擎使用 CSS 列可自然适配，PDF/PdfRenderer 支持横屏，但无显式设计。

**L5 — 性能预算缺失**
无内存预算、冷启动时间目标、帧预算、ANR 阈值。粗略估算：WebView (50-100MB Chromium 引擎) + MuPDF .so (~15MB 加载) + foliate-js (~200KB JS + WebView 开销) + Room/DataStore (~2-5MB) + 应用代码 (~20-30MB) = 潜在 100-170MB。无 WebView 池跨书籍重用策略、无 Bitmap 内存核算、PDF LRU 缓存（3 页）但无单页内存计算（300 DPI 全彩页可达 ~25MB 作为 Bitmap）。

**L6 — KMP 策略未明确声明**
`core:model` 是纯 Kotlin (jvm)，非 multiplatform。HarmonyOS 用 ArkTS（与 KMP 不兼容），Web 用 TypeScript。跨平台共享限于 JSON Schema 契约 + quicktype 代码生成。仅当计划 iOS 目标时 KMP 才有意义（未提及）。这是合理的推迟，但应明确声明。

---

## 5. v2 引入的新问题（v1 中不存在或正常，v2 中恶化）

| 问题 | v1 状态 | v2 状态 | 严重度 |
|------|---------|---------|--------|
| foliate-js vs epubjs 内部矛盾 | v1 明确使用 epubjs 0.3.x | v2 同时引用 "foliate-js"（line 140 模块描述）和 "epubjs 0.3.x"（lines 207, 626 渲染规范），这是不同的库、不同的 API | 🔴 严重 |
| 模块过度拆分 | v1 为单模块简单项目 | v2 拆分 19-20 模块，为当前 3 个 Kotlin 文件的代码量严重过度模块化。可合并为 ~12 模块而不丧失关键隔离属性 | 🟡 中 |
| "离线优先" 语义降级 | v1 CLAUDE.md 的原则可能是泛化的设计方向 | v2 将其狭隘化为仅进度同步，丢失了书籍内容离线可用的含义 | 🔴 严重 |
| Compose↔View 混合架构复杂性 | v1 为纯 Compose 或纯 View（取决于平台） | v2 引入 FrameLayout+View+ComposeView 三层混合，导航桥接、生命周期协调、状态恢复均未定义 | 🟠 高 |
| WebView 转向风险 | v1 "不使用 WebView" | v2 通过 foliate-js WebView 渲染 EPUB——反转立场但未充分应对 WebView 安全、性能、生命周期复杂性 | 🟠 高 |
| 渲染引擎多样性 | v1 仅 epubjs + iframe PDF | v2 扩展到 5-6 引擎跨 JS/Android-API/JNI/Kotlin 边界，每个引擎独立线程、缓存、生命周期语义 | 🟡 中 |
| 手写笔批注架构 | v1 不存在 | v2 提供完整设计但 100% 为纸上设计——5 路径坐标转换链从未对抗真实引擎集成验证 | 🟡 中 |
| ServiceLoader 发现 | v1 无扩展系统 | v2 正确使用 ServiceLoader 但需注意 R8 优化可能重命名 META-INF/services 中的类名；应验证 ExtensionLoader 仅使用 ServiceLoader.iterator() 而不使用 Class.forName() | 🟢 低 |

---

## 6. 具体修复清单

### 6.1 一致性修复（Consistency）

| # | 位置 | 当前内容 | 应改为 | 优先级 |
|---|------|---------|--------|--------|
| C1 | Section 2.1 标题 + Section 9.3 + Phase 1 Step 3 | "20 个模块" / 19 条目 / "20 个子模块" | 统一为实际模块数。建议：确认最终模块列表后全文替换为精确数字。若实际为 19，标题改为 "19 个模块"，Phase 1 Step 3 改为 "19 个子模块（含 :app 共 20）" | 🔴 |
| C2 | Section 4.1 vs Section 6.1 — Locator 定义 | 两个位置分别定义 Locator，一个不含 @Serializable（render.api），一个含 @Serializable（core.model） | 从 `:render:api` 删除 Locator 定义，改为 `import dev.readflow.core.model.Locator`。保留 `:core:model` 中的 @Serializable 版本为唯一定义 | 🔴 |
| C3 | Section 3.3 (app 模块) vs 附录 B (features:reader 模块) — ReaderRootLayout | 两个冲突的文件路径 | 确定 ReaderRootLayout 所属模块（推荐 `:features:reader`），统一全文引用 | 🟠 |
| C4 | InkOverlay.kt line 732 | `package no.dev.readflow.ink` | `package dev.readflow.ink` | 🟡 |
| C5 | CalibreRepository.kt line 1085 | `package u/dev.readflow.core.calibre` | `package dev.readflow.core.calibre` | 🟡 |
| C6 | ExtensionLoader.kt line 1329 | `package Pages dev.readflow.extension` | `package dev.readflow.extension` | 🟡 |
| C7 | ReadflowApplication.kt line 1493 | `package io dev.readflow` | `package dev.readflow` | 🟡 |
| C8 | Section 4.3 line 637 vs Section 3.1 line 213 — PDF API level | "API 21+" vs "API 26+" | 统一为 "API 26+"（与 minSdk=26 一致） | 🟡 |
| C9 | Section 8.2 Koin DI — render:animate 未在 Layer 6 声明 | `:features:reader` 的依赖列表仅含 `:render:api` | 添加 `:render:animate` 依赖（如果 CurlPageTransformer 需要），或在文档中明确说明过渡效果如何从 animate 模块连接到 ReaderState | 🟠 |

### 6.2 定义缺失补全（Completeness）

| # | 缺失项 | 建议定义位置 | 优先级 |
|---|--------|-------------|--------|
| D1 | `InkBrush` 类型 | `:core:model` — 包含 strokeWidth: Float, color: Int, opacity: Float, toolType: InkToolType 等字段 | 🟠 |
| D2 | `ExtensionSettings` 类型 | `:extensions:api` — 应为 `Map<String, Any>` 或类型安全的 setting key-value 容器 | 🟠 |
| D3 | `BookMeta` 完整字段列表 | `:core:model` — 明确列出 id, title, authors, formats, tags, series, seriesIndex, cover, lastModified, sourceId 的 Kotlin data class | 🟡 |
| D4 | `SearchResult` 类型 | `:core:model` — 包含 books: List<BookMeta>, totalCount: Int, query: String | 🟡 |
| D5 | `CalibreConfig` data class | `:core:calibre` — 包含 host: String, port: Int | 🟡 |
| D6 | `ThemeMode` enum | `:core:model` — DAY, NIGHT, WARM, 以及未来的 HIGH_CONTRAST | 🟡 |
| D7 | `TransitionType` enum | `:core:model` — SLIDE, CURL, NONE | 🟡 |
| D8 | `CalibreClient` 类/接口签名 | `:core:calibre` — 定义所有 HTTP 方法签名（getBooks, getBook, search, getFormats 等） | 🟡 |
| D9 | `LinReadsPreferences` 类型 | `:core:prefs` — DataStore 键值集合的类型安全包装 | 🟡 |
| D10 | `AppSettings` 类型 | `:core:prefs` — 若 SettingsRepository 确实暴露 Flow<AppSettings> 而非独立属性则定义，否则从文档中移除 AppSettings 引用 | 🟢 |
| D11 | `AnnotationEntity.strokeData` TypeConverter | `:core:database` — 提供 Room TypeConverter 用于 ByteArray | 🟡 |
| D12 | `ReadingMode` enum 完整定义 | `:core:model` — SCROLL, PAGED, AUTO | 🟡 |

### 6.3 状态管理修复

| # | 修复内容 | 详情 | 优先级 |
|---|---------|------|--------|
| S1 | 添加独立的 `LoadingState` | `sealed class LoadingState { object Loading; data class Loaded<T>(val data: T); data class Error(val error: LinReadsError) }` | 🟠 |
| S2 | ReaderState 添加缺失字段 | `readingMode: ReadingMode`, `isUiVisible: Boolean`, `bookId: String`, `bookUri: Uri` | 🟠 |
| S3 | ReaderState 添加 `loadingState` 替代 `isLoading+error` | `loadingState: LoadingState = LoadingState.Loading` | 🟠 |
| S4 | 明确 TransitionType 默认值 | `transition: TransitionType = TransitionType.SLIDE`（需先定义 TransitionType enum） | 🟡 |
| S5 | 明确 EventBus 与 State 的交互规则 | 文档化：PageChanged 事件 → ViewModel 将当前 Locator 写入 ReaderState；只有 Intent 可触发状态转换，Event 为只读通知 | 🟡 |

### 6.4 Gradle 构建修复（Feasibility — 阻断项）

| # | 修复内容 | 详情 | 优先级 |
|---|---------|------|--------|
| G1 | 将 nanohttpd 添加到 libs.versions.toml | `nanohttpd = "org.nanohttpd:nanohttpd:2.3.1"` 或考虑 Ktor CIO 替代方案。若 vendor nanohttpd，需注明来源和 license | 🔴 |
| G2 | 添加 maven.ghostscript.com 仓库 | `settings.gradle.kts` 中添加 `maven { url = uri("https://maven.ghostscript.com") }`，附带注释说明仅用于 MuPDF 依赖 | 🔴 |
| G3 | 解决 foliate-js vs epubjs 矛盾 | 二选一：若选 foliate-js，全文统一引用并明确 vendoring 策略（git submodule 或复制到 assets/）；若选 epubjs 0.3.x，删除所有 foliate-js 引用 | 🔴 |
| G4 | 评估 Kotlin/AGP/Markwon 版本更新 | Kotlin 更新至最新稳定版（2026 年 6 月预计 2.3.x-2.4.x），Markwon 评估替代方案或确认版本兼容性 | 🟡 |

### 6.5 架构缺口修复

| # | 修复内容 | 详情 | 优先级 |
|---|---------|------|--------|
| A1 | 定义离线书籍缓存策略 | 明确：书籍文件下载到何处（应用私有目录/外部存储）、缓存淘汰策略（LRU, 最大容量）、离线模式下的 BookSource 行为、与 Calibre 的同步策略（检查 lastModified） | 🔴 |
| A2 | 定义同步目标和协议 | 选择同步后端（自建服务/WebDAV/文件同步）、定义传输格式（JSON/Protobuf）、设计冲突解决算法、明确 user-id 分区策略 | 🔴 |
| A3 | 添加性能预算 | 内存上限（建议 200MB 硬限制）、冷启动时间目标（建议 <800ms）、帧率目标（翻页 <120ms）、PDF 单页 Bitmap 内存上限（建议 30MB）、WebView 池策略 | 🟠 |
| A4 | 选定导航桥接方案 | 明确：Reader 使用独立 Activity 还是 AndroidView 包裹在 Compose Screen 中。文档化过渡动画、返回导航、状态恢复策略 | 🟠 |
| A5 | 定义大屏/折叠屏适配策略 | 明确：窗口尺寸类别使用、折叠屏姿势响应、横屏双页布局、分屏兼容性 (android:resizeableActivity=true) | 🟡 |
| A6 | 明确声明 KMP 推迟 | 添加一句："KMP 仅在计划 iOS 目标时才考虑。当前跨平台共享通过 JSON Schema + quicktype 代码生成实现，对 Web + Android + HarmonyOS 的三端目标已足够。" | 🟢 |

### 6.6 ExtensionContext 跨层依赖修复

**修复**: 以下二选一：
- **方案 A（推荐）**: 将 `ReaderState` 从 `:features:reader` 移动至 `:core:model`。ReaderState 是领域模型，本就应属于 Layer 0。`:features:reader` 和 `:extensions:api` 均声明 `implementation(project(":core:model"))`。
- **方案 B**: ExtensionContext 使用类型无关 API（`val readerState: StateFlow<Map<String, Any>>`），由 Extension 自行反序列化。降低了类型安全性。

### 6.7 渲染模块补齐

**修复**: 以下三选一：
- **方案 A（推荐）**: 合并 DOCX/CBZ/MD 引擎到单个 `:render:mupdf` 模块（MuPDF 本身支持这三种格式）。添加 `render:md` 为独立模块（Markwon 与 MuPDF 完全不同）。
- **方案 B**: 为每种格式创建独立渲染模块（增加 3 个模块: render:docx, render:cbz, render:md）。
- **方案 C**: 将所有 MuPDF 相关引擎合并为一个 `:render:mupdf` 模块，将 MD 引擎合并到 `:render:txt` 中（Markwon 与 TxtVirtualPager 同为纯文本渲染，共享同一模块命名）。

选定的方案需同步更新 Section 2.1 模块树、Section 8.2 Koin DI、Section 9.3 settings.gradle.kts，并确保模块数与全文引用一致。

---

## 7. 最终裁决

### 能否进入实现阶段？

**当前状态: 不能。** 至少需要完成以下 **最小修复集** 后方可开始 Phase 1 编码。

### 最小修复集 (Minimum Viable Fix Set)

以下 8 项修复必须在任何代码编写前完成。预计耗时: **2-3 个工作日**。

| # | 修复项 | 类别 | 理由 |
|---|-------|------|------|
| 1 | **解决 foliate-js vs epubjs 矛盾** — 二选一并全文统一 | 可行性 | 两个不同库、不同 API——不可能同时实现两者。选择后影响 EPUB 渲染路径的所有后续决策（WebView 配置、JS bridge、安全模型、性能特征） |
| 2 | **将 nanohttpd 添加到 libs.versions.toml + 添加 maven.ghostscript.com 仓库** | 可行性 | 无此二者 Gradle sync 失败，代码无法编译 |
| 3 | **定义离线书籍缓存策略** | 缺口 | 移动阅读应用的核心功能。影响 BookSource 接口设计、Room schema、Calibre 同步逻辑 |
| 4 | **消除 Locator 重复定义** — 从 `:render:api` 删除，保留 `:core:model` 版本 | 一致性 | 两个不等价类型在代码库中同时存在将导致编译错误或运行时 ClassCastException |
| 5 | **修复 ExtensionContext→ReaderState 跨层依赖** — 将 ReaderState 移至 `:core:model` | 一致性 | 编译时依赖违规——Gradle convention plugin 应阻止此依赖。若不修复，convention plugin 的 enforce 将导致 `:extensions:api` 构建失败 |
| 6 | **补全 ReaderState 关键缺失字段** (readingMode, isUiVisible, bookId, loadingState) | 完备性 | MVI 核心类型——没有这些字段无法实现 ToggleUi、SetMode Intent 和配置变更恢复 |
| 7 | **定义同步目标和协议** — 至少明确同步目的地 | 缺口 | 若无同步目标，BookProgressSaved 事件无意义。影响整个进度同步功能的设计 |
| 8 | **统一模块计数** — 全文替换为实际模块数 | 一致性 | 偏差影响文档可信度和后续引用 |

### 补充建议（Phase 1 内应完成）

- 添加测试章节（至少定义每层测试策略和工具库依赖）
- 补全所有缺失类型定义（InkBrush, ExtensionSettings, CalibreClient 等）
- 修正 4 处包名拼写错误
- 解决 ReaderRootLayout 位置冲突
- 添加性能预算（至少内存和冷启动目标）
- 明确声明 KMP 推迟

---

## 附录: 对标详情 (Benchmarking Details)

| 对标维度 | v2 评级 | KOReader | Mihon | Moon+ Reader | 评注 |
|----------|---------|----------|-------|-------------|------|
| 模块结构 | competitive | 扁平目录（无 Gradle 模块） | 9 模块 Clean Architecture | 单体 God Class | v2 方向正确但当前过度模块化（19 模块对应 3 个 Kotlin 文件）。建议合并为 ~12 模块 |
| 渲染引擎 | competitive | 2 个 C++ 引擎共享内存模型 | 图片查看器，无多格式需求 | 专有引擎 | v2 的 5 引擎覆盖广泛但引入跨 JS/API/JNI/Kotlin 边界的集成风险。foliate-js 提供 MIT 许可的 6 格式覆盖，但 WebView 集成未经验证 |
| 扩展系统 | competitive | 目录扫描 + _meta.lua（13 年实战） | APK 加载 + 签名验证 | 无沙箱 | v2 的 ServiceLoader + ExtensionContext 沙箱优雅且符合规模需求。Mihon 的 APK 加载过度但更强大 |
| 数据层 | competitive | 每书 sidecar 文件（物理绑定） | Room + SQLDelight 混合 | God Class notes 表 | v2 的 Room 4 表设计正确、LWW 同步合理。sidecar 的物理绑定优势在导出场景 |
| 状态管理 | **best-in-class** | 事件驱动（2013 设计），无结构化容器 | ScreenModel + StateFlow | 全局可变静态状态 | v2 的 MVI + EventBus + Hook 是最现代、可测试的状态架构。建议添加 Interactor 模式分离关注点 |
| 手写笔批注 | **best-in-class** | 无（电子墨水设备） | 无（漫画/图片阅读器） | 无 | v2 是四个项目中唯一尝试手写笔批注的。双 InkAnchor 策略正确但完全未经实现验证 |
| **综合排名** | **competitive** | production (13年) | production (150K行) | production (商业) | v2 的设计野心在四个项目中最高，但实现成熟度最低。KOReader 和 Mihon 用更简单的架构达到了生产级质量 |

---

*审计由 Claude Code (deepseek-v4-pro) 执行，基于 v2 架构设计文档全量审计输入。*
