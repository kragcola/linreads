# LinReads Architecture

> **Android 端当前权威规范**：[Android Architecture v4](android-architecture-v4.md)（22 模块，8 层）。v1/v2/v3 文档仅作历史参考，不再描述当前实现。

## System Overview

自用三端阅读器，灵感来自静读天下。书库托管在本地 Calibre Content Server（局域网），三端通过 HTTP REST 接入，不依赖云服务。

```
┌────────────────────────────────────────────────────┐
│          Calibre Content Server (LAN :8080)        │
│    GET /ajax/search  /ajax/books  /get/<fmt>/<id>  │
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

`*` Web 当前实现仍用 `epubjs`（浏览器 JS / iframe 宿主，非 Android WebView）。**Android v4 不走此路线**：EPUB 为自研原生重排（jsoup → Compose/AnnotatedString，无 WebView、无 epub-ts、无 CFI）。三端仅在 `shared/api` 类型契约层对齐，不要求引擎层一致。

## 共享契约

`shared/api/calibre-contract.ts` 定义三端共用的 Calibre API 类型（`BookMeta`、`SearchResult`、`CalibreConfig`）。这是**唯一真相来源**：改 API shape 必须先改这个文件，再同步三端实现。

## Web 端

**路由：**
- `/library` → `Library.tsx`：书库列表，搜索/封面/点击进入阅读
- `/read/:id` → `Reader.tsx`：EPUB（epubjs）或 PDF（iframe）

**Calibre 代理（绕 CORS）：**
Vite dev 服务将 `/calibre/*` 代理到 `VITE_CALIBRE_URL`（默认 `http://localhost:8080`）。生产部署需在 nginx / CDN 层做同等配置。

**EPUB 渲染注意事项：** 当前 Web 代码使用 epubjs 0.3.x。涉及 EPUB/CFI/书签/渲染前先读 `.claude/skills/linreads-epub/SKILL.md`。**不要**把 Web 的 epubjs/CFI 路径当作 Android 当前或目标方案。

**依赖关键版本：**

| 包 | 版本 | 说明 |
|----|------|------|
| react | ^18.3.1 | Concurrent features |
| epubjs | ^0.3.93 | 当前 Web EPUB renderer |
| react-router-dom | ^6.26.0 | SPA routing |
| axios | ^1.7.7 | HTTP client |

## Android 端

**权威文档：** [android-architecture-v4.md](android-architecture-v4.md)（22 模块 / 8 层）

**技术栈：** Kotlin + Jetpack Compose + Ktor HTTP client；阅读引擎在独立 `:render:*` 模块，经 `ReaderEngine` 可插拔注册。

**当前能力（v4lite 已落地，持续体验打磨）：**

| 能力 | 状态 | 实现要点 |
|------|------|----------|
| 书库 | ✅ | 本地导入 + Calibre LAN |
| EPUB | ✅ 原生重排 | jsoup 解析 → 流式/分页宿主；**无 WebView / epub-ts / CFI**；定位用 spine + 章节内字符偏移 + progression |
| PDF | ✅ PdfRenderer | 系统 `PdfRenderer`；搜索/划选能力随 open-session 的 framework 文本 API 动态投影（见 v4 §5.1 / PDF 引擎） |
| TXT / MD | ✅ | TxtVirtualPager + Markwon 路径 |
| 进度 | ⚠️ LWW 骨架 | 本地 Room/DataStore 优先；远程后端仍为 no-op 候选 |
| OTA | ✅ | phase2 updater + GitHub Actions `dev-latest` |

**模块与引擎（摘要，细节以 v4 为准）：**
- `:features:library` / `:features:reader` / `:features:settings`
- `:render:api`（`ReaderEngine` 契约）+ `:render:epub` / `:render:pdf` / `:render:txt` / `:render:md` / `:render:animate`
- `:core:model` / `:core:database` / `:core:prefs` / `:core:calibre` / `:core:sync` 等

历史说明：v3 曾规划 21 模块与 `WebView + epub-ts` EPUB 路线；该方案**已被 v4 取代**，不得再当作当前实现或目标。旧 epublib/MuPDF-for-EPUB 路线同样已废弃。

注：`dev.readflow` 包名和部分类名仍是技术标识，产品对外名称为 LinReads。

## HarmonyOS 端

**技术栈：** ArkTS + ArkUI + `@ohos.net.http`

**目录结构：**
```
entry/src/main/ets
├── pages/Index.ets            # 主页，加载书库列表
├── components/BookList.ets    # 书单列表组件
└── services/CalibreService.ets # HTTP 客户端
```

当前状态：`Index.ets` 调用 `CalibreService.search()`，`BookList.ets` 已能渲染基础书单。`BASE_URL` 仍为默认内网地址 `192.168.1.1:8080`，需接 Settings 页持久化配置。**EPUB / PDF 阅读与进度同步仍待实现。**

## 进度同步（待实现）

三端统一阅读进度设计要点（详见 `.claude/skills/linreads-sync/SKILL.md`）：

- **离线优先**：本地先写，后台异步推送
- **冲突策略**：阅读位置用 LWW（Last-Write-Wins，以时间戳为准）；书签/高亮用 Union（合并不删除）
- **存储**：Android → Room；HarmonyOS → relationalStore；Web → IndexedDB

同步服务端方案待定（Calibre 不提供同步 API，需自建轻量后端或 P2P）。Android 端已有 LWW 骨架与 `NoOpSyncBackend`，跨设备真实后端仍属后续里程碑。

## 关键约束

1. **无云依赖**：书库在本地 Calibre，同步方案也必须离线友好
2. **CORS**：Calibre 服务端需开 `--cors` 或由客户端代理绕行
3. **格式支持**：EPUB/PDF 优先；MOBI/AZW3 依赖平台转换能力，不保证
4. **NapCat 条款不适用**：这是 LinReads，不是 omubot——没有 NapCat，没有 QQ bot
