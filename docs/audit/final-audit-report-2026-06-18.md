# LinReads Android 架构终审报告

> 审计日期：2026-06-18
> 范围：`android/` 源码 + `docs/android-architecture.md` + `docs/wiki/` 设计文档 + `shared/api/`
> 方法：四个独立维度的交叉审计（开放度 · 构建便利度 · 接口契约 · 集成匹配度），合并为终审结论
> 实际代码规模：5 个源文件，约 120 行 Kotlin（不含 build.gradle.kts）

---

## 一、执行摘要

**该架构目前不具备实施就绪条件。** 在四个审计维度上，综合得分低于可接受的实施门槛（满分 5 分，加权平均 2.1/5）。三个结构性矛盾——(1) Compose Bitmap 渲染与 View 系统 ink 覆盖层不兼容，(2) "零 WebView"原则与 EPUB 渲染/笔写叠加需求互斥，(3) 共享契约无跨端强制执行机制——构成不可绕过的阻断点。好消息是实际代码仅约 120 行，改架构的沉没成本为零。当前架构文档所描述的"纸面架构"方向基本正确（MVI + ReaderEngine interface + Room），但实际代码与文档之间存在巨大鸿沟（0 个 ViewModel、0 个 Room 实体、0 个测试），且文档自身也存在未解决的设计断裂。在完成本报告第五节所列的"便利基线"修复清单（4 个 BLOCKER + 6 个 HIGH + 5 个 MEDIUM）之前，不应开始任何功能实现代码的编写。

---

## 二、综合评分表

| 维度 | 子维度 | 得分 | 权重 | 加权 |
|------|--------|------|------|------|
| **扩展开放度** | 格式路由 | 2/5 | 0.25 | 0.50 |
| | 引擎可替换性 | 2/5 | 0.25 | 0.50 |
| | 功能扩展点 | 1/5 | 0.25 | 0.25 |
| | Ink 覆盖层 | 2/5 | 0.15 | 0.30 |
| | 同步后端 | 1/5 | 0.10 | 0.10 |
| *小计* | | *1.65* | | |
| **构建便利度** | 模块化 | 3/5 | 0.15 | 0.45 |
| | 构建系统 | 1/5 | 0.30 | 0.30 |
| | 依赖管理 | 1/5 | 0.20 | 0.20 |
| | 开发体验 | 1/5 | 0.25 | 0.25 |
| | 测试 | 1/5 | 0.10 | 0.10 |
| *小计* | | *1.30* | | |
| **接口契约** | 契约一致性 | 2/5 | 0.25 | 0.50 |
| | 接口稳定性 | 2/5 | 0.20 | 0.40 |
| | 跨平台对齐 | 2/5 | 0.25 | 0.50 |
| | 分层清晰度 | 2/5 | 0.15 | 0.30 |
| | 错误处理 | 1/5 | 0.15 | 0.15 |
| *小计* | | *1.85* | | |
| **集成匹配度** | Compose-View 断裂 | 2/5 | 0.25 | 0.50 |
| | 渲染-Ink 数据流 | 3/5 | 0.20 | 0.60 |
| | 总数据流管道 | 2/5 | 0.20 | 0.40 |
| | 生命周期/进程死亡 | 1/5 | 0.15 | 0.15 |
| | 多格式共存 | 2/5 | 0.20 | 0.40 |
| *小计* | | *2.05* | | |

| **总加权平均** | | | | **2.08 / 5** |
| **最低子维度** | 构建系统 (1/5)、扩展点 (1/5)、同步 (1/5)、测试 (1/5)、开发体验 (1/5)、错误处理 (1/5)、生命周期 (1/5) | | | |
| **最高子维度** | 渲染-Ink数据流 (3/5)、模块化 (3/5) | | | |

---

## 三、Top 5 核心问题（按严重程度排序）

### 问题 1：Compose Bitmap 渲染 ↔ View 系统 Ink 的架构互斥

