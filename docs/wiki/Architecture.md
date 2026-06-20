# Architecture

## 系统总览

```
┌────────────────────────────────────────────────────┐
│       Calibre Content Server (LAN :8080)           │
│  GET /ajax/search  /ajax/books  /get/<fmt>/<id>    │
└────────────────────────────────────────────────────┘
           │               │               │
      ┌────┘           ┌───┘           ┌───┘
      ▼                ▼               ▼
┌──────────┐    ┌────────────┐   ┌──────────────┐
│   Web    │    │  Android   │   │  HarmonyOS   │
│ React 18 │    │ Kotlin +   │   │ ArkTS +      │
│ epubjs*  │    │ Compose +  │   │ ArkUI        │
│ Vite     │    │ Ktor       │   │              │
└──────────┘    └────────────┘   └──────────────┘
```

`*` Web 当前实现使用 `epubjs`（WebView/JS 引擎）。**Android v4 不复用此路线**，EPUB 走自研原生重排（jsoup→AnnotatedString，去 WebView），见 v4 §12.3。

## 共享契约

`shared/api/calibre-contract.ts` 定义三端共用的 Calibre API 类型（`BookMeta`、`SearchResult`、`CalibreConfig`）。这是**唯一真相来源**：改 API shape 必须先改这个文件，再同步三端实现。

涉及三端共用类型改动的流程：
1. 改 `shared/api/calibre-contract.ts`
2. 同步 `web/src/services/calibre.ts`
3. 同步 `android/.../CalibreClient.kt`
4. 同步 `harmony/.../CalibreService.ets`

## 端架构对比

| 维度 | Web | Android | HarmonyOS |
|------|-----|---------|-----------|
| 语言 | TypeScript | Kotlin | ArkTS |
| UI 框架 | React 18 | Jetpack Compose | ArkUI |
| HTTP 客户端 | axios | Ktor | @ohos.net.http |
| EPUB 渲染 | epubjs 0.3.x（WebView/JS） | **原生重排（jsoup→AnnotatedString，去 WebView）** | 待定 |
| PDF 渲染 | iframe (浏览器内置) | PdfRenderer (系统) | 待定 |
| 本地存储 | IndexedDB | Room | relationalStore |
| 设置存储 | localStorage | DataStore Preferences | 待定 |
| DI 框架 | React Context | Koin | 无（手动） |
| 路由 | react-router-dom v6 | Navigation Compose 2.8+ | ArkUI Router |
| 状态管理 | useState/useReducer | StateFlow (MVVM) / MVI | @State/@Prop |
| 构建工具 | Vite | Gradle | DevEco Studio |

## Web 端架构

**路由：**
- `/library` → `Library.tsx`：书库列表，搜索/封面/点击进入阅读
- `/read/:id` → `Reader.tsx`：EPUB（epubjs）或 PDF（iframe）

**Calibre 代理（绕 CORS）：**
Vite dev 服务将 `/calibre/*` 代理到 `VITE_CALIBRE_URL`（默认 `http://localhost:8080`）。生产部署需在 nginx / CDN 层做同等配置。

**依赖关键版本：**

| 包 | 版本 | 说明 |
|----|------|------|
| react | ^18.3.1 | Concurrent features |
| epubjs | ^0.3.93 | 当前 Web EPUB renderer |
| react-router-dom | ^6.26.0 | SPA routing |
| axios | ^1.7.7 | HTTP client |

## Android 端架构

### 分层设计

