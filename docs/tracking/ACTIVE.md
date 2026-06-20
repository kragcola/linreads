# Active Work

_最后更新：2026-06-20_

Mode: `task`
Objective: v4lite 本地阅读闭环（用户验收 Phase A）— TXT ✅ + EPUB + PDF + 书库接入
Active tracker: [linreads-architecture-docs-2026-06-18.md](linreads-architecture-docs-2026-06-18.md)
v4lite 执行文档: [../android-v4lite-plan.md](../android-v4lite-plan.md)
Test ledger: N/A

> ⛔ **IMPLEMENTATION GATE**：已于 2026-06-19 获用户放行 Android Phase 1 地基 + TXT 最小阅读切片实装。v4lite 后续阶段（L1–L5）按 [android-v4lite-plan.md](../android-v4lite-plan.md) 推进，需用户逐阶段确认后实装。

## 当前状态

- Phase 1 地基框架 ✅、TXT 最小阅读链路 ✅（`-Preadflow.phase=2 :app:assembleDebug` SUCCESSFUL）
- **v4lite 计划文档已创建**：`docs/android-v4lite-plan.md`，划分 L1–L5 五阶段，目标 Phase A 本地阅读闭环
- TXT 真机/AVD 验证待完成（需 AVD system-image）

## v4lite 阶段状态

| Phase | 目标 | 状态 |
|-------|------|------|
| L1 书库接入 | SAF 导入 + 书架真实数据 | ⬜ 待实装 |
| L2 Reader 完善 | TXT 进度/字号/主题/chrome | ⬜ 待实装 |
| L3 EPUB 原生重排 | jsoup→AnnotatedString，连续滚动 | ⬜ 待实装（最高风险）|
| L4 PDF 引擎 | PdfRenderer + 分页 | ⬜ 待实装 |
| L5 Phase A 验收门 | ACTION_VIEW / 性能 / TalkBack | ⬜ 待实装 |

## 下一步

- 用户确认 v4lite 计划 → 从 **L1 书库接入**开始实装
- L1 优先任务：`LibraryRepository` 实现 + `LibraryScreen` SAF picker + Navigation 书架→阅读器

## 恢复备注

- 详细证据、风险、回滚见 active tracker
- v4lite 各阶段验收清单见 [android-v4lite-plan.md §八](../android-v4lite-plan.md)
