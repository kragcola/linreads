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
import dev.readflow.di.appModules
import dev.readflow.di.seedIfFirstLaunch
import dev.readflow.extensions.api.FirstLaunchSeeder
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import okhttp3.OkHttpClient
import kotlinx.coroutines.Dispatchers
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
        runBlocking(Dispatchers.IO) {
            recoverBookDeletionsAtStartup(
                recover = deletionStore::recoverInterruptedDeletions,
                onFailure = { bookId, error ->
                    val message = bookId?.let { "Failed to recover staged deletion for $it" }
                        ?: "Failed to enumerate staged book deletions"
                    Log.e("ReadflowApplication", message, error)
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
