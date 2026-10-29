package io.mo.xatype.hooks

import android.inputmethodservice.InputMethodService
import android.text.InputType
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.BuildConfig
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiMethod
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.keyboard.EmojiCandidates
import io.mo.xatype.keyboard.EmojiHostBridge
import io.mo.xatype.keyboard.PredictionMode
import io.mo.xatype.keyboard.ShuangpinScheme
import io.mo.xatype.util.XposedUtils
import java.util.concurrent.CompletableFuture

object EmojiCandidateHook {
    fun install(module: XposedInterface, loader: ClassLoader) {
        val abi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_ENGINE)
        val bridge = EmojiHostBridge(loader, abi)
        val manager = abi.type("ab.x0")
        val serviceField = manager.declaredFields.single {
            it.type == abi.type("com.mi.ime.MiInputMethodService")
        }.apply { isAccessible = true }
        val factory = manager.abiMethod(abi, "b", bridge.engineType)
        require(factory.returnType == bridge.baseInterface)
        // These existing fingerprints resolve the same preferences used by both native layouts.
        val nativeBoolean = HostSymbols.method(HostSymbols.T9_SHUANGPIN_BOOLEAN)
        val nativeString = HostSymbols.method(HostSymbols.T9_SHUANGPIN_STRING)
        val dictionary by lazy {
            CompletableFuture.supplyAsync {
                runCatching {
                    checkNotNull(javaClass.getResourceAsStream("/xatype/emoji.tsv")) { "Emoji table missing" }
                        .reader(Charsets.UTF_8).use(EmojiCandidates::read)
                }.onSuccess { XposedUtils.log(module, "Emoji dictionary loaded") }
                    .onFailure { XposedUtils.logError(module, "Emoji dictionary unavailable", it) }.getOrNull()
            }
        }
        module.hook(factory).intercept { chain ->
            val original = chain.proceed() ?: return@intercept null
            val kind = (chain.getArg(0) as? Enum<*>)?.name
            if (BuildConfig.DEBUG) XposedUtils.log(module,
                "[Emoji] factory mode=$kind engine=${original.javaClass.name} supported=${bridge.supports(original)}")
            if (kind !in setOf("PINYIN", "T9", "SHUANGPIN") || !bridge.supports(original)) return@intercept original
            runCatching {
                val service = serviceField.get(chain.thisObject) as InputMethodService
                // Disabled users keep the unwrapped native engine; enabling takes effect after an IME restart.
                ConfigManager.syncFromProvider(service)
                if (!ConfigManager.isEmojiCandidatesEnabled() && ConfigManager.getPredictionMode() == PredictionMode.KEEP)
                    return@intercept original
                if (ConfigManager.isEmojiCandidatesEnabled()) dictionary.getNow(null) // Load only for emoji enhancement.
                bridge.create(original, { dictionary.getNow(null) }, {
                    val inputType = service.currentInputEditorInfo?.inputType ?: InputType.TYPE_NULL
                    val variation = inputType and InputType.TYPE_MASK_VARIATION
                    ConfigManager.isEmojiCandidatesEnabled() &&
                        (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                        variation !in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                }, scheme = {
                    if (kind == "T9" && ConfigManager.isT9ShuangpinEnabled()) {
                        ShuangpinScheme.resolve(ConfigManager.getT9ShuangpinScheme())
                    } else if (nativeBoolean?.invoke(null, "shuangpin_enabled", false) == true) {
                        ShuangpinScheme.resolve(nativeString?.invoke(null, "shuangpin_scheme", "XIAOHE") as? String)
                    } else null
                }, prediction = ConfigManager::getPredictionMode, predictionFailed = { failure ->
                    HostSymbols.markUnavailable(HostSymbols.KEYBOARD_PREDICTION, failure)
                    XposedUtils.logError(module, "Post-commit prediction control disabled for this session", failure)
                }, observe = { message ->
                    if (BuildConfig.DEBUG) XposedUtils.log(module, "[Emoji] $message")
                }, failed = { failure ->
                    HostSymbols.markUnavailable(HostSymbols.KEYBOARD_EMOJI, failure)
                    XposedUtils.logError(module, "Emoji candidates disabled for this session", failure)
                })
            }.onFailure {
                HostSymbols.markUnavailable(HostSymbols.KEYBOARD_EMOJI, it)
                HostSymbols.markUnavailable(HostSymbols.KEYBOARD_PREDICTION, it)
                XposedUtils.logError(module, "Emoji bridge unavailable; keeping native engine", it)
            }.getOrDefault(original)
        }
        module.deoptimize(factory)
        XposedUtils.log(module, "Native candidate enhancements ready (opt-in, resolved ABI)")
    }
}
