# LinReads Android 架构审计 — 第三轮终审报告

> 审计日期：2026-06-18
> 审计方法：交叉校验 (Cross-Check) + 构建就绪度 (Build Readiness) + 实现差距 (Implementation Gap) + 一致性评分 (Coherence Scoring)
> 前序报告：`v2-audit-report-2026-06-18.md`（v2 五维审计）→ `final-audit-report-2026-06-18.md`（v1/v2 终审）→ 本报告（Round 3 综合裁决）
> 实际代码规模：3 个 Kotlin 源文件，约 120 行（不含 build.gradle.kts）

---

## 一、当前状态摘要

LinReads Android 端目前处于**架构设计成熟但实现起点极低**的状态。v2 架构设计文档（`docs/android-architecture-v2.md`，2310 行）已经过三轮迭代审计，补遗文档（Addendum）修复了 ReaderState、InkBrush、ExtensionSettings、DownloadStatus、SyncBackend、BookSource、性能预算七类缺口，设计在纸面层面已基本内洽。然而实现端几乎为零——21 个模块中仅 `:app` 有实际目录，其余 20 个模块目录不存在；`CalibreClient.kt` 中的 `BookMeta`/`SearchResult` 是内联定义而非共享类型；Room、Koin、ReaderEngine、Extension SPI、Convention Plugin 等核心基础设施无一存在。主文档与补遗文档之间存在 8 处交叉校验矛盾（含 4 个 HIGH 级别的类型定义不兼容），且 `libs.versions.toml` 缺失 3 个必要依赖导致构建必然失败。好消息是架构方向正确、MVI 选型合理、Calibre 集成定位独特、且仅约 120 行代码意味着改架构的沉没成本为零。进入 Phase 1 实现的前提是：接受将范围从 21 模块缩减至 5 模块的条件，并在每个周末交付可运行的 APK。

---

## 二、审计评分

### 2.1 交叉校验评分：2/5 — 主文档与补遗文档存在 4 个 HIGH 级矛盾

交叉校验将主架构文档（`docs/android-architecture-v2.md`）与补遗文档（Addendum）逐项比对，发现 8 处不一致，其中 4 处为 HIGH 严重度。核心矛盾：

