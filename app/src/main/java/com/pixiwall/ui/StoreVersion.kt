package com.pixiwall.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pixiwall.Store

/** Changes whenever anything in the store changes, so composables know when to re-read it. */
@Composable
fun rememberStoreVersion(store: Store): Int {
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(store) {
        val stop = store.observe { version++ }
        onDispose { stop() }
    }
    return version
}
