# Maintenance Log

（逆时间序，最新在最上面）

---

## 2026-06-26

### Android v4 EPUB 分页跨段 selection 补证
- 回填 `PAGE-05`：短段落合并到同一 paged Compose 页后，跨段拖选现在会把 selection start/end locator 映射到首个/最后一个有效段落，而不是只保存第一个 segment，避免合并页上高亮/笔记跨段丢尾
- 验证：RED `./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime maps compose selection across packed paragraphs"` 先失败于 `selectedText` 只包含第一段；GREEN 后同一测试与相邻短段/对话段合并测试通过；`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest" --tests "dev.readflow.render.epub.EpubPageMappingTest"` 通过
- 边界：这是 Robolectric/code-contract 证据，不是真实设备 Compose selection action-mode、TalkBack speech、混合 EPUB 视觉或性能预算；`PAGE-05` 仍保持 `PARTIAL`

### Android v4 纯阅读 AVD 尾项补证
- 回填 `UX-02`：不再把 AVD 口径写成“无法模拟双指 pinch”。基于 `ReaderPinchRuntimeSmokeTest` 的 instrumentation multi-touch 注入，`adb -s emulator-5554 shell am instrument -w -e class dev.readflow.ux02.ReaderPinchRuntimeSmokeTest dev.readflow.test/androidx.test.runner.AndroidJUnitRunner` 通过 `OK (2 tests)`
- 证据：`/tmp/readflow-ux02-runtime-20260626-final/ux02-runtime-smoke/txt-summary.txt` 记录 TXT 字号预览 `16sp -> 22sp`、可见文本 `42.0px -> 59.294098px`、持久化 `font_size_sp=22`；`pdf-summary.txt` 记录 PDF `FIT_CENTER -> MATRIX`，`matrix_scale_x/y=1.4117643`，并保留前后截图
- 边界：这属于 AVD instrumentation 注入手势，不等于真实手机/平板双指手感、缩放视觉细节或跨尺寸调校；`UX-02` 仍保持 `VERIFY`

### Android v4 EPUB 分页运行时补证
- 回填 `PAGE-05`：`adb -s emulator-5554 shell am instrument -w -e class dev.readflow.page05.EpubPagedRuntimeSmokeTest dev.readflow.test/androidx.test.runner.AndroidJUnitRunner` 通过 `OK (3 tests)`，把 EPUB paged gate 从 Robolectric/code-contract 推进到 AVD runtime reader route
- 证据目录：`/tmp/readflow-page05-runtime-20260626-final/page05-epub-runtime-smoke`
- `mode-switch-summary.txt`：paged text page 在切回 scroll 后，旧 compose root 的 `pageProgress/selection/semantics/stateDescription` 全部清空
- `link-navigation-summary.txt`：页内链接从 `Read the scene after selection.` 跳到图片 fragment 页 `Scene art，第 3 页，共 4 页`，旧 selection/highlight 归零，DB progress 落到 `LocatorStrategy.Section(spineIndex=1, elementIndex=1, charOffset=1)`
- `image-cover-summary.txt`：image-only cover 首屏直接落在图片页 `第 1 页，共 2 页`，下一页进入正文 `Chapter one text starts immediately after the image-only cover.`
- 边界：仍是 AVD runtime + XML/DB/screenshot 证据，不是真机图文分页视觉、TalkBack speech/action-mode、Compose selection action-mode、帧率/内存预算或真实 Calibre/SAF smoke；`PAGE-05` 继续保持 `PARTIAL`

### Android v4 高亮/笔记 TXT note-save 稳定性补证
- 回填 `READ-05`：`adb -s emulator-5554 shell am instrument -w -e class dev.readflow.read05.TxtRead05NoteSaveSmokeTest dev.readflow.test/androidx.test.runner.AndroidJUnitRunner` 通过 `OK (1 test)`
- 结论：当前 phase2 构建下，TXT note-save 链路已能稳定完成“输入笔记 -> 保存 -> 选区浮层消失 -> Room 持久化 -> 标注面板回看”
- 边界：这次 smoke 没补齐 MD runtime 锚点、EPUB link-selection/highlight coexistence、TalkBack/action-mode、真机长按保存和后续备份/同步导出，`READ-05` 仍保持 `VERIFY`

### v4 纯阅读 grouped AVD/runtime 文档回填
- 回填 `docs/tracking/android-v4-pure-reading-gap-checklist.md` 与 `docs/tracking/android-v4-pure-reading-unfinished-backfill.md`：`SRC-01~09` 统一升级为 2026-06-24/25 的 grouped AVD/runtime 证据，覆盖 Calibre 连接/发现/搜索下载/LRU/移除/离线，以及 Backup 导出/恢复
- 保留真实设备、TalkBack 语音遍历、性能预算、Calibre/SAF 真机 smoke 作为后续必须补的 `VERIFY` / `PARTIAL` 边界
- 目前仍未修改业务代码；这是纯文档/维护回填，后续只需继续补真机或构建验证即可
- 验证：`git diff --check -- docs/tracking/android-v4-pure-reading-gap-checklist.md docs/tracking/android-v4-pure-reading-unfinished-backfill.md` 通过

## 2026-06-24

### LinReads Backup schema hardening 回填
- 回填 SRC-09：备份恢复 schema 校验从“只拒绝未来版本”收紧为必须显式声明且只接受 `schema_version = 1`；缺失 `schema_version`、`schema_version = 0` 或负数等畸形版本不再进入恢复合并流程，状态仍保持 `VERIFY`
- 实现口径：`LinReadsBackupRestorer` 在读取 ZIP `manifest.json` 并校验 `format=linreads-backup` 后，要求 manifest 显式携带 `schema_version` 且等于当前 schema；拒绝非法 schema 时不会写入 `reading_progress` / `bookmarks` / `text_annotations`
- 验证：RED `./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :core:database:testDebugUnitTest --tests "dev.readflow.core.database.LinReadsBackupExporterTest.restoreRejectsSchemaVersionsBeforeOneWithoutMutatingLocalData"` 先失败于 `schema_version=0` 被接受；RED `./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :core:database:testDebugUnitTest --tests "dev.readflow.core.database.LinReadsBackupExporterTest.restoreRejectsMissingSchemaVersionWithoutMutatingLocalData"` 先失败于缺失 `schema_version` 被默认成 v1；GREEN 后两条 schema tests 通过；`LinReadsBackupExporterTest` class、`:core:database:testDebugUnitTest`、phase2 `:core:database:testDebugUnitTest :features:settings:testDebugUnitTest --tests "dev.readflow.features.settings.SettingsViewModelTest" :features:settings:compileDebugKotlin :app:assembleDebug`、phase1 `:app:assembleDebug`、`git diff --check` 和 touched-file 空白扫描通过
- 状态：SRC-09 仍为 `VERIFY`；这是 JVM/schema contract 证据，不是真实手机/平板 SAF 恢复、实际导出文件手动 ZIP 核验、跨设备导出→恢复、大库/损坏 ZIP 或确认/预览 UX smoke

### EPUB paged text-to-image Compose root progress 退场清理回填
- 回填 PAGE-05：EPUB paged 活跃文本 `ComposeView` 在字号/行距重分页后映射为 image slice 时，不再残留 root page-progress 语义；状态仍保持 `PARTIAL`
- 实现口径：`retireEpubComposeTextPage()` 保留既有 text surface、selection、link、gesture 和 text semantics 清理，同时将 root `contentDescription`、`R.id.epub_compose_page_progress_description`、正文语义委托标记和 API 30+ `stateDescription` 一并清空；真正的图片页仍由新建 `ImageView.contentDescription` 暴露 alt/page 文案
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime retires active compose text page when repagination maps page to image slice" --rerun-tasks` 先失败于旧 Compose root 仍保留 progress 语义；GREEN 后同一测试通过；相邻 targeted tests、`EpubReflowEngineTest` class、`:render:epub:testDebugUnitTest --rerun-tasks` 通过；phase2 `:render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 组合通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 EPUB 混合图文分页视觉、TalkBack speech/action-mode、Compose selection action-mode、帧率/内存预算、paged link tapping、外链 chooser/browser 或真实 Calibre/SAF smoke 验收

### EPUB paged Compose root progress 退场清理回填
- 回填 PAGE-05：EPUB paged 文本页切到 scroll 模式后，旧 `ComposeView` root 不再残留 `R.id.epub_compose_page_progress_description`、正文语义委托标记或 API 30+ `stateDescription`，避免退场页面继续暴露 stale 页码/进度语义；状态仍保持 `PARTIAL`
- 实现口径：`clearEpubComposeTextPageForBookReset()` 在清理 text surface、selection、link 和 semantics tags 之前，额外清空 root `contentDescription`、page progress tag、`epub_compose_page_root_delegates_accessibility_to_text=false`，并在 API 30+ 清空 `stateDescription`；同一 book-reset 清理路径也覆盖 `close()` 和跨书 `openBook()` reset
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime clears compose page progress semantics when switching to scroll mode" --rerun-tasks` 先失败于切到 scroll 后旧 progress tag 未清；GREEN 后同一测试通过；相邻 lifecycle targeted tests、`EpubReflowEngineTest` class、`:render:epub:testDebugUnitTest --rerun-tasks` 通过；phase2 `:render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 组合通过；`git diff --check` 和 touched-file 空白扫描通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 TalkBack speech/action-mode、paged link tapping、外链 chooser/browser smoke、混合 EPUB 视觉、帧率/内存预算或真实 Calibre/SAF smoke 验收

### EPUB paged stale link activation guard 回填
- 回填 PAGE-05：EPUB paged 文本页退场后，旧 `ComposeView` 捕获的 link callback 或 `LinkAnnotation.Url` listener 即使被调用，也不会再打开外链或驱动旧页链接动作；状态仍保持 `PARTIAL`
- 实现口径：paged Compose link handler 增加 active-page/slice guard，只有当前 `ComposeView` 仍存在于 `activePagedTextPages` 且 slice 未过期时才转发到 `handleLinkClick()`；初次绑定、selection 更新/清理后重建的 annotated string，以及可见 `BasicText` 的 `LinkInteractionListener` 都使用同一 guarded handler
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime ignores stale compose link activation after switching to scroll mode" --rerun-tasks` 先失败于切到 scroll 后旧 link activation 仍启动外链 intent；GREEN 后同一测试通过；`EpubReflowEngineTest` class、`:render:epub:testDebugUnitTest --rerun-tasks` 通过；phase2 `:render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 组合通过；`git diff --check` 和 touched EPUB Kotlin 文件尾随空白扫描通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 paged link tapping、TalkBack link traversal/speech、外链 chooser/browser smoke、混合 EPUB 视觉、帧率/内存预算或真实 Calibre/SAF smoke 验收

### EPUB paged Compose LinkAnnotation listener 回填
- 回填 PAGE-05：EPUB paged 可见 Compose `BasicText` 的 `LinkAnnotation.Url` 现在带 `LinkInteractionListener`，Compose 原生 link activation 会复用既有 `handleLinkClick()`；状态仍保持 `PARTIAL`
- 实现口径：`epubComposeAnnotatedText()` 新增可选 `linkClickHandler`，为每个有效 page-local `EpubTextLink` 包装 `LinkInteractionListener.onClick()`；初次绑定、selection 更新、selection 清理以及可见 `BasicText` 重建的 annotated string 都传入同一条 link 处理路径
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime exposes compose link annotation listener for internal links" --rerun-tasks` 先失败于 listener 为空；GREEN 后同一测试通过；`EpubReflowEngineTest` class、`:render:epub:testDebugUnitTest --rerun-tasks` 通过；phase2 `:render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 组合通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 TalkBack link traversal/speech、paged link tapping、外链 chooser/browser smoke、混合 EPUB 视觉、帧率/内存预算或真实 Calibre/SAF smoke 验收

### EPUB paged Compose LinkAnnotation 回填
- 回填 PAGE-05：EPUB paged 可见 Compose `BasicText` 的 `AnnotatedString` 现在同时暴露 Compose 原生 `LinkAnnotation.Url` ranges；legacy `URL` string annotation 和 underline span 仍保留，状态仍保持 `PARTIAL`
- 实现口径：`epubComposeAnnotatedText()` 对每个有效 page-local `EpubTextLink` 继续写入 `addStringAnnotation("URL", href, ...)`，并额外调用 `AnnotatedString.Builder.addLink(LinkAnnotation.Url(...))`；`TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))` 与既有显式 underline span 保持一致
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime exposes compose link annotations for inline links" --rerun-tasks` 先失败于 link annotations 缺失；GREEN 后同一测试通过；`EpubReflowEngineTest` class、`:render:epub:testDebugUnitTest --rerun-tasks` 通过；phase2 `:render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 组合通过；`git diff --check` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 TalkBack link traversal/speech、paged link tapping、外链 chooser/browser smoke、混合 EPUB 视觉、帧率/内存预算或真实 Calibre/SAF smoke 验收

### EPUB paged Compose URL 注解回填
- 回填 PAGE-05：EPUB paged 可见 Compose `BasicText` 现在使用带 URL string annotation 的 `AnnotatedString`，page-local inline link 会写入 `URL` annotation 并加下划线样式，供 TalkBack/link traversal 后续真机验收前做代码契约锁定；状态仍保持 `PARTIAL`
- 实现口径：新增 `R.id.epub_compose_text_annotated_string` keyed tag；`epubComposeAnnotatedText()` 在保留既有高亮/selection highlight 背景样式的同时，为有效 `EpubTextLink` range 写入 `addStringAnnotation("URL", href, ...)` 和 `TextDecoration.Underline`；selection 更新、selection 清理、text page retire 和 book reset 会同步刷新或清空该 tag
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime exposes compose url annotations for inline links" --rerun-tasks` 先失败于缺少 annotated-string 暴露；GREEN 后同一测试通过；`EpubReflowEngineTest` class、`:render:epub:testDebugUnitTest --rerun-tasks` 通过；phase2 `:render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 组合通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 TalkBack link traversal/speech、paged link tapping、外链 chooser/browser smoke、混合 EPUB 视觉、帧率/内存预算或真实 Calibre/SAF smoke 验收

### EPUB paged 外链 intent selection 清理回填
- 回填 PAGE-05：EPUB paged `ComposeView` 文本页点击外链打开 `ACTION_VIEW` intent 前，现在会清空 engine selection 与活跃 page-local selection/highlight，避免跳到外部应用再返回 reader 时旧选区残留；状态仍保持 `PARTIAL`
- 实现口径：`handleLinkClick()` 的 external 分支先复用 `clearTextSelection()`，再调用既有 `openExternalLink()`；外链 intent 仍使用 `ACTION_VIEW`、原始 URL data 和 `FLAG_ACTIVITY_NEW_TASK`
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime clears compose selection when external link opens intent" --rerun-tasks` 先失败于外链 intent 后 selection 仍未清空；GREEN 后同一测试通过；`EpubReflowEngineTest` class、`:render:epub:testDebugUnitTest --rerun-tasks` 通过；phase2 `:render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 组合通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机外链 intent chooser/browser smoke、paged link tapping、TalkBack link traversal、混合 EPUB 视觉、帧率/内存预算或真实 Calibre/SAF smoke 验收

### EPUB paged 内链跳转 selection 清理回填
- 回填 PAGE-05：EPUB paged 内链跳转离开当前文本页时，现在会清空 engine selection 与活跃 `ComposeView` 的 page-local selection/highlight，避免点链接跳页后旧选区残留；状态仍保持 `PARTIAL`
- 实现口径：`goToInternalLink()` 在 paged 模式更新 locator/chapter state、请求 ViewPager 目标页之前复用 `clearTextSelection()`；外链 intent 与 scroll 模式链接路径保持既有语义
- 验证：RED/GREEN `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime clears compose selection when link navigation leaves active page" --rerun-tasks` 通过；`EpubReflowEngineTest` class、`:render:epub:testDebugUnitTest --rerun-tasks` 通过；phase2 `:render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 组合通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 paged link tapping、TalkBack link traversal、外链 intent、混合 EPUB 视觉、帧率/内存预算或真实 Calibre/SAF smoke 验收

### EPUB paged Compose link callback 回填
- 回填 PAGE-05：EPUB paged `ComposeView` 文本页现在暴露 `R.id.epub_compose_text_link_callback` 和 `R.id.epub_compose_text_link_tap_wired`，可见 Compose 文本层会用 `TextLayoutResult` tap hit-test 命中 page-local `EpubTextLink` 并复用既有链接处理；状态仍保持 `PARTIAL`
- 实现口径：`EpubComposeTextPage` 新增 link tap pointerInput；`bindEpubComposeTextPage()` 写入 link callback/tap-wired tags；text page retire / book reset 清空这两个 tags；`goToInternalLink()` 在 paged 模式不再依赖 `RecyclerView.post`，会直接更新 `currentLocator` / `chapterInfo` 并请求 ViewPager 目标页
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime exposes compose link callback that navigates internal links" --rerun-tasks` 先失败于 link callback 为空；GREEN 后同一测试通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 paged link tapping、TalkBack link traversal、外链 intent、视觉或性能验收

### EPUB paged Compose link ranges tag 回填
- 回填 PAGE-05：EPUB paged 文本页现在通过 `R.id.epub_compose_text_links` 在 `ComposeView` root 暴露当前 page-local inline link ranges，和既有 text/highlight/style/selection tags 对齐；状态仍保持 `PARTIAL`
- 实现口径：`:render:epub` 新增 keyed id `epub_compose_text_links`；`EpubReflowEngine.createPageView()` / `bindEpubComposeTextPage()` 写入 `slice.links`，`retireEpubComposeTextPage()` / `clearEpubComposeTextPageForBookReset()` 清空为 empty list，避免重分页、切模式或关书后暴露 stale link metadata
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime exposes compose text link ranges" --rerun-tasks` 先失败于缺少 link ranges tag；GREEN 后同一测试通过；`EpubReflowEngineTest` class 通过；`:render:epub:testDebugUnitTest --rerun-tasks` 通过；phase2 `:render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 组合通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 paged link tapping、TalkBack link traversal、外链 intent、视觉或性能验收

### EPUB paged page-local link metadata 回填
- 回填 PAGE-05：EPUB paged block/style-aware pagination 现在会把 cached `EpubDisplayBlock.Text.links` 透传到 `EpubPageSlice.links`，并裁剪为 page-local offsets，避免分页后丢失该页 inline link metadata；状态仍保持 `PARTIAL`
- 实现口径：`EpubPageSlice` 新增默认空 `links`；`epubMeasuredPagedLayout()` 增加默认 `linkProvider`，在 measured line slice、empty/cold fallback 和 full-text fallback 中写入 page-local links；`epubPagedLayoutWithBlocks()` 从 cached text blocks 提供 link ranges。跨页链接会按当前 page slice 可见范围裁剪
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubPageMappingTest.paged layout preserves page local link metadata" --rerun-tasks` 先失败于 `links` metadata 为空；GREEN 后同一测试通过；`EpubPageMappingTest` class 通过；`:render:epub:testDebugUnitTest --rerun-tasks` 通过；phase2 `:render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 组合通过；`git diff --check` 通过，未跟踪 touched Kotlin 文件的 no-index whitespace check 无空白输出
- 状态：PAGE-05 仍为 `PARTIAL`；这是 JVM/code-contract 证据，不是真机 EPUB paged link tapping、TalkBack link traversal、外链 intent、混合图文分页视觉、Compose selection action-mode、帧率/内存预算、真实 Calibre 或 SAF smoke 验收

