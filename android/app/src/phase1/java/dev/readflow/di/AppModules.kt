package dev.readflow.di

import android.app.Application
import androidx.room.Room
import dev.readflow.core.calibre.CalibreClient
import dev.readflow.core.calibre.CalibreRepository
import dev.readflow.core.calibre.CalibreRepositoryImpl
import dev.readflow.core.database.LibraryRepository
import dev.readflow.core.database.LibraryStore
import dev.readflow.core.database.ReadflowDatabase
import dev.readflow.core.prefs.DataStoreSettingsRepository
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.core.sync.NoOpSyncBackend
import dev.readflow.core.sync.SyncBackend
import dev.readflow.core.sync.SyncManager
import dev.readflow.extensions.api.FirstLaunchSeeder
import dev.readflow.extensions.api.LocalBookImporter
import dev.readflow.extensions.api.LocalFileBookSource
import dev.readflow.extensions.api.ReaderEventBus
import dev.readflow.features.library.LibraryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.io.File

/**
 * Phase 1 Koin wiring (真实数据接入). Room + LibraryRepository + LocalFileBookSource +
 * FirstLaunchSeeder 全部接线。首启自动播种 assets/sample_books/。
 */

val coreModule = module {
    single<SyncBackend> { NoOpSyncBackend() }
    single { SyncManager(get()) }
    single { ReaderEventBus() }
}

val databaseModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            ReadflowDatabase::class.java,
            "readflow.db",
        )
            .fallbackToDestructiveMigration(dropAllTables = true) // Phase 1: schema 不稳定，简化为销毁重建
            .build()
    }
    single { get<ReadflowDatabase>().bookDao() }
    single { get<ReadflowDatabase>().readingProgressDao() }
    single { get<ReadflowDatabase>().textAnnotationDao() }
    single { get<ReadflowDatabase>().inkStrokeDao() }
    single { get<ReadflowDatabase>().bookmarkDao() }
    single { get<ReadflowDatabase>().readingSessionDao() }
    single { LibraryRepository(get()) }
    single<LibraryStore> { get<LibraryRepository>() }
}

val extensionsModule = module {
    single { LocalFileBookSource(androidContext()) }
    single<LocalBookImporter> { get<LocalFileBookSource>() }
    single { FirstLaunchSeeder(androidContext(), get(), get()) }
}

val settingsModule = module {
    single<SettingsRepository> { DataStoreSettingsRepository(androidContext()) }
    single<(String) -> CalibreRepository> {
        { baseUrl ->
            CalibreRepositoryImpl(
                client = CalibreClient(baseUrl),
                booksDir = File(androidContext().filesDir, "books"),
            )
        }
    }
}

val featureModule = module {
    viewModel { LibraryViewModel(get(), get(), get(), get()) }
}

val appModules = listOf(coreModule, databaseModule, extensionsModule, settingsModule, featureModule)

/** Call from Application.onCreate after Koin start to seed sample books on first launch. */
fun seedIfFirstLaunch(app: Application, seeder: FirstLaunchSeeder) {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        seeder.seedIfEmpty()
    }
}
