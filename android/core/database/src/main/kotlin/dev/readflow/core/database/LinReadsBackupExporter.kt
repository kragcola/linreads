package dev.readflow.core.database

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

interface LinReadsBackupExportStore {
    suspend fun export(output: OutputStream): LinReadsBackupExportResult
}

interface LinReadsBackupRestoreStore {
    suspend fun restore(input: InputStream): LinReadsBackupRestoreResult
}

class LinReadsBackupExporter(
    private val progressDao: ReadingProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val textAnnotationDao: TextAnnotationDao,
) : LinReadsBackupExportStore {
    override suspend fun export(output: OutputStream): LinReadsBackupExportResult {
        val progress = progressDao.allForBackup()
        val bookmarks = bookmarkDao.allForBackup()
        val textAnnotations = textAnnotationDao.allForBackup()
        val manifest = LinReadsBackupManifest(
            format = BACKUP_FORMAT,
            schemaVersion = BACKUP_SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            readingProgress = progress.map { it.toBackupRecord() },
            bookmarks = bookmarks.map { it.toBackupRecord() },
            textAnnotations = textAnnotations.map { it.toBackupRecord() },
        )
        val manifestBytes = backupJson.encodeToString(manifest).encodeToByteArray()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestBytes)
            zip.closeEntry()
        }
        return LinReadsBackupExportResult(
            progressCount = progress.size,
            bookmarkCount = bookmarks.size,
            textAnnotationCount = textAnnotations.size,
        )
    }

    private fun ReadingProgressEntity.toBackupRecord() = BackupReadingProgress(
        bookId = bookId,
        locatorJson = locatorJson,
        totalProgression = totalProgression,
        progressPercent = progressPercent,
        updatedAt = updatedAt,
        deviceId = deviceId,
    )

    private fun BookmarkEntity.toBackupRecord() = BackupBookmark(
        id = id,
        bookId = bookId,
        totalProgression = totalProgression,
        locatorJson = locatorJson,
        text = text,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deviceId = deviceId,
        isDeleted = isDeleted,
    )

    private fun TextAnnotationEntity.toBackupRecord() = BackupTextAnnotation(
        id = id,
        bookId = bookId,
        totalProgression = totalProgression,
        anchorType = anchorType,
        anchorJson = anchorJson,
        selectedText = selectedText,
        note = note,
        color = color,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deviceId = deviceId,
        isDeleted = isDeleted,
    )
}

data class LinReadsBackupExportResult(
    val progressCount: Int,
    val bookmarkCount: Int,
    val textAnnotationCount: Int,
)

class LinReadsBackupRestorer(
    private val progressDao: ReadingProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val textAnnotationDao: TextAnnotationDao,
) : LinReadsBackupRestoreStore {
    override suspend fun restore(input: InputStream): LinReadsBackupRestoreResult {
        val manifest = input.use { backupJson.decodeFromString<LinReadsBackupManifest>(it.readManifestJson()) }
        require(manifest.format == BACKUP_FORMAT) { "不是 LinReads 备份文件" }
        require(manifest.schemaVersion == BACKUP_SCHEMA_VERSION) { "不支持的备份版本：${manifest.schemaVersion}" }

        var progressCount = 0
        for (record in manifest.readingProgress) {
            val entity = record.toEntity()
            val existing = progressDao.get(entity.bookId)
            if (existing == null || entity.updatedAt > existing.updatedAt) {
                progressDao.upsert(entity)
                progressCount += 1
            }
        }

        var bookmarkCount = 0
        for (record in manifest.bookmarks) {
            val entity = record.toEntity()
            val existing = bookmarkDao.getById(entity.id)
            if (existing == null || entity.updatedAt > existing.updatedAt) {
                bookmarkDao.upsert(entity)
                bookmarkCount += 1
            }
        }

        var textAnnotationCount = 0
        for (record in manifest.textAnnotations) {
            val entity = record.toEntity()
            val existing = textAnnotationDao.getById(entity.id)
            if (existing == null || entity.updatedAt > existing.updatedAt) {
                textAnnotationDao.upsert(entity)
                textAnnotationCount += 1
            }
        }

        return LinReadsBackupRestoreResult(
            progressCount = progressCount,
            bookmarkCount = bookmarkCount,
            textAnnotationCount = textAnnotationCount,
        )
    }

    private fun InputStream.readManifestJson(): String {
        ZipInputStream(this).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name == "manifest.json") {
                    return zip.readBytes().decodeToString()
                }
            }
        }
        error("备份文件缺少 manifest.json")
    }

    private fun BackupReadingProgress.toEntity() = ReadingProgressEntity(
        bookId = bookId,
        locatorJson = locatorJson,
        totalProgression = totalProgression,
        progressPercent = progressPercent,
        updatedAt = updatedAt,
        deviceId = deviceId,
    )

    private fun BackupBookmark.toEntity() = BookmarkEntity(
        id = id,
        bookId = bookId,
        totalProgression = totalProgression,
        locatorJson = locatorJson,
        text = text,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deviceId = deviceId,
        isDeleted = isDeleted,
    )

    private fun BackupTextAnnotation.toEntity() = TextAnnotationEntity(
        id = id,
        bookId = bookId,
        totalProgression = totalProgression,
        anchorType = anchorType,
        anchorJson = anchorJson,
        selectedText = selectedText,
        note = note,
        color = color,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deviceId = deviceId,
        isDeleted = isDeleted,
    )
}

data class LinReadsBackupRestoreResult(
    val progressCount: Int,
    val bookmarkCount: Int,
    val textAnnotationCount: Int,
)

private val backupJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

private const val BACKUP_FORMAT = "linreads-backup"
private const val BACKUP_SCHEMA_VERSION = 1

@Serializable
private data class LinReadsBackupManifest(
    val format: String,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("exported_at") val exportedAt: Long,
    @SerialName("reading_progress") val readingProgress: List<BackupReadingProgress>,
    val bookmarks: List<BackupBookmark>,
    @SerialName("text_annotations") val textAnnotations: List<BackupTextAnnotation>,
    val assets: List<BackupAsset> = emptyList(),
)

@Serializable
private data class BackupReadingProgress(
    @SerialName("book_id") val bookId: String,
    @SerialName("locator_json") val locatorJson: String,
    @SerialName("total_progression") val totalProgression: Float,
    @SerialName("progress_percent") val progressPercent: Float,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("device_id") val deviceId: String,
)

@Serializable
private data class BackupBookmark(
    val id: String,
    @SerialName("book_id") val bookId: String,
    @SerialName("total_progression") val totalProgression: Float,
    @SerialName("locator_json") val locatorJson: String,
    val text: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("is_deleted") val isDeleted: Boolean,
)

@Serializable
private data class BackupTextAnnotation(
    val id: String,
    @SerialName("book_id") val bookId: String,
    @SerialName("total_progression") val totalProgression: Float,
    @SerialName("anchor_type") val anchorType: String,
    @SerialName("anchor_json") val anchorJson: String,
    @SerialName("selected_text") val selectedText: String,
    val note: String? = null,
    val color: Int,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("is_deleted") val isDeleted: Boolean,
)

@Serializable
private data class BackupAsset(
    val path: String,
    @SerialName("media_type") val mediaType: String? = null,
    val bytes: Long? = null,
)
