package com.pixiwall.core

/**
 * Resource ceilings so that no animation, however heavy, can starve the phone.
 * They apply to every animation the same way; nothing here knows about a specific animation.
 */
object Governor {
    /** Largest backing-store size a wallpaper may render (about the pixel count of 1080 x 2592). */
    const val WALLPAPER_MAX_PIXELS = 2_800_000L

    /** "Max" frame rate means 60 for a wallpaper: 90/120 fps behind the home screen only burns GPU and battery. */
    const val WALLPAPER_MAX_FPS = 60

    /** The system's wallpaper picker preview is brief and shares the GPU with the picker's own UI. */
    const val PREVIEW_MAX_FPS = 30
    const val PREVIEW_MAX_SCALE = 0.75

    /** Frame rate to run at: 30, 60, or 0 for unlimited (only the in-app preview may be unlimited). */
    fun fps(settingFps: Int, isWallpaper: Boolean, isPreview: Boolean): Int {
        var fps = settingFps
        if (isWallpaper && fps == 0) fps = WALLPAPER_MAX_FPS
        if (isPreview && (fps == 0 || fps > PREVIEW_MAX_FPS)) fps = PREVIEW_MAX_FPS
        return fps
    }

    /** Fraction of the native pixel ratio to render at. */
    fun scale(settingPercent: Int, isWallpaper: Boolean, isPreview: Boolean, surfacePixels: Long): Double {
        var scale = settingPercent.coerceIn(25, 100) / 100.0
        if (isWallpaper && surfacePixels > WALLPAPER_MAX_PIXELS) {
            scale = minOf(scale, Math.sqrt(WALLPAPER_MAX_PIXELS.toDouble() / surfacePixels.toDouble()))
        }
        if (isPreview) scale = minOf(scale, PREVIEW_MAX_SCALE)
        return scale
    }
}
