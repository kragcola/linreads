# 外部对标审计：v4 架构 vs 成熟项目与前沿实现

> **类型**: 外部对标审计（external benchmark audit）
> **日期**: 2026-06-19
> **审计对象**: `docs/android-architecture-v4.md`
> **方法**: 以成熟开源阅读器（KOReader / Readium Kotlin Toolkit / Librera）、现代 Compose 阅读器（Book's Story / Myne / readest）、前沿 WebView EPUB 库（epub.js / @likecoin/epub-ts / foliate-js）的真实架构、源码、issue 为参照，比对 v4 的技术路线。区别于前两轮 v3 框架审计（内部自洽性 / 可实现性），本轮只问一个问题：**v4 的路线在外部成熟实践里站得住脚吗？**
> **结论速览**: 路线整体与主流一致（统一引擎接口、Hybrid 原生 View、Locator 多策略、PDF 走系统引擎、MuPDF optional）。但有 **1 个 P1 级路线偏离**（EPUB 内容服务用 nanohttpd，前沿已全面弃用本地 HTTP server）、**1 个 P1 级失效兜底**（epub-ts fallback 指向已废弃的 epubjs），以及 3 个 P2 增强项。
> **裁决结果（2026-06-19，用户）**: 两个 P1 合并解决——**Android EPUB 放弃 WebView 整条路线，改自研原生重排**（jsoup→AnnotatedString）。用户指出 WebView 是 Web 端「单文件即插即用」路线被带进安卓的惯性，安卓应走原生。nanohttpd/CSP/JS bridge/CFI/epub-ts 一并移除。已落入 v4 §5.5、§12.3 ADR-EPUB-Engine、裁决 E1。P2-EXT-3（Locator 同步主键）→ E2 已落；P2-EXT-4（KOSync）→ E3 已落为可选后端。
>
> **注**: 本审计正文以「审计时」视角记录 P1-EXT-1/2 的原始发现（针对当时的 WebView+epub-ts 方案）。这些发现现已被 E1 裁决一并解决，正文保留作决策溯源。

## 一、参照系（成熟项目真实架构）

| 项目 | 性质 | EPUB 渲染 | 引擎抽象 | 定位模型 | 备注 |
|------|------|----------|---------|---------|------|
| **KOReader** | Lua + 多 native 引擎，e-ink 多年验证 | crengine（原生重排） | `DocumentRegistry`：extension/mime 注册 + weight 排序 + per-doc/per-type **用户 override** + 引用计数 | **XPointer**（DOM 指针），非页码 | EPUB→crengine、PDF/DjVu→MuPDF、k2pdfopt 做 PDF 重排 |
| **Readium Kotlin Toolkit** | EDRLab 官方级框架，3.x | **WebView**（reflowable/fixed） | `Navigator` + 能力接口分层（Visual/Overflowable/Selectable/Decorable），按 **publication profile** 分派 | **Locator**：progression + position + cssSelector，CFI 降级为可选 `partialCfi` | PDF 走 adapter（Pdfium/PSPDFKit）注入；3.x **移除本地 HTTP server** |
| **Librera** | C + MuPDF | MuPDF native（非 WebView） | 单引擎吃多格式 | 页/reflow | 格式覆盖极广 |
| **Book's Story / Myne** | Kotlin + Compose | 自研 parser → **Compose 原生文本** | 无统一引擎注册 | 文本偏移 | 解析器质量是长期痛点（book-story #42） |
| **readest** | Tauri + React | **foliate-js**（WebView/JS） | foliate-js book/renderer 接口 | Range/byte fraction | Zustand 多 store，非 MVI |

来源：KOReader DeepWiki 5.1/5.2、Readium `docs/guides/navigator`、kotlin-toolkit PR #259、foliate-js README、book-story #42。

## 二、v4 路线 vs 外部实践逐项比对

### ✅ 与主流一致 / 已被验证的决策

