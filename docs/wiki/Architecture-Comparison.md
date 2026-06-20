# 成熟阅读器架构对比审计

> 审计日期：2026-06-18
> 对比对象：KOReader（C++/Lua）· Mihon/Komikku（Kotlin/Compose）· Readium（Swift/Kotlin 工具链）
> 目标：逐维度拆解三个成熟项目的架构决策，对标 LinReads 当前设计，提取可直接采纳的模式

---

## 一、项目概况

| 维度 | KOReader | Mihon/Komikku | Readium | LinReads (设计) |
|------|---------|---------------|---------|----------------|
| 语言 | C++ (base) + Lua (frontend) | Kotlin + Compose | Swift / Kotlin + Web | Kotlin + Compose |
| 平台 | Kindle/Kobo/PB/Android/Linux | Android only | iOS / Android / Web | Android + HarmonyOS + Web |
| 焦点 | 多格式电子书阅读 | 在线漫画/webtoon | EPUB 标准工具链 SDK | 自托管 Calibre 书库 |
| 年龄 | 13年 (2013–) | 5年 (Tachiyomi→Mihon) | 9年 (2017–) | <1月 (设计阶段) |
| 架构哲学 | 厚 C 核心 + 薄 Lua UI | Clean Architecture + 插件化 | 三层标准件 (Server/Streamer/Navigator) | 格式独立引擎 + MVI |
| 代码规模 | ~40万行 Lua + ~30万行 C/C++ | ~15万行 Kotlin (app + 9模块) | SDK (多仓库) | 3文件 ~120行 (实现) |

---

## 二、架构模式对比

### 2.1 KOReader：双语言分层 + 事件总线

```
┌────────────────────────────────────────┐
│  Lua Frontend (UI / 插件 / 文档接口)     │
│  frontend/  ├── ui/widget/  (Widget 基类) │
│             ├── apps/reader/ (ReaderUI)   │
│             ├── document/   (Document 抽象)│
│             └── plugins/    (.koplugin)   │
├────────────────────────────────────────┤
│  C/C++ Backend (渲染引擎 / 平台抽象)     │
│  koreader-base/                         │
│  ├── crengine (EPUB/FB2/HTML 渲染)      │
│  ├── MuPDF   (PDF/CBZ 渲染)             │
│  ├── DjVuLibre (DJVU)                   │
│  └── libk2pdfopt (PDF 重排)             │
├────────────────────────────────────────┤
│  FFI (LuaJIT C FFI)                     │
└────────────────────────────────────────┘
```

**关键模式**：
- **Event Dispatcher**：`UIManager:sendEvent()` 和 `broadcastEvent()` 解耦组件通信
- **Widget Stack**：`_window_stack` 管理 Widget 生命周期（Z-order, dirty, modal）
- **DocumentRegistry**：注册表模式映射扩展名→Provider，支持 weight 竞争 + 用户 override
- **Reference Counting**：Document 实例通过引用计数共享（多个组件打开同一个文档不重复加载）
- **PluginLoader**：扫描 `*.koplugin` 目录 → 解析 `_meta.lua` → 惰性加载 `main.lua`
- **HandlerSandbox**：插件事件处理函数被 wrap 以提供更好的错误堆栈追踪

**对 LinReads 启示**：
1. DocumentRegistry 的 weight-based provider selection + 用户 override 是最成熟的格式注册模式
2. 共享 Document 实例通过引用计数——避免多组件重复打开同一文件
3. 事件总线解耦——ReaderUI 的模块只需注册事件监听，无需知道彼此存在

### 2.2 Mihon/Komikku：Clean Architecture + 编译时模块化

```
┌──────────────────────────────────────────┐
│  app/ (Application, Activity, DI 组装)    │
├──────────────────────────────────────────┤
│  presentation-core/  (共享 Compose 组件)  │
│  presentation-widget/ (桌面小部件)        │
├──────────────────────────────────────────┤
│  domain/  (Interactor/UseCase, 零 Android 依赖) │
├──────────────────────────────────────────┤
│  data/    (Repository 实现 + SQLDelight)  │
├──────────────────────────────────────────┤
│  core/common, core/archive, core-metadata │
├──────────────────────────────────────────┤
│  source-api/  (Source 接口定义)           │
│  source-local/ (本地文件源实现)           │
├──────────────────────────────────────────┤
│  i18n/  (国际化资源)                      │
│  telemetry/  (可选分析)                   │
└──────────────────────────────────────────┘
```

