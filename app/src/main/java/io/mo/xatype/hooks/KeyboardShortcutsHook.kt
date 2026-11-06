package io.mo.xatype.hooks

import android.inputmethodservice.InputMethodService
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostCoroutines
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
    /**
     * Keyboards whose visible keys come from the native layouts. The shape (虎码/五笔) keyboard
     * replaces only the PINYIN engine and keeps the native 26-key layout and candidate callbacks.
     */
    private val shortcutKeyboards = setOf(KeyboardChoice.PINYIN, KeyboardChoice.KEYBOARD18, KeyboardChoice.SHAPE)

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
        val handler = abi.namedMethods(abi.type("ab.f1"), "c").single {
            it.parameterCount >= 2 && it.parameterTypes[0] == layouts.key && it.parameterTypes[1] == callbacks
        }
        // From 0.2.1358 the handler is a suspend function: (key, callbacks, continuation) -> Object.
        val coroutines = if (handler.parameterCount == 3) HostCoroutines(loader) else null
        require((coroutines == null) == !actions.suspending)
        val mainCallbacks = if (coroutines != null)
            hostService.getMethod("getKeyHandlerCallbacks\$app_iflytekFullRelease") else null
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
        require(factory.returnType == layouts.layout && (handler.returnType == Void.TYPE || coroutines != null))

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
                (chain.getArg(index) as? Enum<*>)?.let { layoutMode = it.name }
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
            // A null key resumes the native handler's own state machine.
            val key = chain.args[0] ?: return@intercept chain.proceed()
            val action = label.get(key) as String
            if (action !in KeyboardShortcuts.actions || (kind.get(key) as Enum<*>).name != "CHAR" ||
                (popup.get(key) as? List<*>)?.contains(action) != true)
                return@intercept chain.proceed()
            val ime = service ?: return@intercept chain.proceed()
            val target = chain.getArg(1)!!
            val selectedKeyboard = KeyboardSelectionHook.selected(ime)
            val currentMode = (mode.invoke(target) as? Enum<*>)?.name
            // Chinese 18-key owns its decoder; English uses the native engine and must
            // finish native composition here before dispatching any editor action.
            if (selectedKeyboard == KeyboardChoice.KEYBOARD18 && currentMode != "ENGLISH")
                return@intercept chain.proceed()
            if (supported != true || selectedKeyboard !in shortcutKeyboards ||
                KeyboardShortcutLayout.fromHost(currentMode,
                    layoutId.invoke(target) as? String, keyboard18 = false) == null) return@intercept coroutines?.unit
            // Consume stale popup actions after disabling as well, never insert their labels.
            if (!ConfigManager.isKeyboardShortcutsEnabled()) return@intercept coroutines?.unit
            if (coroutines == null) {
                runCatching {
                    val connection = ime.currentInputConnection ?: return@runCatching
                    actions.finishInput(target)
                    KeyboardShortcutActions.perform(connection, action)
                }.onFailure { XposedUtils.logError(module, "Key shortcut failed: $action", it) }
                return@intercept null
            }
            fun act() = runCatching {
                ime.currentInputConnection?.let { KeyboardShortcutActions.perform(it, action) }
            }.onFailure { XposedUtils.logError(module, "Key shortcut failed: $action", it) }
            val caller = chain.args[2]!!
            coroutines.suspendHook(caller, { XposedUtils.logError(module, "Key shortcut failed: $action", it) }) { finish ->
                // Selecting candidates is the main input's pipeline; reply/translation boxes act directly.
                if (mainCallbacks?.invoke(ime) !== target) {
                    act()
                    finish(Result.success(null))
                } else actions.finishInput(coroutines, coroutines.context(caller), ime, target) { result ->
                    if (result.isSuccess) act()
                    finish(result)
                }
            }
        }
        module.deoptimize(handler)
        // The floating keyboard shrinks its keys; labels follow (see ShortcutSwipeHostHook).
        val uiManager = HostSymbols.method(HostSymbols.UI_MANAGER)
        val uiFloating = HostSymbols.method(HostSymbols.UI_FLOATING)
        fun floating(): Boolean {
            val ime = service ?: return false
            if (uiManager == null || uiFloating == null) return false
            return runCatching { uiManager.invoke(ime)?.let(uiFloating::invoke) == true }.getOrDefault(false)
        }
        ShortcutSwipeHostHook(floating = ::floating, enabled = {
            supported == true && ConfigManager.isKeyboardShortcutsEnabled() &&
                service?.let { KeyboardSelectionHook.selected(it) in shortcutKeyboards } == true
        }, layout = { KeyboardShortcutLayout.fromHost(layoutMode, nativeLayoutId,
            keyboard18 = service?.let { KeyboardSelectionHook.selected(it) == KeyboardChoice.KEYBOARD18 } == true) })
            .install(module, loader)
        XposedUtils.log(module, "Independent downward swipe shortcut hooks installed (resolved ABI)")
    }
}
