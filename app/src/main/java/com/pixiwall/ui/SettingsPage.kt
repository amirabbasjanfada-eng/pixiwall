package com.pixiwall.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pixiwall.Store

/** The four essential settings. Changes apply immediately to the preview and to a running wallpaper. */
@Composable
fun SettingsPage(store: Store, modifier: Modifier = Modifier) {
    val version = rememberStoreVersion(store)
    val settings = remember(version) { store.settings }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Choice("Frame rate", listOf(30 to "30 fps", 60 to "60 fps", 0 to "Max"), settings.fps) {
            store.settings = store.settings.copy(fps = it)
        }
        Choice("Rendering resolution", listOf(50 to "50%", 75 to "75%", 100 to "100%"), settings.resolution) {
            store.settings = store.settings.copy(resolution = it)
        }
        SwitchRow("Touch interaction", settings.touch) {
            store.settings = store.settings.copy(touch = it)
        }
        SwitchRow("GPU acceleration", settings.gpu) {
            store.settings = store.settings.copy(gpu = it)
        }
    }
}

@Composable
private fun Choice(title: String, options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in options) {
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
