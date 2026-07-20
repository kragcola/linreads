package dev.readflow.render.epub

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImageSize

/**
 * The load-bearing invariant of the continuous-flow rewrite (审计 C2/L12): the styled Spannable's
 * text MUST be byte-identical to [EpubChapterFlow.text], in every branch — including a missing image
 * (alt-text fallback). If length ever diverges, the offset map and persisted Locator break.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpubFlowSpannableTest {

    private val attachedDrawableCallback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) = Unit
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
        override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
    }

    private fun style() = EpubFlowStyle(
        fontSizeSp = 18f,
        lineSpacingMultiplier = 1.5f,
        inkColor = 0xFF000000.toInt(),
        typeface = android.graphics.Typeface.SERIF,
        columnWidthPx = 800,
        imageMaxHeightPx = 1200,
        density = 2f,
    )

    private fun text(p: Int, s: String) =
        EpubDisplayBlock.Text(text = s, headingLevel = null, paragraphIndex = p)

    private fun heading(p: Int, s: String) =
        EpubDisplayBlock.Text(text = s, headingLevel = 1, paragraphIndex = p)

    private fun image(p: Int, href: String, alt: String? = null) =
        EpubDisplayBlock.Image(href = href, altText = alt, paragraphIndex = p)

    @Test
    fun `embedded typeface span preserves an existing bold style`() {
        val paint = android.text.TextPaint().apply { typeface = Typeface.DEFAULT_BOLD }

        EpubTypefaceSpan(Typeface.DEFAULT).updateMeasureState(paint)

        assertTrue(
            paint.typeface.style and Typeface.BOLD != 0 || paint.isFakeBoldText,
        )
    }

    @Test
    fun `embedded typeface span caches four styles and clears stale fake bold skew`() {
        val span = EpubTypefaceSpan(Typeface.DEFAULT)
        val paint = android.text.TextPaint()

        // Warm the four Android style combinations; cache must stay at most size 4.
        listOf(
            Typeface.NORMAL,
            Typeface.BOLD,
            Typeface.ITALIC,
            Typeface.BOLD_ITALIC,
        ).forEach { style ->
            paint.typeface = Typeface.defaultFromStyle(style)
            paint.isFakeBoldText = false
            paint.textSkewX = 0f
            span.updateMeasureState(paint)
            span.updateDrawState(paint)
        }
        assertEquals(4, span.styleCacheSizeForTest())

        // Re-applying the same styles must not grow the cache.
        paint.typeface = Typeface.DEFAULT_BOLD
        span.updateMeasureState(paint)
        assertEquals(4, span.styleCacheSizeForTest())

        // After a bold pass that may set fake-bold, a normal pass must clear both paint flags.
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.isFakeBoldText = true
        paint.textSkewX = -0.25f
        span.updateMeasureState(paint)
        paint.typeface = Typeface.DEFAULT
        span.updateMeasureState(paint)
        assertFalse(paint.isFakeBoldText)
        assertEquals(0f, paint.textSkewX, 0.0001f)
        assertEquals(4, span.styleCacheSizeForTest())
    }

    @Test
    fun `font family style span uses bookFontResolver for measure and draw typeface`() {
        val resolved = Typeface.MONOSPACE
        var resolveCalls = 0
        val flow = epubBuildChapterFlow(
            0,
            listOf(
                EpubDisplayBlock.Text(
                    text = "Styled family",
                    headingLevel = null,
                    paragraphIndex = 0,
                    styleSpans = listOf(
                        EpubTextStyleSpan(
                            start = 0,
                            end = "Styled family".length,
                            style = EpubTextStyle.FontFamily,
                            fontFamily = "Story, serif",
                        ),
                    ),
                ),
            ),
        )
        val sb = build(
            flow,
            style = style().copy(
                bookFontResolver = { family ->
                    resolveCalls += 1
                    assertTrue(family.contains("Story", ignoreCase = true))
                    resolved
                },
            ),
        ) { null } as android.text.Spanned

        val spans = sb.getSpans(0, sb.length, EpubTypefaceSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(1, resolveCalls)

        val measurePaint = android.text.TextPaint()
        val drawPaint = android.text.TextPaint()
        spans[0].updateMeasureState(measurePaint)
        spans[0].updateDrawState(drawPaint)
        assertSame(measurePaint.typeface, drawPaint.typeface)
        assertNotNull(measurePaint.typeface)
    }

    @Test
    fun `font family style span is omitted when bookFontResolver returns null`() {
        val flow = epubBuildChapterFlow(
            0,
            listOf(
                EpubDisplayBlock.Text(
                    text = "No face yet",
                    headingLevel = null,
                    paragraphIndex = 0,
                    styleSpans = listOf(
                        EpubTextStyleSpan(
                            start = 0,
                            end = "No face yet".length,
                            style = EpubTextStyle.FontFamily,
                            fontFamily = "Story",
                        ),
                    ),
                ),
            ),
        )
        val sb = build(
            flow,
            style = style().copy(bookFontResolver = { null }),
        ) { null } as android.text.Spanned

        assertEquals(0, sb.getSpans(0, sb.length, EpubTypefaceSpan::class.java).size)
    }

    private fun build(
        flow: EpubChapterFlow,
        style: EpubFlowStyle = style(),
        fullPageHrefs: Set<String> = emptySet(),
        pageHeightPx: Int = 1200,
        inlineMaxHeightPx: Int = 720,
        decode: (String) -> android.graphics.Bitmap?,
    ): CharSequence {
        val ctx = RuntimeEnvironment.getApplication()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val loader = EpubFlowImageLoader(
            epubFileProvider = { java.io.File("/tmp/unused.epub") },
            executor = executor,
            columnWidthPx = style.columnWidthPx,
            pageHeightProvider = { pageHeightPx },
            inlineMaxHeightPx = inlineMaxHeightPx,
            fullPageHrefs = fullPageHrefs,
        )
        val resolver = EpubFlowImageSizeResolver(
            columnWidthPx = style.columnWidthPx,
            pageHeightProvider = { pageHeightPx },
            inlineMaxHeightPx = inlineMaxHeightPx,
            fullPageHrefs = fullPageHrefs,
        )
        return epubBuildFlowSpannable(
            context = ctx,
            flow = flow,
            style = style,
            markwonTheme = io.noties.markwon.core.MarkwonTheme.create(ctx),
            imageLoader = loader,
            imageSizeResolver = resolver,
            onLinkClick = {},
            fullPageHrefs = fullPageHrefs,
        )
    }

    @Test
    fun `spannable text is byte-identical to flow text with an image`() {
        val flow = epubBuildChapterFlow(0, listOf(heading(0, "标题"), text(1, "正文内容"), image(2, "a.png")))
        val sb = build(flow) { null }
        assertEquals(flow.text, sb.toString())
        assertEquals(flow.text.length, sb.length)
    }

    @Test
    fun `spannable text is byte-identical with image and long alt text`() {
        // The image is anchored on a single U+FFFC char; the AsyncDrawableSpan must NOT change buffer
        // length regardless of alt text (审计 C2).
        val flow = epubBuildChapterFlow(0, listOf(text(0, "前文"), image(1, "missing.png", alt = "一张很长的替代文字说明")))
        val sb = build(flow) { null }
        assertEquals(flow.text, sb.toString())
        assertEquals(flow.text.length, sb.length)
    }

    @Test
    fun `spannable text is byte-identical with image and no alt text`() {
        val flow = epubBuildChapterFlow(0, listOf(text(0, "前文"), image(1, "missing.png", alt = null)))
        val sb = build(flow) { null }
        assertEquals(flow.text, sb.toString())
    }

    @Test
    fun `horizontal rule has a visible drawing span`() {
        val flow = epubBuildChapterFlow(
            spineIndex = 0,
            blocks = epubDisplayBlocks(
                parseReaderItemsFromHtml(
                    spineIndex = 0,
                    html = "<html><body><p>上文</p><hr/><p>下文</p></body></html>",
                ),
            ),
        )

        val spannable = build(flow) { null } as android.text.Spanned
        val ruleSpans = spannable.getSpans(0, spannable.length, Any::class.java)
            .filter { it.javaClass.simpleName == "EpubHorizontalRuleSpan" }

        assertEquals(1, ruleSpans.size)
        val ruleSegment = flow.segments.single { it.block is EpubDisplayBlock.Break }
        assertEquals(ruleSegment.layoutStart, spannable.getSpanStart(ruleSpans.single()))
        assertEquals(ruleSegment.layoutEnd, spannable.getSpanEnd(ruleSpans.single()))
    }

    @Test
    fun `full page image reserves page sized placeholder before decode`() {
        val ctx = RuntimeEnvironment.getApplication()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val loader = EpubFlowImageLoader(
                epubFileProvider = { java.io.File("/tmp/unused.epub") },
                executor = executor,
                columnWidthPx = 800,
                pageHeightProvider = { 1200 },
                inlineMaxHeightPx = 720,
                fullPageHrefs = setOf("cover.png"),
                imageBoundsProvider = { href ->
                    if (href == "cover.png") EpubImageBounds(width = 800, height = 1200) else null
                },
            )
            val resolver = EpubFlowImageSizeResolver(
                columnWidthPx = 800,
                pageHeightProvider = { 1200 },
                inlineMaxHeightPx = 720,
                fullPageHrefs = setOf("cover.png"),
            )

            val drawable = AsyncDrawable("cover.png", loader, resolver, null)
            val span = AsyncDrawableSpan(
                io.noties.markwon.core.MarkwonTheme.create(ctx),
                drawable,
                AsyncDrawableSpan.ALIGN_CENTER,
                false,
            )
            val metrics = Paint.FontMetricsInt()
            val width = span.getSize(Paint(), "\uFFFC", 0, 1, metrics)

            assertTrue("full-page images should reserve layout space before async decode", drawable.hasResult())
            assertEquals(Rect(0, 0, 800, 1200), drawable.bounds)
            assertEquals(800, width)
            assertEquals(-1200, metrics.ascent)
            assertEquals(0, metrics.descent)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `inline image reserves capped placeholder before decode`() {
        val ctx = RuntimeEnvironment.getApplication()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val loader = EpubFlowImageLoader(
                epubFileProvider = { java.io.File("/tmp/unused.epub") },
                executor = executor,
                columnWidthPx = 800,
                pageHeightProvider = { 1200 },
                inlineMaxHeightPx = 720,
                fullPageHrefs = emptySet(),
                imageBoundsProvider = { href ->
                    if (href == "wide.png") EpubImageBounds(width = 1600, height = 400) else null
                },
            )
            val resolver = EpubFlowImageSizeResolver(
                columnWidthPx = 800,
                pageHeightProvider = { 1200 },
                inlineMaxHeightPx = 720,
                fullPageHrefs = emptySet(),
            )

            val drawable = AsyncDrawable("wide.png", loader, resolver, null)
            val span = AsyncDrawableSpan(
                io.noties.markwon.core.MarkwonTheme.create(ctx),
                drawable,
                AsyncDrawableSpan.ALIGN_CENTER,
                false,
            )
            val metrics = Paint.FontMetricsInt()
            val width = span.getSize(Paint(), "\uFFFC", 0, 1, metrics)

            assertTrue("inline images with known bounds should reserve their final capped box", drawable.hasResult())
            assertEquals(Rect(0, 0, 800, 200), drawable.bounds)
            assertEquals(800, width)
            assertEquals(-200, metrics.ascent)
            assertEquals(0, metrics.descent)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `flow layout sees known image height before async decode`() {
        val ctx = RuntimeEnvironment.getApplication()
        val flow = epubBuildChapterFlow(0, listOf(text(0, "Before image"), image(1, "cover.png"), text(2, "After image")))
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val loader = EpubFlowImageLoader(
                epubFileProvider = { java.io.File("/tmp/unused.epub") },
                executor = executor,
                columnWidthPx = 800,
                pageHeightProvider = { 1200 },
                inlineMaxHeightPx = 720,
                fullPageHrefs = setOf("cover.png"),
                imageBoundsProvider = { href ->
                    if (href == "cover.png") EpubImageBounds(width = 800, height = 1200) else null
                },
            )
            val resolver = EpubFlowImageSizeResolver(
                columnWidthPx = 800,
                pageHeightProvider = { 1200 },
                inlineMaxHeightPx = 720,
                fullPageHrefs = setOf("cover.png"),
            )
            val fullPageImageOffsets = flow.segments.mapNotNullTo(HashSet()) { segment ->
                (segment.block as? EpubDisplayBlock.Image)
                    ?.takeIf { !it.isInlineContent }
                    ?.let { segment.layoutStart }
            }
            val spannable = epubBuildFlowSpannable(
                context = ctx,
                flow = flow,
                style = style().copy(columnWidthPx = 800, imageMaxHeightPx = 1200),
                markwonTheme = io.noties.markwon.core.MarkwonTheme.create(ctx),
                imageLoader = loader,
                imageSizeResolver = resolver,
                onLinkClick = {},
                fullPageHrefs = setOf("cover.png"),
                fullPageImageOffsets = fullPageImageOffsets,
            )
            val textView = android.widget.TextView(ctx).apply {
                textSize = 18f
                setText(spannable)
                measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
                )
                layout(0, 0, 800, measuredHeight)
            }
            val imageLine = requireNotNull(textView.layout).getLineForOffset(flow.text.indexOf('\uFFFC'))
            val imageLineHeight = textView.layout.getLineBottom(imageLine) - textView.layout.getLineTop(imageLine)

            assertTrue(
                "known image bounds should shape the first layout, not arrive as a later reflow",
                imageLineHeight >= 1200,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `decoded image keeps reserved placeholder bounds`() {
        val epub = java.io.File.createTempFile("readflow-image-bounds", ".epub")
        val image = android.graphics.Bitmap.createBitmap(1600, 400, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            ZipOutputStream(epub.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("wide.png"))
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
        } finally {
            image.recycle()
        }

        val ctx = RuntimeEnvironment.getApplication()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val loader = EpubFlowImageLoader(
                epubFileProvider = { epub },
                executor = executor,
                columnWidthPx = 800,
                pageHeightProvider = { 1200 },
                inlineMaxHeightPx = 720,
                fullPageHrefs = emptySet(),
                imageBoundsProvider = { href ->
                    if (href == "wide.png") EpubImageBounds(width = 1600, height = 400) else null
                },
            )
            val resolver = EpubFlowImageSizeResolver(
                columnWidthPx = 800,
                pageHeightProvider = { 1200 },
                inlineMaxHeightPx = 720,
                fullPageHrefs = emptySet(),
            )
            val drawable = AsyncDrawable("wide.png", loader, resolver, null)
            AsyncDrawableSpan(
                io.noties.markwon.core.MarkwonTheme.create(ctx),
                drawable,
                AsyncDrawableSpan.ALIGN_CENTER,
                false,
            ).getSize(Paint(), "\uFFFC", 0, 1, Paint.FontMetricsInt())
            assertEquals(Rect(0, 0, 800, 200), drawable.bounds)

            drawable.setCallback2(object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) = Unit
                override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
                override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
            })
            executor.shutdown()
            assertTrue("decode should finish in the unit test window", executor.awaitTermination(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue("async decode should replace the transparent placeholder", drawable.result is BitmapDrawable)
            assertEquals(Rect(0, 0, 800, 200), drawable.bounds)
            drawable.initWithKnownDimensions(800, 18f)
            assertEquals(Rect(0, 0, 800, 200), drawable.bounds)
        } finally {
            executor.shutdownNow()
            epub.delete()
        }
    }

    @Test
    fun `decoded image notifies host refresh when reserved bounds are unchanged`() {
        val epub = java.io.File.createTempFile("readflow-image-refresh", ".epub")
        val image = android.graphics.Bitmap.createBitmap(1600, 400, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            ZipOutputStream(epub.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("wide.png"))
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
        } finally {
            image.recycle()
        }

        val ctx = RuntimeEnvironment.getApplication()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val refreshes = AtomicInteger(0)
        val results = mutableListOf<EpubAsyncImageResult>()
        try {
            val loader = EpubFlowImageLoader(
                epubFileProvider = { epub },
                executor = executor,
                columnWidthPx = 800,
                pageHeightProvider = { 1200 },
                inlineMaxHeightPx = 720,
                fullPageHrefs = emptySet(),
                imageBoundsProvider = { href ->
                    if (href == "wide.png") EpubImageBounds(width = 1600, height = 400) else null
                },
                onImageResultChanged = { result ->
                    results += result
                    refreshes.incrementAndGet()
                },
            )
            val resolver = EpubFlowImageSizeResolver(
                columnWidthPx = 800,
                pageHeightProvider = { 1200 },
                inlineMaxHeightPx = 720,
                fullPageHrefs = emptySet(),
            )
            val drawable = AsyncDrawable("wide.png", loader, resolver, null)
            loader.registerOccurrence(drawable, layoutStart = 42)
            AsyncDrawableSpan(
                io.noties.markwon.core.MarkwonTheme.create(ctx),
                drawable,
                AsyncDrawableSpan.ALIGN_CENTER,
                false,
            ).getSize(Paint(), "\uFFFC", 0, 1, Paint.FontMetricsInt())
            assertEquals(Rect(0, 0, 800, 200), drawable.bounds)

            drawable.setCallback2(object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) = Unit
                override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
                override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
            })
            executor.shutdown()
            assertTrue("decode should finish in the unit test window", executor.awaitTermination(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue("async decode should replace the transparent placeholder", drawable.result is BitmapDrawable)
            assertEquals(Rect(0, 0, 800, 200), drawable.bounds)
            assertEquals(
                "bounds-equal decode must notify the host so the bitmap is drawn",
                1,
                refreshes.get(),
            )
            val result = results.single()
            assertEquals(42, result.layoutStart)
            assertEquals(EpubAsyncImageResultKind.PIXELS_ONLY, result.kind)
            assertEquals(Rect(0, 0, 800, 200), result.beforeBounds)
            assertEquals(Rect(0, 0, 800, 200), result.afterBounds)
        } finally {
            executor.shutdownNow()
            epub.delete()
        }
    }

    @Test
    fun `all PIXELS_ONLY results require TextView rebind`() {
        val stableBounds = Rect(0, 0, 800, 1200)
        val fullPagePixels = EpubAsyncImageResult(
            layoutStart = 42,
            destination = "plate.png",
            generation = 1L,
            beforeBounds = stableBounds,
            afterBounds = Rect(stableBounds),
            isFullPage = true,
        )
        val inlinePixels = fullPagePixels.copy(
            destination = "inline.png",
            isFullPage = false,
        )
        val changedGeometry = inlinePixels.copy(
            afterBounds = Rect(0, 0, 640, 960),
        )
        val unknownOccurrence = inlinePixels.copy(layoutStart = -1)

        assertEquals(EpubAsyncImageResultKind.PIXELS_ONLY, fullPagePixels.kind)
        assertTrue(
            "same-size full-page bitmap must replace the transparent TextView display-list owner",
            fullPagePixels.requiresTextRebind,
        )
        assertTrue(
            "same-size inline pixels must also replace the transparent TextView display-list owner",
            inlinePixels.requiresTextRebind,
        )
        assertTrue(changedGeometry.requiresTextRebind)
        assertTrue(unknownOccurrence.requiresTextRebind)
    }

    @Test
    fun `PIXELS_ONLY successful install notifies result once and never dual fires decode finished`() {
        // Locks the host-wake contract: result callback present → exactly one onImageResultChanged,
        // zero onDecodeFinished; result callback absent → exactly one onDecodeFinished fallback.
        val epub = createImageEpub("pixels-only-notify-once")
        val executor = QueuedExecutorService()
        try {
            val withResultRefreshes = AtomicInteger(0)
            val withResultCompletions = AtomicInteger(0)
            val withResultKinds = mutableListOf<EpubAsyncImageResultKind>()
            val withResultLoader = imageLoader(
                epub = epub,
                executor = executor,
                imageBoundsProvider = { EpubImageBounds(width = 4, height = 4) },
                onImageResultChanged = { result ->
                    withResultKinds += result.kind
                    withResultRefreshes.incrementAndGet()
                },
                onDecodeFinished = { withResultCompletions.incrementAndGet() },
            )
            val withResultDrawable = asyncDrawable(withResultLoader)
            withResultDrawable.setCallback2(attachedDrawableCallback)
            assertEquals(1, executor.queuedTaskCount)
            executor.runNext()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(withResultDrawable.result is BitmapDrawable)
            assertFalse("successful install must clear pending decode", withResultLoader.hasPendingDecodes())
            assertEquals(
                "successful PIXELS_ONLY install must fire onImageResultChanged exactly once",
                1,
                withResultRefreshes.get(),
            )
            assertEquals(listOf(EpubAsyncImageResultKind.PIXELS_ONLY), withResultKinds)
            assertEquals(
                "result callback must suppress the fallback onDecodeFinished path",
                0,
                withResultCompletions.get(),
            )

            val fallbackCompletions = AtomicInteger(0)
            val fallbackLoader = imageLoader(
                epub = epub,
                executor = executor,
                imageBoundsProvider = { EpubImageBounds(width = 4, height = 4) },
                onDecodeFinished = { fallbackCompletions.incrementAndGet() },
            )
            val fallbackDrawable = asyncDrawable(fallbackLoader)
            fallbackDrawable.setCallback2(attachedDrawableCallback)
            assertEquals(1, executor.queuedTaskCount)
            executor.runNext()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(fallbackDrawable.result is BitmapDrawable)
            assertFalse(fallbackLoader.hasPendingDecodes())
            assertEquals(
                "callback-absent successful install must fall back to exactly one onDecodeFinished",
                1,
                fallbackCompletions.get(),
            )
        } finally {
            executor.shutdownNow()
            epub.delete()
        }
    }

    @Test
    fun `hasRelevantPendingDecodes ignores far page layout starts`() {
        val epub = createImageEpub("relevant-far-page")
        val executor = QueuedExecutorService()
        try {
            val loader = imageLoader(epub = epub, executor = executor)
            val drawable = asyncDrawable(loader)
            loader.registerOccurrence(drawable, layoutStart = 5_000)
            drawable.setCallback2(attachedDrawableCallback)
            assertTrue("fixture needs an in-flight decode", loader.hasPendingDecodes())

            assertFalse(
                "a far-page pending decode must not gate the current/adjacent windows",
                loader.hasRelevantPendingDecodes(listOf(0 until 100, 100 until 200, 200 until 300)),
            )
            assertTrue(
                "the same pending decode must still gate when its layoutStart is in range",
                loader.hasRelevantPendingDecodes(listOf(4_900 until 5_100)),
            )
        } finally {
            executor.shutdownNow()
            epub.delete()
        }
    }

    @Test
    fun `hasRelevantPendingDecodes blocks current and adjacent layout starts`() {
        val epub = createImageEpub("relevant-near-page")
        val executor = QueuedExecutorService()
        try {
            val loader = imageLoader(epub = epub, executor = executor)
            val current = asyncDrawable(loader)
            loader.registerOccurrence(current, layoutStart = 150)
            current.setCallback2(attachedDrawableCallback)

            assertTrue(
                "current-page pending decode must gate reveal/precache",
                loader.hasRelevantPendingDecodes(listOf(0 until 100, 100 until 200, 200 until 300)),
            )

            loader.cancelAll()
            val previous = asyncDrawable(loader)
            loader.registerOccurrence(previous, layoutStart = 50)
            previous.setCallback2(attachedDrawableCallback)
            assertTrue(
                "previous-page pending decode must gate reveal/precache",
                loader.hasRelevantPendingDecodes(listOf(0 until 100, 100 until 200, 200 until 300)),
            )

            loader.cancelAll()
            val next = asyncDrawable(loader)
            loader.registerOccurrence(next, layoutStart = 250)
            next.setCallback2(attachedDrawableCallback)
            assertTrue(
                "next-page pending decode must gate reveal/precache",
                loader.hasRelevantPendingDecodes(listOf(0 until 100, 100 until 200, 200 until 300)),
            )
        } finally {
            executor.shutdownNow()
            epub.delete()
        }
    }

    @Test
    fun `hasRelevantPendingDecodes conservatively blocks unregistered occurrences`() {
        val epub = createImageEpub("relevant-unknown")
        val executor = QueuedExecutorService()
        try {
            val loader = imageLoader(epub = epub, executor = executor)
            val drawable = asyncDrawable(loader)
            // Intentionally skip registerOccurrence — layoutStart is unknown.
            drawable.setCallback2(attachedDrawableCallback)
            assertTrue(loader.hasPendingDecodes())

            assertTrue(
                "an unregistered pending decode must block so correctness is not lost",
                loader.hasRelevantPendingDecodes(listOf(0 until 100, 100 until 200)),
            )
            assertTrue(
                "empty relevant ranges must also block while any decode is pending",
                loader.hasRelevantPendingDecodes(emptyList()),
            )
        } finally {
            executor.shutdownNow()
            epub.delete()
        }
    }

    @Test
    fun `failed image decode notifies the host that pending work finished`() {
        val epub = java.io.File.createTempFile("readflow-image-missing", ".epub")
        ZipOutputStream(epub.outputStream()).use { }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val completions = AtomicInteger(0)
        try {
            val loader = EpubFlowImageLoader(
                epubFileProvider = { epub },
                executor = executor,
                columnWidthPx = 800,
                pageHeightProvider = { 1200 },
                inlineMaxHeightPx = 720,
                fullPageHrefs = emptySet(),
                onDecodeFinished = { completions.incrementAndGet() },
            )
            val resolver = EpubFlowImageSizeResolver(
                columnWidthPx = 800,
                pageHeightProvider = { 1200 },
                inlineMaxHeightPx = 720,
                fullPageHrefs = emptySet(),
            )
            val drawable = AsyncDrawable("missing.png", loader, resolver, null)
            drawable.setCallback2(object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) = Unit
                override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
                override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
            })

            executor.shutdown()
            assertTrue("missing-image decode should finish in the unit test window", executor.awaitTermination(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(false, loader.hasPendingDecodes())
            assertEquals(
                "even a failed decode must wake a reveal gate waiting for the pending set to empty",
                1,
                completions.get(),
            )
        } finally {
            executor.shutdownNow()
            epub.delete()
        }
    }

    @Test
    fun `releaseAll rejects a decoded result already posted to the main handler`() {
        val epub = createImageEpub("release-all-late-result")
        val executor = QueuedExecutorService()
        val refreshes = AtomicInteger(0)
        val completions = AtomicInteger(0)
        try {
            val loader = imageLoader(
                epub = epub,
                executor = executor,
                onImageResultChanged = { _ -> refreshes.incrementAndGet() },
                onDecodeFinished = { completions.incrementAndGet() },
            )
            val drawable = asyncDrawable(loader)
            drawable.setCallback2(attachedDrawableCallback)
            assertEquals("attach should queue exactly one decode", 1, executor.queuedTaskCount)

            executor.runNext()
            assertTrue("the posted result must still count as pending until the main handler consumes it", loader.hasPendingDecodes())
            loader.releaseAll()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(loader.hasPendingDecodes())
            assertFalse("a released loader must not install a late bitmap", drawable.result is BitmapDrawable)
            assertEquals("a released loader must not wake its retired host", 0, refreshes.get())
            assertEquals("a released loader must not wake its retired host", 0, completions.get())
        } finally {
            executor.shutdownNow()
            epub.delete()
        }
    }

    @Test
    fun `cancelAll rejects a posted result and remains reusable`() {
        val epub = createImageEpub("cancel-all-reuse")
        val executor = QueuedExecutorService()
        val refreshes = AtomicInteger(0)
        try {
            val loader = imageLoader(
                epub = epub,
                executor = executor,
                onImageResultChanged = { _ -> refreshes.incrementAndGet() },
            )
            val retired = asyncDrawable(loader)
            retired.setCallback2(attachedDrawableCallback)
            executor.runNext()

            loader.cancelAll()
            val replacement = asyncDrawable(loader)
            replacement.setCallback2(attachedDrawableCallback)
            assertEquals("the reusable generation should queue the replacement decode", 1, executor.queuedTaskCount)
            executor.runNext()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(loader.hasPendingDecodes())
            assertFalse("the retired generation must not install its late result", retired.result is BitmapDrawable)
            assertTrue("cancelAll must not permanently release the loader", replacement.result is BitmapDrawable)
            assertEquals("only the replacement result may refresh the host", 1, refreshes.get())
        } finally {
            executor.shutdownNow()
            epub.delete()
        }
    }

    @Test
    fun `provider exceptions do not escape image scheduling or placeholder lookup`() {
        val executor = QueuedExecutorService()
        try {
            val fileFailureLoader = EpubFlowImageLoader(
                epubFileProvider = { throw IllegalStateException("book already closed") },
                executor = executor,
                columnWidthPx = 8,
                pageHeightProvider = { 8 },
                inlineMaxHeightPx = 8,
                fullPageHrefs = emptySet(),
            )
            val fileFailure = runCatching { fileFailureLoader.load(asyncDrawable(fileFailureLoader)) }.exceptionOrNull()
            assertNull("a closing book provider must degrade to a missing image", fileFailure)
            assertFalse(fileFailureLoader.hasPendingDecodes())

            val boundsFailureLoader = EpubFlowImageLoader(
                epubFileProvider = { File("/tmp/unused.epub") },
                executor = executor,
                columnWidthPx = 8,
                pageHeightProvider = { 8 },
                inlineMaxHeightPx = 8,
                fullPageHrefs = emptySet(),
                imageBoundsProvider = { throw IllegalStateException("bounds source retired") },
            )
            val placeholder = runCatching {
                boundsFailureLoader.placeholder(asyncDrawable(boundsFailureLoader))
            }
            assertNull("a retired bounds provider must degrade to no placeholder", placeholder.exceptionOrNull())
            assertNull(placeholder.getOrNull())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `late page height provider exception finishes the request without crashing the handler`() {
        val epub = createImageEpub("late-page-height-provider")
        val executor = QueuedExecutorService()
        val completions = AtomicInteger(0)
        var providerRetired = false
        try {
            val loader = imageLoader(
                epub = epub,
                executor = executor,
                fullPage = true,
                pageHeightProvider = {
                    if (providerRetired) throw IllegalStateException("preview viewport retired")
                    8
                },
                onDecodeFinished = { completions.incrementAndGet() },
            )
            val drawable = asyncDrawable(loader)
            drawable.setCallback2(attachedDrawableCallback)
            executor.runNext()
            providerRetired = true

            val handlerFailure = runCatching { shadowOf(Looper.getMainLooper()).idle() }.exceptionOrNull()

            assertNull("a late provider failure must not escape the main handler", handlerFailure)
            assertFalse(loader.hasPendingDecodes())
            assertFalse("a failed layout provider must not install a bitmap with unknown bounds", drawable.result is BitmapDrawable)
            assertEquals("provider failure must still release reveal gates", 1, completions.get())
        } finally {
            executor.shutdownNow()
            epub.delete()
        }
    }

    @Test
    fun `full page image re-fits to the real viewport at decode time not the pre-measure estimate`() {
        // Repro of the "封面/彩插 不显示或闪一下消失" bug: the placeholder is reserved BEFORE the view is
        // measured, when pageHeightProvider still returns the screen-derived estimate (~100px too tall
        // because it includes the system-bar area the reader avoids). If decode reuses that stale
        // reserved box, the full-page image line ends up taller than the real viewport and the page clip
        // drops it. The decode must re-fit the full-page image to the MEASURED viewport.
        val epub = java.io.File.createTempFile("readflow-fullpage-refit", ".epub")
        val image = android.graphics.Bitmap.createBitmap(1120, 1600, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            ZipOutputStream(epub.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("cover.png"))
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
        } finally {
            image.recycle()
        }

        val ctx = RuntimeEnvironment.getApplication()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        // pageHeightProvider changes between placeholder (pre-measure estimate) and decode (real viewport).
        var measured = false
        val estimatePageH = 1200
        val realViewportH = 1000
        try {
            val loader = EpubFlowImageLoader(
                epubFileProvider = { epub },
                executor = executor,
                columnWidthPx = 800,
                pageHeightProvider = { if (measured) realViewportH else estimatePageH },
                inlineMaxHeightPx = 720,
                fullPageHrefs = setOf("cover.png"),
                imageBoundsProvider = { href ->
                    if (href == "cover.png") EpubImageBounds(width = 1120, height = 1600) else null
                },
            )
            val resolver = EpubFlowImageSizeResolver(
                columnWidthPx = 800,
                pageHeightProvider = { if (measured) realViewportH else estimatePageH },
                inlineMaxHeightPx = 720,
                fullPageHrefs = setOf("cover.png"),
            )
            val drawable = AsyncDrawable("cover.png", loader, resolver, null)
            // Placeholder reserved at the pre-measure estimate: 1120x1600 fit to 800x1200 → 840 too wide,
            // so height-bound: 800 wide, 800*1600/1120 = 1142 tall.
            AsyncDrawableSpan(
                io.noties.markwon.core.MarkwonTheme.create(ctx),
                drawable,
                AsyncDrawableSpan.ALIGN_CENTER,
                false,
            ).getSize(Paint(), "￼", 0, 1, Paint.FontMetricsInt())

            // View is now measured — the real viewport is shorter than the estimate.
            measured = true
            drawable.setCallback2(object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) = Unit
                override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
                override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
            })
            executor.shutdown()
            assertTrue("decode should finish in the unit test window", executor.awaitTermination(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue("async decode should replace the placeholder", drawable.result is BitmapDrawable)
            // The decoded full-page image must fit the REAL viewport (1000), not the estimate (1200):
            // 1120x1600 fit to 800x1000 → height-bound at 1000, width 1000*1120/1600 = 700.
            assertTrue(
                "full-page image height must fit the measured viewport (<=1000), was ${drawable.bounds.height()}",
                drawable.bounds.height() <= realViewportH,
            )
            assertEquals("aspect ratio must be preserved", Rect(0, 0, 700, 1000), drawable.bounds)
        } finally {
            executor.shutdownNow()
            epub.delete()
        }
    }

    @Test
    fun `inline avatar keeps its capped size regardless of viewport measurement`() {
        // Regression guard for the "聊天头像不被误触/误缩放" requirement: a 142x142 chat avatar is an
        // inline image (below FULL_PAGE_IMAGE_MIN_LONGEST_SIDE_PX), so the full-page re-fit must NOT touch
        // it — its box stays intrinsic-capped no matter what the viewport measures.
        val epub = java.io.File.createTempFile("readflow-avatar", ".epub")
        val image = android.graphics.Bitmap.createBitmap(142, 142, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            ZipOutputStream(epub.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("avatar.png"))
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
        } finally {
            image.recycle()
        }

        val ctx = RuntimeEnvironment.getApplication()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        var measured = false
        try {
            val loader = EpubFlowImageLoader(
                epubFileProvider = { epub },
                executor = executor,
                columnWidthPx = 800,
                pageHeightProvider = { if (measured) 1000 else 1200 },
                inlineMaxHeightPx = 720,
                fullPageHrefs = emptySet(),
                imageBoundsProvider = { href ->
                    if (href == "avatar.png") EpubImageBounds(width = 142, height = 142) else null
                },
            )
            val resolver = EpubFlowImageSizeResolver(
                columnWidthPx = 800,
                pageHeightProvider = { if (measured) 1000 else 1200 },
                inlineMaxHeightPx = 720,
                fullPageHrefs = emptySet(),
            )
            val drawable = AsyncDrawable("avatar.png", loader, resolver, null)
            AsyncDrawableSpan(
                io.noties.markwon.core.MarkwonTheme.create(ctx),
                drawable,
                AsyncDrawableSpan.ALIGN_CENTER,
                false,
            ).getSize(Paint(), "￼", 0, 1, Paint.FontMetricsInt())

            measured = true
            drawable.setCallback2(object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) = Unit
                override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
                override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
            })
            executor.shutdown()
            assertTrue("decode should finish in the unit test window", executor.awaitTermination(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                "inline avatar must keep its intrinsic 142x142 box, untouched by full-page re-fit",
                Rect(0, 0, 142, 142),
                drawable.bounds,
            )
        } finally {
            executor.shutdownNow()
            epub.delete()
        }
    }

    @Test
    fun `first-line indent applies to body paragraph but not heading`() {
        val flow = epubBuildChapterFlow(0, listOf(heading(0, "标题"), text(1, "正文内容")))
        val sb = build(flow, style().copy(firstLineIndentPx = 72)) { null } as android.text.Spanned

        val bodySeg = flow.segments.first { it.paragraphIndex == 1 }
        val bodyMargins = sb.getSpans(
            bodySeg.layoutStart, bodySeg.layoutEnd, android.text.style.LeadingMarginSpan.Standard::class.java,
        )
        assertEquals(1, bodyMargins.size)
        // First line indented 72px, wrapped lines flush (0) — Moon+ 首行缩进.
        assertEquals(72, bodyMargins[0].getLeadingMargin(true))
        assertEquals(0, bodyMargins[0].getLeadingMargin(false))

        val headSeg = flow.segments.first { it.paragraphIndex == 0 }
        val headMargins = sb.getSpans(
            headSeg.layoutStart, headSeg.layoutEnd, android.text.style.LeadingMarginSpan.Standard::class.java,
        )
        assertEquals(0, headMargins.size)
    }

    @Test
    fun `first-line indent disabled when zero`() {
        val flow = epubBuildChapterFlow(0, listOf(text(0, "正文内容")))
        val sb = build(flow, style().copy(firstLineIndentPx = 0)) { null } as android.text.Spanned
        val margins = sb.getSpans(0, sb.length, android.text.style.LeadingMarginSpan.Standard::class.java)
        assertEquals(0, margins.size)
    }

    @Test
    fun `computed css reaches alignment text spans indentation and block box rendering`() {
        val block = EpubDisplayBlock.Text(
            text = "Styled paragraph",
            headingLevel = null,
            paragraphIndex = 0,
            styleSpans = listOf(
                EpubTextStyleSpan(0, 6, EpubTextStyle.ForegroundColor, color = 0xFF13579B.toInt()),
                EpubTextStyleSpan(7, 16, EpubTextStyle.Underline),
            ),
            blockStyle = EpubBlockStyle(
                textAlign = EpubTextAlign.End,
                textIndent = EpubCssLength(2f, EpubCssUnit.Em),
                margin = EpubCssInsets(
                    top = EpubCssLength(0.5f, EpubCssUnit.Em),
                    left = EpubCssLength(1f, EpubCssUnit.Em),
                    bottom = EpubCssLength(0.5f, EpubCssUnit.Em),
                ),
                padding = EpubCssInsets.all(EpubCssLength(0.25f, EpubCssUnit.Em)),
                backgroundColor = 0xFFFFEECC.toInt(),
                borders = EpubCssBorders(
                    bottom = EpubCssBorder(
                        EpubCssLength(1f, EpubCssUnit.Px),
                        EpubCssBorderStyle.Solid,
                        0xFF333333.toInt(),
                    ),
                ),
            ),
        )
        val flow = epubBuildChapterFlow(0, listOf(block))
        val sb = build(flow) { null } as android.text.Spanned

        val alignment = sb.getSpans(0, sb.length, android.text.style.AlignmentSpan.Standard::class.java).single()
        assertEquals(android.text.Layout.Alignment.ALIGN_OPPOSITE, alignment.alignment)
        val margin = sb.getSpans(0, sb.length, android.text.style.LeadingMarginSpan.Standard::class.java).single()
        assertTrue(margin.getLeadingMargin(true) > margin.getLeadingMargin(false))
        assertTrue(margin.getLeadingMargin(false) > 0)
        assertEquals(
            0xFF13579B.toInt(),
            sb.getSpans(0, 6, android.text.style.ForegroundColorSpan::class.java).single().foregroundColor,
        )
        assertEquals(1, sb.getSpans(7, 16, android.text.style.UnderlineSpan::class.java).size)
        val box = sb.getSpans(0, sb.length, EpubBlockBoxSpan::class.java).single()
        assertEquals(0xFFFFEECC.toInt(), box.backgroundColor)
        assertEquals(0xFF333333.toInt(), box.borders.bottom?.color)
        assertTrue(box.verticalInsetPx > 0)
    }

    @Test
    fun `computed image css reaches async drawable dimensions and paragraph alignment`() {
        val block = EpubDisplayBlock.Image(
            href = "plate.png",
            altText = "Plate",
            paragraphIndex = 0,
            style = EpubImageStyle(
                width = EpubCssLength(50f, EpubCssUnit.Percent),
                height = EpubCssLength(4f, EpubCssUnit.Em),
                maxHeight = EpubCssLength(6f, EpubCssUnit.Em),
                alignment = EpubTextAlign.Center,
            ),
        )
        val flow = epubBuildChapterFlow(0, listOf(block))
        val sb = build(flow) { null } as android.text.Spanned

        val drawable = sb.getSpans(0, sb.length, AsyncDrawableSpan::class.java).single().drawable
        val imageSize = requireNotNull(drawable.imageSize)
        assertEquals(50f, imageSize.width.value)
        assertEquals("%", imageSize.width.unit)
        assertEquals(4f, imageSize.height.value)
        assertEquals("em", imageSize.height.unit)
        val alignment = sb.getSpans(0, sb.length, android.text.style.AlignmentSpan.Standard::class.java).single()
        assertEquals(android.text.Layout.Alignment.ALIGN_CENTER, alignment.alignment)
    }

    @Test
    fun `css width placeholder preserves bounds aspect ratio before decode`() {
        val resolver = EpubFlowImageSizeResolver(
            columnWidthPx = 800,
            pageHeightProvider = { 1200 },
            inlineMaxHeightPx = 720,
            fullPageHrefs = emptySet(),
        )
        val drawable = AsyncDrawable(
            "placeholder.png",
            AsyncDrawableLoader.noOp(),
            resolver,
            ImageSize(ImageSize.Dimension(50f, "%"), null),
        ).apply {
            initWithKnownDimensions(800, 20f)
            setResult(ColorDrawable(Color.GRAY).apply { setBounds(0, 0, 600, 300) })
        }

        assertEquals(Rect(0, 0, 400, 200), resolver.resolveImageSize(drawable))
    }

    @Test
    fun `image resolver re-fits full page results after viewport size changes but keeps inline bounds`() {
        var columnWidthPx = 800
        var pageHeightPx = 1200
        val fullPageResolver = EpubFlowImageSizeResolver(
            columnWidthPx = columnWidthPx,
            columnWidthProvider = { columnWidthPx },
            pageHeightProvider = { pageHeightPx },
            inlineMaxHeightPx = 720,
            fullPageHrefs = setOf("plate.png"),
        )
        val inlineResolver = EpubFlowImageSizeResolver(
            columnWidthPx = columnWidthPx,
            columnWidthProvider = { columnWidthPx },
            pageHeightProvider = { pageHeightPx },
            inlineMaxHeightPx = 720,
            fullPageHrefs = emptySet(),
        )
        val bitmap = android.graphics.Bitmap.createBitmap(160, 90, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val fullPage = AsyncDrawable("plate.png", AsyncDrawableLoader.noOp(), fullPageResolver, null).apply {
                initWithKnownDimensions(800, 20f)
                setResult(BitmapDrawable(null, bitmap))
            }
            val inline = AsyncDrawable("inline.png", AsyncDrawableLoader.noOp(), inlineResolver, null).apply {
                initWithKnownDimensions(800, 20f)
                setResult(BitmapDrawable(null, bitmap))
            }

            assertEquals(Rect(0, 0, 800, 450), fullPageResolver.resolveImageSize(fullPage))
            assertEquals(Rect(0, 0, 160, 90), inlineResolver.resolveImageSize(inline))

            columnWidthPx = 600
            pageHeightPx = 800

            assertEquals(Rect(0, 0, 600, 338), fullPageResolver.resolveImageSize(fullPage))
            assertEquals(Rect(0, 0, 160, 90), inlineResolver.resolveImageSize(inline))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `inline occurrence does not blacklist a later standalone occurrence of the same href`() {
        val pageHeightPx = 1200
        val columnWidthPx = 800
        val inlineMaxHeightPx = 300
        val sharedHref = "mixed-use-plate.png"
        val flow = epubBuildChapterFlow(
            spineIndex = 0,
            blocks = listOf(
                image(0, sharedHref).copy(isInlineContent = true),
                text(1, "Body between inline and standalone uses"),
                image(2, sharedHref).copy(isInlineContent = false),
            ),
        )
        val fullPageImageOffsets = flow.segments.mapNotNullTo(HashSet()) { segment ->
            (segment.block as? EpubDisplayBlock.Image)
                ?.takeIf { !it.isInlineContent }
                ?.let { segment.layoutStart }
        }
        val fullPageHrefs = setOf(sharedHref)
        val resolver = EpubFlowImageSizeResolver(
            columnWidthPx = columnWidthPx,
            pageHeightProvider = { pageHeightPx },
            inlineMaxHeightPx = inlineMaxHeightPx,
            fullPageHrefs = fullPageHrefs,
        )
        val executor = QueuedExecutorService()
        val loader = EpubFlowImageLoader(
            epubFileProvider = { null },
            executor = executor,
            columnWidthPx = columnWidthPx,
            pageHeightProvider = { pageHeightPx },
            inlineMaxHeightPx = inlineMaxHeightPx,
            fullPageHrefs = fullPageHrefs,
            imageBoundsProvider = { EpubImageBounds(columnWidthPx, pageHeightPx) },
        )
        val context = RuntimeEnvironment.getApplication()
        val spannable = epubBuildFlowSpannable(
            context = context,
            flow = flow,
            style = style().copy(columnWidthPx = columnWidthPx, imageMaxHeightPx = pageHeightPx),
            markwonTheme = io.noties.markwon.core.MarkwonTheme.create(context),
            imageLoader = loader,
            imageSizeResolver = resolver,
            onLinkClick = {},
            fullPageHrefs = fullPageHrefs,
            fullPageImageOffsets = fullPageImageOffsets,
        )
        val imageSpans = spannable.getSpans(0, spannable.length, AsyncDrawableSpan::class.java)
            .sortedBy { spannable.getSpanStart(it) }
        val bitmap = android.graphics.Bitmap.createBitmap(
            columnWidthPx,
            pageHeightPx,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        try {
            assertEquals(
                Rect(0, 0, 200, inlineMaxHeightPx),
                requireNotNull(loader.placeholder(imageSpans[0].drawable)).bounds,
            )
            assertEquals(
                Rect(0, 0, columnWidthPx, pageHeightPx),
                requireNotNull(loader.placeholder(imageSpans[1].drawable)).bounds,
            )
            imageSpans.forEach { span ->
                span.drawable.initWithKnownDimensions(columnWidthPx, 32f)
                span.drawable.setResult(BitmapDrawable(null, bitmap))
            }

            assertEquals(2, imageSpans.size)
            assertTrue(
                "the mixed-content occurrence must retain inline capped bounds",
                imageSpans[0].drawable.bounds.height() <= inlineMaxHeightPx,
            )
            assertEquals(
                "the later standalone occurrence must still use the full page despite sharing an inline href",
                Rect(0, 0, columnWidthPx, pageHeightPx),
                imageSpans[1].drawable.bounds,
            )
        } finally {
            bitmap.recycle()
            executor.shutdownNow()
        }
    }

    @Test
    fun `heading attached and later standalone occurrences of the same href keep full page bounds`() {
        val pageHeightPx = 1200
        val columnWidthPx = 800
        val sharedHref = "reused-plate.png"
        val flow = epubBuildChapterFlow(
            spineIndex = 0,
            blocks = listOf(
                heading(0, "Illustration"),
                image(1, sharedHref),
                text(2, "Body between the two occurrences"),
                image(3, sharedHref),
            ),
        )
        val resolver = EpubFlowImageSizeResolver(
            columnWidthPx = columnWidthPx,
            pageHeightProvider = { pageHeightPx },
            inlineMaxHeightPx = 720,
            fullPageHrefs = setOf(sharedHref),
        )
        val spannable = epubBuildFlowSpannable(
            context = RuntimeEnvironment.getApplication(),
            flow = flow,
            style = style().copy(
                fontSizeSp = 32f,
                lineSpacingMultiplier = 1.3f,
                columnWidthPx = columnWidthPx,
                density = 1f,
            ),
            markwonTheme = io.noties.markwon.core.MarkwonTheme.create(RuntimeEnvironment.getApplication()),
            imageLoader = AsyncDrawableLoader.noOp(),
            imageSizeResolver = resolver,
            onLinkClick = {},
            fullPageHrefs = setOf(sharedHref),
        )
        val imageSpans = spannable.getSpans(0, spannable.length, AsyncDrawableSpan::class.java)
            .sortedBy { spannable.getSpanStart(it) }
        val bitmap = android.graphics.Bitmap.createBitmap(
            columnWidthPx,
            pageHeightPx,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        try {
            imageSpans.forEach { span ->
                span.drawable.initWithKnownDimensions(columnWidthPx, 32f)
                span.drawable.setResult(BitmapDrawable(null, bitmap))
            }

            assertEquals(2, imageSpans.size)
            assertEquals(
                "a heading-attached occurrence stays full-size for cropped continuation preview",
                Rect(0, 0, columnWidthPx, pageHeightPx),
                imageSpans[0].drawable.bounds,
            )
            assertEquals(
                "a later standalone occurrence keeps the same full-page policy",
                Rect(0, 0, columnWidthPx, pageHeightPx),
                imageSpans[1].drawable.bounds,
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `large multiline h1 keeps a full sized attached image for continuation preview`() {
        val pageHeightPx = 720
        val columnWidthPx = 280
        val fontSizeSp = 32f
        val lineSpacingMultiplier = 1.3f
        val imageHref = "heading-plate.png"
        val flow = epubBuildChapterFlow(
            spineIndex = 0,
            blocks = listOf(
                heading(0, "A large\nchapter\nheading\non five\nlines"),
                image(1, imageHref),
            ),
        )
        val resolver = EpubFlowImageSizeResolver(
            columnWidthPx = columnWidthPx,
            pageHeightProvider = { pageHeightPx },
            inlineMaxHeightPx = pageHeightPx,
            fullPageHrefs = setOf(imageHref),
        )
        val context = RuntimeEnvironment.getApplication()
        val spannable = epubBuildFlowSpannable(
            context = context,
            flow = flow,
            style = style().copy(
                fontSizeSp = fontSizeSp,
                lineSpacingMultiplier = lineSpacingMultiplier,
                columnWidthPx = columnWidthPx,
                imageMaxHeightPx = pageHeightPx,
                density = 1f,
            ),
            markwonTheme = io.noties.markwon.core.MarkwonTheme.create(context),
            imageLoader = AsyncDrawableLoader.noOp(),
            imageSizeResolver = resolver,
            onLinkClick = {},
            fullPageHrefs = setOf(imageHref),
        )
        val imageSpan = spannable.getSpans(0, spannable.length, AsyncDrawableSpan::class.java).single()
        val bitmap = android.graphics.Bitmap.createBitmap(800, 2400, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            imageSpan.drawable.initWithKnownDimensions(columnWidthPx, fontSizeSp)
            imageSpan.drawable.setResult(BitmapDrawable(null, bitmap))
            val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = fontSizeSp
                typeface = android.graphics.Typeface.SERIF
            }
            val baseFontHeightPx = (paint.fontMetricsInt.descent - paint.fontMetricsInt.ascent).coerceAtLeast(1)
            val spacingAddPx = ((lineSpacingMultiplier - 1f) * baseFontHeightPx).coerceAtLeast(0f)
            val layout = android.text.StaticLayout.Builder.obtain(
                spannable,
                0,
                spannable.length,
                paint,
                columnWidthPx,
            )
                .setIncludePad(false)
                .setLineSpacing(spacingAddPx, 1f)
                .build()
            val headingSegment = flow.segments[0]
            val imageSegment = flow.segments[1]
            val firstHeadingLine = layout.getLineForOffset(headingSegment.layoutStart)
            val lastHeadingLine = layout.getLineForOffset(headingSegment.layoutEnd - 1)
            val imageLine = layout.getLineForOffset(imageSegment.layoutStart)
            val headingAndSeparatorHeightPx = layout.getLineTop(imageLine) - layout.getLineTop(firstHeadingLine)
            val completeGroupHeightPx = layout.getLineBottom(imageLine) - layout.getLineTop(firstHeadingLine)
            val expectedImageBounds = epubFlowImageTargetSize(
                intrinsicWidth = 800,
                intrinsicHeight = 2400,
                columnWidthPx = columnWidthPx,
                pageHeightPx = pageHeightPx,
                inlineMaxHeightPx = pageHeightPx,
                isFullPage = true,
            )

            assertTrue("the H1 precondition must exercise at least five measured lines", lastHeadingLine - firstHeadingLine + 1 >= 5)
            assertTrue("the heading and separator leave positive room for an attached image", headingAndSeparatorHeightPx in 1 until pageHeightPx)
            assertEquals(
                "the attached image must keep full-page FIT bounds instead of shrinking into residual heading room",
                expectedImageBounds,
                imageSpan.drawable.bounds,
            )
            assertTrue(
                "full-size image after a tall H1 is allowed to overflow for cropped preview; " +
                    "group=${completeGroupHeightPx}px page=${pageHeightPx}px",
                completeGroupHeightPx > pageHeightPx,
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `full page illustrations fit the usable viewport while inline images keep intrinsic size`() {
        val viewportStyle = style().copy(
            columnWidthPx = 760,
            imageMaxHeightPx = 720,
            density = 1f,
        )
        val fullPageHrefs = setOf("plain.png", "percent.png", "fixed.png", "landscape.png")

        assertEquals(
            Rect(0, 0, 728, 1040),
            resolvedImageBounds(
                href = "plain.png",
                imageStyle = EpubImageStyle(),
                intrinsicWidth = 1120,
                intrinsicHeight = 1600,
                flowStyle = viewportStyle,
                pageHeightPx = 1040,
                fullPageHrefs = fullPageHrefs,
            ),
        )
        assertEquals(
            Rect(0, 0, 728, 1040),
            resolvedImageBounds(
                href = "percent.png",
                // CSS `height:auto` is represented by no explicit height dimension.
                imageStyle = EpubImageStyle(width = EpubCssLength(100f, EpubCssUnit.Percent)),
                intrinsicWidth = 1120,
                intrinsicHeight = 1600,
                flowStyle = viewportStyle,
                pageHeightPx = 1040,
                fullPageHrefs = fullPageHrefs,
            ),
        )
        assertEquals(
            Rect(0, 0, 728, 1040),
            resolvedImageBounds(
                href = "fixed.png",
                imageStyle = EpubImageStyle(
                    width = EpubCssLength(320f, EpubCssUnit.Px),
                    height = EpubCssLength(400f, EpubCssUnit.Px),
                ),
                intrinsicWidth = 1120,
                intrinsicHeight = 1600,
                flowStyle = viewportStyle,
                pageHeightPx = 1040,
                fullPageHrefs = fullPageHrefs,
            ),
        )
        assertEquals(
            Rect(0, 0, 760, 428),
            resolvedImageBounds(
                href = "landscape.png",
                imageStyle = EpubImageStyle(),
                intrinsicWidth = 1600,
                intrinsicHeight = 900,
                flowStyle = viewportStyle,
                pageHeightPx = 1040,
                fullPageHrefs = fullPageHrefs,
            ),
        )
        assertEquals(
            Rect(0, 0, 142, 142),
            resolvedImageBounds(
                href = "avatar.png",
                imageStyle = EpubImageStyle(),
                intrinsicWidth = 142,
                intrinsicHeight = 142,
                flowStyle = viewportStyle,
                pageHeightPx = 1040,
                fullPageHrefs = fullPageHrefs,
            ),
        )
        assertEquals(
            Rect(0, 0, 560, 420),
            resolvedImageBounds(
                href = "inline-plate.png",
                imageStyle = EpubImageStyle(),
                intrinsicWidth = 560,
                intrinsicHeight = 420,
                flowStyle = viewportStyle,
                pageHeightPx = 1040,
                fullPageHrefs = fullPageHrefs,
            ),
        )
        assertEquals(
            Rect(0, 0, 380, 190),
            resolvedImageBounds(
                href = "inline-css.png",
                imageStyle = EpubImageStyle(width = EpubCssLength(50f, EpubCssUnit.Percent)),
                intrinsicWidth = 600,
                intrinsicHeight = 300,
                flowStyle = viewportStyle,
                pageHeightPx = 1040,
                fullPageHrefs = fullPageHrefs,
            ),
        )
    }

    @Test
    fun `unsafe css colors and negative vertical margins degrade without harming reader layout`() {
        assertEquals(0xFFE8E6E1.toInt(), epubSafeCssForeground(0xFF000000.toInt(), null, 0xFFE8E6E1.toInt()))
        assertNull(epubSafeCssBackground(0xFF202020.toInt(), 0xFF000000.toInt()))

        val block = EpubDisplayBlock.Text(
            text = "Body",
            headingLevel = null,
            paragraphIndex = 0,
            blockStyle = EpubBlockStyle(
                margin = EpubCssInsets(
                    top = EpubCssLength(-2f, EpubCssUnit.Em),
                    bottom = EpubCssLength(-1f, EpubCssUnit.Em),
                ),
            ),
        )
        val sb = build(epubBuildChapterFlow(0, listOf(block))) { null } as android.text.Spanned
        assertEquals(0, sb.getSpans(0, sb.length, EpubBlockBoxSpan::class.java).size)
    }

    @Test
    fun `css foreground only trusts a background that survives the reader contrast filter`() {
        val inlineBlock = EpubDisplayBlock.Text(
            text = "Text",
            headingLevel = null,
            paragraphIndex = 0,
            styleSpans = listOf(
                EpubTextStyleSpan(0, 4, EpubTextStyle.ForegroundColor, color = Color.WHITE),
                EpubTextStyleSpan(0, 4, EpubTextStyle.BackgroundColor, color = Color.BLACK),
            ),
        )
        val inline = build(epubBuildChapterFlow(0, listOf(inlineBlock))) { null } as android.text.Spanned
        assertEquals(Color.BLACK, inline.getSpans(0, 4, android.text.style.ForegroundColorSpan::class.java).single().foregroundColor)
        assertEquals(0, inline.getSpans(0, 4, android.text.style.BackgroundColorSpan::class.java).size)

        val blockBackground = inlineBlock.copy(
            styleSpans = listOf(EpubTextStyleSpan(0, 4, EpubTextStyle.ForegroundColor, color = Color.WHITE)),
            blockStyle = EpubBlockStyle(backgroundColor = Color.BLACK),
        )
        val block = build(epubBuildChapterFlow(0, listOf(blockBackground))) { null } as android.text.Spanned
        assertEquals(Color.BLACK, block.getSpans(0, 4, android.text.style.ForegroundColorSpan::class.java).single().foregroundColor)
        assertEquals(0, block.getSpans(0, 4, EpubBlockBoxSpan::class.java).size)
    }

    private fun imageLoader(
        epub: File,
        executor: QueuedExecutorService,
        fullPage: Boolean = false,
        pageHeightProvider: () -> Int = { 8 },
        imageBoundsProvider: (String) -> EpubImageBounds? = { null },
        onImageResultChanged: ((EpubAsyncImageResult) -> Unit)? = null,
        onDecodeFinished: (() -> Unit)? = null,
    ) = EpubFlowImageLoader(
        epubFileProvider = { epub },
        executor = executor,
        columnWidthPx = 8,
        pageHeightProvider = pageHeightProvider,
        inlineMaxHeightPx = 8,
        fullPageHrefs = if (fullPage) setOf(TEST_IMAGE_HREF) else emptySet(),
        imageBoundsProvider = imageBoundsProvider,
        onImageResultChanged = onImageResultChanged,
        onDecodeFinished = onDecodeFinished,
    )

    private fun asyncDrawable(loader: EpubFlowImageLoader) = AsyncDrawable(
        TEST_IMAGE_HREF,
        loader,
        EpubFlowImageSizeResolver(
            columnWidthPx = 8,
            pageHeightProvider = { 8 },
            inlineMaxHeightPx = 8,
            fullPageHrefs = emptySet(),
        ),
        null,
    )

    private fun resolvedImageBounds(
        href: String,
        imageStyle: EpubImageStyle,
        intrinsicWidth: Int,
        intrinsicHeight: Int,
        flowStyle: EpubFlowStyle,
        pageHeightPx: Int,
        fullPageHrefs: Set<String>,
    ): Rect {
        val flow = epubBuildChapterFlow(
            spineIndex = 0,
            blocks = listOf(
                EpubDisplayBlock.Image(
                    href = href,
                    altText = null,
                    paragraphIndex = 0,
                    style = imageStyle,
                ),
            ),
        )
        val spannable = build(
            flow = flow,
            style = flowStyle,
            fullPageHrefs = fullPageHrefs,
            pageHeightPx = pageHeightPx,
            inlineMaxHeightPx = 720,
        ) { null } as android.text.Spanned
        val drawable = spannable.getSpans(0, spannable.length, AsyncDrawableSpan::class.java).single().drawable
        val target = epubFlowImageTargetSize(
            intrinsicWidth = intrinsicWidth,
            intrinsicHeight = intrinsicHeight,
            columnWidthPx = flowStyle.columnWidthPx,
            pageHeightPx = pageHeightPx,
            inlineMaxHeightPx = 720,
            isFullPage = href in fullPageHrefs,
        )
        drawable.initWithKnownDimensions(flowStyle.columnWidthPx, flowStyle.fontSizeSp * flowStyle.density)
        drawable.setResult(ColorDrawable(Color.GRAY).apply { bounds = target })
        return Rect(drawable.bounds)
    }

    private fun createImageEpub(prefix: String): File {
        val epub = File.createTempFile(prefix, ".epub")
        val image = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            ZipOutputStream(epub.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(TEST_IMAGE_HREF))
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
        } finally {
            image.recycle()
        }
        return epub
    }

    private class QueuedExecutorService : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false

        val queuedTaskCount: Int get() = tasks.size

        fun runNext() {
            tasks.removeFirst().run()
        }

        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException("executor stopped")
            tasks.addLast(command)
        }

        override fun shutdown() {
            stopped = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return tasks.toMutableList().also { tasks.clear() }
        }

        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
    }

    private companion object {
        const val TEST_IMAGE_HREF = "image.png"
    }
}
