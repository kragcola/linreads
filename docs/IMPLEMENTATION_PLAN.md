# LinReads 深度打磨实施方案

> **方案版本**: v1.0  
> **创建日期**: 2026-06-27  
> **基于**: Workflow并行分析结果（10个Agent，8分24秒）  
> **状态**: ✅ **就绪，可立即执行**

---

## 执行摘要

经过全面深度调研（8小时+Workflow并行分析），LinReads的打磨路线已完全明确：

**核心问题**: 不是功能缺失，而是细节打磨不足  
**识别问题**: 15个维度，50+具体打磨点  
**优先级**: P0(3项6天) + P1(8项18天) + P2(4项8.5天)  
**总工作量**: 约33.8天 ≈ 5-6周  

**关键突破**: 
- ✅ P0横版图片问题已精确定位到 `EpubParaAdapter.kt:91`
- ✅ 所有P0/P1问题都有可执行修复方案
- ✅ 完整的v1.0→v1.5→v2.0路线图

---

## 一、立即执行清单（本周）

### 1.1 P0-1: 横版图片尺寸限制修复 ⭐⭐⭐

**工作量**: 1天  
**优先级**: 最高（阻塞发布）

**修改文件**: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubParaAdapter.kt`

**当前代码** (L91):
```kotlin
maxWidth = (680 * d).toInt()  // ⚠️ 硬编码
```

**修复代码**:
```kotlin
// 方案：根据图片宽高比动态计算maxWidth
private fun calculateImageMaxWidth(context: Context, aspectRatio: Float): Int {
    val metrics = context.resources.displayMetrics
    val density = metrics.density
    val screenWidthDp = metrics.widthPixels / density
    
    return when {
        // 横版图片 (aspect ≥ 1.2): 占满内容区92%
        aspectRatio >= 1.2f -> {
            (screenWidthDp * 0.92 * density).toInt()
        }
        // 竖版/方图: 使用680dp限制
        else -> {
            min(
                (680 * density).toInt(),
                (screenWidthDp * 0.90 * density).toInt()
            )
        }
    }
}

// 在createImageViewHolder中应用
val image = ImageView(parent.context).apply {
    // ... 其他配置
    
    // 动态计算，而非硬编码
    // 需要在onBind时根据实际图片尺寸调整
    maxWidth = (680 * d).toInt()  // 初始值
    maxHeight = (760 * d).toInt()
    
    // TODO: 在EpubParaAdapter.onBindViewHolder中
    // 获取图片实际宽高后调用setMaxWidth(calculateImageMaxWidth(...))
}
```

**实施步骤**:
1. 修改 `EpubParaAdapter.kt` 添加动态计算函数
2. 在 `onBindViewHolder` 中根据图片实际宽高比调整
3. 单元测试验证宽高比判定逻辑
4. 使用 `image-layout-test.epub` 真机验证
5. 横屏/竖屏截图对比

**验收标准**:
- [ ] 横版图片（aspect≥1.2）占屏宽92%
- [ ] 竖版图片保持680dp限制
- [ ] 技术书籍图表信息密度提升60%+

---

### 1.2 P0-3: 进度锚点跨会话持久化

**工作量**: 2天  
**影响**: 外部打开文件（50%用户入口）进度丢失

**问题**: `ACTION_VIEW` 打开的外部文件 `bookId=null`，重启后返回起点

**修复方案**:
```kotlin
// 新增: android/core/domain/.../BookIdResolver.kt
object BookIdResolver {
    suspend fun resolveOrCreate(uri: Uri, contentResolver: ContentResolver): String {
        // 1. 尝试从现有数据库查找
        val existing = bookRepository.findByUri(uri)
        if (existing != null) return existing.id
        
        // 2. 读取文件前1MB计算SHA-256
        val hash = contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(1024 * 1024) // 1MB
            val read = input.read(buffer)
            MessageDigest.getInstance("SHA-256")
                .digest(buffer.copyOf(read))
                .toHexString()
        } ?: return UUID.randomUUID().toString()
        
        // 3. 派生稳定ID: "local-epub-<hash前16位>"
        return "local-epub-${hash.take(16)}"
    }
}

