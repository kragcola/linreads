package dev.readflow.render.epub

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

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

    private fun build(flow: EpubChapterFlow, decode: (String) -> android.graphics.Bitmap?): CharSequence {
        val ctx = RuntimeEnvironment.getApplication()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val loader = EpubFlowImageLoader(
            epubFileProvider = { java.io.File("/tmp/unused.epub") },
            executor = executor,
            columnWidthPx = 800,
            pageHeightPx = 1200,
            inlineMaxHeightPx = 720,
            fullPageHrefs = emptySet(),
        )
        val resolver = EpubFlowImageSizeResolver(
            columnWidthPx = 800,
            pageHeightPx = 1200,
            inlineMaxHeightPx = 720,
            fullPageHrefs = emptySet(),
        )
        return epubBuildFlowSpannable(
            context = ctx,
            flow = flow,
            style = style(),
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
}
