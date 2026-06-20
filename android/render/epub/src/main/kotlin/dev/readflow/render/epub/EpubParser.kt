package dev.readflow.render.epub

import org.jsoup.Jsoup
import java.io.File
import java.util.zip.ZipFile

/**
 * Parses an EPUB file into a flat paragraph list.
 * Reads container.xml → OPF → spine → per-item HTML → jsoup text extraction.
 * No CFI / WebView dependency.
 */
internal class EpubParser {

    fun parse(file: File): List<EpubPara> = ZipFile(file).use { zip ->
        val opfPath = findOpfPath(zip)
        val baseDir = opfPath.substringBeforeLast('/', "")
        parseSpineHrefs(zip, opfPath).flatMapIndexed { spineIdx, href ->
            val entryPath = if (baseDir.isEmpty()) href else "$baseDir/$href"
            val entry = zip.getEntry(entryPath) ?: return@flatMapIndexed emptyList()
            val html = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            Jsoup.parse(html).body()
                .select("p, h1, h2, h3, h4, h5, h6, li")
                .mapNotNull { el -> el.text().trim().takeIf { it.isNotEmpty() }?.let { EpubPara(spineIdx, it) } }
        }
    }

    private fun findOpfPath(zip: ZipFile): String {
        val entry = zip.getEntry("META-INF/container.xml") ?: return "OEBPS/content.opf"
        val doc = zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "") }
        return doc.selectFirst("rootfile[media-type=application/oebps-package+xml]")
            ?.attr("full-path") ?: "OEBPS/content.opf"
    }

    private fun parseSpineHrefs(zip: ZipFile, opfPath: String): List<String> {
        val entry = zip.getEntry(opfPath) ?: return emptyList()
        val opf = zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "") }
        val manifest = opf.select("manifest item").associate { it.attr("id") to it.attr("href") }
        return opf.select("spine itemref").mapNotNull { manifest[it.attr("idref")] }
    }
}
