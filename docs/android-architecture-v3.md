# LinReads Android 架构文档 v3

> **状态**: 权威规范 — 所有 v1 文档（`Platform-Android.md`、`Android-v2-architecture.md`）自此废弃。
> **最后更新**: 2026-06-18
> **适用版本**: LinReads Android v3.0.0+
> **模块总数**: 21 | **分层**: 8 | **渲染引擎**: 7

---

## 目录

1. [设计原则](#一设计原则)
2. [模块地图](#二模块地图)
3. [视图架构](#三视图架构)
4. [渲染引擎契约](#四渲染引擎契约)
5. [Ink / 手写笔集成](#五ink--手写笔集成)
6. [数据层](#六数据层)
7. [扩展系统](#七扩展系统)
8. [依赖注入](#八依赖注入)
9. [Gradle 基础设施](#九gradle-基础设施)
10. [迁移路径](#十迁移路径)
11. [架构裁决记录](#十一架构裁决记录)

---

## 一、设计原则

| # | 原则 | 说明 |
|---|------|------|
| 1 | **可插拔引擎（Pluggable Engines）** | 渲染引擎通过 `ReaderEngine` 接口自注册，权重发现，新增格式无需改动已有代码（开放-封闭原则）。对标 KOReader 的 per-type 用户 override。 |
| 2 | **混合视图（Hybrid View）** | 文档渲染使用原生 `android.view.View`（WebView、ImageView、RecyclerView），不采用 Compose `Image(bitmap.asImageBitmap())` 作为文档渲染基元。Compose 仅负责 UI Chrome overlay。 |
| 3 | **MVI 单向数据流（Unidirectional Data Flow）** | `ReaderIntent → ReaderViewModel.reduce → ReaderState`。View 层只消费 `StateFlow<ReaderState>`，不直接读写 View 内部状态。 |
| 4 | **离线优先（Offline-First）** | 进度/书签/标注一律先写 Room 本地数据库，UI 立即更新；后台异步同步到远端。翻页进度 2 秒 debounce 后持久化。 |
| 5 | **依赖倒置（Dependency Inversion）** | 功能模块依赖抽象接口（`render:api`、`extensions:api`），不依赖具体引擎实现。所有模块间依赖方向为 Layer N → Layer N-1，无反向依赖。 |

---

## 二、模块地图

### 2.1 完整模块清单（21 个）

```
 1  :app                            # com.android.application，组装点
 2  :core:model                     # 纯 Kotlin (kotlin("jvm"))，零 Android import
 3  :core:calibre                   # CalibreClient + CalibreRepository
 4  :core:database                  # Room 数据库 + DAOs
 5  :core:prefs                     # DataStore Preferences
 6  :core:ui                        # Compose 主题 + 共享组件
 7  :render:api                     # ReaderEngine 接口 + ReaderEngineRegistry
 8  :render:epub                    # WebView + epub-ts
 9  :render:pdf                     # PdfRenderer
10  :render:txt                     # RecyclerView + TxtVirtualPager + Paging3
11  :render:mupdf                   # DOCX + CBZ (MuPDF JNI)
12  :render:md                      # Markdown (Markwon Spannables)
13  :render:animate                 # ViewPager2.PageTransformer（翻页动效）
14  :ink                            # InkOverlay + Canvas（androidx.ink）
15  :features:library               # LibraryScreen + LibraryViewModel
16  :features:reader                # ReaderScreen + ReaderViewModel（MVI）
17  :features:settings              # SettingsScreen + SettingsViewModel
18  :extensions:api                 # Extension SPI + BookSource + ReaderEventBus
19  :extensions:tts                 # TTS 朗读扩展
20  :extensions:stats               # 阅读统计扩展
21  :extensions:opds                # OPDS 书源扩展
```

### 2.2 八层分层与依赖规则

| Layer | 名称 | 模块 | 规则 |
|-------|------|------|------|
| **0** | 纯 Kotlin | `:core:model` | `kotlin("jvm")`，零 Android framework import。仅含数据类型：`BookMeta`、`BookFormat`、`Locator`、`ReaderState`、`ReadflowError`、`InkAnchor`、`TransitionType`、`ThemeMode`、`DownloadStatus`、`SyncBackend`、`Bookmark`、`LoadingState`、`Offset`。依赖：**无**。 |
| **1** | Android 数据 | `:core:calibre`、`:core:database`、`:core:prefs`、`:extensions:api` | `android-library`，可用 `android.*`，禁用 Compose。`extensions:api` 自身为纯 Kotlin 接口，零 Android 依赖。 |
| **2** | 渲染抽象 | `:render:api` | `android-library`，允许 `android.view.View` 和 `android.net.Uri`，禁用 Compose。仅定义 `ReaderEngine` 接口、`ReadingMode`、`ReaderEngineRegistry`。`Locator` 从 `:core:model` import，不重复定义。 |
| **3** | 渲染实现 | `:render:epub`、`:render:pdf`、`:render:txt`、`:render:mupdf`、`:render:md`、`:render:animate` | `android-library`，产出 `View`，禁用 Compose。**不得**依赖 `:core:ui`、`:core:calibre`、`:core:database`、`:core:prefs`、`:ink`、任何 feature、任何 extension。 |
| **4** | Ink | `:ink` | `android-library`，View 系统，无 Compose。依赖 `:core:model` + `:core:database` + `:render:api`。`InkAnchor` 定义在 `:core:model`，序列化/反序列化由 `:ink` 模块的 `InkAnchorCodec` 处理。 |
| **5** | UI 基础 | `:core:ui` | `android-library`，Compose 可用。Material3 主题、色板、字体、间距 tokens、共享 composable。 |
| **6** | 功能模块 | `:features:library`、`:features:reader`、`:features:settings` | `android-library`，Compose + ViewModels。规则：(1) 不得直接依赖 `render:*` 实现模块，仅通过 `:app` DI 注入具体引擎；(2) 功能模块之间不得相互依赖。 |
| **7** | 扩展 | `:extensions:tts`、`:extensions:stats`、`:extensions:opds` | `android-library`，可选。实现 Extension SPI。通过 `settings.gradle.kts` 可条件排除。 |
| **8** | 应用组装 | `:app` | `com.android.application`。`MainActivity`（FrameLayout root + ComposeView overlay）、Koin DI 装配、Navigation host、`AndroidManifest`。依赖：**全部模块**。 |

### 2.3 依赖表（允许的依赖方向：仅向下）

```
                     L0        L1              L2          L3            L4   L5       L6         L7   L8
                     core:     core:calibre    render:api  render:epub   ink  core:ui  features:  ext: app
                     model     core:database               render:pdf               library    tts
                               core:prefs                  render:txt               reader     stats
                               ext:api                     render:mupdf             settings   opds
                                                           render:md
                                                           render:animate

:core:model          -         -               -           -             -    -        -          -    -
:core:calibre        ✅        -               -           -             -    -        -          -    -
:core:database       ✅        -               -           -             -    -        -          -    -
:core:prefs          ✅        -               -           -             -    -        -          -    -
:extensions:api      ✅        -               -           -             -    -        -          -    -
:render:api          ✅        -               -           -             -    -        -          -    -
:render:epub         ✅        -               ✅          -             -    -        -          -    -
:render:pdf          ✅        -               ✅          -             -    -        -          -    -
:render:txt          ✅        -               ✅          -             -    -        -          -    -
:render:mupdf        ✅        -               ✅          -             -    -        -          -    -
:render:md           ✅        -               ✅          -             -    -        -          -    -
:render:animate      ✅        -               ✅          -             -    -        -          -    -
:ink                 ✅        ✅(database)    ✅          -             -    -        -          -    -
:core:ui             ✅        -               -           -             -    -        -          -    -
:features:library    ✅        ✅(calibre)     -           -             -    ✅       -          -    -
:features:reader     ✅        -               ✅(api)     -             -    ✅       -          -    -
:features:settings   ✅        ✅(prefs)       -           -             -    ✅       -          -    -
:extensions:tts      ✅        -               -           -             -    -        -          -    -
:extensions:stats    ✅        ✅(database)    -           -             -    -        -          -    -
:extensions:opds     ✅        -               -           -             -    -        -          -    -
:app                 ✅        ✅              ✅          ✅             ✅   ✅       ✅         ✅   -
```

图例：`✅` = 允许的依赖 | `-` = 禁止的依赖

### 2.4 关键约束（CI 强制执行）

1. `:render:*` 模块不得依赖 Compose。CI 检查：
   ```
   ./gradlew :render:epub:dependencies --configuration compileClasspath | grep compose
   ```
   返回必须为空。

2. `:core:model` 不得依赖 `android.*`。CI 检查：
   ```
   ./gradlew :core:model:dependencies --configuration compileClasspath | grep android
   ```
   返回必须为空。

3. `:features:reader` 不得依赖 `:render:epub` / `:render:pdf` 等具体引擎。CI 检查通过 `compileClasspath` 验证。

4. 功能模块之间零依赖。CI 检查 `:features:library:compileClasspath` 不含 `:features:reader` 或 `:features:settings`。

---

## 三、视图架构

### 3.1 架构决策：Hybrid View

不使用 Compose `Image(bitmap.asImageBitmap())` 作为文档渲染基元。Root 为 `FrameLayout`，文档 View 直接加入 View hierarchy，Compose 仅管理 UI Chrome overlay。

理由：
- WebView（EPUB 渲染）无法在 Compose 中高效嵌入——WebView 持有自己的 Surface，与 Compose 的 draw 管线冲突。
- ImageView（PDF/DOCX/CBZ）可用 `AndroidView` 包装，但 Compose 的 recomposition 会在翻页时引发不必要的 View 重建。
- RecyclerView（TXT）的 `LayoutManager` / `SnapHelper` 与 Compose `LazyColumn` 存在语义差距。
- 统一的 `FrameLayout` z-order 模型使 Ink overlay 层次清晰，触摸路由简单。

### 3.2 FrameLayout Z-order

```
FrameLayout (match_parent × match_parent) ← reader_activity.xml root
│  id: reader_root
│  clipChildren: false (允许 ink 笔迹超出文档边界)
│
├─ [Z=0 — 文档容器]  FrameLayout id: document_host
│   │  同一时刻只有 ONE child，按格式映射：
│   │
│   │  EPUB → WebView
│   │    ├─ epub-ts in iframe(s)，每 spine item 一个
│   │    ├─ JS bridge: CFI 导航、位置回报
│   │    ├─ CSS 注入: 字体大小 / 主题 / 行距
│   │    └─ Shadow DOM 包装实现 CSS 隔离
│   │
│   │  PDF   → ImageView
│   │    ├─ PdfRenderer (API 21+) 驱动
│   │    ├─ Bitmap LRU cache (3 pages: prev/current/next)
│   │    └─ ScaleType.MATRIX for pinch-zoom
│   │
│   │  DOCX  → ImageView (MuPDF JNI, com.artifex.mupdf:fitz:1.27.0)
│   │  CBZ   → ImageView (MuPDF JNI)
│   │    └─ 与 PDF 同 LRU cache 策略
│   │
│   │  TXT   → RecyclerView (vertical LinearLayoutManager)
│   │    ├─ TxtVirtualPager + Paging3 PagingSource
│   │    └─ byteOffset 分页，scroll mode 或 SnapHelper page mode
│   │
│   │  MD    → ScrollView > TextView
│   │    ├─ Markwon Spannables（无中间 HTML，无 WebView）
│   │    └─ 按 ## heading 分 section，滚动时增量 append
│
├─ [Z=1 — 已完成 Ink Canvas]
│  CanvasView (extends View) id: ink_completed
│  z-order: above document_host
│  onDraw(): 反序列化 StrokeInputBatch → CanvasStrokeRenderer
│  touchable: false (不消费事件 — 见触摸路由)
│  行为:
│    • 渲染当前页所有已完成笔迹
│    • 翻页 → 从 Room 加载新页笔迹
│    • stylus ACTION_UP: 笔迹序列化 + append → invalidate()
│    • 擦除: 从列表移除笔迹 → invalidate()
│
├─ [Z=2 — 进行中 Ink（前端缓冲）]
│  InProgressStrokesView (androidx.ink.authoring)
│  id: ink_in_progress
│  z-order: above ink_completed
│  touchable: false (事件由程序路由)
│  行为:
│    • 渲染 ACTIVE 笔迹，<10ms 延迟（前端缓冲路径）
│    • 通过 inkInProgress.handleMotionEvent() 接收 MotionEvent
│    • ACTION_UP → InProgressStrokesView.removeFinishedStroke()
│      → CanvasView 抓取结果 → invalidate()
│    • MotionEventPredictor 开启，提供预测线渲染
│
└─ [Z=3 — Compose UI Overlay]
   ComposeView id: ui_overlay
   z-order: 最上层
   isClickable: false at root (非 UI 区域透传)
   Composition:
     ├─ TopBar (AnimatedVisibility): 返回按钮、书名/章节名、书签开关、Overflow menu
     ├─ 透明透传区域（触摸落到文档层）
     ├─ BottomBar (AnimatedVisibility): 进度条 (SeekBar via AndroidView)、页码指示器、模式 chips
     └─ InkToolbar (SlideIn, 条件渲染): 笔类型选择器、色板 (6 色)、笔触宽度滑块、橡皮擦、Undo/Redo
```

### 3.3 Z-order 总结

| Z | 层 | View 类型 | 消费事件? |
|---|-----|----------|-----------|
| 0 | 文档 | 格式相关 View | Yes —— 手指滚动 / 缩放 |
| 1 | 已完成 Ink | `CanvasView` (custom) | No —— 透传 |
| 2 | 进行中 Ink | `InProgressStrokesView` | No —— 程序路由 |
| 3 | UI Chrome | `ComposeView` | Yes —— 仅 toolbar / button 区域 |

### 3.4 触摸路由（Tool-type-based，非 Mode-toggle-based）

核心原则：Stylus 事件始终走 ink；手指事件始终走文档导航或 UI。不需要按 "ink mode" 按钮。

核心实现类：`ReaderRootLayout`（继承 `FrameLayout`），定义在 `:features:reader` 模块中。

```
MotionEvent arrives at reader_root (FrameLayout)
  │
  ├─ ACTION_DOWN → 记录 tool type
  │   ├─ TOOL_TYPE_STYLUS / TOOL_TYPE_ERASER
  │   │   → INTERCEPT，整个手势流路由到 inkOverlay.handleStylusEvent(event)
  │   │
  │   └─ TOOL_TYPE_FINGER / TOOL_TYPE_MOUSE / TOOL_TYPE_UNKNOWN
  │       → DON'T INTERCEPT
  │       ├─ Hit test against ComposeView UI 区域
  │       │   ├─ hit → ComposeView handles (button tap, slider drag)
  │       │   └─ no hit → pass to documentView
  │       └─ documentView.onTouchEvent(event): scroll/fling/zoom
  │
  ├─ ACTION_POINTER_DOWN (额外手指/stylus)
  │   ├─ new pointer is STYLUS → cancel 当前手指手势 → intercept for ink
  │   └─ new pointer is FINGER (2nd finger) → pinch-zoom on document (if applicable)
  │
  └─ ACTION_UP / ACTION_POINTER_UP
      → Stylus: inkOverlay 完成笔迹 / Finger: 结束手势
```

### 3.5 点击区域翻页（手指点击文档）

- 左 1/3 → 上一页 `ReaderIntent.PreviousPage`
- 右 2/3 → 下一页 `ReaderIntent.NextPage`
- 中间 → 显示/隐藏 Chrome `ReaderIntent.ToggleUi`

### 3.6 缩放手势

| 格式 | 实现方式 |
|------|---------|
| EPUB | WebView 原生处理 pinch-zoom。缩放结束后 debounce 200ms 调 `rendition.resize()` |
| PDF / DOCX / CBZ | `ScaleGestureDetector` + `ImageView.matrix`。翻页时 matrix 重置为 identity |
| TXT / MD | Pinch 触发 `setFontSize()` 而非 viewport 缩放 |

---

## 四、渲染引擎契约

### 4.1 ReaderEngine 接口

定义在 `:render:api`，是阅读器与所有格式引擎之间的唯一契约接口。

```kotlin
// =============================================================================
// Module:  :render:api  (Layer 2 — android-library, NO Compose)
// Package: dev.readflow.render.api
// =============================================================================

package dev.readflow.render.api

import android.net.Uri
import android.os.Bundle
import android.view.View
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.Locator
import kotlinx.coroutines.flow.StateFlow

interface ReaderEngine {

    // ── Identity ────────────────────────────────────────

    /** Stable identifier for user-override and debugging (e.g. "epub-webview"). */
    val id: String

    /** The book format this engine handles. */
    val format: BookFormat

    /**
     * Integer priority for same-format engine selection. Lower = preferred.
     * Convention: 0–9 = system-native, 10–19 = first-party JNI,
     *             20–29 = third-party, 30+ = text-extraction fallback.
     */
    val priority: Int

    /** Whether this engine can handle the given file. Called by the registry at resolution time. */
    suspend fun supports(uri: Uri): Boolean

    // ── Lifecycle ───────────────────────────────────────

    /**
     * Open the document. Heavy work (parsing, indexing) happens here, not in the constructor.
     * Must be called before createView() or goTo().
     * Returns the Locator for the document's first position.
     */
    suspend fun openBook(uri: Uri): Locator

    /**
     * Create the View that renders this document.
     * Called after openBook(). The returned View is added to the document_host FrameLayout.
     * The engine owns this View — callers must call close() before removing it.
     */
    fun createView(): View

    /**
     * Release all resources (file handles, Bitmaps, WebView teardown).
     * After close() the engine is dead — create a new instance to re-open.
     */
    suspend fun close()

    // ── Navigation ──────────────────────────────────────

    /** Navigate to a specific location. */
    suspend fun goTo(locator: Locator)

    /** Current position, updated after every page-turn / scroll-stop. */
    val currentLocator: StateFlow<Locator>

    /**
     * Page count (or equivalent). Reactive — updates when parsing completes
     * or layout changes (e.g. font-size change for reflow formats).
     */
    val pageCount: StateFlow<Int>

    // ── Layout control (reflow formats) ─────────────────

    /** Adjust font size in scaled pixels. No-op for fixed-layout formats. */
    suspend fun setFontSize(sp: Float)

    /** Set reading mode. No-op for formats that only support one mode. */
    suspend fun setMode(mode: ReadingMode)

    // ── View lifecycle (per root-cause resolution #11) ──

    /** Called when the engine's View is attached to a window (config-change restore). */
    fun onViewAttached(view: View) {}

    /** Called when the engine's View is detached (config-change / Activity destroy). */
    fun onViewDetached(view: View) {}

    /** Persist engine-specific state for process-death survival. */
    suspend fun saveState(): Bundle = Bundle.EMPTY

    /** Restore engine-specific state after process-death recreation. */
    suspend fun restoreState(state: Bundle) {}
}

/** Reading mode for reflow formats. */
enum class ReadingMode { SCROLL, PAGED }
```

### 4.2 ReaderEngineRegistry

权重发现 + 用户覆盖。对标 KOReader 的 per-type 用户 override。

```kotlin
// =============================================================================
// Module:  :render:api  (Layer 2 — android-library)
// Package: dev.readflow.render.api
// =============================================================================

package dev.readflow.render.api

import android.net.Uri
import dev.readflow.core.model.BookFormat

class ReaderEngineRegistry(
    private val engines: Set<ReaderEngine>
) {
    /** Per-format user engine overrides. Maps BookFormat → engine id. */
    private val userOverrides = mutableMapOf<BookFormat, String>()

    /**
     * Resolve the best engine for a URI.
     *
     * Algorithm:
     *   1. Check user override for the inferred format → return if found.
     *   2. Filter engines by supports(uri).
     *   3. Sort by (priority, format ordinal).
     *   4. Return the first (lowest priority = best).
     *
     * @throws NoEngineException if no engine supports this file.
     */
    suspend fun resolve(uri: Uri): ReaderEngine {
        val path = uri.lastPathSegment ?: uri.path ?: ""
        val ext = path.substringAfterLast('.', "")
        val format = BookFormat.fromExtension(ext)

        // Honour user override
        userOverrides[format]?.let { engineId ->
            engines.find { it.id == engineId }?.let { return it }
        }

        val candidates = engines
            .filter { it.supports(uri) }
            .sortedWith(compareBy({ it.priority }, { it.format.ordinal }))

        return candidates.firstOrNull()
            ?: throw NoEngineException(uri)
    }

    /** List all engines that claim support for a format (for settings / debug UI). */
    fun candidatesFor(format: BookFormat): List<ReaderEngine> =
        engines.filter { it.format == format }.sortedBy { it.priority }

    /** All registered engines (for introspection). */
    fun allEngines(): Set<ReaderEngine> = engines

    // ── User override ───────────────────────────────────

    fun setUserOverride(format: BookFormat, engineId: String) {
        userOverrides[format] = engineId
    }

    fun getUserOverride(format: BookFormat): String? =
        userOverrides[format]

    fun clearUserOverride(format: BookFormat) {
        userOverrides.remove(format)
    }
}

class NoEngineException(uri: Uri) :
    IllegalStateException("No ReaderEngine supports: $uri")
```

### 4.3 Koin 多绑注册

引擎通过 Koin multi-bind 自注册：

```kotlin
// 在每个 render:* 模块的 DI module 中
val epubEngineModule = module {
    single<ReaderEngine> { EpubWebViewEngine() } bind ReaderEngine::class
}
val pdfEngineModule = module {
    single<ReaderEngine> { PdfRendererEngine() } bind ReaderEngine::class
}
// ... etc

// Registry 自动收集所有绑定
val renderModule = module {
    single { ReaderEngineRegistry(getAll<ReaderEngine>().toSet()) }
}
```

### 4.4 各格式引擎表（7 个引擎）

| 格式 | 引擎 | Priority | 模块 | View 类型 | 关键技术 |
|------|------|----------|------|-----------|---------|
| EPUB | `EpubWebViewEngine` | 0 | `:render:epub` | `WebView` | `@likecoin/epub-ts` (BSD-2-Clause, epubjs v0.3.93 TS 重写)。本地 nanohttpd server 提供解压内容。CFI 导航，CSS 注入（字号/主题/行距），JS bridge 回报位置。Shadow DOM 包装实现 CSS 隔离。`@JavascriptInterface` (AnchorBridge, ProgressBridge) 发送 CFI 和进度百分比。 |
| PDF | `PdfRendererEngine` | 0 | `:render:pdf` | `ImageView` | Android `PdfRenderer` (API 21+)，零外部依赖。LRU cache 3 pages。`ScaleType.MATRIX` + `ScaleGestureDetector`。pageCount sync 已知。 |
| TXT | `TxtVirtualPagerEngine` | 0 | `:render:txt` | `RecyclerView` | `FileChannel` (RandomAccess) 64KB 块扫描 → 页边界检测（空行≥2 / 章节正则 / 字符阈值 2000）→ Paging3 `PagingSource<Long, CharSequence>`。SCROLL（默认）或 PAGED（SnapHelper）。CharsetDetector: UTF-8/GBK/Shift-JIS fallback chain。 |
| DOCX | `MuPdfEngine` | 10 | `:render:mupdf` | `ImageView` | MuPDF JNI `com.artifex.mupdf:fitz:1.27.0`。每页 Bitmap。字号变化会改变 pageCount。与 PDF 同 LRU 策略。 |
| CBZ | `MuPdfEngine` | 10 | `:render:mupdf` | `ImageView` | 同 DOCX（共享 MuPDF JNI 内核）。 |
| MD | `MarkwonEngine` | 0 | `:render:md` | `TextView` (in `ScrollView`) | `Markwon` → `Spannable` → `TextView`。无 WebView。按 `## heading` 分 section，增量 append。扩展 `markwon-ext-tables` + `markwon-ext-strikethrough`。 |
| 翻页动效 | `SlideFadeTransformer` / `CurlPageTransformer` | - | `:render:animate` | `ViewPager2.PageTransformer` | Phase 1: `SlideFadeTransformer`。Phase 2: `CurlPageTransformer` (卷页效果，`rotationY` 3D 投影)。非分页格式（TXT、MD）不适用 ViewPager2。 |

### 4.5 翻页动效策略

放弃 v1 自研 `AnimationEngine`（vsync loop + `Bitmap.createBitmap()` 每帧分配），改用 **ViewPager2 + PageTransformer**。

理由：
1. v1 固定时长补间动画不跟手 — 用户期望翻页边跟随拇指。
2. `Bitmap.createBitmap()` 每帧一次（60fps x 300ms = 18 次分配），低端设备 GC 压力大。
3. ViewPager2 自带跟手拖拽、离屏预渲染、`PageTransformer` 扩展点。

- **Phase 1**：`SlideFadeTransformer`（首发）
- **Phase 2**：`CurlPageTransformer`（卷页效果，`rotationY` 3D 投影）
- **非分页格式**（TXT scroll 模式、MD）：不适用 ViewPager2，直接 `RecyclerView`/`ScrollView` 垂直滚动

### 4.6 EPUB 引擎细节

- **渲染内核**：`@likecoin/epub-ts`（epubjs v0.3.93 的 TypeScript 重写，BSD-2-Clause 许可）
- **内容服务**：本地 nanohttpd server 提供解压后的 EPUB 内容，WebView 通过 HTTP 加载 iframe
- **CSS 隔离**：Shadow DOM 包装，防止 EPUB 内 CSS 泄露到宿主页面
- **JS Bridge**：`@JavascriptInterface` 方法（`AnchorBridge` 上报 CFI 位置，`ProgressBridge` 上报阅读进度百分比）
- **CSS 注入**：字体大小（`font-size`）、主题（`background-color` / `color`）、行距（`line-height`）通过注入 CSS 实现
- **CFI 导航**：`rendition.display(cfi)` 跳转到精确位置

### 4.7 视图生命周期（Root-cause Resolution #11）

引擎 View 在以下场景经历 detach/attach 周期：
- Activity 配置变更（旋转屏幕、折叠屏展开/折叠）
- 进程死亡后恢复（`savedInstanceState` → `restoreState`）

`onViewAttached` / `onViewDetached` 允许引擎在 detach 时暂停渲染（节省资源），在 attach 时恢复。`saveState` / `restoreState` 提供进程死亡存活能力。

---

## 五、Ink / 手写笔集成

### 5.1 Phase 策略

| Phase | 交付内容 | 依赖 |
|-------|---------|------|
| **Phase 1** | `:ink` 模块搭建，`InkOverlay` 接口定义，`InkAnchor` 模型（在 `:core:model`），Room schema 预留（`AnnotationEntity` 含 `strokeData: ByteArray?` + `anchorJson: String?`）。**不实现** ink 渲染。 | `:core:model` + `:render:api` |
| **Phase 2** | `CanvasView` + `InProgressStrokesView` 实现，触摸路由集成，`InkToolbar` composable，`MotionEventPredictor` 开启。PDF 优先（`InkAnchor.Page` — 页面坐标稳定）。 | Phase 1 + `androidx.ink:ink-authoring:1.0.0-beta02` + `androidx.ink:ink-rendering:1.0.0-beta02` |
| **Phase 3** | EPUB `InkAnchor.Text` 支持（回流格式，CSS selector + text offset 锚定）。字号变化后通过重新解析文本锚点的 bounding box 重定位笔迹。 | Phase 2 + EPUB engine 集成 |

### 5.2 InkBrush 类型

定义在 `:ink` 模块（Layer 4），使用 `android.graphics.Color` (int)，非 Compose Color。

```kotlin
// =============================================================================
// Module:  :ink  (Layer 4 — android-library, View system, NO Compose)
// Package: dev.readflow.ink
// =============================================================================

package dev.readflow.ink

import android.graphics.Color

sealed class InkBrush(
    open val color: Int,
    open val width: Float
) {
    /** Standard ballpoint-style pen. Uniform width, full opacity. */
    data class Pen(
        override val color: Int = Color.BLACK,
        override val width: Float = 2f,
    ) : InkBrush(color, width)

    /** Pressure-sensitive fountain pen. Width varies with stylus pressure. */
    data class FountainPen(
        override val color: Int = Color.BLACK,
        override val width: Float = 2f,
        val pressureSensitivity: Float = 1.0f,
    ) : InkBrush(color, width)

    /** Semi-transparent highlighter marker. Wide, behind-text rendering. */
    data class Highlighter(
        override val color: Int = 0x80FFFF00.toInt(),
        override val width: Float = 12f,
    ) : InkBrush(color, width)

    /** Eraser tool. Removes strokes intersecting its path. Color is TRANSPARENT by convention. */
    data class Eraser(
        override val width: Float = 20f,
    ) : InkBrush(Color.TRANSPARENT, width)
}
```

### 5.3 InkOverlay 接口

定义在 `:ink` 模块（Layer 4）。

```kotlin
// =============================================================================
// Module:  :ink  (Layer 4 — android-library, View system, NO Compose)
// Package: dev.readflow.ink
// =============================================================================

package dev.readflow.ink

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

interface InkOverlay {

    // ── Lifecycle ────────────────────────────────────────

    /** Attach both ink layers as siblings of documentView in the parent FrameLayout. */
    fun attach(parent: FrameLayout, documentView: View)

    /** Detach both ink layers. Call before switching document Views (e.g. PDF→EPUB). */
    fun detach()

    // ── Input ────────────────────────────────────────────

    /**
     * Route a stylus MotionEvent through the ink pipeline.
     * Finger events MUST NOT be passed here — the caller performs tool-type routing.
     */
    fun handleStylusEvent(event: MotionEvent): Boolean

    /** Cancel the in-progress stroke (palm rejection, ACTION_CANCEL). */
    fun cancelCurrentStroke()

    // ── Stroke lifecycle (toolbar-driven) ────────────────

    /** Set the active brush for new strokes. */
    fun setBrush(brush: InkBrush)

    /** Undo the last stroke on the current page. Returns true if there was a stroke to undo. */
    fun undo(): Boolean

    /** Redo the last undone stroke on the current page. Returns true if there was a stroke to redo. */
    fun redo(): Boolean

    /** Clear all strokes from the current page (irreversible). */
    fun clearPage()

    // ── Page-transition hooks ────────────────────────────

    /** Called before a page change. Persists in-progress strokes, hides ink layers to avoid ViewPager2 drag ghosting. */
    fun onPageWillChange()

    /** Called after a page change completes. Loads completed strokes for the new page from Room. */
    fun onPageChanged(anchor: InkAnchor)

    // ── State queries ────────────────────────────────────

    /** Whether the current page has any completed strokes. */
    val hasStrokes: Boolean

    /** Whether a stylus stroke is currently in progress (front-buffer active). */
    val isDrawing: Boolean
}
```

### 5.4 InkAnchor 模型

定义在 `:core:model`（Layer 0），纯数据，无 Android 依赖。

```kotlin
// =============================================================================
// Module:  :core:model  (Layer 0 — pure Kotlin, zero Android imports)
// Package: dev.readflow.core.model
// =============================================================================

package dev.readflow.core.model

import kotlinx.serialization.Serializable

/**
 * Binds a set of ink strokes to a document location.
 *
 * Two strategies, chosen by format type:
 *   Page  — fixed-layout (PDF, CBZ, DOCX): anchor to page index.
 *           Page coordinates are stable because layout never reflows.
 *   Text  — reflow (EPUB, TXT, MD): anchor to text position.
 *           Page coordinates would be invalid after a font-size change.
 */
@Serializable
sealed interface InkAnchor {

    /**
     * Page-mode anchor: stroke coordinates are relative to a known immutable page.
     */
    @Serializable
    data class Page(
        val pageIndex: Int,
        val pageWidth: Float,
        val pageHeight: Float,
    ) : InkAnchor

    /**
     * Text-mode anchor: strokes bind to text, not screen position.
     *
     * On reflow, the bounding box of the text anchor is re-resolved
     * and strokes are re-positioned relative to it.
     *
     * Aligned with W3C EPUB Annotations 1.0 layered-selector model.
     */
    @Serializable
    data class Text(
        val sourceHref: String,
        val cssSelector: String,
        val textStartOffset: Int,
        val textEndOffset: Int,
        val offsetXPx: Float,
        val offsetYPx: Float,
        val fontSizeAtCapture: Float,
    ) : InkAnchor
}
```

`InkAnchor` 序列化/反序列化由 `:ink` 模块的 `InkAnchorCodec` 处理。Room 存储 `anchorType: String` + `anchorJson: String`，不直接引用 `InkAnchor` 类型。

### 5.5 Ink 生命周期状态机

```
IDLE
  → (stylus ACTION_DOWN)
  → DRAWING (InProgressStrokesView active, front buffer)
    → (stylus ACTION_UP)
    → COMMITTING (serialize stroke → ByteArray → Room → CanvasView.strokes.add → invalidate())
    → IDLE (loop)
    → (ACTION_CANCEL)
    → cancelCurrentStroke()
    → IDLE (笔迹丢弃)
```

---

## 六、数据层

### 6.1 核心类型定义

所有以下类型唯一地定义在 `:core:model`（Layer 0），由其他模块 import。

#### 6.1.1 Locator

Readium-compatible 定位模型。五种定位策略，均为 `@Serializable`。

```kotlin
// =============================================================================
// Module:  :core:model  (Layer 0 — pure Kotlin, zero Android imports)
// Package: dev.readflow.core.model
// =============================================================================

package dev.readflow.core.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface Locator {

    /** EPUB CFI (Canonical Fragment Identifier) — spine-item-level precision. */
    @Serializable
    data class Cfi(val cfi: String) : Locator

    /** Fixed-layout page index (PDF, CBZ, DOCX). */
    @Serializable
    data class Page(
        val index: Int,
        val total: Int = -1
    ) : Locator

    /** Byte-offset position for plain-text formats (TXT). */
    @Serializable
    data class ByteOffset(
        val offset: Long,
        val length: Int
    ) : Locator

    /** Heading-index + scroll-offset for Markdown and structured reflow. */
    @Serializable
    data class Section(
        val headingIndex: Int,
        val scrollY: Int = 0
    ) : Locator

    /** Sentinel for unopened / unknown position. */
    @Serializable
    data object Unknown : Locator
}
```

#### 6.1.2 ReaderState

不可变的 reader UI 状态快照 — MVI 中的单一事实源。14 个字段，零 Android framework 类型（Root-cause Resolution #1）。

```kotlin
// =============================================================================
// Module:  :core:model  (Layer 0 — pure Kotlin, NO android.view.View)
// Package: dev.readflow.core.model
// =============================================================================

package dev.readflow.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ReaderState(
    val bookId: String,
    val bookMeta: BookMeta? = null,
    val format: BookFormat = BookFormat.UNKNOWN,
    val loadingState: LoadingState = LoadingState.Idle,
    val currentLocator: Locator? = null,
    val totalPages: Int = 0,
    val currentPageIndex: Int = 0,
    val fontSize: Int = 18,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val zoomLevel: Float = 1.0f,
    val panOffset: Offset = Offset.Zero,
    val isUiVisible: Boolean = true,
    val transition: TransitionType = TransitionType.SLIDE,
    val error: ReadflowError? = null,
)

/** Logical pixel offset used for panning fixed-layout pages. */
@Serializable
data class Offset(val x: Float = 0f, val y: Float = 0f) {
    companion object {
        val Zero = Offset()
    }
}
```

#### 6.1.3 ReadflowError

结构化错误层级 — 替代 v1 的裸 `error: String?`。

```kotlin
// =============================================================================
// Module:  :core:model  (Layer 0 — pure Kotlin, zero Android imports)
// Package: dev.readflow.core.model
// =============================================================================

package dev.readflow.core.model

import kotlinx.serialization.Serializable

@Serializable
sealed class ReadflowError(
    open val message: String,
    open val cause: Throwable? = null
) {
    @Serializable
    data class Network(
        val code: Int?,
        override val message: String,
        override val cause: Throwable? = null
    ) : ReadflowError(message, cause)

    @Serializable
    data class Database(
        override val message: String,
        override val cause: Throwable? = null
    ) : ReadflowError(message, cause)

    @Serializable
    data class Parse(
        override val message: String,
        override val cause: Throwable? = null
    ) : ReadflowError(message, cause)

    @Serializable
    data class NotFound(
        val resourceType: String,
        val id: String,
        override val message: String = "$resourceType not found: $id"
    ) : ReadflowError(message)

    @Serializable
    data class Unsupported(
        val format: String,
        override val message: String = "Unsupported format: $format"
    ) : ReadflowError(message)

    @Serializable
    data class Auth(
        override val message: String = "Authentication failed"
    ) : ReadflowError(message)

    @Serializable
    data class Unknown(
        override val message: String = "An unexpected error occurred",
        override val cause: Throwable? = null
    ) : ReadflowError(message, cause)
}

/**
 * Monadic result type for all repository and engine operations.
 * Either Success(value) or Failure(error) — never both, never null.
 */
sealed class ReadflowResult<out T> {
    data class Success<T>(val value: T) : ReadflowResult<T>()
    data class Failure(val error: ReadflowError) : ReadflowResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun <R> map(transform: (T) -> R): ReadflowResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw RuntimeException(error.message, error.cause)
    }
}
```

#### 6.1.4 辅助类型

**DownloadStatus**:

```kotlin
// =============================================================================
// Module:  :core:model  (Layer 0 — pure Kotlin, zero Android imports)
// Package: dev.readflow.core.model
// =============================================================================

package dev.readflow.core.model

enum class DownloadStatus {
    /** Book is only available on the LAN Calibre server. */
    NOT_DOWNLOADED,

    /** WorkManager is actively downloading the book file. */
    DOWNLOADING,

    /** Book has been downloaded to local storage and is available offline. */
    DOWNLOADED,

    /** The last download attempt failed (error details in a companion metadata column). */
    FAILED,
}
```

**LoadingState**:

```kotlin
// =============================================================================
// Module:  :core:model  (Layer 0 — pure Kotlin, zero Android imports)
// Package: dev.readflow.core.model
// =============================================================================

package dev.readflow.core.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface LoadingState {
    /** No operation in progress. */
    @Serializable
    data object Idle : LoadingState

    /** An async operation is in flight (e.g. opening a book). */
    @Serializable
    data object Loading : LoadingState

    /** The last async operation failed with a typed error. */
    @Serializable
    data class Error(val error: ReadflowError) : LoadingState

    /** The last async operation completed successfully. */
    @Serializable
    data object Loaded : LoadingState
}
```

**TransitionType** (SCREAMING_SNAKE_CASE per Root-cause Resolution #7):

```kotlin
// =============================================================================
// Module:  :core:model  (Layer 0 — pure Kotlin, zero Android imports)
// Package: dev.readflow.core.model
// =============================================================================

package dev.readflow.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TransitionType {
    SLIDE,
    CURL,
    FADE,
    NONE,
}
```

### 6.2 BookSource 接口

定义在 `:extensions:api`（Layer 1），纯 Kotlin，零 Android 依赖。统一的书源抽象 — 替代 v1 中 UI 层直接调用 `CalibreClient` 的模式。

```kotlin
// =============================================================================
// Module:  :extensions:api  (Layer 1 — pure Kotlin, zero Android deps)
// Package: dev.readflow.extensions.api
// =============================================================================

package dev.readflow.extensions.api

import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import java.io.File

interface BookSource {
    /** Stable source identifier: "calibre" | "opds" | "local". */
    val sourceId: String

    /** Human-readable display name. */
    val sourceName: String

    /** Full-text search across the source's catalog. */
    suspend fun search(
        query: String,
        offset: Int = 0,
        limit: Int = 100
    ): ReadflowResult<List<BookMeta>>

    /** Fetch metadata for a single book. */
    suspend fun getMetadata(bookId: String): ReadflowResult<BookMeta>

    /** Build the download URL for a book in a given format. */
    suspend fun getDownloadUrl(
        bookId: String,
        format: String
    ): ReadflowResult<String>

    /** Build the cover image URL for a book. */
    suspend fun getCoverUrl(bookId: String): ReadflowResult<String>

    /** Download the book file to local storage (Phase 1 via WorkManager). */
    suspend fun download(
        bookId: String,
        format: String
    ): ReadflowResult<java.io.File>

    /** Query local download status for a book. */
    suspend fun getDownloadStatus(bookId: String): DownloadStatus

    /** Check whether the source is reachable right now. */
    suspend fun isAvailable(): Boolean
}
```

实现类：
- `CalibreBookSource` — Calibre Content Server REST API
- `OpdsBookSource` — OPDS 1.2 / 2.0 feed 解析
- `LocalFileBookSource` — 设备文件系统 (`/sdcard/Books/*.epub`)
- `StubBookSource` — 离线测试源，硬编码数据

### 6.3 SyncBackend

同步后端抽象，定义在 `:core:model`（Layer 0）。

```kotlin
// =============================================================================
// Module:  :core:model  (Layer 0 — pure Kotlin, zero Android imports)
// Package: dev.readflow.core.model
// =============================================================================

package dev.readflow.core.model

interface SyncBackend {
    /** Stable identifier for this backend. */
    val backendId: String

    /** Whether the backend is currently reachable. */
    val isAvailable: Boolean

    /** Push reading progress to the sync backend. */
    suspend fun pushProgress(
        bookId: String,
        locator: Locator
    ): ReadflowResult<Unit>

    /** Pull reading progress from the sync backend (null if no remote progress). */
    suspend fun pullProgress(
        bookId: String
    ): ReadflowResult<Locator?>

    /** Push a bookmark to the sync backend. */
    suspend fun pushBookmark(
        bookId: String,
        bookmark: Bookmark
    ): ReadflowResult<Unit>

    /** Pull all bookmarks for a book from the sync backend. */
    suspend fun pullBookmarks(
        bookId: String
    ): ReadflowResult<List<Bookmark>>
}

class NoOpSyncBackend : SyncBackend {
    override val backendId: String = "noop"
    override val isAvailable: Boolean = false

    override suspend fun pushProgress(
        bookId: String,
        locator: Locator
    ): ReadflowResult<Unit> = ReadflowResult.Success(Unit)

    override suspend fun pullProgress(
        bookId: String
    ): ReadflowResult<Locator?> = ReadflowResult.Success(null)

    override suspend fun pushBookmark(
        bookId: String,
        bookmark: Bookmark
    ): ReadflowResult<Unit> = ReadflowResult.Success(Unit)

    override suspend fun pullBookmarks(
        bookId: String
    ): ReadflowResult<List<Bookmark>> = ReadflowResult.Success(emptyList())
}

@kotlinx.serialization.Serializable
data class Bookmark(
    val id: Long = 0,
    val bookId: String,
    val locator: Locator,
    val text: String,
    val createdAt: Long = 0,
)
```

同步策略：
- **Phase 1**：`NoOpSyncBackend` — 所有操作返回空成功，Settings 中「同步」区域灰显。
- **Phase 2**：主路径 `KorroSyncBackend`（REST API） + 兜底 `WebDavSyncBackend`。
- **冲突解决**：LWW（Last-Write-Wins），`updatedAt` 时间戳决胜；标注/书签 Union merge（不删除）。
- **离线优先**：进度/书签/标注一律先写 Room，UI 立即更新，后台同步。

### 6.4 Room 数据库 Schema

定义在 `:core:database`（Layer 1）。4 张表：

| 表名 | 关键列 | 说明 |
|------|--------|------|
| `books` | `id TEXT PRIMARY KEY`, `title TEXT`, `author TEXT`, `format TEXT`, `cover_url TEXT`, `download_status TEXT`, `local_file_path TEXT?`, `last_read_at INTEGER?` | 书籍元数据 + 下载状态 |
| `reading_progress` | `book_id TEXT`, `locator_json TEXT`, `progress_percent REAL`, `updated_at INTEGER` | 阅读进度（CFI / page / byteOffset JSON） |
| `annotations` | `id INTEGER PRIMARY KEY AUTOINCREMENT`, `book_id TEXT`, `page_index INTEGER`, `anchor_type TEXT?`, `anchor_json TEXT?`, `stroke_data BLOB?`, `selected_text TEXT?`, `note TEXT?`, `color INTEGER`, `created_at INTEGER` | 标注（高亮 + Ink 笔迹） |
| `bookmarks` | `id INTEGER PRIMARY KEY AUTOINCREMENT`, `book_id TEXT`, `locator_json TEXT`, `text TEXT`, `created_at INTEGER` | 书签 |

`InkAnchor` 序列化：Room 以 `anchor_type: String` + `anchor_json: String` 存储，`:ink` 模块的 `InkAnchorCodec` 负责序列化/反序列化。

### 6.5 离线缓存策略

- **智能 LRU 缓存**：最近读过的 5 本书自动缓存（可配置 `SettingsRepository.cacheLimit`）。用户打开一本书 → 后台 `WorkManager` 下载到 `context.filesDir/books/<bookId>.<format>`。缓存满时删除**最久未读**的书（LRU）。
- **手动下载**：书架每本书有「下载」按钮。下载进度通过 `WorkInfo.State` → `DownloadStatus` Flow 暴露给 UI。可暂停/恢复/取消。
- **离线模式**：用户离开 LAN → 书架自动筛选 `downloadStatus = DOWNLOADED`。状态栏提示「离线模式 — 仅显示已下载书籍」。
- **存储预算**：默认上限 500MB（可配置）。单本书上限 50MB EPUB / 200MB PDF。

---

## 七、扩展系统

### 7.1 Extension SPI

定义在 `:extensions:api`（Layer 1），纯 Kotlin，零 Android 依赖。通过 `java.util.ServiceLoader` 发现，零额外依赖。

```kotlin
// =============================================================================
// Module:  :extensions:api  (Layer 1 — pure Kotlin, zero Android deps)
// Package: dev.readflow.extensions.api
// =============================================================================

package dev.readflow.extensions.api

import dev.readflow.core.model.ReaderState
import dev.readflow.event.ReaderEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

// ── Extension meta ────────────────────────────────────────────────────────────

data class ExtensionMeta(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String = "",
    val minAppVersion: String? = null,
)

// ── Extension scope ───────────────────────────────────────────────────────────

/** Coroutine scope that lives for the duration of onAttach → onDetach. */
interface ExtensionScope : CoroutineScope

// ── Menu item ─────────────────────────────────────────────────────────────────

data class ExtensionMenuItem(
    val id: String,
    val label: String,
    val icon: String? = null,
    val showAsAction: Boolean = false,
    val enabled: Boolean = true,
)

// ── Reader hook ───────────────────────────────────────────────────────────────

interface ReaderHook {
    suspend fun onBeforePageRender(pageIndex: Int): Boolean = true
    suspend fun onAfterPageRender(pageIndex: Int) {}
    suspend fun onBookmarkAdd(text: String): String? = text
    suspend fun onBookmarkRemove(pageIndex: Int) {}
    suspend fun onBeforeBookClose() {}
}

// ── Extension context (sandbox) ───────────────────────────────────────────────

interface ExtensionContext {
    /** Snapshot + flow of the current Reader MVI state. */
    val readerState: StateFlow<ReaderState>

    /** Shared event bus for observing and (rarely) emitting reader events. */
    val eventBus: ReaderEventBus

    /** Extension-private key-value store backed by DataStore. */
    val settings: ExtensionSettings

    /** Register a toolbar / overflow menu item visible in the reader. */
    fun registerMenuItem(item: ExtensionMenuItem)

    /** Register lifecycle hooks that intercept reader events. */
    fun registerReaderHook(hook: ReaderHook)

    /** Remove all registrations made by this extension. Called automatically on detach. */
    fun unregisterAll()
}

// ── Extension SPI ─────────────────────────────────────────────────────────────

interface Extension {
    val meta: ExtensionMeta

    /**
     * Extension is being activated.
     * - [scope] is a child of the application SupervisorJob; cancelled after onDetach() returns.
     * - [context] is the sandboxed interface to the reader.
     */
    suspend fun onAttach(scope: ExtensionScope, context: ExtensionContext)

    /** Extension is being deactivated. Release all resources. */
    suspend fun onDetach()
}
```

### 7.2 ServiceLoader 发现

每个扩展模块在 classpath 放置 provider-config 文件：
```
META-INF/services/dev.readflow.extensions.api.Extension
```

文件内容为扩展实现类的全限定名，每行一个。

### 7.3 Extension 生命周期

```
ExtensionLoader.discover()        // 读取所有 classpath 扩展的 meta
  → 用户在 Settings 中启用扩展
    → ExtensionRegistry.onAttach()
      → Extension.onAttach(scope, context)
        → 扩展可以注册 menu items + reader hooks
  → 用户在 Settings 中禁用扩展
    → ExtensionRegistry.onDetach()
      → Extension.onDetach()
        → scope 取消 → 所有协程作业终止
        → ExtensionRegistry 自动调用 unregisterAll()
```

### 7.4 ReaderEventBus

定义在 `:extensions:api`（Layer 1），纯 Kotlin。

```kotlin
// =============================================================================
// Module:  :extensions:api  (Layer 1 — pure Kotlin, zero Android deps)
// Package: dev.readflow.event
// =============================================================================

package dev.readflow.event

sealed class ReaderEvent {

    /** Engine has been created and is ready to open a book. */
    data object EngineInitialized : ReaderEvent()

    /** Engine has been shut down. */
    data object EngineShutdown : ReaderEvent()

    /** A book has been successfully opened. */
    data class BookOpened(
        val bookId: String,
        val format: String,
        val title: String,
        val totalPages: Int,
    ) : ReaderEvent()

    /** The current book has been closed. */
    data class BookClosed(val bookId: String) : ReaderEvent()

    /** The reader has navigated to a new page. */
    data class PageChanged(
        val bookId: String,
        val pageIndex: Int,
        val totalPages: Int,
        val positionCfi: String,
        val progressPercent: Float,
    ) : ReaderEvent()

    /** A bookmark has been added. */
    data class BookmarkAdded(
        val bookId: String,
        val pageIndex: Int,
        val positionCfi: String,
        val text: String,
    ) : ReaderEvent()

    /** A bookmark has been removed. */
    data class BookmarkRemoved(
        val bookId: String,
        val pageIndex: Int,
    ) : ReaderEvent()

    /** A text annotation (highlight) has been added. */
    data class AnnotationAdded(
        val bookId: String,
        val pageIndex: Int,
        val selectedText: String,
        val note: String? = null,
        val color: Int = 0xFFFFEB3B.toInt(),
    ) : ReaderEvent()

    /** A text annotation has been removed. */
    data class AnnotationRemoved(
        val bookId: String,
        val annotationId: Long,
    ) : ReaderEvent()

    /** An ink stroke has been committed to storage. */
    data class InkStrokeCommitted(
        val bookId: String,
        val pageIndex: Int,
        val strokeCount: Int,
    ) : ReaderEvent()

    /** The reading font size has changed. */
    data class FontSizeChanged(
        val bookId: String,
        val newSizeSp: Int,
    ) : ReaderEvent()

    /** The reading theme has changed. */
    data class ThemeChanged(val themeId: String) : ReaderEvent()

    /** Reading progress has been persisted (triggers background sync). */
    data class BookProgressSaved(
        val bookId: String,
        val pageIndex: Int,
        val progressPercent: Float,
        val timestamp: Long,
    ) : ReaderEvent()

    /** TTS has advanced to a specific word (for word-level highlighting). */
    data class TtsWordHighlighted(
        val bookId: String,
        val wordIndex: Int,
    ) : ReaderEvent()
}
```

扩展通过 `SharedFlow<ReaderEvent>` 订阅并响应自己关心的事件子集。Event 是 fire-and-forget，无回复通道。

**注意**：此处 `ReaderEvent` 中使用 `String` 类型的 `bookId`，与 `BookSource` 的 Addendum 版本（`bookId: String`）保持一致。

### 7.5 ExtensionSettings

定义在 `:extensions:api`（Layer 1），持久化于 `DataStore`。

```kotlin
// =============================================================================
// Module:  :extensions:api  (Layer 1 — pure Kotlin, zero Android deps)
// Package: dev.readflow.extensions.api
// =============================================================================

package dev.readflow.extensions.api

import kotlinx.serialization.Serializable

@Serializable
data class ExtensionSettings(
    /** Set of extension ids that are currently enabled. */
    val enabledExtensions: Set<String> = emptySet(),

    /** TTS (text-to-speech) extension configuration. */
    val tts: TtsConfig = TtsConfig(),

    /** Reading statistics extension configuration. */
    val stats: StatsConfig = StatsConfig(),
)

@Serializable
data class TtsConfig(
    val enabled: Boolean = false,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val language: String = "zh-CN",
)

@Serializable
data class StatsConfig(
    val enabled: Boolean = false,
    val dailyGoalMinutes: Int = 30,
)
```

### 7.6 ExtensionContext 沙箱

扩展通过 `ExtensionContext` 沙箱访问 reader 状态，永不持有 core internals 的直接引用。`ReaderState` 中包含零 View 引用（Root-cause Resolution #12），因此沙箱是完整的 — 扩展无法获取文档 View handle。

---

## 八、依赖注入

### 8.1 Koin 模块结构

每个模块提供其自己的 Koin module。`:app` 的 `ReadflowApplication` 启动时加载所有 modules。

```kotlin
// ReadflowApplication.kt (组装点)
class ReadflowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@ReadflowApplication)
            modules(
                // Layer 1
                calibreModule,
                databaseModule,
                prefsModule,
                extensionsApiModule,
                // Layer 2
                renderApiModule,
                // Layer 3
                epubEngineModule,
                pdfEngineModule,
                txtEngineModule,
                muPdfEngineModule,
                markdownEngineModule,
                animateModule,
                // Layer 4
                inkModule,
                // Layer 5
                uiModule,
                // Layer 6
                libraryModule,
                readerModule,
                settingsModule,
                // Layer 7
                ttsExtensionModule,
                statsExtensionModule,
                opdsExtensionModule,
            )
        }
    }
}
```

### 8.2 关键绑定约定

| 类型 | Scope | 绑定方式 |
|------|-------|---------|
| `ReaderEngine` 各实现 | `single` | `single<ReaderEngine> { EpubWebViewEngine() } bind ReaderEngine::class` |
| `ReaderEngineRegistry` | `single` | `single { ReaderEngineRegistry(getAll<ReaderEngine>().toSet()) }` |
| `BookSource` 各实现 | `single` | 同引擎模式 |
| `SyncBackend` | `single` | Phase 1: `single<SyncBackend> { NoOpSyncBackend() }` |
| `ReaderViewModel` | `scope` | Scope 绑定到 Reader Screen 生命周期 |
| `LibraryViewModel` | `scope` | Scope 绑定到 Library Screen 生命周期 |
| Room DAOs | `single` | Room database 创建时提供 |
| `Extension` 各实现 | `single` | ServiceLoader 发现后手动注册 |

### 8.3 功能模块与引擎的隔离

`:features:reader` **不**持有任何 `ReaderEngine` 实现的引用。引擎通过 `ReaderEngineRegistry` 解析后，由 `:app` 注入到 `ReaderViewModel` 的构造函数。

```kotlin
// :features:reader 中的 ViewModel
class ReaderViewModel(
    private val engineRegistry: ReaderEngineRegistry,  // 接口，定义在 :render:api
    private val syncManager: SyncManager,
    private val eventBus: ReaderEventBus,
) : ViewModel() {
    // engineRegistry.resolve(uri) → ReaderEngine 接口
    // 绝不 import EpubWebViewEngine 等具体类型
}
```

---

## 九、Gradle 基础设施

### 9.1 Version Catalog (`gradle/libs.versions.toml`)

```toml
[versions]
agp = "8.8.2"
kotlin = "2.1.10"
ksp = "2.1.10-1.0.31"
compose-bom = "2026.05.01"
room = "2.7.1"
datastore = "1.1.4"
paging = "3.3.6"
koin = "4.0.2"
ktor = "3.1.2"
kotlinx-serialization = "1.7.3"
coroutines = "1.10.1"
coil = "3.1.0"
mupdf = "1.27.0"
markwon = "4.6.2"
ink = "1.0.0-beta02"
junit5 = "5.11.4"
mockk = "1.13.16"

[libraries]
# ... (完整定义省略，按需从 catalog 引用)

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

### 9.2 Convention Plugins（4 个）

位于 `build-logic/convention/src/main/kotlin/`。

| Plugin | 施加模块 | 规则 |
|--------|---------|------|
| `ReadflowAndroidLibraryPlugin` | `:core:calibre`、`:core:database`、`:core:prefs`、`:extensions:api` | 基类：`com.android.library` + `org.jetbrains.kotlin.android`。`compileSdk=35, minSdk=26, targetSdk=35, jvmTarget=17`。 |
| `ReadflowComposePlugin` | `:core:ui`、三个 feature 模块 | 继承 `AndroidLibraryPlugin` + 添加 `kotlin-compose` + `kotlin-serialization` + Compose BOM + 测试依赖。 |
| `ReadflowFeaturePlugin` | `:features:library`、`:features:reader`、`:features:settings` | 继承 `ComposePlugin` + 添加 `lifecycle-viewmodel-compose` + `navigation-compose`。 |
| `ReadflowRenderPlugin` | `:render:*`（全部 7 个） | 继承 `AndroidLibraryPlugin`（**不应用 Compose plugin** — 引擎产出 View/Bitmap）。添加 `coroutines-android` + `kotlinx-serialization`。 |

### 9.3 settings.gradle.kts

```kotlin
rootProject.name = "readflow"

includeBuild("build-logic")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Layer 0
include(":core:model")

// Layer 1
include(":core:calibre")
include(":core:database")
include(":core:prefs")
include(":extensions:api")

// Layer 2
include(":render:api")

// Layer 3
include(":render:epub")
include(":render:pdf")
include(":render:txt")
include(":render:mupdf")
include(":render:md")
include(":render:animate")

// Layer 4
include(":ink")

// Layer 5
include(":core:ui")

// Layer 6
include(":features:library")
include(":features:reader")
include(":features:settings")

// Layer 7
include(":extensions:tts")
include(":extensions:stats")
include(":extensions:opds")

// Layer 8
include(":app")
```

### 9.4 `:core:model` 特殊配置

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
```

纯 JVM library，零 Android 依赖。未来可改为 KMP `commonMain` 零成本迁移。

---

## 十、迁移路径

### Phase 1：基础模块搭建（Week 1-2，约 7-8 天）

**产出模块**：`:core:model`、`:core:calibre`、`:core:prefs`、`:extensions:api`、`:app`（共 5 个模块初始搭建）。

| 步骤 | 操作 |
|------|------|
| 1 | 创建 `gradle/libs.versions.toml`（全部 version + library + bundle + plugin 声明） |
| 2 | 创建 `build-logic/` + 4 个 convention plugins |
| 3 | 创建 `settings.gradle.kts`（include 全部 21 个子模块） |
| 4 | 创建 `:core:model`，定义 `BookMeta`、`BookFormat`、`Locator`、`ReaderState`（不含 View）、`ReadflowError`、`InkAnchor`、`DownloadStatus`、`SyncBackend`、`Bookmark`、`LoadingState`、`TransitionType`、`Offset`、`ThemeMode` |
| 5 | 创建 `:core:calibre`，迁移 v1 `CalibreClient.kt` → `CalibreClient.kt` + `CalibreRepository.kt` |
| 6 | 创建 `:core:prefs` + `SettingsRepository`（DataStore） |
| 7 | 创建 `:extensions:api` + `BookSource` 接口（Addendum 版本：`ReadflowResult<T, ReadflowError>`，String bookId，全部 suspend）+ `Extension` SPI + `ReaderEventBus` + `ReaderEvent` sealed hierarchy |
| 8 | 更新 `:app` 依赖新模块（保留单 Activity 空壳） |

**Phase 1 结束状态**：`./gradlew assembleDebug` 成功编译，功能与迁移前完全一致。

### Phase 2：渲染引擎 + 阅读器（Week 3-4）

| 步骤 | 操作 |
|------|------|
| 9 | 创建 `:render:api` + `ReaderEngine` 接口 + `ReaderEngineRegistry` |
| 10 | 创建 `:render:epub` + `EpubWebViewEngine`（WebView + epub-ts） |
| 11 | 创建 `:render:pdf` + `PdfRendererEngine` |
| 12 | 创建 `:render:txt` + `TxtVirtualPagerEngine`（RecyclerView + Paging3） |
| 13 | 创建 `:render:mupdf` + `MuPdfEngine`（DOCX + CBZ） |
| 14 | 创建 `:render:md` + `MarkwonEngine` |
| 15 | 创建 `:render:animate` + `SlideFadeTransformer` + `CurlPageTransformer` |
| 16 | 创建 `:core:database` + Room schema（4 tables: `books`, `reading_progress`, `annotations`, `bookmarks`） |
| 17 | 搭建 `:features:library` + `LibraryScreen` + `LibraryViewModel` |
| 18 | 搭建 `:features:reader` + `ReaderScreen` + `ReaderViewModel`（MVI）+ `ReaderRootLayout`（混合 View 触摸路由） |
| 19 | 搭建 `:features:settings` + `SettingsScreen` + `SettingsViewModel` |
| 20 | 创建 `:app` 的 `MainActivity`（Navigation host）+ `ReadflowApplication`（Koin 启动） |

**Phase 2 结束状态**：MVP — 用户可浏览 Calibre 书库、打开 EPUB/PDF/TXT/DOCX/CBZ/MD、翻页阅读、调整字号/主题。

### Phase 3：扩展系统 + Ink（Week 5-6）

| 步骤 | 操作 |
|------|------|
| 21 | 创建 `:extensions:tts` + `TtsExtension`（Android TTS） |
| 22 | 创建 `:extensions:stats` + `ReadingStatsExtension` |
| 23 | 创建 `:extensions:opds` + `OpdsBookSource` |
| 24 | 实现 `:ink` 模块 — `CanvasView` + `InProgressStrokesView`（androidx.ink）+ 触摸路由集成 + `InkToolbar` composable |
| 25 | `:app` 整合所有 DI module + extension lifecycle + ink toolbar |

**Phase 3 结束状态**：完整功能 — 对标架构文档中的全部设计。

### 代码移动映射（v1 → v2）

| v1 文件 | v2 文件 | 变化 |
|---------|---------|------|
| `CalibreClient.kt` (~150 lines) | `:core:calibre/.../CalibreClient.kt` + `:core:calibre/.../CalibreRepository.kt` | 提取类型到 `:core:model`，接口化为 `BookSource` |
| `CalibreClient.kt` 中的 data class | `:core:model/.../BookMeta.kt`、`SearchResult.kt` | 加 `seriesIndex`、`cover`、`lastModified` 字段 |
| `ReadflowApp.kt` | `:core:ui/.../ReadflowTheme.kt` | 主题提取为共享模块 |
| `MainActivity.kt` | `:app/.../MainActivity.kt` + `:app/.../ReadflowApplication.kt` | Koin 引导 + Navigation host |
| （无） | `:features:reader/.../ReaderRootLayout.kt` | 新建：混合 View 触摸路由 |
| （无） | `:features:reader/.../ReaderViewModel.kt` | 新建：MVI 单向数据流 |
| `build.gradle.kts` (单体) | 多个 `build.gradle.kts` + `libs.versions.toml` | 单模块 → 多模块（21） |

---

## 十一、架构裁决记录

### 11.1 12 项 Root-cause Resolutions

| # | 裁决 | 详情 |
|---|------|------|
| **R1** | `ReaderState` 不含 View 引用 | 原 v2 设计将文档 View 放在 `ReaderState` 中，导致 Compose snapshot 系统不可用、跨线程传递不安全。裁决：`ReaderState` 移至 `:core:model`（Layer 0，纯数据），`ReaderViewModel` 私有持有文档 View，不通过 State 暴露。 |
| **R2** | `Locator` 唯一定义在 `:core:model` | 原 `render:api` 和 `core:model` 各自定义了 `Locator` — 重复定义，违反单一事实源原则。裁决：`Locator` 唯一定义在 `:core:model`（Layer 0），`render:api` 通过 import 引用。 |
| **R3** | `BookSource` 接口使用 Addendum 版本 | 原版本使用 `Int` bookId、裸类型返回。裁决：统一使用 `String bookId`（与三端共享 Schema 一致）、`ReadflowResult<T, ReadflowError>` 返回类型、全部 `suspend` 函数、`getDownloadStatus()` 替代 `isAvailable()`。 |
| **R4** | 21 模块，非 12 模块 | 原 v2 设计了 12 个模块。裁决：拆分为 21 个模块以提升编译增量性和测试隔离度（`render:pdf`/`render:txt`/`render:mupdf`/`render:md` 独立，`extensions:opds` 独立，`core:prefs`/`core:database` 独立）。 |
| **R5** | `InkAnchor` 定义在 `:core:model`，非 `:ink` | `InkAnchor` 是纯数据模型（sealed interface + data class），零 Android 依赖。放在 `:core:model` 使 Room 可通过 `anchorType: String` + `anchorJson: String` 间接引用，不违反 Layer 0 纯度。 |
| **R6** | 使用 ViewPager2 替代自研 AnimationEngine | 自研 AnimationEngine（vsync loop + Bitmap.createBitmap 每帧分配）在低端设备上有 GC 压力，且固定时长补间不跟手。裁决：使用 ViewPager2 + PageTransformer。 |
| **R7** | `TransitionType` 统一 SCREAMING_SNAKE_CASE | 兼容 kotlinx.serialization 默认枚举序列化行为，避免 JSON 反序列化中的大小写问题。 |
| **R8** | EPUB 渲染引擎采用 epub-ts，非 epubjs | `@likecoin/epub-ts` 是 epubjs v0.3.93 的 TypeScript 重写（BSD-2-Clause 许可），提供更好的类型安全和维护性。本架构文档中所有 EPUB 渲染引用均为 epub-ts。 |
| **R9** | `render:api` 是接口层，不定义数据类型 | `render:api` 仅定义 `ReaderEngine` 接口和 `ReaderEngineRegistry`，所有数据类型（`Locator`、`BookFormat`、`ReadingMode`）来自 `:core:model` import。 |
| **R10** | `ReaderEngineRegistry` 支持用户 override | 对标 KOReader 的 per-type 用户 engine 覆盖功能。`setUserOverride(format, engineId)` / `clearUserOverride(format)` / `getUserOverride(format)` 支持用户为特定格式选择非默认引擎。 |
| **R11** | 新增视图生命周期回调 | `onViewAttached` / `onViewDetached` / `saveState` / `restoreState` 使引擎在配置变更和进程死亡时存活。 |
| **R12** | ExtensionContext 沙箱完整性 | `ReaderState` 中无 View 引用 → 扩展通过 `ExtensionContext` 无法获取文档 View handle → 沙箱完整，扩展只能通过 `ExtensionContext` 提供的 API 交互。 |

### 11.2 废弃文档

以下文档自本文档生效起废弃：

- `docs/Platform-Android.md` — 被本文档完全替代
- `docs/Android-v2-architecture.md` — 被本文档完全替代
- v1 代码中的 `AnimationEngine.kt` — 被 ViewPager2 + PageTransformer 替代

### 11.3 关键技术选型

| 决策点 | 选择 | 排除 |
|--------|------|------|
| EPUB 渲染内核 | `@likecoin/epub-ts` (epubjs v0.3.93 TS 重写) | 裸 epubjs、foliate-js（仅列为未来替代） |
| PDF 渲染 | Android `PdfRenderer` (API 21+) | MuPDF for PDF |
| DOCX / CBZ 渲染 | MuPDF JNI (`com.artifex.mupdf:fitz:1.27.0`) | 自研解析器 |
| MD 渲染 | Markwon Spannables | WebView + HTML 中间层 |
| TXT 渲染 | `RecyclerView` + Paging3 + `FileChannel` | WebView |
| 翻页动效 | ViewPager2.PageTransformer | 自研 AnimationEngine（已废弃） |
| DI 框架 | Koin 4.0.2 | Hilt / Dagger（过度工程） |
| HTTP 客户端 | Ktor 3.1.2 | OkHttp（Ktor 提供更好的 KMP 兼容性储备） |
| 序列化 | kotlinx.serialization 1.7.3 | Gson / Moshi |
| 同步后端 (Phase 1) | NoOpSyncBackend | 无（Phase 1 不实现同步） |

### 11.4 KMP 策略

**Phase 1 不做 KMP。** 当前 HarmonyOS 端使用 ArkTS（非 Kotlin），与 Android 端语言不同。

预留结构：
- `:core:model` 已设计为纯 Kotlin JVM library（`kotlin("jvm")`），无 Android import。未来可改为 KMP `commonMain` 零成本迁移。
- `shared/api/calibre-contract.ts` 通过 JSON Schema codegen 生成 Kotlin + TypeScript 类型。HarmonyOS 端可从 JSON Schema 生成 ArkTS 类型。
- 共享层策略：**类型契约共享**（JSON Schema），非代码共享（KMP）。三端各自实现 UI 层。

未来评估点（不早于 2027）：
- Google 官方 Kotlin/KMP for Android + HarmonyOS 支持成熟度
- `compose-multiplatform` 对 HarmonyOS 的支持

### 11.5 安全策略

- **网络安全**：`network_security_config.xml` 仅允许 `192.168.0.0/16` cleartext HTTP（LAN Calibre）。其他所有网络请求强制 HTTPS。
- **WebView 安全（EPUB 引擎）**：JavaScript 启用（epub-ts 必需），**禁止** `file://` 访问（`setAllowFileAccess(false)`）。CSP 策略：`default-src 'self'; script-src 'self' 'unsafe-inline'; object-src 'none'`。不允许导航到 WebView 外的 URL（`setWebViewClient` 拦截）。
- **凭据存储**：Calibre 用户名/密码使用 `EncryptedSharedPreferences`（AndroidX Security）存储。同步后端 API key（Phase 2）同。

### 11.6 无障碍策略

| 需求 | 实现 |
|------|------|
| TalkBack | 所有交互元素（按钮、书架条目、翻页区域）设 `contentDescription` |
| 字体缩放 | 尊重系统字体缩放设置（`Configuration.fontScale`），独立于 EPUB 内部字号 |
| 高对比度 | 提供「高对比度」主题（黑白），通过 `ThemeMode.HIGH_CONTRAST` 切换 |
| 键盘导航 | `DirectionalNavigationAdapter` — 空格/方向键翻页 |
| TTS | Phase 2 `:extensions:tts` 模块，使用 Android TTS framework |

### 11.7 大屏/折叠屏策略

| 场景 | 行为 |
|------|------|
| 平板横屏 (sw >= 600dp) | 双页模式（`ViewPager2` 显示 2 页，翻页动效一次翻 2 页） |
| 折叠屏展开/折叠 | `WindowSizeClass` 检测 → 切换单/双页模式。不丢失阅读位置 |
| 多窗口/分屏 | 竖屏：单页。横屏：双页。`onConfigurationChanged` 自适应 |
| 拖拽 (Drag & Drop) | 支持从文件管理器拖拽 `.epub`/`.pdf` 到 LinReads 直接打开 |

### 11.8 性能预算

| 指标 | 目标 | 测量方式 |
|------|------|---------|
| 冷启动 | < 2s (to first paint) | Android Studio Profiler / `systrace` |
| EPUB 打开 (1MB) | < 1s (to first page) | 手动计时 |
| PDF 打开 (10MB) | < 2s (to first page) | 手动计时 |
| 翻页延迟 | < 50ms (frame-paced) | `currentLocation()` 计时 |
| 内存峰值 (EPUB) | < 100MB | `dumpsys meminfo` |
| 内存峰值 (PDF + ink) | < 200MB | `dumpsys meminfo` |
| MuPDF .so | ~15MB | APK Analyzer |
| 总 APK 大小 | < 25MB | `./gradlew assembleDebug` + APK Analyzer |

### 11.9 测试策略

| 层 | 测试类型 | 框架 | 目标 |
|----|---------|------|------|
| `:core:model` | Unit | JUnit5 | 所有数据类、序列化、枚举 |
| `:core:calibre` | Unit + Integration | JUnit5 + MockK + Ktor MockEngine | Repository 方法、错误路径 |
| `:core:database` | Unit + Instrumentation | JUnit5 + Room in-memory + Turbine | DAO 查询、Flow 发射 |
| `:render:api` | Unit | JUnit5 | EngineRegistry 选择逻辑 |
| `:render:*` | Instrumentation | Compose Testing + AndroidJUnit4 | 引擎 createView、goTo |
| `:features:*` | Unit (ViewModel) + UI | JUnit5 + MockK + Turbine + Compose Testing | MVI 状态转换、UI 交互 |
| `:ink` | Instrumentation | AndroidJUnit4 | 触控路由、笔迹坐标变换 |

Mock 策略：**Prefer fakes over mocks**。
- `FakeCalibreBookSource` — 返回预置 JSON
- `FakeReaderEngine` — 返回固定 View
- `NoOpSyncBackend` — 空操作同步（也是 Phase 1 生产实现）

---

> **文档维护**：本架构文档为 LinReads Android v3 的唯一权威来源。所有模块、类型、接口的实际实现必须以本文档为准。如发现代码与文档不一致，以文档为准并向架构负责人报告。
>
> **关联文档**：
> - `shared/api/calibre-contract.ts` — 三端共享 Calibre API 类型定义
> - `.claude/skills/readflow-epub/SKILL.md` — EPUB 渲染已知陷阱（开发前必读）
> - `.claude/skills/readflow-sync/SKILL.md` — 进度同步策略设计
> - `.claude/skills/readflow-dev/SKILL.md` — 三端开发规范和命令索引
