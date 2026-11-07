package io.mo.xatype.hooks

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiField
import io.mo.xatype.compat.abiMethod
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.keyboard.HostEngine
import io.mo.xatype.keyboard.HostEngineFactory
import io.mo.xatype.keyboard.HostKeyPath
import io.mo.xatype.keyboard.KeyOp
import io.mo.xatype.keyboard.KeyboardChoice
import io.mo.xatype.keyboard.installMacroGuard
import io.mo.xatype.keyboard.KeyboardShortcutActions
import io.mo.xatype.keyboard.KeyboardShortcuts
import io.mo.xatype.keyboard18.Keyboard18CompositionStore
import io.mo.xatype.keyboard18.Keyboard18Dictionary
import io.mo.xatype.keyboard18.Keyboard18Engine
import io.mo.xatype.keyboard18.Keyboard18HostAppearance
import io.mo.xatype.keyboard18.Keyboard18HostBridge
import io.mo.xatype.keyboard18.Keyboard18HostLayout
import io.mo.xatype.keyboard18.Pinyin18Entry
import io.mo.xatype.keyboard.ShuangpinScheme
import io.mo.xatype.keyboard18.GroupedKeys
import io.mo.xatype.keyboard18.Keyboard14Keys
import io.mo.xatype.keyboard18.Keyboard18Keys
import io.mo.xatype.keyboard18.Keyboard18Store
import io.mo.xatype.keyboard18.Pinyin18Result
import io.mo.xatype.provider.Keyboard18ContentProvider
import io.mo.xatype.util.XposedUtils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in 18/14-key keyboards and independent offline decoder, validated against the resolved host ABI. */
object Keyboard18Hook {
    private val reports = Executors.newSingleThreadExecutor()
    /** Off the main thread: a full load parses ~100k rows and builds two tries. */
    private val preloader = Executors.newSingleThreadExecutor { Thread(it, "xatype-keyboard18-preload").apply { isDaemon = true } }
    private data class Loaded(val settings: Bundle, val keys: GroupedKeys, val dictionary: Keyboard18Dictionary?)
    /** Parsed rows depend only on the scheme, so switching 18 <-> 14 keys rebuilds just the tries. */
    private class Corpus(val scheme: ShuangpinScheme?, val entries: List<Pinyin18Entry>)
    @Volatile private var loaded: Loaded? = null
    private var corpus: Corpus? = null
    @Volatile private var module: XposedInterface? = null
    @Volatile private var service: InputMethodService? = null
    @Volatile private var layoutMode = "PINYIN"
    @Volatile private var chineseReady = false
    @Volatile private var installFailure: Throwable? = null
    @Volatile private var reportedLayout: GroupedKeys? = null
    @Volatile private var applicationContext: Context? = null

    fun install(module: XposedInterface, loader: ClassLoader) {
        this.module = module
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
                // The IME service is bound well before the keyboard first shows; load while it is idle.
                if (installFailure == null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                        Application.getProcessName() == context.packageName)) preload(context)
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
        val engines = HostEngineFactory(abi, bridge.host)
        val hostService = abi.type("com.mi.ime.MiInputMethodService")
        val contextField = engines.manager.declaredFields.single { it.type == hostService }.apply { isAccessible = true }
        val factory = engines.method
        val keys = HostKeyPath(loader, abi, bridge.host)
        val keyClass = keys.keyClass
        val codeField = keyClass.abiField(abi, "a").apply { isAccessible = true }
        val labelField = keyClass.abiField(abi, "b").apply { isAccessible = true }
        val kindField = keyClass.abiField(abi, "f").apply { isAccessible = true }
        val layoutFactory = KeyboardAbi.qwertyFactory(abi)
        // ab.p1.a is declared as MiInputMethodService, not its Context superclass.
        val layoutContext = layoutFactory.declaringClass.declaredFields.single { hostService.isAssignableFrom(it.type) }
            .apply { isAccessible = true }
        require(layoutFactory.returnType == layouts.layout)

