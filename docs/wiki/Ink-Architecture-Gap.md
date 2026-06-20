# 笔写支持：当前架构断裂分析

> 审计日期：2026-06-18
> 触发需求：「阅读 + 跟手的手写笔支持」——用户的核心双需求，非可选附加
> 结论：**当前架构无法同时支持阅读和跟手笔写。存在 5 个结构性断裂点，需在设计层重新定义。**

---

## 一、场景追踪：五个核心交互的断裂点

### 场景 1：在 EPUB 页面上书写

**用户动作**：正在读 EPUB，拿起笔在某个段落旁写注记。

**当前设计链路**：
```
1. MuPDF 渲染 EPUB → Bitmap
2. Compose Image(bitmap.asImageBitmap()) 显示
3. 用户用 stylus 触碰屏幕
4. onTouchEvent() 检测 TOOL_TYPE_STYLUS
5. inkLayer.handleStylusEvent(e) → 写入 InProgressStrokesView
```

**🔴 断裂点**：第 2 步和第 5 步不兼容。

`InProgressStrokesView` 是 Android View（`extends View`），需要挂载在 `FrameLayout` 视图树中。但 Compose `Image` composable 在 Compose 渲染树中，两者之间没有直接的 Z-order 关系。

文档中写的视图层次：
```
FrameLayout (AnnotationContainer)
  ├── WebView / RecyclerView（文档渲染层）
  ├── CanvasView（已完成笔画）
  └── InProgressStrokesView（进行中笔画）
```

这个层次假设文档渲染层是 **WebView 或 RecyclerView**——都是 Android View。但实际设计用的是 MuPDF → Bitmap → Compose `Image`，Compose `Image` **不是 View**，无法放入上述 FrameLayout 层次。

**要么改渲染路径，要么改 ink 方案**——当前设计同时选了"Compose 显示 Bitmap"和"View 系统 ink"，两者互斥。

### 场景 2：改字体大小后，已有的笔迹去哪了？

**用户动作**：
1. 18pt 字号下，在 EPUB 第 5 页第 3 段旁写了一行注记
2. 调大字号到 22pt
3. MuPDF 重新渲染所有页面——文本重排，原来的第 3 段现在在第 7 页第 1 段
4. 那行笔迹应该出现在哪里？

**当前设计**：
```kotlin
@Entity(tableName = "annotations")
data class AnnotationEntity(
    val docId: String,
    val pageRef: String,       // EPUB: chapterId
    val strokeData: ByteArray,
    val updatedAt: Long,
)
```

**🔴 断裂点**：`pageRef = chapterId` 只是章节级定位，不含段落级锚点。

当文本重排（reflow）后：
- 页面边界改变 → 原来的 page 5 不再等同于新 page 5
- 笔迹存储的是**页面坐标**（相对于 page 5 的 x, y），但 page 5 的物理内容已经变了
- 需要的是**内容坐标**（相对于段落第 3 句第 2 个字的 x, y 偏移），但当前设计没有这个抽象

**这是 EPUB 手写的最难问题**——即使在 GoodNotes/Notability 这种成熟产品中，也只支持 PDF（固定布局），不支持 reflowable EPUB 的手写注记。Apple Books 的 EPUB 手写是通过把笔迹转为"浮动图片"锚定在文本流中来处理的。

### 场景 3：缩放后，笔迹坐标如何变换？

**用户动作**：
1. PDF 页面 100% 大小，在某个公式旁写了一个圈
2. 双指放大到 200%
3. 那个圈应该同步放大，还是保持原始大小？
4. 如果在 200% 下写新笔迹，存储坐标是屏幕坐标还是页面坐标？

**当前设计**：**完全未处理缩放场景**。

`ReaderState` 没有 `zoomLevel`、`panOffset` 字段。`ReaderEngine.renderPage()` 接受 `widthPx, heightPx`，但没有 zoom 参数。笔迹存储 `strokeData` 是 `InProgressStrokesView` 产生的屏幕坐标序列。

**断裂点**：笔迹坐标系统未经定义。需要明确三种坐标：
- **Screen coordinates**：手指/笔在屏幕上的位置
- **Page coordinates**：相对于 PDF/EPUB 页面（0,0 → pageW, pageH）的位置，独立于 zoom
- **Content coordinates**：相对于文档内容的位置（reflow 安全）

