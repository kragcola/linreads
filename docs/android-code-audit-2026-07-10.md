# LinReads Android 全量代码审计（2026-07-10）

## 1. 审计状态

- 范围：`android/` 下受版本控制的 Android 生产代码、测试、Gradle 配置、Manifest、Room、DataStore、Compose UI、渲染引擎、Calibre、同步、备份恢复、OTA 与 Phase 3 声明。
- 方法：架构阅读、关键数据流追踪、高风险模式扫描、并发/取消/资源生命周期审查、文件与数据库原子性审查、安全边界审查、Android Lint、分阶段测试与构建。
- 处置原则：确定性缺陷已直接修复并补回归覆盖；涉及产品语义或架构取舍的事项保持未修改，等待明确选择。
- 当前状态：确定性修复和已确认策略均已实施；OTA 长期凭据按用户要求在开发阶段暂时保持现状。

## 2. 项目理解

### 2.1 架构地图

| 层 | 模块 | 职责 |
|---|---|---|
| 应用壳 | `:app` | Application、Activity、导航、Koin、外部文件 Intent、OTA |
| 核心模型 | `:core:model` | 书籍、Locator、ReaderState、主题、错误等纯数据契约 |
| 数据与同步 | `:core:database`、`:core:prefs`、`:core:sync` | Room、DataStore、同步合并、备份恢复 |
| 数据源 | `:core:calibre`、`:extensions:api` | Calibre、本地导入、目录扫描、扩展 SPI |
| UI | `:core:ui`、`:features:library`、`:features:reader`、`:features:settings` | Compose 页面、ViewModel、阅读器宿主 |
| 渲染 | `:render:api`、`:render:txt`、`:render:epub`、`:render:pdf`、`:render:md`、`:render:animate` | 多格式解析、渲染、翻页、状态与缓存 |
| Phase 3 | `:ink` 及若干声明模块 | 手写接口与尚未落地的扩展规划 |

### 2.2 主要运行链路

1. `MainActivity` 处理普通启动及外部 `VIEW`/`SEND` 文件 Intent。
2. `ReadflowApp` 导入外部文件并写入书库，导航到 `reader/{bookId}`。
3. `ReaderViewModel` 读取 Room 数据和进度，通过 `ReaderEngineRegistry` 选择引擎。
4. 引擎持续上报 Locator，ViewModel 持久化进度、书签、标注、引擎状态与阅读会话。
5. 设置页管理 Calibre、备份恢复、主题/字体导入导出及 GitHub OTA 更新。

## 3. 已修复缺陷

### 3.1 阅读器与渲染

- 修复 PDF outline 使用 API 33 `readNBytes()` 导致 Android 8–12 崩溃；改为 API 26 可用的限长读取。
- 串行化 reader open/close；新打开请求会取消旧请求，避免旧书异步结果覆盖当前书。
- 返回书架前落盘进度、引擎状态和阅读会话并关闭引擎；异常路径和取消路径也会释放临时引擎。
- close 从入口起在 `NonCancellable` 中取消/等待打开任务、获取 session mutex 并执行清理；全部清理完成后恢复调用方或内部的协程取消。
- 会话、最终进度、SavedState、引擎快照和引擎关闭彼此隔离，单步普通失败不会跳过后续落盘，也不会阻断替换打开下一本书。
- Reader 搜索等协程捕获逻辑显式重抛 `CancellationException`，恢复结构化并发语义。

### 3.2 数据库、同步与书架

- 备份恢复通过 Room transaction 执行，避免失败或取消后留下半恢复数据。
- 备份恢复只接受首个 ZIP 条目为非目录 `manifest.json`，不会为寻找 manifest 解压任意前置条目；manifest 实际解压仍限制为 16 MiB。
- `LibraryRepository.upsertAll()` 与单本 upsert 对齐，批量刷新不再覆盖本地用户修改的标题、作者、分组、排序和 `lastReadAt`。
- Sync remote pull 失败不再被解释为“远端不存在”，避免随后把本地旧数据反向覆盖远端。
- Library 与 Reader 的 Compose 状态改为 lifecycle-aware collect，减少后台无效收集和生命周期错位。
- 完整删除在事务 runner 抛错后以 Room 实际书籍行状态决定恢复或提交 staging；数据库状态不可查询时保留 staging 供下次启动恢复。
- 中断删除按 book 分组恢复，单组文件异常不会阻断其他书；Application 最外层同时隔离目录枚举失败，避免冷启动崩溃循环。
- 受控文件生产与删除通过共享协调器串行化：同书 Calibre 下载会先取消并等待，尚未确定 ID 的本地导入会先完成 import+upsert，再执行删除。