**是什么**：
`docs/android-architecture.md` 规定的渲染路径是 MuPDF → Bitmap → Compose `Image(bitmap.asImageBitmap())`（纯 Compose 树），而 ink 覆盖层设计使用 `androidx.ink.InProgressStrokesView`——这是一个 `extends View` 的 Android View 系统组件。两种渲染树之间无 Z-order 关系，无法叠加。`docs/wiki/Ink-Architecture-Gap.md` 已完整记录此矛盾，但修复方案尚未应用于主架构文档。

**为什么重要**：
笔写是用户的核心双需求之一（"阅读 + 跟手笔写"），不是可选附加功能。如果初始渲染架构选错，后续所有 ink 集成工作将建立在错误基础上。`InProgressStrokesView` 的前缓冲区低延迟（<10ms）是 androidx.ink 的核心价值——不能用 Compose Canvas（延迟 30-50ms）平替。

**怎么修**：
1. 将 `docs/android-architecture.md` 的视图架构从"纯 Compose"改为 Hybrid View 方案（参照 `Ink-Architecture-Gap.md` 第四节、Mihon 的 `reader_activity.xml` 模式）：`FrameLayout` 根容器 → View 系统文档渲染层（WebView / ImageView / RecyclerView） + CanvasView + InProgressStrokesView → ComposeView 仅用于 UI 覆盖层（工具栏、菜单、设置面板）。
2. 删除 "零 WebView" 原则——EPUB 渲染改走 WebView（对标 Readium Navigator、Mihon WebView 模式）。
3. MuPDF 保留给 PDF/CBZ/DOCX（固定布局格式，page 坐标锚定即可）。

**工作量估算**：2-3 天（重写架构文档 + 建立 Hybrid View 原型 Activity，验证 ComposeView 与 View 树的触摸事件路由）

---

### 问题 2：项目不可构建——缺失 Gradle 基础设施

**是什么**：
`android/` 目录下：
- 无 `gradlew` / `gradlew.bat` / `gradle/wrapper/*` —— `CLAUDE.md` 中记载的 `./gradlew assembleDebug` 和 `./gradlew test` 命令无法执行
- 无 `settings.gradle.kts` —— 缺少 Gradle 多项目构建的最基本文件，项目技术上不是合法的 Gradle 构建
- 无根 `build.gradle.kts` —— 无法管理共享插件版本和配置
- 无 `gradle/libs.versions.toml` —— 所有依赖是 `app/build.gradle.kts` 中的硬编码字符串
- `kotlinCompilerExtensionVersion = "1.5.14"` —— 已过时的 Compose 编译器配置方式，应迁移至 Kotlin 2.0+ 的 Compose 编译器 Gradle 插件
- Compose BOM `2024.12.01` 约 18 个月过时
- 无 KSP 插件配置，但架构文档引用了 `ksp("androidx.room:room-compiler:2.7.1")`
- `app/build.gradle.kts` 声明了 `nl.siegmann.epublib:epublib-core:4.1`，但 `docs/android-architecture.md` 标记此项为"移除"——文档与构建文件不同步

**为什么重要**：
开发者 clone 仓库后**无法从命令行构建 Android 应用**。唯一可行的方式是用 Android Studio 手动指向 `android/app/` 文件夹，让 IDE 注入临时 wrapper。没有 CI 配置，没有任何质量门禁。

**怎么修**：
1. 在 `android/` 下执行 `gradle wrapper --gradle-version 8.9`，提交所有 wrapper 文件
2. 创建 `android/settings.gradle.kts`（含 `pluginManagement` 和 `dependencyResolutionManagement`）
3. 创建 `android/build.gradle.kts`（根，声明 AGP、Kotlin 2.1+、KSP、Compose 编译器插件）
4. 创建 `android/gradle/libs.versions.toml`（版本目录，至少包含 Compose BOM 2026.x、Ktor 3.x、Room 2.7.x、Koin 4.x、Coil 3.x）
5. 移除 `kotlinCompilerExtensionVersion`，改用 `id("org.jetbrains.kotlin.plugin.compose")`
6. 移除 `epublib-core` 依赖（与架构文档对齐）或更新文档说明保留原因

