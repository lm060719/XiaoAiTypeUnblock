package io.mo.xatype.hooks

import io.github.libxposed.api.XposedInterface
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils

/**
 * Blocks Xiaomi OneTrack analytics / ad-monitor reporting from the input method process.
 *
 * OneTrack ships its own global kill switch: the static `OneTrack.isDisable()` (backed by a
 * boolean field) is checked by both the track dispatcher and the network upload layer
 * (confirmed on 0.2.1053, reversing `work/decomp/src-0.2.1053`):
 *
 *   com.xiaomi.onetrack.api.c.a(...)   :496  ->  if (!OneTrack.isDisable()) { ...track... }
 *                                                else "isDisable is true, Not allowed Track"
 *   com.xiaomi.onetrack.api.av.a(...)  :361  ->  if (OneTrack.isDisable() || ...) return;   // upload
 *   com.xiaomi.onetrack.util.s.a(...)  :183  ->  if (OneTrack.isDisable() || ...) return;   // upload
 *
 * Forcing `isDisable()` to true therefore stops both event recording and the actual network
 * upload with one stable, public-API hook — no need to no-op each track()/adTrack() overload.
 *
 * Scope note: this only silences Xiaomi OneTrack. Any telemetry the bundled iFlyTek
 * SmartEngine kernel performs on its own is out of this hook's reach.
 */
object TelemetryBlockHook {

    private const val ONETRACK_CLASS = "com.xiaomi.onetrack.OneTrack"

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        val oneTrackClass = XposedUtils.findClass(ONETRACK_CLASS, classLoader)
        if (oneTrackClass == null) {
            // OneTrack may be absent in some host builds; nothing to block.
            XposedUtils.log(module, "[Telemetry] $ONETRACK_CLASS not present; skipping")
            return
        }

        val isDisable = XposedUtils.findMethodExact(oneTrackClass, "isDisable")
        if (isDisable == null) {
            XposedUtils.logWarn(module, "[Telemetry] OneTrack.isDisable() not found")
            return
        }

        try {
            module.hook(isDisable).intercept { chain ->
                if (!ConfigManager.isTelemetryBlockEnabled()) return@intercept chain.proceed()
                true
            }
            XposedUtils.log(module, "[Telemetry] Hooked OneTrack.isDisable() -> true")
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Failed to hook OneTrack.isDisable", t)
        }
    }
}
