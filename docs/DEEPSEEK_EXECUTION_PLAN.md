# LinReads 打磨实施方案 - DeepSeek执行版

> **目标执行人**: DeepSeek  
> **方案特点**: 零自主发挥空间，所有代码和UI都提供完整示例  
> **执行模式**: 严格按步骤执行，完成后提交给Claude检查  
> **版本**: v1.0  
> **创建日期**: 2026-06-27

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

**位置**: 同一文件，`createImageViewHolder` 方法

**原始代码** (第82-96行左右):
```kotlin
private fun createImageViewHolder(parent: ViewGroup): ImageVH {
    val d = parent.resources.displayMetrics.density
    val image = ImageView(parent.context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL,
        )
        adjustViewBounds = true
        maxWidth = (680 * d).toInt()      // ← 这行需要修改
        maxHeight = (760 * d).toInt()
        minimumHeight = (48 * d).toInt()
        setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (12 * d).toInt())
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    // ... 后续代码
}
```

**修改方案**: 
由于在创建ViewHolder时还不知道图片尺寸，我们需要：
1. 先保持原有初始化
2. 在 `onBindViewHolder` 中动态调整

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

**位置**: 查找处理 `ACTION_VIEW` 或 `openByUri` 的代码

**文件可能位置**:
- `android/features/reader/src/main/kotlin/dev/readflow/reader/ReaderViewModel.kt`
- 或类似的文件

**查找**: 搜索 `ACTION_VIEW` 或 `openByUri` 或 `Intent.ACTION_VIEW`

**原始逻辑** (示例):
```kotlin
suspend fun openByUri(uri: Uri) {
    // 直接使用uri打开，bookId可能为null
    val book = ...
    openBook(book)
}
```

**修改方案**:

**如果找到类似 `openByUri` 或 `handleIntent` 的函数**，修改为:

```kotlin
suspend fun openByUri(uri: Uri, contentResolver: ContentResolver) {
    // 使用BookIdResolver生成稳定ID
    val bookId = BookIdResolver.resolveOrCreate(uri, contentResolver)
    
    // 查找或创建Book记录
    val existingBook = bookRepository.findById(bookId)
    
    val book = existingBook ?: Book(
        id = bookId,
        title = uri.lastPathSegment ?: "Unknown",
        // 保存URI以便后续访问
        sourceUri = uri.toString(),
        // 其他必要字段...
    )
    
    // 确保保存到数据库
    bookRepository.upsert(book)
    
    // 正常打开流程
    openBook(book)
}
```

**重要**: 
- 如果你找不到确切的函数，**立即停止**并报告
- 不要猜测或自己创建新函数
- 提供你找到的相关代码让我检查

#### Step 2.3: 添加必要的导入

在修改的文件顶部添加:

```kotlin
import dev.readflow.core.domain.book.BookIdResolver
import android.content.ContentResolver
```

#### Step 2.4: 编译测试

```bash
./gradlew :core:domain:assembleDebug
./gradlew :features:reader:assembleDebug
```

**预期结果**: 编译成功

#### Step 2.5: 标记完成

完成后在此标记: `[DONE-P0-3]`

---

## Phase 3: P1-2 代码块等宽字体渲染

### 任务目标
让 `<pre>` 和 `<code>` 标签的代码块使用等宽字体，Tab宽度为4空格，并添加背景色。

### 执行步骤

#### Step 3.1: 修改 EpubReaderItemParser.kt

**位置**: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubReaderItemParser.kt`

**查找**: 搜索 `when (element.tagName())` 的代码段，找到处理HTML标签的地方

**原始代码** (可能类似):
```kotlin
when (element.tagName()) {
    "p" -> // 处理段落
    "div" -> // 处理div
    // ... 其他标签
}
```

**修改方案**: 添加对 `pre` 和 `code` 标签的处理

**在相应位置添加**:

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
        isCodeBlock = true,  // 添加标记
        language = language
    )
}
```

**重要**: 这需要修改 `EpubReaderItem.Text` 的定义

#### Step 3.2: 修改 EpubReaderItem.kt

**位置**: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubReaderItem.kt`

**查找**: `EpubReaderItem.Text` 的定义

**原始定义** (可能类似):
```kotlin
data class Text(
    val text: String,
    val locator: EpubItemLocator,
    // ... 其他字段
) : EpubReaderItem()
```

**修改为**:

```kotlin
data class Text(
    val text: String,
    val locator: EpubItemLocator,
    val isCodeBlock: Boolean = false,  // 新增：是否为代码块
    val language: String = "",          // 新增：代码语言
    // ... 其他现有字段保持不变
) : EpubReaderItem()
```

#### Step 3.3: 修改 EpubParaAdapter.kt 渲染代码块

**位置**: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubParaAdapter.kt`

**查找**: `onBindViewHolder` 中处理 `TextVH` 的部分

