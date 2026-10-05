package io.mo.xatype.compat

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Maps a Kotlin data class's property labels to its obfuscated fields.
 *
 * R8 renames fields but keeps the generated toString() text, such as
 * `KeyboardColors(keyboardBackground=..., ...)`. Changing one constructor
 * argument at a time and diffing both toString() and the field values ties
 * each label to exactly one field, independent of bytecode order.
 */
internal object DataClassLabels {
    private const val BASE_COLOR = 0x11223344L shl 32
    private const val PROBE_COLOR = 0x55667788L shl 32

    /** @return label to field name; properties that cannot be told apart are omitted. */
    fun resolve(type: Class<*>): Map<String, String> {
        val constructor = primaryConstructor(type)
        val base = defaults(constructor.parameterTypes)
        val baseInstance = constructor.newInstance(*base)
        val baseLabels = parse(baseInstance.toString())
        val fields = instanceFields(type)
        val baseValues = fields.associateWith { it.get(baseInstance) }
        val result = LinkedHashMap<String, String>()

        constructor.parameterTypes.forEachIndexed { index, paramType ->
            val probe = probeValue(paramType) ?: return@forEachIndexed
            val args = base.copyOf().also { it[index] = probe }
            val instance = runCatching { constructor.newInstance(*args) }.getOrNull()
                ?: return@forEachIndexed
            val labels = parse(instance.toString())
            val label = labels.keys.singleOrNull { labels[it] != baseLabels[it] }
            val field = fields.singleOrNull { it.get(instance) != baseValues[it] }
            if (label != null && field != null) result[label] = field.name
        }
        return result
    }

    private fun primaryConstructor(type: Class<*>): Constructor<*> =
        type.declaredConstructors
            .filter { !it.isSynthetic }
            .maxByOrNull { it.parameterCount }
            ?.apply { isAccessible = true }
            ?: throw FingerprintMiss("${type.name}: no constructor")

    private fun instanceFields(type: Class<*>): List<Field> =
        type.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }

    private fun defaults(types: Array<Class<*>>): Array<Any?> = Array(types.size) {
        when (types[it]) {
            java.lang.Long.TYPE -> BASE_COLOR
            java.lang.Integer.TYPE -> 0
            java.lang.Float.TYPE -> 0f
            java.lang.Boolean.TYPE -> false
            else -> null
        }
    }

    /** A value whose toString() differs from the default; nested data classes are built recursively. */
    private fun probeValue(type: Class<*>): Any? = when (type) {
        java.lang.Long.TYPE -> PROBE_COLOR
        java.lang.Integer.TYPE -> 7
        java.lang.Float.TYPE -> 7f
        java.lang.Boolean.TYPE -> true
        else -> runCatching {
            val constructor = primaryConstructor(type)
            constructor.newInstance(*defaults(constructor.parameterTypes))
        }.getOrNull()
    }

    /** Splits `Name(a=1, b=Color(1, 2), c=null)` into labels and raw values. */
    fun parse(text: String): Map<String, String> {
        val open = text.indexOf('(')
        if (open < 0 || !text.endsWith(")")) return emptyMap()
        val body = text.substring(open + 1, text.length - 1)
        val result = LinkedHashMap<String, String>()
        var depth = 0
        var start = 0
        fun add(end: Int) {
            val part = body.substring(start, end)
            val eq = part.indexOf('=')
            if (eq > 0) result[part.substring(0, eq).trim()] = part.substring(eq + 1)
        }
        body.forEachIndexed { index, char ->
            when (char) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    add(index)
                    start = index + 1
                }
            }
        }
        add(body.length)
        return result
    }
}
