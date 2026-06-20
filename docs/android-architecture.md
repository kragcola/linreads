# Android 端架构设计

> 版本：2026-06-17  
> 原则：全原生（零 WebView）· 性能优先 · 开放可扩展 · 开发期不臃肿

---

## 分层总览

```
┌──────────────────────── UI Layer ───────────────────────────┐
│  Compose + Navigation 2.8+（type-safe sealed routes）        │
│  Library / Settings  →  MVVM（StateFlow）                   │
│  ReaderScreen        →  MVI（Intent → State，单向数据流）    │
├──────────────────────── Render Layer ───────────────────────┤
│  ReaderEngine（interface）                                   │
│  ┌────────────────────┐  ┌──────────────┐  ┌────────────┐  │
│  │  MuPdfEngine       │  │  TxtEngine   │  │  MdEngine  │  │
│  │  EPUB · PDF · DOCX │  │  TxtVirtual  │  │  Markwon   │  │
│  │  → Bitmap/page     │  │  Pager+Pg3   │  │  Spannable │  │
│  └────────────────────┘  └──────────────┘  └────────────┘  │
│  AnimationEngine（自研，vsync-driven，bitmap-in/out）        │
│  InkOverlay（Phase 2，androidx.ink 1.0.0）                   │
├──────────────────────── Data Layer ─────────────────────────┤
│  CalibreRepository（Ktor）  BookshelfRepository（Room）      │
│  SettingsRepository（DataStore Preferences）                 │
└─────────────────────────────────────────────────────────────┘
         ↕ Koin DI（模块注入，无注解处理器）
```

---

## 包结构

单模块，包边界清晰，随时可拆为 Gradle 子模块。

```
dev.readflow/
├── ui/
│   ├── nav/          路由定义（sealed class Route）
│   ├── library/      LibraryScreen + LibraryViewModel
│   ├── reader/       ReaderScreen + ReaderViewModel（MVI）
│   └── settings/     SettingsScreen + SettingsViewModel
├── data/
│   ├── calibre/      CalibreClient（已有）+ CalibreRepository
│   ├── local/        Room DB：Book, ReadingProgress, Annotation
│   └── prefs/        DataStore SettingsRepository（baseUrl/主题/字体）
├── render/
│   ├── api/          ReaderEngine, PageContent, ReaderPosition（接口层）
│   ├── document/     MuPdfEngine（JNI → libmupdf.so）
│   │                 ↳ 处理 EPUB · PDF · DOCX
│   ├── txt/          TxtEngine（TxtVirtualPager + Paging3）
│   └── markdown/     MdEngine（Markwon → Compose AndroidView 包装）
├── animate/          自研动画引擎
│   ├── AnimationEngine.kt
│   ├── PageTransition.kt（interface + TransitionType enum）
│   └── transitions/
│       ├── SlideTransition.kt
│       ├── CurlTransition.kt（贝塞尔卷页物理）
│       └── FadeTransition.kt
├── ink/              Phase 2
│   └── InkOverlay.kt（androidx.ink FrameLayout 覆盖层）
└── di/
    └── AppModule.kt（Koin 模块定义）
```

---

## 渲染引擎选型

### 统一接口

```kotlin
interface ReaderEngine {
    val format: BookFormat
    val pageCount: Int
    suspend fun open(uri: Uri)
    suspend fun renderPage(index: Int, widthPx: Int, heightPx: Int): Bitmap
    fun positionOf(index: Int): ReaderPosition
    fun close()
}
```

MuPdfEngine 实现此接口，每次 `renderPage()` 返回一张 Bitmap。
TxtEngine / MdEngine 不使用此接口（滚动模式，见下）。

### 各格式策略

| 格式 | 引擎 | 渲染模式 | 大文件策略 |
|------|------|---------|------------|
| EPUB | **MuPDF**（JNI） | 分页 Bitmap | 流式加载，MuPDF 内部管理内存 |
| PDF  | **PdfRenderer**（系统 API）| 分页 Bitmap | 逐页光栅化，LRU 缓存 3 页 |
| DOCX | **MuPDF**（JNI） | 分页 Bitmap | 流式加载 |
| TXT  | **TxtVirtualPager**（自研） | 滚动/分页 | byteOffset 分页，Paging3 |
| MD   | **Markwon** | 滚动 | 按标题分 section 懒加载 |
| DOC  | DocumentReader（文本提取） | 滚动 | 文本流，无内存问题 |