**工作量估算**：0.5-1 天（Gradle 基础设施是标准模板化操作）

---

### 问题 3：共享契约无强制执行——三端类型定义已出现分歧

**是什么**：
`shared/api/calibre-contract.ts` 定义了 `BookMeta` 包含 `series_index`、`cover`、`last_modified` 三个字段，但三端各自定义的 `BookMeta` 均缺失这些字段：

| 契约字段 | Web | Android (`CalibreClient.kt:14`) | HarmonyOS | 共享契约 |
|----------|-----|------|-----------|---------|
| `series_index` | ❌ | ❌ | ❌ | ✅ |
| `cover` | ❌ | ❌ | ❌ | ✅ |
| `last_modified` | ❌ | ❌ | ❌ | ✅ |

此外，Android 和 HarmonyOS（非 TypeScript 平台）完全无法消费 `.ts` 文件。没有代码生成、没有 JSON Schema 导出、没有 CI 漂移检测。契约实质上只是文档，不是契约。

**为什么重要**：
三端进度同步、书库一致性等功能依赖统一的类型定义。当前状态下，任何一个 API 变更都会在三端产生不同的、静默的兼容性断裂。等到三端各自积累了数百行类型定义代码后，统一成本将极其高昂。

**怎么修**：
1. 将共享契约导出为 JSON Schema（`shared/api/calibre-contract.schema.json`）
2. 添加 CI 检查：对比契约 Schema 与各平台实际类型定义的差异，构建失败时告警
3. 为 Kotlin (Android) 和 ArkTS (HarmonyOS) 提供代码生成脚本（QuickType 或自定义生成器），从 JSON Schema 产生平台类型
4. 补充遗漏字段到三端实现
5. 将 `CalibreConfig` 实际应用到 Android 和 HarmonyOS（当前 Web 用 env var，Android 用构造函数参数，HarmonyOS 用模块级变量——均不一致）

**工作量估算**：1-2 天（JSON Schema 导出 + 代码生成器 + 三端字段补齐）

---

### 问题 4：无插件/扩展机制——所有功能必须修改核心文件

**是什么**：
`android/app/src/main/java/dev/LinReads/ui/LinReadsApp.kt` 中，Library、Reader、Settings 三个 Tab 的编译通过 `when(selected)` 分支硬编码路由。添加 TTS 朗读、OPDS 书源、阅读统计等任何可选功能，都需要修改：
- `LinReadsApp.kt`（导航）
- `app/build.gradle.kts`（依赖）
- Room schema（如需新表）
- Koin 模块（DI 注册）

零扩展点：无 ServiceLoader 发现、无事件总线、无插件目录扫描、无拦截器管道。`CalibreClient` 是具体类而非接口——添加 OPDS 书源需要创建平行客户端或修改数据层。

**为什么重要**：
虽然项目定位为"个人项目"不需要 Mihon 级别的 APK 动态加载，但即使是个人项目也需要扩展点来支持**自己的可选功能**。KOReader 的 `.koplugin` 目录扫描模式证明：轻量扩展机制的成本极低，但收益巨大——TTS、统计、OPDS 等功能可以独立开发、独立测试、按需加载。

**怎么修**：
1. 定义 `Extension` 接口：
   ```kotlin
   interface Extension {
       val id: String
       suspend fun onAttach(readerState: StateFlow<ReaderState>)
       suspend fun onDetach()
   }
   ```
2. 使用 ServiceLoader 从 `plugins/` 目录发现扩展
3. 提取 `BookSource` 接口（`fun search()`, `fun metadata()`, `fun downloadUrl()`），让 `CalibreBookSource` 实现它，OPDS 和本地文件作为独立实现
4. 定义 `ReaderEventBus`（`SharedFlow<ReaderEvent>`），扩展监听事件而无需核心代码感知其存在
5. 导航改为注册表模式：每个功能注册自己的 `NavGraphBuilder` 扩展函数