### 场景 4：笔迹进行中，触发翻页

**用户动作**：
1. 正在第 5 页用笔写笔记
2. 手指不小心滑了一下，触发翻页手势
3. 笔迹还没提笔（ACTION_UP 未触发），但页面要切换了

**当前设计**：
```kotlin
sealed interface ReaderIntent {
    object NextPage : ReaderIntent
    object PreviousPage : ReaderIntent
    object ToggleInk : ReaderIntent  // ← ink 是"模式"，不是"状态"
}
```

**🔴 断裂点**：`ToggleInk` 暗示笔写是一个模式开关。但笔/手指自动路由的设计又说不需要模式切换。这是内在矛盾。

缺少的逻辑：
1. `isStylusActive: Boolean` ——当前是否有进行中的笔划
2. 如果有进行中笔划，拦截手指翻页手势
3. `ACTION_UP` 后自动提交笔迹到 `CanvasView` + Room
4. 提交完成后才允许页面跳转

### 场景 5：翻页后再翻回来，笔迹还在吗？

**用户动作**：
1. 在第 5 页写字
2. 翻到第 6 页
3. 翻回第 5 页
4. 刚才写的字应该还在

**当前设计**：部分支持——`AnnotationEntity` 有 `pageRef` 字段，理论上可以按 `pageRef` 查询并重新渲染。

但问题在于：
- `InProgressStrokesView` 的笔迹提交后去哪里？文档说去 `CanvasView`（已完成笔画 View）
- 翻页时 `CanvasView` 是否被销毁？
- 翻回来时如何重建 `CanvasView` 并重新渲染之前的笔迹？
- 重新渲染时，页面 Bitmap 已经重新生成过（MuPDF → 新 Bitmap），笔迹叠加在新 Bitmap 上是否还需要 `CanvasView`？

**🟡 部分断裂**：存储有设计，但渲染重建流程未定义。

---

## 二、三个核心架构矛盾

### 矛盾 1：Compose Bitmap 渲染 ↔ View 系统 ink

```
┌─────────────────────────────────────────────────┐
│  设计 A：Compose Image(bitmap) 显示文档           │
│  设计 B：FrameLayout > InProgressStrokesView     │
│  问题：A 和 B 在不同的渲染树上，无法叠加           │
└─────────────────────────────────────────────────┘
```

**只有两种解法**：

| 方案 | 做法 | 代价 |
|------|------|------|
| **文档也走 View** | 用 `AndroidView` 包装文档渲染，放入 FrameLayout，ink View 叠在上面 | 失去 Compose 动画的 `graphicsLayer` 优势 |
| **ink 也走 Compose** | 用 Compose `Canvas` + `pointerInput` 实现手写，放弃 `InProgressStrokesView` 的前缓冲区低延迟 | 笔迹延迟从 ~9ms 增加到 ~30-50ms |

不存在"两者兼得"的方案。androidx.ink 的 `InProgressStrokesView` 依赖 View 系统的前缓冲区渲染——这是它的核心价值，也是它的平台限制。

### 矛盾 2：ToggleInk 模式 ↔ 笔/手指自动路由

```
┌─────────────────────────────────────────────────┐
│  设计 A：ToggleInk → isInkMode: Boolean          │
│  设计 B：笔自动写，手指自动翻页（无需模式切换）     │
│  问题：ReaderState 建模了 A，触控路由代码实现了 B  │
└─────────────────────────────────────────────────┘
```

如果笔写是自动检测的（`TOOL_TYPE_STYLUS → inkLayer`），那 `isInkMode` 就不应该是一个 toggle，而应该是 `isStylusPresent: Boolean`（反映硬件状态）+ `activeStrokeId: String?`（反映正在进行的笔划）。

### 矛盾 3："零 WebView" ↔ 笔写叠加

```
┌─────────────────────────────────────────────────┐
│  设计 A：零 WebView（纯原生 Compose + MuPDF）     │
│  设计 B：ink 覆盖层需要 View 树宿主               │
│  问题：如果文档不走 WebView，ink View 的宿主是什么？│
└─────────────────────────────────────────────────┘
```

文档的手写架构图明确画了 `WebView / RecyclerView（文档渲染层）` 在 FrameLayout 中。这说明原设计者**假设了文档渲染层是 View 系统组件**。但 MuPDF bitmap 方案走的是 Compose `Image`，不兼容。

