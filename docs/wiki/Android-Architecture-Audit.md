# Android 端架构审计报告

> 审计日期：2026-06-18
> 审计对象：`docs/android-architecture.md` + `docs/wiki/Platform-Android.md`
> 对照基准：静读天下（源码验证版）、KOReader、Mihon/Komikku、Google 架构建议（2026.04）
> 审计方法：设计文档结构审查 + 源码实现现状核对 + 行业前沿对标

---

## ⚠️ 前置警告：设计与实现的鸿沟

| 维度 | 设计文档描述 | 实际代码实现 |
|------|------------|------------|
| 源文件数 | 20+ 文件（分层包结构） | **3 个 .kt 文件** |
| UI 架构 | MVI (Reader) + MVVM (Library/Settings) | `Text("书库 — 连接 Calibre")` |
| DI | Koin 模块化注入 | 无任何 DI 框架 |
| 渲染引擎 | MuPDF JNI + TxtVirtualPager + Markwon | epublib-core（未使用） |
| 动画引擎 | vsync 驱动 + CurlTransition | 不存在 |
| 存储 | Room + DataStore | 不存在 |
| 本地数据 | CalibreRepository + BookshelfRepository | 仅 HTTP client stub |
| 测试 | MVI + Engine interface 完全可 mock | 无测试基础设施 |

**当前代码仓库中的 Android 端是 scaffold（骨架）**，设计文档是完整的技术蓝图。以下审计以**设计方案**为主，以**当前实现可行性**为辅进行评价。

---

## 一、与静读天下逐项对比

### 1.1 架构模式

| 维度 | 静读天下 | LinReads 设计 |
|------|---------|-------------|
| UI 模式 | 无模式（Activity 直接操作 SQLite / 静态状态） | MVVM (Library/Settings) + MVI (Reader) |
| 数据流 | 手动触发，无响应式 | StateFlow + Flow（Room），单向数据流 |
| 依赖注入 | 无（静态方法直接调用） | Koin 构造器注入 |
| 状态管理 | 全局 static 可变字段（`Sync.cloudBookList` 等） | ViewModel scoped StateFlow |
| **评级** | ❌ 腐朽 | ✅ 现代 |

**结论**：LinReads 在架构模式上**全面碾压**静读天下。但这是"纸上碾压"——设计兑现之前，所有的"MVI + Flow"优势只是承诺。

### 1.2 渲染引擎

| 维度 | 静读天下 | LinReads 设计 |
|------|---------|-------------|
| 核心引擎 | MRTextView (97KB Java) 一统所有文本格式 | 格式独立：MuPDF / TxtVirtualPager / Markwon |
| 引擎性质 | 100% 自研 StaticLayout 替代（MyLayout） | MuPDF 第三方 JNI + PDF 系统原生 |
| EPUB 字体 | 成熟，CSS 全覆盖，多年调优 | Phase 1 CSS 注入（局限），Phase 2 自研反排 |
| PDF | RadaeePDF 商业库（GPL 不兼容） | **系统 PdfRenderer（零依赖）** ← 优势 |
| TXT | MRTextView 内部处理 | TxtVirtualPager（~300 行，Paging3） ← 更轻量 |
| CHM | 自实现 34 类（LZX 解压） | 不支持 — 合理取舍 |
| 跨平台潜力 | Android-only（MyLayout 只跑在 Android） | MuPDF C++ 核心可跨平台 ← 优势 |
| **评级** | 🟡 老化但功能全 | 🟢 现代但未实现 |

**差距项**：
1. **EPUB 字体渲染**：静读天下有多年的 CSS 渲染调优积累（MyHtml, HtmlToSpannedConverter 各 2000+ 行）。LinReads Phase 1 的 CSS 注入方案（`fitz.Document.setMetadata("style", "body{font-size:18pt}")`）只能改字号，**无法处理行高/段距/首行缩进/文本对齐**等细节。这是 LinReads 与成熟阅读器之间最大的渲染质量差距。

2. **MuPDF 无原生文字选择**：MuPDF bitmap 渲染模式无法像 MRTextView（基于 Span 体系）那样实现精确的文字选择、划词取义、脚注跳转。文档将文字选择推到 Phase 2 InkOverlay，但"坐标映射反查"方案在 EPUB 复杂排版中精度存疑。

**优势项**：
1. **PDF 零依赖**：用系统 PdfRenderer 替代 RadaeePDF，避开商业许可风险。这是结构性的正确决策。
2. **格式独立可插拔**：ReaderEngine interface 模式优于 BaseEBook 封闭继承。新增格式只需添加实现类，无需修改核心。
3. **TxtVirtualPager 轻量**：~300 行 vs MRTextView 97KB，正确的成本收益比。

