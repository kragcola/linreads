# Active Work

_最后更新：2026-06-21_

Mode: `task`
Objective: v4lite 本地阅读闭环（用户验收 Phase A）— **L1–L5 全部 ✅**，Phase A 功能条件已满足
Active tracker: [linreads-architecture-docs-2026-06-18.md](linreads-architecture-docs-2026-06-18.md)
v4lite 执行文档: [../android-v4lite-plan.md](../android-v4lite-plan.md)
Test ledger: N/A

> ⛔ **IMPLEMENTATION GATE**：已于 2026-06-19 获用户放行。2026-06-20 `38367f5` 将 v4lite L1–L5 全部落地。后续 27 个提交为体验打磨。

## 当前状态

- Phase 1 地基框架 ✅、v4lite L1–L5 全部完成 ✅
- `-Preadflow.phase=2 :app:assembleDebug` SUCCESSFUL
- 真机通过 OTA 安装验证 ✅
- EPUB/PDF/TXT/MD 四引擎就位
- Settings（URL/字号/主题/更新）可用
- OTA 更新系统完整（检查→下载→进度→安装）
- 非 v4lite 原计划附加：Dwell 建组、拖拽排序、文件夹批量导入、Bundle 详情页

## v4lite 阶段状态

| Phase | 目标 | 状态 |
|-------|------|------|
| L1 书库接入 | SAF 导入 + 书架真实数据 | ✅ 完成（`38367f5`） |
| L2 Reader 完善 | TXT 进度/字号/主题/chrome | ✅ 完成（`38367f5`） |
| L3 EPUB 原生重排 | jsoup→AnnotatedString，连续滚动 | ✅ 完成（`38367f5`，基础连续滚动） |
| L4 PDF 引擎 | PdfRenderer + 分页 | ✅ 完成（`38367f5`） |
| L5 Phase A 验收门 | ACTION_VIEW / 性能 / TalkBack | ✅ 功能完成；性能测量和 TalkBack 测试待独立验证 |

## Phase A 验收检查清单

- [x] 安装后不需要账号/Calibre/网络/选引擎
- [x] SAF 导入 TXT/EPUB/PDF 后书架刷新显示
- [x] ACTION_VIEW 从文件管理器打开可直接阅读
- [x] 最近阅读列表正确（lastReadAt 降序）
- [x] 关闭再开书进度恢复（TXT/EPUB/PDF 各有 locator）
- [x] 字号调整 + 主题切换（白/暗/护眼/系统）生效
- [x] 进度/书架数据离线本地存储（Room，无网络可用）
- [ ] APK < 25MB 和冷启动 < 2s 性能测量
- [ ] TalkBack smoke 测试

## 剩余差距

| 项目 | 说明 |
|------|------|
| L3 EPUB 分页模式 | 当前仅连续滚动；ViewPager2 分页为 v4 最高风险项，待独立 gate |
| L5 性能测量 | 冷启动/首屏/翻页/内存峰值未正式测量 |
| L5 TalkBack | 无障碍 smoke test 未执行 |
| EPUB 总进度分母 | totalProgression 预扫全书字符数待实现（v4 §7.1） |
| 进度同步 | LWW 骨架已就位（NoOpSyncBackend），实际后端待做 |
| 进程死亡恢复 | SavedStateHandle + EngineStateStore 两层恢复待验证 |
| 数据导出 | LinReads Backup ZIP 待实现 |

## 下一步

- Phase A 性能测量 + TalkBack smoke
- EPUB 分页模式 gate（v4 最高技术风险）
- Calibre 书源接入（Phase B）
- 进度同步后端选型与实现（Phase D）

## 恢复备注

- v4lite L1–L5 已全部完成，当前在体验打磨阶段
- 27 个增量提交（6/20–6/21）主要是 OTA 更新系统 + 6 项需求完善
- 详细证据、风险、回滚见 active tracker