**如果保持"零 WebView"，要么放弃 Compose 用纯 View 显示 Bitmap（`ImageView`），要么放弃 `InProgressStrokesView` 用 Compose Canvas 手写。**

---

## 三、最核心的未解决问题：Reflow EPUB 上的笔迹锚定

这是整个领域的难题，不是 LinReads 独有的。但当前设计完全回避了它。

### 问题定义

```
EPUB 页面 = MuPDF 根据 (HTML内容, CSS, 字号, 屏幕宽度) 动态排版的结果
字号改变 → 页面内容重排 → 页面边界改变 → 页面上的 (x,y) 坐标失去意义
```

### 三种可能的解决方案

| 方案 | 做法 | 优点 | 缺点 |
|------|------|------|------|
| **A. 禁止手写** | EPUB 只读，笔写仅在 PDF/DOCX 等固定布局格式可用 | 零实现成本 | 用户需求是"阅读+笔写"，不是"PDF+笔写" |
| **B. 文本锚定** | 笔迹存储为 `(textOffset, xOffset, yOffset)`，即"距离第 N 个字符右方 x px 下方 y px" | 字号安全，重排安全 | 实现极复杂，需要反向定位引擎 |
| **C. 固定布局** | EPUB 渲染时锁定字号/宽度，把当前排版结果当作"固定页面"处理 | 实现简单 | 失去 reflow EPUB 的核心价值——动态字号 |

**当前设计没有选择任何一个方案**。`AnnotationEntity.pageRef = chapterId` 暗示的是介于 B 和 C 之间的模糊地带——粒度到章节但不锚定到文字。

### GoodNotes/Notability/Apple Books 怎么做的？

- **GoodNotes/Notability**：只支持 PDF。规避了 reflow 问题。
- **Apple Books**：EPUB 手写笔迹转为"浮动图片"，通过 CSS `position: absolute` 嵌入 HTML 流。重排时图片跟随文本流移动。
- **Kindle Scribe**：EPUB 手写锚定在"单词"级别，通过 Kindle Format 8 的 `kfx` 定位。
- **KOReader**：不支持手写注记。

**LinReads 对手写需求比上述所有产品都更激进**——要求在 reflow EPUB 上做跟手笔写。这个问题的难度不应该被低估。

---

## 四、修正方案

### 推荐架构：Hybrid View 方案

```
Activity (ComponentActivity)
  └── FrameLayout (根视图)
        ├── AndroidView (Compose → View 桥)  ← Reader 主体区域
        │     └── FrameLayout (ReaderContainer)
        │           ├── [文档渲染层]  ← ImageView / WebView / RecyclerView
        │           │    EPUB: WebView (放弃"零 WebView")
        │           │    PDF:  ImageView + PdfRenderer
        │           │    TXT:  RecyclerView + TxtVirtualPager
        │           │    MD:   TextView + Markwon
        │           ├── CanvasView (已完成笔迹，标准双缓冲)
        │           └── InProgressStrokesView (进行中笔迹，前缓冲区)
        └── ComposeView (Compose overlay)  ← 工具栏/菜单/设置面板
```

**关键改变**：
1. **EPUB 走 WebView**（epubjs 或 Readium navigator），有完整的 CSS/文字选择/链接支持
2. **文档渲染层全部是 View 系统组件**，可与 ink View 在同一个 FrameLayout 中叠加
3. **Compose 仅用于 UI overlay**（工具栏、设置面板）——这是 Mihon 的做法（ReaderActivity 是 View，菜单是 Compose）
4. MuPDF 保留给 PDF/CBZ/DOCX（这些格式不 reflow，笔迹锚定在 page 坐标即可）

### 笔迹坐标系统

