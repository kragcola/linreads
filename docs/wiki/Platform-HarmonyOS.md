# Platform: HarmonyOS

## 技术栈

| 类别 | 选型 |
|------|------|
| 语言 | ArkTS |
| UI 框架 | ArkUI |
| HTTP 客户端 | @ohos.net.http |
| 本地存储 | relationalStore（计划） |
| 构建工具 | DevEco Studio |

## 目录结构

```
entry/src/main/ets
├── pages/Index.ets            # 主页，加载书库列表
├── components/BookList.ets    # 书单列表组件
└── services/CalibreService.ets # HTTP 客户端
```

## 当前状态

⚠️ **基础列表阶段** — `Index.ets` 调用 `CalibreService.search()`，`BookList.ets` 已能渲染基础书单。`BASE_URL` 仍为默认内网地址 `192.168.1.1:8080`，需接 Settings 页持久化配置。

## CalibreService

`CalibreService.ets` 使用 `@ohos.net.http` 进行 HTTP 请求（不能用 fetch/axios）：

```typescript
// 关键注意点
// 1. 使用 @ohos.net.http，不是 fetch
// 2. BASE_URL 当前是默认内网地址，待 Settings 页持久化配置
// 3. 需处理 HTTP Basic Auth（Calibre 可选认证）
```

## 待实现

### P0 — 书库列表
- 完善书单 UI、封面和错误状态
- `BASE_URL` 通过 Settings 页持久化设置

### P1 — EPUB 阅读
- 选型待定：WebView + epub-ts 或原生 ArkUI 渲染
- 需评估 HarmonyOS WebView 对 epub-ts 的兼容性

### P2 — PDF 阅读
- HarmonyOS 原生 PDF 组件或 WebView 方案

### P3 — 进度同步
- relationalStore 本地存储
- 与 Android/Web 端同步协议对接

## 已知限制

- HarmonyOS 生态工具链与 Android/iOS 不同，部分库需要移植或寻找替代
- `@ohos.net.http` API 与 fetch/axios 不同，`CalibreService.ets` 已封装差异
- 手写笔注记方案需独立调研（androidx.ink 仅 Android）

---

_参考：_ [Architecture](Architecture.md) · [Calibre API](Calibre-API.md)
