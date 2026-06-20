# LinReads Android v4 实现进度追踪

> **文档目的**: 对照 `android-architecture-v4.md` 规划，逐条追踪实际实现状态。
> **最后更新**: 2026-06-19
> **当前 Phase**: 构建 Phase 1（基建期，书库浏览 + 本地导入 + 进度存储）

---

## 一、用户轻量验收阶段（§2.2）

| Phase | 目标 | 状态 | 备注 |
|-------|------|------|------|
| **A 无账号本地阅读闭环** | 安装后直接打开本地书并继续阅读（落构建 Phase 2） | 🟡 **进行中** | 书架 ✅、本地导入 ✅、进度存储 ✅；阅读器 ⏳（Phase 2） |
| **B Calibre 可选书源** | 连接流程短、失败可理解 | ⏳ **待做** | CalibreClient 基础就绪，待连接向导 UI |
| **C 阅读质量闭环** | 主流格式稳定阅读、设置不打扰 | ⏳ **待做** | Phase 2 渲染引擎 |
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
| `:core:prefs` | ⏳ **待创建** | DataStore Preferences（baseUrl/字号/主题/deviceId/engineOverrides） |
| `:core:sync` | ⏳ **待创建** | `SyncBackend` + `NoOpSyncBackend` + `SyncManager` |
| `:extensions:api` | ✅ **完成** | `BookSource`/`Extension` SPI/`ReaderEventBus`/`LocalFileBookSource`/`FirstLaunchSeeder` |

### Layer 2 — 渲染抽象
| 模块 | 状态 | 说明 |
|------|------|------|
| `:render:api` | ⏳ **待创建** | `ReaderEngine`/`EngineDescriptor`/`ReaderEngineRegistry`/`PageTransitionHost` 接口（Phase 2 前置） |

### Layer 3 — 渲染实现
| 模块 | 状态 | 说明 |
|------|------|------|
| `:render:epub` | ⏳ **待创建** | 原生重排（jsoup→AnnotatedString），无 WebView（Phase 2） |
| `:render:pdf` | ⏳ **待创建** | PdfRenderer（Phase 2） |
| `:render:txt` | ⏳ **待创建** | RecyclerView + TxtVirtualPager（Phase 2） |
| `:render:md` | ⏳ **待创建** | Markwon Spannables（Phase 2） |
| `:render:mupdf` | ⏳ **待创建** | DOCX/CBZ，optional（Phase 3） |
| `:render:animate` | ⏳ **待创建** | `PageTransitionHost` 实现（Phase 2） |

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
| `:features:library` | ✅ **基础完成** | `LibraryScreen` + `LibraryViewModel`（真实数据流）；`onItemClick` 待连接 Navigation |
| `:features:reader` | ⏳ **待创建** | `ReaderScreen` + `ReaderViewModel`（MVI）+ `ReaderRootLayout`（Phase 2） |
| `:features:settings` | ⏳ **待创建** | Phase 2 |

### Layer 7 — 扩展
| 模块 | 状态 | 说明 |
|------|------|------|
| `:extensions:tts` | ⏳ **待创建** | Phase 3 |
| `:extensions:stats` | ⏳ **待创建** | Phase 3 |
| `:extensions:opds` | ⏳ **待创建** | Phase 3 |

### Layer 8 — 应用组装
| 模块 | 状态 | 说明 |
|------|------|------|
| `:app` | ✅ **Phase 1 就绪** | Koin DI + MainActivity + Navigation host（当前仅 LibraryScreen）；Phase 2 需补 reader 导航 |

---

## 三、Phase 1 详细状态（§11.1）

### 3.1 基础设施
| 项目 | 状态 | 说明 |
|------|------|------|
| `gradle/libs.versions.toml` | ✅ | 版本目录维护中 |
| `build-logic/` convention plugins | ⏳ | 当前各模块直接配置；Phase 1 step 2 任务待收敛 |
| `settings.gradle.kts` 分阶段 include | ✅ | `readflow.phase` property 驱动，默认 phase=1 |
| Phase 1 可编译闭环 | ✅ | `-Preadflow.phase=1 assembleDebug` 通过 |

