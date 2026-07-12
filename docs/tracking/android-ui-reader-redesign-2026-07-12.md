# Android UI / Reader Redesign — 2026-07-12

Mode: `task-ui`
Status: `complete`

## Objective

重新审计并重塑 Android 主应用书架、阅读界面与设置页。方向为 Contemporary Editorial：暖白中性底、墨色正文、深青单一强调、衬线展示字 + 无衬线 UI、大留白和轻表面。允许明显区别旧风格，但不修改阅读内核、翻页手势或业务行为。

## Baseline evidence

- 书架：`/tmp/readflow-library-before.png`
- 阅读正文：`/tmp/readflow-reader-before.png`
- 阅读 chrome：`/tmp/readflow-reader-chrome-before.png`
- AVD viewport：1600×2560
- Baseline HEAD：`0c3bf0c8`

## Audit health score — before

| Dimension | Score | Key finding |
|---|---:|---|
| Accessibility | 2/4 | 多数语义存在，但书架菜单仅 36dp，夜间纯黑预设违反阅读规范 |
| Performance | 3/4 | 列表 lazy 且阅读动画已优化；全局程序纸纹与重阅读 chrome 有额外绘制 |
| Responsive | 1/4 | 1600px AVD 仍五列窄封面，长标题严重断行 |
| Theming | 1/4 | token 存在，但黄纸/暖褐主壳与阅读纹理过重，强调层级不清 |
| Anti-patterns | 1/4 | 假木板、旧布、厚重纸纹和三层整宽控制面板并存 |
| **Total** | **8/20** | **Poor** |

## Audit health score — after

| Dimension | Score | Key finding |
|---|---:|---|
| Accessibility | 3/4 | AVD 已覆盖语义、48dp、对比度与可调 Slider；仍缺实机和真人 TalkBack 完整语音/焦点遍历 |
| Performance | 4/4 | 书架保持 LazyGrid；App shell 去除程序纸纹，阅读纸纹为轻量平铺，未引入 blur 或布局动画 |
| Responsive | 4/4 | 360dp、800dp 竖屏与 1280dp 横屏均无溢出，Settings 840dp、Library 1120dp 限宽实测生效 |
| Theming | 4/4 | Light/Dark/Sepia 容器色阶完整，阅读预设无 Material 紫灰回退 |
| Anti-patterns | 4/4 | 衬线展示与正文层级清晰，无厚木板、重纸纹、纯黑阅读底或泛 AI 渐变 |
| **Total** | **19/20** | **Excellent** |

## Findings

- [P1] `BookGrid.kt` · Responsive：Adaptive 116dp 在平板上产生五列窄封面，标题不可扫读。改为 2/3/4/5 固定断点并限制内容宽度。
- [P1] `ReaderScreen.kt` · Reader chrome：顶部与正文标题叠加，底部进度/章节/六入口三层面板遮挡大量正文。改为有边距的顶部浮层和两层底部浮层，功能面板仅在打开时扩展。
- [P1] `SettingsScreen.kt` · Information architecture：连接、排版、同步、备份、导出和更新无分组铺成单列。改为 5 个有标题和说明的设置卡片。
- [P1] `BookGrid.kt` · Accessibility：封面菜单按钮 36dp，小于 Android 48dp 推荐触控区。提升为 48dp。
- [P2] `Theme.kt` / `PaperSurface.kt` · Theming：旧黄纸和装饰纹理主导 app shell。换为暖白编辑部底、深青强调、平静表面。
- [P2] `ReaderPaperBackground.kt` · Reading comfort：纸纹肉眼明显并干扰小字号。保持同一 drawable 链路，仅降低 alpha，避免破坏翻页纹理连续性测试。
- [P2] `ReaderPalette.kt` · Night mode：BLACK 使用纯黑背景。改为近黑 `#101310`，文字仍非纯白。
- [P3] `BookCover.kt` · Polish：封面暗角、圆形进度和布纹强化怀旧感。改为更轻阴影、底边进度与柔和渐层。

## Decisions

