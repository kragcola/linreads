# LinReads Architecture

> **→ Android 端架构设计见 [Android Architecture v3](android-architecture-v3.md)**（权威规范，v1/v2 已废弃）

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

`*` Web current implementation still uses `epubjs`; Android v3 and future EPUB work standardize on `@likecoin/epub-ts`.

## 共享契约

`shared/api/calibre-contract.ts` 定义三端共用的 Calibre API 类型（`BookMeta`、`SearchResult`、`CalibreConfig`）。这是**唯一真相来源**：改 API shape 必须先改这个文件，再同步三端实现。

## Web 端

**路由：**
- `/library` → `Library.tsx`：书库列表，搜索/封面/点击进入阅读
- `/read/:id` → `Reader.tsx`：EPUB（当前 epubjs，目标 epub-ts）或 PDF（iframe）

**Calibre 代理（绕 CORS）：**
Vite dev 服务将 `/calibre/*` 代理到 `VITE_CALIBRE_URL`（默认 `http://localhost:8080`）。生产部署需在 nginx / CDN 层做同等配置。

**EPUB 渲染注意事项：** 当前 Web 代码仍使用 epubjs 0.3.x；架构目标为 `@likecoin/epub-ts`。涉及 EPUB/CFI/书签/渲染前先读 `.claude/skills/linreads-epub/SKILL.md`。

**依赖关键版本：**

| 包 | 版本 | 说明 |
|----|------|------|
| react | ^18.3.1 | Concurrent features |
| epubjs | ^0.3.93 | 当前 Web EPUB renderer |
| react-router-dom | ^6.26.0 | SPA routing |
| axios | ^1.7.7 | HTTP client |

## Android 端

**技术栈：** Kotlin + Jetpack Compose + Ktor HTTP client

**包结构：**
```
dev.readflow
├── calibre/CalibreClient.kt   # HTTP 客户端，搜索/元数据/下载URL
├── ui/ReadflowApp.kt          # 顶层 Scaffold + 底部导航（书库/阅读/设置）
└── MainActivity.kt            # 入口
```

当前状态：UI 骨架完整，`LibraryScreen` 尚未接入 `CalibreClient`。Android v3 目标架构见 `docs/android-architecture-v3.md`，采用 21 模块、Hybrid View、`WebView + epub-ts` EPUB 引擎、PdfRenderer PDF 引擎、TxtVirtualPager TXT 引擎；旧 epublib/MuPDF-for-EPUB 路线已废弃。

注：`dev.readflow` 包名和 `ReadflowApp` 类名仍是当前技术标识，尚未做代码级改名。

## HarmonyOS 端

**技术栈：** ArkTS + ArkUI + `@ohos.net.http`

**目录结构：**
```
entry/src/main/ets
├── pages/Index.ets            # 主页，加载书库列表
├── components/BookList.ets    # 书单列表组件
└── services/CalibreService.ets # HTTP 客户端
```

当前状态：`Index.ets` 调用 `CalibreService.search()`，`BookList.ets` 已能渲染基础书单。`BASE_URL` 仍为默认内网地址 `192.168.1.1:8080`，需接 Settings 页持久化配置。

## 进度同步（待实现）

三端统一阅读进度设计要点（详见 `.claude/skills/linreads-sync/SKILL.md`）：

- **离线优先**：本地先写，后台异步推送
- **冲突策略**：阅读位置用 LWW（Last-Write-Wins，以时间戳为准）；书签/高亮用 Union（合并不删除）
- **存储**：Android → Room；HarmonyOS → relationalStore；Web → IndexedDB

同步服务端方案待定（Calibre 不提供同步 API，需自建轻量后端或 P2P）。

## 关键约束

1. **无云依赖**：书库在本地 Calibre，同步方案也必须离线友好
2. **CORS**：Calibre 服务端需开 `--cors` 或由客户端代理绕行
3. **格式支持**：EPUB/PDF 优先；MOBI/AZW3 依赖平台转换能力，不保证
4. **NapCat 条款不适用**：这是 LinReads，不是 omubot——没有 NapCat，没有 QQ bot
