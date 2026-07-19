package dev.readflow.render.pdf

import android.graphics.Point
import android.graphics.pdf.PdfRenderer
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Framework PDF text-layer surface accessed only via reflection so minSdk 26 class loading
 * never links API-35+/flagged model types (`PageMatchBounds`, `PdfPageTextContent`,
 * `SelectionBoundary`, `PageSelection`, …).
 *
 * Detection is fail-closed: missing methods, disabled flags, or invocation errors → [Unavailable]
 * (or selection-disabled binding that still serves search/text when those bind).
 */
internal interface PdfFrameworkTextApi {
    /** True when reflection binding exists for text/search (methods/classes resolved). */
    val isAvailable: Boolean

    /** True when [selectContent] reflection binding exists (may be false while search works). */
    val supportsSelection: Boolean

    fun hasTextOnPage(page: PdfRenderer.Page): Boolean

    /** Concatenated page text from [getTextContents]; empty on failure. */
    fun pageText(page: PdfRenderer.Page): String

    fun searchPage(page: PdfRenderer.Page, query: String): List<PdfFrameworkTextMatch>

    /**
     * Same-page framework selection via [PdfRenderer.Page.selectContent].
     * Returns null when selection is unavailable, empty, or invocation fails (fail-closed).
     * Production signatures never mention framework model types.
     */
    fun selectContent(
        page: PdfRenderer.Page,
        start: PdfSelectionBoundarySpec,
        stop: PdfSelectionBoundarySpec,
    ): PdfFrameworkPageSelection?

    companion object {
        fun resolve(
            classLoader: ClassLoader = PdfRenderer.Page::class.java.classLoader
                ?: ClassLoader.getSystemClassLoader(),
        ): PdfFrameworkTextApi = ReflectivePdfFrameworkTextApi.tryCreate(classLoader) ?: Unavailable

        val Unavailable: PdfFrameworkTextApi = object : PdfFrameworkTextApi {
            override val isAvailable: Boolean = false
            override val supportsSelection: Boolean = false
            override fun hasTextOnPage(page: PdfRenderer.Page): Boolean = false
            override fun pageText(page: PdfRenderer.Page): String = ""
            override fun searchPage(page: PdfRenderer.Page, query: String): List<PdfFrameworkTextMatch> =
                emptyList()
            override fun selectContent(
                page: PdfRenderer.Page,
                start: PdfSelectionBoundarySpec,
                stop: PdfSelectionBoundarySpec,
            ): PdfFrameworkPageSelection? = null
        }
    }
}

/** One search hit in page-point space (1/72"), isolated from framework model types. */
internal data class PdfFrameworkTextMatch(
    val textStartIndex: Int,
    val bounds: List<PdfRect>,
)

/** Point or character-index boundary for [PdfFrameworkTextApi.selectContent]. */
internal sealed class PdfSelectionBoundarySpec {
    data class Point(val x: Int, val y: Int) : PdfSelectionBoundarySpec()
    data class CharIndex(val index: Int) : PdfSelectionBoundarySpec()
}

/** Local copy of framework [SelectionBoundary] getters. */
internal data class PdfFrameworkSelectionBoundary(
    val index: Int,
    val pointX: Int?,
    val pointY: Int?,
    val isRtl: Boolean,
)

/** Local copy of [PdfPageTextContent] text + bounds. */
internal data class PdfFrameworkTextContent(
    val text: String,
    val bounds: List<PdfRect>,
)

/** Local copy of framework [PageSelection] — never hold framework instances past the call. */
internal data class PdfFrameworkPageSelection(
    val page: Int,
    val start: PdfFrameworkSelectionBoundary,
    val stop: PdfFrameworkSelectionBoundary,
    val selectedTextContents: List<PdfFrameworkTextContent>,
) {
    val selectedText: String
        get() = selectedTextContents.joinToString(separator = "") { it.text }

    val bounds: List<PdfRect>
        get() = selectedTextContents.flatMap { it.bounds }
}

/**
 * Reflective binding for [PdfRenderer.Page.getTextContents] / [PdfRenderer.Page.searchText] /
 * [PdfRenderer.Page.selectContent] and flagged result model methods.
 * Public production signatures never mention those types.
 */