### EPUB scroll goTo 分页回调边界回填
- 回填 PAGE-05：EPUB 在 scroll 模式下执行 `goTo(Section...)` 不再触发 `pageRequestCallback`，避免连续滚动阅读器被旧 paged host callback 误驱动翻页；状态仍保持 `PARTIAL`
- 实现口径：`EpubReflowEngine.goTo()` 只有在 `_pagingKind == PAGED` 时才请求 paged page index；internal-link post 路径也使用同一模式边界，scroll 模式只滚动/更新 locator/chapterInfo，不向 ViewPager 宿主发页请求
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.scroll runtime does not request paged navigation for section goTo" --rerun-tasks` 先失败于 scroll `goTo()` 触发了 page request；GREEN 后同一测试通过；相邻 paged navigation selection cleanup targeted 测试通过；`EpubReflowEngineTest` class 通过；`:render:epub:testDebugUnitTest --rerun-tasks` 通过；phase2 `:render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 组合通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 EPUB 混合图文分页视觉、Compose selection action-mode、TalkBack speech/action-mode、帧率/内存预算、真实 Calibre 或 SAF smoke 验收

### EPUB paged close 书籍级进度状态清理回填
- 回填 PAGE-05：`EpubReflowEngine.close()` 除清理页面/selection 状态外，现在会把 `pageCount` 清零、`currentLocator` 置为 `Unknown`，并将 `chapterInfo` 归零，避免关闭书后外部观察者继续看到 stale 页数、locator 或章节进度；状态仍保持 `PARTIAL`
- 实现口径：`close()` 与 `resetBookStateForOpen()` 的书籍级状态重置对齐；`_tableOfContents` 既有清空逻辑保留，同时补齐 `_currentLocator`、`_chapterInfo` 和 `_pageCount` 清理
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime clears book progress state when closing book" --rerun-tasks` 先失败于 `pageCount` 仍为 `2`；GREEN 后同一测试通过；相邻 close 生命周期测试 `paged runtime clears compose text page state when closing book` 通过；`EpubReflowEngineTest` class 通过；`:render:epub:testDebugUnitTest --rerun-tasks` 通过；phase2 `:render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 组合通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 EPUB 混合图文分页视觉、Compose selection action-mode、TalkBack speech/action-mode、帧率/内存预算、真实 Calibre 或 SAF smoke 验收

### EPUB paged active 图片页 progress 重绑回填
- 回填 PAGE-05：EPUB paged 图片页在字号/行距重分页后，如果同一 page index 仍是 image slice，旧 active `ImageView.contentDescription` 会刷新 alt/page progress 文案，避免总页数变化后继续暴露 stale 页码；状态仍保持 `PARTIAL`
- 实现口径：`EpubReflowEngine` 新增 active image page weak-map；`createImagePageView()` 记录 image page state，`setFontSize()` / `setLineSpacing()` 重建 `pagedSlices` 后会重绑活跃图片页。若新 slice 仍是图片，则更新 container tag、背景、图片文案与 bitmap；若同一 index 已变为 text slice，则清理旧图片页 `contentDescription` 与 drawable
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime updates active image page progress after page count changes" --rerun-tasks` 先失败于旧图片页仍保留旧总页数文案；GREEN 后同一测试通过；相邻三条 image page targeted tests 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过；`git diff --check` 通过，未跟踪测试文件的 `git diff --no-index --check` 无空白输出
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 EPUB 混合图文分页视觉、TalkBack speech/action-mode、Compose selection action-mode、帧率/内存预算、真实 Calibre 或 SAF smoke 验收

### EPUB paged mode switch 图片页状态退场回填
- 回填 PAGE-05：EPUB paged 图片页切换到 scroll 模式后，旧 paged `ImageView` 的 alt/page `contentDescription` 与已载入 drawable 都会被清理，避免旧 ViewPager 图片页在模式切换后继续暴露 stale 图片语义或视觉内容；状态仍保持 `PARTIAL`
- 实现口径：`setMode()` 在 reading mode 真实离开 paged、切到 continuous 时，除清理 active paged text `ComposeView` 外，也会遍历 active page containers 并递归清理其中 `ImageView.contentDescription` / drawable；同一 helper 也复用于 `openBook()` 书籍态 reset 和 `close()` 清理路径
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime clears image page accessibility state when switching to scroll mode" --rerun-tasks` 先失败于切换 scroll 后旧 `ImageView.contentDescription` 仍包含 `Cover art`；RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime clears image page drawable state when switching to scroll mode" --rerun-tasks` 先失败于旧 drawable 未清；GREEN 后两条 targeted 测试通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过；`git diff --check` 通过，未跟踪测试文件的 `git diff --no-index --check` 无空白输出
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 EPUB 混合图文分页视觉、TalkBack speech/action-mode、Compose selection action-mode、帧率/内存预算、真实 Calibre 或 SAF smoke 验收

### EPUB paged mode switch Compose 页状态退场回填
- 回填 PAGE-05：EPUB paged 文本页切换到 scroll 模式后，旧 paged `ComposeView` 的 text surface、selection callback、gesture wiring、selection tags 和 text semantics tags 会一并清理，避免旧 ViewPager 页在模式切换后继续暴露正文/选择/语义状态；状态仍保持 `PARTIAL`
- 实现口径：`setMode()` 在 reading mode 真实离开 paged、切到 continuous 时，先复用 `clearTextSelection()` 清掉 selection surface，再对所有 active paged text `ComposeView` 调用 `clearEpubComposeTextPageForBookReset()`，最后清空 active page tracking；旧 callback 晚到也无法写入 selection
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime clears compose text page state when switching to scroll mode" --rerun-tasks` 先失败于切换 scroll 后旧 text surface 仍可见；GREEN 后同一测试通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 EPUB 混合图文分页视觉、Compose selection action-mode、TalkBack speech/action-mode、帧率/内存预算、真实 Calibre 或 SAF smoke 验收

### EPUB paged mode switch 旧 Compose callback 失效回填
- 回填 PAGE-05：EPUB paged 文本页切换到 scroll 模式后，旧 paged `ComposeView` 捕获的 selection callback 即使晚到触发，也不能重新写入 engine selection 或 page-local selection/highlight；状态仍保持 `PARTIAL`
- 实现口径：`setMode()` 在 reading mode 真实离开 paged、切到 continuous 时，先复用 `clearTextSelection()` 清掉 selection surface，再清空 active paged text/page container tracking；旧 callback 因不再属于 active page state，会在 `updatePagedTextSelection()` 入口直接失效
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime ignores stale compose selection callback after switching to scroll mode" --rerun-tasks` 先失败于旧 callback 晚到后重新写入 selection；GREEN 后同一测试通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 EPUB 混合图文分页视觉、Compose selection action-mode、TalkBack speech/action-mode、帧率/内存预算、真实 Calibre 或 SAF smoke 验收

### EPUB paged mode switch selection 状态清理回填
- 回填 PAGE-05：EPUB paged 文本页已有 selection 时切换到 scroll 模式，现在会清理 engine selection 与旧 paged `ComposeView` 的 page-local selection/highlight，避免阅读模式切换后旧 ViewPager 页继续携带 stale selection；状态仍保持 `PARTIAL`
- 实现口径：`setMode()` 在真实 reading mode 变化时、更新 `_pagingKind` 前调用既有 `clearTextSelection()`，让 paged→scroll 与 scroll→paged 的 selection surface 都重新归零；未变化的重复 `setMode()` 不额外清理
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime clears compose selection when switching to scroll mode" --rerun-tasks` 先失败于切换 scroll 后旧 selection 仍未清空；GREEN 后同一测试通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 EPUB 混合图文分页视觉、Compose selection action-mode、TalkBack speech/action-mode、帧率/内存预算、真实 Calibre 或 SAF smoke 验收

### EPUB paged close 生命周期 Compose 页状态清理回填
- 回填 PAGE-05：`EpubReflowEngine.close()` 现在会清理已创建 paged `ComposeView` 的 text surface、selection tags、selection callback、gesture wiring 和 text semantics tags，避免 engine 关闭后外部仍持有的旧页继续暴露 stale selection/语义状态；状态仍保持 `PARTIAL`
- 实现口径：`close()` 在清空 active page weak maps 前复用书籍态清理路径，对所有 active paged text `ComposeView` 调用 `clearEpubComposeTextPageForBookReset()`；关闭后的旧 Compose selection callback 因 active page state 已移除，即使被调用也不会写入 `currentTextSelection`
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime clears compose text page state when closing book" --rerun-tasks` 先失败于 `close()` 后旧 text surface 仍可见；GREEN 后同一测试通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过；`git diff --check` 通过，未跟踪测试文件的 `git diff --no-index --check` 无空白输出
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 EPUB 混合图文分页视觉、Compose selection action-mode、TalkBack speech/action-mode、帧率/内存预算、真实 Calibre 或 SAF smoke 验收

### EPUB paged 跨书 openBook selection 状态清理回填
- 回填 PAGE-05：同一个 `EpubReflowEngine` 打开第二本 EPUB 时，现在会清理上一本文本 selection、活跃 paged `ComposeView` 的 page-local selection/highlight 和旧 Compose selection callback，避免旧书选区或晚到回调污染新书；状态仍保持 `PARTIAL`
- 实现口径：`openBook()` 入口调用 `resetBookStateForOpen()`，清掉旧 lazy cache、active page maps、Compose text surface/selection tags、engine selection、目录/page count/current locator 等书籍态；`updatePagedTextSelection()` 增加 active page guard，旧 `ComposeView` 持有的过期 callback 即使被调用，也不会写入新书的 `currentTextSelection`
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime clears compose selection when opening another book" --rerun-tasks` 先失败于打开第二本书后旧 selection 状态仍未清空；GREEN 后同一测试通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过；`git diff --check` 通过，未跟踪测试文件的 `git diff --no-index --check` 无空白输出
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 EPUB 混合图文分页视觉、Compose selection action-mode、TalkBack speech/action-mode、帧率/内存预算、真实 Calibre 或 SAF smoke 验收

### EPUB paged ViewPager 旧 page request callback 清理回填
- 回填 PAGE-05：`ViewPagerTransitionHost` 直接从一个 `PagedReaderEngine` 重新 `bind()` 到另一个 engine 时，现在会清掉旧 engine 的 page request callback，避免旧 EPUB/PDF 分页 engine 继续驱动新 ViewPager 翻页；状态仍保持 `PARTIAL`
- 实现口径：`bind()` 在取消旧 `pageCountJob` 后立即调用旧 `pagedEngine?.setPageRequestCallback(null)`，再绑定新的 engine/callback；`unbind()` 既有释放语义保持不变。测试 fake engine 现在保存 callback 并可触发旧 callback，覆盖不经 `unbind()` 的直接重绑路径
- 验证：RED `./gradlew --no-daemon --no-parallel :render:animate:testDebugUnitTest --tests "dev.readflow.render.animate.ViewPagerTransitionHostTest.paged host clears old page request callback when rebound" --rerun-tasks` 先失败于 `ViewPagerTransitionHostTest.kt:102`，旧 engine request 后 `pager.currentItem` 变为 `2`；GREEN 后同一测试通过；`./gradlew --no-daemon --no-parallel :render:animate:testDebugUnitTest --tests "dev.readflow.render.animate.ViewPagerTransitionHostTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:animate:testDebugUnitTest --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 EPUB 混合图文分页视觉、Compose selection action-mode、TalkBack speech/action-mode、帧率/内存预算、真实 Calibre 或 SAF smoke 验收

### EPUB paged ViewPager 宿主 unbind/rebind 生命周期回填
- 回填 PAGE-05：`ViewPagerTransitionHost.unbind()` 取消宿主协程后，同一个 host 再次 `bind()` 时现在会重建可用 scope，避免 pageCount collection 与 page request callback 停止工作；状态仍保持 `PARTIAL`
- 实现口径：`ViewPagerTransitionHost` 的 host scope 改为可重启；`bind()` 在取消旧 `pageCountJob` 后检测 scope 是否仍 active，若已因 `unbind()` 取消则重新创建，再启动新的 `PagedReaderEngine.pageCount` collection 和页面回调；`unbind()` 仍保留取消旧任务/清空 pager adapter 的释放语义
- 验证：RED `./gradlew --no-daemon --no-parallel :render:animate:testDebugUnitTest --tests "dev.readflow.render.animate.ViewPagerTransitionHostTest.paged host refreshes adapter after unbind and rebind" --rerun-tasks` 先失败于 rebind 后 refresh observer count 仍为 `0`；GREEN 后同一测试通过；`./gradlew --no-daemon --no-parallel :render:animate:testDebugUnitTest --tests "dev.readflow.render.animate.ViewPagerTransitionHostTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:animate:testDebugUnitTest --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 EPUB 混合图文分页视觉、Compose selection action-mode、TalkBack speech/action-mode、帧率/内存预算、真实 Calibre 或 SAF smoke 验收

### EPUB paged 宿主页数变化刷新回填
- 回填 PAGE-05：EPUB paged 重分页可能改变 `engine.pageCount` 与页类型，`ViewPagerTransitionHost` 现在会观察分页 engine 的页数变化并刷新 adapter，避免 ViewPager2 继续显示旧页或旧 text/image 绑定；状态仍保持 `PARTIAL`
- 实现口径：分页 bind 时为 `PagedReaderEngine.pageCount` 启动主线程 collection；每次 emission 调用 `PagedEngineAdapter.refreshPageCount()` / `notifyDataSetChanged()` 触发页面重绑，并在新 page count 小于当前页时把 `pager.currentItem` clamp 到有效范围；重新 bind/unbind 会取消旧 `pageCountJob`，page request callback 也避免在空 adapter 上做非法 `coerceIn`
- 验证：RED `./gradlew --no-daemon --no-parallel :render:animate:testDebugUnitTest --tests "dev.readflow.render.animate.ViewPagerTransitionHostTest.paged host refreshes adapter when engine page count changes" --rerun-tasks` 先失败于 refresh observer count 为 `0`；GREEN 后同一测试通过；`./gradlew --no-daemon --no-parallel :render:animate:testDebugUnitTest --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过；`git diff --check` 通过，未跟踪 `ViewPagerTransitionHost.kt` / `ViewPagerTransitionHostTest.kt` 的 `git diff --no-index --check` 无空白输出
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 EPUB 混合图文分页视觉、Compose selection action-mode、TalkBack speech/action-mode 或帧率/内存预算验收

### EPUB paged Compose 文本页重分页到图片页退场回填
- 回填 PAGE-05：EPUB paged active `ComposeView` 原本是文本页，但字号/行距重分页后同一 page index 映射为 image slice 时，会退出文本页/selection 状态，避免旧文本 surface、callback 和 selection 高亮残留；状态仍保持 `PARTIAL`
- 实现口径：`rebindActiveComposeTextPage()` 检测新 slice kind；若为 `EpubPageSliceKind.Image`，从 active text page weak-map 移除旧 `ComposeView`，清空 engine selection、page-local selection range/highlight、text surface、selection callback、gesture wiring 和 text semantics tags，并保留页码 progress state，等待分页宿主按 image slice 创建真实 `ImageView` 页面
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime retires active compose text page when repagination maps page to image slice" --rerun-tasks` 先失败于旧 `epub_compose_text_surface_visible` 仍为 `true`；GREEN 后同一测试通过；相邻重分页/跨页 selection targeted tests 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过；`git diff --check` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机图文分页视觉、Compose selection action-mode、TalkBack speech/action-mode 或帧率/内存预算验收

