# Platform: Web

## 技术栈

| 类别 | 选型 | 版本 |
|------|------|------|
| 框架 | React | ^18.3.1 |
| 语言 | TypeScript | ^5.x |
| 构建 | Vite | ^5.x |
| EPUB 渲染 | epubjs 当前实现 / epub-ts 目标 | ^0.3.93 / target |
| HTTP | axios | ^1.7.7 |
| 路由 | react-router-dom | ^6.26.0 |

## 路由

| 路径 | 组件 | 功能 |
|------|------|------|
| `/library` | `Library.tsx` | 书库列表，搜索/封面/点击进入阅读 |
| `/read/:id` | `Reader.tsx` | EPUB（当前 epubjs，目标 epub-ts）或 PDF（iframe） |

## Calibre 代理

Vite dev 服务将 `/calibre/*` 代理到 `VITE_CALIBRE_URL`（默认 `http://localhost:8080`），绕过 CORS：

```typescript
// vite.config.ts
server: {
  proxy: {
    '/calibre': {
      target: env.VITE_CALIBRE_URL || 'http://localhost:8080',
      changeOrigin: true,
      rewrite: (path) => path.replace(/^\/calibre/, ''),
    }
  }
}
```

生产部署需在 nginx / CDN 层做同等配置。

## EPUB 渲染

当前代码使用 epubjs 0.3.x。架构目标为迁移到 `@likecoin/epub-ts`（epubjs v0.3.93 的 TypeScript 重写）；涉及 EPUB/CFI/书签/渲染前必读 `.claude/skills/linreads-epub/SKILL.md`。

当前状态：基础 EPUB 阅读可用，CFI 书签/字体大小/主题切换待实现。

## PDF 渲染

通过 `<iframe src="...">` 加载 PDF URL，依赖浏览器内置 PDF 查看器。简单可用，但无自定义 UI 控制。

## 环境配置

在 `web/.env.example` 里查看环境变量；复制为 `.env.local` 修改 `VITE_CALIBRE_URL`。

## 开发命令

```bash
cd web && npm install && npm run dev     # 启动开发服务器
cd web && npm run build                   # 生产构建
cd web && npx tsc --noEmit               # TypeScript 类型检查
```

---

_参考：_ [docs/architecture.md](../architecture.md) · [Calibre API](Calibre-API.md)
