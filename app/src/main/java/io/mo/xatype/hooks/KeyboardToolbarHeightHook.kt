package io.mo.xatype.hooks

import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils

object KeyboardToolbarHeightHook {
    fun install(module: XposedInterface) {
        val bar = HostSymbols.method(HostSymbols.TOOLBAR_HEIGHT)
        val total = HostSymbols.method(HostSymbols.TOOLBAR_TOTAL_HEIGHT)
        val consume = HostSymbols.method(HostSymbols.TOOLBAR_HEIGHT_CONSUME)
        val callers = HostSymbols.methods(HostSymbols.TOOLBAR_HEIGHT_CALLERS)
        if (bar == null || total == null || consume == null || callers.isEmpty()) {
            XposedUtils.log(module, "KeyboardToolbarHeightHook: skipped unsupported target")
            return
        }
        bar.isAccessible = true
        val composerIndex = total.parameterTypes.indexOf(bar.parameterTypes.single())
        check(composerIndex >= 0) { "unified height Composer" }
        (callers + listOf(total, bar)).distinct().forEach { module.deoptimize(it) }
        module.hook(consume).intercept { chain ->
            chain.proceed().also { KeyboardToolbarHeightPolicy.observe(it) }
        }
        module.hook(bar).intercept { chain ->
            if (!ConfigManager.isToolbarHeightEnabled() || KeyboardToolbarHeightPolicy.isNativeRead()) {
                chain.proceed()
            } else {
                KeyboardToolbarHeightPolicy.height(ConfigManager.getToolbarHeight()) { chain.proceed() as Float }
            }
        }
        // Adjust only the source height. Its animated/cached CompositionLocal is
        // already adjusted; hooking the local getter would subtract the delta twice.
        module.hook(total).intercept { chain ->
            val native = chain.proceed() as Float
            if (!ConfigManager.isToolbarHeightEnabled()) native else {
                KeyboardToolbarHeightPolicy.total(native) {
                    // Balanced native Compose groups read the current phone/tablet/orientation
                    // height and floating state; never reuse measurements from another layout.
                    KeyboardToolbarHeightPolicy.delta(ConfigManager.getToolbarHeight()) {
                        bar.invoke(null, chain.getArg(composerIndex)) as Float
                    }
                }
            }
        }
        XposedUtils.log(module, "KeyboardToolbarHeightHook: installed top bar and unified height hooks")
    }
}
