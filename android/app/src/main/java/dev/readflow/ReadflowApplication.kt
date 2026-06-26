package dev.readflow

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.readflow.di.appModules
import dev.readflow.di.seedIfFirstLaunch
import dev.readflow.extensions.api.FirstLaunchSeeder
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/** App entry point. Starts Koin with phase modules + seeds sample books on first launch. */
class ReadflowApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@ReadflowApplication)
            modules(appModules)
        }
        // First-launch seeding: if shelf empty, import assets/sample_books/.
        val seeder: FirstLaunchSeeder by inject()
        seedIfFirstLaunch(this, seeder)
    }

    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory())
            }
            .build()
}