注：PDF 使用系统 PdfRenderer（零依赖、API21+），不使用 MuPDF 处理 PDF。MuPDF 仅用于 EPUB + DOCX + CBZ。

#### MuPDF 选型理由

- **AGPL**，个人项目免费；支持 EPUB · PDF · XPS · CBZ · DOCX
- `com.artifex.mupdf:fitz`（Maven Central）低级 JNI 绑定，细粒度控制
- 每页输出 Bitmap → 与 AnimationEngine 零摩擦对接
- KOReader 同款引擎，在 Android e-ink 设备上验证多年
- EPUB font-size：通过 `fitz.Document.setMetadata("style", "body{font-size:18pt}")` 注入 CSS 变量（Phase 1 局限；Phase 2 升级为自研反排引擎）

#### PDF 保持系统 PdfRenderer

MuPDF 也能处理 PDF，但 Android 系统 `PdfRenderer` 零依赖，API35+ 升级 PDFium 内核支持文字选择。保持轻量，PDF 走系统原生。

#### TXT：TxtVirtualPager（自研 ~300 行）

```
FileChannel（RandomAccess）
    ↓ 后台协程，64 KB 块扫描
TxtPager —— 定位页边界
    规则：空行≥2 / 章节正则 / 字符阈值 2000
    ↓
List<PagePointer>{ byteOffset, length }
    ↓ Paging3 PagingSource（byteOffset 作 Key）
LazyColumn + items(pagingItems)
```

#### MD：Markwon

- 原生 Spannables，无中间 HTML，无 WebView
- 包装为 `AndroidView { TextView }` 嵌入 Compose
- 按一级标题 `# ` 分节，节间懒加载

---

## 自研动画引擎

### 设计原则

- **vsync 对齐**：`withFrameNanos`（Choreographer tick），非轮询
- **bitmap in/out**：从 / 到页面各一张 Bitmap，与渲染引擎解耦
- **GPU 加速**：Compose `graphicsLayer` 走 RenderThread，无需 SurfaceView
- **可插拔 Transition**：interface + enum，运行时切换

### 核心代码

```kotlin
// render/api/PageContent.kt
data class PageContent(val bitmap: Bitmap, val position: ReaderPosition)

// animate/PageTransition.kt
interface PageTransition {
    fun render(canvas: Canvas, from: Bitmap, to: Bitmap, progress: Float)
}

// animate/AnimationEngine.kt
class AnimationEngine(
    private val interpolator: TimeInterpolator = DecelerateInterpolator(1.5f)
) {
    suspend fun run(
        from: Bitmap, to: Bitmap,
        transition: PageTransition,
        durationMs: Long = 300L,
        onFrame: (Bitmap) -> Unit,
    ) {
        val out = Bitmap.createBitmap(from.width, from.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val startNs = withFrameNanos { it }
        var done = false
        while (!done) {
            withFrameNanos { now ->
                val raw = (now - startNs) / (durationMs * 1_000_000f)
                val p = interpolator.getInterpolation(raw.coerceIn(0f, 1f))
                transition.render(canvas, from, to, p)
                onFrame(out)
                if (raw >= 1f) done = true
            }
        }
        out.recycle()
    }
}
```

Compose 侧：`var frame by remember { mutableStateOf(initialBitmap) }`，`LaunchedEffect(intent) { engine.run(...) { frame = it } }`，`Image(frame.asImageBitmap())`。

### CurlTransition（贝塞尔卷页）

卷页不用 `DecelerateInterpolator` 糊弄，需要真实纸张弯曲物理：

```
卷折点 (cx, cy) 沿屏幕右边缘 → 左侧移动
翻转区域：三次贝塞尔曲面（控制点由 cx 推算）
阴影：曲线法线方向线性衰减
背面：to.bitmap 镜像 matrix
```

