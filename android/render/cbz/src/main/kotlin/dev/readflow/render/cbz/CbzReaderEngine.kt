package dev.readflow.render.cbz

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.github.panpf.zoomimage.ZoomImageView
import com.github.panpf.zoomimage.subsampling.ImageSource
import com.github.panpf.zoomimage.subsampling.TileAnimationSpec
import com.github.panpf.zoomimage.subsampling.TileState
import com.github.panpf.zoomimage.subsampling.fromFile
import com.github.panpf.zoomimage.util.Logger
import dev.readflow.core.archive.ZipArchiveLimits
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.pageChapterInfo
import dev.readflow.core.model.readerPaletteFor
import dev.readflow.render.api.DirectionalPagedReaderEngine
import dev.readflow.render.api.PageReadingDirection
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReadingMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

/** Local CBZ reader: bounded ZIP extraction and a small, zoomable rapid-turn working set. */
class CbzReaderEngine(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DirectionalPagedReaderEngine {
    override val id: String = "cbz-image-pager"
    override val format: BookFormat = BookFormat.CBZ
    override val priority: Int = 0
    override val supportsSearch: Boolean = false
    override val supportedModes: Set<ReadingMode> = setOf(ReadingMode.PAGED)
    override val preferredOffscreenPageLimit: Int = PREFETCH_RADIUS

    private val _pagingKind = MutableStateFlow(PagingKind.PAGED)
    override val pagingKind: StateFlow<PagingKind> = _pagingKind.asStateFlow()

    private val _pageReadingDirection = MutableStateFlow(PageReadingDirection.LEFT_TO_RIGHT)
    override val pageReadingDirection: StateFlow<PageReadingDirection> =
        _pageReadingDirection.asStateFlow()

    private val _currentLocator = MutableStateFlow(Locator(LocatorStrategy.Unknown))
    override val currentLocator: StateFlow<Locator> = _currentLocator.asStateFlow()

    private val _pageCount = MutableStateFlow(0)
    override val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private val _chapterInfo = MutableStateFlow(pageChapterInfo(0, 0, DOCUMENT_TITLE))
    override val chapterInfo: StateFlow<ChapterInfo> = _chapterInfo.asStateFlow()

    private var pageRequestCallback: ((pageIndex: Int) -> Unit)? = null
    private var archiveSession: CbzArchiveSession? = null
    private var copiedArchive: File? = null
    private var generation: Long = 0L
    private var prefetchEpoch: Long = 0L
    private var prefetchJob: Job? = null
    private var themeMode: ThemeMode = ThemeMode.SYSTEM
    private var scope: CoroutineScope = newScope()
    private val zoomImageContext = ContextThemeWrapper(
        context,
        androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar,
    )
    private val activeBindings = Collections.newSetFromMap(WeakHashMap<CbzPageBinding, Boolean>())

    override suspend fun supports(uri: Uri): Boolean =
        uri.lastPathSegment.orEmpty().substringAfterLast('.', "").equals("cbz", ignoreCase = true)

    override suspend fun openBook(uri: Uri): Locator {
        val currentGeneration = ++generation
        activeBindings.toList().forEach { retireBinding(it) }
        activeBindings.clear()
        closeOpenArchive()
        if (!scope.isActive) scope = newScope()
        val archive = withContext(ioDispatcher) {
            clearStaleCaches()
            readableArchiveFile(uri)
        }
        val output = File(
            context.cacheDir,
            "$CBZ_PAGE_CACHE_DIR/${UUID.randomUUID()}",
        )
        val session = try {
            withContext(ioDispatcher) {
                CbzArchiveSession.open(archive.file, output).let { opened ->
                    try {
                        opened.preparePageBlocking(0)
                        opened
                    } catch (error: Throwable) {
                        opened.close()
                        throw error
                    }
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + ioDispatcher) {
                output.deleteRecursively()
                archive.file.takeIf { archive.temporary }?.delete()
            }
            throw error
        }
        if (currentGeneration != generation) {
            withContext(NonCancellable + ioDispatcher) {
                session.close()
                archive.file.takeIf { archive.temporary }?.delete()
            }
            throw IOException("CBZ 打开请求已过期")
        }
        archiveSession = session
        copiedArchive = archive.file.takeIf { archive.temporary }
        val total = session.manifest.pages.size
        val initial = pageLocator(0, total)
        _pageCount.value = total
        _pageReadingDirection.value = when (session.manifest.readingDirection) {
            CbzReadingDirection.LEFT_TO_RIGHT -> PageReadingDirection.LEFT_TO_RIGHT
            CbzReadingDirection.RIGHT_TO_LEFT -> PageReadingDirection.RIGHT_TO_LEFT
        }
        publishLocator(initial)
        prefetchAround(0)
        return initial
    }

    override fun createView(): View = createPageView(pageIndexForLocator(_currentLocator.value))

    override fun createPageView(pageIndex: Int): View {
        val total = _pageCount.value.coerceAtLeast(1)
        val index = pageIndex.coerceIn(0, total - 1)
        val palette = currentPalette()
        val pageDescription = "漫画第 ${index + 1} 页，共 $total 页"
        val image = ZoomImageView(zoomImageContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundColor(palette.paper)
            subsampling.setTileAnimationSpec(TileAnimationSpec.None)
        }
        val progress = ProgressBar(context).apply {
            contentDescription = "正在加载第 ${index + 1} 页"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val errorText = TextView(context).apply {
            text = "此页图片无法显示"
            setTextColor(palette.ink)
            gravity = Gravity.CENTER
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val container = CbzPageGestureHost(
            context = context,
            zoomImage = image,
            onBoundarySwipe = { dragX -> requestBoundaryPage(index, dragX) },
        ).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(palette.paper)
            contentDescription = pageDescription
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(image)
            addView(
                progress,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
            addView(
                errorText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
        val binding = CbzPageBinding(
            pageIndex = index,
            ownerGeneration = generation,
            pageDescription = pageDescription,
            container = container,
            image = image,
            progress = progress,
            errorText = errorText,
        )
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                if (binding.retired || binding.ownerGeneration != generation) return
                binding.disposed = false
                binding.decoderFailed = false
                activeBindings.add(binding)
                applyPageContentState(binding, CbzPageContentState.LOADING)
                loadPage(binding)
            }

            override fun onViewDetachedFromWindow(view: View) {
                releaseBinding(binding)
            }
        })
        return container
    }

    override fun setPageRequestCallback(callback: ((pageIndex: Int) -> Unit)?) {
        pageRequestCallback = callback
    }

    override suspend fun goTo(locator: Locator) {
        val total = _pageCount.value
        if (total <= 0) return
        val index = pageIndexForLocator(locator)
        publishLocator(pageLocator(index, total))
        pageRequestCallback?.invoke(index)
        prefetchAround(index)
    }

    override suspend fun seekToProgress(fraction: Float) {
        val total = _pageCount.value
        if (total <= 0) return
        val target = (fraction.coerceIn(0f, 1f) * total).toInt().coerceIn(0, total - 1)
        goTo(pageLocator(target, total))
    }

    override suspend fun close() {
        generation++
        activeBindings.toList().forEach { retireBinding(it) }
        activeBindings.clear()
        prefetchJob?.cancel()
        prefetchJob = null
        scope.cancel()
        closeOpenArchive()
        pageRequestCallback = null
        _pageCount.value = 0
        _pageReadingDirection.value = PageReadingDirection.LEFT_TO_RIGHT
        publishLocator(Locator(LocatorStrategy.Unknown))
    }

    override suspend fun setFontSize(sp: Float) = Unit

    override suspend fun setMode(mode: ReadingMode) {
        _pagingKind.value = PagingKind.PAGED
    }

    override suspend fun setTheme(mode: ThemeMode) {
        if (themeMode == mode) return
        themeMode = mode
        val paper = currentPalette().paper
        activeBindings.forEach { binding ->
            binding.container.setBackgroundColor(paper)
            binding.image.setBackgroundColor(paper)
            binding.errorText.setTextColor(currentPalette().ink)
        }
    }

    internal fun preparedPageIndexesForTest(): Set<Int> =
        archiveSession?.preparedIndexes().orEmpty()

    internal fun activePageIndexesForTest(): Set<Int> =
        activeBindings.filterNot(CbzPageBinding::disposed).mapTo(linkedSetOf(), CbzPageBinding::pageIndex)

    private fun publishLocator(locator: Locator) {
        _currentLocator.value = locator
        val total = _pageCount.value
        val index = if (total > 0) pageIndexForLocator(locator) else 0
        _chapterInfo.value = pageChapterInfo(index, total, DOCUMENT_TITLE)
    }

    private fun requestBoundaryPage(currentIndex: Int, dragX: Float) {
        val total = _pageCount.value
        if (total <= 0 || dragX == 0f) return
        val physicalDelta = if (dragX < 0f) 1 else -1
        val logicalDelta = when (_pageReadingDirection.value) {
            PageReadingDirection.LEFT_TO_RIGHT -> physicalDelta
            PageReadingDirection.RIGHT_TO_LEFT -> -physicalDelta
        }
        val target = (currentIndex + logicalDelta).coerceIn(0, total - 1)
        if (target != currentIndex) pageRequestCallback?.invoke(target)
    }

    private fun loadPage(binding: CbzPageBinding) {
        if (binding.disposed || binding.job?.isActive == true) return
        val expectedGeneration = generation
        binding.job?.cancel()
        binding.job = scope.launch {
            val prepared = try {
                withContext(ioDispatcher) {
                    archiveSession?.preparePageBlocking(binding.pageIndex) {
                        isActive && !binding.disposed && expectedGeneration == generation
                    }
                } ?: return@launch
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (!binding.disposed && expectedGeneration == generation) {
                    binding.progress.visibility = View.GONE
                    binding.errorText.visibility = View.VISIBLE
                    binding.container.contentDescription =
                        "漫画第 ${binding.pageIndex + 1} 页加载失败"
                }
                return@launch
            }
            if (
                binding.disposed || expectedGeneration != generation ||
                !activeBindings.contains(binding)
            ) {
                return@launch
            }
            try {
                val geometry = cbzSubsamplingGeometry(prepared.width, prepared.height)
                if (geometry == null) {
                    val bitmap = withContext(ioDispatcher) { decodeDirectPageBitmap(prepared) }
                    if (
                        binding.disposed || expectedGeneration != generation ||
                        !activeBindings.contains(binding)
                    ) {
                        bitmap.recycle()
                        return@launch
                    }
                    binding.directBitmap = bitmap
                    binding.image.setImageBitmap(bitmap)
                    applyPageContentState(binding, CbzPageContentState.CONTENT)
                    return@launch
                }
                installDecoderFailureBridge(binding, expectedGeneration)
                binding.image.setImageDrawable(
                    CbzPageSizeDrawable(geometry.width, geometry.height),
                )
                binding.image.setSubsamplingImage(ImageSource.fromFile(prepared.file))
                val subsampling = binding.image.subsampling
                combine(
                    subsampling.readyState,
                    subsampling.imageLoadRectState,
                    subsampling.foregroundTilesState,
                    subsampling.backgroundTilesState,
                ) { decoderReady, loadRect, foregroundTiles, backgroundTiles ->
                    var hasForegroundTile = false
                    var allForegroundTilesFailed = true
                    var hasRenderableTile = false
                    if (!loadRect.isEmpty) {
                        foregroundTiles.forEach { tile ->
                            if (tile.srcRect.overlaps(loadRect)) {
                                hasForegroundTile = true
                                if (tile.state != TileState.STATE_ERROR) {
                                    allForegroundTilesFailed = false
                                }
                                if (tile.alpha > 0 && tile.tileImage?.isRecycled == false) {
                                    hasRenderableTile = true
                                }
                            }
                        }
                        if (!hasRenderableTile) {
                            backgroundTiles.forEach { tile ->
                                if (
                                    tile.srcRect.overlaps(loadRect) && tile.alpha > 0 &&
                                    tile.tileImage?.isRecycled == false
                                ) {
                                    hasRenderableTile = true
                                }
                            }
                        }
                    }
                    if (binding.decoderFailed) {
                        CbzPageContentState.ERROR
                    } else {
                        cbzPageContentState(
                            decoderReady = decoderReady,
                            loadRectReady = !loadRect.isEmpty,
                            hasForegroundTile = hasForegroundTile,
                            allForegroundTilesFailed = allForegroundTilesFailed,
                            hasRenderableTile = hasRenderableTile,
                        )
                    }
                }
                    .distinctUntilChanged()
                    .collect { state ->
                        if (
                            binding.disposed || expectedGeneration != generation ||
                            !activeBindings.contains(binding)
                        ) {
                            return@collect
                        }
                        applyPageContentState(binding, state)
                    }
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (!binding.disposed && expectedGeneration == generation) {
                    applyPageContentState(binding, CbzPageContentState.ERROR)
                }
            }
        }
    }

    private fun installDecoderFailureBridge(binding: CbzPageBinding, expectedGeneration: Long) {
        if (binding.originalLogPipeline != null) return
        val logger = binding.image.logger
        binding.originalLogPipeline = logger.pipeline
        binding.originalLogLevel = logger.level
        logger.pipeline = CbzZoomLogPipeline(logger.pipeline) {
            if (binding.decoderFailed) return@CbzZoomLogPipeline
            binding.decoderFailed = true
            val publishFailure = Runnable {
                if (
                    !binding.disposed && expectedGeneration == generation &&
                    activeBindings.contains(binding)
                ) {
                    applyPageContentState(binding, CbzPageContentState.ERROR)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                publishFailure.run()
            } else {
                binding.image.post(publishFailure)
            }
        }
        logger.level = Logger.Level.Debug
    }

    private fun decodeDirectPageBitmap(prepared: PreparedCbzPage): Bitmap {
        val pixels = prepared.width.toLong() * prepared.height.toLong()
        if (pixels <= 0L || pixels > MAX_DIRECT_BITMAP_PIXELS) {
            throw IOException("CBZ 页面尺寸不适合直接解码")
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(prepared.file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            BitmapFactory.decodeFile(prepared.file.absolutePath)
                ?: throw IOException("CBZ 页面无法解码")
        }
    }

    private fun applyPageContentState(binding: CbzPageBinding, state: CbzPageContentState) {
        when (state) {
            CbzPageContentState.LOADING -> {
                binding.progress.visibility = View.VISIBLE
                binding.errorText.visibility = View.GONE
                binding.container.contentDescription = "${binding.pageDescription}，正在加载"
            }
            CbzPageContentState.CONTENT -> {
                binding.progress.visibility = View.GONE
                binding.errorText.visibility = View.GONE
                binding.container.contentDescription = binding.pageDescription
            }
            CbzPageContentState.ERROR -> {
                binding.progress.visibility = View.GONE
                binding.errorText.visibility = View.VISIBLE
                binding.container.contentDescription =
                    "漫画第 ${binding.pageIndex + 1} 页加载失败"
            }
        }
    }

    private fun prefetchAround(center: Int) {
        val session = archiveSession ?: return
        val total = _pageCount.value
        val expectedGeneration = generation
        val expectedEpoch = ++prefetchEpoch
        val retained = buildList {
            add(center)
            for (distance in 1..PREFETCH_RADIUS) {
                add(center + distance)
                add(center - distance)
            }
        }
            .filterTo(linkedSetOf()) { it in 0 until total }
        activeBindings.forEach { binding ->
            if (!binding.disposed && binding.pageIndex in 0 until total) {
                retained += binding.pageIndex
            }
        }
        prefetchJob?.cancel()
        prefetchJob = scope.launch(ioDispatcher) {
            session.retainPreparedIndexes(retained)
            retained.forEach { pageIndex ->
                if (!isActive || expectedGeneration != generation || expectedEpoch != prefetchEpoch) {
                    return@launch
                }
                runCatching {
                    session.preparePageBlocking(pageIndex) {
                        isActive && expectedGeneration == generation && expectedEpoch == prefetchEpoch
                    }
                }
            }
        }
    }

    private fun releaseBinding(binding: CbzPageBinding) {
        if (binding.disposed) return
        binding.disposed = true
        binding.job?.cancel()
        binding.job = null
        activeBindings.remove(binding)
        binding.image.setSubsamplingImage(null as ImageSource?)
        binding.image.setImageDrawable(null)
        binding.directBitmap?.recycle()
        binding.directBitmap = null
        binding.originalLogPipeline?.let { binding.image.logger.pipeline = it }
        binding.originalLogLevel?.let { binding.image.logger.level = it }
        binding.originalLogPipeline = null
        binding.originalLogLevel = null
    }

    private fun retireBinding(binding: CbzPageBinding) {
        binding.retired = true
        releaseBinding(binding)
        activeBindings.remove(binding)
    }

    private suspend fun closeOpenArchive() {
        prefetchEpoch++
        prefetchJob?.cancel()
        prefetchJob = null
        val oldSession = archiveSession
        val oldCopy = copiedArchive
        archiveSession = null
        copiedArchive = null
        if (oldSession != null || oldCopy != null) {
            withContext(ioDispatcher) {
                oldSession?.close()
                oldCopy?.delete()
            }
        }
    }

    private fun readableArchiveFile(uri: Uri): ArchiveFile {
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File)
            if (file?.isFile == true) {
                if (file.length() > MAX_SOURCE_ARCHIVE_BYTES) {
                    throw IOException("CBZ 源文件超过安全上限")
                }
                return ArchiveFile(file, temporary = false)
            }
        }
        val directory = File(context.cacheDir, CBZ_SOURCE_CACHE_DIR).apply { mkdirs() }
        val copy = File.createTempFile("cbz-", ".cbz", directory)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                copy.outputStream().use { output ->
                    copyCbzArchiveBounded(input, output, MAX_SOURCE_ARCHIVE_BYTES)
                }
            } ?: throw IOException("无法读取 CBZ 文件")
            return ArchiveFile(copy, temporary = true)
        } catch (error: Throwable) {
            copy.delete()
            throw error
        }
    }

    private fun clearStaleCaches() {
        synchronized(CACHE_CLEANUP_LOCK) {
            if (staleCacheCleanupComplete) return
            listOf(CBZ_PAGE_CACHE_DIR, CBZ_SOURCE_CACHE_DIR).forEach { name ->
                val root = File(context.cacheDir, name)
                root.listFiles()?.forEach(File::deleteRecursively)
                root.mkdirs()
            }
            staleCacheCleanupComplete = true
        }
    }

    private fun currentPalette() = readerPaletteFor(
        themeMode,
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES,
    )

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class ArchiveFile(val file: File, val temporary: Boolean)

    private class CbzPageBinding(
        val pageIndex: Int,
        val ownerGeneration: Long,
        val pageDescription: String,
        val container: FrameLayout,
        val image: ZoomImageView,
        val progress: ProgressBar,
        val errorText: TextView,
        var job: Job? = null,
        var disposed: Boolean = true,
        var retired: Boolean = false,
        var directBitmap: Bitmap? = null,
        var originalLogPipeline: Logger.Pipeline? = null,
        var originalLogLevel: Logger.Level? = null,
        @Volatile var decoderFailed: Boolean = false,
    )

    internal companion object {
        const val PREFETCH_RADIUS = 2
        const val CBZ_PAGE_CACHE_DIR = "cbz_pages"
        const val CBZ_SOURCE_CACHE_DIR = "cbz_sources"
        const val DOCUMENT_TITLE = "漫画"
        const val MAX_SOURCE_ARCHIVE_BYTES = ZipArchiveLimits.DEFAULT_MAX_SOURCE_BYTES
        const val MAX_DIRECT_BITMAP_PIXELS = 4L * 1024L * 1024L
        private val CACHE_CLEANUP_LOCK = Any()

        @Volatile
        private var staleCacheCleanupComplete = false

        fun resetCacheCleanupForTest() {
            synchronized(CACHE_CLEANUP_LOCK) {
                staleCacheCleanupComplete = false
            }
        }
    }
}

internal data class CbzSubsamplingGeometry(val width: Int, val height: Int)

/** Arbitrates ZoomImage pan/pinch against the horizontal pager without delaying fit-scale swipes. */
internal class CbzPageGestureHost(
    context: Context,
    private val zoomImage: ZoomImageView,
    private val onBoundarySwipe: (dragX: Float) -> Unit,
) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var hasMultiplePointers = false
    private var gestureDirectionUndecided = false
    private var releasedToPager = false
    private var boundaryDragX = 0f

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        val imageOwnsHorizontalGesture =
            zoomImage.canScrollHorizontally(-1) || zoomImage.canScrollHorizontally(1)
        parent?.requestDisallowInterceptTouchEvent(
            disallowIntercept &&
                (gestureDirectionUndecided || hasMultiplePointers || imageOwnsHorizontalGesture),
        )
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                hasMultiplePointers = false
                gestureDirectionUndecided = true
                releasedToPager = false
                boundaryDragX = 0f
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                hasMultiplePointers = true
                gestureDirectionUndecided = false
                releasedToPager = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1 || hasMultiplePointers) {
                    hasMultiplePointers = true
                    releasedToPager = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    val dragX = event.x - downX
                    val dragY = event.y - downY
                    val horizontal = kotlin.math.abs(dragX)
                    val vertical = kotlin.math.abs(dragY)
                    if (horizontal > touchSlop && horizontal > vertical) {
                        gestureDirectionUndecided = false
                        val scrollDirection = if (dragX > 0f) -1 else 1
                        val imageCanScroll = zoomImage.canScrollHorizontally(scrollDirection)
                        releasedToPager = !imageCanScroll
                        boundaryDragX = if (imageCanScroll) 0f else dragX
                        parent?.requestDisallowInterceptTouchEvent(imageCanScroll)
                    } else if (vertical > touchSlop && vertical > horizontal) {
                        gestureDirectionUndecided = false
                        releasedToPager = false
                        boundaryDragX = 0f
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                val dragY = event.y - downY
                if (
                    releasedToPager && !hasMultiplePointers &&
                    kotlin.math.abs(boundaryDragX) > touchSlop &&
                    kotlin.math.abs(boundaryDragX) > kotlin.math.abs(dragY)
                ) {
                    onBoundarySwipe(boundaryDragX)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                resetGesture()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (
                    releasedToPager && !hasMultiplePointers &&
                    kotlin.math.abs(boundaryDragX) > touchSlop
                ) {
                    onBoundarySwipe(boundaryDragX)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                resetGesture()
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    private fun resetGesture() {
        hasMultiplePointers = false
        gestureDirectionUndecided = false
        releasedToPager = false
        boundaryDragX = 0f
    }
}

internal fun cbzSubsamplingGeometry(width: Int, height: Int): CbzSubsamplingGeometry? {
    if (width < 2 || height < 2) return null
    return CbzSubsamplingGeometry(width / 2, height / 2)
}

internal class CbzZoomLogPipeline(
    private val delegate: Logger.Pipeline,
    private val onDecoderFailure: () -> Unit,
) : Logger.Pipeline {
    override fun log(level: Logger.Level, tag: String, msg: String, tr: Throwable?) {
        if (isCbzDecoderFailureLog(msg)) onDecoderFailure()
        if (level >= Logger.Level.Info) delegate.log(level, tag, msg, tr)
    }

    override fun flush() = delegate.flush()
}

internal fun isCbzDecoderFailureLog(message: String): Boolean =
    ". resetTileDecoder:" in message && ". failed." in message

internal fun copyCbzArchiveBounded(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long,
): Long {
    require(maxBytes >= 0L)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return copied
        if (copied > maxBytes - count) throw IOException("CBZ 源文件超过安全上限")
        output.write(buffer, 0, count)
        copied += count
    }
}

internal enum class CbzPageContentState { LOADING, CONTENT, ERROR }

/** Supplies ZoomImage's content geometry without decoding or displaying a preview bitmap. */
internal class CbzPageSizeDrawable(
    private val imageWidth: Int,
    private val imageHeight: Int,
) : Drawable() {
    override fun getIntrinsicWidth(): Int = imageWidth

    override fun getIntrinsicHeight(): Int = imageHeight

    override fun draw(canvas: Canvas) = Unit

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Android SDK")
    override fun getOpacity(): Int = PixelFormat.TRANSPARENT
}

internal fun cbzPageContentState(
    decoderReady: Boolean,
    loadRectReady: Boolean,
    hasForegroundTile: Boolean,
    allForegroundTilesFailed: Boolean,
    hasRenderableTile: Boolean,
): CbzPageContentState {
    if (hasRenderableTile) {
        return CbzPageContentState.CONTENT
    }
    if (!decoderReady || !loadRectReady || !hasForegroundTile) {
        return CbzPageContentState.LOADING
    }
    return if (allForegroundTilesFailed) {
        CbzPageContentState.ERROR
    } else {
        CbzPageContentState.LOADING
    }
}

private fun pageLocator(index: Int, total: Int): Locator {
    val safeTotal = total.coerceAtLeast(1)
    val safeIndex = index.coerceIn(0, safeTotal - 1)
    val progression = safeIndex.toFloat() / safeTotal
    return Locator(
        strategy = LocatorStrategy.Page(safeIndex, safeTotal),
        progression = progression,
        totalProgression = progression,
    )
}