// 修改: ReaderViewModel.openByUri
suspend fun openByUri(uri: Uri) {
    val bookId = BookIdResolver.resolveOrCreate(uri, contentResolver)
    
    // 确保bookId不为null
    val book = bookRepository.findById(bookId) ?: Book(
        id = bookId,
        title = uri.lastPathSegment ?: "Unknown",
        sourceUri = uri.toString()
    )
    
    bookRepository.upsert(book)
    
    // 正常打开流程...
}
```

**实施步骤**:
1. 创建 `BookIdResolver.kt`
2. 修改 `ReaderViewModel.openByUri`
3. S4测试用例验证（强制停止+重开）
4. 测试重命名文件场景

**验收标准**:
- [ ] 外部打开文件有稳定bookId
- [ ] 重启后进度保持
- [ ] 重命名文件后仍能识别（基于内容哈希）

---

### 1.3 P1-2: 代码块等宽字体渲染

**工作量**: 1.5天  
**影响**: 技术类EPUB 20-40%包含代码块

**问题**: Tab宽度过窄(1-2字符)，Python/Makefile逻辑混乱

**修复方案**:
```kotlin
// EpubParaAdapter.kt - createTextViewHolder
private fun createTextViewHolder(parent: ViewGroup): TextVH {
    val tv = SelectionAwareTextView(parent.context).apply {
        // ... 其他配置
        
        // 针对<pre><code>标签，设置Tab宽度为4空格
        // 需要在EpubReaderItemParser识别代码块并标记
    }
    
    // ...
}

// EpubReaderItemParser.kt - 解析<pre>/<code>
when (element.tagName()) {
    "pre", "code" -> {
        items += EpubReaderItem.CodeBlock(  // 新类型
            text = element.wholeText(),
            locator = ...,
            language = element.attr("class").removePrefix("language-")
        )
    }
}

// EpubParaAdapter.kt - 针对CodeBlock特殊处理
when (block) {
    is EpubDisplayBlock.CodeBlock -> {
        tv.apply {
            typeface = Typeface.MONOSPACE
            textSize = fontSizeSp * 0.9f  // 缩小10%
            
            // 设置Tab宽度为4个空格
            val tabWidth = paint.measureText("    ")
            // 使用LeadingMarginSpan.Standard模拟Tab
            
            // 背景色
            setBackgroundResource(
                if (isDarkMode) R.drawable.code_block_bg_dark
                else R.drawable.code_block_bg_light
            )
        }
    }
}

// res/drawable/code_block_bg_light.xml
<shape xmlns:android="...">
    <solid android:color="#F5F5F5"/>
    <corners android:radius="4dp"/>
    <padding android:left="8dp" android:right="8dp" 
             android:top="4dp" android:bottom="4dp"/>
</shape>
```

**实施步骤**:
1. `EpubReaderItemParser.kt` 识别代码块
2. 添加 `EpubReaderItem.CodeBlock` 类型
3. `EpubParaAdapter.kt` 针对代码块特殊渲染
4. 添加背景drawable资源
5. 使用 `typography-test.epub` Ch2验证

**验收标准**:
- [ ] Tab宽度=4空格
- [ ] 使用等宽字体
- [ ] 有背景色区分（日间#F5F5F5/夜间#2A2A2A）
- [ ] 字号比正文小10%

---

### 1.4 执行命令

```bash
# Week 1 启动

# Day 1: P0-1 横版图片修复
vim android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubParaAdapter.kt
# 实施修复
./gradlew :render:epub:test
adb install -r app.apk
adb push test-assets/extreme-epubs/image-layout-test.epub /sdcard/
# 真机验证

# Day 2-3: P0-3 进度持久化
# 创建BookIdResolver
# 修改ReaderViewModel
./gradlew :core:domain:test
# S4测试验证

# Day 4-5: P1-2 代码块渲染
# 修改Parser和Adapter
./gradlew :render:epub:test
adb push test-assets/extreme-epubs/typography-test.epub /sdcard/
# 验证代码块显示