### 1.3 动画引擎

| 维度 | 静读天下 | LinReads 设计 |
|------|---------|-------------|
| 翻页动画 | NewCurl3D (~1966 行) | 自研 AnimationEngine + CurlTransition (~120行) |
| 驱动方式 | 未知（推测 View 动画/Canvas） | vsync 对齐（`withFrameNanos`，Choreographer tick） |
| 与渲染引擎耦合 | 紧耦合（在 View 内部处理） | 解耦（bitmap in/out，与 ReaderEngine 无关）|
| 可切换 | 黑盒 | interface + enum 插件化 |
| 跟手翻页 | 未知（推测有） | 已知局限：Phase 1 无 follow-finger |
| **评级** | 🟡 可工作但不透明 | 🟢 设计优秀但 Phase 1 功能欠缺 |

**差距项**：
1. **跟手翻页**：静读天下的翻页大概率支持手势跟随（1966 行代码体量暗示这一点）。LinReads 文档明确承认 CurlTransition 无动态 follow-finger，推到 Phase 2。这是**用户感知最明显的差距**——翻页不跟手会显著降低阅读体验。
2. **卷页物理**：NewCurl3D 有成熟的 3D 卷页效果。LinReads 的 CurlTransition 设计有正确思路（贝塞尔曲面 + 阴影），但 120 行 vs 1966 行，功能深度差距明显。

**优势项**：
1. **架构解耦**：bitmap-in/out 模式使得动画引擎完全独立于渲染引擎，这是静读天下做不到的。
2. **vsync 精确驱动**：避免掉帧和撕裂，比传统 View 动画更流畅。

### 1.4 数据层

| 维度 | 静读天下 | LinReads 设计 |
|------|---------|-------------|
| 数据库 | SQLite 裸操作（raw SQL + Cursor） | Room (编译时 SQL 校验 + Flow) |
| 响应式 | 无（手动刷新 UI） | Room → Flow → ViewModel → Compose 自动重组 |
| Schema 演化 | 手动 `onUpgrade` + `bak1/bak2` 哑列 | Room Migration + `fallbackToDestructiveMigration` |
| 进度/标注 | notes 表三合一（`highlightLength==0` 区分） | **分表**：`reading_progress` / `annotations` |
| 设置 | SharedPreferences 裸写 | DataStore Preferences（类型安全 + Flow） |
| 并发 | 零保护静态可变字段 | 协程 + Flow（结构化并发） |
| **评级** | ❌ 腐朽 | ✅ 现代 |

**结论**：数据层设计完胜静读天下，且无争议。Room + DataStore 是 2025-2026 标准做法。唯一建议是将 Room 迁移计划从 Room 2.7.1 升到最新稳定版（撰写时已到 2.8.x+），并考虑 SQLDelight 作为 KMP 未来路径。

### 1.5 可测试性

| 维度 | 静读天下 | LinReads 设计 |
|------|---------|-------------|
| 单元测试 | 不可能（全局静态状态，View 绑定） | ✅ ReaderEngine interface mockable |
| UI 测试 | 不可能 | ✅ Compose Testing API |
| 集成测试 | 不可能 | ✅ Room in-memory + Ktor MockEngine |
| **评级** | ❌ 零 | ✅ 设计就绪 |

这是 LinReads 最大的结构性优势——不是因为做到了，而是因为**设计了能做**。静读天下 15 年积累的代码完全没有可测试性，任何改动都有退化风险。

### 1.6 网络层

| 维度 | 静读天下 | LinReads 设计 |
|------|---------|-------------|
| Calibre REST | 无 | ✅ Ktor Client + 三端共享类型契约 |
| OPDS | ✅ 原生支持 (8 类) | 备选（Calibre 自身支持 OPDS） |
| 云同步 | Dropbox/GDrive/WebDAV/FTP（1529 行 God Class） | 待设计（linreads-sync skill） |
| **评级** | 🟡 全面但腐朽 | 🟢 专注但缺失 OPDS |

**差距项**：
1. **OPDS 支持**：静读天下原生支持 OPDS 协议，与 Calibre 天然兼容。LinReads 现在只有 Calibre REST API（`/ajax/search`），缺少 OPDS 意味着无法接入 Calibre 以外的 OPDS 书源（如 Gutendex、自建 OPDS 服务器）。
2. **多后端同步**：静读天下支持 4 种云后端。LinReads 的进度同步方案尚未确定后端选型。

