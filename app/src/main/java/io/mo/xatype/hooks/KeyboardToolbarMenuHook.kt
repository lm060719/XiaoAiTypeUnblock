package io.mo.xatype.hooks

import android.content.Intent
import android.content.res.Resources
import android.inputmethodservice.InputMethodService
import android.view.HapticFeedbackConstants
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.BuildConfig
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.util.XposedUtils
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy

/** Reuse native cards, tokens and touch targets without extending the host enum or its dispatcher. */
internal object KeyboardToolbarMenuHook {
    private const val ONE_HAND_LABEL = -0x584101
    private const val SETTINGS_LABEL = -0x584102

    // Tag the page that receives the extra card so native Settings cards keep their own action.
    private class ModuleSettingsPage(items: List<*>) : ArrayList<Any?>(items)
    private data class MenuPage(val close: Any, val hasModuleSettings: Boolean)

    fun install(module: XposedInterface, loader: ClassLoader) {
        val grid = checkNotNull(HostSymbols.method(HostSymbols.ONE_HAND_MENU_GRID))
        val card = checkNotNull(HostSymbols.method(HostSymbols.ONE_HAND_MENU_CARD))
        val panel = checkNotNull(HostSymbols.method(HostSymbols.ONE_HAND_MENU_PANEL))
        val chunk = checkNotNull(HostSymbols.method(HostSymbols.ONE_HAND_MENU_CHUNK))
        val itemType = card.parameterTypes[0]
        val items = itemType.enumConstants.associateBy { (it as Enum<*>).name }
        // This toolbar-only item supplies a stable row key without extending the host enum.
        val oneHandItem = checkNotNull(items["APPS"])
        val oneHandTemplate = checkNotNull(items["KEYBOARD_LAYOUT"])
        // The host uses SETTINGS in a separate control outside the paginated card list.
        val settingsItem = items["SETTINGS"]
        val manager = checkNotNull(HostSymbols.method(HostSymbols.UI_MANAGER))
        val mode = checkNotNull(HostSymbols.method(HostSymbols.UI_MODE)).returnType
        val mainMode = mode.enumConstants.single { (it as Enum<*>).name == "MAIN" }
        val setMode = manager.returnType.declaredMethods.single {
            it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(mode, Boolean::class.javaPrimitiveType))
        }.apply { isAccessible = true }
        val serviceClass = loader.loadClass("com.mi.ime.MiInputMethodService")
        val createInputView = checkNotNull(XposedUtils.findMethodExact(serviceClass, "onCreateInputView"))
        var currentService = WeakReference<InputMethodService>(null)
        val menuPage = ThreadLocal<MenuPage>()
        val extending = ThreadLocal<Boolean>()
        val callback = card.parameterTypes[5]
        val invoke = callback.declaredMethods.single { it.parameterCount == 0 }

        module.hook(createInputView).intercept { chain ->
            currentService = WeakReference(chain.thisObject as InputMethodService)
            chain.proceed()
        }
        module.hook(Resources::class.java.getDeclaredMethod("getText", Int::class.javaPrimitiveType))
            .intercept { chain ->
                when (chain.getArg(0)) {
                    ONE_HAND_LABEL -> OneHandPolicy.MENU_ACTION
                    SETTINGS_LABEL -> "模块设置"
                    else -> chain.proceed()
                }
            }
        module.hook(panel).intercept { chain ->
            val previous = extending.get()
            extending.set(chain.getArg(0) == false && currentService.get() != null)
            try { chain.proceed() } finally {
                if (previous == null) extending.remove() else extending.set(previous)
            }
        }
        module.hook(chunk).intercept { chain ->
            val values = chain.getArg(0) as? List<*>
            if (extending.get() != true || values.isNullOrEmpty() || values.any { !itemType.isInstance(it) }) {
                chain.proceed()
            } else {
                val addSettings = settingsItem != null && settingsItem !in values
                val entries = buildList {
                    if (addSettings) add(settingsItem)
                    addAll(values)
                    if (OneHandHook.service() != null && oneHandItem !in values) add(oneHandItem)
                }
                val pages = chain.proceed(arrayOf(entries, chain.getArg(1))) as ArrayList<*>
                // The host keeps these pages in Compose state and passes each list to the grid.
                if (addSettings && pages.isNotEmpty()) {
                    ArrayList<Any?>(pages).apply { this[0] = ModuleSettingsPage(first() as List<*>) }
                } else {
                    pages
                }
            }
        }
        module.hook(grid).intercept { chain ->
            val close = chain.getArg(10)
            val previous = menuPage.get()
            if (chain.getArg(1) == false && close != null) {
                menuPage.set(MenuPage(close, chain.getArg(0) is ModuleSettingsPage))
            } else {
                menuPage.remove()
            }
            try { chain.proceed() } finally {
                if (previous == null) menuPage.remove() else menuPage.set(previous)
            }
        }
        module.hook(card).intercept { chain ->
            val item = chain.getArg(0)
            val page = menuPage.get()
            val openSettings = item === settingsItem && page?.hasModuleSettings == true
            val service = if (openSettings) currentService.get() else OneHandHook.service()
            if ((!openSettings && item !== oneHandItem) || page == null || service == null) chain.proceed()
            else {
                val args = chain.args.toTypedArray()
                val template = if (openSettings) settingsItem else oneHandTemplate
                args[0] = template
                args[3] = false
                args[4] = mapOf(template to if (openSettings) SETTINGS_LABEL else ONE_HAND_LABEL)
                args[5] = Proxy.newProxyInstance(callback.classLoader, arrayOf(callback)) { proxy, method, values ->
                    when (method.name) {
                        "equals" -> proxy === values?.get(0)
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> if (openSettings) "XatypeModuleSettings" else "XatypeOneHandMenu"
                        else -> {
                            val result = invoke.invoke(page.close)
                            setMode.invoke(manager.invoke(service), mainMode, false)
                            if (openSettings) {
                                openModuleSettings(module, service)
                            } else {
                                service.window?.window?.decorView?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                OneHandHook.perform(service, OneHandPolicy.MENU_ACTION)
                            }
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

    private fun openModuleSettings(module: XposedInterface, service: InputMethodService) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory("de.robv.android.xposed.category.MODULE_SETTINGS")
            .setClassName(BuildConfig.APPLICATION_ID, "io.mo.xatype.ui.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            service.startActivity(intent)
            service.requestHideSelf(0)
        } catch (failure: RuntimeException) {
            XposedUtils.logError(module, "Module settings could not be opened", failure)
            Toast.makeText(service, "无法打开模块设置", Toast.LENGTH_SHORT).show()
        }
    }
}