- 不采用 UI Pro Max 自动推荐的 Liquid Glass：性能与文字对比不适合长时阅读。
- 不新增字体二进制或图片资产；使用系统 Serif/Sans，保持 APK 轻量和离线稳定。
- 不触碰 EPUB/PDF/TXT/MD 引擎、翻页算法、locator 或手势路由。
- 保留自动化依赖的中文标签和 `contentDescription`：`书架`、`设置`、`目录`、`搜索`、`书签`、`标注`、`排版`、`主题`、`上一章`、`下一章`。
- 对阅读背景只降低纹理强度，不换绘制模型，避免静态页与翻页快照的纹理相位不一致。
- 阅读 chrome 保持成熟阅读器常见的“短暂覆盖、点正文隐藏”模型，不在显隐时动态缩放 engine viewport；后者会触发 EPUB 重分页和 locator 跳位。当前浮层已压成两层并保留 48dp 操作区，遮挡取舍由可即时隐藏来化解。
- 设置页主题预设不用无提示横向滚动，改为 `FlowRow` 显式换行，避免窄屏隐藏选项。

## Implementation status

- [x] Theme、shape、type tokens 改为 Contemporary Editorial。
- [x] 书架响应式密度 RED/GREEN 测试。
- [x] 去木板线、宽封面、作者/进度层级、48dp 菜单。
- [x] 书架页头重排。
- [x] 阅读顶部/底部 chrome 压缩为浮动两层。
- [x] 设置页重组为书源、阅读、同步备份、数据主题、关于更新 5 个分组卡片。
- [x] AVD light/dark、书架/阅读/设置截图与 UI tree；补 360dp Reader/Settings 手机宽度截图。
- [x] Slider 语义触控区实测从 44dp 提升到 48dp；目录抽屉空标签 clickable 从 1 降为 0。
- [x] 五模块单测、四个 lint、AndroidTest compile、assembleDebug 与安装门。
- [x] 独立只读无障碍/主题复核（无 P0）；确定项已修复。
- [x] A03 无障碍 smoke：首次引导同节点 `ACTION_CLICK`、五个单一 48dp `SeekBar`、动态 Compose accessibility cache 清理与 TOC stale-node 防护均已验证。
- [x] `Theme.kt` Material3 `surfaceContainer*` 与 SEPIA `outlineVariant` 已补齐并完成浅色、夜间、护眼黄视觉复核。
- [x] 最终只读代码复审与 Design Audit：无 P0-P2，最终 19/20。
- [x] 复审新增两项 P2 已闭环：修正 Library/Settings 限宽 Modifier 顺序；无封面作者在后置暗角下最低对比度恢复到 AA。

## Test ledger

