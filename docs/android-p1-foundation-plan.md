# Android Phase 1 地基预备执行文档

_创建：2026-06-19 · 依据：[android-architecture-v4.md](android-architecture-v4.md) §3 / §9 / §10 / §11_

> **本文档定位**：Phase 1 的**地基框架搭建**执行清单，不实现具体功能。
> 目标是把 v4 §10.3 phase1 的 9 个模块 + `build-logic` 全部立起骨架、接好分层依赖与 DI 装配点，
> 让 `-Preadflow.phase=1 ./gradlew :app:assembleDebug` 在**空壳可编译**前提下通过。
> 业务行为（Calibre 拉取、Room 查询、本地导入、书库 UI）一律留到「实装具体功能」阶段，见 §6 边界表。

## 一、范围与原则

地基预备 = **结构成立**，不是**功能成立**。本阶段只做三件事：

1. 把 phase1 的 9 个 Gradle 模块 + `build-logic` 全部建出空壳，目录/包名/`build.gradle.kts` 就位。
2. 按 v4 §3.2 八层依赖规则连好模块间 `implementation(project(...))`，让 CI 约束（§3.3）可空跑通过。
3. 把 Koin DI 装配点、Application、Navigation host 立起来，编译通过、能装能启动到一个占位首屏。

**不做**（留给后续「实装具体功能」）：
- 任何真实 Calibre HTTP 调用、Room 查询逻辑、DataStore 读写行为
- 本地文件导入（SAF / `ACTION_VIEW`）的真实登记流程
- 书库列表 UI 的真实数据绑定、最近阅读、进度持久化业务

> 自测红线（linreads-dev R2/R3）：本阶段产出的每个类要么是**纯数据/接口定义**，要么是**空实现 + TODO 占位**。出现真实业务分支逻辑即越界。

## 二、前置条件（已就位，开工前核验）

| 项 | 状态 | 核验命令 |
|----|------|---------|
| 工具链 AGP 8.13.2 / Gradle 8.14.3 / Kotlin 2.1.10 | ✅ catalog 已锁 | `cat gradle/libs.versions.toml` |
| compileSdk/targetSdk = 36 | ✅ 四处口径统一 | `grep -rn "Sdk = 36" app/build.gradle.kts` |
| JAVA_HOME 指向 Studio JBR | ⚠️ 每次 shell 需 export | 见下方注 |
| SDK Platform 36 已装 | ✅ | `ls $ANDROID_HOME/platforms` |
| `:app` 空壳已可编译 | ✅ BUILD SUCCESSFUL | `./gradlew -Preadflow.phase=1 :app:assembleDebug` |

> 命令行构建前置：`export JAVA_HOME="/Volumes/OmubotDisk/Applications/Android Studio.app/Contents/jbr/Contents/Home"`

## 三、模块依赖矩阵（phase1，依据 §3.2）

phase1 在场 9 模块 + `:app`。下表是本阶段要连好的 `implementation(project(...))` 关系，方向严格自上层指向下层，不得反向、不得跨规则。

| 模块 | Layer | 插件 | 依赖的 phase1 模块 |
|------|-------|------|------|
| `:core:model` | 0 | `ReadflowJvmLibrary`（kotlin jvm） | 无 |
| `:core:calibre` | 1 | `ReadflowAndroidLibrary` | `:core:model` |
| `:core:database` | 1 | `ReadflowAndroidLibrary` + ksp(room) | `:core:model` |
| `:core:prefs` | 1 | `ReadflowAndroidLibrary` | `:core:model` |
| `:core:sync` | 1 | `ReadflowAndroidLibrary`（纯 kotlin 亦可） | `:core:model` |
| `:extensions:api` | 1 | `ReadflowAndroidLibrary`（纯 kotlin） | `:core:model` |
| `:core:ui` | 5 | `ReadflowCompose` | `:core:model` |
| `:features:library` | 6 | `ReadflowFeature` | `:core:model` `:core:ui` `:core:calibre` `:core:database` `:core:prefs` `:extensions:api` |
| `:app` | 8 | `com.android.application` | 全部 phase1 模块 |

> 注：`:features:library` 依赖 `:core:calibre`/`:core:database`/`:core:prefs` 是为后续功能预留装配；本阶段仅建依赖边，不写调用逻辑。`:core:sync` phase1 只提供 `NoOpSyncBackend`，feature 不直接依赖它（经 `:app` DI 注入）。