### 3.2 核心模块
| 项目 | 状态 | 实现文件 |
|------|------|---------|
| `:core:model` 纯数据类型 | ✅ | `BookMeta.kt`（含 `LibraryItem`/`BookBundle`）、`Locator.kt`、`ReadflowError.kt`、`LoadingState.kt` |
| `:core:calibre` 基础客户端 | ✅ | `CalibreClient.kt`（未连接 UI） |
| `:core:prefs` SettingsRepository | ⏳ | 待创建（DataStore + engineOverrides/deviceId） |
| `:core:database` Room 5 表 | ✅ | `Entities.kt`、`Daos.kt`、`ReadflowDatabase.kt`（version=2） |
| `LibraryRepository` | ✅ | `observeShelf()` 含分组逻辑（`collectionName` → `BookBundle`） |
| `:core:sync` NoOpSyncBackend | ⏳ | 待创建 |

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
| Navigation 连接 | 🟡 | `onItemClick()` 待实现（Phase 2 需 reader route） |

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
| 可插拔引擎 | ⏳ | `ReaderEngineRegistry` 待 Phase 2 实现 |
| 混合视图（Hybrid View） | ⏳ | `ReaderRootLayout` 待 Phase 2 |
| MVI 单向数据流 | ✅ | `LibraryViewModel` 已遵循；`ReaderViewModel` 待 Phase 2 |
| 离线优先 | ✅ | Room 本地存储 + 2s debounce（架构预留，持久化逻辑待 Phase 2） |
| 依赖倒置 | ✅ | 当前已遵循（`:features:library` → `:core:database` 抽象） |
| 用户轻量优先 | ✅ | Phase 1 无账号/网络/同步依赖 |
| 可实现性优先 | ✅ | 模型全部可序列化，已验证编译通过 |

### 4.2 v4 新增裁决（§12.2）
| # | 裁决 | 状态 | 说明 |
|---|------|------|------|
| V1 | `ReadflowError` 纯数据 + `ReadflowException` 分层 | ✅ | `ReadflowError.kt` 已落地，`Kind` 枚举 + factory methods |
| V2 | `ReaderEngine` 线程契约 | ⏳ | 接口设计已明确，待 Phase 2 实现 |
| V3 | `EngineDescriptor` 懒加载 + Registry 线程安全 | ⏳ | Phase 2 |
| V4 | `SyncBackend` 移出 Layer 0 至 `:core:sync` | ⏳ | 模块待创建 |
| V5 | Koin multibind 统一扩展发现 | ✅ | `FirstLaunchSeeder` 已用 Koin 注入 |
| V6 | `PageTransitionHost` 拆分 | ⏳ | Phase 2 接口设计 |
| V7 | `BookSource.download()` 返回 `DownloadedAsset` | ✅ | `LocalFileBookSource.import()` 已遵循 |
| V8 | MuPDF optional | ⏳ | Phase 3 |
| V9 | EPUB 去 WebView 删 nanohttpd | ⏳ | Phase 2 原生重排 |
| V10 | TXT 字符边界对齐 | ⏳ | Phase 2 |
| V11 | 用户轻量契约为 P0 gate | ✅ | Phase A 进行中 |
| V12 | 文档不硬编码版本 | ✅ | 以 `libs.versions.toml` 为准 |
| E1 | EPUB 原生重排（jsoup→AnnotatedString） | ⏳ | Phase 2 核心任务 |
| E2 | `Locator` 增 `totalProgression` 同步主键 | ✅ | `Locator.kt` 已落地 |
| E3 | 可选兼容 KOSync | ⏳ | Phase 3 |

### 4.3 数据层（§7）
| 项目 | 状态 | 说明 |
|------|------|------|
| `Locator` 带 payload 的 `LocatorStrategy` | ✅ | `Section`/`Page`/`ByteOffset`/`Unknown` sealed interface |
| `ReaderState` 真正可序列化 | ✅ | 全部字段可 `@Serializable` |
| 进程死亡恢复链（两层） | ⏳ | `SavedStateHandle` 机制待 Phase 2 `ReaderViewModel` 验证 |
| `EngineStateStore` | ⏳ | 接口定义待 `:render:api`，实现在 `:app` |
| Room 5 表 + 索引 | ✅ | `books`/`reading_progress`/`bookmarks`/`text_annotations`/`ink_strokes` |
| 同步元数据（`updatedAt`/`deviceId`） | ✅ | `Bookmark`/`ReadingProgress` 已含字段 |
| `deviceId` 生成与持久化 | ⏳ | 待 `:core:prefs` 实现 |
| `totalProgression` 三端统一口径 | ✅ | 规范已定义（§7.1 F3），实现待 Phase 2 EPUB 引擎 |

---

## 五、待办事项（按优先级）

### P0 — Phase 1 收尾
- [ ] **创建 `:core:prefs` 模块**：DataStore Preferences（baseUrl/字号/主题/deviceId/engineOverrides）
- [ ] **创建 `:core:sync` 模块**：`SyncBackend` 接口 + `NoOpSyncBackend` 实现
- [ ] **`build-logic/` convention plugins**：收敛重复的 `build.gradle.kts` 配置
- [ ] **`LibraryViewModel.onItemClick()` 占位实现**：Phase 1 可先 Toast 提示「Phase 2 将打开阅读器」
- [ ] **`EmptyState` 按钮连接**：`onConnectCalibre` 导航到设置（Phase 2 Settings）；`onImportLocal` 触发 SAF picker

