package io.mo.xatype.hooks

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiField
import io.mo.xatype.compat.abiMethod
import io.mo.xatype.keyboard.KeyboardChoice
import io.mo.xatype.keyboard.KeyboardPickerBridge
import io.mo.xatype.keyboard18.Keyboard18Store
import io.mo.xatype.provider.Keyboard18ContentProvider
import io.mo.xatype.provider.ShapeContentProvider
import io.mo.xatype.shape.ShapeStore
import io.mo.xatype.util.XposedUtils
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/** Add choices to the host's own picker. The host still owns rendering and switching. */
object KeyboardSelectionHook {
    private const val PREFS = "xatype_keyboard_choice"
    private const val SELECTED = "selected"
    private data class Visibility(val shape: Boolean, val keyboard18: Boolean,
                                  val legacyShape: Boolean, val legacy18: Boolean)
    @Volatile private var visibility: Visibility? = null
    @Volatile private var context: Context? = null
    @Volatile private var ready = false

    @Synchronized
    private fun visibility(ctx: Context): Visibility {
        visibility?.let { return it }
        val shape = checkNotNull(ctx.contentResolver.call(ShapeContentProvider.CONTENT_URI, "visibility", null, null))
        val keyboard18 = checkNotNull(ctx.contentResolver.call(Keyboard18ContentProvider.CONTENT_URI, "snapshot", null, null))
        val engineReady = HostSymbols.has(HostSymbols.KEYBOARD_ENGINE) && HostSymbols.KEYBOARD_ENGINE !in HostSymbols.missing
        val layoutReady = HostSymbols.has(HostSymbols.KEYBOARD_LAYOUT) && HostSymbols.KEYBOARD_LAYOUT !in HostSymbols.missing
        return Visibility(shape.getBoolean(ShapeStore.SHOW_IN_PICKER) && engineReady,
            keyboard18.getBoolean(Keyboard18Store.SHOW_IN_PICKER) && engineReady && layoutReady,
            shape.getBoolean(ShapeStore.ENABLED), keyboard18.getBoolean(Keyboard18Store.ENABLED)).also { visibility = it }
    }

    fun selected(ctx: Context): KeyboardChoice {
        if (!ready) return KeyboardChoice.PINYIN
        context = ctx.applicationContext ?: ctx
        return runCatching {
            val visible = visibility(ctx)
            val sp = ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = sp.getString(SELECTED, null)
            KeyboardChoice.resolve(saved, visible.shape, visible.keyboard18, visible.legacyShape, visible.legacy18).also {
                // Store migration/fallback so a hidden choice cannot unexpectedly reactivate later.
                if (saved != it.id) check(sp.edit().putString(SELECTED, it.id).commit())
            }
        }.getOrDefault(KeyboardChoice.PINYIN)
    }

    fun install(module: XposedInterface, loader: ClassLoader) {
        val abi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_PICKER)
        val picker = KeyboardPickerBridge(loader, abi)
        val callback = abi.type("bd.b")
        val invoke = callback.abiMethod(abi, "g", Any::class.java)
        val compose = abi.type("s0.p")
        val screen = abi.type("z9.u7").abiMethod(abi, "a0", String::class.java,
            callback, abi.type("kotlin.jvm.functions.Function0"), Set::class.java,
            compose, Int::class.javaPrimitiveType)
        val rows = abi.type("oc.m").abiMethod(abi, "T0", Iterable::class.java, Int::class.javaPrimitiveType)
        val hostService = abi.type("com.mi.ime.MiInputMethodService")
        // onModeSelected is a synthetic lambda capturing the service; 0.2.1246 moved it to another
        // lambda class (qb.z0), so read the captured service instead of matching one class.
        fun hostContext(callback: Any): Context? = callback.javaClass.declaredFields
            .firstOrNull { hostService.isAssignableFrom(it.type) }
            ?.apply { isAccessible = true }?.get(callback) as? Context