## 四、执行步骤 → 验证

每步 `做什么 → 验证`。验证全部走命令行（`-Preadflow.phase=1`），逐步可回滚。

### Step 0 — 基线快照
- 做：确认当前空壳 `:app` 可编译，记录基线。
- 验证：`./gradlew -Preadflow.phase=1 :app:assembleDebug` → BUILD SUCCESSFUL。

### Step 1 — `build-logic` convention plugins（§10.2）
- 做：建 `build-logic/convention/` composite build，落 4 个插件：
  - `ReadflowJvmLibraryPlugin`（`:core:model` 用，kotlin jvm + serialization）
  - `ReadflowAndroidLibraryPlugin`（Layer 1 用，`compileSdk=36/minSdk=26/targetSdk=36/jvmTarget=17`）
  - `ReadflowComposePlugin`（`:core:ui` 用，继承 AndroidLibrary + compose + serialization）
  - `ReadflowFeaturePlugin`（feature 用，继承 Compose + viewmodel/navigation）
  - `settings.gradle.kts` 顶部加 `includeBuild("build-logic")`。
- 验证：`./gradlew -Preadflow.phase=1 :app:assembleDebug` 仍 SUCCESSFUL（插件未被引用，不应破坏现状）。
- 边界：插件只配置编译参数，不含任何业务逻辑。

### Step 2 — `:core:model`（Layer 0 纯数据）

- 做：`kotlin("jvm")` 模块，建 v4 §7 全部纯数据类型骨架：
  - `Locator` + `LocatorStrategy`（sealed：`Page`/`Section`/`ByteOffset`/`Unknown`，§7.1）
  - `ReaderState`（§7.2）、`ReadflowError`（可序列化，§7.3）
  - `BookMeta`/`BookFormat`/`DownloadStatus`/`DownloadedAsset`/`ThemeMode`/`Offset`（§7.4）
  - `Bookmark`/`ReadingProgress`（含同步元数据 `updatedAt`/`deviceId`/`isDeleted`，§7.7）
  - `InkAnchor`（§6.3）、`LoadingState`、`TransitionType`
  - 全部 `@Serializable`，无 `suspend`、无 `Flow`、无 `interface`（sealed 数据多态除外）。
- 验证：`./gradlew -Preadflow.phase=1 :core:model:compileKotlin` 通过；
  纯度 grep `grep -rE '(^import android\.|: *Flow<|suspend |^interface )' core/model/src/main` 返回空（§3.3 约束 2）。
- 边界：只定义类型，零行为。

### Step 3 — Layer 1 数据模块空壳

- 做：建 `:core:calibre`/`:core:database`/`:core:prefs`/`:core:sync`/`:extensions:api` 五模块：
  - `:core:calibre`：`CalibreClient`（迁现有 app 内类，类型引用指向 `:core:model`）+ `CalibreRepository` 接口空壳。
  - `:core:database`：`ReadflowDatabase`（Room `@Database`，5 表实体 §7.8）+ 5 个 DAO 接口（仅签名，不写复杂查询）。
  - `:core:prefs`：`SettingsRepository` 接口 + DataStore 骨架（`engineOverrides`/`deviceId`/字号/主题/baseUrl 字段声明）。
  - `:core:sync`：`SyncBackend` 接口 + `NoOpSyncBackend` 空实现 + `SyncManager` 骨架（§7.6 / F10）。
  - `:extensions:api`：`BookSource`/`Extension` SPI + `ReaderEventBus`/`ReaderEvent`（纯 Kotlin，§8）。
- 验证：`./gradlew -Preadflow.phase=1 :core:calibre:assemble :core:database:assemble :core:prefs:assemble :core:sync:assemble :extensions:api:assemble` 全通过；Room schema 导出无报错。
- 边界：DAO/Repository 方法体 `TODO()` 或返回空，不写真实 SQL 业务/网络调用。

### Step 4 — `:core:ui`（Layer 5）+ `:features:library`（Layer 6）空壳

- 做：
  - `:core:ui`：Material3 `ReadflowTheme` + 色板/字体/间距 tokens（按 linreads-dev 排版规范预留：正文≥16sp、line-height≥1.6、暖白/夜间非纯黑色值）。
  - `:features:library`：`LibraryViewModel`（空 state）+ `LibraryScreen`（占位 composable，显示空态文案），连好对 `:core:*` 的依赖边但不绑真实数据。
