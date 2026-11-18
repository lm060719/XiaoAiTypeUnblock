package io.mo.xatype.keyboard

import io.mo.xatype.config.ConfigManager
import io.mo.xatype.config.ConfigSchema
import org.junit.Assert.*
import org.junit.Test

class KeyboardHapticsTest {
    @Test fun nativeLimitIsTheDefaultAndTheSettingIsGlobal() {
        assertEquals(false, ConfigSchema.defaults[ConfigManager.KEY_NATIVE_VIBRATION_LIMIT])
        assertEquals(ConfigSchema.Scope.GLOBAL, ConfigSchema[ConfigManager.KEY_NATIVE_VIBRATION_LIMIT]!!.scope)
    }

    @Test fun originalSliderValuesIncludingZeroKeepTheirMeaning() {
        for (value in 0..100) {
            assertEquals(value, KeyboardHaptics.intensity(value, false))
            assertEquals(value, KeyboardHaptics.intensity(value, true))
        }
    }

    @Test fun extendedValuesArePreservedWithoutMultiplyingTheOriginalRange() {
        assertEquals(150, KeyboardHaptics.intensity(150, true))
        assertEquals(200, KeyboardHaptics.intensity(200, true))
        assertEquals(200, KeyboardHaptics.intensity(Int.MAX_VALUE, true))
        assertEquals(0, KeyboardHaptics.intensity(Int.MIN_VALUE, true))
    }

    @Test fun disablingTheFeatureCapsPreviouslySavedHighValuesAtTheNativeMaximum() {
        assertEquals(100, KeyboardHaptics.intensity(150, false))
        assertEquals(100, KeyboardHaptics.intensity(200, false))
        assertEquals(100, KeyboardHaptics.intensity(Int.MAX_VALUE, false))
    }
}
