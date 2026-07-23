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
        // Parity with SettingsViewModel.normalizedEpubFontFamily / DataStore canonicalEpubFontReplacements:
        // collapse internal whitespace to a single space after quote stripping.
        assertEquals("book serif", normalizeFontFamilyKey("\"Book   Serif\""))
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
                .missing { font-family: "Missing Face", serif; }
              </style>
            </head>
            <body>
              <p class="decorated">你好</p>
              <p class="missing">缺失字体</p>
              <p style="font-family: monospace">code-like</p>
            </body></html>
        """.trimIndent()
        val content = parseReaderItemsContent(spineIndex = 0, html = html)
        assertEquals("fonts/Story.ttf", content.bookFontMap.faceForFamily("Story")?.srcPath)
        val body = content.items.filterIsInstance<EpubReaderItem.Text>().first()
        val familySpan = body.styleSpans.firstOrNull { it.style == EpubTextStyle.FontFamily }
        assertTrue(familySpan != null)
        assertTrue(familySpan!!.fontFamily!!.contains("Story", ignoreCase = true))
        assertEquals(setOf("story", "missing face"), content.referencedFontFamilies)
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

    @Test
    fun `generic-only family stack keeps the reader typeface without consulting resolvers`() {
        var replacementCalls = 0
        var embeddedCalls = 0

        val resolved = resolveEpubCssTypeface(
            cssFontFamily = "serif, system-ui, sans-serif, monospace",
            replacements = mapOf("serif" to "system_sans"),
            bookFonts = epubBookFontMapFromFaces(
                listOf(EpubFontFace("serif", "fonts/ignored.ttf")),
            ),
            replacementResolver = {
                replacementCalls += 1
                android.graphics.Typeface.DEFAULT
            },
            embeddedResolver = {
                embeddedCalls += 1
                android.graphics.Typeface.DEFAULT
            },
        )

        assertNull(resolved)
        assertEquals(0, replacementCalls)
        assertEquals(0, embeddedCalls)
    }

    @Test
    fun `replacement miss falls through to a later embedded family in the css stack`() {
        val bookFonts = epubBookFontMapFromFaces(
            listOf(EpubFontFace("Mincho", "fonts/mincho.otf")),
        )
        var embeddedPath: String? = null

        val resolved = resolveEpubCssTypeface(
            cssFontFamily = "Unknown, Mincho, serif",
            replacements = mapOf("other" to "system_serif"),
            bookFonts = bookFonts,
            replacementResolver = { android.graphics.Typeface.DEFAULT_BOLD },
            embeddedResolver = { face ->
                embeddedPath = face.srcPath
                android.graphics.Typeface.MONOSPACE
            },
        )

        assertSame(android.graphics.Typeface.MONOSPACE, resolved)
        assertEquals("fonts/mincho.otf", embeddedPath)
    }

    @Test
    fun `unavailable user replacement falls back to the embedded face for the same family`() {
        val bookFonts = epubBookFontMapFromFaces(
            listOf(EpubFontFace("Story", "fonts/story.ttf")),
        )
        var requestedReplacement: String? = null
        var embeddedCalls = 0

        val resolved = resolveEpubCssTypeface(
            cssFontFamily = "Story, serif",
            replacements = mapOf("story" to "custom:missing.ttf"),
            bookFonts = bookFonts,
            replacementResolver = { fontId ->
                requestedReplacement = fontId
                null
            },
            embeddedResolver = {
                embeddedCalls += 1
                android.graphics.Typeface.DEFAULT_BOLD
            },
        )

        assertSame(android.graphics.Typeface.DEFAULT_BOLD, resolved)
        assertEquals("custom:missing.ttf", requestedReplacement)
        assertEquals(1, embeddedCalls)
    }

    @Test
    fun `Moon-style base replacement key covers Story-Regular Bold Italic when exact map missing`() {
        val bookFonts = epubBookFontMapFromFaces(
            listOf(
                EpubFontFace("Story-Regular", "fonts/story-regular.ttf"),
                EpubFontFace("Story-Bold", "fonts/story-bold.ttf"),
                EpubFontFace("Story-Italic", "fonts/story-italic.ttf"),
            ),
        )
        val replacements = mapOf("story" to "system_sans")
        val requestedReplacementIds = mutableListOf<String>()
        var embeddedCalls = 0
        val embeddedResolver: (EpubFontFace) -> android.graphics.Typeface? = {
            embeddedCalls += 1
            android.graphics.Typeface.MONOSPACE
        }
        val replacementResolver: (String) -> android.graphics.Typeface? = { fontId ->
            requestedReplacementIds += fontId
            android.graphics.Typeface.DEFAULT_BOLD
        }

        for (cssFamily in listOf("Story-Regular", "Story-Bold", "Story-Italic")) {
            val resolved = resolveEpubCssTypeface(
                cssFontFamily = cssFamily,
                replacements = replacements,
                bookFonts = bookFonts,
                replacementResolver = replacementResolver,
                embeddedResolver = embeddedResolver,
            )
            assertSame(
                "CSS family $cssFamily should use story base replacement",
                android.graphics.Typeface.DEFAULT_BOLD,
                resolved,
            )
        }
        assertEquals(listOf("system_sans", "system_sans", "system_sans"), requestedReplacementIds)
        assertEquals(0, embeddedCalls)

        // Exact style-specific replacement still wins when both exact and base mappings exist.
        val exactWins = resolveEpubCssTypeface(
            cssFontFamily = "Story-Bold",
            replacements = mapOf(
                "story-bold" to "system_serif",
                "story" to "system_sans",
            ),
            bookFonts = bookFonts,
            replacementResolver = { fontId ->
                when (fontId) {
                    "system_serif" -> android.graphics.Typeface.SERIF
                    "system_sans" -> android.graphics.Typeface.SANS_SERIF
                    else -> null
                }
            },
            embeddedResolver = embeddedResolver,
        )
        assertSame(android.graphics.Typeface.SERIF, exactWins)
    }

    @Test
    fun `exact replacement null falls through to base replacement before embedded`() {
        val bookFonts = epubBookFontMapFromFaces(
            listOf(EpubFontFace("Story-Bold", "fonts/story-bold.ttf")),
        )
        val replacementCalls = mutableListOf<String>()
        var embeddedCalls = 0

        val resolved = resolveEpubCssTypeface(
            cssFontFamily = "Story-Bold",
            replacements = mapOf(
                "story-bold" to "exact_missing",
                "story" to "base_ok",
            ),
            bookFonts = bookFonts,
            replacementResolver = { fontId ->
                replacementCalls += fontId
                when (fontId) {
                    "exact_missing" -> null
                    "base_ok" -> android.graphics.Typeface.DEFAULT_BOLD
                    else -> null
                }
            },
            embeddedResolver = {
                embeddedCalls += 1
                android.graphics.Typeface.MONOSPACE
            },
        )

        assertSame(android.graphics.Typeface.DEFAULT_BOLD, resolved)
        assertEquals(listOf("exact_missing", "base_ok"), replacementCalls)
        assertEquals(0, embeddedCalls)
    }

    @Test
    fun `base replacement null falls through to exact embedded face`() {
        val bookFonts = epubBookFontMapFromFaces(
            listOf(EpubFontFace("Story-Bold", "fonts/story-bold.ttf")),
        )
        val replacementCalls = mutableListOf<String>()
        val embeddedPaths = mutableListOf<String>()

        val resolved = resolveEpubCssTypeface(
            cssFontFamily = "Story-Bold",
            replacements = mapOf("story" to "base_missing"),
            bookFonts = bookFonts,
            replacementResolver = { fontId ->
                replacementCalls += fontId
                null
            },
            embeddedResolver = { face ->
                embeddedPaths += face.srcPath
                android.graphics.Typeface.MONOSPACE
            },
        )

        assertSame(android.graphics.Typeface.MONOSPACE, resolved)
        assertEquals(listOf("base_missing"), replacementCalls)
        assertEquals(listOf("fonts/story-bold.ttf"), embeddedPaths)
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
