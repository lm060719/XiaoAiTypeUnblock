package io.mo.xatype.hooks

import android.inputmethodservice.InputMethodService
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiField
import io.mo.xatype.compat.abiMethod
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.keyboard.KeyboardChoice
import io.mo.xatype.keyboard.KeyboardShortcutActions
import io.mo.xatype.keyboard.KeyboardShortcutHostActions
import io.mo.xatype.keyboard.KeyboardShortcutHostLayout
import io.mo.xatype.keyboard.KeyboardShortcutLayout
import io.mo.xatype.keyboard.KeyboardShortcuts
import io.mo.xatype.util.XposedUtils

/** Native nine/26-key integration; does not load or require either experimental decoder. */
object KeyboardShortcutsHook {
    @Volatile private var service: InputMethodService? = null
    @Volatile private var supported: Boolean? = null
    @Volatile private var layoutMode = "PINYIN"
    @Volatile private var nativeLayoutId = ""

    fun install(module: XposedInterface, loader: ClassLoader) {
        val abi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_LAYOUT, HostSymbols.KEYBOARD_ACTIONS, HostSymbols.KEYBOARD_SWIPE)
        val layouts = KeyboardShortcutHostLayout(loader, abi)
        val actions = KeyboardShortcutHostActions(loader, abi)
        val hostService = abi.type("com.mi.ime.MiInputMethodService")
        val manager = abi.type("ab.p1")
        val context = manager.declaredFields.single { hostService.isAssignableFrom(it.type) }
            .apply { isAccessible = true }
        val engineType = abi.type("z8.g")
        val factory = KeyboardAbi.qwertyFactory(abi)
        val callbacks = abi.type("ab.b1")
        val handler = abi.type("ab.f1").abiMethod(abi, "c", layouts.key, callbacks)
        val label = layouts.key.abiField(abi, "b").apply { isAccessible = true }
        val kind = layouts.key.abiField(abi, "f").apply { isAccessible = true }
        val popup = layouts.key.abiField(abi, "i").apply { isAccessible = true }
        val mode = callbacks.abiMethod(abi, "m")
        val layoutId = callbacks.abiMethod(abi, "A")
        val visibleLayoutId = layouts.layout.abiField(abi, "a").apply { isAccessible = true }
        // The setter also sees T9 and temporary number panels, which bypass the QWERTY factory.
        val setLayout = manager.declaredMethods.single {
            it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(layouts.layout))
        }.apply { isAccessible = true }
        require(factory.returnType == layouts.layout && handler.returnType == Void.TYPE)

        fun captureManager(instance: Any) {
            service = context.get(instance) as InputMethodService
            supported = true
        }

        val modeChange = manager.abiMethod(abi, "H", engineType,
            android.view.inputmethod.EditorInfo::class.java, Boolean::class.javaPrimitiveType)
        val editorChange = manager.abiMethod(abi, "G", android.view.inputmethod.EditorInfo::class.java, engineType)
        for ((method, index) in listOf(modeChange to 0, editorChange to 1)) {
            module.hook(method).intercept { chain ->
                captureManager(chain.thisObject!!)
                layoutMode = (chain.getArg(index) as Enum<*>).name
                chain.proceed()
            }
            module.deoptimize(method)
        }
        module.hook(factory).intercept { chain ->
            captureManager(chain.thisObject!!)
            chain.proceed()
        }
        module.deoptimize(factory)
        module.hook(setLayout).intercept { chain ->
            captureManager(chain.thisObject!!)
            nativeLayoutId = visibleLayoutId.get(chain.getArg(0)) as String
            chain.proceed()
        }
        module.deoptimize(setLayout)
        module.hook(handler).intercept { chain ->
            val key = chain.getArg(0)!!
            val action = label.get(key) as String
            if (action !in KeyboardShortcuts.actions || (kind.get(key) as Enum<*>).name != "CHAR" ||
                (popup.get(key) as? List<*>)?.contains(action) != true)
                return@intercept chain.proceed()
            val ime = service ?: return@intercept chain.proceed()
            // The 18-key adapter commits its own pending decoding before dispatching.
            if (KeyboardSelectionHook.selected(ime) == KeyboardChoice.KEYBOARD18) return@intercept chain.proceed()
            val target = chain.getArg(1)!!
            if (supported != true || KeyboardSelectionHook.selected(ime) != KeyboardChoice.PINYIN ||
                KeyboardShortcutLayout.fromHost((mode.invoke(target) as? Enum<*>)?.name,
                    layoutId.invoke(target) as? String, keyboard18 = false) == null) return@intercept null
            // Consume stale popup actions after disabling as well, never insert their labels.
            if (ConfigManager.isKeyboardShortcutsEnabled()) runCatching {
                val connection = ime.currentInputConnection ?: return@runCatching
                actions.finishInput(target)
                KeyboardShortcutActions.perform(connection, action, ime)
            }.onFailure { XposedUtils.logError(module, "Key shortcut failed: $action", it) }
            null
        }
        module.deoptimize(handler)
        ShortcutSwipeHostHook(enabled = {
            supported == true && ConfigManager.isKeyboardShortcutsEnabled() &&
                service?.let {
                    KeyboardSelectionHook.selected(it) in setOf(KeyboardChoice.PINYIN, KeyboardChoice.KEYBOARD18)
                } == true
        }, layout = { KeyboardShortcutLayout.fromHost(layoutMode, nativeLayoutId,
            keyboard18 = service?.let { KeyboardSelectionHook.selected(it) == KeyboardChoice.KEYBOARD18 } == true) })
            .install(module, loader)
        XposedUtils.log(module, "Independent downward swipe shortcut hooks installed (resolved ABI)")
    }
}