### EPUB paged Compose 跨页导航 selection 清理回填
- 回填 PAGE-05：EPUB paged Compose 文本页在 ViewPager/page navigation 离开当前页时会清理旧 selection，避免 engine `currentTextSelection`、page-local selection range 和 selection highlight 留在上一页；状态仍保持 `PARTIAL`
- 实现口径：`EpubReflowEngine.goTo()` 在 PAGED 模式记录当前 page index 与目标 page index，只有跨页导航时调用既有 `clearTextSelection()`，统一清空 engine selection 和所有活跃 `ComposeView` 的 selection range/highlight state；同页导航不额外清理
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime clears compose selection when page navigation leaves active page" --rerun-tasks` 先失败；GREEN 后同一测试通过；串行 `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime clears compose selection when page navigation leaves active page" --tests "dev.readflow.render.epub.EpubSelectionTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过；`git diff --check` 通过，未跟踪 touched Kotlin 文件的 `git diff --no-index --check` 无空白输出
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged Compose 拖选独立连接符 focus 清理回填
- 回填 PAGE-05：EPUB paged Compose 拖选 focus 落在独立 word connector（如 `Alpha - beta` 中的 `-`）时，drag helper 现在会清理连接符与边缘空白，避免先生成包含分隔符的 page-local range；状态仍保持 `PARTIAL`
- 实现口径：`epubComposeDragSelectionRange()` 在向左/向右扩展时复用既有 `epubTrimSelectionEdges()`，让 standalone connector 与周边空白在 helper 层就被排除；`epubComposeDragFocusRange()` 只把词内部连接符继续当作可扩展 focus，独立/边缘连接符返回 collapsed focus。额外补充 emoji modifier guard，确认 `👍🏽` 这类肤色 modifier focus 已由现有 text-element 路径覆盖
- 验证：`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest.compose drag selection keeps emoji modifier focus on text element boundary" --rerun-tasks` 通过，证明 modifier 行为已存在；RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest.compose drag selection ignores standalone word connector focus" --rerun-tasks` 先失败于实际 range 为 `0..6`；GREEN 后同一测试通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime exposes compose text gesture selection wiring" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过；`git diff --check` 通过，未跟踪 touched Kotlin 文件的 `git diff --no-index --check` 无空白输出
- 状态：PAGE-05 仍为 `PARTIAL`；这是 JVM/Robolectric/code-contract 证据，不是真机 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged Compose 拖选 emoji/text-element 边界回填
- 回填 PAGE-05：EPUB paged Compose 拖选 focus 落在 emoji/ZWJ 等可见 text element 内部时，会按完整 text element 扩展 selection range；状态仍保持 `PARTIAL`
- 实现口径：`EpubComposeInitialSelection` 携带原始 page text；`epubComposeDragSelectionRange()` 在判断向左/向右扩展前，先把 focus offset 规整到 code point / `BreakIterator` text element 边界，并对 emoji symbol / ZWJ sequence 复用 symbol sequence 扩展逻辑，避免拖到 `👩‍💻` 内部时产生半个 surrogate 或半个序列的 range
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest.compose drag selection keeps emoji focus on text element boundary" --rerun-tasks` 先失败；GREEN 后同一测试通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime exposes compose text gesture selection wiring" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过；`git diff --check` 通过，未跟踪 touched Kotlin 文件的 `git diff --no-index --check` 无空白输出
- 状态：PAGE-05 仍为 `PARTIAL`；这是 JVM/Robolectric/code-contract 证据，不是真机 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged Compose emoji/ZWJ 初始选区回填
- 回填 PAGE-05：EPUB paged Compose 长按初始选区现在会把 emoji symbol 与 ZWJ emoji 序列作为单个可见 text element 选中；状态仍保持 `PARTIAL`
- 实现口径：`epubComposeInitialSelectionRange()` 在非词字符路径中识别 symbol/emoji sequence，扩展 variation selector、肤色 modifier 与 zero-width-joiner 串，并要求 range 内包含 selectable symbol；standalone punctuation 仍返回 `null`，不改变独立连接符清理语义
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest.compose initial selection keeps emoji zwj sequence to one text element" --rerun-tasks` 先失败；GREEN 后同一测试通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 JVM/code-contract 证据，不是真机 emoji Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged Compose callback trim range 镜像回填
- 回填 PAGE-05：EPUB paged Compose selection callback 现在会把 Compose 可观察 selection range 与 selection highlight 同步到 shared EPUB selection 的 trimmed 有效范围；状态仍保持 `PARTIAL`
- 实现口径：新增 `epubNormalizedTextSelectionRange()` 作为 `epubTextSelection()` 的共同有效范围出口；`updatePagedTextSelection()` 用同一 trimmed paragraph range 回写 `R.id.epub_compose_text_selection_range` / `R.id.epub_compose_text_selection_highlight_range`，避免 raw callback range 覆盖 `- beta` 时 UI 高亮仍包含分隔符
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime mirrors trimmed compose callback selection range into compose state" --rerun-tasks` 先失败；GREEN 后同一测试通过；相邻 `EpubSelectionTest`、callback code point、callback combining mark、blank selection targeted 回归通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB selection 边缘空白与连接符清理回填
- 回填 PAGE-05：EPUB selection 生成 `ReaderTextSelection` 前会清理选区两端的空白和 standalone word connector，避免 Compose 拖选/callback 落在分隔符边缘时产生 `- beta` 这类带边缘噪声的 selectedText；状态仍保持 `PARTIAL`
- 实现口径：`epubTextSelection()` 在 code point/text-element 边界规整后，对 start/end 继续 trim `Character.isWhitespace` / `Character.isSpaceChar` 与已知 word joiner；内部连接符不动，locator start/end 跟随 trim 后的真实正文锚点
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest.selection trims edge whitespace and standalone word connectors" --rerun-tasks` 先失败；GREEN 后同一测试通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 JVM/code-contract 证据，不是真机 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged Compose Unicode hyphen 初始选区回填
- 回填 PAGE-05：EPUB paged Compose 初始选词现在支持词内 Unicode hyphen `\u2010` / non-breaking hyphen `\u2011`，`well\u2011being` 这类正文长按不再被切成半个词；状态仍保持 `PARTIAL`
- 实现口径：`epubComposeInitialSelectionRange()` 的 word joiner 判定把 `\u2010` / `\u2011` 纳入词内部连接符；en dash/em dash 等句间分隔符不纳入，既有独立连接符 trim 语义保持不变
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest.compose initial selection expands unicode hyphen word" --rerun-tasks` 先失败；GREEN 后同一测试通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 JVM/code-contract 证据，不是真机 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged Compose standalone connector punctuation 初始选区回填
- 回填 PAGE-05：EPUB paged Compose 初始选词现在不会把独立的 `'` / `\u2019` / `-` 分隔符当成正文选区；状态仍保持 `PARTIAL`
- 实现口径：`epubComposeInitialSelectionRange()` 扫描词边界后会 trim 词首/词尾连接符；连接符只有位于词内部时才保留，独立或位于边缘时返回 `null`，继续走 collapsed/clear selection 语义
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest.compose initial selection ignores standalone word connector punctuation" --rerun-tasks` 先失败；GREEN 后同一测试通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 JVM/code-contract 证据，不是真机 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged Compose typographic apostrophe 初始选区回填
- 回填 PAGE-05：EPUB paged Compose 初始选词现在支持常见排版右单引号 `\u2019`，`reader\u2019s` 这类正文长按不再被拆成 `reader` / `s`；状态仍保持 `PARTIAL`
- 实现口径：`epubComposeInitialSelectionRange()` 的 word code point 判定将 `\u2019` 纳入拉丁词连接符；既有 ASCII、CJK、combining mark、outside-text 和 drag selection 行为不变
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest.compose initial selection expands typographic apostrophe word" --rerun-tasks` 先失败；GREEN 后同一测试通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 JVM/code-contract 证据，不是真机 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged Compose 文本外长按折叠选区回填
- 回填 PAGE-05：EPUB paged Compose 长按 hit-test offset 位于文本范围外时不再误吸附首词/末词，而是走 collapsed selection 清理路径；状态仍保持 `PARTIAL`
- 实现口径：`epubComposeInitialSelectionRange()` 对空文本或 `offset !in 0 until text.length` 直接返回 `null`，由 `epubComposeInitialSelectionAt()` 把越界 offset clamp 成 `0..0` 或 `text.length..text.length`，且 `anchor=null`
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest.compose initial selection event reports collapsed range outside text" --rerun-tasks` 先失败于越界 offset 仍选中边缘词；GREEN 后同一测试通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 JVM/code-contract 证据，不是真机 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged Compose 拖拽选区保留初始词回填
- 回填 PAGE-05：EPUB paged Compose 长按拖选现在会保留最初长按命中的词，不会在向左拖动时丢掉初始词；状态仍保持 `PARTIAL`
- 实现口径：新增 `epubComposeDragSelectionRange()`，让拖拽 focus 在初始词左侧时返回 `focus..initialWordEnd`，在右侧时返回 `initialWordStart..focus`，focus 仍在初始词内时保留完整初始词；空白/collapsed 初始 selection 不生成拖选 range
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest.compose drag selection keeps initial word when dragging before it" --rerun-tasks` 先失败；GREEN 后同一测试通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest" --rerun-tasks` 通过；相邻 `EpubReflowEngineTest.paged runtime exposes compose text gesture selection wiring`、`paged runtime keeps compose callback selection on code point boundaries`、`paged runtime keeps compose callback selection on combining mark boundaries` 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 JVM/Robolectric/code-contract 证据，不是真机 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged Compose 初始 combining mark 选区回填
- 回填 PAGE-05：EPUB paged Compose 长按初始 selection 现在会保留 combining-mark text element，不会把 decomposed accent 选成半个可见字形；状态仍保持 `PARTIAL`
- 实现口径：`epubComposeInitialSelectionRange()` 先把 hit-test offset 扩到 `BreakIterator.getCharacterInstance(Locale.ROOT)` 识别的 text-element/grapheme，再基于 text-element range 向前/向后扫描词边界；命中 base letter 或 combining mark offset 都返回完整词选区
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest.compose initial selection keeps combining mark with word range" --rerun-tasks` 先失败于只选中 base letter / mark offset 无 range；GREEN 后同一测试通过；相邻 initial-selection / callback boundary targeted tests 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 JVM/code-contract 证据，不是真机 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged Compose combining mark 选区边界回填
- 回填 PAGE-05：EPUB paged Compose selection callback 现在会把 page-local start/end 从 code point 边界进一步规整到用户可见 text element 边界，避免 decomposed accent / combining mark 被选成半个可见字形；状态仍保持 `PARTIAL`
- 实现口径：`epubSelectionRangeOnCodePointBoundaries()` 先做 surrogate-safe code point 边界规整，再通过 `BreakIterator.getCharacterInstance(Locale.ROOT)` 扩到 text-element/grapheme 边界；`epubTextSelection()` 与 paged Compose callback 继续共用同一路径
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime keeps compose callback selection on combining mark boundaries" --rerun-tasks` 先失败于 callback 只写入 base letter range；GREEN 后同一测试通过；相邻 selection/callback targeted tests 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 JVM/Robolectric/code-contract 证据，不是真机 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged Compose callback code point 边界回填
- 回填 PAGE-05：EPUB paged Compose selection callback 现在会把 page-local start/end 规整到 Unicode code point 边界，避免拖选 focus 落在 CJK Extension-B surrogate pair 低代理项时生成半个 Unicode 字符；状态仍保持 `PARTIAL`
- 实现口径：新增 `epubSelectionRangeOnCodePointBoundaries()`，`epubTextSelection()` 与 paged Compose callback 都复用该边界规整；Compose selection range tag、selection highlight 和 engine `currentTextSelection` 统一使用规整后的 page-local range
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime keeps compose callback selection on code point boundaries" --rerun-tasks` 先失败于 callback 写入 `0..1` 半 surrogate range；GREEN 后同一测试通过；相邻 selection/callback targeted tests 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 JVM/Robolectric/code-contract 证据，不是真机 CJK Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged Compose CJK 起始选区回填
- 回填 PAGE-05：EPUB paged Compose 长按起始选区对中文/日文假名/韩文等无空格文本不再整段扩选，并且能覆盖 CJK Extension-B surrogate pair；状态仍保持 `PARTIAL`
- 实现口径：`epubComposeInitialSelectionRange()` 改为 code point aware；CJK/Hiragana/Katakana/Hangul 走单个 Unicode code point 初始 selection，ASCII/拉丁字母、数字、下划线、撇号和连字符仍按词边界扩展，空白 offset collapsed 清理路径不变
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest.compose initial selection keeps cjk long press to one character" --rerun-tasks` 先失败于 helper 返回整段 CJK range；RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest.compose initial selection keeps cjk surrogate pair to one code point" --rerun-tasks` 先失败于 surrogate pair 非完整 code point range；GREEN 后同一测试与相邻 initial selection targeted tests 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 JVM/code-contract 证据，不是真机中日韩文本 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged Compose 长按起始选区回填
- 回填 PAGE-05：EPUB paged Compose 文本层在长按开始时会先按词边界生成 page-local selection range，并立即走 Compose selection callback；状态仍保持 `PARTIAL`
- 实现口径：新增 `epubComposeInitialSelectionRange()`，把长按 hit-test offset 扩展到连续字母/数字/下划线/撇号/连字符区间；空白 offset 不扩展为词选区，但 `epubComposeInitialSelectionAt()` 会返回 collapsed range 并经 `EpubComposeTextPage.onDragStart` 写入 selection callback，复用空/blank selection 清理路径清空旧 engine selection / Compose selection range / selection highlight；`R.id.epub_compose_text_long_press_selection_wired` 暴露接线状态
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest.compose initial selection expands long press offset to word range" --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime exposes compose text gesture selection wiring" --rerun-tasks` 先失败于 helper 返回 `null` 和接线 tag 缺失；后续 RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest.compose initial selection event reports word range with anchor" --tests "dev.readflow.render.epub.EpubSelectionTest.compose initial selection event reports collapsed range for whitespace" --rerun-tasks` 先失败于 word range/anchor 断言；GREEN 后 targeted selection/gesture tests 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 JVM/Robolectric/code-contract 证据，不是真机 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged active Compose 进度语义回填
- 回填 PAGE-05：EPUB paged 文本页在行距/字号触发重分页后，会刷新活跃 `ComposeView` 的页码进度语义；状态仍保持 `PARTIAL`
- 实现口径：active page rebind 计算重建后的 page count，clamp active page index，并重新调用 `applyComposePageAccessibilityProgress()` 更新 `R.id.epub_compose_page_progress_description` 与 API 30+ `stateDescription`，避免 TalkBack/无障碍进度语义保留旧总页数
- 验证：RED/GREEN `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime updates compose page progress after line spacing rebuilds page count" --rerun-tasks`；相邻重分页 targeted tests 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged active Compose 重分页状态回填
- 回填 PAGE-05：EPUB paged 文本页在字号/行距触发重分页后，会把活跃 `ComposeView` 重新绑定到当前 page index 的新 `EpubPageSlice`；状态仍保持 `PARTIAL`
- 实现口径：`EpubPagedTextPageState` 记录 `pageIndex`；active page rebind 时若 `pagedSlices[pageIndex]` 已变化，会同步更新 weak-map state、根 `ComposeView.tag`、page text 和高亮；`setFontSize()` / `setLineSpacing()` 重建切片后清空 engine selection、Compose selection range 和 selection highlight，避免旧 page-local offset 留在新页面上
- 验证：RED/GREEN `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime rebinds active compose page to rebuilt line spacing slice" --rerun-tasks`；RED/GREEN `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime clears page local compose selection when line spacing rebuilds slices" --rerun-tasks`；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged selection state 移除 View bridge 回填
- 回填 PAGE-05：EPUB paged 文本页不再创建或暴露空 `SelectionAwareTextView` bridge；状态仍保持 `PARTIAL`
- 实现口径：`R.id.epub_selection_overlay_view` 保持 `null`；active page rebind、clear selection 和 selection highlight state 改由 `ComposeView` page state 维护，Compose selection callback 直接写入 engine `currentTextSelection`、Compose selection range 和 selection highlight tag；TXT/MD/连续 EPUB 的原生 `SelectionAwareTextView` 路径不在本轮变更范围
- 验证：RED/GREEN `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime updates compose selection without selection aware text view bridge" --rerun-tasks`；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel :render:txt:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

---

## 2026-06-23

### EPUB paged selection bridge 脱离 Compose tree 回填
- 回填 PAGE-05：EPUB paged 文本页不再把空 `SelectionAwareTextView` bridge 通过 Compose `AndroidView` 挂进 UI tree；状态仍保持 `PARTIAL`
- 实现口径：`EpubComposeTextPage` 只保留前景 `SelectionContainer` / `BasicText` 文本与 gesture path；空 `SelectionAwareTextView` bridge 继续作为未挂载对象保存在 `ComposeView` tag 与 active weak-map 中，用于复用现有 selection callback/state 更新路径
- 验证：RED/GREEN `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime keeps selection bridge outside compose tree while compose gesture text owns foreground" --rerun-tasks`；相邻回归 `paged runtime keeps transparent overlay out of native selection while compose callback selects text` 与 `paged runtime keeps transparent overlay text empty while compose surface owns content after rebind` 通过；`./gradlew --no-daemon --no-parallel :render:txt:testDebugUnitTest :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged transparent overlay 空文本桥回填
- 回填 PAGE-05：EPUB paged 透明 `SelectionAwareTextView` overlay 现在不再复制整页正文和高亮 spans；状态仍保持 `PARTIAL`
- 实现口径：paged 文本页创建和标注 rebind 后，透明 overlay 始终保持空文本；可见正文、高亮和 selection highlight 由 Compose `BasicText` / `AnnotatedString` surface 承担，selection 更新继续通过 Compose callback bridge 写入 engine `currentTextSelection` 与 Compose selection range tag
- 验证：RED/GREEN `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime keeps transparent overlay text empty while compose surface owns content after rebind" --rerun-tasks`；回归 `paged runtime keeps transparent overlay out of native selection while compose callback selects text` 通过；`./gradlew --no-daemon --no-parallel :render:txt:testDebugUnitTest :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机视觉、TalkBack speech/action-mode、Compose selection action-mode 或帧率/内存预算验收

### EPUB paged transparent overlay native selection 关闭回填
- 回填 PAGE-05：EPUB paged 透明 `SelectionAwareTextView` overlay 现在不再保持 native `TextView` selection；状态仍保持 `PARTIAL`
- 实现口径：`SelectionAwareTextView.nativeTextSelectionEnabled` 默认 `true`，保持普通 TXT/MD 等文本视图原生选择；EPUB paged overlay 在创建和 Compose rebind 时设为 `false`，同步保持 `isTextSelectable=false`，selection 更新继续通过 Compose callback bridge 写入 engine `currentTextSelection` 与 Compose selection range tag
- 验证：RED/GREEN `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime keeps transparent overlay out of native selection while compose callback selects text" --rerun-tasks`；普通 TXT 默认行为守护 `./gradlew --no-daemon --no-parallel :render:txt:testDebugUnitTest --tests "dev.readflow.render.txt.TxtVirtualPagerEngineTest.paragraph adapter keeps bound text selectable" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:txt:testDebugUnitTest :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 Compose selection action-mode、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged transparent overlay touch/focus bridge 回填
- 回填 PAGE-05：EPUB paged 透明 `SelectionAwareTextView` overlay 现在仅作为 selection 状态/兼容桥，不再作为触摸或焦点目标；状态仍保持 `PARTIAL`
- 实现口径：`SelectionAwareTextView.touchSelectionEnabled` 默认 `true`，保持普通 TXT/MD 等文本视图原有可点击、可长按、可聚焦选择行为；EPUB paged overlay 在创建和 Compose rebind 时设为 `false`，同步关闭 `isClickable` / `isLongClickable` / `isFocusable` / `isFocusableInTouchMode`
- 验证：RED/GREEN `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime keeps transparent overlay out of touch and focus after compose text rebind" --rerun-tasks`；普通 TXT 默认行为守护 `./gradlew --no-daemon --no-parallel :render:txt:testDebugUnitTest --tests "dev.readflow.render.txt.TxtVirtualPagerEngineTest.paragraph adapter keeps bound text selectable" --rerun-tasks` 通过；`./gradlew --no-daemon --no-parallel :render:txt:testDebugUnitTest :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric/code-contract 证据，不是真机 Compose selection、TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged Compose root accessibility progress 回填
- 回填 PAGE-05：EPUB paged 文本页根 `ComposeView` 不再用页码 `contentDescription` 抢占正文语义，页码改为独立 progress/state 契约；状态仍保持 `PARTIAL`
- 实现口径：文本页根清空 `contentDescription`，通过 `R.id.epub_compose_page_progress_description` 记录页码，并在 API 30+ 写入 `stateDescription`；`R.id.epub_compose_page_root_delegates_accessibility_to_text` 标记根节点把可读正文语义交给内部 Compose `BasicText`
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime returns compose view as text page root" --rerun-tasks` 先失败于根节点仍有 `contentDescription`；GREEN 后同一 targeted test 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric 语义边界契约，不是真机 TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged transparent overlay accessibility persistence 回填
- 回填 PAGE-05：EPUB paged 透明 `SelectionAwareTextView` overlay 现在在创建和 Compose rebind 后都会保持无障碍隐藏，避免标注/文本刷新后重新被 TalkBack 聚焦；状态仍保持 `PARTIAL`
- 实现口径：`SelectionAwareTextView` 新增默认关闭的 `hideFromAccessibility` 模式；EPUB paged overlay 启用该模式，让 `keepTextSelectable()` / `setText()` 后仍应用 `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS`
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime keeps transparent overlay accessibility hidden after compose text rebind" --rerun-tasks` 先失败于 rebind 后 overlay accessibility 从 `4` 变回 `1`；GREEN 后同一 targeted test 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric overlay accessibility 稳定性契约，不是真机 TalkBack speech/action-mode、视觉或帧率/内存预算验收

### EPUB paged Compose blank selection cleanup 回填
- 回填 PAGE-05：EPUB paged Compose/overlay 共用 selection 更新路径现在会在空白-only selection 时清空 engine selection、Compose selection range 和 Compose selection highlight，避免留下 stale 视觉选区；状态仍保持 `PARTIAL`
- 实现口径：`updatePagedTextSelection()` 先调用 `epubTextSelection(...)` 生成实际语义 selection，再以该结果决定是否写入 `R.id.epub_compose_text_selection_range` / `R.id.epub_compose_text_selection_highlight_range`；collapsed 或 whitespace-only 选择统一走清空路径
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime clears compose selection state for blank compose selection" --rerun-tasks` 先失败；校准测试文本后 GREEN 同一 targeted test 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Robolectric selection 状态契约，不是真机手势/action-mode、TalkBack speech 或帧率/内存预算验收

### EPUB paged Compose text semantics / overlay accessibility 回填
- 回填 PAGE-05：EPUB paged 可见 Compose `BasicText` 不再清空语义；透明 `SelectionAwareTextView` overlay 被标记为无障碍隐藏，避免 TalkBack 聚焦到不可见兼容层；状态仍保持 `PARTIAL`
- 实现口径：新增 `R.id.epub_compose_text_semantics_exposed` 与 `R.id.epub_selection_overlay_accessibility_hidden` keyed tags；`SelectionAwareTextView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS`，并移除 Compose 文本 modifier 末尾的 `clearAndSetSemantics {}`，让前景 Compose 文本承担可读语义
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime exposes compose text semantics instead of transparent overlay accessibility" --rerun-tasks` 先失败于语义/overlay accessibility tag 为空；GREEN 后同一 targeted test 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Compose 文本语义和透明 overlay 无障碍隐藏的代码契约，不是真机 TalkBack speech/action-mode 验收，也没有帧率/内存预算或完整图文分页验收

