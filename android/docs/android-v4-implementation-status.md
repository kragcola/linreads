# LinReads Android v4 实现进度追踪

> **文档目的**: 对照 `android-architecture-v4.md` 规划，逐条追踪实际实现状态。
> **最后更新**: 2026-06-21
> **当前 Phase**: v4lite L1–L5 全部完成，Phase A 功能就绪，当前在体验打磨 + 性能测量阶段

---

## 一、用户轻量验收阶段（§2.2）

| Phase | 目标 | 状态 | 备注 |
|-------|------|------|------|
| **A 无账号本地阅读闭环** | 安装后直接打开本地书并继续阅读 | ✅ **功能就绪** | 书架+导入+阅读+进度+设置全链路通；性能/TalkBack 测量待补 |
| **B Calibre 可选书源** | 连接流程短、失败可理解 | ⏳ **待做** | CalibreClient 基础就绪，设置可配 URL；待连接向导+搜索+下载 UI |
| **C 阅读质量闭环** | 主流格式稳定阅读、设置不打扰 | 🟡 **进行中** | 四引擎已就绪；性能/TalkBack 测量待补 |
| **D 数据出口与离线缓存** | 用户能控制自己的书和数据 | ⏳ **待做** | 导出/恢复机制待实现 |
| **E 精品增强** | Ink/TTS/OPDS/Sync/DOCX/CBZ 逐个进入不污染基础体验 | ⏳ **待做** | Phase 3+ |

---

## 二、模块地图（§3.1，22 个模块）

### Layer 0 — 纯 Kotlin
| 模块 | 状态 | 说明 |
|------|------|------|
| `:core:model` | ✅ **完成** | `BookMeta`、`Locator`、`ReaderState`、`ReadflowError`、`InkAnchor`、`LoadingState`、`DownloadedAsset`、`Bookmark`、`ReadingProgress` 全部就位且可序列化 |

### Layer 1 — Android 数据
| 模块 | 状态 | 说明 |
|------|------|------|
| `:core:calibre` | ✅ **完成** | `CalibreClient` 基础 HTTP 客户端，未连接 UI |
| `:core:database` | ✅ **完成** | Room 5 表（books/reading_progress/bookmarks/text_annotations/ink_strokes）+ `LibraryRepository`，含 `observeShelf()` 分组逻辑 |
| `:core:prefs` | ✅ **完成** | `SettingsRepository`（DataStore），字号/主题/Calibre URL 持久化，被 ReaderViewModel/SettingsViewModel import |
| `:core:sync` | ✅ **完成** | `SyncBackend` 接口 + `NoOpSyncBackend` + `SyncManager`（LWW 骨架，实际 no-op，被 ReaderViewModel import）|
| `:extensions:api` | ✅ **完成** | `BookSource`/`Extension` SPI/`ReaderEventBus`/`LocalFileBookSource`/`FirstLaunchSeeder` |

### Layer 2 — 渲染抽象
| 模块 | 状态 | 说明 |
|------|------|------|
| `:render:api` | ✅ **完成** | `ReaderEngine`(49行)/`EngineDescriptor`/`ReaderEngineRegistry`(46行)/`PageTransitionHost`(26行) 接口全部就位 |

### Layer 3 — 渲染实现
| 模块 | 状态 | 说明 |
|------|------|------|
| `:render:epub` | ✅ **完成** | EpubReflowEngine(107行)+EpubParser+EpubParaAdapter；ZipFile+jsoup→RecyclerView 连续滚动（v4lite 基础，分页待 gate）|
| `:render:pdf` | ✅ **完成** | PdfRendererEngine(107行)+PdfPageAdapter；逐页 ImageView+ViewPager2 分页 |
| `:render:txt` | ✅ **完成** | TxtVirtualPagerEngine(128行)+TxtParagraphAdapter；RecyclerView 虚拟分页 |
| `:render:md` | ✅ **完成** | MarkdownEngine(106行)；Markwon Spannables→RecyclerView |
| `:render:mupdf` | ⏳ **待创建** | DOCX/CBZ，optional（Phase 3）；仅空壳 build.gradle.kts |
| `:render:animate` | ✅ **完成** | NoTransitionHost + DefaultPageTransitionHostFactory |

