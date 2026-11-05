package io.mo.xatype.compat

import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.OpCodeMatchType
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexClass

/**
 * Reference IDs identify bridge operations, never host names to fall back to.
 * Witnesses match bytecode and strings, then follow instruction-order call/field
 * references. Renaming a class, method or field does not change these witnesses.
 * Each [KeyboardAbiReference] variant holds the witnesses of one host build;
 * a group takes the first variant that resolves completely.
 */
internal class KeyboardAbiFingerprints(private val bridge: DexKitBridge) {
    private val variants = KeyboardAbiReference.variants.map { Variant(bridge, it) }

    fun resolve(group: String): List<String> {
        val misses = ArrayList<String>()
        for (variant in variants) {
            try {
                return variant.resolve(group)
            } catch (miss: FingerprintMiss) {
                misses += miss.message.orEmpty()
            }
        }
        throw FingerprintMiss(misses.joinToString(" | "))
    }
}

/** Resolves IDs with the witnesses of one reference build. */
private class Variant(private val bridge: DexKitBridge, private val reference: JSONObject) {
    private val resolved = HashMap<String, String>()
    private val anchors = HashMap<String, Result<MethodData>>()

    fun resolve(group: String): List<String> = KeyboardAbiReference.keys(group, reference).map { id ->
        "$id=${symbol(id)}"
    }

    private fun symbol(id: String): String = resolved.getOrPut(id) {
        val rule = reference.getJSONObject("rules").getJSONObject(id)
        when {
            rule.has("stable") -> rule.getString("stable")
            rule.has("owner") -> {
                val owner = bridge.getClassData(symbol("c:" + rule.getString("owner")))
                    ?: throw FingerprintMiss("$id: owner unavailable")
                single(owner.methods.filter { shape(it) == rule.getString("shape") }, id).descriptor
            }
            else -> {
                val method = anchor(rule.getString("anchor"))
                val index = rule.optInt("index", -1)
                when (rule.getString("extract")) {
                    "method" -> method.descriptor
                    "owner" -> checkNotNull(method.declaredClass).descriptor
                    "invoke" -> method.invokes.getOrNull(index)?.descriptor
                    "invokeOwner" -> method.invokes.getOrNull(index)?.declaredClass?.descriptor
                    "field" -> method.usingFields.getOrNull(index)?.field?.descriptor
                    "fieldOwner" -> method.usingFields.getOrNull(index)?.field?.declaredClass?.descriptor
                    "returnType" -> bridge.getClassData(method.returnTypeName)?.descriptor
                    "parameter" -> method.paramTypeNames.getOrNull(index)?.let { bridge.getClassData(it)?.descriptor }
                    else -> null
                } ?: throw FingerprintMiss("$id: witness operand unavailable")
            }
        }
    }

    private fun anchor(id: String): MethodData = anchors.getOrPut(id) {
        runCatching {
            val ref = reference.getJSONObject("anchors").getJSONObject(id)
            val codes = ref.getJSONArray("opcodes").let { array -> List(array.length()) { array.getInt(it) } }
            val strings = ref.getJSONArray("strings").let { array -> List(array.length()) { array.getString(it) } }
            val scope = ref.optString("scope").takeIf { it.isNotEmpty() }?.let { symbol("c:$it") }
            val shape = ref.getString("shape")
            val parameterCount = shape.substringAfterLast(':').let { if (it.isEmpty()) 0 else it.split(',').size }
            val candidates = bridge.findMethod {
                matcher {
                    paramCount(parameterCount)
                    // Reassembly/R8 can widen const-string after string-pool reordering.
                    // Match strings natively, then compare normalized opcode widths.
                    if (codes.none { it == 0x1a || it == 0x1b }) opCodes(codes, OpCodeMatchType.Equals)
                    if (strings.isNotEmpty()) usingEqStrings(strings)
                    if (scope != null) declaredClass(DexClass(scope).typeName)
                }
            }.filter { shape(it) == shape && normalize(it.opCodes) == normalize(codes) }
            single(candidates, "keyboard witness $id")
        }
    }.getOrThrow()

    private fun normalize(codes: List<Int>) = codes.map { if (it == 0x1b) 0x1a else it }

    private fun shape(method: MethodData): String =
        "${java.lang.reflect.Modifier.isStatic(method.modifiers)}:${coarse(method.returnTypeName)}:" +
            method.paramTypeNames.joinToString(",", transform = ::coarse)

    private fun coarse(type: String): String =
        if ('.' !in type || type.startsWith("java.") || type.startsWith("android.") ||
            type.startsWith("com.mi.") || type.startsWith("kotlin.jvm.functions.")) type else "*"
}

internal object KeyboardAbiReference {
    /** Witnesses from verified host builds, newest first; all share the same ID labels. */
    private val resources = listOf("/xatype/keyboard-abi-21358.json", "/xatype/keyboard-abi-21246.json",
        "/xatype/keyboard-abi.json")

    val variants: List<JSONObject> by lazy {
        resources.map { name ->
            val stream = checkNotNull(javaClass.getResourceAsStream(name)) {
                "Keyboard ABI reference resource unavailable: $name"
            }
            stream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        }
    }

    /** The 0.2.1053 reference; its IDs name the desktop fixtures. */
    val data: JSONObject get() = variants.last()

    fun keys(group: String, reference: JSONObject = data): List<String> =
        reference.getJSONObject("groups").optJSONArray(group)?.let { array ->
            List(array.length()) { array.getString(it) }
        } ?: throw FingerprintMiss("$group: not in this reference")

    /** The variant whose IDs for [group] are exactly [ids]; a profile records no variant name. */
    fun variant(group: String, ids: Set<String>): JSONObject? = variants.firstOrNull { reference ->
        runCatching { keys(group, reference).toSet() == ids }.getOrDefault(false)
    }
}
