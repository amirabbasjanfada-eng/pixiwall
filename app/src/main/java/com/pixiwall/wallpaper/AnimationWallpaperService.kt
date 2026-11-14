package com.pixiwall.wallpaper

import android.app.KeyguardManager
import android.app.Presentation
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import com.pixiwall.Diag
import com.pixiwall.Store
import com.pixiwall.Watchdog
import com.pixiwall.core.BUILTIN
import com.pixiwall.core.RateLimiter
import com.pixiwall.core.Screen
import com.pixiwall.engine.AnimationHost
import java.util.concurrent.atomic.AtomicInteger

private const val DEBOUNCE_MS = 250L          // wait for the surface to settle before building anything
private const val MAX_WAIT_MS = 1000L         // ...but never wait longer than this, even if it keeps changing
private const val IDLE_TEARDOWN_MS = 30_000L  // hidden this long: free the WebView and the GPU memory
private const val COOL_DOWN_MS = 30_000L      // after a failure: stop trying for a while
private const val MAX_AUTO_RETRIES = 3
private val engineCounter = AtomicInteger()

/** Process-wide ceilings, so that no bug can ever create virtual displays or WebViews in a loop. */
internal object Budget {
    private const val MAX_VIRTUAL_DISPLAYS = 4
    private const val MAX_PIPELINES = 3
    private val creations = RateLimiter(max = 6, windowMs = 60_000L)
    private val ownDisplays = HashSet<Int>()
    private var virtualDisplays = 0
    private var pipelines = 0

    @Synchronized
    fun tryAcquireVirtualDisplay(): Boolean {
        if (virtualDisplays >= MAX_VIRTUAL_DISPLAYS) return false
        if (!creations.tryAcquire()) return false
        virtualDisplays++
        return true
    }

    @Synchronized
    fun registerDisplay(displayId: Int) {
        ownDisplays.add(displayId)
    }

    @Synchronized
    fun releaseVirtualDisplay(displayId: Int) {
        if (virtualDisplays > 0) virtualDisplays--
        ownDisplays.remove(displayId)
    }

    /** True for a display this app created itself; a wallpaper engine must never render onto one. */
    @Synchronized
    fun isOwnDisplay(displayId: Int): Boolean = ownDisplays.contains(displayId)

    @Synchronized
    fun tryAcquirePipeline(): Boolean {
        if (pipelines >= MAX_PIPELINES) return false
        pipelines++
        return true
    }

    @Synchronized
    fun releasePipeline() {
        if (pipelines > 0) pipelines--
    }

    @Synchronized
    fun describe(): String = "virtualDisplays=$virtualDisplays pipelines=$pipelines"
}

/**
 * The live wallpaper.
 *
 * WebGL only works in a WebView that is attached to a hardware-accelerated window; a WebView that is
 * merely drawn into a canvas (software draw) has no WebGL. So each engine owns one virtual display whose
 * output is the wallpaper surface, shows a Presentation on it and puts the WebView in that Presentation
 * (the approach Chromium documents for rendering a WebView to a Surface).
 *
 * Rules that keep the phone responsive:
 *  - The virtual display is created ONCE per engine and then reused: resize() and setSurface() adapt it.
 *    Creating and destroying virtual displays in a loop is what can hang SurfaceFlinger and the whole phone.
 *  - Nothing is built inside the framework's surface callbacks. They only record state and schedule one
 *    debounced reconcile() on the main handler, so a burst of surface changes (as the system's wallpaper
 *    picker produces while it animates) results in a single build.
 *  - Nothing is rendered while hidden: the animation is paused and the virtual display's output is detached.
 *    A WebView is also never paused before its animation has started.
 *  - Hard process-wide ceilings (Budget) and a per-engine rate limiter stop any rebuild loop.
 *
 * One service serves both screens. Which animation an engine runs is decided by resolveScreen():
 *  - Android 14+ with separate engines: the engine's own wallpaper flags (home or lock);
 *  - otherwise (one engine shared by both screens): whether the keyguard is showing right now.
 */
class AnimationWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = PixiEngine()

    private inner class PixiEngine : Engine() {
        private val svc: Context = this@AnimationWallpaperService
        private val tag = "engine#" + engineCounter.incrementAndGet()
        private val main = Handler(Looper.getMainLooper())
        private val store = Store.get(svc)
        private val keyguard: KeyguardManager? = svc.getSystemService(KeyguardManager::class.java)
        private val displays: DisplayManager? = svc.getSystemService(DisplayManager::class.java)
        private val rebuilds = RateLimiter(max = 4, windowMs = 15_000L)

        // What the framework last told us.
        private var surface: Surface? = null
        private var surfaceW = 0
        private var surfaceH = 0
        private var visible = false
        private var destroyed = false

        // The pipeline: one long-lived virtual display, plus the replaceable Presentation + WebView on it.
        private var virtualDisplay: VirtualDisplay? = null
        private var vdW = 0
        private var vdH = 0
        private var outputAttached = false
        private var presentation: Presentation? = null
        private var host: AnimationHost? = null
        private var pipelineW = 0
        private var pipelineH = 0
        private var activeKey: String? = null

        private var pendingSince = 0L
        private var coolingDownUntil = 0L
        private var autoRetries = 0
        private var sawRawTouch = false
        private var touchFlag: Boolean? = null
        private var unobserve: (() -> Unit)? = null
        private var receiverRegistered = false

        private val reconcileRunnable = Runnable { reconcile() }
        private val idleRunnable = Runnable { dismissPresentation("hidden for ${IDLE_TEARDOWN_MS / 1000} s") }

        private val userPresent = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // The device was just unlocked: switch from the lock animation to the home one.
                if (host != null && visible) refresh()
            }
        }

        /* ---------------------------------------------------------------- framework callbacks */

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            Diag.init(svc)
            Watchdog.acquire()
            Diag.i("$tag created preview=$isPreview sdk=${Build.VERSION.SDK_INT} ${Budget.describe()}")
            applyTouchFlag(store.settings.touch)
            unobserve = store.observe { if (!destroyed && host != null && visible) refresh() }
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= 33) {
                svc.registerReceiver(userPresent, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                svc.registerReceiver(userPresent, filter)
            }
            receiverRegistered = true
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            super.onSurfaceCreated(holder)
            Diag.i("$tag surfaceCreated")
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (holder == null || width <= 0 || height <= 0) return
            Diag.i("$tag surfaceChanged ${width}x$height visible=$visible")
            surface = holder.surface
            surfaceW = width
            surfaceH = height
            request() // never build inside a surface callback
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            Diag.i("$tag surfaceDestroyed")
            surface = null
            cancelRequest()
            goIdle("surface destroyed") // the surface is invalid as soon as this callback returns
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            Diag.i("$tag visible=$visible")
            if (visible) {
                request()
            } else {
                cancelRequest()
                goIdle("hidden")
            }
        }

        override fun onTouchEvent(event: MotionEvent?) {
            if (event != null) {
                sawRawTouch = true
                host?.touch(event)
            }
            super.onTouchEvent(event)
        }

        // Launchers that do not forward raw touches report taps as commands instead.
        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean,
        ): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP && !sawRawTouch) {
                host?.tap(x.toFloat(), y.toFloat())
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onDestroy() {
            destroyed = true
            main.removeCallbacks(reconcileRunnable)
            main.removeCallbacks(idleRunnable)
            dismissPresentation("engine destroyed")
            releaseVirtualDisplay("engine destroyed")
            unobserve?.invoke()
            unobserve = null
            if (receiverRegistered) {
                try {
                    svc.unregisterReceiver(userPresent)
                } catch (e: IllegalArgumentException) {
                    Diag.w("$tag receiver was not registered", e)
                }
                receiverRegistered = false
            }
            Diag.i("$tag destroyed ${Budget.describe()}")
            Watchdog.release()
            super.onDestroy()
        }

        /* ---------------------------------------------------------------- scheduling */

        // Coalesces a burst of events into one reconcile(), waiting for things to settle.
        private fun request() {
            if (destroyed) return
            val now = SystemClock.uptimeMillis()
            if (pendingSince == 0L) pendingSince = now
            val delay = minOf(DEBOUNCE_MS, maxOf(0L, MAX_WAIT_MS - (now - pendingSince)))
            main.removeCallbacks(reconcileRunnable)
            main.postDelayed(reconcileRunnable, delay)
        }

        private fun cancelRequest() {
            main.removeCallbacks(reconcileRunnable)
            pendingSince = 0L
        }

        /* ---------------------------------------------------------------- the one place that builds things */

        // Brings the pipeline in line with what the framework reported. Idempotent and cheap when nothing changed.
        private fun reconcile() {
            pendingSince = 0L
            if (destroyed) return
            val s = surface
            if (s == null || !s.isValid || surfaceW <= 0 || surfaceH <= 0 || !visible) {
                goIdle("nothing to show (surface=${s != null} visible=$visible)")
                return
            }
            if (Budget.isOwnDisplay(engineDisplayId())) {
                Diag.e("$tag was attached to a virtual display of this app; not rendering, to avoid a loop")
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now < coolingDownUntil) {
                Diag.w("$tag cooling down for another ${coolingDownUntil - now} ms; not rendering")
                return
            }
            main.removeCallbacks(idleRunnable)
            try {
                if (!ensureVirtualDisplay(s)) return
                if (!ensurePresentation()) return
                host?.resume()
                refresh()
            } catch (t: Throwable) {
                Diag.e("$tag could not start rendering", t)
                dismissPresentation("error")
                releaseVirtualDisplay("error") // do not keep a possibly broken display; Budget bounds how often a new one is made
                coolDown()
            }
        }

        // Create the virtual display once; afterwards only resize it or re-attach the output surface.
        private fun ensureVirtualDisplay(s: Surface): Boolean {
            val dpi = svc.resources.displayMetrics.densityDpi
            val existing = virtualDisplay
            if (existing == null) {
                val manager = displays
                if (manager == null) {
                    Diag.e("$tag there is no DisplayManager")
                    return false
                }
                if (!Budget.tryAcquireVirtualDisplay()) {
                    Diag.e("$tag refusing to create another virtual display (${Budget.describe()})")
                    coolDown()
                    return false
                }
                val started = SystemClock.elapsedRealtime()
                val created = try {
                    manager.createVirtualDisplay(
                        "pixiwall",
                        surfaceW,
                        surfaceH,
                        dpi,
                        s,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                    )
                } catch (t: Throwable) {
                    Budget.releaseVirtualDisplay(-1)
                    throw t
                }
                val displayId = created.display.displayId
                Budget.registerDisplay(displayId)
                virtualDisplay = created
                vdW = surfaceW
                vdH = surfaceH
                outputAttached = true
                val took = SystemClock.elapsedRealtime() - started
                Diag.i("$tag virtual display #$displayId created ${vdW}x$vdH @${dpi}dpi in $took ms (${Budget.describe()})")
                return true
            }
            if (vdW != surfaceW || vdH != surfaceH) {
                // A Presentation cancels itself when its display changes size, so drop it first.
                dismissPresentation("display resize")
                existing.resize(surfaceW, surfaceH, dpi)
                vdW = surfaceW
                vdH = surfaceH
                Diag.i("$tag virtual display resized to ${vdW}x$vdH")
            }
            if (!outputAttached) {
                existing.setSurface(s)
                outputAttached = true
                Diag.i("$tag virtual display output attached")
            }
            return true
        }

        private fun ensurePresentation(): Boolean {
            if (presentation != null && host != null && pipelineW == vdW && pipelineH == vdH) return true
            dismissPresentation("rebuild")
            val display = virtualDisplay?.display ?: return false
            if (!rebuilds.tryAcquire()) {
                Diag.e("$tag rebuilt the pipeline too often; backing off")
                coolDown()
                return false
            }
            if (!Budget.tryAcquirePipeline()) {
                Diag.e("$tag too many live pipelines (${Budget.describe()}); backing off")
                coolDown()
                return false
            }
            var shown: Presentation? = null
            var newHost: AnimationHost? = null
            try {
                val started = SystemClock.elapsedRealtime()
                val p = Presentation(svc, display)
                shown = p
                val webView = WebView(p.context)
                val h = AnimationHost(svc, webView) { handleRendererGone() }
                newHost = h
                h.label = tag
                p.setContentView(
                    webView,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                p.setOnDismissListener { onPresentationDismissed(p) }
                p.show()
                h.open()
                presentation = p
                host = h
                pipelineW = vdW
                pipelineH = vdH
                activeKey = null
                val took = SystemClock.elapsedRealtime() - started
                Diag.i("$tag presentation + WebView built in $took ms (${Budget.describe()})")
                return true
            } catch (t: Throwable) {
                Diag.e("$tag could not build the presentation", t)
                try {
                    newHost?.destroy()
                } catch (e: Throwable) {
                    Diag.w("$tag cleanup: host.destroy failed", e)
                }
                try {
                    shown?.dismiss()
                } catch (e: Throwable) {
                    Diag.w("$tag cleanup: presentation.dismiss failed", e)
                }
                Budget.releasePipeline()
                coolDown()
                return false
            }
        }

        // Replaceable part only: the virtual display stays.
        private fun dismissPresentation(reason: String) {
            val h = host
            val p = presentation
            host = null
            presentation = null
            activeKey = null
            if (h == null && p == null) return
            Diag.i("$tag pipeline torn down ($reason)")
            try {
                p?.setOnDismissListener(null)
            } catch (e: Throwable) {
                Diag.w("$tag could not clear the dismiss listener", e)
            }
            try {
                h?.destroy()
            } catch (e: Throwable) {
                Diag.w("$tag host.destroy failed", e)
            }
            try {
                p?.dismiss()
            } catch (e: Throwable) {
                Diag.w("$tag presentation.dismiss failed", e)
            }
            Budget.releasePipeline()
        }

        // Called when the system itself dismissed the Presentation (for example because the display changed).
        private fun onPresentationDismissed(p: Presentation) {
            if (p !== presentation) return
            Diag.w("$tag the system dismissed the presentation")
            dismissPresentation("dismissed by the system")
            request()
        }

        private fun handleRendererGone() {
            Diag.e("$tag WebView renderer died; rebuilding the presentation")
            dismissPresentation("renderer gone")
            request()
        }

        // Nothing to show right now: stop all work, detach the output, free the WebView later.
        private fun goIdle(reason: String) {
            host?.pause()
            detachOutput()
            if (host != null) {
                main.removeCallbacks(idleRunnable)
                main.postDelayed(idleRunnable, IDLE_TEARDOWN_MS)
            }
            Diag.i("$tag idle ($reason)")
        }

        // Detaching the surface turns the virtual display "off" without destroying it.
        private fun detachOutput() {
            val vd = virtualDisplay ?: return
            if (!outputAttached) return
            outputAttached = false
            try {
                vd.setSurface(null)
                Diag.i("$tag virtual display output detached")
            } catch (t: Throwable) {
                Diag.w("$tag setSurface(null) failed", t)
            }
        }

        private fun releaseVirtualDisplay(reason: String) {
            val vd = virtualDisplay ?: return
            virtualDisplay = null
            outputAttached = false
            val displayId = try {
                vd.display.displayId
            } catch (e: Throwable) {
                -1
            }
            try {
                vd.release()
            } catch (t: Throwable) {
                Diag.w("$tag virtualDisplay.release failed", t)
            }
            Budget.releaseVirtualDisplay(displayId)
            Diag.i("$tag virtual display #$displayId released ($reason) (${Budget.describe()})")
        }

        private fun coolDown() {
            coolingDownUntil = SystemClock.elapsedRealtime() + COOL_DOWN_MS
            if (autoRetries < MAX_AUTO_RETRIES && !destroyed) {
                autoRetries++
                Diag.w("$tag will retry once after the cool-down ($autoRetries/$MAX_AUTO_RETRIES)")
                main.removeCallbacks(reconcileRunnable)
                main.postDelayed(reconcileRunnable, COOL_DOWN_MS + 200L)
            }
        }

        /* ---------------------------------------------------------------- what to show */

        // Picks the animation for the screen this engine is showing and starts it if anything changed.
        private fun refresh() {
            val h = host ?: return
            val settings = store.settings
            applyTouchFlag(settings.touch)
            h.touchEnabled = settings.touch
            val library = store.library
            val screen = resolveScreen()
            val animation = library.find(library.resolve(screen)) ?: BUILTIN
            val key = "${animation.id}|${settings.fps}|${settings.resolution}|${settings.gpu}|${vdW}x$vdH"
            if (key == activeKey) return
            activeKey = key
            Diag.i("$tag showing '${animation.name}' on the $screen screen")
            h.start(
                animation,
                settings,
                isWallpaper = true,
                isPreview = isPreview,
                surfacePixels = vdW.toLong() * vdH.toLong(),
            )
        }

        // setTouchEventsEnabled() makes the framework re-run its surface update, so only call it on a change,
        // and never from inside a surface callback (all callers here run from posted work).
        private fun applyTouchFlag(enabled: Boolean) {
            if (touchFlag == enabled) return
            touchFlag = enabled
            setTouchEventsEnabled(enabled)
        }

        private fun resolveScreen(): Screen {
            if (isPreview) return store.lastTarget // the system's preview: show what the user just pressed
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    val flags = wallpaperFlags
                    val home = (flags and WallpaperManager.FLAG_SYSTEM) != 0
                    val lock = (flags and WallpaperManager.FLAG_LOCK) != 0
                    if (lock && !home) return Screen.LOCK
                    if (home && !lock) return Screen.HOME
                } catch (e: IllegalStateException) {
                    Diag.w("$tag wallpaper flags are not available yet", e)
                }
            }
            return if (keyguard?.isKeyguardLocked == true) Screen.LOCK else Screen.HOME
        }

        @Suppress("DEPRECATION")
        private fun engineDisplayId(): Int {
            val context = displayContext ?: return Display.DEFAULT_DISPLAY
            return try {
                if (Build.VERSION.SDK_INT >= 30) {
                    context.display?.displayId ?: Display.DEFAULT_DISPLAY
                } else {
                    (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.displayId
                }
            } catch (e: Exception) {
                Display.DEFAULT_DISPLAY
            }
        }
    }
}
