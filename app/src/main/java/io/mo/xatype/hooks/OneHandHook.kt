package io.mo.xatype.hooks

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils

/** Change the native layout model, so measured keys, hit targets and popups share its geometry. */
object OneHandHook {
    fun install(module: XposedInterface) {
        val dimensions = HostSymbols.clazz(HostSymbols.ONE_HAND_DIMENSIONS)
        val layout = HostSymbols.method(HostSymbols.HEIGHT_LAYOUT)
        val manager = HostSymbols.method(HostSymbols.UI_MANAGER)
        val floating = HostSymbols.method(HostSymbols.UI_FLOATING)
        if (dimensions == null || layout == null || manager == null || floating == null) {
            HostSymbols.markUnavailable(HostSymbols.ONE_HAND_DIMENSIONS,
                IllegalStateException("Native dimensions or UI geometry unavailable"))
            XposedUtils.log(module, "OneHandHook: skipped unsupported native dimensions")
            return
        }
        val int = Int::class.javaPrimitiveType!!
        val constructor = dimensions.getDeclaredConstructor(int, int, int, int, int, int)
        val serviceIndex = layout.parameterTypes.indexOfFirst { InputMethodService::class.java.isAssignableFrom(it) }
        check(serviceIndex >= 0)
        val active = ThreadLocal<InputMethodService>()
        var failureReported = false
        module.deoptimize(layout)
        module.hook(layout).intercept { chain ->
            val previous = active.get()
            active.set(chain.getArg(serviceIndex) as InputMethodService)
            try { chain.proceed() } finally {
                if (previous == null) active.remove() else active.set(previous)
            }
        }
        module.hook(constructor).intercept { chain ->
            val args = chain.args.toTypedArray()
            val service = active.get()
            if (service != null && ConfigManager.getOneHandSide() != OneHandPolicy.OFF) {
                // Only the dimension object belonging to this layout, never unrelated constructions.
                active.remove()
                val geometry = runCatching {
                    val config = service.resources.configuration
                    val density = service.resources.displayMetrics.density
                    val windowWidth = service.window?.window?.decorView?.width ?: 0
                    val width = if (windowWidth > 0 && density > 0f) (windowWidth / density).toInt() else config.screenWidthDp
                    OneHandPolicy.geometry(width, ConfigManager.getOneHandWidth(),
                        ConfigManager.getOneHandSide(), config.orientation == Configuration.ORIENTATION_PORTRAIT &&
                            !service.isFullscreenMode, floating.invoke(manager.invoke(service)) != false)
                }.getOrElse { failure ->
                    if (!failureReported) {
                        failureReported = true
                        XposedUtils.logError(module, "One-hand geometry unavailable, retaining native layout", failure)
                    }
                    null
                }
                if (geometry != null) {
                    args[1] = geometry.leftMargin
                    args[2] = geometry.rightMargin
                    args[4] = geometry.offset
                }
            }
            chain.proceed(args)
        }
        XposedUtils.log(module, "OneHandHook: installed native dimension adjustment")
    }
}