**修改策略**: 根据 `isCodeBlock` 标记应用特殊样式

**在 `onBindViewHolder` 的 `is TextVH` case中添加**:

```kotlin
is TextVH -> {
    val block = blockAt(position) as? EpubDisplayBlock.Text ?: return
    
    // 检查是否为代码块
    val isCodeBlock = block.isCodeBlock // 假设已添加此字段
    
    if (isCodeBlock) {
        // 代码块特殊处理
        holder.tv.apply {
            // 使用等宽字体
            typeface = android.graphics.Typeface.MONOSPACE
            
            // 字号缩小10%
            textSize = fontSizeSp * 0.9f
            
            // 设置背景色（根据主题）
            val bgColor = if (isNightMode()) {
                android.graphics.Color.parseColor("#2A2A2A")
            } else {
                android.graphics.Color.parseColor("#F5F5F5")
            }
            setBackgroundColor(bgColor)
            
            // 添加padding
            val paddingPx = (8 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            
            // 处理Tab宽度为4空格
            val spaceWidth = paint.measureText(" ")
            val tabWidth = spaceWidth * 4
            
            // 替换Tab为4个空格（简单方案）
            val textWithSpaces = block.text.replace("\t", "    ")
            text = textWithSpaces
        }
    } else {
        // 正常文本处理
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

**添加辅助函数** (在 `EpubParaAdapter` 类中):

```kotlin
private fun isNightMode(): Boolean {
    // 检查当前是否为夜间模式
    // 根据实际的主题系统调整
    return themeMode == ThemeMode.DARK  // 根据实际变量名调整
}
```

#### Step 3.4: 修改 EpubDisplayBlock.kt

**位置**: 找到 `EpubDisplayBlock.Text` 的定义

**添加字段**:

```kotlin
data class Text(
    val text: String,
    val isCodeBlock: Boolean = false,  // 新增
    val language: String = "",         // 新增
    // ... 其他现有字段
) : EpubDisplayBlock()
```

#### Step 3.5: 编译测试

```bash
./gradlew :render:epub:assembleDebug
```

#### Step 3.6: 标记完成

完成后在此标记: `[DONE-P1-2]`

---

## Phase 4: P1-3 PDF手势缩放

### 任务目标
为PDF阅读添加双指捏合缩放功能。

### 执行步骤

#### Step 4.1: 修改 PDF 渲染的 View

**位置**: 查找PDF渲染相关的代码

**可能位置**:
- `android/render/pdf/src/main/kotlin/dev/readflow/render/pdf/`
- 查找包含 `PdfRenderer` 的文件

**查找**: 搜索 `ImageView` 或显示PDF页面的View

**修改方案**: 添加 `ScaleGestureDetector`

**完整示例代码**:

```kotlin
class PdfPageView(context: Context) : androidx.appcompat.widget.AppCompatImageView(context) {
    
    private var scaleGestureDetector: ScaleGestureDetector
    private var scaleFactor = 1.0f
    private val scaleMatrix = Matrix()
    
    init {
        scaleType = ScaleType.MATRIX
        
        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // 更新缩放因子
                scaleFactor *= detector.scaleFactor
                
                // 限制缩放范围: 0.5x - 3.0x
                scaleFactor = scaleFactor.coerceIn(0.5f, 3.0f)
                
                // 应用缩放
                scaleMatrix.setScale(scaleFactor, scaleFactor)
                imageMatrix = scaleMatrix
                
                return true
            }
        })
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

**如果找不到具体文件**:
- 立即停止
- 报告你搜索的关键词和找到的相关文件
- 等待具体指导

#### Step 4.2: 内存优化 - 使用 RGB_565

**位置**: 查找创建 Bitmap 的代码

**查找**: 搜索 `Bitmap.Config.ARGB_8888` 或 `PdfRenderer.Page.render`

**修改**: 将 `ARGB_8888` 改为 `RGB_565`

**示例**:

```kotlin
// 原始代码
val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

// 修改为
val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
```

**好处**: 内存占用减少50%

#### Step 4.3: 编译测试

```bash
./gradlew :render:pdf:assembleDebug
```

#### Step 4.4: 标记完成

完成后在此标记: `[DONE-P1-3]`

---

## Phase 5: P0-2 EPUB分页稳定性

### 任务目标
防止标题（heading）跨页显示，保持分页稳定。

### 执行步骤

#### Step 5.1: 修改分页逻辑

**位置**: 查找分页算法相关代码

**可能文件**:
- `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubPageMapping.kt`
- 或包含 `pagination` 的文件

**查找**: 搜索 `linesPerPage` 或 `calculatePages`

**修改策略**: 在遇到heading时，如果剩余空间<3行，则flush到下一页

