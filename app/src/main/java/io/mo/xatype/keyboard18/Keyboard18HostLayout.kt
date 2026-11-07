package io.mo.xatype.keyboard18

import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiField

/** Build host-native KeyData/KeyRow/KeyboardLayout objects, preserving the control row. */
class Keyboard18HostLayout(loader: ClassLoader, private val abi: KeyboardAbi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_LAYOUT)) {
    private val key = abi.type("ka.c")
    private val kind = abi.type("ka.f")
    private val row = abi.type("ka.e")
    val layout = abi.type("ka.g")
    private val int = Int::class.javaPrimitiveType!!
    private val float = Float::class.javaPrimitiveType!!
    private val bool = Boolean::class.javaPrimitiveType!!
    private val string = String::class.java
    // 0.2.1246 appended absorbsAdjacentGap; null on builds without it.
    private val absorbsGap = abi.fieldOrNull(key, "s")?.apply { isAccessible = true }
    private val keyConstructor = key.getConstructor(int, string, float, java.lang.Float::class.java,
        java.lang.Float::class.java, kind, string, string, List::class.java, Integer::class.java,
        Integer::class.java, bool, java.lang.Boolean::class.java, bool, bool, Integer::class.java, bool, Integer::class.java,
        *listOfNotNull(absorbsGap?.let { bool }).toTypedArray())
    private fun newKeyData(vararg values: Any?, absorbs: Boolean = false): Any =
        keyConstructor.newInstance(*values, *(if (absorbsGap != null) arrayOf<Any?>(absorbs) else emptyArray()))
    private val rowConstructor = row.getConstructor(float, List::class.java, List::class.java)
    private val layoutConstructor = layout.getConstructor(string, string, List::class.java, List::class.java,
        java.lang.Float::class.java, java.lang.Float::class.java, bool, java.lang.Float::class.java, bool)
    private val charKind = kind.enumConstants.single { (it as Enum<*>).name == "CHAR" }
    private val gapKind = kind.enumConstants.single { (it as Enum<*>).name == "GAP" }
    // Function keys take the host's grey control background, like shift and delete.
    private val functionKind = kind.enumConstants.singleOrNull { (it as Enum<*>).name == "FUNCTION" } ?: charKind
    private val fields = ('a'..'r').associate { it.toString() to abi.field(key, it.toString()) }.onEach { it.value.isAccessible = true }
    private fun value(instance: Any, name: String): Any? = instance.javaClass.abiField(abi, name)
        .apply { isAccessible = true }.get(instance)