### EPUB paged Compose selection overlay z-order 回填
- 回填 PAGE-05：EPUB paged 文本页的透明 Android selection overlay 已移到 Compose 文本/gesture 层之后，避免 overlay 在代码层级上挡住 Compose 长按拖选接线；状态仍保持 `PARTIAL`
- 实现口径：新增 `R.id.epub_selection_overlay_behind_compose_text` keyed tag；`EpubComposeTextPage` 仍保留 `AndroidView(SelectionAwareTextView)` 作为兼容/状态桥，但在 `Box` 中先声明 AndroidView、再声明 `SelectionContainer { BasicText(...) }`，让可见 Compose 文本和 `pointerInput` gesture modifier 成为前景交互层
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime keeps selection overlay behind compose gesture text" --rerun-tasks` 先失败于 overlay-behind tag 为空；GREEN 后同一 targeted test 通过；`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest` 通过；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Compose gesture 前景层级的代码契约，不是真机已验证的纯 Compose selection/action-mode，也没有 TalkBack action-mode、帧率/内存预算或完整图文分页验收

### EPUB paged Compose gesture selection wiring 回填
- 回填 PAGE-05：EPUB paged Compose 文本层现在有基于 `TextLayoutResult` 的长按拖选 hit-test 接线；状态仍保持 `PARTIAL`
- 实现口径：`EpubComposeTextPage` 通过 `BasicText(onTextLayout)` 保存 `TextLayoutResult`，再用 `Modifier.pointerInput` / `detectDragGesturesAfterLongPress` 把长按拖选坐标映射为 page-local text offset，并调用上一片的 `R.id.epub_compose_text_selection_callback`；新增 `R.id.epub_compose_text_gesture_selection_wired` 暴露接线状态。透明 `AndroidView(SelectionAwareTextView)` overlay 仍保留，不把本轮记录成真实手势/action-mode 替代
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime exposes compose text gesture selection path" --rerun-tasks` 先失败于 gesture wiring tag 为空；命名 refactor 后 GREEN `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime exposes compose text gesture selection wiring" --rerun-tasks`；`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是 Compose hit-test/gesture wiring，不是真机已验证的纯 Compose selection，也没有 TalkBack action-mode、帧率/内存预算或完整图文分页验收

### EPUB paged Compose selection callback bridge 回填
- 回填 PAGE-05：EPUB paged `ComposeView` 现在通过 `R.id.epub_compose_text_selection_callback` 暴露 Compose-side selection 回调桥；状态仍保持 `PARTIAL`
- 实现口径：新增 `R.id.epub_compose_text_selection_callback`；overlay selection callback 与 Compose-side callback 复用同一段 `updatePagedTextSelection()`，同步 engine `currentTextSelection`、`R.id.epub_compose_text_selection_range`、`R.id.epub_compose_text_selection_highlight_range` 和 Compose selection highlight state，并归一化 page-local range；透明 `AndroidView(SelectionAwareTextView)` overlay 继续保留真实长按/action-mode 路径
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime exposes compose text selection callback bridge" --rerun-tasks` 先失败于 callback tag 为空；GREEN 后同一 targeted test、`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这是未来纯 Compose selection 手势的回调落点，不是真实手势/action-mode 迁移；仍缺真机/平板视觉、TalkBack action-mode、帧率/内存预算和完整图文分页验收

### EPUB paged Compose selection container 回填
- 回填 PAGE-05：EPUB paged 文本页现在把可见 Compose `BasicText` 包入 `SelectionContainer`，并通过 `ComposeView` keyed tag 暴露 Compose selection container 已启用状态；状态仍保持 `PARTIAL`
- 实现口径：新增 `R.id.epub_compose_text_selection_enabled`；`EpubComposeTextPage` 用 `SelectionContainer { BasicText(...) }` 承载可见文本，同时继续保留透明 `AndroidView(SelectionAwareTextView)` overlay，避免拆掉现有长按/action-mode 和标注保存路径
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime exposes compose text selection container state" --rerun-tasks` 先失败于 selection-enabled tag 为空；GREEN 后同一 targeted test、`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest`、`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；Compose 文本层已有 selection container 可测落点，但真实手势/action-mode 仍依赖透明 Android `SelectionAwareTextView` overlay，不是纯 Compose selection；仍缺真机/平板视觉、TalkBack action-mode、帧率/内存预算和完整图文分页验收

### EPUB paged Compose selection 高亮状态回填
- 回填 PAGE-05：EPUB paged 文本页现在会把透明 `SelectionAwareTextView` overlay 产生的 page-local selection range 同步为 Compose 可消费的高亮 range，并写入 `ComposeView` keyed tag；状态仍保持 `PARTIAL`
- 实现口径：新增 `R.id.epub_compose_text_selection_highlight_range` 与 `EpubComposeSelectionHighlightColor`；overlay selection callback 继续负责既有长按/action-mode 兼容，同时创建 `ReaderTextHighlightRange`、更新 Compose state，并让 `EpubComposeTextPage` 把该 selection highlight 追加到 Compose `AnnotatedString` 背景 ranges
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime exposes compose selection highlight range" --rerun-tasks` 先失败于 selection highlight tag 为空；GREEN 后同一 targeted test、`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest`、`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；selection 高亮状态已进入 Compose 渲染层，但手势与 action-mode 仍依赖透明 Android `SelectionAwareTextView` overlay，不是纯 Compose selection；仍缺真机/平板视觉、TalkBack action-mode、帧率/内存预算和完整图文分页验收

### EPUB paged Compose 文本样式/布局状态回填
- 回填 PAGE-05：EPUB paged 文本页现在会把 slice 的 `EpubPageTextStyle` 与 Compose 侧布局参数暴露到 `ComposeView` keyed tags，并把 blockquote/list/pre 等布局差异应用到可见 Compose `BasicText`；状态仍保持 `PARTIAL`
- 实现口径：新增 `EpubComposeTextLayout`，把基础 padding、indent、blockquote 额外缩进和 preformatted 横向滚动统一映射到 Compose 文本层；`ComposeView` 暴露 `R.id.epub_compose_text_style` / `R.id.epub_compose_text_layout`，`EpubComposeTextPage` 用同一 layout 驱动 padding 与 `horizontalScroll`
- 验证：先补最小 `EpubComposeTextLayout` 后，RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime exposes compose text style and layout state" --rerun-tasks` 失败于 style tag 为空；GREEN 后同一 targeted test、`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest`、`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；可见文本/高亮/样式布局已由 Compose 承担，但 selection 手势与 action-mode 仍依赖透明 Android `SelectionAwareTextView` overlay；仍缺真机/平板视觉、TalkBack action-mode、帧率/内存预算和完整图文分页验收

### EPUB paged Compose selection 状态回填
- 回填 PAGE-05：EPUB paged 文本页现在会把透明 `SelectionAwareTextView` overlay 产生的 page-local selection range 同步到 `ComposeView` keyed tag，作为后续纯 Compose selection 迁移的可测状态落点；状态仍保持 `PARTIAL`
- 实现口径：`ComposeView` 暴露 `R.id.epub_compose_text_selection_range` 与 `R.id.epub_selection_overlay_view`；overlay selection callback 仍负责现有长按选择兼容，同时写入 Compose 根节点的 selection range；`clearTextSelection()` 会同步清空活跃 paged Compose 页上的 selection range，避免 stale selection 被后续 Compose 层读取
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime mirrors overlay selection range into compose state" --rerun-tasks` 失败于 overlay view tag 为空；GREEN 后同一 targeted test 通过；RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime clears compose selection range with engine selection" --rerun-tasks` 失败于 stale selection range 未清理；GREEN 后两个 selection targeted tests、`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest`、`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；selection 手势与 action-mode 仍依赖透明 Android `SelectionAwareTextView` overlay，不是纯 Compose selection；仍缺真机/平板视觉、TalkBack action-mode、帧率/内存预算和完整图文分页验收

### EPUB paged Compose 可见文本与高亮回填
- 回填 PAGE-05：EPUB paged 文本页从透明 Compose 文本面推进到可见 Compose `BasicText` 渲染层，并把分页高亮 ranges 迁到 Compose `AnnotatedString` 背景样式；状态仍保持 `PARTIAL`
- 实现口径：`SelectionAwareTextView` 继续通过 `AndroidView` 作为透明选择覆盖层保留长按选择 callback；`ComposeView` keyed tags 暴露 page text、Compose 文本可见状态、Compose 高亮 ranges 和 selection overlay 可见状态；字号、行距、主题和标注刷新会重新绑定活跃分页页的 Compose 文本层
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime makes compose text visible while selection overlay stays non visual" --rerun-tasks` 失败于可见状态 tag 为空；RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime exposes compose text highlight ranges" --rerun-tasks` 失败于高亮 ranges tag 为空；GREEN 后两个 targeted tests、`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest`、`./gradlew --no-daemon --no-parallel -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；可见文本和高亮已由 Compose 承担，但 selection 仍是透明 Android `SelectionAwareTextView` 覆盖层，不是纯 Compose selection；仍缺真机/平板视觉、TalkBack action-mode、帧率/内存预算和完整图文分页验收

### EPUB paged Compose text surface 回填
- 回填 PAGE-05：EPUB paged 文本页在 `ComposeView` 根节点内新增透明 Compose `BasicText` 文本面，状态仍保持 `PARTIAL`
- 实现口径：`:render:epub` 增加 `compose.foundation`；`EpubComposeTextPage` 先放置透明且清空语义的 `BasicText`，再用 `AndroidView(SelectionAwareTextView)` 保留既有 selection/highlight path，避免双重显示或 TalkBack 重复朗读；`R.id.epub_compose_text_surface` keyed tag 暴露 page text 供 Robolectric 验证
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime exposes compose text surface state" --rerun-tasks` 失败于 tag 为空；GREEN 后同一 targeted test、`./gradlew :render:epub:testDebugUnitTest`、`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；Compose 文本面目前非交互且透明，真实可见文本、selection 和 highlight 仍由 Android `SelectionAwareTextView` 承担，也没有真机视觉、帧率/内存预算或完整图文分页验收

### EPUB paged 文本页 ComposeView 宿主回填
- 回填 PAGE-05：`:render:epub` 启用 Compose 编译，EPUB paged 文本页现在由 `ComposeView` 作为页面宿主承载，状态仍保持 `PARTIAL`
- 实现口径：`EpubReflowEngine.createPageView()` 对文本 slice 直接返回 `ComposeView` 根节点，根节点保留 page slice tag、页码 `contentDescription` 和页面背景；`setContent { AndroidView(...) }` 继续复用既有 `SelectionAwareTextView`，保留 selection/highlight callback；图片页仍走既有 `ImageView` 路径
- 验证：RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime renders text pages with compose view host" --rerun-tasks` 失败于缺少 `ComposeView`；追加 RED `./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime returns compose view as text page root" --rerun-tasks` 失败于根节点仍非 `ComposeView`；GREEN 后 root targeted test、`./gradlew :render:epub:testDebugUnitTest`、`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；文本内容仍由 Android `SelectionAwareTextView` 渲染，不是纯 Compose `Text` / selection，也没有真机视觉、帧率/内存预算或完整图文分页验收

### EPUB default runtime Compose TextMeasurer 回填
- 回填 PAGE-05：公开 `EpubReflowEngine(context)` 默认 paged measurement 现在直接使用 Compose `TextMeasurer.measure()` / `TextLayoutResult` line ranges，并继续把 page slice 标记为 `ComposeTextLayoutResult`，状态仍保持 `PARTIAL`
- 实现口径：默认 `currentPageLineMeasurer()` 构造 `EpubPageLineMeasurer.ComposeTextLayoutResult`；`currentComposeTextLayoutLines()` 用 Compose `TextMeasurer` 生成 layout，再把 `getLineStart()` / `getLineEnd(visibleEnd = true)` 转为 `EpubTextLayoutLineRange`；`TextStyle` 覆盖字号、行高、标题加粗/字号、Serif/Monospace。Robolectric 通过默认构造 `openBook()` → `setMode(PAGED)` → `createPageView(0)` 断言 tagged `EpubPageSlice.measurement == ComposeTextLayoutResult`
- 验证：`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime uses real compose text layout measurement by default" --rerun-tasks`、`./gradlew :render:epub:testDebugUnitTest`、`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这不是 per-page `ComposeView` 视觉分页，也没有真机视觉、帧率/内存预算或完整图文分页验收

### EPUB runtime Compose measurement 来源预接线回填
- 回填 PAGE-05：`EpubReflowEngine` 新增 internal `EpubPageLineMeasurer` 注入点，runtime `buildPagedSlices()` 会通过当前 measurer 把 `ComposeTextLayoutResult` measurement 传入 `epubPagedLayoutWithBlocks()`；Robolectric 覆盖 `openBook()` → `setMode(PAGED)` → `createPageView(0)` 路径，状态仍保持 `PARTIAL`
- 实现口径：公开 `EpubReflowEngine(context)` 构造保持不变；测试用 internal constructor 注入 `EpubPageLineMeasurer.ComposeTextLayoutResult`，并从 tagged `EpubPageSlice` 断言 measurement 仍为 `ComposeTextLayoutResult`。验证中定位到 Robolectric SDK main thread 上 `runBlocking` 与 `openBook()` 内 `Dispatchers.Main` 自锁，已改为 `runTest` + `Dispatchers.setMain`，并给 `:render:epub` 测试补 `libs.coroutines.test`
- 验证：`./gradlew --no-daemon --no-parallel :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubReflowEngineTest.paged runtime can use compose text layout measurement source" --rerun-tasks`、`./gradlew :render:epub:testDebugUnitTest`、`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这只是 runtime measurement 来源预接线和 Robolectric evidence，不是实际 `ComposeView` / 真实 `TextLayoutResult` 排版分页，也没有真机视觉、帧率/内存预算或完整图文分页验收

### EPUB 分页 block cold-cache fallback 来源保留回填
- 回填 PAGE-05：block/style-aware 分页路径在 cached text 为空、回退 indexed/viewport slicing 时，fallback page slice 现在也会保留调用方传入的 `ComposeTextLayoutResult` measurement 和 cached `EpubPageTextStyle`，状态仍保持 `PARTIAL`
- 实现口径：`epubMeasuredPagedLayout()` 的 empty-text fallback 不再丢弃当前 block 的 `textStyle` / `measurement`；即使 `textProvider` 返回空文本，冷缓存切片仍保持与调用方一致的 measurement 来源和样式元数据
- 验证：RED/GREEN `./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubPageMappingTest.block paged layout preserves compose measurement and style when cached text is empty"`、`./gradlew :render:epub:testDebugUnitTest`、`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这仍不是 runtime `ComposeView` 真分页，没有真机视觉、帧率/内存预算或完整图文分页验收

### EPUB 分页 block Compose measurement 来源保留回填
- 回填 PAGE-05：`epubPagedLayoutWithBlocks()` 新增 `measurement` 参数并传入 `epubMeasuredPagedLayout()`，图文/样式感知分页路径现在也可保留 `ComposeTextLayoutResult` 来源，状态仍保持 `PARTIAL`
- 实现口径：block-based paged layout 继续复用 cached text/image block 顺序、`EpubPageTextStyle` 样式元数据和 measured-line 分组逻辑；当调用方提供 `EpubPageMeasurement.ComposeTextLayoutResult` 时，生成的 text page slice 不再被降回默认 `StaticLayout` 来源
- 验证：RED `./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubPageMappingTest.block paged layout can preserve compose measurement source"` 先失败于 `No parameter with name 'measurement' found`；GREEN 后同一 targeted test、`./gradlew :render:epub:testDebugUnitTest`、`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这只是 block/style-aware 分页核心的 measurement 来源保留，不是 runtime `ComposeView` 真分页，也没有真机视觉、帧率/内存预算或完整图文分页验收

### EPUB 分页 Compose fallback 来源保留回填
- 回填 PAGE-05：Compose/TextLayoutResult 风格 visual line ranges 为空或被过滤为空时，fallback 完整文本页现在会保留 `ComposeTextLayoutResult` measurement 来源和对应 `EpubPageTextStyle`，状态仍保持 `PARTIAL`
- 实现口径：`epubMeasuredPagedLayout()` 的 empty-lines fallback 不再创建默认 `StaticLayout` slice，而是沿用当前段落的 `textStyle` 与调用方传入的 `measurement`，避免后续 runtime `TextLayoutResult` 接入时丢失 slice 来源
- 验证：RED `./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubPageMappingTest.compose measured pagination keeps measurement and style when line ranges are empty"` 先失败于 fallback slice 未保留 measurement/style；GREEN 后同一 targeted test、`./gradlew :render:epub:testDebugUnitTest`、`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这只是 TextLayoutResult-style 测量 fallback 的来源保留，不是 runtime `ComposeView` 页面渲染，也没有真机视觉、帧率/内存预算或完整图文分页证据

### EPUB 分页 Compose TextLayoutResult 线段入口回填
- 回填 PAGE-05：EPUB paged mode 的分页核心现在可以消费 Compose/TextLayoutResult 风格的 visual line ranges，并把 page slice 标记为 `ComposeTextLayoutResult` 来源，状态仍保持 `PARTIAL`
- 实现口径：新增 `EpubPageMeasurement` 与 `EpubTextLayoutLineRange`；`epubComposeMeasuredPagedLayout()` 复用既有 measured-line 分页分组逻辑，把 TextLayoutResult line ranges 转为 `EpubPageSlice`，为后续 runtime `ComposeView` / `TextLayoutResult` 真分页接入提供本地可测入口
- 验证：RED `./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubPageMappingTest.compose text layout result line ranges are preserved as compose measured page slices"` 先失败于缺少 Compose/TextLayoutResult 分页入口；GREEN 后同一 targeted test、`./gradlew :render:epub:testDebugUnitTest`、`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这只是可消费 TextLayoutResult line ranges 的分页核心入口，不是 runtime `ComposeView` 页面渲染，也没有真机视觉、帧率/内存预算或完整图文分页证据

### EPUB 分页图片页 locator 回填
- 回填 PAGE-05：在 cached 图片块独立 page slice 基础上，EPUB paged mode 的图片页现在有独立 Section locator 锚点，状态仍保持 `PARTIAL`
- 实现口径：图片 page slice 锚到关联段落末尾；`epubLocatorForPageSlice()` 对图片页回写该锚点；`epubPageIndexFromLocator()` 优先匹配零宽图片页锚点，避免从图片页恢复/导航时落回前一个文本页
- 验证：RED `./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubPageMappingTest.image page slice maps to a distinct section offset after preceding text"` 先失败于图片页与相邻文本页共享 locator；GREEN 后同一 targeted test、`./gradlew :render:epub:testDebugUnitTest`、`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这只是图片页 locator 锚点修正，不是 `ComposeView` / `TextLayoutResult` 真分页，也没有真机视觉、帧率/内存预算或完整图文分页证据

### EPUB 分页样式感知测量回填
- 回填 PAGE-05：在 cached 文本块样式透传基础上，EPUB paged mode 的 StaticLayout/measured-line 分页测量现在会使用对应文本块样式，状态仍保持 `PARTIAL`
- 实现口径：`epubMeasuredPagedLayout()` / `epubPagedLayoutWithBlocks()` 把 `EpubPageTextStyle` 传给 line breaker；`EpubReflowEngine.buildPagedSlices()` 在 runtime StaticLayout 测量时按 heading 字号/粗体与 pre/table 等宽字体构造 `TextPaint`，避免测量仍按普通正文样式估算
- 验证：RED `./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubPageMappingTest.paged layout measures cached text blocks with their style metadata"` 先失败于 line breaker 只收到默认样式；GREEN 后同一 targeted test、`./gradlew :render:epub:testDebugUnitTest`、`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这只是样式感知 TextView/StaticLayout 页切片，不是 `ComposeView` / `TextLayoutResult` 真分页，也没有真机视觉、帧率/内存预算或完整图文分页证据

