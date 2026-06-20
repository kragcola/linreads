# LinReads Wiki

自用三端阅读器——Android · HarmonyOS · Web。灵感来自 [静读天下 (Moon+ Reader)](https://www.moondownload.com/)，通过 Calibre Content Server 局域网接入书库，不依赖云服务。

## 项目定位

- **本地优先**：书库托管在本地 Calibre，无云依赖
- **三端覆盖**：Android（Kotlin/Compose）、HarmonyOS（ArkTS/ArkUI）、Web（React/TypeScript）
- **目标格式支持**：EPUB、PDF、TXT、MD、DOCX，优先级 EPUB > PDF > MOBI；当前可用能力以平台状态表为准
- **开放可扩展**：渲染引擎接口化，格式插件化；Android EPUB 采用 Hybrid View + 原生重排，PDF/TXT/MD/DOCX/CBZ 各走专用引擎

## 快速导航

| 页面 | 内容 |
|------|------|
| [Architecture](Architecture.md) | 系统总览、共享契约、端架构对比 |
| [Platform: Web](Platform-Web.md) | React 18 + epubjs（current）/ epub-ts（Web 迁移目标）+ Vite 技术细节 |
| [Platform: Android](Platform-Android.md) | 历史页（deprecated）；当前权威规范见 Android Architecture v4 |
| [Platform: HarmonyOS](Platform-HarmonyOS.md) | ArkTS + ArkUI 技术细节 |
| [Calibre API](Calibre-API.md) | Calibre Content Server REST API 参考 |
| [Rendering Engine](Rendering-Engine.md) | 各格式渲染方案选型与大文件策略 |
| [Progress Sync](Progress-Sync.md) | 三端阅读进度同步策略（LWW/Union） |
| [Research: Moon+ Reader](Research-MoonReader.md) | 静读天下架构评审与启示 |
| [Research: Stylus & Ink](Research-Stylus.md) | Android 手写笔注记可行性 |
| [Development Guide](Development-Guide.md) | 开发环境、命令、规范 |
| [Active Work & Roadmap](Active-Work.md) | 当前状态、待办、演进路径 |

## 系统架构速览

```
┌────────────────────────────────────────────────────┐
│       Calibre Content Server (LAN :8080)           │
│  GET /ajax/search  /ajax/books  /get/<fmt>/<id>    │
└────────────────────────────────────────────────────┘
           │               │               │
      ┌────┘           ┌───┘           ┌───┘
      ▼                ▼               ▼
┌──────────┐    ┌────────────┐   ┌──────────────┐
│   Web    │    │  Android   │   │  HarmonyOS   │
│ React 18 │    │ Kotlin +   │   │ ArkTS +      │
│ epubjs*  │    │ Compose +  │   │ ArkUI        │
│ Vite     │    │ Ktor       │   │              │
└──────────┘    └────────────┘   └──────────────┘
```

`*` Web 使用 `epubjs`（WebView/JS）。**Android v4 不复用此路线**，EPUB 走自研原生重排（jsoup→AnnotatedString，去 WebView，见 v4 §12.3）。

## 平台状态

| 平台 | 书库列表 | EPUB 阅读 | PDF 阅读 | 进度同步 | 手写注记 |
|------|---------|----------|---------|---------|---------|
| Web | ✅ | ✅ 基础 | ✅ iframe | ❌ 待做 | N/A |
| Android | ⚠️ scaffold | ❌ 待做 | ❌ 待做 | ❌ 待做 | Phase 2 |
| HarmonyOS | ✅ 基础列表 | ❌ 待做 | ❌ 待做 | ❌ 待做 | 待调研 |

注：`dev.readflow`、`ReadflowApp`、Android/HarmonyOS 展示名等技术标识尚未做代码级迁移；当前漂移修正只收文档口径。

## 关键设计决策

1. **共享类型契约** — `shared/api/calibre-contract.ts` 是三端共同的 API 类型定义，是唯一真相来源
2. **格式独立渲染** — 不重写 MRTextView 式统一引擎，各格式走各自最优路径
3. **Hybrid View Android 阅读器** — EPUB 用原生重排（jsoup→AnnotatedString，去 WebView）；PDF/TXT/MD/DOCX/CBZ 使用原生或成熟引擎
4. **离线优先同步** — 本地先写，后台异步推送；LWW 解决进度冲突，Union 合并标注
5. **ViewPager2 翻页动效**（Android）— 默认 SlideFade，Phase 2 预留 Curl PageTransformer

## 仓库结构

```
readflow/
├── shared/api/calibre-contract.ts   # 三端共享类型契约
├── shared/calibre/api.md            # Calibre API 文档
├── web/                             # Web 端 (React 18 + TypeScript)
├── android/                         # Android 端 (Kotlin + Compose)
├── harmony/                         # HarmonyOS 端 (ArkTS)
├── docs/wiki/                       # 项目 Wiki（本目录）
├── docs/research/                   # 调研报告
├── docs/tracking/                   # 任务跟踪
├── CLAUDE.md                        # Agent 指令
└── maintenance-log.md               # 维护日志
```

---

_最后更新：2026-06-18_
