package io.mo.xatype.hooks

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiField
import io.mo.xatype.compat.abiMethod
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.keyboard.KeyboardChoice
import io.mo.xatype.keyboard.KeyboardShortcutActions
import io.mo.xatype.keyboard.KeyboardShortcuts
import io.mo.xatype.keyboard18.Keyboard18Dictionary
import io.mo.xatype.keyboard18.Keyboard18Engine
import io.mo.xatype.keyboard18.Keyboard18HostAppearance
import io.mo.xatype.keyboard18.Keyboard18HostBridge
import io.mo.xatype.keyboard18.Keyboard18HostLayout
import io.mo.xatype.keyboard18.Keyboard18Keys
import io.mo.xatype.keyboard18.Keyboard18Store
import io.mo.xatype.provider.Keyboard18ContentProvider
import io.mo.xatype.util.XposedUtils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in 18-key keyboard and independent offline decoder, validated against the resolved host ABI. */
object Keyboard18Hook {
    private val reports = Executors.newSingleThreadExecutor()
    private data class Loaded(val settings: Bundle, val dictionary: Keyboard18Dictionary?)
    @Volatile private var loaded: Loaded? = null
    @Volatile private var service: InputMethodService? = null
    @Volatile private var layoutMode = "PINYIN"
    @Volatile private var chineseReady = false
    @Volatile private var installFailure: Throwable? = null
    @Volatile private var layoutReported = false
    @Volatile private var applicationContext: Context? = null

    fun install(module: XposedInterface, loader: ClassLoader) {
        // Package hooks install before Application exists. Keep this observer independent
        // so even an ABI-resolution failure can reach the module's status page.
        val applicationCreate = Instrumentation::class.java.getDeclaredMethod(
            "callApplicationOnCreate", Application::class.java)
        module.hook(applicationCreate).intercept { chain ->
            val result = chain.proceed()
            val context = chain.getArg(0) as? Context
            if (context?.packageName == "com.xiaomi.type") {
                applicationContext = context.applicationContext ?: context
                installFailure?.let { reportFailure(context, module, "Hook安装失败", it) }
            }
            result
        }
        module.deoptimize(applicationCreate)
        try {
            installHostHooks(module, loader)
        } catch (failure: Throwable) {
            installFailure = failure
            applicationContext?.let { reportFailure(it, module, "Hook安装失败", failure) }
            throw failure
        }
    }

    private fun installHostHooks(module: XposedInterface, loader: ClassLoader) {
        val abi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_ENGINE)
        val bridge = Keyboard18HostBridge(loader, abi)
        val layouts = Keyboard18HostLayout(loader, abi)
        val manager = abi.type("ab.x0")
        val hostService = abi.type("com.mi.ime.MiInputMethodService")
        val contextField = manager.declaredFields.single { it.type == hostService }.apply { isAccessible = true }
        val factory = manager.abiMethod(abi, "b", bridge.engineType)
        val callbackClass = abi.type("ab.b1")
        val keyClass = abi.type("ka.c")
        val keyHandler = abi.type("ab.f1").abiMethod(abi, "c", keyClass, callbackClass)
        val current = callbackClass.abiMethod(abi, "u")
        val uppercase = callbackClass.abiMethod(abi, "o")
        val commit = callbackClass.abiMethod(abi, "K", String::class.java)
        val refresh = callbackClass.abiMethod(abi, "y", abi.type("z8.r"))
        val codeField = keyClass.abiField(abi, "a").apply { isAccessible = true }
        val labelField = keyClass.abiField(abi, "b").apply { isAccessible = true }
        val kindField = keyClass.abiField(abi, "f").apply { isAccessible = true }
        val layoutFactory = KeyboardAbi.qwertyFactory(abi)
        // ab.p1.a is declared as MiInputMethodService, not its Context superclass.
        val layoutContext = layoutFactory.declaringClass.declaredFields.single { hostService.isAssignableFrom(it.type) }
            .apply { isAccessible = true }
        val macros = abi.type("ab.s0").abiMethod(abi, "l", bridge.baseInterface)
        require(factory.returnType == bridge.baseInterface && layoutFactory.returnType == layouts.layout)

