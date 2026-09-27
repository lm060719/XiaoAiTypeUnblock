package io.mo.xatype.hooks

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.view.View
import io.github.libxposed.api.XposedModule
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils

/** Sync before the IME rebuilds its palette, then style the new view tree. */
object AppearanceConfigurationHook {
    fun install(
        module: XposedModule,
        serviceClass: Class<*>,
        applyStyle: (InputMethodService, View) -> Unit
    ) {
        val method = generateSequence(serviceClass) { it.superclass }
            .mapNotNull { XposedUtils.findMethodExact(it, "onConfigurationChanged", Configuration::class.java) }
            .firstOrNull() ?: return
        module.hook(method).intercept { chain ->
            val service = (chain.thisObject as? InputMethodService)
                ?.takeIf { serviceClass.isInstance(it) }
            if (service != null) ConfigManager.syncFromProvider(service)
            val result = chain.proceed()
            if (service != null) {
                ConfigManager.syncFromProvider(service)
                if (ConfigManager.isStyleEnabled()) {
                    val root = XposedUtils.getObjectField(service, "currentImeRootView") as? View
                    if (root != null) applyStyle(service, root)
                }
            }
            result
        }
    }
}
