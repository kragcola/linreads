# LinReads 打磨实施方案 - DeepSeek执行版 (Week 7-8)

> **执行人**: DeepSeek
> **范围**: 批注/书签导出 Markdown（主） + 主题导入导出 JSON + 阅读统计数据层
> **工作量**: 约 5 人日真人 → 规划 5-6 小时 DeepSeek（含强制中场质量门）
> **版本**: v5.0（接续 Week 5-6 实时设置/字体导入）
> **前置**: Week 5-6 全部 ✅（FontChoice 导入、实时设置、TXT编码保位置）已编译+单测验证

---

## ⚠️ 本轮特别说明：质量门（必读）

你（DeepSeek）执行很快，但**任务量一大、代码质量就下滑**——前几轮出现过：路径穿越、主线程 I/O、层次违规、漏补 fake、用不存在的 API。本轮把任务拆成 6 个 Phase，**每个 Phase 之间有一道「🛑 质量门」**。

**规则**：
1. 每个 Phase 做完，**必须先停下，逐字读完紧随其后的「🛑 质量门」块**，对照自检，再进下一个 Phase。
2. 质量门里的自检项**逐条回答 ✅/❌**写进报告，不允许跳过或笼统带过。
3. 任一自检项为 ❌，**当场修，修完再过门**，不要带着问题往下做。
4. 不许「攒着最后一起测」——每个 Phase 当场编译验证。

---

## 🚦 编译验证门禁（沿用 Week 5-6，硬性）

每个 Phase 完成标记 `[DONE-Pn]` 必须附**真实**编译输出：
```bash
cd /Volumes/OmubotDisk/readflow/android
./gradlew -Preadflow.phase=2 assembleDebug 2>&1 | tail -5
```
格式：
```
[DONE-Pn]
真实输出: BUILD SUCCESSFUL in XXs   ← 你本机真实行，禁止编造
```
全部完成后跑受影响模块单测并贴输出（见末尾）。

**禁止**：不跑编译就标 DONE / 编造 BUILD SUCCESSFUL / 以「环境/SIP/磁盘」为由跳过（真跑不动标 `[BLOCKED-Pn]` 贴真实报错）。

---

## 执行说明

**模块速查**（已核实，注意：**没有 `:core:domain`**）:
- 实体/DAO：`core/database/.../Entities.kt`、`Daos.kt`（`BookDao.getById(id):BookEntity?`、`BookmarkDao.allForBackup():List<BookmarkEntity>`、`TextAnnotationDao.allForBackup()`）
- DB 类：`core/database/.../ReadflowDatabase.kt`（当前 `version = 3`，用 `@AutoMigration`）
- 备份导出范本：`core/database/.../LinReadsBackupExporter.kt`（`LinReadsBackupExportStore.export(OutputStream)`）+ `SettingsViewModel.exportBackup`（`backupDispatcher` IO）+ `SettingsScreen` SAF（`CreateDocument`）
- 设置仓库：`core/prefs/.../SettingsRepository.kt`（全字段见下）
- DI：`app/src/phase2/.../di/AppModules.kt` + `app/src/phase1/.../di/AppModules.kt`（**两处都要改**）
- JSON 库：**kotlinx.serialization**（`@Serializable` + `Json.encodeToString`），全项目唯一，禁止引入 Gson/Moshi
- 选词流：`render/api/.../ReaderTextSelection.kt`、reader `TextSelectionActions`（本轮不碰，词典留后）

**设置字段全集**（主题导出用）：`fontSize:Int(18)`、`lineSpacing:Float(1.75)`、`themeMode:ThemeMode(SYSTEM)`、`fontChoice:FontChoice(SourceHan)`、`txtEncoding:TxtEncoding(AUTO)`、`readingMode:ReaderReadingMode(SCROLL)`。**不含** calibreUrl/deviceId（那不是主题）。

**执行顺序**（严格按序，每段后过质量门）:
```
P1 批注导出-生成逻辑 → P2 批注导出-store+UI
→ P3 主题导出导入-模型+逻辑 → P4 主题导出导入-UI
→ P5 阅读统计-Room实体+DAO+迁移 → P6 阅读统计-记录+聚合
```

---

## Phase 1: 批注/书签导出 — Markdown 生成逻辑（纯函数）

