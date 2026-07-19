# Active Work & Recovery

_最后更新：2026-07-19_
_文档角色：当前状态入口（非脚手架路线图）_

## 权威入口

| 用途 | 文档 |
|------|------|
| **现网恢复 / 当前任务权威** | [docs/tracking/ACTIVE.md](../tracking/ACTIVE.md) |
| Android 架构权威 | [docs/android-architecture-v4.md](../android-architecture-v4.md)（22 模块，原生 EPUB，PdfRenderer） |
| 纯阅读缺口验收表 | [docs/tracking/android-v4-pure-reading-gap-checklist.md](../tracking/android-v4-pure-reading-gap-checklist.md) |
| 未完成项回填总表 | [docs/tracking/android-v4-pure-reading-unfinished-backfill.md](../tracking/android-v4-pure-reading-unfinished-backfill.md) |
| 三端总览 | [docs/architecture.md](../architecture.md) · 仓库根 [CLAUDE.md](../../CLAUDE.md) |
| 历史审计台账（非 live backlog） | [docs/tracking/BACKLOG.md](../tracking/BACKLOG.md) |

> **接手规则**：以 `ACTIVE.md` 的最新条目与 v4 源码为准。本页只提供导航与平台快照；不要把下方「历史里程碑」当当前 TODO。

## 平台快照（与 CLAUDE.md 对齐）

| 功能 | Web | Android | HarmonyOS |
|------|-----|---------|-----------|
| 书库列表 | ✅ | ✅ v4lite（本地导入 + Calibre LAN） | ✅ 基础列表 |
| EPUB 阅读 | ✅ 基础（epubjs） | ✅ 原生重排（无 WebView / CFI） | ❌ 待做 |
| PDF 阅读 | ✅ iframe | ✅ PdfRenderer | ❌ 待做 |
| TXT / MD | ❌ | ✅ | ❌ |
| 进度同步 | ❌ | ⚠️ LWW 骨架 | ❌ |
| OTA | N/A | ✅ phase2 | N/A |

Android 补充：Reader 持续体验打磨中；本地 JVM 聚焦回归与外部设备门（物理平板、OEM system-bar、TalkBack speech、真实 PDF 文本层语料等）见 `ACTIVE.md`。工作树可能含未提交 Reader 改动——描述现状时勿仅凭「已提交 HEAD」断言。

## 当前工作方向（摘要）

1. **Android Reader 体验**：排版/字体、书架、书签与搜索、菜单架构、跨格式 capability 投影；以 `ACTIVE.md` 最新审计为准。
2. **产品门仍偏外部**：物理设备帧时/视觉、真人无障碍、真实 Calibre/LAN、API 36/OEM PDF 文本层等，不能单靠 JVM/AVD 宣称最终完成。
3. **Web**：保持 epubjs 基础阅读；CFI/主题等增强非 Android 同步路线。
4. **HarmonyOS**：书库基础可用；阅读器格式与同步待做。

## 接手 Agent 须知

1. 改 Calibre API shape 前先看 `shared/api/calibre-contract.ts`，改后三端同步
2. Android EPUB：读 `docs/android-architecture-v4.md` §5.5 / §12.3（原生重排，去 WebView）；**不要**按 v3 的 WebView+epub-ts 实现
3. Web EPUB：仍是 epubjs，见 `.claude/skills/linreads-epub/SKILL.md`
4. Web `/calibre` 代理在 `web/vite.config.ts`（dev）
5. Android 本地验证：定向 Gradle 单测即可；全量回归 / R8 / OTA 走 GitHub Actions
6. HarmonyOS `CalibreService.ets` 用 `@ohos.net.http`，不能用 fetch/axios
7. 中文 UI，英文代码/注释/commit
8. 现网任务进度只写 `ACTIVE.md`（Codex 整合 tracker）；本 wiki 页只导航

---

## 历史里程碑（2026-06 脚手架期，非当前状态）

以下内容保留作时间线，**不是**当前路线图：

- 2026-06-18 前后：三端脚手架、9-skill 技能栈、文档与 wiki 初始化（当时 Android 书库/阅读多为 scaffold）
- 2026-06-20：`38367f5` 起 v4lite L1–L5 落地；其后进入 Reader 体验与纯阅读缺口回填
- 静读天下反编译、渲染选型、手写笔调研等早期研究仍在 `docs/research/` 与 `docs/audit/`

旧版本页曾把 P0「Android 书库列表」等列为未勾选 TODO；该阶段已过，细节以 `ACTIVE.md` 与 v4 回填表为准。

---

_参考：_ [ACTIVE.md](../tracking/ACTIVE.md) · [maintenance-log.md](../../maintenance-log.md) · [architecture.md](../architecture.md)