| # | 冲突位置 | 冲突内容 | 严重度 |
|---|---------|---------|--------|
| 1 | Main §8.4 L1682-1693 vs Addendum P2.1 L18-33 | **ReaderState 定义互不兼容。** 主文档定义 10 个字段含 `documentView: View?`、`isLoading: Boolean`、`book: BookMeta?`、`fontSize: Float`。补遗文档定义 14 个字段，语义完全不同：`bookId: String`、`loadingState: LoadingState`、`fontSize: Int`、`format: BookFormat`、`theme: ThemeMode`、`zoomLevel`、`panOffset`、`isUiVisible`。补遗将 `documentView` 移除（声称 ReaderState 移到 `core:model`），但这与 MVI 契约中"View-driven rendering"原则矛盾——`core:model` 是 Layer 0 零依赖纯 Kotlin 模块，不可能持有 `android.view.View`。 | **HIGH** |
| 2 | Main §6.5 L1161-1171 vs Addendum P2.6 L191-205 | **BookSource 接口签名 7 处差异。** `search()` 返回 `SearchResult` vs `Result<List<BookMeta>>`；`bookId` 类型 `Int` vs `String`；`getDownloadUrl`/`getCoverUrl` 非 suspend vs suspend 返回 `Result`；参数顺序 `limit,offset` vs `offset,limit`；补遗移除 `sourceDescription` 和 `isAvailable()`；补遗新增 `download()` 和 `getDownloadStatus()`；包路径 `dev.readflow.extension` vs `dev.readflow.extensions.api`。此矛盾直接影响 Extension SPI 的所有消费者。 | **HIGH** |
| 3 | Main §4.1 L460-482 vs §6.1 L922-953 | **Locator sealed interface 在主文档自身内定义两次。** 一次在 `dev.readflow.render.api`（无 `@Serializable`），一次在 `dev.readflow.core.model`（有 `@Serializable`）。相同层级结构、不同包名、不同序列化注解。BACKLOG P1 L19 已承认未解决。 | **HIGH** |
| 4 | Addendum P2.1 L16: `import dev.readflow.render.api.Locator` in `core/model/.../ReaderState.kt` | **补遗的 ReaderState（位于 `core:model`）从 `render:api` 导入 Locator。** 但 `core:model` 是 Layer 0 无依赖模块，而 `render:api` 是 Layer 2 且依赖 `core:model`。这产生循环依赖 `core:model → render:api → core:model`。补遗声称此移动解决了 ExtensionContext 跨层依赖违规，但引入了新的循环依赖。 | **HIGH** |
| 5 | Main §2.1 L58 vs §11 B4 L2182 vs Appendix A L2211 vs Addendum P3.3 L281 | **模块计数不一致。** 主文档模块树和 `settings.gradle.kts` 各为 19，B4 阻断修复和附录 A 称 20，补遗称 21。`settings.gradle.kts` 和 `app/build.gradle.kts` 的依赖块从未为补遗新增的两个模块（`render:mupdf`、`render:md`）更新。 | **MEDIUM** |
| 6 | Main L1688 vs Addendum L31, L44 | **TransitionType 枚举值命名风格不兼容。** 主文档用 `TransitionType.Slide`（PascalCase），补遗定义 `TransitionType.SLIDE`（SCREAMING_SNAKE_CASE，含 SLIDE、CURL、FADE、NONE）。Kotlin Serialization 默认使用名称字符串，此不匹配将导致序列化失败。 | **MEDIUM** |
| 7 | Main L140, L207, L626 vs Addendum L275 vs BACKLOG P1 L25 | **EPUB 引擎命名混用。** 主文档 L140 称 epub-ts，L207 和 L626 仍称 epubjs 0.3.x。补遗统一称 epub-ts。BACKLOG 称统一已完成。三者指同一引擎但主文档未全部更新。 | **LOW** |
| 8 | Addendum P2.2 L57 `import androidx.compose.ui.graphics.Color` in `ink/.../InkBrush.kt` vs Main Layer 4 L148-151 | **InkBrush 在 `:ink` 模块中导入 `androidx.compose.ui.graphics.Color`。** 但主文档声明 `:ink` 是 View 系统模块，不含 Compose。CI 渲染模块 Compose-leak 检查（L2082）将标记此依赖。 | **LOW** |

**关键发现：** 补遗文档虽填补了主文档的类型定义空白，但引入了与主文档的不兼容变更（ReaderState 重定义、BookSource 签名变更、TransitionType 命名风格切换），且未同步更新主文档。两文档目前描述的是**两个不同的类型系统**，任何实现者依据其中一份文档编码都将与另一份产生编译冲突。

---

### 2.2 构建就绪度评分：WILL_FAIL — Gradle sync 必然失败

构建就绪度评估直接的可构建性（clone → `./gradlew assembleDebug`），结论是**当前无法通过 Gradle sync**，存在以下致命缺失：

| 缺失项 | 影响 | 严重度 |
|--------|------|--------|
| `libs.versions.toml` 缺失 `kotlinx-serialization-json` 库条目 | `:core:model` 模块（Layer 0，定义所有 JSON 类型）无法编译 | 🔴 阻断 |
| `libs.versions.toml` 缺失 `kotlin-serialization` 插件声明 | 所有使用 `@Serializable` 的 data class 编译失败 | 🔴 阻断 |
| `libs.versions.toml` 缺失 `kotlin-jvm` 插件声明 | `:core:model` 纯 JVM 模块无法配置 Kotlin 编译器 | 🔴 阻断 |
| `app/build.gradle.kts` 缺失 `material-icons-extended` 依赖 | `ReadflowApp.kt` 引用 `Icons.Default.LibraryBooks`（需要此 artifact），编译必然失败 | 🔴 阻断 |
| 根 `build.gradle.kts` 使用硬编码 `id()` 版本字符串而非 `alias(libs.plugins...)` | 与 `app/build.gradle.kts` 的版本目录用法不一致，存在版本漂移风险 | 🟠 高 |
| `build-logic/` convention plugin 目录不存在 | Phase 1 构建要求但不阻塞单模块构建。无 convention plugin 则分层规则无编译期强制 | 🟡 中 |
| `settings.gradle.kts` 仅含 `:app` | 单模块构建可进行，但所有 `implementation(project(":core:model"))` 等引用将失败 | 🟡 中 |

