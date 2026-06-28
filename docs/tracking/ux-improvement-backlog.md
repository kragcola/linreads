# UX 改进任务清单

> **来源**: 本地离线阅读体验对比调研报告  
> **对比对象**: LinReads vs 静读天下 (Moon+ Reader Pro v9.7)  
> **创建日期**: 2026-06-27  
> **优先级**: P0 (阻塞) > P1 (重要) > P2 (增强)

---

## 真机 UX 体验缺陷台账（来源：`docs/testing/ux-simulation-and-reading-test.md`）

> 真机模拟器 emulator-5554 实测 + 认知走查发现的缺陷与体验项，按优先级排期。
> 最后更新：2026-06-27（本轮修复三项并真机复验）

| ID | 缺陷/项目 | 优先级 | 状态 | 处置 |
|----|-----------|--------|------|------|
| **可拖动进度条** | 2dp 只读 `LinearProgressIndicator` → 可 scrub seek 条 | P1 | ✅ 已修 | 新增 `ReaderEngine.seekToProgress(fraction)` 默认实现（EPUB/TXT/MD 走 progression-only Locator，PDF override 映射页码）+ `ReaderProgressSeekBar`（拖动跟手、松手提交、显示%）。真机 0%→33%→67% 验证，3 单测覆盖 |
| **首次手势引导** | 进书零引导 → 一次性 coach-marks | P1 | ✅ 已修 | 新增 `SettingsRepository.readerGuideShown` 持久化标志 + `ReaderGestureGuideOverlay`（三区 上翻/呼出菜单/下翻 + 捏合/划选/拖进度条提示）。首开展示、点击关闭、再开不重现，真机验证，3 单测覆盖 |
| **R2** | FontPanel "阅读正文预览" 标签随阅读字号缩放成巨字 | P3 | ✅ 已修 | 拆成固定字号标签「正文预览」+ 真正按字号缩放的样张「海上生明月…」。真机 30sp 下标签恒 36px、样张 62→102px |
| **R1** | 顶栏 chrome 与系统状态栏叠字 | P1 | ✅ 已修（上一轮） | document AndroidView 加 `windowInsetsPadding(WindowInsets.systemBars)` |
| 字号/行距口径不一 | reader 上限 32sp，Settings 28sp；行距下限不一 | P2 | 🔴 待排 | 建议都 12–32sp/1sp、行距下限统一 1.4 |
| 章节箭头触摸目标 | 裸 `Text("←")` < 48dp | P2 | 🔴 待排 | 升级为 ≥48dp IconButton |
| reader 错误页 | 直接显示原始异常文本，无重试/返回 | P2 | 🔴 待排 | 加重试+返回按钮，隐藏堆栈 |
| 全书页码缺失 | 底部仅章节%，无 X/Y 页码 | P3 | 🔴 待排 | 底部补全书% + 绝对页码（C4） |
| 菜单图标复用 | 书签/标注/排版共用铅笔图标 | P3 | 🔴 待排 | 各用区分图标 |
| 亮度/暖光叠层 | 无阅读内亮度/蓝光过滤 | P3 | 🔴 待排 | 补叠层滑块 |
| C1 跨端进度同步 | NoOpSyncBackend（占位） | 计划内 | 🔴 待排 | 接真实后端，行业第一痛点 |

**下一步建议排期**：P2 三项（字号口径 / 章节箭头触摸目标 / 错误页）是低成本合规修复，可一并处理；P3 为锦上添花；C1 跨端同步需单独架构排期。

---

## P0 - 阻塞离线体验 (必须完成才能发布)

### P0-UX-1: 验证点击区域逻辑
- **描述**: 确认点击区域翻页功能是否按静读天下标准实现 (左 1/3 上翻, 右 2/3 下翻)
- **当前状态**: 代码中存在手势检测，但逻辑待定位
- **验证方法**: 
  ```bash
  # 1. 真机测试点击响应
  # 2. 查找 ReaderViewModel 或 ReaderRootLayout 的点击处理
  grep -r "onTap\|onClick" android/features/reader/src/main/kotlin/
  ```
- **参考**: `moonreader-decompiled/.../ActivityTxt.java:197` 的 OnClickListener
- **工作量**: 1 天
- **阻塞理由**: 基本交互缺失影响阅读流畅度

### P0-UX-2: 真机排版质量审计
- **描述**: 在物理设备上验证排版参数的实际视觉效果
- **当前状态**: 代码参数优秀 (18sp, 1.75行高)，但未真机验证
- **验证清单**:
  - [ ] 字号视觉舒适度 (对比静读天下截图)
  - [ ] 行高是否过松/过紧
  - [ ] 段间距是否明显
  - [ ] 日间/夜间模式对比度测量 (≥4.5:1)
