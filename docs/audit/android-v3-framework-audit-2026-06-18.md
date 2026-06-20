# LinReads Android v3 框架架构审计

> 日期：2026-06-18  
> 审计对象：`docs/android-architecture-v3.md`  
> 重审说明：本版按用户反馈修正审计口径。“轻量”不是开发轻量，也不是少建模块；允许内部工程复杂、允许自研引擎。审计重点改为：复杂度是否被框架封装，最终用户是否获得安装轻、启动轻、阅读轻、配置轻、迁移轻、权限轻的体验。  
> 范围说明：本报告审计设计文档，不审计具体功能完成度；当前仓库状态仅用于校准可落地性。

---

## 一、结论摘要

**总体裁决：v3 方向正确，可以承载精品阅读器；但必须补一层“用户轻量架构契约”。**

上一版把“轻量性”主要理解成少人团队的开发负担，这是不准确的。对 LinReads 这种精品阅读器，内部可以有 21 个模块，可以有复杂分层，也可以为了体验自研 TXT/分页/排版/批注引擎。真正需要审计的是：这些复杂能力是否被框架吸收，而不是转嫁给用户。

按这个口径重审，v3 的主线仍然成立：

- Hybrid View 是正确底座，能把 EPUB WebView、PDF ImageView、TXT RecyclerView、Ink overlay 放在各自最适合的运行环境里。
- `ReaderEngine` 抽象不是开发轻量的工具，而是用户体验轻量的工具：用户不该知道引擎存在，系统自动选最稳、最快、最省电的实现。
- `Locator`、`ReaderState`、`BookSource`、`InkAnchor` 这些统一模型，为阅读进度、批注、导入导出、跨端迁移留下了共同语言。

但 v3 目前缺少一组面向用户的硬约束。文档写了性能预算、离线优先、WebView 安全、无障碍和多格式，但没有把它们组织成可验收的“用户轻量契约”。结果是：开发上可能很优雅，用户侧仍可能变成大包体、慢首开、多权限、设置复杂、数据难迁移、同步难理解。

本次重审不再建议用“减少物理模块”作为核心方案。正确方向是：

1. 保留 v3 目标架构和 21 模块上限，模块数由工程边界决定。
2. 增加用户轻量架构契约，作为实现前的 P0 gate。
3. 所有自研或第三方引擎，都按用户指标裁决：APK 体积、首屏耗时、内存、电量、稳定性、可访问性、数据可迁移性。
4. Calibre、OPDS、同步、Ink、TTS、DOCX/CBZ 都可以做，但必须是渐进增强；首次打开本地书不应依赖账号、服务器或复杂配置。

---

## 二、评分

| 维度 | 分数 | 判断 |
| --- | ---: | --- |
| 框架方向 | 3.5 / 4 | Hybrid View、ReaderEngine、纯数据 State 是正确主线 |
| 用户使用轻量性 | 2.3 / 4 | 离线优先和预算有方向，但首开路径、设置收敛、权限、导入导出尚未形成契约 |
| 运行轻量性 | 2.8 / 4 | 性能预算写得较好；WebView、MuPDF、Ink、TTS 等重能力缺少按需装载和实测 gate |
| 便携性 | 2.6 / 4 | `Locator` 和纯模型有迁移潜力；`java.io.File`、缺少导出格式、同步元数据不足削弱用户数据可携带性 |
| 开放性 | 3.0 / 4 | 引擎、书源、扩展接口完整；但第三方插件、开放备份、OPDS/WebDAV 的用户边界未定 |
| 少人团队可维护性 | 2.7 / 4 | 多模块可接受，但接口冻结偏早，模型层偏胖，文档版本片段易漂移 |
| 安全/隐私治理 | 2.4 / 4 | 有 WebView、网络、凭据意识；仍缺少权限矩阵、JS bridge ADR、许可证 gate |
| **总分** | **19.3 / 28** | **有条件通过：目标架构可保留，但实现前必须补用户轻量契约与 P0/P1 gate** |

