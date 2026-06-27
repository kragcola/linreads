# DeepSeek 完整执行任务 - Week 1-2方案

你好！我需要你执行LinReads项目的Week 1-2打磨任务。

---

## ⏱️ 时间说明（重要！）

### 为什么Month 2-3 (33.8天) vs DeepSeek (8-12小时)？

**Month 2-3 真人工作 (33.8天)** 包含:
- 需求分析、方案设计
- 单元测试、集成测试、真机测试
- TTS复杂功能(7天)、无障碍验证(4天)、大文件压测(2天)
- 性能优化、bug修复、代码review
- 文档编写、团队沟通、项目管理

**Week 1-2 DeepSeek任务 (9.5天工作量 → 8-12小时)** 包含:
- ✅ **纯代码实现** - 方案已设计好，直接写代码
- ✅ **无需设计** - 所有代码都提供完整示例
- ✅ **无需测试** - 仅编译验证，无真机/单元测试
- ✅ **无复杂功能** - 不含TTS/词典等需深度集成的功能
- ✅ **并行执行** - 不需要休息，连续执行

**本任务具体包含** (6个Phase):
```
P0 - 阻塞发布 (6天 → 3-4h):
1. P0-1: 横版图片修复 (1天 → 0.5h)
2. P0-2: EPUB分页稳定性 (3天 → 1.5h)
3. P0-3: 进度持久化 (2天 → 1h)

P1 - 快速胜利 (3.5天 → 4-6h):
4. P1-2: 代码块等宽字体 (1.5天 → 2h)
5. P1-3: PDF手势缩放 (1.5天 → 1.5h)
6. P1-9: 音量键翻页 (1天 → 1h)

总计: 9.5天工作量 → 8-12小时DeepSeek执行
```

**不包含的内容** (Week 3-4 + Month 2-3):
- 中英文字体回退、TXT编码、无障碍验证
- 搜索书签、手势优化、大文件控制
- 所有P2长期打磨项目

---

## 项目背景

**项目**: LinReads - Android EPUB/PDF/TXT阅读器  
**技术栈**: Kotlin + Jetpack Compose + MVI  
**代码库**: `/Volumes/OmubotDisk/readflow/android/`

**模块结构**:
- `render/epub` - EPUB渲染引擎
- `render/pdf` - PDF渲染引擎
- `core/domain` - 领域层
- `features/reader` - 阅读器功能

**当前问题**: 6个已定位的体验细节问题（根因明确，方案已设计）

---

## 你的任务

**执行约束** (必须遵守):
1. ✅ **严格按文档执行** - 零自主发挥
2. ✅ **复制提供的代码** - 直接粘贴，只改变量名
3. ✅ **不要设计UI** - 所有参数已明确（颜色#F5F5F5，padding 8dp）
4. ✅ **遇到不确定立即停止** - 不要猜测
5. ✅ **每Phase编译测试** - 确保无错误

**你的优势**:
- ✅ 优秀的Kotlin/Android代码能力
- ✅ 能准确理解和复制代码
- ✅ 能查找和定位文件

**你的限制**:
- ❌ 无多模态能力（不能"看"UI）
- ❌ 不需要理解整体架构
- ❌ 不需要自己设计方案

**因此**: 所有代码都提供完整示例，所有UI都精确描述

---

## 执行方案

请阅读以下完整的Week 1-2执行方案：

---

# LinReads 完整打磨实施方案 - DeepSeek执行版 (Week 1-2)

## Phase 1: P0-1 横版图片尺寸限制修复 ⭐⭐⭐

### 任务目标
修复EPUB图片在横屏下显示过小（当前只占75%，应占92%）

### 执行步骤

#### Step 1.1: 添加动态计算函数

