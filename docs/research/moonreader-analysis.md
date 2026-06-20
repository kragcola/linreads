# Moon+ Reader 系统结构分析

> 来源：反编译源码（`moonreader-decompiled/`）+ 官方文档 + 社区资料
> 分析日期：2026-06-17
> APK 版本：moonreader-pro.apk（Pro 版）

---

## 系统层次图

```
┌─────────────────────────────────────────────────────────────────┐
│                        UI 层                                     │
│  ActivityMain（书架）  ActivityTxt（阅读）  Pref*（20+ 设置页）  │
│  BookShelfView  MRBookView  ComicView  BookCalendar              │
│  NewCurl3D（3D翻页）  FlipImageView  ProgressViews               │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│                      引擎层                                      │
│  staticlayout/MRTextView（核心排版引擎，97KB）                   │
│  MyLayout（自研抽象布局引擎，不继承 Android StaticLayout）       │
│    └── SoftHyphenStaticLayout（TeX 连字符实现）                  │
│  CSS（样式引擎）  MyHtml（HTML→Spanned 转换器）                  │
│  Hyphenation（TeX 连字符，多语言）                               │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│                      格式解析层                                  │
│  BaseEBook（抽象基类）                                           │
│    ├── Epub       ├── Mobi      ├── Chm（自实现，34 类含LZX解压）│
│    ├── Fb2        ├── Docx      ├── Rtf        ├── Umd           │
│    ├── Mhtml      ├── Md        └── ComicInfo（CBR/CBZ）         │
│  ★ PDFReader extends FrameLayout（非 BaseEBook），嵌入 RadaeePDF │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│                      存储层                                      │
│  BookDb（SQLite: mrbooks.db）                                    │
│    books / notes / statistics / covers2                          │
│  SharedPreferences（阅读设置、云配置）                           │
│  本地文件系统（书库、缓存、封面）                                 │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│                      同步 / 网络层                               │
│  Cloud（抽象）→ Dropbox / Gdrive / WebDav / Ftp                  │
│  Sync（协调器，1529 行）                                         │
│  OPDS（在线书库协议）                                            │
│  Readwise（高亮导出）                                            │
└─────────────────────────────────────────────────────────────────┘
```

---

## 一、格式解析层

**包**：`com.flyersoft.books`

除 PDF 外所有文本格式继承 `BaseEBook`，统一暴露 `chapters: ArrayList<Chapter>` 和 `pages: ArrayList<Chapter>`。PDF 独立于 BaseEBook 体系，`PDFReader extends FrameLayout`，直接嵌入 RadaeePDF 的渲染视图。

| 类 | 格式 | 备注 |
|----|------|------|
| `Epub` | EPUB 2/3 | 依赖 staticlayout HTML 引擎 |
| `Mobi` | MOBI/AZW3 | 自实现解析 + MyZip_Mobi |
| `PDFReader` | PDF | 商业库 **RadaeePDF**（com.radaee）|
| `Chm` | CHM | 完整自实现（chmlib 34个类，含 LZX 解压）|
| `Fb2` | FB2 | |
| `Docx` | DOCX | |
| `Rtf` | RTF | 依赖 com.rtfparserkit |
| `Umd` | UMD（中文格式）| |
| `Mhtml` | MHTML/MHT | |
| `Md` | Markdown | 依赖 com.vladsch |
| `ComicInfo` | CBR/CBZ | 图片漫画，独立 ComicView 渲染 |

`Chapter` 数据结构：
```
Chapter {
  html: String          // 章节 HTML 内容
  css: CSS              // 章节样式
  title: String
  id: String
  brokenChapter: bool   // 超长章节的分片标记
  additionalText: String
}
```

---

## 二、排版引擎（staticlayout）

**包**：`com.flyersoft.staticlayout`

这是 Moon+ Reader 的核心竞争力，完全绕过 Android 原生 TextView 自定义实现。