---

## 三、主要优点

### 1. Hybrid View 有利于用户侧性能和兼容性

v3 不用 Compose `Image(bitmap.asImageBitmap())` 作为文档渲染基元，而是让文档层直接使用 `View`。这不是开发轻量，但对用户是轻量：减少多余 bitmap 中转，避免重组导致文档 View 重建，让 WebView、PdfRenderer、RecyclerView、Ink 各自走成熟路径。

保留建议：继续坚持 Hybrid View，并把体验验收写清楚：首屏、翻页帧率、旋转恢复、低端机内存、TalkBack 焦点。

### 2. 引擎可插拔给“自研或替换”留下了正确出口

用户不关心 EPUB 是 WebView + epub-ts，TXT 是自研分页，还是 PDF 是 PdfRenderer。用户只关心打开快、翻页稳、排版舒服、数据不丢。`ReaderEngine` 正好把这些实现差异包在内部。

保留建议：允许自研引擎，但不要用“自研/第三方”作为价值判断。每个引擎用同一套用户指标比较。

### 3. Locator 是用户数据可迁移的基础

EPUB CFI、PDF page、TXT byte offset、MD section 统一到 `Locator`，这让进度、书签、批注、同步和导出都有统一锚点。这是用户迁移轻量的基础。

保留建议：`Locator` 应成为所有用户生成数据的公共位置语言，并配套开放导出格式。

### 4. 离线优先方向正确

v3 规定进度/书签/标注先写本地，再后台同步。这符合用户轻量：没有网络也能读，网络恢复后再同步，且不强迫用户登录。

保留建议：同步必须是增强能力，不是使用前提。首发体验应支持完全离线、无账号阅读。

---

## 四、Findings by Severity

### [P0] 缺少“用户轻量架构契约”

**位置**：`docs/android-architecture-v3.md` 全文，尤其 §10、§11.5、§11.6、§11.8  
**类别**：用户体验架构 / 验收治理  
**影响**：v3 有很多正确组件，但缺少统一验收口径。没有契约时，复杂能力很容易以用户可见复杂度的形式泄露出来：首次启动先配服务器、设置页出现引擎名、导入书要理解格式源、同步要理解后端、权限请求过早、重引擎默认打包。  
**建议**：实现前新增 `User-Light Architecture Contract`，至少包含：

- 首次启动：无需账号、无需 Calibre、无需同步配置，也能打开本地 EPUB/PDF/TXT。
- 阅读默认：系统自动选择引擎；普通用户不看见 engine id、priority、backend id。
- 设置分层：默认只暴露字号、主题、行距、翻页方式；高级引擎/同步/扩展进入高级设置。
- 数据出口：书签、进度、标注可一键导出为开放备份包。
- 权限最小化：只在用户触发对应动作时请求文件、网络、通知、麦克风等权限。
- 重能力按需：MuPDF、Ink、TTS、OPDS、Sync 不能成为基础阅读路径的成本。

### [P0] `ReadflowError` 的 `Throwable?` 不可直接序列化

**位置**：`docs/android-architecture-v3.md` §6.1.3  
**类别**：可实现性 / 数据可携带性  
**影响**：文档将 `ReadflowError` 标为 `@Serializable`，但父类和子类包含 `Throwable? cause`。这会破坏 Room/JSON/同步/导出，也会让用户面对不可稳定恢复的错误状态。  
**建议**：拆成两层：

- `ReadflowErrorDto`：纯数据、可序列化，只含 `code`、`message`、`details`、`retryable`。
- `ReadflowException` 或 runtime wrapper：可带 `Throwable`，只存在运行时，不进入持久化模型、同步模型或导出备份。

### [P1] 首次使用路径仍偏 Calibre/架构驱动，不够用户驱动

