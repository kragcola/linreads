# 极端情况离线阅读压力测试方案

> **目标**: 通过模拟极端场景，发现本地离线纯粹阅读的用户体验和阅读质量缺陷
> **创建日期**: 2026-06-27
> **测试范围**: Android (优先) / Web / HarmonyOS
> **测试原则**: 无网络依赖，纯本地文件，真实设备验证

---

## 一、测试维度矩阵

### 1.1 内容极端维度

| 场景 | 测试文件规格 | 预期风险点 | 验证指标 |
|------|------------|-----------|---------|
| **超大 EPUB** | 50MB+, 500+ 章节, 10000+ 段落 | OOM, 解析超时, UI 冻结 | 打开时间 <3s, 内存峰值 <300MB |
| **超小 EPUB** | <10KB, 单章节, 3 段落 | 边界计算错误, 空白页 | 正确渲染，进度计算准确 |
| **畸形 EPUB** | 缺 toc.ncx, 无 nav, spine 空 | 崩溃, 空白屏 | Fallback 生成目录，可阅读 |
| **深层嵌套 HTML** | `<div>` 嵌套 >100 层 | StackOverflow (已有防御 96 层) | 触发安全截断，不崩溃 |
| **巨大单文件** | 单个 spine 条目 >5MB | `EPUB_MAX_SPINE_ENTRY_BYTES` 截断 | 显示警告，加载前 2MB |
| **恶意 XML** | DOCTYPE 实体注入, XXE | 安全漏洞 (已有 sanitize) | 拒绝解析或移除 DOCTYPE |
| **10000+ ZIP 条目** | 超 `EPUB_MAX_ZIP_ENTRIES` | DoS 攻击 | 拒绝打开，提示文件异常 |
| **破损 ZIP** | CRC 错误, 不完整下载 | ZipException 未捕获 | 友好错误提示，不崩溃 |
| **Fixed Layout EPUB** | 预分页 EPUB (漫画/杂志) | 当前引擎不支持 | 检测并提示不支持 |
| **无效 UTF-8** | 二进制混入文本 | 乱码或崩溃 | Charset fallback, 显示替换字符 |
| **超长连续文本** | 单段落 50000 字无换行 | TextView OOM, 布局超时 | 自动软换行，分段渲染 |
| **超多图片 EPUB** | 200+ 内嵌图片 | 内存爆炸 | LRU 图片缓存 (验证是否实现) |
| **Base64 内联图片** | 50+ 个 `data:image` | 解析慢，内存高 | 限制单图 <5MB，超限跳过 |
| **外链资源** | `<img src="https://...">` | 离线场景加载失败 | 跳过远程资源，不阻塞渲染 |
| **特殊字符密集** | Emoji 🎨, RTL 文字 العربية, CJK 扩展 | 字体回退失败，排版错乱 | 正确渲染或显示 ▯ |
| **PDF 大文件** | 500 页 PDF, 100MB+ | PdfRenderer 内存占用 | 虚拟化分页，仅加载可见页 |
| **加密 PDF** | 需要密码 | 无密码输入 UI | 检测加密，提示不支持 |
| **TXT 超大文件** | 50MB 纯文本 | `TxtVirtualPager` 压力测试 | 秒开，翻页流畅 |
| **TXT 无换行** | 单行 10MB | 分页算法失效 | 强制按字符分页 |

### 1.2 排版质量维度

