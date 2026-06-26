package dev.readflow.render.txt

import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.Locator
import dev.readflow.render.api.ReaderTextAnnotation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempFile
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class TxtDocumentTest {

    @Test
    fun `indexes blank-line paragraphs across fixed-size blocks`() {
        val first = "第一段" + "a".repeat(TxtDocument.BLOCK_BYTES + 17)
        val second = "第二段 keeps utf8 😄"
        val third = "Chapter 1\nbody"
        val file = createTempFile(prefix = "readflow-txt-", suffix = ".txt")
        file.writeText(
            "$first\n\n$second\r\n\r\n$third",
            charset = StandardCharsets.UTF_8,
        )

        val document = TxtDocument.index(file.toFile())

        assertEquals(3, document.paragraphCount)
        assertEquals(first, document.readParagraph(0))
        assertEquals(second, document.readParagraph(1))
        assertEquals(third, document.readParagraph(2))
        assertTrue(document.ranges[0].endByte > TxtDocument.BLOCK_BYTES)
        assertTrue(document.ranges.zipWithNext().all { (left, right) -> left.endByte <= right.startByte })
        assertEquals(0, document.indexForOffset(document.ranges[0].startByte))
        assertEquals(1, document.indexForOffset(document.ranges[1].startByte))
        assertEquals(2, document.indexForOffset(Long.MAX_VALUE))
    }

    @Test
    fun `large utf8 corpus keeps block ranges locator search and cache round trip stable`() {
        val paragraphs = (0 until 48).map { index ->
            val prefix = "章节 %02d Readflow marker-%02d ".format(index, index)
            (prefix + "混合 English 中文 kana かな emoji 😄 ".repeat(96)).trimEnd()
        }
        val file = createTempFile(prefix = "readflow-large-utf8-corpus-", suffix = ".txt")
        file.writeText(paragraphs.joinToString("\n\n"), charset = StandardCharsets.UTF_8)

        val document = TxtDocument.index(file.toFile())
        val fingerprint = TxtDocumentFingerprint.fromFile(file.toFile())
        val restored = TxtDocument.index(
            file = file.toFile(),
            charsetDetection = detectionFor(Charset.forName("ISO-8859-1")),
            fingerprint = fingerprint,
            cachedEngineState = document.engineState(fingerprint),
        )
        val searchResult = document.search("marker-37").single()
        val resultStrategy = searchResult.strategy as LocatorStrategy.ByteOffset

        assertTrue(file.toFile().length() > TxtDocument.BLOCK_BYTES * 3)
        assertEquals(paragraphs.size, document.paragraphCount)
        assertEquals(paragraphs[0], document.readParagraph(0))
        assertEquals(paragraphs[37], document.readParagraph(37))
        assertTrue(document.ranges.any { it.startByte > TxtDocument.BLOCK_BYTES })
        assertTrue(document.ranges.zipWithNext().all { (left, right) -> left.endByte <= right.startByte })
        assertEquals(37, document.indexForOffset(document.ranges[37].startByte))
        assertEquals(37, document.indexForOffset(resultStrategy.offset))
        assertTrue(document.isCharacterStartOffset(resultStrategy.offset))
        assertEquals(document.ranges, restored.ranges)
        assertEquals(document.charsetDetection.charset, restored.charsetDetection.charset)
    }

    @Test
    fun `splits long hard-wrapped txt ranges on line boundaries for scroll progress`() {
        val lines = (0 until 160).map { index ->
            "Readflow performance corpus line %06d: long hard-wrapped content".format(index)
        }
        val file = createTempFile(prefix = "readflow-hard-wrapped-", suffix = ".txt")
        file.writeText(lines.joinToString("\n"), charset = StandardCharsets.UTF_8)

        val document = TxtDocument.index(file.toFile())

        assertEquals(lines.size, document.paragraphCount)
        assertEquals(lines[5], document.readParagraph(5))
        assertEquals(5, document.indexForOffset(document.ranges[5].startByte))
    }

    @Test
    fun `keeps the default txt scan block at sixty four kibibytes`() {
        assertEquals(64 * 1024, TxtDocument.BLOCK_BYTES)
    }

    @Test
    fun `decodes gbk text with detected charset`() {
        val file = createTempFile(prefix = "readflow-gbk-", suffix = ".txt")
        val first = "第一章 " + "天地玄黄，宇宙洪荒。日月盈昃，辰宿列张。寒来暑往，秋收冬藏。".repeat(32)
        val second = "中文段落 " + "云腾致雨，露结为霜。金生丽水，玉出昆冈。".repeat(32)
        file.writeBytes("$first\n\n$second".toByteArray(Charset.forName("GBK")))

        val document = TxtDocument.index(file.toFile())

        assertEquals(TxtCharsetDetectionSource.Detector, document.charsetDetection.source)
        assertEquals(first, document.readParagraph(0))
        assertEquals(second, document.readParagraph(1))
    }

    @Test
    fun `decodes shift jis text with detected charset`() {
        val file = createTempFile(prefix = "readflow-sjis-", suffix = ".txt")
        file.writeBytes("第1章\n\n吾輩は猫である。".toByteArray(Charset.forName("Shift_JIS")))

        val document = TxtDocument.index(file.toFile())

        assertEquals(TxtCharsetDetectionSource.Detector, document.charsetDetection.source)
        assertEquals("第1章", document.readParagraph(0))
        assertEquals("吾輩は猫である。", document.readParagraph(1))
    }

    @Test
    fun `decodes utf8 bom without exposing bom in paragraph text`() {
        val file = createTempFile(prefix = "readflow-utf8-bom-", suffix = ".txt")
        file.writeBytes(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "序章\n\nUTF-8 段落".toByteArray(StandardCharsets.UTF_8),
        )

        val document = TxtDocument.index(file.toFile())

        assertEquals(TxtCharsetDetectionSource.Bom, document.charsetDetection.source)
        assertEquals(StandardCharsets.UTF_8, document.charsetDetection.charset)
        assertEquals("序章", document.readParagraph(0))
        assertEquals("UTF-8 段落", document.readParagraph(1))
    }

    @Test
    fun `records utf8 fallback reason when charset cannot be detected`() {
        val file = createTempFile(prefix = "readflow-empty-", suffix = ".txt")

        val detection = TxtCharsetDetector.detect(file.toFile())

        assertEquals(Charsets.UTF_8, detection.charset)
        assertEquals(TxtCharsetDetectionSource.Fallback, detection.source)
        assertNotNull(detection.fallbackReason)
    }

    @Test
    fun `normalizes utf8 byte offsets to character starts`() {
        val file = createTempFile(prefix = "readflow-utf8-offset-", suffix = ".txt")
        file.writeBytes("A你B\n\n下一段".toByteArray(StandardCharsets.UTF_8))
        val document = TxtDocument.index(file.toFile())

        assertEquals(1, document.normalizeOffsetToCharacterStart(2))
        assertEquals(1, document.normalizeOffsetToCharacterStart(3))
        assertTrue(document.isCharacterStartOffset(1))
        assertEquals(0, document.indexForOffset(3))
    }

    @Test
    fun `normalizes gbk byte offsets to character starts`() {
        val charset = Charset.forName("GBK")
        val file = createTempFile(prefix = "readflow-gbk-offset-", suffix = ".txt")
        file.writeBytes("A你B\n\n下一段".toByteArray(charset))
        val document = TxtDocument.index(file.toFile(), charsetDetection = detectionFor(charset))

        assertEquals(1, document.normalizeOffsetToCharacterStart(2))
        assertTrue(document.isCharacterStartOffset(1))
        assertEquals(0, document.indexForOffset(2))
    }

    @Test
    fun `normalizes shift jis byte offsets to character starts`() {
        val charset = Charset.forName("Shift_JIS")
        val file = createTempFile(prefix = "readflow-sjis-offset-", suffix = ".txt")
        file.writeBytes("A猫B\n\n次の段落".toByteArray(charset))
        val document = TxtDocument.index(file.toFile(), charsetDetection = detectionFor(charset))

        assertEquals(1, document.normalizeOffsetToCharacterStart(2))
        assertTrue(document.isCharacterStartOffset(1))
        assertEquals(0, document.indexForOffset(2))
    }

    @Test
    fun `normalizes gb18030 four byte offsets to character starts`() {
        val charset = Charset.forName("GB18030")
        val file = createTempFile(prefix = "readflow-gb18030-offset-", suffix = ".txt")
        val paragraph = "A𠀀B下一段"
        file.writeBytes(paragraph.toByteArray(charset))
        val document = TxtDocument.index(file.toFile(), charsetDetection = detectionFor(charset))

        assertEquals(1, document.normalizeOffsetToCharacterStart(2))
        assertEquals(1, document.normalizeOffsetToCharacterStart(3))
        assertEquals(1, document.normalizeOffsetToCharacterStart(4))
        assertTrue(document.isCharacterStartOffset(1))
        assertEquals(0, document.indexForOffset(4))
    }

    @Test
    fun `mixed encoding corpus keeps search and selection byte locators on character starts`() {
        data class CorpusCase(
            val charset: Charset,
            val paragraph: String,
            val needle: String,
            val selected: String,
        )

        val cases = listOf(
            CorpusCase(
                charset = StandardCharsets.UTF_8,
                paragraph = "序章 Readflow 猫在窗边",
                needle = "猫",
                selected = "Readflow 猫",
            ),
            CorpusCase(
                charset = Charset.forName("GBK"),
                paragraph = "第一章 Readflow 猫在窗边",
                needle = "猫",
                selected = "Readflow 猫",
            ),
            CorpusCase(
                charset = Charset.forName("Shift_JIS"),
                paragraph = "第1章 Readflow 猫が窓辺にいる",
                needle = "猫",
                selected = "Readflow 猫",
            ),
        )

        cases.forEach { corpus ->
            val file = createTempFile(prefix = "readflow-mixed-encoding-", suffix = ".txt")
            file.writeBytes(corpus.paragraph.toByteArray(corpus.charset))
            val document = TxtDocument.index(file.toFile(), charsetDetection = detectionFor(corpus.charset))
            val result = document.search(corpus.needle).single().strategy as LocatorStrategy.ByteOffset
            val start = corpus.paragraph.indexOf(corpus.selected)
            val end = start + corpus.selected.length
            val selection = document.selectionForParagraphRange(0, start, end)!!

            assertEquals(corpus.paragraph, document.readParagraph(0))
            assertEquals(
                corpus.paragraph.substring(0, corpus.paragraph.indexOf(corpus.needle))
                    .toByteArray(corpus.charset).size.toLong(),
                result.offset,
            )
            assertTrue(document.isCharacterStartOffset(result.offset))
            assertEquals(corpus.selected, selection.selectedText)
            assertTrue(document.isCharacterStartOffset((selection.start.strategy as LocatorStrategy.ByteOffset).offset))
            assertTrue(document.isCharacterStartOffset((selection.end.strategy as LocatorStrategy.ByteOffset).offset))
        }
    }

    @Test
    fun `search returns byte-offset locators in document order`() {
        val file = createTempFile(prefix = "readflow-search-", suffix = ".txt")
        val first = "序章 一只猫在窗边"
        val second = "第二段没有目标"
        val third = "尾声 猫又出现了"
        file.writeText("$first\n\n$second\n\n$third", charset = StandardCharsets.UTF_8)
        val document = TxtDocument.index(file.toFile())

        val results = document.search("猫")

        assertEquals(2, results.size)
        assertEquals(
            LocatorStrategy.ByteOffset(
                offset = document.ranges[0].startByte +
                    first.substring(0, first.indexOf("猫")).toByteArray(StandardCharsets.UTF_8).size,
                length = "猫".toByteArray(StandardCharsets.UTF_8).size,
            ),
            results[0].strategy,
        )
        assertEquals(document.ranges[2].startByte + third.substring(0, third.indexOf("猫")).toByteArray(StandardCharsets.UTF_8).size, (results[1].strategy as LocatorStrategy.ByteOffset).offset)
        assertTrue(results[0].totalProgression!! < results[1].totalProgression!!)
    }

    @Test
    fun `selection maps paragraph character range to byte offset anchors`() {
        val file = createTempFile(prefix = "readflow-select-", suffix = ".txt")
        val first = "第一段"
        val second = "A你B猫C"
        file.writeText("$first\n\n$second", charset = StandardCharsets.UTF_8)
        val document = TxtDocument.index(file.toFile())
        val start = second.indexOf("你")
        val end = second.indexOf("C")

        val selection = document.selectionForParagraphRange(1, start, end)!!

        assertEquals("你B猫", selection.selectedText)
        assertEquals(
            LocatorStrategy.ByteOffset(
                offset = document.ranges[1].startByte + second.substring(0, start).toByteArray(StandardCharsets.UTF_8).size,
                length = "你B猫".toByteArray(StandardCharsets.UTF_8).size,
            ),
            selection.start.strategy,
        )
        assertEquals(
            document.ranges[1].startByte + second.substring(0, end).toByteArray(StandardCharsets.UTF_8).size,
            (selection.end.strategy as LocatorStrategy.ByteOffset).offset,
        )
    }

    @Test
    fun `annotation byte anchors map to paragraph highlight ranges`() {
        val file = createTempFile(prefix = "readflow-highlight-", suffix = ".txt")
        val first = "第一段"
        val second = "A你B猫C"
        file.writeText("$first\n\n$second", charset = StandardCharsets.UTF_8)
        val document = TxtDocument.index(file.toFile())
        val start = second.indexOf("你")
        val end = second.indexOf("C")
        val startByte = document.ranges[1].startByte + second.substring(0, start).toByteArray(StandardCharsets.UTF_8).size
        val endByte = document.ranges[1].startByte + second.substring(0, end).toByteArray(StandardCharsets.UTF_8).size

        val ranges = document.highlightRangesForParagraph(
            paragraphIndex = 1,
            annotations = listOf(
                ReaderTextAnnotation(
                    id = "a1",
                    start = Locator(LocatorStrategy.ByteOffset(startByte, (endByte - startByte).toInt())),
                    end = Locator(LocatorStrategy.ByteOffset(endByte, 0)),
                    selectedText = "你B猫",
                    note = null,
                    color = 0x66FFE082,
                ),
            ),
        )

        assertEquals(1, ranges.size)
        assertEquals(start, ranges.single().start)
        assertEquals(end, ranges.single().end)
        assertEquals(0x66FFE082, ranges.single().color)
    }

    @Test
    fun `engine state restores cached charset and paragraph ranges for matching fingerprint`() {
        val file = createTempFile(prefix = "readflow-cache-", suffix = ".txt")
        val text = "第一段\n\n第二段"
        file.writeText(text, charset = StandardCharsets.UTF_8)
        val fingerprint = TxtDocumentFingerprint.fromFile(file.toFile())
        val original = TxtDocument.index(
            file = file.toFile(),
            charsetDetection = detectionFor(StandardCharsets.UTF_8),
            fingerprint = fingerprint,
        )

        val restored = TxtDocument.index(
            file = file.toFile(),
            charsetDetection = detectionFor(Charset.forName("ISO-8859-1")),
            fingerprint = fingerprint,
            cachedEngineState = original.engineState(fingerprint),
        )

        assertEquals(original.ranges, restored.ranges)
        assertEquals(StandardCharsets.UTF_8, restored.charsetDetection.charset)
    }

    @Test
    fun `engine state is ignored when fingerprint does not match`() {
        val firstFile = createTempFile(prefix = "readflow-cache-first-", suffix = ".txt")
        firstFile.writeText("第一段\n\n第二段", charset = StandardCharsets.UTF_8)
        val firstFingerprint = TxtDocumentFingerprint.fromFile(firstFile.toFile())
        val original = TxtDocument.index(
            file = firstFile.toFile(),
            charsetDetection = detectionFor(StandardCharsets.UTF_8),
            fingerprint = firstFingerprint,
        )
        val secondFile = createTempFile(prefix = "readflow-cache-second-", suffix = ".txt")
        secondFile.writeText("Only one paragraph", charset = StandardCharsets.UTF_8)

        val restored = TxtDocument.index(
            file = secondFile.toFile(),
            charsetDetection = detectionFor(Charset.forName("ISO-8859-1")),
            fingerprint = TxtDocumentFingerprint.fromFile(secondFile.toFile()),
            cachedEngineState = original.engineState(firstFingerprint),
        )

        assertEquals(1, restored.paragraphCount)
        assertEquals(Charset.forName("ISO-8859-1"), restored.charsetDetection.charset)
    }

    private fun detectionFor(charset: Charset): TxtCharsetDetection =
        TxtCharsetDetection(
            charset = charset,
            source = TxtCharsetDetectionSource.Detector,
            detectedName = charset.name(),
        )
}
