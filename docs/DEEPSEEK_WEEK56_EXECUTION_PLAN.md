# LinReads 打磨实施方案 - DeepSeek执行版 (Week 5-6 / v1.5+)

> **执行人**: DeepSeek
> **范围**: Week 3-4 三条遗留收口 + 自定义字体导入
> **工作量**: 约 1.5 人日 → 预计 ~1 小时 DeepSeek 执行
> **版本**: v4.0（接续 Week 3-4 v1.5 字体/编码/设置）
> **前置**: Week 3-4 全部 ✅（思源宋体接缝、TXT编码覆盖、行距/模式设置暴露）已编译验证通过

---

## 🚦 编译验证门禁（硬性，最重要，先读）

**本轮起，每个 Phase 的完成标记 `[DONE-Pn]` 必须附带「真实编译输出」，否则一律视为未完成。**

连续两轮（Week 1-2、Week 3-4）出现「报告完成但代码编译不过」：用了不存在的 API、层次违规、漏模块依赖、漏补 fake 实现。本轮强制门禁：

### 每个 Phase 结束必须执行并粘贴输出
```bash
cd /Volumes/OmubotDisk/readflow/android
./gradlew -Preadflow.phase=2 assembleDebug 2>&1 | tail -5
```
**完成标记格式（缺一不可）**:
```
[DONE-Pn]
编译命令: ./gradlew -Preadflow.phase=2 assembleDebug
真实输出: BUILD SUCCESSFUL in XXs   ← 必须是你本机跑出来的真实行，不能编造
```

### 全部 Phase 完成后，额外跑受影响模块单测并粘贴
```bash
./gradlew -Preadflow.phase=2 \
  :features:reader:testDebugUnitTest :features:settings:testDebugUnitTest \
  :features:library:testDebugUnitTest :render:txt:testDebugUnitTest :core:ui:testDebugUnitTest \
  2>&1 | tail -8
```

### 禁止行为（违反则整轮作废）
1. ❌ **不跑编译就标 DONE** —— "环境限制/磁盘不足/SIP" 不是跳过编译的理由；真跑不动就标 `[BLOCKED-Pn]` 并贴出真实报错
2. ❌ **编造 BUILD SUCCESSFUL** —— 必须是真实命令的真实尾部输出
3. ❌ **新增接口成员后不全局搜 fake** —— 加 `SettingsRepository`/`ReaderEngine` 成员，必须 `grep -rn ": SettingsRepository\|: ReaderEngine" --include=*.kt` 找出所有实现（含 test fake）一并补
4. ❌ **新跨模块引用不查 build.gradle.kts** —— 引用别的模块的类，先确认该模块在依赖里

---

## 执行说明

**模块速查**（已核实，注意：**没有 `:core:domain`**）:
- FontProvider：`core/ui/.../FontProvider.kt`（已有 `sourceHanSerif(context)` / `sourceHanSerifFamily(context)`）
- 引擎接口：`render/api/.../ReaderEngine.kt`（已有 `setSerifFont`、`setTxtEncodingOverride`、`setFontSize/setLineSpacing/setTheme`）
- TXT 引擎：`render/txt/.../TxtVirtualPagerEngine.kt`（已有 `encodingOverride`、`currentUri`、`resolveTypeface()`）
- EPUB 引擎：`render/epub/.../EpubReflowEngine.kt`
- Reader VM：`features/reader/.../ReaderViewModel.kt`（开书在 ~169-182，setter 模板在 ~475-535）
- 设置仓库：`core/prefs/.../SettingsRepository.kt` + `DataStoreSettingsRepository.kt`
- 设置 UI：`features/settings/.../SettingsScreen.kt`（SAF launcher 模板在 61-73 行）+ `SettingsViewModel.kt`
- 本地导入模式参考：`extensions/api/.../LocalFileBookSource.kt`（SAF→filesDir 拷贝）

**执行顺序**:
```
Phase 1（实时设置生效）→ Phase 2（TXT编码保位置）→ Phase 3（字体资源收口）→ Phase 4（自定义字体导入）
P4 依赖 P3 的 FontProvider 扩展，必须最后做。
```

---

## Phase 1: 设置变更实时生效（遗留①）

### 问题
`ReaderViewModel` 开书时用 `settings.xxx.first()` 一次性读取字体/编码/行距（~169-182 行），**没有持续 collect**。因此在设置页改「正文字体/行距/阅读模式」时，已打开的书不会实时更新，必须重开。