- **MRTextView**（96,936 字节）：主阅读视图，处理触摸、选择、高亮、脚注、双击查词
- **MyLayout**：继承 Android `StaticLayout`，扩展双向文本、自定义段距
- **HtmlToSpannedConverter / MyHtml**：HTML → Spanned 转换，支持表格（MyTableSpan）、浮动图片（MyFloatSpan）、脚注（MyUrlSpan）
- **Hyphenation**：TeX 连字符算法（SHTextTeXHyphenator），支持多语言断词
- **自定义 Span**：高亮（HighlightSpan）、下划线、删除线、波浪线（MySquigglySpan）、字体（MyTypefaceSpan）、图片（MyImageSpan）

文件抽象层（`SHFile` / `SHPhysicalFile` / `SHResourceFile`）统一处理本地/ZIP内部文件路径。

---

## 三、存储层

**SQLite 数据库**：`mrbooks.db`（BookDb.java）

**books 表**（书库元数据）：
```sql
CREATE TABLE books (
  _id INTEGER PRIMARY KEY AUTOINCREMENT,
  book TEXT,            -- 书名
  filename TEXT,        -- 文件绝对路径
  lowerFilename TEXT,   -- 小写路径（检索用）
  author TEXT,
  description TEXT,
  category TEXT,
  thumbFile TEXT,       -- 缩略图路径
  coverFile TEXT,       -- 封面路径
  addTime TEXT,
  favorite TEXT,        -- 收藏分组
  downloadUrl TEXT,
  rate TEXT,            -- 评分
  bak1 TEXT, bak2 TEXT  -- 备用字段
);
```

**notes 表**（书签 / 高亮 / 笔记，三合一）：
```sql
CREATE TABLE notes (
  _id INTEGER PRIMARY KEY AUTOINCREMENT,
  book TEXT,
  filename TEXT,
  lowerFilename TEXT,
  lastChapter NUMERIC,      -- 章节索引
  lastSplitIndex NUMERIC,   -- 分片索引（超长章节）
  lastPosition NUMERIC,     -- 字符位置（用于进度和书签）
  highlightLength NUMERIC,  -- 高亮长度
  highlightColor NUMERIC,   -- 高亮颜色（ARGB int）
  time NUMERIC,             -- 时间戳（毫秒）
  bookmark TEXT,            -- 书签标注文字
  note TEXT,                -- 用户笔记
  original TEXT,            -- 原始高亮文字
  underline NUMERIC,        -- bool
  strikethrough NUMERIC,    -- bool
  bak TEXT
);
```

> **关键发现**：阅读进度（lastChapter + lastSplitIndex + lastPosition）和书签高亮共用同一张表。进度记录是 `highlightLength = 0` 的特殊 note。

**statistics 表**（阅读统计）：
```sql
CREATE TABLE statistics (
  filename TEXT,
  usedTime NUMERIC,   -- 累计阅读时长（秒）
  readWords NUMERIC,  -- 累计阅读字数
  dates TEXT          -- 每日阅读记录（序列化）
);
```

**书架分类**（ShelfType 枚举）：
| 常量 | 值 | 含义 |
|------|----|------|
| SHELF_TYPE_ALL | 0 | 全部 |
| SHELF_TYPE_FAVORITES | 1 | 收藏 |
| SHELF_TYPE_SERIES | 2 | 系列 |
| SHELF_TYPE_AUTHORS | 3 | 作者 |
| SHELF_TYPE_CATEGORIES | 4 | 分类 |
| SHELF_TYPE_FOLDERS | 5 | 文件夹 |
| SHELF_TYPE_RATINGS | 6 | 评分 |

---

## 四、云同步层

**包**：`com.flyersoft.components.cloud`

### 架构

```
Cloud（抽象基类）
  ├── callbacks: AfterDownload / AfterLogin / AfterUpload / OnGetBackupList
  ├── Dropbox    — Dropbox Java SDK
  ├── Gdrive     — Google Drive API（gRPC / REST）
  ├── WebDav     — 自实现 HTTP（WAuth + WHandler + WHttp）
  └── Ftp        — FTP 连接池（最多5个并发连接）

Sync（协调器）
  ├── 触发时机：onPause（切换/熄屏/退出时上传）
  ├── 同步内容：进度位置 / 书签&笔记 / 封面 / 书架列表
  └── 数据结构：CloudBook（含 deviceId、groupBooks）
```

