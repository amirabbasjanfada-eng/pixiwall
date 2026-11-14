package com.pixiwall.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class MemoryKeyValue : KeyValue {
    val map = HashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

class LibraryTest {
    private fun library(kv: KeyValue = MemoryKeyValue(), prefix: String = "id"): Library {
        var n = 0
        return Library(kv) { prefix + (++n) }
    }

    @Test
    fun startsWithOnlyTheBuiltInPreset() {
        val lib = library()
        assertEquals(listOf(BUILTIN), lib.all())
        assertEquals(BUILTIN_ID, lib.current)
        assertEquals(BUILTIN_ID, lib.resolve(Screen.HOME))
        assertEquals(BUILTIN_ID, lib.resolve(Screen.LOCK))
    }

    @Test
    fun homeAndLockAreIndependent() {
        val lib = library()
        val a = lib.add("a.js")
        val b = lib.add("b.js")
        lib.assign(Screen.HOME, a.id)
        lib.assign(Screen.LOCK, b.id)
        assertEquals(a.id, lib.assigned(Screen.HOME))
        assertEquals(b.id, lib.assigned(Screen.LOCK))

        val c = lib.add("c.js")
        lib.assign(Screen.HOME, c.id) // changing Home must not touch Lock
        assertEquals(c.id, lib.assigned(Screen.HOME))
        assertEquals(b.id, lib.assigned(Screen.LOCK))

        lib.assign(Screen.LOCK, a.id) // and the other way round
        assertEquals(c.id, lib.assigned(Screen.HOME))
        assertEquals(a.id, lib.assigned(Screen.LOCK))
    }

    @Test
    fun selectionsSurviveARestart() {
        val kv = MemoryKeyValue()
        val first = library(kv)
        val a = first.add("a.js")
        val b = first.add("b.js")
        first.assign(Screen.HOME, a.id)
        first.assign(Screen.LOCK, b.id)
        first.current = b.id

        val restarted = library(kv, prefix = "later") // a new object over the same storage
        assertEquals(listOf(BUILTIN, a, b), restarted.all())
        assertEquals(a.id, restarted.assigned(Screen.HOME))
        assertEquals(b.id, restarted.assigned(Screen.LOCK))
        assertEquals(b.id, restarted.current)
    }

    @Test
    fun unsetScreenFallsBackToTheOtherScreenThenToThePreset() {
        val lib = library()
        val a = lib.add("a.js")
        lib.assign(Screen.HOME, a.id)
        assertEquals(a.id, lib.resolve(Screen.HOME))
        assertEquals(a.id, lib.resolve(Screen.LOCK))
        assertNull(lib.assigned(Screen.LOCK)) // the fallback is not stored as a choice

        val other = library()
        val b = other.add("b.js")
        other.assign(Screen.LOCK, b.id)
        assertEquals(b.id, other.resolve(Screen.HOME))
    }

    @Test
    fun ownChoiceBeatsTheFallback() {
        val lib = library()
        val a = lib.add("a.js")
        val b = lib.add("b.js")
        lib.assign(Screen.HOME, a.id)
        lib.assign(Screen.LOCK, b.id)
        assertEquals(a.id, lib.resolve(Screen.HOME))
        assertEquals(b.id, lib.resolve(Screen.LOCK))
    }

    @Test
    fun namesAreMadeUnique() {
        val lib = library()
        assertEquals("animation", lib.add("animation.js").name)
        assertEquals("animation 2", lib.add("animation.js").name)
        assertEquals("animation 3", lib.add("animation.js").name)
        assertEquals("Animation", lib.add(".js").name)
    }

    @Test
    fun cleanNameRemovesPathExtensionAndControlCharacters() {
        assertEquals("Fire", Library.cleanName("/storage/emulated/0/Download/Fire.JS"))
        assertEquals("a b", Library.cleanName("a\tb.js"))
        assertEquals(40, Library.cleanName("x".repeat(100) + ".js").length)
    }

    @Test
    fun removeClearsEveryReferenceToTheAnimation() {
        val lib = library()
        val a = lib.add("a.js")
        lib.assign(Screen.HOME, a.id)
        lib.assign(Screen.LOCK, a.id)
        lib.current = a.id
        lib.remove(a.id)
        assertEquals(listOf(BUILTIN), lib.all())
        assertNull(lib.assigned(Screen.HOME))
        assertNull(lib.assigned(Screen.LOCK))
        assertEquals(BUILTIN_ID, lib.current)
    }

    @Test
    fun theBuiltInPresetCannotBeRemovedAndUnknownIdsAreIgnored() {
        val lib = library()
        lib.remove(BUILTIN_ID)
        assertEquals(listOf(BUILTIN), lib.all())
        lib.assign(Screen.HOME, "does-not-exist")
        assertNull(lib.assigned(Screen.HOME))
        assertNotEquals("does-not-exist", lib.current)
    }
}
