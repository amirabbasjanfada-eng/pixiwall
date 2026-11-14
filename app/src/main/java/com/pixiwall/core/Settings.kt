package com.pixiwall.core

/**
 * User settings.
 * fps: 30, 60, or 0 for "maximum available".
 * resolution: percent of the device's native pixel ratio used for rendering (50, 75 or 100).
 */
data class Settings(
    val fps: Int = 60,
    val resolution: Int = 100,
    val touch: Boolean = true,
    val gpu: Boolean = true,
)
