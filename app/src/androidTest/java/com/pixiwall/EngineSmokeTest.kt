package com.pixiwall

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pixiwall.core.BUILTIN
import com.pixiwall.core.Settings
import com.pixiwall.engine.AnimationHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs the real engine (PixiJS + runtime.js + the built-in preset) in a real, attached WebView.
 * Needs a device or emulator: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class EngineSmokeTest {
    @get:Rule
    val rule = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

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

    private fun waitFor(webView: WebView, expression: String, expected: String) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (js(webView, expression) == expected) return
            Thread.sleep(200)
        }
        assertEquals(expected, js(webView, expression))
    }

    @Test
    fun presetRunsOnWebGlAndObeysThePauseResumeDestroyLifecycle() {
        lateinit var webView: WebView
        lateinit var host: AnimationHost
        rule.scenario.onActivity { activity ->
            webView = WebView(activity)
            activity.setContentView(FrameLayout(activity).apply { addView(webView) })
            host = AnimationHost(activity, webView) {}
            host.open()
            host.start(BUILTIN, Settings(fps = 60, gpu = true), isWallpaper = false)
        }

        waitFor(webView, "window.__host.status().state", "\"running\"")
        assertEquals("\"webgl\"", js(webView, "window.__host.status().renderer")) // WebGL really is in use

        val running = frames(webView)
        Thread.sleep(1000)
        assertTrue("frames advance while running", frames(webView) > running)

        instrumentation.runOnMainSync { host.pause() }
        Thread.sleep(300)
        val paused = frames(webView)
        Thread.sleep(1000)
        assertEquals("no frames while paused", paused, frames(webView))

        instrumentation.runOnMainSync { host.resume() }
        Thread.sleep(1000)
        assertTrue("frames advance after resume", frames(webView) > paused)

        instrumentation.runOnMainSync { host.destroy() } // must not crash; frees the GL context and the WebView
        Thread.sleep(1000)
    }
}
