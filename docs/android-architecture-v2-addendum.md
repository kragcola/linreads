# Android Architecture v2 — 补全附录

> 日期：2026-06-18
> 对应 BACKLOG P2（数据模型）+ P3（架构缺口）

---

## P2.1 ReaderState 补全

当前 v2 文档中 ReaderState 仅有占位 `data class ReaderState(…)`。完整定义：

```kotlin
// core/model/src/main/kotlin/dev/readflow/core/model/ReaderState.kt
package dev.readflow.core.model

// Locator 定义在 core:model（Layer 0），无跨层依赖
// render:api 从 core:model import Locator

data class ReaderState(
    val bookId: String,
    val bookMeta: BookMeta? = null,
    val format: BookFormat = BookFormat.UNKNOWN,
    val loadingState: LoadingState = LoadingState.Idle,
    val currentLocator: Locator? = null,   // Locator ∈ core:model
    val totalPages: Int = 0,
    val currentPageIndex: Int = 0,
    val fontSize: Int = 18,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val zoomLevel: Float = 1.0f,
    val panOffset: Offset = Offset.Zero,
    val isUiVisible: Boolean = true,
    val transition: TransitionType = TransitionType.SLIDE,
    val error: ReadflowError? = null,
)

data class Offset(val x: Float = 0f, val y: Float = 0f)

sealed interface LoadingState {
    data object Idle : LoadingState
    data object Loading : LoadingState
    data class Error(val error: ReadflowError) : LoadingState
    data object Loaded : LoadingState
}

enum class TransitionType { SLIDE, CURL, FADE, NONE }
```

*注：ReaderState 位于 `:core:model`（Layer 0，零 Android 依赖）。Locator 也定义在 `:core:model`，消除 `core:model → render:api` 循环依赖。`render:api` 从 `core:model` import Locator。*

---

## P2.2 InkBrush 类型

```kotlin
// ink/src/main/kotlin/dev/readflow/ink/InkBrush.kt
package dev.readflow.ink

// 使用 android.graphics.Color（View 系统），非 Compose Color
// :ink 模块不含 Compose 依赖

sealed class InkBrush(
    open val color: Int,  // android.graphics.Color int
    open val width: Float  // dp
) {
    data class Pen(
        override val color: Int = android.graphics.Color.BLACK,
        override val width: Float = 2f,
    ) : InkBrush(color, width)

    data class FountainPen(
        override val color: Int = android.graphics.Color.BLACK,
        override val width: Float = 2f,
        val pressureSensitivity: Float = 1.0f,
    ) : InkBrush(color, width)

    data class Highlighter(
        override val color: Int = 0x80FFFF00.toInt(),
        override val width: Float = 12f,
    ) : InkBrush(color, width)

    data class Eraser(
        override val width: Float = 20f,
    ) : InkBrush(android.graphics.Color.TRANSPARENT, width)
}
```

参考 episteme 的 5-tool ink 系统（Pen/FountainPen/Pencil/Highlighter/Eraser），LinReads Phase 1 仅定义接口，Phase 2 实现。

---

## P2.3 ExtensionSettings

```kotlin
// extensions/api/src/main/kotlin/dev/readflow/extensions/api/ExtensionSettings.kt
package dev.readflow.extensions.api

import kotlinx.serialization.Serializable

@Serializable
data class ExtensionSettings(
    val enabledExtensions: Set<String> = emptySet(),
    val tts: TtsConfig = TtsConfig(),
    val stats: StatsConfig = StatsConfig(),
)

@Serializable
data class TtsConfig(
    val enabled: Boolean = false,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val language: String = "zh-CN",
)

@Serializable
data class StatsConfig(
    val enabled: Boolean = false,
    val dailyGoalMinutes: Int = 30,
)
```

存储在 DataStore（`core:prefs`），通过 `SettingsRepository.extensionSettings: Flow<ExtensionSettings>` 暴露。

---

## P2.4 DownloadStatus 枚举

```kotlin
// core/model/src/main/kotlin/dev/readflow/core/model/DownloadStatus.kt
package dev.readflow.core.model

enum class DownloadStatus {
    /** 未下载，仅在 Calibre LAN 可用 */
    NOT_DOWNLOADED,
    /** WorkManager 正在下载 */
    DOWNLOADING,
    /** 已下载到本地，离线可用 */
    DOWNLOADED,
    /** 下载失败（含错误信息） */
    FAILED,
}
```

Room `books` 表新增字段：
- `downloadStatus: String`（枚举名存储）
- `localFilePath: String?`（下载后填 `context.filesDir/books/<bookId>.<format>`）

---

## P2.5 SyncBackend 接口（Phase 1 no-op）

