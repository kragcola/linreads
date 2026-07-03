package dev.readflow.render.epub

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan

/**
 * The load-bearing invariant of the continuous-flow rewrite (审计 C2/L12): the styled Spannable's
 * text MUST be byte-identical to [EpubChapterFlow.text], in every branch — including a missing image
 * (alt-text fallback). If length ever diverges, the offset map and persisted Locator break.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpubFlowSpannableTest {

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

    private fun build(
        flow: EpubChapterFlow,
        style: EpubFlowStyle = style(),
        decode: (String) -> android.graphics.Bitmap?,
    ): CharSequence {
        val ctx = RuntimeEnvironment.getApplication()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val loader = EpubFlowImageLoader(
            epubFileProvider = { java.io.File("/tmp/unused.epub") },
            executor = executor,
            columnWidthPx = 800,
            pageHeightProvider = { 1200 },
            inlineMaxHeightPx = 720,
            fullPageHrefs = emptySet(),
        )
        val resolver = EpubFlowImageSizeResolver(
            columnWidthPx = 800,
            pageHeightProvider = { 1200 },
            inlineMaxHeightPx = 720,
            fullPageHrefs = emptySet(),
        )
        return epubBuildFlowSpannable(
            context = ctx,
            flow = flow,
            style = style,
            markwonTheme = io.noties.markwon.core.MarkwonTheme.create(ctx),
            imageLoader = loader,
            imageSizeResolver = resolver,
            onLinkClick = {},
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
            val spannable = epubBuildFlowSpannable(
                context = ctx,
                flow = flow,
                style = style().copy(columnWidthPx = 800, imageMaxHeightPx = 1200),
                markwonTheme = io.noties.markwon.core.MarkwonTheme.create(ctx),
                imageLoader = loader,
                imageSizeResolver = resolver,
                onLinkClick = {},
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
}
