# Reflow EPUB 手写笔迹锚定：技术调研

> 调研日期：2026-06-18
> 调研问题：在 reflow EPUB 上做跟手手写，笔迹如何锚定到文本内容，使得字号/排版变化后笔迹仍然正确？
> 调研范围：学术论文（2篇）、行业标准（3个）、商业产品（5个）、开源项目（3个）、专利（1个）

---

## 一、问题定义

```
用户场景：
1. 18pt 字号下，在 EPUB 第 5 页某段落旁边手写了一条注记
2. 字号调大到 22pt → 文本重排 → 原来的第 3 段现在在第 7 页
3. 期望：笔迹仍然出现在原来那段文字旁边，而不是留在第 5 页的空白上

核心技术问题：
手写笔迹 = (x₁,y₁), (x₂,y₂), ... 屏幕坐标序列
EPUB 内容 = HTML DOM 流式布局，字号/宽度变化后元素位置全变
问题 = 如何将屏幕坐标映射到稳定的内容锚点上？
```

---

## 二、标准规范层：W3C EPUB Annotations 1.0

**来源**：[W3C Working Draft 2026-05-21](https://www.w3.org/TR/epub-anno-10/)

### 选择器体系

标准定义了三层选择器，通过 `refinedBy` 链式组合：

```
annotation.target = {
    source: "chapter1.xhtml",           // EPUB 资源 URL
    selector: [
        {
            type: "CssSelector",
            value: "#intro > p:nth-child(3)"    // 粗粒度：CSS 选择器
        },
        {
            type: "refinedBy",
            refinedBy: {
                type: "TextPositionSelector",
                start: 42,                      // 精粒度：字符偏移
                end: 58
            }
        }
    ]
}
```

### 标准推荐的最佳实践

> "It is recommended that Reading Systems export multiple selectors, including at least one precise selector (e.g. CssSelector + TextPositionSelector), and one selector resistant to content modifications (e.g., based on text fragments)."

即：**双层锚定 = 粗粒度 CSS 定位 + 精粒度文字偏移**。

### 标准对 LinReads 的适用性

| 标准选择器 | 适用场景 | LinReads 可行性 |
|-----------|---------|----------------|
| `CssSelector` | 定位到段落级 | ✅ EPUB 走 WebView 后天然可用 |
| `TextPositionSelector` | 字符级偏移 | ✅ `innerText` 归一化后计数 |
| `FragmentSelector (CFI)` | EPUB CFI 精确定位 | ✅ epubjs 原生支持 |
| `FragmentSelector (text fragment)` | 文本片段搜索定位 | ⚠️ 标准仍是 Draft，浏览器无标准 API |

**局限**：W3C 标准只定义了**文本注记**的锚定，没有定义**手写笔迹**的坐标锚定。但 `TextPositionSelector` + CSS 的背景足以作为手写锚定的基础层。

---

## 三、学术研究层

### 3.1 Microsoft 专利 US7218783B2：数字墨迹锚定与重排

**来源**：[Google Patents](https://patents.google.com/patent/US7218783B2/en)，2003 年申请，Microsoft 持有

**核心流程**：

```
1. Classification Module  → 识别墨迹类型（文字/图形/标注）
2. Anchoring Module       → 将墨迹锚定到文档内容
3. Reflow Module          → 文档重排后重新定位墨迹
```

**锚定方法**（专利中的关键创新）：
- **空间锚定**：墨迹相对于附近文本字符的 (x,y) 偏移
- **结构锚定**：墨迹关联到文档结构树中的节点
- **语义锚定**：墨迹关联到识别出的语义对象（单词、句子、段落）
- **多重锚定**：同时使用多种方法，选择最优匹配

**重排算法**：
1. 文档重排后，被锚定的文本字符移动到新位置
2. 墨迹通过锚点映射到新位置
3. 应用空间变换（缩放、平移）保持视觉关系
4. 如果锚点失效（文本被删除），标记为"orphaned annotation"

### 3.2 SpaceInk (Microsoft Research, UIST 2019)

**来源**：[Microsoft Research](https://www.microsoft.com/en-us/research/publication/spaceink-making-space-for-in-context-annotations/)

**核心思想**：不试图把墨迹锚定到文本上，而是**动态调整文档排版**为墨迹腾出空间。

```
传统思路：文本重排 → 墨迹需要重新定位（难）
SpaceInk：  墨迹占据空间 → 文本流围绕墨迹排版（反向思维）
```

**技术要点**：
- 墨迹被当作"排版元素"插入文档流
- 文档引擎为墨迹预留空白区域
- 文本围绕墨迹区域重排（类似 CSS `float` 效果）
- 适合"在段落间写大段评注"而非"在段落旁写小注"

**对 LinReads 启示**：SpaceInk 的思路适合"边距注记"场景。如果需要整段评注，可以插入空白区域；如果需要精确的文字旁注，需要锚定方案。

### 3.3 "Reflowing Digital Ink Annotations" (ACM CHI 2003)

**来源**：[ACM DOI](https://dl.acm.org/doi/10.1145/642611.642678)，Bargeron & Moscovich（Microsoft）

**核心发现**：
- 墨迹锚定需要**语义分组**（哪几个 stroke 属于同一个"词"）
- 重排时应保持墨迹的内部空间关系
- 用户对"墨迹跟随文本移动"的预期取决于墨迹类型（下划线跟随文字，边距注记保持大致位置）

**对 LinReads 启示**：区分不同类型的笔迹（高亮下划线 vs 边距注记 vs 自由绘图），各自用不同的锚定策略。

---

## 四、商业产品层

### 4.1 Kindle Scribe

**方案**：不做自由手写，用两种受限模式规避 reflow 问题

| 模式 | 描述 | 锚定方式 |
|------|------|---------|
| **Sticky Notes** | 选定文本 → 弹出便签 → 手写 | 文本选择锚定（KF8 `position`） |
| **Active Canvas** | 在文本中插入一个固定大小的书写区域 | 作为 HTML 元素嵌入文本流 |

**关键洞察**：Amazon **刻意限制了 EPUB 手写的自由度**——不能用笔在页面上任意位置写，只能通过 Sticky Note 或 Active Canvas。这是务实的工程决策：放弃"自由墨迹"以换取"重排安全"。

**数据格式**（从 Kindle 导出推断）：
- Sticky Note：`{startPosition, endPosition, noteText/noteSvg}`
- Active Canvas：嵌入 EPUB spine 的独立 HTML 资源

### 4.2 Apple Books

**方案**：手写注记转为浮动图片嵌入 HTML

**实现方式**（推断）：
1. 用户在 EPUB 页面写注记
2. Apple Books 将墨迹渲染为 SVG/PNG
3. SVG 通过 CSS `position: absolute` 嵌入当前页面的 HTML
4. 重排时，图片跟随最近的文本锚点移动
5. 导出为 W3C Web Annotation 格式（`com.apple.ibooks-sync.plist`）

**局限**：Apple Books 的手写是"半自由"的——可以在选定的文本旁写，但不能完全自由地在页面空白处画。这也是对 reflow 问题的妥协。

### 4.3 BOOX (Onyx)

**方案**：**页面级存储，不锚定到文本**

从 BOOX `.note` 格式分析：
- 笔迹存储为页面坐标（PDF points，1860×2480）
- 每笔带 pressure/tilt/time 元数据
- 通过 shape protobuf 的 transform matrix 支持移动/缩放
- **没有文本锚定信息**——笔迹绑定到"页码"，不绑定到"文字"

**结论**：BOOX 的方案在固定布局（PDF）下完美，但在 reflow EPUB 下**改变字号后笔迹会错位**。这是目前所有 e-ink 设备的共同局限。

### 4.4 Kobo Elipsa / reMarkable

- **Kobo Elipsa**：EPUB 手写支持极有限——仅高亮和打字注记，无手写
- **reMarkable**：不支持 EPUB 原生格式，需先转 PDF

### 4.5 Supernote

EPUB 手写锚定通过 `digest`（内容哈希）定位段落——但实测中字号改变后仍可能错位。

---

## 五、行业共识总结

### 所有产品的妥协

没有一个商业产品做到了**在 reflow EPUB 上完全自由的跟手笔写**。所有方案都是在以下维度之间做取舍：

```
                自由笔写
                   ↑
    Kindle Scribe × (只做便签/Active Canvas)
    Apple Books   × (半自由，需选定文本区域)
    BOOX          ? (表面自由，但字号改后错位)
                   │
    ───────────────┼────────────────→ 重排安全
                   │
    理想方案       ✓ (不存在)
```

### 两派路线

| 路线 | 代表 | 做法 | 优点 | 缺点 |
|------|------|------|------|------|
| **避免问题** | Kindle Scribe | 限制手写形式（便签/Canvas），不让人在文本上面随便写 | 100% 重排安全 | 用户感知"功能受限" |
| **页面级存储** | BOOX | 允许自由写，但把笔迹绑定到页码而非文字 | 写起来自由 | 字号改变错位，用户困惑 |
| **标准锚定** | Apple Books（部分） | 墨迹→图片→锚定到最近的文本选择 | 重排基本安全 | 实现复杂度高，自由墨迹需降级为图片 |

---

## 六、技术实现路径分析

### 路径 A：Sticky Note 模式（推荐为 Phase 1）

```
用户操作：
1. 用笔圈选/长按一段文字
2. 弹出书写区域（类似 Apple Books / Kindle Scribe）
3. 在书写区域内自由手写
4. 笔迹锚定到选中的文字范围

实现：
- 文字选择 → W3C TextPositionSelector (start, end)
- 笔迹 → android.graphics.Path → SVG path → Room Blob
- 锚定 → AnnotationEntity.anchor = TextAnchor(chapterHref, startChar, endChar)
- 重排 → 通过 TextPositionSelector 重新定位文字 → 平移墨迹
```

**优点**：
- 锚定可靠（基于文字位置而非页面坐标）
- 实现可行（文字选择 + 有限书写区域）
- Kindle Scribe / Apple Books 验证了用户体验可接受

**缺点**：
- 不是完全自由的页面书写
- 边距注记需要额外设计

### 路径 B：段落锚定 + 偏移

```
用户操作：
1. 在段落旁自由写注记
2. 笔迹被锚定到最近的段落

实现：
- 通过 WebView JavaScript 获取所有段落的 getBoundingClientRect()
- 笔迹触碰点 → 找到最近的段落 → 计算相对于段落左上角的偏移
- 存储：TextAnchor(chapterHref, paragraphSelector, offsetX, offsetY)
- 重排后：重新获取段落 boundingRect → 笔迹变换到新位置
```

**优点**：
- 比 Sticky Note 更自由（可以在任意地方写）
- 段落级锚定比页码级可靠

**缺点**：
- 字号改变后段落几何变化，笔迹需要缩放变换
- 段落内文本重排，笔迹可能有垂直偏移
- 行间书写无法精确定位到具体文字

### 路径 C：WebView 内嵌 SVG overlay（最完整但最复杂）

```
架构：
WebView
  ├── <iframe> EPUB 内容（epubjs/foliate-js 渲染）
  └── <svg> 墨迹 overlay（position: absolute, 覆盖在 iframe 上）

实现：
1. EPUB 通过 epubjs 在 iframe 中渲染
2. JavaScript 捕获 stylus 事件（pointerdown/pointermove/pointerup）
3. 笔迹实时渲染到 overlay SVG
4. 通过 EPUB CFI 定位笔迹起始点的文本位置
5. 笔迹存储为 SVG path + CFI anchor
6. 重排后通过 CFI 重新定位 → 平移 SVG

技术挑战：
- JavaScript 的 pointer events 延迟高于原生 ink（~30ms vs ~9ms）
- iframe 跨域问题（需要 EPUB 资源同源服务）
- CFI 在动态生成的 HTML 上可能不稳定
```

**优点**：
- 最接近"自由笔写"的理想
- 墨迹可以完全集成到 EPUB 渲染层

**缺点**：
- 延迟问题——JavaScript 笔迹无法达到 androidx.ink 的 <10ms
- 复杂度极高
- CFI 在 epubjs 中已知有精度问题（见 `linreads-epub` skill）

### 路径 D：双模式策略（推荐）

```
PDF/DOCX/CBZ（固定布局）→ 页面级锚定（BOOX 方案，完全自由书写）
EPUB（reflow）          → 文本级锚定（Sticky Note + 段落锚定）
```

**理由**：
- 固定布局格式天然支持自由书写——页面坐标就是内容坐标
- Reflow EPUB 的自由书写是行业难题，不应在 Phase 1 追求
- 用户对 PDF 和 EPUB 的手写预期不同（PDF 上期望"像纸一样写"，EPUB 上预期"像书一样注"）

---

## 七、Android 实现路径

### WebView + JavaScript Bridge + Native Ink Overlay

基于 Android 实际 API 的实现架构：

```
FrameLayout (根视图)
  ├── WebView (EPUB 渲染，epubjs/foliate-js)
  │     ├── iframe[1] 页 1
  │     ├── iframe[2] 页 2
  │     └── ...
  ├── CanvasView (已完成笔迹，标准 Canvas)
  └── InProgressStrokesView (进行中笔迹，前缓冲区)

JavaScript Bridge:
  webview.evaluateJavascript("getAnchors()") → 获取所有段落/文字的 boundingRect
  webview.addJavascriptInterface(anchorBridge, "AnchorBridge") → Kotlin 侧接收锚定数据
```

**关键 JS Bridge API**：

```javascript
// 获取所有可锚定元素的位置
function getAnchors() {
    const paragraphs = document.querySelectorAll('p, h1, h2, h3, h4, h5, h6, li');
    return Array.from(paragraphs).map(p => ({
        tag: p.tagName,
        text: p.innerText.substring(0, 50),
        rect: p.getBoundingClientRect(),  // {left, top, right, bottom, width, height}
        cfi: epubCFI.fromNode(p),         // 如果 epubjs 可用
    }));
}

// 给定 (x, y)，找到最近的段落
function findNearestAnchor(x, y) {
    const anchors = getAnchors();
    // 找垂直距离最近的段落
    return anchors.reduce((best, a) => {
        const dy = Math.abs(y - (a.rect.top + a.rect.bottom) / 2);
        return dy < best.dy ? { anchor: a, dy } : best;
    }, { anchor: null, dy: Infinity });
}
```

**Kotlin 侧坐标转换**：

```kotlin
fun screenToPageCoords(screenX: Float, screenY: Float, webView: WebView, pageIndex: Int): PointF {
    // 1. 获取 iframe 在 WebView 中的位置
    val js = "document.querySelectorAll('iframe')[$pageIndex].getBoundingClientRect()"
    webView.evaluateJavascript(js) { result ->
        // {left, top, width, height}
    }

    // 2. 屏幕坐标 → WebView 坐标 → iframe 坐标 → 页面坐标
    val webViewX = screenX - webView.left
    val webViewY = screenY - webView.top
    val pageX = webViewX - iframeLeft
    val pageY = webViewY - iframeTop
    return PointF(pageX, pageY)
}
```

**Sticky Note 完整交互流**：

```
1. 用户用笔长按文字 → Android 侧收到 LongPress (TOOL_TYPE_STYLUS)
2. Android 侧调用 webview.evaluateJavascript("getSelection()") 获取选中范围
3. 获取选中范围的 TextPositionSelector (start, end)
4. 获取选中文字下方/旁边的空白区域 boundingRect
5. 在 Kotlin 侧创建一个临时的书写 Canvas（浮层）
6. 用户手写 → InkOverlay 捕获笔画
7. 提笔 (ACTION_UP) → 笔迹序列化 + 锚定到 TextPositionSelector → Room
8. Canvas 关闭，页面左上角显示一个便签图标（表示这里有注记）
```

---

## 八、推荐方案

### 分层策略

| 格式 | Phase 1 | Phase 2 |
|------|---------|---------|
| **PDF** | 页面级自由书写（BOOX 模式） | 优化笔迹缩放变换 |
| **DOCX** | 页面级自由书写 | 文字选择 + 锚定 |
| **CBZ/CBR** | 页面级自由书写 | — |
| **EPUB** | **Sticky Notes** + 文字高亮 | 段落锚定自由书写 |
| **TXT** | 高亮 + 打字注记 | — |
| **MD** | 高亮 + 打字注记 | — |

### 数据模型

```kotlin
sealed interface InkAnchor {
    /** 固定布局：页面坐标 */
    data class Page(
        val pageIndex: Int,
        val pageWidth: Float,
        val pageHeight: Float,
    ) : InkAnchor

    /** Reflow 文本：文字位置锚定 */
    data class Text(
        val source: String,          // EPUB resource href
        val cssSelector: String,     // "p:nth-child(3)"
        val startOffset: Int,        // TextPositionSelector.start
        val endOffset: Int,          // TextPositionSelector.end
        val offsetX: Float,          // 距选中区域的水平偏移
        val offsetY: Float,          // 距选中区域的垂直偏移
        val fontSize: Int,           // 书写时的字号（还原用）
    ) : InkAnchor
}
```

### 为什么不做 WebView 内嵌 SVG overlay（路径 C）

1. **延迟**：JS pointer events 延迟 ~30-50ms，vs android.ink <10ms。"跟手"是用户的核心需求
2. **复杂**：CFI 生成、跨 iframe 坐标转换、WebView ↔ Kotlin 双向通信，任何一个环节出 bug 都会导致笔迹错位
3. **成熟度**：没有开源的 JS EPUB SVG overlay 手写方案可以参考

### 为什么 Sticky Note 是 EPUB 的最优 Phase 1 方案

1. **用户预期对齐**：在 reflow 电子书上"像纸一样写"的用户预期本身就不合理——纸质书的文字不会重排
2. **工程可行**：文字选择 + 书写区域 + 锚定，三个子问题都有成熟方案
3. **竞品验证**：Kindle Scribe 和 Apple Books 都选择了这条路，说明这是产品/工程上的最优折衷
4. **渐进增强**：Sticky Note 实现后，可以在此基础上增加段落锚定自由书写

---

## 九、参考资料

| 来源 | 类型 | 链接 |
|------|------|------|
| W3C EPUB Annotations 1.0 | 标准 | https://www.w3.org/TR/epub-anno-10/ |
| W3C Web Annotation Data Model | 标准 | https://www.w3.org/TR/annotation-model/ |
| EPUB CFI 1.1 | 标准 | https://w3c.github.io/epub-specs/epub33/epubcfi/ |
| Readium Locator | 标准 | https://readium.org/architecture/models/locators/ |
| Microsoft Patent US7218783B2 | 专利 | https://patents.google.com/patent/US7218783B2/en |
| SpaceInk (Microsoft Research) | 论文 | https://www.microsoft.com/en-us/research/publication/spaceink-making-space-for-in-context-annotations/ |
| Reflowing Digital Ink Annotations (ACM) | 论文 | https://dl.acm.org/doi/10.1145/642611.642678 |
| BOOX .note Format (boox-note-optimizer) | 开源 | https://github.com/nrontsis/boox-note-optimizer |
| Readest (Foliate successor) | 开源 | https://github.com/readest/readest |
| Kindle Scribe Annotation Guide | 产品 | https://www.amazon.com/gp/help/customer/display.html?nodeId=T0MT2p2D8t046zUxih |
| LinReads Ink Gap Analysis | 前置分析 | [docs/wiki/Ink-Architecture-Gap.md](../wiki/Ink-Architecture-Gap.md) |
