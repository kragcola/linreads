# LinReads Android v3 框架审计（第二轮 · 全维度）

> 日期：2026-06-18
> 审计对象：`docs/android-architecture-v3.md`（1791 行，权威规范）
> 定位校准：少人精品 / 用户侧轻量 / 开发侧可接受中重度
> 与上一轮关系：上一轮（`android-v3-framework-audit-2026-06-18.md`）建立了「用户轻量契约」框架，本轮聚焦**自洽性 + 可实现性 + 运行时正确性**，findings 力求与上一轮不重复。

---

## 一、结论摘要

**裁决：方向成立，但文档存在 3 个会直接导致编译失败的硬伤，和 1 组跨文档矛盾，必须在 Phase 1 落地前修。**

v3 的骨架（Hybrid View / ReaderEngine / 纯数据 State / Locator 统一）是正确的，与「精品阅读器内部可复杂、用户侧要轻」的定位匹配。但作为一份「实现必须以本文档为准」的权威规范，它现在有几处**照抄就编译不过**的代码，以及若干运行时正确性盲区。对少人团队来说，这类硬伤的代价不是「难维护」，而是「按文档写完发现跑不起来，再回头改文档」——直接消耗精品团队最稀缺的资源：注意力连续性。

定位校准说明：「轻量」指用户侧（安装、首开、阅读、配置、迁移、权限），不指开发侧。21 模块、自研引擎、复杂分层均不在本轮批评范围（上一轮已做此修正）。本轮重心是两件上一轮未充分覆盖的事：

1. 设计文档本身的可实现性 / 自洽性（编译期就会爆的硬伤）。
2. 运行时正确性契约（线程、生命周期、并发、序列化）。

## 二、评分

| 维度 | 分数 | 判断 |
|------|-----:|------|
| 框架方向 | 3.6 / 4 | Hybrid View + 引擎抽象 + 纯数据 State 主线稳固 |
| 文档自洽性 | 2.0 / 4 | epubjs/epub-ts 跨文档矛盾、版本漂移、模块计数与实际不符 |
| 可实现性（照抄能否编译） | 1.8 / 4 | `@Serializable` + `Throwable` 三处硬伤、ReaderState 实际不可序列化 |
| 运行时正确性契约 | 2.0 / 4 | 线程/Dispatcher 模型缺失、Registry 非线程安全、WebView 主线程约束未对齐 suspend |
| 用户轻量性 | 2.8 / 4 | 上一轮契约方向正确，仍待落为验收项（不重复展开） |
| 安全/隐私 | 2.4 / 4 | nanohttpd 本地服务端口/token 未定义，CSP 与 epub-ts 需求未验证 |
| 测试可落地性 | 2.5 / 4 | 策略表完整，但纯数据层因序列化硬伤无法按描述测 |
| **总分** | **17.1 / 28** | **有条件通过：先修 P0 自洽/可实现性硬伤，再开 Phase 1** |

## 三、本轮新增 Findings（按严重度）

### [P0-A] `@Serializable` 套 `Throwable` — 三处，照抄编译不过 / 运行时崩

**位置**：§6.1.3 `ReadflowError`（`Network`/`Database`/`Parse`/`Unknown` 均含 `override val cause: Throwable?`）

`kotlinx.serialization` 无法为 `Throwable` 生成序列化器。`@Serializable sealed class ReadflowError(... open val cause: Throwable?)` 在 KSP/编译期就会报 *"Serializer has not been found for type 'Throwable'"*。这是确定性失败，不是 edge case。

连锁影响：

- §6.1.2 `ReaderState.error: ReadflowError?` → **`ReaderState` 实际上不可 `@Serializable`**，而 §11.1 R1 和 §4.7 又承诺它支撑「进程死亡存活 / `saveState`」。整条进程恢复链建立在一个编译不过的前提上。
- §11.9 测试策略写「`:core:model` 测所有数据类、序列化」——这条测试本身会先挂。

**修**（与上一轮「DTO/Exception 两层」方向一致，给可落地版本）：

