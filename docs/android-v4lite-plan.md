# LinReads Android v4lite 最小实现计划

> **创建**: 2026-06-20
> **依据**: [android-architecture-v4.md](android-architecture-v4.md) · 用户验收 Phase A（本地阅读闭环）
> **目标**: TXT ✅ + EPUB + PDF 三格式本地导入阅读，无需账号/Calibre/网络
> **不含**: Calibre书源、进度同步、Ink手写、搜索、书签、TOC、MD引擎

---

## 一、当前已完成（Phase 1 + TXT切片，2026-06-19）

| 状态 | 内容 |
|------|------|
| ✅ | `build-logic/` + 4 convention plugins |
| ✅ | 9 核心模块骨架（`core:model/calibre/database/prefs/sync/ui` + `extensions:api` + `features:library/reader`） |
| ✅ | `render:api` 引擎接口 + Registry + PageTransitionHost 抽象 |
| ✅ | `render:txt` TxtVirtualPagerEngine（RecyclerView + progression 上报） |
| ✅ | `render:animate` NoTransitionHost + DefaultPageTransitionHostFactory |
| ✅ | `render:epub/pdf/md/features:settings` 空壳（phase2 resolve 用） |
| ✅ | `LocalFileBookSource` SAF导入逻辑（待接UI触发） |
| ✅ | Room 5表 entities + DAOs 签名 |
| ✅ | `LibraryViewModel` 绑定 `LibraryRepository`（observeShelf） |
| ✅ | `ReaderViewModel` 最小MVI（OpenBook/CloseBook） |
| ✅ | `-Preadflow.phase=2 :app:assembleDebug` BUILD SUCCESSFUL |

**剩余差距**：书库未绑定真实数据、SAF picker未接UI、进度未持久化、EPUB/PDF引擎空壳未实装、ReaderScreen无UI chrome。

---

## 二、UI 互动清单（v4lite 范围）

### 2.1 LibraryScreen

| 互动 | 实现方式 |
|------|---------|
| 书架列表展示 | `BookGrid` observe `LibraryUiState.items` |
| 空状态提示 | `EmptyState`（含「导入本地书」按钮） |
| 导入本地书 | FAB → `ActivityResultLauncher<Array<String>>` (SAF) → `LocalFileBookSource.import` → Room upsert |
| 打开书 | 点击 BookCard → `navigate(ReaderRoute(bookId))` |
| 最近阅读 | 书架按 `lastReadAt` 降序，已有进度的书优先显示 |

### 2.2 ReaderScreen

| 互动 | 实现方式 | Phase |
|------|---------|-------|
| 顶栏：返回 + 书名 | TopBar Composable，点击文档区 toggle | L2 |
| 底栏：进度条 + 进度% | BottomBar，bind `ReaderState.progression` | L2 |
| 点击中间区域 toggle chrome | `ToggleChrome` Intent，TopBar/BottomBar animateIn/Out | L2 |
| TXT/EPUB 滚动 | RecyclerView/LazyColumn 原生，无手势拦截 | L2/L3 |
| PDF 翻页 | 左/右点击区域（左1/3上页，右2/3下页） | L4 |
| 字号调整 | BottomSheet slider（sp: 12/14/16/18/20/22），`SetFontSize` Intent | L2 |
| 主题切换 | 三选一 chip（白/暗/黄纸），`SetTheme` Intent | L2 |
| 进度恢复 | openBook时从Room查 ReadingProgressEntity → `GoTo(locator)` | L2 |

**不在v4lite范围（Phase B+）**：TOC、搜索、书签、文字选择/高亮、Ink手写、Calibre连接、进度同步。

---

## 三、多格式支持矩阵

| 格式 | 引擎 | 核心依赖 | Locator 策略 | 状态 |
|------|------|---------|-------------|------|
| TXT | `TxtVirtualPagerEngine` | RecyclerView | `ByteOffset` | ✅ 已实装 |
| EPUB | `EpubReflowEngine` | ZipFile + jsoup + Compose | `Section(spineIndex, charOffset)` | ❌ L3 实装 |
| PDF | `PdfRendererEngine` | PdfRenderer (API 26+) | `Page(index)` | ❌ L4 实装 |
| MD | `MarkdownEngine`（空壳） | Markwon | `ByteOffset` | 🔵 低优先级，空壳保留 |