**位置**：`docs/android-architecture-v3.md` §6.2、§10、§11.7  
**类别**：用户使用轻量性  
**影响**：v3 强调 Calibre、OPDS、BookSource、同步和多格式，但没有把“打开一本本地书”定义为第一等路径。对用户来说，最轻的体验是安装后直接打开文件，而不是先理解书源、服务器、baseUrl 或同步后端。  
**建议**：

- 将 Android `ACTION_VIEW` / `ACTION_SEND` / Storage Access Framework 导入列为 Phase 1 验收。
- 本地书、最近阅读、继续阅读作为基础路径；Calibre 是可选书源，不是启动门槛。
- Calibre 设置做成连接向导：自动探测优先，手动 URL 兜底，失败给人话提示。

### [P1] 安装包与按需能力边界未定义

**位置**：`docs/android-architecture-v3.md` §2.1、§4.4、§11.8  
**类别**：运行轻量性 / 发布架构  
**影响**：文档写总 APK `<25MB`，但同时包含 WebView 资源、MuPDF `.so`、Ink、TTS、OPDS、Markwon、Room/Ktor/Koin 等能力。21 个开发模块不是用户负担，但如果所有重能力默认打进基础包，用户会承担安装体积、更新时间、冷启动和内存成本。  
**建议**：

- 区分 `base APK budget` 与 `full feature budget`。
- DOCX/CBZ 的 MuPDF 引擎、Ink、TTS、OPDS、Sync 明确为可选能力，至少通过 feature flag、ABI split 或后续动态交付隔离成本。
- 每个引擎登记体积、冷启动影响、首开耗时、内存峰值，不达标不能默认启用。

### [P1] `ReaderEngine.createView()` 与 `ViewPager2.PageTransformer` 宿主关系未定义

**位置**：`docs/android-architecture-v3.md` §3.2、§4.1、§4.5  
**类别**：交互架构 / 运行轻量性  
**影响**：文档说 `document_host` 同一时刻只有一个 child，`ReaderEngine.createView()` 返回一个 View；同时又把翻页动效设计成 `ViewPager2.PageTransformer`。ViewPager2 需要 adapter/page container，和“单一 document View”不是同一宿主模型。若不拆清，用户侧会表现为翻页不跟手、状态丢失、手势冲突或格式之间行为不一致。  
**建议**：

- `ReaderEngine.createDocumentView()` 只负责格式渲染。
- `PageTransitionHost` 负责包装可分页 engine session。
- 不同格式可以有不同过渡宿主；统一的是用户手势语义，不是强制同一 ViewPager2 实现。

### [P1] `BookSource.download()` 返回 `java.io.File`，削弱用户数据可携带性

**位置**：`docs/android-architecture-v3.md` §6.2  
**类别**：便携性 / 文件模型  
**影响**：`java.io.File` 把书籍资产绑定到 Android/JVM 文件系统，不利于 Storage Access Framework、Web、HarmonyOS、云盘、外部 SD 卡和导出备份。用户最终会感知为“文件在哪、能不能迁走、换设备后还能不能打开”的不确定性。  
**建议**：改为平台中立资产描述：

```kotlin
data class DownloadedAsset(
    val bookId: String,
    val format: BookFormat,
    val localUri: String,
    val sizeBytes: Long,
    val checksum: String? = null,
)
```

Android 数据层再把 `localUri` 映射为 `Uri`、`File` 或 SAF document。

### [P1] 用户数据导出与同步元数据不足

**位置**：`docs/android-architecture-v3.md` §6.3、§6.4  
**类别**：便携性 / 开放性  
**影响**：文档写冲突策略是 LWW 和 Union，但 `SyncBackend.pushProgress(bookId, locator)` 没有 `updatedAt`、`deviceId`、`revision`；`Bookmark` 没有 `updatedAt`、`deletedAt` 或 tombstone。更重要的是，文档没有定义用户可导出的开放备份格式。用户轻量不是“有同步后端”，而是“换设备、换应用、断网、停服都能拿走数据”。  
**建议**：

