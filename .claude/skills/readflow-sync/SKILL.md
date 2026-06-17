---
name: readflow-sync
description: >
  阅读进度、书签、标注的离线存储与跨端同步策略。
  自动激活：涉及进度保存、书签、标注、跨设备同步、离线阅读、冲突解决。
---

# Readflow Sync

## 核心原则

**离线优先（Offline-first）**：所有操作先写本地，网络可用时再同步。
读书是离线场景，不能因无网络而功能降级。

---

## 数据分类与存储策略

### 三类同步数据

| 数据 | 冲突频率 | 合并策略 | 存储位置 |
|------|---------|---------|---------|
| **阅读进度**（最后位置） | 中（多设备交替读） | Last-Write-Wins（时间戳） | 每本书一条记录 |
| **书签** | 低（用户主动添加） | Union（合并不删除） | 列表，CFI去重 |
| **标注/高亮** | 低 | Union + 内容去重 | 列表，CFI范围去重 |

### 本地存储方案

```
Android:    Room Database (SQLite)
HarmonyOS:  relationalStore (@ohos.data.relationalStore)
Web:        IndexedDB（通过 idb 库）
```

---

## 数据结构

```typescript
// 同步单元 — 所有平台通用
interface SyncRecord {
  id: string              // UUID v4
  bookId: string          // Calibre book ID（数字）或本地路径 hash
  type: 'position' | 'bookmark' | 'annotation'
  cfi: string             // 位置（书签/标注用范围 CFI: epubcfi(/6/4!/4,/2,/4)）
  content?: string        // 标注文字
  note?: string           // 用户备注
  color?: string          // 高亮颜色
  deviceId: string        // 设备标识（首次启动生成 UUID，持久化）
  createdAt: string       // ISO 8601
  updatedAt: string       // ISO 8601（冲突解决用）
  deletedAt?: string      // 软删除（同步后才物理删除）
}

interface ReadingPosition extends SyncRecord {
  type: 'position'
  percentage: number      // 仅展示用
  chapterTitle: string
}
```

---

## 同步协议

### 同步触发时机

```
立即触发：
  - 关闭书籍/切换章节（进度）
  - 用户添加/删除书签

延迟触发（debounce 3s）：
  - 翻页中持续更新进度

后台触发：
  - App 进入后台
  - 网络从无到有恢复
```

### 同步算法（Last-Write-Wins + Union）

```typescript
async function syncBook(bookId: string) {
  const local = await db.getAll(bookId)
  const remote = await calibreOrSyncServer.pull(bookId, lastSyncAt)

  // 1. 进度：last-write-wins
  const localPos = local.find(r => r.type === 'position')
  const remotePos = remote.find(r => r.type === 'position')
  const winner = newerOf(localPos, remotePos)
  await db.upsert(winner)
  await remote.push(winner)

  // 2. 书签/标注：union（CFI 去重）
  const localItems = local.filter(r => r.type !== 'position')
  const remoteItems = remote.filter(r => r.type !== 'position')
  const merged = unionByCfi(localItems, remoteItems)
  await db.upsertAll(merged)
  await remote.pushAll(merged.filter(r => r.isNew))
}

function newerOf(a?: SyncRecord, b?: SyncRecord) {
  if (!a) return b
  if (!b) return a
  return a.updatedAt > b.updatedAt ? a : b
}

function unionByCfi(local: SyncRecord[], remote: SyncRecord[]) {
  const byCfi = new Map<string, SyncRecord>()
  for (const r of [...local, ...remote]) {
    const existing = byCfi.get(r.cfi)
    if (!existing || r.updatedAt > existing.updatedAt) {
      byCfi.set(r.cfi, { ...r, isNew: !local.find(l => l.cfi === r.cfi) })
    }
  }
  return [...byCfi.values()]
}
```

### 同步服务端选项（按优先级）

```
1. Calibre Content Server 不支持进度同步 → 需要独立同步端点
2. 自托管选项：
   a. KV 存储（简单）：Cloudflare KV / Redis，key = userId:bookId:type
   b. 关系型（完整）：Supabase（PostgreSQL + REST + 实时订阅）
3. 无服务器降级：同一局域网内直接用 Calibre Server 的自定义字段存储进度
   GET /ajax/book/<id>/calibre-library → 读 #readflow_position 自定义列
   PUT /cdb/cmd/set_custom/<id> → 写
```

---

## 冲突场景处理

### 场景：两台设备交替读同一本书

```
手机读到 40%（updatedAt: T1）
平板读到 60%（updatedAt: T2，T2 > T1）

同步时：
- 平板赢（newer wins）
- 手机拉到 60%
- 提示用户："检测到另一设备的进度（60%），已更新。继续从此处阅读？"
  [是] / [返回我的 40%]
```

### 场景：离线删除书签，另一端不知道

```
软删除：deletedAt 字段，不立即物理删除
同步时：推送 deletedAt，对端收到后标记删除
物理删除：同步成功后 7 天
```

---

## 离线降级规则

| 功能 | 无网络时 | 恢复网络时 |
|------|---------|----------|
| 阅读已下载书籍 | ✅ 完全可用 | — |
| 保存进度/书签 | ✅ 写本地，标记 pending | 自动后台同步 |
| 浏览 Calibre 书库 | ⚠️ 显示本地缓存（注明"离线"） | 自动刷新 |
| 下载新书 | ❌ 提示无网络 | — |

---

## 平台实现要点

### Android（Room + WorkManager）

```kotlin
// 进度保存（Room）
@Entity data class ReadingPosition(
    @PrimaryKey val bookId: Int,
    val cfi: String,
    val percentage: Float,
    val updatedAt: Long,
    val synced: Boolean = false
)

// 后台同步（WorkManager，网络约束）
val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
    .setConstraints(Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build())
    .build()
WorkManager.getInstance(context).enqueue(syncRequest)
```

### HarmonyOS（relationalStore）

```typescript
// 存储进度
const store = await relationalStore.getRdbStore(context, { name: 'readflow.db', ... })
await store.insert('reading_positions', {
  book_id: bookId, cfi, percentage, updated_at: Date.now(), synced: 0
})
```

### Web（IndexedDB via idb）

```typescript
const db = await openDB('readflow', 1, {
  upgrade(db) {
    db.createObjectStore('positions', { keyPath: 'bookId' })
    db.createObjectStore('bookmarks', { keyPath: 'id' })
  }
})
await db.put('positions', { bookId, cfi, percentage, updatedAt: new Date().toISOString() })
```
