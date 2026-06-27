# LinReads 打磨实施方案 - DeepSeek执行版 (Week 3-4 / v1.5)

> **执行人**: DeepSeek
> **范围**: Week 3-4 核心 P1（字体内置与选择 + TXT编码用户覆盖 + Settings 暴露补齐）
> **工作量**: 约 6 天真人工作量 → 预计 6-9 小时 DeepSeek 执行
> **版本**: v3.0（接续 Week 1-2 已完成的 6 项打磨）
> **前置**: Week 1-2 全部 ✅（P0-1/P0-2/P0-3/P1-2/P1-3/P1-9），P0-3 已收口为「由 LocalFileBookSource 实现」，死代码 BookIdResolver 已删

---

## ⚠️ 范围澄清（必读，避免重复造轮子）

本轮**经过代码核查**，以下两项在路线图里被列为待办，但**代码里已经完整实现**，本方案**不包含、严禁重写**：

- ❌ **搜索（in-book search）已完成**：`ReaderEngine.supportsSearch/search()` 已在 TXT/EPUB/MD 三引擎实现，UI 状态 `ReaderSearchState.kt`、ViewModel 意图 `SetSearchQuery/SubmitSearch/GoToSearchResult/ClearSearch` 全部就位。**不要再实现搜索。**
- ❌ **书签持久化已完成**：Room 实体 `BookmarkEntity`（软删除 + sync），`BookmarkDao`，`ReaderBookmarkState.kt`，VM 的 `toggleBookmark/goToBookmark/removeBookmark` 全部就位。**不要再实现书签存储。**

> 教训：Week 1-2 的 BookIdResolver 就是「实现了一个已存在能力的平行版本」，结果是从未被引用的死代码。本轮务必先确认能力是否已存在，再动手。

**TXT 编码检测也已存在**（juniversalchardet：BOM→检测器→UTF-8 兜底，`TxtCharsetDetector.kt`）。本方案只补**用户手动覆盖**这一个缺口，不重写检测逻辑。

---

## ⏱️ 时间说明

**本方案包含**（Week 3-4 核心，约 6 天真人 → 6-9 小时 DeepSeek）：
```
Phase 1: 思源宋体打包（assets + FontProvider）      (1.5天 → 1.5h)
Phase 2: ReaderEngine 字体接缝 + TXT/EPUB 应用     (2天   → 2.5h)
Phase 3: 字体选择 Settings（持久化 + UI）          (1天   → 1.5h)
Phase 4: TXT 编码用户覆盖（设置 → 引擎接缝）        (1天   → 1.5h)
Phase 5: 行距/阅读模式 Settings 暴露（快速胜利）     (0.5天 → 0.5h)
```

**本方案不含**（需真机/人工，非 DeepSeek 任务）：真机排版审计、TalkBack 无障碍验证、帧率 systrace、TTS、离线词典。

---

## 执行说明

**重要**:
1. ✅ 所有代码提供完整示例，UI 参数精确描述
2. ✅ 严格按步骤执行，不要自主发挥
3. ✅ 找不到文件/函数立即停止报告，**不要猜路径**（注意：**没有 `:core:domain` 模块**；settings 在 `:core:prefs`，实体在 `:core:database`，模型在 `:core:model`）
4. ✅ 每个 Phase 结束编译验证：`cd android && ./gradlew -Preadflow.phase=2 <module>:assembleDebug`
5. ✅ 标记完成 `[DONE-Pn]` / `[PARTIAL-Pn]` / `[STUCK-Pn]`

