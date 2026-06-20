# Active Work & Roadmap

_最后更新：2026-06-18_
_当前 HEAD：44f0a64_

## 当前状态

三端脚手架已就位，9-skill 技能栈已安装，项目文档已初始化。
**下阶段目标：Android + HarmonyOS 书库列表落地，Web EPUB 阅读器增强。**

## 平台完成度

| 功能 | Web | Android | HarmonyOS |
|------|-----|---------|-----------|
| 书库列表 | ✅ | ⚠️ scaffold | ✅ 基础列表 |
| EPUB 阅读 | ✅ 基础 | ❌ | ❌ |
| PDF 阅读 | ✅ iframe | ❌ | ❌ |
| TXT 阅读 | ❌ | ❌ | ❌ |
| MD 阅读 | ❌ | ❌ | ❌ |
| DOCX 阅读 | ❌ | ❌ | ❌ |
| 进度同步 | ❌ | ❌ | ❌ |
| 手写注记 | N/A | Phase 2 | 待调研 |

## 待办（优先级顺序）

### P0 — Android 书库列表
- [ ] `LibraryScreen()` 接入 `CalibreClient`（目前只返回占位 Text）
- [ ] Settings 里配 baseUrl
- [ ] 基础 LazyColumn 书单 UI（封面、书名、作者）

### P1 — HarmonyOS 书库列表
- [x] `BookList.ets` 基础书单渲染
- [ ] 完善封面、错误状态和空状态
- [ ] `BASE_URL` 通过 Settings 页持久化设置（当前默认内网地址）

### P2 — Web EPUB 增强
- [ ] CFI 书签保存
- [ ] 字体大小 / 主题切换
- [ ] 键盘翻页 / 手势翻页

### P3 — 三端进度同步
- [ ] 同步后端方案选型（自建轻量 HTTP server vs 本地 P2P）
- [ ] 本地存储实现（Room / relationalStore / IndexedDB）
- [ ] LWW + Union 同步逻辑

### P4 — Android 渲染引擎
- [ ] EPUB 原生重排引擎（jsoup 解析 → AnnotatedString，去 WebView）+ ReaderEngine 实现
- [ ] PdfRenderer + ReaderEngine 实现
- [ ] TxtVirtualPager 实现
- [ ] Markwon MD 渲染
- [ ] ViewPager2 PageTransformer

### P5 — HarmonyOS 阅读器
- [ ] EPUB 渲染方案选型与实现
- [ ] PDF 渲染

### P6 — 手写注记（Android Phase 2）
- [ ] androidx.ink 集成
- [ ] InkOverlay + 笔/手指自动路由
- [ ] Room 存储已完成笔画

## 已完成

- [x] 三端初始脚手架（Web 功能完整，Android/HarmonyOS scaffold）— `5242e1d`
- [x] 9-skill 技能栈安装 — `44f0a64`
- [x] CLAUDE.md、architecture wiki、maintenance-log、ACTIVE.md
- [x] 静读天下反编译分析 + 架构评审
- [x] 渲染引擎选型分析
- [x] Android 手写笔可行性调研
- [x] 项目 Wiki 创建

## 接手此任务的 Agent 须知

1. 改 Calibre API shape 前先看 `shared/api/calibre-contract.ts`，改后三端同步
2. 碰 Android EPUB 渲染读 `docs/android-architecture-v4.md` §5.5/§12.3（原生重排，去 WebView）；Web EPUB 仍是 epubjs，见 `.claude/skills/linreads-epub/SKILL.md`
3. Web 端 `/calibre` 代理配置在 `web/vite.config.ts`（dev）；prod 部署自行处理 CORS
4. Android 测试命令：`cd android && ./gradlew test`
5. HarmonyOS `CalibreService.ets` 的 `get()` 方法用 `@ohos.net.http`，不能用 fetch/axios
6. 中文 UI，英文代码/注释/commit

---

_参考：_ [docs/tracking/ACTIVE.md](../tracking/ACTIVE.md) · [maintenance-log.md](../../maintenance-log.md)
