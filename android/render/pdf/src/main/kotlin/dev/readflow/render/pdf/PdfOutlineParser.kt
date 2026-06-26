package dev.readflow.render.pdf

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.TocEntry
import java.io.File
import java.nio.charset.StandardCharsets

internal object PdfOutlineParser {
    fun parse(file: File, pageCount: Int): List<TocEntry> {
        if (pageCount <= 0 || !file.isFile || file.length() > MAX_SCAN_BYTES) return emptyList()
        val source = runCatching {
            file.inputStream().use { input ->
                val bytes = input.readNBytes(MAX_SCAN_BYTES + 1)
                if (bytes.size > MAX_SCAN_BYTES) return emptyList()
                String(bytes, StandardCharsets.ISO_8859_1)
            }
        }.getOrElse { return emptyList() }
        val objects = parseObjects(source)
        val catalogRef = findCatalogRef(objects, source) ?: return emptyList()
        val catalog = objects[catalogRef] ?: return emptyList()
        val outlinesRef = refAfterName(catalog, "Outlines") ?: return emptyList()
        val pageRefs = pageRefs(objects, catalog).take(pageCount)
        if (pageRefs.isEmpty()) return emptyList()
        val pageIndexByRef = pageRefs.withIndex().associate { (index, ref) -> ref to index }
        val namedDestinations = namedDestinations(objects, catalog)
        val firstOutline = refAfterName(objects[outlinesRef].orEmpty(), "First") ?: return emptyList()
        val entries = mutableListOf<TocEntry>()
        walkOutline(
            ref = firstOutline,
            level = 0,
            objects = objects,
            pageIndexByRef = pageIndexByRef,
            namedDestinations = namedDestinations,
            pageCount = pageCount,
            visited = mutableSetOf(),
            entries = entries,
        )
        return entries
    }

    private fun walkOutline(
        ref: PdfRef,
        level: Int,
        objects: Map<PdfRef, String>,
        pageIndexByRef: Map<PdfRef, Int>,
        namedDestinations: Map<String, PdfRef>,
        pageCount: Int,
        visited: MutableSet<PdfRef>,
        entries: MutableList<TocEntry>,
    ) {
        if (entries.size >= MAX_OUTLINE_ENTRIES || !visited.add(ref)) return
        val body = objects[ref] ?: return
        val title = parseTitle(body, objects)
        val pageIndex = destinationRef(body, objects, namedDestinations)?.let(pageIndexByRef::get)
        if (!title.isNullOrBlank() && pageIndex != null) {
            entries += TocEntry(
                title = title,
                locator = Locator(
                    strategy = LocatorStrategy.Page(pageIndex, pageCount),
                    totalProgression = pageIndex.toFloat() / pageCount,
                ),
                level = level,
            )
        }
        refAfterName(body, "First")?.let { child ->
            walkOutline(child, level + 1, objects, pageIndexByRef, namedDestinations, pageCount, visited, entries)
        }
        refAfterName(body, "Next")?.let { next ->
            walkOutline(next, level, objects, pageIndexByRef, namedDestinations, pageCount, visited, entries)
        }
    }

    private fun parseObjects(source: String): Map<PdfRef, String> {
        val objects = LinkedHashMap<PdfRef, String>()
        objectRegex.findAll(source).forEach { match ->
            objects[PdfRef(match.groupValues[1].toInt(), match.groupValues[2].toInt())] =
                match.groupValues[3]
        }
        return objects
    }

    private fun findCatalogRef(objects: Map<PdfRef, String>, source: String): PdfRef? {
        val rootRef = refAfterName(source, "Root")
        if (rootRef != null && objects[rootRef]?.contains(typeCatalogRegex) == true) return rootRef
        return objects.entries.firstOrNull { (_, body) -> body.contains(typeCatalogRegex) }?.key
    }

