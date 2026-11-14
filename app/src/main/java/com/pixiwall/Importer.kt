package com.pixiwall

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Imports an animation.js file chosen with the system file picker. Never throws; problems become a toast. */
object Importer {
    private const val MAX_BYTES = 4 * 1024 * 1024

    fun import(context: Context, uri: Uri, store: Store) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val result = runCatching { read(app, uri) }
            main.post {
                result.fold(
                    onSuccess = { (name, text) -> save(app, store, name, text) },
                    onFailure = { toast(app, it.message ?: "Could not read the file") },
                )
            }
        }.start()
    }

    private fun read(app: Context, uri: Uri): Pair<String, String> {
        val resolver = app.contentResolver
        var displayName: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) displayName = cursor.getString(0)
        }
        val input = resolver.openInputStream(uri) ?: throw IOException("Could not open the file")
        val out = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw IOException("The file is larger than 4 MB")
                out.write(buffer, 0, n)
            }
        }
        val text = String(out.toByteArray(), Charsets.UTF_8).removePrefix("\uFEFF")
        if (!text.contains("startAnimation")) {
            throw IOException("This file does not define window.startAnimation")
        }
        return Pair(displayName ?: "Animation", text)
    }

    private fun save(app: Context, store: Store, rawName: String, text: String) {
        val animation = store.library.add(rawName)
        try {
            val dir = File(app.filesDir, "animations")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create the storage folder")
            File(dir, animation.id + ".js").writeText(text, Charsets.UTF_8)
            store.library.current = animation.id
        } catch (e: IOException) {
            store.library.remove(animation.id)
            toast(app, "Could not save the file")
        }
    }

    private fun toast(app: Context, message: String) {
        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }
}