### EPUB 分页文本块样式回填
- 回填 PAGE-05：在 StaticLayout/measured-line 与 cached 图片页切片基础上，EPUB paged mode 现在会把 cached 文本块的标题、pre/table、list/blockquote 和缩进元数据透传到 text page slice，状态仍保持 `PARTIAL`
- 实现口径：`EpubPageSlice` 增加 `EpubPageTextStyle`；`epubPagedLayoutWithBlocks()` 为 cached `EpubDisplayBlock.Text` 生成的 page slices 写入 heading/kind/indent；`EpubReflowEngine.createPageView()` 对 paged `SelectionAwareTextView` 复用标题加粗/字号、等宽字体、缩进和 blockquote padding，字号/行距/主题刷新时也从 slice tag 读取同一 style
- 验证：RED `./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubPageMappingTest.paged layout preserves cached text block style metadata"` 先失败于默认样式未透传；GREEN 后同一 targeted test、`./gradlew :render:epub:testDebugUnitTest`、`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug` 通过
- 状态：PAGE-05 仍为 `PARTIAL`；这只是 cached text block style metadata 与 TextView 样式复用，不是 `ComposeView` / `TextLayoutResult` 真分页，也没有真机视觉、帧率/内存预算或完整图文分页证据

### EPUB 分页图片页切片回填
- 回填 PAGE-05：在 StaticLayout/measured-line 文本切片基础上，EPUB paged mode 现在可把已缓存 lazy spine 内的图片块插入独立 page slice，状态仍保持 `PARTIAL`
- 实现口径：`EpubPageSlice` 增加 `EpubPageSliceKind.Image`；`epubPagedLayoutWithBlocks()` 先保留 measured text pages，再按 cached display block 顺序插入图片页；`EpubLazyBook.cachedBlocks()` 只读已缓存 spine，不为分页加载冷 spine；`EpubReflowEngine.createPageView()` 对图片 slice 渲染 `ImageView`，复用 `decodeEpubImage()` 和 alt text/page contentDescription
- 验证：`./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubPageMappingTest.paged layout inserts cached image blocks as standalone slices"`；`./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubLazyBookTest.cached block lookup does not load cold spines for paged image slices"`；`./gradlew :render:epub:testDebugUnitTest`；`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug`
- 状态：PAGE-05 仍为 `PARTIAL`；这只是 cached image block standalone page slice，不是 `ComposeView` / `TextLayoutResult` 真分页，也没有标题/完整图文排版、真机视觉、帧率/内存预算证据

### EPUB 分页 StaticLayout 测量回填
- 回填 PAGE-05：在既有 viewport-derived `EpubPageSlice` 基础上，runtime paged slice builder 现在会在有缓存正文时使用 Android `StaticLayout` 测量 visual line ranges，再按 viewport-derived `linesPerPage` 合并为 page slices，状态仍保持 `PARTIAL`
- 实现口径：`EpubReflowEngine.buildPagedSlices()` 调用 `epubStaticLayoutPagedLayout()`；`epubMeasuredPagedLayout()` 提供可注入 line breaker 的测试入口，验证测量行范围按每页行数分组；空文本或冷 lazy spine 仍回退到 indexed/viewport slicing
- 验证：`./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubPageMappingTest.static layout paged layout follows measured visual line breaks"`；`./gradlew :render:epub:testDebugUnitTest`；`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug`
- 状态：PAGE-05 仍为 `PARTIAL`；当前不是 `ComposeView` / `TextLayoutResult` 真分页，也没有真机视觉、帧率/内存预算和图片/标题感知分页证据

### EPUB 分页 lazy 词边界回填
- 回填 PAGE-05：在既有 viewport-derived `EpubPageSlice` 段内字符切片基础上，runtime paged slice builder 现在可使用已缓存 lazy spine 正文做词/空白边界切分，状态仍保持 `PARTIAL`
- 实现口径：`EpubLazyBook.cachedParagraphAt()` 只读取已缓存 spine，不触发冷 spine 加载；`EpubReflowEngine.buildPagedSlices()` 用该缓存正文作为 `epubViewportPagedLayout()` 的 `textProvider`，因此已 warm 的 spine 可启用边界偏好，冷 spine 仍按索引字符范围切分
- 验证：RED `./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubLazyBookTest.cached paragraph lookup does not load cold spines for paged boundaries"` 先失败于缺少 `cachedParagraphAt`；GREEN 后 `./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubLazyBookTest.cached paragraph lookup does not load cold spines for paged boundaries"`、`./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubLazyBookTest" --tests "dev.readflow.render.epub.EpubPageMappingTest"`、`./gradlew :render:epub:testDebugUnitTest`、`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:animate:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug`
- 状态：PAGE-05 仍为 `PARTIAL`；当前仍是启发式 TextView 字符切片，不是 `ComposeView` / `TextLayoutResult` 真分页，也没有真机视觉、帧率/内存预算和图片/标题感知分页证据

### MD locator 渲染 offset 回填
- 回填 MD-01：补强 Markdown 源码 offset 与 Markwon rendered text offset 的本地证据，状态仍保持 `VERIFY`
- 实现口径：`MarkdownDocument` 提供 rendered-to-source / source-to-rendered 轻量映射；`MarkdownEngine` 的滚动 locator、goTo、文字选择和高亮范围使用同一映射，避免 `#`、`**bold**`、`[link](url)` 等源码语法被渲染剥离后锚点漂移
- 验证：`MarkdownDocumentTest.selection maps rendered markdown offsets back to source anchors` 和 `annotation source anchors map to rendered markdown highlight ranges` 覆盖 rendered `bold and link` 到源码 `**bold**` / `[link](...)` 的选择与高亮投影；`./gradlew :render:md:testDebugUnitTest --tests "dev.readflow.render.md.MarkdownDocumentTest"`；`./gradlew :render:md:testDebugUnitTest`；`./gradlew -Preadflow.phase=2 :render:md:testDebugUnitTest :render:md:compileDebugKotlin :features:reader:testDebugUnitTest :app:assembleDebug`
- 状态：`MD-01` 保持 `VERIFY`；本次不替代真实设备/平板 MD 保存恢复、TOC 跳转、长文滚动、复杂 Markdown inline/table/link 选择和布局变化恢复 smoke

### PDF outline named destinations 回填
- 回填 PDF-02：轻量 `PdfOutlineParser` 在常见 `/Outlines` 解析基础上，新增 catalog `/Names /Dests` name tree 与旧式 catalog `/Dests` dictionary named destination 解析，状态仍保持 `VERIFY`
- 实现口径：`destinationRef()` 现在能处理 direct `/Dest [...]`、GoTo `/D [...]`、named `/Dest (chapter-two)` 和 GoTo named `/D /chapter-one`；name tree 的 `/Names [...]` 会解析 literal string / PDF name 并映射到 destination array 的 page ref；旧式 `/Dests` dictionary 支持 direct destination array 与 indirect destination object wrapping `/D [page /Fit]`
- 验证：`PdfOutlineParserTest.resolves outline named destinations through catalog dest name tree` 覆盖 literal-string 与 PDF-name 两种 name-tree named destination；`PdfOutlineParserTest.resolves outline named destinations through catalog dest dictionary` 覆盖旧式 dictionary direct array 与 indirect `/D [...]` object，并断言目录项落到预期 page index；dictionary RED targeted run 先失败于 entries 为空，随后 targeted/class/all PDF tests 与 phase2 `:render:pdf:testDebugUnitTest :render:pdf:compileDebugKotlin :features:reader:testDebugUnitTest :app:assembleDebug` 通过
- 状态：`PDF-02` 保持 `VERIFY`；本次不替代真实 outline PDF corpus、物理手机/平板 TOC 跳转 smoke、xref/object streams、大 PDF scan cap 和更广 named destination 形态验收

### TXT 大语料/混合编码回归回填
- 回填 TXT-01/02/03/04：补强 TXT FileChannel block indexing、charset-aware ByteOffset 和 mixed-encoding regression 的 JVM/local corpus 证据，状态仍保持 `VERIFY`
- 实现口径：`TxtDocumentTest.large utf8 corpus keeps block ranges locator search and cache round trip stable` 覆盖大于 `3 * 64 KiB` 的 UTF-8 语料、48 段 paragraph、跨 block ranges、`indexForOffset()`、search `ByteOffset` 字符起点，以及 engine-state cache round-trip 后 ranges/charset 不漂移
- 编码边界：`normalizes gb18030 four byte offsets to character starts` 覆盖 GB18030 四字节字符内部 offset 回退；`mixed encoding corpus keeps search and selection byte locators on character starts` 覆盖 UTF-8/GBK/Shift_JIS search 与 selection ByteOffset 字符起点
- 验证：`./gradlew :render:txt:testDebugUnitTest --tests "dev.readflow.render.txt.TxtDocumentTest" --rerun-tasks`；`./gradlew :render:txt:testDebugUnitTest`；`./gradlew -Preadflow.phase=2 :render:txt:testDebugUnitTest :features:reader:testDebugUnitTest :app:assembleDebug`
- 状态：`TXT-01` / `TXT-02` / `TXT-03` / `TXT-04` 保持 `VERIFY`；本次不替代真实手机/平板导入 TXT、PSS/首开/滚动、恢复、搜索/选择 UI smoke 和性能验收

### EPUB lazy/LRU 与 parser hardening 语料回填
- 回填 EPUB-07/08：补强 lazy parsing/LRU 与 parser hardening 的 JVM/corpus 证据，状态仍保持 `VERIFY`
- 实现口径：`EpubLazyBookTest` 覆盖 `prefetchAroundParagraph()` 预热当前/相邻 spine 且不超过 cache limit，以及 `close()` 清理 cache/load counters；`EpubCorpusSmokeTest.validateLazyBookIndex()` 在 5 本公开 EPUB 上校验 lazy/full paragraph、spine、display-block、fixed-layout、char-offset parity、采样正文加载、block index range 和 cache warm/close
- hardening 口径：`EpubParserHardeningTest` 覆盖 XHTML entity/DOCTYPE rejection、lazy oversized spine skip、zip entry-count cap、corrupt ZIP fallback 和 safe zip path rejection；`epubParserGuard()` 捕获 `Exception`，避免 corrupt ZIP 的 `ZipException` 越过空书 fallback
- 验证：`./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubLazyBookTest" --tests "dev.readflow.render.epub.EpubParserHardeningTest" --tests "dev.readflow.render.epub.EpubCorpusSmokeTest" -Dreadflow.epubCorpusDir=/tmp/readflow-epub-corpus --rerun-tasks`；`./gradlew :render:epub:testDebugUnitTest`；`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug`
- 状态：`EPUB-07` / `EPUB-08` 保持 `VERIFY`；本次不替代真实大 EPUB PSS/首开/滚动性能、恶意 corpus/fuzz、encrypted ZIP 和真机/平板内存压力验收

### EPUB 图片/链接/降级矩阵语料回填
- 回填 EPUB-04/05/06：`EpubCorpusSmokeTest` 从“报告覆盖指标”增强为真实语料级验收断言，覆盖包内图片、链接和降级矩阵
- 实现口径：图片校验 safe zip-root href、package-local zip entry、encoded-size budget、alt text 和 display-block image count；链接校验 range、linked text、external 分类、display-block link count 和 internal target 命中；降级矩阵校验 style span 范围、list indent、table/list/blockquote/pre、fixed-layout fallback，以及 lazy/full `isFixedLayout` 与 `spinePaths` parity
- 语料结果：5 本 `/tmp/readflow-epub-corpus` 公开 EPUB 通过，报告覆盖 `packageLocalImages=21`、`altImages=21`、`links=1208`、`targetedInternalLinks=1048`、`styles=787`、`tables=6`、`lists=1092`、`blockquotes=2`、`pre=2`、`idpf-cole-voyage.epub fixed=true`
- 验证：`./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubCorpusSmokeTest" -Dreadflow.epubCorpusDir=/tmp/readflow-epub-corpus --rerun-tasks`；`./gradlew :render:epub:testDebugUnitTest`；`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug`
- 状态：`EPUB-04` / `EPUB-05` / `EPUB-06` 保持 `VERIFY`；本次补强 JVM corpus evidence，不替代真机/平板视觉、TalkBack link traversal、外链 intent、pre 横向滚动和 paged-mode image-aware pagination 验收

### EPUB 语料与 fragment TOC 回填
- 回填 EPUB-01/02/03：真实 EPUB 语料验证现在覆盖字符级 `totalProgression`、typed ReaderItem locators、parsed TOC Section locators 和 fragment/id anchor 精度
- 实现口径：`EpubReaderItem`/`EpubDisplayBlock` 携带 `fragmentIds`；`EpubParser` 构建 `EpubBook.fragmentTargetIndexes` 与 `EpubLazyBook.fragmentTargetIndexes`；`buildEpubToc(..., fragmentTargets)` 通过 `epubResolveHref()` 解析 nav/ncx href；runtime internal link 通过 `epubInternalLinkTargetKey()` 保留 `#fragment`
- 验证：`./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubParserTocTest" --tests "dev.readflow.render.epub.EpubLinkTargetsTest"`；`./gradlew :render:epub:testDebugUnitTest`；`./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubCorpusSmokeTest" -Dreadflow.epubCorpusDir=/tmp/readflow-epub-corpus --rerun-tasks`；`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug`
- 状态：`EPUB-01` / `EPUB-02` / `EPUB-03` 从 `VERIFY` 推进到 `DONE`；PAGE-05 仍为 `PARTIAL`，不把语料/TOC 收口误记为真分页完成

### EPUB 分页切片回填
- 回填 PAGE-05：EPUB paged mode 从 paragraph-per-page 推进到 viewport-derived `EpubPageSlice` 段内字符切片，ViewPager2 paged host 可按切片页恢复与导航
- 实现口径：`EpubPageMapping` 新增 `EpubPageMetrics` / `epubViewportPagedLayout`，按视口宽高、padding、平均字宽和 line height 估算 page size；有实际文本时可优先靠近词/空白边界切分。`EpubReflowEngine` paged 模式使用 slice count 作为页数，渲染 page substring，转换 slice-local 高亮/文字选择 offset，并在字号/行距变化时重建切片
- lazy 边界 follow-up：runtime paged slice builder 已改用 `EpubLazyBook.cachedParagraphAt()` 读取已缓存 lazy spine 正文作为 `textProvider`，因此已 warm 的 spine 可启用词/空白边界切分；冷 spine 仍不为分页强制加载，继续按索引字符范围切分
- 验证：`./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubPageMappingTest"`；`./gradlew :render:epub:compileDebugKotlin`；`./gradlew :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubPageMappingTest" :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin`；`./gradlew -Preadflow.phase=2 :render:epub:testDebugUnitTest :render:animate:testDebugUnitTest :render:api:compileDebugKotlin :render:animate:compileDebugKotlin :render:epub:compileDebugKotlin :features:reader:compileDebugKotlin :app:assembleDebug`
- 状态：PAGE-05 仍为 `PARTIAL`；当前是 viewport-derived TextView 字符切片，不是 `ComposeView` / `TextLayoutResult` 真分页，也没有真机视觉、帧率/内存预算和图片/标题感知分页证据

### MuPDF license ADR 与 optional DOCX/CBZ 裁决
- 回填 OPT-01/02/03：新增 `docs/audit/adr-mupdf-license-2026-06-23.md`，并同步 `docs/android-architecture-v4.md` §5.7/§12.4/§12.5
- 官方来源核验：MuPDF/Artifex 为 AGPL 或商业授权；无法满足 AGPL 时需商业许可；当前 MuPDF Core / standard PyMuPDF 输入列表包含 CBZ，但 DOCX 出现在 PyMuPDF Pro / conversion-layer 文档，不再把 “MuPDF Core 直接支持 DOCX” 当作已确认事实
- 裁决：base APK 保持 MuPDF-free；当前纯阅读回填不启用 AGPL MuPDF-linked binary；未来任何 MuPDF 路线必须先满足完整 AGPL 合规或商业授权，并通过 feature flag / dynamic delivery / ABI split 隔离 native binary 成本
- 状态：`OPT-01` 从 `TODO` 推进到 `DONE`；`OPT-02` / `OPT-03` 从 `TODO` 推进到 `DEFERRED`；当前未完成项回填总表已无 TODO，只剩 VERIFY/PARTIAL 设备和真实场景验收

### LinReads Backup 恢复回填
- 回填 SRC-09：Settings 新增 `恢复备份`，通过 Android SAF `OpenDocument` 选择 LinReads Backup ZIP 文件
- 实现口径：`LinReadsBackupRestorer` 读取 ZIP 内 `manifest.json`，校验 `format=linreads-backup` 与 `schema_version = 1`；只恢复 `reading_progress`、`bookmarks`、`text_annotations`，不触碰 `books` 表，避免覆盖本地书库和下载资产
- 合并策略：按 `updatedAt` 做 LWW；缺失行或备份较新的行 upsert，本地较新的行跳过，bookmark/annotation tombstone 会随备份保留
- 接线：新增 `LinReadsBackupRestoreStore`，phase2 Koin 注入真实 restorer；Settings ViewModel 显示恢复中、成功计数和失败文案，成功说明本地书库不会被覆盖
- 验证：`./gradlew -Preadflow.phase=2 :core:database:testDebugUnitTest --tests "dev.readflow.core.database.LinReadsBackupExporterTest.restoresBackupWithoutOverwritingNewerLocalReadingData"` 通过；最终 `./gradlew -Preadflow.phase=2 :features:reader:testDebugUnitTest --tests "dev.readflow.features.reader.ReaderSavedStateHandleTest" :core:database:testDebugUnitTest --tests "dev.readflow.core.database.LinReadsBackupExporterTest" :features:settings:testDebugUnitTest --tests "dev.readflow.features.settings.SettingsViewModelTest" :features:settings:compileDebugKotlin :app:assembleDebug` 和 `./gradlew -Preadflow.phase=1 :app:assembleDebug` 通过
- 状态：SRC-09 从 `TODO` 推进到 `VERIFY`；当前仍是 JVM ZIP round-trip + ViewModel fake InputStream + build 证据，缺真实手机/平板 SAF 恢复、实际导出文件手动核验、跨设备导出→恢复 round-trip、大库/损坏 ZIP 和确认/预览 UX smoke

### LinReads Backup 导出回填
- 回填 SRC-08：Settings 新增 `导出备份`，通过 Android SAF `CreateDocument("application/zip")` 让用户选择 ZIP 写入位置
- 实现口径：`LinReadsBackupExporter` 导出 Room 中全部 `reading_progress`、`bookmarks`、`text_annotations`，包含 tombstone 记录；ZIP 内写入 `manifest.json`，字段包括 `format=linreads-backup`、`schema_version=1`、三类数据和空 `assets` 扩展位，便于后续 SRC-09 恢复和 optional sidecar assets 接入
- 接线：新增 `LinReadsBackupExportStore`，phase2 Koin 注入真实 exporter；Settings ViewModel 显示导出中、成功计数和失败文案
- 验证：RED `./gradlew -Preadflow.phase=2 :core:database:testDebugUnitTest --tests "dev.readflow.core.database.LinReadsBackupExporterTest"` 先失败于非 ZIP manifest；GREEN 后同一测试通过；最终 `./gradlew -Preadflow.phase=2 :core:database:testDebugUnitTest --tests "dev.readflow.core.database.LinReadsBackupExporterTest" :features:reader:testDebugUnitTest --tests "dev.readflow.features.reader.ReaderSavedStateHandleTest" :features:settings:testDebugUnitTest --tests "dev.readflow.features.settings.SettingsViewModelTest" :features:settings:compileDebugKotlin :app:assembleDebug` 和 `./gradlew -Preadflow.phase=1 :app:assembleDebug` 通过
- 状态：SRC-08 从 `TODO` 推进到 `VERIFY`；当前仍是 JVM ZIP/manifest + ViewModel fake OutputStream + build 证据，缺真实手机/平板 SAF 导出、手动 ZIP 核验、大数据量/异常写入、optional assets 和 SRC-09 恢复验收

