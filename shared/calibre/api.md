# Calibre Content Server API

默认端口 `8080`，支持 HTTP Basic Auth（可选）。

## 关键端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/ajax/search?query=&num=&offset=` | 搜索，返回 `{total_num, book_ids[]}` |
| GET | `/ajax/books?ids=1,2,3` | 批量元数据，返回 `{id: BookMeta}` map |
| GET | `/ajax/book/<id>/<library>` | 单本元数据 |
| GET | `/get/<format>/<id>/<library>` | 下载文件（EPUB/PDF/MOBI/AZW3） |
| GET | `/get/cover/<id>/<library>` | 封面图片 |

`<library>` 默认值：`calibre-library`

## Web 跨域

Calibre GUI → Preferences → Sharing over network → 勾选 "Allow CORS"，或在 `server.py` 里加 `--cors` 参数。

## 启动命令

```bash
# GUI 模式：Preferences → Sharing over network → Start server
# CLI 模式：
calibre-server --port 8080 /path/to/library
```
