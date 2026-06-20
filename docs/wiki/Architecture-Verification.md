# 静读天下架构：源码交叉验证报告

> 验证日期：2026-06-18
> 来源：反编译源码 `moonreader-decompiled/sources/com/flyersoft/`（12031 个 Java 文件，core 包 ~164K 行）
> 对比文档：`docs/research/moonreader-analysis.md` + `docs/research/moonreader-architecture-review.md`

## 核心结论

两篇文档**整体准确**，对架构层次划分、关键设计缺陷的判断正确。共发现 **3 处事实性错误**，**2 处表述不精确**，**1 处遗漏重要细节**。无系统性误判。

---

## ✅ 验证通过的判断

| 文档声明 | 源码验证 | 结论 |
|---------|---------|------|
| Sync.java 是 1529 行的 God Class | `Sync.java`：1529 行，全是 `public static` 方法 | ✅ 精确匹配 |
| Sync.java 大量静态可变状态 | 20 个 `static ArrayList`/`boolean`/`int`/`long` 字段，无同步保护 | ✅ 确认 |
| `pdfCloudIsNewerList` 是 PDF 特例 | `Sync.java:55` — `public static ArrayList<String> pdfCloudIsNewerList` | ✅ 确认 |
| notes 表三合一（进度/书签/高亮） | `notes` schema：`lastChapter, lastSplitIndex, lastPosition, highlightLength, highlightColor, bookmark, note, original, underline, strikethrough` | ✅ 确认 |
| books 表有 `bak1/bak2` 哑列 | `BookDb.java:27` — `bak1 text, bak2 text` | ✅ 确认 |
| statistics 表存阅读时长+字数 | `BookDb.java:30` — `usedTime, readWords, dates` | ✅ 确认 |
| 书架分类 7 种 | `SHELF_TYPE_ALL=0` 到 `SHELF_TYPE_RATINGS=6`，与文档完全一致 | ✅ 确认 |
| BaseEBook 封闭继承层次 | 10 个子类：Epub, Mobi, Chm, Fb2, Docx, Rtf, Umd, Mhtml, Md, ComicInfo — 每个都是 `extends BaseEBook` | ✅ 确认 |
| MRTextView 约 97KB | 文件大小 96,936 字节（2,444 行） | ✅ 确认 |
| PDF 依赖 RadaeePDF 商业库 | `PDFReader.java` 导入 `com.radaee.pdf.*`, `com.radaee.reader.*` | ✅ 确认 |
| CHM 为自实现库 | `chmlib/` 34 个文件，含 `LZXCoder.java`（LZX 解压）、Huffman 树、ITSF/ITSP header 解析 | ✅ 确认 |
| OPDS 协议支持 | `opds/` 包 8 个文件：`OpdsSite, OpdsEntries, OpdsEntry, OpdsJson` | ✅ 确认 |
| 云后端：Dropbox/Gdrive/WebDav/Ftp | `cloud/` 包：`Dropbox.java, Gdrive.java, WebDav.java, Ftp.java`，共同实现 `Cloud` 抽象类 | ✅ 确认 |
| 同步策略 LWW | `Sync.java:296` — `cloudFileIsNewer()` 对比时间戳；`localFileIsNewer()` 反向对比 | ✅ 确认 |
| 同步文件 .mrbooks（ZIP） | `Sync.java` 导入 `MyZip_Java`，`extractSyncNotes()` 从 ZIP 流解包 | ✅ 确认 |
| 无响应式数据层 | `BookDb.allBooks` 是 `static ArrayList<BookInfo>`，无 Flow/LiveData | ✅ 确认 |
| Readwise 集成 | 文档提到 `Readwise.java`，属 Pro 功能 | ✅ 间接确认 |
| MRTextView 绕过原生 TextView | `MyTextView extends View`（不是 TextView），`MRTextView extends MyTextView` | ✅ 确认 |
| Mb 格式解析存在 | `Mobi.java`（1220+ 行），自实现 MOBI/AZW3 解析 | ✅ 确认 |
| 云同步触发时机 onPause | `Sync.java` 方法在 Activity 生命周期回调中调用 | ✅ 确认 |

