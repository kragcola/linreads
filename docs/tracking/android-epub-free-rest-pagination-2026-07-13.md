# Android EPUB FREE_REST Pagination

_最后更新：2026-07-14_

## Objective

- 分页模式中区临时滚动使用原生惯性；松手不吸附，下一次翻页才按完整行对齐。
- 静止分页上下边缘不绘制半行文字，尾页仍能到达原始行顶。

## Decisions

- 用户选择方案 A：Moon+ Reader 风格 `FREE_REST`，动态行窗口决定下一页，不用最近 canonical page 算术导航。
- 状态链：`ALIGNED -> DRAGGING_FREE -> FLING_FREE -> FREE_REST -> ALIGN_AND_TURN`。
- 正向目标从当前 viewport 最后一条完整行之后开始；反向目标以当前顶部相交行作为 exclusive end。
- 取消翻页恢复事务开始时精确 `scrollY`；提交动画结束后才发布 locator。
- oversized image/block 保留为单行不可分割例外。
- 用户选择 cold-cache 方案 B：本地 page shots 每帧最多生成一张；手指保持按下时记录最新坐标，shots ready 后续接同一次手势，不要求重拖。
- 用户选择 A1：page-shot 始终使用 `ARGB_8888`；低内存只减少或关闭 speculative prewarm，不用 RGB_565 降画质。local 与 hidden boundary renderer 共用 session byte-budget，并以三个 distinct identity 为硬上限：两张 active working frames + 一张 continuity/方向帧。

## Scope

- Production：`android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubFlowPaginator.kt`
- Production：`android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubFlowView.kt`
- Production：`android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubReflowEngine.kt`
- Production：`android/render/epub/src/main/kotlin/dev/readflow/render/epub/PageShotBudget.kt`
- JVM：`android/render/epub/src/test/kotlin/dev/readflow/render/epub/EpubFlowPaginatorTest.kt`
- JVM：`android/render/epub/src/test/kotlin/dev/readflow/render/epub/EpubFlowViewTest.kt`
- JVM：`android/render/epub/src/test/kotlin/dev/readflow/render/epub/EpubReflowEngineTest.kt`
- JVM：`android/render/epub/src/test/kotlin/dev/readflow/render/epub/PageShotBudgetTest.kt`
- AndroidTest：`android/app/src/phase2AndroidTest/java/dev/readflow/page05/EpubFlowAnchorRuntimeSmokeTest.kt`

## Confirmed Root Causes

- 临时滚动 MOVE 为手写 `scrollTo`，UP 未调用 `ScrollView.fling`。
- 旧 UP/CANCEL 会调用 `settleTemporaryScrollAnchor()` 吸到最近 canonical page。
- paginator 曾使用含 TextView 上下 padding 的完整 viewport 高度。
- 尾页 top 曾被自然 `maxScroll` 截断到非行顶。
- 离散/交互翻页和纹理缓存仍以 `currentPage +/- 1` 为目标，无法延续任意 FREE_REST viewport。
- `ScrollView.fling(0)` 会把零速 replacement 固定在调用瞬间的旧 `scrollY`；若随后程序化跳转，下一次 `computeScroll()` 会把 viewport 拉回旧位置。
- `dispose()` 清空 `TextView` 会触发布局回调；旧实现保留 `flow` 且 listener/runnable/post 无 disposed gate，退休 view 可重新分页、分配 bitmap 或消费 pending page turn。

## Current State

