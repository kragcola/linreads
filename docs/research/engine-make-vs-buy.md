# 自研引擎必要性评估：Make vs Buy

> 评估日期：2026-06-18
> 评估方法：逐格式对照成熟引擎覆盖度，分析自研 vs 现有方案的 tradeoff
> 对照基准：Moon+ Reader MRTextView、Readium Kotlin、foliate-js、KOReader crengine、MuPDF、前沿国内 App C++/Skia 引擎

---

## 结论先行

**LinReads 不需要自研渲染引擎。** 唯一需要自研的组件是 **TxtVirtualPager（~300 行 Kotlin）**——因为 TXT 大文件阅读没有成熟的开源 Android 方案。其余所有格式都有生产级成熟引擎覆盖。Moon+ Reader 的 MRTextView 是 2012 年的技术选择——在它被创造的年代合理，但放在 2026 年，它的 EPUB 部分（~80% 代码量）属于过度工程。

---

## 一、逐格式成熟引擎覆盖度

### EPUB

| 引擎 | 许可 | 成熟度 | Android 可用性 | 选择？ |
|------|------|--------|---------------|--------|
| **foliate-js** (WebView) | MIT | ⭐⭐⭐⭐ 稳定版 | ✅ WebView | ✅ **推荐** |
| Readium Kotlin Toolkit | BSD-3 | ⭐⭐⭐⭐⭐ 100+ App | ✅ Fragment | 备选 |
| epubjs 0.3.x | MIT | ⭐⭐ 半维护 | ✅ WebView | 考虑迁移 |
| KOReader crengine (C++) | GPLv3 | ⭐⭐⭐⭐⭐ 13年 | ✅ JNI | 过重 |
| MuPDF (C) | AGPL | ⭐⭐⭐⭐ | ✅ JNI | ❌ Bitmap 失去 CSS |

**foliate-js 是 epubjs 的实质升级替代：**
- 活跃维护（299 commits，2025–2026），Readest（21k stars）在用
- 支持 EPUB + MOBI + KF8 + FB2 + CBZ + PDF（实验），**一个库覆盖 6 种格式**
- CFI 实现更精确（状态机 parser vs epubjs 的正则）
- 分页更精确（CSS multi-column + bisecting 查找可见区域 vs epubjs 的估算）
- **内置 overlayer.js**：SVG 覆盖层，可用于高亮、书签、甚至手写 SVG 渲染
- 内置 TTS、全文搜索、字典（StarDict/dictd）、OPDS
- 模块化设计：`epub.js` / `paginator.js` / `overlayer.js` / `search.js` / `tts.js` 各自独立
- 不要求一次性加载全文件（流式解压）
- 安全：强制要求 CSP 阻止 EPUB 内嵌脚本

**对 LinReads 的意义**：foliate-js 不仅覆盖 EPUB，还附带提供了高亮覆盖层（可直接用于 Ink 渲染的 SVG 路径）、TTS、搜索——这些都是 LinReads 计划中的功能。

### PDF

| 引擎 | 许可 | 成熟度 | Android 可用性 | 选择？ |
|------|------|--------|---------------|--------|
| **Android PdfRenderer** (API21+) | 系统 | ⭐⭐⭐⭐ 系统级 | ✅ 内置 | ✅ **推荐** |
| PDF.js (Mozilla) | Apache 2.0 | ⭐⭐⭐⭐⭐ 生产 | ✅ WebView | 备选（foliate-js 集成） |
| MuPDF | AGPL | ⭐⭐⭐⭐⭐ | ✅ JNI | 不需要 |

**PdfRenderer 是正确选择**：零依赖、零许可费、系统维护。API35+ 升级 PDFium 内核（支持文字选择）。对个人阅读器完全够用。

### DOCX

| 引擎 | 许可 | 成熟度 | Android 可用性 | 选择？ |
|------|------|--------|---------------|--------|
| **MuPDF JNI** | AGPL | ⭐⭐⭐⭐⭐ | ✅ `com.artifex.mupdf:fitz` | ✅ **推荐** |
| Apache POI XWPF | Apache 2.0 | ⭐⭐⭐ | ✅ 纯 Java | 15MB 依赖，过重 |

**MuPDF 是 DOCX 的唯一务实选择**。AGPL 对个人项目无影响。

### CBZ/CBR

| 引擎 | 许可 | 成熟度 | Android 可用性 | 选择？ |
|------|------|--------|---------------|--------|
| **foliate-js `comic-book.js`** | MIT | ⭐⭐⭐ | ✅ WebView | ✅ **推荐** |
| MuPDF | AGPL | ⭐⭐⭐⭐⭐ | ✅ JNI | 备选（如果已引入） |

**foliate-js 已内置 CBZ 支持**。如果 EPUB 已用 foliate-js，CBZ 零额外成本。

### MD