```kotlin
@Serializable
data class ReadflowError(
    val kind: Kind,
    val message: String,
    val code: Int? = null,        // Network
    val resourceType: String? = null, val id: String? = null,  // NotFound
    val format: String? = null,   // Unsupported
) { enum class Kind { NETWORK, DATABASE, PARSE, NOT_FOUND, UNSUPPORTED, AUTH, UNKNOWN } }
```

运行时 `Throwable` 不进模型，只在 log/crash 上报时携带。这样 `ReaderState` 才真能序列化，R1/§4.7 的承诺才成立。

### [P0-B] EPUB 引擎跨文档矛盾：epubjs vs epub-ts

**位置**：`docs/audit/decisions-2026-06-18.md` 决策 1（**「默认使用 epubjs 0.3.x」**，foliate-js 备选）↔ v3 §4.6 / §11.1 R8 / §11.3（**`@likecoin/epub-ts`**）

同一天的两份权威文件给出相反的引擎裁决。CLAUDE.md 的口径是「Web 现为 epubjs，Android v3 目标为 epub-ts」——但 decisions 文档决策 1 的措辞是无平台限定的「默认使用 epubjs」。对少人团队，这种矛盾会在真正开始写 `:render:epub` 时引爆一次返工。

更要紧：v3 把 `@likecoin/epub-ts` 当成既定事实写进权威规范，但 Step 0 纪律要求「用到某库特性先查锁定版本」。`epub-ts` 不在任何 Gradle/npm 锁文件里（它是 JS 库，打进 WebView assets），其**与 Android WebView 的 CSP（§11.5 `script-src 'self' 'unsafe-inline'`）、Shadow DOM、blob/iframe 同源行为从未在本仓库验证过**。decisions 文档恰恰因为 blob URL 同源 + CSP 冲突否决了 foliate-js——同类风险对 epub-ts 未做同等审查。

**修**：单独开一条 ADR 定夺 epubjs vs epub-ts，明确「Web 与 Android 是否必须同引擎」，并在 ADR 里记录 epub-ts 在 Android WebView + 上述 CSP 下的最小验证结论（真机/真 EPUB，不能靠记忆）。两份文档收敛到同一结论。

### [P0-C] 线程 / Dispatcher 模型完全缺失，且与 WebView 主线程约束冲突

**位置**：§4.1 `ReaderEngine`、§3、全文

`ReaderEngine` 把 `openBook`/`goTo`/`setFontSize`/`close` 全标 `suspend`，文档说「重活（解析、索引）在这里做」。但：

- WebView 的几乎所有方法（`loadUrl`、`evaluateJavascript`、`rendition.display`）**必须在主线程调用**。一个 `suspend fun goTo()` 若在 IO dispatcher 被调用再去碰 WebView，直接抛 *"All WebView methods must be called on the same thread"*。
- PdfRenderer **非线程安全**，`openPage` 不能并发；TXT 的 `FileChannel` 扫描要在 IO；MuPDF JNI 有自己的线程约束。

文档没有任何一句规定「哪个 engine 方法跑在哪个 dispatcher」。对一个把渲染统一到接口的框架，这是接口契约的核心缺口。照现状各引擎实现者会各写各的，必崩。

**修**：在 §4.1 接口契约里补一段 threading contract，例如：「所有 `ReaderEngine` 方法由调用方在 `Dispatchers.Main` 上调用；引擎内部自行 `withContext(IO)` 处理重活后切回主线程更新 View。」并据此说明 `ReaderViewModel` 的调用约定。

### [P1-D] `ReaderEngineRegistry` 非线程安全

**位置**：§4.2

`userOverrides = mutableMapOf<...>()` 是普通 HashMap，而 `resolve()` 是 `suspend`、`setUserOverride`/`clearUserOverride` 可能从 Settings 协程并发调用。`single` scope 下全 app 共享一个实例。并发读写 HashMap → 可能 `ConcurrentModificationException` 或读到脏数据。少人团队最难复现的就是这种偶发并发 bug。

**修**：换 `ConcurrentHashMap` 或用 Mutex 包写操作；override 本就该持久化（KOReader per-type override 是持久的），建议直接由 `SettingsRepository` 提供 `StateFlow<Map<BookFormat, String>>`，Registry 只读快照。

