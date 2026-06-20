# Android Phase 2 — TXT 最小阅读链路（垂直切片）

_创建：2026-06-19 · 依据：[android-architecture-v4.md](android-architecture-v4.md) §5 / §9 · 在 [P1 地基](android-p1-foundation-plan.md) 之上_

> **目的**：以 **TXT 单一格式**打通「解析→引擎→View→reader→app」端到端最小链路，验证 v4 §5 渲染契约在真机/构建上成立。**不是**完整阅读器：无 chrome/搜索/TOC/选择/ink。
> **范围决断**：测试文件走 bundled `assets/sample.txt`（零导入逻辑）；按 §5.6 建 `NoTransitionHost`（CONTINUOUS 直挂）。

## 一、必要组件（最小集）

| 层 | 模块 | 类 | 最小职责 |
|----|------|----|---------|
| L2 | `:render:api` | `ReaderEngine`/`ReadingMode` | 引擎接口 + 线程契约 |
| L2 | `:render:api` | `EngineDescriptor`/`ReaderEngineRegistry`/`NoEngineException` | 按扩展名解析 |
| L2 | `:render:api` | `PageTransitionHost`/`PagingKind`/`PageTransitionHostFactory` | 宿主抽象 |
| L3 | `:render:txt` | `TxtVirtualPagerEngine` | 文件→段落 RecyclerView，ByteOffset 定位 |
| L3 | `:render:animate` | `NoTransitionHost`/`DefaultPageTransitionHostFactory` | CONTINUOUS 直挂 |
| L6 | `:features:reader` | `ReaderIntent`/`ReaderViewModel`/`ReaderScreen` | MVI，仅依赖 `:render:api` |
| L8 | `:app` | Koin 绑定 + 导航 + `assets/sample.txt` | 组装注入 |

空壳（满足 phase=2 resolve，无源码）：`:render:epub`/`:render:pdf`/`:render:md`/`:features:settings`。

## 二、跳过项（最小化边界）

`EngineStateStore`（saveState 默认空）、Ink、chrome（TopBar/BottomBar）、搜索/TOC/文字选择、ICU4J 编码探测（最小仅 UTF-8）、字号/主题实时调、FileChannel 64KB 流式（最小一次性读小文件）。

## 三、执行步骤 → 验证（已执行 ✅）

1. ✅ `:render:api` 三组接口 → `:render:api:assemble` 通过。
2. ✅ `:render:txt` 引擎（CONTINUOUS、RecyclerView、UTF-8 读取、progression 上报） → `:render:txt:assemble`，约束 1 grep 无 compose。
3. ✅ `:render:animate` NoTransitionHost + factory → `:render:animate:assemble`。
4. ✅ `:render:epub`/`:render:pdf`/`:render:md`/`:features:settings` 空壳 build.gradle.kts → phase=2 resolve。
5. ✅ `:features:reader` MVI（仅依赖 `:render:api`） → 约束 3 grep 无具体引擎。
6. ✅ `:app` 绑定 TXT descriptor + factory + 导航 + `assets/sample.txt`。
7. ✅ `-Preadflow.phase=2 :app:assembleDebug` → BUILD SUCCESSFUL。

**关键工程决断（执行中补充）**：phase1 app 不能引用 phase2 才在场的 `render:*` 模块（F9），否则 `-Preadflow.phase=1` 解析 `:app` 依赖时报 `project not found`。解法：`:app` 用 phase 条件 sourceSet——`src/phase1/`（foundation 版 AppModules/ReadflowApp，零 render 依赖）与 `src/phase2/`（TXT slice 版），`build.gradle.kts` 按 `readflow.phase` 切 srcDir + 条件 `implementation(project(":render:*"))`。phase1/phase2 各自独立可构建。

## 四、验证证据要求

- `-Preadflow.phase=2 :app:assembleDebug` → BUILD SUCCESSFUL。
- C1（§3.3 约束1）：`:render:txt` releaseCompileClasspath 无 compose。
- C3（§3.3 约束3）：`:features:reader` 依赖无 `render:(epub|pdf|txt|md)`，仅 `render:api`。
- phase=1 仍可独立构建（不被 phase=2 改动破坏）。
