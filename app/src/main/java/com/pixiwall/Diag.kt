package com.pixiwall

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.webkit.WebView
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Diagnostics. Everything goes to logcat:   adb logcat -s PixiWall
 * and the last lines are also kept in files/diagnostics.log, so they survive a freeze or a force-close:
 *   adb shell run-as com.pixiwall cat files/diagnostics.log
 * A copy is written to the app's folder on shared storage, which a PC can read over USB without adb:
 *   Android/data/com.pixiwall/files/diagnostics.log
 */
object Diag {
    const val TAG = "PixiWall"
    private const val MAX_LINES = 500

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null
    private var mirror: File? = null
    private var writer: Handler? = null
    private var flushQueued = false

    fun init(context: Context) {
        val app = context.applicationContext
        val first = synchronized(lock) {
            if (file != null) {
                false
            } else {
                file = File(app.filesDir, "diagnostics.log")
                mirror = app.getExternalFilesDir(null)?.let { File(it, "diagnostics.log") }
                val thread = HandlerThread("pixiwall-diag")
                thread.start()
                writer = Handler(thread.looper)
                true
            }
        }
        if (first) i("diagnostics started: " + describeDevice(app))
    }

    fun i(message: String) = log(Log.INFO, message, null)

    fun w(message: String, error: Throwable? = null) = log(Log.WARN, message, error)

    fun e(message: String, error: Throwable? = null) = log(Log.ERROR, message, error)

    private fun describeDevice(context: Context): String {
        val metrics = context.resources.displayMetrics
        val webView = try {
            val info = WebView.getCurrentWebViewPackage()
            if (info == null) "unknown" else info.packageName + " " + info.versionName
        } catch (e: Throwable) {
            "unknown"
        }
        return Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE +
            " (sdk " + Build.VERSION.SDK_INT + "), screen " + metrics.widthPixels + "x" + metrics.heightPixels +
            " @" + metrics.densityDpi + "dpi, WebView " + webView
    }

    private fun log(priority: Int, message: String, error: Throwable?) {
        val text = if (error == null) message else message + "\n" + Log.getStackTraceString(error)
        Log.println(priority, TAG, text)
        synchronized(lock) {
            lines.addLast(format.format(Date()) + " " + text)
            while (lines.size > MAX_LINES) lines.pollFirst()
            val handler = writer
            if (handler != null && !flushQueued) {
                flushQueued = true
                handler.postDelayed({ flush() }, 700L)
            }
        }
    }

    private fun flush() {
        val (snapshot, targets) = synchronized(lock) {
            flushQueued = false
            Pair(lines.joinToString("\n") + "\n", listOfNotNull(file, mirror))
        }
        for (target in targets) {
            try {
                target.writeText(snapshot)
            } catch (e: Exception) {
                Log.w(TAG, "Could not write " + target, e)
            }
        }
    }
}