---

## 2026-06-22

### 真实同步后端延期与 no-op 状态回填
- 回填 STATE-05：当前纯阅读回填明确延期真实远程同步后端，不在本轮选择/实现 KorroSync、KoSync 或 WebDAV
- 实现口径：离线优先本地保存继续可用；Settings 接入 `SyncBackend` 状态，`NoOpSyncBackend` / unavailable backend 显示 `远程同步未启用`，说明进度、书签和标注只保存在本机
- 验证：RED `./gradlew -Preadflow.phase=2 :features:settings:testDebugUnitTest --tests "dev.readflow.features.settings.SettingsViewModelTest.noOpSyncBackendIsShownAsLocalOnlyInsteadOfSynced"` 先失败于 no-op 被显示为 enabled；GREEN 后同一测试通过；最终 `./gradlew -Preadflow.phase=2 :features:settings:testDebugUnitTest :features:settings:compileDebugKotlin :app:assembleDebug`
- 状态：STATE-05 从 `TODO` 推进到 `DEFERRED`；后续跨设备同步里程碑再在 KorroSync/KoSync/WebDAV 中选型并做双设备冲突 smoke

### Calibre 离线模式视图回填
- 回填 SRC-07：书库新增 `全部` / `离线可读` 筛选，用户可在 Calibre/LAN 不可用时只看本地可打开资产
- 实现口径：本地导入书只要 `localUri != null` 即视为离线可读；`calibre-*` 远程书必须同时满足 `DownloadStatus.DOWNLOADED` / `localUri != null`；remote-only 书仍留在 `全部`，不进离线视图
- Bundle 行为：离线筛选下只保留离线可读成员，空 Bundle 不显示；离线筛选为空时展示空状态并提供 `查看全部`
- 验证：`./gradlew -Preadflow.phase=2 :features:library:testDebugUnitTest --tests "dev.readflow.features.library.LibraryViewModelTest" :features:library:compileDebugKotlin :app:assembleDebug`
- 状态：SRC-07 从 `TODO` 推进到 `VERIFY`；当前仍是 JVM ViewModel + Compose compile + phase2 build 证据，缺真实 Calibre Content Server + 真机/平板 source unavailable、离线打开、remote-only 排除、TalkBack 和平板 UI smoke

### Calibre 手动下载/移除下载回填
- 回填 SRC-06：Calibre 搜索 sheet 继续作为手动下载入口；已下载 `calibre-*` 书籍在书架单书菜单暴露 `移除下载`
- 实现口径：`DownloadedBookCache.removeDownloadedAsset()` 只处理远程下载书，删除 app-private `file://` 资产，保留书架 row，并把 `DownloadStatus` 清回 `NOT_DOWNLOADED` / `localUri=NULL`；本地导入书不受影响
- 接线：`LibraryRepository.removeDownloadedAsset()` 委托 cache，`LibraryViewModel.removeDownloadedAsset()` 报告结果，`BookGrid` 仅对 downloaded Calibre 书展示菜单项，`LibraryScreen` 传入回调
- 验证：RED/GREEN `./gradlew -Preadflow.phase=2 :core:database:testDebugUnitTest --tests "dev.readflow.core.database.DownloadedBookCacheTest"`；RED/GREEN `./gradlew -Preadflow.phase=2 :features:library:testDebugUnitTest --tests "dev.readflow.features.library.LibraryViewModelTest"`；最终 `./gradlew -Preadflow.phase=2 :core:database:testDebugUnitTest --tests "dev.readflow.core.database.DownloadedBookCacheTest" :features:library:testDebugUnitTest --tests "dev.readflow.features.library.LibraryViewModelTest" :core:ui:compileDebugKotlin :features:library:compileDebugKotlin :app:assembleDebug`
- 状态：SRC-06 从 `TODO` 推进到 `VERIFY`；当前仍是 JVM fake DAO / temp file + Compose compile + phase2 build 证据，缺真实 Calibre Content Server + 真机/平板下载后移除、实际文件删除、断网状态、重新下载和大文件进度/失败态 smoke

### Calibre 离线缓存 LRU 回填
- 回填 SRC-05：远程下载书现在有可解释的 LRU 缓存上限，默认保留最近 5 本 `calibre-*` 且 `DownloadStatus.DOWNLOADED` / `localUri != null` 的书
- 实现口径：`DownloadedBookCachePlanner` 按 `lastReadAt` 保留最近阅读项，未读视为最旧，当前刚下载/更新的 protected book 不参与本轮淘汰；`DownloadedBookCache` 删除被淘汰的 app-private `file://` 资产，并把书架 row 清为 `DownloadStatus.NOT_DOWNLOADED` / `localUri=NULL`
- 接线：`LibraryRepository.upsertBook()` / `upsertAll()` 在远程下载书 upsert 后触发 trim，本地导入书不参与远程缓存淘汰
- 修复：`DownloadedBookCacheTest` 使用 `kotlinx.coroutines.test.runTest`，补 `:core:database` 的 `testImplementation(libs.coroutines.test)`
- 验证：首次 `./gradlew -Preadflow.phase=2 :core:database:testDebugUnitTest --tests "dev.readflow.core.database.DownloadedBookCachePlannerTest" --tests "dev.readflow.core.database.DownloadedBookCacheTest" :core:database:compileDebugKotlin :features:library:testDebugUnitTest --tests "dev.readflow.features.library.LibraryViewModelTest" :app:assembleDebug` 因缺 `libs.coroutines.test` 失败；补依赖后同一命令通过
- 状态：SRC-05 从 `TODO` 推进到 `VERIFY`；当前是 JVM fake DAO / temp file + phase2 build 证据，仍缺真实 Calibre Content Server + 真机/平板下载 6+ 本远程书后的文件删除、书架状态、离线打开和容量策略说明 smoke

### Reader 高亮/标注 AVD 回填
- 回填 READ-05：补录 TXT reader 长按选区后保存高亮、持久化到 Room、视觉高亮、标注面板列表和列表项跳转的 AVD 证据
- 验证：`./gradlew -Preadflow.phase=2 :features:reader:testDebugUnitTest --tests "dev.readflow.features.reader.ReaderAnnotationStateTest" :render:txt:testDebugUnitTest --tests "dev.readflow.render.txt.TxtDocumentTest.annotation byte anchors map to paragraph highlight ranges" :render:md:testDebugUnitTest --tests "dev.readflow.render.md.MarkdownDocumentTest.annotation anchors map to highlight ranges" :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubAnnotationsTest" :render:api:compileDebugKotlin :features:reader:compileDebugKotlin :render:txt:compileDebugKotlin :render:md:compileDebugKotlin :render:epub:compileDebugKotlin :app:assembleDebug`
- 运行时证据：phase2 debug APK 安装到 `emulator-5554`；证据保存在 `/tmp/readflow-read05-annotation-smoke`
- 结果：打开 TXT `content://media/external/file/40` / `readflow-perf-1mb`，长按正文显示 `笔记` / `高亮` / `保存`；点击 `高亮` 后浮层关闭，`after-highlight-fresh.png` 显示 line `000000` 起始词 `Readflow` 带浅黄色高亮
- 持久化：Room `text_annotations` 写入 active row：`anchorType=text-selection-range`、`selectedText=Readflow`、`note=NULL`、`color=1728045186`、`isDeleted=0`、`deviceId=85187ce7-80c5-4d21-9a73-029e95af0c02`
- 标注面板：`annotations-panel.xml/png` 暴露 `标注` 列表项 `Readflow`；点击列表项关闭面板并回到 line `000000` 锚点；logcat selection-failure/crash/ANR/recycled-bitmap grep 为空
- 状态：READ-05 保持 `VERIFY`；当前只是 AVD synthetic long-press/tap + TXT highlighter 证据，note 保存 AVD 坐标输入未稳定完成，仍缺物理手机/平板、TalkBack/action-mode、MD/EPUB、备份/同步导出验收

### Reader 文字选择长按回填
- 回填 READ-04：补录 TXT reader 长按文字选择后显示选区操作浮层的 AVD 证据
- 修复：`SelectionAwareTextView` fallback 选区已上报后，忽略紧随其后的 collapsed native selection callback，避免 Reader UI 选区状态被清空
- 验证：RED/GREEN `./gradlew -Preadflow.phase=2 :render:txt:testDebugUnitTest --tests "dev.readflow.render.txt.TxtVirtualPagerEngineTest.fallback text selection is not cleared by a collapsed native selection callback"`；组合验证 `./gradlew -Preadflow.phase=2 :render:api:compileDebugKotlin :render:txt:testDebugUnitTest --tests "dev.readflow.render.txt.TxtVirtualPagerEngineTest.paragraph adapter keeps bound text selectable" --tests "dev.readflow.render.txt.TxtVirtualPagerEngineTest.fallback text selection is not cleared by a collapsed native selection callback" --tests "dev.readflow.render.txt.TxtDocumentTest.selection maps paragraph character range to byte offset anchors" :render:md:testDebugUnitTest --tests "dev.readflow.render.md.MarkdownDocumentTest.selection maps source offsets to section anchors and selected text" :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSelectionTest.selection maps paragraph character range to section anchors" :features:reader:testDebugUnitTest --tests "dev.readflow.features.reader.ReaderTextSelectionStateTest" :features:reader:compileDebugKotlin :render:txt:compileDebugKotlin :render:md:compileDebugKotlin :render:epub:compileDebugKotlin :app:assembleDebug`
- 运行时证据：phase2 debug APK 安装到 `emulator-5554`；证据保存在 `/tmp/readflow-read04-selection-smoke`
- 结果：打开 TXT `content://media/external/file/40` / `readflow-perf-1mb`，baseline XML 可见 line `000000`-`000007`；在正文 `(520,330)` 长按后 `longpress.xml/png` 暴露 `笔记`、`取消`、`高亮`、`保存`
- 日志：logcat grep 未发现 `TextView does not support text selection`、`Selection cancelled`、`FATAL EXCEPTION`、`ANR in dev.readflow` 或 recycled-bitmap crash
- 状态：READ-04 保持 `VERIFY`；当前只是 AVD synthetic long-press + XML/screenshot 证据，仍缺物理手机/平板、TalkBack/action-mode、MD offset 精度和 EPUB link coexistence 验证

### Reader 书签 add/list/jump/delete 回填
- 回填 READ-03：补录 Reader 当前位置添加书签、书签面板列表、列表项跳转、删除/tombstone 的 AVD 证据
- 验证：`./gradlew -Preadflow.phase=2 :features:reader:testDebugUnitTest --tests "dev.readflow.features.reader.ReaderBookmarkStateTest" :features:reader:compileDebugKotlin :app:assembleDebug`
- 运行时证据：phase2 debug APK 安装到 `emulator-5554`；证据保存在 `/tmp/readflow-read03-bookmark-smoke`
- 结果：打开 `content://media/external/file/40` / `readflow-perf-1mb`，通过搜索到达 line `000799`-`000807`；点击 top-bar `添加书签` 后 UI 变为 `移除书签`，书签面板暴露 `书签 10%` 与 `删除书签 10%`
- 跳转：滚动离开到 line `000810`-`000818` 后，点击书签列表项回到 line `000799`-`000807`
- 持久化：Room `bookmarks` 先写入 active row：`LocatorStrategy.ByteOffset offset=104000 length=129`、`totalProgression=0.0991817489266396`、`isDeleted=0`、`deviceId=c147edff-a014-41d4-a359-7d7460a42c8f`；删除后同 row 更新为 `isDeleted=1` 且 `updatedAt` 更新；crash/ANR/recycled-bitmap grep 为空
- 状态：READ-03 保持 `VERIFY`；当前只是 AVD synthetic tap/input + XML/screenshot 证据，仍缺物理手机/平板、TalkBack focus/label traversal 和真实同步后端 push/pull 验证

### Reader 搜索 UI/TXT 跳转回填
- 回填 READ-01/READ-02：补录 Reader 搜索入口、搜索面板、结果列表和 TXT 搜索结果跳转的 AVD 证据
- 验证：`./gradlew -Preadflow.phase=2 :features:reader:testDebugUnitTest --tests "dev.readflow.features.reader.ReaderSearchStateTest" :render:txt:testDebugUnitTest --tests "dev.readflow.render.txt.TxtDocumentTest.search returns byte-offset locators in document order" :render:md:testDebugUnitTest --tests "dev.readflow.render.md.MarkdownDocumentTest.search returns section locators at matched character offsets" :render:epub:testDebugUnitTest --tests "dev.readflow.render.epub.EpubSearchTest.search maps matches to section locators with spine character offsets" :app:assembleDebug`
- 运行时证据：phase2 debug APK 安装到 `emulator-5554`；证据保存在 `/tmp/readflow-read-search-smoke`
- 结果：打开 `content://media/external/file/40` / `readflow-perf-1mb`，baseline 为 line `000000`-`000007`；搜索面板暴露 `搜索`、`关键词`、`执行搜索`、`清空搜索`；输入 `000800` 后出现 `结果 1 · 10%`；点击结果后正文跳到 line `000799`-`000807`
- 持久化：Room `reading_progress` 写入 `LocatorStrategy.ByteOffset offset=104000 length=129`、`totalProgression=0.0991817489266396`，与目标行位置一致；crash/ANR/recycled-bitmap grep 为空
- 状态：READ-01/READ-02 保持 `VERIFY`；当前只是 AVD/TXT UI smoke，仍缺物理手机/平板、TalkBack focus/labels、MD/EPUB UI 搜索跳转、大书搜索延迟和 PDF unsupported UI smoke

### Reader replica deviceId 稳定性回填
- 回填 STATE-04：补录当前 AVD 上 `device_id` 首次生成、进度写入、保留数据重装和模拟器重启后的稳定性证据
- 验证：`./gradlew -Preadflow.phase=2 :core:prefs:compileDebugKotlin :features:reader:testDebugUnitTest :app:assembleDebug`
- 运行时证据：phase2 debug APK 安装到 `emulator-5554`；证据保存在 `/tmp/readflow-state04-deviceid-smoke`
- 结果：`pm clear dev.readflow` 后打开 `content://media/external/file/40` / `readflow-perf-1mb` 并滚动，DataStore `files/datastore/readflow_settings.preferences_pb` 生成 `device_id = a9bfdc2c-9f1a-46af-a94b-e46aaea40fd4`；Room `reading_progress.deviceId` 同为该 UUID，locator 为 `ByteOffset offset=520 length=129`，`totalProgression=0.000495908781886101`
- 稳定性：`adb install -r` 保留数据重装 + cold relaunch 后 UUID 不变；AVD reboot、等待 `sys.boot_completed=1`、cold relaunch 后 UUID 与进度 row 仍不变；crash grep 为空
- 状态：STATE-04 保持 `VERIFY`；当前仍是 AVD-only 证据，缺物理手机/平板首次安装、应用升级和系统重启后的真实设备稳定性 smoke

### Reader 状态恢复/engine-state 回填 + TXT 恢复修复
- 回填 STATE-01/02/03：进程恢复验证时发现并修复 TXT 硬换行长文滚动后 locator 仍为 0，以及 `goTo()` 早于 `createView()` 时重开未滚到恢复位置的问题
- 修复：`TxtDocument` 会把超过 4KiB 的硬换行 range 按行切分；`TxtVirtualPagerEngine.createView()` 会按已恢复的 `currentParagraphIndex()` 初始化新 `RecyclerView` 的滚动位置
- 验证：`./gradlew :render:txt:testDebugUnitTest --tests "dev.readflow.render.txt.TxtDocumentTest.splits long hard-wrapped txt ranges on line boundaries for scroll progress"`；`./gradlew :render:txt:testDebugUnitTest --tests "dev.readflow.render.txt.TxtVirtualPagerEngineTest.createView scrolls to locator restored before view exists"`；`./gradlew -Preadflow.phase=2 :features:reader:testDebugUnitTest --tests "dev.readflow.features.reader.ReaderSavedStateHandleTest" :app:testDebugUnitTest --tests "dev.readflow.engine.AndroidEngineStateStoreTest" :render:txt:testDebugUnitTest :app:assembleDebug`
- 运行时证据：phase2 debug APK 安装到 `emulator-5554`；证据保存在 `/tmp/readflow-state-recovery-smoke-final`；打开 `content://media/external/file/40` / `readflow-perf-1mb`，滚动后 Room 写入 `ByteOffset offset=520 length=129 totalProgression=0.000495908781886101`，engine-state cache `129110 bytes`
- 结果：HOME + `am kill dev.readflow` 后进程消失；launcher cold start 回到书架，从书架重开同一本恢复到 line `000003/000004` 附近；crash/ANR/recycled-bitmap grep 为空
- 状态：STATE-01/02/03 保持 `VERIFY`；当前只是 AVD 证据，且没有证明 launcher 自动恢复 reader route，仍缺物理手机/平板系统级 process-death/task restore smoke；EPUB/PDF/MD 非空 engine cache 也尚未验证

### Reader 捏合缩放/字号预览回填
- 回填 UX-02：TXT/EPUB/MD 字号 pinch 预览与 PDF transient matrix zoom 的代码路径已存在，补录当前设备可获得的 AVD 证据
- 验证：`./gradlew :features:reader:testDebugUnitTest --tests "dev.readflow.features.reader.ReaderFontScaleTest" --tests "dev.readflow.features.reader.ReaderZoomScaleTest" :features:reader:compileDebugKotlin :render:pdf:compileDebugKotlin :render:txt:compileDebugKotlin :render:epub:compileDebugKotlin :render:md:compileDebugKotlin :app:assembleDebug -Preadflow.phase=2`
- 运行时证据：phase2 debug APK 安装到 `emulator-5554`；证据保存在 `/tmp/readflow-ux02-pinch-smoke`
- 结果：`adb shell input` help 仅提供单指 `tap`/`swipe`/`motionevent`，无法可靠合成真实双指 pinch；PDF reader 暴露 `阅读内容，捏合缩放页面` 和 `第 1 页，共 50 页`，TXT reader 暴露 `阅读内容，捏合调整字号`，TXT 排版面板暴露 `16sp`、`行距`、`1.75x`、`滚动`、`分页`
- 状态：UX-02 保持 `VERIFY`；仍缺物理手机/平板双指 pinch、PDF 实际矩阵缩放视觉验收和多格式视觉调校