### 同步文件格式

同步单元是 `.mrbooks`（ZIP 格式），内含：
- 进度记录（JSON / 序列化）
- 书签 / 笔记导出
- 书架配置（ShelfOptions）

封面单独上传（coverUploadList / coverDownloadList）。

### 同步策略（从代码推断）

- **LWW（Last-Write-Wins）**：进度同步——上传时带时间戳，下载时对比本地时间戳，更新者获胜
- **覆盖式**：书架列表——以云端为权威（`downloadedShelfList`）
- **增量**：封面——仅上传变更列表（`coverUploadList` / `coverUploadList2`）
- **竞争检测**：`pdfCloudIsNewerList` 单独跟踪 PDF 进度冲突

### 支持的云后端

| 后端 | 认证 | 自托管 |
|------|------|--------|
| Dropbox | OAuth2 | ❌ |
| Google Drive | OAuth2 | ❌ |
| WebDAV | HTTP Basic / Digest | ✅（Nextcloud、Seafile 等）|
| FTP | 用户名/密码 | ✅ |

---

## 五、OPDS 在线书库

**包**：`com.flyersoft.opds`

支持 OPDS 1.x 协议（Atom feed）：
- `OpdsSite`：书库配置（URL、auth）
- `OpdsEntries / OpdsEntry`：书目列表/单本
- `OpdsJson`：JSON 序列化（内部缓存格式）
- 与 Calibre Content Server 完全兼容（Calibre 实现了 OPDS）

---

## 六、后台服务

| 服务 | 功能 |
|------|------|
| `BookDownloadService` | 后台下载书籍（支持取消，`BookDownloadCancelAct`）|
| `BookTtsService` | TTS 朗读（Android TextToSpeech API）|
| `WidgetService / WidgetProvider` | 主屏小组件（显示阅读进度/快捷开书）|

---

## 七、第三方依赖

| 包 | 用途 |
|----|------|
| `com.radaee.pdf` | PDF 渲染（RadaeePDF，商业库）|
| `com.dropbox` | Dropbox SDK |
| `io.grpc` | Google Drive API 传输层 |
| `com.rtfparserkit` | RTF 解析 |
| `com.vladsch` | Markdown 解析 |
| `com.luhuiguo.chinese` | 拼音 / 简繁转换 |
| `com.facebook` | 分析/崩溃上报（推测）|
| `androidx.*` | Jetpack UI |

---

## 八、Readwise 集成

`components/Readwise.java`：将高亮/笔记导出到 Readwise（第三方阅读笔记服务），通过 Readwise HTTP API 推送。这是专业版功能。

---

## 九、关键设计决策（对 LinReads 的启发）

> ⚠️ 2026-06-18 源码交叉验证后修正：PDFReader 非 BaseEBook 子类，MyLayout 非 StaticLayout 子类，CHM 库为 34 类（非 29）。详见 `docs/wiki/Architecture-Verification.md`

| 设计点 | Moon+ Reader 做法 | LinReads 可借鉴 |
|--------|------------------|----------------|
| 进度定位 | `(chapter, splitIndex, position)` 三元组 | 比 CFI 更轻量，适合 TXT/大章节 |
| 书签 / 进度共表 | notes 表统一存储，`highlightLength=0` 表示进度 | 简化 schema |
| 排版引擎 | 完全自实现 MRTextView | epubjs 已够用；自研成本极高 |
| 云同步 | 进度 LWW，书架覆盖，封面增量 | 与 linreads-sync skill 的 LWW+Union 方案一致 |
| PDF 渲染 | 商业库 RadaeePDF | 可用 Mozilla PDF.js（Web）/ Apache PdfBox（Android）|
| OPDS | 原生支持 OPDS + Calibre | LinReads 已走 Calibre REST，OPDS 是备选 |
| CHM | 自实现 29 个类（LZX 解压）| 暂不支持，成本太高 |
| 阅读统计 | 独立 statistics 表（时长+字数+日历）| PrefBookCalendar + BookCalendar view |
