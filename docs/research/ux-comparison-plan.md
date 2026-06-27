# LinReads 本地离线阅读体验对比调研方案

> **调研日期**: 2026-06-27  
> **调研目标**: 对比 LinReads 与静读天下及其他主流阅读软件在纯粹本地离线阅读方面的用户体验  
> **调研方法**: 代码分析 + 已有对比审计 + 模拟器测试 + 特性矩阵对比  
> **状态**: 执行中

---

## 一、调研对象

### 1.1 主要对比目标

| 应用 | 版本 | 可用资源 | 测试方式 |
|------|------|---------|---------|
| **静读天下 (Moon+ Reader Pro)** | v9.7 | 本地 APK + 反编译源码 | 模拟器 + 代码分析 |
| **LinReads** | Dev build #126 | 完整源码 | 模拟器 + 代码审查 |
| **KOReader** | - | 文档参考 | 特性对比 |
| **Readium (参考)** | - | 架构文档 | 技术路线对比 |

### 1.2 次要参考

- Librera (开源，MuPDF 基础)
- FBReader (经典开源阅读器)
- Kindle App (封闭生态参考)
- 微信读书 (在线为主，参考排版)

---

## 二、对比维度矩阵

### 2.1 核心阅读体验 (权重 40%)

| 维度 | 具体指标 | LinReads 状态 | 静读天下参考 | 对比方法 |
|------|---------|--------------|-------------|---------|
| **排版质量** | 行高 ≥1.6, 字号 ≥16sp, 对比度 ≥4.5:1 | 待验证 | 代码: `lineSpacingMultiplier="1.2"` | 模拟器截图对比 |
| **字体支持** | 系统字体 + 自定义字体 | Android 支持系统字体 | 支持导入字体 | 功能清单 |
| **翻页流畅度** | 帧率 ≥30fps, 动画流畅 | 已实现 ViewPager2 | 自定义翻页引擎 | 性能测试 |
| **进度保存** | 本地持久化 + 2s debounce | ✅ 已实现 | ✅ 成熟 | 功能验证 |
| **夜间模式** | 背景/文字色彩科学性 | 待审计 | 多套主题 | 对比度检测 |

### 2.2 格式支持 (权重 25%)

| 格式 | LinReads | 静读天下 | 对比结果 |
|------|---------|---------|---------|
| **EPUB** | ✅ 原生重排 (jsoup→AnnotatedString) | ✅ 成熟引擎 | LinReads 新引擎待验证 |
| **PDF** | ✅ PdfRenderer | ⚠️ 已知崩溃问题 (AVD) | LinReads 优势 |
| **TXT** | ✅ TxtVirtualPager (大文件优化) | ✅ 成熟 | 待性能对比 |
| **MOBI/AZW3** | ❌ 未支持 | ✅ 支持 | 静读天下优势 |
| **DOCX/CBZ** | ⚠️ MuPDF optional | ✅ 支持 | 功能覆盖差距 |
| **Fixed Layout EPUB** | ❌ 检测但不支持 | ✅ 支持 | 静读天下优势 |

### 2.3 离线功能完整性 (权重 20%)

| 功能 | LinReads | 静读天下 | 对比 |
|------|---------|---------|------|
| **书签** | ✅ 本地存储 | ✅ 成熟 | 基本对齐 |
| **标注/高亮** | ✅ Ink 系统 | ✅ 丰富功能 | 待功能深度对比 |
| **目录导航** | ✅ TOC 解析 | ✅ 成熟 | 基本对齐 |
| **全文搜索** | ✅ 已实现 | ✅ 成熟 | 待性能对比 |
| **字典查词** | ❌ 未实现 | ✅ 内置词典 | 静读天下优势 |
| **TTS 朗读** | ⚠️ 扩展系统规划 | ✅ 内置 | 功能差距 |
| **批注导出** | ❌ 未实现 | ✅ 支持 | 静读天下优势 |

### 2.4 交互与手势 (权重 10%)