**关键模式**：
- **ScreenModel (Voyager)**：替代 Android ViewModel，绑定到 Voyager Screen 生命周期
- **Injekt DI**：轻量反射-free DI，`DomainModule` 注册所有 singleton/factory
- **Source Plugin**：外部 APK 通过 `ExtensionLoader` 反射加载 → `SourceManager` 注册 → `Source` interface
- **ChapterLoader → PageLoader 两级管道**：ChapterLoader 决定用哪个 PageLoader（Download/Http）；PageLoader 返回页面图片
- **Hybrid UI**：ReaderActivity 主体是传统 View（`reader_activity.xml`），Compose 用于 overlay（设置面板、工具栏）
- **Interactor 模式**：Domain 层所有业务逻辑是 Interactor (`SyncChaptersWithSource`, `UpdateManga`)——完全不依赖 Android

**对 LinReads 启示**：
1. Interactor 模式——将 Reader 业务逻辑（翻页、进度计算、预取）抽为 domain 层纯 Kotlin 类，可独立测试
2. Hybrid View + Compose——阅读器核心渲染用传统 View（性能），设置面板用 Compose（开发效率）
3. ExtensionLoader 反射加载——LinReads 的 ReaderEngine 注册也可以用类似思路（读取 classpath 上的实现类）
4. ScreenModel 而非 ViewModel——更干净的 Compose 生命周期绑定

### 2.3 Readium：三层标准件

```
┌──────────────────────────────────────────────┐
│  Publication Server                           │
│  暴露 Web Publication Manifest + 资源 (HTTPS) │
├──────────────────────────────────────────────┤
│  Streamer                                     │
│  Parser (解析 EPUB/PDF/CBZ) + Fetcher (资源访问) │
│  产物：in-memory Publication (Manifest + Fetcher) │
├──────────────────────────────────────────────┤
│  Navigator                                    │
│  显示资源 / 导航 / 注入 CSS-JS / 定位 (Locator) │
│  支持 WebView / iframe / native 多种渲染路径   │
└──────────────────────────────────────────────┘
```

**关键模式**：
- **Locator System**：统一的定位模型（跨 EPUB/PDF/Web），支持 CFI、position、page、fragment 多种定位类型
- **Fetcher 抽象**：所有资源访问通过 `Fetcher` 接口（可叠加 decode/decrypt/transform 层）
- **WebView 优先**：HTML/XHTML 资源统一走 WebView，对 EPUB 的 CSS/JS 支持最完整
- **CSS/JS 注入**：Navigator 负责向 WebView 注入 pagination、locator、media-overlay 脚本

**对 LinReads 启示**：
1. **Locator 模型值得采用**——统一 `{href, type, locations, text}` 的进度定位，替代简单 byteOffset
2. **WebView 不是敌人**——Readium 明确推荐 WebView 渲染 HTML 资源，LinReads 的"零 WebView"原则可能对 EPUB 过于激进
3. **Fetcher 管道**——资源访问可叠加 transform（decrypt→decode→resize→cache），LinReads 的封面图片加载可以借鉴

---

## 三、核心维度逐项拆解

### 3.1 格式注册与渲染引擎

| 项目 | 机制 | 扩展性 |
|------|------|--------|
| **KOReader** | `DocumentRegistry` 注册表：Provider 按扩展名+MIME注册，weight 竞争优先级，用户可 per-file/per-type override | ✅ 新格式只需写一个 Provider + 注册 |
| **Mihon** | `Source` interface + `ExtensionLoader` 反射加载 APK。`SourceManager` 管理生命周期 | ✅ APK 格式插件，签名验证 |
| **Readium** | `Streamer` 的 `Parser` 解析出版物 → `Navigator` 根据 content type 选渲染路径 | ✅ Parser 插件，Navigator 多渲染路径 |
| **LinReads** | `ReaderEngine` interface → 编译时 `BookFormat` enum 路由 | ⚠️ 新增格式需改 enum |