**工作量估算**：1-2 天（接口定义 + ServiceLoader + BookSource 提取；具体扩展实现在各自功能阶段完成）

---

### 问题 5：无错误处理体系——所有错误退化为 `String?`

**是什么**：
全代码库唯一的错误表示是 `docs/android-architecture.md` 中 `ReaderState` 的一个字段：
```kotlin
val error: String? = null
```
没有 sealed error 类型、没有错误分类、没有恢复策略、没有日志策略。实际代码中：
- `CalibreClient.kt` 的 `search()` / `bookMeta()` 无 try-catch
- 网络错误（timeout/DNS/connection refused）、解析错误（malformed JSON/EPUB）、渲染错误（MuPDF JNI crash）完全不可区分
- 无重试逻辑、无退避策略、无离线 fallback、无降级模式
- `ReaderState.error` 如何从 CalibreClient → Repository → ViewModel → UI 的传播路径未定义

**为什么重要**：
阅读器是一个长时间运行的应用——网络中断、文件损坏、引擎崩溃是常态而非异常。没有结构化错误处理，用户永远只能看到"出了点问题"，开发者永远无法定位根因。

**怎么修**：
1. 在 `shared/api/` 下定义跨平台的 `LinReadsError` sealed hierarchy：
   ```kotlin
   sealed class ReadflowError {
       data class Network(val code: Int, val detail: String) : ReadflowError()
       data class Parse(val source: String, val detail: String) : ReadflowError()
       data class Render(val engine: String, val page: Int, val detail: String) : ReadflowError()
       data class NotFound(val resource: String) : ReadflowError()
       data class Auth(val reason: String) : ReadflowError()
   }
   ```
2. 所有 CalibreClient/Repository 方法返回 `Result<T, LinReadsError>`
3. 各层定义错误边界：Data 层捕获 HTTP/parse 错误，Domain 层添加重试/fallback 逻辑，UI 层按错误类型渲染差异化界面
4. 添加结构化日志：每个错误携带 layer tag + timestamp + context
5. `ReaderState.error` 从 `String?` 改为 `LinReadsError?`

**工作量估算**：1-2 天（类型定义 + 跨三端统一 + CalibreClient 改造）

---

## 四、架构已经做对的 5 件事

### 1. MVI 架构选型方向正确

`docs/android-architecture.md` 中 `sealed interface ReaderIntent → ReaderState` 的单向数据流设计是现代 Android 的最佳实践。对标 Mihon 的 `ReaderViewModel + StateFlow<ReaderState>` 模式，设计方向完全正确。虽然当前 `ReaderState` 内部结构需要修正（Bitmap 不应在 state holder 中、缺少 LoadingState、`isInkMode` 应改为状态而非模式），但 MVI 框架本身不需要改变。

### 2. ReaderEngine 接口抽象在概念层面正确

将渲染引擎抽象为接口（`open()` / `renderPage()` / `close()`）是正确的设计直觉。这为格式独立性提供了条件，也为测试提供了 mock 点。当前接口的具体方法签名需要调整（`Bitmap` → 平台无关类型、`pageCount` 应为 `suspend fun` 或 `StateFlow`），但抽象层的存在本身是架构的基石。

### 3. Calibre 内容服务器集成的设计方向独特且有价值

三端通过统一的 Calibre REST API 接入自托管书库——这是三个竞品项目（KOReader、Mihon、Readium）都不具备的能力。`CalibreClient.kt`（49 行）已经实现了 `search()`、`bookMeta()`、`downloadUrl()` 三个核心端点，且与 `shared/api/calibre-contract.ts` 的字段设计基本对齐（除上述遗漏字段）。这是 LinReads 的核心差异化竞争力。

### 4. 多格式覆盖的设计野心合理

架构文档覆盖 EPUB、PDF、DOCX、TXT、MD、CBZ 六种格式，且为每种格式选择了恰当的渲染策略：
- PDF → 系统 `PdfRenderer`（零依赖、零许可费）
- MD → Markwon Spannable（无中间 HTML）
- TXT → 自研 `TxtVirtualPager`（约 300 行，轻量合理）
- DOCX → MuPDF JNI（成熟方案）

