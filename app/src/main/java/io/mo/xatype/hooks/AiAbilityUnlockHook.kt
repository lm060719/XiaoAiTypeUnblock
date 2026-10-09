package io.mo.xatype.hooks

import io.github.libxposed.api.XposedInterface
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils

/**
 * Per-ability unlock for the Taiyi AI SDK.
 *
 * Call chain (confirmed on 0.2.1053, reversing `work/decomp/src-0.2.1053`):
 *
 *   gc.b.d(AIRequest)                                    // in-process dispatcher (com.xiaomi.type)
 *     -> gc.c.a(ctx, req)  "AIQuery.getAIDetail"
 *          -> ContentProviderClient.call(
 *                 "com.xiaomi.aiservice.authority.open", "query", sub_method="getAIDetail")
 *             returns AIResponse                         // decided by the external com.xiaomi.aiservice
 *          -> AIDetail.parse(api, AIResponse)            // config(disabled) + body("ai_available") + code
 *     -> if (detail.isSupport())   // AIConfig.disabled, from the response JSON
 *     -> if (detail.isAvailable()) // body "ai_available", else isSupport() && code == 0
 *          -> execute(req)                               // another IPC back to aiservice
 *
 * The whitelist / condition verdict (whiteModels / whiteSocs / whiteRegions / minRam / ...
 * from AIKeys) is evaluated inside com.xiaomi.aiservice and merely parsed here. Both gate
 * checks that the keyboard consumes — isSupport() and isAvailable() — run in-process, so
 * forcing them true lifts the keyboard-side gate for abilities the service reported as
 * unsupported/unavailable on this device.
 *
 * Caveat (surfaced in the verbose log): this only makes the keyboard *attempt* execution.
 * The actual run is another IPC to aiservice; abilities that genuinely need a model the
 * service will not provision on this hardware can still fail downstream with an error code.
 */
object AiAbilityUnlockHook {

    private const val AI_DETAIL_CLASS = "com.xiaomi.taiyi.sdk.base.data.AIDetail"

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        val aiDetailClass = XposedUtils.findClass(AI_DETAIL_CLASS, classLoader)
        if (aiDetailClass == null) {
            XposedUtils.logWarn(module, "[AI Ability] $AI_DETAIL_CLASS not found; skipping")
            return
        }

        var installed = 0
        for (methodName in arrayOf("isSupport", "isAvailable")) {
            val method = XposedUtils.findMethodExact(aiDetailClass, methodName)
            if (method == null) {
                XposedUtils.logWarn(module, "[AI Ability] AIDetail.$methodName() not found")
                continue
            }
            try {
                module.hook(method).intercept { chain ->
                    if (!ConfigManager.isAiAbilityUnlockEnabled()) return@intercept chain.proceed()

                    val original = chain.proceed()
                    if (original == true) return@intercept true

                    if (ConfigManager.isVerboseLogEnabled()) {
                        XposedUtils.log(
                            module,
                            "[AI Ability] AIDetail.$methodName() ${describe(chain.thisObject)} " +
                                "false -> true (keyboard gate lifted; execution still depends on AI service)"
                        )
                    }
                    true
                }
                installed++
                XposedUtils.log(module, "[AI Ability] Hooked AIDetail.$methodName()")
            } catch (t: Throwable) {
                XposedUtils.logError(module, "Failed to hook AIDetail.$methodName", t)
            }
        }

        if (installed == 0) {
            XposedUtils.logWarn(module, "[AI Ability] No AIDetail gate methods hooked")
        }
    }

    /** Best-effort label (ability api + response code) for diagnostics; never throws. */
    private fun describe(detail: Any?): String {
        if (detail == null) return "[api=?]"
        val api = runCatching { XposedUtils.getObjectField(detail, "api") }.getOrNull()
        val code = runCatching { XposedUtils.getObjectField(detail, "code") }.getOrNull()
        return "[api=$api code=$code]"
    }
}
