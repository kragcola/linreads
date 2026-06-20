# 架构定夺记录

> 日期：2026-06-18
> 状态：决策已定，修复留待下一轮

---

## 一、用户决策

### 决策 1：EPUB 引擎 — 保持 epubjs，foliate-js 为备选

**选择**：默认使用 **epubjs 0.3.x**。

**理由**：
- epubjs 已在项目 Web 端验证可用（`web/src/pages/Reader.tsx`）
- Android WebView 中的行为已知可预期
- foliate-js 作者明确声明"不稳定，API 随时可能变"、"当前不可能安全处理 EPUB 脚本"
- foliate-js 的 blob: URL 同源问题与 Android WebView CSP 存在未解决的冲突

**备选**：foliate-js 作为未来升级候选，待其 API 稳定 + WebView 安全模型验证后评估切换。

**搜索结果新增信息**：
- `@asteasolutions/epub-reader`（npm，2026-02）：另一个活跃维护的 EPUB 库，支持 Media Overlays
- `intity/epub-js`：epubjs 的活跃 fork
- `TreineticEpubReader`：Readium JS viewer 的流行 fork
- `@d-i-t-a/reader`（R2D2BC）：Readium v2 的 Web 实现，模块化设计
- **`fbaldhagen/readbooks-v2-android`**：直接对标项目——Android 阅读器，使用 **Readium Kotlin Toolkit 3.1.2**（非 JS 库），Clean Architecture，**完全离线**，MIT 许可。这是 LinReads 最接近的参考实现

### 决策 2：模块粒度 — 保持细粒度

**选择**：维持 20 个 Gradle 模块，不合并。

**理由**：用户明确"保持颗粒细度"。编译期隔离 > 文件数便利。

### 决策 3：手写笔 Phase 策略

**选择**：
- **Phase 1**：不实现手写笔，仅架构预留（`:ink` 模块建好，`InkOverlay` 接口定义好，androidx.ink 依赖声明但未调用）
- **EPUB 笔写方案**：选择 **B（段落级自由书写）**。用户可以在段落旁边自由写，笔迹锚定到最近的段落（CSS Selector + offsetX/Y）
- Phase 2 实现

**架构修正**：`:ink` 模块需在 `build.gradle.kts` 中标记 `implementation("androidx.ink:ink-authoring:1.0.0")` 但代码中仅有接口定义和 TODO stub。reader feature 的视图层级预留 `FrameLayout` 四层结构。

**搜索结果新增信息**：
- **`tilgovi/dom-anchor-text-position`**（MIT, 26 stars）：W3C Web Annotation TextPositionSelector 的 JavaScript 实现。`fromRange(root, range) → {start, end}` 和 `toRange(root, {start, end}) → Range`。可嵌入 foliate-js/epubjs 的 WebView 中实现文字锚定
- **`@net7/annotator`**（TypeScript）：多锚定策略 + 自动降级。失败时尝试备选策略
- **Hypothesis "fuzzy anchoring"**：从 PositionSelector 恢复，处理 DOM 结构变化但文本内容不变的情况

### 决策 4：离线策略 — C（智能缓存）默认 + A（手动下载）按钮

**选择**：
- **默认行为（C）**：最近读过的 N 本书自动缓存到本地（LRU 淘汰，N=5 可配置）。用户无需手动操作
- **手动下载（A）**：书架每本书有"下载"按钮，用户可主动下载任意书籍
- 下载完成后自动出现在书架页面，与 Calibre 远程书籍无 UI 区别（仅角标显示"已下载"）
- 用户离开 LAN 后，书架自动切换为"仅显示已下载"

**架构影响**：
- `BookSource` 接口需增加 `suspend fun download(bookId: String): Result<File, LinReadsError>`
- `CalibreBookSource.download()` → 从 Calibre `/get/<format>/<id>` 下载 → 存到 `context.filesDir/books/<bookId>.<format>`
- Room `books` 表新增字段：`downloadStatus: DownloadStatus`（NOT_DOWNLOADED / DOWNLOADING / DOWNLOADED / FAILED）
- Room `books` 表新增字段：`localFilePath: String?`
- `LibraryViewModel` 支持"仅已下载"筛选模式
- 下载使用 `WorkManager` 后台任务（可暂停/恢复/取消）
- 缓存淘汰使用 `LRU eviction policy`：当缓存总量超过上限，删除最久未读的书

**搜索结果新增信息**：
- `fbaldhagen/readbooks-v2-android` 已实现完整的离线下载模式——"Fully offline once a book is downloaded"，使用 Readium 引擎本地渲染

### 决策 5：同步 — 预留接口，Phase 1 不实现

**选择**：
- **主路径（A）**：自建轻量同步服务（对标 korrosync）。Rust/axum + redb，~5 个 REST 端点，跑在 NAS/树莓派上
- **兜底（B）**：文件同步——进度/书签导出为 JSON → WebDAV/Syncthing 共享目录 → 其他端读取合并
- **Phase 1**：仅定义 `SyncBackend` 接口 + `SyncManager`（no-op 实现）。进度/书签本地存储完成后发 `BookProgressSaved` 事件，SyncManager 目前丢弃该事件。接口预留完整签名