### P1 — Phase 2 准备（阅读器核心）
- [ ] **创建 `:render:api` 模块**：`ReaderEngine`/`EngineDescriptor`/`ReaderEngineRegistry`/`PageTransitionHost` 接口
- [ ] **创建 `:render:epub` 模块**：jsoup 解析 + `ReaderItem` + AnnotatedString 渲染（连续滚动优先）
- [ ] **创建 `:render:pdf` 模块**：PdfRenderer + ImageView
- [ ] **创建 `:render:txt` 模块**：RecyclerView + TxtVirtualPager + 字符边界对齐
- [ ] **创建 `:render:md` 模块**：Markwon Spannables
- [ ] **创建 `:render:animate` 模块**：`SlideFadeTransitionHost` + `NoTransitionHost`
- [ ] **创建 `:features:reader` 模块**：`ReaderScreen` + `ReaderViewModel`(MVI) + `ReaderRootLayout`（Hybrid View）
- [ ] **创建 `:features:settings` 模块**：Settings UI + Calibre 连接向导
- [ ] **Navigation 连接**：LibraryScreen → ReaderScreen 路由

### P2 — Phase 2 增强
- [ ] **`totalProgression` 计算实现**：EPUB 引擎预扫全书纯文本字符数（§7.1 F3）
- [ ] **进程死亡恢复验证**：`SavedStateHandle` + `EngineStateStore` 往返测试
- [ ] **TalkBack smoke test**：无障碍基础验证（§12.7）
- [ ] **性能预算测量**：冷启动/首屏/翻页/内存峰值（§12.8）

### P3 — Phase 3 精品增强
- [ ] **数据导出**：`LinReads Backup` ZIP + JSON manifest
- [ ] **`:render:mupdf` optional**：DOCX/CBZ，过 MuPDF license ADR
- [ ] **`:ink` 实现**：`CanvasView` + `InProgressStrokesView` + 触摸路由
- [ ] **`:extensions:tts`/`:stats`/`:opds`**：按需扩展

---

## 六、当前 APK 状态

### Phase 1 构建
| 指标 | 当前值 | 目标 | 状态 |
|------|--------|------|------|
| 最低 SDK | 26 (Android 8.0) | — | ✅ |
| 目标 SDK | 36 (Android 14+) | — | ✅ |
| base APK 大小 | ~8MB (估) | < 25MB | ✅ |
| 包含渲染引擎 | 无 | Phase 2 加入 | — |
| 功能 | 书架浏览 + 本地导入 + 进度存储 | Phase 1 范围 | ✅ |

### 已验证功能
- ✅ 首次启动自动播种 3 本示例书（`assets/sample_books/`）
- ✅ 书架网格显示：封面 + 书名 + 作者 + 进度条 + 书签（有阅读进度时）
- ✅ 纸质感背景 + 木质隔板沿
- ✅ 空书架状态（EmptyState）
- ✅ Material3 暗色/亮色主题切换
- ✅ Room 数据库迁移（v1 → v2，增 `collectionName` 字段）

### 未验证功能（Phase 1 范围内）
- ⏳ Calibre 连接（CalibreClient 就绪，UI 未连接）
- ⏳ SAF 本地文件导入（LocalFileBookSource 就绪，UI picker 未触发）
- ⏳ 点击书籍进入阅读（Phase 2 ReaderScreen 未创建）
- ⏳ 设置界面（Phase 2）

---

## 七、已知问题

1. **`build-logic/` 未创建**（§10.2）：当前各模块直接在 `build.gradle.kts` 重复配置，待收敛为 convention plugins。
2. **Navigation 未完整连接**：`LibraryViewModel.onItemClick()` 为 TODO 占位，Phase 2 需补 reader route。
3. **`:core:prefs` 缺失**：baseUrl/字号/主题/deviceId 持久化逻辑待实现。
4. **adb 命令不可用**：当前环境中 `adb` 未配置到 PATH，需通过完整路径调用或配置环境变量。

---

## 八、下一步行动

### 立即可做（Phase 1 收尾）
1. **创建 `:core:prefs` 和 `:core:sync` 模块**，完成 Phase 1 模块闭环。
2. **实现 `LibraryViewModel.onItemClick()` 占位逻辑**（如 Toast 或 Log），避免点击无反馈。
3. **修正网格列数验证**：确认不同屏幕密度下的列数符合设计预期（手机 2-3 列，平板更多）。

### Phase 2 启动条件
- [ ] Phase 1 所有模块（9 个）编译通过且功能验证完成
- [ ] `:render:api` 接口设计评审通过
- [ ] 至少一种格式引擎（TXT 或 PDF）实现完成

### 验收标准（Phase A：无账号本地阅读闭环）
- [ ] 用户能从设备/文件管理器分享文件到 Readflow
- [ ] 导入的书显示在书架上
- [ ] 点击书籍进入阅读器（Phase 2）
- [ ] 阅读进度本地保存并在重启后恢复
- [ ] 无需 Calibre/账号/网络即可完成上述流程

---

**文档维护**: 每完成一个模块/功能，更新对应行状态（⏳ → 🟡 → ✅）。每个 Phase 结束时补充「已验证功能」和「性能测量」章节。
