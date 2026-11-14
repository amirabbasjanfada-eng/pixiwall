package com.pixiwall

import android.content.Context
import android.content.SharedPreferences
import com.pixiwall.core.KeyValue
import com.pixiwall.core.Library
import com.pixiwall.core.Screen
import com.pixiwall.core.Settings

/**
 * Everything the app remembers, in one SharedPreferences file: the animation list, the separate
 * Home and Lock selections (see [Library]) and the settings. Survives restarts and reboots.
 * The UI and the wallpaper service share one instance, so changes show up live in both.
 */
class Store private constructor(context: Context) : KeyValue {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pixiwall", Context.MODE_PRIVATE)

    val library = Library(this)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    var settings: Settings
        get() = Settings(
            fps = prefs.getInt("fps", 60),
            resolution = prefs.getInt("resolution", 100),
            touch = prefs.getBoolean("touch", true),
            gpu = prefs.getBoolean("gpu", true),
        )
        set(value) {
            prefs.edit()
                .putInt("fps", value.fps)
                .putInt("resolution", value.resolution)
                .putBoolean("touch", value.touch)
                .putBoolean("gpu", value.gpu)
                .apply()
        }

    /** The screen the user pressed last; the system's wallpaper preview shows that screen's animation. */
    var lastTarget: Screen
        get() = if (prefs.getString("last_target", null) == Screen.LOCK.key) Screen.LOCK else Screen.HOME
        set(value) {
            prefs.edit().putString("last_target", value.key).apply()
        }

    /**
     * Calls [onChange] on the main thread whenever something is saved.
     * Returns a function that stops the observation; keep it (SharedPreferences only holds weak references).
     */
    fun observe(onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        @Volatile
        private var instance: Store? = null

        fun get(context: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(context.applicationContext).also { instance = it }
            }
    }
}
