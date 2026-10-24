package io.mo.xatype.keyboard18

import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiField
import io.mo.xatype.compat.abiMethod

/** Resolved Compose hooks scoped to one generated letter key at a time. */
class Keyboard18HostAppearance(private val layouts: Keyboard18HostLayout) {
    private val drawing = ThreadLocal<Boolean>()

    fun install(module: XposedInterface, loader: ClassLoader) {
        val abi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_LETTER_APPEARANCE)
        val key = abi.type("ka.c")
        val composer = abi.type("s0.p")
        val modifier = abi.type("e1.o")
        val alignment = abi.type("e1.g")
        // 30 parameters through 0.2.1053, 32 from 0.2.1246; only the key is read.
        val renderer = abi.namedMethods(abi.type("z9.w5"), "a").single {
            it.parameterCount >= 30 && it.parameterTypes.first() == key
        }
        val sizing = abi.type("ma.v")
        val typography = sizing.abiMethod(abi, "Q", Int::class.javaPrimitiveType, composer)
        val withHint = sizing.abiMethod(abi, "h0", composer)
        val box = abi.type("androidx.compose.foundation.layout.a")
        val align = box.abiMethod(abi, "a", modifier, alignment)
        val positions = abi.type("e1.b")
        val topCenter = positions.abiField(abi, "b").get(null)
        val topEnd = positions.abiField(abi, "c").get(null)

        module.hook(renderer).intercept { chain ->
            val previous = drawing.get()
            drawing.set(layouts.groupOf(chain.getArg(0)!!) != null)
            try { chain.proceed() } finally {
                if (previous == null) drawing.remove() else drawing.set(previous)
            }
        }
        module.deoptimize(renderer)
        // From 0.2.1246, letter keys on the "qwerty" layout take their label from the key code and shift
        // state. English 18-key groups type their first letter, so "we" would render as "w"; label the
        // whole group instead, following the host's case.
        val bool = Boolean::class.javaPrimitiveType
        renderer.declaringClass.declaredMethods.singleOrNull {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == String::class.java &&
                it.parameterCount == 4 && it.parameterTypes[0] == key &&
                it.parameterTypes[1] == bool && it.parameterTypes[2] == bool
        }?.let { codeLabel ->
            module.hook(codeLabel).intercept { chain ->
                val native = chain.proceed() as String?
                if (drawing.get() != true || native == null) return@intercept native
                val group = layouts.groupOf(chain.getArg(0)!!) ?: return@intercept native
                Keyboard18Style.label(group, native.any(Char::isUpperCase))
            }
            module.deoptimize(codeLabel)
        }
        module.hook(typography).intercept { chain ->
            val native = chain.proceed() as Long
            if (drawing.get() == true) Keyboard18Style.scaleTextUnit(native) else native
        }
        module.deoptimize(typography)
        // h0 delegates to Q for keys with hints; keep that call from being inlined.
        module.deoptimize(withHint)
        module.hook(align).intercept { chain ->
            if (drawing.get() == true && chain.getArg(1) === topEnd) {
                chain.proceed(chain.args.toTypedArray().apply { this[1] = topCenter })
            } else chain.proceed()
        }
        module.deoptimize(align)
    }
}
