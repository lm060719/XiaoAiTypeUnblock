package io.mo.xatype.hooks

import android.content.res.Resources
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import java.lang.reflect.Proxy

/** Reuse native cards, tokens and touch targets without extending the host enum or its dispatcher. */
internal object OneHandMenuHook {
    private const val MENU_LABEL = -0x584101

    fun install(module: XposedInterface) {
        val grid = checkNotNull(HostSymbols.method(HostSymbols.ONE_HAND_MENU_GRID))
        val card = checkNotNull(HostSymbols.method(HostSymbols.ONE_HAND_MENU_CARD))
        val panel = checkNotNull(HostSymbols.method(HostSymbols.ONE_HAND_MENU_PANEL))
        val chunk = checkNotNull(HostSymbols.method(HostSymbols.ONE_HAND_MENU_CHUNK))
        val itemType = card.parameterTypes[0]
        val items = itemType.enumConstants.associateBy { (it as Enum<*>).name }
        // This toolbar-only item supplies a stable row key without extending the host enum.
        val menuItem = checkNotNull(items["APPS"])
        val template = checkNotNull(items["KEYBOARD_LAYOUT"])
        val manager = checkNotNull(HostSymbols.method(HostSymbols.UI_MANAGER))
        val mode = checkNotNull(HostSymbols.method(HostSymbols.UI_MODE)).returnType
        val mainMode = mode.enumConstants.single { (it as Enum<*>).name == "MAIN" }
        val setMode = manager.returnType.declaredMethods.single {
            it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(mode, Boolean::class.javaPrimitiveType))
        }.apply { isAccessible = true }
        val closeMenu = ThreadLocal<Any>()
        val extending = ThreadLocal<Boolean>()
        val callback = card.parameterTypes[5]
        val invoke = callback.declaredMethods.single { it.parameterCount == 0 }

        module.hook(Resources::class.java.getDeclaredMethod("getText", Int::class.javaPrimitiveType))
            .intercept { chain ->
                when (chain.getArg(0)) {
                    MENU_LABEL -> OneHandPolicy.MENU_ACTION
                    else -> chain.proceed()
                }
            }
        module.hook(panel).intercept { chain ->
            val previous = extending.get()
            extending.set(chain.getArg(0) == false && OneHandHook.service() != null)
            try { chain.proceed() } finally {
                if (previous == null) extending.remove() else extending.set(previous)
            }
        }
        module.hook(chunk).intercept { chain ->
            val values = chain.getArg(0) as? List<*>
            // Add before pagination so the entry stays reachable at any grid size.
            if (extending.get() != true || values.isNullOrEmpty() ||
                values.any { !itemType.isInstance(it) || it === menuItem }) chain.proceed()
            else chain.proceed(arrayOf(values + listOf(menuItem), chain.getArg(1)))
        }
        module.hook(grid).intercept { chain ->
            if (chain.getArg(1) != false) chain.proceed() else {
                val args = chain.args.toTypedArray()
                val previous = closeMenu.get()
                closeMenu.set(args[10])
                try { chain.proceed(args) } finally {
                    if (previous == null) closeMenu.remove() else closeMenu.set(previous)
                }
            }
        }
        module.hook(card).intercept { chain ->
            val item = chain.getArg(0)
            val close = closeMenu.get()
            val service = OneHandHook.service()
            if (item !== menuItem || close == null || service == null) chain.proceed()
            else {
                val args = chain.args.toTypedArray()
                args[0] = template
                args[3] = false
                args[4] = mapOf(template to MENU_LABEL)
                args[5] = Proxy.newProxyInstance(callback.classLoader, arrayOf(callback)) { proxy, method, values ->
                    when (method.name) {
                        "equals" -> proxy === values?.get(0)
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "XatypeOneHandMenu"
                        else -> {
                            val result = invoke.invoke(close)
                            setMode.invoke(manager.invoke(service), mainMode, false)
                            service.window?.window?.decorView?.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            OneHandHook.perform(service, OneHandPolicy.MENU_ACTION)
                            result
                        }
                    }
                }
                args[7] = 0
                chain.proceed(args)
            }
        }
        module.deoptimize(grid)
        module.deoptimize(card)
        module.deoptimize(panel)
        module.deoptimize(chunk)
    }
}