### 3.3 Calibre 与本地文件

- Calibre 下载先写同目录 staging 文件，再优先使用原子移动替换；失败保留旧缓存。
- Calibre 搜索实现 latest-wins，慢旧请求不能覆盖新查询结果。
- 文件夹批量导入保留成功项并汇总失败数量，不再静默丢弃失败原因。
- `CalibreRepository`/`CalibreClient` 可关闭；Library 搜索和下载完成后释放 Ktor `HttpClient`。
- Calibre、本地导入、首次种子、封面提取等捕获逻辑显式重抛协程取消。
- 本地文件、引擎状态、封面和字体写入使用 staging + 同目录替换，降低进程终止或磁盘异常造成的截断风险。
- 完整删除除 Room 中的 URI 外还会按稳定 `bookId` 扫描受控目录，清理下载已落盘但 URI 尚未更新的竞态文件。

### 3.4 OTA、输入与安全加固

- OTA 下载记录持久化 URL/tag identity；新版本不会复用旧版本已完成的 APK。
- 主动下载、接收器和清理路径统一维护下载 ID 与身份元数据。
- Phase 1 Manifest 禁用 Phase 2 OTA receiver，避免阶段边界泄漏。
- 封面 XML parser 禁用 DOCTYPE、外部实体和 XInclude，降低 XXE 风险。
- 封面解压限制为 32 MiB，备份 manifest 限制为 16 MiB，主题 JSON 限制为 1 MiB，避免无界内存读取。
- EPUB container/OPF 按实际解压读取量限制为 2 MiB，不信任可伪造或未知的 ZipEntry size；非规范 archive-root 封面兼容路径保持受规范化约束。
- 应用在 Activity 每个真实 `ON_START` 区间自动检查一次 OTA，`ON_STOP`/dispose 会取消在途检查；generation guard 只允许最新请求发布通知，旧结果不能覆盖新版本 PendingIntent。
- Android 13+ 权限缺失或通知被禁用时跳过系统通知且不崩溃。
- `SelectionAwareTextView` 的手动链接点击调用并实现 `performClick()`，修复 `ClickableViewAccessibility`。

### 3.5 用户确认后的策略实施

- 书籍删除确认框提供同层单选项，默认“仅移出书架”；用户可切换为“全部删除”。
- “全部删除”会在 Room 单事务中清理书籍、进度、书签、标注、笔迹和阅读会话；仅删除应用私有 `files/books` 下的受控文件，外部 URI 不删除。
- 受控文件先移动到同目录 staging；数据库事务失败时恢复原文件，成功后清理 staging，避免数据库失败却丢失文件。
- Android 13+ 在用户点击“下载并安装”且通知权限缺失时请求 `POST_NOTIFICATIONS`；拒绝权限不会阻断应用内下载。
- Calibre 明文 HTTP 只允许 localhost/RFC1918；Ktor 每次实际发送均校验 URL，重定向必须保持同 scheme/host/port。
- Coil 封面请求逐次校验明文 URL，并禁用自动重定向，封面加载不能绕过 Calibre 私网边界。
- Phase 3 删除不存在的 `:render:mupdf`、`:extensions:tts`、`:extensions:stats`、`:extensions:opds` 声明，只保留实际存在的 `:ink`。

## 4. 新增回归覆盖

- PDF API 兼容读取。
- Reader open/close 竞态、入口取消、分步持久化失败、替换打开与引擎释放。
- OTA URL/tag 下载身份判断及元数据清理。
- OTA 前台 lifecycle gate、在途任务取消、latest-wins、通知权限/总开关 gate 与启动恢复异常隔离。
- Calibre latest-wins、批量导入部分失败、下载取消不转换为普通失败。
- 删除事务最终状态、分组恢复、稳定 ID 文件清理及生产/删除并发所有权。
- EPUB container/OPF 实际解压上限和 archive-root 封面兼容。
- 备份恢复事务边界、首条目约束与前置压缩炸弹拒绝。
- 批量 upsert 保留本地书架状态。
- Sync pull 失败时不执行 push。

## 5. 已确认的产品/架构决策

### 5.1 OTA 分发与客户端凭据（开发期保留）

