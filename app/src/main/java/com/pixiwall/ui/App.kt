package com.pixiwall.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pixiwall.Store

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

/** Exactly three pages, switched from a tab bar at the top. */
@Composable
fun App(store: Store) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Home", "AI Prompt", "Settings")

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = page) {
                titles.forEachIndexed { index, title ->
                    Tab(selected = page == index, onClick = { page = index }, text = { Text(title) })
                }
            }
            val content = Modifier.weight(1f)
            when (page) {
                0 -> HomePage(store, content)
                1 -> PromptPage(content)
                else -> SettingsPage(store, content)
            }
        }
    }
}
