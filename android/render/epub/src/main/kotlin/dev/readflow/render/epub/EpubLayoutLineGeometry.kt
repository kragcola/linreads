package dev.readflow.render.epub

import android.text.Layout

/** [LineGeometry] backed by a real android [Layout] (StaticLayout). */
internal class EpubLayoutLineGeometry(private val layout: Layout) : LineGeometry {
    override val lineCount: Int get() = layout.lineCount
    override fun getLineTop(line: Int): Int = layout.getLineTop(line.coerceIn(0, lineCount))
    override fun getLineBottom(line: Int): Int = layout.getLineBottom(line.coerceIn(0, lineCount - 1).coerceAtLeast(0))
    override fun getLineStart(line: Int): Int = layout.getLineStart(line.coerceIn(0, lineCount - 1).coerceAtLeast(0))
    override fun getLineEnd(line: Int): Int = layout.getLineEnd(line.coerceIn(0, lineCount - 1).coerceAtLeast(0))
    override fun getLineForVertical(y: Int): Int = layout.getLineForVertical(y)
    override fun getLineForOffset(offset: Int): Int = layout.getLineForOffset(offset.coerceAtLeast(0))
}