### Layer 4 — Ink
| 模块 | 状态 | 说明 |
|------|------|------|
| `:ink` | ⏳ **待创建** | Phase 1 仅架构预留，实现在 Phase 3 |

### Layer 5 — UI 基础
| 模块 | 状态 | 说明 |
|------|------|------|
| `:core:ui` | ✅ **完成** | Material3 主题、`PaperSurface`、`BookCover`、`BundleStack`、`ShelfBoard`、`BookGrid`、`EmptyState`、`Navigation`、字体/间距 tokens |

### Layer 6 — 功能模块
| 模块 | 状态 | 说明 |
|------|------|------|
| `:features:library` | ✅ **完成** | `LibraryScreen` + `LibraryViewModel`（真实数据流、SAF picker、Dwell 建组、拖拽排序、文件夹导入、Bundle 详情页）|
| `:features:reader` | ✅ **完成** | `ReaderScreen`(113行) + `ReaderViewModel`(165行 MVI) + `ReaderRootLayout`；进度 2s debounce→Room、字号/主题/chrome toggle |
| `:features:settings` | ✅ **完成** | `SettingsScreen`(262行) + `SettingsViewModel`；Calibre URL/字号滑块/主题切换/OTA 手动检查更新+下载进度+自动安装 |

### Layer 7 — 扩展
| 模块 | 状态 | 说明 |
|------|------|------|
| `:extensions:tts` | ⏳ **待创建** | Phase 3 |
| `:extensions:stats` | ⏳ **待创建** | Phase 3 |
| `:extensions:opds` | ⏳ **待创建** | Phase 3 |

### Layer 8 — 应用组装
| 模块 | 状态 | 说明 |
|------|------|------|
| `:app` | ✅ **v4lite 就绪** | Koin DI + MainActivity + Navigation host（Library→Reader→Settings 完整路由）+ Phase 1/2 双 sourceSet + OTA 更新集成 |

---

## 三、Phase 1 详细状态（§11.1）

### 3.1 基础设施
| 项目 | 状态 | 说明 |
|------|------|------|
| `gradle/libs.versions.toml` | ✅ | 版本目录维护中 |
| `build-logic/` convention plugins | ✅ | 4 个 convention plugin：ReadflowJvmLibrary/ReadflowAndroidLibrary/ReadflowCompose/ReadflowFeature |
| `settings.gradle.kts` 分阶段 include | ✅ | `readflow.phase` property 驱动，默认 phase=1 |
| Phase 1 可编译闭环 | ✅ | `-Preadflow.phase=1 assembleDebug` 通过 |

### 3.2 核心模块
| 项目 | 状态 | 实现文件 |
|------|------|---------|
| `:core:model` 纯数据类型 | ✅ | `BookMeta.kt`（含 `LibraryItem`/`BookBundle`）、`Locator.kt`、`ReadflowError.kt`、`LoadingState.kt` |
| `:core:calibre` 基础客户端 | ✅ | `CalibreClient.kt`（未连接 UI） |
| `:core:prefs` SettingsRepository | ✅ | DataStore 持久化字号/主题/Calibre URL；被 ReaderViewModel/SettingsViewModel 使用 |
| `:core:database` Room 5 表 | ✅ | `Entities.kt`、`Daos.kt`、`ReadflowDatabase.kt`（version=2） |
| `LibraryRepository` | ✅ | `observeShelf()` 含分组逻辑（`collectionName` → `BookBundle`） |
| `:core:sync` NoOpSyncBackend | ✅ | SyncBackend 接口 + NoOpSyncBackend + SyncManager 就位；ReaderViewModel 已 import |

### 3.3 UI 层
| 项目 | 状态 | 实现文件 |
|------|------|---------|
| Material3 主题 + 色板 | ✅ | `:core:ui/ReadflowColors.kt`、`Type.kt`、`Dimens.kt` |
| `PaperSurface` 纸质感背景 | ✅ | 程序化噪点 + 暖色渐变 |
| `BookCover` | ✅ | 圆角封面 + 素封面 fallback + 进度条 + 磨损边缘 |
| `BundleStack` | ✅ | 最多 4 层偏移堆叠 |
| `ShelfBoard` | ✅ | 4px 窄隔板沿 + 书底柔影 |
| `EmptyState` | ✅ | 空书架提示 + Connect Calibre / Import Local 按钮 |
| `BookGrid` 自适应网格 | ✅ | `GridCells.Adaptive(116.dp)`，手机 2-3 列 |
| `Navigation` 组件 | ✅ | `ReadflowBottomNav` / `ReadflowNavRail` |