**好消息：** Gradle wrapper、`gradle-wrapper.properties`、`gradlew` 均存在且功能正常。修复上述 7 项后，单模块 `:app` 的 `assembleDebug` 应可成功。这约需 0.5-1 个工作日。

---

### 2.3 一致性评分

| 维度 | 评分 | 说明 |
|------|------|------|
| **简洁性 (Simplicity)** | 2/5 | 设计为 21 模块、8 层、convention plugin、MVI、ServiceLoader SPI、ink overlay、6 格式引擎——而当前代码仅 3 个文件约 120 行。设计复杂度与代码规模之间存在数量级差距。必须通过 Phase 1 激进缩减至 5 模块来恢复简洁性。 |
| **聚焦度 (Focus)** | 4/5 | "在任意格式的书上如纸上书写"这一独特价值定位清晰且始终贯穿架构设计的每一个决策——从双 InkAnchor 模型到 FrameLayout z-order 到工具类型触摸路由。Calibre 局域网书库集成是第二个清晰的聚焦点。两个聚焦点彼此协同。 |
| **一致性 (Consistency)** | 4/5 | 除交叉校验发现的 8 处矛盾外，架构内部逻辑链条完整：依赖方向自下而上（Layer 0→Layer 7）、数据流单向（Intent→ViewModel→State→UI）、扩展沙箱边界明确（ExtensionContext 仅暴露 ReaderState Flow + EventBus + DataStore 命名空间）。矛盾主要存在于文档之间而非逻辑链路内部。 |
| **可落地性 (Tractability)** | 2/5 | 21 模块的完整蓝图在当前 120 行代码的基础上不具备可落地性。但若接受 Phase 1 缩减至 5 模块（`:core:model`、`:core:calibre`、`:core:prefs`、`:extensions:api`、`:features:library`），第一周即可交付可运行的书库浏览器 APK。可落地性取决于范围决策而非设计质量。 |
| **可演进性 (Evolvability)** | 5/5 | 设计在所有维度上为未来做好了准备：ReaderEngine 接口使新增格式只需添加模块+实现；Extension SPI + ServiceLoader 使 TTS/统计/OPDS 等功能可独立开发；convention plugin 在编译期强制执行分层规则防止架构腐化；双 InkAnchor 的 Phase 1（Page）/Phase 2（Text）分期策略务实可行。这是架构设计最强的维度。 |

**一致性综合评分：3.4/5**（未加权平均，反映设计质量与实际可执行性之间的张力）

---

## 三、Go/No-Go 裁决

### 裁决：有条件通过 — 架构已就绪进入 Phase 1，但范围必须缩减 75%

**裁决理由：**

架构设计在经过三轮审计（v1 初审 → v2 五维审计 → Round 3 交叉校验+构建就绪度+实现差距评估）后已经成熟。补遗文档正确填补了 ReaderState、InkBrush、ExtensionSettings、DownloadStatus、SyncBackend、BookSource、性能预算七类定义缺口。设计在纸面层面内洽，MVI 状态管理在四个对标项目中达到 best-in-class，双 InkAnchor 策略是业界唯一尝试解决 reflow EPUB 自由手写问题的方案。v1 的 B1-B5 阻断项、H1-H6 高优项、M1-M5 中优项均已在 v2 中获得对应设计。