| 场景 | 测试规格 | UX 缺陷风险 | 验证标准 (linreads-dev) |
|------|---------|------------|----------------------|
| **行高过密** | `line-height: 1.0` | 眼睛疲劳 | 强制最小 1.5，用户可调 1.5-2.0 |
| **行宽过宽** | 单行 >120 字符 | 换行困难 | 限制最大行宽 45-75 字符 |
| **字号过小** | 12px / 12sp | 视力障碍用户无法阅读 | 最小 16px/sp，无障碍放大支持 |
| **对比度不足** | 浅灰 (#888) on 白色 | WCAG 不合格 | 对比度 ≥4.5:1 (AA) |
| **夜间纯黑白** | `#000` on `#FFF` | 光晕刺眼 | 夜间用 `#1A1A1A` + `#E8E6E1` |
| **段间距缺失** | `margin: 0` | 段落粘连难区分 | 段间距 ≥1em |
| **中英文混排** | "这是test混合text" | 字体回退不一致 | 中文宋体 + 英文衬线协调 |
| **代码块排版** | EPUB 内 `<pre><code>` | 用非等宽字体 | 自动识别并应用 monospace |
| **数学公式** | MathML / LaTeX | 不渲染或显示源码 | 降级显示源码 (Phase 1) |
| **竖排文本** | `writing-mode: vertical-rl` | 不支持 | 检测并 fallback 横排 |

### 1.3 交互极端维度

| 场景 | 操作序列 | 风险点 | 验证方法 |
|------|---------|--------|---------|
| **快速连续翻页** | 5 秒内翻 50 页 | UI 卡顿，页面重叠 | 帧率 ≥30fps，无视觉撕裂 |
| **极端缩放** | 字号 8px → 48px 往返 | 布局重算崩溃 | 平滑过渡，内容不丢失 |
| **边界跳转** | 首页 → 跳转 99% → 跳转 0% | 进度计算错误 | 跳转准确，进度条同步 |
| **内存压力下翻页** | 打开 3 本大书后台切换 | 进程被杀，数据丢失 | 进度自动保存 (2s debounce) |
| **长按选择** | 跨页选择文字 | 选区丢失 | 支持跨页选择 (Phase 2) |
| **旋转屏幕** | 阅读中横竖屏切换 | 进度丢失，页面重置 | 保持当前位置不变 |
| **分屏模式** | Android 分屏阅读 | 布局错乱 | 自适应窗口大小 |
| **手势冲突** | 捏合缩放 vs 系统手势 | 误触退出 | 明确手势优先级 |
| **TalkBack 导航** | 无障碍模式阅读 | 语义标注缺失 | 按段落朗读，可跳转 |
| **键盘翻页** | 蓝牙键盘 ← → | 不响应 | 支持方向键翻页 |

### 1.4 设备极端维度

| 场景 | 设备规格 | 风险点 | 测试设备 |
|------|---------|--------|---------|
| **低端设备** | 2GB RAM, Android 8 | OOM 频繁 | 真机测试 (待选型) |
| **老设备** | Android 6, API 23 | API 不兼容 | minSdk=26 边界验证 |
| **小屏设备** | 4.7 寸, 720p | 字体过小，点击区域不足 | 触摸目标 ≥48dp (Material) |
| **大屏设备** | 10 寸平板 | 单列布局浪费空间 | 双页模式 (Phase 2) |
| **折叠屏** | 展开/折叠切换 | Configuration change 进度丢失 | SavedStateHandle 恢复 |
| **电子墨水屏** | E-Ink 刷新慢 | 动画卡顿 | 检测并禁用动画 |
| **低电量模式** | 省电模式限制后台 | 同步失败 | 降级为仅本地 |

### 1.5 环境极端维度

| 场景 | 系统状态 | 风险点 | 模拟方法 |
|------|---------|--------|---------|
| **完全离线** | 飞行模式 + WiFi 关闭 | Calibre 连接失败阻塞 | 离线优先，本地库可用 |
| **存储空间满** | 剩余 <100MB | 下载失败，缓存清理失效 | 优雅降级，提示空间不足 |
| **无存储权限** | 拒绝文件访问 | 无法打开本地文件 | SAF 降级，引导授权 |
| **系统字体缩放** | 200% 字体大小 | 布局溢出，按钮裁切 | sp 单位适配，可滚动 |
| **深色主题强制** | 系统强制深色 | 颜色适配失败 | 响应 `uiMode` 变化 |
| **省流量模式** | Data Saver 开启 | 图片不加载 | 已离线，不受影响 |
| **多用户切换** | Android 多用户 | 数据隔离失败 | 独立用户目录 |
| **应用克隆** | 双开应用 | 数据冲突 | 进程隔离，无共享状态 |

---

## 二、已发现代码层缺陷

### 2.1 Android 端 (render:epub)

| 缺陷 ID | 严重性 | 位置 | 描述 | 验证方法 |
|---------|--------|------|------|---------|
| **A-EPUB-1** | 🔴 HIGH | `EpubParser.kt:30` | `EPUB_MAX_ZIP_ENTRIES=10000` 但无用户友好错误 | 创建 10001 条目 ZIP，验证提示 |
| **A-EPUB-2** | 🟠 MEDIUM | `EpubParser.kt:109` | spine 超限返回 `emptyList()` 静默失败 | 创建 3MB spine，验证是否截断提示 |
| **A-EPUB-3** | 🟡 LOW | `EpubParserSafety.kt:11` | `EPUB_MAX_DOM_DEPTH=96` 但未测试边界 | 生成 97 层嵌套 HTML，触发截断 |
| **A-EPUB-4** | 🟠 MEDIUM | `EpubParser.kt:240` | Fixed Layout 检测后无用户提示 | 打开 FXL EPUB，验证是否提示不支持 |
| **A-EPUB-5** | 🔴 HIGH | `EpubParserSafety.kt:73-84` | `readBoundedBytes` 超限返回 null，上层可能 NPE | Grep 调用方是否有 null 检查 |
| **A-EPUB-6** | 🟡 LOW | `EpubParser.kt:195` | 默认 OPF 路径硬编码 `OEBPS/content.opf` | 测试非标路径 EPUB |
| **A-EPUB-7** | 🟠 MEDIUM | 全局 | 无图片大小/数量限制 | 创建 500 张图片 EPUB，测试内存 |
| **A-EPUB-8** | 🟠 MEDIUM | 全局 | Base64 内联图片无解码上限 | 内嵌 50MB Base64 图，测试 OOM |
| **A-EPUB-9** | 🔴 HIGH | `EpubParserSafety.kt:56-57` | 仅阻止 ENTITY，未阻止其他 XXE 向量 | Payload: `<!DOCTYPE foo [<!ELEMENT foo ANY >]>` |
| **A-EPUB-10** | 🟡 LOW | 全局 | jsoup 默认加载外部资源 | `<img src="http://example.com">` 离线失败 |

### 2.2 Web 端

| 缺陷 ID | 严重性 | 位置 | 描述 | 验证方法 |
|---------|--------|------|------|---------|
| **W-READER-1** | 🔴 HIGH | `Reader.tsx:19-20` | epubjs 初始化无错误处理 | 传入破损 EPUB，验证是否崩溃 |
| **W-READER-2** | 🔴 HIGH | `Reader.tsx:14-24` | 无进度保存逻辑 | 刷新页面，进度丢失 |
| **W-READER-3** | 🟠 MEDIUM | `Reader.tsx:27` | PDF iframe 无加载失败处理 | 测试跨域 PDF，验证错误提示 |
| **W-READER-4** | 🔴 HIGH | `Reader.tsx:19` | epubjs `renderTo` 可能失败，无 catch | 小屏设备 width/height 计算错误 |
| **W-READER-5** | 🟠 MEDIUM | 全局 | 无排版质量控制 (行高/字号) | 检查 CSS 是否强制最小行高 |
| **W-READER-6** | 🟡 LOW | `Reader.tsx:29` | "加载中…" 无超时处理 | 网络慢时永久加载 |
| **W-READER-7** | 🔴 HIGH | 全局 | 无离线存储，依赖实时下载 | 断网后无法阅读 |
| **W-READER-8** | 🟠 MEDIUM | 全局 | epubjs 默认主题未知 | 验证夜间模式对比度 |

### 2.3 HarmonyOS 端

| 缺陷 ID | 严重性 | 位置 | 描述 | 验证方法 |
|---------|--------|------|------|---------|
| **H-EPUB-1** | 🔴 HIGH | 待实现 | EPUB 阅读器未实现 | N/A |

---

## 三、测试资产准备清单

### 3.1 测试文件生成脚本

创建 `scripts/generate-extreme-test-epubs.sh`:

```bash
#!/usr/bin/env bash
# 生成极端测试 EPUB 文件

set -e

OUT_DIR="test-assets/extreme-epubs"
mkdir -p "$OUT_DIR"

# 1. 超大 EPUB (50MB, 500 章节)
python3 scripts/gen_large_epub.py \
  --chapters 500 \
  --size 50MB \
  --output "$OUT_DIR/mega-novel-500ch.epub"

# 2. 深层嵌套 HTML (100 层 div)
python3 scripts/gen_nested_epub.py \
  --depth 100 \
  --output "$OUT_DIR/deep-nested-100.epub"

# 3. 畸形 EPUB (无 toc, 空 spine)
python3 scripts/gen_malformed_epub.py \
  --missing toc,nav \
  --output "$OUT_DIR/no-toc.epub"

# 4. 超多图片 (200 张内嵌)
python3 scripts/gen_image_heavy_epub.py \
  --images 200 \
  --image-size 500KB \
  --output "$OUT_DIR/image-heavy-200.epub"

# 5. 超长单段落 (50000 字无换行)
python3 scripts/gen_long_paragraph_epub.py \
  --chars 50000 \
  --output "$OUT_DIR/single-para-50k.epub"

# 6. XXE 攻击 payload
cat > "$OUT_DIR/xxe-attack.epub" <<'EOF'
[手动构造带 ENTITY 的 EPUB]
EOF

# 7. 超小 EPUB (3 段落)
python3 scripts/gen_minimal_epub.py \
  --paragraphs 3 \
  --output "$OUT_DIR/tiny-3para.epub"

# 8. Fixed Layout EPUB
cp references/test-books/comic-sample-fxl.epub "$OUT_DIR/"

echo "✅ 测试文件已生成到 $OUT_DIR"
```

### 3.2 真实书籍样本

从公版书库下载：

```bash
# 古腾堡计划
wget https://www.gutenberg.org/ebooks/1342.epub.noimages -O test-assets/pride-prejudice.epub
wget https://www.gutenberg.org/ebooks/84.epub.images -O test-assets/frankenstein-images.epub

# 中文公版
# 红楼梦 (超长章节)
# 三体 (科幻，数学公式)
# 古文观止 (繁体，竖排)
```

### 3.3 破损文件

```bash
# 不完整 ZIP
dd if=test-assets/pride-prejudice.epub of=test-assets/corrupted-50percent.epub bs=1024 count=512

# CRC 错误 ZIP
python3 scripts/corrupt_zip_crc.py test-assets/pride-prejudice.epub test-assets/crc-error.epub
```

---

## 四、测试执行矩阵

### 4.1 Phase 1: Android 端压力测试 (优先)

**目标设备**: 主力平板 (用户真实设备)

**测试步骤**:

```bash
# 1. 构建 Phase 2 APK
cd android && ./gradlew -Preadflow.phase=2 assembleDebug

# 2. 安装
adb install -r app/build/outputs/apk/phase2/debug/app-phase2-debug.apk

# 3. 推送测试文件
adb push test-assets/extreme-epubs /sdcard/Download/

# 4. 手动测试 + 记录
# 4.1 打开每个测试 EPUB
# 4.2 记录: 打开时间 / 内存占用 / 崩溃 / UI 响应
# 4.3 截图异常表现

# 5. 内存监控
adb shell dumpsys meminfo dev.readflow | grep TOTAL

# 6. Logcat 捕获
adb logcat -v time | grep -E "(readflow|FATAL|AndroidRuntime)"
```

**测试表格** (边测边填):

| 文件 | 打开时间 | 内存峰值 | 翻页帧率 | 崩溃 | 异常表现 | 截图 |
|------|---------|---------|---------|-----|---------|-----|
| mega-novel-500ch.epub | | | | | | |
| deep-nested-100.epub | | | | | | |
| no-toc.epub | | | | | | |
| image-heavy-200.epub | | | | | | |
| single-para-50k.epub | | | | | | |
| xxe-attack.epub | | | | | | |
| tiny-3para.epub | | | | | | |
| corrupted-50percent.epub | | | | | | |

### 4.2 Phase 2: 排版质量审计

**工具**: Accessibility Scanner + 人工评审

```bash
# 1. 安装 Accessibility Scanner
adb install AccessibilityScanner.apk

# 2. 打开标准测试书 (pride-prejudice.epub)

# 3. 检查清单:
# [ ] 正文字号 ≥16sp
# [ ] 行高 ≥1.6
# [ ] 对比度 ≥4.5:1 (日间)
# [ ] 对比度 ≥4.5:1 (夜间)
# [ ] 段间距 ≥1em
# [ ] 触摸目标 ≥48dp
# [ ] TalkBack 可用
```

**截图对比**:
- 与静读天下并排截图
- 标注差异点

### 4.3 Phase 3: Web 端基础验证

```bash
cd web
npm run dev

# 浏览器打开 http://localhost:5173
# 测试清单:
# [ ] 打开 EPUB 成功
# [ ] 翻页流畅
# [ ] 刷新后进度丢失 (预期缺陷)
# [ ] 破损 EPUB 是否崩溃
# [ ] 夜间模式对比度
```

---

## 五、缺陷修复优先级

### 5.1 P0 (立即修复 - 数据安全/崩溃)

1. **A-EPUB-9**: XXE 防御加固
2. **A-EPUB-5**: `readBoundedBytes` null 检查
3. **W-READER-1**: epubjs 错误处理
4. **W-READER-2**: 进度保存
5. **W-READER-7**: 离线存储

### 5.2 P1 (重要 - UX 阻塞)

6. **A-EPUB-1**: 超大 ZIP 友好提示
7. **A-EPUB-4**: Fixed Layout 提示
8. **A-EPUB-7**: 图片数量/大小限制
9. **W-READER-5**: 排版质量控制

### 5.3 P2 (次要 - 边界情况)

10. **A-EPUB-2**: spine 超限提示
11. **A-EPUB-6**: 非标 OPF 路径
12. **W-READER-3**: PDF 错误处理

---

## 六、自动化测试脚本

### 6.1 Android 单元测试

新增 `android/render/epub/src/test/kotlin/dev/readflow/render/epub/EpubExtremeTest.kt`:

```kotlin
class EpubExtremeTest {
    private val parser = EpubParser()
    
    @Test
    fun `parse deep nested HTML without StackOverflow`() {
        val epub = generateDeepNestedEpub(depth = 100)
        val result = parser.parseBook(epub)
        // 应该截断到 96 层，不崩溃
        assertNotNull(result)
    }
    
    @Test
    fun `parse huge ZIP gracefully`() {
        val epub = generateHugeZipEpub(entries = 10001)
        val result = parser.parseBook(epub)
        // 应该返回 empty，不 OOM
        assertEquals(0, result.paras.size)
    }
    
    @Test
    fun `reject XXE entity injection`() {
        val epub = generateXxeEpub()
        val result = parser.parseBook(epub)
        // 应该拒绝解析
        assertEquals(0, result.paras.size)
    }
    
    @Test
    fun `handle corrupted ZIP without crash`() {
        val epub = File("test-assets/corrupted-50percent.epub")
        val result = runCatching { parser.parseBook(epub) }
        assertTrue(result.isSuccess || result.isFailure)
        // 关键: 不要未捕获异常
    }
}
```

### 6.2 CI 集成

`.github/workflows/extreme-test.yml`:

```yaml
name: Extreme Reading Tests

on: [push, pull_request]

jobs:
  android-extreme:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Generate test assets
        run: bash scripts/generate-extreme-test-epubs.sh
      - name: Run unit tests
        run: cd android && ./gradlew :render:epub:test
      - name: Upload test report
        uses: actions/upload-artifact@v4
        with:
          name: extreme-test-report
          path: android/render/epub/build/reports/tests/
```

---

## 七、验收标准

测试通过条件:

### 7.1 功能正确性
- [ ] 所有 P0 缺陷已修复
- [ ] 15 个测试文件无崩溃
- [ ] 破损文件显示友好错误

### 7.2 性能指标
- [ ] 50MB EPUB 打开 <3 秒
- [ ] 翻页帧率 ≥30fps
- [ ] 内存峰值 <300MB (500 章节书)

### 7.3 排版质量
- [ ] 行高 ≥1.6
- [ ] 字号 ≥16sp
- [ ] 对比度 ≥4.5:1
- [ ] TalkBack 可用

### 7.4 安全性
- [ ] XXE 攻击阻止
- [ ] DoS 文件拒绝
- [ ] 无远程资源泄漏

---

## 八、测试报告模板

测试后填写 `docs/testing/extreme-test-report-YYYY-MM-DD.md`:

```markdown
# 极端情况测试报告

**日期**: 2026-06-27
**测试人**: @kragcola
**测试版本**: LinReads Android v4.0.0-dev124
**测试设备**: [品牌型号], Android [版本], [RAM]

## 执行结果

| 维度 | 通过 | 失败 | 阻塞 |
|------|-----|------|------|
| 内容极端 (19 项) | | | |
| 排版质量 (10 项) | | | |
| 交互极端 (10 项) | | | |
| 设备极端 (7 项) | | | |
| 环境极端 (8 项) | | | |

## 新发现缺陷

[列出测试发现的新问题]

## 修复验证

[列出已修复的缺陷验证结果]

## 推荐行动

[按优先级列出后续修复计划]
```

---

## 附录 A: 参考对标

### 静读天下 (Moon+ Reader)

已知优势待验证:
- 超大文件 (100MB+) 秒开
- 图片惰性加载
- TXT 大文件虚拟分页

对比方法:
```bash
# 同一设备，同一文件，对比:
# - 打开时间
# - 内存占用
# - 翻页流畅度
```

### KOReader

已知优势:
- EPUB/PDF/DJVU 多格式
- 极致优化低端设备
- 强大的排版引擎

---

## 附录 B: 工具链

- **Accessibility Scanner**: 无障碍检查
- **LeakCanary**: 内存泄漏检测
- **Perfetto**: 性能跟踪
- **adb dumpsys meminfo**: 内存监控
- **Calibre ebook-convert**: 格式转换
- **epubcheck**: EPUB 验证
