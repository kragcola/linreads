# Progress Sync

> 状态：**待实现**（设计已完成，详见 `.claude/skills/linreads-sync/SKILL.md`）

## 设计原则

- **离线优先**：本地先写，后台异步推送
- **冲突策略**：
  - 阅读位置 → LWW（Last-Write-Wins，以时间戳为准）
  - 书签/高亮 → Union（合并不删除）
- **端独立性**：任一端离线时正常读写，上线后自动收敛

## 存储方案

| 平台 | 本地存储 | 备注 |
|------|---------|------|
| Android | Room (`reading_progress` 表 + `annotations` 表) | Flow 响应式 |
| HarmonyOS | relationalStore | 待实现 |
| Web | IndexedDB | 待实现 |

## 数据模型

### 进度（独立表，不与书签混用）

```
reading_progress
├── book_id: String       # 书籍唯一标识
├── chapter_index: Int    # 章节索引
├── split_index: Int      # 分片索引（超长章节）
├── position: Int         # 字符位置
├── device_id: String     # 写入设备
├── updated_at: Long      # LWW 时间戳
```

### 标注（独立表）

```
annotations
├── book_id: String
├── page_ref: String      # EPUB: chapterId; PDF: "page:5"
├── type: Enum             # BOOKMARK | HIGHLIGHT | NOTE
├── content: Text
├── stroke_data: Blob      # 手写注记序列化（Android Phase 2）
├── updated_at: Long       # LWW 时间戳
```

## 冲突解决

### LWW（阅读位置）

```
本地位置:  chapter=5, position=1200,  ts=100
云端位置:  chapter=5, position=980,   ts=105  ← 云端更新，覆盖本地
结果:      chapter=5, position=980,   ts=105
```

### Union（书签/高亮）

```
本地书签:  [A, B, C]
云端书签:  [A, D]
结果:      [A, B, C, D]  ← 合并，不删除
```

## 同步后端

方案待定，两个方向：

| 方案 | 优点 | 缺点 |
|------|------|------|
| 自建轻量 HTTP Server | 可控，可部署在 NAS | 需要额外服务 |
| 本地 P2P（WebRTC / mDNS） | 零服务端 | 两端需同时在线 |

Calibre Content Server 不提供同步 API，因此无论如何需要额外组件。

## 设计参考

- **Moon+ Reader**：进度 LWW，书架覆盖，封面增量；约 1529 行 God Class `Sync.java`
- **Readest**：CRDT + HLC（混合逻辑时钟），字段级 LWW，多态 replicas 表
- **KOReader**：每本书 `.sdr/` sidecar 目录，进度/书签/高亮各存独立文件

LinReads 取其精华：Moon+ 的 LWW + Union 策略 + KOReader 的进度/标注分表 + Readest 的离线优先理念。

---

_参考：_ `.claude/skills/linreads-sync/SKILL.md` · [Research: Moon+ Reader](Research-MoonReader.md)