        // Both editor-action refresh and mode changes rebuild QWERTY, including SHUANGPIN.
        // Only replace the selected PINYIN/ENGLISH layout, never other QWERTY-based engines.
        val modeChange = layoutFactory.declaringClass.abiMethod(abi, "H", bridge.engineType,
            android.view.inputmethod.EditorInfo::class.java, Boolean::class.javaPrimitiveType)
        val editorChange = layoutFactory.declaringClass.abiMethod(abi, "G",
            android.view.inputmethod.EditorInfo::class.java, bridge.engineType)
        module.hook(modeChange).intercept { chain ->
            layoutMode = (chain.getArg(0) as Enum<*>).name
            chain.proceed()
        }
        module.deoptimize(modeChange)
        module.hook(editorChange).intercept { chain ->
            layoutMode = (chain.getArg(1) as Enum<*>).name
            chain.proceed()
        }
        module.deoptimize(editorChange)

        module.hook(keyHandler).intercept { chain ->
            val key = chain.getArg(0)!!
            val callbacks = chain.getArg(1)!!
            val session = bridge.session(current.invoke(callbacks))
            val label = labelField.get(key) as String
            val code = codeField.getInt(key)
            val kind = (kindField.get(key) as Enum<*>).name
            fun deliver(result: io.mo.xatype.keyboard18.Pinyin18Result) {
                result.commit?.takeIf { it.isNotEmpty() }?.let { commit.invoke(callbacks, it) }
                refresh.invoke(callbacks, bridge.result(result))
            }
            fun flush() { session?.engine?.let { engine -> deliver(engine.snapshot(commit = engine.commitAll())) } }
            val enabled = loaded?.dictionary != null && service?.let {
                KeyboardSelectionHook.selected(it) == KeyboardChoice.KEYBOARD18
            } == true
            if (enabled && label in KeyboardShortcuts.actions) {
                if (ConfigManager.isKeyboardShortcutsEnabled()) runCatching {
                    flush()
                    (session?.service ?: service)?.let { owner -> owner.currentInputConnection?.let { KeyboardShortcutActions.perform(it, label, owner) } }
                }.onFailure { XposedUtils.logError(module, "18-key shortcut failed: $label", it) }
                null
            } else if (session == null) chain.proceed()
            else if (kind == "BACKSPACE" && session.engine.raw.isNotEmpty()) {
                deliver(session.engine.backspace()); null
            } else if (kind == "SPACE" && session.engine.raw.isNotEmpty()) {
                deliver(session.engine.input(' ')); null
            } else if (kind == "CHAR") {
                val group = Keyboard18Keys.group(code)
                val letter = code.toChar()
                session.onInput()
                if ((group != null && uppercase.invoke(callbacks) == true) || letter in 'A'..'Z') {
                    flush()
                    commit.invoke(callbacks, (group?.first()?.uppercaseChar() ?: letter).toString())
                    null
                } else if (group != null || letter in 'a'..'z' || session.engine.raw.isNotEmpty()) {
                    deliver(session.engine.input(letter)); null
                } else chain.proceed()
            } else {
                if (code < 0 && kind != "BACKSPACE") flush()
                chain.proceed()
            }
        }
        module.deoptimize(keyHandler)
        module.hook(macros).intercept { chain ->
            if (bridge.session(chain.getArg(0)) != null) null else chain.proceed()
        }
        module.deoptimize(macros)