| v4 决策 | 外部佐证 | 评价 |
|---------|---------|------|
| **统一 `ReaderEngine` 接口 + `EngineDescriptor` 按格式注册**（§5.1/5.2） | KOReader `DocumentRegistry`、Readium `Navigator` 接口家族都是这个形态 | 站得住。这是成熟项目主流，不是过度设计。 |
| **per-format 用户 override**（§5.2，priority + `userOverrides`） | KOReader 三级优先（per-doc > per-type > weight）是同一思路 | 站得住。v4 做了 per-format（per-type）级，比 KOReader 少 per-doc 级，对自用产品够用。 |
| **每格式独立引擎实现，不强求一个引擎吃所有格式**（§5.3） | KOReader（crengine/MuPDF/DjVu 分立）、Readium（EPUB WebView / PDF adapter 分立）均如此；Librera 的「单引擎吃多格式」是少数派且靠重 native | 站得住，且优于单引擎路线。 |
| **PDF 走系统 PdfRenderer 而非 MuPDF**（§12.5） | Readium PDF 也不内置引擎、用 Pdfium adapter；MuPDF AGPL 强 copyleft 对个人开源项目是真实负担 | 站得住，license 动机正确。 |
| **MuPDF（DOCX/CBZ）标 optional 不进 base APK**（§5.7/V8） | MuPDF AGPLv3 = 全 App 必须开源否则买商业授权；隔离它既省体积也隔离 license 风险 | 站得住，且 license 隔离动机被外部证实。 |
| **Locator 多策略（Cfi/Page/ByteOffset/Section）**（§7.1） | Readium Locator 正是多 location 并存（progression/position/cssSelector/partialCfi/domRange） | 方向对。但**主次排序**需调整，见 P2-2。 |
| **进度用稳定锚点而非页码** | KOReader XPointer、Readium position list 同理由：reflowable 页码随字号变 | 站得住。 |
| **Hybrid 原生 View 渲染文档、Compose 只做 chrome**（§4.1） | Readium 的 Navigator 也是 Fragment/原生 View，Compose 集成需 `AndroidView` 桥接（社区反馈重） | 站得住，且避开了 Readium 的 Compose 桥接痛点。 |
| **翻页动效在 UI 层（`PageTransitionHost`）而非塞进引擎**（§5.6） | 通用最佳实践：引擎只管「给定位置出像素/HTML」，交互/动画归 UI 层 | 站得住。 |

### P1-EXT-1 — EPUB 内容服务用 nanohttpd，是前沿已弃用的老路

**v4 现状**（§5.5/§12.6/V9）：EPUB 用本地 nanohttpd server 把解压资源喂给 WebView，并花了大量篇幅做安全收敛（loopback only、随机端口、path token、关书停服）。

**外部事实**：
- **Readium 2.x→3.x 主动移除了本地 HTTP server**。PR #259「Remove the need for an HTTP server」+ 迁移指南明确写「The local HTTP server is not needed anymore… 可删除所有 `Server` 与 `baseUrl`」。改用 `WebViewClient.shouldInterceptRequest()` + 自定义 origin（`https://readium`）拦截，并通过 `htmlInjector` 回调在 HTML 进 WebView 前注入 ReadiumCSS/JS。
- **Google 官方推荐 `WebViewAssetLoader`**，把内容映射到 `https://appassets.androidplatform.net/...` 这个**同源 HTTPS secure context**，避开 `file://` 陷阱（issue #34 Readium 也跟踪迁到它）。
- **nanohttpd-on-loopback 的残余风险无法靠收敛根除**：loopback 对**设备上任何其它 App** 仍可达（无 per-app 隔离），比 in-process 拦截弱。v4 的随机端口 + path token 是缓解，但本质是在给一个不必要的开放 socket 打补丁。
- nanohttpd 仍有一个真实优势：**Service Worker 只在 http(s) 下工作**，scheme 拦截不触发 SW 的 fetch。但 epub-ts/epubjs 渲染**不依赖 Service Worker**，这个优势对本项目无意义。

**评级 P1（路线偏离，非 bug）**：v4 选了一条成熟框架已经主动走出来的路，并为此承担了一整套本可避免的安全收敛工作。**建议**：EPUB 内容服务改为 `WebViewAssetLoader`（或自定义 origin 的 `shouldInterceptRequest`），删除 nanohttpd 与 §12.6/V9 的端口/token 安全收敛——这些工作随 server 一起消失。CSP 注入点改用 Readium 式 `htmlInjector` 回调。这不仅更安全，还更省代码。

> 注意：这只针对 **Android EPUB 本地渲染服务**。Calibre LAN 直连（`network_security_config.xml` 192.168/16 cleartext）是另一回事，与本条无关，保留。

### P2-EXT-3 — Locator 主次排序应对齐 Readium（progression/position 主，CFI 兜底）

**v4 现状**（§7.1）：Locator 五策略 `Cfi/Page/ByteOffset/Section/Unknown` 平级并列，EPUB 默认走 `Cfi`。

