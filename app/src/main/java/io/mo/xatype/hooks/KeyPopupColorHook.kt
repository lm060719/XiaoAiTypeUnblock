package io.mo.xatype.hooks

import android.graphics.Color
import android.graphics.Paint
import io.github.libxposed.api.XposedModule
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.lang.reflect.Modifier

internal object KeyPopupColorHook {
    fun install(module: XposedModule, loader: ClassLoader, paletteClassName: String) {
        installPreview(module)
        runCatching {
            val abi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_PICKER)
            val paletteType = Class.forName(paletteClassName, false, loader)
            val composer = abi.type("s0.p")
            // (items, selected, palette, Modifier, Composer, $changed); 0.2.1053 and earlier
            // also take a Boolean before the Modifier and a $default mask after $changed.
            // The Modifier slot is matched by position: its type ID belongs to other groups.
            val current = listOf(List::class.java, Integer.TYPE, paletteType, null, composer, Integer.TYPE)
            val previous = listOf(List::class.java, Integer.TYPE, paletteType, java.lang.Boolean.TYPE,
                null, composer, Integer.TYPE, Integer.TYPE)
            fun matches(types: Array<Class<*>>, expected: List<Class<*>?>) = types.size == expected.size &&
                types.indices.all { expected[it]?.equals(types[it]) ?: !types[it].isPrimitive }
            // The fingerprinted UI owner is shared by supported modern hosts;
            // this signature uniquely identifies the character selector (0.2.974 to 0.2.1246).
            val method = abi.type("z9.u7").declaredMethods.single {
                Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                    (matches(it.parameterTypes, current) || matches(it.parameterTypes, previous))
            }.apply { isAccessible = true }
            val changedIndex = method.parameterTypes.indexOf(composer) + 1
            val palettes = KeyPopupPalette(paletteType)
            module.hook(method).intercept { chain ->
                val enabled = ConfigManager.isStyleEnabled()
                val source = chain.getArg(2)!!
                val replacement = palettes.apply(source,
                    if (enabled) color(ConfigManager.getKeyPopupBackground()) else null,
                    if (enabled) color(ConfigManager.getKeyPopupSelected()) else null,
                    if (enabled) color(ConfigManager.getKeyPopupText()) else null)
                if (replacement === source) chain.proceed() else {
                    val args = chain.args.toTypedArray()
                    args[2] = replacement
                    args[changedIndex] = (args[changedIndex] as Int) and 0x380.inv()
                    chain.proceed(args)
                }
            }
            module.deoptimize(method)
            XposedUtils.log(module, "KeyPopupColorHook: character selector colors installed")
        }.onFailure {
            HostSymbols.markUnavailable(HostSymbols.KEYBOARD_KEY_POPUP, it)
            XposedUtils.logError(module, "KeyPopupColorHook: character selector colors unavailable", it)
        }
    }

    private fun installPreview(module: XposedModule) {
        runCatching {
            val method = checkNotNull(HostSymbols.method(HostSymbols.KEYBOARD_KEY_PREVIEW))
            val paints = method.declaringClass.declaredFields.filter { it.type == Paint::class.java }
                .onEach { it.isAccessible = true }
            check(paints.size == 2)
            module.hook(method).intercept { chain ->
                if (!ConfigManager.isStyleEnabled()) return@intercept chain.proceed()
                val background = argb(ConfigManager.getKeyPopupBackground())
                val text = argb(ConfigManager.getKeyPopupText())
                if (background == null && text == null) return@intercept chain.proceed()
                val values = paints.map { it.get(chain.thisObject) as Paint }
                val label = values.singleOrNull { it.textAlign == Paint.Align.CENTER }
                    ?: return@intercept chain.proceed()
                val fill = values.single { it !== label }
                val oldFill = fill.color
                val oldText = label.color
                try {
                    background?.let { fill.color = it }
                    text?.let { label.color = it }
                    chain.proceed()
                } finally {
                    fill.color = oldFill
                    label.color = oldText
                }
            }
            module.deoptimize(method)
            XposedUtils.log(module, "KeyPopupColorHook: tap preview colors installed")
        }.onFailure {
            // A fingerprint miss is already reported; record installation failures too.
            if (HostSymbols.has(HostSymbols.KEYBOARD_KEY_PREVIEW)) {
                HostSymbols.markUnavailable(HostSymbols.KEYBOARD_KEY_PREVIEW, it)
            }
            XposedUtils.logError(module, "KeyPopupColorHook: tap preview colors unavailable", it)
        }
    }

    private fun color(value: String): Long? = argb(value)?.let { (it.toLong() and 0xffffffffL) shl 32 }
    private fun argb(value: String): Int? = value.trim().takeIf { it.isNotEmpty() }?.let {
        runCatching { Color.parseColor(if (it.startsWith('#')) it else "#$it") }.getOrNull()
    }
}
