# LinReads Android v4 全量全维度审计

> **审计对象**: `docs/android-architecture-v4.md`（1038 行，22 模块/8 层/7 引擎）
> **日期**: 2026-06-18
> **尺子**: 少人开发精品软件（small-team boutique）· 用户侧轻量 · 数据可恢复
> **范围**: 全维度（渲染引擎/视图/Ink · 数据层/同步/扩展 · 模块分层/Gradle/DI · 安全/无障碍/性能/定位）
> **前置**: 本轮在两轮 v3 框架审计 + 一轮外部对标（`external-benchmark-audit-2026-06-19.md`）之上，只收录**新问题**，不重复 §9 摘要 / §12.1–12.3 已记录的 P0-A~P2-J / R1–R12 / E1–E3 / P1–P8。

## 〇、结论先行

整体倾向判断：**对少人精品团队偏重，局部过度，且存在风险错配。**

文档工程品味高——五条轻量契约、Phase A–E 按「用户何时获得轻量体验」排序、自研裁决要 scorecard、measurement gate——方向完全正确。但这些克制原则**没有反向作用到自身结构规模**：22 模块/8 层/7 引擎可插拔 + Registry 权重发现 + override 快照 + 懒加载 + KMP 预留 + 全套测试矩阵，是中型团队的完备地基。

**核心风险错配**：完备的框架（可插拔引擎体系、测试矩阵）排在前面建设，而真正决定「精品」成败、最不确定的**自研 EPUB 原生重排**（解析正确性 + 分页定位 + 无障碍 + 图片内存 + 解析安全）只有一个 ADR 占位。架构完备性在一定程度上掩盖了「EPUB 引擎能否做出精品质量」这一核心交付风险。

## 一、渲染引擎 / 视图 / Ink（§四 §五 §六）

### R-1 [P0] EPUB 分页归属自相矛盾：ViewPager2 host 与 ComposeView 内 HorizontalPager 双重分页
- **证据**：§4.2 L169「分页格式由 PageTransitionHost 包装」+ L413「EPUB paged → ViewPager2-backed host」；§4.2 L170 与 §5.5 L390「EPUB → ComposeView 内部 HorizontalPager 真分页」。
- **问题**：两套互斥分页机制。ComposeView 套进 ViewPager2 单页、再在内部 HorizontalPager 翻页 = 两层水平滑动手势嵌套，争夺横向触摸、动画各算各的，`onPageSettled`(L444) 报的是外层 index 而非内层真实页。
- **建议**：二选一并全文统一。推荐 EPUB 真分页交 ViewPager2 host（与 PDF/CBZ 同构、复用 Curl 动效、`onPageSettled` 才有意义），删 §5.5 L390「HorizontalPager」表述，改为「host adapter 按 spine+测量页号提供 per-page ComposeView」。

### R-2 [P0] ComposeView 的 ViewTree*Owner 挂载责任无人认领
- **证据**：§5.1 L280 `createView(): View` 返回裸 View，EPUB/MD 走 ComposeView；线程契约 L257-268、视图生命周期 L292-298 全篇未提谁挂 owner。
- **问题**：ComposeView 正常 recompose 必须设 `ViewTreeLifecycleOwner`/`ViewTreeViewModelStoreOwner`/`ViewTreeSavedStateRegistryOwner`，否则崩溃或不重组。经 ViewPager2 复用时 owner 易丢——这是 Compose-in-View 最常见崩溃源，契约层空白。
- **建议**：§5.1 补「Compose interop 契约」：ReaderRootLayout 或 host 在 attach 文档 View 时负责挂三件套，或要求 ComposeView 类引擎在 `createView()` 内自挂、`onViewDetached` 释放。

