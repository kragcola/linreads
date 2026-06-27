# DeepSeek代码提交 - Claude审查检查表

> **用途**: DeepSeek完成后，Claude使用此清单进行代码审查  
> **版本**: v1.0

---

## 审查流程

### Step 1: 接收DeepSeek提交

**必需信息**:
- [ ] 完成标记列表 (DONE-P0-1, DONE-P0-3, etc.)
- [ ] 修改的文件列表
- [ ] 编译结果
- [ ] 遇到的问题说明

### Step 2: 快速检查

#### 2.1 文件完整性
```bash
# 检查必须修改的文件是否存在
ls android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubParaAdapter.kt
ls android/core/domain/src/main/kotlin/dev/readflow/core/domain/book/BookIdResolver.kt
```

#### 2.2 编译验证
```bash
cd android
./gradlew clean
./gradlew assembleDebug
```

### Step 3: 代码审查清单

#### Phase 1 审查: 横版图片修复

**EpubParaAdapter.kt**:

检查项:
- [ ] `calculateImageMaxWidth` 函数已添加到companion object
- [ ] 函数签名正确: `(Context, Int, Int) -> Int`
- [ ] 宽高比计算: `aspectRatio = imageWidth.toFloat() / imageHeight.toFloat()`
- [ ] 横版判断: `aspectRatio >= 1.2f`
- [ ] 横版返回: `(screenWidthDp * 0.92f * density).toInt()`
- [ ] 竖版返回: `min((680 * density).toInt(), (screenWidthDp * 0.90f * density).toInt())`
- [ ] `onBindViewHolder` 中 `ImageVH` case调用了 `calculateImageMaxWidth`
- [ ] 传参正确: `bitmap.width, bitmap.height`

常见错误:
- ❌ 忘记检查 `bitmap != null`
- ❌ 传参顺序错误 (height, width)
- ❌ 使用了 `Math.min` 而非 `kotlin.math.min`
- ❌ 忘记导入 `kotlin.math.min`

修复示例:
```kotlin
// 如果缺少null检查
if (bitmap != null) {
    val newMaxWidth = calculateImageMaxWidth(...)
    holder.image.maxWidth = newMaxWidth
}
```

#### Phase 2 审查: 进度持久化

**BookIdResolver.kt**:

检查项:
- [ ] 文件位置正确: `core/domain/.../book/BookIdResolver.kt`
- [ ] package声明正确
- [ ] `resolveOrCreate` 函数签名: `suspend (Uri, ContentResolver) -> String`
- [ ] 使用 `withContext(Dispatchers.IO)`
- [ ] 读取1MB: `ByteArray(1024 * 1024)`
- [ ] SHA-256计算: `MessageDigest.getInstance("SHA-256")`
- [ ] 返回格式: `"local-epub-${hash.take(16)}"`
- [ ] 有异常处理 (IOException, SecurityException)
- [ ] 降级方案: 使用URI哈希

**openByUri修改**:

检查项:
- [ ] 调用了 `BookIdResolver.resolveOrCreate`
- [ ] 传入了 `contentResolver`
- [ ] 检查了 `bookRepository.findById(bookId)`
- [ ] 创建新Book时使用了生成的bookId
- [ ] 调用了 `bookRepository.upsert(book)`

常见错误:
- ❌ 忘记 `suspend` 关键字
- ❌ 没有在IO线程
- ❌ 字节数组大小错误
- ❌ 哈希截取长度错误

#### Phase 3 审查: 代码块渲染

**EpubReaderItem.kt**:

检查项:
- [ ] `Text` data class添加了 `isCodeBlock: Boolean = false`
- [ ] 添加了 `language: String = ""`
- [ ] 默认值正确

**EpubReaderItemParser.kt**:

检查项:
- [ ] 添加了 `"pre", "code"` case
- [ ] 提取了 `element.wholeText()`
- [ ] 解析了 `language` (从class属性)
- [ ] 创建Text时传入 `isCodeBlock = true`

**EpubParaAdapter.kt**:

检查项:
- [ ] `onBindViewHolder` 的 `TextVH` case中检查了 `isCodeBlock`
- [ ] 代码块使用 `Typeface.MONOSPACE`
- [ ] 字号缩小: `fontSizeSp * 0.9f`
- [ ] 背景色: 日间#F5F5F5, 夜间#2A2A2A
- [ ] 添加了padding: 8dp
- [ ] Tab替换为4空格: `replace("\t", "    ")`
- [ ] 非代码块恢复默认样式

