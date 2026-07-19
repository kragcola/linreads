package dev.readflow.f1

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class F1FrameGateSupportTest {

    // region GfxInfoParser

    @Test
    fun `gfx parser extracts total janky p90 and p95`() {
        val metrics = GfxInfoParser.parse(
            """
            Applications Graphics Acceleration Info:
            Total frames rendered: 42
            Janky frames: 5 (11.90%)
            50th percentile: 8ms
            90th percentile: 18ms
            95th percentile: 24ms
            99th percentile: 40ms
            """.trimIndent(),
        )
        assertEquals(42, metrics.totalFrames)
        assertEquals(5, metrics.jankyFrames)
        assertEquals(18, metrics.p90Ms)
        assertEquals(24, metrics.p95Ms)
    }

    @Test
    fun `gfx parser leaves missing lines null`() {
        val metrics = GfxInfoParser.parse("no metrics here\n")
        assertNull(metrics.totalFrames)
        assertNull(metrics.jankyFrames)
        assertNull(metrics.p90Ms)
        assertNull(metrics.p95Ms)
    }

    // endregion

    // region F1FrameGate

    @Test
    fun `hard gate fails when metrics are missing`() {
        val result = F1FrameGate.evaluate(
            GfxInfoMetrics(totalFrames = null, jankyFrames = null, p90Ms = null, p95Ms = null),
        )
        assertFalse(result.pass)
        assertTrue(result.reasons.any { it.startsWith("missing_") })
    }

    @Test
    fun `hard gate fails when total frames below minimum`() {
        val result = F1FrameGate.evaluate(
            GfxInfoMetrics(totalFrames = 3, jankyFrames = 0, p90Ms = 10, p95Ms = 12),
        )
        assertFalse(result.pass)
        assertTrue(result.reasons.any { it.startsWith("total_frames_below_min") })
    }

    @Test
    fun `hard gate fails when p95 exceeds default`() {
        val result = F1FrameGate.evaluate(
            GfxInfoMetrics(totalFrames = 20, jankyFrames = 1, p90Ms = 20, p95Ms = 40),
        )
        assertFalse(result.pass)
        assertTrue(result.reasons.any { it.startsWith("p95_above_max") })
    }

    @Test
    fun `hard gate fails when janky ratio exceeds default`() {
        val result = F1FrameGate.evaluate(
            GfxInfoMetrics(totalFrames = 20, jankyFrames = 8, p90Ms = 12, p95Ms = 16),
        )
        assertFalse(result.pass)
        assertTrue(result.reasons.any { it.startsWith("janky_ratio_above_max") })
        assertEquals(0.4, result.jankyRatio!!, 1e-9)
    }

    @Test
    fun `hard gate passes locked defaults`() {
        val result = F1FrameGate.evaluate(
            GfxInfoMetrics(totalFrames = 16, jankyFrames = 2, p90Ms = 18, p95Ms = 28),
        )
        assertTrue(result.pass)
        assertTrue(result.reasons.isEmpty())
        assertEquals(F1FrameThresholds.DEFAULT_MAX_P95_MS, 32)
        assertEquals(F1FrameThresholds.DEFAULT_MAX_JANKY_RATIO, 0.20, 1e-9)
        assertEquals(F1FrameThresholds.DEFAULT_MIN_TOTAL_FRAMES, 8)
    }

    @Test
    fun `thresholds reject non-positive max p95`() {
        assertThrows(IllegalArgumentException::class.java) {
            F1FrameThresholds(maxP95Ms = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            F1FrameThresholds(maxP95Ms = -1)
        }
    }

    @Test
    fun `thresholds reject non-finite or out-of-range janky ratio`() {
        assertThrows(IllegalArgumentException::class.java) {
            F1FrameThresholds(maxJankyRatio = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            F1FrameThresholds(maxJankyRatio = Double.POSITIVE_INFINITY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            F1FrameThresholds(maxJankyRatio = -0.01)
        }
        assertThrows(IllegalArgumentException::class.java) {
            F1FrameThresholds(maxJankyRatio = 1.01)
        }
    }

    @Test
    fun `thresholds reject non-positive min frames`() {
        assertThrows(IllegalArgumentException::class.java) {
            F1FrameThresholds(minTotalFrames = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            F1FrameThresholds(minTotalFrames = -3)
        }
    }

    @Test
    fun `thresholds accept valid explicit overrides at boundary`() {
        val thresholds = F1FrameThresholds(
            maxP95Ms = 1,
            maxJankyRatio = 0.0,
            minTotalFrames = 1,
        )
        assertEquals(1, thresholds.maxP95Ms)
        assertEquals(0.0, thresholds.maxJankyRatio, 0.0)
        assertEquals(1, thresholds.minTotalFrames)
        val upper = F1FrameThresholds(maxJankyRatio = 1.0)
        assertEquals(1.0, upper.maxJankyRatio, 0.0)
    }

    @Test
    fun `instrumentation override parsing fails fast on invalid values`() {
        assertThrows(IllegalArgumentException::class.java) {
            F1FrameThresholds.fromInstrumentationArgs(
                maxP95Raw = "0",
                maxJankyRaw = null,
                minFramesRaw = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            F1FrameThresholds.fromInstrumentationArgs(
                maxP95Raw = null,
                maxJankyRaw = "NaN",
                minFramesRaw = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            F1FrameThresholds.fromInstrumentationArgs(
                maxP95Raw = null,
                maxJankyRaw = "Infinity",
                minFramesRaw = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            F1FrameThresholds.fromInstrumentationArgs(
                maxP95Raw = null,
                maxJankyRaw = null,
                minFramesRaw = "-1",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            F1FrameThresholds.fromInstrumentationArgs(
                maxP95Raw = "not-a-number",
                maxJankyRaw = null,
                minFramesRaw = null,
            )
        }
    }

    @Test
    fun `instrumentation override parsing records valid explicit overrides`() {
        val thresholds = F1FrameThresholds.fromInstrumentationArgs(
            maxP95Raw = "40",
            maxJankyRaw = "0.35",
            minFramesRaw = "12",
        )
        assertEquals(40, thresholds.maxP95Ms)
        assertEquals(0.35, thresholds.maxJankyRatio, 1e-9)
        assertEquals(12, thresholds.minTotalFrames)
        val defaults = F1FrameThresholds.fromInstrumentationArgs(null, null, null)
        assertEquals(F1FrameThresholds.DEFAULT_MAX_P95_MS, defaults.maxP95Ms)
        assertEquals(F1FrameThresholds.DEFAULT_MAX_JANKY_RATIO, defaults.maxJankyRatio, 1e-9)
        assertEquals(F1FrameThresholds.DEFAULT_MIN_TOTAL_FRAMES, defaults.minTotalFrames)
    }

    // endregion

    // region Fixture ordering

    @Test
    fun `fixture builds unique package-local heading then image order`() {
        val fixture = F1MixedHeadingImageFixture.build()
        val failures = F1MixedHeadingImageFixture.validateOrdering(fixture)
        assertTrue(failures.isEmpty(), "ordering failures: $failures")
        assertTrue(fixture.candidates.size >= F1MixedHeadingImageFixture.MIN_CANDIDATES)
        fixture.candidates.forEach { candidate ->
            val headingPos = fixture.chapterXhtml.indexOf(candidate.headingMarker)
            val imagePos = fixture.chapterXhtml.indexOf(candidate.imageHref)
            assertTrue(headingPos >= 0)
            assertTrue(imagePos > headingPos)
            assertFalse(candidate.imageHref.startsWith("data:", ignoreCase = true))
        }
    }

    // endregion

    // region Boundary selector

    @Test
    fun `selector rejects non-adjacent image page`() {
        val selection = F1BoundarySelector.select(
            listOf(
                F1BoundaryCandidate(
                    candidateIndex = 0,
                    headingPage = 2,
                    imagePage = 4,
                    cropBandTopPx = 100,
                    cropBandBottomPx = 200,
                    viewportHeightPx = 800,
                    imageLineHeightPx = 400,
                ),
            ),
        )
        assertNull(selection)
    }

    @Test
    fun `selector rejects one-pixel crop band under configured minimum`() {
        val selection = F1BoundarySelector.select(
            listOf(
                F1BoundaryCandidate(
                    candidateIndex = 0,
                    headingPage = 1,
                    imagePage = 2,
                    cropBandTopPx = 100,
                    cropBandBottomPx = 101,
                    viewportHeightPx = 800,
                    imageLineHeightPx = 400,
                ),
            ),
            minBandHeightPx = 24,
        )
        assertNull(selection, "1px band must not pass a configured 24px minimum")
    }

    @Test
    fun `selector accepts valid band meeting configured minimum`() {
        val selection = F1BoundarySelector.select(
            listOf(
                F1BoundaryCandidate(
                    candidateIndex = 0,
                    headingPage = 1,
                    imagePage = 2,
                    cropBandTopPx = 100,
                    cropBandBottomPx = 124,
                    viewportHeightPx = 800,
                    imageLineHeightPx = 400,
                ),
            ),
            minBandHeightPx = 24,
        )
        assertNotNull(selection)
        assertEquals(24, selection!!.bandHeightPx)
        assertEquals(1, selection.headingPage)
        assertEquals(2, selection.imagePage)
    }

    @Test
    fun `selector rejects empty or out-of-viewport crop band`() {
        val empty = F1BoundarySelector.select(
            listOf(
                F1BoundaryCandidate(
                    candidateIndex = 0,
                    headingPage = 1,
                    imagePage = 2,
                    cropBandTopPx = 100,
                    cropBandBottomPx = 100,
                    viewportHeightPx = 800,
                    imageLineHeightPx = 400,
                ),
            ),
        )
        assertNull(empty)

        val outOfViewport = F1BoundarySelector.select(
            listOf(
                F1BoundaryCandidate(
                    candidateIndex = 0,
                    headingPage = 1,
                    imagePage = 2,
                    cropBandTopPx = -10,
                    cropBandBottomPx = 50,
                    viewportHeightPx = 800,
                    imageLineHeightPx = 400,
                ),
            ),
        )
        assertNull(outOfViewport)

        val bandTallerThanImage = F1BoundarySelector.select(
            listOf(
                F1BoundaryCandidate(
                    candidateIndex = 0,
                    headingPage = 1,
                    imagePage = 2,
                    cropBandTopPx = 100,
                    cropBandBottomPx = 500,
                    viewportHeightPx = 800,
                    imageLineHeightPx = 200,
                ),
            ),
        )
        assertNull(bandTallerThanImage)
    }

    @Test
    fun `selector picks earliest valid heading page then largest band then lowest index`() {
        val selection = F1BoundarySelector.select(
            listOf(
                // Valid but later heading page — should lose to earlier page.
                F1BoundaryCandidate(
                    candidateIndex = 0,
                    headingPage = 5,
                    imagePage = 6,
                    cropBandTopPx = 100,
                    cropBandBottomPx = 300,
                    viewportHeightPx = 800,
                    imageLineHeightPx = 500,
                ),
                // Invalid adjacency.
                F1BoundaryCandidate(
                    candidateIndex = 1,
                    headingPage = 2,
                    imagePage = 4,
                    cropBandTopPx = 100,
                    cropBandBottomPx = 400,
                    viewportHeightPx = 800,
                    imageLineHeightPx = 500,
                ),
                // Earliest valid heading page, smaller band.
                F1BoundaryCandidate(
                    candidateIndex = 2,
                    headingPage = 3,
                    imagePage = 4,
                    cropBandTopPx = 200,
                    cropBandBottomPx = 260,
                    viewportHeightPx = 800,
                    imageLineHeightPx = 500,
                ),
                // Same earliest heading page, larger band — wins.
                F1BoundaryCandidate(
                    candidateIndex = 3,
                    headingPage = 3,
                    imagePage = 4,
                    cropBandTopPx = 100,
                    cropBandBottomPx = 280,
                    viewportHeightPx = 800,
                    imageLineHeightPx = 500,
                ),
                // Same page + same band height, higher index — loses on index.
                F1BoundaryCandidate(
                    candidateIndex = 4,
                    headingPage = 3,
                    imagePage = 4,
                    cropBandTopPx = 100,
                    cropBandBottomPx = 280,
                    viewportHeightPx = 800,
                    imageLineHeightPx = 500,
                ),
            ),
        )
        assertNotNull(selection, "expected deterministic selection among valid candidates")
        val chosen = checkNotNull(selection)
        assertEquals(3, chosen.candidateIndex)
        assertEquals(3, chosen.headingPage)
        assertEquals(4, chosen.imagePage)
        assertEquals(180, chosen.bandHeightPx)
        assertEquals(100, chosen.cropBandTopPx)
        assertEquals(280, chosen.cropBandBottomPx)
    }

    @Test
    fun `selector returns null when no candidate is valid`() {
        assertNull(F1BoundarySelector.select(emptyList()))
        assertNull(
            F1BoundarySelector.select(
                listOf(
                    F1BoundaryCandidate(
                        candidateIndex = 0,
                        headingPage = 0,
                        imagePage = 0,
                        cropBandTopPx = 0,
                        cropBandBottomPx = 10,
                        viewportHeightPx = 100,
                        imageLineHeightPx = 50,
                    ),
                ),
            ),
        )
    }

    // endregion
}