### R-3 [P1] EPUB 原生重排工时被低估，背书与目标不一致
- **证据**：§5.5 L388-391 把分页表述为单点风险；L982 ADR 拿 Myne/Book's Story 背书「低风险」。
- **问题**：Myne/Book's Story 路线之所以可控，正因为它们是连续滚动 + 系统字体覆盖，**主动放弃了真分页**——文档拿它们背书却把它们没做的真分页排进路线。真正吃工时是三块，文档只点了分页：① 不规范 XHTML 容错（无降级矩阵）；② AnnotatedString 表达力天花板（表格/多级缩进/`<pre>`/ruby/上下标无法表达，超出 §5.5 的 4 类 ReaderItem，需自建布局层）；③ 跨字号稳定分页。
- **建议**：把「真分页」从 Phase 2 默认降为 Phase 3 可选/风险已知项，Phase 1+2 锁定连续滚动即交付（与 Myne 背书对齐）。§5.5 补 ReaderItem 类型 → 渲染降级表（表格→单元格顺序流、多栏→单栏、`<pre>`→等宽不折行滚动），把「走样」从模糊取舍变成可验收的降级契约。

### R-4 [P1] InkAnchor.Text 的 cssSelector 与原生重排管线对不上（E1 删 CFI 时漏清的同源遗留）
- **证据**：§6.3 L516 `val cssSelector: String`、§6.1 L489「CSS selector 锚定…解析自 jsoup DOM」；但 §5.5 L386 解析后即降维成 ReaderItem 序列、L391 定位锚点是「spine index + 字符偏移 + element index」，全程不保留 DOM、不走 CSS。
- **问题**：cssSelector 与 CFI 同源，是 WebView/DOM range 遗留。重排后无 selector→坐标解析路径，Ink over EPUB（Phase 3）按此重定位 bounding box 无法落地。L233 自己说「与 Ink 锚点复用同一套坐标」，字段定义没兑现。
- **建议**：`InkAnchor.Text` 把 `cssSelector` 换成 `spineIndex + elementIndex + textStartOffset/EndOffset`，复用 §5.5/§7.1 定位坐标系。

### R-5 [P1] search() 契约不一致：ReaderIntent.Search 落了契约，ReaderEngine 接口没有 search()
- **证据**：§4.5 L239「`Search` 需 `ReaderEngine` 增 `suspend fun search(query): List<Locator>`」；但 §5.1 接口定义 L269-299 无此方法。L686 的 `search` 是 BookSource 书库检索，同名易混。
- **问题**：P3 声称「Intent 形状先定齐避免回头改」，却漏了支撑 Search 的引擎方法，落地仍要改 `:render:api`，违背初衷。
- **建议**：§5.1 Navigation 区补 `suspend fun search(query: String): List<Locator>` + 能力位 `val supportsSearch: Boolean`（PDF 无文本层返回空，不强制每引擎实现）。

### R-6 [P2] pagingKind 是 val，与 EPUB「Phase1 滚动 / Phase2 分页 / 运行时切模式」冲突
- **证据**：§5.1 L275 `val pagingKind`、L461 ReaderRootLayout 一次性选 host；§4.4 L290 `setMode` 允许阅读中切 SCROLL/PAGED。
- **问题**：同一引擎实例 pagingKind 应随 ReadingMode 变，val + 一次性 host 选择无法表达「运行时切滚动↔分页要换 host」。
- **建议**：pagingKind 改 `StateFlow<PagingKind>`，ReaderRootLayout 观察变化触发 host 重 bind；或明确「pagingKind 仅声明能力上界，运行时切换由 host 内部同时支持滚动+分页」并落注释。

### R-7 [P2] EPUB pinch 实时重排无节流/预览策略
- **证据**：§4.4 L197「Pinch 触发 setFontSize 重排（重新测量分页）」；§5.5 L390 自承跨字号重测是主要复杂度。
- **问题**：pinch 是每帧高频手势，每帧重排整章测量在大章节必丢帧。
- **建议**：§4.4 补约定——pinch 进行中用便宜视觉缩放预览，`ACTION_UP` 后 debounce 提交一次 `setFontSize` 真重排；分页测量按视口惰性（只测当前±1页，pageCount 异步回填，与 L286 reactive 语义一致）。