**模块速查**（已核实）:
- 字体 token：`core/ui/.../Type.kt`（`ReadflowType.Serif = FontFamily.Serif` 占位）
- 引擎接口：`render/api/.../ReaderEngine.kt`（有 `setFontSize/setLineSpacing/setTheme`，**无 setTypeface**）
- TXT 引擎：`render/txt/.../TxtVirtualPagerEngine.kt`（`Typeface.SERIF` 硬编码）、`TxtParagraphAdapter.kt`、`TxtDocument.kt`、`TxtCharsetDetector.kt`
- EPUB 引擎：`render/epub/.../EpubReflowEngine.kt`（Compose 路径 `currentComposeTextStyle`、Paint 路径 `currentPageTextPaint`）
- 设置仓库：`core/prefs/.../SettingsRepository.kt`（接口）+ `DataStoreSettingsRepository.kt`（实现，DataStore 名 `"readflow_settings"`）
- 设置 VM：`features/settings/.../SettingsViewModel.kt`
- 模型枚举：`core/model/.../ReaderState.kt`（`ReaderReadingMode`、`ThemeMode`）

**执行顺序**:
```
第1批（独立）:   Phase 1（字体打包）→ Phase 5（行距/模式暴露，最简单）
第2批（依赖1）:  Phase 2（引擎接缝）→ Phase 3（字体选择 UI）
第3批（独立）:   Phase 4（TXT 编码覆盖）
```

---

## Phase 1: 思源宋体打包（assets + FontProvider）

### 任务目标
把思源宋体作为内置字体打入 `:core:ui` 的 assets，提供统一加载入口 `FontProvider`，**带运行时兜底**（字体文件缺失时回退系统 Serif，保证恒可编译运行）。

### 前置：字体文件（需人工提供一次）
DeepSeek **无法下载二进制字体**。本 Phase 假设字体文件已由人工放入：
```
android/core/ui/src/main/assets/fonts/SourceHanSerifCN-Regular.otf
```
若该文件**不存在**，仍照常执行本 Phase 的代码步骤（`FontProvider` 设计为缺失时自动回退），并在报告中标注 `[字体文件缺失，已回退 SERIF]`。**不要尝试生成或下载字体。**

### 执行步骤

#### Step 1.1: 新建 FontProvider.kt

**文件**: 新建 `android/core/ui/src/main/kotlin/dev/readflow/core/ui/FontProvider.kt`

**完整代码**（直接复制）:
```kotlin
package dev.readflow.core.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.asComposeFontFamily

/**
 * 内置字体统一加载入口。
 * 思源宋体打包在 assets/fonts/ 下；文件缺失时回退系统 Serif，保证恒可运行。
 */
object FontProvider {

    private const val SOURCE_HAN_SERIF_ASSET = "fonts/SourceHanSerifCN-Regular.otf"

    @Volatile
    private var cachedSerif: Typeface? = null

    /** 思源宋体 Typeface（用于 View/Paint 路径）。缺失时回退 Typeface.SERIF。 */
    fun sourceHanSerif(context: Context): Typeface {
        cachedSerif?.let { return it }
        val tf = runCatching {
            Typeface.createFromAsset(context.assets, SOURCE_HAN_SERIF_ASSET)
        }.getOrNull() ?: Typeface.SERIF
        cachedSerif = tf
        return tf
    }

    /** 思源宋体 Compose FontFamily（用于 EPUB Compose 路径）。缺失时回退 FontFamily.Serif。 */
    fun sourceHanSerifFamily(context: Context): FontFamily =
        runCatching { sourceHanSerif(context).asComposeFontFamily() }
            .getOrDefault(FontFamily.Serif)
}
```

> 注：`Typeface.asComposeFontFamily()` 需要 `androidx.compose.ui:ui-text`（`:core:ui` 已依赖 Compose）。若该扩展不可用（编译报错 unresolved），改用：`FontFamily(androidx.compose.ui.text.font.Typeface(sourceHanSerif(context)))`，并在报告中标注采用了备选写法。

#### Step 1.2: 编译验证
```bash
cd android && ./gradlew -Preadflow.phase=2 :core:ui:assembleDebug
```

#### Step 1.3: 标记完成
`[DONE-P1]`（若字体文件缺失，标 `[DONE-P1][字体文件缺失，已回退 SERIF]`）

---
## Phase 2: ReaderEngine 字体接缝 + TXT/EPUB 应用

### 任务目标
给 `ReaderEngine` 增加 `setTypeface(useSourceHan: Boolean)` 默认方法接缝；TXT 与 EPUB 引擎实现它，把 `FontProvider` 的字体应用到正文渲染。

