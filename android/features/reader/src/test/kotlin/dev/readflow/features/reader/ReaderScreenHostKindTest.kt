package dev.readflow.features.reader

import android.net.Uri
import android.view.View
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.ReaderEngine
import dev.readflow.render.api.SelfPagingReaderEngine
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderScreenHostKindTest {

    @Test
    fun `self paging engine keeps the paged host while reporting continuous mode`() {
        val engine = FakeSelfPagingEngine(PagingKind.CONTINUOUS)

        val hostKind = readerHostKindFor(engine, engine.pagingKind.value)

        assertEquals(
            "EPUB self-paging owns SCROLL and PAGED inside one stable surface; switching modes must not remount it",
            ReaderHostKind.PAGED,
            hostKind,
        )
    }

    @Test
    fun `regular continuous engine still uses continuous host`() {
        val engine = FakeReaderEngine(PagingKind.CONTINUOUS)

        val hostKind = readerHostKindFor(engine, engine.pagingKind.value)

        assertEquals(ReaderHostKind.CONTINUOUS, hostKind)
    }

    private open class FakeReaderEngine(
        kind: PagingKind,
    ) : ReaderEngine {
        override val id = "fake"
        override val format = BookFormat.EPUB
        override val priority = 0
        override val pagingKind = MutableStateFlow(kind)
        override val supportedModes = setOf(ReadingMode.SCROLL, ReadingMode.PAGED)
        override val supportsSearch = false
        override val currentLocator = MutableStateFlow(Locator(LocatorStrategy.Unknown))
        override val pageCount = MutableStateFlow(1)
        override val chapterInfo = MutableStateFlow(ChapterInfo(0, 1, "", 0f))

        override suspend fun supports(uri: Uri): Boolean = true
        override suspend fun openBook(uri: Uri): Locator = currentLocator.value
        override fun createView(): View = error("not used")
        override suspend fun close() = Unit
        override suspend fun goTo(locator: Locator) {
            currentLocator.value = locator
        }
        override suspend fun setFontSize(sp: Float) = Unit
        override suspend fun setMode(mode: ReadingMode) {
            pagingKind.value = when (mode) {
                ReadingMode.SCROLL -> PagingKind.CONTINUOUS
                ReadingMode.PAGED -> PagingKind.PAGED
            }
        }
    }

    private class FakeSelfPagingEngine(
        kind: PagingKind,
    ) : FakeReaderEngine(kind), SelfPagingReaderEngine {
        override val selfPagingActive = true
        override suspend fun goToAdjacentPage(delta: Int) = Unit
    }
}