| Time | Command | Actual result | Conclusion |
|---|---|---|---|
| 08:50 | `:core:ui:testDebugUnitTest --tests LibraryGridLayoutTest` | compile RED：缺少 `libraryGridColumns` | 补占位以获得有效断言 RED |
| 08:51 | 同上 | 4 tests，3 assertion failures | 旧/占位策略无法满足中屏和平板密度 |
| 09:02 | `:core:model:test :core:ui:testDebugUnitTest` | 首轮 compile failure：PaddingValues / CircleShape；修正后 BUILD SUCCESSFUL | 主题与网格共享底座 GREEN |
| 09:06 | `:features:library:compileDebugKotlin :features:library:testDebugUnitTest` | BUILD SUCCESSFUL | 书架页面 GREEN |
| 09:11 | `-Preadflow.phase=2 :features:reader:compileDebugKotlin :features:reader:testDebugUnitTest` | BUILD SUCCESSFUL | 阅读 chrome 保持 Reader 单测契约 |
| 10:56 | AVD settings XML，320dpi | `字号/行距` semantics bounds 88px = 44dp | 直接给 Slider `heightIn` 无效，需外层 48dp 合并语义 |
| 11:00 | AVD settings/reader XML，320dpi | settings 字号/行距、reader 字号/行距/总进度均为 96px = 48dp | 48dp 外层容器 GREEN |
| 11:03 | AVD TOC XML + tap sequence | “关闭目录”唯一 clickable 位于右半屏；空标签 clickable `1 -> 0`；目录项可跳转、左侧空白不误关、右侧遮罩关闭 | 抽屉 pointer/语义契约 GREEN |
| 11:09 | AVD `wm size 720x1280` / 320dpi | Reader 与 Settings 360dp 截图无横向溢出，六入口及 Slider 仍为 48dp | 手机宽度 GREEN；随后恢复 1600×2560 |
| 11:12 | 五模块 tests + 四模块 lint + AndroidTest compile + assembleDebug | `BUILD SUCCESSFUL in 38s`，598 tasks | 静态集成门 GREEN |
| 16:xx | `A03AccessibilityRuntimeSmokeTest` 完整流程 | `OK (1 test)` / 28.511s；三个 Reader Slider 均为单一 96px `SeekBar` 且支持 `ACTION_SET_PROGRESS` | cache 清理方案与 Reader 无障碍主链 GREEN |
| 16:xx | 首次引导定向 instrumentation | `OK (1 test)` / 12.141s | 同一引导节点可由 TalkBack 点击关闭，普通触摸仍穿透 |
| 16:20 | `/tmp/readflow-settings-final.xml` | 字号、行距均为单一 `SeekBar`，bounds 高度 96px | Settings 两个 Slider 48dp 语义节点 GREEN |
| 17:01 | AVD `wm size 720x1280` / 320dpi | `/private/tmp/readflow-settings-phone-postfix.png` 中主题预设自然换成四行，最右“深蓝”完整位于卡片内 | 最终 FlowRow 窄屏无溢出 |
| 17:04 | AVD `wm size 2560x1600` / 320dpi | Settings ScrollView `[440,213][2120,1552]` = 840dp；Library 首书 x=200 = 1120dp 居中容器左缘 160px + 20dp 内边距 | `widthIn -> fillMaxWidth` 修复实测 GREEN |
| 17:07 | 最终五模块 tests + 四模块 lint + AndroidTest compile + assembleDebug | `BUILD SUCCESSFUL in 23s`，598 tasks | 两项复审 P2 修复后的最终静态门 GREEN |
| 17:08 | 最新 dirty diff 独立代码复审 + Design Audit | 无 P0/P1/P2；19/20 Excellent | 本轮 UI redesign 可收口 |

## Parallel recovery outcome

- A03 根因恢复流已完成：确认 Compose 1.11.3 在仅 UiAutomator 连接时不发送 accessibility 内容变更事件，UiAutomator 2.3 会复用旧缓存；测试侧每轮动态查询前清缓存，生产 UI 无需为测试缓存增加 paneTitle 或手工事件。
- 主题容器角色恢复流已完成：Light/Dark/Sepia 的 `surfaceContainer*`、outline 与 inverse roles 已补齐，护眼黄无紫灰回退。
- 最终 `/root/postfix_diff_review` 与 `/root/final_visual_audit` 均完成；前者发现的两项 P2 已修复并复核闭环，后者给出 19/20、无 P0-P2。

## Final evidence

- 360dp Settings：`/private/tmp/readflow-settings-phone-postfix.png`
- 1280dp 横屏 Settings：`/private/tmp/readflow-settings-wide-postfix.png`
- 1280dp 横屏 Library：`/private/tmp/readflow-library-wide-postfix.png`
- 护眼黄书架菜单：`/private/tmp/readflow-library-sepia-menu-postfix.png`
- Reader 日间 / 夜间 / 360dp：`/private/tmp/readflow-reader-chrome-after-final.png`、`/private/tmp/readflow-reader-night-after.png`、`/private/tmp/readflow-reader-phone-final.png`
- Settings Slider XML：`/private/tmp/readflow-settings-final.xml`
- 宽屏 bounds XML：`/private/tmp/readflow-settings-wide-postfix.xml`、`/private/tmp/readflow-library-wide-postfix.xml`

## Residual boundary

- 当前验证均为 JVM、lint 与 AVD；按用户要求未做物理手机/平板实机验证。
- 尚未覆盖真人 TalkBack 语音内容与完整焦点顺序、OEM 字体/Insets、高刷新率帧表现、全部格式的逐页视觉遍历和宏基准。
- 本轮未修改 `android/render/**`、阅读内核、翻页算法、locator 或正文手势路由。
- 工作区保持未提交、未推送、未发布 OTA。
