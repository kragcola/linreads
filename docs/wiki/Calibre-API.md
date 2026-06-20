# Calibre API

Calibre Content Server 默认端口 `8080`，支持 HTTP Basic Auth（可选）。

## 关键端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/ajax/search?query=&num=&offset=` | 搜索，返回 `{total_num, book_ids[]}` |
| GET | `/ajax/books?ids=1,2,3` | 批量元数据，返回 `{id: BookMeta}` map |
| GET | `/ajax/book/<id>/<library>` | 单本元数据 |
| GET | `/get/<format>/<id>/<library>` | 下载文件（EPUB/PDF/MOBI/AZW3） |
| GET | `/get/cover/<id>/<library>` | 封面图片 |

`<library>` 默认值：`calibre-library`

## 启动命令

```bash
# GUI 模式：Preferences → Sharing over network → Start server
# CLI 模式：
calibre-server --port 8080 /path/to/library

# 如需跨域访问，加 --cors 参数
calibre-server --port 8080 --cors /path/to/library
```

## Web 跨域配置

Calibre GUI → Preferences → Sharing over network → 勾选 "Allow CORS"，或在 `server.py` 里加 `--cors` 参数。

Web 端 dev 模式下通过 Vite proxy 绕过 CORS（详见 [Platform: Web](Platform-Web.md)）。

## 共享类型契约

`shared/api/calibre-contract.ts` 是三端 API 类型的唯一真相来源。主要类型：

```typescript
// 书籍元数据
interface BookMeta {
  id: number
  title: string[]
  authors: string[]
  formats: string[]
  tags: string[]
  series: string | null
  series_index: number | null
  cover: string
  last_modified: string
}

// 搜索结果
interface SearchResult {
  total_num: number
  book_ids: number[]
}

// Calibre 配置
interface CalibreConfig {
  baseUrl: string
  username?: string
  password?: string
  libraryId?: string
}
```

## 认证

Calibre 支持 HTTP Basic Auth。各端实现：

| 平台 | 实现方式 |
|------|---------|
| Web | axios `auth` 配置 |
| Android | Ktor `BasicAuthCredentials` |
| HarmonyOS | @ohos.net.http 手动设置 Authorization header |

## 注意事项

1. Calibre Content Server **不支持 CORS 预检请求**（OPTIONS），只支持简单请求
2. `/get/<format>/<id>` 返回文件流，需处理大文件下载
3. 搜索端点不支持模糊匹配的复杂语法，仅简单关键词匹配
4. Calibre 不提供同步 API —— 阅读进度同步需自建后端

---

_参考：_ [shared/calibre/api.md](../../shared/calibre/api.md)