internal class ReflectivePdfFrameworkTextApi private constructor(
    private val getTextContents: Method,
    private val searchText: Method,
    private val contentGetText: Method,
    private val contentGetBounds: Method?,
    private val matchGetBounds: Method,
    private val matchGetTextStartIndex: Method,
    private val selectContent: Method?,
    private val boundaryPointCtor: Constructor<*>?,
    private val boundaryIndexCtor: Constructor<*>?,
    private val pageSelectionGetPage: Method?,
    private val pageSelectionGetStart: Method?,
    private val pageSelectionGetStop: Method?,
    private val pageSelectionGetSelectedTextContents: Method?,
    private val boundaryGetIndex: Method?,
    private val boundaryGetPoint: Method?,
    private val boundaryGetIsRtl: Method?,
) : PdfFrameworkTextApi {

    override val isAvailable: Boolean = true

    override val supportsSelection: Boolean =
        selectContent != null &&
            boundaryPointCtor != null &&
            boundaryIndexCtor != null &&
            pageSelectionGetPage != null &&
            pageSelectionGetStart != null &&
            pageSelectionGetStop != null &&
            pageSelectionGetSelectedTextContents != null &&
            boundaryGetIndex != null

    override fun hasTextOnPage(page: PdfRenderer.Page): Boolean = runCatching {
        pageText(page).isNotBlank()
    }.getOrDefault(false)

    override fun pageText(page: PdfRenderer.Page): String = runCatching {
        val contents = getTextContents.invoke(page) as? List<*> ?: return ""
        buildString {
            contents.forEach { item ->
                if (item == null) return@forEach
                val text = contentGetText.invoke(item) as? String ?: return@forEach
                append(text)
            }
        }
    }.getOrDefault("")

    override fun searchPage(page: PdfRenderer.Page, query: String): List<PdfFrameworkTextMatch> {
        if (query.isEmpty()) return emptyList()
        return runCatching {
            val matches = searchText.invoke(page, query) as? List<*> ?: return emptyList()
            matches.mapNotNull { match ->
                if (match == null) return@mapNotNull null
                val start = matchGetTextStartIndex.invoke(match) as? Int ?: return@mapNotNull null
                if (start < 0) return@mapNotNull null
                val boundsRaw = matchGetBounds.invoke(match) as? List<*> ?: return@mapNotNull null
                val bounds = boundsRaw.mapNotNull { rect ->
                    // Framework returns android.graphics.RectF; copy fields into pure PdfRect
                    // so production code never leaks API-36 model types and tests stay pure-JVM.
                    val r = rect as? android.graphics.RectF ?: return@mapNotNull null
                    PdfRect(r.left, r.top, r.right, r.bottom)
                }
                if (bounds.isEmpty()) return@mapNotNull null
                PdfFrameworkTextMatch(textStartIndex = start, bounds = bounds)
            }
        }.getOrDefault(emptyList())
    }

    override fun selectContent(
        page: PdfRenderer.Page,
        start: PdfSelectionBoundarySpec,
        stop: PdfSelectionBoundarySpec,
    ): PdfFrameworkPageSelection? {
        if (!supportsSelection) return null
        return runCatching {
            val startBoundary = createBoundary(start) ?: return null
            val stopBoundary = createBoundary(stop) ?: return null
            val raw = selectContent!!.invoke(page, startBoundary, stopBoundary) ?: return null
            convertPageSelection(raw)
        }.getOrNull()
    }

    private fun createBoundary(spec: PdfSelectionBoundarySpec): Any? = when (spec) {
        is PdfSelectionBoundarySpec.Point ->
            boundaryPointCtor?.newInstance(Point(spec.x, spec.y))
        is PdfSelectionBoundarySpec.CharIndex ->
            boundaryIndexCtor?.newInstance(spec.index)
    }

    private fun convertPageSelection(raw: Any): PdfFrameworkPageSelection? {
        val pageIndex = pageSelectionGetPage!!.invoke(raw) as? Int ?: return null
        val startRaw = pageSelectionGetStart!!.invoke(raw) ?: return null
        val stopRaw = pageSelectionGetStop!!.invoke(raw) ?: return null
        val contentsRaw = pageSelectionGetSelectedTextContents!!.invoke(raw) as? List<*>
            ?: return null
        val start = convertBoundary(startRaw) ?: return null
        val stop = convertBoundary(stopRaw) ?: return null
        val contents = contentsRaw.mapNotNull { item ->
            if (item == null) return@mapNotNull null
            convertTextContent(item)
        }
        return PdfFrameworkPageSelection(
            page = pageIndex,
            start = start,
            stop = stop,
            selectedTextContents = contents,
        )
    }

    private fun convertBoundary(raw: Any): PdfFrameworkSelectionBoundary? {
        val index = boundaryGetIndex?.invoke(raw) as? Int ?: return null
        val isRtl = boundaryGetIsRtl?.invoke(raw) as? Boolean ?: false
        val point = boundaryGetPoint?.invoke(raw) as? Point
        return PdfFrameworkSelectionBoundary(
            index = index,
            pointX = point?.x,
            pointY = point?.y,
            isRtl = isRtl,
        )
    }

    private fun convertTextContent(raw: Any): PdfFrameworkTextContent? {
        val text = contentGetText.invoke(raw) as? String ?: return null
        val bounds = if (contentGetBounds != null) {
            val boundsRaw = contentGetBounds.invoke(raw) as? List<*>
            boundsRaw?.mapNotNull { rect ->
                val r = rect as? android.graphics.RectF ?: return@mapNotNull null
                PdfRect(r.left, r.top, r.right, r.bottom)
            }.orEmpty()
        } else {
            emptyList()
        }
        return PdfFrameworkTextContent(text = text, bounds = bounds)
    }

    companion object {
        fun tryCreate(classLoader: ClassLoader): ReflectivePdfFrameworkTextApi? = runCatching {
            val pageClass = PdfRenderer.Page::class.java
            val getTextContents = pageClass.getMethod("getTextContents")
            val searchText = pageClass.getMethod("searchText", String::class.java)

            val textContentClass = Class.forName(
                "android.graphics.pdf.content.PdfPageTextContent",
                false,
                classLoader,
            )
            val matchClass = Class.forName(
                "android.graphics.pdf.models.PageMatchBounds",
                false,
                classLoader,
            )
            val contentGetText = textContentClass.getMethod("getText")
            val contentGetBounds = runCatching { textContentClass.getMethod("getBounds") }.getOrNull()
            val matchGetBounds = matchClass.getMethod("getBounds")
            val matchGetTextStartIndex = matchClass.getMethod("getTextStartIndex")

            // Selection binding is optional — search still works if these fail.
            val selectionBinding = runCatching {
                val boundaryClass = Class.forName(
                    "android.graphics.pdf.models.selection.SelectionBoundary",
                    false,
                    classLoader,
                )
                val pageSelectionClass = Class.forName(
                    "android.graphics.pdf.models.selection.PageSelection",
                    false,
                    classLoader,
                )
                val selectContent = pageClass.getMethod(
                    "selectContent",
                    boundaryClass,
                    boundaryClass,
                )
                SelectionBinding(
                    selectContent = selectContent,
                    boundaryPointCtor = boundaryClass.getConstructor(Point::class.java),
                    boundaryIndexCtor = boundaryClass.getConstructor(Int::class.javaPrimitiveType),
                    pageSelectionGetPage = pageSelectionClass.getMethod("getPage"),
                    pageSelectionGetStart = pageSelectionClass.getMethod("getStart"),
                    pageSelectionGetStop = pageSelectionClass.getMethod("getStop"),
                    pageSelectionGetSelectedTextContents =
                        pageSelectionClass.getMethod("getSelectedTextContents"),
                    boundaryGetIndex = boundaryClass.getMethod("getIndex"),
                    boundaryGetPoint = boundaryClass.getMethod("getPoint"),
                    boundaryGetIsRtl = boundaryClass.getMethod("getIsRtl"),
                )
            }.getOrNull()

            ReflectivePdfFrameworkTextApi(
                getTextContents = getTextContents,
                searchText = searchText,
                contentGetText = contentGetText,
                contentGetBounds = contentGetBounds,
                matchGetBounds = matchGetBounds,
                matchGetTextStartIndex = matchGetTextStartIndex,
                selectContent = selectionBinding?.selectContent,
                boundaryPointCtor = selectionBinding?.boundaryPointCtor,
                boundaryIndexCtor = selectionBinding?.boundaryIndexCtor,
                pageSelectionGetPage = selectionBinding?.pageSelectionGetPage,
                pageSelectionGetStart = selectionBinding?.pageSelectionGetStart,
                pageSelectionGetStop = selectionBinding?.pageSelectionGetStop,
                pageSelectionGetSelectedTextContents =
                    selectionBinding?.pageSelectionGetSelectedTextContents,
                boundaryGetIndex = selectionBinding?.boundaryGetIndex,
                boundaryGetPoint = selectionBinding?.boundaryGetPoint,
                boundaryGetIsRtl = selectionBinding?.boundaryGetIsRtl,
            )
        }.getOrNull()

        private data class SelectionBinding(
            val selectContent: Method,
            val boundaryPointCtor: Constructor<*>,
            val boundaryIndexCtor: Constructor<*>,
            val pageSelectionGetPage: Method,
            val pageSelectionGetStart: Method,
            val pageSelectionGetStop: Method,
            val pageSelectionGetSelectedTextContents: Method,
            val boundaryGetIndex: Method,
            val boundaryGetPoint: Method,
            val boundaryGetIsRtl: Method,
        )
    }
}
