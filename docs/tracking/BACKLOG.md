# Backlog — 全部未处理发现

> 最后更新：2026-06-18（根因裁决后）
> 来源：终审 v1 + 审计 v2 + Round 3 终审 + 用户独立审计 12 项

---

## ✅ 根因裁决（#1-#12 全部消解）

- [x] **#1 ReaderState** → `core:model`，不含 View。ViewModel 内部私有持有 View 引用
- [x] **#2 Locator** → `core:model` 唯一定义，render:api import 使用
- [x] **#3 BookSource** → 采用 Addendum 版本，主文档同步
- [x] **#4 Platform-Android.md** → DEPRECATED，指向 v2
- [x] **#5 Ink 跨层** → InkAnchor 移入 core:model，DB 层用 anchorJson: String 保持类型无关
- [x] **#6 模块计数** → 统一 21
- [x] **#7 TransitionType** → 统一 SCREAMING_SNAKE_CASE
- [x] **#8 渲染模块** → 统一 7 引擎（epub/pdf/txt/mupdf/md/animate + api）
- [x] **#9 render:api 定位** → 接口层，不含类型定义
- [x] **#10 用户 override** → ReaderEngineRegistry.setUserOverride(format, engineId)
- [x] **#11 View 生命周期** → ReaderEngine.onViewAttached/Detached + saveState/restoreState
- [x] **#12 沙箱冲突** → ReaderState 不含 View 后自然消解

## ✅ Round 3 修复（前一轮完成）

- [x] **HIGH #1 ReaderState 双定义** — 统一 Locator ∈ core:model，消除循环依赖
- [x] **HIGH #2 BookSource 7处签名差异** — 以 addendum 为准
- [x] **HIGH #3 Locator 主文档去重** — 从 render:api 删除定义，保留 core:model
- [x] **HIGH #4 core:model→render:api 循环依赖** — 修正 import 指向 core:model
- [x] **MEDIUM #5 模块计数** — 统一 19→21
- [x] **MEDIUM #6 TransitionType 命名** — 统一 SCREAMING_SNAKE_CASE
- [x] **LOW #7 epubjs 残留** — 全部改为 epub-ts
- [x] **LOW #8 InkBrush Compose 泄漏** — 改用 android.graphics.Color
- [x] **libs.versions.toml 补全** — 新增 kotlinx-serialization-json + material-icons-extended + kotlin-jvm plugin + kotlin-serialization plugin
- [x] **root build.gradle.kts** — 改用 version catalog alias
- [x] **app/build.gradle.kts** — 添加 material-icons-extended 依赖

---

## 🔴 P0 — 构建阻塞（执行则编译失败）

- [x] **nanohttpd 不在 `libs.versions.toml`** — ✅ 已添加到 `gradle/libs.versions.toml`
- [x] **MuPDF 仓库缺失** — ✅ `maven.ghostscript.com` 已添加到 `settings.gradle.kts`
- [x] **Gradle wrapper 未生成** — ✅ 已生成 `gradlew` + `gradle/wrapper/`（Gradle 8.11）
- [x] **Compose BOM 版本过时** — ✅ `2024.12.01` → `2026.06.00`
- [x] **`kotlinCompilerExtensionVersion` 移除** — ✅ 迁移到 `org.jetbrains.kotlin.plugin.compose` gradle plugin
- [x] **移除 epublib-core** — ✅ 已从 `app/build.gradle.kts` 移除，注释中标注

## 🟠 P1 — 架构一致性（文档内部矛盾）

- [x] **Locator 重复定义** — ✅ 代码验证：唯一定义在 `core/model/Locator.kt`，`:render:api` 无重复
- [x] **ExtensionContext→ReaderState 跨层依赖违规** — ✅ 代码验证：`ExtensionContext` 仅引用 `ReaderEventBus`，无 `ReaderState` import
- [x] **模块计数统一** — ✅ 权威架构统一为 21 个模块
- [x] **ReaderRootLayout.kt 位置冲突** — ✅ 统一为 `:features:reader`，修正代码注释中的路径
- [x] **PDF API 等级统一** — ✅ 全文统一为 minSdk=26
- [x] **foliate-js vs epubjs 矛盾** — ✅ 统一为 **epub-ts**，foliate-js 降为未来备选注释

