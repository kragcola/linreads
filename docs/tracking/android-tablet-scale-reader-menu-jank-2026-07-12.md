# Android Tablet Scale / Reader Menu Jank — 2026-07-12

Mode: `task-bug`
Status: `completed`

## Objective

修复 `96af234` UI redesign 的两项用户可见回归：

1. 主页与设置在平板上视觉尺度过大，恢复旧版本按设备宽度分配的密度与比例，同时保留当前 Contemporary Editorial 风格。
2. Reader 内部菜单视觉方向保留，但点击打开/切换时动画卡顿；先取得可复现帧数据和根因，再对照成熟阅读器源码实施最小修复。

## Constraints

- Android only；不修改 EPUB/PDF/TXT/MD 阅读内核、翻页算法、locator 或正文手势路由。
- 缩小视觉尺寸时继续保留 Android 48dp 最小触控区与既有中文自动化标签。
- 体验验证仍以 AVD 为准；2026-07-13 用户已明确授权精确提交、推送 `main` 并发布 Android 开发版 OTA。
- 忽略无关 JAR、游戏服目录及 `android/core/model/bin/`。

## Acceptance

- 360dp：主页、设置不溢出，触控区仍为 48dp。
- 800dp tablet portrait：主页书架密度、页头、设置内容宽度/间距/字号回到 `96af234^` 的多设备比例量级，不再像手机 UI 等比放大。
- 1280dp expanded/landscape：内容保持合理限宽，不横向铺满。
- Reader 菜单：点击打开 chrome、点击六个入口切换面板均无明显首帧停顿；动画仅使用合成友好的 alpha/translation，不驱动 Reader viewport 重布局。
- 目标 60Hz AVD：菜单交互 P95 frame duration < 24ms，且无单帧 > 48ms；若 AVD 噪声无法稳定满足，至少相同设备/相同脚本相对基线明显下降并记录边界。

## Initial hypotheses

- Tablet scale：redesign 把固定 20/24dp padding、较大 typography 和低列数断点直接用于 800dp portrait，缺少旧版基于可用宽度的密度/尺寸约束。
- Menu jank H1：`AnimatedVisibility` 的 expand/shrink 或 slide placement 触发整棵 Reader overlay 重测/重组。
- Menu jank H2：点击时面板内容首次组合，目录/搜索/书签等数据与复杂节点同步创建，首帧工作量超过 16ms。
- Menu jank H3：高圆角 Surface、shadow/tonal elevation 或纸纹背景在动画期间扩大重绘区域；卡顿不是时长曲线本身。

## Parallel ledger

- `/root/tablet_scale_audit` — `completed`，确认固定 2/3/4/5 列与放大后的标题/设置结构是回归根因。
- `/root/mature_menu_motion_research` — `completed`，Moon+ Reader、Legado、Readium、FBReaderJ 均不动画 Reader 菜单高度或正文 viewport。
- `/root/tablet_red_contract_tests` — 中断前已将 RED 测试和结果落盘；6 项中 5 项 assertion failure、0 error，成果已保存。
- `/root/reader_menu_jank_rca` — `failed_after_30m`；canonical target 连续 30 分钟不可见，基线采集文件完整保留。
- `/root/reader_menu_jank_rca_recovery` — `completed`；完成 motion / shadow / first composition / TOC 变量隔离与同脚本帧数据汇总。
- `/root/reader_motion_red_contract` — `completed`；motion policy RED→GREEN，Reader 81/81。
- `/root/reader_overlay_code_review_reconstructed` — `completed`；重建后只读复核无 P0/P1，确认退出内容塌缩、panel 空白触摸穿透、横向 navigation inset 丢失、panel/base 共同退出位移脱节四项 P2。
- `/root/tablet_reader_avd_verify_reconstructed` — `failed_after_30m`；已落盘 360/800/1280dp Library/Settings 截图、hierarchy 与触控报告，未执行 Reader 修后验收。
- `/root/reader_panel_exit_red_test` — `failed_after_30m`；连续不可见 30 分钟且无文件落盘。
- `/root/reader_panel_exit_red_test_rebuilt` — `completed`；独立写入可编译的退出内容保留 RED，2/2 以明确 `ClassNotFoundException` assertion failure 失败。
- `/root/reader_overlay_postfix_review` — `completed`；429 后恢复同一 canonical target，最终确认四项 P2 全部闭环，无新增 P0/P1/P2。
- `/root/tablet_reader_avd_verify_final_rebuilt` — `interrupted`；最终 APK 安装、Reader bounds、空白触摸、子控件与视频证据已保存，父任务中断后由窄范围重建任务继续。
- `/root/reader_framestats_only_rebuilt` — `completed`；18 个 warmed interaction、FrameTimeline CSV、critical logcat 与最终 SHA/display 复核完成。
- `/root/reader_offline_evidence_audit_rebuilt` — `completed`；纯离线核对 base bounds、48dp、safe bounds、触摸隔离与视频证据边界。

