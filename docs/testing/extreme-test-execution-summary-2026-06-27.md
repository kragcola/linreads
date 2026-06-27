# 极端情况离线阅读测试 — 执行总结

> **执行日期**: 2026-06-27
> **测试版本**: LinReads Android v4 (开发中)
> **执行范围**: Android EPUB 解析器
> **执行状态**: ✅ Phase 1 完成

---

## 一、已完成任务

### 1.1 测试方案文档
✅ 创建了完整的测试方案: [docs/testing/extreme-offline-reading-test-plan.md](docs/testing/extreme-offline-reading-test-plan.md)

**内容覆盖**:
- 5 个测试维度矩阵（内容/排版/交互/设备/环境）
- 54 个极端场景定义
- 10 个已识别代码缺陷（Android EPUB）
- 8 个已识别缺陷（Web Reader）
- 测试资产生成脚本规范
- 验收标准和报告模板

### 1.2 测试文件生成工具
✅ 创建了 4 个 Python 测试生成器:

| 脚本 | 功能 | 输出 |
|------|------|------|
| `gen_deep_nested_epub.py` | 深层嵌套 HTML | 触发 DOM 深度限制 |
| `gen_malformed_epub.py` | 畸形 EPUB | 缺少 toc/nav/spine |
| `gen_minimal_epub.py` | 极小 EPUB | 边界测试（3 段落）|
| `gen_long_paragraph_epub.py` | 超长段落 | 单段 50000 字无换行 |

### 1.3 测试资产生成
✅ 成功生成 5 个极端测试 EPUB:

```bash
test-assets/extreme-epubs/
├── deep-nested-97.epub      # 97 层嵌套（接近 96 层限制）
├── deep-nested-100.epub     # 100 层嵌套（超过限制）
├── no-toc.epub              # 缺少目录组件
├── single-para-50k.epub     # 50000 字单段落
└── tiny-3para.epub          # 最小化 EPUB (1KB)
```

### 1.4 Android 单元测试
✅ 创建了 `EpubExtremeTest.kt` 包含 7 个测试用例:

1. `parse deep nested HTML without StackOverflow - 97 layers`
2. `parse deep nested HTML without StackOverflow - 100 layers`
3. `parse malformed EPUB without toc gracefully`
4. `parse minimal EPUB with 3 paragraphs`
5. `parse long single paragraph without OOM`
6. `readBoundedBytes returns null when exceeding limit`
7. `epubParserGuard catches all exceptions`

**编译状态**: ✅ 通过
**执行状态**: ✅ BUILD SUCCESSFUL in 25s

---

## 二、关键发现

### 2.1 代码层缺陷（Android EPUB 解析器）

| ID | 严重性 | 位置 | 描述 | 状态 |
|-----|--------|------|------|------|
| **A-EPUB-1** | 🔴 HIGH | `EpubParser.kt:30` | 超过 10000 ZIP 条目无友好提示 | 📋 已记录 |
| **A-EPUB-2** | 🟠 MEDIUM | `EpubParser.kt:109` | spine 超限静默失败 | 📋 已记录 |
| **A-EPUB-3** | 🟡 LOW | `EpubParserSafety.kt:11` | DOM 深度 96 层边界未充分测试 | ✅ 测试覆盖 |
| **A-EPUB-4** | 🟠 MEDIUM | `EpubParser.kt:240` | Fixed Layout 检测后无用户提示 | 📋 已记录 |
| **A-EPUB-5** | 🔴 HIGH | `EpubParserSafety.kt:73-84` | `readBoundedBytes` 超限返回 null | ✅ 测试覆盖 |
| **A-EPUB-9** | 🔴 HIGH | `EpubParserSafety.kt:56-57` | XXE 防御不完整 | 📋 已记录 |

### 2.2 Web 端关键缺陷

| ID | 严重性 | 描述 | 影响 |
|-----|--------|------|------|
| **W-READER-1** | 🔴 HIGH | epubjs 初始化无错误处理 | 破损文件导致崩溃 |
| **W-READER-2** | 🔴 HIGH | 无进度保存逻辑 | 刷新丢失阅读位置 |
| **W-READER-7** | 🔴 HIGH | 无离线存储 | 断网无法阅读 |

### 2.3 现有防御机制（已验证）

✅ **安全边界**:
- `EPUB_MAX_ZIP_ENTRIES = 10,000` — 防止 ZIP 炸弹
- `EPUB_MAX_SPINE_ENTRY_BYTES = 2MB` — 单章节大小限制
- `EPUB_MAX_DOM_DEPTH = 96` — 防止堆栈溢出
- `sanitizeEpubXml()` — 移除 DOCTYPE/ENTITY

✅ **异常容错**:
- `epubParserGuard` 捕获: StackOverflowError, OutOfMemoryError, RuntimeException
- `ZipFile.use {}` 自动关闭资源
- 解析失败返回空结构（不崩溃）

---

## 三、测试执行建议

### 3.1 立即执行（命令行可用）

```bash
# 1. 运行现有测试套件（包括新增极端测试）
./gradlew :render:epub:test

# 2. 生成测试覆盖率报告
./gradlew :render:epub:jacocoTestReport

# 3. 查看报告
open render/epub/build/reports/tests/testDebugUnitTest/index.html
```

### 3.2 真机测试（需要物理设备）

**前提**: 测试文件需推送到设备

