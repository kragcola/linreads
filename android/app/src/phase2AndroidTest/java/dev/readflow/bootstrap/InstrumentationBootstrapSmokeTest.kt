package dev.readflow.bootstrap

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class InstrumentationBootstrapSmokeTest {

    @Test
    fun targetPackageNameIsReadflow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val targetClassLoader = targetContext.classLoader
        val testClassLoader = instrumentation.context.classLoader

        assertEquals("dev.readflow", targetContext.packageName)
        Class.forName("kotlin.jvm.internal.Intrinsics", true, targetClassLoader)
        Class.forName("kotlin.KotlinVersion", true, targetClassLoader)
        Class.forName("kotlin.collections.CollectionsKt", true, targetClassLoader)
        Class.forName("androidx.tracing.Trace", true, testClassLoader)
    }
}