**搜索结果新增信息**：
- **`szaffarano/korrosync`**（Rust, MIT, v0.4.0）：**可直接复用或参考**。已实现：用户注册/认证、进度上传/下载、healthcheck、rate limiting、TLS 支持、Docker 部署、systemd service。API：`PUT /syncs/progress`、`GET /syncs/progress/{document}`。正是 LinReads 需要的
- **`JediRhymeTrix/readsync`**（Windows service）：跨 KOReader↔Moon+↔Calibre↔Goodreads 同步。证明了本地 LAN sync 的可行性
- **Calibre-Web + KOReader sync**：已有的生产级方案。LinReads 可以直接读取 Calibre-Web 的进度字段

---

## 二、本轮修复清单（下一轮执行）

### P0：构建阻塞（直接导致编译失败）

| # | 问题 | 修复 |
|---|------|------|
| 1 | `nanohttpd` 不在 `libs.versions.toml` | 添加 `org.nanohttpd:nanohttpd:2.3.1` |
| 2 | MuPDF 仓库 `maven.ghostscript.com` 不在 `settings.gradle.kts` | 添加仓库声明 |
| 3 | Gradle wrapper 未生成 | `gradle wrapper --gradle-version 8.11` |
| 4 | Compose BOM 待升级 | `2024.12.01` → `2026.06.00` |

### P1：架构一致性

| # | 问题 | 修复 |
|---|------|------|
| 5 | `Locator` 在 `:core:model` 和 `:render:api` 中重复定义 | 从 `:render:api` 删除，`import dev.readflow.core.model.Locator` |
| 6 | `ExtensionContext.readerState: StateFlow<ReaderState>` 跨层依赖违规 | `ReaderState` 移至 `:core:model`（作为领域模型） |
| 7 | 模块计数不一致（19 vs 20 vs 21） | 统一为实际模块数 |
| 8 | `ReaderRootLayout.kt` 位置冲突（`:app` vs `:features:reader`） | 移至 `:features:reader` |
| 9 | PDF API 等级不一致（21+ vs 26+） | 统一为 minSdk=26 |

### P2：数据模型补全

| # | 问题 | 修复 |
|---|------|------|
| 10 | `ReaderState` 缺失字段 | 添加 `loadingState`, `isUiVisible`, `bookId` |
| 11 | `InkBrush` 类型未定义 | 补全 sealed class |
| 12 | `ExtensionSettings` 类型未定义 | 补全 data class |
| 13 | `DownloadStatus` 枚举缺失 | 新增 `NOT_DOWNLOADED / DOWNLOADING / DOWNLOADED / FAILED` |
| 14 | `SyncBackend` 接口未定义 | 补全接口签名（no-op impl） |

### P3：架构缺口

| # | 问题 | 修复 |
|---|------|------|
| 15 | 离线书籍缓存策略未文档化 | 写入架构文档：LRU 缓存 + 手动下载 + WorkManager |
| 16 | 同步后端策略未文档化 | 写入架构文档：korrosync 参考 + WebDAV 兜底 + Phase 1 no-op |
| 17 | `render:docx`, `render:cbz`, `render:md` 模块未出现在模块树 | 添加或合并到 `render:mupdf` |
| 18 | 性能预算未定义 | 添加：冷启动 < 2s，内存峰值 < 200MB（含 WebView），MuPDF .so ~15MB |
| 19 | 测试策略未定义 | 至少定义每层测试框架和 mock 策略 |
| 20 | foliate-js vs epubjs 矛盾 | 统一为 epubjs（见决策 1），foliate-js 作为注释中的备选方案 |

---

## 三、新发现的外部参考（本轮搜索成果）

### 直接对标项目

- **`fbaldhagen/readbooks-v2-android`**（MIT）：最接近的参考实现。Readium Kotlin Toolkit 3.1.2、离线 EPUB（"fully offline once downloaded"）、TTS、阅读统计、成就系统。多模块 Clean Architecture。Compose + Hilt + Room + DataStore。可直接借鉴其离线下载架构

### 同步服务器

- **`szaffarano/korrosync`**（MIT, Rust）：可直接部署的 KOReader 同步服务器。5 个 REST 端点、用户认证、redb 嵌入式数据库、Docker/Systemd 部署。与 LinReads 需求高度匹配

### EPUB WebView 引擎

- **`@asteasolutions/epub-reader`**（npm, 2026-02）：活跃维护的替代方案，支持 Media Overlays
- **`fbaldhagen/readbooks-v2-android`** 选择了 **Readium Kotlin Toolkit** 而非 JS 引擎——这提示了一个完全不同的路径：用原生 Android Fragment 渲染 EPUB，完全不依赖 WebView JS 引擎

### 文字锚定

- **`tilgovi/dom-anchor-text-position`**（MIT）：W3C TextPositionSelector 的 JS 实现，可直接嵌入 WebView 用于 ink text anchoring
- **`@net7/annotator`**（TypeScript）：多策略锚定 + 自动降级

---

_下一轮：执行以上 20 项修复 + 更新 v2 架构文档 + 更新 CLAUDE.md_