```bash
# 1. 构建 debug APK
./gradlew -Preadflow.phase=2 assembleDebug

# 2. 安装到设备
adb install -r app/build/outputs/apk/phase2/debug/app-phase2-debug.apk

# 3. 推送测试文件
adb push ../test-assets/extreme-epubs /sdcard/Download/LinReads-Test/

# 4. 手动测试流程
# - 打开每个测试 EPUB
# - 观察: 打开时间 / 内存占用 / UI 响应 / 崩溃
# - 记录到 docs/testing/extreme-test-report-2026-06-27.md
```

### 3.3 排版质量验证（需要真机）

**工具**: Accessibility Scanner

```bash
# 1. 安装无障碍扫描器
adb install AccessibilityScanner.apk

# 2. 打开标准测试书（如傲慢与偏见）

# 3. 检查清单:
# [ ] 正文字号 ≥16sp
# [ ] 行高 ≥1.6
# [ ] 对比度 ≥4.5:1 (使用 Contrast Checker 工具)
# [ ] 触摸目标 ≥48dp
# [ ] TalkBack 可用
```

---

## 四、下一步行动

### 4.1 P0 修复（阻塞发布）

1. **A-EPUB-9**: 加固 XXE 防御
   ```kotlin
   // 在 Jsoup.parse 前添加额外检查
   if (xml.contains("<!DOCTYPE") && xml.contains("[")) {
       return null  // 拒绝包含内部子集的 DOCTYPE
   }
   ```

2. **A-EPUB-5**: 加强 null 检查
   ```kotlin
   val bytes = readBoundedBytes(maxBytes)
   if (bytes == null) {
       Log.w("EpubParser", "Entry exceeded $maxBytes bytes: $path")
       return null
   }
   ```

3. **W-READER-2**: 实现进度保存
   ```typescript
   // 使用 localStorage 保存当前 CFI
   book.on('relocated', (location) => {
     localStorage.setItem(`book-${id}-position`, location.start.cfi)
   })
   ```

### 4.2 P1 增强（用户体验）

4. **A-EPUB-1**: 友好错误提示
   ```kotlin
   if (zip.size() > EPUB_MAX_ZIP_ENTRIES) {
       throw ReadflowException(
           kind = ReadflowError.Kind.PARSING_FAILED,
           message = "EPUB 文件结构异常（条目过多）",
       )
   }
   ```

5. **A-EPUB-4**: Fixed Layout 检测提示
   ```kotlin
   if (pkg.isFixedLayout) {
       // 返回特殊错误类型，UI 层显示提示
       return EpubBook(isFixedLayout = true, ...)
   }
   ```

### 4.3 测试覆盖扩展

6. 生成更多测试文件:
   - 超大 EPUB (50MB, 500 章节)
   - 图片密集 EPUB (200 张图)
   - 破损 ZIP (CRC 错误)
   - XXE 攻击 payload

7. 添加性能基准测试:
   ```kotlin
   @Test
   fun `parse 50MB EPUB within 3 seconds`() {
       val start = System.currentTimeMillis()
       parser.parseBook(largeEpub)
       val elapsed = System.currentTimeMillis() - start
       assertTrue(elapsed < 3000) { "解析时间 ${elapsed}ms 超过 3s" }
   }
   ```

---

## 五、交付物清单

### 5.1 文档
- ✅ [测试方案](docs/testing/extreme-offline-reading-test-plan.md) — 54 个场景，完整验收标准
- ✅ 本执行总结 — 记录当前进度和发现

### 5.2 代码
- ✅ `scripts/extreme-test-generators/` — 4 个测试文件生成器
- ✅ `android/render/epub/src/test/.../EpubExtremeTest.kt` — 7 个极端测试用例
- ✅ `test-assets/extreme-epubs/` — 5 个测试 EPUB 文件

### 5.3 待补充
- ⏳ 真机测试报告（需要物理设备）
- ⏳ 排版质量审计报告（需要 Accessibility Scanner）
- ⏳ 性能基准数据（内存/CPU/帧率）
- ⏳ 与静读天下对比截图

---

## 六、风险提示

### 6.1 测试覆盖缺口

1. **Web 端未测试** — `Reader.tsx` 存在多个高危缺陷但未验证
2. **HarmonyOS 端未实现** — EPUB 阅读器待开发
3. **真实大文件未测试** — 当前最大测试文件仅 1.6KB
4. **图片渲染未测试** — 内存压力场景缺失
5. **多用户/分屏未测试** — Android 高级场景未覆盖

### 6.2 已知但未修复缺陷

| 缺陷 | 潜在影响 | 触发条件 |
|------|---------|---------|
| A-EPUB-9 | XXE 安全漏洞 | 恶意构造的 EPUB |
| W-READER-2 | 进度丢失 | Web 端刷新/关闭 |
| W-READER-7 | 离线不可用 | Web 端断网 |

---

## 附录：快速测试命令

```bash
# A. 生成所有测试文件
for script in scripts/extreme-test-generators/*.py; do
    python3 "$script" --help
done

# B. 运行所有 Android 测试
./gradlew test

# C. 查看测试覆盖率
./gradlew jacocoTestReport
open render/epub/build/reports/jacoco/test/html/index.html

# D. 检查代码质量
./gradlew lint
open app/build/reports/lint-results.html
```

---

**建议下一步**: 执行真机测试并补充性能数据，完成 P0 缺陷修复后发布 v4.1。
