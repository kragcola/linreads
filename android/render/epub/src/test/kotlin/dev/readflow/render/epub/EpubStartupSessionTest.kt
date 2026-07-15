package dev.readflow.render.epub

import dev.readflow.core.model.LocatorStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EpubStartupSessionTest {

    @Test
    fun `concurrent adjacent requests parse a target spine once`() {
        val loads = AtomicInteger(0)
        val session = session { spine ->
            loads.incrementAndGet()
            Thread.sleep(40)
            parsedSpine(spine, paragraphCount = 2)
        }
        session.installInitial(parsedSpine(0, paragraphCount = 3), globalParagraphBase = 0)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val requests = executor.invokeAll(
                List(2) {
                    Callable { session.ensureAdjacent(sourceSpineIndex = 0, targetSpineIndex = 1) }
                },
            )

            assertTrueResults(requests.map { it.get(2, TimeUnit.SECONDS) })
            assertEquals(1, loads.get())
            assertEquals(3, session.globalBase(1))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `section request installs a deep target using saved global element index`() {
        val session = session { spine -> parsedSpine(spine, paragraphCount = 3) }

        val resolved = session.ensureSection(
            LocatorStrategy.Section(spineIndex = 4, elementIndex = 91, charOffset = 15),
        )

        assertNotNull(resolved)
        assertEquals(EpubAnchor(4, localParagraphIndex = 1, paragraphOffset = 5), resolved?.anchor)
        assertEquals(91, resolved?.globalParagraphIndex)
        assertEquals(90, session.globalBase(4))
    }

    @Test
    fun `ambiguous section does not install a guessed base`() {
        val session = session { spine ->
            EpubParsedSpine(
                spineIndex = spine,
                path = "ch$spine.xhtml",
                items = emptyList(),
                paras = listOf(
                    para(spine, 0, 0),
                    para(spine, 0, 0),
                    para(spine, 0, 10),
                ),
                blocks = emptyList(),
                fragmentTargetIndexes = emptyMap(),
                charCount = 10,
            )
        }

        assertNull(session.ensureSection(LocatorStrategy.Section(2, 50, 0)))
        assertNull(session.globalBase(2))
    }

    @Test
    fun `close rejects an in flight spine result and prevents later reloads`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loads = AtomicInteger(0)
        val session = session { spine ->
            loads.incrementAndGet()
            started.countDown()
            while (true) {
                try {
                    release.await()
                    break
                } catch (_: InterruptedException) {
                    // Simulate a ZIP/parser call that cannot stop immediately when its owner closes.
                }
            }
            parsedSpine(spine, paragraphCount = 2)
        }
        session.installInitial(parsedSpine(0, paragraphCount = 1), globalParagraphBase = 0)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val request = executor.submit<EpubStartupSpine?> {
                session.ensureAdjacent(sourceSpineIndex = 0, targetSpineIndex = 1)
            }
            started.await(2, TimeUnit.SECONDS)

            session.close()
            release.countDown()

            assertNull(request.get(2, TimeUnit.SECONDS))
            assertNull(session.startupSpine(1))
            assertNull(session.ensureAdjacent(sourceSpineIndex = 0, targetSpineIndex = 1))
            assertEquals(1, loads.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun session(loader: (Int) -> EpubParsedSpine): EpubStartupSession =
        EpubStartupSession(
            packageIndex = EpubPackageIndex(
                opfPath = "content.opf",
                manifestItems = emptyMap(),
                spineItems = (0..4).map { spine ->
                    EpubPackageSpineItem(
                        manifestItem = EpubManifestItem(
                            id = "c$spine",
                            path = "ch$spine.xhtml",
                            mediaType = "application/xhtml+xml",
                            properties = emptySet(),
                        ),
                        uncompressedSizeBytes = 100L,
                    )
                },
                navDocument = null,
                ncxDocument = null,
                isFixedLayout = false,
            ),
            spineLoader = loader,
        )

    private fun parsedSpine(spine: Int, paragraphCount: Int): EpubParsedSpine =
        EpubParsedSpine(
            spineIndex = spine,
            path = "ch$spine.xhtml",
            items = emptyList(),
            paras = List(paragraphCount) { local -> para(spine, local * 10, (local + 1) * 10) },
            blocks = List(paragraphCount) { local ->
                EpubDisplayBlock.Text("paragraph $local", null, local)
            },
            fragmentTargetIndexes = emptyMap(),
            charCount = paragraphCount * 10,
        )

    private fun para(spine: Int, start: Int, end: Int): EpubPara =
        EpubPara(spine, "", start, end, start, end)

    private fun assertTrueResults(results: List<EpubStartupSpine?>) {
        assertEquals(2, results.size)
        results.forEach { assertNotNull(it) }
    }
}