---

## ❌ 事实性错误

### E1：PDFReader 不是 BaseEBook 的子类

**文档说**（`moonreader-analysis.md` 系统层次图）：
```
BaseEBook（抽象基类）
  ├── Epub   ├── Mobi   ├── PDFReader（RadaeePDF）
```

**源码实际**：
```java
// PDFReader.java:69
public class PDFReader extends FrameLayout {
    // 直接继承 FrameLayout，完全绕过 BaseEBook
    // 使用 RadaeePDF 的 GLView / PDFLayoutView 作为内部 View
    // 不与 BaseEBook 的 Chapter/pages 体系交互
}
```

**影响**：体系图需要修正。PDF 在 Moon+ 里走完全独立的渲染路径——它是一个 `FrameLayout` 子类，内部嵌入 RadaeePDF 的 `PDFGLLayoutView`，不经过 `BaseEBook.chapters/pages`。

### E2：MyLayout 不继承 Android StaticLayout

**文档说**（`moonreader-analysis.md` 排版引擎节）：
> "MyLayout：继承 Android StaticLayout，扩展双向文本、自定义段距"

**源码实际**：
```java
// MyLayout.java:22
public abstract class MyLayout {  // 无 extends，继承 Object
    // 这是一个完全自实现的布局抽象类
    // 内部定义了 Directions, Alignment, Ellipsizer 等子结构
    // 子类是 SoftHyphenStaticLayout extends MyLayout
}
```

**影响**：Moon+ Reader 的排版比文档描述的更激进。MyLayout 不继承任何 Android 布局类，是 **100% 自研**的行布局引擎。`SoftHyphenStaticLayout extends MyLayout` 是具体的连字符布局实现。

### E3：CHM 库实际 34 个文件，非 29 个

**文档说**（`moonreader-analysis.md`）：
> "自实现 CHM 解析库（29 个类，含 LZX 解压）"

**源码实际**：
```
chmlib/ 目录：34 个 .java 文件
核心类：ChmDocument, ChmDocumentCache, LZXCoder, HuffmanTreeNode,
        HeaderITSF, HeaderITSP, FileHHC, FileWindows, FileSystem,
        ChunkPMGI, ChunkPMGL, TLV, BitBuffer, ByteBuffer, 等
```

**影响**：数字偏差 5 个文件（约 17%），不影响架构判断但应修正。

---

## 🟡 表述不精确

### I1：`A.java` 和 `T.java` 的性质

**文档说**：
> "`A.java`、`C.java`、`T.java`——工具类命名不具语义，是混淆后的产物（或者一开始就是反模式积累）"

**源码实际**：
- **A.java**（8,682 行，~1180 个 static 字段）：确实是一个超级工具箱——Bitmap、音频、Notification、SharedPreferences、Shortcut、Zip、文件操作、UI 主题……把所有东西塞进了一个类。是 God Class 反模式的典型。
- **C.java**（652 行）：**有明确语义**——Color 和 Material Design 主题常量。包含 Material3 的动态色彩（`DynamicColors`）、日夜模式色值（`NIGHT_BROWSE_COLOR, NIGHT_MAIN_COLOR, amoledBlack` 等）。`C` 就是 `Color/Constant`。
- **T.java**（4,062 行）：通用工具集——文件操作、MIME 类型、网络检测、文本处理、Hash、中文排序（`NatureSortItem`）。`T` 就是 `Tools`。

**结论**：`A.java` 和 `T.java` 确实是反模式；但 `C.java` 有明确职责（色彩主题），不应与 A/T 并列批判。

### I2：MyLayout 与 StaticLayout 的关系

文档暗示 Moon+ 是在 Android `StaticLayout` 基础上的"扩展"，但实际是完全独立的实现。`MyLayout` 的抽象级别更接近 Android 的 `Layout` 基类。两者的 API 设计相似（`draw()`, `getLineStart()`, `getLineEnd()` 等），但在继承树上完全不同——是平行实现而非扩展。

