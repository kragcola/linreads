# Rendering Engine

> 核心决策：**不重写 MRTextView，各格式走各自最优路径**

## 结论速查

| 格式 | 渲染方案 | 大文件策略 | 是否需要自研 |
|------|---------|-----------|------------|
| EPUB | epubjs (Web)；**原生重排 jsoup→AnnotatedString (Android v4，去 WebView)** | 章节懒加载 | Android 自研解析/渲染 |
| PDF | PdfRenderer API21+ / PDFium API35+ | 逐页光栅化，从不全量载入 | ❌ |
| TXT | **TxtVirtualPager**（自研 ~300行） | RecyclerView + byteOffset 分页 | ✅ |
| MD | marked → HTML → WebView (Web) / Markwon (Android) | 按标题分节懒加载 | ⚠️ 小改动 |
| DOCX | mammoth → HTML (Web) / MuPDF (Android) | 流式解析 | ❌ |
| DOC | DocumentReader 文本提取 + 转换提示 | 文本模式无内存问题 | ❌ |

## 各格式详解

### EPUB

- **Web**：实现为 epubjs（WebView/JS 引擎），按章节懒加载，内存中同时只有当前章节。
- **Android（v4）**：自研原生重排——`ZipFile`+jsoup 解析 XHTML → Compose `AnnotatedString` 渲染，**去 WebView/CFI**。定位用 spine index + 章节内字符偏移 + progression。路线裁决与取舍见 `docs/android-architecture-v4.md` §5.5 / §12.3 ADR-EPUB-Engine 及 `docs/audit/external-benchmark-audit-2026-06-19.md`。
- 已知陷阱：EPUB/CFI/字体/flow 切换相关细节见 `.claude/skills/linreads-epub/SKILL.md`

### PDF

Android 系统原生方案，无需商业库：

| API 级别 | 类 | 能力 |
|---------|----|------|
| API 21–34 | `android.graphics.pdf.PdfRenderer` | 逐页光栅化，基础渲染 |
| API 35+ | `PdfRendererPreV`（PDFium 内核） | +文字选择、搜索、注释 |

LinReads minSdk = 26，直接用 `PdfRenderer`。替代 Moon+ Reader 的 RadaeePDF：零依赖、零许可费、系统维护。

### TXT（唯一需要专门设计的格式）

**问题**：5MB 中文小说 ≈ 250 万字。直接 `setText()` 或 `innerHTML` 必定 OOM 或冻结 UI。

**方案：TxtVirtualPager**

```
文件（任意大小）
    ↓ 后台协程，64KB 块顺序读取
TxtPager —— 扫描页边界
    规则（可配置）：
    · 连续空行 ≥ 2
    · 匹配章节正则（"第.{1,5}章"、"Chapter \d+"）
    · 字符数阈值（默认 2000 字/页）
    ↓
List<PagePointer> { byteOffset: Long, length: Int }
（索引文件，内存占用极小）
    ↓
Paging3 PagingSource —— 按需读取对应字节段
    ↓
RecyclerView —— 只渲染屏幕上的页
```

**进度存储**：`{ byteOffset, pageIndex }`，与整个文件解耦，文件移动后可按偏移量修正。

**实现规模**：约 250–350 行 Kotlin，不涉及自定义排版。

### MD

- **小文件**（< 500KB）：`marked(content)` → WebView / Markwon → Spannables，一次渲染
- **大文件**（≥ 500KB）：按 `##`/`###` 标题切分成节，当前节 ± 前后各一节加载

### DOCX

- **Web**：`mammoth.convertToHtml({ arrayBuffer })` → 复用现有 HTML 渲染路径
- **Android**：MuPDF JNI 原生支持 DOCX，解析为 Bitmap 分页

### DOC（Word 97–2003 二进制格式）

无轻量且保真的 Android 开源方案。降级策略：
1. 文本提取（Asutosh11/DocumentReader ~2MB）→ 走 TxtVirtualPager 渲染
2. UI 提示用户转为 DOCX
3. 不引入 Apache POI HWPF（15MB 依赖，性价比低）

## 为什么不重写 MRTextView

Moon+ Reader 的 MRTextView（97KB Java）自定义排版引擎确实优秀，但：

| 维度 | MRTextView | LinReads 方案 |
|------|-----------|--------------|
| 代码量 | 97KB Java | ~400 行 Kotlin 总计 |
| 适用格式 | TXT/CHM（其余勉强） | 各格式专用，最优 |
| 可测性 | 极低（绑定 View 系统） | 纯业务逻辑，可单元测试 |
| 跨平台 | Android Only | TxtVirtualPager 逻辑可复用 |
| 维护成本 | 极高 | 低 |

**结论**：Moon+ Reader 用一个引擎统管所有格式是历史包袱，不是前进方向。LinReads 各格式走各自最优路径。

---

_参考：_ [docs/research/rendering-engine-analysis.md](../research/rendering-engine-analysis.md) · [Platform: Android](Platform-Android.md)
