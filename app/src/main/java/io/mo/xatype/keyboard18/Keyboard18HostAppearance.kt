package io.mo.xatype.keyboard18

import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiField
import io.mo.xatype.compat.abiMethod
import java.lang.reflect.Modifier

/** Resolved Compose hooks scoped to one generated letter key at a time. */
class Keyboard18HostAppearance(private val layouts: Keyboard18HostLayout) {
    private data class LetterRender(val label: String, val useQwertySize: Boolean)

    private val drawing = ThreadLocal<LetterRender>()

    fun install(module: XposedInterface, loader: ClassLoader) {
        val abi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_LETTER_APPEARANCE)
        val key = abi.type("ka.c")
        val composer = abi.type("s0.p")
        val modifier = abi.type("e1.o")
        val alignment = abi.type("e1.g")
        val keyCode = key.abiField(abi, "a")
        // 30 parameters through 0.2.1053, 32 from 0.2.1246; only the key is read.
        val renderer = abi.namedMethods(abi.type("z9.w5"), "a").single {
            it.parameterCount >= 30 && it.parameterTypes.first() == key
        }
        val sizing = abi.type("ma.v")
        val typography = sizing.abiMethod(abi, "Q", Int::class.javaPrimitiveType, composer)
        val withHint = sizing.abiMethod(abi, "h0", composer)
        val textPrefix = listOf(String::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, modifier)
        val letterText = runCatching { abi.type("z9.u7") }.getOrNull()?.declaredMethods?.singleOrNull {
            Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE && it.parameterCount == 16 &&
                it.parameterTypes.take(4) == textPrefix && it.parameterTypes[12] == composer
        }
        val box = abi.type("androidx.compose.foundation.layout.a")
        val align = box.abiMethod(abi, "a", modifier, alignment)
        val positions = abi.type("e1.b")
        val topCenter = positions.abiField(abi, "b").get(null)
        val topEnd = positions.abiField(abi, "c").get(null)

        module.hook(renderer).intercept { chain ->
            val previous = drawing.get()
            val hostKey = chain.getArg(0)!!
            drawing.set(layouts.groupOf(hostKey)?.let { group ->
                LetterRender(Keyboard18Style.label(group, true),
                    letterText != null && GroupedKeys.of(keyCode.getInt(hostKey)) !== Keyboard14Keys)
            })
            try { chain.proceed() } finally {
                if (previous == null) drawing.remove() else drawing.set(previous)
            }
        }
        module.deoptimize(renderer)
        // From 0.2.1246 QWERTY labels can be derived from key codes. Generated Chinese
        // groups must retain their full uppercase label; native English keys are untouched.
        val bool = Boolean::class.javaPrimitiveType
        renderer.declaringClass.declaredMethods.singleOrNull {
            Modifier.isStatic(it.modifiers) && it.returnType == String::class.java &&
                it.parameterCount == 4 && it.parameterTypes[0] == key &&
                it.parameterTypes[1] == bool && it.parameterTypes[2] == bool
        }?.let { codeLabel ->
            module.hook(codeLabel).intercept { chain ->
                val native = chain.proceed() as String?
                if (drawing.get() == null || native == null) return@intercept native
                val group = layouts.groupOf(chain.getArg(0)!!) ?: return@intercept native
                Keyboard18Style.label(group, true)
            }
            module.deoptimize(codeLabel)
        }
        module.hook(typography).intercept { chain ->
            val native = chain.proceed() as Long
            if (drawing.get()?.useQwertySize == false) Keyboard18Style.scaleTextUnit(native) else native
        }
        module.deoptimize(typography)
        // Keep calls to Q from the host's hinted-key size helper visible to the hook.
        module.deoptimize(withHint)
        letterText?.let { text ->
            module.hook(text).intercept { chain ->
                val letter = drawing.get() ?: return@intercept chain.proceed()
                if (!letter.useQwertySize || chain.getArg(0) != letter.label) return@intercept chain.proceed()

                // The host may choose T9 sizing and shrink longer labels before calling Text.
                // Resolve the QWERTY size with the current Composer so it follows host scaling.
                val args = chain.args.toTypedArray()
                val qwertySize = typography.invoke(null, 0, args[12]) as Long
                args[1] = Keyboard18Style.scaleTextUnit(qwertySize, Keyboard18Style.QWERTY_TEXT_SCALE)
                args[13] = (args[13] as Int) and 0x70.inv() // Re-compare the font size in Compose.
                chain.proceed(args)
            }
            module.deoptimize(text)
        }
        module.hook(align).intercept { chain ->
            if (drawing.get() != null && chain.getArg(1) === topEnd) {
                chain.proceed(chain.args.toTypedArray().apply { this[1] = topCenter })
            } else chain.proceed()
        }
        module.deoptimize(align)
    }
}