| 交互 | LinReads | 静读天下 | 验证方法 |
|------|---------|---------|---------|
| **翻页手势** | 左右滑动 | 左右滑动 + 可自定义区域 | 模拟器操作 |
| **点击区域** | 待验证 | 左 1/3 上翻, 右 2/3 下翻 | 代码+实测 |
| **捏合缩放** | 字号调节 | 支持 | 功能验证 |
| **长按选择** | ✅ 文字选择 | ✅ 成熟 | 功能验证 |
| **双击全屏** | 待验证 | ✅ 支持 | 功能验证 |
| **音量键翻页** | 待验证 | ✅ 可配置 | 功能清单 |

### 2.5 无障碍与可访问性 (权重 5%)

| 指标 | LinReads | 静读天下 | 验证方法 |
|------|---------|---------|---------|
| **TalkBack 支持** | ✅ XML 节点暴露良好 | ⚠️ 弱 | AVD TalkBack 测试 |
| **字体缩放** | ✅ sp 单位 | ✅ 支持 | 系统设置测试 |
| **高对比度** | 待验证 | ✅ 支持 | 视觉检查 |
| **触摸目标大小** | ≥48dp (Material 规范) | 待测量 | 布局审查 |

---

## 三、已完成的对比工作

### 3.1 从现有审计文档获得的结论

**来源**: `docs/research/moonreader-linreads-extreme-reading-comparison.md`

#### S1-S8 AVD 对比结果摘要

| 场景 | LinReads 表现 | 静读天下表现 | 结论 |
|------|--------------|-------------|------|
| **S1 冷启动** | 低摩擦，所有格式打开 | EPUB/TXT 可读，PDF 崩溃 | LinReads PDF 优势 |
| **S2 EPUB 分页** | 无空白页问题 | 无空白页 | 平手 |
| **S3 低视力排版** | XML 文本暴露好，页面密度高 | 视觉可读，XML 弱 | LinReads 无障碍优势 |
| **S4 进度锚点** | 立即模式切换保持，重启丢失 | 重启保持更好 | 静读天下优势 (已修复) |
| **S5 手势** | 左右滑动 + tap 区域，XML 可证 | 基本手势 + 工具栏切换 | 基本对齐 |
| **S6 PDF** | 正常打开并翻页 | 崩溃 | LinReads 优势 |
| **S7 搜索/书签** | TOC/书签可达 | 搜索对话框可达 | 功能深度都待验证 |
| **S8 无障碍** | 文本节点暴露好 | 主要暴露资源 ID | LinReads 优势 |

**关键改进**: LinReads 已修复 S4 进度锚点问题 (stable local import IDs)

### 3.2 从外部对标审计获得的架构洞察

**来源**: `docs/audit/external-benchmark-audit-2026-06-19.md`

#### LinReads 的技术优势

1. **架构现代化**: 统一 ReaderEngine 接口 + per-format override (对齐 KOReader)
2. **EPUB 引擎**: 原生重排，避开 WebView + nanohttpd 的老旧路线 (Readium 3.x 已弃用)
3. **PDF 策略**: 系统 PdfRenderer，避开 MuPDF AGPL 授权问题
4. **进度同步设计**: LWW + progression 主键 (对齐 Readium Locator)
5. **Compose Hybrid**: 原生 View 渲染 + Compose Chrome (避开 Readium Compose 桥接痛点)

#### 与静读天下的技术对比

| 维度 | LinReads | 静读天下 | 评价 |
|------|---------|---------|------|
| **EPUB 引擎** | jsoup + Compose AnnotatedString | 自定义 MRTextView | LinReads 更现代 |
| **PDF 引擎** | PdfRenderer (系统) | 自定义 (AVD 有崩溃) | LinReads 更稳定 |
| **架构模式** | MVI + Koin | 传统 MVP | LinReads 更现代 |
| **代码质量** | Kotlin + 现代工具链 | Java + 传统工具 | LinReads 优势 |
| **无障碍** | TalkBack 友好 | 较弱 | LinReads 优势 |

---

## 四、模拟器测试计划

### 4.1 测试环境准备