### 3.4 功能模块
| 项目 | 状态 | 说明 |
|------|------|------|
| `LibraryScreen` | ✅ | `PaperSurface` + loading/error/empty/grid 条件渲染 |
| `LibraryViewModel` | ✅ | 观察 `LibraryRepository.observeShelf()`，真实数据流 |
| Navigation 连接 | ✅ | LibraryScreen → ReaderScreen → SettingsScreen 完整路由；onItemClick 已连接 |

### 3.5 本地导入与播种
| 项目 | 状态 | 实现文件 |
|------|------|---------|
| `LocalFileBookSource` | ✅ | SAF `import(uri)` 复制到 `files/books/`，派生元数据 |
| `FirstLaunchSeeder` | ✅ | `seedIfEmpty()` 从 `assets/sample_books/` 导入 |
| 示例书资源 | ✅ | `app/src/main/assets/sample_books/`（活着.txt、平凡的世界.txt、围城.txt） |
| 首次启动播种验证 | ✅ | Logcat：`首次启动播种了 3 本示例书` |

---

## 四、关键架构决策落地状态（§12）

### 4.1 设计原则（§1）
| 原则 | 状态 | 说明 |
|------|------|------|
| 可插拔引擎 | ✅ | `ReaderEngineRegistry.resolve(uri)` 按扩展名自动选引擎，Koin multibind 注册 |
| 混合视图（Hybrid View） | ✅ | `ReaderScreen` AndroidView 挂载引擎 View + Compose chrome overlay |
| MVI 单向数据流 | ✅ | `LibraryViewModel`(StateFlow) + `ReaderViewModel`(MVI Intent→State) 全部遵循 |
| 离线优先 | ✅ | Room 本地存储 + 进度 2s debounce→ReadingProgressEntity；SyncManager LWW 骨架 |
| 依赖倒置 | ✅ | 当前已遵循（`:features:library` → `:core:database` 抽象） |
| 用户轻量优先 | ✅ | Phase 1 无账号/网络/同步依赖 |
| 可实现性优先 | ✅ | 模型全部可序列化，已验证编译通过 |

### 4.2 v4 新增裁决（§12.2）
| # | 裁决 | 状态 | 说明 |
|---|------|------|------|
| V1 | `ReadflowError` 纯数据 + `ReadflowException` 分层 | ✅ | `ReadflowError.kt` 已落地，`Kind` 枚举 + factory methods |
| V2 | `ReaderEngine` 线程契约 | ✅ | `withContext(Dispatchers.IO)` 包裹 IO，Main 线程回调 |
| V3 | `EngineDescriptor` 懒加载 + Registry 线程安全 | ✅ | Koin multibind 懒加载，resolve() 按扩展名匹配 |
| V4 | `SyncBackend` 移出 Layer 0 至 `:core:sync` | ✅ | `:core:sync` 模块就位，SyncManager+NoOpSyncBackend |
| V5 | Koin multibind 统一扩展发现 | ✅ | `FirstLaunchSeeder` 已用 Koin 注入 |
| V6 | `PageTransitionHost` 拆分 | ✅ | `PageTransitionHost` 接口 + `NoTransitionHost` + `DefaultPageTransitionHostFactory` + paged/continuous 工厂方法 |
| V7 | `BookSource.download()` 返回 `DownloadedAsset` | ✅ | `LocalFileBookSource.import()` 已遵循 |
| V8 | MuPDF optional | ⏳ | Phase 3 |
| V9 | EPUB 去 WebView 删 nanohttpd | ✅ | EpubReflowEngine 纯原生 RecyclerView，无 WebView/JS/CFI/nanohttpd |
| V10 | TXT 字符边界对齐 | ✅ | TxtVirtualPagerEngine Paging3 + ByteOffset locator |
| V11 | 用户轻量契约为 P0 gate | ✅ | Phase A 进行中 |
| V12 | 文档不硬编码版本 | ✅ | 以 `libs.versions.toml` 为准 |
| E1 | EPUB 原生重排（jsoup→AnnotatedString） | ✅ | EpubReflowEngine + EpubParser（jsoup）+ EpubParaAdapter；连续滚动基础就位 |
| E2 | `Locator` 增 `totalProgression` 同步主键 | ✅ | `Locator.kt` 已落地 |
| E3 | 可选兼容 KOSync | ⏳ | Phase 3 |

