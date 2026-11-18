package io.mo.xatype.keyboard

/** Native percentage values pass through unchanged, including zero (silent). */
object KeyboardHaptics {
    const val INTENSITY_KEY = "vibration_intensity"
    const val SLIDER_KEY = "kf_vibration_intensity"
    const val NATIVE_MAX = 100
    const val EXTENDED_MAX = 200

    fun intensity(value: Int, enabled: Boolean) = value.coerceIn(0, if (enabled) EXTENDED_MAX else NATIVE_MAX)
}