## 🟡 P2 — 数据模型补全

- [x] **ReaderState 补全字段** — ✅ 完整定义含 `loadingState`, `isUiVisible`, `bookId`, `zoomLevel`, `panOffset`（见 v2-addendum）
- [x] **InkBrush 类型定义** — ✅ sealed class with Pen/FountainPen/Highlighter/Eraser（见 v2-addendum）
- [x] **ExtensionSettings 类型定义** — ✅ data class with TtsConfig/StatsConfig（见 v2-addendum）
- [x] **DownloadStatus 枚举** — ✅ NOT_DOWNLOADED/DOWNLOADING/DOWNLOADED/FAILED（见 v2-addendum）
- [x] **SyncBackend 接口** — ✅ 完整签名 + NoOpSyncBackend Phase 1 实现（见 v2-addendum）
- [x] **CalibreClient 接口提取** — ✅ BookSource 接口 + CalibreBookSource 实现（见 v2-addendum）

## 🟢 P3 — 架构缺口

- [x] **离线书籍缓存策略文档化** — ✅ LRU 5本 + 手动下载 + WorkManager + 离线模式切换（见 v2-addendum）
- [x] **同步后端策略文档化** — ✅ KorroSync 主路径 + WebDAV 兜底 + Phase 1 no-op（见 v2-addendum）
- [x] **渲染模块补齐** — ✅ 7 个渲染模块（api/epub/pdf/txt/mupdf/md/animate），总模块数 21（见 v2-addendum）
- [x] **性能预算** — ✅ 冷启动<2s, EPUB打开<1s, 翻页<50ms, 内存<200MB, APK<25MB（见 v2-addendum）
- [x] **测试策略** — ✅ 每层框架+Mock策略+fakes over mocks（见 v2-addendum）
- [x] **安全策略** — ✅ LAN白名单 + WebView CSP + EncryptedSharedPreferences（见 v2-addendum）
- [x] **无障碍策略** — ✅ TalkBack + 字体缩放 + 高对比度 + 键盘导航 + TTS（见 v2-addendum）
- [x] **大屏/折叠屏适配** — ✅ 双页模式 + WindowSizeClass + 分屏 + Drag&Drop（见 v2-addendum）
- [x] **KMP 策略声明** — ✅ Phase 1 不做KMP, 类型契约共享, 2027评估（见 v2-addendum）

## 🔵 新发现 — 本轮搜索新增

- [x] **epub-ts 性能评估并写入架构文档** — ✅ 已替代 epubjs 为默认引擎
- [x] **episteme KMP 架构评估** — ✅ 已记录 SharedEpubBook 统一中间表示模式
- [x] **episteme PDF ink 方案评估** — ✅ 已记录纯KMP ink rendering 参考
- [x] **episteme MOBI 纯 Kotlin parser 评估** — ✅ 已记录
- [x] **episteme 自定义 epub_reader.js 评估** — ✅ 已记录 11 JS bridge 模式
- [x] **episteme chunk virtualization 评估** — ✅ 已记录
- [x] **readbooks-v2 离线下载模式评估** — ✅ 已记录 DownloadBookWorker 模式
- [x] **readbooks-v2 Publication 缓存模式评估** — ✅ 已记录 PublicationHolder @Singleton
- [x] **readbooks-v2 阅读会话追踪评估** — ✅ 已记录 ViewModel lifecycle session
- [x] **korrosync API 兼容性评估** — ✅ 已记录 5 端点 REST API
- [x] **dom-anchor-text-position 集成评估** — ✅ 已记录
- [x] **@net7/annotator 调研** — ✅ 已记录

## ⚪ 技能栈更新

- [x] **skill 目录重命名** — ✅ `readflow-*` → `linreads-*`（3个目录 + SKILL.md 内容）
- [x] **CLAUDE.md 更新** — ✅ 项目名、命令、新依赖版本、skill 名、引擎策略
- [x] **CLAUDE.md 命令表修正** — ✅ 添加 lint 命令
- [ ] **ARCHITECTURE.md 更新** — 指向 v2 文档（待用户确认后更新）

---

_总待办数：41 项（P0: 6 / P1: 6 / P2: 6 / P3: 9 / 新发现: 12 / 技能: 4）_