### Reader 键盘/D-pad 导航回填
- 回填 UX-05/PAGE-02/PAGE-03：AVD smoke 先复现 `DPAD_RIGHT`/`DPAD_LEFT` 在 PDF reader 中不翻页的问题，PageDown/PageUp/Space 已可翻页
- 根因：D-pad 左右被 Compose/focus 导航层提前处理，仅靠 `ReaderTapContainer.dispatchKeyEvent()` 的 AndroidView 子树拦截不够
- 修复：新增 `ReaderTapZone.readerTapZoneForKey()` 纯映射；Reader 容器继续处理 Android view path；`ReaderScreen` 给 reader 增加 Compose `FocusRequester`/`focusable()` 和 `onPreviewKeyEvent` 到 native `KeyEvent` 的桥接，并在 reader 面板打开时放行键盘输入
- 验证：`./gradlew :features:reader:testDebugUnitTest --tests "dev.readflow.features.reader.ReaderTapZoneTest" :features:reader:compileDebugKotlin :app:assembleDebug -Preadflow.phase=2`；`./gradlew :features:reader:testDebugUnitTest :features:reader:compileDebugKotlin :render:animate:testDebugUnitTest :app:assembleDebug -Preadflow.phase=2`
- 运行时证据：phase2 debug APK 安装到 `emulator-5554`，打开 PDF `content://media/external/file/38`；`/tmp/readflow-key-transition-smoke-afterfocus` 记录 baseline page 1、`DPAD_RIGHT` page 2、`DPAD_LEFT` page 1、`PAGE_DOWN` page 2、`PAGE_UP` page 1、`SPACE` page 2、`DPAD_CENTER` chrome labels；crash grep 为空
- 状态：UX-05 仍为 `PARTIAL`，PAGE-02/PAGE-03 仍为 `VERIFY`；当前只有 AVD/adb keyevent 证据，缺物理硬件键盘/D-pad、TalkBack action traversal、真机/平板视觉动效和帧率测量

### Phase A 性能预算补测 + PDF bitmap 崩溃修复
- A-02 性能 gate 补录 OTA 预算语料测量：`app-ota.apk` = 9,537,702 bytes（<25MB），无 MuPDF `.so`；AVD final evidence 保存在 `/tmp/readflow-a02-budget-final`
- 覆盖 1MB TXT、1MB EPUB、5MB EPUB、10MB/50 页 PDF：ACTION_VIEW 首开/导入链路、PSS/Java heap、稳定滚动/翻页 gfxinfo、权限弹窗和 crash grep 均已记录
- 测量中复现 PDF 横向翻页崩溃：`Canvas: trying to use a recycled bitmap`；根因是缓存回收 bitmap 时仍有 `ImageView` drawable 绑定到同一 bitmap
- 修复：新增 `PdfBitmapAttachmentRegistry`，在 `PdfRendererEngine` / `PdfPageAdapter` 回收缓存 bitmap 前清空仍绑定该 bitmap 的 `ImageView` drawable，避免 View 绘制已回收 bitmap
- 验证：`./gradlew :render:pdf:testDebugUnitTest --tests "dev.readflow.render.pdf.PdfBitmapAttachmentRegistryTest" --tests "dev.readflow.render.pdf.PdfPageBitmapCacheTest" :render:pdf:compileDebugKotlin`；`./gradlew -Preadflow.phase=2 :app:assembleDebug`；`./gradlew -Preadflow.phase=2 :app:assembleOta`
- 保持 A-02 为 `PARTIAL`：当前仍是 AVD synthetic corpus，不是真机/平板/真实书；TXT 首开代理值 780ms 超 v4lite `<500ms`，PDF p90 65ms 和 jank 64.37% 仍需调优

## 2026-06-21

### OTA 更新 UX 打磨
- 设置页下载进度条（LinearProgressIndicator + 百分比），替代静默通知
- 下载中按钮禁用 + 取消下载功能
- 下载完成后自动拉起系统安装器
- 检查已缓存的下载 URL，避免安装旧版本 APK
- 下载前清理过期 DownloadManager 记录
- 影响范围：`features/settings/SettingsScreen.kt`（~260 行）；真机 OTA 验证通过

## 2026-06-20