```bash
# 1. 准备 AVD
# 已有: readflow_test(AVD) - 16 (Android 16 / API 36)
emulator -avd readflow_test -no-snapshot-load

# 2. 安装 LinReads
cd android
./gradlew -Preadflow.phase=2 assembleDebug
adb install -r app/build/outputs/apk/phase2/debug/app-phase2-debug.apk

# 3. 安装静读天下 (如果有本地 APK)
adb install moonreader-pro.apk

# 4. 推送测试文件
adb push test-assets/extreme-epubs /sdcard/Download/
adb push docs/test-books /sdcard/Download/
```

### 4.2 测试用例设计

#### TC-01: 冷启动与首次打开

**目标**: 对比首次打开 EPUB/PDF/TXT 的流畅度和初始体验

**步骤**:
1. 清除应用数据
2. 通过文件管理器打开测试 EPUB
3. 记录: 打开时间、初始渲染、是否有引导

**LinReads 预期**: 直接打开，无多余引导  
**静读天下预期**: 可能有首次设置向导

#### TC-02: 排版质量审查

**目标**: 对比文字排版的舒适度

**测试文件**: `pride-prejudice.epub` (标准英文), `红楼梦.epub` (中文)

**检查项**:
- [ ] 行高 (期望 ≥1.6)
- [ ] 字号 (期望 ≥16sp)
- [ ] 段间距 (期望 ≥1em)
- [ ] 对比度 (期望 ≥4.5:1)
- [ ] 字体渲染 (抗锯齿、Hinting)

**方法**: 
```bash
# 1. 截图
adb shell screencap -p /sdcard/linreads-typography.png
adb pull /sdcard/linreads-typography.png

# 2. 使用对比度检测工具
# https://webaim.org/resources/contrastchecker/

# 3. 并排对比截图
```

#### TC-03: 翻页性能

**目标**: 对比翻页流畅度

**方法**:
```bash
# 使用 systrace 记录翻页帧率
adb shell "while true; do input swipe 800 500 200 500 100; sleep 1; done" &
TRACE_PID=$!

# 记录 10 秒
python3 $ANDROID_HOME/platform-tools/systrace/systrace.py \
  -o linreads-page-turn.html -t 10 gfx view

kill $TRACE_PID

# 分析帧率 (期望 ≥30fps)
```

#### TC-04: 大文件压力测试

**目标**: 测试 50MB+ EPUB 的打开速度和内存占用

**测试文件**: 使用之前生成的 `mega-novel-500ch.epub` (待生成)

**检查项**:
- [ ] 打开时间 (<3s)
- [ ] 内存峰值 (<300MB)
- [ ] 翻页无卡顿

**方法**:
```bash
# 监控内存
adb shell "while true; do dumpsys meminfo dev.readflow | grep TOTAL; sleep 1; done"

# 记录打开时间
time adb shell am start -a android.intent.action.VIEW \
  -d file:///sdcard/Download/mega-novel-500ch.epub
```

#### TC-05: 极端排版场景

**目标**: 测试特殊排版的处理

**测试文件**: 使用之前生成的极端测试文件
- `deep-nested-100.epub` - 深层嵌套
- `single-para-50k.epub` - 超长段落
- `image-heavy-20.epub` - 图片密集

**检查**: 是否崩溃、渲染是否正确、性能如何

#### TC-06: 进度保存与恢复

**目标**: 验证阅读进度的可靠性

**步骤**:
1. 打开一本书，翻到中间位置
2. 返回书库
3. 重新打开，检查位置是否保持
4. 强制停止应用
5. 重新打开，检查位置是否保持

**LinReads 预期**: 步骤 3 保持 ✅, 步骤 5 保持 ✅ (已修复)  
**静读天下预期**: 步骤 3/5 都保持 ✅

#### TC-07: 手势响应

**目标**: 测试各种手势的响应速度

**手势清单**:
- [ ] 左滑翻页 (上一页)
- [ ] 右滑翻页 (下一页)
- [ ] 点击左 1/3 (上一页)
- [ ] 点击右 2/3 (下一页)
- [ ] 点击中央 (显示/隐藏工具栏)
- [ ] 长按选择文字
- [ ] 捏合缩放

