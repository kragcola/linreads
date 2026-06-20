package dev.readflow.extensions.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.readflow.core.model.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Extracts a cover image for local imports (EPUB: OPF cover-image item; PDF: page 0 render).
 * TXT/MD have no image — returns null so BookCover falls back to the cloth stamp.
 */
internal object CoverExtractor {

    suspend fun extract(
        context: Context,
        srcFile: File,
        format: BookFormat,
        bookId: String,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val out = File(File(context.filesDir, "covers").apply { mkdirs() }, "$bookId.jpg")
            when (format) {
                BookFormat.EPUB -> epubCover(srcFile, out)
                BookFormat.PDF  -> pdfCover(srcFile, out)
                else            -> null
            }?.let { Uri.fromFile(out).toString() }
        }.getOrNull()
    }

    // ── EPUB ─────────────────────────────────────────────────────────────────

    private fun epubCover(epub: File, out: File): File? = ZipFile(epub).use { zip ->
        val opfPath = opfPath(zip)
        val baseDir = opfPath.substringBeforeLast('/', "")
        val opfEntry = zip.getEntry(opfPath) ?: return null
        val doc = zip.getInputStream(opfEntry).use { parseXml(it) }

        val coverHref = coverItemHref(doc) ?: return null
        val imgPath = if (baseDir.isEmpty()) coverHref else "$baseDir/$coverHref"
        val entry = zip.getEntry(imgPath) ?: zip.getEntry(coverHref) ?: return null
        out.outputStream().use { zip.getInputStream(entry).copyTo(it) }
        out
    }

    /** Returns the href of the cover image item in the OPF, or null. */
    private fun coverItemHref(opf: Document): String? {
        val items = opf.getElementsByTagName("item")
        // EPUB3: properties="cover-image"
        for (i in 0 until items.length) {
            val el = items.item(i) as? Element ?: continue
            if (el.getAttribute("properties").contains("cover-image"))
                return el.getAttribute("href")
        }
        // EPUB2: <meta name="cover" content="<id>">
        val metas = opf.getElementsByTagName("meta")
        for (i in 0 until metas.length) {
            val meta = metas.item(i) as? Element ?: continue
            if (meta.getAttribute("name") == "cover") {
                val coverId = meta.getAttribute("content")
                for (j in 0 until items.length) {
                    val item = items.item(j) as? Element ?: continue
                    if (item.getAttribute("id") == coverId)
                        return item.getAttribute("href")
                }
            }
        }
        return null
    }

    private fun opfPath(zip: ZipFile): String {
        val entry = zip.getEntry("META-INF/container.xml") ?: return "OEBPS/content.opf"
        val doc = zip.getInputStream(entry).use { parseXml(it) }
        val rootfiles = doc.getElementsByTagName("rootfile")
        for (i in 0 until rootfiles.length) {
            val rf = rootfiles.item(i) as? Element ?: continue
            if (rf.getAttribute("media-type") == "application/oebps-package+xml")
                return rf.getAttribute("full-path")
        }
        return "OEBPS/content.opf"
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    private fun pdfCover(pdf: File, out: File): File? {
        val pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        return PdfRenderer(pfd).use { renderer ->
            if (renderer.pageCount == 0) return null
            renderer.openPage(0).use { page ->
                val scale = 200f / page.width.coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
                out
            }
        }
    }

    private fun parseXml(input: InputStream): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
}
