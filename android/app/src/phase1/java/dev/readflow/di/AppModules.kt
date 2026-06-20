package dev.readflow.di

import android.app.Application
import androidx.room.Room
import dev.readflow.core.database.LibraryRepository
import dev.readflow.core.database.ReadflowDatabase
import dev.readflow.core.sync.NoOpSyncBackend
import dev.readflow.core.sync.SyncBackend
import dev.readflow.core.sync.SyncManager
import dev.readflow.extensions.api.FirstLaunchSeeder
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
            .fallbackToDestructiveMigration() // Phase 1: schema 不稳定，简化为销毁重建
            .build()
    }
    single { get<ReadflowDatabase>().bookDao() }
    single { get<ReadflowDatabase>().readingProgressDao() }
    single { get<ReadflowDatabase>().textAnnotationDao() }
    single { get<ReadflowDatabase>().inkStrokeDao() }
    single { get<ReadflowDatabase>().bookmarkDao() }
    single { LibraryRepository(get()) }
}

val extensionsModule = module {
    single { LocalFileBookSource(androidContext()) }
    single { FirstLaunchSeeder(androidContext(), get(), get()) }
}

val featureModule = module {
    viewModel { LibraryViewModel(get()) }
}

val appModules = listOf(coreModule, databaseModule, extensionsModule, featureModule)

/** Call from Application.onCreate after Koin start to seed sample books on first launch. */
fun seedIfFirstLaunch(app: Application, seeder: FirstLaunchSeeder) {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        seeder.seedIfEmpty()
    }
}