**优势项**：
1. **Ktor + 共享契约**：比静读天下的裸 HTTP 调用清晰得多。
2. **三端统一**：`shared/api/calibre-contract.ts` 作为唯一真相来源，Android 端只需遵循契约。

---

## 二、与 2025-2026 前沿架构对标

### 2.1 模块化

| 实践 | Google 建议 (2026.04) | Mihon/Komikku | LinReads 设计 |
|------|----------------------|---------------|-------------|
| 模块策略 | Feature-based 多模块 | Clean Architecture 9+ Gradle 模块 | **单模块** (`dev.readflow`) |
| 构建逻辑 | Convention Plugins (`build-logic/`) | 同 | **无** |
| 版本管理 | Version Catalog (`libs.versions.toml`) | 同 | **无** |
| KMP 支持 | AGP 9.0 要求 `shared/` + platform `*App/` | 不适用 | **无 KMP 规划** |

**🔴 严重差距：单模块架构**

LinReads 设计文档中声明"单模块，包边界清晰，随时可拆为 Gradle 子模块"。这在 2026 年已经是不够的——2026 年 5 月 AGP 9.0 发布后，多模块 + convention plugins + version catalog 已成为标准实践。

建议的分割（按 Mihon 模式）：
```
readflow-android/
├── app/                    # Application, MainActivity, DI 组装
├── core/calibre/           # CalibreClient + CalibreRepository
├── core/database/           # Room DB, DAO, Entity
├── core/prefs/              # DataStore SettingsRepository
├── domain/                  # Use Cases (零 Android 依赖)
├── features/library/        # LibraryScreen + LibraryViewModel
├── features/reader/         # ReaderScreen + ReaderViewModel (MVI)
├── features/settings/       # SettingsScreen + SettingsViewModel
├── render/engine/           # ReaderEngine interface
├── render/document/         # MuPdfEngine
├── render/txt/              # TxtVirtualPager
├── render/animate/          # AnimationEngine + transitions
├── presentation-core/       # 共享 Compose 组件 / Design System
└── gradle/libs.versions.toml
```

### 2.2 状态管理

| 实践 | 前沿标准 | LinReads 设计 |
|------|---------|-------------|
| Reader 状态 | MVI (sealed Intent → State) | ✅ MVI |
| Library/Settings | MVVM + sealed UiState | ✅ MVVM |
| 单 StateFlow 暴露 | Strongly Recommended | ✅ `ReaderState` data class |
| `collectAsStateWithLifecycle` | Strongly Recommended | 未提及 |
| `WhileSubscribed(5000)` | Strongly Recommended | 未提及 |
| plain state holder (非 ViewModel) | Recommended | 未提及 |

**🟡 中等差距**：
1. 未约定 `collectAsStateWithLifecycle()` 用法——这是 Compose 生命周期安全的基础，缺失会导致配置变更/后台状态泄漏。
2. 未提及 `WhileSubscribed(5000)` 用于 `stateIn()`——这关系到 Flow 在后台是否继续活跃。

### 2.3 导航

| 实践 | 前沿标准（2025-2026） | LinReads 设计 |
|------|----------------------|-------------|
| 方案 | Voyager（Mihon/Komikku） 或 Navigation Compose 3 | Navigation Compose 2.8+ |
| 类型安全 | sealed class Route（Voyager）/ Serializable Route | 未明确 |

**🟡 轻微差距**：Navigation Compose 2.8+ 已经提供了 type-safe sealed routes，与 Voyager 差距缩小。但 Voyager 在 Compose Multiplatform 场景更强（LinReads 的 HarmonyOS 和 Web 端不涉及）。

### 2.4 渲染引擎架构

与前沿方案对比：

| 维度 | KOReader | Mihon/Komikku | 前沿国内 App | LinReads 设计 |
|------|---------|---------------|------------|-------------|
| 引擎语言 | C++ (crengine/MuPDF) | Kotlin + Compose | C++/Skia 跨平台 | Kotlin 包装 MuPDF JNI |
| 插件化 | ✅ 格式通过注册表 | ✅ 源通过 APK 加载 | ✅ 插件总线 | ⚠️ ReaderEngine interface（非插件化） |
| 平台共享 | C 核心跨 6 平台 | Android-only | C++ 核心 iOS/Android 共享 | ⚠️ MuPDF C 核心可跨平台但仅用于 Android |

**🟡 差距：无真正的插件加载机制**