### 任务目标
把某本书（或全部书）的书签 + 文本标注渲染成 Markdown 字符串。**本 Phase 只写纯函数**，不碰 IO/UI，便于单测。

### 数据来源（已核实）
- 书签：`BookmarkEntity { bookId, totalProgression, text, createdAt, isDeleted }`
- 标注：`TextAnnotationEntity { bookId, totalProgression, selectedText, note, color, createdAt, isDeleted }`
- 书名：`BookDao.getById(bookId)?.title`
- **务必过滤 `isDeleted = true`**（`allForBackup()` 含墓碑）

### 执行步骤

#### Step 1.1: 新建纯函数 + 数据类

**新建**: `core/database/.../NotesMarkdownExporter.kt`
```kotlin
package dev.readflow.core.database

/** 单本书的导出输入（标题 + 该书的书签/标注，均已过滤 isDeleted）。 */
data class BookNotesExport(
    val title: String,
    val bookmarks: List<BookmarkEntity>,
    val annotations: List<TextAnnotationEntity>,
)

object NotesMarkdownExporter {
    /** 渲染为 Markdown。空集合返回带标题的占位说明。按 totalProgression 升序。 */
    fun render(books: List<BookNotesExport>, exportedAtMillis: Long): String {
        val sb = StringBuilder()
        sb.append("# LinReads 阅读笔记\n\n")
        sb.append("> 导出时间：").append(formatTime(exportedAtMillis)).append("\n\n")
        if (books.all { it.bookmarks.isEmpty() && it.annotations.isEmpty() }) {
            sb.append("_暂无书签或标注_\n")
            return sb.toString()
        }
        books.forEach { book ->
            if (book.bookmarks.isEmpty() && book.annotations.isEmpty()) return@forEach
            sb.append("## ").append(book.title).append("\n\n")
            book.annotations.sortedBy { it.totalProgression }.forEach { a ->
                sb.append("> ").append(a.selectedText.trim().replace("\n", " ")).append("\n")
                a.note?.takeIf { it.isNotBlank() }?.let { sb.append("\n📝 ").append(it).append("\n") }
                sb.append("\n— ").append(percent(a.totalProgression)).append("\n\n")
            }
            book.bookmarks.sortedBy { it.totalProgression }.forEach { b ->
                sb.append("- 🔖 ").append(percent(b.totalProgression))
                b.text.trim().takeIf { it.isNotEmpty() }?.let { sb.append("：").append(it.replace("\n", " ")) }
                sb.append("\n")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun percent(p: Float): String = "${(p * 100).toInt()}%"
    private fun formatTime(millis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))
}
```

#### Step 1.2: 单测（本 Phase 必须带）

**新建**: `core/database/src/test/.../NotesMarkdownExporterTest.kt`，至少覆盖：空集合占位、单书含标注+笔记+书签、多书分节、isDeleted 不应出现（调用方过滤，测试构造已过滤的输入即可）、百分比换算。

#### Step 1.3: 编译 + 跑该测试，标 `[DONE-P1]` + 真实输出
```bash
./gradlew -Preadflow.phase=2 :core:database:testDebugUnitTest --tests "*NotesMarkdownExporterTest" 2>&1 | tail -6
```

---

## 🛑 质量门 1 —— 进 Phase 2 前必须逐条自检并写进报告

**停下，逐字读完，逐条回答 ✅/❌：**
1. [ ] `render()` 是**纯函数**吗？（不碰 Context/IO/DAO/线程）若混入了 IO → ❌ 重构
2. [ ] 单测真的**跑过了**吗？贴出 `tests=N failures=0`，不是「应该能过」
3. [ ] Markdown 里用户文本做了换行清理（`replace("\n"," ")`），不会破坏结构？
4. [ ] 没有引入 Gson/Moshi，没用任何不确定是否存在的 API？
5. [ ] 没有动 reader/引擎/已有导出逻辑，改动只新增了 2 个文件？

**任一 ❌：当场修，修完重测，再进 Phase 2。**

---
## Phase 2: 批注导出 — Export Store + DAO 接线 + SAF UI

### 任务目标
把 Phase 1 的纯函数接到真实数据 + SAF 文件写出。**镜像现有 `LinReadsBackupExporter` / `exportBackup` / backup SAF 模式**，不要自创风格。