> 注意：in-reader 自带的字号/主题 setter（~475-535）是即时的；缺的是**从设置页**改动时 reader 的响应。

### 执行步骤

#### Step 1.1: 在 reader 启动协程里 collect 字体设置

**文件**: `features/reader/.../ReaderViewModel.kt`

**先报告**：开书成功后用于「持续监听」的协程区域（已有 `engine.currentLocator.debounce(...).collect`(230 行) 和 `bookmarkDao.observeForBook(...).collect`(249 行) 等 `viewModelScope.launch { ... }` 块）。把这些 launch 块的结构贴出来（230-285 行），我确认在哪个 scope 加 collect 最稳。

#### Step 1.2: 新增字体设置监听（拿到现状后我给精确代码）

**目标逻辑**（结构参考，精确落点等 Step 1.1 报告后我给）:
```kotlin
        // 在开书后的监听协程区新增：设置页改字体时实时下发
        viewModelScope.launch {
            settings.useSourceHanFont.collect { useSourceHan ->
                _uiState.value.engine?.setSerifFont(useSourceHan)
            }
        }
        viewModelScope.launch {
            settings.lineSpacing.collect { spacing ->
                val clamped = clampedReaderLineSpacing(spacing)
                _uiState.value.engine?.setLineSpacing(clamped)
                _uiState.update { it.copy(lineSpacing = clamped) }
            }
        }
```
> ⚠️ 注意去重：`lineSpacing` 的 in-reader setter 已经会写 `settings.setLineSpacing`，collect 会再回灌一次——必须用 `distinctUntilChanged()` 且只更新 engine/UI、**不要再回写 settings**，否则可能成环。先报告现状，我给带去重的最终版。

#### Step 1.3: 编译门禁（见顶部）+ 标记 `[DONE-P1]`

---

## Phase 2: TXT 编码切换保留阅读位置（遗留②）

### 问题
`TxtVirtualPagerEngine.setTxtEncodingOverride()` 改编码后 `openBook(uri)` 重开，定位回到 `locatorForIndex(0,...)`（书首）。应保留当前阅读位置。

### 关键事实（已核实）
- 编码改变会改变**字节→段落**的索引切分，段落 index 不保证对齐。
- 但 `currentLocator` 有 `totalProgression`（0f..1f 百分比），跨重索引是稳定的近似锚点。

### 执行步骤

#### Step 2.1: 重开前抓进度，重开后按 progression 复位

**文件**: `render/txt/.../TxtVirtualPagerEngine.kt`

**先报告**：`setTxtEncodingOverride`(现状)、`openBook`(89-107 现状)、`_currentLocator` 的类型、以及 `goTo(locator)` 的签名与按 progression 跳转的能力（是否支持仅给 totalProgression 的 Locator）。

**目标逻辑**（精确代码等报告后给）:
```kotlin
    override suspend fun setTxtEncodingOverride(charsetName: String?) {
        encodingOverride = charsetName
        val uri = currentUri ?: return
        val keepProgression = _currentLocator.value.totalProgression  // 重开前抓
        openBook(uri)                                                  // 重索引
        keepProgression?.let { p ->
            // 按百分比换算回新索引并跳转（用现有 goTo / locatorForProgression）
            goTo(locatorForProgression(p))
        }
    }
```
> 若引擎没有 `locatorForProgression`，报告现有「progression→段落index」换算点（`locatorForIndex` 附近），我给换算代码。**不要新造定位策略**，复用现有。

#### Step 2.2: 编译门禁 + 标记 `[DONE-P2]`

---
## Phase 3: 字体资源收口 + 文档（遗留③）

### 任务目标
思源宋体二进制需人工放入 assets，DeepSeek 无法下载。本 Phase 把「字体资源约定」固化：建目录占位 + README 说明 + 验证 FontProvider 在缺失/存在两种情况都正确。

### 执行步骤

#### Step 3.1: 建字体目录 + README

**新建**: `android/core/ui/src/main/assets/fonts/README.md`
```markdown
# 内置字体目录

放置思源宋体后正文字体生效；缺失时 FontProvider 自动回退系统 Serif。

必需文件:
- SourceHanSerifCN-Regular.otf  （思源宋体 简体中文 Regular）

下载: https://github.com/adobe-fonts/source-han-serif/releases
许可: SIL Open Font License 1.1（可随 APK 分发）

放好后无需改代码，FontProvider.SOURCE_HAN_SERIF_ASSET 已指向该路径。
```
> 建目录需有占位文件，git 才会跟踪。README 即占位，**不要 commit 字体二进制**（体积大，由人工/CI 提供）。

