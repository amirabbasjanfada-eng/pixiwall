package com.pixiwall.ui

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pixiwall.core.Animation
import com.pixiwall.core.Settings
import com.pixiwall.engine.AnimationHost

/**
 * The large preview on the Home page. It runs the same engine as the live wallpaper (AnimationHost).
 * Leaving the page destroys the animation and its WebView, which frees the GPU resources.
 */
@Composable
fun Preview(animation: Animation, settings: Settings, modifier: Modifier = Modifier) {
    var generation by remember { mutableIntStateOf(0) }
    var crashes by remember { mutableIntStateOf(0) }

    // A new generation rebuilds the WebView, used after the WebView renderer process died.
    key(generation) {
        val holder = remember { mutableStateOf<AnimationHost?>(null) }
        AndroidView(
            modifier = modifier,
            factory = { context ->
                val webView = WebView(context)
                webView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                val created = AnimationHost(context, webView) {
                    if (crashes < 3) {
                        crashes++
                        generation++
                    }
                }
                created.label = "preview"
                holder.value = created
                created.open()
                webView
            },
            update = { webView ->
                webView.setOnTouchListener(if (settings.touch) null else View.OnTouchListener { _, _ -> true })
            },
        )
        val host = holder.value
        if (host != null) {
            DisposableEffect(host) { onDispose { host.destroy() } }
            LaunchedEffect(host, animation.id, settings) { host.start(animation, settings, false) }
            LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { host.pause() }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { host.resume() }
        }
    }
}
