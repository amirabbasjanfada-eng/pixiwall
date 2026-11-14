package com.pixiwall.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernorTest {
    @Test
    fun fpsFollowsTheSettingExceptWhereCapped() {
        assertEquals(60, Governor.fps(60, isWallpaper = false, isPreview = false))
        assertEquals(30, Governor.fps(30, isWallpaper = false, isPreview = false))
        assertEquals(0, Governor.fps(0, isWallpaper = false, isPreview = false)) // the in-app preview may be unlimited
        assertEquals(60, Governor.fps(0, isWallpaper = true, isPreview = false)) // "max" means 60 behind the home screen
        assertEquals(30, Governor.fps(60, isWallpaper = true, isPreview = true)) // the system picker's preview is capped
        assertEquals(30, Governor.fps(0, isWallpaper = true, isPreview = true))
        assertEquals(30, Governor.fps(30, isWallpaper = true, isPreview = true))
    }

    @Test
    fun scaleOnlyShrinksVeryLargeWallpaperSurfaces() {
        val fhd = 1080L * 2400L
        val qhd = 1440L * 3200L
        assertEquals(1.0, Governor.scale(100, isWallpaper = true, isPreview = false, surfacePixels = fhd), 1e-9)
        assertEquals(0.5, Governor.scale(50, isWallpaper = true, isPreview = false, surfacePixels = fhd), 1e-9)

        val capped = Math.sqrt(Governor.WALLPAPER_MAX_PIXELS.toDouble() / qhd.toDouble())
        assertEquals(capped, Governor.scale(100, isWallpaper = true, isPreview = false, surfacePixels = qhd), 1e-9)
        assertTrue(capped < 1.0)
        // the user's lower choice always wins, and the in-app preview is never capped
        assertEquals(0.5, Governor.scale(50, isWallpaper = true, isPreview = false, surfacePixels = qhd), 1e-9)
        assertEquals(1.0, Governor.scale(100, isWallpaper = false, isPreview = false, surfacePixels = qhd), 1e-9)

        assertEquals(0.75, Governor.scale(100, isWallpaper = true, isPreview = true, surfacePixels = fhd), 1e-9)
        assertEquals(0.25, Governor.scale(1, isWallpaper = true, isPreview = false, surfacePixels = fhd), 1e-9)
    }
}
