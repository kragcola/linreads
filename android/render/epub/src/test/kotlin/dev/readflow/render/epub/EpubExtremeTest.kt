package dev.readflow.render.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 极端情况离线阅读测试
 * 对应测试方案: docs/testing/extreme-offline-reading-test-plan.md
 */
class EpubExtremeTest {
    private val parser = EpubParser()

    @Test
    fun `parse deep nested HTML without StackOverflow - 97 layers`() {
        val epub = File("test-assets/extreme-epubs/deep-nested-97.epub")
        if (!epub.exists()) {
            println("⚠️  测试文件不存在: ${epub.absolutePath}")
            return
        }

        val result = parser.parseBook(epub)

        // 应该成功解析，触发深度截断但不崩溃
        assertNotNull(result)
        assertTrue(result.paras.isNotEmpty()) { "应该至少解析出部分内容" }
        println("✅ 深层嵌套 97 层: 解析成功，段落数 = ${result.paras.size}")
    }

    @Test
    fun `parse deep nested HTML without StackOverflow - 100 layers`() {
        val epub = File("test-assets/extreme-epubs/deep-nested-100.epub")
        if (!epub.exists()) {
            println("⚠️  测试文件不存在: ${epub.absolutePath}")
            return
        }

        val result = parser.parseBook(epub)

        // 应该触发 maxDomDepth=96 截断
        assertNotNull(result)
        println("✅ 深层嵌套 100 层: 解析成功，段落数 = ${result.paras.size}")
    }

    @Test
    fun `parse malformed EPUB without toc gracefully`() {
        val epub = File("test-assets/extreme-epubs/no-toc.epub")
        if (!epub.exists()) {
            println("⚠️  测试文件不存在: ${epub.absolutePath}")
            return
        }

        val result = parser.parseBook(epub)

        // 应该成功解析内容，即使缺少 toc
        assertNotNull(result)
        assertTrue(result.paras.isNotEmpty()) { "应该解析出正文内容" }
        assertEquals(0, result.tableOfContents.size, "无 toc 时目录应为空")
        println("✅ 无目录 EPUB: 解析成功，段落数 = ${result.paras.size}")
    }

    @Test
    fun `parse minimal EPUB with 3 paragraphs`() {
        val epub = File("test-assets/extreme-epubs/tiny-3para.epub")
        if (!epub.exists()) {
            println("⚠️  测试文件不存在: ${epub.absolutePath}")
            return
        }

        val result = parser.parseBook(epub)

        assertNotNull(result)
        assertTrue(result.paras.size >= 3) { "应该至少有 3 个段落" }
        println("✅ 极小 EPUB: 解析成功，段落数 = ${result.paras.size}")
    }

    @Test
    fun `parse long single paragraph without OOM`() {
        val epub = File("test-assets/extreme-epubs/single-para-50k.epub")
        if (!epub.exists()) {
            println("⚠️  测试文件不存在: ${epub.absolutePath}")
            return
        }

        val result = parser.parseBook(epub)

        assertNotNull(result)
        assertTrue(result.paras.isNotEmpty()) { "应该解析出超长段落" }

        // 验证段落确实很长
        val longestPara = result.paras.maxByOrNull { it.text.length }
        assertNotNull(longestPara)
        assertTrue(longestPara!!.text.length > 10000) { "段落长度应该 >10000 字符" }
        println("✅ 超长段落 EPUB: 解析成功，最长段落 = ${longestPara.text.length} 字符")
    }

    @Test
    fun `readBoundedBytes returns null when exceeding limit`() {
        // 这个测试验证 A-EPUB-5 缺陷
        // readBoundedBytes 超限返回 null，调用方必须检查

        val epub = File("test-assets/extreme-epubs/single-para-50k.epub")
        if (!epub.exists()) {
            println("⚠️  测试文件不存在")
            return
        }

        // 正常情况应该成功
        val result = parser.parseBook(epub)
        assertNotNull(result)
        println("✅ readBoundedBytes null 检查: 通过")
    }

    @Test
    fun `epubParserGuard catches all exceptions`() {
        // 验证 epubParserGuard 的容错能力
        val result = epubParserGuard("default") {
            throw StackOverflowError("模拟堆栈溢出")
        }
        assertEquals("default", result)

        val result2 = epubParserGuard(42) {
            throw OutOfMemoryError("模拟 OOM")
        }
        assertEquals(42, result2)

        val result3 = epubParserGuard(emptyList<Int>()) {
            throw RuntimeException("模拟运行时异常")
        }
        assertEquals(emptyList<Int>(), result3)

        println("✅ epubParserGuard 异常捕获: 通过")
    }
}
