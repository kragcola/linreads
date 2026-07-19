package dev.readflow.features.reader

import android.net.Uri
import android.view.View
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReaderEngine
import dev.readflow.render.api.ReaderTextAnnotation
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.TextAnnotatableReaderEngine
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderEngineFeatureProjectionTest {

    @Test
    fun `fixed layout engine hides unsupported search annotation and typography commands`() {
        val engine = FakeReaderEngine(
            format = BookFormat.PDF,
            supportsSearch = false,
            supportedModes = setOf(ReadingMode.PAGED),
        )

        assertEquals(
            setOf(
                ReaderFeature.TOC,
                ReaderFeature.BOOKMARKS,
                ReaderFeature.PROGRESS,
                ReaderFeature.THEME,
            ),
            readerFeaturesFor(engine),
        )
    }

    @Test
    fun `reflow annotatable engine exposes shared text commands`() {
        val engine = FakeAnnotatableReaderEngine()

        assertEquals(ReaderFeature.entries.toSet(), readerFeaturesFor(engine))
    }

    @Test
    fun `unsupported search capability is omitted from feature projection`() {
        val engine = FakeReaderEngine(
            format = BookFormat.PDF,
            supportsSearch = false,
            supportedModes = setOf(ReadingMode.PAGED),
        )
        val features = readerFeaturesFor(engine)
        assertFalse(features.contains(ReaderFeature.SEARCH))
        assertTrue(features.contains(ReaderFeature.BOOKMARKS))
    }

    @Test
    fun `annotatable engine with runtime annotation capability false omits ANNOTATIONS only`() {
        // PDF-like: implements TextAnnotatableReaderEngine but session lacks selection API.
        // hasSavedAnnotations defaults false — management panel stays hidden without persisted rows.
        val engine = FakeAnnotatableReaderEngine(
            format = BookFormat.PDF,
            supportsSearch = true,
            supportedModes = setOf(ReadingMode.PAGED),
            supportsTextAnnotationCreation = false,
        )
        val features = readerFeaturesFor(engine, hasSavedAnnotations = false)
        assertFalse(features.contains(ReaderFeature.ANNOTATIONS))
        assertTrue(features.contains(ReaderFeature.SEARCH))
        assertTrue(features.contains(ReaderFeature.BOOKMARKS))
        assertTrue(features.contains(ReaderFeature.TOC))
        assertTrue(features.contains(ReaderFeature.PROGRESS))
        assertTrue(features.contains(ReaderFeature.THEME))
        assertFalse(features.contains(ReaderFeature.FONT))
    }

    @Test
    fun `annotatable engine with creation false still exposes ANNOTATIONS when hasSavedAnnotations`() {
        // Saved/restored rows must surface management even when selectContent is unavailable.
        val engine = FakeAnnotatableReaderEngine(
            format = BookFormat.PDF,
            supportsSearch = true,
            supportedModes = setOf(ReadingMode.PAGED),
            supportsTextAnnotationCreation = false,
        )
        val features = readerFeaturesFor(engine, hasSavedAnnotations = true)
        assertTrue(features.contains(ReaderFeature.ANNOTATIONS))
        assertTrue(features.contains(ReaderFeature.SEARCH))
        assertTrue(features.contains(ReaderFeature.BOOKMARKS))
        assertTrue(features.contains(ReaderFeature.TOC))
        assertTrue(features.contains(ReaderFeature.PROGRESS))
        assertTrue(features.contains(ReaderFeature.THEME))
        assertFalse(features.contains(ReaderFeature.FONT))
    }

    private open class FakeReaderEngine(
        override val format: BookFormat,
        override val supportsSearch: Boolean,
        override val supportedModes: Set<ReadingMode>,
    ) : ReaderEngine {
        override val id = "fake"
        override val priority = 0
        override val pagingKind = MutableStateFlow(
            if (ReadingMode.SCROLL in supportedModes) PagingKind.CONTINUOUS else PagingKind.PAGED,
        )
        override val currentLocator = MutableStateFlow(Locator(LocatorStrategy.Unknown))
        override val pageCount = MutableStateFlow(1)

        override suspend fun supports(uri: Uri): Boolean = true
        override suspend fun openBook(uri: Uri): Locator = currentLocator.value
        override fun createView(): View = error("not used")
        override suspend fun close() = Unit
        override suspend fun goTo(locator: Locator) {
            currentLocator.value = locator
        }
        override suspend fun setFontSize(sp: Float) = Unit
        override suspend fun setMode(mode: ReadingMode) = Unit
    }

    private class FakeAnnotatableReaderEngine(
        format: BookFormat = BookFormat.EPUB,
        supportsSearch: Boolean = true,
        supportedModes: Set<ReadingMode> = setOf(ReadingMode.SCROLL, ReadingMode.PAGED),
        override val supportsTextAnnotationCreation: Boolean = true,
    ) : FakeReaderEngine(
        format = format,
        supportsSearch = supportsSearch,
        supportedModes = supportedModes,
    ), TextAnnotatableReaderEngine {
        override fun setTextAnnotations(annotations: List<ReaderTextAnnotation>) = Unit
    }
}
