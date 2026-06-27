# LinReads 完整打磨实施方案 - DeepSeek执行版 (Week 1-2)

> **执行人**: DeepSeek  
> **范围**: Week 1-2 完整方案（P0全部 + P1快速胜利）  
> **工作量**: 约10天开发工作量  
> **DeepSeek执行时间**: 预计8-12小时  
> **版本**: v2.0 完整版

---

## ⏱️ 时间说明

### 为什么真人需要Month 2-3，DeepSeek只需8-12小时？

**Month 2-3 的真人工作量 (33.8天)**:
- 包含需求分析、设计方案、代码实现、单元测试、集成测试
- 包含真机测试、性能优化、bug修复、代码review
- 包含TTS复杂功能(7天)、无障碍验证(4天)、大文件压测(2天)
- 包含文档编写、团队沟通、项目管理开销

**Week 1-2 的DeepSeek任务 (10天工作量 → 8-12小时)**:
- **不包含设计** - 所有方案已设计好
- **不包含测试** - 仅需编译验证，无真机测试
- **不包含复杂功能** - 无TTS/词典等需要深度集成的功能
- **并行执行** - DeepSeek不需要休息，可连续执行
- **纯实现** - 复制粘贴代码，修改必要参数

**本方案包含的内容** (Week 1-2):
```
P0全部 (6天真人工作量 → 3-4小时DeepSeek):
- P0-1: 横版图片修复 (1天 → 0.5h)
- P0-2: EPUB分页稳定性 (3天 → 1.5h)
- P0-3: 进度持久化 (2天 → 1h)

P1快速胜利 (3.5天真人工作量 → 4-6小时DeepSeek):
- P1-2: 代码块等宽字体 (1.5天 → 2h)
- P1-3: PDF手势缩放 (1.5天 → 1.5h)
- P1-9: 音量键翻页 (1天 → 1h)

总计: 9.5天工作量 → 8-12小时DeepSeek执行时间
```

**不包含在本方案中的内容** (Week 3-4 + Month 2-3):
- P1-1: 中英文字体回退 (2天) - 需要打包思源字体
- P1-4: TXT编码检测 (2.3天) - 需要测试多种编码
- P1-5: 无障碍TalkBack验证 (4天) - 需要真机测试
- P1-6: 搜索书签持久化 (2天) - 需要扩展测试
- P1-7: 手势交互优化 (2天) - 需要性能测试
- P1-8: 大文件内存控制 (2天) - 需要压测
- P2全部 (8.5天) - 长期打磨项目

---

## 执行说明

**重要**:
1. ✅ 本方案包含Week 1-2的全部内容（9个具体任务）
2. ✅ 所有代码都提供完整示例
3. ✅ UI参数都精确描述
4. ✅ 严格按步骤执行，不要自主发挥
5. ✅ 遇到问题立即停止报告

**执行顺序**:
```
第1批 (独立任务，可并行执行概念):
├─ Phase 1: P0-1 横版图片修复
├─ Phase 2: P0-3 进度持久化
└─ Phase 6: P1-9 音量键翻页

第2批 (依赖第1批):
├─ Phase 3: P1-2 代码块等宽字体
├─ Phase 4: P1-3 PDF手势缩放
└─ Phase 5: P0-2 EPUB分页稳定性
```

---

## Phase 1: P0-1 横版图片尺寸限制修复

### 任务目标
修复EPUB图片在横屏模式下显示过小的问题。

### 问题根因
- 文件: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubParaAdapter.kt`
- 位置: 第91行
- 问题: `maxWidth = (680 * d).toInt()` 硬编码680dp
- 影响: 横屏时图片只占75%宽度，技术书籍图表可读性差

### 执行步骤

#### Step 1.1: 添加动态计算函数

**文件**: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubParaAdapter.kt`

**位置**: 在 `companion object` 中添加（如果没有则创建）

