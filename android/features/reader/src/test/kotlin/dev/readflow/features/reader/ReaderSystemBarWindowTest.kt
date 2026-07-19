package dev.readflow.features.reader

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Window-level regression for reader edge-to-edge system bars.
 *
 * Source-string contracts cannot prove focus return or ViewTreeObserver ownership after detach.
 * These tests assert actual [android.view.Window] state on API 29 (Q contrast flags), 31, and 35.
 *
 * Restore-on-leave lives inside private Compose [DisposableEffect] and is not invoked here without
 * a Compose instrumentation host; see [ReaderScreenPaperInsetsVisualContractTest] for the
 * onDispose restore *source* contract. Gap: direct behavioral restore is not covered in this class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 31, 35])
class ReaderSystemBarWindowTest {

    @Test
    fun `applyReaderSystemBars keeps bars transparent and contrast off with palette-driven icons`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val window = activity.window
        val view = FrameLayout(activity)
        activity.setContentView(view)

        @Suppress("DEPRECATION")
        window.statusBarColor = Color.BLACK
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = true
            window.setStatusBarContrastEnforced(true)
        }

        applyReaderSystemBars(window, view, lightBars = true)
        assertReaderEdgeToEdge(window, view, expectedLightBars = true)
        assertTrue(
            "class @Config must execute on API 29, 31, or 35 (got ${Build.VERSION.SDK_INT})",
            Build.VERSION.SDK_INT in setOf(29, 31, 35),
        )

        applyReaderSystemBars(window, view, lightBars = false)
        assertReaderEdgeToEdge(window, view, expectedLightBars = false)
    }

    @Test
    fun `focus return re-applies transparent bars after OEM restores solid status strip`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val window = activity.window
        val view = FrameLayout(activity)
        activity.setContentView(view)

        applyReaderSystemBars(window, view, lightBars = true)
        assertReaderEdgeToEdge(window, view, expectedLightBars = true)

        val session = ReaderSystemBarReapplySession(window, view) { true }
        val uninstall = session.install()

        session.onWindowFocusChanged(false)
        mutateWindowToSolidStatusStrip(window)
        assertSolidStatusStrip(window)

        session.onWindowFocusChanged(true)
        assertReaderEdgeToEdge(window, view, expectedLightBars = true)

        uninstall()
    }

    @Test
    fun `attach re-applies transparent bars when host view re-attaches after OEM rewrite`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val window = activity.window
        val host = FrameLayout(activity)
        val view = FrameLayout(activity)
        activity.setContentView(host)
        host.addView(view)

        applyReaderSystemBars(window, view, lightBars = false)
        val uninstall = installReaderSystemBarReapply(window, view) { false }

        mutateWindowToSolidStatusStrip(window)
        assertSolidStatusStrip(window)

        host.removeView(view)
        mutateWindowToSolidStatusStrip(window)
        host.addView(view)

        assertReaderEdgeToEdge(window, view, expectedLightBars = false)

        uninstall()
    }

    /**
     * Observer ownership: remove must target the VTO that received [addOnWindowFocusChangeListener].
     * Disposing while detached makes [View.getViewTreeObserver] return a different (floating)
     * instance — removing only from that current VTO leaks the listener on the window VTO, so
     * focus after reattach still re-applies. Correct code unbinds the registered observer and
     * re-binds only while attached/install active.
     */
    @Test
    fun `dispose while detached unbinds focus so reattach focus does not re-apply`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val window = activity.window
        val host = FrameLayout(activity)
        val view = FrameLayout(activity)
        activity.setContentView(host)
        host.addView(view)

        applyReaderSystemBars(window, view, lightBars = true)
        val uninstall = installReaderSystemBarReapply(window, view) { true }

        // Detach first so dispose cannot cheat by removing via the still-attached current VTO.
        host.removeView(view)
        uninstall()

        host.addView(view)
        mutateWindowToSolidStatusStrip(window)
        assertSolidStatusStrip(window)

        // Window VTO focus after dispose+reattach must not re-apply (no leaked listener).
        dispatchWindowFocus(view.viewTreeObserver, hasFocus = true)
        assertSolidStatusStrip(window)
    }

    @Test
    fun `reattach re-registers focus on current observer and re-applies after OEM rewrite`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val window = activity.window
        val host = FrameLayout(activity)
        val view = FrameLayout(activity)
        activity.setContentView(host)
        host.addView(view)

        applyReaderSystemBars(window, view, lightBars = true)
        val uninstall = installReaderSystemBarReapply(window, view) { true }

        host.removeView(view)
        mutateWindowToSolidStatusStrip(window)
        host.addView(view)
        assertReaderEdgeToEdge(window, view, expectedLightBars = true)

        mutateWindowToSolidStatusStrip(window)
        assertSolidStatusStrip(window)
        dispatchWindowFocus(view.viewTreeObserver, hasFocus = true)
        assertReaderEdgeToEdge(window, view, expectedLightBars = true)

        uninstall()
    }

    @Test
    fun `focus re-apply uses current lightBars lambda after palette flip`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val window = activity.window
        val view = FrameLayout(activity)
        activity.setContentView(view)

        var lightBars = true
        applyReaderSystemBars(window, view, lightBars = lightBars)
        val session = ReaderSystemBarReapplySession(window, view) { lightBars }
        val uninstall = session.install()
        assertReaderEdgeToEdge(window, view, expectedLightBars = true)

        lightBars = false
        mutateWindowToSolidStatusStrip(window)
        session.onWindowFocusChanged(true)
        assertReaderEdgeToEdge(window, view, expectedLightBars = false)

        lightBars = true
        mutateWindowToSolidStatusStrip(window)
        session.onWindowFocusChanged(true)
        assertReaderEdgeToEdge(window, view, expectedLightBars = true)

        uninstall()
    }

    /**
     * Leave/restore *path* without Compose: disposer must stop re-apply ownership, then host code
     * can restore prior window chrome (colors/contrast/icons) the same way DisposableEffect does.
     * This does not invoke the private composable; it proves the public session disposer and the
     * window APIs that production restore uses remain consistent across SDKs.
     */
    @Test
    fun `disposer stops reapply then host can restore prior bar chrome`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val window = activity.window
        val view = FrameLayout(activity)
        activity.setContentView(view)

        @Suppress("DEPRECATION")
        window.statusBarColor = Color.RED
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.BLUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = true
            window.setStatusBarContrastEnforced(true)
        }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        @Suppress("DEPRECATION")
        val previousStatusBarColor = window.statusBarColor
        @Suppress("DEPRECATION")
        val previousNavigationBarColor = window.navigationBarColor
        val previousNavContrast =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced else false
        val previousStatusContrast =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isStatusBarContrastEnforced else false
        val previousStatusAppearance = controller.isAppearanceLightStatusBars
        val previousNavAppearance = controller.isAppearanceLightNavigationBars

        applyReaderSystemBars(window, view, lightBars = true)
        val uninstall = installReaderSystemBarReapply(window, view) { true }
        assertReaderEdgeToEdge(window, view, expectedLightBars = true)

        uninstall()

        // Mirror ReaderSystemBarAppearance onDispose restore (without Compose).
        @Suppress("DEPRECATION")
        window.statusBarColor = previousStatusBarColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = previousNavigationBarColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = previousNavContrast
            window.setStatusBarContrastEnforced(previousStatusContrast)
        }
        controller.isAppearanceLightStatusBars = previousStatusAppearance
        controller.isAppearanceLightNavigationBars = previousNavAppearance

        @Suppress("DEPRECATION")
        assertEquals(Color.RED, window.statusBarColor)
        @Suppress("DEPRECATION")
        assertEquals(Color.BLUE, window.navigationBarColor)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertTrue(window.isNavigationBarContrastEnforced)
            assertTrue(window.isStatusBarContrastEnforced)
        }
        assertFalse(controller.isAppearanceLightStatusBars)
        assertFalse(controller.isAppearanceLightNavigationBars)

        // Disposer must have dropped focus ownership: OEM rewrite + focus must not re-apply reader chrome.
        mutateWindowToSolidStatusStrip(window)
        dispatchWindowFocus(view.viewTreeObserver, hasFocus = true)
        assertSolidStatusStrip(window)
    }

    private fun dispatchWindowFocus(observer: ViewTreeObserver, hasFocus: Boolean) {
        // Package-private on platform; SDK stubs omit it. Prefer reflective invoke for real VTO
        // dispatch; fall back only if the runtime jar truly has no method (should not happen on
        // Robolectric instrumented android-all).
        val method = ViewTreeObserver::class.java.methods.firstOrNull {
            it.name == "dispatchOnWindowFocusChange" &&
                it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        } ?: ViewTreeObserver::class.java.declaredMethods.firstOrNull {
            it.name == "dispatchOnWindowFocusChange" &&
                it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        }
        requireNotNull(method) {
            "ViewTreeObserver.dispatchOnWindowFocusChange(boolean) missing on test runtime"
        }
        method.isAccessible = true
        method.invoke(observer, hasFocus)
    }

    private fun assertReaderEdgeToEdge(
        window: android.view.Window,
        view: View,
        expectedLightBars: Boolean,
    ) {
        @Suppress("DEPRECATION")
        assertEquals(
            "statusBarColor must stay transparent so paper shows under system icons",
            Color.TRANSPARENT,
            window.statusBarColor,
        )
        @Suppress("DEPRECATION")
        assertEquals(
            "navigationBarColor must stay transparent on the reader",
            Color.TRANSPARENT,
            window.navigationBarColor,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertFalse(
                "status-bar contrast enforcement paints a solid foreign band over paper",
                window.isStatusBarContrastEnforced,
            )
            assertFalse(
                "navigation-bar contrast enforcement must stay off while reading",
                window.isNavigationBarContrastEnforced,
            )
        }
        val controller = WindowCompat.getInsetsController(window, view)
        assertEquals(expectedLightBars, controller.isAppearanceLightStatusBars)
        assertEquals(expectedLightBars, controller.isAppearanceLightNavigationBars)
    }

    private fun mutateWindowToSolidStatusStrip(window: android.view.Window) {
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.BLACK
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.DKGRAY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(true)
            window.isNavigationBarContrastEnforced = true
        }
    }

    private fun assertSolidStatusStrip(window: android.view.Window) {
        @Suppress("DEPRECATION")
        assertEquals(Color.BLACK, window.statusBarColor)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertTrue(window.isStatusBarContrastEnforced)
        }
    }
}
