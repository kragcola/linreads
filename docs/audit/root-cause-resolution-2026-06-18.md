# 架构根因裁决

> 2026-06-18 · 一次性消解 12 项剩余问题

---

## 根因：三角依赖链

```
extensions:api (L1) ──需要──→ ReaderState
                                  │
features:reader (L6) ──拥有──→ ReaderState (含 documentView: View?)
                                  │
core:model (L0) ─────候选归属──→ ReaderState (不能含 View)
```

`ExtensionContext` 暴露 `StateFlow<ReaderState>` 给扩展。`ExtensionContext` 在 Layer 1，只能依赖 Layer 0。所以 `ReaderState` 必须在 `core:model`（Layer 0）。但 `core:model` 是纯 Kotlin 模块，不能含 `android.view.View`。

**裁决**：`ReaderState` 是**纯数据**的 UI 状态快照。`View` 引用不属于它。视图层的 View 引用由 ViewModel 私有管理，不暴露给 State。

---

## 逐项裁决

### #1 ReaderState 归属 → `core:model`（Layer 0）

```
core:model/.../ReaderState.kt  ← 唯一定义位置
  不含 View 引用。ViewModel 内部持有 View 引用，不暴露
  TransitionType 统一 SCREAMING_SNAKE_CASE
  含 @Serializable

extensions:api → StateFlow<ReaderState>  ← 合规 L1→L0
features:reader → ReaderState            ← 合规 L6→L0
```

`documentView: View?` 从 ReaderState **移除**。`ReaderViewModel` 内部持有 `private var documentView: View?`，不通过 State 暴露。扩展若需要与文档 View 交互，通过 `ReaderEventBus` 而非直接引用 View。

### #2 Locator → `core:model`（Layer 0），唯一定义

```
core:model/.../Locator.kt     ← 唯一定义，@Serializable
render:api → import            ← 合规 L2→L0
core:database → import         ← 合规 L1→L0
```

`render:api` 不再定义 Locator，仅 import 使用。消除循环依赖。

### #3 BookSource → Addendum 版本为准

| 维度 | 主文档（废弃） | Addendum（采用） |
|------|-------------|---------------|
| 返回值 | 裸类型 | `Result<T, ReadflowError>` |
| bookId | Int | String |
| suspend | 部分 | 全部 suspend |
| 生命周期 | `isAvailable()` | `getDownloadStatus()` |

主文档 §6.5 的旧定义替换为 Addendum 版本。

### #4 Platform-Android.md → 标记 DEPRECATED

文档顶部加废弃声明，指向 v2 主文档。Wiki sidebar 中标注 `(deprecated)`。

### #5 Ink 跨层依赖 → InkAnchor 移入 `core:model`

```
InkAnchor sealed interface → core:model（Layer 0，纯数据）
:ink 模块 → import InkAnchor from core:model  ← 合规 L4→L0
:core:database → AnnotationEntity.anchorType: String + anchorJson: String
   序列化/反序列化由 :ink 模块的 InkAnchorCodec 处理
   Room 不直接引用 InkAnchor 类型，保持数据库层类型无关
```

### #6 模块计数 → 统一 21

全文（模块树、settings.gradle.kts、迁移路径、审计消解清单）统一为 21 个模块。

21 个模块清单：
```
app
core/model, core/calibre, core/database, core/prefs, core/ui          (5)
render/api, render/epub, render/pdf, render/txt, render/mupdf, render/md, render/animate (7)
ink                                                                     (1)
features/library, features/reader, features/settings                    (3)
extensions/api, extensions/tts, extensions/stats, extensions/opds       (4)
```

### #7 TransitionType → 统一 `SCREAMING_SNAKE_CASE`

```kotlin
enum class TransitionType { SLIDE, CURL, FADE, NONE }
```

与 Kotlin `@Serializable` 默认行为一致。全文统一。

### #8 渲染模块清单 → 7 引擎

| 模块 | 格式 | 引擎 |
|------|------|------|
| `:render:api` | — | ReaderEngine 接口 |
| `:render:epub` | EPUB | WebView + epub-ts |
| `:render:pdf` | PDF | PdfRenderer |
| `:render:txt` | TXT | TxtVirtualPager |
| `:render:mupdf` | DOCX, CBZ | MuPDF JNI |
| `:render:md` | MD | Markwon |
| `:render:animate` | — | ViewPager2.PageTransformer |

主文档 §4.3、§2.1、settings.gradle.kts 全部同步。

### #9 render:api 定位 → 接口层，不含类型定义

```
render:api 职责：
  ✅ ReaderEngine 接口
  ✅ ReaderEngineRegistry（引擎发现 + 用户 override）
  ✅ PageContent（跨引擎传递的页面数据）
  ✅ ReadingMode 枚举
  ❌ Locator（属于 core:model）
  ❌ ReaderState（属于 core:model）
```

### #10 用户引擎 override → 添加

```kotlin
class ReaderEngineRegistry(...) {
    private val userOverrides = mutableMapOf<BookFormat, String>()

    fun setUserOverride(format: BookFormat, engineId: String)
    fun clearUserOverride(format: BookFormat)
    fun getUserOverride(format: BookFormat): String?

    suspend fun resolve(uri: Uri): ReaderEngine {
        val format = BookFormat.fromExtension(...)
        userOverrides[format]?.let { engineId ->
            engines.find { it.id == engineId }?.let { return it }
        }
        return engines.filter { it.supports(uri) }.maxBy { it.priority } ?: throw ...
    }
}
```

对标 KOReader 的 per-type 用户 override。

### #11 View 生命周期协议 → ReaderEngine 扩展

```kotlin
interface ReaderEngine {
    // ... existing methods ...

    /** View 被 attach 到 window 时调用（配置变更恢复） */
    fun onViewAttached(view: View) { }

    /** View 被 detach 时调用（配置变更 / Activity 销毁） */
    fun onViewDetached(view: View) { }

    /** 恢复引擎状态（进程死亡后重建） */
    suspend fun restoreState(state: Bundle) { }

    /** 保存引擎状态 */
    suspend fun saveState(): Bundle { return Bundle.EMPTY }
}
```

ViewModel 在 `onCleared()` 中调用 `engine.saveState()` 存入 `SavedStateHandle`。重建时调用 `engine.restoreState()`。

### #12 ExtensionContext 沙箱 → ReaderState 不含 View 后自然消解

ReaderState 在 core:model 中不含 View 引用 → 扩展无法通过 State 获取 View 引用 → 沙箱完整。MVI 的"唯一状态源"理念不变——视图层状态（View 引用）是 ViewModel 的实现细节，不属于 UI State。

---

## 受影响的文件

| 文件 | 变更 |
|------|------|
| `docs/android-architecture-v2.md` | §2.1 模块树 21；§4.1 ReaderEngine 加生命周期；§4.2 Registry 加 override；§4.3 7 引擎；§6.1 Locator 在 core:model；§6.5 BookSource 替换；§8.4 ReaderState 不含 View |
| `docs/android-architecture-v2-addendum.md` | P2.1 ReaderState import 修正；P2.5 InkAnchor 移入 core:model；P3.3 7 引擎 |
| `docs/wiki/Platform-Android.md` | 顶部加 DEPRECATED 声明 |
| `android/settings.gradle.kts` | include 21 模块 |