- 现状：构建链可把长期 GitHub token 写入 APK；客户端内置秘密可被反编译提取。
- 决策：当前仍处于开发阶段，以便捷为先，暂不修改现有 OTA token 链路。
- 发布前硬门槛：必须重新选择公开无 token 分发或私有后端/短时签名 URL；客户端长期秘密不能作为正式发布方案。

### 5.2 删除书籍语义（已实施）

- 现状：删除主书记录时不会清理受控文件及进度、书签、标注、笔迹、会话，可能产生孤儿数据或重新导入后“复活”。
- 决策：提供两个同层选项，默认仅移出书架；用户可切换为全部删除。

### 5.3 Android 13+ 通知权限（已实施）

- 现状：应用没有主动请求 `POST_NOTIFICATIONS`，OTA 下载通知可能不可见。
- 决策：用户点击“下载并安装”时按上下文请求。

### 5.4 Calibre 明文 HTTP（已实施）

- 现状：应用级 network security config 全局允许明文流量，安全边界过宽。
- 决策：保留局域网 HTTP，代码限制 localhost/RFC1918，并阻止越界重定向。

### 5.5 Phase 3 空项目声明（已实施）

- 现状：`settings.gradle.kts` 声明 `:render:mupdf`、`:extensions:tts`、`:extensions:stats`、`:extensions:opds`，对应目录不存在；Gradle 将其当作空 project，使 Phase 3 构建呈现“虚假通过”。现有 `:ink` 仅含接口、brush 和 anchor codec，尚未接入 app/reader。
- 决策：删除四个未实现 project 声明，Phase 3 只包含实际存在的 `:ink`。

## 6. 仍需关注的 P3 工程项

- 资产协调器当前以 `Job -> bookId` 保存生产者；现有调用图没有嵌套 `produce()`，后续若引入同一协程嵌套生产需改为栈或计数结构。
- 同书删除取消 Calibre 下载后，`LibraryViewModel.downloadingBookId` 可能短暂保留旧值；不影响文件/数据库删除一致性。
- Calibre failure smoke 的 staging 文件筛选未覆盖实际 `calibre-42-*.part` 命名，且当前场景在 metadata 阶段失败；流式中断清理仍需更精确的 instrumentation 证据。
- TXT/EPUB adapter 仍有部分 `notifyDataSetChanged()`，长文本主题或字体变化可能触发全量 rebind。
- `app` 缺少显式应用图标，Lint 会给出 `MissingApplicationIcon` 警告。
- 本机 JDK 23 下 Kotlin 回退 JVM 22，而部分 Java toolchain 为 17；Gradle 9 前应统一版本。
- 若选择保留 Phase 3，应为 `:ink` 增加应用接入和行为测试，而不只是编译接口。
- 依赖升级提示未自动处理；升级属于独立兼容性工作，不应在缺陷修复中盲目追新。

## 7. 修复后验证

2026-07-10 在当前工作树执行以下连续验证，最终进程退出码为 0：

| 命令 | 结果 |
|---|---|
| `android/gradlew -p android test lint -Preadflow.phase=2 --continue` | 通过；1094 actionable tasks，Lint 无 error |
| `android/gradlew -p android test -Preadflow.phase=3 --continue` | 通过；Phase 3 只配置实际存在的 `:ink` |
| `android/gradlew -p android :app:assembleDebug -Preadflow.phase=1` | 通过 |
| `android/gradlew -p android :app:assembleDebug -Preadflow.phase=3` | 通过；只包含真实存在模块 |
| `android/gradlew -p android projects -Preadflow.phase=3 --quiet` | 通过；项目列表不含四个已删除的空模块 |
| `android/gradlew -p android -Preadflow.phase=2 :app:assembleOta` | 通过；599 actionable tasks，产出 9,849,169-byte OTA APK，SHA-256 `eb9cb1ad5ad5c8a7539800ca9dcd04692e4be89abcd31c6bfacd08a4a53a7965` |
| `git diff --check` | 通过 |

## 8. 自动审计结论与发布前事项

自动代码审计、独立数据/安全与 app/lifecycle 复核、确定性修复、用户确认策略与跨阶段门禁已经完成；最终复核未发现残留 Critical/Important。仍建议在发布候选版本上执行以下设备验收：

1. 对 PDF 低 API、Reader 导航退出、OTA A/B 版本、删除双选项做真机或模拟器验收。
2. 验证 Android 13+ 首次点击下载时的通知权限允许/拒绝两条路径。
3. 验证局域网 Calibre 封面/API 正常，公网 HTTP 与越界重定向被拒绝。
4. 正式发布前移除客户端长期 OTA 凭据。