### 4.3 数据层（§7）
| 项目 | 状态 | 说明 |
|------|------|------|
| `Locator` 带 payload 的 `LocatorStrategy` | ✅ | `Section`/`Page`/`ByteOffset`/`Unknown` sealed interface |
| `ReaderState` 真正可序列化 | ✅ | 全部字段可 `@Serializable` |
| 进程死亡恢复链（两层） | 🟡 | `ReaderViewModel` close() 强制保存进度；SavedStateHandle + EngineStateStore 正式两层机制待验证 |
| `EngineStateStore` | 🟡 | 加速缓存仓库接口待定义（`:render:api`），当前 close() 直接写 Room 作为兜底 |
| Room 5 表 + 索引 | ✅ | `books`/`reading_progress`/`bookmarks`/`text_annotations`/`ink_strokes` |
| 同步元数据（`updatedAt`/`deviceId`） | ✅ | `Bookmark`/`ReadingProgress` 已含字段 |
| `deviceId` 生成与持久化 | 🟡 | 当前硬编码 "local"；正式 UUID 生成+持久化待 `:core:prefs` 补全 |
| `totalProgression` 三端统一口径 | 🟡 | 规范已定义（§7.1 F3）；EpubReflowEngine 用 para count 近似，全书预扫待实现 |

---

## 五、待办事项（按优先级）

### P0 — Phase A 收尾（性能+无障碍测量）
- [x] **创建 `:core:prefs` 模块**：DataStore Preferences（字号/主题/Calibre URL）
- [x] **创建 `:core:sync` 模块**：`SyncBackend` 接口 + `NoOpSyncBackend` + SyncManager
- [x] **`build-logic/` convention plugins**：4 个 convention plugin 已落地
- [x] **`LibraryViewModel.onItemClick()`**：已连接 ReaderScreen 导航
- [x] **`EmptyState` 按钮连接**：Connect Calibre → Settings；Import Local → SAF picker
- [ ] **性能测量**：冷启动 < 2s / 1MB TXT首开 < 500ms / EPUB首章 < 1s / 翻页 < 50ms / 内存 < 200MB PSS
- [ ] **TalkBack smoke test**：ReaderScreen chrome 元素 contentDescription、书架卡片无障碍访问

### P1 — 阅读器核心（✅ 已完成）
- [x] **创建 `:render:api` 模块**：`ReaderEngine`/`EngineDescriptor`/`ReaderEngineRegistry`/`PageTransitionHost` 接口
- [x] **创建 `:render:epub` 模块**：jsoup 解析 + EpubPara + RecyclerView 连续滚动
- [x] **创建 `:render:pdf` 模块**：PdfRenderer + ImageView + ViewPager2 分页
- [x] **创建 `:render:txt` 模块**：RecyclerView + TxtVirtualPager + 字符边界对齐
- [x] **创建 `:render:md` 模块**：Markwon Spannables
- [x] **创建 `:render:animate` 模块**：NoTransitionHost + DefaultPageTransitionHostFactory
- [x] **创建 `:features:reader` 模块**：ReaderScreen + ReaderViewModel(MVI) + ReaderRootLayout（Hybrid View）
- [x] **创建 `:features:settings` 模块**：Settings UI + Calibre URL + 字号/主题 + OTA 更新
- [x] **Navigation 连接**：LibraryScreen → ReaderScreen 路由

### P2 — v4lite 待补（Phase A 收尾）
- [ ] **`totalProgression` 计算实现**：EPUB 引擎预扫全书纯文本字符数（§7.1 F3，当前用 para count 近似）
- [ ] **EPUB 分页模式**：ViewPager2 per-page ComposeView（v4 最高技术风险，独立 gate）
- [ ] **进程死亡恢复验证**：`SavedStateHandle` + `EngineStateStore` 往返测试（当前 close() 强制写 Room 兜底）
- [ ] **TalkBack smoke test**：无障碍基础验证（§12.7）
- [ ] **性能预算测量**：冷启动/首屏/翻页/内存峰值（§12.8）

