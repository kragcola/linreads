# DeepSeek 执行任务 - 完整提示词

你好！我需要你帮我执行一个Android项目的代码修复任务。请仔细阅读以下内容后开始执行。

---

## 项目背景

**项目名称**: LinReads  
**项目类型**: Android EPUB/PDF/TXT 阅读器应用  
**技术栈**: Kotlin + Jetpack Compose + MVI架构  
**代码库位置**: `/Volumes/OmubotDisk/readflow/android/`

**项目简介**:
LinReads是一个现代化的开源Android阅读器，支持EPUB、PDF、TXT等格式。项目采用模块化架构，主要模块包括：
- `render/epub` - EPUB渲染引擎
- `render/pdf` - PDF渲染引擎  
- `render/txt` - TXT渲染引擎
- `core/domain` - 领域层逻辑
- `features/reader` - 阅读器功能

**当前问题**:
经过全面调研，我们发现了一些需要打磨的细节问题（不是大bug，而是用户体验细节）。主要包括：
1. 横版图片显示过小（P0，最高优先级）
2. 外部打开文件进度丢失（P0）
3. 代码块没有等宽字体（P1）
4. PDF不支持手势缩放（P1）
5. 分页算法需要优化（P0）

这些问题的根因已经精确定位，修复方案已经设计好。

---

## 你的任务

你需要按照我提供的**详细执行方案**，修改指定的代码文件。

**重要约束**:
1. ✅ **严格按照文档执行** - 不要有任何自主发挥
2. ✅ **使用提供的完整代码** - 直接复制粘贴，只修改必要的变量名
3. ✅ **不要自己设计UI** - 所有UI参数（颜色、尺寸）都已明确提供
4. ✅ **遇到不确定立即停止** - 不要猜测，立即报告
5. ✅ **每个Phase完成后编译测试** - 确保无编译错误

**你的优势**:
- ✅ 你有很好的基础代码能力
- ✅ 你能理解Kotlin和Android代码
- ✅ 你能准确复制粘贴和修改代码

**你的限制**:
- ❌ 你没有多模态能力，不能"看"UI
- ❌ 你不需要理解整个项目架构
- ❌ 你不需要自己设计解决方案

**因此**:
- 所有代码都会提供完整示例
- 所有UI都会精确描述（如：颜色#F5F5F5，padding 8dp）
- 你只需要按照指示执行

---

## 执行方式

### 工作流程

```
第1步: 阅读Phase描述 → 理解任务目标
第2步: 查找指定文件 → 确认文件存在
第3步: 复制提供的代码 → 粘贴到正确位置
第4步: 修改必要变量 → 如函数名、字段名
第5步: 编译测试 → 确保无错误
第6步: 标记完成 → 在文档中标记 [DONE-P0-1]
第7步: 继续下一Phase → 重复流程
```

### 报告格式

每个Phase完成后，报告：

```markdown
[DONE-P0-1] 横版图片修复

修改的文件:
- android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubParaAdapter.kt

修改内容:
1. 添加了 calculateImageMaxWidth 函数到 companion object
2. 修改了 onBindViewHolder 的 ImageVH case，调用动态计算

编译结果:
✅ 成功

遇到的问题:
无
```

如果遇到问题：

```markdown
[STUCK-P0-1] 横版图片修复

停止原因:
找不到 onBindViewHolder 函数

我找到的相关代码:
[粘贴你找到的代码]

请指导下一步。
```

---

## 执行方案文档

请仔细阅读以下完整的执行方案文档：

---

# LinReads 打磨实施方案 - DeepSeek执行版

> **目标执行人**: DeepSeek  
> **方案特点**: 零自主发挥空间，所有代码和UI都提供完整示例  
> **执行模式**: 严格按步骤执行，完成后提交给Claude检查

---

## 执行说明

**重要提示**:
1. ✅ 严格按照本文档执行，**不要自主发挥**
2. ✅ 所有代码都已提供完整示例，**直接复制修改**
3. ✅ UI相关内容都有精确描述，**不要自己设计**
4. ✅ 遇到问题立即停止，等待指导
5. ✅ 完成每个任务后标记 `[DONE]`

**执行流程**:
```
阅读任务 → 复制示例代码 → 修改必要部分 → 测试编译 → 标记完成 → 提交Claude检查
```

---

## Phase 1: P0-1 横版图片尺寸限制修复 (最高优先级)

### 任务目标
修复EPUB图片在横屏模式下显示过小的问题。