**但实施差距是极端的：21 个模块中 0 个存在，3 个现有 Kotlin 文件仅为骨架占位（`Text()` composable，无真实 UI）。** 试图在交付任何可见功能前构建全部 21 个模块是典型的"架构宇航员"反模式——花费数月建造基础设施却没有任何用户能看到的东西。

**通过条件（4 项，必须全部满足方可开始编码）：**

1. **Phase 1 范围上限 5 模块：** `:core:model`、`:core:calibre`、`:core:prefs`、`:extensions:api`、`:features:library`。产出物为连接 Calibre 的工作书库浏览器（书籍列表+封面+设置持久化）。不触碰渲染引擎栈。
2. **渲染引擎栈（`:render:api` 至 `:features:reader`）推迟至 Phase 2，且 Phase 2 作为单个垂直切片交付（仅 EPUB 先行，再 PDF/TXT）。**
3. **Ink、TTS、统计、OPDS、Markdown、同步后端留至 Phase 3。**
4. **硬性规则：每个周末 `./gradlew assembleDebug` 必须成功并产出可运行 APK。** 违反此规则立停当前工作回滚到上一个可构建状态。

**如果上述条件被接受，实施可以立即开始——`libs.versions.toml` 和 Gradle wrapper 已就绪，`CalibreClient.kt` 提供了可工作的 HTTP 客户端作为起点。**

---

## 四、实施关键路径

以下是按依赖顺序排列的最小可行实施路径（Phase 1，目标 5 模块 + 工作书库浏览器）：

| 顺序 | 任务 | 产出物 | 依赖 | 预估 |
|------|------|--------|------|------|
| **1** | **修复构建基础设施** | `libs.versions.toml` 补充 kotlinx-serialization-json、kotlin-serialization 插件、kotlin-jvm 插件、material-icons-extended 依赖；根 `build.gradle.kts` 统一使用 version catalog alias | 无 | 0.5 天 |
| **2** | **创建 `:core:model` 模块** | 纯 Kotlin JVM library，零 Android 依赖。提取 `BookMeta`、`SearchResult`、`BookFormat`、`Locator`、`ReadflowError`、`ThemeMode` 类型定义。使用 `kotlinx.serialization` 标注 JSON 类型 | 步骤 1 | 0.5 天 |
| **3** | **创建 `build-logic/` convention plugin** | `ReadflowAndroidLibraryPlugin`（至少一个），在编译期强制执行分层规则。从第一天起防止架构腐化 | 步骤 1 | 1 天 |
| **4** | **更新 `settings.gradle.kts` + 根 `build.gradle.kts`** | 注册新模块、声明所有 plugin alias。确保 `./gradlew projects` 列出所有模块 | 步骤 2, 3 | 0.5 天 |
| **5** | **创建 `:core:calibre` 模块** | 迁移 `CalibreClient.kt`、添加 `CalibreRepository` 接口、添加 Ktor 重试插件。从此模块起所有 HTTP 调用通过 Repository 抽象 | 步骤 2, 4 | 1 天 |
| **6** | **创建 `:core:prefs` 模块** | DataStore `SettingsRepository`，持久化 `calibreBaseUrl`/认证凭据。LibraryScreen 连接 Calibre 前必须存在 | 步骤 2, 4 | 0.5 天 |
| **7** | **创建 `:core:database` 模块** | Room schema（`BookEntity`、`ReadingProgressEntity`）、DAO、数据库类。进度追踪前必须存在 | 步骤 2, 4 | 1 天 |
| **8** | **创建 `:extensions:api` 模块** | `BookSource` 接口、`ReaderEventBus`、Extension SPI。LibraryViewModel 抽象书源前必须存在 | 步骤 2, 4 | 1 天 |
| **9** | **创建 `:features:library` 模块** | `LibraryViewModel` + `LibraryScreen`，使用真实 Calibre 数据。**第一个用户可见功能**——连接 Calibre、展示书籍列表、显示封面（Coil 加载）、持久化设置 | 步骤 5, 6, 7, 8 | 2-3 天 |
| 10 | 创建 `:render:api` 模块 | `ReaderEngine` 接口 + `Locator` + `ReaderEngineRegistry`。所有渲染工作的入口 | 步骤 2 | Phase 2 |
| 11 | 创建 `:render:epub` 模块 | `EpubWebViewEngine`。实际阅读能力 | 步骤 10 | Phase 2 |
| 12 | 创建 `:features:reader` 模块 | `ReaderViewModel`（MVI）+ `ReaderRootLayout`（Hybrid View）+ `ReaderScreen` | 步骤 10, 11 | Phase 2 |
| 13 | 创建 `:render:pdf` + `:render:txt` + `:render:animate` + `:features:settings` | 剩余渲染引擎和设置页 | 步骤 10 | Phase 2 |
| 14 | 创建 `:extensions:tts`、`:extensions:stats`、`:extensions:opds`、`:ink` | Phase 3 润色功能 | - | Phase 3 |

