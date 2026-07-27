package dev.readflow.core.calibre

import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import dev.readflow.extensions.api.DefaultSourceAdapterRegistry
import dev.readflow.extensions.api.OnlineBookCatalog
import dev.readflow.extensions.api.SourceAdapterFactory
import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceAdapterRegistry
import dev.readflow.extensions.api.SourceCapabilities
import dev.readflow.extensions.api.SourceCredentials
import dev.readflow.extensions.api.SourceDescriptor
import java.io.File

class CalibreSourceAdapterFactory(
    private val booksDir: File,
    private val credentialProvider: (String, String) -> SourceCredentials? = { _, _ -> null },
    private val networkSnapshotProvider: CalibreNetworkSnapshotProvider =
        UnknownCalibreNetworkSnapshotProvider,
) : SourceAdapterFactory {
    override val adapterId = SourceAdapterIds.CALIBRE
    override val latestConfigVersion = 1

    override fun capabilities(configVersion: Int, configJson: String) = SourceCapabilities(
        canSearch = true,
        canFilterByAuthor = true,
        canFilterBySeries = true,
        canFilterByFormat = true,
        canFilterByTag = true,
        canPreviewText = false,
        canDownload = true,
        canBatchAcrossSource = true,
    )

    override fun validate(configVersion: Int, configJson: String): ReadflowResult<Unit> = validateConfig(
        configVersion = configVersion,
        parse = { sourceConfigJson.decodeFromString(CalibreSourceConfig.serializer(), configJson) },
        validate = { config ->
            requireValidCalibreBaseUrl(config.baseUrl)
            require(config.libraryId.isNotBlank()) { "Calibre libraryId 不能为空" }
        },
    )

    override fun open(descriptor: SourceDescriptor): ReadflowResult<OnlineBookCatalog> = runCatching {
        val config = descriptor.calibreConfig()
        val baseUrl = requireValidCalibreBaseUrl(config.baseUrl)
        val mayReadCredentials = !requiresActiveVpnForCalibreHttp(baseUrl) ||
            canUseStoredCalibreCredentials(
                requestUrl = baseUrl,
                network = networkSnapshotProvider.snapshot(),
            )
        val credentials = if (mayReadCredentials) {
            credentialProvider(
                descriptor.id,
                calibreCredentialScopeForRequestUrl(baseUrl),
            )
        } else {
            // The catalog may still probe OPDS without credentials. This preserves useful
            // reachability diagnostics while keeping the stored secret out of memory and off the
            // wire until Android positively reports that this app is routed through Tailscale.
            null
        }
        ReadflowResult.Success(
            CalibreOpdsOnlineCatalog(
                descriptor = descriptor.copy(baseUrl = baseUrl),
                booksDir = booksDir,
                credentials = credentials,
                networkSnapshotProvider = networkSnapshotProvider,
            ),
        )
    }.getOrElse(::sourceOpenFailure)
}

class OpdsSourceAdapterFactory(
    private val booksDir: File,
) : SourceAdapterFactory {
    override val adapterId = SourceAdapterIds.OPDS
    override val latestConfigVersion = 1

    override fun capabilities(configVersion: Int, configJson: String) = SourceCapabilities(
        canSearch = true,
        canFilterByAuthor = true,
        canFilterBySeries = false,
        canFilterByFormat = true,
        canFilterByTag = true,
        canPreviewText = false,
        canDownload = true,
        canBatchAcrossSource = false,
    )

    override fun validate(configVersion: Int, configJson: String): ReadflowResult<Unit> =
        validateHttpCatalogConfig(configVersion, configJson)

    override fun open(descriptor: SourceDescriptor): ReadflowResult<OnlineBookCatalog> = runCatching {
        val config = descriptor.httpCatalogConfig()
        ReadflowResult.Success(
            GenericHttpOnlineCatalog(
                descriptor = descriptor.copy(baseUrl = config.baseUrl),
                booksDir = booksDir,
                wireFormat = GenericCatalogWireFormat.OPDS,
            ),
        )
    }.getOrElse(::sourceOpenFailure)
}

class JsonHttpSourceAdapterFactory(
    private val booksDir: File,
) : SourceAdapterFactory {
    override val adapterId = SourceAdapterIds.JSON_HTTP
    override val latestConfigVersion = 1

    override fun capabilities(configVersion: Int, configJson: String) = SourceCapabilities(
        canSearch = true,
        canFilterByAuthor = true,
        canFilterBySeries = true,
        canFilterByFormat = true,
        canFilterByTag = true,
        canPreviewText = false,
        canDownload = true,
        canBatchAcrossSource = true,
    )

    override fun validate(configVersion: Int, configJson: String): ReadflowResult<Unit> =
        validateHttpCatalogConfig(configVersion, configJson)

    override fun open(descriptor: SourceDescriptor): ReadflowResult<OnlineBookCatalog> = runCatching {
        val config = descriptor.httpCatalogConfig()
        ReadflowResult.Success(
            GenericHttpOnlineCatalog(
                descriptor = descriptor.copy(baseUrl = config.baseUrl),
                booksDir = booksDir,
                wireFormat = GenericCatalogWireFormat.JSON,
            ),
        )
    }.getOrElse(::sourceOpenFailure)
}

fun defaultSourceAdapterRegistry(
    booksDir: File,
    credentialStore: SourceCredentialStore = NoOpSourceCredentialStore,
    networkSnapshotProvider: CalibreNetworkSnapshotProvider = UnknownCalibreNetworkSnapshotProvider,
): SourceAdapterRegistry =
    DefaultSourceAdapterRegistry(
        setOf(
            CalibreSourceAdapterFactory(booksDir, credentialStore::get, networkSnapshotProvider),
            OpdsSourceAdapterFactory(booksDir),
            JsonHttpSourceAdapterFactory(booksDir),
            HtmlRulesV1SourceAdapterFactory(booksDir),
        ),
    )

private fun validateHttpCatalogConfig(configVersion: Int, configJson: String): ReadflowResult<Unit> =
    validateConfig(
        configVersion = configVersion,
        parse = { sourceConfigJson.decodeFromString(HttpCatalogSourceConfig.serializer(), configJson) },
        validate = { requireValidCalibreBaseUrl(it.baseUrl) },
    )

private inline fun <T> validateConfig(
    configVersion: Int,
    parse: () -> T,
    validate: (T) -> Unit,
): ReadflowResult<Unit> {
    if (configVersion != 1) {
        return ReadflowResult.Failure(ReadflowError.unsupported("不支持的书源配置版本：$configVersion"))
    }
    return runCatching {
        validate(parse())
        ReadflowResult.Success(Unit)
    }.getOrElse { error ->
        ReadflowResult.Failure(ReadflowError.parse(error.message ?: "书源配置无效"))
    }
}

private fun sourceOpenFailure(error: Throwable): ReadflowResult.Failure =
    ReadflowResult.Failure(ReadflowError.parse(error.message ?: "书源配置无效"))