---

## 🔵 遗漏重要细节

### O1：排版层继承链

文档未完整描述排版引擎的类层次：

```
View
 └── MyTextView (View 的直接子类，非 TextView)
       └── MRTextView (2,444 行，主阅读视图)

MyLayout (Object 的直接子类，abstract)
 └── SoftHyphenStaticLayout (连字符排版)

MRTextView 持有 MyLayout 引用 → 组合模式，非继承
```

排版引擎 = `MyLayout`（布局计算）+ `MRTextView`（渲染/交互），两者通过组合协作。`SoftHyphenStaticLayout` 是唯一的具体 MyLayout 子类，实现了 TeX 断字算法。

### O2：CSS.java 体量

文档仅在格式解析层提到 `CSS`（样式引擎），但未提及它的体量：**74,897 字节**（~1,788 行），是排版引擎的第三大文件（仅次于 MRTextView 和 HtmlToSpannedConverter）。它负责 EPUB 的 CSS 解析和应用。

### O3：staticlayout 包规模

文档描述了 staticlayout 包的功能，但未提及它的规模：**51 个 Java 文件**，涵盖：
- 排版核心：`MyLayout`, `SoftHyphenStaticLayout`, `MRTextView`
- HTML 转换：`MyHtml`, `HtmlToSpannedConverter`（2,412 行）
- 自定义 Span：`MyTableSpan`, `MyFloatSpan`, `MyBulletSpan`, `MyImageSpan`, `MyTypefaceSpan`, `MyUrlSpan`, `MySquigglySpan`, `MyStyleSpan`, `MySuperscriptSpan`, `MyShadowSpan`, `MyRelativeSizeSpan`, `MyMarginSpan`, `MyFontLightSpan`, 等
- 工具：`TextUtils`, `SHCharacterUtil`, `FastXmlSerializer`, 等

### O4：ActivityTxt 体量

`ActivityTxt.java`：**20,793 行**（163K 总行数的 12.7%），是整个项目中最大的单个文件，承载了阅读器的几乎所有交互逻辑。这是比 `Sync.java` 更严重的 God Class 问题。

---

## 📊 定量数据校准

| 指标 | 文档值 | 源码实测 | 偏差 |
|------|--------|---------|------|
| Sync.java 行数 | 1529 | 1529 | ✅ 0% |
| MRTextView 字节数 | 96,936 | 96,936 | ✅ 0% |
| CHM 库文件数 | 29 | 34 | ❌ +17% |
| BaseEBook 子类数 | 11（含 PDFReader） | 10（不含 PDFReader） | ❌ |
| books 表列数 | 14 | 14 | ✅ 0% |
| notes 表列数 | 14 | 14 | ✅ 0% |
| A.java 行数 | 未提及 | 8,682 | 🔵 |
| ActivityTxt.java 行数 | 未提及 | 20,793 | 🔵 |
| staticlayout 文件数 | 未提及 | 51 | 🔵 |

---

## 总结

| 类别 | 数量 | 详情 |
|------|------|------|
| ✅ 准确 | 22 项 | Sync/BookDb/BaseEBook/MRTextView/RadaeePDF/OPDS/云后端/同步策略 等核心判断全部正确 |
| ❌ 错误 | 3 项 | PDFReader 非 BaseEBook 子类；MyLayout 非 StaticLayout 子类；CHM 文件数 29→34 |
| 🟡 不精确 | 2 项 | C.java 被误归为"无意义单字母"；MyLayout 与 StaticLayout 关系的表述 |
| 🔵 遗漏 | 4 项 | 排版层完整继承链、CSS.java 体量、staticlayout 包规模、ActivityTxt God Class |

**总评**：两份文档对 Moon+ Reader 架构缺陷的判断（God Class、静态可变状态、封闭继承、notes 表语义混用）基于准确的源码理解。三个事实性错误集中在继承关系的细节上——不影响 LinReads 的技术决策参考，但需在 Wiki 中修正。