```kotlin
// core/model/src/main/kotlin/dev/readflow/core/model/SyncBackend.kt
package dev.readflow.core.model

interface SyncBackend {
    val backendId: String
    val isAvailable: Boolean

    suspend fun pushProgress(bookId: String, locator: Locator): Result<Unit, ReadflowError>
    suspend fun pullProgress(bookId: String): Result<Locator?, ReadflowError>
    suspend fun pushBookmark(bookId: String, bookmark: Bookmark): Result<Unit, ReadflowError>
    suspend fun pullBookmarks(bookId: String): Result<List<Bookmark>, ReadflowError>
}

/** Phase 1 no-op 实现 —— 所有操作返回 Unit */
class NoOpSyncBackend : SyncBackend {
    override val backendId = "noop"
    override val isAvailable = false
    override suspend fun pushProgress(bookId: String, locator: Locator) = Result.success(Unit)
    override suspend fun pullProgress(bookId: String) = Result.success(null)
    override suspend fun pushBookmark(bookId: String, bookmark: Bookmark) = Result.success(Unit)
    override suspend fun pullBookmarks(bookId: String) = Result.success(emptyList())
}
```

Phase 2 替换为：
- **主路径**：`KorroSyncBackend` — 对接 korrosync REST API (`PUT /syncs/progress`, `GET /syncs/progress/{document}`)
- **兜底**：`WebDavSyncBackend` — 进度 JSON 文件 ↔ WebDAV 目录

---

## P2.6 BookSource 接口（CalibreClient → 接口提取）

```kotlin
// extensions/api/src/main/kotlin/dev/readflow/extensions/api/BookSource.kt
package dev.readflow.extensions.api

import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.ReadflowError
import java.io.File

interface BookSource {
    val sourceId: String
    val sourceName: String

    suspend fun search(query: String, offset: Int = 0, limit: Int = 100): Result<List<BookMeta>, ReadflowError>
    suspend fun getMetadata(bookId: String): Result<BookMeta, ReadflowError>
    suspend fun getDownloadUrl(bookId: String, format: String): Result<String, ReadflowError>
    suspend fun getCoverUrl(bookId: String): Result<String, ReadflowError>

    /** 下载书籍到本地（Phase 1 实现，使用 WorkManager） */
    suspend fun download(bookId: String, format: String): Result<File, ReadflowError>

    /** 查询本地下载状态 */
    suspend fun getDownloadStatus(bookId: String): DownloadStatus
}
```

现有 `CalibreClient` 改为 `CalibreBookSource : BookSource`。未来 `OpdsBookSource`、`LocalFileBookSource` 各自实现。

---

## P3.1 离线书籍缓存策略

### 默认行为：智能 LRU 缓存

- **最近读过的 5 本书**自动缓存（可配置，`SettingsRepository.cacheLimit`）
- 用户打开一本书 → 后台 `WorkManager` 下载到 `context.filesDir/books/<bookId>.<format>`
- Room `books` 表更新 `downloadStatus = DOWNLOADED` + `localFilePath`
- 缓存满时，删除**最久未读**的书（LRU），保留 `books` 元数据行但清除文件
- 书架 UI 角标：「已下载 ✓」或空白（仅 LAN 可用）

### 手动下载

- 书架每本书有「下载」按钮（A 方案）
- 下载进度通过 `WorkInfo.State` → `DownloadStatus` Flow 暴露给 UI
- 可暂停/恢复/取消

### 离线模式

- 用户离开 LAN → 书架自动筛选 `downloadStatus = DOWNLOADED`
- 状态栏提示「离线模式 — 仅显示已下载书籍」

### 存储预算

- 默认上限 500MB（可配置）
- 单本书上限 50MB EPUB / 200MB PDF

---

## P3.2 同步后端策略

### Phase 1（实现）

- `SyncManager` 持有 `SyncBackend`（初始化为 `NoOpSyncBackend`）
- `ReaderViewModel` 保存进度后发 `BookProgressSaved` 事件 → `SyncManager` 接收
- `SyncManager` 丢弃事件（no-op 实现）。
- `SettingsScreen` 中「同步」区域灰显，标注「同步功能将在后续版本中提供」

### Phase 2（主路径 + 兜底）

```
主路径 (SyncBackend = KorroSyncBackend):
  LinReads Android → HTTP → korrosync (Rust, NAS/树莓派) → redb
  端点: PUT /syncs/progress, GET /syncs/progress/{document}

兜底 (SyncBackend = WebDavSyncBackend):
  LinReads Android → WebDAV → 进度 JSON 文件
  目录: <webdav>/linreads/sync/<deviceId>/progress.json
```

### 冲突解决

- LWW（Last-Write-Wins）：`updatedAt` 时间戳决胜
- 标注/书签：Union merge（不删除）

---

## P3.3 渲染模块补齐

### 最终渲染模块结构（共 6 个）

| 模块 | 格式 | 引擎 |
|------|------|------|
| `:render:api` | — | ReaderEngine 接口 + Locator + ReaderEngineRegistry |
| `:render:epub` | EPUB | WebView + epub-ts（`@likecoin/epub-ts`） |
| `:render:pdf` | PDF | Android PdfRenderer（系统 API） |
| `:render:txt` | TXT | TxtVirtualPager + RecyclerView + Paging3 |
| `:render:mupdf` | DOCX, CBZ | MuPDF JNI（`com.artifex.mupdf:fitz`） |
| `:render:md` | Markdown | Markwon（Spannable 原生渲染） |
| `:render:animate` | — | ViewPager2.PageTransformer（翻页动效） |