- `epubPageFromStartLine()` / `epubPageEndingAtLine()` 已提供任意 FREE_REST viewport 的前后动态行窗口；离散与手指翻页共用该目标，不再以 `currentPage +/- 1` 决定内容。
- 中区慢速释放保留精确 `scrollY`，快速释放走 `ScrollView.fling`；松手不吸附，下一次翻页才进入 `ALIGN_AND_TURN`，并按完整行窗口落页。
- live draw、snapshot、texture key 和 active window 已统一 padding-aware 上下 clip；普通文本页不再绘制半行，oversized image/block 保留 pixel slice，尾页原始 line-top 可达。
- 取消翻页恢复事务起点的精确 viewport；提交后才发布 locator。FREE_REST 预热纹理可按 bitmap identity 转移，布局/主题/高亮/图片变化会使缓存失效。
- 生命周期已加 disposed gate；dispose 会关闭 flow/reveal/pending initial navigation，排队的 layout/reflow/resize/precache work 不能复活退休 view。
- free-fling interruption 现在使用统一 pending-rebase 事务：先在旧 viewport 替换 scroller，最终模式切换、换章、resize 或显式导航定位后，再在目标 viewport 重基准，旧轨迹不能覆盖目标。
- 动态尾页如果落点超过 canonical last top，会同步扩展真实 scroll extent；临时 drag/fling 和正式翻页都能到达该完整行顶，不会被旧 `maxScroll` 截回上一页。
- `DRAGGING_FREE` 已纳入 reflow 延迟；异步图片/高度变化不会在手指仍按住时重分页并把 viewport 从 `159` 吸回 `105`。
- `reportTopOffset()` 本身带 disposed gate；销毁时取消动画不会再发布 locator。oversized 单行的 live/snapshot 底部 padding 裁切也已统一。
- 分页元数据按 layout generation、章节、宽高、行数和 page height 校验；段落区间与页表使用二分查找，warmed `FREE_REST` 翻页不再扫描整章。已排队 precache 时会在计算动态窗口前直接退出。
- 最终 fixes-only 独立复核覆盖上述五项收尾修复，未发现新增 actionable P0-P3。
- cold-cache 方案 B 已完成主体实现：foreground target/front 与 background front/target/previous 都按真实 animation frame 分拆；手指保持时记录最新 MOVE，shots ready 后续接同一手势。callback 绑定 request/token，迟到 A 不能读取、覆盖或清理 replacement B；staged pair 完整时按 bitmap identity 转交，不完整时在同步 fallback draw 前回收。
- held 与 released settle 现在有独立 owner：视觉 invalidator 在 held 时恢复 origin，在已释放 commit/cancel 时按释放决策同步收口；goTo/mode/resize/chapter/dispose 放弃旧 origin，由外部 mutation 最终拥有。`clearBoundaryPreviews()` 只取消 boundary-owned animator，不再抢先发布 local settle 的 parked locator。
- 方案 B correctness/lifecycle 复审已收口，无剩余确定性 P0-P2。后续源码级内存审计发现原“只计算本地三槽”的 A/B 前提不完整：`cachedFront/cachedRevealed/cachedBackward` 可与前后两个 `BoundaryPagePreview` 同时常驻，稳定态是五张；离散跨章路径再抓 outgoing 时可达六张。1080×2400 ARGB_8888 对应约 49.44/59.33 MiB，1600×2560 对应约 78.13/93.75 MiB。
- Moon+ Reader Pro 9.7 的 Java + smali 链路确认其稳定态只有 `current + 单向 next` 两张 RGB_565，后翻丢弃 next 后现场生成 previous；跨章也只缓存下一章一张。CoolReader、FBReaderJ、Legado 与 android-PageFlip 的单页路径同样以 current+本次 target 两工作帧和 identity/slot 转交为主；KOReader、Glide、Coil、Fresco 则提供按字节预算、方向预取、LRU/trim/low-memory 降级证据。没有成熟证据支持 LinReads 固定三张 ARGB，更不支持章内三张之外再无预算地保留双向跨章 preview。
- A1 已落地：`PageShotBudget` normal 为 `min(memoryClass / 8, 48 MiB)`，low-RAM 为 `min(memoryClass / 10, 24 MiB)`；分配前 reservation、commit 后按 `allocationByteCount` 计费，speculative admission 受软预算与 trim/backoff 控制。PINNED active 帧可越软字节上限，但不能越三个 identity 的硬上限；第四张会先走 owner-aware speculative eviction，再决定是否拒绝。
- settle 后 active front/target 按 bitmap identity 重标为下一稳定 cache；离散跨章复用 warmed outgoing，不再额外抓第六张。continuity cover 存在时，会在 cold handoff 前逐出 inactive boundary，确保 cover+target+front 峰值仍为三张。
- boundary owner 时序已闭环：preview 在 outgoing/conversion 分配前即成为 `activeBoundaryPreview`；失败先撤销 active owner 再恢复原 token。已运行的 speculative hidden renderer 若随后升级为真实边界等待，会在 capture 时动态读取 required 状态，转入 PINNED admission，避免三槽满时 EVICTABLE 失败后等待到超时。
- background trim 可前台恢复，OOM/severe pressure 在本 session 保持 backoff；active boundary token/方向与 continuity cover 不被 speculative trim 误删。等待失败会退出 view waiting 状态但保留可重试 token；异常无 drawable settle 会清空旧 `curlOrigin/curlTargetWindow`。
- 生产与测试修改仍未提交、未推送、未发布 OTA；用户明确暂不做物理设备验证。

