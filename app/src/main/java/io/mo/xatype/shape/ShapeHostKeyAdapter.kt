package io.mo.xatype.shape

import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiField
import io.mo.xatype.keyboard.HostKeyPath
import io.mo.xatype.keyboard.KeyOp
import io.mo.xatype.util.XposedUtils

/** Keep raw shape codes out of the host's phonetic touch correction and learning path. */
class ShapeHostKeyAdapter(loader: ClassLoader, private val bridge: ShapeHostBridge, private val abi: KeyboardAbi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_ENGINE)) {
    val path = HostKeyPath(loader, abi, bridge.host)
    val callbacksClass: Class<*> = path.callbacksClass
    val keyClass: Class<*> = path.keyClass
    // Use DEX names, not JADX's f9430a display alias.
    private val code = keyClass.abiField(abi, "a").apply { isAccessible = true }
    private val kind = keyClass.abiField(abi, "f").apply { isAccessible = true }

    init {
        require(code.type == Int::class.javaPrimitiveType && kind.type.isEnum)
    }

    /** The character our session handles for this key; null for native controls and other engines. */
    private fun character(key: Any, callbacks: Any, engine: ShapeEngine): Char? {
        val ascii = code.getInt(key)
        val keyKind = (kind.get(key) as Enum<*>).name
        return when {
            keyKind == "CHAR" && ascii in 97..122 && !path.uppercase(callbacks) -> ascii.toChar()
            keyKind == "CHAR" && engine.code.isNotEmpty() && (ascii in 48..57 || ascii == 59 || ascii == 39) -> ascii.toChar()
            keyKind == "SPACE" && engine.code.isNotEmpty() -> ' '
            keyKind == "BACKSPACE" && engine.code.isNotEmpty() -> '\b'
            else -> null
        }
    }

    /** Through 0.2.1246: true only if we handled this key. Native controls and other engines return false. */
    fun handle(key: Any, callbacks: Any): Boolean {
        check(!path.suspending)
        val proxy = path.engine(callbacks) ?: return false
        val engine = bridge.session(proxy) ?: return false
        val character = character(key, callbacks, engine) ?: return false
        val result = bridge.input(proxy, character)
        if (!result.consumed) return false
        path.deliverNow(callbacks, bridge.result(result))
        return true
    }

    /** The hooked key handler: our keys go to the shape engine, everything else to the host. */
    fun intercept(module: XposedInterface, chain: XposedInterface.Chain): Any? {
        if (!path.suspending) return if (handle(chain.args[0]!!, chain.args[1]!!)) null else chain.proceed()
        if (path.resuming(chain)) return chain.proceed()
        val key = chain.args[0]!!
        val callbacks = chain.args[1]!!
        val engine = bridge.session(path.engine(callbacks)) ?: return chain.proceed()
        val character = character(key, callbacks, engine) ?: return chain.proceed()
        // From 0.2.1358 the session runs the engine and keeps its snapshot current; an
        // unconsumed letter or digit is committed literally, as the native key path does.
        return path.perform(module, chain, listOf(KeyOp.Engine({ error("unused") },
            { bridge.host.key(character.code) }, literal = character.toString().takeIf { character.isLetterOrDigit() }))) {
            XposedUtils.logError(module, "Shape key delivery failed", it)
        }
    }
}