KOReader 的 `DocumentRegistry` 和 Mihon 的 `SourceManager` 都支持**运行时动态加载**格式/源。LinReads 的 `ReaderEngine` interface 只支持编译时注册——新增格式需要改代码重新编译。

建议：ReaderEngine 实现类通过 `ServiceLoader` 或简单的注册表 map 发现，而不是硬编码 `when(format)`。

### 2.5 依赖注入

| 方案 | 市场地位 | LinReads 选择 |
|------|---------|-------------|
| Hilt | Google 官方推荐，复杂 App 首选 | ❌ |
| Koin | 轻量，KMP 兼容，社区广泛使用 | ✅ |
| Injekt | Mihon/Komikku 使用 | ❌ |

**✅ 合理选择**：Koin 对 LinReads 的规模合适。但文档描述"Koin 模块注入，无注解处理器"——注意 Koin 4.0+ 已引入 KSP 注解处理器作为可选项（compile-safe 模块验证），建议评估启用。

### 2.6 缺失的前沿能力

以下能力在 2025-2026 年已成为阅读 App 的标配或差异化特征，LinReads 设计中完全缺失：

| 能力 | 说明 | 优先度 |
|------|------|--------|
| **自适应布局** | 折叠屏、平板、横竖屏切换 | P1 |
| **TTS 朗读** | 静读天下有 `BookTtsService`，Mihon 有 TTS 支持 | P2 |
| **阅读统计** | 时长/字数/日历（静读天下 `statistics` 表 + `PrefBookCalendar`） | P3 |
| **动态色彩** | Material You / 封面取色自适应主题（Komikku 已实现） | P3 |
| **AI 辅助** | 摘要/问答/翻译（2025-2026 前沿） | P4 |
| **无障碍 (a11y)** | TalkBack 适配、字体缩放、对比度（项目已有 `accessibility` skill） | P1 |
| **Detekt/Konsist** | 架构规则静态检查 | P3 |

### 2.7 构建现代化差距

| 项目 | 现状 | 建议 |
|------|------|------|
| Compose BOM | `2024.12.01` | 升到 `2025.06.00`+ |
| Kotlin | 未明确 | 升到 2.2+ (compose compiler 已内置) |
| KSP | 未使用 | 启用（Room 必需） |
| Version Catalog | 无 | 引入 `libs.versions.toml` |
| Convention Plugins | 无 | 引入 `build-logic/` |
| Compose Compiler | `kotlinCompilerExtensionVersion "1.5.14"`（旧方式） | 迁移到 Kotlin 2.0+ Compose Compiler Gradle Plugin |

**🔴 严重差距**：`kotlinCompilerExtensionVersion = "1.5.14"` 是 2024 年 Q1 的旧方案。Kotlin 2.0+ 已将 Compose Compiler 作为 Kotlin 编译器插件内置，不需要单独指定版本。`build.gradle.kts` 中出现此配置意味着项目使用的 Kotlin 版本低于 2.0。

---

## 三、内部设计矛盾

审查过程中发现以下设计文档内部冲突或模糊之处：

### C1：PDF 渲染方案矛盾

| 文档 | 声明 |
|------|------|
| `docs/android-architecture.md` | MuPDF 处理 EPUB · PDF · DOCX |
| `docs/wiki/Platform-Android.md` | PDF → **PdfRenderer**（系统） |
| `docs/research/rendering-engine-analysis.md` | PDF → **PdfRenderer** |

**分析**：android-architecture.md 将 PDF 与其他格式并列在 MuPDF 下，但渲染引擎分析文档和 Wiki 都说用 PdfRenderer。**应该以 PdfRenderer 为准**（零依赖、系统维护），MuPDF 只用于 EPUB + DOCX + CBZ。需修正 android-architecture.md 中的格式策略表。

### C2：epublib vs MuPDF

| 位置 | 内容 |
|------|------|
| `build.gradle.kts` | `implementation("nl.siegmann.epublib:epublib-core:4.1")` |
| `docs/android-architecture.md` | "移除 epublib，用 MuPDF 替代" |

**分析**：build.gradle 仍包含 epublib（且用 `implementation` 而非 `compileOnly`），但设计文档已明确移除。epublib 是 Java EPUB 元数据/解析库，不处理渲染。如果 MuPDF 能读取 EPUB 元数据，epublib 应删除；如果不能，epublib 可用于元数据解析而 MuPDF 用于渲染。当前状态需要澄清。

### C3：动画引擎 Compose 集成方式模糊