### 执行步骤

#### Step 2.1: NotesExportStore（DAO → 纯函数 → OutputStream）

**新建**: `core/database/.../NotesExportStore.kt`，构造注入 `bookDao, bookmarkDao, textAnnotationDao`（与 `LinReadsBackupExporter` 同样的注入风格）。
```kotlin
interface NotesMarkdownExportStore {
    suspend fun export(output: OutputStream): Int  // 返回导出的书数
}

class NotesMarkdownExporter2(/* 注入 3 个 DAO */) : NotesMarkdownExportStore {
    override suspend fun export(output: OutputStream): Int {
        val bookmarks = bookmarkDao.allForBackup().filter { !it.isDeleted }.groupBy { it.bookId }
        val annotations = textAnnotationDao.allForBackup().filter { !it.isDeleted }.groupBy { it.bookId }
        val bookIds = (bookmarks.keys + annotations.keys).distinct()
        val books = bookIds.mapNotNull { id ->
            val title = bookDao.getById(id)?.title ?: return@mapNotNull null
            BookNotesExport(title, bookmarks[id].orEmpty(), annotations[id].orEmpty())
        }
        val md = NotesMarkdownExporter.render(books, System.currentTimeMillis())
        output.use { it.write(md.toByteArray(Charsets.UTF_8)) }
        return books.count { it.bookmarks.isNotEmpty() || it.annotations.isNotEmpty() }
    }
}
```
> 命名 `NotesMarkdownExporter2` 是占位，**先报告** `LinReadsBackupExporter` 的接口/类命名习惯，我给一致命名，别硬留 `2`。

#### Step 2.2: DI 注册（两处 AppModules 都要）

`app/src/phase2/.../di/AppModules.kt` **和** `app/src/phase1/.../di/AppModules.kt` 都加 `single<NotesMarkdownExportStore> { ... }`，仿现有 `LinReadsBackupExportStore` 那行。**先报告**两处 backup store 注册行，我确认 phase1 是否需要（reader 在 phase2，但 settings 页可能 phase1 也加载——按现有 backup 的处理方式对齐）。

#### Step 2.3: SettingsViewModel.exportNotes

仿 `exportBackup(output: OutputStream)`（走 `backupDispatcher`、`runCatching`、`BackupExportUiState` 同款状态）。新增 `notesExportState` 或复用现有导出状态——**先报告** `exportBackup` + 其 Ui state 现状，我给精确代码。

#### Step 2.4: SettingsScreen SAF 按钮

仿 `backupLauncher`（`CreateDocument("text/markdown")`，文件名 `LinReads 笔记.md`），加一个「导出阅读笔记」按钮。**先报告** backup 按钮 + launcher 段，我给一致代码。

#### Step 2.5: 编译门禁 + 标 `[DONE-P2]` + 真实输出

---

## 🛑 质量门 2 —— 进 Phase 3 前必须逐条自检

**停下，逐条回答 ✅/❌：**
1. [ ] `export()` 的文件 I/O 在 **`backupDispatcher`(IO)** 上跑，不在主线程？（Week 5-6 踩过 ANR）
2. [ ] `allForBackup()` 结果**过滤了 `isDeleted`**？否则墓碑会被导出
3. [ ] 新 store 在 **两个 AppModules** 都注册了（或确认只需一个并说明理由）？
4. [ ] SAF 用 `CreateDocument("text/markdown")`，`openOutputStream` 为 null 时有失败处理（仿 backup）？
5. [ ] 没动现有 backup 逻辑、没改 reader/引擎？
6. [ ] 编译真过了，贴了真实 BUILD SUCCESSFUL？

**任一 ❌：当场修，再进 Phase 3。**

---

## Phase 3: 主题导入导出 — JSON 模型 + 序列化/应用逻辑

### 任务目标
把当前排版设置打包成 JSON（导出），或从 JSON 解析并应用（导入）。用 **kotlinx.serialization**。**纯逻辑 + 单测**，UI 留 Phase 4。

### 字段（已核实，仅排版相关）
`fontSize:Int`、`lineSpacing:Float`、`themeMode:String`、`fontChoice:String`、`txtEncoding:String`、`readingMode:String`。枚举/FontChoice 用各自的 `.name`/`serialize()` 存字符串。