**LinReads 差距**：
- KOReader 的 weight 竞争 + 用户 override 三层选择算法值得直接搬运
- Mihon 的 APK 动态加载对 LinReads 过度（不需要热更新格式支持），但 ServiceLoader/SPI 发现编译时已知的实现类是低成本的改进
- Readium 的 content-type 路由（HTML→WebView, Image→native, Audio→native）比 LinReads 的格式→引擎一一映射更灵活

**建议**：
```kotlin
// 改进后的注册方式
interface ReaderEngine {
    val format: BookFormat
    val priority: Int  // 新增：同类格式多个引擎竞争
    fun supports(uri: Uri, mimeType: String?): Boolean  // 新增：更细粒度的匹配
    // ...
}

// 通过 ServiceLoader 或 Koin multi-bind 自动发现
// 无需手动维护 when(format) 分支
```

### 3.2 阅读器状态管理

| 项目 | 模式 | 关键数据结构 |
|------|------|------------|
| **KOReader** | event-driven (事件驱动)。`ReaderUI` 管理模块列表，模块通过 `onReaderReady`, `onPageUpdate` 等事件钩子响应 | `self.ui` 持有所有模块引用 |
| **Mihon** | `ReaderViewModel` + `StateFlow<ReaderState>`。阅读模式、页面列表、加载状态全在 State 里 | `ReaderState(manga, chapter, pages, viewer, ...)` |
| **Readium** | `Publication` in-memory model + `Locator` 定位。状态分散在各组件 | `Publication` + `Locator` |
| **LinReads** | MVI（设计）：`sealed ReaderIntent → ReaderState` | `ReaderState(book, current, prefetched, position, ...)` |

**LinReads 的 MVI 设计比 KOReader 的事件驱动更现代**，但与 Mihon 的 ReaderViewModel 相比有差距：

| Mihon ReaderViewModel | LinReads ReaderState |
|----------------------|---------------------|
| 管理完整的 chapter 切换流程 | 只管单个 reader session |
| `ChapterLoader` 自动判断本地/远端 | 无 loader 抽象 |
| 与 DownloadManager/Tracker 集成 | 无集成点设计 |
| `ReadingMode` 枚举（Pager/Webtoon等） | 只有 `TransitionType` 枚举 |

**建议**：
1. ReaderState 应包含 `loadState: LoadingState`（Loading/Loaded/Error）——当前设计缺失
2. 增加 `ChapterLoader` 概念——读 EPUB 章节 vs 读 PDF 页码 vs 读 TXT byteOffset，统一为此接口
3. Mihon 的 `ReaderViewModel` 持有 `DownloadManager` 引用以判断本地/远端——LinReads 的 Calibre 模式全是远端 HTTP，但 CalibreRepository 应注入 ViewModel

### 3.3 插件系统

| 项目 | 实现 | 粒度 |
|------|------|------|
| **KOReader** | 目录扫描 `.koplugin` → `_meta.lua` → 惰性加载 `main.lua`。插件通过 `addToMainMenu` + `onDispatcherRegisterActions` 集成 | 功能级（菜单、手势、同步） |
| **Mihon** | APK 签名验证 → `ExtensionLoader` 反射 → `SourceManager` 注册。Shared/Private 双路径选择 | 内容源级（在线书源） |
| **Readium** | 无插件系统——通过 Streamer/Navigator 接口扩展 | SDK 级 |
| **LinReads** | 无插件系统设计 | 不适用（个人项目） |

**判断**：LinReads 作为个人项目不需要 Mihon 级别的 APK 动态加载。但 KOReader 的轻量插件模式（目录扫描 + 惰性加载）值得借鉴——用于**可选功能的按需加载**：
- `plugins/LinReads-tts.koplugin` → TTS 朗读
- `plugins/LinReads-stats.koplugin` → 阅读统计
- `plugins/LinReads-opds.koplugin` → OPDS 书源

这在 Android 上可以简化实现：只需在 `plugins/` 目录下放 Kotlin 编译产物（非 APK），通过 ServiceLoader 发现。

### 3.4 数据持久化

| 项目 | 存储 | Schema 管理 | 响应式 |
|------|------|-----------|--------|
| **KOReader** | 每书 `.sdr/` sidecar 目录（Lua tables 序列化）| 文件系统，无 schema | ❌ |
| **Mihon** | SQLDelight（编译时 SQL 校验）| 迁移文件 + `MigrationStrategy` | ✅ `Flow<T>` |
| **Readium** | 不规定（SDK 不包含存储层） | — | — |
| **LinReads** | Room (设计) | Room Migration | ✅ `Flow<T>` |

