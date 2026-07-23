package dev.readflow

import android.app.Application
import android.content.Context
import android.util.Log
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.readflow.core.calibre.requireAllowedCalibreRequestUrl
import dev.readflow.core.database.BookDeletionRecoveryFailure
import dev.readflow.core.database.CompleteBookDeletionStore
import dev.readflow.core.model.FontChoice
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.core.ui.FontProvider
import dev.readflow.di.appModules
import dev.readflow.di.seedIfFirstLaunch
import dev.readflow.extensions.api.FirstLaunchSeeder
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import okhttp3.OkHttpClient
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

    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .followRedirects(false)
                                .followSslRedirects(false)
                                .addNetworkInterceptor { chain ->
                                    requireAllowedCalibreRequestUrl(chain.request().url.toString())
                                    chain.proceed(chain.request())
                                }
                                .build()
                        },
                    ),
                )
            }
            .build()
}

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
