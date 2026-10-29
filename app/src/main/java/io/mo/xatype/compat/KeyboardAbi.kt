package io.mo.xatype.compat

import org.luckypray.dexkit.wrap.DexClass
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Typed access to the resolved keyboard profile. Reference IDs are not runtime fallbacks. */
class KeyboardAbi private constructor(private val loader: ClassLoader, private val symbols: Map<String, String>) {
    private val classes = ConcurrentHashMap<String, Class<*>>()
    private val methods = ConcurrentHashMap<String, Method>()
    private val fields = ConcurrentHashMap<String, Field>()
    private val aliases = ConcurrentHashMap<Class<*>, String>()
    private val canonicalNames = ConcurrentHashMap<Method, String>()

    fun type(id: String): Class<*> = classes.getOrPut(id) {
        val descriptor = symbols["c:$id"]
        if (descriptor != null) DexClass(descriptor).getInstance(loader)
        else {
            require(id.startsWith("android.") || id.startsWith("java.") || id.startsWith("com.mi.") ||
                id.startsWith("kotlin.jvm.functions.")) { "Unresolved keyboard type: $id" }
            Class.forName(id, false, loader)
        }
    }

    private fun alias(type: Class<*>): String = aliases.getOrPut(type) {
        symbols.entries.singleOrNull {
            it.key.startsWith("c:") && DexClass(it.value).typeName == type.name
        }?.key?.removePrefix("c:") ?: error("Keyboard type has no reference ID: ${type.name}")
    }

    fun method(type: Class<*>, name: String, vararg parameters: Class<*>?): Method {
        require(parameters.none { it == null }) { "Null keyboard parameter type" }
        val prefix = "m:${alias(type)}#$name("
        val direct = symbols.keys.filter { it.startsWith(prefix) }
        val ids = direct.ifEmpty {
            val ancestors = HashSet<String>()
            fun visit(owner: Class<*>) {
                if (!ancestors.add(owner.name)) return
                owner.interfaces.forEach(::visit)
                owner.superclass?.let(::visit)
            }
            visit(type)
            val owners = symbols.filter { (id, descriptor) -> id.startsWith("c:") && DexClass(descriptor).typeName in ancestors }
                .keys.map { it.removePrefix("c:") }.toSet()
            symbols.keys.filter { it.startsWith("m:") && it.substringAfter("m:").substringBefore('#') in owners &&
                it.substringAfter('#').startsWith("$name(") }
        }
        val candidates = ids.map { id ->
            methods.getOrPut(id) { DexMethod(symbols.getValue(id)).getMethodInstance(loader).apply { isAccessible = true } }
        }.filter { it.declaringClass.isAssignableFrom(type) && it.parameterTypes.contentEquals(parameters) }.distinct()
        return candidates.singleOrNull() ?: error("Unresolved keyboard method: $prefix (${candidates.size} matches)")
    }

    /**
     * Resolve by owner ID instead of by class, for IDs that share a host class
     * with another ID in some builds (see `qh.y`).
     */
    fun method(owner: String, name: String, vararg parameters: Class<*>): Method {
        val prefix = "m:$owner#$name("
        val candidates = namedMethods(owner, name).filter { it.parameterTypes.contentEquals(parameters) }
        return candidates.singleOrNull() ?: error("Unresolved keyboard method: $prefix (${candidates.size} matches)")
    }

    /** Null when no ID names this field, e.g. a member added by a later host build. */
    fun fieldOrNull(type: Class<*>, name: String): Field? =
        if (symbols.containsKey("f:${alias(type)}#$name")) field(type, name) else null

    fun namedMethods(type: Class<*>, name: String): List<Method> = namedMethods(alias(type), name)

    /** Owner IDs remain unambiguous when multiple IDs map to the same host class. */
    fun namedMethods(owner: String, name: String): List<Method> {
        val prefix = "m:$owner#$name("
        return symbols.keys.filter { it.startsWith(prefix) }.map { id ->
            methods.getOrPut(id) { DexMethod(symbols.getValue(id)).getMethodInstance(loader).apply { isAccessible = true } }
        }.distinct()
    }

    fun field(type: Class<*>, name: String): Field {
        val id = "f:${alias(type)}#$name"
        return fields.getOrPut(id) {
            DexField(symbols[id] ?: error("Unresolved keyboard field: $id"))
                .getFieldInstance(loader).apply { isAccessible = true }
        }
    }

    /** Proxy dispatch follows the resolved method identity, including renamed interfaces. */
    fun canonical(method: Method): String = canonicalNames.getOrPut(method) {
        if (method.name in setOf("equals", "hashCode", "toString")) return@getOrPut method.name
        symbols.entries.firstOrNull { (id, descriptor) ->
            id.startsWith("m:") && DexMethod(descriptor).className == method.declaringClass.name && methods.getOrPut(id) {
                DexMethod(descriptor).getMethodInstance(loader).apply { isAccessible = true }
            } == method
        }?.key?.substringAfter('#')?.substringBefore('(')
            ?: error("Unknown keyboard callback: $method")
    }

