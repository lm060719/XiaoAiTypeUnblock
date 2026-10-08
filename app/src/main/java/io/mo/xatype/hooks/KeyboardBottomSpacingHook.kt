package io.mo.xatype.hooks

import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils

object KeyboardBottomSpacingHook {
    fun install(module: XposedInterface) {
        val gap = HostSymbols.method(HostSymbols.BOTTOM_SPACING)
        val consume = HostSymbols.method(HostSymbols.BOTTOM_SPACING_CONSUME)
        val callers = HostSymbols.methods(HostSymbols.BOTTOM_SPACING_CALLERS)
        if (gap == null || consume == null || callers.isEmpty()) {
            XposedUtils.log(module, "KeyboardBottomSpacingHook: skipped unsupported target")
            return
        }
        // Un-inline the margin helper and the Compose reads we observe.
        module.deoptimize(gap)
        callers.forEach { module.deoptimize(it) }
        module.hook(consume).intercept { chain ->
            chain.proceed().also { KeyboardBottomSpacingPolicy.observe(it) }
        }
        module.hook(gap).intercept { chain ->
            if (ConfigManager.isBottomSpacingEnabled()) {
                KeyboardBottomSpacingPolicy.calculate(ConfigManager.getBottomSpacing()) {
                    chain.proceed() as Float
                }
            } else {
                chain.proceed()
            }
        }
        XposedUtils.log(module, "KeyboardBottomSpacingHook: hooked ${gap.declaringClass.name}.${gap.name}")
    }
}