### P3 — Phase 3 精品增强
- [ ] **数据导出**：`LinReads Backup` ZIP + JSON manifest
- [ ] **`:render:mupdf` optional**：DOCX/CBZ，过 MuPDF license ADR
- [ ] **`:ink` 实现**：`CanvasView` + `InProgressStrokesView` + 触摸路由
- [ ] **`:extensions:tts`/`:stats`/`:opds`**：按需扩展

---

## 六、当前 APK 状态

### v4lite 构建
| 指标 | 当前值 | 目标 | 状态 |
|------|--------|------|------|
| 最低 SDK | 26 (Android 8.0) | — | ✅ |
| 目标 SDK | 36 (Android 14+) | — | ✅ |
| base APK 大小 | ~10MB (估) | < 25MB | ✅ |
| 包含渲染引擎 | TXT+EPUB+PDF+MD 四引擎 | v4lite 范围 | ✅ |
| 功能 | 书架+导入+阅读+设置+OTA | Phase A 范围 | ✅ |

### 已验证功能
- ✅ 首次启动自动播种示例书（assets/sample_books/）
- ✅ 书架网格显示：封面 + 书名 + 作者 + 进度条 + 书签
- ✅ 纸质感背景 + 木质隔板沿
- ✅ 空书架状态 + 导入按钮
- ✅ SAF 本地文件导入（单文件 + 文件夹批量）
- ✅ TXT/EPUB/PDF/MD 四格式阅读
- ✅ 字号调整（12-28sp slider）
- ✅ 主题切换（白/暗/护眼/系统）
- ✅ 阅读进度持久化（Room，2s debounce）
- ✅ 关闭重开进度恢复
- ✅ Settings 完整 UI（Calibre URL / 字号 / 主题 / OTA 检查更新）
- ✅ OTA 更新系统（检查→DownloadManager→进度条→自动安装）
- ✅ 真机 OTA 安装验证通过
- ✅ Room 数据库迁移（v1→v2）

### 未验证/待补
- ⏳ Calibre 连接端到端（CalibreClient 就绪，设置 URL 可持久化，但未在真机上连真实 Calibre 服务器验证）
- ⏳ 性能测量（冷启动/首屏/翻页/内存）
- ⏳ TalkBack 无障碍 smoke test
- ⏳ EPUB 分页模式（当前仅连续滚动）

---

## 七、已知问题

1. **EPUB 仅连续滚动**：分页模式（ViewPager2 per-page ComposeView）为 v4 最高风险项，独立 gate，不绑定基础阅读链路。
2. **totalProgression 近似**：EPUB 引擎用 para count 近似全书进度，精确预扫待实现（v4 §7.1）。
3. **deviceId 硬编码**：当前为 "local"，正式 UUID 生成待 `:core:prefs` 补全。
4. **adb 命令不可用**：当前环境中 `adb` 未配置到 PATH，需通过完整路径调用或配置环境变量。
5. **mupdf/ink 空壳**：`:render:mupdf` 和 `:ink` 仅 build.gradle.kts，Phase 3 实装。

---

## 八、下一步行动

### 立即可做（Phase A 收尾）
1. **性能测量**：冷启动/首屏/翻页/内存峰值正式测量并记录。
2. **TalkBack smoke test**：ReaderScreen chrome 元素无障碍标签验证。
3. **Calibre 端到端验证**：真机上连真实 Calibre 服务器测试搜索和下载。

### 下一阶段启动条件（Phase B+）
- [ ] Phase A 性能 + TalkBack 测量完成
- [ ] EPUB 分页模式 gate 决策（做/不做/推迟）
- [ ] Calibre 书源接入（连接向导→搜索→下载→阅读）

### 验收标准（Phase A：无账号本地阅读闭环）
- [x] 用户能从设备/文件管理器分享文件到 Readflow
- [x] 导入的书显示在书架上
- [x] 点击书籍进入阅读器
- [x] 阅读进度本地保存并在重启后恢复
- [x] 无需 Calibre/账号/网络即可完成上述流程
- [ ] 性能测量和 TalkBack 验证

---

**文档维护**: 每完成一个模块/功能，更新对应行状态（⏳ → 🟡 → ✅）。每个 Phase 结束时补充「已验证功能」和「性能测量」章节。