    private fun pageRefs(objects: Map<PdfRef, String>, catalog: String): List<PdfRef> {
        val pagesRoot = refAfterName(catalog, "Pages")
        val ordered = mutableListOf<PdfRef>()
        if (pagesRoot != null) {
            collectPageRefs(pagesRoot, objects, ordered, mutableSetOf())
        }
        return ordered.ifEmpty {
            objects.entries
                .filter { (_, body) -> isPageObject(body) }
                .map { it.key }
        }
    }

    private fun collectPageRefs(
        ref: PdfRef,
        objects: Map<PdfRef, String>,
        ordered: MutableList<PdfRef>,
        visited: MutableSet<PdfRef>,
    ) {
        if (!visited.add(ref)) return
        val body = objects[ref] ?: return
        if (isPageObject(body)) {
            ordered += ref
            return
        }
        kidsRefs(body).forEach { child ->
            collectPageRefs(child, objects, ordered, visited)
        }
    }

    private fun isPageObject(body: String): Boolean = body.contains(typePageRegex)

    private fun kidsRefs(body: String): List<PdfRef> {
        val kidsBody = kidsRegex.find(body)?.groupValues?.get(1) ?: return emptyList()
        return refRegex.findAll(kidsBody).map { it.toPdfRef() }.toList()
    }

    private fun refAfterName(body: String, name: String): PdfRef? {
        val regex = Regex("""/${Regex.escape(name)}\s+(\d+)\s+(\d+)\s+R""")
        return regex.find(body)?.toPdfRef()
    }

    private fun destinationRef(
        body: String,
        objects: Map<PdfRef, String>,
        namedDestinations: Map<String, PdfRef>,
    ): PdfRef? =
        destArrayRegex.find(body)?.toPdfRef()
            ?: actionDestArrayRegex.find(body)?.toPdfRef()
            ?: refAfterName(body, "Dest")?.let { destinationObjectRef(it, objects, namedDestinations) }
            ?: refAfterName(body, "D")?.let { destinationObjectRef(it, objects, namedDestinations) }
            ?: refAfterName(body, "A")?.let { destinationObjectRef(it, objects, namedDestinations) }
            ?: namedDestinationNameAfter(body, "Dest")?.let(namedDestinations::get)
            ?: namedDestinationNameAfter(body, "D")?.let(namedDestinations::get)

    private fun namedDestinations(objects: Map<PdfRef, String>, catalog: String): Map<String, PdfRef> {
        val destinations = mutableMapOf<String, PdfRef>()
        val visited = mutableSetOf<PdfRef>()
        refAfterName(catalog, "Dests")?.let { collectNamedDestinations(it, objects, destinations, visited) }
        refAfterName(catalog, "Names")
            ?.let(objects::get)
            ?.let { namesBody ->
                refAfterName(namesBody, "Dests")?.let { collectNamedDestinations(it, objects, destinations, visited) }
            }
        return destinations
    }

    private fun collectNamedDestinations(
        ref: PdfRef,
        objects: Map<PdfRef, String>,
        destinations: MutableMap<String, PdfRef>,
        visited: MutableSet<PdfRef>,
    ) {
        if (!visited.add(ref)) return
        val body = objects[ref] ?: return
        val namesBody = arrayBodyAfterName(body, "Names")
        if (namesBody != null) {
            var index = 0
            while (index < namesBody.length) {
                val parsedName = parsePdfStringOrName(namesBody, index) ?: break
                val destination = destinationValueAt(namesBody, parsedName.nextIndex, objects)
                if (destination != null) {
                    destination.pageRef?.let { pageRef ->
                        destinations[parsedName.value] = pageRef
                    }
                    index = destination.nextIndex
                } else {
                    index = parsedName.nextIndex
                }
            }
        } else {
            collectNamedDestinationDictionaryEntries(body, objects, destinations)
        }
        kidsRefs(body).forEach { child ->
            collectNamedDestinations(child, objects, destinations, visited)
        }
    }