**完整代码**:
```kotlin
companion object {
    private const val VIEW_TYPE_TEXT = 0
    private const val VIEW_TYPE_IMAGE = 1
    private const val VIEW_TYPE_BREAK = 2
    
    /**
     * 动态计算图片最大宽度
     * 根据图片宽高比自适应：横版图(aspect≥1.2)占满92%，竖版图保持680dp限制
     */
    private fun calculateImageMaxWidth(
        context: Context,
        imageWidth: Int,
        imageHeight: Int
    ): Int {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val screenWidthDp = metrics.widthPixels / density
        
        val aspectRatio = if (imageHeight > 0) {
            imageWidth.toFloat() / imageHeight.toFloat()
        } else {
            1.0f
        }
        
        return when {
            aspectRatio >= 1.2f -> {
                // 横版图片：占满内容区92%
                (screenWidthDp * 0.92f * density).toInt()
            }
            else -> {
                // 竖版/方图：保持680dp限制
                kotlin.math.min(
                    (680 * density).toInt(),
                    (screenWidthDp * 0.90f * density).toInt()
                )
            }
        }
    }
}
```

#### Step 1.2: 修改图片绑定逻辑

**文件**: 同上

**查找**: `onBindViewHolder` 方法中的 `is ImageVH` case

**原始代码**:
```kotlin
is ImageVH -> {
    val block = blockAt(position) as? EpubDisplayBlock.Image ?: return
    holder.image.setImageBitmap(block.bitmap)
}
```

**修改为**:
```kotlin
is ImageVH -> {
    val block = blockAt(position) as? EpubDisplayBlock.Image ?: return
    val bitmap = block.bitmap
    
    holder.image.setImageBitmap(bitmap)
    
    // 动态调整maxWidth
    if (bitmap != null) {
        val newMaxWidth = calculateImageMaxWidth(
            holder.itemView.context,
            bitmap.width,
            bitmap.height
        )
        holder.image.maxWidth = newMaxWidth
    }
}
```

#### Step 1.3: 编译测试

```bash
cd android
./gradlew :render:epub:assembleDebug
```

#### Step 1.4: 标记完成

`[DONE-P0-1]`

---

## Phase 2: P0-3 进度锚点跨会话持久化

### 任务目标
外部打开文件（文件管理器/其他应用）时，重启后保持阅读进度。

### 问题根因
`ACTION_VIEW` 打开的文件 `bookId=null`，无法保存进度。

### 执行步骤

#### Step 2.1: 创建 BookIdResolver.kt

**文件**: 新建 `android/core/domain/src/main/kotlin/dev/readflow/core/domain/book/BookIdResolver.kt`

**完整代码**:
```kotlin
package dev.readflow.core.domain.book

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

/**
 * 为外部文件生成稳定的bookId
 * 使用SHA-256内容哈希，抗重命名/移动
 */
object BookIdResolver {
    
    suspend fun resolveOrCreate(
        uri: Uri,
        contentResolver: ContentResolver
    ): String = withContext(Dispatchers.IO) {
        try {
            val hash = contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(1024 * 1024) // 读取前1MB
                val bytesRead = input.read(buffer)
                
                if (bytesRead > 0) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    digest.update(buffer, 0, bytesRead)
                    digest.digest().toHexString()
                } else {
                    null
                }
            }
            
            if (hash != null) {
                "local-epub-${hash.take(16)}"
            } else {
                "local-epub-${uri.toString().hashCode().toString(16)}"
            }
        } catch (e: Exception) {
            // 降级方案
            "local-epub-${uri.toString().hashCode().toString(16)}"
        }
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
```

#### Step 2.2: 查找并修改打开文件逻辑

**任务**: 找到处理外部文件打开的代码

**查找关键词** (按优先级):
1. `ACTION_VIEW`
2. `openByUri`
3. `handleIntent`

**找到后报告**:
```
找到关键代码:
文件: [路径]
函数: [函数名]
代码: [粘贴10-20行上下文]
```

**等待我的指导后修改**

#### Step 2.3: 标记完成

`[DONE-P0-3]` 或 `[PARTIAL-P0-3]` (如果Step 2.2需要指导)

---

## Phase 3: P1-2 代码块等宽字体渲染

### 任务目标
`<pre>` 和 `<code>` 标签使用等宽字体，Tab=4空格，有背景色区分。

### UI规格 (精确)
- **字体**: `Typeface.MONOSPACE`
- **字号**: `fontSizeSp * 0.9f` (比正文小10%)
- **背景色**: 
  - 日间模式: `#F5F5F5` (浅灰色)
  - 夜间模式: `#2A2A2A` (深灰色)
