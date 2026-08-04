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
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

        assertEquals("dev.readflow", targetContext.packageName)
        Class.forName("kotlin.jvm.internal.Intrinsics")
        Class.forName("kotlin.KotlinVersion")
        Class.forName("kotlin.collections.CollectionsKt")
    }
}
