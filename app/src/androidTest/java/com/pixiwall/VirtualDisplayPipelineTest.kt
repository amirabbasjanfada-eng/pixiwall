package com.pixiwall

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pixiwall.core.BUILTIN
import com.pixiwall.core.Settings
import com.pixiwall.engine.AnimationHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The exact pipeline the live wallpaper uses (virtual display -> Presentation -> WebView -> PixiJS),
 * driven from a non-Activity context like a Service, with an ImageReader standing in for the wallpaper surface.
 * It checks the things that matter for a wallpaper: WebGL works there, frames reach the surface, the output can be
 * detached and re-attached, the display can be resized and the whole thing can be released.
 * Needs a device or emulator: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext // application context, like a Service

    private fun js(webView: WebView, expression: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(expression) { value ->
                result = value ?: ""
                latch.countDown()
            }
        }
        assertTrue("JavaScript call timed out", latch.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun frames(webView: WebView) = js(webView, "window.__host.status().frames").toInt()

    private fun waitForRunning(webView: WebView) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (js(webView, "window.__host.status().state") == "\"running\"") return
            Thread.sleep(200)
        }
        assertEquals("\"running\"", js(webView, "window.__host.status().state"))
    }

    private fun assertFramesAdvance(webView: WebView, message: String) {
        val before = frames(webView)
        Thread.sleep(1200)
        assertTrue(message, frames(webView) > before)
    }

    @Test
    fun presetRunsInAPresentationOnAVirtualDisplay() {
        val images = AtomicInteger()
        val readerThread = HandlerThread("test-reader")
        readerThread.start()
        val reader = ImageReader.newInstance(1080, 1920, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.close() // always release, so the compositor never waits for us
            images.incrementAndGet()
        }, Handler(readerThread.looper))

        val displays = context.getSystemService(DisplayManager::class.java)
        var display: VirtualDisplay? = null
        var presentation: Presentation? = null
        var host: AnimationHost? = null
        var webView: WebView? = null

        fun buildPresentation(width: Int, height: Int) {
            instrumentation.runOnMainSync {
                val p = Presentation(context, display!!.display)
                val w = WebView(p.context)
                val h = AnimationHost(context, w) {}
                p.setContentView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                p.show()
                h.open()
                h.start(BUILTIN, Settings(), isWallpaper = true, surfacePixels = width.toLong() * height)
                presentation = p
                host = h
                webView = w
            }
        }

        fun teardownPresentation() {
            instrumentation.runOnMainSync {
                host?.destroy()
                presentation?.dismiss()
                host = null
                presentation = null
            }
        }

        try {
            instrumentation.runOnMainSync {
                display = displays.createVirtualDisplay(
                    "pixiwall-test", 1080, 1920, 420, reader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                )
            }
            buildPresentation(1080, 1920)

            waitForRunning(webView!!)
            assertEquals("WebGL is used inside the presentation", "\"webgl\"", js(webView!!, "window.__host.status().renderer"))
            assertFramesAdvance(webView!!, "the animation runs")
            assertTrue("frames reach the output surface", images.get() > 0)

            // Hidden: pause and turn the display's output off, then bring it back.
            instrumentation.runOnMainSync { host!!.pause(); display!!.setSurface(null) }
            Thread.sleep(500)
            instrumentation.runOnMainSync { display!!.setSurface(reader.surface); host!!.resume() }
            assertFramesAdvance(webView!!, "the animation runs again after the output was re-attached")
            val delivered = images.get()
            Thread.sleep(1000)
            assertTrue("images keep arriving after re-attaching", images.get() > delivered)

            // Resize: the Presentation is rebuilt, the same virtual display is reused.
            teardownPresentation()
            instrumentation.runOnMainSync { display!!.resize(720, 1280, 280) }
            buildPresentation(720, 1280)
            waitForRunning(webView!!)
            assertFramesAdvance(webView!!, "the animation runs after a resize")
        } finally {
            teardownPresentation()
            instrumentation.runOnMainSync { display?.release() }
            Thread.sleep(800)
            reader.close()
            readerThread.quitSafely()
        }
    }
}
