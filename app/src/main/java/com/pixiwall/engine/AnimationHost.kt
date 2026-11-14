package com.pixiwall.engine

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pixiwall.Diag
import com.pixiwall.core.Animation
import com.pixiwall.core.BUILTIN_ID
import com.pixiwall.core.Governor
import com.pixiwall.core.Settings
import org.json.JSONObject
import java.util.Locale

/**
 * Runs one animation inside one WebView.
 * The in-app preview and the live wallpaper both use this class, so both execute the same engine
 * page (assets/engine/index.html + runtime.js + PixiJS). Only the place the WebView lives differs.
 * All methods must be called on the main thread.
 */
class AnimationHost(
    context: Context,
    private val webView: WebView,
    private val onRendererGone: () -> Unit,
) {
    private val appContext: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val server = AssetServer(appContext)

    private var pageReady = false
    private var attached = false
    private var destroyed = false
    private var finished = false
    private var paused = false
    private var pendingConfig: String? = null
    private var openedAt = 0L

    /** Shown in log lines so that the preview and each wallpaper engine can be told apart. */
    var label: String = "host"

    /** Whether touches are forwarded to the animation (Settings > Touch interaction). */
    @Volatile
    var touchEnabled: Boolean = true

    init {
        configure()
    }

    /** Starts loading the engine page. Call once. */
    fun open() {
        if (destroyed) return
        openedAt = SystemClock.elapsedRealtime()
        Diag.i("$label open: loading the engine page")
        webView.loadUrl(INDEX_URL)
    }

    /**
     * Starts (or replaces) the running animation. Safe to call before the page has finished loading.
     * [isPreview] is true for the system's wallpaper-picker preview; [surfacePixels] is the pixel count
     * of the wallpaper surface. They only feed the resource [Governor].
     */
    fun start(
        animation: Animation,
        settings: Settings,
        isWallpaper: Boolean,
        isPreview: Boolean = false,
        surfacePixels: Long = 0L,
    ) {
        if (destroyed) return
        val src = if (animation.id == BUILTIN_ID) {
            "https://$HOST/builtin/preset.js"
        } else {
            "https://$HOST/anim/${animation.id}.js"
        }
        val fps = Governor.fps(settings.fps, isWallpaper, isPreview)
        val scale = Governor.scale(settings.resolution, isWallpaper, isPreview, surfacePixels)
        val config = JSONObject()
            .put("src", src)
            .put("fps", fps)
            .put("maxFps", refreshRate())
            .put("resScale", scale)
            .put("gpu", settings.gpu)
            .put("isWallpaper", isWallpaper)
        val scaleText = String.format(Locale.US, "%.2f", scale)
        Diag.i(
            "$label start '${animation.name}' fps=$fps scale=$scaleText gpu=${settings.gpu} " +
                "wallpaper=$isWallpaper preview=$isPreview pageReady=$pageReady attached=$attached"
        )
        touchEnabled = settings.touch
        pendingConfig = config.toString()
        flush()
        pollStatusLater()
    }

    fun pause() {
        if (destroyed) return
        paused = true
        Diag.i("$label pause")
        call("pause")
        webView.onPause()
    }

    fun resume() {
        if (destroyed) return
        if (paused) Diag.i("$label resume")
        paused = false
        webView.onResume()
        call("resume")
    }

    /** Forwards a raw touch event (wallpaper engine). */
    fun touch(event: MotionEvent) {
        if (touchEnabled && !destroyed) webView.dispatchTouchEvent(event)
    }

    /** Forwards a tap reported by the launcher as a wallpaper command. */
    fun tap(x: Float, y: Float) {
        if (!touchEnabled || destroyed) return
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0)
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN)
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN)
        webView.dispatchTouchEvent(down)
        webView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    /** Destroys the animation (frees its GPU resources) and then the WebView. Idempotent. */
    fun destroy() {
        if (destroyed) return
        Diag.i("$label destroy")
        try {
            webView.evaluateJavascript("if(window.__host){window.__host.destroy()}") { finish() }
        } catch (t: Throwable) {
            Diag.w("$label could not ask the page to destroy the animation", t)
            finish()
        }
        destroyed = true
        main.postDelayed({ finish() }, 600)
    }

    private fun finish() {
        if (finished) return
        finished = true
        main.removeCallbacksAndMessages(null)
        try {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
            Diag.i("$label WebView destroyed")
        } catch (t: Throwable) {
            Diag.w("$label WebView cleanup failed", t)
        }
    }

    private fun flush() {
        val config = pendingConfig ?: return
        if (destroyed || !pageReady || !attached) return
        pendingConfig = null
        Diag.i("$label running window.__host.start")
        run("window.__host.start($config)")
        if (paused) call("pause")
    }

    private fun call(method: String) {
        run("if(window.__host){window.__host.$method()}")
    }

    private fun run(js: String) {
        if (destroyed) return
        try {
            webView.evaluateJavascript("(function(){try{$js}catch(e){console.error(String(e))}})()", null)
        } catch (t: Throwable) {
            Diag.w("$label JavaScript call failed", t)
        }
    }

    // Logs what the page reports shortly after a start, so a blank or frozen animation can be diagnosed.
    private fun pollStatusLater() {
        for (delay in longArrayOf(1500L, 4000L, 10000L)) {
            main.postDelayed({ pollStatus(delay) }, delay)
        }
    }

    private fun pollStatus(afterMs: Long) {
        if (destroyed) return
        try {
            webView.evaluateJavascript("JSON.stringify(window.__host&&window.__host.status())") { value ->
                Diag.i("$label status +${afterMs}ms: $value")
            }
        } catch (t: Throwable) {
            Diag.w("$label status poll failed", t)
        }
    }

    private fun refreshRate(): Int {
        val displays = appContext.getSystemService(DisplayManager::class.java)
        val rate = displays?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
        return Math.round(rate).coerceIn(30, 240)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false

        webView.setBackgroundColor(Color.BLACK)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isLongClickable = false
        webView.setOnLongClickListener { true }
        if ((appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true) // lets you inspect debug builds in chrome://inspect
        }

        // WebGL only works while the WebView is attached to a hardware-accelerated window, so the
        // animation is started once both the page has loaded and the view is attached.
        webView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                attached = true
                Diag.i("$label WebView attached (hardwareAccelerated=${v.isHardwareAccelerated})")
                flush()
            }

            override fun onViewDetachedFromWindow(v: View) {
                attached = false
                Diag.i("$label WebView detached")
            }
        })

        // JavaScript warnings and errors end up in the same log as everything else.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                if (message != null) {
                    val level = message.messageLevel()
                    if (level == ConsoleMessage.MessageLevel.ERROR || level == ConsoleMessage.MessageLevel.WARNING) {
                        Diag.w("$label js ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                    }
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                server.handle(request)

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                Diag.i("$label page started: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && url.startsWith("https://$HOST/")) {
                    pageReady = true
                    val ms = SystemClock.elapsedRealtime() - openedAt
                    Diag.i("$label page finished after $ms ms (attached=$attached)")
                    flush()
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                Diag.w("$label load error ${request?.url}: ${error?.errorCode} ${error?.description}")
            }

            // Without this, a crashed WebView renderer would take the whole app down.
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Diag.e("$label WebView renderer process is gone (crashed=${detail?.didCrash()})")
                main.post { if (!destroyed) onRendererGone() }
                return true
            }
        }
    }
}
