package dev.readflow.core.calibre

import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Hard cap for document-picker source configuration JSON (bytes). */
const val SOURCE_CONFIG_IMPORT_MAX_BYTES: Int = 256 * 1024

const val SOURCE_CONFIG_IMPORT_SCHEMA_VERSION: Int = 1

/**
 * Versioned import envelope for user-supplied online source configuration.
 * Declares which adapter owns [configJson]; runtime never executes scripts from the file.
 */
@Serializable
data class SourceConfigImportEnvelope(
    val schemaVersion: Int,
    val name: String,
    val adapterId: String,
    val configVersion: Int,
    val configJson: JsonElement,
)

data class ParsedSourceConfigImport(
    val name: String,
    val adapterId: String,
    val configVersion: Int,
    val configJson: String,
)

private val importEnvelopeJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    isLenient = false
}

/**
 * Parse and structurally validate a versioned source-config envelope.
 * Does not run adapter validation or persist — callers must do that next.
 */
fun parseSourceConfigImportEnvelope(raw: String): ReadflowResult<ParsedSourceConfigImport> {
    if (raw.isBlank()) {
        return ReadflowResult.Failure(ReadflowError.parse("配置文件为空，请选择有效的 JSON 书源配置"))
    }
    val envelope = try {
        importEnvelopeJson.decodeFromString(SourceConfigImportEnvelope.serializer(), raw)
    } catch (_: Exception) {
        return ReadflowResult.Failure(
            ReadflowError.parse("配置 JSON 格式无效，请检查是否为合法的书源导入文件"),
        )
    }
    if (envelope.schemaVersion != SOURCE_CONFIG_IMPORT_SCHEMA_VERSION) {
        return ReadflowResult.Failure(
            ReadflowError.parse(
                "不支持的配置 schema 版本：${envelope.schemaVersion}（当前仅支持 $SOURCE_CONFIG_IMPORT_SCHEMA_VERSION）",
            ),
        )
    }
    val name = envelope.name.trim()
    if (name.isBlank()) {
        return ReadflowResult.Failure(ReadflowError.parse("请填写书源名称"))
    }
    val adapterId = envelope.adapterId.trim()
    if (adapterId.isBlank()) {
        return ReadflowResult.Failure(ReadflowError.parse("书源适配器标识不能为空"))
    }
    val rawConfigJson = when (val element = envelope.configJson) {
        is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ReadflowResult.Failure(ReadflowError.parse("configJson 不能为空"))
        is JsonObject -> importEnvelopeJson.encodeToString(JsonElement.serializer(), element)
        else -> return ReadflowResult.Failure(
            ReadflowError.parse("configJson 必须是 JSON 对象或 JSON 字符串"),
        )
    }
    return ReadflowResult.Success(
        ParsedSourceConfigImport(
            name = name,
            adapterId = adapterId,
            configVersion = envelope.configVersion,
            configJson = rawConfigJson,
        ),
    )
}

/**
 * Stable string form used for semantic duplicate detection after adapter validation.
 * Normalizes base URLs for known adapters; falls back to trimmed raw JSON otherwise.
 */
fun canonicalizeImportedConfigJson(adapterId: String, configJson: String): String =
    runCatching {
        when (adapterId) {
            dev.readflow.extensions.api.SourceAdapterIds.CALIBRE -> {
                val config = sourceConfigJson.decodeFromString(CalibreSourceConfig.serializer(), configJson)
                calibreSourceConfigJson(
                    baseUrl = canonicalizeImportedBaseUrl(config.baseUrl),
                    libraryId = config.libraryId.trim().ifBlank { "calibre-library" },
                )
            }
            dev.readflow.extensions.api.SourceAdapterIds.OPDS,
            dev.readflow.extensions.api.SourceAdapterIds.JSON_HTTP,
            -> {
                val config = sourceConfigJson.decodeFromString(HttpCatalogSourceConfig.serializer(), configJson)
                httpCatalogSourceConfigJson(canonicalizeImportedBaseUrl(config.baseUrl))
            }
            dev.readflow.extensions.api.SourceAdapterIds.HTML_RULES_V1 -> {
                val config = sourceConfigJson.decodeFromString(HtmlRulesV1Config.serializer(), configJson)
                htmlRulesV1ConfigJson(
                    config.copy(
                        searchUrlTemplate = config.searchUrlTemplate.trim(),
                        allowedHosts = config.allowedHosts
                            .map { it.trim().lowercase() }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .sorted(),
                        charset = Charset.forName(config.charset.trim().ifBlank { "UTF-8" }).name(),
                    ),
                )
            }
            else -> stableJsonOrTrim(configJson)
        }
    }.getOrElse { stableJsonOrTrim(configJson) }

private fun canonicalizeImportedBaseUrl(rawUrl: String): String {
    val validated = requireValidCalibreBaseUrl(rawUrl)
    val uri = URI(validated)
    val scheme = uri.scheme.lowercase()
    val host = uri.host.lowercase()
    val port = uri.port.takeUnless {
        (scheme == "http" && it == 80) || (scheme == "https" && it == 443)
    } ?: -1
    val authorityHost = if (host.contains(':')) "[$host]" else host
    val authority = if (port >= 0) "$authorityHost:$port" else authorityHost
    return "$scheme://$authority${uri.rawPath.orEmpty()}".trimEnd('/')
}

private fun stableJsonOrTrim(rawJson: String): String = runCatching {
    val element = importEnvelopeJson.parseToJsonElement(rawJson)
    importEnvelopeJson.encodeToString(JsonElement.serializer(), stableJsonElement(element))
}.getOrDefault(rawJson.trim())

private fun stableJsonElement(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.entries
            .sortedBy(Map.Entry<String, JsonElement>::key)
            .associate { (key, value) -> key to stableJsonElement(value) },
    )
    is JsonArray -> JsonArray(element.map(::stableJsonElement))
    else -> element
}

/**
 * Read at most [maxBytes] from [input]. Fails with a Chinese error when the stream exceeds the bound.
 */
fun readBoundedSourceConfigBytes(
    input: InputStream,
    maxBytes: Int = SOURCE_CONFIG_IMPORT_MAX_BYTES,
): ReadflowResult<String> {
    return try {
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                return ReadflowResult.Failure(
                    ReadflowError.parse(
                        "配置文件过大（上限 ${maxBytes / 1024} KB），请精简后重试",
                    ),
                )
            }
            output.write(buffer, 0, read)
        }
        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            return ReadflowResult.Failure(ReadflowError.parse("配置文件不是有效的 UTF-8 文本"))
        }
        ReadflowResult.Success(decoded)
    } catch (error: Exception) {
        ReadflowResult.Failure(ReadflowError.io(error.message ?: "无法读取配置文件"))
    }
}