### [P1-E] Koin `getAll<ReaderEngine>()` 触发全引擎实例化，冲击冷启动预算

**位置**：§4.3、§8.1、§11.8（冷启动 < 2s、APK < 25MB）

`single { ReaderEngineRegistry(getAll<ReaderEngine>().toSet()) }` 会在 Registry 首次创建时实例化**全部 7 个引擎**。即便构造函数轻，`MuPdfEngine` 所在模块的 JNI `.so`、Markwon、nanohttpd 等可能在类加载/构造期被牵连初始化。这与上一轮「重能力按需装载」和冷启动预算直接打架，也和 §4.1「重活放 openBook 不放构造函数」是同一意图的延伸——但 Registry 的 eager 收集把它破坏了。

**修**：Registry 收集的应是 `Provider<ReaderEngine>` / 工厂 / 轻量描述符（id + format + priority + supports 判定），真正实例化推迟到 `resolve()` 命中后。MuPDF/Ink 等重引擎进一步用 feature flag 或 lazy module。

### [P1-F] `SyncBackend` 放进 Layer 0 `:core:model`，污染纯数据层

**位置**：§6.3、§2.2（Layer 0 描述里列了 `SyncBackend`）

`:core:model` 定位是「纯数据值对象、零行为、未来零成本迁 KMP commonMain」。把 `SyncBackend`（一个带 `suspend` 网络语义的行为接口）和它的 `NoOpSyncBackend` 实现塞进 Layer 0，违反该层自我定义。上一轮已点到（建议移 `:core:sync`），本轮补充一个连带问题：**`Bookmark` 缺同步元数据**（`updatedAt`/`deviceId`/`isDeleted`），而 §6.3 同时声明 LWW + Union——没有 `updatedAt` 的 LWW 是无法实现的。这是接口与所声明策略的自相矛盾，不只是「字段不够」。

**修**：`SyncBackend` 移出 Layer 0；`Bookmark`/进度/标注模型在冻结前补 `updatedAt: Long`、`deviceId: String`、`isDeleted: Boolean`，否则 Phase 2 同步无法兑现 §6.3 的承诺。

### [P1-G] ServiceLoader 与 Koin 双重扩展注册，机制重叠且 Android 上 ServiceLoader 不可靠

**位置**：§7.2（ServiceLoader 发现 Extension）↔ §8.2（「Extension 各实现 `single`，ServiceLoader 发现后手动注册」）

两处对「扩展如何被发现」说法不一：一处 ServiceLoader，一处 Koin single + 手动注册。而 Android 上 `ServiceLoader` 经 R8/混淆后 `META-INF/services` 易被裁剪，需要专门 keep 规则；它本质是编译期 first-party 机制，不是用户可安装插件（上一轮 P2 已点其「过度承诺开放性」，本轮补充的是**两机制并存本身的矛盾**）。少人团队没必要维护两套发现链路。

**修**：二选一。建议直接 Koin multibind（与 ReaderEngine 同模式），删掉 ServiceLoader 描述，扩展开放性问题留作未来 ADR。

### [P2-H] 版本与模块计数漂移（文档 ≠ 仓库）

**位置**：§9.1 / §9.2 / §9.3 ↔ 实际 `libs.versions.toml`、`settings.gradle.kts`

实测对照：

| 项 | v3 文档 | 实际仓库 |
|----|---------|---------|
| agp | 8.8.2 | **8.7.3** |
| ktor | 3.1.2 | **3.1.0** |
| compose-bom | 2026.05.01 | **2026.06.00** |
| ink | 1.0.0-beta02（§5.1/§9.1） | **1.0.0（稳定）** |
| build-logic convention plugins | 4 个（§9.2） | **不存在** |
| settings include | 全 21 模块（§9.3） | **6 个**（含 `:features:library`，而 Phase1 文档只列 5 个） |

ink 尤其值得一提：文档写 beta02，仓库已是 1.0.0 stable——这反而是好事，但文档没跟上。上一轮已建议「文档只写『版本以 toml 为准』」，本轮确认漂移仍在，且 Phase1 模块清单（5 vs 实际 6）也对不上。