    private fun collectNamedDestinationDictionaryEntries(
        body: String,
        objects: Map<PdfRef, String>,
        destinations: MutableMap<String, PdfRef>,
    ) {
        var index = 0
        var dictionaryDepth = 0
        var arrayDepth = 0
        while (index < body.length) {
            when {
                body[index] == '(' -> index = skipLiteralString(body, index) + 1
                body[index] == '[' -> {
                    arrayDepth++
                    index++
                }
                body[index] == ']' -> {
                    if (arrayDepth > 0) arrayDepth--
                    index++
                }
                body[index] == '<' && body.getOrNull(index + 1) == '<' -> {
                    dictionaryDepth++
                    index += 2
                }
                body[index] == '>' && body.getOrNull(index + 1) == '>' -> {
                    if (dictionaryDepth > 0) dictionaryDepth--
                    index += 2
                }
                body[index] == '/' && dictionaryDepth == 1 && arrayDepth == 0 -> {
                    val parsedName = parsePdfStringOrName(body, index)
                    if (parsedName == null) {
                        index++
                    } else {
                        destinationValueRef(body, parsedName.nextIndex, objects)?.let { pageRef ->
                            destinations[parsedName.value] = pageRef
                        }
                        index = parsedName.nextIndex
                    }
                }
                else -> index++
            }
        }
    }

    private fun destinationValueRef(
        body: String,
        start: Int,
        objects: Map<PdfRef, String>,
    ): PdfRef? {
        arrayBodyAt(body, start)?.body?.let { destinationArray ->
            return arrayDestinationRef(destinationArray)
        }
        val ref = refAt(body, start) ?: return null
        return destinationObjectRef(ref, objects, emptyMap())
    }

    private fun destinationObjectRef(
        ref: PdfRef,
        objects: Map<PdfRef, String>,
        namedDestinations: Map<String, PdfRef>,
    ): PdfRef? {
        val body = objects[ref] ?: return null
        destinationRef(body, objects, namedDestinations)?.let { return it }
        arrayBodyAfterName(body, "D")?.let { destinationArray ->
            arrayDestinationRef(destinationArray)?.let { return it }
        }
        arrayBodyAfterName(body, "Dest")?.let { destinationArray ->
            arrayDestinationRef(destinationArray)?.let { return it }
        }
        return arrayBodyAt(body, 0)?.body?.let { arrayDestinationRef(it) }
    }

    private fun destinationValueAt(
        body: String,
        start: Int,
        objects: Map<PdfRef, String>,
    ): ParsedDestinationRef? {
        arrayBodyAt(body, start)?.let { destinationArray ->
            return ParsedDestinationRef(
                pageRef = arrayDestinationRef(destinationArray.body),
                nextIndex = destinationArray.nextIndex,
            )
        }
        var index = start
        while (index < body.length && body[index].isWhitespace()) index++
        val match = refRegex.find(body, index) ?: return null
        if (match.range.first != index) return null
        return ParsedDestinationRef(
            pageRef = destinationObjectRef(match.toPdfRef(), objects, emptyMap()),
            nextIndex = match.range.last + 1,
        )
    }

    private fun refAt(body: String, start: Int): PdfRef? {
        var index = start
        while (index < body.length && body[index].isWhitespace()) index++
        val match = refRegex.find(body, index) ?: return null
        return if (match.range.first == index) match.toPdfRef() else null
    }

    private fun namedDestinationNameAfter(body: String, name: String): String? {
        val marker = "/$name"
        val markerIndex = body.indexOf(marker)
        if (markerIndex < 0) return null
        val afterMarker = markerIndex + marker.length
        if (afterMarker < body.length && isPdfNameChar(body[afterMarker])) return null
        return parsePdfStringOrName(body, afterMarker)?.value
    }