**示例代码**（需要根据实际代码结构调整）:

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
                
                if (remainingLines < 3 && currentLineCount > 0) {
                    // 空间不足，flush当前页
                    breaks.add(PageBreak(currentPageStart, index))
                    currentPageStart = index
                    currentLineCount = 0
                }
                
                // 添加heading占用的行数
                currentLineCount += item.estimatedLines
            }
            is EpubReaderItem.Text -> {
                currentLineCount += item.estimatedLines
                
                // 如果超过一页，添加分页符
                if (currentLineCount >= linesPerPage) {
                    breaks.add(PageBreak(currentPageStart, index + 1))
                    currentPageStart = index + 1
                    currentLineCount = 0
                }
            }
            // ... 其他类型
        }
    }
    
    // 最后一页
    if (currentPageStart < items.size) {
        breaks.add(PageBreak(currentPageStart, items.size))
    }
    
    return breaks
}

data class PageBreak(val startIndex: Int, val endIndex: Int)
```

**重要**: 
- 如果现有代码结构差异很大，**不要强行修改**
- 立即停止并报告现有代码结构
- 等待具体指导

#### Step 5.2: 添加测量缓存

**位置**: 同一文件或相关文件

**添加LruCache**:

```kotlin
import androidx.collection.LruCache

class EpubPageCalculator {
    
    // 缓存文本测量结果，最多缓存500个
    private val measurementCache = LruCache<String, Int>(500)
    
    fun measureTextLines(text: String, widthPx: Int, textPaint: TextPaint): Int {
        val cacheKey = "$text|$widthPx"
        
        // 先查缓存
        measurementCache.get(cacheKey)?.let { return it }
        
        // 实际测量
        val lines = // ... 实际测量逻辑
        
        // 缓存结果
        measurementCache.put(cacheKey, lines)
        
        return lines
    }
}
```

#### Step 5.3: 编译测试

```bash
./gradlew :render:epub:assembleDebug
```

#### Step 5.4: 标记完成

完成后在此标记: `[DONE-P0-2]`

---

## 执行检查清单

完成所有Phase后，进行以下检查:

### 编译检查

```bash
cd android
./gradlew clean
./gradlew assembleDebug
```

**预期**: 无编译错误

### 代码检查清单

- [ ] P0-1: EpubParaAdapter.kt 添加了 calculateImageMaxWidth 函数
- [ ] P0-1: onBindViewHolder 中 ImageVH 调用了动态计算
- [ ] P0-3: 创建了 BookIdResolver.kt 文件
- [ ] P0-3: 修改了 openByUri 相关逻辑
- [ ] P1-2: EpubReaderItem.Text 添加了 isCodeBlock 字段
- [ ] P1-2: EpubParaAdapter 中代码块有特殊渲染
- [ ] P1-3: PDF添加了ScaleGestureDetector
- [ ] P0-2: 分页逻辑添加了heading保护

### 提交给Claude检查

完成后，提供以下信息:

1. **完成标记**:
   - [DONE-P0-1]: ✅/❌
   - [DONE-P0-3]: ✅/❌
   - [DONE-P1-2]: ✅/❌
   - [DONE-P1-3]: ✅/❌
   - [DONE-P0-2]: ✅/❌

2. **修改的文件列表**:
   ```
   - android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubParaAdapter.kt
   - android/core/domain/src/main/kotlin/dev/readflow/core/domain/book/BookIdResolver.kt
   - ... (列出所有修改的文件)
   ```

3. **遇到的问题**:
   - 列出所有不确定或无法完成的部分

4. **编译结果**:
   - 成功/失败
   - 如果失败，提供完整错误信息

---

## 注意事项

### 不要做的事情 ❌

1. ❌ **不要自己设计UI** - 所有UI描述都已提供
2. ❌ **不要自主发挥** - 严格按照代码示例
3. ❌ **不要猜测文件位置** - 找不到立即停止并报告
4. ❌ **不要修改未提及的代码** - 只修改明确指出的部分
5. ❌ **不要跳过编译测试** - 每个Phase完成后都要编译

### 应该做的事情 ✅

1. ✅ **复制粘贴代码** - 直接使用提供的完整代码
2. ✅ **逐步执行** - 按Phase顺序，一步一步来
3. ✅ **立即报告问题** - 遇到任何不确定立即停止
4. ✅ **标记完成** - 每个Phase完成后标记
5. ✅ **提供详细信息** - 提交检查时提供完整信息

---

## 结束

完成所有Phase后:

1. 确保所有 [DONE-*] 标记已完成
2. 运行最终编译检查
3. 填写检查清单
4. 提交给Claude进行代码审查

**Claude会检查**:
- 代码正确性
- 逻辑完整性
- 潜在bug
- 最佳实践

**不要自己提交代码到Git** - 等待Claude审查通过后再提交。

---

Good luck! 严格按照步骤执行，遇到问题立即停止。🚀