**外部事实**：Readium 刻意把 CFI **降级**为 HTML location 下的可选字段 `partialCfi`，主定位语言是 `progression`（资源内 0–1）+ `position`（出版物内整数索引）+ `cssSelector`。理由：CFI 在改字号后漂移（epubjs #1194 实证），且跨引擎/跨端互操作性差。KOReader 同样用 XPointer + percentage 而非 CFI。三端进度同步（本项目核心诉求）若以 CFI 为主键，Web(epubjs CFI) 与未来原生引擎的 CFI 未必可互换。

**建议**（P2，影响同步设计而非编译）：Locator 增加 `progression: Float`（资源内）与 `totalProgression: Float`（全书）作为**跨端同步主键**，CFI 退为 EPUB 引擎内精确恢复的实现细节。这与 `linreads-sync` 的 LWW 设计正交但互补——同步比对用 progression，本端精确跳转用 CFI。KOSync 协议也正是用 `percentage = cur/total`，对算法宽容，只要本端内部一致即可跨端对齐。

### P2-EXT-4 — 进度同步可评估直接兼容 KOSync 协议

**v4 现状**（§7.6/§7.7）：Phase 2 主路径 `KorroSyncBackend`（REST，对标 korrosync）+ `WebDavSyncBackend` 兜底，LWW + Union。

**外部事实**：KOReader 的 **KOSync** 是已有成熟生态的进度同步协议——REST+JSON、`X-Auth-User`/`X-Auth-Key`(=MD5(password)) 鉴权、`PUT/GET /syncs/progress`、LWW(按 timestamp)、客户端 ~25s 防抖、文档 ID 用 filename-MD5 或 partial-MD5 内容指纹。已有自托管 server 可直接复用。

**建议**（P2，增强非必须）：`SyncBackend` 接口已是可插拔的，**新增一个 `KoSyncBackend` 作为可选后端**几乎零架构成本，却能让本项目与 KOReader 生态互通（用户可能已在跑 koreader-sync-server）。v4 的 ~2s debounce 与 KOSync 的 ~25s 防抖思路一致，可统一。这不取代 korrosync，只是多一个互通选项。

### P2-EXT-5 — Compose 阅读器主流走「自研 parser + 原生文本」，可记录为未来 TXT/MD 增强参照

**外部事实**：Book's Story / Myne 这类现代 Compose 阅读器不走 WebView，而是自研 EPUB parser → Compose 原生文本渲染。代价是解析器质量长期痛点（book-story #42 在重写解析器）。

**评价**：v4 EPUB 走 WebView 是对的（排版保真 + 复用 Web 端栈），**不建议**改原生文本路线（会扛 HTML+CSS 排版与解析器维护的长期债，与「少人精品」人力不符）。但 v4 的 **TXT 自研 `TxtVirtualPager`（§5.4）与 MD（Markwon 原生）已经是「原生文本」路线**——这两个格式的方向与 Compose 主流一致，无需改。仅记录：若未来要给 TXT/MD 加排版增强（分栏、断词），Compose Text + 自研分页是有先例的路线。

## 三、汇总与建议优先级

| 编号 | 级别 | 问题 | 建议 | 影响面 |
|------|------|------|------|--------|
| P1-EXT-1 | P1 路线偏离 | EPUB 内容服务用 nanohttpd，前沿（Readium 3.x）已弃用本地 HTTP server | 改 `WebViewAssetLoader` / 自定义 origin 拦截 + `htmlInjector` 注入；删 nanohttpd 与 §12.6/V9 端口安全收敛 | §5.5、§12.6、V9、ADR-EPUB-Engine |
| P1-EXT-2 | P1 兜底失效 | epub-ts 失败兜底指向已废弃且同渲染模型的 epubjs | 兜底改分级：先查内容服务层 → foliate-js → Readium；删除「回退裸 epubjs」 | §12.3 ADR-EPUB-Engine |
| P2-EXT-3 | P2 增强 | Locator 五策略平级，CFI 当主键不利跨端同步 | 增 `progression`/`totalProgression` 为同步主键，CFI 退为引擎内细节 | §7.1、linreads-sync |
| P2-EXT-4 | P2 增强 | 未利用成熟 KOSync 生态 | 加可选 `KoSyncBackend` 互通后端（接口已支持，近零成本） | §7.6 |
| P2-EXT-5 | P2 记录 | — | TXT/MD 原生文本路线已与 Compose 主流一致，记录备查；EPUB 维持 WebView 不改原生 | 无需改 |

