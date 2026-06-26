# LinReads Android 架构文档 v4

> **状态**: 权威规范 — 取代 v3（`android-architecture-v3.md`），所有更早的 Android 架构文档（v1/v2）自此废弃。
> **最后更新**: 2026-06-18
> **适用版本**: LinReads Android v4.0.0+
> **模块总数**: 22 | **分层**: 8 | **渲染引擎**: 7
> **定位**: 少人精品 · 用户侧轻量 · 开发侧可接受中重度

## v4 相对 v3 的变更（审计修复摘要）

本版基于两轮 v3 框架审计（`docs/audit/android-v3-framework-audit-2026-06-18.md` 与 `…-round2-…md`）逐项修复。关键变更：

| 编号 | 来源 | 修复 |
|------|------|------|
| P0-A | R2 | `ReadflowError` 改为可序列化纯数据（`Kind` 枚举），`Throwable` 移入运行时 `ReadflowException`；`ReaderState` 真正可 `@Serializable` |
| P0-B | R2 | EPUB 改原生重排（去 WebView）；见 ADR-EPUB-Engine（外部对标修订） |
| P0-C | R2 | §4.1 新增 ReaderEngine **线程契约**（调用方在 Main，引擎内部切 IO） |
| P1-D | R2 | `ReaderEngineRegistry` 线程安全：override 由 `SettingsRepository` 提供只读快照 |
| P1-E | R2 | Registry 收集 `EngineDescriptor`（懒加载），重引擎不在冷启动期实例化 |
| P1-F | R2/R1 | `SyncBackend` 移出 Layer 0 → 新模块 `:core:sync`；进度/书签/标注补同步元数据 |
| P1-G | R2 | 扩展发现统一 Koin multibind，删除 ServiceLoader |
| P2-H | R2 | 文档不再硬编码版本号 / 模块清单，改引用 `libs.versions.toml` + 分阶段 include |
| P2-I | R2 | EPUB 去 WebView 后无需本地 HTTP server，nanohttpd/CSP/path token 安全收敛整体移除 |
| P2-J | R2 | TXT 分页对齐字符边界，`Locator.ByteOffset` 保证落在字符起始字节 |
| R1-1 | R1 | 新增「用户轻量架构契约」为 Phase A 验收 gate |
| R1-2 | R1 | `BookSource.download()` 返回平台中立 `DownloadedAsset`，非 `java.io.File` |
| R1-3 | R1 | `ReaderEngine.createView()` 与 ViewPager2 拆为 `PageTransitionHost` |
| R1-4 | R1 | 新增权限矩阵、MuPDF optional gate、无障碍 smoke list、性能测量 gate |
| E1 | 外部对标 | EPUB 去 WebView 改原生重排（jsoup→AnnotatedString），删 epub-ts/nanohttpd/CSP/JS bridge/CFI |
| E2 | 外部对标 | `Locator` 增 `progression`/`totalProgression` 作三端同步主键（对标 Readium/KOReader） |
| E3 | 外部对标 | 进度同步可选兼容 KOSync（新增 `KoSyncBackend`，接口已支持） |
| P1 | 复审 | `PageTransitionHost` 补 `next()/previous()`、`offscreenPageLimit`、`onPageSettled`、挂载协议；`ReaderEngine.pagingKind` 供 `ReaderRootLayout` 判定分页/连续 |
| P2 | 复审 | 进程死亡恢复链落定：`SavedStateHandle`(ReaderState 语义位置) + `Engine.saveState()`(可丢加速缓存) 两层职责 |
| P3 | 复审 | 正式落 `ReaderIntent` 契约，补 Search/TOC/文字选择/高亮 |
| P5 | 复审 | `ExtensionContext` 增 UI 态字段防御性使用约定 |
| P6 | 复审 | `settings.gradle.kts` 分阶段 include 改 `readflow.phase` property 强制，弃注释 |
| F1 | 全维度审计 | EPUB 分页归属统一为 ViewPager2 host（删 ComposeView 内 HorizontalPager 双重分页矛盾） |
| F2 | 全维度审计 | `:render:api` 补 Compose interop 契约（ComposeView 的 ViewTree*Owner 挂载责任） |
| F3 | 全维度审计 | §7.1 钉死 `totalProgression` 三端统一计算口径（规范化纯文本字符偏移），E2 落地必要补全 |
| F4 | 全维度审计 | `ReaderState`/`Locator` 等持久化态明确 `@Parcelize`，修正 SavedStateHandle 存取机制（D-2） |
| F5 | 全维度审计 | Engine 加速缓存改写本地文件，SavedStateHandle 只存轻量 key（避 TransactionTooLarge） |
| F6 | 全维度审计 | §7.7 补 `deviceId` 来源 + LWW 时钟漂移 tie-break 规则 |
| F7 | 全维度审计 | `InkAnchor.Text` 弃 `cssSelector`，改 spine+element+charOffset（E1 删 CFI 漏清遗留） |
| F8 | 全维度审计 | §5.1 `ReaderEngine` 补 `search()` + `supportsSearch`（对齐 §4.5 Search Intent） |
| F9 | 全维度审计 | §10.3 修 phase1 include 边界（`:core:ui`/`:core:database` 提至 phase1，否则编译炸） |
| F10 | 全维度审计 | §3.2/§9.3 补 `PageTransitionHostFactory`/`SyncManager` 模块归属 |
| F11 | 全维度审计 | §12.6 补 EPUB 解析安全：XML 嵌套深度上限、图片解码上限、默认不加载内嵌远程资源 |
| F12 | 全维度审计 | §12.6 network_security_config 放行全部 RFC1918 私有段（10/8、172.16/12、192.168/16） |
| F13 | 全维度审计 | §12.7 无障碍补实现机制（LinkAnnotation + 段落级 semantics + 朗读顺序） |
| F14 | 全维度审计 | §12.8 EPUB 内存预算改惰性解析 LRU + 明确 PSS 测量口径 |

> **判据声明（2026-06-18，用户裁决）**：本项目北极星为**用户侧轻量**，开发侧复杂度（多模块/自研引擎/重构）均可接受。故全维度审计中「替开发省工」类建议（模块合并、扩展系统 YAGNI、测试矩阵削减、KMP 取舍）**不予采纳**；只采纳「替用户兜底」类——数据不丢、进程不崩、读着顺、不偷偷联网、无障碍可用。F1–F14 即此筛选结果，详见 `docs/audit/v4-full-dimension-audit-2026-06-18.md`。

---

## 目录