### R-8 [P2] TXT 自研 CharsetDetector 性价比低、易错
- **证据**：§5.4 L374「自研 CharsetDetector(UTF-8/GBK/Shift-JIS fallback)」。
- **问题**：GBK 与 Shift-JIS 字节区间重叠易误判，编码探测是经典坑；scorecard「数据可恢复」硬指标依赖探测准确性，而探测最该外包成熟库。
- **建议**：分页器自研保留（~300 行可控）；编码探测改用 ICU4J `CharsetDetector` 或 juniversalchardet，仅保留「探测结果→字符边界回退」自研逻辑。§5.4 拆开两个职责。

## 二、数据层 / 同步 / 扩展（§七 §八 §9.2）

### D-1 [P0] totalProgression 缺统一计算口径，跨端同步主键会漂移
- **证据**：§7.1 L532-543，把 `totalProgression`(0..1) 定为三端同步稳定主键，却全程未定义各端怎么算。
- **问题**：Web epubjs 走 CFI/locations（按 spine 字数）、Android 原生重排按「页 index/总页数」或「累计字符偏移/全书字符数」、HarmonyOS 第三套。分母取页数则同样字号敏感（与 ByteOffset 一样漂）；取字符偏移则三端解析过滤口径（是否计标签/空白/脚注）不一致也偏。结果：同一位置三端值不等，LWW 比对出的「最新进度」落到错误位置，且静默无报错。这让 E2 承诺落空。
- **建议**：§7.1 钉死规范化定义（如 `totalProgression = 累计 spine 规范化纯文本字符偏移 / 全书规范化纯文本总字符数`），明确字符规范化规则（剥标签/折叠空白/含不含 metadata），写进 `shared/api` 契约，三端各写对拍测试（同书同位置误差 <0.5%）。

### D-2 [P0] @Serializable 对象不能直接进 SavedStateHandle，§7.2 恢复链按现写法不成立
- **证据**：§7.2 L596 `handle.get<ReaderState>(KEY_STATE)`、L607 `handle[KEY_STATE] = currentState`。
- **问题**：SavedStateHandle 走 Android Bundle/Parcelable，kotlinx `@Serializable` 与之互不相通——`@Serializable` 不会让类型可进 Bundle（需 `@Parcelize` 或先 `encodeToString` 存 String）。P0-A 的「真正可序列化」是 kotlinx 维度，不等于 SavedStateHandle 可存。现示例编译可过、运行期取回 null 或抛异常。
- **建议**：二选一写进文档——(a) `ReaderState` 加 `@Parcelize`；或 (b) `handle["k"]=Json.encodeToString(state)` / `decodeFromString`。`Offset`/`Locator`/`ReadflowError` 同理。

### D-3 [P1] Engine Bundle 塞回 SavedStateHandle，TransactionTooLargeException 风险只是搬家
- **证据**：§7.2 L608 `handle[KEY_ENGINE_BUNDLE] = engine.saveState()`，L583 自承 Bundle 可达数千项分页表、L585 「可丢」。
- **问题**：SavedStateHandle 经 Binder 事务受 ~1MB TransactionTooLarge 限制，分页表正是最易超限的东西。两层拆分本意保 ReaderState 纯净，却把崩溃风险转嫁给 Engine Bundle——可丢的东西更不该占稀缺 Binder 预算。
- **建议**：Engine 加速缓存写本地文件（`cacheDir/engine-state/<bookId>.bin`），SavedStateHandle 只存指向它的轻量 key/校验，缺失即重算。

### D-4 [P1] deviceId 无来源、updatedAt 裸客户端时钟做 LWW 无防漂移
- **证据**：§7.7 L742-744/L754 列了 `deviceId`/`updatedAt: Long` 字段，L729 LWW「updatedAt 决胜」。
- **问题**：(a) 全篇没说 deviceId 怎么生成/持久化（重装清数据后是否变）；(b) updatedAt 是 wall-clock，三端时钟漂移（离线 LAN 无 NTP 平板）下 LWW 会把慢钟设备旧进度判成「最新」覆盖真实较新进度——静默数据丢失。KOSync/korrosync 互通时基准更难对齐。
- **建议**：deviceId 首次启动生成 UUID 持久化于 DataStore（明确「清数据即换 replica」语义）；LWW 引入抗漂移——优先服务端盖时间戳，或 `(updatedAt, deviceId)` 元组 tie-break + 约束最大可信时钟偏移，文档给出规则。

