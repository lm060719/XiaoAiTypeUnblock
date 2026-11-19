package io.mo.xatype.keyboard

import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiField
import io.mo.xatype.keyboard18.GroupedKeys
import io.mo.xatype.keyboard18.Keyboard14Keys
import io.mo.xatype.keyboard18.Keyboard18Keys
import java.lang.ref.WeakReference
import java.util.IdentityHashMap

/** Transform an already built layout, after the host has resolved editor actions and key sizes. */
internal class KeyboardPositionsHostLayout(loader: ClassLoader, abi: KeyboardAbi) {
    private val keys = KeyboardShortcutHostLayout(loader, abi)
    val layout = keys.layout
    private val row = abi.type("ka.e")
    private val float = Float::class.javaPrimitiveType
    private val bool = Boolean::class.javaPrimitiveType
    private val string = String::class.java
    private val rowConstructor = row.getConstructor(float, List::class.java, List::class.java)
    private val layoutConstructor = layout.getConstructor(string, string, List::class.java, List::class.java,
        java.lang.Float::class.java, java.lang.Float::class.java, bool, java.lang.Float::class.java, bool)
    private val layoutFields = ('a'..'i').map { layout.abiField(abi, it.toString()).apply { isAccessible = true } }
    private val rowKeys = row.abiField(abi, "a").apply { isAccessible = true }
    private val rowHeight = row.abiField(abi, "b").apply { isAccessible = true }
    private val rightKeys = row.abiField(abi, "c").apply { isAccessible = true }
    private data class AppliedLayout(
        val layout: WeakReference<Any>,
        val original: Any,
        val slots: Map<Any, Any>,
    )
    // Match published layouts and keys by identity. A rebuilt key may compare equal to an unmoved key.
    private val applied = mutableListOf<AppliedLayout>()

    @Synchronized
    fun reorder(incoming: Any, settings: (KeyboardPositions) -> String?): Any {
        applied.removeAll { it.layout.get() == null }
        // The host can restore a previous layout after leaving a temporary number/symbol panel.
        val original = applied.firstOrNull { it.layout.get() === incoming }?.original ?: incoming
        val slots = IdentityHashMap<Any, Any>()
        val updated = rebuild(original, settings, slots)
        if (updated == incoming) return incoming
        if (updated !== original) applied += AppliedLayout(WeakReference(updated), original, slots)
        return updated
    }

    @Synchronized
    fun originalSlot(key: Any): Any = applied.firstNotNullOfOrNull { it.slots[key] } ?: key

    @Suppress("UNCHECKED_CAST")
    private fun rebuild(original: Any, settings: (KeyboardPositions) -> String?, slots: MutableMap<Any, Any>): Any {
        val id = layoutFields[0].get(original)
        if (id != "qwerty" && id != "t9" && id != "stroke") return original
        val rows = layoutFields[2].get(original) as List<Any>
        // Split keyboards have a separate right-hand grid and can duplicate controls.
        if (rows.any { rightKeys.get(it) != null }) return original
        val keyRows = rows.map { rowKeys.get(it) as List<Any> }
        val allKeys = keyRows.flatten()
        val profile = when (id) {
            "t9" -> KeyboardPositions.NINE
            "stroke" -> KeyboardPositions.STROKE
            else -> when (allKeys.firstNotNullOfOrNull { GroupedKeys.of(keys.code(it)) }) {
                Keyboard18Keys -> KeyboardPositions.GROUPED18
                Keyboard14Keys -> KeyboardPositions.GROUPED14
                else -> KeyboardPositions.QWERTY
            }
        }
        val moves = profile.movedKeys(settings(profile))
        if (moves.isEmpty()) return original
        val byId = allKeys.groupBy { identity(it, profile) }
        // Some variants omit or duplicate a key. Apply each cycle only if all of its slots exist.
        val applicable = moves.filterKeys { start ->
            var slot = start
            do {
                if (byId[slot]?.size != 1) return@filterKeys false
                slot = moves.getValue(slot)
            } while (slot != start)
            true
        }
        if (applicable.isEmpty()) return original
        val newRows = rows.mapIndexed { index, originalRow ->
            val items = keyRows[index]
            val replacements = items.map { slot ->
                val source = applicable[identity(slot, profile)] ?: return@map slot
                keys.keyInSlot(byId.getValue(source).single(), slot).also { slots[it] = slot }
            }
            if (items.indices.all { items[it] === replacements[it] }) originalRow
            else rowConstructor.newInstance(rowHeight.get(originalRow), replacements, null)
        }
        val args = layoutFields.map { it.get(original) }.toTypedArray()
        args[2] = newRows
        return layoutConstructor.newInstance(*args)
    }

    private fun identity(key: Any, profile: KeyboardPositions): Int? {
        val code = keys.code(key)
        val kind = keys.kind(key)
        if (kind == "GAP") return null
        if (profile == KeyboardPositions.NINE) {
            val letters = KeyboardShortcuts.t9Letters[code.toChar()] ?: return null
            return code.takeIf { kind == "CHAR" && keys.label(key).equals(letters, ignoreCase = true) }
        }
        if (profile == KeyboardPositions.STROKE) {
            // Stroke keys 1–6 use digit codes and resource-backed labels. A literal digit is a side key.
            if (code in '1'.code..'6'.code && keys.label(key) == code.toChar().toString()) return null
            return code.takeIf(profile.keys::containsKey)
        }
        val id = when (code) {
            in 'A'.code..'Z'.code -> code.toChar().lowercaseChar().code
            '，'.code -> PositionKeyCode.COMMA
            '。'.code -> PositionKeyCode.PERIOD
            '\''.code -> if (profile == KeyboardPositions.GROUPED14) PositionKeyCode.SEPARATOR else code
            else -> code
        }
        return id.takeIf(profile.keys::containsKey)
    }
}
