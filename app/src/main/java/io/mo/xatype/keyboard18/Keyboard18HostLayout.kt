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
    private val fields = ('a'..'r').associate { it.toString() to abi.field(key, it.toString()) }.onEach { it.value.isAccessible = true }
    private fun value(instance: Any, name: String): Any? = instance.javaClass.abiField(abi, name)
        .apply { isAccessible = true }.get(instance)

    /** Require the generated code/label/hint combination, so other layouts keep native styling. */
    fun groupOf(instance: Any): String? {
        if (!key.isInstance(instance) || fields.getValue("f").get(instance) !== charKind) return null
        val code = fields.getValue("a").getInt(instance)
        val group = Keyboard18Keys.group(code) ?: Keyboard18Keys.groups.firstOrNull {
            it.first() == code.toChar().lowercaseChar()
        } ?: return null
        val label = fields.getValue("b").get(instance)
        if (label != Keyboard18Style.label(group) && label != Keyboard18Style.label(group, true)) return null
        return group.takeIf { fields.getValue("g").get(instance) == Keyboard18Keys.hints[Keyboard18Keys.groups.indexOf(group)] }
    }
    fun rebuild(original: Any, english: Boolean, semicolon: Boolean = false): Any {
        if (value(original, "a") != "qwerty") return original
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
        fun newKey(code: Int, label: String, weight: Float, hint: String?, popup: List<String>?): Any =
            newKeyData(code, label, weight, null, height, charKind, hint,
                hint?.takeIf { it.length == 1 }, popup, 0, null, false, english, false, false, null, false, null)
        fun resized(source: Any, fixedWidth: Float?, weight: Float): Any = newKeyData(
            fields.getValue("a").get(source), fields.getValue("b").get(source), weight, fixedWidth,
            fields.getValue("e").get(source), fields.getValue("f").get(source), fields.getValue("g").get(source),
            fields.getValue("h").get(source), fields.getValue("i").get(source), fields.getValue("j").get(source),
            fields.getValue("k").get(source), fields.getValue("l").get(source), fields.getValue("m").get(source),
            fields.getValue("n").get(source), fields.getValue("o").get(source), fields.getValue("p").get(source),
            fields.getValue("q").get(source), fields.getValue("r").get(source),
            absorbs = absorbsGap?.getBoolean(source) ?: false)
        val newRows = Keyboard18Keys.rows.mapIndexed { index, groups ->
            val keys = groups.map { group ->
                val hint = Keyboard18Keys.hints[Keyboard18Keys.groups.indexOf(group)]
                val popup = group.map { if (uppercase) it.uppercaseChar().toString() else it.toString() } +
                    listOf(hint).filter { it.length == 1 } +
                    if (!english && !uppercase && semicolon && group == "l") listOf(";") else emptyList()
                newKey(if (english) (if (uppercase) group.first().uppercaseChar() else group.first()).code else Keyboard18Keys.code(group),
                    Keyboard18Style.label(group, true), Keyboard18Style.weight(group), hint, popup)
            }.toMutableList()
            // 7/6/5 rows all span the keyboard width; the middle row's six keys are widened to match.
            if (index == 2) { keys.add(0, resized(shift, null, Keyboard18Style.CONTROL_WEIGHT))
                keys.add(resized(delete, null, Keyboard18Style.CONTROL_WEIGHT)) }
            val total = keys.sumOf { fields.getValue("c").getFloat(it).toDouble() }.toFloat()
            // Native GAP items occupy width without adding a key separator.
            val visibleCount = keys.count { fields.getValue("f").get(it) !== gapKind }
            val available = width?.let { (it - spacing * (visibleCount - 1)).coerceAtLeast(1f) }
            val sized = keys.map { item -> val weight = fields.getValue("c").getFloat(item)
                resized(item, available?.let { it * weight / total }, weight) }
            rowConstructor.newInstance(value(rows[index], "b"), sized, null)
        } + rows.last()
        return layoutConstructor.newInstance("qwerty", "18键", newRows, value(original, "d"),
            value(original, "e"), value(original, "f"), value(original, "g"), value(original, "h"), value(original, "i"))
    }
}