## Next

1. 保持 A1 与既有 FREE_REST/完整行裁切语义不变；不再引入 RGB_565 fallback 或无界 bitmap pool。
2. 物理手机/平板继续 deferred；后续实机只验手感、真实帧/PSS、系统 trim/OOM 行为，不把本轮 AVD 结果冒充实机。
3. `snapshotPageAt()` 的裸 Bitmap lease/release 契约与未入账 preview 的防御性校验属于非阻断 API hardening；当前无 production caller，可在下一轮测试接口收窄时处理。
4. 提交、推送与 OTA 仅在用户明确要求后执行；当前保持 dirty worktree。

## Mature Project Source Audit

- Moon+ Reader Pro `9.7 (907005)`：`ActivityTxt.createCachePageShots()` 只保留 current+next；smali `get3dCurlShot()` 证实前翻转交 current/next，后翻先回收 next 再生成 previous。普通单页 shot 为 RGB_565；没有 page-shot byte budget、trim 或 pool，冷缓存等待会退化为硬翻，因此只借鉴两槽/方向偏置，不照搬冷路径。
- CoolReader `064723e` / FBReaderJ `e83aec9`：固定 current+target 两槽，完成后 shift/relabel identity；CoolReader 按刚才方向继续预热并有最多两个空闲 exact-size buffer，二者普通 LCD 均偏向 RGB_565。
- Legado `c6513c7`：虽有 prev/current/next recorder，横翻仍按手势方向只录 current+target；默认 ARGB_8888 并回 Glide pool，可选 Picture/RenderNode 只适合 slide/cover，仿真仍依赖 bitmap。
- Librera `2b277c8`：ViewPager 名义 1/3/5 页窗口，离窗 fragment 销毁，页图由 Glide 异步缓存；trim/OOM 会清缓存。不能把它的三页 UI 窗口等同于三张无预算 page-shot。
- android-PageFlip `6c361e0`：单页 CPU 侧只复用一张绘制 bitmap，GPU 侧 current+target 两 texture；翻完转交 texture ID 并延迟删旧 texture。它证明 slot rotation，不证明固定三张 CPU shot。
- KOReader `035497b`：全局文档 LRU 预算为可用内存 40%，夹在 16–512 MiB；默认只 hint 下一页一张，单项超过缓存一半就拒绝，整页放不下则改渲染局部，低可用内存时砍半缓存。
- Glide `b5b428b`、Coil `d22b61d`、Fresco `57fdac3`：均按实际 bitmap 字节与 memory class/档位做有界 LRU，并有 low-memory/trim 清理思想；没有源码证据支持无条件固定三张活跃全屏截图。Glide 的“屏数”还分属 resource cache 与 bitmap pool，不能直接解读成三张 page-shot。

## Test Ledger

