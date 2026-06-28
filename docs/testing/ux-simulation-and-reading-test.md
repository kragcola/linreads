# LinReads 阅读体验模拟 + 自产阅读测试

> 角色：实际用户侧体验师
> 方法：联网调研竞品弊端 → 代码级认知走查（cognitive walkthrough，追真实用户路径过真实代码）→ design-audit 评分 → 产出真机阅读测试协议
> 日期：2026-06-27
> 范围：Android v4lite reader / library / settings
> 诚实声明：本轮**未交互式驱动模拟器**。下面是基于真实代码路径的认知走查 + 竞品对照，结论均带 `file:line` 证据；真机实跑用文末第 5 节协议执行。

---

## 1. 竞品弊端调研（互联网 1–3 星评论聚类）

汇总 Kindle / Apple Books / Kobo / Libby / Everand 的 1–3 星评论，以及国内静读天下 / 多看 / 微信读书的吐槽。**五条跨产品通病**：

| # | 行业通病 | 代表来源 |
|---|---------|---------|
| C1 | **同步丢失最后阅读位置**——手机读到 100 页，平板打开回到第 1 页 | Unstar 全品类首位投诉；Kindle/Apple Books 均中招 |
| C2 | **改字号后重排错位 / 脚注跳错**——OS 的无障碍字体/高对比设置不被 App 尊重 | Unstar「font rendering glitches」；多看 6.1.2 正文固定黑体争议 |
| C3 | **标注导出被锁 / 受限**——需 Readwise/Bookcision 第三方工具才能导出 | Unstar「annotation export locked」 |
| C4 | **进度「位置 vs 页码」语义混乱**——重排书无固定页，百分比/location 各产品不一致 | Unstar「position-vs-page ambiguity」 |
| C5 | **DRM 锁定**——换账号/换区/换设备后已购书失效 | 全品类；本项目自托管 Calibre，天然规避 |

**交互层高频吐槽**（GitHub issue + V2EX + Reddit + Threads）：
- 文本选择/取消选择卡顿、不跟手（readest #3670）
- 音量键翻页失灵 / 被系统音量抢占（anx-reader #309）
- 缺上下滑动翻页（V2EX 1171952）
- 单手翻页：横排书只有触屏翻页时，向右翻只能用右手（Threads）
- 阅读器首页被推荐位占满 70%，找不到「在读」（Kindle）
- 阅读错位后无法恢复，缺「跳到最远位置」（Kindle/Facebook 群）

**无障碍专项**（Vision Australia / W3C / Dyslexia 研究）：
- 行高须 ≥ 1.5×（正文阅读建议 1.6–1.8）
- 缺 OpenDyslexic 等阅读障碍字体选项（audiobookshelf #3474）
- 字符易混（如 b/d/p/q），字距可调有帮助
- 复杂背景害可读性，需纯净背景

---

## 2. LinReads 对照：每条通病的真实落地（代码级）