### D-5 [P1] 扩展系统对少人首方编译期场景明显 YAGNI
- **证据**：§8.2 L805 自注「当前为 internal first-party 编译期扩展，非用户插件」，却上整套：Extension SPI + 5 个 ReaderHook + 沙箱 ExtensionContext + 15 种事件 ReaderEventBus + ExtensionSettings + §8.1/P5 整段「扩展防御性读 UI 态」约定。
- **问题**：对编译期首方代码，「沙箱」（R12 拿不到 View）和防御性约定都在防一个不存在的威胁——代码全自己写、可直接重构。TTS/stats/opds 三个已知功能用普通 feature 模块直连 Repository/ViewModel 即可，hook/eventbus 在三个首方消费者下解耦收益为负（多一层间接 + SharedFlow 时序/丢事件调试成本）。
- **建议**：Phase A 砍到最小——TTS/stats/opds 做成普通 `:features:*` 直连仓库；仅留极薄 ReaderEventBus（若确需跨模块通知）。SPI/沙箱/ReaderHook/15 事件推迟到 L805 已提的「真正用户插件 ADR」再引入。

### D-6 [P2] annotations 单表混 ink BLOB + 高亮 + 笔记，宽稀疏表拖累查询
- **证据**：§7.8 L765，`stroke_data BLOB?` 与 `selected_text`/`note` 同表。
- **问题**：列高亮列表时 Room 实体带出大 BLOB，列表滚动/同步序列化成本高；大量列对任一类型 NULL（稀疏）。四表均未提 index。
- **建议**：拆 `ink_strokes`（重 BLOB）与 `text_annotations`（高亮/笔记），或 DAO 做不含 BLOB 的 projection 实体；`book_id` 等同步/查询热列建索引。

### D-7 [P2] bookmarks 无可排序位置列
- **证据**：§7.8 L766，bookmarks 仅 `locator_json` TEXT。
- **问题**：「按书内位置排序书签」要把每条 JSON 反序列化后内存排，书签多时浪费。（进度有 `progress_percent` 兜底，不算问题。）
- **建议**：bookmarks 冗余 `total_progression REAL` 排序列（与 §7.1 主键同源），locator_json 仍存完整细节。

## 三、模块分层 / Gradle / DI（§三 §九 §十）

### M-1 [P0] phase1 include 了 :features:library，但其依赖 :core:ui/:core:database 在 phase2，编译必炸
- **证据**：§10.3 L887-888 `phaseInclude(1, …, ":features:library")`，但 `:core:ui`/`:core:database` 在 L889-891 phase2；§3.2 L144-145 feature 必依赖 :core:ui。
- **问题**：`-Preadflow.phase=1 assembleDebug` 在 `:features:library` 解析 `:core:ui` 时报「project not found」——恰恰打脸 §10.3 自夸的「缺模块报 project not found」。Phase A「最近阅读 + 进度本地保存」也需持久化层。
- **建议**：要么 `:core:ui` 提到 phase1，要么 `:features:library` 降到 phase2（phase1 用 `:app` 承载最小 UI）；并澄清 phase1「进度本地保存」走 `:core:prefs` 还是 `:core:database`。

### M-2 [P1] PageTransitionHostFactory 未在任何层/模块声明，reader 依赖合规性无据
- **证据**：§9.2 L848 `ReaderViewModel` 注入 `PageTransitionHostFactory`；§3.1/§3.2 只列 `PageTransitionHost`（`:render:api`）和 `:render:animate`（实现），Factory 归属未交代。
- **问题**：若 Factory 是 `:render:api` 接口则 reader→render:api 合规；若泄漏 `:render:animate`（Layer 3 实现）则违反 L145「feature 不得直接依赖 render 实现」。
- **建议**：§3.2 L141 `:render:api` 类型清单显式加入 `PageTransitionHostFactory`，§9.3 注明 reader 仅依赖该接口。