### 执行步骤

#### Step 2.1: ReaderEngine 增加接缝

**文件**: `android/render/api/src/main/kotlin/dev/readflow/render/api/ReaderEngine.kt`

**查找**: `suspend fun setLineSpacing(multiplier: Float) {}`（约 71 行）

**在其下方新增**（默认空实现，非字体引擎无需理会）:
```kotlin
    /** 切换正文字体：true=内置思源宋体，false=系统 Serif。默认空实现。 */
    suspend fun setSerifFont(useSourceHan: Boolean) {}
```

#### Step 2.2: TXT 引擎应用字体

**文件**: `android/render/txt/src/main/kotlin/dev/readflow/render/txt/TxtVirtualPagerEngine.kt`

**2.2a** 在引擎字段区（`fontSizeSp`/`lineSpacingMultiplier` 附近，约 77-79 行）新增字段:
```kotlin
    private var useSourceHan: Boolean = true
```

**2.2b** 实现接缝（与 `setFontSize`(311 行) 同级，紧随其后新增）:
```kotlin
    override suspend fun setSerifFont(useSourceHan: Boolean) {
        this.useSourceHan = useSourceHan
        withContext(Dispatchers.Main) {
            (recyclerView?.adapter as? TxtParagraphAdapter)?.updateTypeface(resolveTypeface())
        }
    }

    private fun resolveTypeface(): android.graphics.Typeface =
        if (useSourceHan) dev.readflow.core.ui.FontProvider.sourceHanSerif(context)
        else android.graphics.Typeface.SERIF
```
> 若引擎里持有 RecyclerView 的字段名不是 `recyclerView`，先报告该字段的真实名字与 adapter 获取方式，**不要猜**。

**2.2c** `createView()`（109 行）里把 adapter 的初始 typeface 设为 `resolveTypeface()` —— 先报告 `TxtParagraphAdapter(...)` 当前构造参数，我会给精确传参（adapter 当前用 `Typeface.SERIF` 硬编码，见 `TxtParagraphAdapter.kt:49`）。

#### Step 2.3: TxtParagraphAdapter 支持动态 typeface

**文件**: `android/render/txt/src/main/kotlin/dev/readflow/render/txt/TxtParagraphAdapter.kt`

**查找**: `typeface = Typeface.SERIF`（约 49 行）与现有 `updateFontSize`(75)/`updateLineSpacing`(80)

**新增字段 + 更新方法**（仿照现有 updateFontSize 的 notifyDataSetChanged 模式）:
```kotlin
    private var typeface: Typeface = Typeface.SERIF

    fun updateTypeface(tf: Typeface) {
        typeface = tf
        notifyDataSetChanged()
    }
```
并在 `onBindViewHolder`（约 63 行，设置 textSize/lineSpacing 处）应用 `holder.tv.typeface = typeface`。

#### Step 2.4: EPUB 引擎应用字体（Compose + Paint 双路径）

**文件**: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubReflowEngine.kt`

EPUB 有两条字体路径，**都要改**：
- Compose 路径 `currentComposeTextStyle`（约 920 行，`fontFamily = ... else FontFamily.Serif` 在 930-933）
- Paint 路径 `currentPageTextPaint`（约 977 行，`Typeface.SERIF` 在 987-991）

**先报告这两段的完整现状代码**（各 15-20 行），我会给出精确替换 —— 因为这里有 Monospace（pre/table）分支，必须保留代码块用等宽字体的逻辑（Week 1-2 P1-2 成果），只替换「正文 Serif」那一支为 `FontProvider`。新增字段 `private var useSourceHan = true` 与 `override suspend fun setSerifFont(...)`，模式同 TXT。

> ⚠️ 务必保留 pre/code/table 的 `Monospace` 分支不动，否则会回退 Week 1-2 的代码块渲染。

#### Step 2.5: ViewModel 开书时下发字体

**文件**: `android/features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderViewModel.kt`

**查找**: 开书路径里读取 `settings.fontSize.first()` / `settings.lineSpacing.first()` 并调 `engine.setFontSize(...)` 的位置（约 169-174 行）

**在同处新增**（依赖 Phase 3 的 settings 字段，若 Phase 3 未完成先用常量 `true` 占位并标注）:
```kotlin
        engine.setSerifFont(settings.useSourceHanFont.first())
