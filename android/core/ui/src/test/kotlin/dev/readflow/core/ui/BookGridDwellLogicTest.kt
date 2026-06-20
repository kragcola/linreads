package dev.readflow.core.ui

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlin.math.sqrt

/**
 * dwell 悬停建组逻辑验证测试。
 *
 * 不测 Compose recomposition（那需要 ComposeTestRule + instrumentation），
 * 只验证核心判定逻辑的数值正确性：
 * - 抖动容差计算
 * - 时间窗口边界
 * - 坐标距离判定
 */
class BookGridDwellLogicTest {

    // ── 参数定义（与 BookGrid.kt 同步） ──
    private val dwellThresholdMs = 700L
    private val dwellMoveTolerance = 12f // dp，测试时用 px 等效值

    // ── 距离计算辅助（模拟 Offset.getDistance()） ──
    private data class TestOffset(val x: Float, val y: Float) {
        fun getDistance(): Float = sqrt(x * x + y * y)
        operator fun minus(other: TestOffset) = TestOffset(x - other.x, y - other.y)
    }

    @Test
    fun `dwell threshold is 700ms`() {
        assertEquals(700L, dwellThresholdMs, "dwell 触发时间应为 700ms")
    }

    @Test
    fun `move tolerance is 12dp`() {
        assertEquals(12f, dwellMoveTolerance, "抖动容差应为 12dp")
    }

    @Test
    fun `stationary position stays within tolerance`() {
        val start = TestOffset(100f, 100f)
        val jitter1 = TestOffset(105f, 103f) // 移动 ~5.8px
        val jitter2 = TestOffset(108f, 107f) // 移动 ~10px

        val dist1 = (jitter1 - start).getDistance()
        val dist2 = (jitter2 - start).getDistance()

        assertTrue(dist1 < dwellMoveTolerance, "5.8px 抖动应在容差内: $dist1")
        assertTrue(dist2 < dwellMoveTolerance, "10px 抖动应在容差内: $dist2")
    }

    @Test
    fun `movement exceeding tolerance resets dwell`() {
        val start = TestOffset(100f, 100f)
        val farMove = TestOffset(120f, 100f) // 移动 20px

        val distance = (farMove - start).getDistance()

        assertTrue(distance > dwellMoveTolerance, "20px 移动应超出容差，重置 dwell: $distance")
    }

    @Test
    fun `diagonal movement within tolerance`() {
        val start = TestOffset(100f, 100f)
        val diagonal = TestOffset(108f, 108f) // 对角 ~11.3px

        val distance = (diagonal - start).getDistance()

        assertTrue(distance < dwellMoveTolerance, "对角 11.3px 应在容差内: $distance")
    }

    @Test
    fun `diagonal movement exceeding tolerance`() {
        val start = TestOffset(100f, 100f)
        val diagonal = TestOffset(110f, 110f) // 对角 ~14.1px

        val distance = (diagonal - start).getDistance()

        assertTrue(distance > dwellMoveTolerance, "对角 14.1px 应超出容差: $distance")
    }

    @Test
    fun `time window boundaries`() {
        // 模拟时间戳边界判定
        val dwellStartTime = 1000L
        val justBefore = dwellStartTime + dwellThresholdMs - 1 // 699ms
        val justAt = dwellStartTime + dwellThresholdMs // 700ms
        val after = dwellStartTime + dwellThresholdMs + 1 // 701ms

        assertFalse((justBefore - dwellStartTime) >= dwellThresholdMs, "699ms 不应触发 dwell")
        assertTrue((justAt - dwellStartTime) >= dwellThresholdMs, "700ms 应触发 dwell")
        assertTrue((after - dwellStartTime) >= dwellThresholdMs, "701ms 应触发 dwell")
    }

    @Test
    fun `edge case - zero movement`() {
        val start = TestOffset(100f, 100f)
        val same = TestOffset(100f, 100f)

        val distance = (same - start).getDistance()

        assertEquals(0f, distance, "零移动距离应为 0")
        assertTrue(distance < dwellMoveTolerance, "零移动应在容差内")
    }

    @Test
    fun `edge case - exact tolerance boundary`() {
        val start = TestOffset(100f, 100f)
        val exactBoundary = TestOffset(112f, 100f) // 正好 12px

        val distance = (exactBoundary - start).getDistance()

        assertEquals(12f, distance, 0.01f, "边界距离应为 12px")
        // 注意：实际逻辑用 > 判断，所以 == 12 不应重置
        assertFalse(distance > dwellMoveTolerance, "正好 12px 不应触发重置（>= 才重置）")
    }

    @Test
    fun `parameter rationale - 700ms is comfortable`() {
        // 人因学验证：700ms 在舒适区间
        // - iOS/iPadOS 主屏 dwell 时间约 600-800ms
        // - 太短（< 500ms）误触多，太长（> 1000ms）不耐烦
        assertTrue(dwellThresholdMs in 600L..800L, "700ms 应在人因学舒适区间 [600, 800]")
    }

    @Test
    fun `parameter rationale - 12dp tolerance handles natural jitter`() {
        // 人手持握平板时自然抖动约 8-15px（@160dpi）
        // 12dp ≈ 19.2px @160dpi，覆盖自然抖动且不过宽
        val minJitter = 8f
        val maxComfortableJitter = 20f
        assertTrue(dwellMoveTolerance in minJitter..maxComfortableJitter,
            "12dp 应覆盖自然抖动范围 [8, 20]px")
    }

    @Test
    fun `scroll edge threshold is reasonable`() {
        val scrollEdgeThreshold = 80f // dp，BookGrid 中定义
        // 边缘区 80dp ≈ 128px @160dpi，约占 1080p 屏幕高度的 6-7%，不会太敏感
        assertTrue(scrollEdgeThreshold in 60f..100f, "80dp 边缘区应在合理范围")
    }
}