---

## 四、本地导入阅读完整流程

```
用户点击「导入」
  → ActivityResultLauncher (SAF, MIME: text/plain + application/epub+zip + application/pdf)
  → LocalFileBookSource.import(uri)          // 已实现：拷贝到 files/books/，派生 BookMeta
  → LibraryRepository.upsertBook(meta)       // Room INSERT OR REPLACE
  → LibraryViewModel.observeShelf() 自动刷新  // StateFlow 驱动
  → 用户点击书目
  → navigate(ReaderRoute(bookId))
  → ReaderViewModel.onIntent(OpenBook(localUri))
  → ReaderEngineRegistry.resolve(uri)        // 按扩展名选引擎
  → engine.openBook(uri)
  → 挂载 PageTransitionHost
  → 查 ReadingProgressEntity → GoTo(locator)  // 进度恢复
  
阅读中：
  engine.progression → ReaderViewModel (2s debounce) → Room.upsert(ReadingProgressEntity)
```

**ACTION_VIEW / ACTION_SEND 直接打开**：`AndroidManifest.xml` 声明 intent-filter，`MainActivity.handleIncomingIntent` → 直接跳 ReaderScreen，同时后台 upsert 到书架。

---

## 五、阶段划分

> **Phase 编号 L1–L5 对应 v4lite 实装序，≠ Gradle构建 phase 1/2/3**。
> Gradle构建 phase 保持现有配置（`-Preadflow.phase=2` 覆盖全部 v4lite 范围）。

---

### Phase L1 — 书库接入（预计 3–4天）

**目标**：LibraryScreen 显示真实书目，SAF 导入可用，TXT 从书架可打开阅读。

| # | 任务 | 文件 |
|---|------|------|
| L1-1 | `LibraryRepository` 实现：`observeShelf()` → Room `BookDao.observeAll()` + join progress，map 到 `LibraryItem` | `core/database/LibraryRepository.kt` |
| L1-2 | `LibraryScreen` 完善：`BookGrid` 绑定真实 state，`EmptyState` 含导入按钮，加载/错误态 | `features/library/LibraryScreen.kt` |
| L1-3 | SAF picker 接入：`rememberLauncherForActivityResult` → `onImportLocal` → `LocalFileBookSource.import` → `repository.upsertBook` | `features/library/LibraryScreen.kt` |
| L1-4 | Navigation：`LibraryScreen` 点击书目 → `navigate("reader/{bookId}")` | `app/ReadflowApp.kt` |
| L1-5 | `FirstLaunchSeeder`：首次启动把 `assets/sample.txt` 导入书架（测试用，可选） | `extensions/api/FirstLaunchSeeder.kt` |
| L1-6 | Koin：`LibraryViewModel(repository)` 绑定 Room db 实例 | `app/AppModules.kt` |

**验收**：
- 空书架显示 EmptyState；导入 TXT 后书架刷新并显示卡片
- 点击书目能打开 ReaderScreen（此时 TXT 已可阅读）

---

### Phase L2 — Reader 完善（预计 3–4天）

**目标**：TXT 阅读 + 进度持久化 + 基础 UI chrome（字号/主题/进度条）。

| # | 任务 | 文件 |
|---|------|------|
| L2-1 | `ReaderViewModel` 扩展：`SetFontSize` / `SetTheme` / `ToggleChrome` Intent 处理；progression 回调 → Room 2s debounce 写 `ReadingProgressEntity` | `features/reader/ReaderViewModel.kt` |
| L2-2 | `ReaderViewModel.openBook`：先查 Room progress，engine.openBook 后 `GoTo(savedLocator)` | `features/reader/ReaderViewModel.kt` |
| L2-3 | `ReaderScreen` TopBar：返回按钮 + 书名；BottomBar：LinearProgressIndicator + 进度% + 字号/主题入口 | `features/reader/ReaderScreen.kt` |
| L2-4 | chrome toggle：点击文档区 `ToggleChrome`，TopBar/BottomBar `animateEnterExit` | `features/reader/ReaderScreen.kt` |
| L2-5 | `SettingsRepository` 实现：DataStore 读写 `fontSize`/`themeMode`；ViewModel 初始化时读取 | `core/prefs/SettingsRepository.kt` |
| L2-6 | `TxtVirtualPagerEngine` progression 回调接入：scroll listener → `engine.currentLocator` → Intent `ReportProgress` | `render/txt/TxtVirtualPagerEngine.kt` |
| L2-7 | `ReaderState` 扩展为可渲染 state：`progression: Float`, `isUiVisible: Boolean`, `fontSizeSp: Float`, `themeMode: ThemeMode` | `features/reader/ReaderViewModel.kt` |