### 执行步骤

#### Step 3.1: @Serializable 模型 + 编解码

**新建**: `core/model/.../ThemeProfile.kt`
```kotlin
package dev.readflow.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ThemeProfile(
    val name: String = "我的主题",
    val fontSize: Int = 18,
    val lineSpacing: Float = 1.75f,
    val themeMode: String = "SYSTEM",
    val fontChoice: String = "source_han",
    val txtEncoding: String = "AUTO",
    val readingMode: String = "SCROLL",
) {
    fun encode(): String = Json.encodeToString(this)
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        /** 解析失败返回 null（不抛）。 */
        fun decode(raw: String): ThemeProfile? =
            runCatching { json.decodeFromString<ThemeProfile>(raw) }.getOrNull()
    }
}
```
> `encode()` 里也应使用 `json` 实例（带 `encodeDefaults`），统一。修正：把 `Json.encodeToString` 改成 `json.encodeToString(this)`，并把 `json` 移到 companion 顶部。

#### Step 3.2: 范围校验（应用前防脏数据）

导入应用时，对数值做 clamp（`fontSize` 合理区间、`lineSpacing` 用 reader 已有的 `clampedReaderLineSpacing` 同款边界），枚举用 `runCatching{ valueOf }.getOrDefault`。**先报告** `ThemeMode`/`ReaderReadingMode`/`TxtEncoding`/`FontChoice` 的解析现状（哪些已有 `parse`/`valueOf`），我给应用代码。

#### Step 3.3: 单测：round-trip（encode→decode 相等）+ 脏 JSON 返回 null + 未知字段忽略

#### Step 3.4: 编译 + 测试 + 标 `[DONE-P3]` + 真实输出

---

## 🛑 质量门 3 —— 进 Phase 4 前必须逐条自检

1. [ ] 用的是 **kotlinx.serialization**，不是 Gson/Moshi？
2. [ ] `decode()` 对脏 JSON **返回 null 不抛**？单测覆盖了？
3. [ ] 主题只含排版字段，**没有 calibreUrl/deviceId** 这类非主题数据？
4. [ ] round-trip 单测真跑过（贴 `failures=0`）？
5. [ ] 没动 reader/引擎/已有设置逻辑？

**任一 ❌：当场修，再进 Phase 4。**

---
## Phase 4: 主题导入导出 — SAF UI 接线

### 任务目标
设置页加「导出主题 / 导入主题」，导出当前设置为 `.json`，导入后应用到 settings。

### 执行步骤

#### Step 4.1: SettingsViewModel 导出/导入方法

- `exportTheme(output: OutputStream)`：读当前各 settings flow 的 `.first()` 组 `ThemeProfile` → `encode()` → 写出（走 `backupDispatcher`）。
- `importTheme(input: InputStream)`：读全文 → `ThemeProfile.decode()`；null 则置失败状态；非 null 则逐字段 clamp/校验后调 `settings.setFontSize/.setLineSpacing/.setThemeMode/.setFontChoice/.setTxtEncoding/.setReadingMode`。
> **先报告** `SettingsViewModel` 现有 import/export backup 两个方法 + 状态枚举，我给与之一致的 `exportTheme/importTheme` 精确代码（含 clamp）。

#### Step 4.2: SettingsScreen 两个 SAF 按钮

- 导出：`CreateDocument("application/json")`，文件名 `LinReads 主题.json`
- 导入：`OpenDocument(arrayOf("application/json"))`
仿 backup 的 launcher + 按钮。**先报告** backup 两按钮段，我给一致代码。

#### Step 4.3: 编译门禁 + 标 `[DONE-P4]` + 真实输出

---

## 🛑 质量门 4 —— 进 Phase 5 前必须逐条自检

1. [ ] 导入/导出 I/O 都在 **IO dispatcher**？
2. [ ] 导入对脏 JSON（`decode`→null）有**用户可见失败提示**，不崩溃？
3. [ ] 导入字段做了 **clamp/枚举兜底**（fontSize/lineSpacing 不会被恶意值搞坏）？
4. [ ] SAF MIME 正确（导出 `application/json`、导入过滤 json），null 流有处理？
5. [ ] 编译真过、贴了真实输出？