| Time | Command | Actual | Conclusion |
|---|---|---|---|
| 2026-07-13 resume | `:render:epub:testDebugUnitTest --tests EpubFlowViewTest` | 既有检查点：七个行为断言 RED；padding/final-top 三项 GREEN | RED 已锁定目标行为，继续实现生产链路 |
| 2026-07-13 lifecycle RED | focused `dispose prevents...` + `programmatic same chapter navigation...` | 2 assertion failures：dispose 后 generation `1 -> 2`；导航后旧 fling trace `[169,233,296,359]` | 锁定 dispose 复活与程序化导航 scroller ownership |
| 2026-07-13 rebase RED | focused mode switch / new chapter restore | mode trace `[0,105,105,105,105]`；换章从 restored `1365` 回旧 `105` | `fling(0)` 需在最终目标 viewport 再基准 |
| 2026-07-13 dispose-post RED | focused queued size reanchor | `retiredY=2730 finalY=2730 reported=[] tapZones=[NEXT]` | resize post 在 dispose 后仍消费 pending boundary turn |
| 2026-07-13 focused GREEN | 四条 lifecycle/rebase focused | `BUILD SUCCESSFUL in 12s` | mode、chapter、deferred resize 与 dispose 隔离闭环 |
| 2026-07-13 full EPUB | `./gradlew --no-daemon -Preadflow.phase=2 :render:epub:testDebugUnitTest --rerun-tasks` | 392 tests / 0 failures / 0 errors / 2 skipped；`BUILD SUCCESSFUL in 53s` | 强制重跑无缓存掩盖 |
| 2026-07-13 integration | Reader + Animate tests、EPUB lint、AndroidTest compile、debug assemble | 434 tasks；`BUILD SUCCESSFUL in 48s` | 跨模块静态/构建门通过 |
| 2026-07-13 AVD install | `:app:installDebug :app:installDebugAndroidTest` | Android 16 `readflow_test(AVD)` 安装成功 | 运行态 APK 与测试 APK 已更新 |
| 2026-07-13 AVD focused | `EpubFlowAnchorRuntimeSmokeTest` 两条 FREE_REST 用例 | `OK (2 tests)` / `24.106s` | 下一完整行续页与 MoonReader 中区语义通过 |
| 2026-07-13 logcat | targeted AVD 后 critical grep | `NO_CRITICAL_LOGCAT_MATCHES` | 无 crash / ANR / OOM / recycled bitmap / assertion 签名 |
| 2026-07-13 dynamic-tail RED -> GREEN | focused dynamic last-line extent | RED：`canonicalLastTop=2730`、`targetTop=2765`、`finalY=2730`；GREEN 后到达 `2765` | 动态尾页真实 extent 与 drag/fling 上限闭环 |
| 2026-07-13 dragging-reflow RED -> GREEN | focused `DRAGGING_FREE` async reflow | RED：手指仍按住时 `scrollY 159 -> 105`，分页高度 `2800 -> 4200` | 拖动期间延迟 reflow，松手前不吸附 |
| 2026-07-13 dispose-locator RED -> GREEN | focused committed animator dispose | RED：预期 locator `[]`，实际 `[60]` | `reportTopOffset()` disposed gate 阻止退休 view 发布进度 |
| 2026-07-13 oversized-padding RED -> GREEN | focused live/snapshot bottom clip | RED：live bottom `155`、snapshot bottom `144`，相差 `11px` | oversized 单行 live/snapshot padding 语义统一 |
| 2026-07-13 warmed-complexity RED -> GREEN | 512 segments / 341 pages deterministic counter | 旧访问 `segmentAccesses=2061`、`pageAccesses=2379`；代际 metadata + 两级二分后复杂度门 GREEN | warmed FREE_REST 不再整章扫描 |
| 2026-07-13 final EPUB rerun | `./gradlew --no-daemon -Preadflow.phase=2 :render:epub:testDebugUnitTest --rerun-tasks` | 397 tests / 0 failures / 0 errors / 2 skipped；`BUILD SUCCESSFUL in 54s` | 当前 JUnit XML 亦核对为 `397/0/0/2` |
| 2026-07-13 final integration | Reader + Animate tests、EPUB lint、AndroidTest compile、debug APK assemble | 434 actionable tasks；`BUILD SUCCESSFUL in 53s` | 跨模块静态、测试与构建门最终通过 |
| 2026-07-13 final AVD | Android 16 AVD 两条 FREE_REST runtime | terminal checkpoint：`OK (2 tests)` / `24.387s`；live TestRunner log 佐证两用例约 `24.382s` 内完成且 critical 签名为 0；JUnit XML 后被单用例覆盖 | 运行态下一完整行续页与中区语义通过；非物理设备 |
| 2026-07-13 fixes-only review | 独立静态复核 dynamic extent / dragging reflow / dispose / oversized clip / metadata | 无 actionable P0-P3 | 最终代码复核收口 |
| 2026-07-13 final whitespace | `git diff --check` | exit 0 | 当前 production/test/docs diff 无空白错误 |
| 2026-07-13 cold B full EPUB | `:render:epub:testDebugUnitTest --rerun-tasks` | 404 tests / 0 failures / 0 errors / 2 skipped；`BUILD SUCCESSFUL in 50s` | 分帧 handoff、invalidation matrix 与三帧 precache 强制重跑 GREEN |
| 2026-07-13 cold B integration | Reader + Animate tests、EPUB lint、AndroidTest compile、debug assemble | 434 actionable tasks；`BUILD SUCCESSFUL in 49s` | 联合静态/构建门 GREEN |
| 2026-07-13 cold B AVD | 旧关键 3 条 + 新 Choreographer 真帧 1 条 | `OK (3 tests)` / `41.259s`；`OK (1 test)` / `13.37s`；critical logcat 0 | 真帧 target/front 分拆、同手势续接与既有 FREE_REST/A2 路径通过；非物理设备 |
| 2026-07-13 P1 review RED | late callback、staged owner、held/settling mutation、draw 内 A→B、boundary pre-clear focused | 所有目标断言先 RED；代表值包括 staged `2 -> draws 4`、旧 locator `[60,120]` | 锁定 request ownership、峰值 owner 与 locator publication 顺序 |
| 2026-07-13 P1 GREEN | 新 focused + `EpubFlowViewTest` class | focused 全 GREEN；整类 `BUILD SUCCESSFUL in 21s` | request/token、bitmap identity/recycle、held/settle 与 boundary owner 闭环 |
| 2026-07-13 P1 final review | replacement read-only production review | 无确定性 P0-P2；32 MiB budget 按约定不计 | correctness/lifecycle 收口，等待用户选择稳定态内存策略 |
| 2026-07-13 mature page-shot memory audit | Moon+ 9.7 Java+smali；CoolReader/FBReaderJ/Legado/Librera/android-PageFlip/KOReader；Glide/Coil/Fresco；LinReads owner graph | 成熟阅读器主路径收敛为 current+方向 target 两工作帧；LinReads 实测 owner 图为稳定 local3+boundary2=5、离散跨章峰值=6 | 原本仅比较本地三槽的 A/B 作废；改为统一 budget/admission 的 A+，等待 A1/A2 像素格式选择 |
| 2026-07-14 A1 focused RED/GREEN | identity rotation、one-shot soft budget、active boundary owner、waiting retry、no-drawable lifecycle、第四 identity、required-session promotion | 所有目标先由明确行为断言锁定；最终 focused 全 GREEN | ARGB_8888 active pair 可越软预算但不可越三 identity；owner/token/lifecycle 闭环 |
| 2026-07-14 A1 full EPUB | `./gradlew --no-daemon -Pkotlin.incremental=false -Preadflow.phase=2 :render:epub:testDebugUnitTest --rerun-tasks` | 441 tests / 0 failures / 0 errors / 2 skipped；45/45 tasks；`BUILD SUCCESSFUL in 44s` | 全模块强制重跑通过 |
| 2026-07-14 A1 integration | Reader + Animate tests、EPUB lint、AndroidTest compile、debug assemble | Reader 83/83；Animate 13/13；lint 0 errors / 8 warnings；434 tasks；`BUILD SUCCESSFUL in 1m14s` | 跨模块静态、测试与构建门通过 |
| 2026-07-14 A1 AVD | Android 16 AVD：短章节边界、continuity cover、cold handoff 三条 runtime | `OK (3 tests)` / `41.85s`；critical logcat grep 无匹配 | 运行态关键链路通过；非物理设备 |
| 2026-07-14 A1 final review | owner replacement + completion code review | Critical 0 / Important 0；`git diff --check` clean | P0/P1 收口；仅保留 test-only Bitmap lease API Minor |

## Rollback

- 仅回退本 tracker 中列出的 Android EPUB production/test 文件；不得触碰工作树中的服务器/Minecraft 未跟踪文件。