    companion object {
        val groups = listOf(HostSymbols.KEYBOARD_ENGINE, HostSymbols.KEYBOARD_LAYOUT, HostSymbols.KEYBOARD_ACTIONS,
            HostSymbols.KEYBOARD_PICKER, HostSymbols.KEYBOARD_SETTINGS, HostSymbols.KEYBOARD_SWIPE,
            HostSymbols.KEYBOARD_LETTER_APPEARANCE, HostSymbols.KEYBOARD_SURFACE_TEXT)

        fun require(loader: ClassLoader, vararg required: String): KeyboardAbi {
            required.forEach { check(HostSymbols.has(it)) { "Keyboard ABI unavailable: $it" } }
            val combined = LinkedHashMap<String, String>()
            groups.forEach { group ->
                HostSymbols.strings(group)?.let { values ->
                    parse(values).forEach { (id, descriptor) ->
                        check(combined[id] == null || combined[id] == descriptor) { "Conflicting keyboard ABI: $id" }
                        combined[id] = descriptor
                    }
                }
            }
            return KeyboardAbi(loader, combined)
        }

        /** The QWERTY layout factory by its leading parameters; 0.2.1246 dropped the trailing shift state. */
        fun qwertyFactory(abi: KeyboardAbi): Method {
            val prefix = listOf(String::class.java, Integer::class.java, String::class.java, java.lang.Boolean.TYPE)
            return abi.namedMethods(abi.type("ab.p1"), "e").single {
                it.parameterTypes.take(4) == prefix && it.returnType == abi.type("ka.g")
            }
        }

        /** Explicit fixture injection; production hooks always use [require]. */
        internal fun reference(loader: ClassLoader): KeyboardAbi = KeyboardAbi(loader,
            KeyboardAbiReference.data.getJSONObject("reference").let { json ->
                json.keys().asSequence().associateWith { json.getString(it) }
            })

        private fun parse(values: List<String>): Map<String, String> {
            require(values.all { '=' in it }) { "Malformed keyboard ABI profile" }
            val result = values.associate { it.substringBefore('=') to it.substringAfter('=') }
            require(result.size == values.size) { "Duplicate keyboard ABI ID" }
            return result
        }

        internal fun validate(group: String, values: List<String>, loader: ClassLoader): Boolean = runCatching {
            val profile = parse(values)
            val variant = requireNotNull(KeyboardAbiReference.variant(group, profile.keys)) {
                "Incomplete keyboard ABI profile"
            }
            val reference = variant.getJSONObject("reference")
            fun compatible(expected: String, actual: Class<*>): Boolean {
                val mapped = profile["c:$expected"]
                return if (mapped != null) DexClass(mapped).typeName == actual.name
                else if (expected.startsWith("java.") || expected.startsWith("android.") ||
                    expected.startsWith("com.mi.") || '.' !in expected) expected == actual.name
                else true
            }
            profile.forEach { (id, descriptor) ->
                val rule = variant.getJSONObject("rules").getJSONObject(id)
                when {
                    id.startsWith("c:") -> {
                        val type = DexClass(descriptor).getInstance(loader)
                        require(type.modifiers and (0x0200 or 0x0400 or 0x4000) == rule.getInt("kind")) {
                            "Incompatible keyboard class: $id"
                        }
                    }
                    id.startsWith("m:") -> {
                        val method = DexMethod(descriptor).getMethodInstance(loader).apply { isAccessible = true }
                        val expected = DexMethod(reference.getString(id))
                        val ownerId = id.removePrefix("m:").substringBefore('#')
                        val owner = DexClass(profile.getValue("c:$ownerId")).getInstance(loader)
                        require(method.declaringClass.isAssignableFrom(owner)) { "Incompatible keyboard method owner: $id" }
                        require(java.lang.reflect.Modifier.isStatic(method.modifiers) == rule.getBoolean("static"))
                        require(expected.paramTypeNames.size == method.parameterCount &&
                            expected.paramTypeNames.zip(method.parameterTypes).all { compatible(it.first, it.second) } &&
                            compatible(expected.returnTypeName, method.returnType)) { "Incompatible keyboard method signature: $id" }
                    }
                    id.startsWith("f:") -> {
                        val field = DexField(descriptor).getFieldInstance(loader).apply { isAccessible = true }
                        val ownerId = id.removePrefix("f:").substringBefore('#')
                        val owner = DexClass(profile.getValue("c:$ownerId")).getInstance(loader)
                        require(java.lang.reflect.Modifier.isStatic(field.modifiers) == rule.getBoolean("static"))
                        require(field.declaringClass.isAssignableFrom(owner) &&
                            compatible(DexField(reference.getString(id)).typeName, field.type)) { "Incompatible keyboard field: $id" }
                    }
                    else -> error("Unknown keyboard ABI ID: $id")
                }
            }
        }.isSuccess
    }
}

internal fun Class<*>.abiMethod(abi: KeyboardAbi, name: String, vararg parameters: Class<*>?): Method =
    abi.method(this, name, *parameters)

internal fun Class<*>.abiField(abi: KeyboardAbi, name: String): Field = abi.field(this, name)