- **Padding**: 8dp 四边
- **圆角**: 4dp
- **Tab宽度**: 4个空格

### 执行步骤

#### Step 3.1: 修改 EpubReaderItem.Text

**文件**: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubReaderItem.kt`

**查找**: `data class Text`

**添加字段**:
```kotlin
data class Text(
    val text: String,
    val locator: EpubItemLocator,
    val isCodeBlock: Boolean = false,  // 新增
    val language: String = "",          // 新增
    // ... 其他现有字段保持不变
) : EpubReaderItem()
```

#### Step 3.2: 修改 EpubDisplayBlock.Text

**文件**: 查找定义 `EpubDisplayBlock.Text` 的文件

**同样添加字段**:
```kotlin
data class Text(
    val text: String,
    val isCodeBlock: Boolean = false,  // 新增
    val language: String = "",          // 新增
    // ... 其他现有字段
) : EpubDisplayBlock()
```

#### Step 3.3: 修改 EpubReaderItemParser.kt

**文件**: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubReaderItemParser.kt`

**查找**: `when (element.tagName())` 代码段

**添加case**:
```kotlin
"pre", "code" -> {
    val codeText = element.wholeText()
    
    val language = element.attr("class")
        .split(" ")
        .firstOrNull { it.startsWith("language-") }
        ?.removePrefix("language-")
        ?: ""
    
    items += EpubReaderItem.Text(
        text = codeText,
        locator = EpubItemLocator(spineIndex, items.size),
        isCodeBlock = true,
        language = language
    )
}
```

#### Step 3.4: 修改 EpubParaAdapter.kt 渲染逻辑

**文件**: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubParaAdapter.kt`

**查找**: `onBindViewHolder` 中的 `is TextVH` case

**先报告现有代码结构，然后按以下逻辑修改**:

**修改思路**:
```kotlin
is TextVH -> {
    val block = blockAt(position) as? EpubDisplayBlock.Text ?: return
    
    if (block.isCodeBlock) {
        // 代码块特殊渲染
        holder.tv.apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = fontSizeSp * 0.9f
            
            // 背景色
            val bgColor = if (isNightMode()) {
                android.graphics.Color.parseColor("#2A2A2A")
            } else {
                android.graphics.Color.parseColor("#F5F5F5")
            }
            setBackgroundColor(bgColor)
            
            // Padding
            val paddingPx = (8 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            
            // Tab替换为4空格
            val textWithSpaces = block.text.replace("\t", "    ")
            text = textWithSpaces
        }
    } else {
        // 正常文本
        holder.tv.apply {
            typeface = android.graphics.Typeface.DEFAULT
            textSize = fontSizeSp
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            text = block.text
        }
    }
}
```

**添加辅助函数**:
```kotlin
private fun isNightMode(): Boolean {
    // 根据实际主题系统判断
    return themeMode == ThemeMode.DARK  // 根据实际变量名调整
}
```

#### Step 3.5: 编译测试

```bash
./gradlew :render:epub:assembleDebug
```

#### Step 3.6: 标记完成

`[DONE-P1-2]`

---

## Phase 4: P1-3 PDF手势缩放

### 任务目标
添加双指捏合缩放功能，并优化内存。

### 执行步骤

#### Step 4.1: 查找PDF渲染代码

**查找**:
1. 搜索包含 `PdfRenderer` 的文件
2. 查看 `android/render/pdf/` 目录
3. 查找显示PDF的 `ImageView` 或自定义View

**报告格式**:
```
找到的PDF相关文件:
1. [文件路径] - [用途]
2. [文件路径] - [用途]

PDF页面显示的View类型:
- [View类名或ImageView]
```

#### Step 4.2: 添加缩放功能

**方案A**: 如果是自定义View，修改为:

```kotlin
class PdfPageView(context: Context) : AppCompatImageView(context) {
    
    private var scaleGestureDetector: ScaleGestureDetector
    private var scaleFactor = 1.0f
    private val scaleMatrix = Matrix()
    