模块树从 19 增至 **21 个模块**。更新所有文档中的模块计数。

---

## P3.4 性能预算

| 指标 | 目标 | 测量方式 |
|------|------|---------|
| 冷启动 | < 2s (to first paint) | Android Studio Profiler / `systrace` |
| EPUB 打开 (1MB) | < 1s (to first page) | 手动计时 |
| PDF 打开 (10MB) | < 2s (to first page) | 手动计时 |
| 翻页延迟 | < 50ms (frame-paced) | `currentLocation()` 计时 |
| 内存峰值 (EPUB) | < 100MB | `dumpsys meminfo` |
| 内存峰值 (PDF + ink) | < 200MB | `dumpsys meminfo` |
| MuPDF .so | ~15MB | APK Analyzer |
| 总 APK 大小 | < 25MB | `./gradlew assembleDebug` + APK Analyzer |

---

## P3.5 测试策略

| 层 | 测试类型 | 框架 | 目标 |
|----|---------|------|------|
| `:core:model` | Unit | JUnit5 | 所有数据类、序列化、枚举 |
| `:core:calibre` | Unit + Integration | JUnit5 + MockK + Ktor MockEngine | Repository 方法、错误路径 |
| `:core:database` | Unit + Instrumentation | JUnit5 + Room in-memory + Turbine | DAO 查询、Flow 发射 |
| `:render:api` | Unit | JUnit5 | EngineRegistry 选择逻辑 |
| `:render:*` | Instrumentation | Compose Testing + AndroidJUnit4 | 引擎 createView、goTo |
| `:features:*` | Unit (ViewModel) + UI | JUnit5 + MockK + Turbine + Compose Testing | MVI 状态转换、UI 交互 |
| `:ink` | Instrumentation | AndroidJUnit4 | 触控路由、笔迹坐标变换 |

**Mock 策略**：Prefer fakes over mocks。`FakeCalibreBookSource` 返回预置 JSON，`FakeReaderEngine` 返回固定 Bitmap。

---

## P3.6 安全策略

### 网络安全

- `network_security_config.xml`：仅允许 `192.168.0.0/16` cleartext HTTP（LAN Calibre）
- 其他所有网络请求强制 HTTPS
- `AndroidManifest.xml`：`android:usesCleartextTraffic="true"` + `android:networkSecurityConfig="@xml/network_security_config"`

### WebView 安全（EPUB）

- JavaScript 启用（epub-ts 必需）
- **禁止** `file://` 访问（`setAllowFileAccess(false)`）
- CSP 策略：`Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; object-src 'none'`
- 不允许导航到 WebView 外的 URL（`setWebViewClient` 拦截）

### 凭据存储

- Calibre 用户名/密码：DataStore 存储，使用 `EncryptedSharedPreferences`（AndroidX Security）
- 同步后端 API key（Phase 2）：同上

---

## P3.7 无障碍策略

| 需求 | 实现 |
|------|------|
| TalkBack | 所有交互元素（按钮、书架条目、翻页区域）设 `contentDescription` |
| 字体缩放 | 尊重系统字体缩放设置（`Configuration.fontScale`），独立于 EPUB 内部字号 |
| 高对比度 | 提供「高对比度」主题（黑白），通过 `ThemeMode.HIGH_CONTRAST` 切换 |
| 键盘导航 | `DirectionalNavigationAdapter`（Readium 模式）— 空格/方向键翻页 |
| TTS | Phase 2 `extensions:tts` 模块，使用 Android TTS framework |

---

## P3.8 大屏/折叠屏

| 场景 | 行为 |
|------|------|
| 平板横屏 (sw >= 600dp) | 双页模式（`ViewPager2` 显示 2 页，翻页动效一次翻 2 页） |
| 折叠屏展开/折叠 | `WindowSizeClass` 检测 → 切换单/双页模式。不丢失阅读位置 |
| 多窗口/分屏 | 竖屏：单页。横屏：双页。`onConfigurationChanged` 自适应 |
| 拖拽 (Drag & Drop) | 支持从文件管理器拖拽 .epub/.pdf 到 LinReads 直接打开 |

---

## P3.9 KMP 策略声明

**Phase 1 不做 KMP。** 当前 HarmonyOS 端使用 ArkTS（非 Kotlin），与 Android 端语言不同。

**预留结构**：
- `core:model` 已设计为纯 Kotlin JVM library（`kotlin("jvm")`），无 Android import。未来可改为 KMP `commonMain` 零成本迁移
- `shared/api/calibre-contract.ts` 通过 JSON Schema codegen 生成 Kotlin + TypeScript 类型。HarmonyOS 端可从 JSON Schema 生成 ArkTS 类型
- 共享层策略：**类型契约共享**（JSON Schema），非代码共享（KMP）。三端各自实现 UI 层

**未来评估点**（不早于 2027）：
- Google 官方 Kotlin/KMP for Android + HarmonyOS 支持成熟度
- `compose-multiplatform` 对 HarmonyOS 的支持

---

_以上所有类型定义和策略描述补全到 v2 架构文档。BACKLOG P2 6/6 完成，P3 9/9 完成。_