```kotlin
sealed interface InkAnchor {
    /** 固定布局（PDF/CBZ/DOCX）：锚定在页面坐标 */
    data class Page(
        val pageIndex: Int,
        val pageWidth: Float,   // 渲染时的页面尺寸（用于缩放还原）
        val pageHeight: Float,
    ) : InkAnchor

    /** Reflow EPUB：锚定在文本位置 */
    data class Text(
        val chapterHref: String,
        val textOffset: Int,     // 章节内的字符偏移
        val xOffsetPx: Float,    // 距字符右方的像素偏移
        val yOffsetPx: Float,    // 距字符基线的像素偏移
        val fontSize: Int,       // 书写时的字号（用于缩放还原）
        val pageWidth: Int,      // 书写时的页面宽度（用于重排检测）
    ) : InkAnchor
}

@Entity(tableName = "ink_strokes")
data class InkStrokeEntity(
    @PrimaryKey val id: String,
    val docId: String,
    val anchor: InkAnchor,           // ← 替换模糊的 pageRef: String
    val strokeData: ByteArray,       // ink-storage 序列化
    val strokeBounds: Rect,          // 笔迹包围盒（页面坐标，用于快速查询）
    val createdAt: Long,
    val updatedAt: Long,
)
```

### ReaderState 修正

```kotlin
data class ReaderState(
    val book: Book? = null,
    val current: PageContent? = null,
    val prefetched: List<PageContent> = emptyList(),  // 双向预取，非单向
    val position: ReaderPosition = ReaderPosition.Start,
    val zoomLevel: Float = 1.0f,        // ← 新增
    val panOffset: Offset = Offset.Zero, // ← 新增
    val isStylusNearby: Boolean = false, // ← 替换 isInkMode
    val activeStrokeId: String? = null,  // ← 新增：进行中的笔划
    val pageInkStrokes: List<InkStrokeEntity> = emptyList(), // ← 新增：当前页笔迹
    val transition: TransitionType = TransitionType.Slide,
    val fontSize: Int = 18,
    val error: String? = null,
)
```

### Phase 分期修正

| Phase | 内容 | 依赖 |
|-------|------|------|
| **Phase 1A** | 修正视图架构：hybrid View + Compose。EPUB 走 WebView。ink 基础叠加 | 放弃"零 WebView"原则 |
| **Phase 1B** | `InkAnchor.Page` 实现：PDF 上可写，page 坐标存储，缩放/平移安全 | Phase 1A |
| **Phase 2** | `InkAnchor.Text` 实现：EPUB 文本锚定，重排安全 | 需要 WebView JS bridge 定位文字 |
| **Phase 3** | 笔势翻页（stylus swipe near edge → page turn） + 跟手卷页 | 需要 VelocityTracker 集成 |

### 放弃的设计

| 放弃项 | 原因 |
|--------|------|
| "零 WebView" | EPUB 渲染 + 笔写叠加都需要 WebView，这是 Readium 和 Mihon 的共同选择 |
| Compose `Image(bitmap)` 作为主渲染路径 | 与 View 系统 ink 不可调和 |
| `AnimationEngine` 的独立地位 | 卷页动画降为 WebView 的 CSS transition 或 ViewPager2 的 PageTransformer |
| CurlTransition 120 行 | 贝塞尔卷页物理无法在 120 行内实现到可接受质量 |

---

## 五、结论

**当前架构不支持"阅读 + 跟手笔写"组合场景。** 不是因为设计粗心，而是因为五个子系统（Compose Image 渲染、MuPDF Bitmap、View 系统 ink、EPUB reflow、单页预取）各自独立设计，没有定义它们之间的交互协议。

**核心修正**：
1. 视图架构从"纯 Compose"改为"hybrid View + Compose overlay"（对标 Mihon）
2. EPUB 渲染从 MuPDF Bitmap 改为 WebView（对标 Readium）
3. 笔迹从模糊的 `pageRef: String` 改为 `InkAnchor.Page` / `InkAnchor.Text` 双模式
4. `isInkMode` toggle 改为 `isStylusNearby` + `activeStrokeId` 状态
5. 放弃"零 WebView"原则——对 EPUB 格式，WebView 是正确的渲染路径

这些修正应在任何 Android 代码实现前完成。当前 `LinReadsApp.kt` 只有 42 行 stub 代码——现在改架构没有沉没成本。

---

**→ 后续调研**：[Ink Anchoring Research](../research/ink-anchoring-research.md) — 行业方案、标准、论文、产品调研

---
_参考：_
- [Platform: Android](Platform-Android.md) — 原始设计
- [docs/android-architecture.md](../android-architecture.md) — 详细架构
- [Architecture Comparison](Architecture-Comparison.md) — 三项目对比
- [Android Architecture Audit](Android-Architecture-Audit.md) — 前次审计
