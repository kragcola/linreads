package dev.readflow.render.epub

internal data class EpubPara(
    val spineIndex: Int,
    val text: String,
    val spineCharStart: Int = 0,
    val spineCharEnd: Int = text.length,
    val documentCharStart: Int = 0,
    val documentCharEnd: Int = text.length,
)