- **工具**: 
  - 对比度检测: https://webaim.org/resources/contrastchecker/
  - Accessibility Scanner
- **工作量**: 0.5 天
- **阻塞理由**: 排版质量是阅读体验核心

### P0-UX-3: 真机翻页帧率测试
- **描述**: 测量真机翻页流畅度，确保 ≥30fps
- **当前状态**: 理论架构良好 (ViewPager2)，但无真机数据
- **测试方法**:
  ```bash
  # 使用 systrace 记录帧率
  python3 $ANDROID_HOME/platform-tools/systrace/systrace.py \
    -o page-turn-trace.html -t 10 gfx view
  
  # 或使用 dumpsys gfxinfo
  adb shell dumpsys gfxinfo dev.readflow
  ```
- **目标**: 翻页动画 ≥30fps，理想 ≥60fps
- **工作量**: 0.5 天
- **阻塞理由**: 流畅度直接影响用户满意度

---

## P1 - 重要功能缺口 (显著提升竞争力)

### P1-UX-4: 实现离线词典支持
- **描述**: 长按选择文字后，提供词典查询功能
- **参考实现**: 静读天下 `my_dict_url` + `dict_index`
- **设计方案**:
  1. 内置词典: 打包常用词典数据库 (如 ECDICT)
  2. 自定义 URL: 支持用户配置在线词典 API (有道/金山/谷歌翻译)
  3. 系统词典: 调用 Android `ACTION_DEFINE` Intent
- **优先级**: **P1** (离线阅读刚需)
- **工作量**: 3-5 天
- **参考代码**:
  ```kotlin
  // 静读天下参考
  // moonreader-decompiled/.../A.java: my_dict_url, dict_index
  
  // 建议实现
  fun lookupWord(word: String) {
      when (settings.dictMode) {
          DictMode.BUILTIN -> lookupBuiltin(word)
          DictMode.CUSTOM_URL -> openUrl(settings.dictUrl.replace("{word}", word))
          DictMode.SYSTEM -> startActivity(Intent(Intent.ACTION_DEFINE).putExtra("query", word))
      }
  }
  ```

### P1-UX-5: 实现 TTS 朗读
- **描述**: 文本转语音朗读功能
- **当前状态**: 扩展系统已规划 (§8)，但未实现
- **设计方案**:
  1. 使用 Android TextToSpeech API
  2. 支持速度/音调调节
  3. 后台播放 + 锁屏控制
  4. 定时停止 (参考静读天下 `tts_stop_time`)
- **优先级**: **P1** (视障用户刚需，通勤场景高需求)
- **工作量**: 5-7 天
- **参考代码**:
  ```kotlin
  // 静读天下参考
  // A.java: tts_divide, tts_speed, tts_pitch, tts_stop_time
  
  // 扩展点
  // android/extensions/api/.../ReaderExtension.kt
  interface TtsExtension : ReaderExtension {
      suspend fun speak(text: String, rate: Float, pitch: Float)
      fun pause()
      fun resume()
      fun stop()
  }
  ```

### P1-UX-6: MOBI/AZW3 格式支持决策
- **描述**: 明确是否支持 Kindle 格式
- **调研结论**: 
  - MOBI 已过时，EPUB 是主流
  - 但用户可能有历史 Kindle 购买书籍
- **方案选项**:
  1. **不支持** (推荐): 引导用户用 Calibre 转换为 EPUB
  2. **支持**: 集成 mobi-rust 或类似解析器
- **决策点**: 用户调研 - 有多少人需要 MOBI？
- **工作量**: 
  - 不支持: 0 天 (文档说明)
  - 支持: 10-15 天 (新引擎开发)
- **优先级**: P1 (需要明确策略)

### P1-UX-7: 音量键翻页支持
- **描述**: 按音量键上/下翻页
- **参考**: 静读天下 `doVolumeKeyUp` / `doVolumeKeyDown`
- **实现**:
  ```kotlin
  // android/features/reader/.../ReaderViewModel.kt
  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
      return when (keyCode) {
          KeyEvent.KEYCODE_VOLUME_UP -> {
              if (settings.volumeKeyPageTurn) {
                  previousPage()
                  true
              } else false
          }
          KeyEvent.KEYCODE_VOLUME_DOWN -> {
              if (settings.volumeKeyPageTurn) {
                  nextPage()
                  true
              } else false
          }
          else -> false
      }
  }
  ```
