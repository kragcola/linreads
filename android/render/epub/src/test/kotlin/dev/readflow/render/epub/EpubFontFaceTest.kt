package dev.readflow.render.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpubFontFaceTest {

    @Test
    fun `parses local url font-face and ignores remote and generic families`() {
        val css = """
            @font-face {
              font-family: "Book Serif";
              src: url("../fonts/BookSerif.ttf") format("truetype");
            }
            @font-face {
              font-family: serif;
              src: url("ignore-generic.ttf");
            }
            @font-face {
              font-family: Remote;
              src: url("https://example.com/x.ttf");
            }
            @font-face {
              font-family: 'Local Sans';
              src: local("Arial"), url("fonts/LocalSans.otf");
            }
        """.trimIndent()
        val faces = parseEpubFontFaces(css, resourceBaseDir = "OEBPS/styles")
        assertEquals(2, faces.size)
        assertEquals("book serif", faces[0].family)
        assertTrue(faces[0].srcPath.endsWith("fonts/BookSerif.ttf") || faces[0].srcPath.contains("BookSerif.ttf"))
        assertEquals("local sans", faces[1].family)
        assertTrue(faces[1].srcPath.contains("LocalSans.otf"))
    }

    @Test
    fun `book map is deterministic first face wins and resolves css lists`() {
        val faces = listOf(
            EpubFontFace(family = "Primary", srcPath = "fonts/a.ttf"),
            EpubFontFace(family = "Primary", srcPath = "fonts/b.ttf"),
            EpubFontFace(family = "Secondary", srcPath = "fonts/c.otf"),
        )
        val map = epubBookFontMapFromFaces(faces)
        assertEquals("fonts/a.ttf", map.faceForFamily("Primary")?.srcPath)
        assertEquals("fonts/c.otf", map.faceForFamily("'Secondary', serif")?.srcPath)
        assertNull(map.faceForFamily("serif, sans-serif"))
        assertNull(resolveCssFontFamily("monospace", map))
        assertEquals(
            "fonts/a.ttf",
            resolveCssFontFamily("Primary, serif", map)?.srcPath,
        )
    }

    @Test
    fun `normalize and split css font family lists handle quotes and commas`() {
        assertEquals("noto serif", normalizeFontFamilyKey("\"Noto Serif\""))
        assertEquals(
            listOf("\"Noto Serif\"", "serif"),
            splitCssFontFamilyList("\"Noto Serif\", serif"),
        )
    }

    @Test
    fun `woff sources are not advertised as loadable Android fonts`() {
        val faces = parseEpubFontFaces(
            css = """
                @font-face { font-family: WoffOne; src: url(fonts/one.woff); }
                @font-face { font-family: WoffTwo; src: url(fonts/two.woff2); }
            """.trimIndent(),
            resourceBaseDir = "OEBPS",
        )

        assertTrue(faces.isEmpty())
    }

    @Test
    fun `html cascade surfaces font-family spans and book font map`() {
        val html = """
            <html><head>
              <style>
                @font-face { font-family: "Story"; src: url("fonts/Story.ttf"); }
                .decorated { font-family: "Story", serif; }
              </style>
            </head>
            <body>
              <p class="decorated">你好</p>
              <p style="font-family: monospace">code-like</p>
            </body></html>
        """.trimIndent()
        val content = parseReaderItemsContent(spineIndex = 0, html = html)
        assertEquals("fonts/Story.ttf", content.bookFontMap.faceForFamily("Story")?.srcPath)
        val body = content.items.filterIsInstance<EpubReaderItem.Text>().first()
        val familySpan = body.styleSpans.firstOrNull { it.style == EpubTextStyle.FontFamily }
        assertTrue(familySpan != null)
        assertTrue(familySpan!!.fontFamily!!.contains("Story", ignoreCase = true))
    }

    @Test
    fun `typeface lookup is memory only before background prewarm`() {
        var loads = 0
        val cache = EpubBookFontTypefaceCache { path ->
            loads += 1
            null
        }
        val face = EpubFontFace(family = "Missing", srcPath = "fonts/missing.ttf")
        assertNull(cache.typefaceFor(face))
        assertNull(cache.typefaceFor(face))
        assertEquals(0, loads)
    }

    @Test
    fun `disk cache is reused across instances and isolated by book key`() {
        val root = createTempDirectory(prefix = "epub-font-cache-").toFile()
        val face = EpubFontFace(family = "Story", srcPath = "fonts/story.ttf")
        try {
            var firstZipReads = 0
            val callerThread = Thread.currentThread()
            var loaderThread: Thread? = null
            val first = diskBackedCache(
                root = root,
                bookKey = "book-a",
                bytesForPath = {
                    firstZipReads += 1
                    byteArrayOf(1, 2, 3, 4)
                },
                typefaceFromFile = {
                    loaderThread = Thread.currentThread()
                    android.graphics.Typeface.DEFAULT
                },
            )
            assertTrue(runBlocking { first.prewarm(face) })
            assertSame(android.graphics.Typeface.DEFAULT, first.typefaceFor(face))
            assertEquals(1, firstZipReads)
            assertNotSame(callerThread, loaderThread)

            var reopenedZipReads = 0
            val reopened = diskBackedCache(
                root = root,
                bookKey = "book-a",
                bytesForPath = {
                    reopenedZipReads += 1
                    byteArrayOf(9)
                },
            )
            assertTrue(runBlocking { reopened.prewarm(face) })
            assertSame(android.graphics.Typeface.DEFAULT, reopened.typefaceFor(face))
            assertEquals(0, reopenedZipReads)

            var secondBookZipReads = 0
            val secondBook = diskBackedCache(
                root = root,
                bookKey = "book-b",
                bytesForPath = {
                    secondBookZipReads += 1
                    byteArrayOf(5, 6, 7, 8)
                },
            )
            assertTrue(runBlocking { secondBook.prewarm(face) })
            assertEquals(1, secondBookZipReads)
            assertEquals(2, root.walkTopDown().count { it.isFile })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unsafe paths and invalid typefaces fail soft during prewarm`() {
        val root = createTempDirectory(prefix = "epub-font-invalid-").toFile()
        try {
            var zipReads = 0
            val unsafe = diskBackedCache(
                root = root,
                bookKey = "book-a",
                bytesForPath = {
                    zipReads += 1
                    byteArrayOf(1)
                },
            )
            assertFalse(
                runBlocking {
                    unsafe.prewarm(EpubFontFace("Unsafe", "../outside.ttf"))
                },
            )
            assertEquals(0, zipReads)

            val invalid = diskBackedCache(
                root = root,
                bookKey = "book-b",
                bytesForPath = { byteArrayOf(1, 2, 3) },
                typefaceFromFile = { throw IllegalArgumentException("invalid font") },
            )
            val face = EpubFontFace("Invalid", "fonts/invalid.ttf")
            assertFalse(runBlocking { invalid.prewarm(face) })
            assertNull(invalid.typefaceFor(face))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `persisted family replacement wins over embedded face`() {
        val adapters = Class.forName("dev.readflow.render.epub.EpubFontFaceKt")
        val resolve = adapters.declaredMethods.singleOrNull { method ->
            method.name == "resolveEpubCssTypeface" && method.parameterTypes.size == 5
        } ?: throw AssertionError("CSS replacement resolver is missing")
        resolve.isAccessible = true
        val bookFonts = epubBookFontMapFromFaces(
            listOf(EpubFontFace("Book Serif", "fonts/story.ttf")),
        )
        var replacementId: String? = null
        var embeddedCalls = 0

        val resolved = resolve.invoke(
            null,
            "Book Serif, serif",
            mapOf("book serif" to "system_sans"),
            bookFonts,
            { fontId: String ->
                replacementId = fontId
                android.graphics.Typeface.DEFAULT_BOLD
            },
            { _: EpubFontFace ->
                embeddedCalls += 1
                android.graphics.Typeface.MONOSPACE
            },
        ) as android.graphics.Typeface?

        assertSame(android.graphics.Typeface.DEFAULT_BOLD, resolved)
        assertEquals("system_sans", replacementId)
        assertEquals(0, embeddedCalls)
    }

    private fun diskBackedCache(
        root: File,
        bookKey: String,
        bytesForPath: (String) -> ByteArray?,
        typefaceFromFile: (File) -> android.graphics.Typeface? = { android.graphics.Typeface.DEFAULT },
    ): EpubBookFontTypefaceCache {
        val constructor = EpubBookFontTypefaceCache::class.java.declaredConstructors
            .singleOrNull { it.parameterTypes.size == 4 }
            ?: throw AssertionError("disk-backed cache constructor is missing")
        constructor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return constructor.newInstance(
            root,
            bookKey,
            bytesForPath,
            typefaceFromFile,
        ) as EpubBookFontTypefaceCache
    }
}
