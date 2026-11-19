package io.mo.xatype.hooks

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.keyboard.KeyboardPositionsHostLayout
import io.mo.xatype.util.XposedUtils

internal object KeyboardPositionsHook {
    @Volatile private var layoutAdapter: KeyboardPositionsHostLayout? = null

    fun originalSlot(key: Any): Any = layoutAdapter?.originalSlot(key) ?: key

    fun install(module: XposedInterface, loader: ClassLoader) {
        val abi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_LAYOUT)
        val layouts = KeyboardPositionsHostLayout(loader, abi)
        val manager = abi.type("ab.p1")
        val service = abi.type("com.mi.ime.MiInputMethodService")
        val context = manager.declaredFields.single { service.isAssignableFrom(it.type) }
            .apply { isAccessible = true }
        val setLayout = manager.declaredMethods.single {
            it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(layouts.layout))
        }.apply { isAccessible = true }
        module.hook(setLayout).intercept { chain ->
            val incoming = chain.getArg(0) ?: return@intercept chain.proceed()
            val layout = runCatching {
                ConfigManager.syncFromProvider(context.get(chain.thisObject) as Context)
                layouts.reorder(incoming, ConfigManager::getKeyboardPositions)
            }.onFailure { XposedUtils.logError(module, "Could not apply custom key positions", it) }
                .getOrDefault(incoming)
            if (layout === incoming) chain.proceed() else chain.proceed(arrayOf(layout))
        }
        module.deoptimize(setLayout)
        layoutAdapter = layouts
    }
}