| 引擎 | 许可 | 成熟度 | Android 可用性 | 选择？ |
|------|------|--------|---------------|--------|
| **Markwon** | Apache 2.0 | ⭐⭐⭐⭐ | ✅ 原生 Spannable | ✅ **推荐** |
| marked → WebView | MIT | ⭐⭐⭐⭐ | ✅ WebView | 备选 |

**Markwon 是正确的**：原生 Spannable 渲染（零中间 HTML），成熟维护（4.6.2），支持 table/strikethrough 扩展。

### TXT ← **唯一需要自研的格式**

| 方案 | 状态 |
|------|------|
| 现有开源 Android TXT 大文件阅读器 | **不存在** |
| `openproject/TxtView` (2018) | 9 stars，年久失修 |
| `YVWX/NewTextViewer` (2024) | 0 stars，个人项目 |
| `f3401pal/TextViewPlus` (2018) | 1 star，无维护 |
| Moon+ Reader MRTextView | 闭源，不可复用 |
| KOReader crengine | GPL，C++，过重 |
| Android Paging3 + RecyclerView | 框架可用，但需要**自定义分页策略** |

**TxtVirtualPager 是必须自研的**——不是因为想做，而是因为没有东西可用。300 行代码的投入产出比极高。

---

## 二、Moon+ Reader MRTextView 自研：时代的产物，而非永恒的设计

### MRTextView 做了什么（从反编译源码核实）

```
MyLayout (abstract, 自研) — 985 行
  └── SoftHyphenStaticLayout (TeX 连字符) — 未知行数
MRTextView (主阅读视图) — 2,444 行
MyHtml + HtmlToSpannedConverter — 2,412 行
CSS 样式引擎 — 1,788 行
自定义 Span (MyTable/Float/Bullet/Image/Typeface/Url/Squiggly/Style/Superscript/Shadow/Margin/FontLight/RelativeSize) — 14 个类
```

**总代码量**：~8,000 行 Java（staticlayout 包 51 个文件）

### MRTextView 的价值分析

| 对 EPUB | 对 TXT |
|---------|--------|
| ❌ 过度工程：WebView 天然支持 CSS/DOM/字体/链接 | ✅ 合理：超大文本文件不能用 `setText()` |
| ❌ Android-only：不能复用到 Web/iOS | ⚠️ 但也可以用 RecyclerView + 分页替代自定义排版 |
| ❌ 不可测：与 View 系统紧耦合 | ⚠️ 字节偏移分页可以纯 Kotlin 测试 |
| 结论：MRTextView 的 EPUB 路径是**错误的技术选择** | 结论：MRTextView 的 TXT 路径**方向对但实现过重** |

### 为什么 Moon+ 当年选择了自研（2012 年背景）

1. **2012 年 Android WebView 质量极差**：不同厂商的 WebView 内核碎片化严重，CSS 支持不一致，性能低下
2. **没有 epubjs/foliate-js**：这两个库分别是 2014 年和 2023 年才出现
3. **没有 Readium SDK**：2017 年才有
4. **TXT 大文件确实需要自定义分页**：Android 原生 TextView 对 >1MB 文本会 OOM

**这些条件在 2026 年全部不成立**。Android WebView (Chromium) 现在统一且高性能，foliate-js 覆盖了 6 种格式的渲染。

---

## 三、前沿国内 App 的 C++/Skia 自研引擎：LinReads 不需要

### 微信读书 / 掌阅 / 知乎盐言 / 百度阅读做了什么

```
C++ 渲染引擎 (ReaderSDK)
├── Expat SAX 解析 → 统一 DOM
├── Skia 图形库 → 硬件加速光栅化
├── FreeType + HarfBuzz → 复杂文本排版
└── 跨平台：C++ 核心 iOS/Android 共享，平台代码 <10%

效果：首屏加载提速 60%+，内存降低 40%，CSS3 级别排版
```

### 这些 App 为什么需要自研

| 需求 | LinReads 是否有此需求 |
|------|---------------------|
| 出版级 CJK 排版（竖排、ruby、圈点、kerning） | ❌ 不需要 |
| 首屏 <100ms（竞品体验基线） | ❌ 个人项目，WebView 首屏 ~500ms 可接受 |
| 数百万 DAU，崩溃率 <0.01% | ❌ 个人使用 |
| 多书城内容一致性（同一本书在所有设备上排版完全一致） | ❌ 只连一个 Calibre 书库 |
| 版权保护（DRM、LCP） | ❌ 不需要 |
| 自研成本：5-10 人年 C++ 开发 | ❌ 单人项目 |

**LinReads 不需要 C++/Skia 引擎。** 这是完全不同量级的产品需求。

---

## 四、最终引擎矩阵

### 采纳的引擎（零自研代码，全部成熟开源）

