package io.mo.xatype.hooks

import android.content.Context
import android.util.AttributeSet
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardHapticBinding
import io.mo.xatype.compat.NativeVibrationSlider
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.keyboard.KeyboardHaptics
import io.mo.xatype.util.XposedUtils

/** Extend the native preference; the original manager still owns all haptic playback. */
object KeyboardHapticsHook {
    fun install(module: XposedInterface, loader: ClassLoader) {
        val binding = KeyboardHapticBinding(Class.forName("com.mi.ime.MiInputMethodService", false, loader))
        val reader = checkNotNull(HostSymbols.method(HostSymbols.KEYBOARD_HAPTIC_INTENSITY))
        val preference = Class.forName("androidx.preference.SeekBarPreference", false, loader)
        val constructor = preference.getDeclaredConstructor(Context::class.java, AttributeSet::class.java)
        var failureLogged = false
        fun sync(context: Context) {
            runCatching { ConfigManager.syncFromProvider(context) }.onFailure { failure ->
                if (!failureLogged) {
                    failureLogged = true
                    XposedUtils.logError(module, "Unable to sync native vibration limit", failure)
                }
            }
        }

        // Cap old saved values too: disabling the feature must restore the actual native maximum.
        module.hook(reader).intercept { chain ->
            val native = chain.proceed() as Int
            if (chain.getArg(1) != KeyboardHaptics.INTENSITY_KEY) native
            else KeyboardHaptics.intensity(native, ConfigManager.isNativeVibrationLimitEnabled())
        }
        module.deoptimize(reader)
        module.hook(binding.dispatch).intercept { chain ->
            runCatching { sync(binding.context(checkNotNull(chain.thisObject))) }.onFailure {
                if (!failureLogged) {
                    failureLogged = true
                    XposedUtils.logError(module, "Native vibration context unavailable", it)
                }
            }
            chain.proceed()
        }
        module.deoptimize(binding.dispatch)

        // Extend before Preference restores its saved value, avoiding the native 100-value clamp.
        module.hook(constructor).intercept { chain ->
            val result = chain.proceed()
            runCatching {
                val slider = checkNotNull(chain.thisObject)
                if (NativeVibrationSlider.isIntensity(slider)) {
                    sync(chain.getArg(0) as Context)
                    if (ConfigManager.isNativeVibrationLimitEnabled()) {
                        NativeVibrationSlider.extend(slider)
                    }
                }
            }.onFailure {
                HostSymbols.markUnavailable(HostSymbols.KEYBOARD_HAPTICS, it)
                XposedUtils.logError(module, "Native vibration slider range unavailable", it)
            }
            result
        }
        XposedUtils.log(module, "Native vibration slider limit installed (100 -> ${KeyboardHaptics.EXTENDED_MAX})")
    }
}