    init {
        scaleType = ScaleType.MATRIX
        
        scaleGestureDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scaleFactor *= detector.scaleFactor
                    scaleFactor = scaleFactor.coerceIn(0.5f, 3.0f)
                    
                    scaleMatrix.setScale(scaleFactor, scaleFactor)
                    imageMatrix = scaleMatrix
                    
                    return true
                }
            }
        )
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        return true
    }
    
    fun resetScale() {
        scaleFactor = 1.0f
        scaleMatrix.setScale(1.0f, 1.0f)
        imageMatrix = scaleMatrix
    }
}
```

**方案B**: 如果是普通ImageView，报告给我现有代码，我会指导如何修改

#### Step 4.3: 内存优化 - RGB_565

**查找**: 所有创建PDF Bitmap的地方

**原始代码**:
```kotlin
val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
```

**修改为**:
```kotlin
val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
```

#### Step 4.4: 编译测试

```bash
./gradlew :render:pdf:assembleDebug
```

#### Step 4.5: 标记完成

`[DONE-P1-3]`

---

## Phase 5: P0-2 EPUB分页稳定性

### 任务目标
防止标题（Heading）跨页显示，添加page-break保护。

### 执行步骤

#### Step 5.1: 查找分页算法代码

**查找关键词**:
1. `calculatePages`
2. `linesPerPage`
3. `PageMapping`
4. `pagination`

**报告格式**:
```
找到的分页代码:
文件: [路径]
函数: [函数名]
代码片段: [粘贴20-30行核心逻辑]
```

#### Step 5.2: 添加Heading保护逻辑

**修改思路** (具体代码根据现有结构调整):

```kotlin
fun calculatePageBreaks(items: List<EpubReaderItem>, linesPerPage: Int): List<PageBreak> {
    val breaks = mutableListOf<PageBreak>()
    var currentLineCount = 0
    var currentPageStart = 0
    
    items.forEachIndexed { index, item ->
        when (item) {
            is EpubReaderItem.Heading -> {
                // 检查剩余空间
                val remainingLines = linesPerPage - currentLineCount
                
                // 如果剩余空间<3行且当前页不为空，flush到下一页
                if (remainingLines < 3 && currentLineCount > 0) {
                    breaks.add(PageBreak(currentPageStart, index))
                    currentPageStart = index
                    currentLineCount = 0
                }
                
                currentLineCount += item.estimatedLines
            }
            is EpubReaderItem.Text -> {
                currentLineCount += item.estimatedLines
                
                if (currentLineCount >= linesPerPage) {
                    breaks.add(PageBreak(currentPageStart, index + 1))
                    currentPageStart = index + 1
                    currentLineCount = 0
                }
            }
        }
    }
    
    // 最后一页
    if (currentPageStart < items.size) {
        breaks.add(PageBreak(currentPageStart, items.size))
    }
    
    return breaks
}
```

#### Step 5.3: 添加测量缓存（可选优化）

```kotlin
import androidx.collection.LruCache

private val measurementCache = LruCache<String, Int>(500)