文档说 "Compose 侧：`var frame by remember { mutableStateOf(initialBitmap) }`"。但 `Bitmap` 不是 `@Stable`/`@Immutable` 类型——每次设置都会触发完整重组。正确做法是用 `ImageBitmap`（Compose 原生类型）或在 `Canvas` composable 中直接用 `drawImage` 绕过重组。当前伪代码暗示的性能特征与声明目标（"GPU 加速、RenderThread"）有出入。

### C4：预取页数不足

文档说预取策略为 `index + 1`（当前页就绪后预取下一页）。但 CurlTransition 需要 **当前页 + 下一页的 bitmap** 才能渲染卷页效果——如果用户在动画中途改变方向往回翻，`prefetched` 对反向翻页零作用。

建议：双向预取（`index - 1` 和 `index + 1`），LRU 缓存 3-5 页。

---

## 四、总体评估

### 评分卡

| 维度 | vs 静读天下 | vs 2025-2026 前沿 | 实现度 |
|------|-----------|-----------------|--------|
| 架构模式 | ✅ +3 | ✅ +1 | 0% |
| 渲染引擎 | 🟡 各有优劣 | 🟡 -1 | 0% |
| 动画引擎 | 🟡 +1 (设计) / -1 (功能) | 🟡 持平 | 0% |
| 数据层 | ✅ +3 | ✅ +1 | 0% |
| 可测试性 | ✅ +3 | ✅ +1 | 0% |
| 模块化 | — | 🔴 -2 | 0% |
| 构建现代化 | — | 🔴 -2 | 20% |
| 网络层 | ✅ +1 | 🟡 持平 | 30% |
| 同步 | — | 🟡 -1 | 0% |
| 插件/扩展 | 🟡 -1 (缺 OPDS) | 🔴 -2 (缺运行时加载) | 0% |
| 整体 | **设计完胜** | **结构差距明显** | **骨架** |

### 核心结论

1. **设计层的最大优势**：架构模式（MVI + MVVM）、数据层（Room + DataStore）、可测试性设计——这三个维度上，LinReads 的**文档蓝图比静读天下好一个时代**。

2. **设计层的最大劣势**：
   - 与静读天下比：EPUB 字体渲染深度差距最大（CSS 注入 vs 多年 CSS 全覆盖调优），翻页跟手缺失，OPDS 缺失。
   - 与前沿比：单模块架构过时，无插件加载机制，构建工具链陈旧（Kotlin 1.x + 旧 Compose Compiler），缺少自适应布局和无障碍设计。

3. **最大风险**：设计文档 95% 未实现。当前 3 个 .kt 文件总共约 120 行代码，连"骨架"都算不上。从 scaffold 到设计蓝图，工作量估算：
   - MVVM + MVI + Koin + Room + DataStore + Navigation：**2-3 周**
   - MuPDF JNI 集成 + ReaderEngine + 4 格式渲染器：**4-6 周**
   - AnimationEngine + 3 种 Transition + 双向预取：**2-3 周**
   - 测试、构建现代化、多模块拆分：**1-2 周**
   - **总计：9-14 周（单人全职）**

4. **最紧迫的修正**（应在开始实现前完成）：
   - [ ] 统一 PDF 渲染方案（以 PdfRenderer 为准，修正 android-architecture.md）
   - [ ] 决定 epublib 去留（元数据解析 vs MuPDF 全覆盖）
   - [ ] 升级 Kotlin + Compose Compiler 到 2.2+ / BOM 2025.06+
   - [ ] 引入 version catalog + convention plugins
   - [ ] 规划多模块拆分（至少 `:core:calibre`, `:core:database`, `:features:reader` 三个模块）
   - [ ] 明确 EPUB 字体渲染的 Phase 1 具体覆盖范围（字号/行高/段距/缩进/对齐？）

---

_参考：_
- [docs/android-architecture.md](../android-architecture.md) — 原始设计文档
- [docs/wiki/Architecture-Verification.md](Architecture-Verification.md) — 静读天下源码验证
- [Platform: Android](Platform-Android.md) — Wiki 摘要版
- [Rendering Engine](Rendering-Engine.md) — 渲染引擎选型
- [Research: Moon+ Reader](Research-MoonReader.md) — 静读天下评审
- [Google Android Architecture Recommendations](https://developer.android.com/topic/architecture/recommendations) (2026.04)
- [Mihon/Komikku DeepWiki](https://deepwiki.com/mihonapp/mihon)
- [KOReader Architecture](https://deepwiki.com/koreader/koreader)