常见错误:
- ❌ 忘记恢复非代码块的样式
- ❌ 颜色值错误 (没有#号)
- ❌ padding单位错误 (忘记乘density)
- ❌ 字体设置在错误的位置

#### Phase 4 审查: PDF缩放

**PDF View修改**:

检查项:
- [ ] 创建了 `ScaleGestureDetector`
- [ ] 实现了 `SimpleOnScaleGestureListener`
- [ ] `onScale` 中更新了 `scaleFactor`
- [ ] 缩放范围限制: `coerceIn(0.5f, 3.0f)`
- [ ] 应用了 Matrix: `imageMatrix = scaleMatrix`
- [ ] `onTouchEvent` 调用了 `scaleGestureDetector.onTouchEvent`

**内存优化**:

检查项:
- [ ] Bitmap配置改为 `RGB_565`
- [ ] 所有创建Bitmap的地方都修改了

常见错误:
- ❌ 缩放范围错误 (如1.0f-3.0f，应该是0.5f-3.0f)
- ❌ 没有返回true在onTouchEvent
- ❌ 忘记设置 `scaleType = ScaleType.MATRIX`

#### Phase 5 审查: 分页稳定性

检查项:
- [ ] 在遇到Heading时检查剩余空间
- [ ] 剩余行数 < 3时flush到下一页
- [ ] 添加了LruCache缓存
- [ ] 缓存key包含text和width

常见错误:
- ❌ 逻辑错误导致空白页
- ❌ 无限循环
- ❌ 缓存key不唯一

### Step 4: 功能逻辑审查

#### 横版图片逻辑

验证:
```kotlin
// 测试用例
// 横版图片 (1920x1080, aspect=1.78)
// 屏幕 (1080px宽, density=3.0)
// screenWidthDp = 360
// 预期: (360 * 0.92 * 3.0) = 993px

// 竖版图片 (1080x1920, aspect=0.56)
// 预期: min(680*3.0, 360*0.9*3.0) = min(2040, 972) = 972px
```

检查计算是否正确。

#### 进度持久化逻辑

验证:
- 同一文件多次打开生成相同bookId
- 文件重命名后仍生成相同bookId
- 权限不足时使用降级方案

#### 代码块渲染逻辑

验证:
- 普通文本不受影响
- 代码块有明显视觉区分
- Tab正确替换为4空格
- 夜间模式背景色正确

### Step 5: 潜在Bug检查

#### 常见Bug模式

1. **空指针**:
   - 检查所有 `bitmap.width` 前是否检查了null
   - 检查所有 `uri` 操作前是否检查了null

2. **内存泄漏**:
   - LruCache是否正确初始化
   - Listener是否正确释放

3. **并发问题**:
   - IO操作是否在正确线程
   - 是否有 `suspend` 标记

4. **数值溢出**:
   - 宽高计算是否可能溢出
   - 缓存key是否过长

5. **逻辑错误**:
   - 条件判断是否正确
   - 边界情况是否处理

### Step 6: 代码质量检查

#### Kotlin风格

- [ ] 使用了 `?.let` 而非多层if
- [ ] 使用了 `when` 而非多个if-else
- [ ] 正确使用了默认参数
- [ ] 正确使用了命名参数

#### 命名规范

- [ ] 变量名清晰 (不要用a, b, c)
- [ ] 函数名动词开头
- [ ] 常量全大写

#### 代码组织

- [ ] 导入语句正确
- [ ] 没有未使用的导入
- [ ] 代码缩进正确

### Step 7: 生成反馈

#### 如果通过

```markdown
✅ 代码审查通过

检查项: 全部通过
编译: ✅ 成功
逻辑: ✅ 正确
质量: ✅ 良好

可以提交代码:
git add .
git commit -m "feat: Phase 1-5打磨修复

- P0-1: 修复横版图片尺寸限制
- P0-3: 实现进度锚点持久化  
- P1-2: 添加代码块等宽字体渲染
- P1-3: 实现PDF手势缩放
- P0-2: 优化EPUB分页稳定性"
```

#### 如果需要修改

```markdown
⚠️ 代码审查发现问题

Phase X - 问题Y:
位置: 文件名:行号
问题: 具体描述
修复: 提供修复代码

请修改后重新提交审查。
```

---

## 审查模板

使用以下模板进行系统化审查:

```markdown
# DeepSeek代码审查结果

## 基本信息
- 审查时间: YYYY-MM-DD HH:MM
- 提交的Phase: [列出]
- 修改的文件数: X

## 编译检查
- [ ] 编译成功/失败
- [ ] 错误信息: [如有]

## Phase审查

### P0-1 横版图片
- [ ] 通过/需修改
- 问题: [如有]

### P0-3 进度持久化
- [ ] 通过/需修改
- 问题: [如有]

### P1-2 代码块渲染
- [ ] 通过/需修改
- 问题: [如有]

### P1-3 PDF缩放
- [ ] 通过/需修改
- 问题: [如有]

### P0-2 分页稳定性
- [ ] 通过/需修改
- 问题: [如有]

## 潜在Bug
- [列出发现的潜在问题]

## 代码质量
- 风格: ✅/⚠️
- 命名: ✅/⚠️
- 组织: ✅/⚠️

## 总体评价
- [ ] ✅ 通过，可以提交
- [ ] ⚠️ 需要小修改
- [ ] ❌ 需要大修改

## 下一步
[具体指导]
```

---

使用此检查表可以系统化地审查DeepSeek提交的代码，确保质量和正确性。