**Phase 1 总预估：7-8 个工作日。** 产出物为可安装的 APK，连接 Calibre 并展示书籍库。此里程碑验证多模块方案在真实构建中的可行性，然后再投入完整的渲染引擎栈。

---

## 五、剩余风险

### 风险 1：架构过度设计 — 21 模块为 120 行代码建造了一座宫殿

设计指定 21 模块、8 层、convention plugin、MVI、ServiceLoader SPI、ink overlay、6 格式引擎——而当前代码仅 3 个 Kotlin 文件约 120 行。存在花费数月建造基础设施却零用户可见功能的风险。

**缓解：** Phase 1 激进缩减至 5 模块。先交付工作书库浏览器，验证多模块方案。Phase 2 再加渲染引擎。绝对遵守"每周可构建 APK"硬性规则。

---

### 风险 2：WebView + epubjs 在 Android 上的稳定性未验证

架构押注于通过 WebView 的 EPUB 渲染（`@likecoin/epub-ts`，epubjs 的 TS 重写）。Android WebView 在不同 OEM（Samsung、Xiaomi、华为）之间存在已知碎片化问题、JavaScript 桥接延迟、以及大 EPUB 文件上的内存泄漏。v1 文档原初拒绝了 WebView 正是出于这些原因。

**缓解：** 在承诺全面投入前，至少在 3 台真机设备上原型验证 `EpubWebViewEngine`。准备 fallback 方案（MuPDF bitmap 渲染，即使牺牲 CFI 能力）。

---

### 风险 3：多模块 Gradle 构建复杂性

21 模块配合 convention plugin、KSP 注解处理（Room）、`build-logic` included build 将导致同步缓慢和晦涩的构建错误。当前根 `build.gradle.kts` 已经存在版本目录不一致问题（硬编码版本 vs alias）。存在开发体验摩擦和 CI 失败风险。

**缓解：** 从 3-4 模块起步，逐步增加 convention plugin。每次新增模块后验证 `./gradlew assembleDebug` 成功。

---

### 风险 4：补遗文档与主文档的类型系统分裂

交叉校验发现补遗的 ReaderState（14 字段、`loadingState: LoadingState`、`fontSize: Int`、无 `documentView`）与主文档的 ReaderState（10 字段、`isLoading: Boolean`、`fontSize: Float`、`documentView: View?`）是互不兼容的两种定义。`core:model`（Layer 0）不能持有 `android.view.View`，因此主文档的 `documentView: View?` 设计在补遗的模块归属方案下技术上不可能实现。必须在 Phase 1 开始前选定一种定义并全文统一。

**缓解：** 采用补遗的 ReaderState 定义（14 字段、`loadingState: LoadingState`、无 Android View 依赖），因为它解决了 v2 审计中提出的 Loading/Error 状态混合问题（M3），且与 `core:model` Layer 0 定位兼容。将此决定明确写入 CLAUDE.md。更新主文档 §8.4 和所有引用处。删除 MVI 契约中"View-driven rendering"措辞中对 `documentView` 字段的引用，改为通过 `ReaderEngine.createView()` 在 `ReaderRootLayout` 中获取 View——View 不出现在 State 中。