### M-3 [P1] SyncManager 出现在 DI 但不在模块地图
- **证据**：§9.2 L849 `ReaderViewModel` 注入 `SyncManager`；22 模块只有 `:core:sync` 的 `SyncBackend`/`NoOpSyncBackend`，无 `SyncManager`。
- **问题**：归属模块未定义，实现时可能塞进错误层。
- **建议**：明确 `SyncManager` 落 `:core:sync` 还是 feature 内，补进清单。

### M-4 [P2] 22 模块对少人团队偏细，多个超薄模块可合并
- **证据**：§3.1 L106-133。
- **建议**：`:core:sync`（2 文件）→ 并入 `:extensions:api` 或 `:core:prefs`；`:render:animate`（唯一 host 实现，与 reader 强耦合）→ 并入 `:render:api` 或 `:features:reader`；`:render:md`（Markwon，体量小）→ 并入 `:render:txt` 归文本族；`:extensions:stats`/`:opds`（phase3 optional）可暂缓建模块。由 22 收敛到约 16-17，分层不变。

### M-5 [P2] §3.3 约束2「不得含接口行为类型」不可机械 grep
- **证据**：§3.3 L149-155。
- **问题**：约束 1/3/4/5 可脚本化，但「区分纯数据 interface 与行为 interface」无法靠 grep 判定，CI 形同虚设。
- **建议**：约束 2 收紧为可执行规则（`:core:model` 不得出现 `interface`/`suspend`/返回 `Flow` 的 fun）。

### M-6 [P2] build-logic 4 plugin 基本值得，但「待创建」跨 phase1 是漂移温床
- **证据**：§10.2 L862-871，承认 build-logic 尚未创建、各模块手写重复配置。
- **建议**：phase1 至少先落 `ReadflowAndroidLibraryPlugin` + `ReadflowComposePlugin`，别让「待创建」状态跨越 phase1。

## 四、安全 / 无障碍 / 性能 / 定位（§一 §二 §12.6-12.11）

### X-1 [P0] jsoup 解析攻击面只防 zip slip，未防 XML 解析炸弹
- **证据**：§12.6 L996，声称「无 JS 执行面」，安全焦点全压 zip slip + 体积上限。
- **问题**：EPUB 内 XHTML/OPF/NCX 是攻击者完全可控 XML。jsoup 不解析 DTD（缓解 XXE/billion laughs），但对**超深嵌套标签**无内建保护——万级嵌套 `<div>` 会 `StackOverflowError`。§12.6 未提。
- **建议**：明确「OPF/NCX 用关闭 DTD/外部实体的解析器 + XHTML 设嵌套深度上限 + 解析在 catch `StackOverflowError`/`OOM` 的隔离作业里」，作为 EPUB scorecard 安全面强制项。

### X-2 [P1] 超大/恶意图片 BitmapFactory OOM 未进安全矩阵
- **证据**：§12.6 L996 只管解压体积；§12.8 L1012 内存预算。
- **问题**：EPUB 内嵌图片像素尺寸攻击者可控（如 30000×30000 PNG），直接 `decodeStream` 瞬间撑爆内存 OOM。
- **建议**：写明「图片先 `inJustDecodeBounds` 读尺寸 → 设像素上限拒绝/降采样 `inSampleSize` → 受控 stream 解码」，与 §12.8 内存预算挂钩。

### X-3 [P1] EPUB 内嵌外部 URL 自动回连（SSRF/隐私指纹）未识别
- **证据**：§12.6 L996 只说「外部链接交系统浏览器并二次确认」——那是用户点击的导航链接。
- **问题**：XHTML 里 `<img src="http://...">`、CSS `@import`、`<link>` 在渲染时可能被自动加载，构成离线阅读器隐性回连（暴露 IP/阅读行为，LAN 场景可探测内网）。
- **建议**：默认**不**自动加载 EPUB 内任何远程资源（只渲染包内资源），远程需用户显式开启——契合原则 4 离线优先。