**任一 ❌：当场修，再进 Phase 5。**

---

## Phase 5: 阅读统计 — Room 实体 + DAO + 数据库迁移

### 任务目标
新增 `reading_sessions` 表记录每次阅读会话。**本 Phase 只做数据层 + 迁移**，记录/聚合留 Phase 6。

### ⚠️ 迁移红线（已核实，最易出错）
当前 `ReadflowDatabase` `version = 3`，用 `@AutoMigration`。**纯新增表**可用 auto-migration。步骤缺一不可：

### 执行步骤

#### Step 5.1: 新增实体

`core/database/.../Entities.kt` 末尾新增：
```kotlin
@Entity(
    tableName = "reading_sessions",
    indices = [Index("bookId"), Index("startedAt")],
)
data class ReadingSessionEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val startedAt: Long,
    val durationMs: Long,
    val deviceId: String,
)
```

#### Step 5.2: 新增 DAO

`core/database/.../Daos.kt` 新增：
```kotlin
@Dao
interface ReadingSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ReadingSessionEntity)

    @Query("SELECT COALESCE(SUM(durationMs),0) FROM reading_sessions WHERE startedAt >= :sinceMillis")
    suspend fun totalDurationSince(sinceMillis: Long): Long

    @Query("SELECT COALESCE(SUM(durationMs),0) FROM reading_sessions WHERE bookId = :bookId")
    suspend fun totalDurationForBook(bookId: String): Long

    @Query("SELECT * FROM reading_sessions ORDER BY startedAt DESC")
    suspend fun allForBackup(): List<ReadingSessionEntity>
}
```

#### Step 5.3: DB 版本 + AutoMigration + DAO 访问器

`ReadflowDatabase.kt`：
- `entities` 数组加 `ReadingSessionEntity::class`
- `version = 3` → `version = 4`
- `autoMigrations` 加 `AutoMigration(from = 3, to = 4)`
- 新增 `abstract fun readingSessionDao(): ReadingSessionDao`

#### Step 5.4: DI 注册 DAO（两处 AppModules）

phase1 + phase2 的 `AppModules.kt` 都加 `single { get<ReadflowDatabase>().readingSessionDao() }`，仿现有 `bookmarkDao()`/`textAnnotationDao()` 那几行。**先报告**两处 DAO 注册段。

#### Step 5.5: DAO 单测（Robolectric in-memory DB）

仿现有 DAO 测试（**先报告**是否已有 `*DaoTest`/in-memory Room 测试范本），覆盖：insert + `totalDurationSince` 区间求和 + `totalDurationForBook`。

#### Step 5.6: 编译门禁 + 标 `[DONE-P5]` + 真实输出
> 迁移验证：编译后确认 KSP 生成了 `ReadflowDatabase_AutoMigration_3_4_Impl`（`core/database/build/generated/ksp/` 下），并贴一行确认。

---

## 🛑 质量门 5 —— 进 Phase 6 前必须逐条自检（迁移是高危区）

1. [ ] `version` 从 3 **改成了 4**（不是仍停在 3）？
2. [ ] `@AutoMigration(from = 3, to = 4)` **加了**，且这是**纯新增表**（没改/删旧列，否则 auto 不安全）？
3. [ ] `entities` 数组加了新实体、新增了 `readingSessionDao()` 访问器？
4. [ ] **两处** AppModules 都注册了 DAO？
5. [ ] KSP 真的生成了 `_AutoMigration_3_4_Impl`？贴出确认
6. [ ] DAO 单测真跑过（贴 `failures=0`）？
7. [ ] 编译真过、贴了真实输出？

**任一 ❌：当场修，再进 Phase 6。迁移错了会让用户升级丢数据，务必逐条确认。**

---

## Phase 6: 阅读统计 — 会话记录 + 聚合查询

### 任务目标
reader 打开→关闭记一次会话（时长），统计入库。**不做图表 UI**（留后续/真机轮），只做记录 + 一个聚合读取方法。

### 执行步骤

#### Step 6.1: ReaderViewModel 记录会话