**文件**: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubParaAdapter.kt`

**在 companion object 中添加**:

```kotlin
companion object {
    private const val VIEW_TYPE_TEXT = 0
    private const val VIEW_TYPE_IMAGE = 1
    private const val VIEW_TYPE_BREAK = 2
    
    /**
     * 动态计算图片最大宽度
     * 横版图(aspect≥1.2)占92%，竖版图保持680dp
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
                (screenWidthDp * 0.92f * density).toInt()
            }
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

#### Step 1.2: 修改图片绑定逻辑

**在 onBindViewHolder 中找到 `is ImageVH` case，修改为**:

```kotlin
is ImageVH -> {
    val block = blockAt(position) as? EpubDisplayBlock.Image ?: return
    val bitmap = block.bitmap
    
    holder.image.setImageBitmap(bitmap)
    
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

#### Step 1.3: 编译

```bash
cd android
./gradlew :render:epub:assembleDebug
```

#### Step 1.4: 标记

`[DONE-P0-1]`

---

## Phase 2: P0-3 进度锚点跨会话持久化 ⭐⭐⭐

### 任务目标
外部打开文件时，重启后保持进度

### Step 2.1: 创建BookIdResolver

**新建文件**: `android/core/domain/src/main/kotlin/dev/readflow/core/domain/book/BookIdResolver.kt`

**完整代码**:

```kotlin
package dev.readflow.core.domain.book

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

object BookIdResolver {
    
    suspend fun resolveOrCreate(
        uri: Uri,
        contentResolver: ContentResolver
    ): String = withContext(Dispatchers.IO) {
        try {
            val hash = contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(1024 * 1024)
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
            "local-epub-${uri.toString().hashCode().toString(16)}"
        }
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
```

### Step 2.2: 查找并修改打开文件逻辑

**查找关键词**: `ACTION_VIEW`, `openByUri`, `handleIntent`

**找到后报告**:
```
找到关键代码:
文件: [路径]
函数: [函数名]
代码: [粘贴上下文]
```

**等待我的指导**

#### 标记

`[PARTIAL-P0-3]` 或 `[DONE-P0-3]`

---

## Phase 3: P1-2 代码块等宽字体渲染 ⭐⭐

### 任务目标
`<pre>/<code>` 使用等宽字体，Tab=4空格，有背景色

### UI规格
- 字体: `Typeface.MONOSPACE`
- 字号: `fontSizeSp * 0.9f`
- 背景色: 日间 `#F5F5F5`，夜间 `#2A2A2A`
- Padding: 8dp
- Tab: 4空格

### Step 3.1: 修改 EpubReaderItem.Text

**文件**: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubReaderItem.kt`

**添加字段**:
```kotlin
data class Text(
    val text: String,
    val locator: EpubItemLocator,
    val isCodeBlock: Boolean = false,  // 新增
    val language: String = "",          // 新增
    // ... 其他现有字段
) : EpubReaderItem()
```

### Step 3.2: 修改 EpubDisplayBlock.Text

**查找并添加相同字段**

### Step 3.3: 修改 EpubReaderItemParser.kt

**在 `when (element.tagName())` 中添加**:

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

### Step 3.4: 修改 EpubParaAdapter.kt

**在 `onBindViewHolder` 的 `is TextVH` case中**:

```kotlin
is TextVH -> {
    val block = blockAt(position) as? EpubDisplayBlock.Text ?: return
    
    if (block.isCodeBlock) {
        holder.tv.apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = fontSizeSp * 0.9f
            
            val bgColor = if (isNightMode()) {
                android.graphics.Color.parseColor("#2A2A2A")
            } else {
                android.graphics.Color.parseColor("#F5F5F5")
            }
            setBackgroundColor(bgColor)
            
            val paddingPx = (8 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            
            text = block.text.replace("\t", "    ")
        }
    } else {
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
    return themeMode == ThemeMode.DARK  // 根据实际变量名调整
}
```

#### 编译

```bash
./gradlew :render:epub:assembleDebug
```

#### 标记

`[DONE-P1-2]`

---

## Phase 4: P1-3 PDF手势缩放 ⭐

### Step 4.1: 查找PDF代码

**查找**: `PdfRenderer`, `android/render/pdf/`

**报告**: 找到的文件和View类型

### Step 4.2: 添加缩放（根据查找结果，我会指导）

### Step 4.3: 内存优化

**查找并修改**: `Bitmap.Config.ARGB_8888` → `Bitmap.Config.RGB_565`

#### 标记

`[DONE-P1-3]` 或 `[PARTIAL-P1-3]`

---

## Phase 5: P0-2 EPUB分页稳定性 ⭐

### Step 5.1: 查找分页代码

**查找**: `calculatePages`, `linesPerPage`, `PageMapping`

**报告**: 找到的代码片段

### Step 5.2: 添加Heading保护（等待指导）

#### 标记

`[DONE-P0-2]` 或 `[PARTIAL-P0-2]`

---

## Phase 6: P1-9 音量键翻页 ⭐

### Step 6.1: 查找阅读器Activity

**查找**: `ReaderActivity`, `onKeyDown`

### Step 6.2: 添加音量键监听

**在Activity中添加**:

```kotlin
override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    val volumeKeyEnabled = viewModel.settingsState.value.volumeKeyPageTurn
    
    if (volumeKeyEnabled) {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                viewModel.previousPage()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                viewModel.nextPage()
                return true
            }
        }
    }
    
    return super.onKeyDown(keyCode, event)
}
```

#### 编译

```bash
./gradlew :features:reader:assembleDebug
```

#### 标记

`[DONE-P1-9]`

---

## 执行策略

### 推荐顺序

**第1轮** (独立任务，3-4h):
1. Phase 1 - 横版图片
2. Phase 2 Step 2.1 - BookIdResolver
3. Phase 6 - 音量键

**第2轮** (查找报告，2-3h):
4. Phase 3 Step 3.1-3.3 - 代码块数据
5. Phase 4 Step 4.3 - RGB_565

**第3轮** (等待指导，3-4h):
6. Phase 2 Step 2.2
7. Phase 3 Step 3.4
8. Phase 4 Step 4.1-4.2
9. Phase 5

---

## 最终提交格式

```markdown
# Week 1-2 执行完成报告

## 完成情况
- P0-1: ✅
- P0-2: ✅
- P0-3: ✅
- P1-2: ✅
- P1-3: ✅
- P1-9: ✅

## 修改的文件
1. android/render/epub/.../EpubParaAdapter.kt
2. android/core/domain/.../BookIdResolver.kt
3. ... (完整列表)

## 编译结果
✅ 成功

## 编译输出
[粘贴]

## 遇到的问题
[列出]

## 需要检查的重点
[标出不确定的地方]
```

---

## 重要提醒

### 必须遵守
1. ✅ 所有代码直接复制
2. ✅ UI参数严格遵守
3. ✅ 不确定立即停止
4. ✅ 每Phase编译测试
5. ✅ 标记完成状态

### 不要做
1. ❌ 不要设计UI
2. ❌ 不要跳过编译
3. ❌ 不要改未提及的代码
4. ❌ 不要猜测位置

---

## 开始执行

准备好了吗？从 **Phase 1 (P0-1 横版图片)** 开始！

执行步骤:
1. 打开 EpubParaAdapter.kt
2. 添加 calculateImageMaxWidth 函数
3. 修改 onBindViewHolder
4. 编译测试
5. 报告结果

Good luck! 🚀
