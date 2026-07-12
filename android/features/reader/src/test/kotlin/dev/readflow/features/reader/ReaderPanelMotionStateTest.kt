package dev.readflow.features.reader

import java.lang.reflect.Method
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderPanelMotionStateTest {
    @Test
    fun `font content remains visible while the panel exits or returns to toc`() {
        val state = newState()

        assertEquals(ReaderPanel.FONT, state.contentFor(ReaderPanel.FONT))

        state.commit(ReaderPanel.FONT)

        assertEquals(ReaderPanel.FONT, state.contentFor(null))
        assertEquals(ReaderPanel.FONT, state.contentFor(ReaderPanel.TOC))
    }

    @Test
    fun `theme content switches immediately and remains visible while exiting`() {
        val state = newState()

        assertEquals(ReaderPanel.THEME, state.contentFor(ReaderPanel.THEME))

        state.commit(ReaderPanel.THEME)

        assertEquals(ReaderPanel.THEME, state.contentFor(null))
    }

    private fun newState(): Any {
        val type = try {
            Class.forName("dev.readflow.features.reader.ReaderPanelMotionState")
        } catch (exception: ClassNotFoundException) {
            throw AssertionError(
                "Expected dev.readflow.features.reader.ReaderPanelMotionState to exist",
                exception,
            )
        }

        return try {
            type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        } catch (exception: ReflectiveOperationException) {
            throw AssertionError("Expected ReaderPanelMotionState to have a no-arg constructor", exception)
        }
    }

    private fun Any.contentFor(target: ReaderPanel?): ReaderPanel? =
        panelMethod("contentFor").invoke(this, target) as ReaderPanel?

    private fun Any.commit(target: ReaderPanel?) {
        panelMethod("commit").invoke(this, target)
    }

    private fun Any.panelMethod(baseName: String): Method =
        javaClass.declaredMethods
            .singleOrNull { method ->
                method.name.startsWith(baseName) && method.parameterCount == 1
            }
            ?.apply { isAccessible = true }
            ?: throw AssertionError(
                "Expected ${javaClass.name} to declare one single-argument method starting with $baseName",
            )
}
