package io.mo.xatype.hooks

import android.content.Context
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.keyboard.HostEngineFactory
import io.mo.xatype.keyboard.KeyboardChoice
import io.mo.xatype.keyboard.installMacroGuard
import io.mo.xatype.provider.ShapeContentProvider
import io.mo.xatype.shape.ShapeDictionary
import io.mo.xatype.shape.ShapeDictionaryCodec
import io.mo.xatype.shape.ShapeEngine
import io.mo.xatype.shape.ShapeHostBridge
import io.mo.xatype.shape.ShapeHostKeyAdapter
import io.mo.xatype.shape.ShapeStore
import io.mo.xatype.util.XposedUtils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in replacement of the PINYIN engine, enabled only after the engine ABI resolves. */
object ShapeInputHook {
    private val reports = Executors.newSingleThreadExecutor()
    private var loaded: Loaded? = null
    private data class Loaded(val settings: Bundle, val dictionary: ShapeDictionary)

    fun install(module: XposedInterface, loader: ClassLoader) {
        val abi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_ENGINE)
        // A missing/mismatched class leaves the original factory untouched.
        val bridge = ShapeHostBridge(loader, abi)
        val engines = HostEngineFactory(abi, bridge.host)
        val service = abi.type("com.mi.ime.MiInputMethodService")
        val contextField = engines.manager.declaredFields.single { it.type == service }.apply { isAccessible = true }
        val factory = engines.method
        val keys = ShapeHostKeyAdapter(loader, bridge, abi)

        // Bypass phonetic touch correction only for our own session and supported keys.
        // Other engines and keyboard controls continue through the original handler.
        module.hook(keys.path.handler).intercept { chain -> keys.intercept(module, chain) }
        module.deoptimize(keys.path.handler)

        // Pinyin time/date/calculator expansions must not add or displace shape candidates.
        installMacroGuard(module, abi, bridge.host) { bridge.session(it) != null }

        module.hook(factory).intercept { chain ->
            if (engines.engineType(chain) !== bridge.pinyin) chain.proceed() else {
                val context = contextField.get(engines.manager(chain)) as Context
                if (KeyboardSelectionHook.selected(context) != KeyboardChoice.SHAPE) return@intercept chain.proceed()
                val config = runCatching { load(context) }.onFailure {
                    XposedUtils.logError(module, "Shape dictionary unavailable; keeping original engine", it)
                }.getOrNull()
                if (config == null || !config.settings.getBoolean(ShapeStore.SHOW_IN_PICKER)) chain.proceed()
                else {
                    runCatching {
                        val engine = ShapeEngine(config.dictionary, ShapeStore.rules(config.settings, config.dictionary.maxCodeLength))
                        val firstInput = AtomicBoolean(false)
                        report(context, config.settings, "形码引擎已接入，等待输入验证（${config.dictionary.name}）")
                        bridge.create(engine) {
                            if (firstInput.compareAndSet(false, true)) report(context, config.settings, "已收到形码输入（${config.dictionary.name}）")
                        }
                    }.onFailure { XposedUtils.logError(module, "Shape engine creation failed; keeping original engine", it) }
                        .getOrElse { chain.proceed() }
                }
            }
        }
        module.deoptimize(factory)
        XposedUtils.log(module, "Shape input ABI ready (opt-in, resolved ABI)")
    }

    @Synchronized
    private fun load(context: Context): Loaded? {
        loaded?.let { return it }
        val settings = context.contentResolver.call(ShapeContentProvider.CONTENT_URI, "prepare", null, null) ?: return null
        if (!settings.getBoolean(ShapeStore.SHOW_IN_PICKER)) return null
        val dictionary = checkNotNull(context.contentResolver.openInputStream(ShapeContentProvider.DICTIONARY_URI))
            .use(ShapeDictionaryCodec::read)
        // An import must not pair a new table with settings captured before the import.
        val latest = context.contentResolver.call(ShapeContentProvider.CONTENT_URI, "snapshot", null, null)
        check(latest?.getLong(ShapeStore.REVISION) == settings.getLong(ShapeStore.REVISION)) { "码表已更新，请重启输入法" }
        return Loaded(settings, dictionary).also { loaded = it }
    }

    private fun report(context: Context, settings: Bundle, message: String) {
        val app = context.applicationContext ?: context
        reports.execute {
            runCatching { app.contentResolver.call(ShapeContentProvider.CONTENT_URI, "report", null, Bundle().apply {
                putLong(ShapeStore.REVISION, settings.getLong(ShapeStore.REVISION))
                putString(ShapeStore.STATUS, message)
            }) }
        }
    }

}
