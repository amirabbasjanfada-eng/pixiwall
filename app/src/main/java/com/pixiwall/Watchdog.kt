package com.pixiwall

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock

/**
 * Logs when the main thread stops responding, together with the stack it is stuck in.
 * That is exactly the information needed to diagnose an "App isn't responding" freeze.
 * Costs one tiny wake-up every 500 ms while the app or a wallpaper engine is alive.
 */
object Watchdog {
    private const val PERIOD_MS = 500L
    private const val STALL_MS = 2000L
    private const val REPORT_EVERY_MS = 4000L
    private const val MAX_REPORTS = 12

    private val main = Handler(Looper.getMainLooper())
    private var users = 0
    private var thread: HandlerThread? = null

    @Volatile
    private var worker: Handler? = null

    @Volatile
    private var lastPong = 0L
    private var lastReport = 0L
    private var reports = 0

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val silent = now - lastPong
            if (silent > STALL_MS && now - lastReport > REPORT_EVERY_MS && reports < MAX_REPORTS) {
                reports++
                lastReport = now
                Diag.w("MAIN THREAD STALLED for $silent ms. It is currently in:\n" + mainStack())
            }
            main.post { pong() }
            worker?.postDelayed(this, PERIOD_MS)
        }
    }

    @Synchronized
    fun acquire() {
        users++
        if (users != 1) return
        lastPong = SystemClock.uptimeMillis()
        reports = 0
        val t = HandlerThread("pixiwall-watchdog")
        t.start()
        thread = t
        val h = Handler(t.looper)
        worker = h
        h.post(tick)
    }

    @Synchronized
    fun release() {
        users--
        if (users > 0) return
        users = 0
        worker?.removeCallbacksAndMessages(null)
        worker = null
        thread?.quitSafely()
        thread = null
    }

    private fun pong() {
        val now = SystemClock.uptimeMillis()
        val gap = now - lastPong
        if (gap > STALL_MS) Diag.w("main thread responsive again after $gap ms")
        lastPong = now
    }

    private fun mainStack(): String =
        Looper.getMainLooper().thread.stackTrace.take(30).joinToString("\n") { "    at $it" }
}
