package com.pixiwall.engine

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.pixiwall.Diag
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** Reserved by Android for exactly this purpose (see WebViewAssetLoader). Nothing is ever fetched from it. */
const val HOST = "appassets.androidplatform.net"
const val INDEX_URL = "https://$HOST/engine/index.html"

/**
 * Answers every request the WebView makes, from inside the app:
 *   /engine/*   index.html, runtime.js, pixi.min.js   (APK assets)
 *   /builtin/preset.js                                (APK assets)
 *   /anim/<id>.js                                     (imported animations, app-private storage)
 * Any other http(s) request is refused, so an imported animation cannot reach the network.
 */
class AssetServer(private val context: Context) {

    fun handle(request: WebResourceRequest?): WebResourceResponse? {
        val uri = request?.url ?: return null
        val scheme = uri.scheme ?: return null
        if (scheme != "https" && scheme != "http") return null // data:, blob:, about: are WebView-internal
        if (scheme != "https" || uri.host != HOST) {
            Diag.w("blocked a request to " + uri) // imported animations cannot reach the network
            return reply(403, "Forbidden", TEXT, null)
        }

        val segments = uri.pathSegments
        if (segments.size != 2) return notFound()
        val name = segments[1]
        return when (segments[0]) {
            "engine" -> if (name in ENGINE_FILES) asset("engine/$name", mimeOf(name)) else notFound()
            "builtin" -> if (name == "preset.js") asset("builtin/preset.js", JS) else notFound()
            "anim" -> animation(name)
            else -> notFound()
        }
    }

    private fun asset(path: String, mime: String): WebResourceResponse =
        try {
            reply(200, "OK", mime, context.assets.open(path))
        } catch (e: IOException) {
            notFound()
        }

    private fun animation(fileName: String): WebResourceResponse {
        if (!fileName.endsWith(".js")) return notFound()
        val id = fileName.removeSuffix(".js")
        if (!ID.matches(id)) return notFound()
        val file = File(File(context.filesDir, "animations"), "$id.js")
        return try {
            if (file.isFile) reply(200, "OK", JS, FileInputStream(file)) else notFound()
        } catch (e: IOException) {
            notFound()
        }
    }

    private fun notFound(): WebResourceResponse = reply(404, "Not Found", TEXT, null)

    private fun reply(status: Int, reason: String, mime: String, body: InputStream?): WebResourceResponse =
        WebResourceResponse(
            mime,
            "UTF-8",
            status,
            reason,
            mapOf("Cache-Control" to "no-store"),
            body ?: ByteArrayInputStream(ByteArray(0)),
        )

    private fun mimeOf(name: String): String = if (name.endsWith(".html")) "text/html" else JS

    private companion object {
        const val JS = "text/javascript"
        const val TEXT = "text/plain"
        val ENGINE_FILES = setOf("index.html", "runtime.js", "pixi.min.js")
        val ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}
