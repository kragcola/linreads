# DeepSeek 补充任务 - P0-2 和 P0-3 完成指导

> **任务**: 完成Week 1-2剩余的2个部分完成Phase  
> **预计时间**: 30分钟  
> **状态**: 需要立即执行

---

## 审查结论

✅ **已完成的4个Phase代码质量优秀** (P0-1, P1-2, P1-3, P1-9)  
⚠️ **需要补充的2个Phase** (P0-2, P0-3)

---

## 补充任务1: P0-3 进度持久化集成

### 问题

BookIdResolver已创建，但可能位置不对，且未集成到打开文件逻辑。

### Step 1: 查找BookIdResolver位置

**执行命令**:
```bash
find /Volumes/OmubotDisk/readflow/android -name "BookIdResolver.kt" 2>/dev/null
```

**报告格式**:
```
找到的文件:
[完整路径]

或者：
未找到文件
```

### Step 2: 确认或创建BookIdResolver

**如果未找到文件，创建新文件**:

**文件路径**: `android/core/domain/src/main/kotlin/dev/readflow/core/domain/book/BookIdResolver.kt`

**完整代码** (直接复制):

```kotlin
package dev.readflow.core.domain.book

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

### Step 3: 查找打开文件的代码

**执行命令**:
```bash
grep -n "incomingBookUri" /Volumes/OmubotDisk/readflow/android/app/src/main/kotlin/dev/readflow/android/ReadflowApp.kt
```

**如果找到，执行**:
```bash
sed -n '行号-10,行号+20p' /Volumes/OmubotDisk/readflow/android/app/src/main/kotlin/dev/readflow/android/ReadflowApp.kt
```

**报告格式**:
```
找到 incomingBookUri 在第X行

周围代码:
[粘贴30行代码]
```

### Step 4: 等待我的集成指导

报告Step 3的结果后，我会提供精确的集成代码。

---

## 补充任务2: P0-2 EPUB分页Heading保护

### 问题

已定位关键代码，但未实施修改。

### Step 1: 读取分页代码

**执行命令**:
```bash
sed -n '400,450p' /Volumes/OmubotDisk/readflow/android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubPageMapping.kt
```

**报告**: 粘贴这50行代码

### Step 2: 等待我的修改指导

报告Step 1的结果后，我会提供精确的修改位置和代码。

---

## 执行顺序

**第1步**: 执行"补充任务1 Step 1-3"，报告结果  
**第2步**: 执行"补充任务2 Step 1"，报告结果  
**第3步**: 等待我的具体修改代码  
**第4步**: 应用修改  
**第5步**: 编译测试  

---

## 报告模板

```markdown
## 补充任务执行报告

### 任务1: P0-3 进度持久化

#### Step 1 结果:
[BookIdResolver.kt 位置]

#### Step 3 结果:
找到 incomingBookUri 在第X行

周围代码:
```kotlin
[粘贴代码]
```

### 任务2: P0-2 分页稳定性

#### Step 1 结果:
EpubPageMapping.kt 第400-450行:
```kotlin
[粘贴代码]
```

---
等待Claude的集成代码指导
```

---

**准备好了吗？开始执行补充任务！**