## Root cause evidence

### Tablet scale

- `BookGrid.kt` 从旧版 `GridCells.Adaptive(116.dp)` 攦为按完整窗口选择固定 2/3/4/5 列，但网格随后又限宽 1120dp；800dp 因此只有 4 列、1280dp 只有 5 列。
- 单格宽由旧版量级的 138.4dp / 129.75dp 膨胀到 172dp / 193.6dp，分别增加约 24% / 49%。
- 书架标题从 22/30sp 放大到 34/42sp；设置新增五组说明、20dp 卡内边距和 24dp 节间距，五节固定结构开销约 606dp。
- 已实现目标：360/800/1280dp 分别为 2/5/7 列，单格约 150/132.8/130.29dp；书架标题 26/34sp、设置标题 22/30sp；设置移除新增说明层并收紧为 16dp 页面边距、16dp 节间距、16dp 卡内边距和 12dp 内容间距。
- 全局 shape 未回退，Reader 菜单视觉不受平板比例 slice 影响；所有明确触控目标继续保持至少 48dp。

### Reader menu motion

- 当前 `ReaderControlPanel` 使用未指定 transition 的 `AnimatedVisibility`。Compose 1.11.3 默认 `fadeIn + expandIn` / `shrinkOut + fadeOut`，其中 size spring 会逐帧改变底部 Surface 的测量高度。
- 顶部与底部 chrome 的 slide/fade 同样未指定 animation spec，走默认 spring；底部 Surface 同时带 10dp shadow elevation。
- Moon+ Reader 9.7 将正文和 top/bottom/font/brightness 作为同一 FrameLayout 的 sibling overlay，只做 150ms translation；子面板直接切 visibility。
- Legado 使用 sibling overlay 和 150–200ms translation，复杂设置在主菜单退出后才打开；Readium 使用独立 BottomSheet window；FBReaderJ 缓存 popup 后直接 VISIBLE/GONE。
- 成熟实现的共同约束是 Reader viewport 稳定、菜单高度不参与逐帧动画；首次预组合全部面板并非必要条件。
- 已实施方案 A：保留现有视觉，把面板与稳定底部 chrome 分离；top/base/panel 使用显式 150ms、12dp alpha/translation tween，不使用 expand/shrink、animateContentSize、SizeTransform、spring 或 panel crossfade。退出期间保留最后非 TOC panel 内容；panel 空白触摸由 overlay 消费，横向 navigationBars inset 独立补齐。

## Test ledger