### X-4 [P1] AnnotatedString 的 TalkBack 语义声明了目标没给机制
- **证据**：§12.7 L1002「AnnotatedString 链接可聚焦、文本可线性朗读」是验收目标。
- **问题**：Compose `BasicText(AnnotatedString)` 默认不会把内嵌可点击 span 暴露成独立可聚焦无障碍节点；需显式 `LinkAnnotation`（Compose 1.7+）或 `Modifier.semantics`，自研分页的朗读顺序由谁保证也没写。少人团队若到验收期才发现要返工渲染层。
- **建议**：ADR-EPUB-Engine 把「链接用 LinkAnnotation 承载 + 段落级 semantics 节点 + 朗读顺序=重排流顺序」写成实现约束，确认 catalog Compose 版本支持 LinkAnnotation。

### X-5 [P1] EPUB 内存预算<100MB 对全书解析成 ReaderItem 序列不可达且无测量口径
- **证据**：§12.8 L1012。
- **问题**：若一次性把整书解析驻留成 ReaderItem 列表，中等 EPUB（含图片/数万段落）的 AnnotatedString+SpanStyle 对象图易超 100MB；且没说 100MB 是 PSS/Java heap/native 哪种、在多大书上测。指标不可测即非 gate。
- **建议**：(1) 明确改为按 spine 章节惰性解析 + LRU 驱逐（只驻留当前+预取章节），否则预算与架构自相矛盾；(2) 把 100MB 定义为「打开 X MB EPUB 后 Java heap PSS 峰值」并固定样本。

### X-6 [P2] network_security_config 仅放 192.168/16，对 10.x/172.16 局域网过严
- **证据**：§12.6 L995。
- **问题**：RFC1918 含 `10.0.0.0/8` 与 `172.16.0.0/12`，很多路由器/Calibre 部署在这两段，只放 192.168/16 会让这些用户 cleartext 走不通，属难自查的「连不上」，与 Phase B「失败有原因」冲突。
- **建议**：放行全部三段 RFC1918 cleartext（仍排除公网强制 HTTPS），或向导探测阶段按用户实际 baseUrl 动态判定 + 明确提示。

### X-7 [P1] 测试矩阵全套对少人团队偏奢侈，未分必须/可选
- **证据**：§12.11 L1028 罗列覆盖全 22 模块的测试矩阵，未标 Phase 优先级。
- **建议**：分层——**必须**：`:core:model` 序列化往返、`:render:txt`/EPUB 解析的字符边界与分页定位单测（纯 JVM fakes，最高 ROI）；**重要**：`:core:calibre` Ktor MockEngine、`:core:database` Room in-memory；**可推后**：`:features:*` Compose Testing、`:render:*`/`:ink` Instrumentation（设备依赖重，进 Phase C/E）。Turbine 仅在确有 Flow 时序断言时用。

### X-8 [P2] KMP 预留可能为不发生的迁移牺牲当下
- **证据**：§12.10 L1024，已诚实声明「共享走 JSON Schema 契约非代码共享，评估点不早于 2027」，HarmonyOS 又是 ArkTS。
- **问题**：KMP 代码共享在可见未来无真实消费方。若「预留」只是「写 model 时不用 JVM-only API」的自律，成本近零可接受；若已反映在模块划分/依赖约束上则是付当下利息。
- **建议**：把「预留」降级为一行编码约定（避免 JVM-only API、用 kotlinx.serialization），明确不引入 multiplatform 插件/源集结构，等 2027 有真实需求再付迁移成本。

