# 静读天下架构评审：合理性、改进空间与冗余设计

> 调研日期：2026-06-17
> 横向对比：KOReader（开源墨水屏，C++/Lua）、Readium SDK（标准 EPUB 工具链）、前沿国内阅读 App（C++/Skia 排版引擎）、Readest（本地优先 CRDT 同步）

---

## 一、整体判断

Moon+ Reader 是一个**功能完整、架构老化**的应用。核心代码写于 2010–2015 年前后，此后以叠加功能为主而未做结构性重构。在它的年代，这套设计是合理的；放在 2026 年，有 3 个严重问题、4 个中等问题，以及 1 处被严重高估的设计决策。

---

## 二、问题清单（按严重程度）

### 🔴 严重问题

#### S1：`Sync.java` 是 1529 行的 God Class，依赖大量静态可变状态

```java
// Sync.java — 公开静态字段，随处可变
public static ArrayList<String> cloudBookList = new ArrayList<>();
public static ArrayList<String> uploadShelfBookList;
public static boolean uploadBookFilesWorking;
public static long lastSyncShelfTime;
public static ArrayList<String> pdfCloudIsNewerList; // PDF 冲突单独处理——ad-hoc 异常
```

**问题**：
- 书架同步、封面同步、进度同步、下载管理全揉在一个类里
- 静态可变状态在多线程下无任何同步保护（`boolean syncWindowOpened` 等）
- `pdfCloudIsNewerList` 是专门给 PDF 打的补丁——说明同步逻辑本身没有通用冲突模型
- 无法单独测试任何一个同步路径

**横向对比**：Readest（开源 EPUB 阅读器，2025 年）用 CRDT + HLC（混合逻辑时钟）作为统一冲突模型，`replicas` 表多态存储不同类型的同步项，字段级 LWW，PDF 和 EPUB 走同一套路径，无特例分支。

---

#### S2：`notes` 表 schema 用 `highlightLength == 0` 区分进度、书签、高亮

```sql
-- 一张表承担三种语义
CREATE TABLE notes (
  lastChapter, lastSplitIndex, lastPosition,  -- 进度字段
  highlightLength, highlightColor,             -- 高亮字段
  bookmark, note, original,                    -- 书签/笔记字段
  underline, strikethrough                     -- 装饰字段
);
-- "进度记录" = 一条 highlightLength = 0 的 note，通过约定区分
```

**问题**：
- 语义模糊：查询进度需要 `WHERE highlightLength = 0`，这是隐式约定而非显式类型
- `bak1 text, bak2 text` 占位列：schema 演化靠加哑列，不可维护
- `books` 表同样有 `bak1/bak2`——说明这是全局模式

**横向对比**：KOReader 给每本书维护独立 sidecar `.sdr/` 目录，进度/书签/高亮各存独立文件，互不干扰，迁移一本书时附带所有注记。

---

#### S3：PDF 渲染强依赖 `com.radaee.pdf`（商业库）

**问题**：
- RadaeePDF 是付费商业库，GPL 不兼容，分发/开源受限
- PDF 渲染质量与路线图不受 Moon+ 控制
- Android 系统自带 `PdfRenderer`（API 21+）；Chromium 的 PDFium（被 Android WebView 使用）已在 API 35+ 以 `PdfRendererPreV` 形式暴露

**可替代方案**：
| 替代 | 许可 | 质量 |
|------|------|------|
| Android `PdfRenderer` (API 21+) | 系统内置 | 基础，够用 |
| `PdfRendererPreV` (API 35 PDFium) | 系统内置 | 好 |
| MuPDF (AGPL/商业双许可) | 可控 | 优 |
| Apache PdfBox (Apache 2.0) | 开源 | 中 |

---

### 🟡 中等问题

#### M1：`BaseEBook` 是封闭的继承层次，新增格式必须修改核心

（注：PDFReader 不在 BaseEBook 体系内——`PDFReader extends FrameLayout`，走独立渲染路径。）

```java
// 每种格式是 BaseEBook 的直接子类
abstract class BaseEBook { ... }
class Epub extends BaseEBook { ... }
class Mobi extends BaseEBook { ... }
// 新增格式 = 新建子类 + 修改调用方的 instanceof 判断链
```

**横向对比**：KOReader 的 `DocumentRegistry` 是注册表模式——格式解析器向注册表注册自己支持的扩展名，调用方不感知具体类型。符合开闭原则，插件可动态加载。

---

#### M2：无响应式数据层，Activity 直接操作 SQLite

```java
// BookDb.java — 直接返回 ArrayList，无 Flow/LiveData
static ArrayList<BookInfo> allBooks;
// Activity 直接调用
BookDb.getAllBooks(context) → ArrayList<BookInfo>
```