```

#### Step 2.6: 编译验证
```bash
cd android && ./gradlew -Preadflow.phase=2 :render:api:assembleDebug :render:txt:assembleDebug :render:epub:assembleDebug :features:reader:assembleDebug
```

#### Step 2.7: 标记完成
`[DONE-P2]`

---

## Phase 3: 字体选择 Settings（持久化 + UI）

### 任务目标
新增「正文字体」设置项（系统宋体 / 思源宋体），持久化到 DataStore，并在设置页提供切换。默认思源宋体（`true`）。

### 执行步骤

#### Step 3.1: SettingsRepository 接口加字段

**文件**: `android/core/prefs/src/main/kotlin/dev/readflow/core/prefs/SettingsRepository.kt`

**新增**（仿照现有 `fontSize`/`setFontSize` 风格）:
```kotlin
    val useSourceHanFont: Flow<Boolean>
    suspend fun setUseSourceHanFont(enabled: Boolean)
```

#### Step 3.2: DataStoreSettingsRepository 实现

**文件**: `android/core/prefs/src/main/kotlin/dev/readflow/core/prefs/DataStoreSettingsRepository.kt`

**3.2a** companion（约 86-93 行 KEY 区）新增:
```kotlin
        private val KEY_USE_SOURCE_HAN = booleanPreferencesKey("use_source_han_font")
```
**3.2b** getter（仿 `fontSize` 的 `.map`）:
```kotlin
    override val useSourceHanFont: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_USE_SOURCE_HAN] ?: true }
```
**3.2c** setter（仿 `setFontSize`）:
```kotlin
    override suspend fun setUseSourceHanFont(enabled: Boolean) {
        context.dataStore.edit { it[KEY_USE_SOURCE_HAN] = enabled }
    }
```
> 确认文件已 `import androidx.datastore.preferences.core.booleanPreferencesKey`，缺则补。

#### Step 3.3: SettingsViewModel 暴露

**文件**: `android/features/settings/src/main/kotlin/dev/readflow/features/settings/SettingsViewModel.kt`

仿照现有 `fontSize`(67)/`themeMode`(70) 暴露 `useSourceHanFont` StateFlow，并加 setter 调 `settings.setUseSourceHanFont(...)`。**先报告该 VM 现有的 StateFlow 暴露写法**（`stateIn` 还是别的），我给精确代码。

#### Step 3.4: 设置页 UI 开关

在设置页 Composable 中，字号设置附近新增一个开关项：
- 标题：`正文字体`
- 选项：`思源宋体（内置）` / `系统宋体`
- 绑定 `useSourceHanFont`，切换时调 VM setter
> 先报告设置页 Composable 文件路径与现有「字号」项的写法，我给出风格一致的精确代码。

#### Step 3.5: 设置变更即时生效（可选但推荐）
若 ReaderViewModel 已 collect settings flow，则字体切换自动下发；否则在 reader 的 settings 变更处新增 `engine.setSerifFont(it)`。先报告 reader 是否已 collect settings 变更。

#### Step 3.6: 编译验证
```bash
cd android && ./gradlew -Preadflow.phase=2 :core:prefs:assembleDebug :features:settings:assembleDebug
```

#### Step 3.7: 标记完成
`[DONE-P3]`

---
## Phase 4: TXT 编码用户覆盖（设置 → 引擎接缝）

### 任务目标
TXT 自动编码检测已存在（`TxtCharsetDetector`：BOM→juniversalchardet→UTF-8 兜底）。本 Phase 只补**用户手动指定编码**：当检测错误（乱码）时，用户可强制选 UTF-8/GBK/GB18030/Big5/Shift_JIS，引擎据此重新解码。

### 关键接缝（已核实）
`TxtDocument.index(...)` 已接受 `charsetDetection:` 参数（`TxtDocument.kt:223`），但 `TxtVirtualPagerEngine.openBook`（94 行）**没传**。这就是集成点。

### 执行步骤

#### Step 4.1: 定义编码枚举

**文件**: 新建 `android/core/model/src/main/kotlin/dev/readflow/core/model/TxtEncoding.kt`

**完整代码**:
```kotlin
package dev.readflow.core.model