### 问题根因
文件: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubParaAdapter.kt`  
位置: 第91行  
问题代码: `maxWidth = (680 * d).toInt()`  
原因: 硬编码680dp适合竖版，横版时太小

### 执行步骤

#### Step 1.1: 修改 EpubParaAdapter.kt

**位置**: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubParaAdapter.kt`

**操作**: 在类的companion object中添加以下函数（如果没有companion object，则创建一个）

**添加位置**: 在类的最后，`private const val VIEW_TYPE_*` 定义之后

**完整代码**（直接复制）:

```kotlin
companion object {
    private const val VIEW_TYPE_TEXT = 0
    private const val VIEW_TYPE_IMAGE = 1
    private const val VIEW_TYPE_BREAK = 2
    
    /**
     * 计算图片最大宽度，根据屏幕方向和图片宽高比动态调整
     * 
     * @param context Android Context
     * @param imageWidth 图片原始宽度（像素）
     * @param imageHeight 图片原始高度（像素）
     * @return 计算后的最大宽度（像素）
     */
    private fun calculateImageMaxWidth(
        context: Context,
        imageWidth: Int,
        imageHeight: Int
    ): Int {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val screenWidthDp = metrics.widthPixels / density
        
        // 计算图片宽高比
        val aspectRatio = if (imageHeight > 0) {
            imageWidth.toFloat() / imageHeight.toFloat()
        } else {
            1.0f
        }
        
        return when {
            // 横版图片 (宽高比 ≥ 1.2): 占满内容区92%
            aspectRatio >= 1.2f -> {
                (screenWidthDp * 0.92f * density).toInt()
            }
            // 竖版/方图: 使用680dp限制或屏幕宽度90%，取较小值
            else -> {
                kotlin.math.min(
                    (680 * density).toInt(),
                    (screenWidthDp * 0.90f * density).toInt()
                )
            }
        }
    }
}
```

#### Step 1.2: 修改 createImageViewHolder 方法

**不要修改 createImageViewHolder**，保持原样。

#### Step 1.3: 修改 onBindViewHolder 方法

**位置**: 同一文件，`onBindViewHolder` 方法

**查找**: 找到处理 `VIEW_TYPE_IMAGE` 的部分

**原始代码** (大约第120-130行):
```kotlin
override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    when (holder) {
        is ImageVH -> {
            val block = blockAt(position) as? EpubDisplayBlock.Image ?: return
            holder.image.setImageBitmap(block.bitmap)
            // 可能还有其他代码
        }
        // ... 其他case
    }
}
```

**修改后的代码**（完整替换 ImageVH 的 case）:

```kotlin
is ImageVH -> {
    val block = blockAt(position) as? EpubDisplayBlock.Image ?: return
    val bitmap = block.bitmap
    
    // 设置图片
    holder.image.setImageBitmap(bitmap)
    
    // 动态计算并设置maxWidth（基于实际图片尺寸）
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

#### Step 1.4: 编译测试

```bash
cd android
./gradlew :render:epub:assembleDebug
```

**预期结果**: 编译成功，无错误

**如果编译失败**:
- 检查是否正确添加了 `kotlin.math.min` 的导入
- 检查花括号是否匹配
- 立即停止，报告错误信息

#### Step 1.5: 标记完成

完成后在此标记: `[DONE-P0-1]`

---

## Phase 2: P0-3 进度锚点跨会话持久化

### 任务目标
解决外部打开文件（通过文件管理器等）时进度丢失的问题。

### 问题根因
外部 `ACTION_VIEW` 打开的文件 `bookId` 为 `null`，导致重启后返回起点。

### 执行步骤

#### Step 2.1: 创建 BookIdResolver.kt

**位置**: 新建文件 `android/core/domain/src/main/kotlin/dev/readflow/core/domain/book/BookIdResolver.kt`

**完整代码**（直接复制）:

```kotlin
package dev.readflow.core.domain.book

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

/**
 * 负责为书籍生成或解析稳定的ID
 * 
 * 使用SHA-256内容哈希生成稳定ID，即使文件被重命名或移动也能识别
 */
object BookIdResolver {
    
    /**
     * 为给定URI的文件解析或创建稳定的bookId
     * 
     * @param uri 文件URI
     * @param contentResolver Android ContentResolver
     * @return 稳定的bookId（格式: "local-epub-<hash前16位>"）
     */
    suspend fun resolveOrCreate(
        uri: Uri,
        contentResolver: ContentResolver
    ): String = withContext(Dispatchers.IO) {
        try {
            // 读取文件前1MB计算SHA-256哈希
            val hash = contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(1024 * 1024) // 1MB
                val bytesRead = input.read(buffer)
                
                if (bytesRead > 0) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    digest.update(buffer, 0, bytesRead)
                    digest.digest().toHexString()
                } else {
                    null
                }
            }
            