- 进度、书签、标注模型预留 `updatedAt`、`deviceId`、`schemaVersion`、`deletedAt` 或 `isDeleted`、`sourceReplicaId`。
- 新增 `LinReads Backup` 格式：ZIP + JSON manifest + bookmarks/progress/annotations + optional sidecar assets。
- `SyncBackend` 移出 `:core:model`，放入后续 `:sync:api` 或 `:core:sync`。

### [P1] WebView + JS bridge 安全策略仍不足以承载不可信 EPUB

**位置**：`docs/android-architecture-v3.md` §4.6、§11.5  
**类别**：安全 / 隐私轻量性  
**影响**：EPUB 是不可信内容载体。文档提到 CSP 和禁止 file access，但未定义 bridge origin 校验、随机 path token、URL 拦截白名单、外链打开策略、脚本权限边界。安全问题一旦暴露，用户会付出隐私和信任成本。  
**建议**：实现前新增 `ADR-EPUB-WebView-Security`：

- 本地 server 使用随机 path token。
- bridge 方法校验当前 origin/path。
- 禁止任意导航，外链交给系统浏览器并二次确认。
- 禁止 file/content universal access。
- 每本书独立 WebView lifecycle，关闭时清理 timers、history、cache 策略。

### [P1] MuPDF 许可证与可选性需要明确

**位置**：`docs/android-architecture-v3.md` §4.4、§11.3、§11.8  
**类别**：开放性 / 发布治理 / 安装轻量性  
**影响**：MuPDF 处理 DOCX/CBZ 能提升格式覆盖，但 AGPL/商业授权、`.so` 体积、ABI 分发和商店发布策略都需要前置裁决。用户侧的轻量目标不是“少写代码”，而是“不为少数格式承担所有人的安装成本和授权风险”。  
**建议**：

- DOCX/CBZ 标为 optional engine。
- 首次启用前完成 license ADR。
- 评估 MuPDF 与自研/转换式/服务端预处理方案时，以用户安装包、离线能力、隐私和性能共同裁决。

### [P1] 权限与隐私矩阵缺失

**位置**：`docs/android-architecture-v3.md` §11.5  
**类别**：权限轻量性 / 隐私  
**影响**：阅读器可能涉及文件访问、局域网 HTTP、凭据、通知、TTS、同步、外链、拖拽导入。文档目前只有网络和凭据存储策略，没有逐功能权限矩阵。用户会在不理解原因时被请求权限，形成使用负担。  
**建议**：新增权限矩阵：

- 基础阅读：不请求危险权限。
- 导入本地文件：SAF 单次授权优先，不申请广域存储。
- Calibre：仅用户添加服务器后访问局域网。
- 同步：默认关闭，凭据本地加密，明确可删除。
- TTS/通知：用户启用时再请求。

### [P2] Extension SPI 的开放性容易过度承诺

**位置**：`docs/android-architecture-v3.md` §7  
**类别**：开放性 / 用户心智轻量性  
**影响**：ServiceLoader 在 Android 上更像 first-party 编译期扩展，不等于用户可安装插件系统。若在产品中暴露“插件”，用户会期待安装、卸载、权限、签名、兼容性和崩溃隔离。  
**建议**：前三个阶段标注为 `internal first-party extension API`。真正用户插件系统必须另立 ADR，包含权限模型、签名、API versioning、R8 keep rules 和崩溃隔离。

### [P2] `core:model` 职责偏胖，但不是用户轻量的核心阻断

**位置**：`docs/android-architecture-v3.md` §2.2、§6.1、§6.3  
**类别**：可维护性  
**影响**：`core:model` 同时放 `ReaderState`、`InkAnchor`、`SyncBackend`、`Bookmark`、`DownloadStatus`、`ThemeMode`。这会让内部 API 变重，但不会直接构成用户轻量问题。它的风险是后续演进时把实验能力固化为用户可见复杂度。  
**建议**：`core:model` 只保留跨模块必需值对象；行为接口如 `SyncBackend` 移出；Ink 只先保留通用 `AnnotationAnchor`，具体 `InkAnchor` 到 Ink 阶段再冻结。