fun measureTextLines(text: String, widthPx: Int): Int {
    val cacheKey = "$text|$widthPx"
    
    measurementCache.get(cacheKey)?.let { return it }
    
    val lines = // ... 实际测量逻辑
    
    measurementCache.put(cacheKey, lines)
    return lines
}
```

#### Step 5.4: 编译测试

```bash
./gradlew :render:epub:assembleDebug
```

#### Step 5.5: 标记完成

`[DONE-P0-2]`

---

## Phase 6: P1-9 音量键翻页 (新增)

### 任务目标
支持使用音量键进行翻页操作（音量上=上一页，音量下=下一页）。

### 执行步骤

#### Step 6.1: 查找阅读器Activity或Fragment

**查找**:
1. 搜索 `ReaderActivity` 或 `ReaderFragment`
2. 查找包含 `onKeyDown` 或 `dispatchKeyEvent` 的代码

**报告**:
```
找到的阅读器主界面:
文件: [路径]
类型: Activity / Fragment
是否已有onKeyDown: 是/否
```

#### Step 6.2: 添加音量键监听

**方案A**: 如果是Activity

**文件**: `ReaderActivity.kt` (具体路径根据查找结果)

**添加代码**:
```kotlin
override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    // 检查是否启用音量键翻页（从Settings读取）
    val volumeKeyEnabled = viewModel.settingsState.value.volumeKeyPageTurn
    
    if (volumeKeyEnabled) {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                // 上一页
                viewModel.previousPage()
                return true  // 消费事件，阻止音量调整
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // 下一页
                viewModel.nextPage()
                return true
            }
        }
    }
    
    return super.onKeyDown(keyCode, event)
}
```

**方案B**: 如果是Fragment，报告给我现有结构，我会指导

#### Step 6.3: 添加Settings选项（可选）

**文件**: `SettingsViewModel.kt` 或相关Settings数据类

**添加字段**:
```kotlin
data class ReaderSettings(
    // ... 现有字段
    val volumeKeyPageTurn: Boolean = true,  // 新增：默认启用
)
```

**UI添加开关** (如果有Settings页面):
```kotlin
// 在Settings Composable中添加
SwitchPreference(
    title = "音量键翻页",
    subtitle = "使用音量键进行上/下翻页",
    checked = settings.volumeKeyPageTurn,
    onCheckedChange = { viewModel.updateVolumeKeyPageTurn(it) }
)
```

#### Step 6.4: 编译测试

```bash
./gradlew :features:reader:assembleDebug
```

#### Step 6.5: 标记完成

`[DONE-P1-9]`

---

## 执行检查清单

### 编译验证

```bash
cd android
./gradlew clean
./gradlew assembleDebug
```

**预期**: 无编译错误

### 完成标记

- [ ] [DONE-P0-1] 横版图片修复
- [ ] [DONE-P0-2] EPUB分页稳定性
- [ ] [DONE-P0-3] 进度持久化
- [ ] [DONE-P1-2] 代码块等宽字体
- [ ] [DONE-P1-3] PDF手势缩放
- [ ] [DONE-P1-9] 音量键翻页

### 提交报告格式

```markdown
# Week 1-2 执行完成报告

## 完成情况
- P0-1: ✅/❌
- P0-2: ✅/❌
- P0-3: ✅/❌
- P1-2: ✅/❌
- P1-3: ✅/❌
- P1-9: ✅/❌

## 修改的文件列表
1. android/render/epub/.../EpubParaAdapter.kt
2. android/core/domain/.../BookIdResolver.kt
3. ... (列出所有修改的文件)

## 编译结果
✅ 成功 / ❌ 失败

## 编译输出
[粘贴最终编译命令和结果]

## 遇到的问题
[列出所有需要确认或无法完成的部分]

## 需要Claude检查的重点
[标出不确定的修改]
```

---

## 执行策略

### 推荐执行顺序

**第1轮** (独立任务，约3-4小时):
1. Phase 1 (P0-1) - 横版图片
2. Phase 2 Step 2.1 - 创建BookIdResolver
3. Phase 6 (P1-9) - 音量键翻页

**第2轮** (需要查找，约2-3小时):
4. Phase 3 Step 3.1-3.3 - 代码块数据模型
5. Phase 4 Step 4.3 - RGB_565优化

**第3轮** (需要指导，约3-4小时):
6. Phase 2 Step 2.2 - openByUri修改
7. Phase 3 Step 3.4 - 代码块渲染
8. Phase 4 Step 4.1-4.2 - PDF缩放
9. Phase 5 - 分页优化

**总计**: 8-11小时实际执行时间

---

## 重要提醒

### 必须遵守

1. ✅ **所有代码直接复制** - 不要改动逻辑
2. ✅ **UI参数严格遵守** - #F5F5F5, 8dp, 0.9f等
3. ✅ **遇到不确定立即停止** - 不要猜测
4. ✅ **每个Phase编译测试** - 确保无错误
5. ✅ **标记完成状态** - [DONE]/[PARTIAL]/[STUCK]

### 不要做

1. ❌ **不要自己设计UI** - 所有UI都已明确
2. ❌ **不要跳过编译** - 每Phase必须编译
3. ❌ **不要修改未提及的代码** - 只改指定部分
4. ❌ **不要猜测文件位置** - 找不到就报告

---

## 开始执行

**建议从 Phase 1 开始**，这是最高优先级且最独立的任务。

准备好了吗？开始执行！🚀
