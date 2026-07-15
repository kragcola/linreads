package dev.readflow.features.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderTypographyPanelTest {

    @Test
    fun `font size stepper moves one sp and clamps at both ends`() {
        assertEquals(19f, steppedReaderFontSize(currentSp = 18f, direction = 1), 0.001f)
        assertEquals(17f, steppedReaderFontSize(currentSp = 18f, direction = -1), 0.001f)
        assertEquals(12f, steppedReaderFontSize(currentSp = 12f, direction = -1), 0.001f)
        assertEquals(32f, steppedReaderFontSize(currentSp = 32f, direction = 1), 0.001f)
    }

    @Test
    fun `line spacing stepper moves one tenth without float drift`() {
        assertEquals(1.4f, steppedReaderLineSpacing(current = 1.3f, direction = 1), 0.001f)
        assertEquals(1.2f, steppedReaderLineSpacing(current = 1.3f, direction = -1), 0.001f)
        assertEquals(1.0f, steppedReaderLineSpacing(current = 1.0f, direction = -1), 0.001f)
        assertEquals(2.2f, steppedReaderLineSpacing(current = 2.2f, direction = 1), 0.001f)
    }

    @Test
    fun `preview line height responds to both typography values`() {
        assertEquals(23.4f, readerTypographyPreviewLineHeightSp(fontSizeSp = 18f, lineSpacing = 1.3f), 0.001f)
        assertEquals(44f, readerTypographyPreviewLineHeightSp(fontSizeSp = 20f, lineSpacing = 2.2f), 0.001f)
    }

    @Test
    fun `stepper accessibility descriptions include action control and current value`() {
        assertEquals(
            "减小字号，当前 18sp",
            readerTypographyStepDescription(label = "字号", value = "18sp", increase = false),
        )
        assertEquals(
            "增大行距，当前 1.3倍",
            readerTypographyStepDescription(label = "行距", value = "1.3倍", increase = true),
        )
    }
}