- **工作量**: 1 天
- **优先级**: P1 (高频需求，实现简单)

---

## P2 - 增强体验 (锦上添花)

### P2-UX-8: Fixed Layout EPUB 支持
- **描述**: 支持预分页 EPUB (漫画/杂志/绘本)
- **当前状态**: 检测到但不支持 (`EpubPackage.isFixedLayout`)
- **影响场景**: 漫画阅读用户
- **实现复杂度**: 高 (需要图片分页引擎)
- **工作量**: 10-15 天
- **优先级**: P2 (小众场景)

### P2-UX-9: 批注导出功能
- **描述**: 导出书签/高亮/笔记为文本或 Markdown
- **参考**: 静读天下支持导出
- **格式建议**:
  ```markdown
  # 《书名》阅读笔记
  
  ## 第一章
  
  > 高亮文本
  
  📝 我的批注
  
  ---
  导出时间: 2026-06-27
  ```
- **工作量**: 2-3 天
- **优先级**: P2 (学术阅读场景需要)

### P2-UX-10: 自定义字体导入
- **描述**: 支持用户导入 TTF/OTF 字体文件
- **参考**: 静读天下 `outerFontsFolder`
- **实现**:
  ```kotlin
  // 扫描字体文件夹
  val customFonts = File(settings.fontFolder).listFiles { file ->
      file.extension in setOf("ttf", "otf")
  }
  
  // 应用字体
  val typeface = Typeface.createFromFile(fontFile)
  textPaint.typeface = typeface
  ```
- **工作量**: 3-5 天
- **优先级**: P2 (审美需求)

### P2-UX-11: 阅读统计
- **描述**: 记录阅读时长、页数、字数
- **参考**: 静读天下 `statistics_*` 字段
- **展示**: 每日/每周/每月统计图表
- **工作量**: 3-5 天
- **优先级**: P2 (gamification 需求)

### P2-UX-12: 手势区域自定义
- **描述**: 允许用户自定义 9 宫格点击区域动作
- **参考**: 静读天下 `do91`-`do99` 配置
- **UI 设计**: 可视化 9 宫格配置页面
- **工作量**: 5-7 天
- **优先级**: P2 (高级用户需求)

### P2-UX-13: 主题导入导出
- **描述**: 保存/分享自定义主题配置
- **格式**: JSON 配置文件
  ```json
  {
    "name": "我的护眼主题",
    "fontSize": 18,
    "lineSpacing": 1.75,
    "dayBackground": "#FAFAF8",
    "dayForeground": "#1A1A1A",
    "nightBackground": "#1A1A1A",
    "nightForeground": "#E8E6E1"
  }
  ```
- **工作量**: 2-3 天
- **优先级**: P2 (社区分享需求)

---

## 任务优先级总结

### 立即执行 (本周)
1. P0-UX-1: 点击区域验证
2. P0-UX-2: 真机排版审计
3. P0-UX-3: 真机帧率测试

### 近期规划 (2-4 周)
4. P1-UX-7: 音量键翻页 (快速胜利)
5. P1-UX-4: 离线词典
6. P1-UX-5: TTS 朗读
7. P1-UX-6: MOBI 格式决策

### 中期规划 (1-3 月)
8. P2-UX-9: 批注导出
9. P2-UX-10: 自定义字体
10. P2-UX-11: 阅读统计
11. P2-UX-13: 主题导入导出

### 长期规划 (3-6 月)
12. P2-UX-8: Fixed Layout EPUB
13. P2-UX-12: 手势区域自定义

---

## 验收标准

### P0 任务验收
- [ ] 点击左右区域能正确翻页
- [ ] 真机截图与静读天下并排对比，排版质量不低于对方
- [ ] systrace 显示翻页帧率 ≥30fps

### P1 任务验收
- [ ] 长按选词后能查询词典
- [ ] TTS 能正确朗读中英文
- [ ] 音量键翻页功能正常，可配置开关

### P2 任务验收
- [ ] 批注能导出为 Markdown 文件
- [ ] 自定义字体能正确渲染
- [ ] 阅读统计数据准确，图表美观

---

## 关联文档

- [对比报告](docs/research/offline-reading-ux-comparison-report.md)
- [对比审计](docs/research/moonreader-linreads-extreme-reading-comparison.md)
- [架构文档](docs/android-architecture-v4.md)
- [极端测试](docs/testing/extreme-offline-reading-test-plan.md)

---

**维护者**: LinReads 开发团队  
**更新频率**: 每完成一项任务更新状态  
**最后更新**: 2026-06-27
