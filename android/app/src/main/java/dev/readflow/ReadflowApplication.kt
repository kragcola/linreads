package dev.readflow

import android.app.Application
import android.content.Context
import android.util.Log
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.readflow.core.calibre.requireAllowedCalibreRequestUrl
import dev.readflow.core.calibre.CALIBRE_COVER_SOURCE_QUERY_PARAMETER
import dev.readflow.core.calibre.SourceCredentialStore
import dev.readflow.core.calibre.calibreCredentialScopeForRequestUrl
import dev.readflow.core.database.BookDeletionRecoveryFailure
import dev.readflow.core.database.CompleteBookDeletionStore
import dev.readflow.core.model.FontChoice
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.core.ui.FontProvider
import dev.readflow.di.appModules
import dev.readflow.di.seedIfFirstLaunch
import dev.readflow.extensions.api.FirstLaunchSeeder
import dev.readflow.extensions.api.SourceCredentials
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import okhttp3.OkHttpClient
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** App entry point. Starts Koin with phase modules + seeds sample books on first launch. */
class ReadflowApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@ReadflowApplication)
            modules(appModules)
        }
        val deletionStore: CompleteBookDeletionStore by inject()
        val settings: SettingsRepository by inject()
        runBlocking(Dispatchers.IO) {
            recoverBookDeletionsAtStartup(
                recover = deletionStore::recoverInterruptedDeletions,
                onFailure = { bookId, error ->
                    val message = bookId?.let { "Failed to recover staged deletion for $it" }
                        ?: "Failed to enumerate staged book deletions"
                    Log.e("ReadflowApplication", message, error)
                },
            )
            recoverImportedFontDeletionsAtStartup(
                pendingDeletions = { settings.pendingImportedFontDeletions.first() },
                finalizeFile = { choice ->
                    FontProvider.finalizePendingImportedFontDeletion(
                        this@ReadflowApplication,
                        choice,
                    )
                },
                completeDeletion = settings::completeImportedFontDeletion,
                recoverOrphans = {
                    FontProvider.recoverInterruptedFontDeletions(this@ReadflowApplication)
                },
                onFailure = { choice, error ->
                    val target = choice?.serialize() ?: "font deletion ledger"
                    Log.e("ReadflowApplication", "Failed to recover $target", error)
                },
            )
        }
        // First-launch seeding: if shelf empty, import assets/sample_books/.
        val seeder: FirstLaunchSeeder by inject()
        seedIfFirstLaunch(this, seeder)
    }

    override fun newImageLoader(context: Context): ImageLoader {
        val credentialStore: SourceCredentialStore by inject()
        return ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .followRedirects(false)
                                .followSslRedirects(false)
                                .authenticator { _, response -> authenticateCalibreCover(response) }
                                .addNetworkInterceptor { chain ->
                                    chain.proceed(
                                        authenticatedCalibreCoverRequest(chain.request(), credentialStore),
                                    )
                                }
                                .build()
                        },
                    ),
                )
            }
            .build()
    }
}

internal fun authenticatedCalibreCoverRequest(
    request: Request,
    credentialStore: SourceCredentialStore,
): Request {
    val sourceId = request.url.queryParameter(CALIBRE_COVER_SOURCE_QUERY_PARAMETER)
        ?.takeIf(String::isNotBlank)
        ?: return request.also { requireAllowedCalibreRequestUrl(it.url.toString()) }
    val sanitizedUrl = request.url.newBuilder()
        .removeAllQueryParameters(CALIBRE_COVER_SOURCE_QUERY_PARAMETER)
        .build()
    requireAllowedCalibreRequestUrl(sanitizedUrl.toString())
    val scope = calibreCredentialScopeForRequestUrl(sanitizedUrl.toString())
    val credentials = credentialStore.get(sourceId, scope)
    return request.newBuilder()
        .url(sanitizedUrl)
        .removeHeader("Authorization")
        .apply {
            if (credentials != null) tag(SourceCredentials::class.java, credentials)
        }
        .build()
}

internal fun authenticateCalibreCover(
    response: Response,
    cnonceFactory: () -> String = ::newDigestCnonce,
): Request? {
    val request = response.request
    val credentials = request.tag(SourceCredentials::class.java) ?: return null
    if (request.header("Authorization") != null) return null

    val digest = response.challenges().firstOrNull { it.scheme.equals("Digest", ignoreCase = true) }
    if (digest != null) {
        val digestParams = digest.authParams.mapNotNull { (key, value) ->
            key?.let { it.lowercase() to value }
        }.toMap()
        val authorization = digestAuthorization(
            request = request,
            credentials = credentials,
            parameters = digestParams,
            // OkHttp always returns ISO-8859-1 from Challenge.charset when the server omits the
            // parameter (RFC default). Calibre uses UTF-8 for non-ASCII credentials regardless of
            // what the header says, so use UTF-8 unless the server explicitly declared a charset.
            charset = if (digestParams.containsKey("charset")) digest.charset ?: Charsets.UTF_8
                      else Charsets.UTF_8,
            cnonce = cnonceFactory(),
        ) ?: return null
        return request.newBuilder().header("Authorization", authorization).build()
    }

    val basic = response.challenges().firstOrNull { it.scheme.equals("Basic", ignoreCase = true) }
        ?: return null
    // Same OkHttp default-charset caveat as Digest above.
    val basicCharset = if (basic.authParams.any { (k, _) -> k?.lowercase() == "charset" }) {
        basic.charset ?: Charsets.UTF_8
    } else {
        Charsets.UTF_8
    }
    return request.newBuilder()
        .header("Authorization", Credentials.basic(credentials.username, credentials.password, basicCharset))
        .build()
}