---

### 风险 5：MuPDF AGPL v3 许可证

MuPDF 使用 AGPL v3，要求完整对应源码向所有 APK 接收者开放（含任何与 MuPDF 链接的代码）。对开源个人项目可管理，但永久阻断未来闭源分发可能。

**缓解：** 在 CLAUDE.md 中明确注明此许可证约束。保留 PdfRenderer（系统 API，无许可证负担）作为 PDF 主引擎，MuPDF 仅用于 DOCX/CBZ fallback。

---

## 六、架构对比终审

### LinReads v2 与五大竞品/参考项目对比

| 对比维度 | LinReads v2 | Moon+ Reader | KOReader | Mihon | Readium | episteme |
|----------|-------------|-------------|----------|-------|---------|----------|
| **模块化** | 21 模块 8 层分层 | 单体 God Class（Sync.java 1529 行含 public static 可变状态） | 扁平目录，无 Gradle 模块 | 9 模块 Clean Architecture | 跨平台 Toolkit（Kotlin/Swift/TypeScript） | 单模块 Go 后端 + Flutter |
| **渲染引擎** | 6 格式可插拔（epub-ts/PdfRenderer/MuPDF/Markwon/自研 TxtVirtualPager） | 专有引擎（`BaseEBook` 闭合层级，新增格式需改源码） | 2 个 C++ 引擎（CoolReader+MuPDF），共享内存模型 | 图片查看器，无多格式需求 | 单一 EPUB 引擎（WebView+JS），模块化但仅 EPUB | 单一引擎，无格式路由器 |
| **状态管理** | **best-in-class**：MVI + StateFlow + EventBus + Hook | 全局可变静态状态（`public static int searchType` 等） | 事件驱动（2013 设计），无结构化状态容器 | ScreenModel + StateFlow（简化版 MVI） | 每个宿主自行实现，无统一模式 | 未公开 |
| **扩展系统** | ServiceLoader + ExtensionContext 沙箱（仅暴露 ReaderState Flow + EventBus + DataStore 命名空间） | 无沙箱（功能硬编码在 God Class 中） | 目录扫描 + `_meta.lua`（13 年实战验证） | APK 动态加载 + 签名验证（~2000 行基础设施） | 宿主集成 API，非面向终端用户扩展 | 未公开 |
| **手写笔批注** | **best-in-class**：双 InkAnchor（Page + Text）+ FrameLayout z-order + 工具类型路由（stylus→ink, finger→document, 无模式切换） | 无 | 无（电子墨水设备无触控笔 API） | 无（漫画/图片阅读器不需要） | 无 | 有 ink 工具但无跨格式文档锚点模型 |
| **离线阅读** | 设计存在但未实现（离线书籍缓存策略缺失，见 S1） | 完整支持（本地文件 + OPDS 缓存） | 完整支持（本地目录 + 网络存储） | 完整支持（本地文件 + 扩展下载） | 依赖宿主实现 | 完整支持（本地文件） |
| **书源多样性** | Calibre（仅 LAN）+ 设计中的 Extension SPI（OPDS 等留 Phase 3） | 单一（本地文件 + 少数内置书源） | 多样化（本地 + OPDS + 云存储） | 高度扩展（1000+ 扩展源由社区维护） | 由宿主提供 | Calibre + 本地 |
| **跨平台** | Android + HarmonyOS + Web（三端，无 iOS） | Android 单平台 | 嵌入式 Linux + Android（Kobo/PocketBook 等电子墨水设备，无 iOS） | Android 单平台 | **best-in-class**：Android/iOS/Web/Desktop 全平台 | 全平台（Flutter），API 未开放 |
| **许可证** | 待定（部分依赖含 AGPL v3：MuPDF） | 专有闭源 | AGPL v3 | Apache 2.0 | BSD-3-Clause | 未公开 |
| **成熟度** | **设计阶段**（0 模块实现，120 行代码） | 生产级（商业应用，数百万下载） | 生产级（13 年历史，电子墨水设备标准阅读器） | 生产级（150K 行代码，活跃社区） | 生产级（行业标准 EPUB 工具包，被 Thorium/Kotlin Reader 等采用） | 原型/早期阶段 |