**方法**: 手动测试 + 录屏对比响应时间

#### TC-08: TalkBack 无障碍测试

**目标**: 验证屏幕阅读器支持

**步骤**:
1. 启用 TalkBack
2. 进入书库
3. 打开一本书
4. 逐元素导航
5. 检查是否能听到书名、作者、内容

**方法**:
```bash
# 启用 TalkBack
adb shell settings put secure enabled_accessibility_services \
  com.google.android.marvin.talkback/.TalkBackService

# 录制屏幕 + 音频
adb shell screenrecord --verbose /sdcard/talkback-test.mp4

# 测试后查看 XML 层次
adb shell uiautomator dump
adb pull /sdcard/window_dump.xml
```

---

## 五、代码级对比分析

### 5.1 静读天下代码参考点

基于反编译代码 (`moonreader-decompiled/`) 的关键发现：

#### 排版参数

```bash
# 查找行间距设置
grep -r "lineSpacing\|setLineSpace" moonreader-decompiled/sources/com/flyersoft/ \
  --include="*.java" | head -20
```

**已知参数**:
- 默认行高: `lineSpacingMultiplier="1.2"` (res/layout/about.xml)
- 字号: `textSize="14.0sp"` ~ `16.0sp`
- 自定义设置: `A.setLineSpace(MRTextView)` 方法

#### 翻页引擎

```bash
# 查找翻页相关代码
find moonreader-decompiled/sources -name "*.java" | \
  xargs grep -l "PageTurn\|CurlGesture\|SimpleGesture" | head -10
```

**关键类**:
- `ActivityTxt.java` - 主阅读器 Activity
- `MRTextView.java` - 自定义文本渲染 View
- `MyCurlGesture` / `MySimpleGesture` - 手势处理

#### PDF 引擎

```bash
# 查找 PDF 相关
grep -r "PDFReader\|PdfRenderer" moonreader-decompiled/sources/ \
  --include="*.java" | head -10
```

**发现**: `PDFReader.java` - 独立 PDF 渲染器 (非 MuPDF)

### 5.2 LinReads 代码审查重点

#### EPUB 引擎实现

**文件**: `android/render/epub/src/main/kotlin/dev/readflow/render/epub/`

**关键类**:
- `EpubParser.kt` - jsoup 解析 → EpubReaderItem
- `EpubReflowEngine.kt` - Compose AnnotatedString 渲染
- `EpubParserSafety.kt` - 安全边界 (DOM 深度、ZIP 大小)

**排版参数** (待验证):
```kotlin
// 需要检查实际值
val lineHeight: Float = ??? // 期望 ≥1.6
val fontSize: Float = ??? // 期望 ≥16sp
val paragraphSpacing: Float = ??? // 期望 ≥1em
```

#### PDF 引擎实现

**文件**: `android/render/pdf/src/main/kotlin/dev/readflow/render/pdf/`

**技术栈**: Android PdfRenderer (系统 API, minSdk 26)

**优势**: 系统级稳定性，无第三方依赖

#### TXT 引擎实现

**文件**: `android/render/txt/src/main/kotlin/dev/readflow/render/txt/`

**关键**: `TxtVirtualPager` - 大文件虚拟化分页 (~300 行)

**待验证**: 50MB+ 文件性能

---

## 六、特性对比清单

### 6.1 功能完整性评分

| 功能类别 | LinReads | 静读天下 | 备注 |
|---------|---------|---------|------|
| **基础阅读** | 85% | 95% | LinReads 缺 MOBI/Fixed Layout |
| **离线管理** | 80% | 90% | LinReads 缺字典/TTS |
| **标注高亮** | 75% | 90% | LinReads Ink 系统待验证功能深度 |
| **搜索导航** | 85% | 90% | 基本功能对齐 |
| **无障碍** | 90% | 60% | LinReads TalkBack 更好 |
| **性能稳定性** | 85% | 80% | LinReads PDF 更稳定，静读天下更成熟 |

### 6.2 用户体验评分矩阵