    private fun arrayBodyAt(body: String, start: Int): ParsedArray? {
        var index = start
        while (index < body.length && body[index].isWhitespace()) index++
        if (body.getOrNull(index) != '[') return null
        val arrayStart = index + 1
        var depth = 1
        index = arrayStart
        while (index < body.length) {
            when (body[index]) {
                '\\' -> index++
                '(' -> index = skipLiteralString(body, index)
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        return ParsedArray(body.substring(arrayStart, index), index + 1)
                    }
                }
            }
            index++
        }
        return null
    }

    private fun arrayBodyAfterName(body: String, name: String): String? {
        val marker = "/$name"
        val markerIndex = body.indexOf(marker)
        if (markerIndex < 0) return null
        var index = markerIndex + marker.length
        while (index < body.length && body[index].isWhitespace()) index++
        if (body.getOrNull(index) != '[') return null
        val start = index + 1
        var depth = 1
        index = start
        while (index < body.length) {
            when (body[index]) {
                '\\' -> index++
                '(' -> index = skipLiteralString(body, index)
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return body.substring(start, index)
                }
            }
            index++
        }
        return null
    }

    private fun parseTitle(body: String, objects: Map<PdfRef, String>): String? {
        val marker = "/Title"
        val markerIndex = body.indexOf(marker)
        if (markerIndex < 0) return null
        var index = markerIndex + marker.length
        while (index < body.length && body[index].isWhitespace()) index++
        if (index >= body.length) return null
        return when {
            body[index] == '(' -> parseLiteralString(body, index)
            body[index] == '<' && body.getOrNull(index + 1) != '<' -> parseHexString(body, index)
            else -> refAt(body, index)?.let(objects::get)?.trimStart()?.let { referencedBody ->
                when {
                    referencedBody.startsWith("(") -> parseLiteralString(referencedBody, 0)
                    referencedBody.startsWith("<") && referencedBody.getOrNull(1) != '<' -> parseHexString(referencedBody, 0)
                    else -> null
                }
            }
        }?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parsePdfStringOrName(body: String, start: Int): ParsedPdfName? {
        var index = start
        while (index < body.length && body[index].isWhitespace()) index++
        if (index >= body.length) return null
        return when {
            body[index] == '(' -> {
                val text = parseLiteralString(body, index) ?: return null
                ParsedPdfName(text, nextPdfTokenIndex(body, index))
            }
            body[index] == '<' && body.getOrNull(index + 1) != '<' -> {
                val text = parseHexString(body, index) ?: return null
                ParsedPdfName(text, nextPdfTokenIndex(body, index))
            }
            body[index] == '/' -> {
                val end = (index + 1 until body.length).firstOrNull { !isPdfNameChar(body[it]) } ?: body.length
                ParsedPdfName(decodePdfName(body.substring(index + 1, end)), end)
            }
            else -> null
        }
    }

    private fun nextPdfTokenIndex(body: String, start: Int): Int {
        var index = start
        if (body.getOrNull(index) == '(') {
            return skipLiteralString(body, index) + 1
        }
        if (body.getOrNull(index) == '<') {
            val end = body.indexOf('>', startIndex = index + 1)
            return if (end >= 0) end + 1 else body.length
        }
        return index
    }

    private fun skipLiteralString(body: String, start: Int): Int {
        var depth = 0
        var index = start + 1
        while (index < body.length) {
            when (body[index]) {
                '\\' -> index++
                '(' -> depth++
                ')' -> {
                    if (depth == 0) return index
                    depth--
                }
            }
            index++
        }
        return body.lastIndex
    }

    private fun parseLiteralString(body: String, start: Int): String? {
        val bytes = mutableListOf<Byte>()
        var depth = 0
        var index = start + 1
        while (index < body.length) {
            when (val char = body[index]) {
                '\\' -> {
                    index++
                    if (index >= body.length) return null
                    when (val escaped = body[index]) {
                        'n' -> bytes += '\n'.pdfByte()
                        'r' -> bytes += '\r'.pdfByte()
                        't' -> bytes += '\t'.pdfByte()
                        'b' -> bytes += '\b'.pdfByte()
                        'f' -> bytes += 0x0c.toByte()
                        '(', ')', '\\' -> bytes += escaped.pdfByte()
                        '\r' -> if (body.getOrNull(index + 1) == '\n') index++
                        '\n' -> Unit
                        in '0'..'7' -> {
                            var octal = escaped.toString()
                            repeat(2) {
                                val next = body.getOrNull(index + 1)
                                if (next != null && next in '0'..'7') {
                                    index++
                                    octal += body[index]
                                }
                            }
                            bytes += (octal.toInt(8) and 0xff).toByte()
                        }
                        else -> bytes += escaped.pdfByte()
                    }
                }
                '(' -> {
                    depth++
                    bytes += char.pdfByte()
                }
                ')' -> {
                    if (depth == 0) return decodePdfText(bytes.toByteArray())
                    depth--
                    bytes += char.pdfByte()
                }
                else -> bytes += char.pdfByte()
            }
            index++
        }
        return null
    }

    private fun parseHexString(body: String, start: Int): String? {
        val end = body.indexOf('>', startIndex = start + 1)
        if (end < 0) return null
        val hex = body.substring(start + 1, end).filterNot(Char::isWhitespace)
        if (hex.isEmpty()) return null
        val bytes = hex.chunked(2).mapNotNull { chunk ->
            chunk.padEnd(2, '0').toIntOrNull(16)?.toByte()
        }.toByteArray()
        return decodePdfText(bytes)
    }

    private fun decodePdfText(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        val utf8 = String(bytes, StandardCharsets.UTF_8)
        return if ('\uFFFD' in utf8) String(bytes, StandardCharsets.ISO_8859_1) else utf8
    }

    private fun MatchResult.toPdfRef(): PdfRef =
        PdfRef(groupValues[1].toInt(), groupValues[2].toInt())

    private fun Char.pdfByte(): Byte = (code and 0xff).toByte()

    private fun isPdfNameChar(char: Char): Boolean =
        !char.isWhitespace() && char !in charArrayOf('[', ']', '<', '>', '(', ')', '/', '%')

    private fun decodePdfName(name: String): String =
        name.replace(pdfNameEscapeRegex) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }

    private fun arrayDestinationRef(body: String): PdfRef? =
        refRegex.find(body)?.toPdfRef()

    private data class PdfRef(val objectNumber: Int, val generation: Int)

    private data class ParsedPdfName(val value: String, val nextIndex: Int)

    private data class ParsedArray(val body: String, val nextIndex: Int)

    private data class ParsedDestinationRef(val pageRef: PdfRef?, val nextIndex: Int)

    private const val MAX_SCAN_BYTES = 16 * 1024 * 1024
    private const val MAX_OUTLINE_ENTRIES = 512
    private val objectRegex = Regex("""(?s)(\d+)\s+(\d+)\s+obj\b(.*?)\bendobj""")
    private val refRegex = Regex("""(\d+)\s+(\d+)\s+R""")
    private val typeCatalogRegex = Regex("""/Type\s*/Catalog\b""")
    private val typePageRegex = Regex("""/Type\s*/Page(?!s)\b""")
    private val kidsRegex = Regex("""(?s)/Kids\s*\[(.*?)]""")
    private val destArrayRegex = Regex("""/Dest\s*\[\s*(\d+)\s+(\d+)\s+R""")
    private val actionDestArrayRegex = Regex("""/D\s*\[\s*(\d+)\s+(\d+)\s+R""")
    private val pdfNameEscapeRegex = Regex("""#([0-9A-Fa-f]{2})""")
}

internal fun buildPdfFallbackToc(total: Int): List<TocEntry> =
    (0 until total).map { index ->
        TocEntry(
            title = "第 ${index + 1} 页（页列表）",
            locator = Locator(
                strategy = LocatorStrategy.Page(index, total),
                totalProgression = if (total > 0) index.toFloat() / total else 0f,
            ),
        )
    }