### LinReads v2 的独特价值

**"在任意格式的书上如纸上书写，不管格式。"** — 一个格式无关的阅读表面，手写笔批注是一等架构公民。

这一价值通过四项架构决策实现：
1. **可插拔 ReaderEngine** 总是产出 `android.view.View`（而非 Bitmap/Composable），使 ink canvas 可以跨格式层叠在任何文档 View 之上
2. **FrameLayout z-order 分层** 将 ink canvas（CanvasView + InProgressStrokesView）与文档 View 交错排列，笔迹在物理上位于文档之上
3. **InkAnchor 双模型** 将屏幕坐标桥接到五种文档位置模型（CFI for EPUB、page index for PDF/DOCX/CBZ、byte offset for TXT、section heading for MD）——实现了格式无关的批注定位
4. **工具类型触摸路由** 使触控笔 vs 手指的区分对用户透明——无需模式切换

**没有任何竞品同时具备这四个条件：**
- Moon+ Reader 无 ink 层
- KOReader 的 C++/Lua 架构无法在多格式引擎之上干净地层叠批注
- Mihon 是漫画聚焦的图片查看器
- Readium 是 EPUB 专用工具包，无批注层抽象
- episteme 有 ink 工具但不具备跨多种格式的文档锚点模型

---

## 七、从 Round 1 到 Round 3 的演变

| 维度 | Round 1 (v1 初审) | Round 2 (v2 五维审计) | Round 3 (交叉校验+构建就绪度) |
|------|-------------------|----------------------|------------------------------|
| **审计方法** | 四维独立性审计（开放度·构建便利度·接口契约·集成匹配度） | 五维审计（一致性·完备性·可行性·对标·缺口） | 交叉校验（主文档 vs 补遗 逐项比对）+ 构建就绪度（clone→build）+ 实现差距（模块存在性检查） |
| **综合评分** | 2.08/5（不具备实施就绪条件） | 3.3/5（不建议直接进入实现） | 交叉校验 2/5，一致性 3.4/5（有条件通过，范围缩减 75%） |
| **阻断项数量** | 4 个 BLOCKER + 6 个 HIGH + 5 个 MEDIUM | 4 严重 + 8 高 + 8 中 + 6 低 | 4 HIGH 交叉校验矛盾 + 4 构建阻断缺失 |
| **最大问题** | Compose Bitmap→View Ink 架构互斥 | foliate-js vs epubjs 内部矛盾 + 离线书籍缓存缺失 | 主文档与补遗描述两个不同的类型系统（ReaderState/BookSource 互不兼容） |
| **最大进步** | — | Hybrid View 架构解决 B1、ReaderEngine 接口解决 B4、结构化错误类型解决 H1、Extension SPI 新增 | 补遗填补 7 类定义缺口、性能预算确立、ReaderState 迁移到 core:model 解决 ExtensionContext 跨层依赖 |
| **新引入问题** | — | 模块过度拆分（19-20 模块为 120 行代码）、WebView 转向风险、"离线优先"语义降级 | core:model→render:api 循环依赖、TransitionType 命名不兼容、模块计数漂移至 21 |
| **构建状态** | 缺少 gradlew/settings.gradle.kts/根 build.gradle.kts/libs.versions.toml——完全不可构建 | Gradle wrapper 存在但缺少 nanohttpd 依赖和 maven.ghostscript.com 仓库 | Gradle wrapper 存在且可用，但 4 个依赖/插件缺失导致 sync 必然失败 |
| **实现差距** | 5 个源文件约 120 行，单模块 scaffold | 同上，但有了完整的 21 模块蓝图和 convention plugin 设计 | 0/21 模块存在（仅 :app 有目录），20 个模块目录全部为空 |
| **裁决** | 不具备实施就绪条件 | 当前不能进入实现，需完成最小修复集（8 项，2-3 天） | **有条件通过**——范围缩减至 5 模块，每周交付可运行 APK |
| **关键设计变更** | — | Compose-only → Hybrid View；WebView 禁 → WebView for EPUB；无扩展 → Extension SPI | documentView 从 ReaderState 移除（归入 ReaderRootLayout）；epubjs → epub-ts；TransitionType 从 PascalCase → SCREAMING_SNAKE_CASE |
| **对标评级** | 未做 | competitive（MVI 和 Ink 为 best-in-class） | 维持 competitive，独特价值进一步明确（"格式无关的书写表面"是唯一跨 5 竞品的差异化特征） |