### X-9 [P1] 原则7「每段代码照抄编译通过」与文档现状内部矛盾
- **证据**：§一 L67。
- **问题**：多处是目标声明而非可编译契约——§12.7 链接可聚焦没给 LinkAnnotation/semantics 代码、§12.6 XML 安全没给解析器配置代码、§12.8 内存预算无可测定义。原则越强，未兑现处越显眼，易让团队误以为「文档写了=可实现」。
- **建议**：要么把原则 7 限定为「标注为『规范实现』的代码块保证可编译」，要么补齐上述三处最小可编译/可测形态。

### X-10 [P0] 全局风险错配：完备框架先行，核心 EPUB 引擎质量后置
- **证据**：§二 L75-91 五条契约 + §3.1 22 模块 + §5.3 7 引擎可插拔 + Registry 权重/override/懒加载（§5.2）。
- **问题**：Phase A 其实只需 1~2 引擎（TXT+EPUB）+ 本地导入 + 最近阅读 + 进度本地存——克制可达的 MVP。但文档把「可插拔 7 引擎 + Registry 权重发现 + override 快照 + descriptor 懒加载」当地基先建，真正最难最不确定的自研 EPUB 重排（解析正确/分页定位/无障碍/图片内存/解析安全）被一个 ADR 一笔带过。完备度走前面，决定「精品」成败的引擎质量走后面。
- **建议**：明确 Phase A 只落 EPUB+TXT 两引擎、Registry 起步退化为简单 if/else（接口预留但不先建权重/override/懒加载），把节省精力压到 EPUB 重排四件难事上。先证明能把一种格式做到精品，再让可插拔体系生长出来。

## 五、按严重度汇总（建议处置）

| 编号 | 严重度 | 一句话 | 建议 |
|------|--------|--------|------|
| R-1 | P0 | EPUB 分页 ViewPager2 vs HorizontalPager 双重矛盾 | 二选一统一，推荐 ViewPager2 host |
| R-2 | P0 | ComposeView ViewTree*Owner 挂载无人认领 | §5.1 补 Compose interop 契约 |
| D-1 | P0 | totalProgression 三端无统一计算口径 | §7.1 钉死规范化定义 + 对拍测试 |
| D-2 | P0 | @Serializable 不能直接进 SavedStateHandle | @Parcelize 或 encodeToString |
| M-1 | P0 | phase1 include library 但依赖在 phase2 必炸 | 调 phase 边界 |
| X-1 | P0 | jsoup XML 解析炸弹未防 | 关 DTD + 嵌套深度上限 + 隔离作业 |
| X-5 | P0 | EPUB<100MB 不可达且无测量口径 | 改惰性解析 LRU + 定义 PSS 口径 |
| X-10 | P0 | 全局风险错配：框架先行引擎后置 | Phase A 砍到 2 引擎 + Registry 退化 |
| R-3,R-4,R-5 | P1 | 重排工时低估/InkAnchor cssSelector 遗留/search() 缺失 | 降级表 + 换锚点字段 + 补接口 |
| D-3,D-4,D-5 | P1 | Engine Bundle 超限/LWW 时钟漂移/扩展 YAGNI | 写文件 + tie-break + 收敛扩展 |
| M-2,M-3 | P1 | HostFactory/SyncManager 不在模块地图 | 补归属 |
| X-2,X-3,X-4,X-7,X-9 | P1 | 图片 OOM/外部回连/TalkBack 机制/测试分层/原则7矛盾 | 各见正文 |
| R-6,R-7,R-8,D-6,D-7,M-4,M-5,M-6,X-6,X-8 | P2 | 见正文 | — |

**与既有裁决的关系**：D-1（totalProgression 口径）是 E2 落地的必要补全；D-2（SavedStateHandle 机制）是 P0-A/P2 恢复链的运行期前提；R-4（InkAnchor cssSelector）是 E1 删 CFI 时漏清的同源遗留；R-1/R-2/R-5 是 E1 去 WebView 后渲染契约尚未收口的新缺口。建议优先消解 8 个 P0，其中 R-1、R-4、R-5 是文档内部直接打架的契约，X-10 是定位层的方向性建议。


