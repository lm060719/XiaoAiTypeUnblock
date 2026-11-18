package io.mo.xatype.compat

import io.mo.xatype.keyboard.KeyboardHaptics
import java.lang.reflect.Modifier

/** Constructor-time access: only the vibration preference, before saved progress is restored. */
internal object NativeVibrationSlider {
    fun isIntensity(slider: Any): Boolean {
        var type: Class<*>? = slider.javaClass
        while (type != null && type != Any::class.java) {
            if (type.declaredFields.any {
                !Modifier.isStatic(it.modifiers) && it.type == String::class.java &&
                    it.apply { isAccessible = true }.get(slider) == KeyboardHaptics.SLIDER_KEY
            }) return true
            type = type.superclass
        }
        return false
    }

    fun extend(slider: Any) {
        check(isIntensity(slider)) { "Not the native vibration intensity preference" }
        // At construction the value/min/increment are below 100; the unique 100 int is max.
        // Refuse ambiguous layouts rather than modifying a guessed, obfuscated field.
        val maximum = slider.javaClass.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType &&
                it.apply { isAccessible = true }.getInt(slider) == KeyboardHaptics.NATIVE_MAX
        }.single()
        maximum.setInt(slider, KeyboardHaptics.EXTENDED_MAX)
    }
}