**验收**：
- 关闭再开 TXT，进度恢复到上次位置
- 字号滑块调整后文字大小变化
- 主题切换白/暗/黄纸

---

### Phase L3 — EPUB 原生重排（预计 5–7天，最高技术风险）

**目标**：EPUB 文件可以在 `:render:epub` 打通阅读链路（连续滚动模式，`pagingKind=CONTINUOUS`）。

> ⚠️ 最高风险点：复杂 EPUB CSS/嵌套/图文混排，按降级矩阵实现，不求像素完美。

| # | 任务 |
|---|------|
| L3-1 | **EpubParser**（ZipFile）：解析 `META-INF/container.xml` → OPF → spine → 每个 spine 项读 XHTML，用 jsoup 提取 body 文本节点序列 → `List<ReaderItem>` |
| L3-2 | **ReaderItem** 数据结构（`:render:epub` 内部）：`data class ReaderItem(spineIndex: Int, charOffset: Int, type: Type, text: String?, imageUri: Uri?)` |
| L3-3 | **EpubReflowEngine**：`LazyColumn` 渲染 `List<ReaderItem>`，`buildAnnotatedString` 处理段落样式（h1-h6 字重/字号，em/strong，纯文本段落），图片用 `AsyncImage`（Coil） |
| L3-4 | **Locator 映射**：scroll listener → 可见第一个 `ReaderItem` → `Locator.Section(spineIndex, elementIndex, charOffset)`；`GoTo(locator)` 时 `LazyListState.scrollToItem` |
| L3-5 | **降级矩阵**（必须实现）：`<table>` → 行流式文本；`<style>` → 忽略；`<script>` → 忽略；未知标签 → 提取文本内容 |
| L3-6 | **totalProgression 预扫**：`openBook` 时遍历所有 spine 项统计纯文本字符数，供进度主键用 |
| L3-7 | **EngineDescriptor** 注册 + Koin 绑定 |
| L3-8 | 真实 EPUB 验证（3-5本不同复杂度）：中文EPUB、图文混排、多spine章节 |

**降级矩阵**（EPUB → AnnotatedString，必须落实）：

| EPUB 元素 | 处理 |
|-----------|------|
| `<h1>-<h6>` | SpanStyle(fontSize 按级别, fontWeight=Bold) |
| `<p>` | 标准段落，首行缩进 2em |
| `<em>/<i>` | fontStyle=Italic |
| `<strong>/<b>` | fontWeight=Bold |
| `<img>` | ReaderItem.Image → AsyncImage，撑满列宽 |
| `<table>` | 提取单元格文本，逗号分隔，降级段落 |
| `<a>` | 提取文本，去链接（Phase B+ 实现内链跳转） |
| `@font-face` / CSS | 忽略，使用系统字体 + 用户设定字号 |
| 内嵌远程资源 | 不加载（F11 安全约定） |

**验收**：标准 EPUB 能读完整章节；字号变化后 `spineIndex+charOffset` 定位稳定（同一段落不跑偏）；大书（10MB+）内存 < 200MB PSS。

---

### Phase L4 — PDF 引擎（预计 2–3天）

**目标**：PDF 分页阅读，`PdfRenderer`（API 26+）。