### [P2] 性能预算有目标，但缺少测量 gate

**位置**：`docs/android-architecture-v3.md` §11.8  
**类别**：运行轻量性  
**影响**：冷启动、EPUB 打开、PDF 打开、内存峰值、APK 体积都有目标，这是优点。但没有说明何时测量、谁阻断、不同引擎是否单独计量。没有 gate，预算会变成愿望清单。  
**建议**：

- 每个阶段必须产出 APK Analyzer、冷启动、首开、内存峰值记录。
- 预算分为基础阅读包和完整功能包。
- 自研引擎和第三方引擎同台对比，胜负以用户指标决定。

### [P2] Hybrid View + Compose overlay 的无障碍路径未细化

**位置**：`docs/android-architecture-v3.md` §3、§11.6  
**类别**：可用性 / 用户轻量性  
**影响**：TalkBack、键盘导航、高对比度有策略，但 Hybrid View + Compose overlay 的 focus order、click region semantics、WebView 文本可访问性、Ink toolbar focus trap 没有验收用例。无障碍不是附加功能，它直接决定很多用户能不能轻松阅读。  
**建议**：为 ReaderRootLayout 增加 accessibility smoke list：

- TalkBack 能读出书名、章节、进度。
- 左/右翻页区域有明确 action label。
- Toolbar 出现/隐藏时焦点不丢失。
- WebView EPUB 不吞掉系统返回和方向键。

### [P2] Gradle 版本和文档片段已经漂移

**位置**：`docs/android-architecture-v3.md` §9；`android/gradle/libs.versions.toml`  
**类别**：可维护性 / 可复现性  
**影响**：v3 示例写 AGP、Ktor、Compose BOM、Ink 等版本，但当前 catalog 已不同。用户轻量依赖稳定发布，稳定发布依赖可复现构建。  
**建议**：架构文档只写“版本以 `android/gradle/libs.versions.toml` 为准”。若必须列版本，用生成片段或单独维护清单。

### [P3] 命名迁移仍是发布前一致性工作

**位置**：当前仓库 `dev.readflow`、`ReadflowApp`、manifest label  
**类别**：一致性 / 品牌收口  
**影响**：项目名已是 LinReads，但 Android 包名和部分类名仍是 readflow。对架构不构成阻断，但发布前会影响用户认知一致性。  
**建议**：作为 release polish 单独处理，不要和框架重构混在一起。

---

## 五、用户轻量架构契约草案

### 1. 安装轻

- 基础阅读包只包含首发必须能力。
- 重引擎、手写、TTS、OPDS、同步按需启用或延后打包。
- 每个 engine 记录 APK 增量、ABI 影响、冷启动影响。

### 2. 首开轻

- 第一次启动不要求登录、不要求连接 Calibre、不要求选择引擎。
- 主路径是“打开本地书”与“继续阅读”。
- Calibre 是增强书源，有向导、有自动探测、有失败解释。

### 3. 阅读轻

- 普通用户只看到字号、主题、行距、翻页方式、目录、书签。
- 引擎选择、同步后端、调试信息进入高级设置。
- 格式差异由系统吸收，用户手势语义保持一致。

### 4. 数据轻

- 进度、书签、标注离线本地优先。
- 一键导出开放备份包。
- 同步是可选增强；停服、断网、换设备不应导致数据不可取回。

### 5. 权限轻

- 默认不请求危险权限。
- 文件访问走 SAF 或用户主动分享。
- 网络、同步、通知、TTS 等权限按功能触发。
- 凭据可查看、可删除、可重新授权。

---

