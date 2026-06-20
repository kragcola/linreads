# Platform: Android ⚠️ DEPRECATED

> **本文档已废弃。** 描述的架构（零 WebView、MuPDF Bitmap、自研 AnimationEngine、纯 Compose）已被 Hybrid View 架构取代。所有设计决策以 **[Android Architecture v4](../android-architecture-v4.md)** 为准（取代 v2/v3）。本文仅保留作为 v1 设计历史参考。

## v1 技术栈（历史参考）

| 类别 | 选型 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.1+ |
| UI | Jetpack Compose | BOM 2024+ |
| HTTP | Ktor Client | 3.x |
| 本地存储 | Room | 2.7.1 |
| 设置 | DataStore Preferences | 1.1.4 |
| DI | Koin | 4.0.2 |
| 导航 | Navigation Compose | 2.8.9 |
| 图片加载 | Coil 3 | 3.1.0 |
| 分页 | Paging 3 | 3.3.6 |
| EPUB/PDF/DOCX | MuPDF (fitz) | 1.27.0 |
| Markdown | Markwon | 4.6.2 |
| 手写笔 | androidx.ink | 1.0.0 (Phase 2) |

## 分层架构

```
UI Layer:    Compose + Navigation (MVVM for Library/Settings, MVI for Reader)
Render Layer: ReaderEngine interface → MuPDF | TxtVirtualPager | Markwon
              AnimationEngine (vsync-driven, bitmap-in/out)
              InkOverlay (Phase 2)
Data Layer:  CalibreRepository (Ktor) | BookshelfRepository (Room) | SettingsRepository (DataStore)
DI:          Koin modules
```

## 渲染引擎

### 统一接口

```kotlin
interface ReaderEngine {
    val format: BookFormat        // EPUB, PDF, TXT, MD, DOCX, DOC
    val pageCount: Int
    suspend fun open(uri: Uri)
    suspend fun renderPage(index: Int, widthPx: Int, heightPx: Int): Bitmap
    fun positionOf(index: Int): ReaderPosition
    fun close()
}
```

### 各格式策略

| 格式 | 引擎 | 渲染模式 | 大文件策略 |
|------|------|---------|------------|
| EPUB | **MuPDF**（JNI） | 分页 Bitmap | 流式加载，MuPDF 内部管理内存 |
| PDF | **PdfRenderer**（系统） | 分页 Bitmap | 逐页光栅化，LRU 缓存 3 页 |
| DOCX | **MuPDF**（JNI） | 分页 Bitmap | 同上 |
| TXT | **TxtVirtualPager**（自研 ~300行） | 滚动/分页 | byteOffset 分页，Paging3 |
| MD | **Markwon** | 滚动 | 按标题分 section 懒加载 |
| DOC | DocumentReader（文本提取） | 滚动 | 文本流，无内存问题 |

### MuPDF 选型理由

- **AGPL**，个人项目免费；支持 EPUB · PDF · XPS · CBZ · DOCX
- `com.artifex.mupdf:fitz`（Maven Central）低级 JNI 绑定，细粒度控制
- 每页输出 Bitmap → 与 AnimationEngine 零摩擦对接
- KOReader 同款引擎，在 Android e-ink 设备上验证多年
- EPUB font-size：通过 `fitz.Document.setMetadata("style", "body{font-size:18pt}")` 注入 CSS

### PDF 保持系统 PdfRenderer

MuPDF 也能处理 PDF，但 Android 系统 `PdfRenderer` 零依赖，API35+ 升级 PDFium 内核支持文字选择。保持轻量，PDF 走系统原生。

### TXT：TxtVirtualPager（自研 ~300 行）

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

## 自研动画引擎

### 设计原则

- **vsync 对齐**：`withFrameNanos`（Choreographer tick），非轮询
- **bitmap in/out**：从 / 到页面各一张 Bitmap，与渲染引擎解耦
- **GPU 加速**：Compose `graphicsLayer` 走 RenderThread，无需 SurfaceView
- **可插拔 Transition**：interface + enum，运行时切换

### Transition 类型

| Transition | 描述 |
|-----------|------|
| `SlideTransition` | 水平滑动（默认） |
| `CurlTransition` | 贝塞尔卷页物理（~120行） |
| `FadeTransition` | 淡入淡出 |

### CurlTransition 物理模型

卷页不用 `DecelerateInterpolator` 糊弄，需要真实纸张弯曲物理：
- 卷折点 `(cx, cy)` 沿屏幕右边缘 → 左侧移动
- 翻转区域：三次贝塞尔曲面（控制点由 cx 推算）
- 阴影：曲线法线方向线性衰减
- 背面：to.bitmap 镜像 matrix

## MVI Reader State

预取策略：`current` 就绪后，后台协程立即渲染 `index + 1` 存入 `prefetched`。翻页动画启动时两张 Bitmap 已备好，动画无需等待渲染。

## 手写笔注记（Phase 2）

使用 `androidx.ink` 1.0.0，笔/手指自动路由，无需模式切换：

```kotlin
override fun onTouchEvent(e: MotionEvent): Boolean {
    return when (e.getToolType(0)) {
        MotionEvent.TOOL_TYPE_STYLUS,
        MotionEvent.TOOL_TYPE_ERASER -> {
            inkLayer.handleStylusEvent(e)   // 笔 → 手写层
            true
        }
        else -> false  // 手指 → 文档滚动/点击
    }
}
```

视图层次：
```
FrameLayout (AnnotationContainer)
  ├── WebView / RecyclerView（文档渲染层）
  ├── CanvasView（已完成笔画，标准双缓冲）
  └── InProgressStrokesView（进行中笔画，前缓冲区，透明叠加）
```

存储：`ink-storage` 序列化的 `StrokeInputBatch` → Room `ByteArray` 列，比 JSON 小约 70%。

## 已知局限与演进路径

| 局限 | 影响 | Phase 2 方案 |
|------|------|-------------|
| MuPDF EPUB 字号调整需 CSS 注入 | 字号切换后需重新渲染全部页 | 自研 EPUB 反排引擎 |
| MuPDF bitmap 模式无原生文字选择 | EPUB/DOCX 无法划词标注 | InkOverlay + 坐标映射反查 |
| DOC 只做文本提取 | 格式信息丢失 | 提示用户转 DOCX |
| CurlTransition 无动态 follow-finger | 翻页不跟手 | VelocityTracker + 手势驱动进度 |

## 与竞品对比

| 维度 | 静读天下 | LinReads |
|------|---------|---------|
| 渲染模式 | MRTextView 一统（Java 97KB） | 格式独立，MuPDF + 原生 |
| EPUB 字体 | 成熟，多年调优 | Phase 1 CSS 注入，Phase 2 自研 |
| 动画 | 黑盒 | 自研 vsync 驱动，物理卷页 |
| WebView | 无（Native Java 渲染） | 无（全原生） |
| 可测试性 | 差（MRTextView 紧耦合） | MVI + Engine interface 完全可 mock |
| Calibre | 无 | 原生 REST API，核心差异点 |

---

_参考：_ [docs/android-architecture.md](../android-architecture.md) · [Research: Stylus](Research-Stylus.md) · [Rendering Engine](Rendering-Engine.md)
