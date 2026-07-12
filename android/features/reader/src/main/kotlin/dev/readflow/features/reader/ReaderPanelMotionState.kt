package dev.readflow.features.reader

internal class ReaderPanelMotionState {
    private var retainedPanel: ReaderPanel? = null

    fun contentFor(targetPanel: ReaderPanel?): ReaderPanel? =
        targetPanel.asBottomPanel() ?: retainedPanel

    fun commit(targetPanel: ReaderPanel?) {
        targetPanel.asBottomPanel()?.let { retainedPanel = it }
    }

    private fun ReaderPanel?.asBottomPanel(): ReaderPanel? =
        this?.takeUnless { it == ReaderPanel.TOC }
}