完整数学实现在 `animate/transitions/CurlTransition.kt`（约 120 行）。

---

## MVI Reader State

```kotlin
// ui/reader/ReaderContract.kt

sealed interface ReaderIntent {
    data class Open(val bookId: Int, val format: BookFormat) : ReaderIntent
    object NextPage : ReaderIntent
    object PreviousPage : ReaderIntent
    data class GoTo(val position: ReaderPosition) : ReaderIntent
    data class SetTransition(val type: TransitionType) : ReaderIntent
    data class SetFontSize(val sp: Int) : ReaderIntent   // EPUB CSS 注入
    object ToggleInk : ReaderIntent
}

data class ReaderState(
    val book: Book? = null,
    val current: PageContent? = null,
    val prefetched: PageContent? = null,  // 当前页就绪后立即预取 +1 页
    val position: ReaderPosition = ReaderPosition.Start,
    val transition: TransitionType = TransitionType.Slide,
    val fontSize: Int = 18,
    val isInkMode: Boolean = false,
    val error: String? = null,
)
```

预取策略：`current` 就绪后，后台协程立即渲染 `index + 1` 存入 `prefetched`。翻页动画启动时两张 Bitmap 已备好，动画无需等待渲染。

---

## 依赖变更

```kotlin
// 移除
// "nl.siegmann.epublib:epublib-core:4.1"  ← 用 MuPDF 替代

// 渲染
"com.artifex.mupdf:fitz:1.27.0"                          // MuPDF JNI

// Markdown 原生渲染
"io.noties.markwon:core:4.6.2"
"io.noties.markwon:ext-tables:4.6.2"
"io.noties.markwon:ext-strikethrough:4.6.2"

// 导航
"androidx.navigation:navigation-compose:2.8.9"

// DI
"io.insert-koin:koin-android:4.0.2"
"io.insert-koin:koin-androidx-compose:4.0.2"

// 本地存储
"androidx.room:room-runtime:2.7.1"
"androidx.room:room-ktx:2.7.1"
ksp("androidx.room:room-compiler:2.7.1")

// 设置
"androidx.datastore:datastore-preferences:1.1.4"

// 封面图片
"io.coil-kt.coil3:coil-compose:3.1.0"

// TXT 分页
"androidx.paging:paging-runtime:3.3.6"
"androidx.paging:paging-compose:3.3.6"

// Phase 2：手写笔
// "androidx.ink:ink-authoring:1.0.0"
// "androidx.ink:ink-rendering:1.0.0"
```

KSP plugin：`com.google.devtools.ksp:2.1.21-1.0.32`

---

## 已知局限与演进路径

| 局限 | 影响 | Phase 2 方案 |
|------|------|-------------|
| MuPDF EPUB 字号调整需 CSS 注入，不够灵活 | 字号切换后需重新渲染全部页 | 自研 EPUB 反排引擎（参考 CREngine 思路） |
| MuPDF bitmap 模式无原生文字选择 | EPUB/DOCX 无法划词标注 | Phase 2 InkOverlay + 坐标映射反查 |
| DOC 只做文本提取 | 格式信息丢失 | 提示用户转 DOCX，不计划原生 DOC 渲染 |
| AnimationEngine CurlTransition 无动态 follow-finger | 翻页不跟手 | 集成 `VelocityTracker` + 手势驱动进度 |

---

## 与竞品对比（设计层面）

| 维度 | 静读天下 | LinReads |
|------|---------|---------|
| 渲染模式 | MRTextView 一统（Java 97KB，Android-only） | 格式独立，MuPDF + 原生 |
| EPUB 字体 | 成熟，多年调优 | Phase 1 CSS 注入，Phase 2 自研 |
| 动画 | 黑盒 | 自研 vsync 驱动，物理卷页 |
| WebView | 无（Native Java 渲染） | 无（全原生，同样无 WebView）|
| 可测试性 | 差（MRTextView 紧耦合） | MVI + Engine interface 完全可 mock |
| Calibre | 无 | 原生 REST API，核心差异点 |