**LinReads 对比**：
- Room 比 KOReader 的 sidecar 文件更适合结构化查询（搜索/筛选/统计）
- SQLDelight 比 Room 多一个优势：KMP 兼容（如果 LinReads 未来要共享数据层到 HarmonyOS Kotlin MP）
- KOReader 的 sidecar 模式最大优点是**书和注记物理绑定**——复制一本书时自动带走过往所有注记。LinReads 应考虑类似的"导出"功能

**建议**：
- 维持 Room（成熟、KSP、Compose 集成好）
- 增加 per-book 导出功能（`reading_progress + annotations` → JSON/ZIP，可随书迁移）

### 3.5 进度存储与定位

| 项目 | 定位方式 | 跨格式统一性 |
|------|---------|------------|
| **KOReader** | `xpointer` (crengine) / page number (MuPDF) / page label (PDF) | ❌ 引擎差异 |
| **Mihon** | `(chapterIndex, pageIndex)` 简单整数 | ✅ 所有源统一 |
| **Readium** | `Locator { href, type, locations: {CFI, position, progression} }` | ✅ 统一模型 |
| **LinReads** | `(chapterIndex, splitIndex, position)` 三元组 + byteOffset | ⚠️ EPUB 和 TXT 不统一 |

**Readium Locator 是最成熟的标准**：
```json
{
  "href": "/chapter1.xhtml",
  "type": "application/xhtml+xml",
  "title": "Chapter 1",
  "locations": {
    "cfi": "epubcfi(/6/4[chap01ref]!/4[body01]/10/2/1:0)",
    "position": 42,
    "progression": 0.35
  }
}
```

**建议**：LinReads 应采用 Readium 的 Locator 作为统一进度模型——`cfi` 给 EPUB，`page` 给 PDF，`position` 给 TXT，`progression` 作为通用 fallback。比当前的三元组设计更灵活。

### 3.6 渲染路径选择

| 项目 | HTML 内容 | 图片内容 | 文本内容 | 策略 |
|------|---------|---------|---------|------|
| **KOReader** | crengine (C++ 排版) | MuPDF (bitmap) | crengine | C 引擎 → Lua 包装 |
| **Mihon** | N/A (无 HTML 书) | Coil + ImageView | N/A | 图片解码 → View |
| **Readium** | **WebView (推荐)** | Native Image | Native Audio | content-type 路由 |
| **LinReads** | MuPDF bitmap (设计) | MuPDF bitmap | TxtVirtualPager | 格式→引擎 |

**关键洞察**：

Readium 的明确建议——**HTML 内容走 WebView**——值得 LinReads 重新考虑"零 WebView"的立场：

| 维度 | MuPDF bitmap 渲染 EPUB | WebView 渲染 EPUB |
|------|----------------------|-------------------|
| CSS 支持 | ❌ 仅能注入简单 CSS | ✅ 完整浏览器 CSS 引擎 |
| 字体渲染 | ⚠️ 取决于 MuPDF 字体配置 | ✅ 系统字体栈 + @font-face |
| 文字选择 | ❌ 无（需要坐标反查） | ✅ 浏览器原生 Selection API |
| 链接/脚注 | ❌ 需要自解析 | ✅ 浏览器原生 |
| 性能 | ✅ GPU bitmap 快 | ⚠️ WebView 初始化开销 |
| 包体积 | +15MB (libmupdf.so) | 0 (系统自带) |

**建议**：EPUB 格式**建议优先考虑 WebView 路径**（Readium Navigator 模式），MuPDF 保留给 PDF/CBZ/DOCX。这与 LinReads Web 端（epubjs via WebView）保持一致。

### 3.7 动画与翻页

| 项目 | 实现 | 跟手 |
|------|------|------|
| **KOReader** | crengine 内置分页 + Lua UI 翻页手势 | ✅ 完整手势系统 |
| **Mihon** | `ViewPager2` + `RecyclerView`（系统组件翻页） | ✅ ViewPager2 原生支持 |
| **Readium** | Navigator 层处理，平台原生翻页 API | ✅ 平台原生 |
| **LinReads** | 自研 AnimationEngine + CurlTransition | ❌ Phase 1 无 |