| 格式 | 引擎 | 代码量 | 许可 | 包体积增量 |
|------|------|--------|------|-----------|
| EPUB | **foliate-js** (WebView) | 0 行（JS 库） | MIT | ~200KB JS |
| MOBI/KF8 | **foliate-js** `mobi.js` | 0 行（附带） | MIT | 同上 |
| FB2 | **foliate-js** `fb2.js` | 0 行（附带） | MIT | 同上 |
| CBZ | **foliate-js** `comic-book.js` | 0 行（附带） | MIT | 同上 |
| PDF | Android **PdfRenderer** | 0 行（系统 API） | 系统 | 0 |
| DOCX | **MuPDF** JNI (`fitz`) | 0 行（JNI 绑定） | AGPL | ~15MB .so |
| MD | **Markwon** | 0 行（库） | Apache 2.0 | ~500KB |
| **TXT** | **TxtVirtualPager** | **~300 行 Kotlin** | 自研 | 0 |

### 总自研代码量

```
TxtVirtualPager:  ~300 行（唯一的自研组件）
其余全部格式:      0 行（全部复用成熟引擎）
────────────────
合计:            ~300 行自研代码
```

对比 Moon+ Reader：~8,000 行自研引擎代码。LinReads 只需要其 3.75%。

### 格式覆盖对比

| 格式 | Moon+ Reader (2012) | LinReads (2026) |
|------|---------------------|-----------------|
| EPUB | ❌ 自研 MRTextView | ✅ foliate-js (MIT) |
| MOBI | ❌ 自研 Mobi.java | ✅ foliate-js (附带) |
| FB2 | ❌ 自研 Fb2.java | ✅ foliate-js (附带) |
| PDF | ❌ RadaeePDF (商业) | ✅ PdfRenderer (系统) |
| DOCX | ❌ 自研 Docx.java | ✅ MuPDF (AGPL) |
| CBZ | ❌ 自研 ComicInfo | ✅ foliate-js (附带) |
| CHM | ❌ 自研 34 类 (~4000 行) | ❌ 不支持（成本太高） |
| MD | ❌ 自研 Md.java | ✅ Markwon (Apache 2.0) |
| TXT | ❌ MRTextView 内部 | ✅ TxtVirtualPager (~300 行) |
| **总自研代码** | **~12,000 行 Java** | **~300 行 Kotlin** |
| **许可风险** | **RadaeePDF 商业付费** | **全部开源/系统** |

---

## 五、对 v2 架构文档的修正建议

基于 foliate-js 的发现，v2 架构中 EPUB 引擎应从 epubjs 改为 foliate-js：

| 维度 | epubjs 0.3.x | foliate-js |
|------|-------------|-----------|
| 维护状态 | 半放弃 | 活跃（299 commits） |
| 格式支持 | 仅 EPUB | EPUB+MOBI+FB2+CBZ+PDF |
| CFI | 正则，已知精度问题 | 状态机，更精确 |
| 覆盖层 | 需 marks-pane | 内置 overlayer.js (SVG) |
| TTS | 无 | 内置 SSML 生成 |
| 搜索 | 无 | 内置 Intl.Collator 搜索 |
| OPDS | 无 | 内置 OPDS 1.x→2.0 转换 |
| 分页 | 估算 | CSS columns + bisecting |
| 授权 | MIT | MIT |

**建议**：`render:epub` 模块使用 foliate-js 替代 epubjs，模块名保持 `:render:epub` 但内部引擎改为 foliate-js。同时 foliate-js 自动覆盖 MOBI、FB2、CBZ——这意味着原有的 `render:pdf` 模块保持不变（仍用 PdfRenderer），但 CBZ 可以走 foliate-js 的 `comic-book.js`。

---

## 六、自研引擎决策树（LinReads 专用）

```
这个格式有成熟的开源引擎吗？
  ├── 有 → 用它。不要再花时间自研。
  │     ├── EPUB/MOBI/FB2/CBZ → foliate-js
  │     ├── PDF → PdfRenderer
  │     ├── DOCX → MuPDF
  │     └── MD → Markwon
  │
  └── 没有 → 再问：这个格式重要吗？
        ├── 重要（TXT，中文小说主格式）→ 自研（但只做分页，不做排版）
        │     └── TxtVirtualPager (~300 行)
        └── 不重要（CHM）→ 不支持
```

**一行总结**：LinReads 的自研引擎策略是 **"只做 TXT 分页，其余全用现成"**。这是 Moon+ Reader 12,000 行自研代码教会我们的教训——不要为了"能做"而去做那些已经有成熟方案的事情。

---

_参考：_
- [foliate-js GitHub](https://github.com/johnfactotum/foliate-js) — MIT licensed, actively maintained
- [Readium Kotlin Toolkit](https://readium.org/kotlin-toolkit/) — BSD-3, 100+ apps
- [Moon+ Reader MRTextView 验证](docs/wiki/Architecture-Verification.md) — 源码分析
- [Moon+ Reader 架构评审](docs/research/moonreader-architecture-review.md) — 自研引擎评估
- [渲染引擎选型分析](docs/research/rendering-engine-analysis.md) — v1 分析
- [Android Architecture v2](docs/android-architecture-v2.md) — 当前架构