/** TXT 正文编码覆盖项。AUTO=沿用自动检测；其余强制指定。 */
enum class TxtEncoding(val charsetName: String?) {
    AUTO(null),
    UTF_8("UTF-8"),
    GBK("GBK"),
    GB18030("GB18030"),
    BIG5("Big5"),
    SHIFT_JIS("Shift_JIS"),
}
```

#### Step 4.2: Settings 加字段

**文件**: `SettingsRepository.kt` + `DataStoreSettingsRepository.kt`

仿 Phase 3 模式新增（用 `stringPreferencesKey`，存枚举名，读时 `runCatching{ TxtEncoding.valueOf(it) }.getOrDefault(AUTO)`，与 `themeMode`/`readingMode` 的枚举读取写法一致）:
```kotlin
    // 接口
    val txtEncoding: Flow<TxtEncoding>
    suspend fun setTxtEncoding(encoding: TxtEncoding)
```
```kotlin
    // 实现
    private val KEY_TXT_ENCODING = stringPreferencesKey("txt_encoding")

    override val txtEncoding: Flow<TxtEncoding> =
        context.dataStore.data.map {
            runCatching { TxtEncoding.valueOf(it[KEY_TXT_ENCODING] ?: "AUTO") }
                .getOrDefault(TxtEncoding.AUTO)
        }

    override suspend fun setTxtEncoding(encoding: TxtEncoding) {
        context.dataStore.edit { it[KEY_TXT_ENCODING] = encoding.name }
    }
```

#### Step 4.3: 引擎接受编码覆盖

**文件**: `render/txt/.../TxtVirtualPagerEngine.kt`

**4.3a** 新增字段 `private var encodingOverride: String? = null`。
**4.3b** 在 `openBook`（94 行 `TxtDocument.index(...)`）按覆盖构造 `charsetDetection` 传入。**先报告** `TxtCharsetDetection` 的构造签名（见 `TxtCharsetDetector.kt:13`：`charset/source/detectedName/fallbackReason`），我给精确传参，形如:
```kotlin
        val override = encodingOverride?.let { name ->
            runCatching { java.nio.charset.Charset.forName(name) }.getOrNull()
        }?.let { cs ->
            TxtCharsetDetection(charset = cs, source = TxtCharsetDetectionSource.Fallback, fallbackReason = "user-override")
        }
        val document = TxtDocument.index(
            file = copied.file,
            deleteOnClose = copied.deleteOnClose,
            fingerprint = copied.fingerprint,
            cachedEngineState = pendingEngineState,
            charsetDetection = override,   // 新增
        )