**先报告**：reader 开书成功处（~176-220）与 `onCleared()`/关闭处（~570 附近 job cancel 段）的现状代码。我给精确落点：
- 开书成功时记 `sessionStartedAt = System.currentTimeMillis()` + 当前 `bookId`
- 关闭/`onCleared` 时若 `sessionStartedAt != null`：`duration = now - start`，`duration > 阈值(如 1s)` 才 `readingSessionDao.insert(...)`（避免误开的 0 时长噪声），用 `viewModelScope` 但注意 `onCleared` 后 scope 已取消——**用注入的 applicationScope 或在关闭意图里写，先报告再定**。
> ⚠️ `onCleared()` 里 `viewModelScope.launch` 不会执行（scope 已 cancel）。这是高危点：必须在「关闭书」的显式路径里写库，或用独立 scope。先报告关闭路径，我给安全写法。

#### Step 6.2: 注入 ReadingSessionDao

`ReaderViewModel` 构造注入新 DAO，更新 phase2 AppModules 的 VM 构造 + 所有 reader 测试 fake/构造。**先 `grep -rn "ReaderViewModel(" --include=*.kt`** 找出所有构造点一并补（含测试）。

#### Step 6.3: 单测

聚合逻辑/记录阈值用纯函数或 DAO 测试覆盖；reader 集成测试若已有，补一条会话写入断言（先报告 reader 测试现状）。

#### Step 6.4: 编译门禁 + 全量单测 + 标 `[DONE-P6]` + 真实输出

---

## 🛑 质量门 6（最终）—— 收尾前逐条自检

1. [ ] 会话写库**没放在 `onCleared` 的 `viewModelScope.launch`**（那不会执行）？用了安全路径？
2. [ ] 新 DAO 注入后，**所有 `ReaderViewModel(` 构造点（含测试 fake）都补了**？（Week 3-4 漏 fake 的教训）
3. [ ] 0/极短时长会话被阈值过滤，不会刷垃圾数据？
4. [ ] 没有在 reader 渲染热路径里做阻塞 I/O？
5. [ ] 全量单测跑过（贴输出）？

---

## 最终验收命令（全做完跑，贴完整输出）
```bash
cd /Volumes/OmubotDisk/readflow/android
./gradlew -Preadflow.phase=2 assembleDebug 2>&1 | tail -5
./gradlew -Preadflow.phase=2 \
  :core:database:testDebugUnitTest :core:model:testDebugUnitTest \
  :features:settings:testDebugUnitTest :features:reader:testDebugUnitTest \
  :features:library:testDebugUnitTest 2>&1 | tail -8
```

## 提交报告格式
```markdown
# Week 7-8 执行完成报告

## 完成情况（每条贴真实编译尾部输出）
- P1..P6: ✅ / BUILD SUCCESSFUL in XXs

## 质量门自检结果（6 道门，逐条 ✅/❌）
门1: 1.✅ 2.✅ ... / 门2: ... / ...（如实填，有 ❌ 写明怎么修的）

## "先报告"点的现状代码（等 Claude 精确指导处）

## 修改/新增文件列表

## BLOCKED / 不确定项
```

---

## 重要提醒（红线汇总）
1. ✅ **每 Phase 后过质量门**，逐条 ✅/❌ 写进报告，❌ 当场修
2. ✅ **编译门禁**：每 Phase 贴真实 `assembleDebug` 输出
3. ✅ **外部输入清洗**（路径/JSON），**IO 不上主线程**（Week 5-6 路径穿越+ANR 教训）
4. ✅ **新接口成员/新构造参数必 grep 所有实现+fake 补全**（Week 3-4 漏 fake 教训）
5. ✅ **DB 迁移**：version+1、AutoMigration、entities、DAO 访问器、两处 DI，五件套缺一不可
6. ✅ **保留既有成果**：导出/备份/reader/引擎/字体接缝一律不回退
7. ✅ **"先报告"点先贴现状再改**，不猜路径/签名/字段名

### 不要做
1. ❌ 不跑编译就标 DONE / 编造输出 / 跳过质量门
2. ❌ 引入 Gson/Moshi（只用 kotlinx.serialization）
3. ❌ 在 `onCleared` 的 `viewModelScope` 里写库
4. ❌ 做阅读统计图表 UI（本轮只到数据层 + 记录）
5. ❌ 改 DB 旧列/旧表（只新增表）

---

## 开始执行
从 Phase 1 起严格按序，每段后过质量门。准备好了吗？开始执行！🚀