| 体验维度 | 权重 | LinReads | 静读天下 | 说明 |
|---------|------|---------|---------|------|
| 首次使用门槛 | 10% | 9/10 | 7/10 | LinReads 更简洁 |
| 阅读舒适度 | 25% | ?/10 | 8/10 | 待排版审计 |
| 翻页流畅度 | 15% | ?/10 | 8/10 | 待性能测试 |
| 功能丰富度 | 20% | 6/10 | 9/10 | 静读天下更全面 |
| 稳定性 | 15% | 8/10 | 7/10 | LinReads PDF 优势 |
| 无障碍 | 10% | 9/10 | 6/10 | LinReads TalkBack 优势 |
| 启动速度 | 5% | ?/10 | 8/10 | 待测试 |
| **总分** | 100% | **?/10** | **7.8/10** | LinReads 待测试项多 |

---

## 七、待执行任务清单

### 7.1 高优先级 (P0)

- [ ] 生成大文件测试语料 (50MB EPUB, 500 章节)
- [ ] 模拟器安装双端并推送测试文件
- [ ] 执行 TC-01 ~ TC-08 测试用例
- [ ] 截图对比排版质量
- [ ] 性能数据采集 (帧率、内存、启动时间)

### 7.2 中优先级 (P1)

- [ ] LinReads 排版参数代码审查
- [ ] 对比度自动检测脚本
- [ ] TalkBack 完整流程录屏
- [ ] 功能深度对比 (书签/标注/搜索)
- [ ] 手势响应时间量化

### 7.3 低优先级 (P2)

- [ ] 下载更多竞品 APK (FBReader, Librera)
- [ ] 真机测试 (替代模拟器)
- [ ] 用户访谈 (收集真实反馈)
- [ ] 长期使用体验跟踪

---

## 八、输出物规划

### 8.1 对比报告文档

**文件**: `docs/research/offline-reading-ux-comparison-report.md`

**结构**:
1. 执行摘要
2. 测试环境与方法
3. 逐维度对比结果
4. 截图证据
5. 性能数据图表
6. LinReads 改进建议清单
7. 结论与优先级

### 8.2 改进任务 Backlog

**文件**: `docs/tracking/ux-improvement-backlog.md`

**格式**:
```markdown
- [ ] **P0-UX-1**: 修正行高为 1.6 (当前 ?)
- [ ] **P0-UX-2**: 确保日间模式对比度 ≥4.5:1
- [ ] **P1-UX-3**: 实现点击区域自定义 (参考静读天下)
- [ ] **P1-UX-4**: 添加音量键翻页支持
- [ ] **P2-UX-5**: 集成离线词典
```

### 8.3 测试证据归档

**目录**: `docs/research/ux-comparison-evidence/`

**内容**:
- 截图对比 (LinReads vs 静读天下)
- 性能图表 (帧率、内存曲线)
- TalkBack 录屏
- Systrace 分析结果
- 测试日志

---

## 九、执行时间线

| 阶段 | 预计时间 | 产出 |
|------|---------|------|
| **Phase 1: 环境准备** | 1 小时 | 模拟器就绪、测试文件就位 |
| **Phase 2: 基础测试** | 2 小时 | TC-01~TC-04 执行完成 |
| **Phase 3: 深度对比** | 2 小时 | TC-05~TC-08 + 代码分析 |
| **Phase 4: 数据分析** | 1 小时 | 性能图表、对比表格 |
| **Phase 5: 报告撰写** | 2 小时 | 完整对比报告 |
| **总计** | **8 小时** | 完整调研报告 + 改进清单 |

---

## 十、风险与限制

### 10.1 已知限制

1. **模拟器非真机**: 性能数据可能不准确
2. **静读天下版本**: v9.7 可能不是最新版
3. **测试覆盖**: 无法测试所有功能组合
4. **主观评价**: 排版舒适度有主观性

### 10.2 风险缓解

- 使用标准化测试用例减少主观性
- 多轮测试取平均值
- 与已有 AVD 对比数据交叉验证
- 明确标注"模拟器数据，待真机验证"

---

**准备完成，等待执行指令。**