            // 如果成功计算哈希，返回基于哈希的稳定ID
            if (hash != null) {
                "local-epub-${hash.take(16)}"
            } else {
                // 降级：使用URI的哈希
                "local-epub-${uri.toString().hashCode().toString(16)}"
            }
        } catch (e: IOException) {
            // 如果读取失败，使用URI的哈希作为降级方案
            "local-epub-${uri.toString().hashCode().toString(16)}"
        } catch (e: SecurityException) {
            // 权限问题，使用URI的哈希
            "local-epub-${uri.toString().hashCode().toString(16)}"
        }
    }
    
    /**
     * 将ByteArray转换为16进制字符串
     */
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
```

#### Step 2.2: 修改 ReaderViewModel.kt (或相关的打开文件逻辑)

**重要**: 这一步需要你先查找代码，然后报告给我确认

**请执行以下操作**:

1. 在项目中搜索以下关键词（优先级从高到低）:
   - `ACTION_VIEW`
   - `openByUri`
   - `Intent.ACTION_VIEW`
   - `handleIntent`

2. 找到后，报告给我：
   ```
   找到关键代码:
   文件: [文件路径]
   函数名: [函数名]
   代码片段: [粘贴相关代码]
   ```

3. 我会告诉你具体如何修改

**暂时跳过Step 2.2，先完成Step 2.1，然后报告查找结果**

#### Step 2.3: 编译测试

```bash
./gradlew :core:domain:assembleDebug
```

#### Step 2.4: 标记完成

完成Step 2.1后标记: `[PARTIAL-P0-3]`  
完成Step 2.2后标记: `[DONE-P0-3]`

---

## Phase 3: P1-2 代码块等宽字体渲染

### 任务目标
让 `<pre>` 和 `<code>` 标签的代码块使用等宽字体，Tab宽度为4空格，并添加背景色。

**注意**: 这个Phase涉及多个文件，需要按顺序修改

### 执行步骤

#### Step 3.1: 修改 EpubReaderItem.kt

**位置**: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubReaderItem.kt`

**查找**: `EpubReaderItem.Text` 的定义（应该是一个 data class）

**原始定义** (可能类似):
```kotlin
data class Text(
    val text: String,
    val locator: EpubItemLocator,
    // ... 其他字段
) : EpubReaderItem()
```

**修改为**（添加两个新字段，其他字段保持不变）:

```kotlin
data class Text(
    val text: String,
    val locator: EpubItemLocator,
    val isCodeBlock: Boolean = false,  // 新增：是否为代码块
    val language: String = "",          // 新增：代码语言
    // ... 其他现有字段保持不变
) : EpubReaderItem()
```

#### Step 3.2: 修改 EpubDisplayBlock.kt

**位置**: 查找定义 `EpubDisplayBlock.Text` 的文件

**修改**: 同样添加 `isCodeBlock` 和 `language` 字段

```kotlin
data class Text(
    val text: String,
    val isCodeBlock: Boolean = false,  // 新增
    val language: String = "",         // 新增
    // ... 其他现有字段
) : EpubDisplayBlock()
```

#### Step 3.3: 修改 EpubReaderItemParser.kt

**位置**: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubReaderItemParser.kt`

**查找**: 搜索 `when (element.tagName())` 的代码段

**操作**: 在 `when` 语句中添加对 `pre` 和 `code` 标签的处理

**添加这个case**（找到合适的位置插入）:

```kotlin
"pre", "code" -> {
    // 提取代码块文本
    val codeText = element.wholeText()
    
    // 检测语言（如果有class="language-xxx"）
    val language = element.attr("class")
        .split(" ")
        .firstOrNull { it.startsWith("language-") }
        ?.removePrefix("language-")
        ?: ""
    
    // 创建文本item，但标记为代码块
    items += EpubReaderItem.Text(
        text = codeText,
        locator = EpubItemLocator(spineIndex, items.size),
        isCodeBlock = true,
        language = language
    )
}
```

#### Step 3.4: 修改 EpubParaAdapter.kt 渲染代码块

**位置**: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubParaAdapter.kt`

**查找**: `onBindViewHolder` 中处理 `TextVH` 的部分

**重要**: 先找到现有的 `is TextVH` case，报告给我现有代码结构，我会告诉你如何精确修改

**预期修改**（具体代码我会根据你报告的现有结构来调整）:

