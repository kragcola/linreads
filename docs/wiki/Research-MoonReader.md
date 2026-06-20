# Research: Moon+ Reader

> 静读天下 (Moon+ Reader) 架构评审——合理性、改进空间与冗余设计
> 调研日期：2026-06-17
> ⚠️ 2026-06-18 源码交叉验证后修正 3 处错误，详见 [Architecture Verification](Architecture-Verification.md)

## 整体判断

Moon+ Reader 是一个**功能完整、架构老化**的应用。核心代码写于 2010–2015 年前后，此后以叠加功能为主而未做结构性重构。在它的年代，这套设计是合理的；放在 2026 年，有 3 个严重问题、4 个中等问题。

## 严重问题

### S1：`Sync.java` 是 1529 行的 God Class

- 书架同步、封面同步、进度同步、下载管理全揉在一个类里
- 静态可变状态在多线程下无任何同步保护
- `pdfCloudIsNewerList` 是专门给 PDF 打的补丁——说明同步逻辑本身没有通用冲突模型
- 无法单独测试任何一个同步路径

### S2：`notes` 表用 `highlightLength == 0` 区分进度、书签、高亮

- 一张表承担三种语义，靠隐式约定区分
- `bak1 text, bak2 text` 占位列——schema 演化靠加哑列
- KOReader 对比：每本书独立 sidecar `.sdr/` 目录，进度/书签/高亮各存独立文件

### S3：PDF 渲染强依赖 `com.radaee.pdf`（商业库）

- RadaeePDF 是付费商业库，GPL 不兼容
- Android 系统自带 `PdfRenderer`（API 21+），API 35+ 有 PDFium 内核

## 中等问题

### M1：`BaseEBook` 封闭继承层次

新增格式必须修改核心代码（`instanceof` 判断链）。KOReader 用注册表模式（`DocumentRegistry`），符合开闭原则。

### M2：无响应式数据层

Activity 直接操作 SQLite，返回 `ArrayList` 而非 `Flow`/`LiveData`，UI 更新需手动触发。

### M3：`MRTextView`（97KB）对 EPUB 来说是过度工程

对 TXT 和超大章节合理，但对 EPUB，业界标准是 WebView（Readium）或 C++/Skia（跨平台）。MRTextView 只能跑在 Android。

### M4：`A.java`、`C.java`、`T.java`——单字母工具类

不具语义，混淆/反模式积累。

## 合理但有参考价值的设计

### R1：进度定位用 `(chapter, splitIndex, position)` 三元组

比 EPUB CFI 轻量，对超大 TXT 文件更稳定（CFI 依赖 DOM，TXT 无 DOM）。✅ LinReads 已采用。

### R2：自实现 CHM 解析库（29 个类，含 LZX 解压）

完全自包含，无第三方依赖。但仅 CHM 一个格式就用了 29 个类 → 全格式自实现成本极高。

### R3：OPDS 协议支持

前瞻性设计，与 Calibre 天然兼容。

## 架构缺陷根因

```
2012  基础 EPUB/TXT 阅读
  ↓ 叠加
2014  云同步（Sync.java 生长）
  ↓ 叠加
2016  PDF（接入 RadaeePDF）
  ↓ 叠加
2018  Readwise / TTS / 统计
  ↓ 叠加
2020+ 更多格式

每次叠加加重 God Class 和静态状态，
从未对核心 schema/同步模型/渲染层做过根本性重构
```

## 横向架构对比

| 维度 | Moon+ Reader | KOReader | Readium | 前沿国内 App |
|------|-------------|---------|---------|------------|
| 渲染引擎 | Java 自定义 StaticLayout | C++ (MuPDF/crengine) | WebView + JS | C++/Skia，跨平台 |
| 格式扩展 | 封闭继承层次 | 注册表模式 | Streamer/Navigator SDK | 插件总线 |
| 同步模型 | LWW + 静态状态 | 插件化 | 依赖平台 | 段落级 + 服务器冲突解决 |
| 数据层 | SQLite 裸调用 | 按书 sidecar 文件 | 各平台自定 | 响应式 Room/Core Data |
| 可测性 | 极低 | 中等 | 高 | 高 |

## 对 LinReads 的参考结论

| 静读天下做法 | LinReads 应如何 |
|------------|---------------|
| 进度 `(chapter, splitIndex, position)` | ✅ 采用 |
| notes/progress 混表 | ❌ 分开：`reading_progress` 表 + `annotations` 表 |
| RadaeePDF 商业库 | ❌ 用 Android `PdfRenderer` / PDFium |
| Sync God Class | ❌ 拆为 PositionSync / AnnotationSync / ShelfSync |
| `bak1/bak2` 哑列 | ❌ 用 `extra_json TEXT` 或正式迁移 |
| BaseEBook 继承层次 | ❌ 用 ReaderEngine 接口 + 注册表 |
| 静态可变同步状态 | ❌ 用 Repository + Flow + 协程 |
| MRTextView 自定义排版 | ⚠️ 暂不做，epubjs 够用；TXT 大文件用 TxtVirtualPager |

---

_参考：_ [docs/research/moonreader-architecture-review.md](../research/moonreader-architecture-review.md) · [docs/research/moonreader-analysis.md](../research/moonreader-analysis.md)