        // Both editor-action refresh and mode changes rebuild QWERTY, including SHUANGPIN.
        // Only replace the selected PINYIN/ENGLISH layout, never other QWERTY-based engines.
        val modeChange = layoutFactory.declaringClass.abiMethod(abi, "H", bridge.engineType,
            android.view.inputmethod.EditorInfo::class.java, Boolean::class.javaPrimitiveType)
        val editorChange = layoutFactory.declaringClass.abiMethod(abi, "G",
            android.view.inputmethod.EditorInfo::class.java, bridge.engineType)
        module.hook(modeChange).intercept { chain ->
            (chain.getArg(0) as? Enum<*>)?.let { layoutMode = it.name }
            chain.proceed()
        }
        module.deoptimize(modeChange)
        module.hook(editorChange).intercept { chain ->
            (chain.getArg(1) as? Enum<*>)?.let { layoutMode = it.name }
            chain.proceed()
        }
        module.deoptimize(editorChange)

        module.hook(keys.handler).intercept { chain ->
            if (keys.resuming(chain)) return@intercept chain.proceed()
            val key = chain.args[0]!!
            val callbacks = chain.args[1]!!
            val session = bridge.session(keys.engine(callbacks))
            val label = labelField.get(key) as String
            val code = codeField.getInt(key)
            val kind = (kindField.get(key) as Enum<*>).name
            val host = bridge.host
            // Engine operations run directly through 0.2.1246 and as session commands after.
            fun engineOp(direct: () -> Pinyin18Result, command: () -> Any) =
                KeyOp.Engine({ bridge.result(synchronized(session!!.engine, direct)) }, command)
            fun flush(): KeyOp = engineOp({ session!!.engine.let { it.snapshot(commit = it.commitAll()) } }) {
                host.replaceRaw(HostEngine.FLUSH)
            }
            fun perform(ops: List<KeyOp>, thenNative: Boolean = false) = keys.perform(module, chain, ops, thenNative) {
                XposedUtils.logError(module, "18-key input delivery failed", it)
            }
            val enabled = loaded?.dictionary != null && service?.let { groupedKeys(it) } != null
            if (enabled && session != null && label in KeyboardShortcuts.actions) {
                if (!ConfigManager.isKeyboardShortcutsEnabled()) keys.consumed() else perform(listOf(flush(), KeyOp.Action {
                    runCatching {
                        session.service.currentInputConnection?.let { KeyboardShortcutActions.perform(it, label) }
                    }.onFailure { XposedUtils.logError(module, "Grouped-key shortcut failed: $label", it) }
                }))
            } else if (session == null) chain.proceed()
            else if (kind == "BACKSPACE" && session.engine.raw.isNotEmpty()) {
                perform(listOf(engineOp({ session.engine.backspace() }) { host.backspace!! }))
            } else if (kind == "SPACE" && session.engine.raw.isNotEmpty()) {
                perform(listOf(engineOp({ session.engine.input(' ') }) { host.key(' '.code) }))
            } else if (kind == "CHAR" || (session.engine.keys.separatorKey && Keyboard18HostLayout.isSeparator(code))) {
                // The 14-key separator is the host's segmentation key but types like a character.
                val grouped = session.engine.keys
                val separator = grouped.separatorKey && Keyboard18HostLayout.isSeparator(code)
                val group = if (separator) null else grouped.group(code)
                val letter = if (separator) Keyboard18HostLayout.SEPARATOR else code.toChar()
                session.onInput()
                // 14-key shows a separator instead of shift; a shift left over from English must not
                // turn its group taps into capitals that cannot be released from this layout.
                if ((group != null && !grouped.separatorKey && keys.uppercase(callbacks)) || letter in 'A'..'Z') {
                    // Native keys release a one-shot shift after the uppercase letter; caps lock stays.
                    perform(listOf(flush(), KeyOp.Commit((group?.first()?.uppercaseChar() ?: letter).toString()),
                        KeyOp.Action { keys.releaseShift(callbacks) }))
                } else if (group != null || letter in 'a'..'z' || session.engine.raw.isNotEmpty()) {
                    perform(listOf(engineOp({ session.engine.input(letter) }) { host.key(letter.code) }))
                } else if (grouped.separatorKey && letter == Keyboard18HostLayout.SEPARATOR) {
                    // With nothing composing, the separator key types an apostrophe.
                    perform(listOf(KeyOp.Commit(letter.toString())))
                } else chain.proceed()
            } else if (code < 0 && kind != "BACKSPACE" && session.engine.raw.isNotEmpty()) {
                perform(listOf(flush()), thenNative = true)
            } else chain.proceed()
        }
        module.deoptimize(keys.handler)
        installMacroGuard(module, abi, bridge.host) { bridge.session(it) != null }