1. [设计原则](#一设计原则)
2. [用户轻量架构契约](#二用户轻量架构契约)
3. [模块地图](#三模块地图)
4. [视图架构](#四视图架构)
5. [渲染引擎契约](#五渲染引擎契约)
6. [Ink / 手写笔集成](#六ink--手写笔集成)
7. [数据层](#七数据层)
8. [扩展系统](#八扩展系统)
9. [依赖注入](#九依赖注入)
10. [Gradle 基础设施](#十gradle-基础设施)
11. [迁移路径](#十一迁移路径)
12. [架构裁决记录](#十二架构裁决记录)

---

## 一、设计原则

| # | 原则 | 说明 |
|---|------|------|
| 1 | **可插拔引擎（Pluggable Engines）** | 渲染引擎通过 `ReaderEngine` 接口自注册，权重发现，新增格式无需改动已有代码。对标 KOReader 的 per-type 用户 override。 |
| 2 | **混合视图（Hybrid View）** | 文档渲染使用原生 `android.view.View` / Compose 文本基元（`AnnotatedString`、ImageView、RecyclerView），不引入 WebView 作为文档渲染基元。Compose 负责 UI Chrome overlay 与原生重排文本。 |
| 3 | **MVI 单向数据流** | `ReaderIntent → ReaderViewModel.reduce → ReaderState`。View 层只消费 `StateFlow<ReaderState>`。 |
| 4 | **离线优先（Offline-First）** | 进度/书签/标注一律先写 Room，UI 立即更新；后台异步同步。翻页进度 2 秒 debounce 后持久化。 |
| 5 | **依赖倒置** | 功能模块依赖抽象接口（`render:api`、`extensions:api`、`core:sync`），不依赖具体实现。依赖方向恒为 Layer N → Layer N-1。 |
| 6 | **用户轻量优先（v4 新增）** | 内部工程可中重度复杂，但复杂度必须被框架吸收，不得以用户可见复杂度（首开配置、引擎名、权限、包体积）泄露。见第二节契约。 |
| 7 | **可实现性优先（v4 新增）** | 本文档的每段代码必须能照抄编译通过；持久化模型必须真正可序列化；接口必须带线程契约。文档与 `libs.versions.toml` 冲突时以 catalog 为准。 |

---

## 二、用户轻量架构契约

> 实现前 P0 gate（来自 Round 1 审计）。复杂能力可以做，但必须满足以下契约，否则不得默认启用。

### 2.1 五条轻量基线

1. **安装轻** — base APK 只含首发必须能力。MuPDF（DOCX/CBZ）、Ink、TTS、OPDS、Sync 为可选/按需，不进 base 包成本。每个引擎登记 APK 增量、ABI 影响、冷启动影响。
2. **首开轻** — 第一次启动不要求账号、不要求连接 Calibre、不要求选引擎。主路径是「打开本地书」与「继续阅读」。Calibre 是增强书源，有向导、自动探测、失败解释。
3. **阅读轻** — 普通用户只看到字号、主题、行距、翻页方式、目录、书签。引擎选择、同步后端、调试信息进入高级设置。格式差异由系统吸收，手势语义一致。
4. **数据轻** — 进度/书签/标注离线本地优先；一键导出开放备份包（`LinReads Backup`：ZIP + JSON manifest）。同步是可选增强，停服/断网/换设备不导致数据不可取回。
5. **权限轻** — 默认不请求危险权限；文件走 SAF 或用户主动分享；网络/同步/通知/TTS 按功能触发；凭据可查看、可删除、可重新授权。

### 2.2 验收顺序（按用户获得轻量体验排序）

> **验收 Phase（A–E）≠ Gradle 构建 Phase（1–3）**（最终审计决断 1）。下表是「用户获得轻量体验」的北极星排序；它**不等于** §10.3 的构建分期。具体映射：构建 **Phase 1 = 纯基建期**（书库浏览 + 本地导入 + 最近阅读 + 进度本地存储，**尚不能渲染正文**）；用户验收 **Phase A「本地阅读闭环」落在构建 Phase 2**（reader + 渲染引擎就位时）。即「能打开本地书并继续阅读」是构建 Phase 2 的结束态，不是构建 Phase 1 的。如此 Phase 1 自成可编译、可验收的基建闭环，不假装能阅读。

| Phase | 目标 | 验收 |
|-------|------|------|
| **A 无账号本地阅读闭环** | 安装后直接打开本地书并继续阅读（落构建 Phase 2） | `ACTION_VIEW`/`ACTION_SEND`/SAF 导入至少一条可用；最近阅读可见；进度本地保存；不要求 Calibre/同步/账号/网络 |
| **B Calibre 可选书源** | 连接流程短、失败可理解 | baseUrl 来自设置/向导不硬编码；可搜索真实书籍；书名/作者/格式/封面可见；失败有原因和下一步 |
| **C 阅读质量闭环** | 主流格式稳定阅读、设置不打扰 | 系统自动选引擎；字号/主题/行距/翻页可用；首屏/翻页/内存达预算；TalkBack smoke 通过 |
| **D 数据出口与离线缓存** | 用户能控制自己的书和数据 | 已下载可离线打开；缓存可解释可清理；进度/书签/标注可导出恢复 |
| **E 精品增强** | Ink/TTS/OPDS/Sync/DOCX/CBZ 逐个进入不污染基础体验 | 每能力独立 ADR、权限说明、包体积预算、关闭路径；默认关闭或按需；出错不影响基础阅读 |

### 2.3 自研引擎裁决准则

自研不是禁忌，按用户收益裁决而非开发工作量。每个引擎进入实现前做一页 scorecard：包体积、首屏（1MB EPUB / 10MB PDF / 5MB TXT）、内存峰值与释放、电量、可访问性、数据可导出恢复、安全面（JS bridge / JNI / 文件权限 / 网络服务）。

既定裁决：

- **TXT** — 自研虚拟分页器成立（降低大文件内存、编码兼容、进度稳定）。
- **PDF** — 优先系统 `PdfRenderer`，除非功能缺口明确且第三方/自研在用户指标上明显胜出。
- **EPUB** — **原生重排**（jsoup 解析 XHTML → Compose `AnnotatedString`），去 WebView。见 ADR-EPUB-Engine。
- **DOCX/CBZ** — 重引擎适合可选能力，不让所有用户默认承担成本。

## 三、模块地图

### 3.1 完整模块清单（22 个）

> v4 相对 v3 新增 `:core:sync`（承接从 Layer 0 移出的 `SyncBackend`，见 P1-F）。

```text
 1  :app                            # com.android.application，组装点
 2  :core:model                     # 纯 Kotlin，零 Android import，零行为接口
 3  :core:calibre                   # CalibreClient + CalibreRepository
 4  :core:database                  # Room 数据库 + DAOs
 5  :core:prefs                     # DataStore Preferences（含 engine override 持久化）
 6  :core:sync                      # SyncBackend 接口 + NoOpSyncBackend（v4 新增）
 7  :core:ui                        # Compose 主题 + 共享组件
 8  :render:api                     # ReaderEngine 接口 + EngineDescriptor + Registry + PageTransitionHost + EngineStateStore 接口（M1）
 9  :render:epub                    # 原生重排（jsoup → AnnotatedString），无 WebView
10  :render:pdf                     # PdfRenderer
11  :render:txt                     # RecyclerView + TxtVirtualPager + Paging3
12  :render:mupdf                   # DOCX + CBZ (MuPDF JNI) — optional / 按需
13  :render:md                      # Markdown (Markwon Spannables)
14  :render:animate                 # PageTransitionHost 实现（翻页动效）
15  :ink                            # InkOverlay + Canvas（androidx.ink）
16  :features:library               # LibraryScreen + LibraryViewModel
17  :features:reader                # ReaderScreen + ReaderViewModel（MVI）+ ReaderRootLayout
18  :features:settings              # SettingsScreen + SettingsViewModel
19  :extensions:api                 # Extension SPI + BookSource + ReaderEventBus
20  :extensions:tts                 # TTS 朗读扩展
21  :extensions:stats               # 阅读统计扩展
22  :extensions:opds                # OPDS 书源扩展
```

### 3.2 八层分层与依赖规则

| Layer | 名称 | 模块 | 规则 |
|-------|------|------|------|
| **0** | 纯 Kotlin | `:core:model` | `kotlin("jvm")`，零 Android import。**仅含数据值对象，零行为接口**：`BookMeta`、`BookFormat`、`Locator`、`ReaderState`、`ReadflowError`、`InkAnchor`、`TransitionType`、`ThemeMode`、`DownloadStatus`、`DownloadedAsset`、`Bookmark`、`LoadingState`、`Offset`。`SyncBackend` 已移出（v4，P1-F）。依赖：**无**。 |
| **1** | Android 数据 | `:core:calibre`、`:core:database`、`:core:prefs`、`:core:sync`、`:extensions:api` | `android-library`（`:core:sync`/`:extensions:api` 为纯 Kotlin），可用 `android.*`，禁用 Compose。 |
| **2** | 渲染抽象 | `:render:api` | `android-library`，允许 `android.view.View`/`android.net.Uri`，禁用 Compose。定义 `ReaderEngine`、`EngineDescriptor`、`ReaderEngineRegistry`、`PageTransitionHost`、`PageTransitionHostFactory`（F10：host 工厂接口在此层，feature 仅依赖此抽象）、`EngineStateStore`（M1：加速缓存仓库接口，实现在 `:app`）、`ReadingMode`、`PagingKind`。`Locator` 从 `:core:model` import。 |
| **3** | 渲染实现 | `:render:epub`、`:render:pdf`、`:render:txt`、`:render:mupdf`、`:render:md`、`:render:animate` | `android-library`，产出 `View`。**例外（E1 后）**：`:render:epub`、`:render:md` 因产出 `ComposeView`（原生重排文本/Markwon→Compose）**允许依赖 Compose runtime**，但仍**禁用** `:core:ui`（不复用 app 主题，自带渲染所需最小 Compose）；其余 `:render:*`（pdf/txt/mupdf/animate）禁用 Compose。全部**不得**依赖 `:core:ui`、`:core:calibre`、`:core:database`、`:core:prefs`、`:ink`、任何 feature、任何 extension。 |
| **4** | Ink | `:ink` | `android-library`，View 系统，无 Compose。依赖 `:core:model` + `:core:database` + `:render:api`。`InkAnchor` 在 `:core:model`，编解码由 `:ink` 的 `InkAnchorCodec` 处理。 |
| **5** | UI 基础 | `:core:ui` | `android-library`，Compose 可用。Material3 主题、色板、字体、间距 tokens、共享 composable。 |
| **6** | 功能模块 | `:features:library`、`:features:reader`、`:features:settings` | `android-library`，Compose + ViewModels。(1) 不得直接依赖 `render:*` 实现模块，仅通过 `:app` DI 注入；(2) 功能模块之间不得相互依赖。 |
| **7** | 扩展 | `:extensions:tts`、`:extensions:stats`、`:extensions:opds` | `android-library`，可选，实现 Extension SPI。`settings.gradle.kts` 可条件排除。 |
| **8** | 应用组装 | `:app` | `com.android.application`。`MainActivity` + Koin DI 装配 + Navigation host + `AndroidManifest`。依赖：**全部模块**。 |

### 3.3 关键约束（CI 强制执行）

每条都给出可直接落 CI 的判定命令（F-M5：约束 2 收紧为可机械 grep 的规则，不靠人判定「行为接口」）：

1. **非 Compose 渲染实现不得依赖 Compose**：`:render:pdf`/`:render:txt`/`:render:mupdf`/`:render:animate` 的 `./gradlew :render:pdf:dependencies --configuration releaseCompileClasspath | grep -i compose` 必须返回空。**例外**：`:render:epub`/`:render:md` 允许 Compose（产出 ComposeView，见 §3.2 Layer 3 例外），但仍不得出现 `:core:ui`（`grep core:ui` 返回空）。
2. `:core:model` 纯度（可机械判定）：源码目录 `grep -rE '(^import android\.|: *Flow<|suspend |interface )' core/model/src/main` 必须返回空——即不含 `android.*` import、不含 `suspend`、不含返回 `Flow` 的成员、不声明 `interface`（仅 `data class`/`enum`/`sealed interface`+`@Serializable` 数据；`sealed interface` 作纯数据多态用 `InkAnchor`、`LocatorStrategy`、`LoadingState` 等需在白名单显式豁免）。
3. `:features:reader` 不得依赖具体引擎：`./gradlew :features:reader:dependencies | grep -E 'render:(epub|pdf|txt|mupdf|md)'` 返回空（仅允许 `render:api`）。
4. 功能模块之间零依赖：任两 `:features:*` 互不出现在对方 `dependencies` 输出中。
5. **base APK 不得包含 `:render:mupdf` 的 MuPDF `.so`**（v4，见 §5.7 optional gate）：`unzip -l app-base-release.apk | grep -i 'libmupdf'` 返回空。

## 四、视图架构

### 4.1 架构决策：Hybrid View

Root 为 `FrameLayout`，文档 View 直接加入 View hierarchy，Compose 管理 UI Chrome overlay 与原生重排文本。理由：ImageView 经 `AndroidView` 包装会因 recomposition 在翻页时不必要重建 View；RecyclerView 的 `LayoutManager`/`SnapHelper` 与 `LazyColumn` 语义有差距；统一 `FrameLayout` z-order 使 Ink overlay 层次清晰、触摸路由简单。EPUB 改原生重排后**不再引入 WebView**，文档层全部是原生 View / Compose 文本，Ink over EPUB 也因此与 PDF/TXT 同构（不再隔着 WebView Surface）。

**Compose interop 契约（F2 — ComposeView 在 View 体系内的生命周期挂载）**：EPUB/MD 走 `ComposeView`，而 `ComposeView` 要正常 `setContent`/recompose，**必须**在其依附的 View 树上设好三个 owner：`ViewTreeLifecycleOwner`、`ViewTreeViewModelStoreOwner`、`ViewTreeSavedStateRegistryOwner`，否则运行期崩溃或不重组（Compose-in-View 最常见崩溃源，且经 ViewPager2 复用时 owner 易丢）。本架构约定责任归属：**`ReaderRootLayout`（`:features:reader`）在把文档 View attach 进 `document_host` 时，统一对 ComposeView 类文档 View 调用 `setViewTreeLifecycleOwner` 等三件套**（owner 取自承载它的 `Activity`/`Fragment`），并在 `onViewDetached`/host `unbind()` 时不强依赖引擎清理——引擎只负责自己的内容，owner 挂载/解绑由 reader 宿主负责。各 `:render:*` 引擎不得假设自己能拿到宿主 owner（保持 Layer 3 不依赖 feature）。

### 4.2 FrameLayout Z-order

```text
FrameLayout (match_parent) ← reader_root, clipChildren=false（允许 ink 超出文档边界）
│
├─ [Z=0 — 文档容器]  FrameLayout id: document_host
│   同一时刻只有 ONE child；分页格式由 PageTransitionHost 包装（见 §5.6），连续格式直接挂载：
│     EPUB(滚动) → 单 ComposeView（LazyColumn 连续滚动）；EPUB(分页) → host 的 per-page ComposeView（见 §5.5，不在单 ComposeView 内套 HorizontalPager）
│     PDF  → ImageView（PdfRenderer，Bitmap LRU 3 页，ScaleType.MATRIX）
│     DOCX/CBZ → ImageView（MuPDF JNI，optional 引擎）
│     TXT  → RecyclerView（TxtVirtualPager + Paging3，字符边界对齐，见 §5.4）
│     MD   → ScrollView > TextView（Markwon Spannables，无 WebView）
│
├─ [Z=1 — 已完成 Ink Canvas]  CanvasView，touchable=false，渲染当前页已完成笔迹
├─ [Z=2 — 进行中 Ink]  InProgressStrokesView（androidx.ink.authoring），前端缓冲 <10ms
└─ [Z=3 — Compose UI Overlay]  ComposeView，root 非 UI 区域透传
     ├─ TopBar：返回、书名/章节、书签开关、Overflow
     ├─ 透明透传区域（触摸落到文档层）
     ├─ BottomBar：进度条、页码、模式 chips
     └─ InkToolbar（条件渲染）：笔类型、色板、笔宽、橡皮、Undo/Redo
```

### 4.3 触摸路由（Tool-type-based）

核心原则：Stylus 事件始终走 ink；手指事件走文档导航或 UI。无需「ink mode」按钮。核心类 `ReaderRootLayout`（继承 `FrameLayout`，在 `:features:reader`）。

- `ACTION_DOWN` 记录 tool type：`STYLUS`/`ERASER` → INTERCEPT 路由到 `inkOverlay.handleStylusEvent`；`FINGER`/`MOUSE`/`UNKNOWN` → 不拦截，先 hit-test ComposeView UI 区域，命中交 Compose，否则交 documentView。
- `ACTION_POINTER_DOWN`：新指为 STYLUS → cancel 当前手指手势转 ink；第二根手指 → 文档 pinch-zoom（若适用）。
- 手指点击翻页：左 1/3 上一页、右 2/3 下一页、中间 toggle Chrome。

### 4.4 缩放手势

| 格式 | 实现 |
|------|------|
| EPUB | Pinch **进行中**只做廉价视觉缩放预览（当前页文本 `scale`），`ACTION_UP` 后 debounce 提交一次 `setFontSize()` 真重排，非 viewport 缩放（F-R7：避免每帧重测整章分页丢帧） |
| PDF / DOCX / CBZ | `ScaleGestureDetector` + `ImageView.matrix`；翻页时 matrix 重置 |
| TXT / MD | 同 EPUB：pinch 预览 + `ACTION_UP` 提交 `setFontSize()`，非 viewport 缩放 |

> **重排测量惰性化（F-R7 / 配合 F14）**：分页模式下 `setFontSize` 不一次性重测全书，只测当前视口 ±1 页即可翻页，`pageCount`（§5.1 `StateFlow<Int>`）随后台测量异步回填，与其 reactive 语义一致。

### 4.5 ReaderIntent 契约（P3 fix — v4 此前只在原则 3 提概念，未落契约）

MVI 的输入端 `ReaderIntent` 在 v4 正式落为契约（定义在 `:features:reader`，因 Intent 是功能层概念，`:core:model` 不引入行为类型）。除导航/外观外，**搜索、目录、文字选择** 是核心阅读能力，必须在架构层有对应 Intent，而非散落在 View 回调里：

```kotlin
// :features:reader
sealed interface ReaderIntent {
    // ── 生命周期 ──
    data class OpenBook(val uri: Uri) : ReaderIntent  // engine 加速缓存按 bookId 从 EngineStateStore 取（F5），不经 Bundle 传
    object CloseBook : ReaderIntent

    // ── 导航 ──
    object NextPage : ReaderIntent
    object PrevPage : ReaderIntent
    data class GoTo(val locator: Locator) : ReaderIntent

    // ── 外观 ──
    data class SetFontSize(val sp: Float) : ReaderIntent
    data class SetTheme(val theme: ThemeMode) : ReaderIntent
    data class SetMode(val mode: ReadingMode) : ReaderIntent
    object ToggleChrome : ReaderIntent

    // ── 目录（TOC，核心） ──
    object OpenToc : ReaderIntent
    data class SelectTocItem(val tocHref: String) : ReaderIntent   // → 解析为 Locator → GoTo

    // ── 文档内搜索（核心） ──
    data class Search(val query: String) : ReaderIntent
    data class GoToSearchResult(val resultLocator: Locator) : ReaderIntent
    object ClearSearch : ReaderIntent

    // ── 文字选择 → 高亮/标注 ──
    data class SelectText(val anchor: InkAnchor.Text) : ReaderIntent  // 引擎上报选区锚点
    data class AddHighlight(val anchor: InkAnchor.Text, val color: Int) : ReaderIntent
    data class AddBookmark(val locator: Locator, val note: String? = null) : ReaderIntent
}
```

引擎侧支撑：TOC 来自解析（EPUB nav/ncx、PDF outline）；`Search`/`GoToSearchResult` 需 `ReaderEngine` 增 `suspend fun search(query): List<Locator>`（原生重排在已解析的 `ReaderItem` 文本上做，PDF 走 PdfRenderer 文本层或降级不支持）；文字选择由各引擎 View 上报 `InkAnchor.Text`（§6.3），与 Ink 锚点模型复用同一套坐标。搜索/TOC/选择按 Phase 推进（Phase 2 基础导航 + TOC，搜索/选择可后置），但 **Intent 契约在架构层先定义齐**，避免功能落地时回头改 MVI 形状。

## 五、渲染引擎契约

### 5.1 ReaderEngine 接口（含线程契约，P0-C）

定义在 `:render:api`（Layer 2）。

```kotlin
package dev.readflow.render.api

import android.net.Uri
import android.view.View
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.Locator
import kotlinx.coroutines.flow.StateFlow

/**
 * THREADING CONTRACT (v4, P0-C):
 *   - ALL ReaderEngine methods are CALLED by the caller (ReaderViewModel) on
 *     Dispatchers.Main. Engines must assume the main thread on entry.
 *   - View mutations (Compose recompose, ImageView, PdfRenderer) therefore happen
 *     on the main thread without extra hops.
 *   - Heavy work (parsing, indexing, FileChannel scan, JNI decode) MUST be moved
 *     off-main by the engine itself via withContext(Dispatchers.IO/Default),
 *     then switch back to Main before touching its View.
 *   - PdfRenderer is NOT thread-safe: engines must serialize page access with a
 *     single-threaded dispatcher or Mutex.
 */
interface ReaderEngine {

    // ── Identity ────────────────────────────────────────
    val id: String                 // e.g. "epub-reflow" — for override & debug
    val format: BookFormat
    val priority: Int              // lower = preferred; 0-9 system, 10-19 first-party JNI, 20-29 third-party, 30+ fallback
    val pagingKind: StateFlow<PagingKind>  // PAGED → PageTransitionHost; CONTINUOUS → scroll. StateFlow: runtime SCROLL↔PAGED switch (R-6)
    val supportsSearch: Boolean    // F8: false for fixed-layout/no-text-layer (PDF image-only) engines
    suspend fun supports(uri: Uri): Boolean

    // ── Lifecycle ───────────────────────────────────────
    suspend fun openBook(uri: Uri): Locator   // heavy work here, NOT in constructor
    fun createView(): View                    // called after openBook(); engine owns the View
    suspend fun close()                       // release file handles, Bitmaps, zip handle

    // ── Navigation ──────────────────────────────────────
    suspend fun goTo(locator: Locator)
    val currentLocator: StateFlow<Locator>
    val pageCount: StateFlow<Int>             // reactive; updates after parse / reflow

    // ── In-document search (F8, backs ReaderIntent.Search §4.5) ──
    // Returns empty list when !supportsSearch. Reflow engines search parsed ReaderItem
    // text; PdfRenderer goes through its text layer or returns empty (image-only PDF).
    suspend fun search(query: String): List<Locator> = emptyList()

    // ── Layout control (reflow formats) ─────────────────
    suspend fun setFontSize(sp: Float)        // no-op for fixed-layout
    suspend fun setMode(mode: ReadingMode)

    // ── View lifecycle (process-death / config-change) ──
    fun onViewAttached(view: View) {}
    fun onViewDetached(view: View) {}
    // Engine-private acceleration cache only (see §7.2 recovery chain). NOT semantic
    // position — that lives in ReaderState.currentLocator. May be dropped safely; persisted
    // to a cache FILE by EngineStateStore (NOT SavedStateHandle) to avoid TransactionTooLarge.
    suspend fun saveState(): ByteArray = ByteArray(0)
    suspend fun restoreState(state: ByteArray) {}
}

enum class ReadingMode { SCROLL, PAGED }
```

### 5.2 EngineDescriptor + 懒加载 Registry（P1-D / P1-E）

v4 把「引擎元信息」与「引擎实例」分离：Registry 只持有轻量 `EngineDescriptor`，命中后才用 `provider()` 实例化。重引擎（MuPDF/Ink）不在冷启动期被牵连初始化。override 由 `SettingsRepository` 提供只读快照，Registry 自身无可变状态 → 线程安全。

```kotlin
package dev.readflow.render.api

import android.net.Uri
import dev.readflow.core.model.BookFormat
import kotlinx.coroutines.flow.StateFlow

/** Lightweight, eagerly-collectible engine metadata. No engine instance is created here. */
data class EngineDescriptor(
    val id: String,
    val format: BookFormat,
    val priority: Int,
    /** Cheap predicate (extension/MIME sniff). Heavy probing happens after instantiation. */
    val quickSupports: (Uri) -> Boolean,
    /** Deferred factory — invoked only when this engine actually wins resolution. */
    val provider: () -> ReaderEngine,
)

class ReaderEngineRegistry(
    private val descriptors: Set<EngineDescriptor>,
    /** Per-format user override id, read-only snapshot owned by SettingsRepository (P1-D). */
    private val userOverrides: StateFlow<Map<BookFormat, String>>,
) {
    /** Resolve and instantiate the best engine. Registry holds no mutable state. */
    suspend fun resolve(uri: Uri): ReaderEngine {
        val path = uri.lastPathSegment ?: uri.path ?: ""
        val format = BookFormat.fromExtension(path.substringAfterLast('.', ""))

        userOverrides.value[format]?.let { id ->
            descriptors.find { it.id == id }?.let { return it.provider() }
        }

        val winner = descriptors
            .filter { it.format == format && it.quickSupports(uri) }
            .minByOrNull { it.priority }
            ?: throw NoEngineException(uri)

        return winner.provider().also {
            // Confirm with the heavier suspend supports() check after instantiation.
            if (!it.supports(uri)) throw NoEngineException(uri)
        }
    }

    fun candidatesFor(format: BookFormat): List<EngineDescriptor> =
        descriptors.filter { it.format == format }.sortedBy { it.priority }
}

class NoEngineException(uri: Uri) : IllegalStateException("No ReaderEngine supports: $uri")
```

引擎覆盖的写路径（用户在 Settings 选非默认引擎）由 `SettingsRepository.setEngineOverride(format, id)` 持久化到 DataStore，Registry 只读其 `StateFlow`。这同时满足 KOReader per-type override 的「持久化」语义和线程安全。

### 5.3 各格式引擎表

| 格式 | 引擎 | Priority | 模块 | View | 装载 |
|------|------|----------|------|------|------|
| EPUB | `EpubReflowEngine` | 0 | `:render:epub` | ComposeView | base |
| PDF | `PdfRendererEngine` | 0 | `:render:pdf` | ImageView | base |
| TXT | `TxtVirtualPagerEngine` | 0 | `:render:txt` | RecyclerView | base |
| MD | `MarkwonEngine` | 0 | `:render:md` | TextView | base |
| DOCX | `MuPdfEngine` | 10 | `:render:mupdf` | ImageView | **optional（§5.7）** |
| CBZ | `MuPdfEngine` | 10 | `:render:mupdf` | ImageView | **optional（§5.7）** |
| 翻页动效 | `PageTransitionHost` 实现 | — | `:render:animate` | — | base |

### 5.4 TXT 字符边界对齐（P2-J）

`TxtVirtualPagerEngine` 用 `FileChannel` 64KB 块扫描分页 + 成熟编码探测库（ICU4J `CharsetDetector` 或 juniversalchardet，**不自研探测器**——GBK 与 Shift-JIS 字节区间重叠，自研探测误判率高，是该外包给成熟库的部分；自研仅保留「分页器 + 探测结果→字符边界回退」逻辑）。**契约**：

- 页边界必须对齐到**字符起始字节**。按字节切块时若落在多字节字符中间，向前回退到上一个合法字符起点（UTF-8 看高位 bit，GBK 看双字节区间）。
- `Locator.ByteOffset.offset` 定义为「保证落在字符起始字节」。重开时据此 seek 不会产生半字符乱码。
- 这是自研引擎 scorecard「数据可恢复」的硬指标，CI 用混合编码样本测往返一致性。

### 5.5 EPUB 引擎细节（原生重排，去 WebView — E1）

> 路线裁决见 ADR-EPUB-Engine §12.3。要点：Android 不复用 Web 端的 WebView+epubjs 惯性（那是 Web「单文件即插即用」路线的产物），改自研原生重排，与 PDF/TXT/MD 同构。

**管线**：

1. **解析**（`:render:epub`，参考 Myne 的 `EpubParser`，Apache-2.0）：`ZipFile` 解 `META-INF/container.xml` → `.opf`（metadata/manifest/spine）→ nav/`.ncx`（TOC）。正文用 **jsoup** 遍历 XHTML DOM → 内部 `ReaderItem`（`Text`/`Heading`/`Image`/`Break`）序列。章节 body 懒加载（按 spine 项）。
2. **渲染**（ComposeView）：`ReaderItem` → Compose `AnnotatedString`（粗/斜/标题/对齐/段距/链接），图片 `BitmapFactory.decodeByteArray` → `Image`。`Html.fromHtml` 不作主解析器（CSS 支持太弱），仅作畸形片段兜底。
3. **分页**（统一交 `PageTransitionHost`，不在 ComposeView 内自建 HorizontalPager — F1）：
   - **Phase 1（构建 Phase 2 起步，决断 4 锁定）**：`pagingKind = CONTINUOUS`，挂 `NoTransitionHost`，单个 ComposeView 内 `LazyColumn` 连续滚动（最低风险，Myne/Book's Story 路线）。这是 EPUB 的**默认且首发唯一**形态。
   - **Phase 2（仅在分页 gate 通过后启用，决断 4）**：`pagingKind = PAGED`，由 ViewPager2-backed host 驱动分页；host 的 adapter 按「spine + `TextLayoutResult` 测量出的页号」提供 **per-page ComposeView**（每页一个 ComposeView，每页内只渲染该页文本，**不在单个 ComposeView 内再套 HorizontalPager**）。这样 EPUB 与 PDF/CBZ 分页同构、复用 Curl 动效、`onPageSettled` 报的就是真实页号。跨字号需重新测量分页、图片/段落边界不可切割是主要复杂度来源（见 §12.3 实现风险 gate）。**这是全架构最高技术风险点：每页新建 composition + RecyclerView 复用时反复重挂 ViewTree*Owner 三件套，达「翻页 < 50ms frame-paced」（§12.8）存在被推翻退回连续滚动的真实可能；故它独立成 gate，不与基础阅读链路绑定。**
   - 两种分页机制**互斥**，由 §5.6 `ReaderRootLayout` 按 `pagingKind` 选定唯一 host，杜绝「外层 ViewPager2 + 内层 HorizontalPager」双重水平滑动嵌套。
4. **定位锚点（无 CFI）**：`spine index + 章节内字符偏移/元素 index + progression`。锚到字符偏移/元素 index 而非页码或像素 → 跨字号稳定（重排只改行不改字符序）。章节内细分可借 nav fragmentId。对应 `Locator.Section`/`ByteOffset` 策略，**不再用 `Locator.Cfi`**。
5. **字号/主题/行距**：用户设置驱动 `AnnotatedString` 的 `SpanStyle`/`ParagraphStyle` + 容器背景，重排即生效。
6. **图片/封面（受控解码，F11/X-2）**：从 zip 按 manifest `media-type=image/*` 提字节解码；封面经 metadata `meta name="cover"` → manifest id 定位。**必须先 `BitmapFactory.Options.inJustDecodeBounds=true` 读出原始尺寸 → 超过像素上限（如 > 4096×4096 或解码后 > 视口×4 内存）则用 `inSampleSize` 降采样或拒绝**，绝不直接全尺寸 `decodeByteArray`（EPUB 内嵌图片像素尺寸由文件提供方控制，恶意/异常大图会瞬间 OOM 撑爆 §12.8 内存预算）。
7. **内嵌字体（@font-face）**：Phase 1 用系统/用户选定字体覆盖书内字体（多数原生阅读器做法）；精确 CSS→字体映射放弃。

**ReaderItem 类型 → 渲染降级矩阵（R-3，把「走样」从模糊取舍变成可验收契约）**：`AnnotatedString`+`ParagraphStyle` 无法表达表格/多级缩进等结构，超出 `Text`/`Heading`/`Image`/`Break` 四类基元的内容按下表确定性降级，避免「不可预期地走样」：

| 源结构 | 降级渲染 | 备注 |
|--------|---------|------|
| `<table>` | 单元格按行优先顺序流式排（失去表格对齐） | Phase 2 可选改 Compose `Layout` 自绘简单表 |
| 多栏 / `column-count` | 单栏 | 重排器本就单栏 |
| `<pre>` / 代码块 | 等宽字体 + 横向滚动不折行 | 保代码可读性 |
| 嵌套列表 | 按层级缩进（缩进上限 4 级，超出截断） | `ParagraphStyle.textIndent` |
| ruby / 上下标 | 退化为基准文本（ruby 注音并入括号） | — |
| `<blockquote>` | 左缩进 + 弱化前景色 | — |
| fixed-layout EPUB | 不支持，提示「该书为固定布局，当前版本不支持」 | 不静默白屏 |

该矩阵是 §12.3 实现风险 gate 的验收对象——在真实 EPUB 集上确认每种结构按表降级、无崩溃、无白屏。

**必须接受的取舍**（写入 ADR）：放弃多栏/float 绕排/@font-face 精确字体/固定布局(fixed-layout)/JS/复杂表格；复杂排版书（技术书、图文混排、诗歌缩进）会走样，fixed-layout EPUB 基本不支持。换来：无 WebView 内存/启动成本、无 nanohttpd/CSP/JS bridge 安全面、Ink/选择/触摸与其它格式统一、Hybrid 架构自洽。长期成本转移到「应对各家不规范 XHTML」的解析器维护（Book's Story #42 是前车之鉴）。

> **安全简化**：去 WebView 后，§12.6 的 WebView CSP / `setAllowFileAccess` / 本地 HTTP server / path token 一整套**全部移除**——EPUB 渲染不再有远程内容执行面，剩下的只是 zip 解析的常规健壮性（防 zip slip 路径穿越、解压体积上限）。

### 5.6 翻页动效：PageTransitionHost（R1-3）

v3 把翻页设计成 `ViewPager2.PageTransformer`，与「`document_host` 同时只有一个 child」矛盾（Round 1 P1）。v4 拆清宿主关系：

```kotlin
package dev.readflow.render.api

import android.view.View

/**
 * Wraps a pageable engine session so finger-following page transitions work
 * WITHOUT forcing every format into one ViewPager2.
 *
 * - Paged formats (EPUB paged, PDF, DOCX, CBZ) → ViewPager2-backed host.
 * - Continuous formats (TXT scroll, MD) → NoTransitionHost (vertical scroll, no paging).
 *
 * The host owns page container/adapter; the engine only supplies page Views and
 * navigation. document_host attaches the host's root (hostView()), not the engine
 * View directly.
 *
 * MOUNTING PROTOCOL (P1 fix):
 *   1. ReaderRootLayout reads engine.pagingKind (below) to decide which host to use.
 *   2. host.bind(engine) → host.hostView() added as the SINGLE child of document_host.
 *   3. On book close / engine swap: host.unbind() → remove hostView() from document_host.
 *
 * NAVIGATION DIRECTION (P1 fix): user-driven page turns (swipe / tap zone / key) are
 * routed THROUGH the host so the ViewPager2 animation and the engine stay in sync.
 * The host translates a swipe into engine.goTo(next/prev) and reports the settled page
 * back via onPageSettled — ReaderViewModel observes engine.currentLocator for state,
 * NOT the host (the host is a transport, not a source of truth).
 */
interface PageTransitionHost {
    fun hostView(): View
    fun bind(engine: ReaderEngine)
    fun setTransition(type: dev.readflow.core.model.TransitionType)

    /** Page prefetch budget (maps to ViewPager2.offscreenPageLimit). Default 1. */
    fun setOffscreenPageLimit(limit: Int)

    /** Forward/backward driven by UI (tap zone, key, edge swipe). Delegates to engine.goTo. */
    suspend fun next()
    suspend fun previous()

    /** Settled-page callback so the host can be a dumb transport; ViewModel still trusts engine.currentLocator. */
    fun setOnPageSettled(callback: (pageIndex: Int) -> Unit)

    fun unbind()
}

/** Tells ReaderRootLayout which host an engine needs. Exposed on ReaderEngine (see §5.1). */
enum class PagingKind { PAGED, CONTINUOUS }
```

- **Phase 1**：`SlideFadeTransitionHost`（ViewPager2 + SlideFade）；`NoTransitionHost`（滚动格式）。
- **Phase 2**：`CurlTransitionHost`（`rotationY` 3D 卷页）。
- 统一的是用户手势语义，不是强制同一 ViewPager2 实现。

**`ReaderRootLayout` 的挂载判定（P1 fix，接口层定义）**：分页/连续的区分不放在具体实现里，而由 `ReaderEngine.pagingKind`（§5.1）声明，`ReaderRootLayout`（`:features:reader`）据此向 `PageTransitionHostFactory`（`:render:api`）请求对应 host：

```kotlin
// :features:reader — 只依赖 :render:api 抽象，不 import 具体引擎/host
// 观察 pagingKind StateFlow，值变即重挂 host（R-6 运行时切模式）
fun mountHostFor(kind: PagingKind) {
    val host = when (kind) {
        PagingKind.PAGED      -> hostFactory.paged(transition)   // SlideFade / Curl
        PagingKind.CONTINUOUS -> hostFactory.continuous()        // NoTransitionHost
    }
    host.bind(engine)
    host.setOnPageSettled { idx -> viewModel.onPageSettled(idx) }  // host 只做 transport
    documentHost.replaceSingleChild(host.hostView())               // document_host 唯一 child
}
// 初次挂载 + 后续模式切换统一走 collect
lifecycleScope.launch { engine.pagingKind.collect { mountHostFor(it) } }
```

`ReaderViewModel` 的 `currentPageIndex` 仍以 `engine.currentLocator` 为真相源（host 回调只用于驱动动画结算 / 触发 debounce 持久化），保证「单一真相」与 R1。

**运行时切换阅读模式（R-6）**：`pagingKind`（§5.1）声明引擎的**默认**分页形态，但用户经 `setMode(SCROLL/PAGED)`（§4.4）可在阅读中切换滚动↔分页。约定：`ReaderEngine` 暴露 `val pagingKind: StateFlow<PagingKind>`（而非常量），`ReaderRootLayout` 观察其变化——值变时 `host.unbind()` → 按新值经 `hostFactory` 取新 host → `bind` 并以 `currentLocator` 恢复位置。这样「Phase1 仅 CONTINUOUS、Phase2 支持运行时切 PAGED」「同一 EPUB 引擎实例两种形态」都被覆盖，不需换引擎实例。

### 5.7 Optional DOCX/CBZ gate（MuPDF license decision）

- DOCX/CBZ 保持 **optional / deferred**，**不进 base APK**（§3.3 CI 约束 5）。
- 2026-06-23 `ADR-MuPDF-License` 已裁决：当前里程碑不启用 AGPL MuPDF 路线；任何 MuPDF-linked binary 必须先满足完整 AGPL 合规或商业授权。
- DOCX 不再假定由 MuPDF Core 直接支持；当前官方资料把 Office DOC/DOCX 放在 PyMuPDF Pro / conversion-layer 范畴，故 DOCX optional engine 延期。
- CBZ 可被 MuPDF 支持，但本轮也延期；若未来优先级升高，先评估无 AGPL 的 first-party ZIP image pager，再比较 MuPDF。
- 用户打开 DOCX/CBZ 时应显示「此格式当前未启用」与后续路线说明，而非静默失败。

## 六、Ink / 手写笔集成

> Phase 1 仅架构预留（接口 + 模型 + Room schema），不实现渲染；EPUB 笔写采用段落级自由书写（决策 3，方案 B）。

### 6.1 Phase 策略

| Phase | 交付 | 依赖 |
|-------|------|------|
| **1** | `:ink` 模块搭建、`InkOverlay` 接口、`InkAnchor` 模型（在 `:core:model`）、Room `ink_strokes` 表预留 `stroke_data BLOB` + `anchor_json TEXT`（§7.8 拆表后）。`androidx.ink` 依赖声明但不调用。**不实现渲染**。 | `:core:model` + `:render:api` |
| **2** | `CanvasView` + `InProgressStrokesView`、触摸路由集成、`InkToolbar`、`MotionEventPredictor`。PDF 优先（`InkAnchor.Page` 坐标稳定）。 | Phase 1 + `androidx.ink`（版本以 catalog 为准） |
| **3** | EPUB `InkAnchor.Text`（spine+element+charOffset 锚定，段落级，解析自 jsoup DOM 而非 WebView/CSS — F7）。字号变化后按文本锚点在重排后的 `ReaderItem` 序列重定位 bounding box。 | Phase 2 + EPUB engine |

### 6.2 InkOverlay 接口（`:ink`，Layer 4）

`attach(parent, documentView)` / `detach()` / `handleStylusEvent(MotionEvent): Boolean`（仅 stylus，调用方做 tool-type 路由）/ `cancelCurrentStroke()` / `setBrush(InkBrush)` / `undo()` / `redo()` / `clearPage()` / `onPageWillChange()` / `onPageChanged(anchor)` / `hasStrokes` / `isDrawing`。

`InkBrush`（`:ink`，使用 `android.graphics.Color` int）：`Pen` / `FountainPen(pressureSensitivity)` / `Highlighter` / `Eraser`。

### 6.3 InkAnchor 模型（`:core:model`，Layer 0，纯数据）

```kotlin
package dev.readflow.core.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface InkAnchor {
    /** Fixed-layout (PDF/CBZ/DOCX): anchor to immutable page coordinates. */
    @Serializable
    data class Page(val pageIndex: Int, val pageWidth: Float, val pageHeight: Float) : InkAnchor

    /** Reflow (EPUB/TXT/MD): anchor to text, re-resolved on font-size change.
     *  F7: uses the SAME spine+element+charOffset coordinate system as Locator (§7.1),
     *  NOT cssSelector — native reflow has no DOM/CSS at render time (CFI/selector were
     *  WebView-era concepts removed by E1). */
    @Serializable
    data class Text(
        val spineIndex: Int,          // which spine item (chapter)
        val elementIndex: Int,        // index into the parsed ReaderItem sequence
        val textStartOffset: Int,     // char offset within the element (code points)
        val textEndOffset: Int,
        val offsetXPx: Float,         // capture-time pixel hint, re-resolved on reflow
        val offsetYPx: Float,
        val fontSizeAtCapture: Float,
    ) : InkAnchor
}
```

Room 以 `anchor_type: String` + `anchor_json: String` 存储，`:ink` 的 `InkAnchorCodec` 负责编解码 —— `:core:model` 不依赖 Room。重定位时由 EPUB 引擎按 `spineIndex + elementIndex + textStartOffset` 在重排后的 `ReaderItem` 序列上反查屏幕坐标（与 §5.5 定位锚点、§7.1 `Locator` 同坐标系），字号变化后据此重算 bounding box。

## 七、数据层

### 7.1 Locator（`:core:model`，Layer 0）

定位策略由下方 `LocatorStrategy` 显式建模（B2）：`Page(index, total)`、`Section(spineIndex, elementIndex, charOffset)`、`ByteOffset(offset, length)`（保证 offset 落在字符起始字节，见 §5.4）、`Unknown`。EPUB 原生重排用 `Section`（spine + 元素 index + 章节内字符偏移）定位，**不再使用 CFI**（CFI 是 WebView/DOM range 概念，去 WebView 后无意义；E1）。

**跨端同步主键（v4 外部对标修订，E2/P2-EXT-3）**：`Locator` 额外携带与定位策略无关的两个进度标量，作为三端同步的**稳定主键**——`ByteOffset`/`Section` 等是引擎内精确恢复用的细节，对字号敏感、互操作性差，不适合做同步比对键。注意 Web 端仍用 epubjs CFI，但 CFI 不进同步主键，三端统一用 `totalProgression` 比对。

```kotlin
@Serializable
data class Locator(
    val strategy: LocatorStrategy,        // 带 payload 的精确定位（Android 无 Cfi）
    val progression: Float? = null,       // 资源内进度 0..1（章节内）
    val totalProgression: Float? = null,  // 全书进度 0..1（跨端同步主键）
)

/**
 * 精确定位策略，每种携带本端跳转所需 payload（B2 — v3/早期 v4 只在散文里列了
 * Page/ByteOffset/Section/Unknown 却没给字段，导致 goTo()/search()/InkAnchor 无处存
 * spine index、章节内字符偏移、元素 index。此处补齐为带 payload 的 sealed interface）。
 * 全部 @Serializable、纯数据，满足 §3.3 约束 2 的 :core:model 纯度白名单。
 */
@Serializable
sealed interface LocatorStrategy {
    /** Fixed-layout（PDF/CBZ/DOCX）：页号定位。 */
    @Serializable
    data class Page(val index: Int, val total: Int) : LocatorStrategy

    /** Reflow（EPUB 原生重排 / TXT / MD）：spine + 元素 index + 章节内字符偏移。
     *  与 §5.5 定位锚点、§6.3 InkAnchor.Text 同坐标系；charOffset 单位为 code point，
     *  且保证落在字符起始位置（TXT 见 §5.4）。 */
    @Serializable
    data class Section(
        val spineIndex: Int,        // 哪个 spine 项（章节）
        val elementIndex: Int,      // 解析后 ReaderItem 序列内的元素 index
        val charOffset: Int,        // 元素内字符偏移（code point）
    ) : LocatorStrategy

    /** 纯字节偏移定位（大文件 TXT）：offset 保证落在字符起始字节（§5.4）。 */
    @Serializable
    data class ByteOffset(val offset: Long, val length: Int) : LocatorStrategy

    /** 未知 / 不可定位（解析失败兜底）：仅靠 totalProgression 近似恢复。 */
    @Serializable
    data object Unknown : LocatorStrategy
}
```

> **建模说明（B2）**：早期版本把「Page/ByteOffset/Section」当作裸枚举名写在注释里，但 `goTo(locator)`、`search(): List<Locator>`、`InkAnchor.Text` 都依赖 spine index + 字符偏移这些 **payload**。裸枚举装不下，照抄即语义落空（违反原则 7）。故 `LocatorStrategy` 必须是带字段的 `@Serializable sealed interface`，`Section` 与 `InkAnchor.Text`（§6.3）共用同一坐标系。`ByteOffset`/`Section` 是「引擎内精确恢复」用，对字号敏感、互操作差，**不做同步比对键**；同步比对一律用下面的 `totalProgression`。

对标 Readium（progression + position + totalProgression 为主、`partialCfi` 为可选兼容字段）与 KOReader（`percentage = cur/total`）。三端同步比对用 `totalProgression`，本端精确跳转用 `strategy`。与 `linreads-sync` 的 LWW 正交互补：LWW 决谁赢，`totalProgression` 决进度值。

**`totalProgression` 三端统一计算口径（F3 — E2 落地必要补全）**：把进度标量定为同步主键，前提是三端算出的值对同一阅读位置**收敛**，否则换设备会跳到错误位置且静默无报错。各端解析/渲染引擎不同（Web epubjs、Android 原生重排、HarmonyOS），若各按「页号/总页数」算，分母对字号敏感会漂移；若按字符偏移算，标签/空白计入口径不一致也会偏。故钉死**唯一规范**：

```text
totalProgression = 全书累计「规范化纯文本」字符偏移 / 全书「规范化纯文本」总字符数
```

规范化纯文本（normalized plain text）定义如下：

1. 按 spine 顺序拼接，仅取**文本节点**（剥除所有 HTML/XML 标签、属性、注释、`<script>`/`<style>` 内容）。
2. 不计入 EPUB metadata（OPF/NCX/nav 导航文本不算正文）。
3. 空白折叠：连续空白（空格/制表/换行）折叠为单个空格；首尾空白裁除。
4. 图片/公式等非文本节点按 **0 字符**计（不占进度），与是否显示无关。
5. 字符计数单位为 **Unicode code point**（非 UTF-16 char、非字节），避免代理对/多字节口径分歧。

**全书字符总数来源（决断 2 — 接受首开预扫）**：`totalProgression` 的分母是「全书规范化纯文本总字符数」，与 §5.5/§12.8 的**按章节惰性解析**表面冲突（惰性解析时全书未必都解析过）。裁决：**`openBook()` 时做一次全书纯文本预扫**，对每个 spine 项只跑「解析 XHTML → 规范化纯文本 → 计 code point 数」，产出一张轻量 **per-spine 字符计数表**（仅整数计数，**不保留正文**，内存占用与书体积近似无关，几十 KB 量级），据此得全书总字符数与每章累计起点偏移。

- 该预扫是**一次性成本，计入「打开」预算而非「翻页」预算**（§12.8）；与 §5.5 惰性「正文解析」正交——预扫只数字符不留 `ReaderItem`，惰性解析才构建可渲染序列并受 LRU 驱逐。二者解耦，故「内存与书体积解耦」仍成立。
- 计数表随 §7.2 Engine 加速缓存（`EngineStateStore`）落本地文件，重开命中则跳过预扫；缓存缺失则重算（可丢不影响正确性）。
- 大书预扫若超首开预算，可先以「当前章节级粗进度」占位、后台补算精确 `totalProgression`，回填后刷新进度条（与 §4.4 `pageCount` 异步回填同模式）。

**跨端收敛（决断 3 — 当前仅 Android，三端契约后置）**：本项目当前只实装 Android，三端 `totalProgression` 收敛（< 0.5% 误差、共享参考实现、对拍语料）**暂不落地**。上述口径先作为 **Android 端内部规范**保证「同一设备换字号/重开进度稳定」。待 Web/HarmonyOS 真正接入进度同步时，再把规范化算法提为 `shared/` 共享参考实现 + 三端对拍语料并冻结契约——在此之前 §7.6 同步用 `totalProgression` 仅在 Android 单端内自洽，跨端互通是 Phase 3+ 的事，不阻塞当前实装。

### 7.2 ReaderState（`:core:model`，真正可序列化 — P0-A）

```kotlin
package dev.readflow.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ReaderState(
    val bookId: String,
    val bookMeta: BookMeta? = null,
    val format: BookFormat = BookFormat.UNKNOWN,
    val loadingState: LoadingState = LoadingState.Idle,
    val currentLocator: Locator? = null,
    val totalPages: Int = 0,
    val currentPageIndex: Int = 0,
    val fontSize: Int = 18,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val zoomLevel: Float = 1.0f,
    val panOffset: Offset = Offset.Zero,
    val isUiVisible: Boolean = true,
    val transition: TransitionType = TransitionType.SLIDE,
    val error: ReadflowError? = null,   // now serializable (P0-A)
)

@Serializable
data class Offset(val x: Float = 0f, val y: Float = 0f) {
    companion object { val Zero = Offset() }
}
```

`ReaderState` 不含 View 引用（R1）。因为 `ReadflowError` 现在是纯数据（§7.3），`ReaderState` 整体真正可 `@Serializable`，§5.1 的 `saveState`/进程死亡恢复链才成立。

**持久化机制（F4 — kotlinx `@Serializable` ≠ SavedStateHandle 可存）**：`@Serializable` 是 kotlinx 维度的可序列化，**不等于**可直接放进 `SavedStateHandle`——后者走 Android `Bundle`/`Parcelable` 体系，二者互不相通。`handle.get<ReaderState>()` 对一个仅标 `@Serializable` 的对象运行期取回 null 或抛异常（编译却能过，易漏）。本项目统一规则：**进 `SavedStateHandle` 的态走 JSON String 中转**，不混用 `@Parcelize`（避免在 `:core:model` 纯 JVM 模块引入 Android `Parcelable` 依赖，破坏 Layer 0 纯度）：

```kotlin
// :core:model 保持纯 JVM @Serializable；进 SavedStateHandle 时由 :features:reader 做 JSON 中转
private fun SavedStateHandle.putState(state: ReaderState) {
    this[KEY_STATE] = Json.encodeToString(state)          // String，Bundle 安全
}
private fun SavedStateHandle.getState(): ReaderState? =
    get<String>(KEY_STATE)?.let { Json.decodeFromString<ReaderState>(it) }
```

**进程死亡恢复链（P2 fix，两层职责分明；F5 修正存储位置）**：v4 有两套并存的状态保存机制，边界如下——

| 层 | 机制 | 存什么 | 存在哪 | 谁负责 |
|----|------|--------|--------|--------|
| **ViewModel 态** | `ReaderState` JSON String | bookId、locator、UI 态（字号/主题/进度/可见性） | `SavedStateHandle`（语义位置，体量小） | `ReaderViewModel` |
| **Engine 内部态** | `ReaderEngine.saveState()` 产出的加速缓存 | 引擎私有瞬态（EPUB 已测量分页表、PDF 页缓存游标、TXT FileChannel 扫描位） | **本地文件** `cacheDir/engine-state/<bookId>.bin`，`SavedStateHandle` 只存指向它的 key + 校验 | `ReaderEngine` 实现 |

为什么 Engine 态不进 `SavedStateHandle`：`SavedStateHandle` 经 Binder 事务受 **~1MB `TransactionTooLargeException`** 限制，而引擎分页表可达数千项，正是最易超限的东西——「可丢的加速缓存」更不该占用稀缺 Binder 预算。故它落本地文件，`SavedStateHandle` 仅存语义位置（`ReaderState`）+ 缓存文件指针。`ReaderState` 存「语义位置」（locator 足以重开并跳转），缓存文件存「省去重算的加速数据」（可丢，丢了只是慢，不会错）。

**`EngineStateStore` 归属（M1 — 此前散见 §5.1/§4.5/§7.2 引用却无模块归属，与早期 `SyncManager` 同类遗漏）**：`EngineStateStore` 是「按 bookId 读写 `cacheDir/engine-state/<bookId>.bin`」的加速缓存仓库，接口定义在 **`:render:api`（Layer 2）**（与 `ReaderEngine.saveState()/restoreState()` 同层、同语义域），具体实现（用 `Context.cacheDir`）在 **`:app`** 装配并经 Koin 注入 `ReaderViewModel`。它只搬运不透明 `ByteArray`，不依赖任何具体引擎，故不破坏 Layer 2 的「不依赖实现」约束。

```kotlin
// :render:api
interface EngineStateStore {
    suspend fun load(bookId: String): ByteArray?       // 缺失返回 null → 引擎重算
    suspend fun save(bookId: String, state: ByteArray) // 原子写 cacheDir/engine-state/<bookId>.bin
    suspend fun evict(bookId: String)                  // 关书/清缓存
}
```

恢复路径：

```kotlin
class ReaderViewModel(
    private val handle: SavedStateHandle,   // survives process death
    private val engineRegistry: ReaderEngineRegistry,
    private val engineStateStore: EngineStateStore,  // 读写 cacheDir/engine-state/*.bin
    /* ... */
) : ViewModel() {
    // 1. ReaderState 从 SavedStateHandle 恢复（JSON String 中转，进程死亡后仍在）
    private val restored: ReaderState? = handle.getState()

    fun onOpen(uri: Uri) = viewModelScope.launch {
        val engine = engineRegistry.resolve(uri)
        engine.openBook(uri)                                  // 语义位置由 locator 驱动
        restored?.bookId?.let { engineStateStore.load(it) }   // 2. 加速缓存（可空，缺失则重算）
            ?.let { engine.restoreState(it) }
        restored?.currentLocator?.let { engine.goTo(it) }
    }

    // 写回：UI/系统触发 onSaveInstanceState 时
    fun persist(engine: ReaderEngine) = viewModelScope.launch {
        handle.putState(currentState)                         // ReaderState → SavedStateHandle（小）
        engineStateStore.save(currentState.bookId, engine.saveState())  // Engine 缓存 → 文件（大、可丢）
    }
}
```

约定：`ReaderState` 是恢复的**充分条件**（仅凭它即可重开书并回到正确位置）；Engine 加速缓存是**可选优化**（缺失时引擎重新解析/测量，不影响正确性）。

### 7.3 ReadflowError — 可序列化纯数据 + 运行时异常分层（P0-A）

v3 把 `Throwable? cause` 放进 `@Serializable` 模型，KSP 编译期即报 *"Serializer has not been found for type 'Throwable'"*。v4 拆两层：持久化/状态用纯数据 `ReadflowError`；运行时携带 `Throwable` 的 `ReadflowException` 不进任何持久化/同步/导出模型。

```kotlin
package dev.readflow.core.model

import kotlinx.serialization.Serializable

/** Pure, serializable error value. Goes into ReaderState / Room / sync / backup. */
@Serializable
data class ReadflowError(
    val kind: Kind,
    val message: String,
    val code: Int? = null,            // Network HTTP code
    val resourceType: String? = null, // NotFound
    val id: String? = null,           // NotFound
    val format: String? = null,       // Unsupported
) {
    enum class Kind { NETWORK, DATABASE, PARSE, NOT_FOUND, UNSUPPORTED, AUTH, UNKNOWN }

    companion object {
        fun network(code: Int?, message: String) = ReadflowError(Kind.NETWORK, message, code = code)
        fun parse(message: String) = ReadflowError(Kind.PARSE, message)
        fun notFound(type: String, id: String) =
            ReadflowError(Kind.NOT_FOUND, "$type not found: $id", resourceType = type, id = id)
        fun unsupported(format: String) =
            ReadflowError(Kind.UNSUPPORTED, "Unsupported format: $format", format = format)
        fun auth() = ReadflowError(Kind.AUTH, "Authentication failed")
        fun unknown(message: String = "An unexpected error occurred") = ReadflowError(Kind.UNKNOWN, message)
    }
}

/** Runtime-only carrier. NEVER persisted/serialized. Keeps the original cause for logs/crash. */
class ReadflowException(
    val error: ReadflowError,
    cause: Throwable? = null,
) : RuntimeException(error.message, cause)
```

`ReadflowResult<T>`（monadic，`Success`/`Failure(ReadflowError)`）保持不变：`map`/`getOrNull`/`getOrThrow`（throw `ReadflowException(error)`）。

### 7.4 辅助类型（`:core:model`）

- **DownloadStatus**：`NOT_DOWNLOADED` / `DOWNLOADING` / `DOWNLOADED` / `FAILED`。
- **LoadingState**（`@Serializable` sealed）：`Idle` / `Loading` / `Error(ReadflowError)` / `Loaded`。
- **TransitionType**（`@Serializable` enum，SCREAMING_SNAKE_CASE）：`SLIDE` / `CURL` / `FADE` / `NONE`。
- **DownloadedAsset（R1-2，替代 `java.io.File`）**：

```kotlin
@Serializable
data class DownloadedAsset(
    val bookId: String,
    val format: BookFormat,
    val localUri: String,     // platform-neutral; data layer maps to Uri / File / SAF doc
    val sizeBytes: Long,
    val checksum: String? = null,
)
```

### 7.5 BookSource 接口（`:extensions:api`，Layer 1）

```kotlin
package dev.readflow.extensions.api

import dev.readflow.core.model.*

interface BookSource {
    val sourceId: String          // "calibre" | "opds" | "local"
    val sourceName: String

    suspend fun search(query: String, offset: Int = 0, limit: Int = 100): ReadflowResult<List<BookMeta>>
    suspend fun getMetadata(bookId: String): ReadflowResult<BookMeta>
    suspend fun getDownloadUrl(bookId: String, format: String): ReadflowResult<String>
    suspend fun getCoverUrl(bookId: String): ReadflowResult<String>

    /** Returns a platform-neutral asset descriptor, NOT java.io.File (R1-2). */
    suspend fun download(bookId: String, format: String): ReadflowResult<DownloadedAsset>
    suspend fun getDownloadStatus(bookId: String): DownloadStatus
    suspend fun isAvailable(): Boolean
}
```

实现：`CalibreBookSource`、`OpdsBookSource`、`LocalFileBookSource`（SAF / `ACTION_VIEW`，支撑 Phase A 本地阅读闭环）、`StubBookSource`（离线测试）。

### 7.6 SyncBackend（`:core:sync`，Layer 1 — 移出 Layer 0，P1-F）

```kotlin
package dev.readflow.core.sync

import dev.readflow.core.model.*

interface SyncBackend {
    val backendId: String
    val isAvailable: Boolean
    suspend fun pushProgress(bookId: String, progress: ReadingProgress): ReadflowResult<Unit>
    suspend fun pullProgress(bookId: String): ReadflowResult<ReadingProgress?>
    suspend fun pushBookmark(bookmark: Bookmark): ReadflowResult<Unit>
    suspend fun pullBookmarks(bookId: String): ReadflowResult<List<Bookmark>>
}

class NoOpSyncBackend : SyncBackend {
    override val backendId = "noop"
    override val isAvailable = false
    override suspend fun pushProgress(bookId: String, progress: ReadingProgress) = ReadflowResult.Success(Unit)
    override suspend fun pullProgress(bookId: String) = ReadflowResult.Success(null)
    override suspend fun pushBookmark(bookmark: Bookmark) = ReadflowResult.Success(Unit)
    override suspend fun pullBookmarks(bookId: String) = ReadflowResult.Success(emptyList())
}
```

- **Phase 1**：`NoOpSyncBackend`，Settings「同步」区灰显。
- **Phase 2**：主路径 `KorroSyncBackend`（REST，对标 korrosync）+ 兜底 `WebDavSyncBackend`。
- **可选互通（E3/P2-EXT-4）**：新增 `KoSyncBackend` 兼容 KOReader **KOSync** 协议（REST+JSON、`X-Auth-User`/`X-Auth-Key`=MD5(password)、`PUT/GET /syncs/progress`、LWW by timestamp、文档 ID 用 filename-MD5 或 partial-MD5）。接口已是可插拔的，新增此后端近零架构成本，让本项目与现成 koreader-sync-server 生态互通。`percentage = cur/total` 与 §7.1 `totalProgression` 对齐。
- **冲突解决**：LWW（`updatedAt` 决胜）；书签/标注 Union merge（用 `isDeleted` tombstone，不物理删除）。客户端推送 debounce（v4 ~2s，KOSync 互通时对齐其 ~25s）。

### 7.7 同步元数据（P1-F — 没有 updatedAt 的 LWW 无法实现）

进度、书签、标注模型在冻结前必须带同步元数据，否则 §7.6 的 LWW/Union 是空头承诺：

```kotlin
// :core:model
@Serializable
data class ReadingProgress(
    val bookId: String,
    val locator: Locator,
    val progressPercent: Float,
    val updatedAt: Long,        // LWW key
    val deviceId: String,       // origin replica
)

@Serializable
data class Bookmark(
    val id: String,             // UUID, stable across devices
    val bookId: String,
    val locator: Locator,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,        // LWW
    val deviceId: String,
    val isDeleted: Boolean = false,  // tombstone for Union merge
)
```

**`deviceId` 来源（F6）**：首次启动生成一个 `UUID.randomUUID()`，持久化于 `:core:prefs` DataStore（key `device_id`），全生命周期稳定。语义明确为「**replica 标识**」：用户清除应用数据 / 重装即换新 deviceId（视作新 replica），这与离线优先的本地数据语义一致，不依赖任何账号体系。`deviceId` 不含设备指纹/硬件 ID，避免隐私问题（权限轻）。

**LWW 时钟漂移防御（F6）**：`updatedAt` 是客户端 wall-clock，多设备（尤其离线 LAN、无 NTP 的平板）时钟漂移会让「慢钟设备的旧进度」被判成最新而覆盖真实较新进度——静默数据丢失，直接违背「数据轻=换设备能接着读」。对策：

- **tie-break 规则**：LWW 比较键为元组 `(updatedAt, deviceId)`，`updatedAt` 相等时按 `deviceId` 字典序确定性打破并发，避免「同毫秒」抖动。
- **进度类（`ReadingProgress`）额外护栏**：当两端 `updatedAt` 差值在可疑阈值内（如 < 5 min）且 `totalProgression` 出现**回退**（新值 < 旧值）时，不静默覆盖，提示用户「检测到另一设备的较早进度，是否跳转」由用户裁决，而非时钟说了算。阅读进度宁可问一次，不可悄悄回退。
- **有 KOSync/korrosync 服务端互通时**：优先采信**服务端落库时间戳**而非客户端时钟（服务端单调，消除多端时钟基准差）。
- **书签/标注（Union merge）**：本就靠 `id`(UUID) + `isDeleted` tombstone 合并，对时钟漂移不敏感，仅 `updatedAt` 用于同一 id 的字段更新择新，套用上面 tie-break 即可。

### 7.8 Room Schema（`:core:database`，5 表 — F6/D-6/D-7 拆表 + 索引）

| 表 | 关键列 |
|----|--------|
| `books` | `id PK`, `title`, `author`, `format`, `cover_url`, `download_status`, `local_uri?`, `last_read_at?` |
| `reading_progress` | `book_id PK`, `locator_json`, `total_progression REAL`, `progress_percent`, `updated_at`, `device_id` |
| `text_annotations` | `id PK (UUID)`, `book_id`, `total_progression REAL`, `anchor_type`, `anchor_json`, `selected_text`, `note?`, `color`, `created_at`, `updated_at`, `device_id`, `is_deleted` |
| `ink_strokes` | `id PK (UUID)`, `book_id`, `page_index`, `anchor_type`, `anchor_json`, `stroke_data BLOB`, `created_at`, `updated_at`, `device_id`, `is_deleted` |
| `bookmarks` | `id PK (UUID)`, `book_id`, `total_progression REAL`, `locator_json`, `text`, `created_at`, `updated_at`, `device_id`, `is_deleted` |

**拆表理由（D-6）**：v4 早期把手写笔迹（`stroke_data BLOB`，可能很大）与文字高亮/笔记混在单张 `annotations` 表，列高亮列表时 Room 实体会带出大 BLOB、列表滚动/同步序列化成本高，且大量列对任一类型恒为 NULL（稀疏）。拆成 `text_annotations`（轻，高频读）与 `ink_strokes`（重 BLOB，按页惰性读）两表，互不拖累。

**索引与排序列（D-7）**：所有同步/查询热列建索引——`book_id`（全部表）、`(book_id, updated_at)`（同步增量拉取）、`(book_id, is_deleted)`（Union merge 过滤）。`bookmarks`/`text_annotations`/`reading_progress` 冗余 `total_progression REAL` 列（与 §7.1 同步主键同源），使「按书内位置排序书签/标注」「SQL 层比较进度」可直接在 DB 完成，无需把每条 `locator_json` 反序列化后内存排序；`locator_json` 仍存完整定位细节供精确跳转。

### 7.9 离线缓存与数据出口

- **智能 LRU 缓存**：最近 5 本（可配 `cacheLimit`）自动下载到 `filesDir/books/<bookId>.<format>`，满时删最久未读。
- **手动下载**：书架「下载」按钮，`WorkManager` 后台任务，`WorkInfo.State → DownloadStatus` Flow。
- **离线模式**：离开 LAN → 书架筛 `DOWNLOADED`，状态栏提示。
- **存储预算**：默认 500MB；单本 50MB EPUB / 200MB PDF。
- **数据出口（数据轻）**：`LinReads Backup` = ZIP + JSON manifest（progress/bookmarks/annotations）+ optional sidecar assets，一键导出/恢复。

## 八、扩展系统

> 发现机制统一为 **Koin multibind**，删除 ServiceLoader（P1-G 决策）。

### 8.1 Extension SPI（`:extensions:api`，Layer 1，纯 Kotlin）

`Extension` 接口：`meta: ExtensionMeta`、`suspend fun onAttach(scope, context)`、`suspend fun onDetach()`。配套 `ExtensionMeta`、`ExtensionScope : CoroutineScope`、`ExtensionMenuItem`、`ReaderHook`（`onBeforePageRender`/`onAfterPageRender`/`onBookmarkAdd`/`onBookmarkRemove`/`onBeforeBookClose`）。

`ExtensionContext`（沙箱）：`readerState: StateFlow<ReaderState>`、`eventBus: ReaderEventBus`、`settings: ExtensionSettings`、`registerMenuItem`、`registerReaderHook`、`unregisterAll`。因 `ReaderState` 无 View 引用，沙箱完整 —— 扩展拿不到文档 View handle（R12）。

**UI 态字段的语义边界（P5）**：`ReaderState` 同时含数据态（`bookId`/`currentLocator`/`format`）与 **UI 态**（`isUiVisible`/`zoomLevel`/`panOffset`/`theme`/`fontSize`/`transition`）。沙箱在「无 View 引用」意义上完整，但 UI 态字段经同一 `StateFlow` 暴露，扩展可能误把它们当业务输入（如「`zoomLevel < 0.5` 时不激活 TTS」），造成 UI 态与扩展逻辑耦合、随 UI 重构而脆裂。**设计约定**：

- UI 态字段供扩展做**自适应展示**（如按 `theme` 调菜单配色），不应作为**业务决策依据**。
- 扩展对 UI 态字段做**防御性读取**：容忍其缺省/突变，不假设取值范围或变更时机。
- 若扩展确需稳定的语义信号（如「正在沉浸阅读」），应由 `ReaderEventBus` 发明确事件（§8.4），而非从 `isUiVisible` 这类 UI 态推断。
- 未来若收紧，可考虑给扩展暴露 `ReaderState` 的**数据态投影视图**（剔除 UI 态字段），本版先以约定约束。

### 8.2 发现：Koin multibind（取代 ServiceLoader）

v3 同时写了 ServiceLoader（§7.2）和 Koin single + 手动注册（§8.2），机制重叠且 Android 上 ServiceLoader 经 R8 易裁剪需 keep 规则。v4 统一：每个扩展模块声明 Koin module，`bind Extension::class`，Registry 用 `getAll<Extension>()` 收集。

```kotlin
val ttsExtensionModule = module {
    single<Extension> { TtsExtension() } bind Extension::class
}
// ExtensionRegistry 在 :app 收集
single { ExtensionRegistry(getAll<Extension>()) }
```

> 注：当前为 **internal first-party 编译期扩展**，非用户可安装插件。真正的用户插件系统（签名、权限、API versioning、崩溃隔离）须另立 ADR（Round 1 P2）。

### 8.3 生命周期与 ExtensionContext

`discover() → 用户 Settings 启用 → ExtensionRegistry.onAttach() → Extension 注册 menu/hooks → 用户禁用 → onDetach() → scope 取消 → unregisterAll()`。

### 8.4 ReaderEventBus + ReaderEvent（`:extensions:api`，纯 Kotlin）

`SharedFlow<ReaderEvent>`，fire-and-forget。事件含：`EngineInitialized`/`EngineShutdown`、`BookOpened`/`BookClosed`、`PageChanged`、`BookmarkAdded`/`BookmarkRemoved`、`AnnotationAdded`/`AnnotationRemoved`、`InkStrokeCommitted`、`FontSizeChanged`、`ThemeChanged`、`BookProgressSaved`、`TtsWordHighlighted`。所有 `bookId: String`。

### 8.5 ExtensionSettings

`ExtensionSettings(enabledExtensions: Set<String>, tts: TtsConfig, stats: StatsConfig)`，持久化于 DataStore（`:core:prefs`），经 `SettingsRepository.extensionSettings: Flow<ExtensionSettings>` 暴露。

---

## 九、依赖注入

### 9.1 Koin 模块结构

每模块提供自己的 Koin module；`:app` 的 `ReadflowApplication.onCreate()` 启动 `startKoin { androidContext(...); modules(...) }`，按 Layer 顺序加载。

### 9.2 关键绑定约定

| 类型 | Scope | 绑定 |
|------|-------|------|
| `EngineDescriptor` 各项 | `single` | `single { EngineDescriptor("epub-reflow", EPUB, 0, ::epubQuickSupports) { EpubReflowEngine(...) } } bind EngineDescriptor::class` |
| `ReaderEngineRegistry` | `single` | `single { ReaderEngineRegistry(getAll<EngineDescriptor>().toSet(), get<SettingsRepository>().engineOverrides) }` |
| `BookSource` 各实现 | `single` | 同 descriptor 模式 |
| `SyncBackend` | `single` | Phase 1：`single<SyncBackend> { NoOpSyncBackend() }` |
| `Extension` 各实现 | `single` | `bind Extension::class`，Registry `getAll` 收集 |
| `ReaderViewModel` / `LibraryViewModel` | `scope` | 绑定到对应 Screen 生命周期 |
| Room DAOs | `single` | Room database 创建时提供 |

注意：Registry 收集的是 `EngineDescriptor`（轻量），不是 `ReaderEngine` 实例 → 重引擎不在冷启动期实例化（P1-E）。

### 9.3 功能模块与引擎隔离

`:features:reader` 不持有任何 `ReaderEngine` 实现引用，只通过 `ReaderEngineRegistry`（接口，`:render:api`）解析。

```kotlin
class ReaderViewModel(
    private val engineRegistry: ReaderEngineRegistry,
    private val transitionHostFactory: PageTransitionHostFactory,  // :render:api 接口（F10）
    private val syncManager: SyncManager,                          // :core:sync（F10，见下）
    private val eventBus: ReaderEventBus,
) : ViewModel() { /* 绝不 import EpubReflowEngine 等具体类型 */ }
```

> **F10 归属补全**：`PageTransitionHostFactory` 是 `:render:api`（Layer 2）的**接口**，具体 host 实现在 `:render:animate`（Layer 3），由 `:app` DI 绑定注入——`:features:reader` 只依赖该接口，不违反「feature 不依赖 render 实现」。`SyncManager` 落 `:core:sync`（Layer 1），封装 `SyncBackend` + 离线队列 + LWW/Union 合并（§7.6/§7.7），对 feature 暴露挂起 API；它不是 §3.1 遗漏的新模块，而是 `:core:sync` 内的协调类，已补入该模块职责。

---

## 十、Gradle 基础设施

### 10.1 版本管理（P2-H：不在文档硬编码版本）

> **所有版本号以 `android/gradle/libs.versions.toml` 为唯一真相来源。** 本文档不再列举具体版本，避免漂移。截至本版仓库 catalog 已包含 agp / kotlin / compose-bom / room / koin / ktor / coil / paging / navigation / lifecycle / datastore / markwon / jsoup（EPUB 解析）/ mupdf / ink / 测试栈等条目。**nanohttpd 随 EPUB 去 WebView 移除**（E1，已从 `libs.versions.toml` 删除）。新增依赖先进 catalog，再在模块 `build.gradle.kts` 引用。

### 10.2 Convention Plugins（`build-logic/convention/`）

| Plugin | 施加 | 规则 |
|--------|------|------|
| `ReadflowAndroidLibraryPlugin` | `:core:calibre`/`:core:database`/`:core:prefs` 等 | `com.android.library` + kotlin。`compileSdk=36, minSdk=26, targetSdk=36, jvmTarget=17` |
| `ReadflowComposePlugin` | `:core:ui` + 三 feature | 继承上 + `kotlin-compose` + `kotlin-serialization` + Compose BOM + 测试 |
| `ReadflowFeaturePlugin` | 三 feature | 继承 Compose + `lifecycle-viewmodel-compose` + `navigation-compose` |
| `ReadflowRenderPlugin` | `:render:*` | 继承 AndroidLibrary（**不应用 Compose**）+ `coroutines-android` + `kotlinx-serialization` |

> 现状：`build-logic/` 在当前仓库**尚未创建**（Phase 1 step 2 任务）。在它落地前，各模块直接在 `build.gradle.kts` 重复配置；落地后收敛。

### 10.3 settings.gradle.kts（分阶段 include，P2-H / P6）

模块按 phase 渐进 `include`，与实际开发进度对齐，而非一次性声明 22 个。仓库 `settings.gradle.kts` 已落 `readflow.phase` property 驱动的 `phaseInclude()`（默认 phase=1）；`rootProject.name = "LinReads"`，仓库已含 `maven("https://maven.ghostscript.com")`（MuPDF）。

**强制机制（P6 — 不靠注释）**：v3/早期 v4 用「注释掉 include」表达分阶段，忘了取消注释会编译失败且无提示。v4 改用 Gradle property `readflow.phase`（`gradle.properties` 或 `-Preadflow.phase=2`）驱动声明式 include，模块归属 phase 显式可查。**phase 归属规则（F9 修正）：一个模块必须与其全部编译期依赖处于同一或更早 phase，否则 `-Preadflow.phase=N assembleDebug` 在解析依赖时报 `project not found`。** 早期 v4 把 `:features:library`（Layer 6 Compose feature）放 phase1，却把它必依赖的 `:core:ui`(Layer 5) / `:core:database`(Layer 1) 放 phase2，phase1 编译必炸——这恰好打脸本机制的卖点。修正后 phase1 自成可编译闭环：

```kotlin
// settings.gradle.kts
val phase = (extra.properties["readflow.phase"] as String?)?.toInt() ?: 1

fun phaseInclude(minPhase: Int, vararg paths: String) {
    if (phase >= minPhase) paths.forEach { include(it) }
}

// Phase 1 = 基建期：书库浏览 + 本地导入 + 最近阅读 + 进度本地存储（尚不渲染正文）。
// library 的依赖（ui/database）必须同期在场（F9）。
phaseInclude(1, ":app", ":core:model", ":core:calibre", ":core:prefs",
                ":core:sync", ":core:database", ":core:ui",
                ":extensions:api", ":features:library")
// Phase 2 = 渲染引擎 + reader，用户验收 Phase A「本地阅读闭环」在此达成。
phaseInclude(2, ":render:api", ":render:epub", ":render:pdf", ":render:txt",
                ":render:md", ":render:animate",
                ":features:reader", ":features:settings")
phaseInclude(3, ":render:mupdf", ":ink",
                ":extensions:tts", ":extensions:stats", ":extensions:opds")
```

> 构建 Phase 1 是**基建期**，不渲染正文（决断 1-B）：它交付书库浏览 / 本地导入 / 最近阅读 / 进度本地存储，让 `-Preadflow.phase=1 assembleDebug` 自成可编译、可验收的闭环。用户验收 Phase A「能打开本地书并继续阅读」在构建 Phase 2（reader + 引擎就位）达成。`:core:database`（Room，最近阅读/进度需结构化查询与排序，见 §7.8）与 `:core:prefs`(DataStore，只存 baseUrl/字号/主题/engine override) 职责不混，二者均在 phase1。

好处：当前 phase 一目了然、缺模块时报「project not found」而非神秘编译错、CI 可对每个 phase 跑 `-Preadflow.phase=N assembleDebug` 验证该阶段自洽。

### 10.4 `:core:model` 特殊配置

`kotlin("jvm")` + `kotlin-serialization`，仅依赖 `kotlinx-serialization-json`。纯 JVM library，零 Android 依赖，未来可零成本迁 KMP `commonMain`。

## 十一、迁移路径

> 阶段顺序对齐第二节「用户轻量验收顺序」：先无账号本地阅读闭环，再 Calibre，再阅读质量，再数据出口，最后精品增强。

### Phase 1：基础模块 + 基建闭环（不渲染正文 — 决断 1-B）

产出：`:core:model`、`:core:calibre`、`:core:prefs`、`:core:sync`、`:core:database`、`:core:ui`、`:extensions:api`、`:features:library`、`:app`（与 §10.3 phase1 include 一致——`:features:library` 的依赖 `:core:ui`/`:core:database` 必须同期在场，F9）。

> **范围界定（决断 1-B）**：Phase 1 是**基建期**，交付书库浏览 + 本地文件导入登记 + 最近阅读列表 + 进度本地存储，**不含 reader 与任何渲染引擎**（在 Phase 2）。故 Phase 1 结束态是「能浏览/导入/管理书目并持久化进度记录」，**不是**「能打开正文阅读」——后者是用户验收 Phase A，落在构建 Phase 2。这样 Phase 1 自成可编译、可验收的闭环，不假装能阅读。

1. `gradle/libs.versions.toml`（已存在，维护）。
2. `build-logic/` + convention plugins：phase1 至少先落 `ReadflowAndroidLibraryPlugin` + `ReadflowComposePlugin`（render/feature 两个后补，别让「待创建」跨越 phase1，F-M6）。
3. `settings.gradle.kts` 分阶段 include。
4. `:core:model`：全部纯数据类型（含 §7.1 `LocatorStrategy` 带 payload sealed、§7.3 可序列化 `ReadflowError`、`DownloadedAsset`、含同步元数据的 `Bookmark`/`ReadingProgress`）。
5. `:core:calibre`：`CalibreClient` → `CalibreClient` + `CalibreRepository`，类型提取到 `:core:model`。
6. `:core:prefs`：`SettingsRepository`（含 `engineOverrides: StateFlow<Map<BookFormat,String>>`、`device_id` 持久化 F6、字号/主题/baseUrl 设置项）。
7. `:core:database`：5 表（§7.8）+ DAO；承接「最近阅读 + 进度本地保存」结构化查询。
8. `:core:sync`：`SyncBackend` + `NoOpSyncBackend` + `SyncManager`（F10）。
9. `:core:ui`：Material3 主题 + 共享 composable（`:features:library` 依赖）。
10. `:extensions:api`：`BookSource`（返回 `DownloadedAsset`）+ `Extension` SPI + `ReaderEventBus`。
11. **基建闭环**：`LocalFileBookSource` + `ACTION_VIEW`/`ACTION_SEND`/SAF 导入登记 + 最近阅读列表 + 进度记录本地持久化，不依赖 Calibre/账号/网络。

**结束态**：`-Preadflow.phase=1 ./gradlew assembleDebug` 通过；安装后能浏览书库、导入本地书登记到书架、查看最近阅读、进度记录持久化（打开正文阅读在 Phase 2）。

### Phase 2：渲染引擎 + 阅读器（Phase B + C）

9–20：`:render:api`（`ReaderEngine` + `EngineDescriptor` + Registry + `PageTransitionHost` + `PageTransitionHostFactory`）→ `:render:epub`/`:render:pdf`/`:render:txt`/`:render:md`/`:render:animate` → `:features:reader`（含 `ReaderRootLayout`）/`:features:settings` → `:app`（Navigation host + Koin）。`:core:database`/`:core:ui` 已在 phase1 就位。Calibre 接入向导 + 自动探测。

**结束态**：浏览 Calibre 书库、打开 EPUB/PDF/TXT/MD、翻页、调字号/主题；系统自动选引擎；首屏/翻页/内存达预算；TalkBack smoke 通过。

### Phase 3：精品增强（Phase D + E）

数据出口（`LinReads Backup` 导出/恢复、离线缓存）→ `:extensions:tts`/`:extensions:stats`/`:extensions:opds` → `:ink` 实现（`CanvasView` + `InProgressStrokesView` + 触摸路由 + `InkToolbar`）。DOCX/CBZ 已由 `ADR-MuPDF-License` 延期，不再作为当前 Phase 3 默认下一项。每能力独立 ADR + 权限说明 + 包体积预算 + 关闭路径。

---

## 十二、架构裁决记录

### 12.1 v3 继承的 Root-cause Resolutions（R1–R12）

R1 `ReaderState` 不含 View 引用；R2 `Locator` 唯一定义在 `:core:model`；R3 `BookSource` 用 String bookId + `ReadflowResult` + 全 suspend；R4 模块细粒度（v4 为 22）；R5 `InkAnchor` 在 `:core:model`；R6 ViewPager2 替代自研 AnimationEngine；R7 `TransitionType` SCREAMING_SNAKE_CASE；R8 EPUB **改原生重排去 WebView**（外部对标修订，见 §12.3 新 ADR）；R9 `render:api` 是接口层不定义数据类型；R10 Registry 支持用户 override；R11 视图生命周期回调；R12 ExtensionContext 沙箱完整。

### 12.2 v4 新增裁决（两轮审计修复）

| # | 裁决 | 依据 |
|---|------|------|
| **V1** | `ReadflowError` 纯数据 + `ReadflowException` 运行时分层 | P0-A：`@Serializable` 不能套 `Throwable`，否则 `ReaderState` 序列化与进程恢复链整体失效 |
| **V2** | `ReaderEngine` 带线程契约（调用方 Main，引擎内部切 IO） | P0-C：主线程渲染约束、PdfRenderer 非线程安全 |
| **V3** | Registry 用 `EngineDescriptor` 懒加载 + override 只读快照 | P1-D 线程安全 + P1-E 冷启动预算 |
| **V4** | `SyncBackend` 移出 Layer 0 至 `:core:sync`；模型补 `updatedAt`/`deviceId`/`isDeleted` | P1-F：Layer 0 纯度 + LWW/Union 可实现性 |
| **V5** | 扩展发现统一 Koin multibind，删 ServiceLoader | P1-G：机制重叠 + Android R8 裁剪风险 |
| **V6** | `PageTransitionHost` 拆分翻页宿主与文档渲染 | R1-3：单 child document_host 与 ViewPager2 矛盾 |
| **V7** | `BookSource.download()` 返回 `DownloadedAsset` | R1-2：`java.io.File` 削弱跨平台/SAF/导出可携带性 |
| **V8** | MuPDF DOCX/CBZ 为 optional，不进 base APK | 用户安装轻 + license 风险隔离 |
| **V9** | ~~nanohttpd loopback + path token~~ → **EPUB 去 WebView 后整体移除**（见 E1） | P2-I 失效：无 WebView 即无本地 HTTP server 泄露面 |
| **V10** | TXT 分页对齐字符边界，`ByteOffset` 落字符起始 | P2-J：多字节编码半字符乱码 |
| **V11** | 用户轻量契约为实现前 P0 gate | Round 1 P0 |
| **V12** | 文档不硬编码版本/模块清单，以 catalog + 分阶段 include 为准 | P2-H：文档漂移 |
| **E1** | **EPUB 改原生重排（jsoup→AnnotatedString），去 WebView/epub-ts/nanohttpd/CSP/JS bridge/CFI** | 外部对标 P1-EXT-1/2 + 用户裁决：WebView 是 Web「即插即用」路线惯性，安卓应原生；Readium 3.x 已弃本地 HTTP server，epub-ts 兜底（裸 epubjs）共享同一废弃渲染模型而无效 |
| **E2** | `Locator` 增 `progression`/`totalProgression` 为三端同步主键 | P2-EXT-3：对标 Readium/KOReader，进度键不依赖对字号敏感的 CFI |
| **E3** | 进度同步可选兼容 KOSync（`KoSyncBackend`） | P2-EXT-4：复用 koreader-sync-server 生态，接口已支持近零成本 |

### 12.2b 复审修订（P1–P8 逐条处置）

针对一轮设计复审提出的 8 项，逐条处置如下（已落档 5 项，2 项不成立，1 项维持）：

| # | 处置 | 落点 |
|---|------|------|
| **P1** | ✅ 已修。`PageTransitionHost` 补 `next()/previous()`/`setOffscreenPageLimit()`/`setOnPageSettled()` + 挂载协议注释；新增 `ReaderEngine.pagingKind` 让 `ReaderRootLayout` 在接口层判定分页/连续，不依赖具体实现 | §5.1、§5.6 |
| **P2** | ✅ 已修。明确两层恢复：`SavedStateHandle`+`ReaderState`(语义位置，充分条件，JSON String 中转见 F4) 与 `Engine.saveState()`(实现私有加速缓存，写本地文件见 F5，可丢)；给出 ViewModel 恢复路径与「为何不全塞 ReaderState」理由 | §7.2 |
| **P3** | ✅ 已修。v4 此前只在原则 3 提 MVI 概念、未落 `ReaderIntent` 契约（「9 个 action」是 v3 旧物，v4 并无）；现正式定义含 OpenToc/SelectTocItem/Search/文字选择/高亮的完整 Intent | §4.5 |
| **P4** | ⛔ 不成立（已过时）。该项针对「epub-ts + WebView 验证 gate + 回退裸 epubjs」，但 E1 裁决后 Android EPUB 已去 WebView 改原生重排，无 epub-ts、无该 gate、无 epubjs 回退。原生重排自有实现风险 gate（§12.3 末条），与此项无关 | — |
| **P5** | ✅ 已修。`ExtensionContext` 增 UI 态字段（`isUiVisible`/`zoomLevel`/`panOffset`/`theme`/`fontSize`）防御性使用约定；稳定语义信号走 `ReaderEventBus` | §8.1 |
| **P6** | ✅ 已修。分阶段 include 改 `readflow.phase` Gradle property 强制声明，弃「注释/取消注释」 | §10.3 |
| **P7** | ➖ 维持。`build-logic/` 未创建已由 §10.2 如实标注为 Phase 1 step 2 任务，是已知状态非隐藏缺陷；落地前各模块重复配置可接受 | §10.2（不改） |
| **P8** | ⛔ 不成立。`Platform-Android.md` 第 1 行已是 `⚠️ DEPRECATED` 并有指向 v4 的废弃横幅，与 §12.12 声明一致，无矛盾 | — |

### 12.3 ADR-EPUB-Engine（决策：原生重排，去 WebView）

- **背景**：v3/早期 v4 沿用 Web 端的 WebView + epubjs/epub-ts 路线。外部对标（`docs/audit/external-benchmark-audit-2026-06-19.md`）发现两个 P1 路线问题：(1) EPUB 内容服务用 nanohttpd 本地 HTTP server，而 Readium 3.x 已主动移除本地 server（PR #259）改同源拦截；(2) epub-ts 的失败兜底「裸 epubjs」与 epub-ts **共享 iframe+CSS-multicolumn 渲染模型**且 epubjs 已废弃（#1406），兜底无效。
- **用户裁决**：WebView 是 Web 端「单文件即插即用」应急路线被带进安卓的惯性产物；安卓与 Web 是不同开发路线，EPUB 应走原生。
- **裁决**：**Android EPUB 改自研原生重排**——`ZipFile` + jsoup 解析 XHTML → 内部 `ReaderItem` → Compose `AnnotatedString` 渲染（管线见 §5.5）。参考 Myne `EpubParser`（Apache-2.0）与 Book's Story 路线。**不再引入 WebView / epub-ts / nanohttpd / CSP / JS bridge / CFI。**
- **跨端关系**：与 Web 端（仍 epubjs/WebView）仅在 `shared/api` 类型契约层对齐，不再要求引擎层一致。三端进度同步用 `totalProgression`（§7.1 E2），不用 CFI。
- **必须接受的取舍**：放弃多栏/float 绕排/@font-face 精确字体/fixed-layout/JS/复杂表格；复杂排版书走样、fixed-layout EPUB 基本不支持。换取：无 WebView 成本与远程内容执行面、Ink/选择/触摸与 PDF/TXT 同构、Hybrid 架构自洽。长期成本是「不规范 XHTML」解析器维护（Book's Story #42 前车之鉴）。
- **实现风险 gate（Phase 2）**：在真实 EPUB 集（含中英混排、图文混排、嵌套列表、表格）上验证：章节解析正确、AnnotatedString 渲染按 §5.5 降级矩阵保真可接受、跨字号锚点（spine+字符偏移）稳定、惰性解析+LRU 大书内存达 §12.8 预算。Phase 1 先做连续滚动（`LazyColumn`，`pagingKind=CONTINUOUS`），分页（per-page ComposeView 经 ViewPager2 host，`TextLayoutResult` 测量切页）作 Phase 2 增强，**不在单 ComposeView 内套 HorizontalPager**（F1）。
- **取代**：本 ADR 取代 `decisions-2026-06-18.md` 决策 1（无平台限定的「默认 epubjs」，仅适用 Web）与早期 v4 的「epub-ts + WebView 验证 gate」表述。

### 12.2c 全维度审计修订（F1–F14 落点索引）

判据=用户侧轻量（详见摘要表「判据声明」）。下表为 `docs/audit/v4-full-dimension-audit-2026-06-18.md` 中「替用户兜底」类发现的落点；「替开发省工」类（模块合并 M-4、扩展 YAGNI D-5、测试削减 X-7、KMP X-8）按判据**不采纳**。

| # | 一句话 | 落点 |
|---|--------|------|
| F1 | EPUB 分页统一 ViewPager2 host，删双重分页矛盾 | §4.2、§5.5 步骤3、§12.3 |
| F2 | ComposeView 的 ViewTree*Owner 挂载契约 | §4.1 |
| F3 | totalProgression 三端统一计算口径 | §7.1 |
| F4 | @Serializable→SavedStateHandle 走 JSON String 中转 | §7.2 |
| F5 | Engine 加速缓存写本地文件，避 TransactionTooLarge | §5.1、§7.2 |
| F6 | deviceId 来源 + LWW 时钟漂移 tie-break/进度回退护栏 | §7.7 |
| F7 | InkAnchor.Text 弃 cssSelector 改 spine+element+charOffset | §6.3 |
| F8 | ReaderEngine 补 search()+supportsSearch | §5.1 |
| F9 | phase1 include 边界修正（ui/database 提前） | §10.3 |
| F10 | PageTransitionHostFactory/SyncManager 模块归属 | §3.2、§9.3 |
| F11 | EPUB 解析安全：XML 炸弹/图片 OOM/远程回连 | §12.6、§5.5 步骤6 |
| F12 | network_security_config 放行全部 RFC1918 | §12.6 |
| F13 | 无障碍补 LinkAnnotation+semantics 机制 | §12.7 |
| F14 | EPUB 内存改惰性解析 LRU + PSS 口径 | §12.8 |
| R-6 | pagingKind 改 StateFlow，支持运行时切模式 | §5.1、§5.6 |
| R-8 | TXT 编码探测改用成熟库 | §5.4 |
| R-3 | ReaderItem→渲染降级矩阵 | §5.5 |
| R-7 | pinch 预览+ACTION_UP 提交重排 | §4.4 |

### 12.4 ADR-MuPDF-License（DOCX/CBZ optional，2026-06-23 裁决）

详见 `docs/audit/adr-mupdf-license-2026-06-23.md`。结论：当前纯阅读回填不启用 AGPL MuPDF；base APK 必须保持 MuPDF-free；任何 MuPDF-linked binary 都必须先完成 AGPL 合规或商业授权。DOCX 因当前官方资料未显示为 MuPDF Core 标准输入而延期；CBZ 虽可由 MuPDF 支持，但本轮也延期，未来优先评估无 AGPL 的 first-party ZIP image pager。

### 12.5 关键技术选型

EPUB→原生重排（jsoup→AnnotatedString，无 WebView）；PDF→系统 PdfRenderer；DOCX/CBZ→当前延期（MuPDF 不进 base，未来另行 ADR 更新）；MD→Markwon Spannables；TXT→RecyclerView+Paging3+FileChannel；翻页→PageTransitionHost(ViewPager2)；DI→Koin；HTTP→Ktor；序列化→kotlinx.serialization；同步(P1)→NoOpSyncBackend（可选 KoSync 互通）。

### 12.6 安全 / 权限矩阵（R1）

- **网络**：`network_security_config.xml` 放行**全部 RFC1918 私有段** cleartext（`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`，覆盖各类家庭/办公路由器与 Calibre 部署），其余强制 HTTPS（F12：早期仅放 `192.168/16` 会让 10.x/172.16 网段用户连不上 LAN Calibre 且难自查，与 Phase B「失败可理解」冲突）。向导探测阶段按用户实际 baseUrl 校验网段并给出明确提示。
- **EPUB 解析安全（原生重排，F11/X-1/X-2/X-3）**：无 WebView/JS 执行面，但 XHTML/OPF/NCX 是文件提供方完全可控的 XML，攻击面转移到解析侧，强制项：
  - **XML 解析炸弹**：OPF/NCX 用**关闭 DTD/外部实体**的解析器（防 XXE / billion-laughs 实体展开）；XHTML 解析设**嵌套深度上限**（如 ≤ 512 层，防超深 `<div>` 嵌套触发 `StackOverflowError`）；解析在可 `catch StackOverflowError`/`OutOfMemoryError` 的隔离作业里进行，失败降级为「该书解析异常」而非崩溃整个 App。
  - **zip 健壮性**：防 **zip slip**（拒绝解压路径穿越 `../`）、限制解压总体积/单文件大小/条目数（防 zip bomb）。
  - **图片解码**：先 `inJustDecodeBounds` 读尺寸 → 超像素上限降采样或拒绝（见 §5.5 步骤 6，防恶意大图 OOM）。
  - **内嵌远程资源**：**默认不自动加载** EPUB 内任何远程 URL（`<img src="http://…">`、CSS `@import`、`<link>`），只渲染包内资源——避免离线阅读器隐性回连暴露 IP/阅读行为、LAN 场景探测内网（SSRF/隐私指纹）；远程资源需用户显式开启。用户**主动点击**的外部链接才交系统浏览器并二次确认（与自动加载区分）。
- **凭据**：Calibre / 同步 API key 用 `EncryptedSharedPreferences`，可查看/删除/重新授权。
- **权限矩阵**：基础阅读不请求危险权限；导入本地文件走 SAF 单次授权（不申请广域存储）；Calibre 仅用户添加服务器后访问 LAN；同步默认关、凭据本地加密；TTS/通知用户启用时再请求。

### 12.7 无障碍（含 ReaderRootLayout smoke list，Round 1 P2）

TalkBack contentDescription（按钮/书架条目/翻页区域）；尊重 `Configuration.fontScale`（独立于 EPUB 内部字号）；`ThemeMode.HIGH_CONTRAST`；键盘/方向键翻页。

**原生重排文本的无障碍实现机制（F13 — 声明目标须附机制）**：`AnnotatedString` 的 TalkBack 语义不会自动产生，Compose 默认不会把内嵌可点击 span 暴露成独立可聚焦节点，故约定实现形态：

- **链接**：用 Compose `LinkAnnotation`（Compose 1.7+，确认 catalog 版本满足）承载，使其成为可聚焦、可激活的无障碍节点，而非 `Modifier.clickable` 包整段。
- **朗读顺序**：等于 `ReaderItem` 重排流顺序（解析即朗读序），分页模式下按当前页内 ReaderItem 顺序朗读；每个段落级 ReaderItem 渲染为带 `Modifier.semantics` 的节点，保证 TalkBack 线性遍历。
- **图片**：`Image` 的 `contentDescription` 取 XHTML `alt` 属性，无 `alt` 时标记为 decorative（`null`）跳过朗读。

**Hybrid View smoke list**：TalkBack 读出书名/章节/进度；左右翻页区有 action label；Toolbar 显隐时焦点不丢；EPUB 原生重排文本可被 TalkBack 线性朗读、`AnnotatedString` 链接可聚焦。

### 12.8 性能预算 + 测量 gate（Round 1 P2）

| 指标 | 目标 | 测量口径 |
|------|------|---------|
| 冷启动 | < 2s to first paint | adb am start -W TotalTime |
| EPUB 打开 (1MB) | < 1s | openBook → 首屏 paint |
| PDF 打开 (10MB) | < 2s | 同上 |
| 翻页延迟 | < 50ms frame-paced | Choreographer 帧间隔 |
| 内存峰值 (EPUB) | < 150MB | 打开 5MB EPUB 后 **Java heap PSS 峰值**（F14 放宽并定义口径） |
| 内存峰值 (PDF + ink) | < 200MB | 同上口径 |
| base APK | < 25MB | APK Analyzer（**不含 MuPDF .so**） |

**EPUB 内存口径与惰性策略（F14 / X-5）**：早期「< 100MB」既无测量口径、又与「全书解析成 ReaderItem 序列常驻」自相矛盾（中等图文书易超）。修正：(1) **按 spine 章节惰性解析 + LRU 驱逐**，只常驻当前 + 预取 ±1 章的 ReaderItem，已读章节可丢弃重解析，使内存与书体积**解耦**；(2) 预算定义为「打开 5MB EPUB、连续翻页 50 页后的 Java heap PSS 峰值 < 150MB」，固定样本可复现。图片按 §5.5 受控解码计入。

**测量 gate**：每 Phase 末产出 APK Analyzer + 冷启动 + 首开 + 内存峰值记录；预算分 base / full feature；自研与第三方引擎同台对比，以用户指标裁决。

### 12.9 大屏 / 折叠屏

平板横屏 (sw≥600dp) 双页；折叠屏 `WindowSizeClass` 切单/双页不丢位置；多窗口自适应；支持从文件管理器拖拽 `.epub`/`.pdf` 打开。

### 12.10 KMP 策略

Phase 1 不做 KMP（HarmonyOS 用 ArkTS）。`:core:model` 纯 JVM 预留 commonMain 迁移；共享走类型契约（JSON Schema from `shared/api/calibre-contract.ts`）非代码共享。评估点不早于 2027。

### 12.11 测试策略

`:core:model` Unit（序列化/枚举/`ReadflowError` 往返）；`:core:calibre` Unit+Ktor MockEngine；`:core:database` Room in-memory + Turbine；`:render:api` Registry 选择逻辑（含 override 快照、descriptor 懒加载）；`:render:txt` 字符边界往返（混合编码样本）；`:render:*` Instrumentation；`:features:*` ViewModel + Compose Testing；`:ink` Instrumentation。Mock 策略：prefer fakes（`FakeCalibreBookSource` / `FakeReaderEngine` / `NoOpSyncBackend`）。

### 12.12 废弃文档

`android-architecture-v3.md`、v2、v2-addendum、`Platform-Android.md`（wiki）自本文档生效起为历史参考；`decisions-2026-06-18.md` 决策 1 由 §12.3 ADR 取代。

---

> **文档维护**：本文档为 LinReads Android v4 唯一权威来源。代码与文档不一致时以文档为准并报告。版本号一律以 `android/gradle/libs.versions.toml` 为准。
>
> **关联**：`shared/api/calibre-contract.ts`、`.claude/skills/linreads-epub/SKILL.md`、`.claude/skills/linreads-sync/SKILL.md`、`.claude/skills/linreads-dev/SKILL.md`、两轮审计报告 `docs/audit/android-v3-framework-audit*.md`。