#### Step 3.2: 自检 FontProvider 路径常量

**文件**: `core/ui/.../FontProvider.kt`
确认 `SOURCE_HAN_SERIF_ASSET = "fonts/SourceHanSerifCN-Regular.otf"` 与 README 文件名一致。若不一致，以 README 为准对齐。**只读核对，不要改逻辑。**

#### Step 3.3: 编译门禁 + 标记 `[DONE-P3]`

---

## Phase 4: 自定义字体导入

### 任务目标
用户经系统文件选择器导入 TTF/OTF，拷贝到 `filesDir/fonts/`，在设置页「正文字体」里可选；reader 应用所选字体。这是 Week 3-4 字体能力的自然延伸。

### 设计（已核实可行）
- 复用 `SettingsScreen.kt:61-73` 的 `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())` 模式。
- 拷贝逻辑镜像 `LocalFileBookSource.import` 的 SAF→filesDir 流程。
- 字体选择从布尔 `useSourceHanFont` **升级为字符串 `fontId`**：`"system"` / `"source_han"` / `"custom:<filename>"`。

### 执行步骤

#### Step 4.1: 设置模型从 Boolean 升级为 fontId

**文件**: `core/model/` 新建 `FontChoice.kt`
```kotlin
package dev.readflow.core.model

/** 正文字体选择。SYSTEM=系统Serif；SOURCE_HAN=内置思源；CUSTOM=用户导入(filesDir/fonts/<fileName>)。 */
sealed interface FontChoice {
    object System : FontChoice
    object SourceHan : FontChoice
    data class Custom(val fileName: String) : FontChoice

    fun serialize(): String = when (this) {
        System -> "system"
        SourceHan -> "source_han"
        is Custom -> "custom:$fileName"
    }
    companion object {
        fun parse(raw: String?): FontChoice = when {
            raw == null || raw == "source_han" -> SourceHan
            raw == "system" -> System
            raw.startsWith("custom:") -> Custom(raw.removePrefix("custom:"))
            else -> SourceHan
        }
    }
}
```
> **兼容旧设置**：Week 3-4 存的是 `use_source_han_font: Boolean`。新增 `font_choice: String` 键，迁移：旧 true→`source_han`，false→`system`。保留旧 `useSourceHanFont` getter/setter **不删**（reader 仍可能引用），新增 `fontChoice` 并行。等我确认迁移策略再定是否废弃旧键。

#### Step 4.2: FontProvider 支持自定义字体

**文件**: `core/ui/.../FontProvider.kt`
```kotlin
    private val customCache = java.util.concurrent.ConcurrentHashMap<String, Typeface>()

    /** filesDir/fonts/ 下的自定义字体目录。 */
    fun customFontsDir(context: Context): java.io.File =
        java.io.File(context.filesDir, "fonts").apply { mkdirs() }

    /** 按 FontChoice 解析 Typeface（View/Paint 路径）。任何失败回退系统 Serif。 */
    fun typefaceFor(context: Context, fontId: String): Typeface =
        when {
            fontId == "system" -> Typeface.SERIF
            fontId == "source_han" -> sourceHanSerif(context)
            fontId.startsWith("custom:") -> {
                val name = fontId.removePrefix("custom:")
                customCache.getOrPut(name) {
                    runCatching {
                        Typeface.createFromFile(java.io.File(customFontsDir(context), name))
                    }.getOrNull() ?: Typeface.SERIF
                }
            }
            else -> sourceHanSerif(context)
        }

    /** 列出已导入的自定义字体文件名。 */
    fun listCustomFonts(context: Context): List<String> =
        customFontsDir(context).listFiles { f -> f.extension.lowercase() in setOf("ttf", "otf") }
            ?.map { it.name }?.sorted().orEmpty()

    /** Compose FontFamily 版本。 */
    fun fontFamilyFor(context: Context, fontId: String): FontFamily =
        runCatching { FontFamily(ComposeTypeface(typefaceFor(context, fontId))) }
            .getOrDefault(FontFamily.Serif)
```

#### Step 4.3: 引擎接缝从 Boolean 改 fontId