        module.hook(factory).intercept { chain ->
            val context = contextField.get(engines.manager(chain)) as InputMethodService
            service = context
            chineseReady = false
            val grouped = groupedKeys(context)
            if (engines.engineType(chain) !== bridge.pinyin || grouped == null)
                chain.proceed() else {
                val config = runCatching { load(context, grouped) }.onFailure {
                    XposedUtils.logError(module, "Grouped-key dictionary unavailable; retaining native input", it)
                    reportFailure(context, module, "设置或词库读取失败", it)
                }.getOrNull()
                val dictionary = config?.dictionary
                if (dictionary == null) chain.proceed() else runCatching {
                    val device = context.createDeviceProtectedStorageContext()
                    val sp = device.getSharedPreferences("keyboard18_frequency", Context.MODE_PRIVATE)
                    val compositions = Keyboard18CompositionStore(
                        device.getSharedPreferences("keyboard18_compositions", Context.MODE_PRIVATE))
                    val engine = Keyboard18Engine(dictionary,
                        frequency = { text -> sp.getInt(text, 0) },
                        learn = { text ->
                            if (sp.contains(text) || sp.all.size < 2048) sp.edit()
                                .putInt(text, (sp.getInt(text, 0) + 1).coerceAtMost(10_000)).apply()
                        },
                        compositionCandidates = compositions::candidates,
                        recordComposition = compositions::record)
                    val first = AtomicBoolean(false)
                    report(context, config.settings, "${grouped.title}离线引擎已接入，等待输入")
                    bridge.create(engine, context) {
                        if (first.compareAndSet(false, true)) report(context, config.settings, "已收到${grouped.title}输入（未做运行验收）")
                    }
                }.onSuccess { chineseReady = true }
                    .onFailure {
                        chineseReady = false
                        XposedUtils.logError(module, "Grouped-key engine creation failed", it)
                        reportFailure(context, module, "引擎创建失败", it)
                    }
                    .getOrElse { chain.proceed() }
            }
        }
        module.deoptimize(factory)
        module.hook(layoutFactory).intercept { chain ->
            val original = chain.proceed()!!
            val context = layoutContext.get(chain.thisObject) as Context
            val grouped = groupedKeys(context) ?: return@intercept original
            val config = runCatching { load(context, grouped) }.onFailure {
                XposedUtils.logError(module, "18-key layout settings unavailable", it)
                reportFailure(context, module, "布局设置读取失败", it)
            }.getOrNull()
            val english = chain.getArg(3) == true
            // English keeps the host's native 26-key QWERTY; only Chinese uses the grouped grid.
            if (english || config?.dictionary == null || !chineseReady || layoutMode != "PINYIN") original else runCatching {
                layouts.rebuild(original, english = false,
                    semicolon = Keyboard18Store.scheme(config.settings)?.usesSemicolon == true, keys = grouped).also { rebuilt ->
                    if (reportedLayout !== grouped) {
                        reportedLayout = grouped
                        report(context, config.settings, if (rebuilt !== original)
                            "${grouped.title}布局已替换（中文）"
                        else "${grouped.title}未替换：当前不是标准26键布局（分离布局暂不支持）")
                    }
                }
            }.onFailure {
                XposedUtils.logError(module, "Grouped-key layout unavailable; retaining native layout", it)
                report(context, config.settings, "${grouped.title}布局接入失败，已保留原布局")
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

    private fun groupedKeys(context: Context): GroupedKeys? = when (KeyboardSelectionHook.selected(context)) {
        KeyboardChoice.KEYBOARD18 -> Keyboard18Keys
        KeyboardChoice.KEYBOARD14 -> Keyboard14Keys
        else -> null
    }

    /**
     * Loads the selected grouped keyboard's dictionary in the background. Engine creation still calls
     * [load] on the main thread; it then finds the cache, or waits only for the load already under way.
     */
    fun preload(context: Context) {
        val app = context.applicationContext ?: context
        preloader.execute {
            runCatching {
                val keys = groupedKeys(app) ?: return@runCatching
                if (load(app, keys).dictionary == null) return@runCatching
                // Warm the preferences engine creation opens, so their first read does not block either.
                val device = app.createDeviceProtectedStorageContext()
                device.getSharedPreferences("keyboard18_frequency", Context.MODE_PRIVATE).all
                device.getSharedPreferences("keyboard18_compositions", Context.MODE_PRIVATE).all
            }.onFailure { failure ->
                // Engine creation retries on the main thread and reports a failure there.
                module?.let { XposedUtils.logWarn(it, "18-key preload failed: ${failure.javaClass.simpleName}: ${failure.message}") }
            }
        }
    }

    /** One dictionary stays resident: switching between 18 and 14 keys rebuilds its tries for the new grouping. */
    @Synchronized
    private fun load(context: Context, keys: GroupedKeys): Loaded {
        loaded?.takeIf { it.keys === keys }?.let { return it }
        loaded = null
        val settings = context.contentResolver.call(Keyboard18ContentProvider.CONTENT_URI, "snapshot", null, null)
            ?: error("无法读取${keys.title}设置")
        if (!Keyboard18Store.visible(settings, keys)) return Loaded(settings, keys, null).also { loaded = it }
        val scheme = Keyboard18Store.scheme(settings)
        val entries = corpus?.takeIf { it.scheme == scheme }?.entries
            ?: checkNotNull(context.contentResolver.openInputStream(Keyboard18ContentProvider.DICTIONARY_URI))
                .use { Keyboard18Dictionary.entries(it, scheme) }
                .also { corpus = Corpus(scheme, it) }
        val dictionary = Keyboard18Dictionary.build(entries, scheme, keys)
        val latest = context.contentResolver.call(Keyboard18ContentProvider.CONTENT_URI, "snapshot", null, null)
        check(latest?.getLong(Keyboard18Store.REVISION) == settings.getLong(Keyboard18Store.REVISION)) { "设置已更新，请重启输入法" }
        return Loaded(settings, keys, dictionary).also { loaded = it }
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
                val keys = applicationContext?.let(::groupedKeys) ?: Keyboard18Keys
                if (!Keyboard18Store.visible(settings, keys)) return@runCatching
                val cause = generateSequence(failure) { it.cause }.last()
                val message = "${keys.title}未接入：$stage；${cause.javaClass.simpleName}: ${cause.message.orEmpty()}".take(400)
                app.contentResolver.call(Keyboard18ContentProvider.CONTENT_URI, "report", null, Bundle().apply {
                    putLong(Keyboard18Store.REVISION, settings.getLong(Keyboard18Store.REVISION))
                    putString(Keyboard18Store.STATUS, message)
                })
            }.onFailure { XposedUtils.logWarn(module, "Unable to deliver 18-key failure status: ${it.javaClass.simpleName}") }
        }
    }
}