**修**：§9.1 删除硬编码版本号，改引用 `libs.versions.toml`；§9.3 模块清单标注「随 phase 渐进 uncomment」，并与实际 settings 对齐。

### [P2-I] nanohttpd 本地内容服务的安全边界未定义

**位置**：§4.6、§11.5（WebView 安全只讲了 CSP / file access）

EPUB 走「本地 nanohttpd 提供解压内容 → WebView HTTP 加载」。但文档没定义：监听地址（必须 `127.0.0.1`，绝不可 `0.0.0.0`，否则同 LAN 设备可读你解压的书）、随机端口、随机 path token、book 关闭后 server 生命周期。上一轮的 "ADR-EPUB-WebView-Security" 提了 path token，但没点出 nanohttpd 绑定地址这个具体高危点——本地 HTTP server 绑错网卡是真实泄露面。

**修**：明确 `loopback only + 随机端口 + 每本书随机 path token + 关书即停 server`，写进 EPUB 安全 ADR。

### [P2-J] TXT 字节偏移分页与字符边界对齐未约束

**位置**：§3.2 / §4.4（TXT 引擎）

`byteOffset` 分页 + 多字符集 fallback（UTF-8/GBK/Shift-JIS）有个经典陷阱：**按字节切块可能切在一个多字节字符中间**，GBK/UTF-8 下会产生乱码或丢字，进而 `Locator.ByteOffset` 记录的位置在重开时落到非字符边界。这是 TXT 自研引擎（你们明确要自研、用户指标也支持自研）的核心正确性点，文档只字未提边界对齐。

**修**：在 TXT 引擎契约里要求「页边界对齐到字符边界（解码探测回退）」，并把 `Locator.ByteOffset` 定义为「保证落在字符起始字节」。这是自研引擎 scorecard 里「数据可恢复」的硬指标。

## 四、上一轮 findings 的状态核对（避免重复，仅标状态）

| 上一轮 finding | 本轮状态 |
|----------------|---------|
| 用户轻量契约缺失 [P0] | 仍未落为验收项，方向认可，不重复 |
| ReadflowError 不可序列化 [P0] | **本轮升级为 P0-A，并发现连带打穿 ReaderState 序列化** |
| BookSource 返回 `java.io.File` [P1] | 仍在（§6.2、§7.1 Phase1 step7），认可上一轮 `DownloadedAsset` 方案 |
| ReaderEngine/ViewPager2 宿主冲突 [P1] | 仍在（§3.2 单 child vs §4.5 ViewPager2），认可上一轮 `PageTransitionHost` 拆分 |
| 同步元数据不足 [P1] | **本轮补充：缺 `updatedAt` 使 §6.3 LWW 无法实现，属自相矛盾（P1-F）** |
| MuPDF license/optional [P1]、权限矩阵 [P1] | 仍待 ADR，不重复 |

## 五、修复 Gate（按「先能编译、再能跑、再轻量」排序）

适配少人精品节奏，建议这个顺序：

1. **Gate 0（开 Phase 1 前必须）**：P0-A 错误模型重构 → P0-B epubjs/epub-ts ADR 收敛 → P0-C 线程契约写进 §4.1。这三条不修，照文档写的第一周就会卡。
2. **Gate 1（Phase 2 渲染前）**：P1-D Registry 线程安全、P1-E 引擎懒加载、P1-G 扩展发现机制二选一、P2-I nanohttpd 绑定 loopback。
3. **Gate 2（冻结模型前）**：P1-F SyncBackend 移层 + 补同步元数据、P2-J TXT 字符边界、P2-H 文档版本/模块去硬编码。
4. **承接上一轮**：用户轻量契约落为 Phase A 验收、BookSource 资产返回、PageTransitionHost、权限矩阵、MuPDF ADR。

## 六、一句话

v3 的「内部可中重度、用户侧要轻」定位是对的，骨架也撑得起精品阅读器；但这份**权威规范现在有三处照抄编译不过的代码和一组跨文档矛盾**——对少人团队，先把文档改到「能照着写、写完能跑」，比再加任何架构理念都值钱。