    /** Require the generated code/label/hint combination, so other layouts keep native styling. */
    fun groupOf(instance: Any): String? {
        if (!key.isInstance(instance) || fields.getValue("f").get(instance) !== charKind) return null
        val code = fields.getValue("a").getInt(instance)
        val label = fields.getValue("b").get(instance)
        val hint = fields.getValue("g").get(instance)
        // Plain letter codes keep the original 18-key fallback only; 14-key hints such as "?"
        // also appear on native 26-key letters, which must keep native styling.
        val owners = GroupedKeys.of(code)?.let(::listOf) ?: listOf(Keyboard18Keys)
        return owners.firstNotNullOfOrNull { keys ->
            val group = keys.groupOf(code) ?: return@firstNotNullOfOrNull null
            group.takeIf { (label == Keyboard18Style.label(group) || label == Keyboard18Style.label(group, true)) &&
                hint == keys.hint(group) }
        }
    }
    fun rebuild(original: Any, english: Boolean, semicolon: Boolean = false, keys: GroupedKeys = Keyboard18Keys): Any {
        if (english || value(original, "a") != "qwerty") return original
        @Suppress("UNCHECKED_CAST")
        val rows = value(original, "c") as List<Any>
        if (rows.size != 4 || rows.any { value(it, "c") != null }) return original // Split-pad layout is retained.
        @Suppress("UNCHECKED_CAST")
        val originals = rows.flatMap { value(it, "a") as List<Any> }
        val letters = originals.filter { fields.getValue("a").getInt(it).toChar().lowercaseChar() in 'a'..'z' }
        if (letters.size != 26) return original
        val template = letters.first()
        val shift = originals.firstOrNull { fields.getValue("a").getInt(it) == -1 } ?: return original
        val delete = originals.firstOrNull { fields.getValue("a").getInt(it) == -2 } ?: return original
        val height = fields.getValue("e").get(template)
        val spacing = (value(original, "f") as? Float) ?: Keyboard18Style.FALLBACK_SPACING
        @Suppress("UNCHECKED_CAST")
        val first = value(rows[0], "a") as List<Any>
        val fixed = first.map { fields.getValue("d").get(it) as? Float }
        val width = if (fixed.all { it != null }) fixed.sumOf { it!!.toDouble() }.toFloat() + spacing * (first.size - 1) else null
        val uppercase = letters.any { fields.getValue("a").getInt(it).toChar() in 'A'..'Z' }
        fun newKey(code: Int, label: String, weight: Float, hint: String?, popup: List<String>?, type: Any = charKind): Any =
            newKeyData(code, label, weight, null, height, type, hint,
                hint?.takeIf { it.length == 1 }, popup, 0, null, false, english, false, false, null, false, null)
        fun resized(source: Any, fixedWidth: Float?, weight: Float): Any = newKeyData(
            fields.getValue("a").get(source), fields.getValue("b").get(source), weight, fixedWidth,
            fields.getValue("e").get(source), fields.getValue("f").get(source), fields.getValue("g").get(source),
            fields.getValue("h").get(source), fields.getValue("i").get(source), fields.getValue("j").get(source),
            fields.getValue("k").get(source), fields.getValue("l").get(source), fields.getValue("m").get(source),
            fields.getValue("n").get(source), fields.getValue("o").get(source), fields.getValue("p").get(source),
            fields.getValue("q").get(source), fields.getValue("r").get(source),
            absorbs = absorbsGap?.getBoolean(source) ?: false)
        // Native row 3 sets shift and delete apart from the letters with fixed-width side gaps.
        @Suppress("UNCHECKED_CAST")
        val sideGaps = (value(rows[2], "a") as List<Any>).filter {
            fields.getValue("f").get(it) === gapKind && fields.getValue("d").get(it) != null }
        val newRows = keys.rows.mapIndexed { index, groups ->
            val items = groups.map { group ->
                val hint = keys.hint(group)
                val popup = group.map { if (uppercase) it.uppercaseChar().toString() else it.toString() } +
                    listOf(hint).filter { it.length == 1 } +
                    if (!uppercase && semicolon && group == keys.semicolonGroup) listOf(";") else emptyList()
                newKey(keys.code(group),
                    Keyboard18Style.label(group, true), Keyboard18Style.weight(group), hint, popup)
            }.toMutableList()
            // Every letter row spans the keyboard width; shorter rows are widened to match.
            if (index == 2 && keys.separatorKey) {
                // 14-key puts the syllable separator where shift sits, styled as a control key, and
                // keeps the native side gaps so the four letters stand apart like the 26-key row.
                if (sideGaps.size == 2) { items.add(0, sideGaps[0]); items.add(sideGaps[1]) }
                // The host only dispatches function keys it knows; -8 is its own segmentation key.
                items.add(0, newKey(if (functionKind === charKind) SEPARATOR.code else SEPARATOR_CODE,
                    SEPARATOR.toString(), keys.controlWeight, null, null, functionKind))
                items.add(resized(delete, null, keys.controlWeight))
            } else if (index == 2) {
                items.add(0, resized(shift, null, keys.controlWeight))
                items.add(resized(delete, null, keys.controlWeight))
            }
            fun isGap(item: Any) = fields.getValue("f").get(item) === gapKind
            val total = items.filterNot(::isGap).sumOf { fields.getValue("c").getFloat(it).toDouble() }.toFloat()
            // Native GAP items keep their fixed width and add no key separator.
            val visibleCount = items.count { !isGap(it) }
            val gapWidth = items.filter(::isGap).sumOf { (fields.getValue("d").get(it) as? Float ?: 0f).toDouble() }.toFloat()
            val available = width?.let { (it - spacing * (visibleCount - 1) - gapWidth).coerceAtLeast(1f) }
            val sized = items.map { item -> if (isGap(item)) item else { val weight = fields.getValue("c").getFloat(item)
                resized(item, available?.let { it * weight / total }, weight) } }
            rowConstructor.newInstance(value(rows[index], "b"), sized, null)
        } + rows.last()
        return layoutConstructor.newInstance("qwerty", keys.title, newRows, value(original, "d"),
            value(original, "e"), value(original, "f"), value(original, "g"), value(original, "h"), value(original, "i"))
    }
    companion object {
        const val SEPARATOR = '\''
        /** The host's native segmentation key code, reused for the 14-key separator. */
        const val SEPARATOR_CODE = -8
        fun isSeparator(code: Int) = code == SEPARATOR_CODE || code == SEPARATOR.code
    }
}