**文件**: `render/api/.../ReaderEngine.kt`
新增 `suspend fun setFont(fontId: String) {}`（**保留** `setSerifFont(Boolean)` 不删，避免破坏现有调用；新接缝并行）。
TXT/EPUB 引擎实现 `setFont`：把 `resolveTypeface()` / `currentComposeTextStyle` / `currentPageTextPaint` 改成读 `FontProvider.typefaceFor(context, currentFontId)`，新增字段 `private var currentFontId = "source_han"`。**先报告**两引擎当前 `resolveTypeface` / 字体分支现状，我给精确替换（务必保留 pre/code/table 的 Monospace 分支）。

#### Step 4.4: Settings 仓库 + VM + 导入 UI

- `SettingsRepository`/`DataStoreSettingsRepository`：新增 `fontChoice: Flow<FontChoice>` + `setFontChoice(...)`（`stringPreferencesKey("font_choice")`，读用 `FontChoice.parse`）。**记得 grep 所有 fake 补实现**（门禁第 3 条）。
- `SettingsViewModel`：暴露 `fontChoice` + `importFont(uri)`（拷贝到 `customFontsDir`，仿 `LocalFileBookSource.import` 的 `contentResolver.openInputStream` → `outputStream` 拷贝，文件名取 `OpenableColumns.DISPLAY_NAME`）。
- `SettingsScreen`：把「正文字体」FilterChip 组改成动态列表（系统/思源 + `listCustomFonts()` 各一项 + 一个「+ 导入字体」按钮触发 `OpenDocument(arrayOf("font/ttf","font/otf","application/octet-stream"))`）。**先报告**现有「正文字体」FilterChip 段（Week 3-4 加的）与 SAF launcher 写法，我给一致代码。

#### Step 4.5: reader 应用 fontId

`ReaderViewModel` 开书处把 `engine.setSerifFont(...)` 换/补为 `engine.setFont(settings.fontChoice.first().serialize())`；Phase 1 的 collect 也改监听 `fontChoice`。

#### Step 4.6: 编译门禁 + 单测门禁 + 标记 `[DONE-P4]`

---

## 完成标记汇总（每条必带真实编译输出）

- [ ] [DONE-P1] 设置实时生效 + BUILD SUCCESSFUL 输出
- [ ] [DONE-P2] TXT编码保位置 + BUILD SUCCESSFUL 输出
- [ ] [DONE-P3] 字体资源收口 + BUILD SUCCESSFUL 输出
- [ ] [DONE-P4] 自定义字体导入 + BUILD SUCCESSFUL + 单测输出

## 提交报告格式
```markdown
# Week 5-6 执行完成报告

## 完成情况（每条贴真实编译尾部输出）
- P1: ✅ / BUILD SUCCESSFUL in XXs
- P2: ✅ / BUILD SUCCESSFUL in XXs
- P3: ✅ / BUILD SUCCESSFUL in XXs
- P4: ✅ / assembleDebug + testDebugUnitTest 输出

## "先报告"点的现状代码（等 Claude 精确指导）
（P1.1 监听协程区 / P2.1 openBook+goTo / P4.3 两引擎字体分支 / P4.4 字体FilterChip段）

## 修改/新增文件列表

## 遇到的 BLOCKED / 不确定项
```

---

## 重要提醒

### 必须遵守
1. ✅ **编译门禁**（顶部）—— 每 Phase 贴真实 `assembleDebug` 输出，否则不算 DONE
2. ✅ **加接口成员必 grep 所有 fake 补实现**（Week 3-4 漏了 3 个 fake 导致测试编译失败）
3. ✅ **新跨模块引用先查 build.gradle.kts 依赖**（Week 3-4 漏 `:core:ui` 依赖）
4. ✅ **不用没把握的 API**（Week 3-4 用了不存在的 `asComposeFontFamily()`）；不确定就报告
5. ✅ **保留 Week 1-2/3-4 成果**：代码块 Monospace、横版图片、Heading 保护、思源宋体接缝一律不得回退
6. ✅ **所有"先报告"点先贴现状代码**，等精确指导再改，不要凭猜替换

### 不要做
1. ❌ 不跑编译就标 DONE / 编造 BUILD SUCCESSFUL
2. ❌ 下载/commit 字体二进制（只建目录 + README）
3. ❌ 删除 `setSerifFont`/`useSourceHanFont` 旧接缝（新旧并行，迁移由我确认）
4. ❌ 猜文件位置/字段名

---

## 开始执行

从 Phase 1 起按序执行，Phase 4 必须最后（依赖 P3 的 FontProvider 扩展）。每 Phase 编译验证并贴真实输出。

准备好了吗？开始执行！🚀