        // Add mode items at the selector's row grouping boundary. Avoid hooking listOf,
        // which is called throughout ordinary keyboard composition and input handling.
        module.hook(rows).intercept { chain ->
            val ctx = context
            val visible = visibility
            val items = chain.getArg(0) as? List<*>
            if (!ready || ctx == null || visible == null || items == null || items.size !in 1..4) chain.proceed()
            else {
                val extended = picker.extend(items.toTypedArray(), visible.shape, visible.keyboard18)
                if (extended == null) chain.proceed() else chain.proceed(arrayOf(extended, chain.getArg(1)))
            }
        }
        module.deoptimize(rows)
        // ART may have inlined row grouping into this composable before hook installation.
        module.deoptimize(abi.type("z9.c3").abiMethod(abi, "m", Any::class.java, Any::class.java))
        module.hook(screen).intercept { chain ->
            val original = chain.getArg(1)!!
            val previous = if (Proxy.isProxyClass(original.javaClass)) Proxy.getInvocationHandler(original) as? SelectionCallback else null
            val ctx = previous?.context ?: hostContext(original)
            if (ctx == null) chain.proceed() else {
                val choice = selected(ctx)
                val visible = visibility
                if (visible == null) chain.proceed() else {
                    val handler = previous ?: SelectionCallback(ctx, original) { target, id -> invoke.invoke(target, id) }
                    val wrapped = if (previous != null) original else Proxy.newProxyInstance(loader, arrayOf(callback), handler)
                    val args = chain.args.toTypedArray()
                    if (args[0] == KeyboardChoice.PINYIN.id) args[0] = choice.id
                    args[1] = wrapped
                    args[3] = (args[3] as? Set<*>)?.toMutableSet()?.apply {
                        if (visible.shape) add(KeyboardChoice.SHAPE.id)
                        if (visible.keyboard18) add(KeyboardChoice.KEYBOARD18.id)
                    }
                    chain.proceed(args)
                }
            }
        }
        module.deoptimize(screen)
        ready = true
        runCatching { installSettings(module, loader, callback) }.onFailure {
            HostSymbols.markUnavailable(HostSymbols.KEYBOARD_SETTINGS, it)
            XposedUtils.logError(module, "Native keyboard settings entries unavailable; keyboard panel remains available", it)
        }
        XposedUtils.log(module, "Independent keyboard picker ready (resolved ABI)")
    }

    /** The native Settings > Keyboard type page uses preferences rather than Compose. */
    private fun installSettings(module: XposedInterface, loader: ClassLoader, callback: Class<*>) {
        val abi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_SETTINGS)
        val fragment = abi.type("db.l")
        val base = abi.type("dg.u")
        val preference = abi.type("androidx.preference.Preference")
        val single = abi.type("miuix.preference.SingleChoicePreference")
        val group = abi.type("androidx.preference.PreferenceGroup")
        val constructor = single.getConstructor(Context::class.java)
        val find = fragment.abiMethod(abi, "f", String::class.java)
        val requireContext = fragment.abiMethod(abi, "requireContext")
        val setKey = preference.abiMethod(abi, "D", String::class.java)
        val setTitle = preference.abiMethod(abi, "G", String::class.java)
        val value = single.abiField(abi, "n0").apply { isAccessible = true }
        val add = group.abiMethod(abi, "L", preference)
        val icon = preference.abiField(abi, "j").apply { isAccessible = true }
        val bind = abi.method("qh.y", "y", base, String::class.java,
            Map::class.java, String::class.java, callback)
        val invoke = callback.abiMethod(abi, "g", Any::class.java)
        val service = abi.type("com.mi.ime.MiInputMethodService")
        val instance = service.abiField(abi, "instance").apply { isAccessible = true }
        val engineReady = service.abiMethod(abi, "isEngineManagerReady\$app_iflytekFullRelease")
        val engineType = abi.type("z8.g")
        val switch = service.abiMethod(abi, "switchEngine\$app_iflytekFullRelease", engineType)
        module.hook(bind).intercept { chain ->
            val page = chain.getArg(0)!!
            if (!fragment.isInstance(page) || chain.getArg(1) != "keyboard_type_group") chain.proceed() else {
                val ctx = requireContext.invoke(page) as Context
                val choice = selected(ctx)
                val visible = visibility
                val category = find.invoke(page, "keyboard_type_group")
                if (visible == null || category == null) chain.proceed() else {
                    @Suppress("UNCHECKED_CAST")
                    val mapping = LinkedHashMap(chain.getArg(2) as Map<String, String>)
                    val native = find.invoke(page, "kbd_type_pinyin")
                    fun entry(choice: KeyboardChoice, label: String) {
                        if (find.invoke(page, choice.id) == null) {
                            val row = constructor.newInstance(ctx)
                            setKey.invoke(row, choice.id)
                            setTitle.invoke(row, label)
                            value.set(row, choice.id)
                            if (native != null) icon.setInt(row, icon.getInt(native))
                            check(add.invoke(category, row) == true)
                        }
                        mapping[choice.id] = choice.id
                    }
                    if (visible.shape) entry(KeyboardChoice.SHAPE, "虎码/五笔")
                    if (visible.keyboard18) entry(KeyboardChoice.KEYBOARD18, "18键")
                    val original = chain.getArg(4)!!
                    val handler = SelectionCallback(ctx, original, "PINYIN") { target, id ->
                        val result = invoke.invoke(target, id)
                        // Native preference sync compares engine enums, so PINYIN -> PINYIN
                        // would otherwise leave a previous custom proxy alive.
                        runCatching {
                            val running = instance.get(null)
                            if (running != null && engineReady.invoke(running) == true) {
                                val type = engineType.enumConstants.single { (it as Enum<*>).name == id }
                                switch.invoke(running, type)
                            }
                        }.onFailure { XposedUtils.logError(module, "Unable to refresh keyboard from native settings", it) }
                        result
                    }
                    val args = chain.args.toTypedArray()
                    args[2] = mapping
                    if (args[3] == "PINYIN" && choice != KeyboardChoice.PINYIN) args[3] = choice.id
                    args[4] = Proxy.newProxyInstance(loader, arrayOf(callback), handler)
                    chain.proceed(args)
                }
            }
        }
        module.deoptimize(bind)
        module.deoptimize(fragment.abiMethod(abi, "h", String::class.java))
    }

    private class SelectionCallback(val context: Context, val original: Any,
                                    val pinyinId: String = KeyboardChoice.PINYIN.id,
                                    val abi: KeyboardAbi = KeyboardAbi.require(context.classLoader, HostSymbols.KEYBOARD_PICKER),
                                    val invoke: (Any, String) -> Any?) : InvocationHandler {
        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? = when (abi.canonical(method)) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "XatypeKeyboardSelection"
            "g" -> {
                val id = args!![0] as String
                val visible = visibility(context)
                val choice = KeyboardChoice.resolve(id, visible.shape, visible.keyboard18)
                val sp = context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val before = sp.getString(SELECTED, null)
                check(sp.edit().putString(SELECTED, choice.id).commit())
                try {
                    // Even PINYIN -> PINYIN recreates the engine through the normal host lifecycle.
                    invoke(original, if (id == KeyboardChoice.SHAPE.id || id == KeyboardChoice.KEYBOARD18.id)
                        pinyinId else id)
                } catch (failure: Throwable) {
                    sp.edit().putString(SELECTED, before).commit()
                    throw failure
                }
            }
            else -> method.invoke(original, *(args ?: emptyArray()))
        }
    }
}