- 验证：`./gradlew -Preadflow.phase=1 :core:ui:assemble :features:library:assemble` 通过。
- 边界：UI 只渲染静态占位，ViewModel 不发起真实数据流。

### Step 5 — `:app` 组装（Layer 8）

- 做：
  - `ReadflowApplication`：`startKoin { androidContext(); modules(...) }`，各模块 Koin module 空骨架按 Layer 顺序加载（§9.1）。
  - `MainActivity`：Navigation host，单一占位路由指向 `LibraryScreen`。
  - `EngineStateStore` 实现留空（M1，phase2 才用，可不建或仅占位）。
  - `network_security_config.xml` 预留（LAN 明文 HTTP，域名占位，linreads-dev 平台速查）。
- 验证：`./gradlew -Preadflow.phase=1 :app:assembleDebug` → BUILD SUCCESSFUL；`adb install` 后能启动到占位首屏（可选，需 AVD）。
- 边界：DI 只装配，不触发业务；首屏是占位。

### Step 6 — 分层约束空跑校验（§3.3）

- 做：对 phase1 模块跑 §3.3 可机械判定的 CI 约束命令。
- 验证：
  - 约束 2（model 纯度）grep 返回空。
  - `:features:library:dependencies` 不含任何 `render:*`（约束 3 的 phase1 等价检查）。
  - 功能模块间零依赖（phase1 只有 library 一个 feature，自动满足）。
- 边界：仅校验，不改结构。

## 五、地基完成判定（Definition of Done）

全部满足才算地基预备完成：

1. `build-logic` 4 插件落地并被各模块引用，配置不再重复散落。
2. phase1 的 9 个模块 + `:app` 全部存在、目录/包名/`build.gradle.kts` 就位。
3. 模块依赖严格符合 §三 矩阵，无反向/越层依赖。
4. `./gradlew -Preadflow.phase=1 :app:assembleDebug` → BUILD SUCCESSFUL。
5. §3.3 可机械判定约束（model 纯度、feature 不依赖引擎）空跑通过。
6. 每个类是纯数据/接口/空实现，`git diff` 无真实业务逻辑（R2/R3 自测）。

## 六、本阶段不做（功能实装边界表）

| 领域 | 地基阶段（本文档） | 功能实装（后续） |
|------|------|------|
| Calibre | `CalibreClient`/`CalibreRepository` 接口 + 空壳 | 真实 `/ajax/*` 拉取、格式优先级、封面加载 |
| Room | `@Database`/实体/DAO 签名 | 真实查询、最近阅读排序、进度持久化 |
| Prefs | `SettingsRepository` 字段声明 | baseUrl/字号/主题真实读写 |
| 本地导入 | `LocalFileBookSource` 接口占位 | SAF/`ACTION_VIEW` 真实登记流程 |
| 书库 UI | `LibraryScreen` 静态占位 | 真实列表绑定、空态/加载态/错误态 |
| Sync | `NoOpSyncBackend` 空实现 | LWW/Union 合并、KOSync 兼容 |

## 七、风险与回滚

- **build-logic composite build 引入失败**：`includeBuild` 配置错误会让全仓构建炸。回滚 = 移除 `includeBuild` 行，模块退回直接配置（§10.2 注：插件落地前各模块重复配置可接受）。
- **Room ksp + Kotlin 2.1.10 版本兼容**：ksp `2.1.10-1.0.31` 已与 kotlin 对齐；若 schema 导出报错，先验证 `room = 2.7.1` 与 ksp 协同（Step 3 单独 assemble 即可早暴露）。
- **每步独立可编译**：Step 2→5 逐模块 assemble，任一步失败不污染下一步，可单点回滚到上一步快照。
- IMPLEMENTATION GATE：本文档仅规划，跨入 Step 1 起创建 `.kt`/`.kts` 实现代码需用户显式许可。

## 八、与 v4 文档的对应索引

- 模块清单：§3.1（22 模块，phase1 取 9）
- 分层依赖规则：§3.2 / CI 约束：§3.3
- 数据类型定义：§6.3 / §7.1–7.8
- Extension SPI：§8 · DI 绑定约定：§9
- Convention plugins：§10.2 · 分阶段 include：§10.3（仓库已落 `phaseInclude`）
- Phase 1 产出与结束态：§11 Phase 1
