---
name: linreads-epub
description: >
  EPUB 2/3 格式规范、epubjs 已知陷阱、CFI 书签定位、字体嵌入、格式兼容处理。
  自动激活：涉及 EPUB 渲染、书签/进度保存、章节导航、字体、epubjs API 调用。
---

# LinReads EPUB

## EPUB 规范速查

### EPUB 2 vs EPUB 3 差异（必知）

| 特性 | EPUB 2 | EPUB 3 |
|------|--------|--------|
| 内容文档 | XHTML 1.1 | HTML5 |
| 样式 | CSS 2.1 | CSS3（含媒体查询） |
| 导航 | NCX (`toc.ncx`) | Navigation Document (`nav.xhtml`) + NCX |
| 媒体 | 有限 | audio/video/MathML/SVG |
| 元数据 | OPF 2.0 | OPF 3.0 |
| 字体混淆 | 可选 | 可选 (Adobe/IDPF) |

**实践规则：** 读取时同时检查 NCX 和 Navigation Document，优先用 Nav。判断版本：`package[@version]` 属性。

### CFI (Canonical Fragment Identifier) — 书签/进度核心

```
epubcfi(/6/4!/4/2/2:100)
         ↑  ↑  ↑↑↑↑↑ ↑
         |  |  内容偏移  字符偏移
         |  spine索引(内容文档)
         package索引(spine)
```

- `/6` = spine element（固定）
- `/4` = spine item index（偶数，从2开始）
- `!` = 进入内容文档
- `/4/2/2` = DOM路径
- `:100` = 字符偏移

**保存进度用 CFI，不用页码**（页码依赖字号/屏幕大小，CFI 是绝对位置）。

---

## epubjs 已知陷阱

### 陷阱 1：`book.locations` 必须先生成

```javascript
// ❌ 直接用 percentageFromCfi 会返回 null
book.rendition.currentLocation().start.percentage

// ✅ 先生成位置索引（耗时，做一次后缓存到 localStorage）
await book.locations.generate(1024)  // 1024 = chars per location
const pct = book.locations.percentageFromCfi(cfi)
```

### 陷阱 2：`rendition.display()` 是异步的，不能连续调用

```javascript
// ❌ 第二次 display 会覆盖第一次，结果不可预期
rendition.display(cfi1)
rendition.display(cfi2)

// ✅ 等待
await rendition.display(cfi)
```

### 陷阱 3：EPUB 内 CSS 可能污染外层样式

epubjs 用 iframe 渲染，但某些 EPUB 的 CSS 包含 `*` 选择器或 `:root` 会穿透。
始终把 epubjs viewer 放在有 `all: initial` 隔离的容器里，或用 Shadow DOM。

### 陷阱 4：字体 CORS

EPUB 内嵌字体通过 blob URL 注入，但某些浏览器 (Safari) 对 blob:// 字体有限制。
解决：`book.settings.requestCredentials = false`；如仍失败，用 `requestHeaders` 添加字体白名单。

### 陷阱 5：`book.destroy()` 必须调用

不调用 `destroy()` → 内存泄漏，尤其在 SPA 路由切换时。

```javascript
// React
useEffect(() => {
  const book = ePub(url)
  const rendition = book.renderTo(ref.current, options)
  return () => { book.destroy() }  // cleanup
}, [url])
```

### 陷阱 6：分页模式下 `resize` 事件

窗口 resize 后必须调用 `rendition.resize()`，否则布局错乱。

```javascript
useEffect(() => {
  const onResize = () => rendition.resize(container.offsetWidth, container.offsetHeight)
  window.addEventListener('resize', onResize)
  return () => window.removeEventListener('resize', onResize)
}, [rendition])
```

---

## 格式兼容处理

### 检测 EPUB 版本

```typescript
// Web (epubjs)
const book = ePub(url)
await book.opened
const version = book.packaging.metadata.get('version') ?? '2.0'

// Android (epublib)
val book = EpubReader().readEpub(stream)
val version = book.version  // "2.0" | "3.0"
```

### 字体嵌入处理

```typescript
// 检查字体是否混淆（Adobe/IDPF混淆）
// 混淆字体文件头前1040字节被XOR处理，epubjs自动处理
// Android epublib 不自动处理混淆字体 → 需手动解混淆或使用系统字体回退

// Web：强制使用阅读器默认字体（忽略嵌入字体）
rendition.themes.override('font-family', userFontFamily)
```

### 非标准 EPUB 容错

```typescript
// 常见问题：OPF 路径不在 META-INF/container.xml 声明的位置
// epubjs 会自动搜索，Android 需手动兜底：
try {
  EpubReader().readEpub(stream)
} catch (e: EpubProcessingException) {
  // 尝试 fallback：强制从根路径查找 .opf
}
```

---

## 书签/进度数据结构

```typescript
interface ReadingPosition {
  bookId: string          // Calibre book ID
  cfi: string             // epubcfi(...)
  chapterHref: string     // e.g. "OEBPS/chapter1.xhtml"
  chapterTitle: string    // human-readable
  percentage: number      // 0.0–1.0（展示用，存储用CFI）
  updatedAt: string       // ISO 8601
  deviceId: string        // 哪台设备
}
```

---

## 参考资料（本地）

```bash
# 查静读天下如何处理 epubcfi
grep -r "epubcfi\|EpubCfi\|CfiNavigator" moonreader-decompiled/sources/ --include="*.java" -l

# 查静读天下书签存储结构
grep -r "bookmark\|Bookmark\|position\|Position" moonreader-decompiled/sources/com/ --include="*.java" -l | head -10

# 查静读天下支持的格式列表
grep -r "EPUB\|PDF\|MOBI\|AZW" moonreader-unpacked/res/values/ --include="*.xml"
```