**问题**：UI 更新需手动触发，无法感知数据变化，极易产生数据/视图不同步 bug。

**现代做法**：Room + `Flow<List<BookInfo>>` → ViewModel `StateFlow` → Compose/RecyclerView 自动重组。

---

#### M3：`MRTextView`（97KB）自定义排版引擎——对 EPUB 来说是过度工程

Moon+ Reader 完全绕过 Android 原生 TextView，自实现：行布局、断字（TeX 算法）、Span、HTML 解析、图片浮动、表格。

**合理的部分**：对 TXT 和超大章节（中文小说可达百万字），自定义分页比 WebView 快。

**过度的部分**：对 EPUB，业界标准是 WebView（Readium）或者 C++/Skia（跨平台高性能方案）。MRTextView 只能跑在 Android，无法复用到 iOS 或 Web。

**前沿方案**：某国内头部阅读 App 用 C++/Skia 三层架构：
```
C++ 渲染引擎（ReaderSDK）     ← Expat SAX 解析 → 统一 DOM → Skia 光栅化
    ↕ 异步 Swift/Kotlin 包装层
    ↕ 业务插件层（RxSwift/RxJava 消息总线）
```
首屏延迟降低 >60%，内存减少 >40%，且 C++ 核心 iOS/Android 共享，平台特定代码 <10%。

---

#### M4：`A.java`、`C.java`、`T.java`——工具类命名不具语义

这三个类用单字母命名，是混淆后的产物（或者一开始就是反模式积累）。`BookDb.java` 本身逻辑超过 500 行，混合了 ORM、业务逻辑和 UI 相关逻辑。

---

### 🟢 合理但有取舍的设计

#### R1：进度定位用 `(chapter, splitIndex, position)` 三元组

比 EPUB CFI 轻量，对超大 TXT 文件更稳定（CFI 依赖 DOM，TXT 无 DOM）。对 LinReads 有参考价值。

#### R2：自实现 CHM 解析库（34 个类，含 LZX 解压）

完全自包含，无第三方依赖，合理。但仅 CHM 这一个格式就用了 34 个类，说明若要支持全格式，自实现成本极高。

#### R3：OPDS 协议支持

前瞻性的设计，与 Calibre 天然兼容。

---

## 三、架构缺陷根因总结

```
根因：功能叠加驱动，无迭代重构

2012  基础 EPUB/TXT 阅读
  ↓ 叠加
2014  云同步（Dropbox → Sync.java 生长）
  ↓ 叠加
2016  PDF（接入商业库 RadaeePDF）
  ↓ 叠加
2018  Readwise / TTS / 统计日历 / OPDS
  ↓ 叠加
2020+ 更多格式（Md, Docx...）

每次叠加都加重了 God Class 和静态状态，
从未对核心 schema / 同步模型 / 渲染层做过根本性重构
```

---

## 四、横向架构对比

| 维度 | Moon+ Reader | KOReader | Readium | 前沿国内 App |
|------|-------------|---------|---------|------------|
| 渲染引擎 | Java 自定义 StaticLayout | C++ (MuPDF/crengine) | WebView + JS | C++/Skia，跨平台 |
| 格式扩展 | 封闭继承层次 | 注册表模式（插件化）| Streamer/Navigator SDK | 插件总线 |
| 同步模型 | LWW + 静态状态 | 插件化（无统一模型）| 依赖平台 | 段落级 + 服务器冲突解决 |
| 数据层 | SQLite 裸调用 | 按书 sidecar 文件 | 各平台自定 | 响应式 Room/Core Data |
| 架构模式 | Activity + God Class | 模块 + 事件总线 | 三层 SDK | 三层 + 插件总线 |
| 可测性 | 极低（静态状态）| 中等 | 高 | 高 |

---

## 五、对 LinReads 的参考结论

| 静读天下做法 | LinReads 应如何 |
|------------|---------------|
| 进度 `(chapter, splitIndex, position)` | ✅ 采用，比 CFI 轻 |
| notes/progress 混表 | ❌ 分开：`reading_progress` 表 + `annotations` 表 |
| RadaeePDF 商业库 | ❌ 用 Android `PdfRenderer` / PDFium，Web 用 PDF.js |
| Sync God Class | ❌ 拆为：PositionSync / AnnotationSync / ShelfSync，各自独立 |
| `bak1/bak2` 哑列 | ❌ 用 `extra_json TEXT` 或正式版本迁移 |
| BaseEBook 继承层次 | ❌ 用 DocumentParser 注册表 + 接口 |
| 静态可变同步状态 | ❌ 用 Repository + Flow + 协程 |
| MRTextView 自定义排版 | ⚠️ 暂不做，epubjs 够用；TXT 大文件再评估 |