| # | 任务 |
|---|------|
| L4-1 | **PdfRendererEngine**：`openBook` 打开 `PdfRenderer`；`pagingKind=PAGED` |
| L4-2 | per-page `ImageView`：`renderPage(index)` → `Bitmap` → `ImageView`（`ScaleType.FIT_CENTER`） |
| L4-3 | **PageTransitionHost**：`SlideFadeTransitionHost`（ViewPager2 包装 per-page ImageView）或直接 `NoTransitionHost`（连续滚动，简单但页感差）——推荐先做 ViewPager2 分页 |
| L4-4 | **Locator 映射**：`Locator.Page(index)`；`GoTo` → `ViewPager2.setCurrentItem` |
| L4-5 | Bitmap LRU 缓存（当前页±2页，释放远端），防 OOM |
| L4-6 | EngineDescriptor 注册 + Koin 绑定 |

**验收**：PDF 能翻页；进度恢复到页；1080p渲染 < 300ms。

---

### Phase L5 — Phase A 验收门（预计 2–3天）

**目标**：满足用户验收 Phase A 全部条件，可对外发布。

| # | 任务 |
|---|------|
| L5-1 | `AndroidManifest` 声明 `ACTION_VIEW` + `ACTION_SEND` intent-filter（MIME: txt/epub/pdf） |
| L5-2 | `MainActivity.handleIncomingIntent`：外部打开 → upsert 书架 → 直接进 ReaderScreen |
| L5-3 | 性能测量 gate：冷启动 < 2s，1MB TXT首开 < 500ms，EPUB首章 < 1s，翻页 < 50ms，内存 < 200MB PSS |
| L5-4 | TalkBack smoke：ReaderScreen TopBar/BottomBar 有 contentDescription；书架卡片可无障碍访问 |
| L5-5 | `network_security_config.xml` 放行全部 RFC1918（10/8、172.16/12、192.168/16），其余强制 HTTPS |
| L5-6 | APK size check：base APK < 25MB（无 MuPDF .so） |
| L5-7 | 最近阅读排序：LibraryScreen 书架按 `lastReadAt` 降序 |

---

## 六、实现顺序与时间估算

```
✅ Phase 1 (基建)    已完成
✅ TXT 切片          已完成
─────────────────────────────────────
L1 书库接入          3–4天   → TXT 从书架可打开
L2 Reader 完善       3–4天   → TXT 进度/字号/主题
L3 EPUB 原生重排     5–7天   → 最高风险，单独 gate
L4 PDF 引擎          2–3天
L5 Phase A 验收门    2–3天
─────────────────────────────────────
合计                ~15–21天（单人全职）
```

> L1 → L2 必须顺序（L2 依赖书库导入有数据）；L3 / L4 可并行；L5 在 L1-L4 完成后。

---

## 七、关键风险与应对

| 风险 | 等级 | 应对 |
|------|------|------|
| EPUB 复杂样式/嵌套（L3） | 🔴 最高 | 严格降级矩阵；不求像素完美；真实EPUB集验证至少5本 |
| EPUB `AnnotatedString` 性能（大章节卡顿） | 🟠 高 | 每章节 lazy parse；LRU ±2章；`LazyColumn` keys 稳定 |
| PDF `PdfRenderer` 不支持复杂 PDF（加密/损坏） | 🟡 中 | 优雅降级（显示错误状态，不崩溃） |
| `ViewTreeLifecycleOwner` 挂载崩溃（F2，ComposeView in View） | 🟡 中 | `ReaderRootLayout` 统一挂载三件套（F2 约定） |
| SAF Uri 权限在重启后失效 | 🟡 中 | `import` 时 copy 到 `files/books/`（已实现，不依赖 SAF Uri 持久化） |

---

## 八、Phase A 验收检查清单

- [ ] 安装后**不需要**账号/Calibre/网络/选引擎即可正常使用
- [ ] SAF 导入 TXT/EPUB/PDF 后书架刷新显示书目
- [ ] `ACTION_VIEW` 从文件管理器打开 TXT/EPUB/PDF 可直接阅读
- [ ] 最近阅读列表正确（最近读的在前）
- [ ] 关闭再开书，进度恢复（TXT: ByteOffset，EPUB: spineIndex+charOffset，PDF: 页码）
- [ ] 字号调整 + 主题切换生效
- [ ] 进度/书架数据离线本地存储（无网络可用）
- [ ] APK < 25MB，冷启动 < 2s
- [ ] TalkBack smoke 通过（无崩溃，主要元素有 label）
