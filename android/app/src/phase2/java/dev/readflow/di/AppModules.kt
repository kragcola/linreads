package dev.readflow.di

import androidx.room.Room
import androidx.room.withTransaction
import dev.readflow.core.calibre.CalibreEndpointProbe
import dev.readflow.core.calibre.CalibreConnectionTester
import dev.readflow.core.calibre.CalibreClient
import dev.readflow.core.calibre.CalibreRepository
import dev.readflow.core.calibre.CalibreRepositoryImpl
import dev.readflow.core.calibre.GuidedCalibreEndpointProbe
import dev.readflow.core.calibre.createCalibreConnectionTester
import dev.readflow.core.database.LibraryRepository
import dev.readflow.core.database.LibraryStore
import dev.readflow.core.database.BackupRestoreTransactionRunner
import dev.readflow.core.database.CompleteBookDeletionStore
import dev.readflow.core.database.CoroutineBookAssetOperationCoordinator
import dev.readflow.core.database.FileManagedBookAssetDeletionStore
import dev.readflow.core.database.LibraryDeletionTransactionRunner
import dev.readflow.core.database.LinReadsBackupExportStore
import dev.readflow.core.database.LinReadsBackupExporter
import dev.readflow.core.database.LinReadsBackupRestoreStore
import dev.readflow.core.database.LinReadsBackupRestorer
import dev.readflow.core.database.NotesMarkdownExportStore
import dev.readflow.core.database.NotesMarkdownFileExporter
import dev.readflow.core.database.ReadflowDatabase
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookAssetOperationCoordinator
import dev.readflow.core.sync.NoOpSyncBackend
import dev.readflow.core.sync.SyncBackend
import dev.readflow.core.sync.SyncManager
import dev.readflow.engine.AndroidEngineStateStore
import dev.readflow.extensions.api.FirstLaunchSeeder
import dev.readflow.extensions.api.LocalBookImporter
import dev.readflow.extensions.api.LocalFileBookSource
import dev.readflow.extensions.api.ReaderEventBus
import dev.readflow.features.library.LibraryViewModel
import dev.readflow.features.reader.ReaderViewModel
import dev.readflow.core.prefs.DataStoreSettingsRepository
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.features.settings.SettingsViewModel
import dev.readflow.render.animate.DefaultPageTransitionHostFactory
import dev.readflow.render.api.EngineDescriptor
import dev.readflow.render.api.EngineStateStore
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
import java.io.File
import org.koin.dsl.module

val coreModule = module {
    single<BookAssetOperationCoordinator> { CoroutineBookAssetOperationCoordinator() }
    single<SyncBackend> { NoOpSyncBackend() }
    single { SyncManager(get()) }
    single { ReaderEventBus() }
}

val databaseModule = module {
    single {
        Room.databaseBuilder(androidContext(), ReadflowDatabase::class.java, "readflow.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
    single { get<ReadflowDatabase>().bookDao() }
    single { get<ReadflowDatabase>().bookDeletionDao() }
    single { get<ReadflowDatabase>().readingProgressDao() }
    single { get<ReadflowDatabase>().textAnnotationDao() }
    single { get<ReadflowDatabase>().inkStrokeDao() }
    single { get<ReadflowDatabase>().bookmarkDao() }
    single { get<ReadflowDatabase>().readingSessionDao() }
    single {
        val database = get<ReadflowDatabase>()
        CompleteBookDeletionStore(
            deletionDao = get(),
            assetStore = FileManagedBookAssetDeletionStore(File(androidContext().filesDir, "books")),
            transactionRunner = LibraryDeletionTransactionRunner { block ->
                database.withTransaction { block() }
            },
            assetOperations = get(),
        )
    }
    single { LibraryRepository(bookDao = get(), completeBookDeletionStore = get()) }
    single<LibraryStore> { get<LibraryRepository>() }
    single<LinReadsBackupExportStore> { LinReadsBackupExporter(get(), get(), get()) }
    single<LinReadsBackupRestoreStore> {
        val database = get<ReadflowDatabase>()
        LinReadsBackupRestorer(
            progressDao = get(),
            bookmarkDao = get(),
            textAnnotationDao = get(),
            transactionRunner = BackupRestoreTransactionRunner { block ->
                database.withTransaction { block() }
            },
        )
    }
    single<NotesMarkdownExportStore> { NotesMarkdownFileExporter(get(), get(), get()) }
}

val extensionsModule = module {
    single { LocalFileBookSource(androidContext()) }
    single<LocalBookImporter> { get<LocalFileBookSource>() }
    single { FirstLaunchSeeder(androidContext(), get(), get(), get()) }
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
    single<EngineStateStore> { AndroidEngineStateStore(androidContext().cacheDir) }
}

val settingsModule = module {
    single<SettingsRepository> { DataStoreSettingsRepository(androidContext()) }
    single<CalibreConnectionTester> { createCalibreConnectionTester() }
    single<CalibreEndpointProbe> { GuidedCalibreEndpointProbe(get()) }
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
    viewModel { LibraryViewModel(get(), get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { ReaderViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}

val appModules = listOf(coreModule, databaseModule, extensionsModule, renderModule, settingsModule, featureModule)

fun seedIfFirstLaunch(app: android.app.Application, seeder: dev.readflow.extensions.api.FirstLaunchSeeder) {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { seeder.seedIfEmpty() }
}
