package io.mo.xatype.compat

import org.junit.Assert.*
import org.junit.Test

class NativeVibrationSliderTest {
    open class Preference(val key: String)
    class Slider(key: String) : Preference(key) {
        var value = 0
        var min = 0
        var max = 100
        var increment = 1
    }
    class Ambiguous : Preference("kf_vibration_intensity") {
        var first = 100
        var second = 100
    }

    @Test fun extendsOnlyTheMaximumAndPreservesInitialProgressAndIncrement() {
        val slider = Slider("kf_vibration_intensity")
        NativeVibrationSlider.extend(slider)
        assertEquals(200, slider.max)
        assertEquals(0, slider.value)
        assertEquals(0, slider.min)
        assertEquals(1, slider.increment)
    }

    @Test fun soundVolumeAndOtherPreferencesAreNotModified() {
        val slider = Slider("kf_sound_volume")
        assertFalse(NativeVibrationSlider.isIntensity(slider))
        assertTrue(runCatching { NativeVibrationSlider.extend(slider) }.isFailure)
        assertEquals(100, slider.max)
    }

    @Test fun ambiguousFieldLayoutsFailBeforeMutatingAnyValue() {
        val slider = Ambiguous()
        assertTrue(runCatching { NativeVibrationSlider.extend(slider) }.isFailure)
        assertEquals(100, slider.first)
        assertEquals(100, slider.second)
    }
}
