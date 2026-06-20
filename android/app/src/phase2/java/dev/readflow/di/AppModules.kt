package dev.readflow.di

import androidx.room.Room
import dev.readflow.core.database.LibraryRepository
import dev.readflow.core.database.ReadflowDatabase
import dev.readflow.core.model.BookFormat
import dev.readflow.core.sync.NoOpSyncBackend
import dev.readflow.core.sync.SyncBackend
import dev.readflow.core.sync.SyncManager
import dev.readflow.extensions.api.FirstLaunchSeeder
import dev.readflow.extensions.api.LocalFileBookSource
import dev.readflow.extensions.api.ReaderEventBus
import dev.readflow.features.library.LibraryViewModel
import dev.readflow.features.reader.ReaderViewModel
import dev.readflow.core.prefs.DataStoreSettingsRepository
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.features.settings.SettingsViewModel
import dev.readflow.render.animate.DefaultPageTransitionHostFactory
import dev.readflow.render.api.EngineDescriptor
import dev.readflow.render.api.PageTransitionHostFactory
import dev.readflow.render.api.ReaderEngineRegistry
import dev.readflow.render.epub.EpubReflowEngine
import dev.readflow.render.pdf.PdfRendererEngine
import dev.readflow.render.md.MarkdownEngine
import dev.readflow.render.txt.TxtVirtualPagerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val coreModule = module {
    single<SyncBackend> { NoOpSyncBackend() }
    single { SyncManager(get()) }
    single { ReaderEventBus() }
}

val databaseModule = module {
    single {
        Room.databaseBuilder(androidContext(), ReadflowDatabase::class.java, "readflow.db")
            .fallbackToDestructiveMigration()
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

val renderModule = module {
    single(named("txt")) {
        EngineDescriptor(
            id = "txt-virtual-pager",
            format = BookFormat.TXT,
            priority = 0,
            quickSupports = { uri ->
                (uri.lastPathSegment ?: uri.path ?: "").substringAfterLast('.', "")
                    .equals("txt", ignoreCase = true)
            },
            provider = { TxtVirtualPagerEngine(androidContext()) },
        )
    }
    single(named("epub")) {
        EngineDescriptor(
            id = "epub-reflow",
            format = BookFormat.EPUB,
            priority = 0,
            quickSupports = { uri ->
                (uri.lastPathSegment ?: uri.path ?: "").substringAfterLast('.', "")
                    .equals("epub", ignoreCase = true)
            },
            provider = { EpubReflowEngine(androidContext()) },
        )
    }
    single(named("pdf")) {
        EngineDescriptor(
            id = "pdf-renderer",
            format = BookFormat.PDF,
            priority = 0,
            quickSupports = { uri ->
                (uri.lastPathSegment ?: uri.path ?: "").substringAfterLast('.', "")
                    .equals("pdf", ignoreCase = true)
            },
            provider = { PdfRendererEngine(androidContext()) },
        )
    }
    single(named("md")) {
        EngineDescriptor(
            id = "md-markwon",
            format = BookFormat.MD,
            priority = 0,
            quickSupports = { uri ->
                (uri.lastPathSegment ?: uri.path ?: "").substringAfterLast('.', "")
                    .let { it.equals("md", ignoreCase = true) || it.equals("markdown", ignoreCase = true) }
            },
            provider = { MarkdownEngine(androidContext()) },
        )
    }
    single {
        ReaderEngineRegistry(
            descriptors = setOf(
                get<EngineDescriptor>(named("txt")),
                get<EngineDescriptor>(named("epub")),
                get<EngineDescriptor>(named("pdf")),
                get<EngineDescriptor>(named("md")),
            ),
            userOverrides = MutableStateFlow(emptyMap<BookFormat, String>()),
        )
    }
    single<PageTransitionHostFactory> { DefaultPageTransitionHostFactory(androidContext()) }
}

val settingsModule = module {
    single<SettingsRepository> { DataStoreSettingsRepository(androidContext()) }
}

val featureModule = module {
    viewModel { LibraryViewModel(get(), get(), get()) }
    viewModel { SettingsViewModel(get()) }
    viewModel { ReaderViewModel(get(), get(), get(), get(), get()) }
}

val appModules = listOf(coreModule, databaseModule, extensionsModule, renderModule, settingsModule, featureModule)

fun seedIfFirstLaunch(app: android.app.Application, seeder: dev.readflow.extensions.api.FirstLaunchSeeder) {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { seeder.seedIfEmpty() }
}