private fun digestAuthorization(
    request: Request,
    credentials: SourceCredentials,
    parameters: Map<String, String>,
    charset: java.nio.charset.Charset,
    cnonce: String,
): String? {
    val realm = parameters["realm"] ?: return null
    val nonce = parameters["nonce"] ?: return null
    val algorithm = parameters["algorithm"]?.uppercase() ?: "MD5"
    if (algorithm != "MD5" && algorithm != "MD5-SESS") return null
    val qop = parameters["qop"]
        ?.split(',')
        ?.map(String::trim)
        ?.firstOrNull { it.equals("auth", ignoreCase = true) }
        ?.lowercase()
    if (parameters.containsKey("qop") && qop == null) return null

    val uri = buildString {
        append(request.url.encodedPath)
        request.url.encodedQuery?.let { append('?').append(it) }
    }
    val initialHa1 = md5Hex("${credentials.username}:$realm:${credentials.password}", charset)
    val ha1 = if (algorithm == "MD5-SESS") {
        md5Hex("$initialHa1:$nonce:$cnonce", charset)
    } else {
        initialHa1
    }
    val ha2 = md5Hex("${request.method}:$uri", charset)
    val nonceCount = "00000001"
    val digestResponse = if (qop == null) {
        md5Hex("$ha1:$nonce:$ha2", charset)
    } else {
        md5Hex("$ha1:$nonce:$nonceCount:$cnonce:$qop:$ha2", charset)
    }

    return buildString {
        // OkHttp rejects non-ASCII bytes in header values; percent-encode non-ASCII chars so
        // the header is valid ASCII while preserving the UTF-8 byte sequence the server expects.
        append("Digest username=\"").append(credentials.username.digestAsciiSafe()).append('"')
        append(", realm=\"").append(realm.digestQuoted()).append('"')
        append(", nonce=\"").append(nonce.digestQuoted()).append('"')
        append(", uri=\"").append(uri.digestQuoted()).append('"')
        append(", response=\"").append(digestResponse).append('"')
        append(", algorithm=").append(algorithm)
        if (qop != null) {
            append(", qop=").append(qop)
            append(", nc=").append(nonceCount)
            append(", cnonce=\"").append(cnonce.digestQuoted()).append('"')
        }
        parameters["opaque"]?.let { append(", opaque=\"").append(it.digestQuoted()).append('"') }
    }
}

/** Escapes `\` and `"` for use inside a Digest quoted-string directive. */
private fun String.digestQuoted(): String = replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * Makes a Digest `username` value safe for an HTTP header by percent-encoding any code-point
 * above U+007F as its UTF-8 bytes.  OkHttp rejects raw non-ASCII octets in header values
 * (checkValue throws IllegalArgumentException), so we encode them here.  Calibre's content
 * server decodes percent-encoded usernames before matching, so the server-side lookup still
 * works for non-ASCII usernames.
 */
private fun String.digestAsciiSafe(): String = buildString {
    for (char in this@digestAsciiSafe) {
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char.code > 0x7F -> char.toString().toByteArray(Charsets.UTF_8)
                .forEach { b -> append('%').append("%02X".format(b.toInt() and 0xff)) }
            else -> append(char)
        }
    }
}

private fun md5Hex(value: String, charset: java.nio.charset.Charset): String =
    MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(charset))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun newDigestCnonce(): String = ByteArray(16)
    .also(SecureRandom()::nextBytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal suspend fun recoverImportedFontDeletionsAtStartup(
    pendingDeletions: suspend () -> Set<FontChoice.Custom>,
    finalizeFile: (FontChoice.Custom) -> Result<Unit>,
    completeDeletion: suspend (FontChoice.Custom) -> Unit,
    recoverOrphans: () -> Result<Unit>,
    onFailure: (choice: FontChoice.Custom?, error: Throwable) -> Unit,
) {
    val pending = try {
        pendingDeletions()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onFailure(null, error)
        return
    }
    pending.forEach { choice ->
        val finalized = try {
            finalizeFile(choice)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
        val fileError = finalized.exceptionOrNull()
        if (fileError != null) {
            onFailure(choice, fileError)
            return@forEach
        }
        try {
            completeDeletion(choice)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onFailure(choice, error)
        }
    }
    val orphanRecovery = try {
        recoverOrphans()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
    orphanRecovery.exceptionOrNull()?.let { error -> onFailure(null, error) }
}

internal suspend fun recoverBookDeletionsAtStartup(
    recover: suspend () -> List<BookDeletionRecoveryFailure>,
    onFailure: (bookId: String?, error: Throwable) -> Unit,
) {
    try {
        recover().forEach { failure -> onFailure(failure.bookId, failure.error) }
    } catch (error: Throwable) {
        onFailure(null, error)
    }
}