## 六、自研引擎裁决准则

自研引擎不是禁忌。对 LinReads，是否自研应按用户收益裁决，而不是按开发工作量裁决。

建议每个引擎进入实现前做一页 scorecard：

| 指标 | 必填问题 |
| --- | --- |
| 包体积 | 自研/第三方/系统方案分别增加多少 APK？ |
| 首屏 | 1MB EPUB、10MB PDF、5MB TXT 首屏耗时多少？ |
| 内存 | 打开、翻页、关闭后的峰值与释放是否稳定？ |
| 电量 | 连续翻页、滚动、TTS、Ink 是否有异常耗电？ |
| 可访问性 | TalkBack、字号缩放、高对比度、键盘是否可用？ |
| 数据 | 进度、书签、批注能否导出并跨版本恢复？ |
| 安全 | 是否引入 JS bridge、JNI、文件权限、网络服务风险？ |

裁决示例：

- TXT：自研虚拟分页器很可能成立，因为可降低大文件内存、提升编码兼容和进度稳定性。
- PDF：优先系统 `PdfRenderer`，除非功能缺口明确且第三方或自研方案在用户指标上明显胜出。
- EPUB：WebView + epub-ts 是现实路线；若未来自研排版引擎能显著降低启动、内存、安全面，并保持 EPUB 兼容性，可以重新开 ADR。
- DOCX/CBZ：重引擎适合可选能力，不适合让所有用户默认承担成本。

---

## 七、建议的实现验收顺序

这里不是按开发轻量排序，而是按用户获得轻量体验的顺序排序。

### Phase A：无账号本地阅读闭环

目标：安装后用户能直接打开一本本地书并继续阅读。

验收：

- Android `ACTION_VIEW` / `ACTION_SEND` / SAF 导入至少一种路径可用。
- 最近阅读列表可见。
- 进度本地保存。
- 不要求 Calibre、同步、账号或网络。

### Phase B：Calibre 作为可选书源

目标：用户愿意连接 Calibre 时，流程短且失败可理解。

验收：

- baseUrl 来自设置或向导，不硬编码。
- 可搜索/列出真实书籍。
- 书名、作者、格式、封面可见。
- 连接失败有明确原因和下一步。

### Phase C：EPUB/PDF/TXT 阅读质量闭环

目标：主流格式可稳定阅读，设置不打扰。

验收：

- 系统自动选择引擎。
- 字号、主题、行距、翻页方式可用。
- 首屏、翻页、内存达到预算。
- TalkBack smoke test 通过。

### Phase D：数据出口与离线缓存

目标：用户能控制自己的书和阅读数据。

验收：

- 已下载书籍可离线打开。
- 缓存策略可解释、可清理。
- 进度、书签、标注可导出和恢复。

### Phase E：精品增强能力

目标：Ink、TTS、OPDS、Sync、DOCX/CBZ 等能力逐个进入，但不污染基础体验。

验收：

- 每个能力有独立 ADR、权限说明、包体积预算和关闭路径。
- 默认关闭或按需启用。
- 出错时不影响基础阅读。

---

## 八、最终建议

1. **保留 v3 作为目标架构，不用模块数衡量轻量性。**
2. **立即在 v3 前补 `User-Light Architecture Contract`，它应成为实现前 P0 gate。**
3. **允许自研引擎，但必须用用户指标赢得资格：更快、更稳、更省、更可迁移、更少权限。**
4. **优先交付无账号本地阅读闭环，再接 Calibre、同步、扩展和重格式。**
5. **先修 P0/P1 文档问题**：用户轻量契约、可序列化错误模型、ReaderEngine/PageTransition host、BookSource 资产返回、同步元数据和导出格式、WebView 安全 ADR、MuPDF license/optional gate、权限矩阵。

一句话：**v3 的复杂度可以保留在工程内部，甚至可以为了体验自研；但用户手里拿到的必须是一把很轻的阅读器。**