| 通病 | LinReads 现状 | 证据 | 裁决 |
|------|--------------|------|------|
| **C1 丢位置** | 单机进度强保存（关书前 force-save 绕过 debounce）；**但跨端同步是 NoOpSyncBackend，isAvailable=false** | [ReaderViewModel.kt:600-606](../../android/features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderViewModel.kt#L600) · [SyncBackend.kt:18-20](../../android/core/sync/src/main/kotlin/dev/readflow/core/sync/SyncBackend.kt#L18) | ⚠️ 单机稳，跨端**未做** |
| **C2 改字号错位** | ✅ **解决**——改字号/行距 `rebuildPagedSlices()` 后用 `epubPageIndexFromLocator(currentLocator)` 按 spine+段落+charOffset 重锚定到原位置 | [EpubReflowEngine.kt:775-791](../../android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubReflowEngine.kt#L775) · [EpubPageMapping.kt:565-604](../../android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubPageMapping.kt#L565) | ✅ 强于 Kindle/Kobo |
| **C3 标注导出** | ✅ Week 7-8 刚加：书签+标注导出 Markdown（SAF），无锁、无第三方依赖 | [NotesMarkdownExporter.kt](../../android/core/database/src/main/kotlin/dev/readflow/core/database/NotesMarkdownExporter.kt) | ✅ 强于全部竞品 |
| **C4 进度语义** | 百分比 + 「第 i/N 章 · pct%」，**无绝对页码 X/Y**，无可拖动进度条 | [ReaderScreen.kt:255-308](../../android/features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderScreen.kt#L255) · [ReaderAccessibilityLabels.kt:6](../../android/features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderAccessibilityLabels.kt#L6) | ⚠️ 语义统一但**不可 scrub** |
| **C5 DRM** | 自托管 Calibre + 本地导入，无 DRM 锁 | CLAUDE.md 架构 | ✅ 天然规避 |
| **音量键翻页** | ✅ 已实现 VOL_UP/DOWN→上下翻；**但任意面板打开时全部按键被禁** | [ReaderTapZone.kt:36-42](../../android/features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderTapZone.kt#L36) · [ReaderScreen.kt:124](../../android/features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderScreen.kt#L124) | ✅ 已避坑，注意面板态 |
| **行高** | 默认 1.75×，范围 1.4–2.2（reader）；满足 ≥1.6 阅读建议 | [ReaderLineSpacing.kt:3-11](../../android/features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderLineSpacing.kt#L3) | ✅ |
| **背景色** | 日间暖纸 `#EDE6D6`（非纯白）、夜间暖棕 `#2A2620`（非纯黑）、护眼 `#F4EEDD`；对比 ~11.5:1 | core/ui/Theme.kt:17,23,59 | ✅ 护眼优秀 |
| **阅读障碍字体** | 自定义 TTF/OTF 导入可塞 OpenDyslexic，但无内置项、reader 面板内不可选字体 | [SettingsScreen.kt:259-277](../../android/features/settings/src/main/kotlin/dev/readflow/features/settings/SettingsScreen.kt#L259) | ⚠️ 间接可行 |

**一句话**：LinReads 在行业最痛的 C2（改字号丢位置）和 C3（标注导出）上**反超主流竞品**，护眼配色也到位；短板在 C1 跨端同步未做、C4 进度不可拖动、以及一批交互发现性/触摸目标细节。

---

## 3. Design-Audit 健康度评分

| 维度 | 得分 | 关键发现 |
|------|------|---------|
| 无障碍 Accessibility | 3/4 | 标签模块完备、TalkBack 有 ACTION_SCROLL 备选；缺 OS fontScale 跟随、章节箭头是裸 Text |
| 性能 Performance | 待真机 | 改字号全量 `rebuildPagedSlices()` 重排，大书可能卡顿；文本选择竞品普遍卡（需实测） |
| 响应式 Responsive | 2/4 | 章节 ←/→ < 48dp；6 个菜单按钮 SpaceEvenly 窄屏可能拥挤 |
| 主题 Theming | 3/4 | 4 套预设暖色到位、JSON 导入导出；缺亮度/真黑 AMOLED/自定义色 |
| 反模式 Anti-Patterns | 3/4 | 背景非纯白纯黑、行高达标、衬线宋体；扣分项见下 |
| **合计** | **~14/20** | **Good**（性能维度真机后回填） |

---

## 4. 认知走查：四条真实用户旅程（追代码路径）

### 旅程 A — 新用户第一次打开一本书
1. 书架点书 → reader 加载（`CircularProgressIndicator`，[ReaderScreen.kt:96](../../android/features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderScreen.kt#L96)）。
2. 进入后**全屏纯阅读，工具栏默认隐藏**（`isUiVisible=false`，[ReaderViewModel.kt:59](../../android/features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderViewModel.kt#L59)）。
3. 🔴 **痛点**：屏幕上**零提示**。新用户不知道「点中间 1/3 呼出工具栏」「点左/右翻页」「捏合调字号」。捏合提示只作为 TalkBack contentDescription 存在（[ReaderScreen.kt:946](../../android/features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderScreen.kt#L946)），明眼人看不到。三个核心手势全靠瞎试。→ **P1 缺首次引导**。

### 旅程 B — 阅读中调大字号
1. 点中间呼出工具栏 → 点「排版」→ 拖字号滑块（2 tap + drag），或直接**捏合**（0 tap，PDF 除外）。字号即时生效且**位置不丢**（旅程实测 C2 已解决）。
2. ⚠️ **不一致**：reader 滑块 12–32sp / 1sp 步进；Settings 滑块 12–28sp / 2sp 步进（[SettingsScreen.kt:236](../../android/features/settings/src/main/kotlin/dev/readflow/features/settings/SettingsScreen.kt#L236)）。同一用户在两处看到不同上限（32 vs 28），困惑。→ **P2 两处控件口径不一**。

### 旅程 C — 跳到书的某个位置
1. 想跳到「大约 70%」：底部进度条是 **2dp 不可点的 `LinearProgressIndicator`**（[ReaderScreen.kt:255](../../android/features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderScreen.kt#L255)），**拖不动**。
2. 只能走 目录/书签/搜索/上下章箭头，粒度到「章」，到不了任意百分比。
3. 🔴 **痛点**：竞品标配的拖动进度条 + 实时页码预览缺失。这是阅读器最高频交互之一。→ **P1 进度条不可 scrub**。

### 旅程 D — 切到平板继续读（跨端）
1. Settings 同步状态显示「远程同步未启用…真实同步后端已延期」（[SettingsViewModel.kt:352](../../android/features/settings/src/main/kotlin/dev/readflow/features/settings/SettingsViewModel.kt#L352)）。
2. 🔴 换设备进度**不会同步**（NoOpSyncBackend）。这正是行业第一投诉 C1。→ 计划内延期，但用户预期落差最大。

---

## 5. 自产阅读测试协议（真机/模拟器执行）

> 目的：把上面的代码推断变成可勾选的真机验收项。每条含 步骤 / 期望 / 通过判据 / 关联痛点。
> 测试素材：1 本带图文混排长 EPUB、1 本扫描版 PDF、1 个大 TXT（≥5MB）、1 本带多级目录 EPUB。

### T1 翻页与手势（关联 C2/交互吐槽）
| ID | 步骤 | 期望 | 通过判据 |
|----|------|------|---------|
| T1.1 | 点屏幕左 1/3 / 右 2/3 / 中 1/3 | 上翻/下翻/呼出工具栏 | 三区准确，中区切换不误翻 |
| T1.2 | paged 模式下按音量上/下键 | 上/下翻页 | 不触发系统音量条 |
| T1.3 | 打开任一面板后按音量键 | （已知）按键被禁 | 记录是否影响预期，决定是否放行翻页键 |
| T1.4 | 横向快速滑动 | 引擎原生翻页或不误触 | 不卡、不丢位置 |
| T1.5 | scroll 模式下点左/右 1/3 | 仅中区有效（左右被禁） | 确认与设计一致，评估用户是否困惑 |

### T2 排版与重排位置保持（关联 C2，**最高优先**）
| ID | 步骤 | 期望 | 通过判据 |
|----|------|------|---------|
| T2.1 | 读到某段中间，字号 16→28sp | 重排后**仍停在同一段同一句** | 偏移 ≤ 1 屏；不回章首 |
| T2.2 | 行距 1.4→2.2 | 同上重锚定 | 位置不丢 |
| T2.3 | 大书（>500 页 EPUB）改字号 | 重排耗时可接受 | 无明显卡顿/ANR（实测 `rebuildPagedSlices` 全量重排成本） |
| T2.4 | 切换衬线/思源宋体/自定义字体 | 字体生效 | 不崩、不丢位置 |
| T2.5 | 导入 OpenDyslexic TTF 并应用 | 正文用该字体 | 阅读障碍场景可用 |

### T3 进度与导航（关联 C4）
| ID | 步骤 | 期望 | 通过判据 |
|----|------|------|---------|
| T3.1 | 观察底部进度 | 百分比 + 章节 | 语义清晰 |
| T3.2 | 尝试拖动进度条 | （已知不可拖）| 记录为缺陷，评估是否补 seek 条 |
| T3.3 | 多级目录跳转 | 缩进正确、跳准 | 4 级以内缩进可辨 |
| T3.4 | 关书重开 | 回到上次位置 | 单机进度不丢（force-save 验证） |

### T4 阅读aids：书签/标注/搜索/导出（关联 C3）
| ID | 步骤 | 期望 | 通过判据 |
|----|------|------|---------|
| T4.1 | 顶栏书签 toggle | 图标变色 + 列表出现 | 状态正确，可删 |
| T4.2 | 长按选词 → 高亮 + 写笔记 | 标注入库、列表可见 | 选择跟手（实测竞品卡顿点） |
| T4.3 | 全书搜索关键词 | 结果列表带百分比、可跳 | 跳转准确 |
| T4.4 | Settings 导出笔记 Markdown | 文件含全部书签+标注 | 打开 .md 内容正确（C3 卖点） |
| T4.5 | 主题 JSON 导出再导入 | 排版设置恢复 | 有成功/失败反馈（Week 7-8 已补 UI） |

### T5 主题与护眼
| ID | 步骤 | 期望 | 通过判据 |
|----|------|------|---------|
| T5.1 | 切日间/夜间/护眼 | 背景非纯白/非纯黑/淡黄 | 取色符合 Theme.kt |
| T5.2 | 夜间模式长读 | 不刺眼 | 暖棕 `#2A2620`，无光晕 |
| T5.3 | 找亮度调节 | （已知缺失）| 记录为缺陷 |

### T6 无障碍（关联无障碍专项）
| ID | 步骤 | 期望 | 通过判据 |
|----|------|------|---------|
| T6.1 | 开 TalkBack 翻页 | ACTION_SCROLL 上/下页可用 | 朗读「上一页/下一页」 |
| T6.2 | TalkBack 读进度/章节 | 朗读「全书进度 + 章节」 | 标签完整 |
| T6.3 | 系统字体放到最大 | 正文是否跟随 | 记录：正文用 app 滑块，不跟 OS fontScale |
| T6.4 | 测章节 ←/→ 箭头点击 | 能点中 | 实测是否因 <48dp 难点中（P2） |

### T7 边界与异常
| ID | 步骤 | 期望 | 通过判据 |
|----|------|------|---------|
| T7.1 | 打开损坏/超大文件 | 友好错误 | 不崩；记录是否暴露原始异常文本 |
| T7.2 | 加载中返回 | 可取消 | 无残留状态 |
| T7.3 | 书架为空 | EmptyState 引导（连 Calibre/导入）| 引导清晰 |
| T7.4 | reader 打开失败 | 错误页 | 记录：当前是裸字符串无重试按钮（P2） |

---

## 6. 改进建议优先级（喂给后续 skill / 排期）

- **[P1] 首次引导**：✅ **已修**（reader 首开一次性手势浮层 + DataStore 标志一次性化）。
- **[P1] 可拖动进度条**：✅ **已修**（`ReaderEngine.seekToProgress` + `ReaderProgressSeekBar`，真机验证）。
- **[P2] 统一字号/行距口径**：🔴 待排。reader 与 Settings 两处滑块范围/步进对齐（建议都 12–32sp / 1sp，行距下限统一 1.4）。
- **[P2] 章节箭头触摸目标**：🔴 待排。裸 `Text("←")` 升级为 ≥48dp 的 IconButton。
- **[P2] reader 错误页**：🔴 待排。加重试 + 返回按钮，隐藏原始异常文本。
- **[P3] 亮度/暖光叠层**：🔴 待排。补阅读内亮度滑块或蓝光过滤。
- **[P3] reader 面板内字体切换**：🔴 待排。字体选择目前只在 Settings，长读时切字体需跳出。
- **[P3] 菜单图标区分**：🔴 待排。书签/标注/排版当前共用 `Icons.Default.Edit`，辨识度低。
- **（计划内）C1 跨端同步**：NoOpSyncBackend → 真实后端，行业第一痛点。

> 完整排期台账见 [`docs/tracking/ux-improvement-backlog.md`](../tracking/ux-improvement-backlog.md) 顶部「真机 UX 体验缺陷台账」。

---

## 7. 结论

LinReads 在**最难做对的两点**上已经领先主流竞品：改字号不丢位置（spine+charOffset 重锚定）、标注无锁导出 Markdown。护眼配色、行高、音量键翻页、TalkBack 备选操作都已到位，底子扎实。

真正待补的是**发现性与导航手感**（首次引导、可拖动进度条）和**计划内的跨端同步**。这些不是 bug，是「好用 vs 难用」的分水岭——恰好对应调研里用户给阅读 App 打低分的高频原因。

建议真机阶段先按 **T2（重排保位）+ T4（标注导出）** 验证两大卖点是否真稳，再用 **T1/T3/T6** 暴露手感细节。

---

## 8. 真机实测执行记录（模拟器 emulator-5554）

> 设备：Android-36 平板模拟器，1600×2560 @ density 320，arm64-v8a
> 应用：`dev.readflow` phase2 debug APK（含 Week 7-8 全部改动）
> 方法：adb 截图 + uiautomator dump 精确定位 + input tap/swipe 真实交互
> 测试书：`seed_围城`（EPUB 节选，单章两段）

### 通过项 ✅

| 用例 | 结果 | 实测证据 |
|------|------|---------|
| **T2.1 改字号重排保位** | ✅ **通过（最关键卖点）** | 字号 18→32sp，正文首段从 3 行重排成 7 行，**仍锚定"红海早过了"开头，位置零丢失**。行业第二大痛点（C2 改字号丢位置）真机坐实解决，强于 Kindle/Kobo |
| **T4.2 长按选词→高亮→标注** | ✅ **通过（C3 卖点链路）** | 长按选中"作者：钱钟书"→弹标注面板（笔记框+取消/高亮/保存）→点保存→正文渲染暖黄高亮 `0x66FFE082` + 标注面板列出该条，入库成功 |
| **T5.2 夜间护眼配色** | ✅ 通过 | 切夜间：背景暖棕 `#2A2620`（非纯黑）、文字暖白（非纯白），无光晕，配色符合 Theme.kt |
| 触摸目标（底栏 6 按钮） | ✅ 通过 | uiautomator 实测 bounds=96×96px @ density320 = 48dp，达标 |
| 书架视觉 | ✅ 通过 | 暖纸背景 `#EDE6D6`、布纹书封（红/绿），符合设计 |

### 真机新发现缺陷 🔴（认知走查未发现，真机才暴露）

| # | 缺陷 | 现场 | 优先级 |
|---|------|------|--------|
| **R1** | **顶栏 status bar 叠字**：阅读器 chrome 标题栏 / 标注高亮区 与系统状态栏时间重叠（"11:xx" 压在 "围城（节选）" / "作者：钱钟书" 上），日间夜间都在。chrome 顶栏未避让 status bar inset | s2–s13 全程可见 | **P1** |
| **R2** | **"阅读正文预览"分隔标签跟随阅读字号缩放**：字号调到 32sp 时，FontPanel 里这个 UI 分区标签本身也被放大成巨字，应使用固定 UI 字号而非 `fontSizeSp` | s8 | P3 |

### 代码审查结论的真机坐实 ⚠️

- **字号口径不一**：reader 滑块上限实测 **32sp**，Settings 是 28sp —— §4 旅程 B 的发现确认。
- **图标复用**：底栏 书签/标注/排版 三个按钮**肉眼可见共用同一铅笔图标**，辨识度低 —— 审查 P3 确认。
- **进度无绝对页码**：底部恒显 "1 / 1章 · 0%"，章节+百分比，无 X/Y 页码（C4）。
- **零手势引导**：进书即纯阅读，无任何提示中间点呼出工具栏（旅程 A 的 P1 坐实）。

### 本轮未充分覆盖（受测试书限制）

`seed_围城` 是单章两段的节选，**一屏内容**，以下用例需更长的书/PDF/大 TXT 才能跑：
- T1 翻页手势（节选翻不动页）
- T2.3 大书改字号重排性能
- T3.2 进度条拖动、T3.3 多级目录跳转
- T1.2 音量键翻页

### 小结

三大核心卖点（**改字号保位、标注无锁、夜间护眼**）真机全部验证通过，底子扎实。新抓到 R1 顶栏叠字（P1，影响每一屏的观感）和 R2 预览标签缩放（P3）两个真机专属缺陷。建议下一步导入一本长 EPUB + 一个大 TXT，补跑翻页/进度/性能用例。

---

## 9. 真机扩测记录（长书 readflow-perf-1mb.epub，4 章）

> 续测：导入 1.1MB 多章 EPUB（合成性能书，段落带 `#NNNNN` 编号，可精确追踪翻页位置）
> R1 inset 修复已合入此次构建。

### 通过项 ✅

| 用例 | 结果 | 实测证据 |
|------|------|---------|
| **导入流程** | ✅ 通过 | 书架"+"→选择文件→SAF→选 epub→书架新增第 5 本"readflow-perf-1mb"，"离线可读 5" |
| **T2.3 长书加载性能** | ✅ 通过（有感知） | 1MB EPUB 解析耗时约 5–8s（CircularProgressIndicator），明显慢于节选的秒开；加载成功无 ANR/崩溃。真机性能基线 |
| **T3.3 多级目录跳章** | ✅ 通过 | 目录列出 Chapter 1–4；点 Chapter 3 → 正文全变"Chapter 3…#00000"，进度"第3章 / 3/4章·0%"，跳转精准 |
| **T1 滚动翻页 + 进度递增** | ✅ 通过 | scroll 模式上滑 5 次：#00000→#00015~#00017，进度 0%→1%，段落编号连续无丢段 |
| **T1.1 分页三区点击翻页** | ✅ **通过（静读天下式核心交互）** | 切分页模式后：点右 2/3 #00009~11→#00012~14（下一页）；点左 1/3 回 #00009~11（上一页）。前进后退一致、精准 |
| **R1 修复在长书生效** | ✅ 复验通过 | 状态栏时钟与"Chapter 3"标题清晰分隔，不再叠字 |

### 真机确认的设计语义点 ⚠️

- **章内进度 vs 全书进度**：跳到第 3 章开头显示"3/4章 · **0%**"，这 0% 是**章内**进度（刚到本章开头），非全书。与代码 `readerChapterProgressDescription` 一致，但用户视角易困惑"都第 3 章了怎么还 0%"。归入 C4 进度语义可优化，非 bug。

### 仍未覆盖（低价值，不再真机重复）

- **T3.2 进度条拖动**：代码已确认是不可交互的 2dp `LinearProgressIndicator`（§4 旅程 C），无需真机重复——结论已定为 P1 缺陷。
- **T1.2 音量键翻页**：模拟器无物理音量键映射到翻页，意义有限；代码层已确认实现（[ReaderTapZone.kt:36](../../android/features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderTapZone.kt#L36)）。

### 扩测总结

长书把节选测不到的**翻页（滚动+分页双向）、多章目录跳转、加载性能**全部补齐，结果均通过。至此真机已覆盖：导入 → 加载 → 三区/滚动翻页 → 多章导航 → 改字号保位 → 标注 → 夜间 → 进度，阅读主链路端到端走通。唯一硬缺陷仍是 **R1（已修）**；待办的体验提升项是 **进度条不可拖动（P1）+ 首次手势引导（P1）+ 字号口径统一（P2）**。


---

## 10. P1/P2 修复轮记录（2026-06-27）

> 按用户排期依次修复并真机复验：可拖动进度条 → 首次手势引导 → R2。

### 改动

| 项目 | 改动点 | 三端 |
|------|--------|------|
| **可拖动进度条（P1）** | `ReaderEngine.seekToProgress(fraction)` 默认实现（构造 progression-only `Locator` 交给 `goTo`，EPUB/TXT/MD 均按 totalProgression 解析）；`PdfRendererEngine` override 映射为 `Page` 页码。新增 `ReaderIntent.SeekToProgress` + VM `seekToProgress()`（走 `persistExplicitNavigation` 落盘）。UI 把只读 2dp `LinearProgressIndicator` 换成 `ReaderProgressSeekBar`：拖动期用本地值跟手、松手才提交（避免每帧重排）、右侧实时显示 %。 | Android ✅ / HarmonyOS 待 / Web N/A |
| **首次手势引导（P1）** | `SettingsRepository.readerGuideShown: Flow<Boolean>` + `setReaderGuideShown()`（DataStore `reader_guide_shown`，默认 false）。VM 开书后若未展示过则 `showGuide=true`；`ReaderIntent.DismissGuide` 关闭并落盘标志。UI `ReaderGestureGuideOverlay`：半透明遮罩 + 三区（上翻/呼出菜单/下翻）+ 底部「捏合调字号 · 长按划选标注 · 拖底部进度条跳转」，点任意处关闭。 | Android ✅ / HarmonyOS 待 / Web N/A |
| **R2 预览标签缩放（P3）** | FontPanel 把单个随字号缩放的「阅读正文预览」拆成两段：固定 `labelMedium` 的「正文预览」标签 + 真正按 `fontSizeSp` 缩放的样张「海上生明月，天涯共此时。」。同步更新 4 个 instrumented 测试的锚点文案。 | Android ✅ / HarmonyOS 待 / Web N/A |

### 验证

- **编译**：`clean assembleDebug`（phase=2）BUILD SUCCESSFUL。
- **单测**：reader/settings/library/pdf 模块 `testDebugUnitTest` 全绿；reader 新增 3 测（seekToProgress 落到 progression-only Locator 并落盘 / 首开展示并 dismiss 持久化 / 已看过不再展示）。
- **真机**（emulator-5554）：
  - 进度条：拖/点 seek 条 0%→33%→67%，引擎跟随跳转，无崩溃。
  - 引导：首开浮层三区+提示正确渲染；点击关闭；**再次开书不再出现**（DataStore 标志持久化坐实）。
  - R2：字号 30sp 下「正文预览」标签恒 36px、样张 62→102px——标签不再随字号爆字。

### 过程中发现的工程隐患 ⚠️

给 `ReaderEngine` 接口加默认方法后，Gradle 增量编译未重编 txt/md/epub 引擎模块（标记 up-to-date），导致 APK 内 `TxtVirtualPagerEngine` 缺少 `seekToProgress` 默认方法桥接，真机 `AbstractMethodError` 崩溃。`--rerun-tasks` / `clean` 后正常。**教训**：改 `render:api` 接口（尤其加默认方法）后，必须 `clean` 全量重编，不能信增量构建的 up-to-date。