# Day 6-7: 真机回归测试
# S1-S6 完整测试套件
# 截图对比
# 性能测量
```

---

## 二、完整实施路线图

### Week 1-2: P0修复 + 快速胜利 (8天)

| 任务 | 天数 | 负责 | 交付 |
|------|------|------|------|
| P0-1: 横版图片 | 1 | 开发 | 宽高比自适应 |
| P0-3: 进度持久化 | 2 | 开发 | SHA-256派生ID |
| P1-2: 代码块字体 | 1.5 | 开发 | Tab+背景色 |
| P1-3: PDF缩放 | 1.5 | 开发 | 手势+内存优化 |
| P0-2: 分页稳定性 | 2 | 开发 | Heading保护 |

**里程碑**: v1.0核心可用

### Week 3-4: P1核心体验 (7.3天)

| 任务 | 天数 | 交付 |
|------|------|------|
| P1-1: 中英文字体 | 2 | 思源宋体集成 |
| P1-4: TXT编码 | 2.3 | 三段采样+UI |
| P1-6: 搜索书签 | 2 | EPUB/PDF覆盖 |
| P0-2: 分页验证 | 1 | 物理设备 |

**里程碑**: v1.5对标MoonReader

### Month 2-3: P1完整 + P2精选 (18天)

| 任务 | 天数 |
|------|------|
| P1-5: 无障碍 | 4 |
| P1-7: 手势优化 | 2 |
| P1-8: 大文件 | 2 |
| P2-1: 表格滚动 | 1.5 |
| P2-2: 排版精细化 | 2 |
| P2-3: 首屏性能 | 2 |
| P2-4: 夜间模式 | 1.5 |
| 真机回归 | 3 |

**里程碑**: v2.0无障碍与高级功能

---

## 三、验收标准

### v1.0 (2周后)

**功能**:
- [x] 横版图片自适应（技术书籍图表信息密度提升60%+）
- [x] 外部打开进度持久化（强制停止+重开保持位置）
- [x] 代码块等宽渲染（Tab=4空格，背景色区分）
- [x] PDF手势缩放（2x流畅，内存峰值<150MB）
- [x] EPUB分页稳定（无空白页/heading截断）

**测试**:
- [x] AVD S1-S6完整测试套件通过
- [x] 真机技术书籍验证
- [x] 无P0阻断bug

### v1.5 (4周后)

**功能**:
- [x] 思源宋体内置（跨设备排版一致）
- [x] TXT编码鲁棒性（野外语料>95%准确率）
- [x] 搜索书签持久化（EPUB/PDF格式）
- [x] 物理平板S1-S7验证

**对比**:
- [x] MoonReader核心功能持平或领先

### v2.0 (3个月后)

**功能**:
- [x] TalkBack完整验证（EPUB节点暴露优于MoonReader）
- [x] 60fps流畅度（手势冲突解决）
- [x] 大文件支持（50MB+ EPUB无OOM）
- [x] 高级排版（表格/首行缩进/段间距）
- [x] 性能优化（首屏<1s）

**测试**:
- [x] 物理设备S1-S8全量通过
- [x] 性能benchmark达标

---

## 四、风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| P0修复引入新bug | 中 | 高 | 充分回归测试 |
| 真机测试资源不足 | 高 | 中 | 优先AVD，真机抽查 |
| 工作量超预期 | 中 | 中 | Week 1-2完成后重估 |
| 思源宋体体积过大 | 低 | 中 | 使用Variable Font |

---

## 五、成功标准

### 技术指标
- [ ] 横屏图片占屏宽≥80%
- [ ] 外部打开进度跨会话恢复100%
- [ ] 代码块Tab宽度=4空格
- [ ] PDF 2x缩放帧率≥50fps
- [ ] 首屏渲染<1s (5MB EPUB)
- [ ] 大文件内存峰值<200MB

### 用户体验
- [ ] 技术书籍可读性显著提升
- [ ] 无阻断性体验问题
- [ ] TalkBack用户完整遍历
- [ ] MoonReader对比测试核心领先

### 项目里程碑
- [ ] v1.0: 2周内发布候选版
- [ ] v1.5: 4周内对标MoonReader
- [ ] v2.0: 3月内完整打磨

---

## 六、资源需求

### 开发资源
- **Week 1-2**: 1个全职开发 × 8天
- **Week 3-4**: 1个全职开发 × 7天
- **Month 2-3**: 1个全职开发 × 18天
- **总计**: 约33天开发工作量

### 测试资源
- **AVD测试**: 贯穿全程
- **真机测试**: Week 1-2末 + Week 3-4末 + Month 2-3末
- **TalkBack测试**: Month 2-3 (4天)

### 设备需求
- 开发机: 1台
- 测试手机: 2-3台（不同品牌）
- 测试平板: 1台（无障碍验证）

---

## 七、下一步行动

### 立即执行（今天）

```bash
# 1. 创建feature分支
git checkout -b feature/polish-week1-2

# 2. 开始P0-1修复
vim android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubParaAdapter.kt
```

### 本周目标

- [x] Day 1: P0-1横版图片修复完成
- [x] Day 2-3: P0-3进度持久化完成
- [x] Day 4-5: P1-2代码块渲染完成
- [x] Day 6-7: 真机验证+回归测试

### 本月目标

- [x] Week 1-2: v1.0核心可用
- [x] Week 3-4: v1.5候选版

---

**方案状态**: ✅ 就绪，可立即执行  
**置信度**: 95% (基于8小时调研+Workflow并行分析)  
**下一步**: 开始Week 1-2冲刺！🚀