### v4lite L1–L5 全部落地（commit `38367f5`）
- **L1 书库接入**：LibraryScreen 真实数据流 + SAF picker 导入 + Navigation 书架→阅读器
- **L2 Reader 完善**：ReaderScreen(113行) + ReaderViewModel(165行) MVI + 进度 2s debounce 持久化 + 字号/主题/chrome toggle
- **L3 EPUB 原生重排**：EpubReflowEngine(107行) + EpubParser + EpubParaAdapter；ZipFile+jsoup→RecyclerView 连续滚动；Locator.Section(spineIndex, elementIndex, charOffset)
- **L4 PDF 引擎**：PdfRendererEngine(107行) + PdfPageAdapter；逐页 ImageView + ViewPager2 分页
- **L5 Phase A 验收**：ACTION_VIEW/ACTION_SEND intent-filter、进度恢复、最近阅读排序、RFC1918 网络安全
- **额外引擎**：Markdown 引擎（MarkdownEngine.kt 106行，Markwon，BookFormat.MD）
- **OTA 更新系统**：GitHubUpdateChecker + AppUpdateManager + UpdateInstallReceiver；前台检查→通知→DownloadManager→安装
- **Settings 完整 UI**：Calibre URL / 字号滑块(12-28sp) / 主题切换(白/暗/护眼/系统) / 手动检查更新
- **render 全部模块就位**：api(121行) / epub(204行) / pdf(163行) / txt(202行) / md(106行) / animate(2文件) / mupdf(空壳)
- **features 全部模块就位**：library / reader / settings
- **core:prefs** SettingsRepository（DataStore）已存在并被 ReaderViewModel import
- **core:sync** SyncManager + NoOpSyncBackend 已存在并被 ReaderViewModel import（LWW 骨架，实际 no-op）
- **build-logic/** 4 个 convention plugin 已落地
- 验证：`-Preadflow.phase=2 :app:assembleDebug` BUILD SUCCESSFUL；真机 OTA 安装验证通过
- 影响范围：14 个 render/features 模块从空壳→实现 + app Phase 1/2 双 sourceSet；总计约 2000 行 Kotlin

### OTA 更新系统迭代（13 个增量提交）
- 手动 HTTP → DownloadManager（`9819723`）
- POST_NOTIFICATIONS 权限 + 版本比较修正（`f3aa680`）
- goAsync() 解决后台启动 Activity 限制（`66a4018`）
- GitHub 私有仓库 Bearer token 认证（`4017054`/`a7f8373`/`b46def6`/`604d251`）
- 权限引导 REQUEST_INSTALL_PACKAGES（`17e5bec`）
- 设置页手动「检查更新」按钮（`35dfc2d`）
- HTTP 错误不再静默吞掉（`149067c`/`d7ac74f`）
- 应用内下载进度 + 自动拉起安装器 + 跳过重复下载（`a59bc83`/`049e45f`）

### 功能增强（非 v4lite 原计划）
- **6 项需求合包**（`a3c96f8`）：进度持久化完善、主题切换、封面展示、文件夹批量导入、拖拽排序建组、字体预览
- **Dwell 悬停建组**（`1ad420c`）：长按悬停触发建组 + 根因修复
- **Bundle 详情页 + 拖拽优化**（`c1b8c78`）：长按菜单计时修正、拖拽命中坐标修正、Bundle 堆叠视觉打磨

### 构建/签名/CI
- 签名：KEYSTORE_BASE64 环境变量（`2d8aa39`）、CI 固定 debug keystore（`1c4fb7a`/`e401896`）
- 减包：material-icons-extended → core（`ec58bfd`）
- 种子书「测试安装文本」（`3b7ba45`）、Seeder SharedPrefs 追踪（`a88b0da`）

### Bug 修复
- 边缘到边缘状态栏 + 顶栏导入/设置按钮（`a0ee647`）
- SAF 文件名用 OpenableColumns.DISPLAY_NAME 解析（`7d21d0b`）
- 旧 DownloadManager 记录清理（`3b7ba45`）

## 2026-06-19

## 2026-06-19

### TXT 最小阅读链路（垂直切片，phase 2 起点）
- 依据 `docs/android-p2-txt-minimal-slice.md`，以 TXT 单格式打通「解析→引擎→View→reader→app」端到端
- `:render:api`（L2）：`ReaderEngine`/`ReadingMode`/`PagingKind`（线程契约）、`EngineDescriptor`/`ReaderEngineRegistry`/`NoEngineException`（按扩展名 resolve）、`PageTransitionHost`/`PageTransitionHostFactory`（§5.6）
- `:render:txt`（L3）：`TxtVirtualPagerEngine`（CONTINUOUS、RecyclerView、UTF-8 整读切段、滚动上报 progression、ByteOffset/Section locator）+ `TxtParagraphAdapter`（18sp/行高1.6）；最小版未上 FileChannel 64KB 流式 + ICU 编码探测
- `:render:animate`（L3）：`NoTransitionHost`（CONTINUOUS 直挂）+ `DefaultPageTransitionHostFactory`
- `:features:reader`（L6）：`ReaderIntent`（MVI 子集）+ `ReaderViewModel`（经 Registry resolve，不 import 具体引擎）+ `ReaderScreen`（AndroidView 挂 host）
- 空壳（满足 phase=2 resolve）：`:render:epub`/`:render:pdf`/`:render:md`/`:features:settings` 仅 build.gradle.kts
- `:core:model`：`BookFormat.fromExtension()` 补全（Registry 解析用）
- `:app`：Koin renderModule（TXT descriptor + Registry + HostFactory）+ ReaderViewModel 绑定 + 导航「打开示例 TXT」+ `assets/sample.txt` + copySampleToCache（assets→cacheDir→file:// uri 供 ContentResolver）
- **F9 工程决断**：phase1 app 不能引用 phase2 才在场的 `render:*`（否则 `-Preadflow.phase=1` 解析报 project not found）。解法：`:app` phase 条件 sourceSet `src/phase1`（foundation 版，零 render）/`src/phase2`（TXT slice 版），build.gradle.kts 按 `readflow.phase` 切 srcDir + 条件 render 依赖
- catalog 补 `recyclerview = 1.4.0`
- 验证：`-Preadflow.phase=2 clean :app:assembleDebug` → SUCCESSFUL（apk 22.3MB）；`-Preadflow.phase=1 clean :app:assembleDebug` → SUCCESSFUL（独立可构建未被破坏）；C1 render:txt 无 compose；C3 features:reader 仅依赖 render:api；model 纯度 grep 空；phase1 无 render import
- 影响范围：3 个新 render 模块 + 4 空壳 + features:reader + app phase 双 sourceSet；真机滚动验证待 AVD

### Phase 1 地基框架搭建（不实现功能）
- 依据 `docs/android-p1-foundation-plan.md`（同日新建的 P1 执行文档）落地 9 模块 + `:app` 骨架
- `build-logic/` composite build + 4 个 convention plugin：`ReadflowJvmLibrary`（core:model）/`ReadflowAndroidLibrary`（Layer1）/`ReadflowCompose`（core:ui）/`ReadflowFeature`（feature）；`settings.gradle.kts` 加 `includeBuild("build-logic")`；catalog 补 4 个 gradlePlugin 依赖项供插件 compileOnly
- `:core:model`（Layer0 纯 JVM）：`Locator`/`LocatorStrategy`、`ReaderState`、`ReadflowError`/`ReadflowException`、`ReadflowResult`、`BookMeta`/`BookFormat`/`DownloadStatus`/`ThemeMode`/`Offset`/`DownloadedAsset`、`LoadingState`/`TransitionType`、`ReadingProgress`/`Bookmark`（带同步元数据）、`InkAnchor`——全 `@Serializable`，纯度 grep 通过
- Layer1 五模块：`:core:calibre`（CalibreClient 迁出 app + CalibreRepository 接口）、`:core:database`（Room 5 表实体 + DAO 签名 + ReadflowDatabase，schema 导出）、`:core:prefs`（SettingsRepository 接口）、`:core:sync`（SyncBackend+NoOpSyncBackend+SyncManager）、`:extensions:api`（BookSource/Extension SPI/ReaderEventBus）
- `:core:ui`（Material3 ReadflowTheme，暖白/夜间色板）、`:features:library`（LibraryViewModel 空 state + LibraryScreen 占位）
- `:app`：ReadflowApplication（startKoin）+ Koin 模块骨架（NoOpSyncBackend/SyncManager/ReaderEventBus/LibraryViewModel）+ Navigation host（单占位路由）+ network_security_config.xml 占位（C2：不硬编码 baseUrl）；删 app 内旧 CalibreClient.kt
- 全部为纯数据/接口/空壳，无业务逻辑（R2/R3）；业务实装边界见 P1 文档 §6
- 验证：`-Preadflow.phase=1 :app:assembleDebug` → BUILD SUCCESSFUL（app-debug.apk 24.5MB）；model 纯度 grep 空；features:library 不依赖 render:*；phase=1 不含 phase2/3 模块、phase=2 正确纳入 render/reader
- 影响范围：`android/build-logic/`、9 个新模块、catalog、settings、app 组装层；IMPLEMENTATION GATE 已获用户「通过，执行」放行

### Android 工具链落地 + SDK 口径升 36
- 本机装 Android Studio 2026.1.1（build AI-261，外挂盘 `/Volumes/OmubotDisk/Applications/Android Studio.app`）+ SDK（外挂盘 `/Volumes/OmubotDisk/Applications/sdk`，Platform 36 / build-tools 36.1.0+37.0.0）
- 中文化：官方语言包（plugin 13710）最新仅到 build 242，不覆盖 261，保持英文 UI
- 因 SDK 只有 Platform 36，项目从 35 升 36：`app/build.gradle.kts` compileSdk/targetSdk 35→36；AGP 8.7.3→8.13.2（旧 AGP 不认 SDK36/build-tools37）；Gradle wrapper 8.11→8.14.3（AGP8.13 要求）；v4 §10.2 convention plugin 口径同步 35→36
- 修既有 scaffold 编译缺陷（非升级引入）：Manifest 引用未依赖的 `Theme.AppCompat.*` → 新建 `res/values/themes.xml` 定义 `Theme.LinReads`（系统 DeviceDefault，纯 Compose 不引 AppCompat）；补 `compileOptions` source/target=17 解决 JVM target 不一致
- 新建 `android/local.properties`（指外挂盘 SDK，已被 .gitignore 忽略）；JAVA_HOME 用 Studio 自带 JBR 21
- 验证：`./gradlew -Preadflow.phase=1 :app:assembleDebug` → BUILD SUCCESSFUL，产出 app-debug.apk（空壳）
- 影响范围：构建配置 + 1 资源文件 + 1 manifest 行 + 文档口径；尚未进 Phase 1 模块实装

### v4 架构最终审计 + 无争议项修补
- 对 `docs/android-architecture-v4.md` 做实装前最终审计：未发现颠覆性方向错误；路线（统一引擎接口、Hybrid 原生 View、去 WebView 原生重排、Locator 进度主键、PdfRenderer、MuPDF optional）与外部成熟实践一致
- 已修补（无取舍空间项）：
  - **B2 Locator 建模洞** — `LocatorStrategy` 从散文裸枚举名补为带 payload 的 `@Serializable sealed interface`（`Page`/`Section(spineIndex,elementIndex,charOffset)`/`ByteOffset`/`Unknown`），与 `InkAnchor.Text` 共坐标系；§3.3 纯度白名单补 `LocatorStrategy`/`LoadingState`
  - **M1 EngineStateStore 归属缺失** — 接口落 `:render:api`（Layer 2），实现落 `:app`；§3.1/§3.2 模块表补登记
  - **文档↔仓库漂移**：`settings.gradle.kts` 注释开关 → `readflow.phase` property 驱动 `phaseInclude()`（落地 P6/F9，phase1 含 `:core:ui`/`:core:database`/`:core:sync`）；`libs.versions.toml` 删 nanohttpd（落地 E1），加 jsoup；`app/build.gradle.kts` 注释从 WebView+epub-ts+nanohttpd 改原生重排+jsoup
- 留待用户决断（4 项，未改）：B1 Phase 1 范围与「无账号本地阅读闭环」矛盾、B3a totalProgression 全书分母 vs 惰性解析冲突、B3b 三端规范化纯文本收敛机制、M3 EPUB 分页是否锁定 Phase 2
- 影响范围：文档层 + 构建配置（settings/catalog/app build），无运行时 Kotlin 代码变更

### v4 四项决断落档（用户裁决）
- **决断 1（B 选项）**：构建 Phase 1 重定义为**基建期**（书库浏览 + 本地导入登记 + 最近阅读 + 进度本地存储，**不渲染正文**）；用户验收 Phase A「能打开本地书并继续阅读」下移到构建 Phase 2（reader + 引擎就位）。§2.2/§10.3/§11 Phase 1 措辞与结束态同步修正，消除「Phase 1 没有 reader/引擎却号称能阅读」的自相矛盾
- **决断 2（接受预扫）**：`totalProgression` 分母「全书纯文本总字符数」由 `openBook()` 一次性预扫得出——每个 spine 项只「解析→规范化→计 code point」产出轻量 per-spine 计数表（不留正文，几十 KB），计入「打开」预算；与惰性正文解析正交，「内存与书体积解耦」仍成立；计数表随 EngineStateStore 缓存，大书可后台补算回填。落 §7.1
- **决断 3（仅 Android，三端契约后置）**：当前只实装 Android，三端 `totalProgression` 收敛（< 0.5%、共享参考实现、对拍语料）暂不落地；口径先作 Android 端内部规范保证单设备换字号/重开稳定；待 Web/HarmonyOS 接入同步时再提为 `shared/` 共享实现并冻结契约。落 §7.1
- **决断 4（锁定连续滚动）**：EPUB 首发唯一形态 = `pagingKind=CONTINUOUS`（LazyColumn 连续滚动）；分页（per-page ComposeView over ViewPager2）独立成 Phase 2 gate，明确标注为全架构最高技术风险点、达不到 < 50ms 帧预算可推翻退回连续滚动，不与基础阅读链路绑定。落 §5.5
- 影响范围：文档层（§2.2/§5.5/§7.1/§10.3/§11），无运行时代码变更

---

## 2026-06-18

### Android v3 Framework Architecture Audit
- 新增并重审 `docs/audit/android-v3-framework-audit-2026-06-18.md`，从少人团队精品项目视角审计 `docs/android-architecture-v3.md`；重审后按用户反馈明确“轻量”指用户使用轻量，不是开发轻量或少建模块
- 结论：v3 方向正确，适合作为目标架构；21 模块、自研引擎和复杂内部实现均可接受，但必须补 `User-Light Architecture Contract`，确保复杂度不转嫁给用户
- 评分：19.3/28；强项为 Hybrid View、ReaderEngine、纯数据 ReaderState、Locator、离线优先；弱项为用户轻量契约、首开路径、安装包/按需能力边界、数据导出、权限矩阵、WebView 安全/许可证 gate
- 关键 P0/P1：缺少用户轻量架构契约；`ReadflowError` 不应携带可序列化 `Throwable`；首次使用路径应支持无账号本地阅读；`ReaderEngine.createView()` 与 `ViewPager2.PageTransformer` 宿主关系需定义；`BookSource.download()` 不宜返回 `java.io.File`；SyncBackend 缺少 LWW/Union 元数据和开放备份格式；WebView JS bridge 需安全 ADR；MuPDF 需 license/optional gate；权限与隐私矩阵缺失
- 建议验收顺序：无账号本地阅读闭环 → Calibre 作为可选书源 → EPUB/PDF/TXT 阅读质量闭环 → 数据出口与离线缓存 → Ink/TTS/OPDS/Sync/DOCX/CBZ 精品增强
- 影响范围：文档/审计层，无运行时代码变更

### Architecture Drift Cleanup
- 收口当前入口文档与 wiki 的口径漂移：`docs/architecture.md`、`docs/wiki/Architecture.md`、`Home.md`、`Platform-Web.md`、`Platform-HarmonyOS.md`、`Rendering-Engine.md`、`Development-Guide.md`、`Active-Work.md`、`Calibre-API.md`、`README.md`、`CLAUDE.md`
- 明确区分当前实现与目标架构：Web 当前仍使用 `epubjs`；Android v3 与后续 EPUB 工作目标为 `@likecoin/epub-ts`
- 移除当前入口中的旧 `epublib` / Android MuPDF-for-EPUB / 自研 AnimationEngine 目标描述，改为 v3 的 Hybrid View、WebView + epub-ts、PdfRenderer、TxtVirtualPager、ViewPager2 PageTransformer
- 修正 HarmonyOS 状态：`BookList.ets` 已有基础书单渲染，剩余为 Settings 持久化 baseUrl、封面、错误/空状态
- 修正 `BACKLOG.md` 中模块计数“统一为 19”的错误，权威口径为 21 模块；历史审计/研究材料保留原文
- 修正 README/wiki 对格式能力的表述：当前可用能力是 Web 基础 EPUB/PDF，MOBI/TXT/MD/DOCX 等按目标架构待实现
- 修正 `Platform-Android.md` deprecated banner：历史页保留，但明确当前权威规范是 Android Architecture v3
- 影响范围：文档/追踪层，无运行时代码变更

### Continuity Tracker Normalization
- 按 `omubot-continuity` 规范收口进度追踪：`docs/tracking/ACTIVE.md` 改为短入口，明确 `Mode: task`、当前 objective、active tracker 和 implementation gate
- 新增 `docs/tracking/linreads-architecture-docs-2026-06-18.md`，承接 live todo、决策、分区进度、验证、风险、回滚和不再重复调查项
- 记录当前文档漂移风险：历史追踪/文档中仍出现 19/20/21 模块说法，`docs/architecture.md` 仍有 epubjs/epublib 旧描述；后续按 tracker 的 Live Todo 收口
- 影响范围：文档/agent handoff 层，无运行时代码变更

### Codex Continuity Skill 迁移
- 从外置盘 Omubot 项目 `/Volumes/OmubotDisk/omubot` 定位并迁移 `omubot-continuity` skill，用于 Codex 上下文压缩/新会话后的任务恢复、tracker 维护、handoff 和 Test Ledger 纪律
- 源文件：`/Volumes/OmubotDisk/omubot/.claude/skills/omubot-continuity/SKILL.md`
- 目标：`/Users/kragcola/.codex/skills/omubot-continuity/SKILL.md`
- 已校验源/目标 `cmp` 一致；Codex 需重启后自动发现该 skill
- 未迁移 Omubot 项目内 `.codex/hooks.json` 与 `scripts/dev/codex-session-start.py`，因为它们依赖 Omubot 专属 `docs/project-info.md`、`docs/tracking/ACTIVE.md` 和 tracker 结构；如要给 LinReads 启用自动 SessionStart 恢复，需要单独做项目适配

### Codex Skill 对齐
- 修正 `.claude/skills/` 中面向人的 Readflow 命名残留：开发、EPUB、同步、无障碍、TDD、调试等说明统一称 **LinReads**
- 保留 `dev.readflow`、`adb logcat | grep -i readflow` 等当前 Android 包名/日志技术标识，未做代码级包名迁移
- 将 Claude 侧 9 个 skill 同步安装到 Codex：`linreads-dev`、`linreads-epub`、`linreads-sync`、`tdd`、`systematic-debugging`、`accessibility`、`design-audit`、`requesting-code-review`、`receiving-code-review`
- 影响范围：agent 行为层；无运行时代码变更。Codex 需重启后自动发现新 skill

### v3 架构文档发布
- 合并 v2 主文档 (2310行) + Addendum + 根因裁决 (12项) → **统一 v3 文档** (1791行, ~75KB)
- 11 章节：设计原则 / 模块地图 / 视图架构 / 渲染引擎 / Ink集成 / 数据层 / 扩展系统 / DI / Gradle / 迁移路径 / 裁决记录
- 19 项质量检查全部通过：21 模块/8 层一致、epub-ts 唯一引用、ViewPager2 替代自研引擎、Locator∈core:model、ReaderState 零 View 引用、BookSource Addendum 版本、7 渲染引擎、12 项裁决已记录
- v1/v2 文档标记废弃。Platform-Android.md 标记 DEPRECATED
- `docs/architecture.md` 指向 v3
- 影响范围：新增 1 文档 + 更新 4 文档

### 第三轮架构终审
- 三维并发审计（交叉校验 2/5 + 构建就绪度 WILL_FAIL + 一致性 3.4/5）
- 裁决：**有条件通过**。架构已就绪，但 Phase 1 范围必须缩减 75%（21→5 模块）
- 发现 8 处交叉矛盾（4 HIGH）：ReaderState 双定义冲突、BookSource 7 处签名差异、Locator 重复定义、core:model→render:api 循环依赖
- 构建必败：缺失 kotlinx-serialization-json、kotlin-serialization 插件、kotlin-jvm 插件、material-icons-extended
- 关键路径：14 步，Phase 1 预估 7-8 工作日，首个交付物为工作书库浏览器 APK
- 5 风险：架构过度设计、WebView 碎片化、多模块复杂性、epub-ts 兼容性、文档-实现鸿沟
- Round 1→3 评分演进：2.08→3.3→3.4。Go/No-Go 演进：NO→CONDITIONAL NO→CONDITIONAL GO
- 新增 `docs/audit/round3-audit-report-2026-06-18.md`：第三轮终审报告

### 修复执行 + 架构补全
- 执行 P0 构建阻塞修复：Gradle wrapper 生成 (8.11) + settings.gradle.kts + build.gradle.kts + gradle.properties + libs.versions.toml (46 依赖，2026 版本)
- 执行 P1 架构一致性修复：v2 文档 epublib→epub-ts 统一、模块计数 19→21 统一、ReaderRootLayout 路径修正、PDF API 26+ 统一
- 新增 `docs/android-architecture-v2-addendum.md`：补全 P2（6 类型定义：ReaderState/InkBrush/ExtensionSettings/DownloadStatus/SyncBackend/BookSource）+ P3（9 策略文档：离线/同步/渲染/性能/测试/安全/无障碍/大屏/KMP）
- 重命名 3 个 skill 目录：`readflow-*` → `linreads-*` + SKILL.md 内容更新
- 更新 CLAUDE.md：epub-ts 引擎说明、架构文档链接、lint 命令
- 更新 `docs/architecture.md`：指向 v2 架构文档
- BACKLOG 从 41→1 待办：仅剩 `ARCHITECTURE.md` 更新确认
- 影响范围：构建文件 (8 新建/修改) + 文档 (4 修改/新建) + skill 目录 (3 重命名)

### 项目改名 + EPUB 引擎新发现
- 项目名从 **readflow** 改名为 **LinReads**。批量更新 28 个文档文件（wiki/CLAUDE/README/audit/research）
- 发现 **`@likecoin/epub-ts`**：epubjs v0.3.93 的完全 TypeScript 重写，drop-in 替换（改一行 import），API 完全兼容
  - 性能：`locations.generate(1000)` 从 43 秒 → 159ms（270x 提升），包体积 57.5KB（vs epubjs 132.8KB）
  - BSD-2-Clause 许可，970+ 测试，活跃维护，支持不安全上下文（http:// 内网 nanohttpd 可用）
  - **这应该成为 LinReads 的默认 EPUB 引擎**
- 发现 **`Aryan-Raj3112/episteme`**（914 stars，AGPL-3.0）：Kotlin Multiplatform 阅读器（Android + Desktop）
  - 格式覆盖与 LinReads 高度重叠：PDF/EPUB/MOBI/FB2/DOCX/TXT/MD/HTML/Comics
  - PDF ink annotations 已实现。使用 PdfiumAndroidKt + libmobi + Jsoup + Flexmark
  - 离线优先，20 语言（含中文简体），F-Droid 可下载
- 已 clone `references/readbooks-v2-android`（MIT）：Readium Kotlin 3.1.2 + 离线下载 + 阅读会话追踪的完整参考实现
- 影响范围：文档层改名 + 新增参考项目

### 架构定夺 + 信息搜寻
- 用户 5 项决策：epubjs（默认）+ foliate-js（备选）、保持 20 模块细粒度、Phase 1 不做笔写（B 方案预留）、C 智能缓存 + A 手动下载、A 自建同步 + B WebDAV 兜底（Phase 1 预留）
- 广度搜索发现：
  - 直接对标项目 `fbaldhagen/readbooks-v2-android`（MIT）：Readium Kotlin Toolkit 3.1.2、完全离线、Clean Architecture
  - 同步服务 `szaffarano/korrosync`（MIT, Rust）：5 端点、Docker 部署、v0.4.0 生产可用
  - EPUB 引擎备选 `@asteasolutions/epub-reader`（npm, 2026-02）+ `intity/epub-js`
  - 文字锚定 `tilgovi/dom-anchor-text-position`（MIT）：W3C TextPositionSelector JS 实现
  - 多策略锚定 `@net7/annotator`（TypeScript）+ Hypothesis fuzzy anchoring
- 新增 `docs/audit/decisions-2026-06-18.md`：完整决策 + 20 项修复清单 + 新参考汇总
- 影响范围：文档层，修复留待下一轮

### v2 架构全方位审计
- 五维度并发审计 v2 架构文档：一致性 3/5、完备性 3/5、可行性 3/5、对标 competitive、缺口 4/5
- 综合评分 **3.3/5**，状态管理 + 手写笔批注 best-in-class
- 发现 4 项严重问题：离线书籍缓存缺失、同步后端未定义、Gradle 构建将失败（nanohttpd 缺失 + maven.ghostscript.com 未声明）、foliate-js 与 Android WebView 安全冲突
- 发现 9 处内部矛盾：模块计数偏差、Locator 重复定义、ExtensionContext 跨层依赖违规、ReaderRootLayout 位置冲突、PDF API 等级不一致等
- 发现 8 项 v2 新引入问题：foliate-js vs epubjs 二选一矛盾、模块过度拆分、离线优先语义降级等
- 最小修复集：8 项必须在编码前完成，预计 2-3 工作日
- 新增 `docs/audit/v2-audit-report-2026-06-18.md`：完整 v2 审计报告（299 行）

### 优化架构 v2
- 基于全部审计发现 + 前沿项目搜索，生成优化架构 v2 文档（`docs/android-architecture-v2.md`，2310 行）
- 四大设计维度并发生成：模块化构建（20 个 Gradle 模块 + convention plugins）、混合视图 + 渲染契约（ReaderEngine 接口 v2）、数据层 + 共享契约（Locator + LinReadsError + JSON Schema 生成）、扩展系统 + DI（Extension SPI + ServiceLoader + BookSource + ReaderEventBus + Koin multi-bind）
- 关键变更：EPUB 改 WebView、动画改用 ViewPager2、文档渲染基元从 Bitmap 改为 View、错误从 String? 改为 sealed hierarchy、书源从具体类改为接口
- 附录 A：v1 vs v2 20 项关键决策对照表
- 附录 B：~60 个关键文件路径汇总
- 状态：架构定稿，所有 4 个 BLOCKER + 6 个 HIGH + 5 个 MEDIUM 项已消解

### 自研引擎必要性评估
- 逐格式对照成熟引擎覆盖度，分析自研 vs 现有方案的 tradeoff
- 结论：**LinReads 不需要自研渲染引擎**。唯一需要自研的是 TxtVirtualPager（~300 行），因为 TXT 大文件阅读没有成熟开源 Android 方案
- 发现 foliate-js（MIT，Readest 21k stars 在用）是 epubjs 的实质升级：支持 EPUB+MOBI+FB2+CBZ+PDF 6 种格式，内置 CFI/overlayer SVG/TTS/搜索/OPDS
- Moon+ Reader MRTextView 分析：~8000 行自研引擎代码，EPUB 部分是过度工程（2012 年 WebView 质量差，现在不成立），TXT 部分方向正确但实现过重
- 前沿国内 App C++/Skia 引擎（微信读书/掌阅/知乎盐言）：LinReads 不需要——那是出版级需求，需要 5-10 人年 C++ 投入
- LinReads 总自研代码：~300 行。对比 Moon+ Reader ~12,000 行。许可风险：零（全部开源/系统）
- 新增 `docs/research/engine-make-vs-buy.md`：完整评估文档
- v2 架构 `render:epub` 模块从 epubjs 改为 foliate-js

### 终审：架构开放度与便捷度
- 四维度并发审计（扩展开放度/构建便利度/接口契约/集成匹配度），综合得分 **2.08/5**
- 结论：**架构目前不具备实施就绪条件**，距可实施状态需 4-6.5 工作日阻断项修复
- 三个结构性矛盾：(1) Compose Bitmap ↔ View Ink 互斥 (2) "零 WebView" ↔ EPUB/笔写需求冲突 (3) 共享契约零强制执行
- 5 个做对的设计：MVI 方向正确、ReaderEngine 抽象、Calibre 集成、多格式覆盖、Ink 锚定研究深度
- 便利基线清单：4 BLOCKER + 6 HIGH + 5 MEDIUM，预估总投入 12-19 工作日可完成全部修复
- 新增 `docs/audit/final-audit-report-2026-06-18.md`：完整终审报告（286 行）
- 影响范围：判定阶段（明确在修复完成前不应写功能代码）

### EPUB 手写笔迹锚定技术调研
- 调研核心问题：reflow EPUB 上手写笔迹如何锚定到文本内容，使其在字号/排版变化后不丢失
- 调研范围：W3C EPUB Annotations 1.0 (2026-05)、Microsoft 专利 US7218783B2、SpaceInk (UIST 2019)、ACM 论文 "Reflowing Digital Ink Annotations" (2003)
- 商业产品分析：Kindle Scribe (Sticky Notes + Active Canvas)、Apple Books (SVG→HTML position:absolute)、BOOX (页面坐标、不锚定文字)、Kobo/reMarkable/Supernote
- 结论：行业无完全自由的 reflow EPUB 笔写方案。所有产品都做了取舍——Kindle Scribe 限制手写形式，BOOX 牺牲重排安全
- 推荐 LinReads 采用双模式策略：PDF 页面级自由书写 + EPUB Sticky Note（文本锚定）
- 给出 4 条技术路径的详细分析（路径 A-D）和 Android WebView + JS Bridge + Native Ink 的具体实现方案
- 新增 `docs/research/ink-anchoring-research.md`：完整调研文档

### 笔写+阅读架构断裂分析
- 追踪「阅读 + 跟手笔写」五个核心场景，发现当前架构无法支持此组合需求
- 发现 3 个架构矛盾：Compose Bitmap ↔ View ink 互斥、ToggleInk ↔ 自动路由矛盾、"零 WebView" ↔ ink 宿主缺失
- 定位最难问题：reflow EPUB 上笔迹锚定——行业无成熟开源方案（GoodNotes 只做 PDF，Apple Books 用浮动图片嵌入 HTML）
- 提出修正方案：hybrid View+Compose 架构、EPUB 改 WebView、笔迹双锚定模型（Page/Text）、ReaderState 重构
- 放弃"零 WebView"原则——对 EPUB 格式，WebView 是正确的渲染路径
- 新增 `docs/wiki/Ink-Architecture-Gap.md`：完整断裂分析 + 修正方案
- 影响范围：架构设计层（需在编码前修正）

### 成熟阅读器架构对比审计
- 对标三个成熟项目拆解架构：KOReader（C++/Lua, 13年）、Mihon/Komikku（Kotlin/Compose, 5年）、Readium（Swift/Kotlin SDK, 9年）
- 逐维度对比：格式注册/渲染路径/状态管理/插件系统/进度定位/动画翻页/数据持久化
- 提出 3 个关键设计重审建议：EPUB 改走 WebView（非 MuPDF bitmap）、进度模型采用 Readium Locator、动画先用 HorizontalPager 再逐步替换自研
- 提取 7 个可立即采纳的模式（P0: 渲染路径+Locator; P1: Interactor+格式注册表+双向预取; P2: 轻量插件+sidecar 导出）
- 新增 `docs/wiki/Architecture-Comparison.md`：完整对比报告含评分矩阵
- 影响范围：文档层，无代码改动

### Android 端架构审计
- 独立审计 `docs/android-architecture.md` + `docs/wiki/Platform-Android.md`，三方对标：静读天下源码验证版 + 2025-2026 前沿架构（Mihon/Komikku/KOReader/Google 建议）+ 实际代码实现状态
- 发现 1 处内部设计矛盾（PDF 渲染方案 MuPDF vs PdfRenderer，已统一为 PdfRenderer）
- 发现 4 处设计与实现的严重滞后（构建工具链/Kotlin 版本/Compose Compiler/模块化）
- 新增 `docs/wiki/Android-Architecture-Audit.md`：完整审计报告含评分卡、修正清单、工作量估算（9-14 周单人全职）
- 修正：`docs/android-architecture.md` PDF 渲染策略表
- 影响范围：文档层，无代码改动

### 静读天下架构源码交叉验证
- 独立分析 `moonreader-decompiled/sources/com/flyersoft/` 核心包（~164K 行），逐项对照已有文档
- 发现并修正 3 处事实性错误：
  1. PDFReader 非 BaseEBook 子类（`extends FrameLayout`），走独立 RadaeePDF 渲染路径
  2. MyLayout 非 Android StaticLayout 子类（`public abstract class MyLayout` 无 extends），是 100% 自研布局引擎
  3. CHM 库为 34 个类（非 29）
- 补充 4 处遗漏细节：排版层完整继承链、CSS.java 体量（75KB）、staticlayout 包规模（51 文件）、ActivityTxt God Class（20,793 行）
- 新增 `docs/wiki/Architecture-Verification.md`：完整验证报告
- 修正文档：`docs/research/moonreader-analysis.md`、`docs/research/moonreader-architecture-review.md`
- 影响范围：文档层，无代码改动

### 项目 Wiki 创建
- 新增 `docs/wiki/`：14 页结构化项目 Wiki
  - 详见上方 2026-06-18 条目（同一条维护日志的上一个条目）
- 影响范围：文档层，无代码改动

---

## 2026-06-17

### 项目文档初始化
- 新增 `CLAUDE.md`：项目指令、命令速查、平台状态、skill 使用指南
- 新增 `docs/architecture.md`：系统架构 wiki
- 新增 `maintenance-log.md`（本文件）
- 新增 `docs/tracking/ACTIVE.md`：跨 agent 任务交接
- 影响范围：文档层，无代码改动
- 回滚：`git revert`（纯文档，无运行时影响）

### Skill 栈安装（commit `44f0a64`）
- 安装 9个 skill 到 `.claude/skills/`：
  - 层1（通用开发律条）：`systematic-debugging`、`tdd`、`requesting-code-review`、`receiving-code-review`
  - 层2（UI/UX）：`accessibility`、`design-audit`
  - 层3（LinReads 定制）：`linreads-dev`、`linreads-epub`、`linreads-sync`
- 来源：obra/superpowers（社区）+ mrKanoh（改写）+ tdimino（精简）+ 自写（LinReads-\*）
- 影响范围：`.claude/skills/`，仅 agent 行为，无运行时影响

---

## 2026-06（估算，来自 commit `5242e1d`）

### 初始三端脚手架
- Web：React18 + epubjs + Vite，书库列表 + EPUB/PDF 基础阅读，Calibre proxy 配置
- Android：Kotlin + Compose + Ktor，UI 骨架（书库/阅读/设置三 Tab）+ CalibreClient
- HarmonyOS：ArkTS + ArkUI + @ohos.net.http，书库页骨架 + CalibreService
- 共享：`shared/api/calibre-contract.ts` 三端类型契约，`shared/calibre/api.md` API 参考
- 状态：Web 端功能最完整；Android/HarmonyOS 为 scaffold，尚未连接真实数据
