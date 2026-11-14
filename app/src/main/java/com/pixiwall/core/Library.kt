package com.pixiwall.core

import java.util.UUID

const val BUILTIN_ID = "builtin"

enum class Screen(val key: String) { HOME("home"), LOCK("lock") }

data class Animation(val id: String, val name: String)

/** The single built-in preset. It only exists to prove that the PixiJS engine works. */
val BUILTIN = Animation(BUILTIN_ID, "Engine test")

/** Minimal string storage so the library logic can be unit-tested without Android. */
interface KeyValue {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/**
 * The imported animations plus, for each screen, which animation the user picked.
 * Home and Lock are two independent entries; nothing here is a single global selection.
 */
class Library(
    private val kv: KeyValue,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun all(): List<Animation> = listOf(BUILTIN) + readImported()

    fun find(id: String?): Animation? = if (id == null) null else all().firstOrNull { it.id == id }

    /** The animation shown in the Home preview. */
    var current: String
        get() = find(kv.getString(KEY_CURRENT))?.id ?: BUILTIN_ID
        set(value) {
            if (find(value) != null) kv.putString(KEY_CURRENT, value)
        }

    fun add(rawName: String): Animation {
        val base = cleanName(rawName)
        val taken = all().map { it.name }.toSet()
        var name = base
        var n = 2
        while (name in taken) {
            name = "$base $n"
            n++
        }
        val animation = Animation(newId(), name)
        writeImported(readImported() + animation)
        return animation
    }

    /** Only used to roll back a failed import. */
    fun remove(id: String) {
        if (id == BUILTIN_ID) return
        writeImported(readImported().filter { it.id != id })
        for (screen in Screen.entries) {
            if (kv.getString(selectionKey(screen)) == id) kv.putString(selectionKey(screen), null)
        }
        if (kv.getString(KEY_CURRENT) == id) kv.putString(KEY_CURRENT, null)
    }

    /** What the user explicitly picked for this screen, or null. */
    fun assigned(screen: Screen): String? = find(kv.getString(selectionKey(screen)))?.id

    fun assign(screen: Screen, id: String) {
        if (find(id) != null) kv.putString(selectionKey(screen), id)
    }

    /**
     * What a wallpaper engine runs on this screen: the screen's own pick; if the user never picked
     * one for it, the other screen's pick (how Android treats an unset lock screen); else the preset.
     */
    fun resolve(screen: Screen): String {
        val other = if (screen == Screen.HOME) Screen.LOCK else Screen.HOME
        return assigned(screen) ?: assigned(other) ?: BUILTIN_ID
    }

    private fun readImported(): List<Animation> {
        val raw = kv.getString(KEY_LIST) ?: return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab > 0 && tab < line.length - 1) Animation(line.substring(0, tab), line.substring(tab + 1)) else null
        }
    }

    private fun writeImported(list: List<Animation>) {
        kv.putString(KEY_LIST, list.joinToString("\n") { it.id + "\t" + it.name })
    }

    companion object {
        private const val KEY_LIST = "animations"
        private const val KEY_CURRENT = "current"

        private fun selectionKey(screen: Screen) = "selected_" + screen.key

        /** File name -> display name: no folders, no ".js", no control characters, at most 40 characters. */
        fun cleanName(raw: String): String {
            var s = raw.substringAfterLast('/').substringAfterLast('\\').trim()
            if (s.endsWith(".js", ignoreCase = true)) s = s.dropLast(3)
            s = s.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
            if (s.length > 40) s = s.take(40).trim()
            return if (s.isEmpty()) "Animation" else s
        }
    }
}