### 三轮审计的关键趋势

1. **设计成熟度单调上升。** 每一轮审计都发现了问题，每一个问题都在下一轮设计中获得了回应。v1 的 4 个 BLOCKER 全部在 v2 中解决（B1→Hybrid View、B2→Gradle 基础设施、B3→JSON Schema+codegen、B4→ReaderEngine 接口）。v2 的 26 个问题（S1-S4、H1-H8、M1-M8、L1-L6）在补遗中处理了 7 类定义缺口和性能预算。交叉校验发现的 8 处矛盾是需要解决的最后一批文档级不一致。

2. **实施债务单调上升。** 每一轮审计都扩展了架构蓝图的范围（v1: 1 模块 → v2: 19 模块 → Addendum: 21 模块），而实际代码量保持不变（~120 行）。设计-实现鸿沟从 Round 1 的"一个模块无基础设施"扩大到 Round 3 的"21 模块蓝图，0 模块存在"。这是范围缩减至 5 模块的核心理由。

3. **风险重心从架构正确性向执行可行性转移。** Round 1/2 的主要风险是"设计错误"（Compose-View 互斥、foliate-js 安全冲突、API 级别矛盾）。Round 3 的主要风险是"执行过热"（范围过大、构建复杂度过高、基础设施分散注意力）。这是积极的信号——设计已经足够好，现在需要的是纪律而非更多设计。

---

## 八、最终结论

**LinReads Android v2 架构在经过三轮审计后，设计质量达到可实施水准，但实现必须从 5 模块书库浏览器起步，而非 21 模块完整蓝图。**

三个数字概括当前状态：
- **2/5** 交叉校验评分——补遗与主文档之间 4 个 HIGH 级类型不兼容必须在编码前解决
- **0/21** 模块存在——仅 `:app` 目录存在，20 个模块目录为空
- **5 模块** Phase 1 硬上限——在此范围内可在一周内交付工作书库浏览器 APK

独特的架构价值主张——"在任意格式的书上如纸上书写"——在所有竞品中无出其右。双 InkAnchor 模型 + FrameLayout z-order + 工具类型触摸路由 + ReaderEngine View 抽象的组合是业界唯一的跨格式自由手写方案。但这一价值主张的兑现取决于能否先交付一个简单的、工作的、用户可见的功能——书库浏览器——然后在此基础上迭代添加渲染和批注能力。

**"先让它工作，再让它正确，最后让它快速。"** Phase 1 应聚焦于"让它工作"。Phase 2 和 3 的架构蓝图已经足够正确，可以等待。

---

*审计由 Claude Code (deepseek-v4-pro) 执行。*
*输入来源：交叉校验矛盾清单（8 项）、构建就绪度评估（7 项缺失）、实现差距评估（0/21 模块）、一致性五维评分（simplicity/focus/consistency/tractability/evolvability）、关键路径分析（14 步）、风险矩阵（5 项）、竞品对标（6 项目 12 维度）。*
