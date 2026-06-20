# 渲染引擎选型分析

> 调研日期：2026-06-17
> 目标格式：MD · TXT · PDF · EPUB · DOC · DOCX，全部需要大文件支持
> 结论：**不重写 MRTextView，各格式走各自最优路径**

---

## 结论速查表

| 格式 | 渲染方案 | 大文件策略 | 是否需要自研 |
|------|---------|-----------|------------|
| EPUB | epubjs + WebView | 章节懒加载（原生支持）| ❌ |
| PDF | `PdfRenderer` (API21+) / PDFium (API35+) | 逐页光栅化，从不全量载入 | ❌ |
| TXT | **TxtVirtualPager**（自研）| RecyclerView + byteOffset 分页 | ✅ ~300 行 |
| MD | marked → HTML → WebView | 按标题分节懒加载 | ⚠️ 小改动 |
| DOCX | mammoth → HTML → WebView | mammoth 流式解析 | ❌ |
| DOC | DocumentReader（文本提取）+ 转换提示 | 文本模式无内存问题 | ❌ |

---

## 一、EPUB

epubjs 按章节懒加载，内存中同时只有当前章节。100MB 的 EPUB 与 1MB 的没区别。当前 Web 端实现无需改动，Android 端接入 epubjs via WebView 即可。

---

## 二、PDF

Android 系统原生方案，无需商业库：

| API 级别 | 类 | 能力 |
|---------|----|------|
| API 21–34 | `android.graphics.pdf.PdfRenderer` | 逐页光栅化，基础渲染 |
| API 35+ | `PdfRendererPreV`（PDFium 内核）| +文字选择、搜索、注释 |

LinReads minSdk = 26，直接用 `PdfRenderer`。大 PDF 逐页渲染，内存占用恒定（约单页大小）。

替代 Moon+ 的 RadaeePDF：零依赖、零许可费、系统维护。

---

## 三、TXT（唯一需要专门设计的格式）

### 问题

5MB 中文小说 ≈ 250 万字。直接 `setText()` 或 `innerHTML` 必定 OOM 或冻结 UI。Moon+ Reader 用97KB 的 `MRTextView` 自定义排版解决这个问题，代价是 Android-only、不可测试、维护成本极高。

### LinReads 方案：TxtVirtualPager

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

---

## 四、MD

小文件（< 500KB）：`marked(content)` → `WebView.loadDataWithBaseURL()`，一次渲染。

大文件（≥ 500KB，如技术文档）：
1. 按 `##` / `###` 标题切分成节
2. 当前节 ± 前后各一节加载到 WebView
3. 节间跳转用 `scrollTo()` + 段锚点

改动量：在现有 `Reader.tsx`（Web）和 Android WebView 接入层各加约 50 行逻辑。

---

## 五、DOCX

**Web**：`mammoth.convertToHtml({ arrayBuffer })` → 复用现有 HTML 渲染路径。mammoth 是流式 XML 解析器，大 DOCX 无内存问题。

**Android**：两个选项：

| 方案 | AAR 体积 | 格式保真度 |
|------|---------|-----------|
| mammoth.js via WebView（同 Web）| 零额外依赖 | 中（与 Web 一致）|
| Apache POI XWPF | ~15MB | 高 |

建议先用 mammoth via WebView 保持三端一致，有强格式需求时再引入 POI。

---

## 六、DOC（Word 97–2003 二进制格式）

没有轻量且保真的 Android 开源方案。实用策略：

1. **文本提取**：用 `Asutosh11/DocumentReader`（~2MB），提取纯文本后走 TxtVirtualPager 渲染
2. **UI 提示**：「此文件为旧版 DOC 格式，排版可能丢失。建议使用 Word/WPS 另存为 DOCX 后重新导入」
3. **不引入 Apache POI HWPF**：15MB 依赖 + 有限的格式还原，性价比低

DOC 在 2026 年主要是存量文件，文本提取兜底是合理的降级。

---

## 七、为什么不重写 MRTextView

| 维度 | MRTextView | TxtVirtualPager + 各格式专用方案 |
|------|-----------|-------------------------------|
| 代码量 | 97KB Java | ~400 行 Kotlin 总计 |
| 适用格式 | TXT/CHM（其余勉强）| 各格式专用，最优 |
| 可测性 | 极低（绑定 View 系统）| 纯业务逻辑，可单元测试 |
| 跨平台 | Android Only | TxtVirtualPager 逻辑可复用 |
| 维护成本 | 极高 | 低 |

Moon+ Reader 用一个引擎统管所有格式是历史包袱，不是前进方向。