格式覆盖的广度是合理的——虽然 EPUB 的渲染路径需要修正为 WebView，但格式策略的整体框架是经过思考的。

### 5. Ink 锚定问题的研究深度值得肯定

`docs/wiki/Ink-Architecture-Gap.md` 和 `docs/research/ink-anchoring-research.md` 对 EPUB reflow 上笔迹锚定这一行业难题的分析是全面且诚实的：明确记录了 GoodNotes/Notability 只支持 PDF、Apple Books 用浮动图片/CSS absolute 定位、Kindle Scribe 用 KFX 词级定位、KOReader 不支持手写——没有任何产品在 reflow EPUB 上解决了自由手写问题。`InkAnchor.Page` / `InkAnchor.Text` 的双模式锚定设计是务实的分期策略，Phase 1 先做 Page 锚定（PDF/CBZ），Phase 2 再做 Text 锚定（EPUB），这是正确的问题分解。

---

## 五、"便利基线"修复清单

### BLOCKERS（不修复则不能写任何功能代码）

| # | 项目 | 涉及文件 | 工作量 |
|---|------|---------|--------|
| B1 | **解决 Compose-View Ink 架构互斥**：将 `docs/android-architecture.md` 的视图架构改为 Hybrid View（FrameLayout + View 文档渲染 + Compose UI overlay） | `docs/android-architecture.md`（重写视图层次章节）、新建原型 Activity | 2-3 天 |
| B2 | **建立 Gradle 构建基础设施**：wrapper + settings + root build + version catalog + Compose 编译器插件迁移 + 移除 epublib-core | `android/settings.gradle.kts`（新建）、`android/build.gradle.kts`（新建）、`android/gradle/libs.versions.toml`（新建）、`android/app/build.gradle.kts`（重写）、`android/gradle/wrapper/*`（生成） | 0.5-1 天 |
| B3 | **共享契约机械化**：JSON Schema 导出 + 三端字段补齐（`series_index`, `cover`, `last_modified`）+ CI 漂移检测 | `shared/api/calibre-contract.schema.json`（新建）、`android/.../CalibreClient.kt`（补充字段）、`web/.../calibre.ts`（补充字段） | 1-2 天 |
| B4 | **ReaderEngine 接口落地为真实 Kotlin 文件**：将设计文档中的接口定义转为 `android/app/src/main/java/dev/LinReads/render/api/ReaderEngine.kt`，包含修正后的方法签名（`pageCount` 改为 `suspend fun`，添加 `supports()` 和 `priority`） | `render/api/ReaderEngine.kt`（新建）、`render/api/PageContent.kt`（新建）、`render/api/ReaderPosition.kt`（新建） | 0.5 天 |

### HIGH（开始编码前应完成）

| # | 项目 | 涉及文件 | 工作量 |
|---|------|---------|--------|
| H1 | **定义结构化错误类型**：`LinReadsError` sealed hierarchy + 三端统一 | `shared/api/errors.ts` 或 Kotlin `render/api/LinReadsError.kt` | 0.5 天 |
| H2 | **建立扩展点骨架**：`Extension` 接口 + `BookSource` 接口提取 + ServiceLoader 配置 | `render/api/Extension.kt`（新建）、`data/BookSource.kt`（新建）、`di/AppModule.kt`（新建） | 1 天 |
| H3 | **实现 ReaderState 修正**：移除 Bitmap、添加 `LoadingState`、`isStylusNearby` 替换 `isInkMode`、添加 `FormatConfig` sealed interface | `ui/reader/ReaderContract.kt`（新建） | 0.5 天 |
| H4 | **实现 InkAnchor 数据契约**：`InkAnchor.Page` / `InkAnchor.Text` sealed interface 落地为 Kotlin 代码 | `render/api/InkAnchor.kt`（新建） | 0.5 天 |
| H5 | **建立 CI 管道**：`.github/workflows/android-ci.yml`（assembleDebug + lint + detekt + test） | `.github/workflows/android-ci.yml`（新建） | 0.5 天 |
| H6 | **实现 CalibreRepository 接口**：从直接使用 `CalibreClient` 改为通过 repository 接口访问 | `data/calibre/CalibreRepository.kt`（新建）、修改 `CalibreClient.kt` | 0.5 天 |