        module.hook(factory).intercept { chain ->
            val context = contextField.get(chain.thisObject) as InputMethodService
            service = context
            chineseReady = false
            if (chain.getArg(0) !== bridge.pinyin || KeyboardSelectionHook.selected(context) != KeyboardChoice.KEYBOARD18)
                chain.proceed() else {
                val config = runCatching { load(context) }.onFailure {
                    XposedUtils.logError(module, "18-key dictionary unavailable; retaining native input", it)
                    reportFailure(context, module, "设置或词库读取失败", it)
                }.getOrNull()
                val dictionary = config?.dictionary
                if (dictionary == null) chain.proceed() else runCatching {
                    val sp = context.createDeviceProtectedStorageContext().getSharedPreferences("keyboard18_frequency", Context.MODE_PRIVATE)
                    val engine = Keyboard18Engine(dictionary,
                        frequency = { text -> sp.getInt(text, 0) },
                        learn = { text ->
                            if (sp.contains(text) || sp.all.size < 2048) sp.edit()
                                .putInt(text, (sp.getInt(text, 0) + 1).coerceAtMost(10_000)).apply()
                        })
                    val first = AtomicBoolean(false)
                    report(context, config.settings, "18键离线引擎已接入，等待输入")
                    bridge.create(engine, context) {
                        if (first.compareAndSet(false, true)) report(context, config.settings, "已收到18键输入（未做运行验收）")
                    }
                }.onSuccess { chineseReady = true }
                    .onFailure {
                        chineseReady = false
                        XposedUtils.logError(module, "18-key engine creation failed", it)
                        reportFailure(context, module, "引擎创建失败", it)
                    }
                    .getOrElse { chain.proceed() }
            }
        }
        module.deoptimize(factory)
        module.hook(layoutFactory).intercept { chain ->
            val original = chain.proceed()!!
            val context = layoutContext.get(chain.thisObject) as Context
            if (KeyboardSelectionHook.selected(context) != KeyboardChoice.KEYBOARD18) return@intercept original
            val config = runCatching { load(context) }.onFailure {
                XposedUtils.logError(module, "18-key layout settings unavailable", it)
                reportFailure(context, module, "布局设置读取失败", it)
            }.getOrNull()
            val english = chain.getArg(3) == true
            // English keeps the host's native 26-key QWERTY; only Chinese uses the 18-key grid.
            if (english || config?.dictionary == null || !chineseReady || layoutMode != "PINYIN") original else runCatching {
                layouts.rebuild(original, english = false,
                    semicolon = Keyboard18Store.scheme(config.settings)?.usesSemicolon == true).also { rebuilt ->
                    if (!layoutReported) {
                        layoutReported = true
                        report(context, config.settings, if (rebuilt !== original)
                            "18键布局已替换（中文）"
                        else "18键未替换：当前不是标准26键布局（分离布局暂不支持）")
                    }
                }
            }.onFailure {
                XposedUtils.logError(module, "18-key layout unavailable; retaining native layout", it)
                report(context, config.settings, "18键布局接入失败，已保留原布局")
            }.getOrDefault(original)
        }
        module.deoptimize(layoutFactory)
        // Optional presentation hooks must not prevent the input engine from installing.
        runCatching { Keyboard18HostAppearance(layouts).install(module, loader) }.onFailure {
            HostSymbols.markUnavailable(HostSymbols.KEYBOARD_LETTER_APPEARANCE, it)
            XposedUtils.logError(module, "18-key typography unavailable; retaining native typography", it)
        }
        XposedUtils.log(module, "18-key keyboard hooks installed (opt-in, resolved ABI)")
    }

    @Synchronized
    private fun load(context: Context): Loaded {
        loaded?.let { return it }
        val settings = context.contentResolver.call(Keyboard18ContentProvider.CONTENT_URI, "snapshot", null, null)
            ?: error("无法读取18键设置")
        if (!settings.getBoolean(Keyboard18Store.SHOW_IN_PICKER)) return Loaded(settings, null).also { loaded = it }
        val dictionary = checkNotNull(context.contentResolver.openInputStream(Keyboard18ContentProvider.DICTIONARY_URI))
            .use { Keyboard18Dictionary.read(it, Keyboard18Store.scheme(settings)) }
        val latest = context.contentResolver.call(Keyboard18ContentProvider.CONTENT_URI, "snapshot", null, null)
        check(latest?.getLong(Keyboard18Store.REVISION) == settings.getLong(Keyboard18Store.REVISION)) { "设置已更新，请重启输入法" }
        return Loaded(settings, dictionary).also { loaded = it }
    }
    private fun report(context: Context, settings: Bundle, message: String) {
        val app = context.applicationContext ?: context
        reports.execute { runCatching { app.contentResolver.call(Keyboard18ContentProvider.CONTENT_URI, "report", null,
            Bundle().apply { putLong(Keyboard18Store.REVISION, settings.getLong(Keyboard18Store.REVISION))
                putString(Keyboard18Store.STATUS, message) }) } }
    }
    private fun reportFailure(context: Context, module: XposedInterface, stage: String, failure: Throwable) {
        val app = context.applicationContext ?: context
        reports.execute {
            runCatching {
                val settings = app.contentResolver.call(Keyboard18ContentProvider.CONTENT_URI, "snapshot", null, null)
                    ?: return@runCatching
                if (!settings.getBoolean(Keyboard18Store.SHOW_IN_PICKER)) return@runCatching
                val cause = generateSequence(failure) { it.cause }.last()
                val message = "18键未接入：$stage；${cause.javaClass.simpleName}: ${cause.message.orEmpty()}".take(400)
                app.contentResolver.call(Keyboard18ContentProvider.CONTENT_URI, "report", null, Bundle().apply {
                    putLong(Keyboard18Store.REVISION, settings.getLong(Keyboard18Store.REVISION))
                    putString(Keyboard18Store.STATUS, message)
                })
            }.onFailure { XposedUtils.logWarn(module, "Unable to deliver 18-key failure status: ${it.javaClass.simpleName}") }
        }
    }
}