大致方向是检查 `isCodeBlock` 标记，如果是代码块则：
- 使用 `Typeface.MONOSPACE`
- 字号 `fontSizeSp * 0.9f`
- 背景色：日间 `#F5F5F5`，夜间 `#2A2A2A`
- Padding: 8dp
- Tab替换为4空格

**请先完成Step 3.1-3.3，然后报告Step 3.4的现有代码结构**

#### Step 3.5: 编译测试

```bash
./gradlew :render:epub:assembleDebug
```

#### Step 3.6: 标记完成

完成后标记: `[DONE-P1-2]`

---

## Phase 4: P1-3 PDF手势缩放

### 任务目标
为PDF阅读添加双指捏合缩放功能。

### 执行步骤

#### Step 4.1: 查找PDF渲染代码

**请先执行查找**:

1. 搜索包含 `PdfRenderer` 的文件
2. 查找目录 `android/render/pdf/`
3. 报告给我你找到的相关文件

**报告格式**:
```
找到的PDF相关文件:
1. [文件路径] - [简要描述]
2. [文件路径] - [简要描述]
```

**等待我的具体指导后再修改**

#### Step 4.2: 内存优化 - RGB_565

**这一步可以先做**:

在你找到的PDF相关代码中，搜索 `Bitmap.Config.ARGB_8888`

如果找到，修改为:
```kotlin
// 原始
val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

// 修改为
val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
```

**好处**: 内存占用减少50%

#### Step 4.3: 标记完成

完成后标记: `[DONE-P1-3]` 或 `[PARTIAL-P1-3]`（如果只完成了4.2）

---

## Phase 5: P0-2 EPUB分页稳定性

### 任务目标
防止标题（heading）跨页显示，保持分页稳定。

### 执行步骤

#### Step 5.1: 查找分页代码

**请先查找**:

1. 搜索文件名包含 `pagination` 或 `PageMapping`
2. 搜索函数名包含 `calculatePages` 或 `linesPerPage`
3. 报告给我找到的代码

**报告格式**:
```
找到的分页相关代码:
文件: [路径]
函数: [函数名]
代码片段: [粘贴关键部分]
```

**等待我的具体指导**

#### Step 5.2: 标记完成

完成后标记: `[DONE-P0-2]`

---

## 执行策略

### 推荐执行顺序

**第一批** (优先完成，相对独立):
1. Phase 1 (P0-1) - 横版图片修复
2. Phase 2 Step 2.1 (P0-3) - 创建BookIdResolver
3. Phase 3 Step 3.1-3.2 (P1-2) - 修改数据类
4. Phase 4 Step 4.2 (P1-3) - RGB_565优化

**第二批** (需要查找确认):
5. Phase 2 Step 2.2 - 修改openByUri逻辑
6. Phase 3 Step 3.3-3.4 - 代码块渲染
7. Phase 4 Step 4.1 - PDF手势缩放
8. Phase 5 - 分页优化

### 报告格式统一

每完成一个Phase，使用以下格式报告:

```markdown
## Phase X 完成报告

[DONE-PX-X] 任务名称

### 修改的文件
- 文件路径1
- 文件路径2

### 修改内容摘要
1. 在XX文件添加了XX函数
2. 在XX文件修改了XX逻辑

### 编译结果
✅ 成功 / ❌ 失败

### 编译输出
[粘贴编译命令和结果]

### 遇到的问题
无 / [具体描述]

### 需要确认的地方
无 / [需要我检查的地方]
```

---

## 重要提醒

### 开始执行前

1. ✅ 确认你理解了任务目标
2. ✅ 确认你知道哪些可以直接执行，哪些需要先查找报告
3. ✅ 确认你知道如何标记完成
4. ✅ 确认你知道遇到问题立即停止

### 执行过程中

1. ✅ 每完成一个Step就编译测试
2. ✅ 复制代码时保持格式（缩进、换行）
3. ✅ 修改代码时只改必要的部分
4. ✅ 不确定的立即停止，不要猜测

### 完成后提交

1. ✅ 提供所有Phase的完成标记
2. ✅ 提供完整的文件修改列表
3. ✅ 提供最终编译结果
4. ✅ 说明遇到的所有问题

---

## 开始执行

现在你可以开始执行了！

**建议从 Phase 1 (P0-1 横版图片修复) 开始**，因为它是最高优先级且相对独立。

执行步骤:
1. 打开 `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubParaAdapter.kt`
2. 找到 companion object（或创建一个）
3. 添加 `calculateImageMaxWidth` 函数
4. 修改 `onBindViewHolder` 的 `ImageVH` case
5. 编译测试
6. 报告结果

**准备好了吗？开始执行吧！**

如果有任何疑问，随时停下来问我。

Good luck! 🚀