```
┌──────────────────────── UI Layer ───────────────────────────┐
│  Compose + Navigation 2.8+（type-safe sealed routes）        │
│  Library / Settings  →  MVVM（StateFlow）                   │
│  ReaderScreen        →  MVI（Intent → State，单向数据流）    │
├──────────────────────── Render Layer ───────────────────────┤
│  ReaderEngine（interface）                                   │
│  ┌────────────────────┐  ┌──────────────┐  ┌────────────┐  │
│  │  EpubReflowEngine  │  │  TxtEngine   │  │  MdEngine  │  │
│  │  jsoup→Annotated   │  │  TxtVirtual  │  │  Markwon   │  │
│  │  Str（去 WebView） │  │  Pager+Pg3   │  │  Spannable │  │
│  └────────────────────┘  └──────────────┘  └────────────┘  │
│  ViewPager2.PageTransformer（SlideFade / Curl）             │
│  InkOverlay（Phase 2，androidx.ink 1.0.0）                   │
├──────────────────────── Data Layer ─────────────────────────┤
│  CalibreRepository（Ktor）  BookshelfRepository（Room）      │
│  SettingsRepository（DataStore Preferences）                 │
└─────────────────────────────────────────────────────────────┘
         ↕ Koin DI（模块注入，无注解处理器）
```

### 包结构

```
:app                         组装点，MainActivity + DI
:core:model                  纯 Kotlin 数据类型
:core:calibre                CalibreClient + CalibreRepository
:core:database               Room DB：Book / Progress / Annotation
:core:prefs                  DataStore SettingsRepository
:core:ui                     Compose 主题 + 共享组件
:render:api                  ReaderEngine + Registry
:render:epub                 原生重排（jsoup → AnnotatedString，无 WebView）
:render:pdf                  PdfRenderer
:render:txt                  TxtVirtualPager + Paging3
:render:mupdf                DOCX / CBZ via MuPDF JNI
:render:md                   Markwon
:render:animate              ViewPager2.PageTransformer
:features:library            LibraryScreen + ViewModel
:features:reader             ReaderScreen + ReaderViewModel（MVI）
:features:settings           SettingsScreen + ViewModel
:ink                         Phase 2（androidx.ink 覆盖层）
:extensions:*                TTS / Stats / OPDS
```

### 渲染引擎统一接口

```kotlin
interface ReaderEngine {
    val id: String
    val supportedFormats: Set<BookFormat>
    suspend fun open(uri: Uri): EngineSession
    fun createView(context: Context): View
    suspend fun goTo(locator: Locator)
    suspend fun currentLocator(): Locator
    suspend fun saveState(): Bundle
    suspend fun restoreState(state: Bundle)
    fun close()
}
```

### Reader MVI 契约

```kotlin
sealed interface ReaderIntent {
    data class Open(val bookId: Int, val format: BookFormat) : ReaderIntent
    object NextPage : ReaderIntent
    object PreviousPage : ReaderIntent
    data class GoTo(val position: ReaderPosition) : ReaderIntent
    data class SetTransition(val type: TransitionType) : ReaderIntent
    data class SetFontSize(val sp: Int) : ReaderIntent
    object ToggleInk : ReaderIntent
}

data class ReaderState(
    val book: Book? = null,
    val current: PageContent? = null,
    val prefetched: PageContent? = null,
    val position: Locator? = null,
    val transition: TransitionType = TransitionType.SLIDE,
    val fontSize: Int = 18,
    val isInkMode: Boolean = false,
    val error: String? = null,
)
```

## HarmonyOS 端架构

**目录结构：**
```
entry/src/main/ets
├── pages/Index.ets            # 主页，加载书库列表
├── components/BookList.ets    # 书单列表组件
└── services/CalibreService.ets # HTTP 客户端
```

当前状态：`Index.ets` 调用 `CalibreService.search()`，`BookList.ets` 已能渲染基础书单。`BASE_URL` 仍为默认内网地址 `192.168.1.1:8080`，需接 Settings 页持久化配置。

注：`dev.readflow`、`ReadflowApp` 等技术标识尚未代码级迁移；本文只收当前架构口径。

## 关键约束

1. **无云依赖**：书库在本地 Calibre，同步方案也必须离线友好
2. **CORS**：Calibre 服务端需开 `--cors` 或由客户端代理绕行
3. **格式支持**：EPUB/PDF 优先；MOBI/AZW3 依赖平台转换能力，不保证
4. **Chinese for UI, English for code**：用户界面字符串用中文，代码/注释/提交信息用英文

---

_详见各平台专页：_ [Platform: Web](Platform-Web.md) · [Platform: Android](Platform-Android.md) · [Platform: HarmonyOS](Platform-HarmonyOS.md)
