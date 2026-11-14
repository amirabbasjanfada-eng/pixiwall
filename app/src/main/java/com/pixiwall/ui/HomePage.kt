package com.pixiwall.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pixiwall.Importer
import com.pixiwall.Store
import com.pixiwall.core.BUILTIN
import com.pixiwall.core.Screen

/**
 * Home: large preview, the animation's name, an import button, and one separate button per screen.
 * The two screen buttons store their choice independently; pressing one never touches the other.
 */
@Composable
fun HomePage(store: Store, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = rememberStoreVersion(store)
    val library = store.library
    val animations = remember(version) { library.all() }
    val current = remember(version) { library.find(library.current) ?: BUILTIN }
    val settings = remember(version) { store.settings }
    val homeId = remember(version) { library.assigned(Screen.HOME) }
    val lockId = remember(version) { library.assigned(Screen.LOCK) }

    val metrics = context.resources.displayMetrics
    val ratio = metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) Importer.import(context, uri, store)
    }

    fun step(delta: Int) {
        val size = animations.size
        val index = animations.indexOfFirst { it.id == current.id }
        library.current = animations[((index + delta) % size + size) % size].id
    }

    fun assign(screen: Screen) {
        library.assign(screen, current.id)
        store.lastTarget = screen
        if (!WallpaperLauncher.isActive(context, screen)) {
            WallpaperLauncher.open(context, screen)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val previewWidth = minOf(maxWidth, maxHeight * ratio)
            Box(
                modifier = Modifier
                    .width(previewWidth)
                    .height(previewWidth / ratio),
            ) {
                Preview(current, settings, Modifier.fillMaxSize())
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { step(-1) }, enabled = animations.size > 1) { Text("\u2039") }
            Text(
                text = current.name,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = { step(1) }, enabled = animations.size > 1) { Text("\u203A") }
        }

        OutlinedButton(
            onClick = { picker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Import animation.js")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenButton("Home screen", homeId == current.id, Modifier.weight(1f)) { assign(Screen.HOME) }
            ScreenButton("Lock screen", lockId == current.id, Modifier.weight(1f)) { assign(Screen.LOCK) }
        }
    }
}

/** Filled with a check mark when the shown animation is the one assigned to that screen. */
@Composable
private fun ScreenButton(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (active) {
        Button(onClick = onClick, modifier = modifier) { Text("$label \u2713") }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}