**LinReads 的 AnimationEngine 设计被高估了**：

1. Mihon 对漫画（图片为主的翻页）直接用系统 `ViewPager2`——零自研代码，零 bug
2. KOReader 的翻页是 crengine C++ 内部分页逻辑，不是独立的动画引擎
3. LinReads 的 CurlTransition（贝塞尔卷页）需要真实纸张弯曲物理——120 行代码不太现实

**建议**：
- 对于简单滑动翻页：直接用 Compose `HorizontalPager`（系统组件，支持跟手）
- 卷页效果：降低 Phase 1 优先级——这个功能需要大量调优才能到可接受的质量
- 动画引擎设计可保留，但应先实现系统组件方案，再用自研逐步替换

---

## 四、LinReads 可立即采纳的模式

### 🔴 P0（开始编码前必须决定）

**1. 渲染路径：WebView 还是 MuPDF bitmap？**

基于 Readium 标准 + KOReader 实践，建议分路径：

| 格式 | 渲染方式 | 理由 |
|------|---------|------|
| EPUB | **WebView**（注入 pagination JS） | CSS 完整支持、文字选择原生、零包体积 |
| PDF | **PdfRenderer**（系统 API） | 零依赖、零许可费 |
| DOCX | **MuPDF JNI** | MuPDF 的 DOCX 支持成熟 |
| CBZ/CBR | **MuPDF JNI** | 图片集，bitmap 渲染最佳 |
| TXT | **TxtVirtualPager** + LazyColumn | 自研轻量方案合理 |
| MD | **Markwon → Compose** | 原生 Spannable 渲染 |

**2. 进度模型：采用 Readium Locator**

```kotlin
data class Locator(
    val href: String,          // 资源标识（章节/页码）
    val type: String,          // MIME type
    val title: String?,
    val locations: Locations
)

data class Locations(
    val cfi: String? = null,         // EPUB CFI
    val position: Int? = null,       // 通用位置
    val progression: Float? = null,  // 0.0-1.0 进度百分比
    val page: Int? = null,          // PDF 页码
    val byteOffset: Long? = null,    // TXT 字节偏移
)
```

### 🟡 P1（早期实现中优先）

**3. Interactor 模式（从 Mihon 借鉴）**

```kotlin
// domain/reader/interactor/TurnPage.kt
class TurnPage(
    private val engine: ReaderEngine,
    private val progressRepo: ReadingProgressRepository,
) {
    suspend operator fun invoke(
        direction: Direction,
        currentState: ReaderState,
    ): ReaderState {
        val newPage = engine.renderPage(currentState.position + direction.offset, ...)
        progressRepo.save(Locator.from(newPage))
        return currentState.copy(current = newPage, position = newPage.position)
    }
}
```

所有 Reader 业务逻辑（翻页、预取、进度保存、书签操作、字体调整）抽为 Interactor，零 Android 依赖，可独立单元测试。

**4. 格式注册表（从 KOReader 借鉴）**

```kotlin
// Koin multi-bind 自动发现所有 ReaderEngine 实现
@Module
val renderModule = module {
    // 所有 ReaderEngine 实现类自动注册
    single<ReaderEngine>(named("epub")) { EpubEngine() }
    single<ReaderEngine>(named("pdf")) { PdfEngine() }
    single<ReaderEngine>(named("txt")) { TxtEngine() }
    // ...
}

class ReaderEngineRegistry(
    private val engines: Map<String, ReaderEngine>, // Koin 注入
) {
    fun resolve(format: BookFormat, uri: Uri, mimeType: String?): ReaderEngine? {
        return engines.values
            .filter { it.supports(uri, mimeType) }
            .maxByOrNull { it.priority }
    }
}
```

**5. 双向预取（修正当前设计的单向预取缺陷）**

当前设计只预取 `index + 1`。修正为：

```kotlin
class PagePrefetcher(
    private val engine: ReaderEngine,
    private val scope: CoroutineScope,
) {
    private val cache = LruCache<Int, PageContent>(maxSize = 5)

    fun onPageChanged(newIndex: Int) {
        // 预取前后各 2 页
        listOf(-2, -1, 1, 2)
            .map { newIndex + it }
            .filter { it !in cache }
            .forEach { scope.launch { cache[it] = engine.renderPage(it, ...) } }
    }
}
```

