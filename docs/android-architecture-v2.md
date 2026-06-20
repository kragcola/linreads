# Android 端架构设计 v2

> 版本：2026-06-18
> 状态：架构定稿，待实现
> 原则：多模块分层 · View 驱动渲染 · 开放可扩展 · 离线优先

---

## 目录

1. [设计原则](#1-设计原则)
2. [模块地图](#2-模块地图)
3. [视图架构](#3-视图架构)
4. [渲染引擎契约](#4-渲染引擎契约)
5. [Ink / 手写笔集成](#5-ink--手写笔集成)
6. [数据层](#6-数据层)
7. [扩展系统](#7-扩展系统)
8. [依赖注入](#8-依赖注入)
9. [Gradle 基础设施](#9-gradle-基础设施)
10. [迁移路径](#10-迁移路径)
11. [审计消解清单](#11-审计消解清单)

---

## 1. 设计原则

v2 的五条不可妥协原则，每一条都对应 v1 发现的架构缺陷（见 `docs/android-architecture.md` 及审计报告）。

### 原则 1：格式引擎可插拔 — 开放而非封闭

v1 只有 MuPdfEngine 一个实现，需改源码才能加格式。v2 用 `ReaderEngineRegistry`（权重排序 + `supports()` 检测），新增格式 = 新增 Gradle 模块 + 实现 `ReaderEngine`，**不改 core 代码**。此原则直接回应 Moon+ Reader `BaseEBook` 闭合层级的问题（详见 `docs/research/moonreader-architecture-review.md` S1/M1）。

### 原则 2：混合视图 — Compose 做 Chrome，View 做文档

v1 `renderPage() → Bitmap → Image(bitmap.asImageBitmap())` 的路径在每次翻页时多做一次 Bitmap → ImageBitmap 拷贝，且 Compose `HorizontalPager` 嵌 `AndroidView` 会引入额外重组边界。v2 使用 `FrameLayout` 根布局，三层 z-order：文档 View（Layer 0）→ Ink Canvas（Layer 1-2）→ ComposeView（Layer 3，仅 UI chrome）。触摸路由在 View 层面完成，不走 Compose 重组。

### 原则 3：MVI 单向数据流 — ViewModel 是唯一状态源

```kotlin
sealed interface ReaderIntent { … }    // 用户操作
data class ReaderState(…)              // 唯一状态
```

`ReaderViewModel` 持有格式引擎引用，处理 `ReaderIntent` 并产出新的 `ReaderState`。扩展通过 `ReaderEventBus` 观察事件，通过 `ReaderHook` 拦截生命周期，永远不直接修改 ViewModel 状态。

### 原则 4：离线优先 — 本地先写，后台同步

进度、书签、标注一律先写 Room，再通过 `ReaderEventBus.BookProgressSaved` 触发后台同步。同步策略采用 LWW（Last-Write-Wins）+ user-id 分区，详见 `.claude/skills/linreads-sync/SKILL.md`。

### 原则 5：依赖倒置 — 功能模块只依赖接口

`features:reader` 依赖 `render:api`（`ReaderEngine` 接口），不依赖 `render:epub` 或 `render:pdf`（具体实现）。具体引擎由 `:app` 在 DI 层注入。此规则由 convention plugin + CI lint 强制执行。

---

## 2. 模块地图

### 2.1 完整模块树（19 个模块）

```
android/
├── app/                            # :app (com.android.application)
│
├── core/
│   ├── model/                      # :core:model      纯 Kotlin，零 Android import
│   ├── calibre/                    # :core:calibre    数据层，无 UI
│   ├── database/                   # :core:database   Room，无 UI
│   ├── prefs/                      # :core:prefs      DataStore，无 UI
│   └── ui/                         # :core:ui         Compose 主题 + 共享组件
│
├── render/
│   ├── api/                        # :render:api      ReaderEngine 接口层
│   ├── epub/                       # :render:epub     WebView + epub-ts
│   ├── pdf/                        # :render:pdf      PdfRenderer
│   ├── txt/                        # :render:txt      RecyclerView 虚拟分页
│   └── animate/                    # :render:animate  ViewPager2 + PageTransformer
│
├── ink/                            # :ink             androidx.ink 覆盖层
│
├── features/
│   ├── library/                    # :features:library
│   ├── reader/                     # :features:reader
│   └── settings/                   # :features:settings
│
└── extensions/
    ├── api/                        # :extensions:api   Extension SPI + BookSource
    ├── tts/                        # :extensions:tts
    ├── stats/                      # :extensions:stats
    └── opds/                       # :extensions:opds
```

### 2.2 模块职责与依赖规则

#### Layer 0：纯 Kotlin（`kotlin("jvm")`，零 Android framework import）

| 模块 | 职责 | 依赖 |
|------|------|------|
| `:core:model` | 数据类型：`Book`、`BookMeta`、`BookFormat`、`Locator`、`CalibreConfig`、`ThemeMode`、`TransitionType`。使用 `kotlinx.serialization` 标注 JSON 类型。构建为纯 JVM library。 | **无** |

```kotlin
// core/model/src/main/kotlin/dev/readflow/core/model/BookFormat.kt
package dev.readflow.core.model

@Serializable
enum class BookFormat(val extension: String, val mimeType: String) {
    EPUB("epub", "application/epub+zip"),
    PDF("pdf", "application/pdf"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    CBZ("cbz", "application/vnd.comicbook+zip"),
    TXT("txt", "text/plain"),
    MD("md", "text/markdown"),
    UNKNOWN("", "application/octet-stream");

    companion object {
        fun fromExtension(ext: String): BookFormat =
            entries.find { it.extension.equals(ext, ignoreCase = true) } ?: UNKNOWN
    }
}
```

#### Layer 1：Android 数据（`android-library`，可用 `android.*`，禁用 Compose）

| 模块 | 职责 | 依赖 |
|------|------|------|
| `:core:calibre` | `CalibreClient`（Ktor HTTP）+ `CalibreRepository`。翻译 Calibre REST JSON → `core:model` 类型。构造时接受 `baseUrl` / credentials，绝不硬编码。 | `:core:model` |
| `:core:database` | Room 数据库 — `BookEntity`、`ReadingProgressEntity`、`AnnotationEntity`、`BookmarkEntity`。DAO：`BookDao`、`ProgressDao`、`AnnotationDao`、`BookmarkDao`。使用 KSP annotation processor。 | `:core:model` |
| `:core:prefs` | DataStore Preferences — `SettingsRepository` 暴露 `Flow<AppSettings>`。存储键：`calibreBaseUrl`、`calibreUsername`、`calibrePassword`（加密）、`theme`、`fontSize`、`lineHeight`、`fontFamily`、`transitionType`、`tapZoneConfig`。无 Compose。 | `:core:model` |
| `:extensions:api` | Extension SPI — `BookSource` 接口（`search` / `getMetadata` / `getDownloadUrl`）、`Extension` SPI、`ReaderEvent` sealed hierarchy、`ReaderHook` 接口。纯 Kotlin 接口，零 Android deps。 | `:core:model` |

#### Layer 2：渲染抽象（`android-library`，允许 `android.graphics.Bitmap`，禁用 Compose）

| 模块 | 职责 | 依赖 |
|------|------|------|
| `:render:api` | `ReaderEngine` 接口、`PageContent`、`ReadingMode`、`ReaderEngineRegistry`。Locator 从 `:core:model` import 使用。只引入 `android.graphics.Bitmap` 和 `android.view.View`，无其他 Android 耦合。 | `:core:model` |

#### Layer 3：渲染实现（`android-library`，产出 View，禁用 Compose）

| 模块 | 职责 | 依赖 |
|------|------|------|
| `:render:epub` | EPUB 渲染 via WebView + **epub-ts** (`@likecoin/epub-ts`, BSD-2-Clause, epubjs v0.3.93 的 TypeScript 重写，改一行 import 即可迁移)。实现 `ReaderEngine`。本地 HTTP server（nanohttpd）提供解压内容，CFI 导航，CSS 注入控制字号/主题，JavaScript bridge 回报位置。未来可评估切换 foliate-js（覆盖 MOBI/FB2/CBZ 格式）或 Readium Kotlin Toolkit（原生 Fragment）。 | `:core:model` + `:render:api` |
| `:render:pdf` | PDF 渲染 via Android `PdfRenderer`（minSdk=26，系统 API）。实现 `ReaderEngine`。逐页 `Bitmap`，LRU 缓存 3 页，懒加载。 | `:core:model` + `:render:api` |
| `:render:txt` | TXT 渲染 via `RecyclerView` + `TxtVirtualPager` + Paging3。实现 `ReaderEngine`。`FileChannel` 64KB 块扫描，byteOffset 分页，章节边界检测（空行≥2 / 章节正则 / 2000字符阈值）。 | `:core:model` + `:render:api` |
| `:render:animate` | `CurlPageTransformer`（ViewPager2.PageTransformer）：翻页动效。非独立引擎——提供 `ViewPager2.PageTransformer` 实现给 reader 使用。 | `:core:model` + `:render:api` |

**关键隔离规则**：render 模块**不得**依赖 `:core:ui`、`:core:calibre`、`:core:database`、`:core:prefs`、`:ink`、任何 feature、任何 extension。引擎可替换是核心架构目标。

#### Layer 4：Ink（`android-library`，View 系统，无 Compose）

| 模块 | 职责 | 依赖 |
|------|------|------|
| `:ink` | `InkOverlay`（`androidx.ink` View 层处理自由笔迹）。`InkAnchor` 解析器将屏幕坐标 → 文档 `Locator`（通过 `render:api`）。笔迹持久化到 `core:database` 的 `AnnotationEntity`。Phase 2 交付——模块已搭建但尚未接入 reader Activity。 | `:core:model` + `:core:database` + `:render:api` |

#### Layer 5：UI 基础（`android-library`，Compose 可用）

| 模块 | 职责 | 依赖 |
|------|------|------|
| `:core:ui` | 共享 Compose theme 和 design tokens — `LinReadsTheme`（Material3）、色板（日/夜/暖）、字体（Georgia / Noto Serif CJK）、间距 tokens。复用 composable：`LinReadsScaffold`、`ProgressIndicator`、`ErrorBanner`。 | `:core:model` |

#### Layer 6：功能模块（`android-library`，Compose + ViewModels）

| 模块 | 职责 | 依赖 |
|------|------|------|
| `:features:library` | `LibraryScreen` + `LibraryViewModel`（MVVM + StateFlow）。浏览 Calibre 书单（via `:core:calibre`），合并本地阅读进度（via `:core:database`）。封面 via Coil。搜索、排序、格式筛选。 | `:core:model` + `:core:ui` + `:core:calibre` + `:core:database` |
| `:features:reader` | `ReaderScreen` + `ReaderViewModel`（MVI：`ReaderIntent` → `ReaderState`）。编排格式引擎选择、页面预取、转场控制、字体/亮度、ink toggle。混合 View 宿主。 | `:core:model` + `:core:ui` + `:core:database` + `:render:api` + `:ink` |
| `:features:settings` | `SettingsScreen` + `SettingsViewModel`。读写 `AppSettings` via `:core:prefs`。分区：Connection / Reading / Extensions / About。阅读主题实时预览。 | `:core:model` + `:core:ui` + `:core:prefs` |

**功能模块隔离规则**：
- 不得直接依赖 `render:*` 实现模块（`:render:epub`、`:render:pdf`、`:render:txt`）。只由 `:app` 通过 DI 注入具体引擎。
- 功能模块之间不得相互依赖。

#### Layer 7：扩展（`android-library`，可选）

| 模块 | 职责 | 依赖 |
|------|------|------|
| `:extensions:tts` | Text-to-Speech。实现 Extension SPI + `Android TTS`。朗读当前页内容，可调速/调音高。可通过 `settings.gradle.kts` 条件排除。 | `:core:model` + `:extensions:api` |
| `:extensions:stats` | 阅读统计。实现 Extension SPI。追踪阅读时间、翻页数、完本数。查询 `:core:database`。导出 JSON。 | `:core:model` + `:core:database` + `:extensions:api` |
| `:extensions:opds` | OPDS 书源。实现 `BookSource`（from `:extensions:api`）。抓取 OPDS 1.2/2.0 catalog，解析为 `BookMeta`。使用 Ktor HTTP（与 `:core:calibre` 同模式）。 | `:core:model` + `:core:calibre` + `:extensions:api` |

#### Layer 8：应用组装（`com.android.application`）

| 模块 | 职责 | 依赖 |
|------|------|------|
| `:app` | Application entry point — `MainActivity`（混合 View 的 FrameLayout root + ComposeView overlay 做 UI chrome）、Koin DI 装配、Navigation host、`AndroidManifest`、LAN Calibre 访问的 network security config。 | **全部模块** |

---

## 3. 视图架构

### 3.1 混合 View 层级

v2 不使用 Compose `Image(bitmap.asImageBitmap())` 作为文档渲染基元。root 是 `FrameLayout`，文档 View 直接加入 View hierarchy，Compose 仅管理 UI chrome overlay。

```
FrameLayout (match_parent × match_parent) ← reader_activity.xml root
│  id: reader_root
│  clipChildren: false (让 ink 笔迹可超出边界)
│
├─ [Layer 0 — 文档容器]
│  FrameLayout
│  │  id: document_host
│  │  layout: match_parent × match_parent
│  │
│  │  ┌──────────────────────────────────────────────────────┐
│  │  │ 格式 → View 映射（同一时刻只有 ONE child）：          │
│  │  │                                                      │
│  │  │ EPUB  → WebView                                     │
│  │  │   └─ epub-ts in iframe(s), 每 spine item 一个   │
│  │  │   └─ JS bridge: CFI导航, 位置回报                    │
│  │  │   └─ CSS 注入: 字体大小/主题/行距                    │
│  │  │   └─ Shadow DOM 包装实现 CSS 隔离                    │
│  │  │                                                      │
│  │  │ PDF   → ImageView                                   │
│  │  │   └─ PdfRenderer (API 26+) 驱动                      │
│  │  │   └─ Bitmap LRU cache (3 pages: prev/current/next)   │
│  │  │   └─ ScaleType.MATRIX for pinch-zoom                 │
│  │  │                                                      │
│  │  │ DOCX  → ImageView (MuPDF JNI)                       │
│  │  │ CBZ   → ImageView (MuPDF JNI)                       │
│  │  │   └─ com.artifex.mupdf:fitz:1.27.0                  │
│  │  │   └─ MuPDF renders per-page Bitmap                   │
│  │  │   └─ 与 PDF 同 LRU cache 策略                       │
│  │  │                                                      │
│  │  │ TXT   → RecyclerView (vertical LinearLayoutManager)  │
│  │  │   └─ TxtVirtualPager + Paging3 PagingSource          │
│  │  │   └─ byteOffset 分页，scroll mode 或 SnapHelper page mode│
│  │  │                                                      │
│  │  │ MD    → ScrollView > TextView                        │
│  │  │   └─ Markwon Spannables（无中间 HTML，无 WebView）    │
│  │  │   └─ 按 ## heading 分 section，scroll 时增量 append   │
│  │  └──────────────────────────────────────────────────────┘
│
├─ [Layer 1 — 已完成 Ink Canvas]
│  CanvasView (extends View)
│  │  id: ink_completed
│  │  layout: match_parent × match_parent
│  │  z-order: above document_host
│  │  onDraw(): 反序列化 StrokeInputBatch → CanvasStrokeRenderer
│  │  touchable: false (不消费事件 — 见触摸路由)
│  │
│  │  行为:
│  │  - 渲染当前页所有已完成笔迹
│  │  - 翻页 → 从 Room 加载新页笔迹
│  │  - stylus ACTION_UP: 笔迹序列化 + append → invalidate()
│  │  - 擦除: 从列表移除笔迹 → invalidate()
│
├─ [Layer 2 — 进行中 Ink（前端缓冲）]
│  InProgressStrokesView (androidx.ink.authoring)
│  │  id: ink_in_progress
│  │  layout: match_parent × match_parent
│  │  z-order: above ink_completed
│  │  touchable: false (事件由程序路由)
│  │
│  │  行为:
│  │  - 渲染 ACTIVE 笔迹，<10ms 延迟（前端缓冲路径）
│  │  - 通过 inkInProgress.handleMotionEvent() 接收 MotionEvent
│  │  - ACTION_UP → InProgressStrokesView.removeFinishedStroke()
│  │    → CanvasView 抓取结果 → invalidate()
│  │  - MotionEventPredictor 开启，提供预测线渲染
│
└─ [Layer 3 — Compose UI Overlay]
   ComposeView
      id: ui_overlay
      layout: match_parent × match_parent
      z-order: 最上层
      isClickable: false at root (非 UI 区域透传)

      Composition:
      ┌────────────────────────────────────────────┐
      │ TopBar (AnimatedVisibility)                 │
      │  ├─ 返回按钮                                 │
      │  ├─ 书名 / 章节名                            │
      │  ├─ 书签开关                                 │
      │  └─ Overflow menu (设置, TOC, ink 工具)      │
      ├────────────────────────────────────────────┤
      │                                            │
      │         透明透传区域                          │
      │     (触摸落到文档层)                          │
      │                                            │
      ├────────────────────────────────────────────┤
      │ BottomBar (AnimatedVisibility)              │
      │  ├─ 进度条 (SeekBar via AndroidView)        │
      │  ├─ 页码指示器 (current / total)             │
      │  └─ 阅读模式 chips (scroll / page)          │
      ├────────────────────────────────────────────┤
      │ InkToolbar (SlideIn, 条件渲染)               │
      │  ├─ 笔类型选择器 (ballpoint/fountain)        │
      │  ├─ 色板 (6 色)                              │
      │  ├─ 笔触宽度滑块                             │
      │  ├─ 橡皮擦开关                               │
      │  └─ Undo / Redo 按钮                         │
      └────────────────────────────────────────────┘
```

### 3.2 Z-order 总结

| Z | 层 | View 类型 | 消费事件? |
|---|-----|----------|-----------|
| 0 | 文档 | 格式相关 View | Yes — 手指滚动/缩放 |
| 1 | 已完成 Ink | CanvasView (custom) | No — 透传 |
| 2 | 进行中 Ink | InProgressStrokesView | No — 程序路由 |
| 3 | UI Chrome | ComposeView | Yes — 仅 toolbar/button 区域 |

### 3.3 触摸事件路由

**核心原则：按工具类型路由（tool-type-based），不按模式切换（mode-toggle-based）**。用户不需要按 "ink mode" 按钮。Stylus 事件始终走 ink；手指事件始终走文档导航或 UI。这是 Samsung Notes、GoodNotes 和 androidx.ink 示例验证过的标准做法。

```
MotionEvent arrives at reader_root (FrameLayout)
  │
  ├─ ACTION_CANCEL? → 取消当前笔迹 + forward cancel 到文档 → return
  │
  ├─ ACTION_DOWN → 记录 tool type
  │   │
  │   ├─ TOOL_TYPE_STYLUS / TOOL_TYPE_ERASER?
  │   │   └─ yes → INTERCEPT。整个手势流路由到：
  │   │             inkOverlay.handleStylusEvent(event)
  │   │             ├─ ACTION_DOWN: 开始新笔迹
  │   │             ├─ ACTION_MOVE: 延长笔迹（前端缓冲）
  │   │             └─ ACTION_UP:   提交 → CanvasView
  │   │
  │   └─ TOOL_TYPE_FINGER / TOOL_TYPE_MOUSE / TOOL_TYPE_UNKNOWN?
  │       └─ no → DON'T INTERCEPT。正常子 View 分发：
  │                 │
  │                 ├─ Hit test against ComposeView UI 区域
  │                 │   ├─ hit → ComposeView handles (button tap, slider drag)
  │                 │   └─ no hit → pass to documentView
  │                 │
  │                 └─ documentView.onTouchEvent(event)
  │                     ├─ EPUB WebView: scroll / pinch-zoom / text selection
  │                     ├─ PDF ImageView: scroll (ViewPager2) / pinch-zoom
  │                     ├─ TXT RecyclerView: fling / scroll
  │                     └─ MD TextView: scroll
  │
  ├─ ACTION_POINTER_DOWN (额外手指/stylus 按下)
  │   ├─ new pointer is STYLUS? → cancel 当前手指手势 → intercept for ink
  │   └─ new pointer is FINGER (2nd finger)? → pinch-zoom on document
  │
  └─ ACTION_UP / ACTION_POINTER_UP
      └─ Stylus: inkOverlay 完成笔迹。Finger: 结束 scroll/fling/zoom。
```

**核心实现类：`ReaderRootLayout`**

```kotlin
// features/reader/src/main/java/dev/readflow/features/reader/ReaderRootLayout.kt
package dev.readflow.ui.reader

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import dev.readflow.ink.InkOverlay

class ReaderRootLayout(context: Context) : FrameLayout(context) {

    private var inkOverlay: InkOverlay? = null
    private var composeOverlay: ComposeView? = null
    private var documentView: android.view.View? = null
    private var activePointerToolType: Int = MotionEvent.TOOL_TYPE_UNKNOWN

    fun bind(ink: InkOverlay, compose: ComposeView, document: android.view.View) {
        this.inkOverlay = ink
        this.composeOverlay = compose
        this.documentView = document
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerToolType = e.getToolType(0)
                when {
                    activePointerToolType == MotionEvent.TOOL_TYPE_STYLUS ||
                    activePointerToolType == MotionEvent.TOOL_TYPE_ERASER -> {
                        return true  // 拦截 → onTouchEvent 处理
                    }
                    else -> return false  // 不拦截 → 子 View 正常处理
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = e.actionIndex
                if (e.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_STYLUS) {
                    // 取消进行中的手指手势
                    val cancel = MotionEvent.obtain(e).apply {
                        action = MotionEvent.ACTION_CANCEL
                    }
                    documentView?.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    activePointerToolType = MotionEvent.TOOL_TYPE_STYLUS
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                inkOverlay?.cancelCurrentStroke()
                documentView?.dispatchTouchEvent(e)
                return false
            }
            else -> return false
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        return when (activePointerToolType) {
            MotionEvent.TOOL_TYPE_STYLUS,
            MotionEvent.TOOL_TYPE_ERASER -> {
                inkOverlay?.handleStylusEvent(e) ?: false
            }
            else -> {
                // 手指事件未被子 View 消费：点击屏幕中间 → 切换 UI overlay
                if (e.action == MotionEvent.ACTION_UP) {
                    // toggle ComposeOverlay visibility
                }
                true
            }
        }
    }
}
```

### 3.4 点击区域翻页（手指点击文档）

```kotlin
// 手指点击文档区域时，根据点击位置决定翻页方向
fun onDocumentTap(event: MotionEvent, viewWidth: Int): ReaderIntent? {
    if (event.action != MotionEvent.ACTION_UP) return null
    val x = event.x
    val leftThird = viewWidth / 3f
    val rightThird = 2f * viewWidth / 3f

    return when {
        x < leftThird  -> ReaderIntent.PreviousPage   // 左 1/3 → 上一页
        x > rightThird -> ReaderIntent.NextPage        // 右 2/3 → 下一页
        else           -> ReaderIntent.ToggleUi        // 中间 → 显示/隐藏 chrome
    }
}
```

### 3.5 缩放手势

| 格式 | 实现方式 |
|------|---------|
| EPUB | WebView 原生处理 pinch-zoom。缩放结束后 debounce 200ms 调 `rendition.resize()` |
| PDF/DOCX/CBZ | `ScaleGestureDetector` + `ImageView.matrix`。翻页时 matrix 重置为 identity |
| TXT/MD | Pinch 触发 `setFontSize()` 而非 viewport 缩放 |

---

## 4. 渲染引擎契约

### 4.1 ReaderEngine 接口

```kotlin
// render/api/src/main/kotlin/dev/readflow/render/api/ReaderEngine.kt
package dev.readflow.render.api

import android.net.Uri
import android.view.View
import kotlinx.coroutines.flow.StateFlow

// Locator sealed interface 定义在 :core:model（Layer 0），含 @Serializable 标注。
// render:api 通过 import dev.readflow.core.model.Locator 使用。
// 完整定义见 §6.1（数据层）或 core/model/src/main/kotlin/dev/readflow/core/model/Locator.kt

enum class ReadingMode { SCROLL, PAGED }

/**
 * ReaderEngine: 每种格式渲染器必须实现的契约。
 *
 * v1 → v2 关键变化:
 *   1. createView() 返回 View，不是 Bitmap — 混合 View 架构
 *   2. pageCount 是 StateFlow<Int> — 响应式，非固定属性
 *   3. goTo() / currentLocator 使用 Locator sealed hierarchy
 *   4. supports() + priority 支持基于权重的 registry 发现
 *   5. 生命周期显式化: open() → render loop → close()
 */
interface ReaderEngine {

    // ── 标识 ────────────────────────────────────────────

    /** 此引擎处理的格式。 */
    val format: BookFormat

    /**
     * 整数优先级，用于同格式多引擎时的权重选择。
     * 数字越小 = 优先级越高。Registry 升序排序。
     *
     * 惯例:
     *   0–9   = 系统原生渲染器 (PdfRenderer, WebView)
     *   10–19 = 第一方 JNI (MuPDF)
     *   20–29 = 第三方降级方案
     *   30+   = 纯文本提取降级
     */
    val priority: Int

    /** 此引擎能否处理给定的文件。 */
    suspend fun supports(uri: Uri): Boolean

    // ── 生命周期 ────────────────────────────────────────

    /**
     * 打开文档。必须在 createView() 或 goTo() 之前调用。
     * 重活（解析、索引）在此执行，不在构造时。
     *
     * @return 文档首个位置的 Locator。
     */
    suspend fun open(uri: Uri): Locator

    /**
     * 创建渲染此文档的 View。
     *
     * 在 open() 之后调用。返回的 View 被加入 document_host FrameLayout。
     * 引擎持有所有权 — 调用方必须在移除 View 前调 close()。
     *
     * 替代 v1 的 renderPage(index, widthPx, heightPx): Bitmap。
     */
    fun createView(): View

    /**
     * 释放所有资源（文件句柄、Bitmap、WebView teardown）。
     * close() 后引擎失效 — 新建实例以重新打开。
     */
    suspend fun close()

    // ── 导航 ────────────────────────────────────────────

    /** 跳转到指定位置。 */
    suspend fun goTo(locator: Locator)

    /** 当前位置，每次翻页/滚动停止后更新。 */
    val currentLocator: StateFlow<Locator>

    /**
     * 页数（或等效概念）。响应式 — 文档解析完成或布局变化（如改字号）时更新。
     *
     * 各格式语义:
     *   EPUB:   spine item 数 × 列数（如双栏）
     *   PDF:    PdfRenderer.pageCount（固定，open 时已知）
     *   DOCX:   MuPDF pageCount（字号变化时会变）
     *   TXT:    TxtVirtualPager.estimatedPageCount（动态）
     */
    val pageCount: StateFlow<Int>

    // ── 布局控制（EPUB/TXT 回流格式） ────────────────────

    /** 调整字号（回流格式）。固定布局格式 no-op。 */
    suspend fun setFontSize(sp: Float)

    /** 设置阅读模式。仅支持单一模式的格式 no-op。 */
    suspend fun setMode(mode: ReadingMode)
}
```

### 4.2 ReaderEngineRegistry — 权重发现的引擎注册表

```kotlin
// render/api/src/main/kotlin/dev/readflow/render/api/ReaderEngineRegistry.kt
package dev.readflow.render.api

import android.net.Uri

/**
 * 基于权重的 ReaderEngine 提供者发现。
 *
 * 引擎通过 Koin multi-bind 自注册。Reader 需要打开文件时，
 * Registry 查询所有引擎的 supports() 并选择最低 priority 的匹配。
 *
 * 此 Registry 刻意不是 sealed hierarchy 或硬编码 when-chain。
 * 新增格式 = 新增模块，不改现有代码 — 满足 OCP 原则。
 */
class ReaderEngineRegistry(
    private val engines: Set<ReaderEngine>  // Koin 注入
) {
    /**
     * 为 URI 查找最佳引擎。
     *
     * 算法:
     *   1. 按 supports(uri) 过滤 → candidate set
     *   2. 按 (priority, format ordinal) 排序
     *   3. 返回第一个（priority 数字最小 = 最优）
     *
     * @throws NoEngineException 如果没有引擎支持此文件。
     */
    suspend fun resolve(uri: Uri): ReaderEngine {
        val candidates = engines
            .filter { it.supports(uri) }
            .sortedWith(compareBy({ it.priority }, { it.format.ordinal }))

        return candidates.firstOrNull()
            ?: throw NoEngineException(uri)
    }

    /** 列出声称支持某格式的所有引擎。用于设置/调试 UI。 */
    fun candidatesFor(format: BookFormat): List<ReaderEngine> =
        engines.filter { it.format == format }.sortedBy { it.priority }
}

class NoEngineException(uri: Uri) :
    IllegalStateException("No ReaderEngine supports: $uri")
```

### 4.3 各格式引擎规格

#### EPUB — EpubWebViewEngine（priority: 0）

- **路径**: `render/epub/src/main/kotlin/dev/LinReads/render/epub/EpubWebViewEngine.kt`
- **渲染**: WebView + epub-ts 0.3.x
- **文件访问**: 本地 nanohttpd server 提供 EPub 解压内容，或 `file://` 直接访问
- **导航**: `webView.evaluateJavascript("epub-ts rendition.display('$cfi')")`
- **主题**: CSS 注入（`body { background: ...; color: ...; }`）
- **字号**: CSS `font-size` 注入 epub-ts rendition theme
- **位置回报**: `@JavascriptInterface` (AnchorBridge, ProgressBridge) → 发送 CFI 和进度百分比
- **v1 变动**: 推翻 v1 "零 WebView" 原则，审计认定 EPUB 用 WebView 是正确选择（epub-ts 成熟度、CFI 精度、CSS 灵活性均优于 MuPDF 的 Bitmap 方案）

#### PDF — PdfRendererEngine（priority: 0）

- **路径**: `render/pdf/src/main/kotlin/dev/LinReads/render/pdf/PdfRendererEngine.kt`
- **渲染**: Android `PdfRenderer`（API 21+），零外部依赖
- **缓存**: LRU cache 3 pages（prev / current / next）
- **缩放**: `ImageView.ScaleType.MATRIX` + `ScaleGestureDetector`
- **pageCount**: `PdfRenderer.pageCount`（sync 已知）
- **v1 保持一致**

#### DOCX / CBZ — MuPdfEngine（priority: 10）

- **路径**: `render/docx/...` 和 `render/cbz/...`（可合并为一个 `render:mupdf` 模块，或保持分离用相同引擎内核）
- **渲染**: MuPDF JNI（`com.artifex.mupdf:fitz:1.27.0`）
- **输出**: 每页 `Bitmap`
- **缓存**: 与 PDF 同 LRU 策略
- **字号**: MuPDF `Document.setMetadata("style", "...")` CSS 注入

#### TXT — TxtVirtualPagerEngine（priority: 0）

- **路径**: `render/txt/src/main/kotlin/dev/LinReads/render/txt/TxtVirtualPagerEngine.kt`
- **渲染**: `RecyclerView` (vertical `LinearLayoutManager`) + Paging3
- **分页逻辑**: `FileChannel` (RandomAccess) 64KB 块扫描 → 页边界检测（空行≥2 / 章节正则 `/第[0-9零一二三四五六七八九十百千万]+[章节回]/` / 字符阈值 2000）→ `List<PagePointer>{ byteOffset, length }` → Paging3 `PagingSource<Long, CharSequence>`
- **阅读模式**: SCROLL（默认）或 PAGED（SnapHelper）

#### MD — MarkwonEngine（priority: 0）

- **路径**: `render/markdown/...`（或合并入 `render:txt`？建议独立模块 `render:md`）
- **渲染**: `Markwon` → `Spannable` → `TextView`（嵌入 `ScrollView`）
- **无 WebView**: 纯 Span，无中间 HTML
- **章节**: 按 `## heading` 分 section，滚动时增量 append

### 4.4 翻页动效 — ViewPager2 + PageTransformer

**v1→v2 重大变更**: 放弃自研 `AnimationEngine`（vsync loop + `Bitmap.createBitmap()` 每帧分配），改用 ViewPager2。

**放弃理由**:
1. v1 的 vsync-loop 固定时长补间动画**不跟手** — 用户期望翻页边跟随拇指
2. `Bitmap.createBitmap()` 每帧一次 vsync（60fps × 300ms = 18 次分配），低端设备 GC 压力大
3. ViewPager2 自带跟手拖拽、离屏预渲染、`PageTransformer` 扩展点 — AndroidX 团队多年 bug 修复

```kotlin
// Phase 1: ViewPager2 + SlideFadeTransformer（首发）
ViewPager2 (horizontal, inside document_host for paged formats)
  │
  │  Adapter: ReaderPageAdapter
  │    ├─ getItemCount() = engine.pageCount.collect()
  │    └─ onCreateViewHolder() → returns FrameLayout wrapper
  │         └─ contains the format's View for ONE page
  │
  │  OffscreenPageLimit = 2
  │  PageTransformer = SlideFadeTransformer (Phase 1 default)
  │
  │  registerOnPageChangeCallback:
  │    onPageSelected(position) → engine.goTo(Locator.Page(position))

// Phase 2: CurlPageTransformer（卷页效果）
class CurlPageTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        // position ∈ [-1, 1]
        // -1 = 页面完全在左侧屏幕外
        //  0 = 页面居中（当前）
        //  1 = 页面完全在右侧屏幕外

        if (position < -1 || position > 1) {
            page.alpha = 0f
            return
        }

        // 左页: 向左卷曲
        if (position < 0) {
            page.translationX = 0f
            page.pivotX = page.width.toFloat()   // 右边缘为轴
            page.rotationY = position * -30f       // 3D 卷曲（最大 30 度）
            page.alpha = 1f + position * 0.5f      // 卷曲时淡出
        }
        // 右页: 从右侧滑入
        else {
            page.translationX = -page.width * position
            page.alpha = 1f - position * 0.3f
        }
    }
}
```

**已知局限（Phase 2 可接受）**:
- `rotationY` 是平面矩形 3D 投影，非真实 Bezier 曲面。真曲面 + 背面渲染需 GLSurfaceView 或自定义 Canvas path clipping — 但这是 Kindle / Google Play Books / Moon+ 都没有的小众特性
- `PageTransformer` 无法渲染卷页**背面**文字 — 正面卷曲 + 阴影效果已是行业标准

**非分页格式（TXT、MD）不使用 ViewPager2** — 直接 `RecyclerView` / `ScrollView` 垂直滚动。Compose overlay 进度条反映的是滚动百分比而非页码。

---

## 5. Ink / 手写笔集成

### 5.1 InkOverlay 接口

```kotlin
// ink/src/main/kotlin/dev/readflow/ink/InkOverlay.kt
package no.dev.readflow.ink

import android.view.MotionEvent
import android.view.View

/**
 * InkOverlay: 管理任意文档 View 之上的两层 ink stack。
 *
 * Layer 1 — CanvasView（已完成笔迹，标准 Canvas 渲染）
 * Layer 2 — InProgressStrokesView（进行中笔迹，前端缓冲 <10ms）
 *
 * InkOverlay 不持有文档 View。它作为 sibling 加入父 FrameLayout。
 * 宿主（ReaderViewModel 或 ReaderActivity）连接触摸路由来调用 handleStylusEvent()。
 */
interface InkOverlay {

    // ── 生命周期 ────────────────────────────────────────

    /** 将 ink layers 作为 documentView 的 siblings 加入父 FrameLayout。 */
    fun attach(parent: android.widget.FrameLayout, documentView: View)

    /** 分离两层 ink。在文档 View 切换前调用（如 PDF engine → EPUB engine）。 */
    fun detach()

    // ── 输入 ─────────────────────────────────────────────

    /** 路由 stylus MotionEvent 通过 ink pipeline。手指事件不要调用此法。 */
    fun handleStylusEvent(event: MotionEvent): Boolean

    /** 取消进行中的笔迹（palm rejection）。 */
    fun cancelCurrentStroke()

    // ── 笔迹生命周期（toolbar 驱动） ─────────────────────

    fun setBrush(brush: InkBrush)
    fun undo(): Boolean
    fun redo(): Boolean
    fun clearPage()

    // ── 翻页转换 ────────────────────────────────────────

    /** 翻页之前调用。持久化进行中笔迹，隐藏 ink 层避免 ViewPager2 拖拽鬼影。 */
    fun onPageWillChange()

    /** 翻页完成后调用。从 Room 加载新页的已完成笔迹。 */
    fun onPageChanged(anchor: InkAnchor)

    // ── 状态查询 ────────────────────────────────────────

    val hasStrokes: Boolean
    val isDrawing: Boolean
}
```

### 5.2 InkAnchor 模型

笔迹需要绑定到文档位置，而非屏幕坐标。两种策略基于格式类型：

```kotlin
// ink/src/main/kotlin/dev/readflow/ink/InkAnchor.kt
package dev.readflow.ink

/**
 * InkAnchor: 将一组笔迹绑定到文档中的某个位置。
 *
 * 两种策略:
 *   Page  — 固定布局 (PDF, CBZ, DOCX)：锚定到页面索引。
 *           页面坐标稳定，因为布局永不回流。
 *   Text  — 回流 (EPUB, TXT, MD)：锚定到文本位置。
 *           页面坐标在改字号后失效。
 */
sealed interface InkAnchor {

    /**
     * Page-mode anchor: 笔迹坐标相对于已知不可变页面。
     */
    data class Page(
        val pageIndex: Int,
        val pageWidth: Float,   // 笔画时捕获（用于宽高比修正）
        val pageHeight: Float,
    ) : InkAnchor

    /**
     * Text-mode anchor: 笔迹绑定文本位置，不绑定屏幕位置。
     * 回流时通过重新解析文本锚点的 bounding box 来重定位笔迹。
     *
     * 实现对齐 W3C EPUB Annotations 1.0 layered selector 模型。
     */
    data class Text(
        val sourceHref: String,           // EPUB resource（章节文件）
        val cssSelector: String,          // e.g. "#intro > p:nth-child(3)"
        val textStartOffset: Int,         // 节点内字符偏移
        val textEndOffset: Int,
        val offsetXPx: Float,             // 笔迹相对于 anchor rect 原点的偏移
        val offsetYPx: Float,
        val fontSizeAtCapture: Float,     // 存储用于缩放补偿
    ) : InkAnchor
}
```

### 5.3 Ink 生命周期状态机

```
                    ┌──────────────┐
    attach() ──────→│    IDLE      │
                    │ (no stroke   │
                    │  in progress)│
                    └──┬───────────┘
                       │
    stylus ACTION_DOWN│ (MotionEvent.getToolType(0) == TOOL_TYPE_STYLUS)
    → handleStylusEvent()
                       ▼
              ┌────────────────┐
              │   DRAWING      │
              │ InProgress-    │
              │ StrokesView    │
              │ active         │
              │ (front buffer) │
              └──┬───┬─────┬───┘
                 │   │     │
    stylus       │   │     └── ACTION_CANCEL → cancelCurrentStroke()
    ACTION_UP    │   │         → 回到 IDLE（笔迹丢弃）
    → finish     │   │
    stroke       │   │
                 ▼   │
              ┌────────────┐
              │ COMMITTING │
              │ (serialize │
              │  stroke)   │
              └──┬─────────┘
                 │
    onStrokeFinished(stroke, anchor)
    → serialize to ByteArray (ink-storage)
    → insert into Room (AnnotationDao)
    → CanvasView.strokes.add(stroke)
    → CanvasView.invalidate()
                 │
                 ▼
              ┌──────────────┐
              │    IDLE      │  (loop)
              └──────────────┘
```

### 5.4 Phase 策略

| Phase | 交付内容 | 依赖 |
|-------|---------|------|
| Phase 1 | `:ink` 模块搭建，`InkOverlay` 接口定义，`InkAnchor` 模型，Room schema 预留 | `:core:model` + `:render:api` |
| Phase 2 | CanvasView + InProgressStrokesView 实现，触摸路由集成，`InkToolbar` composable，`MotionEventPredictor` 开启 | Phase 1 + `androidx.ink:ink-authoring:1.0.0-beta02` + `androidx.ink:ink-rendering:1.0.0-beta02` |

### 5.5 坐标转换链

```
MotionEvent (screen coordinates, origin at display top-left)
    │
    ▼  [1] Screen → Root FrameLayout
    │      event.x, event.y already relative to reader_root
    │
    ▼  [2] Root → Document View
    │      val docX = event.x - documentView.left
    │      val docY = event.y - documentView.top
    │
    ▼  [3] Document View → Content (format-dependent)
    │
    │   EPUB (WebView):
    │     WebView-local coords. 如有 iframe 嵌套须减去 offset.
    │     → rescaled by WebView.getScale() if pinch-zoomed.
    │
    │   PDF (ImageView):
    │     → transformed by ImageView.getImageMatrix().invert()
    │       to get page-local (bitmap) coordinates.
    │
    │   DOCX/CBZ (ImageView): same as PDF.
    │
    │   TXT (RecyclerView):
    │     → findChildViewUnder() → item View offset
    │       → byteOffset of the visible text line.
    │
    │   MD (TextView):
    │     → getLayout().getOffsetForHorizontal(line, x)
    │       → character offset within Spanned text.
    │
    ▼  [4] Content → Locator → InkAnchor
```

---

## 6. 数据层

### 6.1 Locator 模型（共享类型）

```kotlin
// core/model/src/main/kotlin/dev/readflow/core/model/Locator.kt
package dev.readflow.core.model

import kotlinx.serialization.Serializable

/**
 * 对标 Readium Locator 模型。
 * 用 sealed interface 替代 v1 的 ad-hoc (cfi, percentage) 元组。
 */
@Serializable
sealed interface Locator {
    @Serializable
    data class Cfi(val cfi: String) : Locator

    @Serializable
    data class Page(val index: Int, val total: Int = -1) : Locator

    @Serializable
    data class ByteOffset(val offset: Long, val length: Int) : Locator

    @Serializable
    data class Section(
        val headingIndex: Int,
        val scrollY: Int = 0
    ) : Locator

    @Serializable
    data object Unknown : Locator
}
```

### 6.2 错误模型

```kotlin
// core/model/src/main/kotlin/dev/readflow/core/model/ReadflowError.kt
package dev.readflow.core.model

/** 结构化错误层级，替代 v1 的 bare String?。 */
sealed class ReadflowError(
    open val message: String,
    open val cause: Throwable? = null
) {
    data class Network(
        val code: Int?,
        override val message: String,
        override val cause: Throwable? = null
    ) : ReadflowError(message, cause)

    data class Database(
        override val message: String,
        override val cause: Throwable? = null
    ) : ReadflowError(message, cause)

    data class Parse(
        override val message: String,
        override val cause: Throwable? = null
    ) : ReadflowError(message, cause)

    data class NotFound(
        val resourceType: String,
        val id: String,
        override val message: String = "$resourceType not found: $id"
    ) : ReadflowError(message)

    data class Unsupported(
        val format: String,
        override val message: String = "Unsupported format: $format"
    ) : ReadflowError(message)

    data class Auth(
        override val message: String = "Authentication failed"
    ) : ReadflowError(message)
}

/** 所有 repository 返回此类型。 */
typealias ReadflowResult<T> = Result<T, ReadflowError>
```

### 6.3 Room 数据库 Schema

```kotlin
// core/database/src/main/kotlin/dev/readflow/core/database/ReadflowDatabase.kt
package dev.readflow.core.database

import androidx.room.*

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: Int,                // Calibre book ID
    val title: String,
    val authors: String,                    // JSON array stored as string
    val formats: String,                     // JSON array stored as string
    val tags: String = "[]",
    val series: String? = null,
    val seriesIndex: Float? = null,         // v2新增: 对齐 shared/api/calibre-contract.ts
    val cover: String? = null,               // v2新增: cover image path/URL
    val lastModified: String? = null,        // v2新增: ISO 8601 timestamp
    val sourceId: String = "calibre",        // "calibre" | "opds" | "local"
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "reading_progress",
    indices = [Index(value = ["bookId"], unique = true)]
)
data class ReadingProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Int,
    val locatorJson: String,                // Locator serialized as JSON
    val progressPercent: Float,             // 0.0 .. 1.0
    val lastPageIndex: Int,
    val totalPages: Int,
    val updatedAt: Long = System.currentTimeMillis()  // epoch millis, for LWW merge
)

@Entity(
    tableName = "annotations",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["bookId"])]
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Int,
    val locatorJson: String,                // Locator serialized as JSON
    val anchorJson: String?,                // InkAnchor serialized (null = highlight only)
    val selectedText: String?,              // highlighted text (null = ink stroke)
    val note: String? = null,               // user note
    val color: Int = 0xFFFFEB3B.toInt(),    // default yellow
    val strokeData: ByteArray? = null,       // serialized ink stroke (androidx.ink)
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["bookId"])]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Int,
    val locatorJson: String,                // Locator serialized as JSON
    val text: String,                       // bookmark label (chapter name or user text)
    val createdAt: Long = System.currentTimeMillis()
)
```

### 6.4 Repository 接口

```kotlin
// core/calibre/src/main/kotlin/dev/readflow/core/calibre/CalibreRepository.kt
package u/dev.readflow.core.calibre

import dev.readflow.core.model.*

interface CalibreRepository {
    suspend fun search(query: String, limit: Int = 20, offset: Int = 0): ReadflowResult<SearchResult>
    suspend fun getMetadata(bookId: Int): ReadflowResult<BookMeta>
    fun getDownloadUrl(bookId: Int, format: String): String
    fun getCoverUrl(bookId: Int): String
    suspend fun isAvailable(): Boolean
}

// core/database/src/main/kotlin/dev/readflow/core/database/ReadingProgressRepository.kt
interface ReadingProgressRepository {
    suspend fun getProgress(bookId: Int): ReadflowResult<ReadingProgressEntity?>
    suspend fun saveProgress(progress: ReadingProgressEntity): ReadflowResult<Unit>
    suspend fun deleteProgress(bookId: Int): ReadflowResult<Unit>
    fun observeProgress(bookId: Int): Flow<ReadingProgressEntity?>
}

interface AnnotationRepository {
    suspend fun getAnnotationsForPage(bookId: Int, locatorJson: String): ReadflowResult<List<AnnotationEntity>>
    suspend fun saveAnnotation(annotation: AnnotationEntity): ReadflowResult<Long>
    suspend fun deleteAnnotation(id: Long): ReadflowResult<Unit>
    fun observeAnnotations(bookId: Int): Flow<List<AnnotationEntity>>
}

interface BookmarkRepository {
    suspend fun getBookmarks(bookId: Int): ReadflowResult<List<BookmarkEntity>>
    suspend fun saveBookmark(bookmark: BookmarkEntity): ReadflowResult<Long>
    suspend fun deleteBookmark(id: Long): ReadflowResult<Unit>
    fun observeBookmarks(bookId: Int): Flow<List<BookmarkEntity>>
}

// core/prefs/src/main/kotlin/dev/readflow/core/prefs/SettingsRepository.kt
interface SettingsRepository {
    val calibreBaseUrl: Flow<String?>
    val calibreUsername: Flow<String?>
    val calibrePassword: Flow<String?>       // encrypted
    val theme: Flow<String>                  // "day" | "night" | "warm"
    val fontSize: Flow<Int>
    val lineHeight: Flow<Float>
    val fontFamily: Flow<String>
    val transitionType: Flow<String>
    val tapZoneConfig: Flow<String>
    val enabledExtensions: Flow<Set<String>>

    suspend fun setCalibreBaseUrl(url: String)
    suspend fun setCalibreCredentials(username: String, password: String)
    suspend fun setTheme(theme: String)
    suspend fun setFontSize(sp: Int)
    suspend fun setLineHeight(height: Float)
    suspend fun setFontFamily(family: String)
    suspend fun setTransitionType(type: String)
    suspend fun setEnabledExtension(id: String, enabled: Boolean)
}
```

### 6.5 BookSource 抽象（替代直接依赖 CalibreClient）

```kotlin
// extensions/api/src/main/kotlin/dev/readflow/extension/BookSource.kt
package com.dev.readflow.extension

import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.SearchResult

/**
 * 统一书源抽象。替代 v1 中 CalibreClient 被 UI 层直接调用的模式。
 *
 * 实现:
 *   - CalibreBookSource      (Calibre Content Server REST API)
 *   - OpdsBookSource         (OPDS 1.x / 2.0 feed parsing)
 *   - LocalFileBookSource    (设备文件系统: /sdcard/Books/*.epub)
 *   - StubBookSource         (离线测试源，返回硬编码数据)
 */
interface BookSource {
    val sourceId: String            // "calibre", "opds", "local"
    val sourceName: String          // human-readable
    val sourceDescription: String

    suspend fun search(query: String, limit: Int = 20, offset: Int = 0): SearchResult
    suspend fun getMetadata(bookId: Int): BookMeta
    fun getDownloadUrl(bookId: Int, format: String): String
    fun getCoverUrl(bookId: Int): String
    suspend fun isAvailable(): Boolean
}

/**
 * 聚合所有 BookSource 实现。
 */
class BookSourceRegistry(
    private val sources: List<BookSource>,
) {
    suspend fun unifiedSearch(query: String, limit: Int = 20): List<UnifiedSearchHit> {
        return sources
            .filter { it.isAvailable() }
            .flatMap { source ->
                try {
                    val result = source.search(query, limit)
                    result.book_ids.map { bookId ->
                        UnifiedSearchHit(
                            globalId = "${source.sourceId}:$bookId",
                            sourceId = source.sourceId,
                            bookId = bookId,
                        )
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            .take(limit)
    }

    fun sourceById(sourceId: String): BookSource? =
        sources.firstOrNull { it.sourceId == sourceId }
}

data class UnifiedSearchHit(
    val globalId: String,   // "calibre:42"
    val sourceId: String,   // "calibre"
    val bookId: Int,        // 42
)
```

### 6.6 离线优先同步策略

1. **本地先写**: 进度/书签/标注一律先写 Room，UI 立即更新
2. **事件触发**: `ReaderEventBus.BookProgressSaved` 触发后台同步协程
3. **LWW 合并**: 按 `updatedAt` 时间戳 Last-Write-Wins，user-id 分区（单用户场景退化为简单时间比较）
4. **防抖动**: 翻页进度 2 秒 debounce 后再写 Room，避免频繁 I/O
5. **Schema 源**: `shared/api/calibre-contract.schema.json` → quicktype 生成 Kotlin + TypeScript 类型，CI 校验一致性

---

## 7. 扩展系统

### 7.1 Extension SPI

```kotlin
// extensions/api/src/main/kotlin/dev/readflow/extension/Extension.kt
package dev.readflow.extension

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import dev.readflow.event.ReaderEventBus

/** 扩展元数据（无需实例化即可读取）。 */
data class ExtensionMeta(
    val id: String,                    // "dev.readflow.extension.tts"
    val name: String,                  // "TTS 朗读"
    val description: String,
    val version: String,               // semver
    val author: String = "",
    val minAppVersion: String? = null,
)

/** 扩展存活期间的协程 scope。在 onDetach() 完成后自动取消。 */
interface ExtensionScope : CoroutineScope

/**
 * 沙盒化上下文，在 attach 时注入每个扩展。
 * 扩展只能通过此上下文访问 reader 状态、事件、设置、UI 钩子 —
 * 永远不持有对 core internals 的直接引用。
 */
interface ExtensionContext {
    /** 当前 Reader MVI 状态的快照 + flow。 */
    val readerState: StateFlow<ReaderState>

    /** 共享事件总线。 */
    val eventBus: ReaderEventBus

    /** 扩展专属命名空间 key-value store（DataStore 后端）。 */
    val settings: ExtensionSettings

    /** 注册一个在 reader 中可见的 toolbar / overflow menu item。 */
    fun registerMenuItem(item: ExtensionMenuItem)

    /** 注册拦截 reader 事件的生命周期钩子。 */
    fun registerReaderHook(hook: ReaderHook)

    /** 移除此扩展所做的所有注册。detach 时自动调用。 */
    fun unregisterAll()
}

data class ExtensionMenuItem(
    val id: String,
    val label: String,
    val icon: String? = null,
    val showAsAction: Boolean = false,  // true = toolbar icon, false = overflow menu
    val enabled: Boolean = true,
)

/** Reader 生命周期钩子。扩展只需 override 关心的钩子，其余默认 no-op。 */
interface ReaderHook {
    suspend fun onBeforePageRender(pageIndex: Int): Boolean = true
    suspend fun onAfterPageRender(pageIndex: Int) {}
    suspend fun onBookmarkAdd(text: String): String? = text
    suspend fun onBookmarkRemove(pageIndex: Int) {}
    suspend fun onBeforeBookClose() {}
}

/**
 * 每个扩展要实现的核心 SPI。
 */
interface Extension {
    val meta: ExtensionMeta

    /**
     * 扩展被用户启用。
     * - [scope] 是 application scope 的子 SupervisorJob。
     *   在 onDetach() 返回后取消，终止扩展启动的所有协程。
     * - [context] 是扩展与 reader 交互的沙盒。
     */
    suspend fun onAttach(scope: ExtensionScope, context: ExtensionContext)

    /** 扩展被禁用。释放资源。 */
    suspend fun onDetach()
}
```

### 7.2 ServiceLoader 发现

使用 `java.util.ServiceLoader` — **零额外依赖**。每个扩展模块在 `META-INF/services/` 下放一个文件：

```
META-INF/services/dev.readflow.extension.Extension
```

内容为一行一个全限定类名：

```
dev.readflow.extension.tts.TtsExtension
dev.readflow.extension.opds.OpdsBrowserExtension
dev.readflow.extension.stats.ReadingStatsExtension
```

**为什么不用 Mihon 风格的 APK 加载？**
- LinReads 是个人项目 — 所有扩展都是 `app/build.gradle.kts` 中的编译期依赖。不需要动态 APK 加载、classloader 隔离、签名校验
- ServiceLoader 使用 JVM 内置的 classpath 扫描，零文件系统遍历
- Mihon 风格（ExtensionManager, DexClassLoader, per-extension APK signing）增加约 2000 行基础设施来解决一个我们不存在的问题

```kotlin
// extensions/api/src/main/kotlin/dev/readflow/extension/ExtensionLoader.kt
package Pages dev.readflow.extension

import java.util.ServiceLoader

object ExtensionLoader {
    /** 返回 classpath 上每个 Extension 的元数据。 */
    fun discover(): List<ExtensionMeta> {
        val loader = ServiceLoader.load(
            Extension::class.java,
            Extension::class.java.classLoader
        )
        return loader.map { it.meta }
    }

    /** 按 id 加载单个扩展，未找到返回 null。 */
    fun load(id: String): Extension? {
        val loader = ServiceLoader.load(
            Extension::class.java,
            Extension::class.java.classLoader
        )
        return loader.firstOrNull { it.meta.id == id }
    }
}
```

### 7.3 启用/禁用流程

用户在 Settings 中切换扩展。启用集合持久化在 DataStore：

```
DataStore key: "extensions_enabled" → Set<String> of extension ids
```

`ExtensionRegistry` 监听此 preference，按需调用 `onAttach()` / `onDetach()`。未启用的扩展永不实例化 — 仅在 Settings 列表中显示元数据（来自 `discover()`）。

### 7.4 ReaderEventBus

```kotlin
// extensions/api/src/main/kotlin/dev/readflow/event/ReaderEventBus.kt
package dev.readflow.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class ReaderEvent {
    data object EngineInitialized : ReaderEvent()
    data object EngineShutdown : ReaderEvent()
    data class BookOpened(
        val bookId: Int,
        val format: String,
        val title: String,
        val totalPages: Int,
    ) : ReaderEvent()
    data class BookClosed(val bookId: Int) : ReaderEvent()
    data class PageChanged(
        val bookId: Int,
        val pageIndex: Int,
        val totalPages: Int,
        val positionCfi: String,
        val progressPercent: Float,
    ) : ReaderEvent()
    data class BookmarkAdded(
        val bookId: Int,
        val pageIndex: Int,
        val positionCfi: String,
        val text: String,
    ) : ReaderEvent()
    data class BookmarkRemoved(val bookId: Int, val pageIndex: Int) : ReaderEvent()
    data class AnnotationAdded(
        val bookId: Int,
        val pageIndex: Int,
        val selectedText: String,
        val note: String? = null,
        val color: Int = 0xFFFFEB3B.toInt(),
    ) : ReaderEvent()
    data class AnnotationRemoved(val bookId: Int, val annotationId: Long) : ReaderEvent()
    data class InkStrokeCommitted(val bookId: Int, val pageIndex: Int, val strokeCount: Int) : ReaderEvent()
    data class FontSizeChanged(val bookId: Int, val newSizeSp: Int) : ReaderEvent()
    data class ThemeChanged(val themeId: String) : ReaderEvent()
    data class BookProgressSaved(
        val bookId: Int,
        val pageIndex: Int,
        val progressPercent: Float,
        val timestamp: Long,
    ) : ReaderEvent()
    data class TtsWordHighlighted(val bookId: Int, val wordIndex: Int) : ReaderEvent()
}

class ReaderEventBus {
    private val _events = MutableSharedFlow<ReaderEvent>(
        replay = 0,                // 无粘性 — 新订阅者只收到新事件
        extraBufferCapacity = 64,  // 容忍突发（快速翻页、批量导入）
    )

    val events: SharedFlow<ReaderEvent> = _events.asSharedFlow()

    suspend fun emit(event: ReaderEvent) { _events.emit(event) }

    /**
     * 非挂起 try-emit。在非协程上下文中 fire-and-forget 使用（如 View 触摸处理）。
     * 如果缓冲满返回 false（事件丢弃）。
     */
    fun tryEmit(event: ReaderEvent): Boolean = _events.tryEmit(event)
}
```

### 7.5 扩展生命周期

```
[App process start]
    │
    ▼
ReadflowApplication.onCreate()
    │
    ├─ startKoin { modules(...) }
    │     Koin 初始化完整 DI 图。
    │
    ├─ ExtensionRegistry 创建（Koin singleton），调用 ExtensionLoader.discover()
    │     ServiceLoader.load(Extension::class.java)
    │     → 读取 META-INF/services/dev.readflow.extension.Extension
    │     → 返回 List<ExtensionMeta>
    │     // 扩展尚未实例化 — 只读元数据
    │
    ├─ ExtensionRegistry 从 DataStore 读取 enabledExtensions: Set<String>
    │     对每个启用的 id, 调用 attach(id):
    │       val ext = ExtensionLoader.load(id)   // ServiceLoader 查找 + 实例化
    │       val scope = ExtensionScope(SupervisorJob() + Dispatchers.Default)
    │       val context = ExtensionContextImpl(...)
    │       ext.onAttach(scope, context)
    │
    ▼
[App idle — 等待用户交互]

用户打开一本书:
    ReaderViewModel → engine.open(uri) → eventBus.emit(BookOpened(...))
    → TtsExtension: "新书打开 — 加载 TTS engine"
    → ReadingStatsExtension: "开始 session 计时"
    → OpdsBrowserExtension: ignores

用户翻页:
    ReaderViewModel.onNextPage() → eventBus.emit(PageChanged(...))
    → TtsExtension: "朗读当前页"
    → ReadingStatsExtension: "更新字数统计"
    → ProgressTracker: debounce 2s → 保存到 Room progressDao

用户在 Settings 中禁用扩展:
    SettingsViewModel.toggleExtension("dev.readflow.extension.tts", enabled = false)
    → ExtensionRegistry.detach(id):
        ext.onDetach()           // 扩展释放 TTS engine、停止音频
        context.unregisterAll()   // 移除 menu items + hooks
        scope.cancel()            // 取消扩展启动的所有协程
```

---

## 8. 依赖注入

### 8.1 Koin 引导

Koin 4.0.2 (`io.insert-koin:koin-android:4.0.2` + `koin-androidx-compose:4.0.2`)

```kotlin
// app/src/main/java/dev/readflow/ReadflowApplication.kt
package io dev.readflow

import android.app.Application
import dev.readflow.di.*
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class ReadflowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@ReadflowApplication)
            modules(
                // 顺序重要：基础模块在前
                prefsModule,
                databaseModule,
                calibreModule,
                booksourceModule,
                renderModule,
                eventModule,
                libraryModule,
                readerModule,
                settingsModule,
                extensionModule,
            )
        }
    }
}
```

### 8.2 各 Koin 模块

```kotlin
// app/src/main/java/dev/readflow/di/Modules.kt
package dev.readflow.di

// ─── prefsModule ─────────────────────────────────────────
val prefsModule = module {
    single<DataStore<Preferences>> {
        androidx.datastore.preferences.preferencesDataStore(
            name = "readflow_prefs"
        ).getValue(null, null)
    }
    single { ReadflowPreferences(get()) }
}

// ─── databaseModule ──────────────────────────────────────
val databaseModule = module {
    single {
        Room.databaseBuilder(get(), ReadflowDatabase::class.java, "readflow.db")
            .fallbackToDestructiveMigration()  // Phase 1; 发布前加 migration
            .build()
    }
    single { get<ReadflowDatabase>().bookDao() }
    single { get<ReadflowDatabase>().progressDao() }
    single { get<ReadflowDatabase>().annotationDao() }
    single { get<ReadflowDatabase>().bookmarkDao() }
}

// ─── calibreModule ───────────────────────────────────────
val calibreModule = module {
    single {
        val prefs: ReadflowPreferences = get()
        val baseUrl = prefs.calibreBaseUrl.first()
        if (baseUrl.isNullOrBlank()) null
        else CalibreClient(
            baseUrl = baseUrl,
            username = prefs.calibreUsername.first() ?: "",
            password = prefs.calibrePassword.first() ?: "",
        )
    }
    single { CalibreRepositoryImpl(get()) }
}

// ─── booksourceModule ───────────────────────────────────
val booksourceModule = module {
    single<BookSource> { CalibreBookSource(get()) }
    single<BookSource> { OpdsBookSource() }           // TODO: implement
    single<BookSource> { LocalFileBookSource(get()) } // uses Android Context

    single { BookSourceRegistry(getAll<BookSource>()) }
}

// ─── renderModule ───────────────────────────────────────
val renderModule = module {
    // 每个引擎按 named qualifier 注册
    single<ReaderEngine>(named("epub"))  { EpubWebViewEngine()  }  // priority 0
    single<ReaderEngine>(named("pdf"))   { PdfRendererEngine()   }  // priority 0
    single<ReaderEngine>(named("docx"))  { MuPdfDocxEngine()     }  // priority 10
    single<ReaderEngine>(named("cbz"))   { MuPdfCbzEngine()      }  // priority 10
    single<ReaderEngine>(named("txt"))   { TxtVirtualPagerEngine()} // priority 0
    single<ReaderEngine>(named("md"))    { MarkwonEngine()       }  // priority 0

    // Koin 自动将 named bindings 收集为 Set<ReaderEngine>
    single { ReaderEngineRegistry(getAll()) }
}

// ─── eventModule ────────────────────────────────────────
val eventModule = module {
    single { ReaderEventBus() }
}

// ─── libraryModule ───────────────────────────────────────
val libraryModule = module {
    viewModel { LibraryViewModel(get(), get(), get()) }
    // BookSourceRegistry + BookDao + ProgressDao
}

// ─── readerModule ────────────────────────────────────────
val readerModule = module {
    viewModel { (bookId: Int) ->
        ReaderViewModel(
            bookId = bookId,
            engineRegistry = get(),
            eventBus = get(),
            progressDao = get(),
            annotationDao = get(),
            bookmarkDao = get(),
        )
    }
}

// ─── settingsModule ─────────────────────────────────────
val settingsModule = module {
    viewModel {
        SettingsViewModel(
            prefs = get(),
            extensionRegistry = get(),
            bookSourceRegistry = get(),
        )
    }
}

// ─── extensionModule ────────────────────────────────────
val extensionModule = module {
    single {
        ExtensionRegistry(
            prefs = get(),       // ReadflowPreferences (for enabled set)
            eventBus = get(),    // ReaderEventBus (injected into ExtensionContext)
        )
    }
}
```

### 8.3 依赖图总结

```
ReadflowApplication
    │
    ├─ prefsModule          DataStore<Preferences> + ReadflowPreferences
    ├─ databaseModule       Room ReadflowDatabase + DAOs
    ├─ calibreModule        CalibreClient (nullable) + CalibreRepository
    ├─ booksourceModule     BookSourceRegistry (multi-bind BookSource)
    ├─ renderModule         ReaderEngineRegistry (multi-bind ReaderEngine)
    ├─ eventModule          ReaderEventBus
    ├─ libraryModule        LibraryViewModel
    ├─ readerModule         ReaderViewModel (parameterized: bookId)
    ├─ settingsModule       SettingsViewModel
    └─ extensionModule      ExtensionRegistry (reads prefs, owns Extension lifecycles)

Cross-cutting:
    eventBus ─── consumed by: ExtensionRegistry, ReaderViewModel, any Extension
    prefs     ─── consumed by: CalibreModule, SettingsViewModel, ExtensionRegistry
    bookSources ─ consumed by: LibraryViewModel, SettingsViewModel
```

### 8.4 MVI Reader State

```kotlin
// features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderContract.kt
package dev.readflow.features.reader

import dev.readflow.core.model.*
import dev.readflow.render.api.Locator
import android.view.View

sealed interface ReaderIntent {
    data class Open(val uri: android.net.Uri) : ReaderIntent
    data class GoTo(val locator: Locator) : ReaderIntent
    data object NextPage : ReaderIntent
    data object PreviousPage : ReaderIntent
    data class SetFontSize(val sp: Float) : ReaderIntent
    data class SetMode(val mode: ReadingMode) : ReaderIntent
    data class SetTransition(val type: TransitionType) : ReaderIntent
    data object ToggleInk : ReaderIntent
    data object ToggleUi : ReaderIntent
    data object Close : ReaderIntent
}

data class ReaderState(
    val book: BookMeta? = null,
    val documentView: View? = null,            // v2: View instead of Bitmap
    val currentLocator: Locator = Locator.Unknown,
    val pageCount: Int = 0,
    val currentPageIndex: Int = 0,
    val transition: TransitionType = TransitionType.Slide,
    val fontSize: Float = 18f,
    val isInkMode: Boolean = false,
    val isLoading: Boolean = false,
    val error: ReadflowError? = null,          // v2: typed error instead of String?
)
```

---

## 9. Gradle 基础设施

### 9.1 Version Catalog（`gradle/libs.versions.toml`）

```toml
# android/gradle/libs.versions.toml
# 单一依赖版本源。2026-06-18 更新 for readflow multi-module Android project.

[versions]
agp = "8.8.2"
kotlin = "2.1.10"
ksp = "2.1.10-1.0.31"

# Compose — 2026.05.01 是当前最新 BOM（2026.06.00 未在 Maven Central 发布）
compose-bom = "2026.05.01"
activity-compose = "1.10.1"
navigation = "2.9.4"
lifecycle = "2.9.0"

# Data
room = "2.7.1"
datastore = "1.1.4"
paging = "3.3.6"

# DI
koin = "4.0.2"

# Network
ktor = "3.1.2"

# Serialization / Concurrency
kotlinx-serialization = "1.7.3"
coroutines = "1.10.1"

# Imaging
coil = "3.1.0"

# Rendering
mupdf = "1.27.0"
markwon = "4.6.2"

# Ink (handwriting)
ink = "1.0.0-beta02"

# Testing
junit5 = "5.11.4"
mockk = "1.13.16"
androidx-test = "1.6.1"
espresso = "3.6.1"

[libraries]
# --- Compose BOM (platform) ---
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }

# --- Compose ---
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-ui-test = { module = "androidx.compose.ui:ui-test-junit4" }
compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-material-icons = { module = "androidx.compose.material:material-icons-extended" }

# --- AndroidX ---
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activity-compose" }
navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
core-ktx = { module = "androidx.core:core-ktx", version = "1.15.0" }
browser = { module = "androidx.browser:browser", version = "1.9.0" }

# --- Room ---
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }

# --- DataStore ---
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }

# --- Paging ---
paging-runtime = { module = "androidx.paging:paging-runtime", version.ref = "paging" }
paging-compose = { module = "androidx.paging:paging-compose", version.ref = "paging" }

# --- Koin ---
koin-android = { module = "io.insert-koin:koin-android", version.ref = "koin" }
koin-compose = { module = "io.insert-koin:koin-androidx-compose", version.ref = "koin" }

# --- Ktor ---
ktor-client-android = { module = "io.ktor:ktor-client-android", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }

# --- KotlinX ---
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }

# --- Coil ---
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }

# --- MuPDF ---
mupdf-fitz = { module = "com.artifex.mupdf:fitz", version.ref = "mupdf" }

# --- Markwon ---
markwon-core = { module = "io.noties.markwon:core", version.ref = "markwon" }
markwon-ext-tables = { module = "io.noties.markwon:ext-tables", version.ref = "markwon" }
markwon-ext-strikethrough = { module = "io.noties.markwon:ext-strikethrough", version.ref = "markwon" }

# --- Ink ---
ink-authoring = { module = "androidx.ink:ink-authoring", version.ref = "ink" }
ink-rendering = { module = "androidx.ink:ink-rendering", version.ref = "ink" }

# --- Testing ---
junit5 = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit5" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidx-test" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidx-test" }
espresso-core = { module = "androidx.test.espresso:espresso-core", version.ref = "espresso" }

# --- Bundles ---
[bundles]
compose = [
    "compose-ui",
    "compose-material3",
    "compose-material-icons",
    "compose-ui-tooling-preview",
    "lifecycle-runtime-compose"
]
compose-debug = [
    "compose-ui-tooling",
    "compose-ui-test-manifest"
]
room = [
    "room-runtime",
    "room-ktx"
]
ktor = [
    "ktor-client-android",
    "ktor-client-content-negotiation",
    "ktor-serialization-json"
]
test-unit = [
    "junit5",
    "mockk",
    "coroutines-test"
]

# --- Plugins ---
[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

### 9.2 Convention Plugins

存放在 `android/build-logic/convention/src/main/kotlin/`，提供 4 个 plugin 来执行分层规则：

```
android/build-logic/
├── settings.gradle.kts
└── convention/
    ├── build.gradle.kts
    └── src/main/kotlin/
        ├── ReadflowAndroidLibraryPlugin.kt   # 基类：android-library + kotlin-android
        ├── ReadflowComposePlugin.kt           # android-library + compose + serialization
        ├── ReadflowFeaturePlugin.kt           # compose + lifecycle-viewmodel + navigation
        └── ReadflowRenderPlugin.kt            # android-library + coroutines（NO Compose）
```

**LinReadsAndroidLibraryPlugin** — 所有非 Compose library 模块：
- 应用 `com.android.library` + `org.jetbrains.kotlin.android`
- `compileSdk=35, minSdk=26, targetSdk=35, jvmTarget=17`
- 施加于：`:core:calibre`、`:core:database`、`:core:prefs`、`:extensions:api`

**LinReadsComposePlugin** — 使用 Jetpack Compose 的模块：
- 继承 `LinReadsAndroidLibraryPlugin` + 添加 `kotlin-compose` + `kotlin-serialization` + Compose BOM + 测试依赖
- 施加于：`:core:ui`、`:features:library`、`:features:reader`、`:features:settings`

**LinReadsFeaturePlugin** — 功能模块：
- 继承 `LinReadsComposePlugin` + 添加 `lifecycle-viewmodel-compose` + `navigation-compose`
- 施加于：`:features:library`、`:features:reader`、`:features:settings`

**LinReadsRenderPlugin** — 渲染引擎模块：
- 继承 `LinReadsAndroidLibraryPlugin`（**不应用 Compose plugin** — 引擎产出 View/Bitmap）
- 添加 `coroutines-android` + `kotlinx-serialization`
- 施加于：`:render:api`、`:render:epub`、`:render:pdf`、`:render:txt`、`:render:animate`
- CI 检查：`./gradlew :render:epub:dependencies --configuration compileClasspath | grep compose` 必须返回空

### 9.3 `settings.gradle.kts`（模块注册）

```kotlin
// android/settings.gradle.kts
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "readflow"

// ── App ──────────────────────────────────────────────
include(":app")

// ── Core ─────────────────────────────────────────────
include(":core:model")
include(":core:calibre")
include(":core:database")
include(":core:prefs")
include(":core:ui")

// ── Render ───────────────────────────────────────────
include(":render:api")
include(":render:epub")
include(":render:pdf")
include(":render:txt")
include(":render:animate")

// ── Ink ──────────────────────────────────────────────
include(":ink")

// ── Features ─────────────────────────────────────────
include(":features:library")
include(":features:reader")
include(":features:settings")

// ── Extensions ───────────────────────────────────────
include(":extensions:api")
include(":extensions:tts")
include(":extensions:stats")
include(":extensions:opds")
```

### 9.4 Root `build.gradle.kts`

```kotlin
// android/build.gradle.kts
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
```

### 9.5 `:app` build.gradle.kts

```kotlin
// android/app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.readflow"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.readflow"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.bundles.compose.debug)

    // Koin DI
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // All project modules — app is the assembly point
    implementation(project(":core:model"))
    implementation(project(":core:calibre"))
    implementation(project(":core:database"))
    implementation(project(":core:prefs"))
    implementation(project(":core:ui"))
    implementation(project(":render:api"))
    implementation(project(":render:epub"))
    implementation(project(":render:pdf"))
    implementation(project(":render:txt"))
    implementation(project(":render:animate"))
    implementation(project(":ink"))
    implementation(project(":features:library"))
    implementation(project(":features:reader"))
    implementation(project(":features:settings"))
    implementation(project(":extensions:api"))
    implementation(project(":extensions:tts"))
    implementation(project(":extensions:stats"))
    implementation(project(":extensions:opds"))

    // Test
    testImplementation(libs.junit5)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.androidx.test.runner)
}
```

### 9.6 `:core:model` build.gradle.kts（纯 Kotlin）

```kotlin
// android/core/model/build.gradle.kts
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
```

### 9.7 构建命令速查

```bash
# 编译 debug APK
./gradlew assembleDebug

# 运行单元测试
./gradlew test

# 静态检查
./gradlew lint

# 试运行（不实际编译）
./gradlew assembleDebug --dry-run

# 列出所有模块
./gradlew projects

# 查看 app 的运行时依赖树
./gradlew :app:dependencies --configuration debugRuntimeClasspath

# CI 检查: render:epub 不能引入 Compose
./gradlew :render:epub:dependencies --configuration compileClasspath \
  | grep -q 'compose' && echo 'FAIL: render:epub leaks Compose' \
  || echo 'PASS: render:epub is Compose-free'

# 更新 Gradle wrapper
./gradlew wrapper --gradle-version 8.9
```

---

## 10. 迁移路径

### 当前状态（v1，2026-06-18）

```
android/
├── app/
│   ├── build.gradle.kts                  # 单模块，直接声明所有依赖
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/dev/readflow/
│           ├── MainActivity.kt           # 空壳 Activity
│           ├── calibre/CalibreClient.kt  # Ktor HTTP client (~150 lines)
│           └── ui/ReadflowApp.kt         # Compose 启动入口
```

共 **3 个 Kotlin 文件**，无 Room、无 ReaderEngine、无 extension 系统。

### 迁移 Phase 1：基础模块搭建（Week 1-2）

目标：建立 Gradle 多模块骨架 + 核心类型可用。

| 步骤 | 操作 | 产出 |
|------|------|------|
| 1 | 创建 `gradle/libs.versions.toml`（含全部 version + library + bundle + plugin 声明） | 单一版本源 |
| 2 | 创建 `build-logic/` + 4 个 convention plugins | 分层强制执行 |
| 3 | 创建 `settings.gradle.kts`（include 全部 19 个子模块） | 模块注册 |
| 4 | 创建 `core:model` 模块，迁移现有 `CalibreClient.kt` 中的 `BookMeta`、`SearchResult` 等 data class → `BookMeta.kt`、`SearchResult.kt`、`BookFormat.kt`、`Locator.kt` | 纯 Kotlin 类型 |
| 5 | 创建 `core:calibre` 模块，迁移 `CalibreClient.kt` → `CalibreClient.kt` + `CalibreRepository.kt` | Calibre HTTP 客户端 |
| 6 | 创建 `core:prefs` 模块 + `SettingsRepository`（DataStore） | 持久化用户设置 |
| 7 | 创建 `extensions:api` 模块 + `BookSource` 接口 + `ReaderEventBus` + `ReaderEvent` sealed hierarchy | 扩展 SPI |
| 8 | 更新 `:app` 的 `build.gradle.kts` 依赖新模块（先引入 core 模块，保留单 Activity） | 模块化但不改功能 |

**Phase 1 结束状态**: `./gradlew assembleDebug` 成功编译，功能与迁移前完全一致。此时仍是单 Activity 空壳。

### 迁移 Phase 2：渲染引擎（Week 3-4）

| 步骤 | 操作 | 产出 |
|------|------|------|
| 9 | 创建 `render:api` 模块 + `ReaderEngine` 接口 + `Locator` sealed hierarchy + `ReaderEngineRegistry` | 渲染契约 |
| 10 | 创建 `render:epub` 模块 + `EpubWebViewEngine`（WebView + epub-ts） | EPUB 阅读 |
| 11 | 创建 `render:pdf` 模块 + `PdfRendererEngine`（PdfRenderer） | PDF 阅读 |
| 12 | 创建 `render:txt` 模块 + `TxtVirtualPagerEngine`（RecyclerView + Paging3） | TXT 阅读 |
| 13 | 创建 `render:animate` 模块 + `CurlPageTransformer`（ViewPager2.PageTransformer） | 翻页动效 |
| 14 | 创建 `core:database` 模块 + Room schema（4 tables）+ DAOs | 本地持久化 |
| 15 | 搭建 `features:library` + `LibraryScreen` + `LibraryViewModel` | 书库界面 |
| 16 | 搭建 `features:reader` + `ReaderScreen` + `ReaderViewModel`（MVI）+ `ReaderRootLayout`（混合 View） | 阅读器界面 |
| 17 | 搭建 `features:settings` + `SettingsScreen` + `SettingsViewModel` | 设置界面 |
| 18 | 创建 `:app` 的 `MainActivity`（Navigation host）+ `LinReadsApplication`（Koin 启动） | 可运行的 App |

**Phase 2 结束状态**: 用户可浏览 Calibre 书库、打开 EPUB/PDF/TXT、翻页阅读、调整字号/主题。这是 MVP。

### 迁移 Phase 3：扩展系统 + Ink（Week 5-6）

| 步骤 | 操作 | 产出 |
|------|------|------|
| 19 | 创建 `extensions:tts` + `TtsExtension`（Android TTS） | 朗读 |
| 20 | 创建 `extensions:stats` + `ReadingStatsExtension` | 阅读统计 |
| 21 | 创建 `extensions:opds` + `OpdsBookSource` | OPDS 书源 |
| 22 | 创建 `:ink` 模块 + `InkOverlay` 接口 + `InkAnchor` 模型 | Ink 契约 |
| 23 | 实现 `CanvasView` + `InProgressStrokesView`（androidx.ink）+ 触摸路由集成 | 手写笔迹 |
| 24 | `:app` 整合所有 DI module + extension lifecycle + ink toolbar | 完整功能 |

**Phase 3 结束状态**: 所有功能可用。对标 v1 架构文档中的完整设计。

### 代码移动映射（v1 → v2）

| v1 文件 | v2 文件 | 变化 |
|---------|---------|------|
| `CalibreClient.kt` | `core:calibre/.../CalibreClient.kt` | 提取类型到 `core:model`，接口化 `BookSource` |
| `CalibreClient.kt` 中的 data class | `core:model/.../BookMeta.kt`、`SearchResult.kt` | 加 `seriesIndex`、`cover`、`lastModified` 字段 |
| `LinReadsApp.kt` | `core:ui/.../LinReadsTheme.kt` | 主题提取为共享模块 |
| `MainActivity.kt` | `app/.../MainActivity.kt` + `LinReadsApplication.kt` | Koin 引导 + Navigation host |
| （无） | `features:reader/.../ReaderRootLayout.kt` | 新建：混合 View 触摸路由 |
| （无） | `features:reader/.../ReaderViewModel.kt` | 新建：MVI 单向数据流 |
| `build.gradle.kts` | 多个 `build.gradle.kts` + `libs.versions.toml` | 单模块 → 多模块 |

---

## 11. 审计消解清单

来自最终审计的 BLOCKERS、HIGH、MEDIUM 项在此逐一消解。

### BLOCKERS（阻断项，须在 Phase 1 消除）

| ID | 问题 | v2 消解方案 | 关联文件 |
|----|------|------------|---------|
| B1 | v1 "零 WebView" 原则迫使 EPUB 用 MuPDF JNI 渲染 — 但 epub-ts 成熟度、CFI 精度、CSS 灵活性远超 MuPDF 的 Bitmap 方案 | **已推翻**。`render:epub` 使用 `WebView + epub-ts 0.3.x`。`ReaderEngine.createView()` 返回 View，不强制 Bitmap 路径。详见第 4.3 节 EPUB 规格。 | `render/epub/.../EpubWebViewEngine.kt` |
| B2 | v1 自研 AnimationEngine 无跟手拖拽 + 每帧分配 Bitmap | **已替换**。采用 `ViewPager2 + PageTransformer`。自带跟手拖拽、离屏预渲染、零额外分配。详见第 4.4 节。 | `render/animate/.../CurlPageTransformer.kt` |
| B3 | Calibre API 类型在三端各写一份，`series_index`/`cover`/`last_modified` 缺失 | **已修复**。`shared/api/calibre-contract.schema.json` → quicktype 生成 Kotlin + TypeScript。`BookMeta` 包含所有缺失字段。CI gate 防止分歧。详见第 6 节。 | `shared/api/calibre-contract.schema.json`、`core/model/.../BookMeta.kt` |
| B4 | 单模块架构无分层强制执行 — `MainActivity` 可 import Room DAO | **已修复**。20 模块 + 8 层分层 + convention plugin 强制依赖方向。CI lint 抓超标 import。详见第 2 + 9 节。 | `build-logic/convention/...`、`settings.gradle.kts` |
| B5 | `error: String?` 无结构化错误上下文 | **已修复**。`LinReadsError` sealed class hierarchy + `LinReadsResult<T>` monad。详见第 6.2 节。 | `core/model/.../LinReadsError.kt` |

### HIGH（高优先，Phase 2 消除）

| ID | 问题 | v2 消解方案 | 关联文件 |
|----|------|------------|---------|
| H1 | TXT 分页无字符集检测 | `TxtVirtualPager` 加 `CharsetDetector`（ICU4J 或 `java.nio.charset.Charset` UTF-8/GBK/Shift-JIS fallback chain） | `render/txt/.../CharsetDetector.kt` |
| H2 | markdown 无表格/删除线支持 | 加 `markwon-ext-tables` + `markwon-ext-strikethrough`（已在 `libs.versions.toml` 中声明） | `render/markdown/.../MarkwonConfig.kt` |
| H3 | 无网络超时重试策略 | `CalibreClient` 加 `HttpRequestRetry` plugin（Ktor），指数退避 3 次 | `core/calibre/.../CalibreClient.kt` |
| H4 | 进度 sync 策略未指定为 LWW | `shared/api/` 文档明确 LWW 策略 + `updatedAt` 字段。Android 端 `ReadingProgressRepository.saveProgress()` 实现 LWW 合并。 | `shared/api/sync-strategy.md`、`core/database/.../ReadingProgressRepository.kt` |

### MEDIUM（中优先，Phase 3 消除）

| ID | 问题 | v2 消解方案 | 关联文件 |
|----|------|------------|---------|
| M1 | 无阅读统计（时长、速度、完本） | `extensions:stats` 模块 — `ReadingStatsExtension` 追踪 `BookOpened`/`PageChanged`/`BookClosed` 事件并导出 JSON | `extensions/stats/.../ReadingStatsExtension.kt` |
| M2 | 无 OPDS 书源支持 | `BookSource` 接口 + `extensions:opds` 模块 — `OpdsBookSource` 解析 OPDS 1.2/2.0 feed | `extensions/opds/.../OpdsBookSource.kt` |
| M3 | 无 TTS 朗读 | `extensions:tts` 模块 — `TtsExtension` 使用 Android TTS framework，通过 `ReaderEventBus.PageChanged` 触发朗读 | `extensions/tts/.../TtsExtension.kt` |
| M4 | Manifest 无 `usesCleartextTraffic` — LAN Calibre 可能被系统拦截 | `AndroidManifest.xml` 加 `android:usesCleartextTraffic="true"` + `network_security_config.xml` 白名单 `192.168.0.0/16` | `app/src/main/AndroidManifest.xml` |

---

## 附录 A：v1 vs v2 关键决策对照表

| 维度 | v1 (`docs/android-architecture.md`) | v2 (本文档) | 变更原因 |
|------|-------------------------------------|------------|---------|
| EPUB 渲染 | MuPDF JNI → Bitmap | WebView + epub-ts 0.3.x | CFI 精度、CSS 灵活性、JS 生态 |
| 翻页动画 | 自研 `AnimationEngine`（vsync loop + Bitmap/帧） | ViewPager2 + PageTransformer | 跟手拖拽、零额外分配、AndroidX 成熟度 |
| 模块化 | 单模块 + 包约定 | 20 个 Gradle 子模块 + convention plugin | 分层强制执行、编译期隔离 |
| 文档渲染基元 | `Bitmap`（`engine.renderPage() → Bitmap`） | `View`（`engine.createView() → View`） | 混合 View hierarchy 无 Bitmap↔ImageBitmap 拷贝 |
| 错误处理 | `error: String?` | `LinReadsError` sealed hierarchy + `LinReadsResult<T>` | 结构化错误上下文 |
| 书源 | `CalibreClient` 具体类被 UI 层直接调用 | `BookSource` 接口 + `BookSourceRegistry` | 可扩展到 OPDS/本地文件，OCP 原则 |
| 扩展系统 | 无 | ServiceLoader + `Extension` SPI + `ReaderEventBus` | 可插拔 TTS/统计/OPDS |
| 依赖注入 | Koin（无注解处理器） | Koin 4.0.2 + multi-bind + 参数化 ViewModel | 同上，增加 multi-bind 和 ViewModel 参数支持 |
| 位置模型 | ad-hoc `(cfi, percentage, pageIndex)` 元组 | `Locator` sealed hierarchy（对标 Readium） | 跨平台互操作 |
| DOCX 渲染 | MuPDF | MuPDF（不变） | — |
| PDF 渲染 | PdfRenderer | PdfRenderer（不变） | — |
| TXT 渲染 | TxtVirtualPager + Paging3 | RecyclerView + TxtVirtualPager + Paging3（架构不变） | — |
| MD 渲染 | Markwon Spannables | Markwon Spannables（不变）+ 扩展 table/strikethrough | — |
| Ink | Phase 2, `androidx.ink` | Phase 2, `androidx.ink` + 两层 Canvas + InkAnchor 模型 | 增加 Text-mode anchor for EPUB reflow |

## 附录 B：关键文件路径汇总

```
android/
├── gradle/
│   └── libs.versions.toml                          # 单一依赖版本源
├── build-logic/                                     # Convention plugins
│   ├── settings.gradle.kts
│   └── convention/
│       ├── build.gradle.kts
│       └── src/main/kotlin/
│           ├── ReadflowAndroidLibraryPlugin.kt      # 基础 library plugin
│           ├── ReadflowComposePlugin.kt              # Compose plugin
│           ├── ReadflowFeaturePlugin.kt              # Feature plugin
│           └── ReadflowRenderPlugin.kt               # Render plugin（NO Compose）
├── build.gradle.kts                                 # Root build
├── settings.gradle.kts                              # Module registration
├── app/
│   ├── build.gradle.kts                             # App assembly
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/xml/network_security_config.xml       # LAN Calibre 白名单
│       └── java/dev/readflow/
│           ├── ReadflowApplication.kt               # Koin bootstrap
│           ├── MainActivity.kt                      # Navigation host
│           └── di/Modules.kt                        # All Koin modules
├── core/
│   ├── model/src/main/kotlin/dev/readflow/core/model/
│   │   ├── BookMeta.kt                              # 包含 seriesIndex, cover, lastModified
│   │   ├── SearchResult.kt
│   │   ├── BookFormat.kt
│   │   ├── Locator.kt                               # Readium Locator sealed hierarchy
│   │   ├── CalibreConfig.kt
│   │   ├── ThemeMode.kt
│   │   ├── TransitionType.kt
│   │   └── ReadflowError.kt                         # Sealed error hierarchy
│   ├── calibre/.../CalibreClient.kt                 # Ktor HTTP, migrated from v1
│   ├── calibre/.../CalibreRepository.kt             # Repository interface + impl
│   ├── database/.../ReadflowDatabase.kt             # Room DB + 4 entities + 4 DAOs
│   ├── prefs/.../ReadflowPreferences.kt             # DataStore typed accessor
│   └── ui/.../
│       ├── ReadflowTheme.kt                         # Material3 theme
│       ├── Typography.kt                            # Reader fonts
│       └── components/                              # Shared composables
├── render/
│   ├── api/.../ReaderEngine.kt                      # Core engine interface
│   ├── api/.../ReaderEngineRegistry.kt              # Weight-based discovery
│   ├── epub/.../EpubWebViewEngine.kt                # WebView + epub-ts
│   ├── pdf/.../PdfRendererEngine.kt                 # PdfRenderer
│   ├── txt/.../
│   │   ├── TxtVirtualPagerEngine.kt                 # RecyclerView + Paging3
│   │   └── TxtVirtualPager.kt                       # ByteOffset boundary scanning
│   └── animate/.../CurlPageTransformer.kt           # ViewPager2.PageTransformer
├── ink/.../
│   ├── InkOverlay.kt                                # Ink manager interface
│   └── InkAnchor.kt                                 # Page/Text anchor models
├── features/
│   ├── library/.../
│   │   ├── LibraryScreen.kt
│   │   └── LibraryViewModel.kt
│   ├── reader/.../
│   │   ├── ReaderScreen.kt
│   │   ├── ReaderRootLayout.kt                      # Hybrid View touch routing
│   │   ├── ReaderViewModel.kt                       # MVI unidirectional data flow
│   │   └── ReaderContract.kt                        # ReaderIntent + ReaderState
│   └── settings/.../
│       ├── SettingsScreen.kt
│       └── SettingsViewModel.kt
└── extensions/
    ├── api/.../
    │   ├── Extension.kt                             # Extension SPI
    │   ├── ExtensionLoader.kt                        # ServiceLoader discovery
    │   ├── ExtensionRegistry.kt                      # Lifecycle manager
    │   ├── ExtensionContext.kt                       # Sandboxed context
    │   ├── BookSource.kt                             # Book source interface
    │   ├── ReaderEventBus.kt                         # Shared event bus
    │   └── ReaderEvent.kt                            # Sealed event hierarchy
    ├── tts/.../TtsExtension.kt                       # Android TTS
    ├── stats/.../ReadingStatsExtension.kt            # Reading analytics
    └── opds/.../OpdsBookSource.kt                    # OPDS catalog parser

shared/
└── api/
    ├── calibre-contract.schema.json                  # JSON Schema source of truth
    ├── calibre-contract.ts                           # TypeScript types (generated)
    └── package.json                                  # quicktype generate script
```
