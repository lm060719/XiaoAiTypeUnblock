package io.mo.xatype.keyboard18

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class Keyboard18CompositionStoreTest {
    /** Minimal in-memory preferences; the store only needs reads, edits and apply(). */
    private class MemoryPreferences : SharedPreferences {
        val values = HashMap<String, Any?>()
        var reads = 0
        override fun getAll(): Map<String, *> { reads++; return HashMap(values) }
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?) = values[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val changes = LinkedHashMap<String, Any?>()
            val removed = HashSet<String>()
            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun remove(key: String) = apply { removed += key; changes.remove(key) }
            override fun clear() = apply { removed += values.keys }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { removed.forEach(values::remove); values.putAll(changes) }
        }
    }

    @Test fun `three identical compositions are learned and survive a reload`() {
        val prefs = MemoryPreferences()
        val store = Keyboard18CompositionStore(prefs)
        repeat(2) { store.record("QUANPIN:bihai", listOf("你", "好"), listOf("ni", "hao")) }
        assertTrue(store.candidates("QUANPIN:bihai").isEmpty())
        store.record("QUANPIN:bihai", listOf("你", "好"), listOf("ni", "hao"))
        val expected = listOf(Keyboard18CompositionStore.Learned("你好", "ni hao"))
        assertEquals(expected, store.candidates("QUANPIN:bihai"))
        assertTrue(store.candidates("XIAOHE:bihai").isEmpty())
        // The pending counter is dropped once the phrase is learned.
        assertEquals(1, prefs.values.size)
        assertEquals(expected, Keyboard18CompositionStore(prefs).candidates("QUANPIN:bihai"))
    }

    @Test fun `lookups do not reread preferences on every keystroke`() {
        val prefs = MemoryPreferences()
        val store = Keyboard18CompositionStore(prefs)
        repeat(50) { store.candidates("QUANPIN:b") }
        store.record("QUANPIN:b", listOf("不", "是"), listOf("bu", "shi"))
        repeat(50) { store.candidates("QUANPIN:b") }
        assertEquals(1, prefs.reads)
    }

    @Test fun `single picks are ignored and pending counts are capped`() {
        val prefs = MemoryPreferences()
        val store = Keyboard18CompositionStore(prefs)
        store.record("QUANPIN:a", listOf("啊"), listOf("a"))
        assertTrue(prefs.values.isEmpty())
        repeat(600) { store.record("QUANPIN:k$it", listOf("词", "$it"), listOf("ci", "x")) }
        assertTrue(prefs.values.size <= 512)
    }
}