### 🟢 P2（功能完整阶段）

**6. 轻量插件目录（从 KOReader 借鉴）**

用于可选功能（TTS、阅读统计、OPDS 书源），简化实现：
- `plugins/` 目录存放 Kotlin 编译产物
- `_meta.json` 描述插件元数据
- ServiceLoader 发现 + `PluginLoader` 惰性加载

**7. per-book sidecar 导出（从 KOReader 借鉴）**

阅读进度 + 标注 + 手写注记 → JSON/ZIP 导出，与书文件物理绑定。

---

## 五、总体评分

| 维度 | KOReader | Mihon/Komikku | Readium | LinReads (设计) |
|------|---------|---------------|---------|----------------|
| 架构模式 | 🟡 事件驱动（老化） | ✅ Clean Architecture | ✅ 标准三层 | ✅ MVI (现代) |
| 渲染引擎 | ✅ C++ 双引擎（crengine+MuPDF） | 🟢 图片渲染（简单场景） | ✅ WebView 优先 | 🟡 MuPDF bitmap（取舍待定） |
| 格式扩展 | ✅ DocumentRegistry | ✅ Source/ExtensionLoader | ✅ Parser 插件 | ⚠️ 编译时 enum |
| 进度定位 | 🟡 引擎差异 | 🟢 整数组 | ✅ Locator | ⚠️ 三元组（未统一） |
| 插件系统 | ✅ .koplugin 扫描 | ✅ APK + 签名验证 | ❌ 无（SDK 定位） | ❌ 无 |
| 动画翻页 | 🟢 crengine 内置 | ✅ ViewPager2 | ✅ 平台原生 | 🟡 自研（过度设计风险） |
| 数据存储 | 🟡 sidecar 文件 | ✅ SQLDelight + Flow | ❌ 无 | ✅ Room + Flow |
| 模块化 | ❌ 单仓库无 Gradle | ✅ 9+ Gradle 模块 | ✅ 多仓库 | ❌ 单模块 |
| 可测试性 | 🟡 Lua Busted | ✅ 分层可测 | ✅ 接口化 | ✅ 设计就绪 |
| 跨平台 | ✅ 6 平台 | ❌ Android only | ✅ iOS/Android/Web | ✅ 三端 |
| 实现度 | ✅ 13年生产 | ✅ 5年生产 | ✅ SDK 生产 | ❌ 骨架 |

### 核心结论

1. **LinReads 的架构设计在"纸面上"属于现代主流**，MVI + Room + ReaderEngine interface 的组合不落后于三个成熟项目
2. **但三个关键设计决策需要重新审视**：
   - EPUB 渲染路径：MuPDF bitmap vs WebView——建议改走 WebView
   - 进度模型：三元组 vs Readium Locator——建议采用 Locator
   - 动画引擎：自研 vs 系统组件——建议先用 HorizontalPager，再逐步替换
3. **三个可以"免费搬运"的模式**：
   - KOReader 的 DocumentRegistry（weight 竞争 + 用户 override）
   - Mihon 的 Interactor 模式（纯 Kotlin domain 层）
   - Readium 的 Locator 模型（跨格式统一定位）
4. **最大短板不是设计而是实现**：三个对比项目都有 5-13 年的生产级代码，LinReads 只有 3 个 stub 文件。

---

_参考：_
- [KOReader Architecture](https://deepwiki.com/koreader/koreader/1.1-architecture-overview) + [Document Registry](https://deepwiki.com/koreader/koreader/5.1-document-registry-and-providers) + [Plugin Architecture](https://deepwiki.com/koreader/koreader/9.1-plugin-architecture)
- [Mihon Core Architecture](https://deepwiki.com/mihonapp/mihon/3-core-architecture) + [Reader System](https://deepwiki.com/mihonapp/mihon/4-manga-reader-system) + [Extension System](https://deepwiki.com/mihonapp/mihon/7-extension-system)
- [Readium Navigator Architecture](https://readium.org/technical/r2-navigator-architecture/) + [Core Components](https://deepwiki.com/readium/architecture/2-core-architecture-components)
- [Android Architecture Audit](Android-Architecture-Audit.md) — 前一次审计
- [Architecture Verification](Architecture-Verification.md) — 静读天下验证