```
> `TxtCharsetDetection`/`TxtCharsetDetectionSource` 当前是 `internal`，若引擎在同模块可直接用；跨文件不可见则报告，我决定是否提升可见性。
**4.3c** 新增 `suspend fun setTxtEncodingOverride(charsetName: String?)`：保存覆盖并**重开当前书**使其生效（报告引擎是否持有 currentUri/可重新 openBook，我给重载逻辑）。

#### Step 4.4: ViewModel + 设置页 UI
- 开书路径（`ReaderViewModel.kt` ~169-174）读 `settings.txtEncoding.first()`，仅 TXT 格式时把 `charsetName` 下发引擎。
- 设置页新增「TXT 编码」下拉（自动/UTF-8/GBK/GB18030/Big5/Shift_JIS）。先报告设置页现有下拉/选择项写法，我给一致代码。

#### Step 4.5: 编译验证
```bash
cd android && ./gradlew -Preadflow.phase=2 :core:model:assembleDebug :core:prefs:assembleDebug :render:txt:assembleDebug :features:reader:assembleDebug :features:settings:assembleDebug
```

#### Step 4.6: 标记完成
`[DONE-P4]`

---

## Phase 5: 行距 / 阅读模式 Settings 暴露（快速胜利）

### 任务目标
`SettingsRepository` 已支持 `lineSpacing` 和 `readingMode`，但 `SettingsViewModel` **没暴露**（只暴露了 calibreUrl/fontSize/themeMode）。本 Phase 把这两项接到设置页，无需新增仓库字段。

### 执行步骤

#### Step 5.1: SettingsViewModel 暴露两项
**文件**: `features/settings/.../SettingsViewModel.kt`
仿现有 `fontSize`(67)/`themeMode`(70) 暴露 `lineSpacing: StateFlow<Float>` 与 `readingMode: StateFlow<ReaderReadingMode>`，并加 setter 调 `settings.setLineSpacing(...)` / `settings.setReadingMode(...)`。**先报告该 VM 的 StateFlow 暴露与 setter 现有写法**，我给精确代码。

#### Step 5.2: 设置页 UI
- 行距：滑块或档位（1.2 / 1.5 / 1.75 / 2.0），绑定 `lineSpacing`
- 阅读模式：分段控件（滚动 SCROLL / 翻页 PAGED），绑定 `readingMode`
先报告设置页现有「字号」「主题」项写法，我给风格一致的代码。

#### Step 5.3: 编译验证
```bash
cd android && ./gradlew -Preadflow.phase=2 :features:settings:assembleDebug
```

#### Step 5.4: 标记完成
`[DONE-P5]`

---

## 执行检查清单

### 编译验证（全量）
```bash
cd android && ./gradlew -Preadflow.phase=2 assembleDebug
```

### 完成标记
- [ ] [DONE-P1] 思源宋体打包 + FontProvider
- [ ] [DONE-P2] ReaderEngine 字体接缝 + TXT/EPUB 应用
- [ ] [DONE-P3] 字体选择 Settings（持久化 + UI）
- [ ] [DONE-P4] TXT 编码用户覆盖
- [ ] [DONE-P5] 行距 / 阅读模式 Settings 暴露

### 提交报告格式
```markdown
# Week 3-4 执行完成报告

## 完成情况
- P1: ✅/❌（字体文件是否存在）
- P2: ✅/❌
- P3: ✅/❌
- P4: ✅/❌
- P5: ✅/❌

## 修改/新增的文件列表
1. core/ui/.../FontProvider.kt (新增)
2. ... (列出全部)

## 需要 Claude 决策/给精确代码的地方
（Phase 2.2/2.4、3.3/3.4、4.3/4.4、5.1/5.2 里标了"先报告"的点，把现状代码贴出来）

## 编译结果
✅/❌ + 最终命令与输出

## 不确定/无法完成项
```

---

## 重要提醒

### 必须遵守
1. ✅ **先确认能力是否已存在再动手**（搜索/书签已完成，严禁重写）
2. ✅ **所有"先报告"的点必须先贴现状代码**，等精确指导后再改 —— 不要凭猜替换
3. ✅ **保留 Week 1-2 成果**：EPUB 代码块的 Monospace 分支、横版图片、Heading 保护，一律不得回退
4. ✅ **没有 `:core:domain` 模块**，新文件按真实模块归位
5. ✅ **每 Phase 编译**，标 `[DONE]/[PARTIAL]/[STUCK]`

### 不要做
1. ❌ 不要下载/生成字体二进制（缺失则回退并标注）
2. ❌ 不要重新实现搜索或书签持久化
3. ❌ 不要重写 TXT 编码自动检测（只加用户覆盖）
4. ❌ 不要猜文件位置/字段名，找不到就报告

---

## 开始执行

建议从 **Phase 1**（字体打包，最独立）与 **Phase 5**（设置暴露，最简单）起步，再进 Phase 2/3，最后 Phase 4。

准备好了吗？开始执行！🚀