| Time | Experiment | Actual result | Conclusion |
|---|---|---|---|
| 18:01 | 1600×2560 AVD，首次打开 Reader chrome | 12/12 frames >16.67ms；total P50 148.49ms、P95 191.00ms、max 193.96ms；post-SyncQueued P95 141.59ms | 首次打开稳定复现严重卡顿，主要耗时位于 RenderThread/GPU 后段，非单纯 Compose app-thread 首次组合 |
| 18:04 | 同脚本 warmed chrome 开/关，各 5 次 | 每轮动画帧几乎全部 miss deadline；open P95 119.61–252.71ms，close P95 136.71–268.11ms | 卡顿并非仅首次组合；默认 spring/大面积 shadow 合成链仍需隔离 |
| 18:11–18:22 | FONT、THEME、TOC 首次及 warmed 切换 | FONT first P95 293.17ms；THEME first P95 201.35ms；TOC first P95 253.09ms；warmed 仍显著 miss | 首次内容创建会放大尖峰，但 warmed 仍卡，不能只靠预组合处理 |
| 18:28 | `LibraryGridLayoutTest` RED | 6 tests，5 failures，0 errors；800dp 期望 5 实际 4，display 期望 26sp 实际 34sp 等均为具体 assertion failure | 测试有效命中比例回归，不是编译错误 |
| 18:45 | 平板比例 GREEN + Library/Settings compile | `:core:ui:testDebugUnitTest` 6/6；两个 feature Kotlin compile 成功 | 2/5/7 列、封面上限及标题比例契约已闭环 |
| 19:20 | Reader motion policy RED | Reader 81 tests：新增策略测试 1 failure、原有 80 pass、0 error；失败为 policy object 不存在 | RED 可编译且精确锁定 150ms、8–12dp、alpha/translation-only、无 spring/无 size animation |
| 19:24 | 平板 postfix review | 发现 1000dp gap 切换使 7 列倒退为 6 列，以及测试复制常量可能假绿，两项 P2 | 补 995..1021dp 单调 RED；抽取 production `libraryGridLayout()` 统一消费 Dimens |
| 19:27 | P2 GREEN + re-review | `LibraryGridLayoutTest` 7/7，995..1021dp 列数单调；同一 metrics 驱动生产列数/gap/限宽，review 无剩余 finding | 平板静态 slice 闭环，待 AVD 像素与 semantics 验证 |
| 20:xx | RCA recovery 三变量隔离 | baseline chrome-open P95 231.76ms；motion-only 155.94ms（-32.7%）；主线程 P50 多为 1–8ms，post-Sync P50 80–125ms；shadow=0 结果混杂；TOC-first app P95 4.64ms | 默认 spring/size animation 是放大器；主导瓶颈在大面积 overlay 的 RenderThread/GPU present；采用 sibling overlay + 150ms alpha/translation，panel 间直接替换 |
| 20:xx | 方案 A production GREEN | panel 移出 base chrome sizing Column；base 固定测量，panel sibling overlay 以 base 实测高度锚定并重叠 12dp；top/bottom/panel/TOC 显式 150ms tween；Reader 81/81 | 生产实现达到 RCA 的最小架构方向，待独立复核与最终 AVD |
| 20:57 | 五模块 test + 四模块 lint + AndroidTest compile + assemble | `BUILD SUCCESSFUL in 12s`，598 tasks；`git diff --check` clean | 初版 sibling overlay 静态集成门 GREEN |
| 21:xx | 独立 overlay postfix review | 无 P0/P1；四项 P2：exit content 先塌空、panel 空白穿透、横向 navigation inset 丢失、共同退出位移脱节；首次锚点与 RTL TOC 为 P3 | 最终 AVD framestats 暂停；先修四项 P2，再重建 APK |
| 21:15–21:22 | AVD 360/800/1280dp Library/Settings | hierarchy 实测首行 2/5/7 列，封面宽 150/133/130.5dp；三档截图无横向溢出；完整可见 clickable/adjustable 节点均至少 48dp，360dp 初始报告的小于 48dp 项均由 viewport 顶/底裁切，滚动后恢复 | 平板比例与触控中间证据 GREEN；Reader 证据因 postfix P2 作废并等待修后 APK |
| 21:3x | 三项独立 P2 局部修复 compile | top/base/panel 统一 12dp 短位移，panel 补横向 navigationBars inset 并吞空白触摸；`:features:reader:compileDebugKotlin` `BUILD SUCCESSFUL in 16s` | 三项生产修复可编译；退出内容保留仍等待独立 RED 测试恢复 |
| 22:0x | 退出内容保留 RED→GREEN | 重建 RED 写手新增 `ReaderPanelMotionStateTest`，初始 2/2 明确 failure；生产新增 `ReaderPanelMotionState`，`contentFor` 立即切非空 panel、`commit` 仅在 `SideEffect` 后保留最后非 TOC panel；目标测试 GREEN | 退出时内容不再先塌空，非空 panel 间仍直接替换 |
| 22:07 | 最终五模块 test + 四模块 lint + AndroidTest compile + assemble | `BUILD SUCCESSFUL in 37s`，598 tasks；Reader 83/83；最终 APK SHA-256 `a508fb1826ee2676e6f6257db9bffa88e21092e82365098a525f62c119b38378`；`git diff --check` clean | 四项 P2 修后的最终静态门 GREEN |
| 22:xx | 独立 postfix re-review | 四项原 P2 全部闭环，无新增 P0/P1/P2；仅 P3 为 JVM 测试未直接覆盖 Compose 命中/布局 | 代码层收口，动态 AVD 负责补行为证据 |
| 22:11–22:18 | 最终 APK Reader AVD 行为验收（中断前） | API36 / 1600×2560@320dpi，设备安装 SHA 精确匹配；FONT/THEME/SEARCH/BOOKMARKS/ANNOTATIONS 前后 base 的章节/进度/六入口 bounds 逐像素不变，panel/base 在 y=2166 无缝；主题子控件可切夜间；点击 panel 标题、padding、空书签区不翻页、不隐藏 chrome；chrome-toggle 采样帧中正文不重排，但 panel-close 录像未命中关闭终态 | sibling overlay、子控件命中与空白拦截动态 GREEN；专用 panel-exit 录像保持未覆盖边界 |
| 23:xx | Reader 离线证据终审 | 五种 panel 下 base 11 组 bounds 最大差 0px；panel bottom/base surface top 同为 y=2166；05–14 共 157 个完整可见交互/可调节点全部 >=48dp；主题子控件生效，标题/padding/空列表点击前后应用区域 0 像素变化 | 固定 base、无缝静态接缝、48dp 与空白防穿透 GREEN；gesture-nav 竖屏 safe bounds GREEN，三键侧栏/横屏 Reader 未覆盖 |
| 23:xx | 最终 warmed FrameTimeline（18 events） | chrome-open P50/P95/max 122.86/155.75/161.66ms；baseline P95 231.76ms，改善 32.8%；motion-only 155.94ms，最终仅低 0.1%（噪声内持平）。其余 P95：close 192.41、font 243.64、theme 201.32、TOC close/open 264.02/271.24ms；六类均 MISS 24ms，几乎全部帧 >48ms | 相对 baseline 的 fallback 验收满足；绝对 AVD 性能门未过，主导瓶颈仍为 ANGLE/SwiftShader RenderThread/post-Sync，与 RCA 一致，不能宣称 sibling overlay 带来额外帧时收益 |
| 23:xx | 最终 logcat / identity / display | Reader PID critical 0；55 条 AndroidRuntime 均来自 shell uiautomator；APK SHA 前后均 `a508fb1826ee2676e6f6257db9bffa88e21092e82365098a525f62c119b38378`；最终 1600×2560@320dpi、gesture nav、Reader 前台 | 无 Reader crash/ANR/OOM/assertion/recycled；测量身份与显示口径稳定 |

## Next step

无代码阻断项。本批次由当前实现 commit 推送 `main` 后触发 `Android Dev Release`，远端发布身份以 workflow 的 commit / BUILD_TAG / `dev-latest` 资产为准。后续若继续打磨，需在物理设备或硬件加速基准上评估真实手感；三键侧栏/横屏 Reader、专用 panel-close 高帧率录像与 Compose UI 接线测试属于明确遗留，不影响本轮 AVD fallback 收口。