### MEDIUM（可推迟但会累积技术债）

| # | 项目 | 涉及文件 | 工作量 |
|---|------|---------|--------|
| M1 | **DocumentRegistry 格式注册表**：weight-based 引擎竞争 + 用户 per-book override | `render/api/ReaderEngineRegistry.kt`（新建） | 0.5-1 天 |
| M2 | **ReaderEventBus**：`SharedFlow<ReaderEvent>` + `ReaderEvent` sealed class | `ui/reader/ReaderEventBus.kt`（新建） | 0.5 天 |
| M3 | **生命周期/进程死亡策略**：SavedStateHandle bundle 映射 + WebView saveState/restoreState + 笔迹 draft 保存 | `ui/reader/ReaderViewModel.kt`（新建） | 1 天 |
| M4 | **SyncBackend 接口契约**：在 `shared/api/` 下定义 `SyncBackend` + `SyncEvent` + `SyncResult` | `shared/api/sync-contract.ts`（新建） | 0.5 天 |
| M5 | **统一进度模型（Readium Locator）**：替换当前的三元组进度表示为 `Locator(href, type, locations)` | `render/api/Locator.kt`（新建）、修改 `ReaderPosition` | 1 天 |

---

## 六、终审结论

**LinReads Android 架构目前不具备实施就绪条件，但距可实施状态仅需 4 个阻断项修复（预估 4-6.5 个工作日）。**

核心矛盾不是"设计方向错误"而是"设计文档与实现代码之间的巨大鸿沟 + 设计文档自身的三个未解决断裂点"。好的一面是：架构文档中的 MVI 框架、ReaderEngine 抽象、多格式覆盖策略、Calibre 集成定位均方向正确，且实际代码仅约 120 行——改架构没有沉没成本。

**最小可行修复集**（修复后可开始编写 Reader 功能代码）：
1. 解决 Compose-View Ink 互斥（Hybrid View 架构重写）
2. 建立 Gradle 构建基础设施（可 clone-and-build）
3. 共享契约代码生成（三端类型统一）
4. ReaderEngine 接口 + ReaderState 修正落地为代码文件
5. 结构化错误类型 + CalibreRepository 接口提取（H1 + H6）

完成上述 6-8 个工作日的修复后，架构即可支撑 Android Reader 功能实现——EPUB 阅读、PDF 阅读、基础翻页和进度保存。笔写（Ink）功能仍需额外 2-3 周（Phase 1B：PDF 固定布局笔写 + Page 锚定），EPUB 笔写（Text 锚定）是整个领域的未解决问题，应诚实标记为 Phase 2+ 且不设截止日期。

---

**审计基准文件清单**：
- `android/app/build.gradle.kts`（30 行）—— 实际唯一的构建文件
- `android/app/src/main/AndroidManifest.xml`（14 行）
- `android/app/src/main/java/dev/LinReads/MainActivity.kt`（13 行）
- `android/app/src/main/java/dev/LinReads/ui/LinReadsApp.kt`（42 行）
- `android/app/src/main/java/dev/LinReads/calibre/CalibreClient.kt`（49 行）
- `shared/api/calibre-contract.ts`（26 行）
- `docs/android-architecture.md`（295 行）—— 主要设计文档
- `docs/wiki/Ink-Architecture-Gap.md`（331 行）—— Ink 断裂分析
- `docs/wiki/Architecture-Comparison.md`（439 行）—— 三项目竞品对比
- `docs/research/ink-anchoring-research.md` —— 笔迹锚定研究
- `CLAUDE.md` —— 项目指令与命令表