## 四、总评

v4 的架构路线在外部成熟实践里**整体站得住**：统一引擎接口、每格式独立实现 + 用户 override、Hybrid 原生 View、PDF 走系统引擎、MuPDF optional、稳定锚点进度——这些都与 KOReader / Readium 的成熟选择同向，且 v4 在若干处（避开 Readium 的 Compose 桥接痛点、PDF 避开 MuPDF AGPL）做了合理取舍。

唯二需要正视的是 **EPUB 内容服务的 nanohttpd 路线**（Readium 已经替全行业趟过并主动退出，v4 不必重走并为之做一整套安全收敛）和 **epub-ts 的失效兜底**（不能退回与它共享渲染模型且已废弃的 epubjs）。这两条都集中在 EPUB WebView 子系统，且互相关联——改用同源 HTTPS 内容服务后，CSP/blob 冲突缓解，反过来让 foliate-js 重新成为可行兜底、也提高 epub-ts 过 gate 的概率。**建议在 ADR-EPUB-Engine 的真机验证 gate 里，把「内容服务用 WebViewAssetLoader 而非 nanohttpd」作为前置条件一并验证。**

P2 三项（Locator 同步主键、KOSync 互通、TXT/MD 路线确认）是增强与确认，不阻塞实现。

---

> **关联文档**：`docs/android-architecture-v4.md`、两轮 v3 框架审计 `docs/audit/android-v3-framework-audit*.md`、`.claude/skills/linreads-epub/SKILL.md`、`.claude/skills/linreads-sync/SKILL.md`。
> **方法声明**：本审计为路线对标，结论基于外部项目的公开源码/文档/issue（KOReader DeepWiki、Readium 官方文档与 PR #259/#34、foliate-js README、epubjs issues #1110/#1194/#1406、epub-ts README benchmark）。未在本项目真机上复现 epub-ts 的 WebView 行为——这正是 ADR-EPUB-Engine gate 要做的事。


### P1-EXT-2 — epub-ts 的失败兜底指向已废弃的 epubjs

**v4 现状**（ADR-EPUB-Engine §12.3）：epub-ts 若过不了真机 WebView+CSP gate，「回退裸 epubjs」，foliate-js 因 blob 同源 + CSP 冲突被排除。

**外部事实**：
- **epubjs 已实质废弃**：~3 年无实质提交，官方 issue #1406「Is this project abandoned?」确认；`locations.generate()` 在 1.7MB 书上阻塞主线程 **~43 秒**（#1110/#1405 长期未解）；CFI 在改字号后定位漂移（#1194，2025 仍 open）。
- **epub-ts 正是 epubjs v0.3.93 的 fork**，继承同一套 iframe + CSS multi-column 渲染模型，**CFI 漂移与 CSS 怪癖这类 bug 一个都没修**；它修的是 parse/locations/bundle（43s→159ms 的 270x 是真实 benchmark，但只在桌面 Chrome 测过，**无任何 Android WebView 验证**）。
- 所以「回退裸 epubjs」= 回退到一个比 epub-ts 更慢（locations 慢 270x）、且同样有 CFI 漂移、且已无人维护的库。**这不是兜底，是退到更差的同源版本。**

**评级 P1（兜底失效）**：gate 失败的真正风险在于「iframe+CSP+blob 同源在 Android WebView 不工作」，而 epubjs 与 epub-ts **共享这个渲染模型**，所以 epubjs 大概率同样过不了 gate——兜底无效。**建议**：把失败兜底从「裸 epubjs」改为分级：
1. 首选仍 epub-ts（locations 优势真实，且与 Web 端类型契约对齐）。
2. gate 失败先排查**内容服务层**（若已按 P1-EXT-1 改 WebViewAssetLoader，同源 HTTPS context 能解掉相当部分 blob/CSP 冲突——这正是 foliate-js 被排除的那个理由可能消失）。
3. 真正的备选引擎应是 **foliate-js**（Foliate/readest 的现役引擎，无 deps、scrolled⇄paginated 不重载、安全模型成熟），而非倒回 epubjs。foliate-js 的 blob 同源问题恰恰是 WebViewAssetLoader 同源方案能缓解的。
4. 最重，但**架构最稳的参照**是 Readium kotlin-toolkit（唯一经实战验证的 Android WebView EPUB 方案）。若 EPUB 是核心且要长期维护，至少照抄它的「自定义 origin 拦截 + 严格 CSP + script execution prevention」三件套。


