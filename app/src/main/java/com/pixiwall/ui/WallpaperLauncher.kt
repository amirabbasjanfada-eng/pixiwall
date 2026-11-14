package com.pixiwall.ui

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.pixiwall.core.Screen
import com.pixiwall.wallpaper.AnimationWallpaperService

object WallpaperLauncher {

    private fun component(context: Context) = ComponentName(context, AnimationWallpaperService::class.java)

    /** True when this app's wallpaper is already the live wallpaper of that screen. */
    fun isActive(context: Context, screen: Screen): Boolean =
        try {
            val manager = WallpaperManager.getInstance(context)
            val info = if (Build.VERSION.SDK_INT >= 34) {
                manager.getWallpaperInfo(
                    if (screen == Screen.LOCK) WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
                )
            } else {
                manager.wallpaperInfo
            }
            info?.component == component(context)
        } catch (e: Exception) {
            false
        }

    /** Opens the system's live-wallpaper preview for this app, where the user confirms. */
    fun open(context: Context, screen: Screen) {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(context))
        try {
            context.startActivity(intent)
            if (Build.VERSION.SDK_INT >= 34) {
                val label = if (screen == Screen.LOCK) "Lock screen" else "Home screen"
                Toast.makeText(context, "Choose \"$label\"", Toast.LENGTH_SHORT).show()
            }
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(context, "No live wallpaper picker found", Toast.LENGTH_LONG).show()
            }
        }
    }
}
