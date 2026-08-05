package dev.readflow.epub

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderNode
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlin.jvm.functions.Function1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class EpubSlideRenderNodeRuntimeSmokeTest {

    @Test
    fun slideArtifactRenderNodeUsesTheRecordedViewportAsItsBounds() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val width = 37
        val height = 53
        val artifactClass = Class.forName("dev.readflow.render.epub.SlidePageArtifact")
        val companion = artifactClass.getDeclaredField("Companion").apply {
            isAccessible = true
        }.get(null)
        val recordMethod = companion.javaClass.declaredMethods.single { method ->
            method.name.startsWith("record") && method.parameterTypes.size == 3
        }.apply { isAccessible = true }
        val draw = Function1<Canvas, Unit> { canvas ->
            canvas.drawColor(Color.WHITE)
            Unit
        }
        val artifact = recordMethod.invoke(companion, width, height, draw)

        try {
            val record = checkNotNull(artifactClass.getDeclaredField("record").apply {
                isAccessible = true
            }.get(artifact)) {
                "API 29+ hardware recording must retain an artifact record"
            }
            assertTrue(
                "API 29+ SLIDE artifacts must use the RenderNode record path",
                record.javaClass.name.endsWith("Api29SlidePageRenderNodeRecord"),
            )
            val renderNode = record.javaClass.getDeclaredField("renderNode").apply {
                isAccessible = true
            }.get(record) as RenderNode

            assertEquals("RenderNode left must anchor at the artifact origin", 0, renderNode.left)
            assertEquals("RenderNode top must anchor at the artifact origin", 0, renderNode.top)
            assertEquals("RenderNode right must cover the recorded viewport", width, renderNode.right)
            assertEquals("RenderNode bottom must cover the recorded viewport", height, renderNode.bottom)
            assertEquals("RenderNode width must match the recording width", width, renderNode.width)
            assertEquals("RenderNode height must match the recording height", height, renderNode.height)
        } finally {
            artifactClass.declaredMethods.single { method ->
                method.name.startsWith("discard") && method.parameterTypes.isEmpty()
            }.apply { isAccessible = true }.invoke(artifact)
        }
    }
}
