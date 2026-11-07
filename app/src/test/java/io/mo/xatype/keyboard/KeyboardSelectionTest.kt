package io.mo.xatype.keyboard

import org.junit.Assert.*
import org.junit.Test
import z9.g6

class KeyboardSelectionTest {
    private val picker = KeyboardPickerBridge(javaClass.classLoader!!, io.mo.xatype.compat.KeyboardAbi.reference(javaClass.classLoader!!))
    private fun native() = arrayOf(
        g6(26, "qwerty_chinese", "拼音26键"), g6(9, "t9_chinese", "拼音9键"),
        g6(1, "handwriting_chinese", "手写"), g6(2, "stroke_chinese", "笔画"))

    @Test fun bothChoicesCoexistAndKeepNativeItems() {
        val original = native()
        val result = picker.extend(original, true, true)!!
        assertEquals(6, result.size)
        original.forEachIndexed { index, item -> assertSame(item, result[index]) }
        assertEquals(listOf("xatype_shape", "xatype_keyboard18"), result.drop(4).map { (it as g6).a })
        assertEquals(listOf("虎码/五笔", "18键"), result.drop(4).map { (it as g6).b })
        assertEquals(listOf(26, 26), result.drop(4).map { (it as g6).c })
        assertEquals(4, original.size)
    }

    @Test fun nativeModesStayListedWhenAModuleEngineLoadedFirst() {
        // A module engine created first leaves the host's native core unloaded: only English is listed.
        val englishOnly = setOf("qwerty_english")
        val modes = KeyboardPickerBridge.modes(englishOnly, KeyboardChoice.KEYBOARD14, false, true, true)
        assertTrue(modes.containsAll(KeyboardPickerBridge.NATIVE_IDS + "qwerty_english"))
        assertTrue(modes.containsAll(listOf(KeyboardChoice.KEYBOARD18.id, KeyboardChoice.KEYBOARD14.id)))
        assertFalse(KeyboardChoice.SHAPE.id in modes)
        // With native pinyin selected, the host's own availability is kept as it is.
        assertEquals(englishOnly, KeyboardPickerBridge.modes(englishOnly, KeyboardChoice.PINYIN, false, false, false))
        val partial = setOf("qwerty_chinese", "t9_chinese")
        assertEquals(partial, KeyboardPickerBridge.modes(partial, KeyboardChoice.KEYBOARD18, false, false, false))
    }

    @Test fun keyboard14IsAddedAfterOtherChoicesAndHiddenByDefault() {
        val result = picker.extend(native(), true, true, show14 = true)!!
        assertEquals(listOf("xatype_shape", "xatype_keyboard18", "xatype_keyboard14"), result.drop(4).map { (it as g6).a })
        assertEquals("14键", (result.last() as g6).b)
        assertEquals(KeyboardChoice.PINYIN, KeyboardChoice.resolve(KeyboardChoice.KEYBOARD14.id, true, true))
        assertEquals(KeyboardChoice.KEYBOARD14, KeyboardChoice.resolve(KeyboardChoice.KEYBOARD14.id, false, false, show14 = true))
        assertTrue(KeyboardChoice.KEYBOARD14.grouped && KeyboardChoice.KEYBOARD18.grouped && !KeyboardChoice.SHAPE.grouped)
    }

    @Test fun visibilitySwitchesAreIndependent() {
        for (shape in listOf(false, true)) for (keyboard18 in listOf(false, true)) {
            val ids = picker.extend(native(), shape, keyboard18)!!.map { (it as g6).a }
            assertEquals(shape, KeyboardChoice.SHAPE.id in ids)
            assertEquals(keyboard18, KeyboardChoice.KEYBOARD18.id in ids)
            assertTrue(KeyboardChoice.PINYIN.id in ids)
        }
    }

    @Test fun unrelatedKotlinListsAreNotModified() {
        assertNull(picker.extend(arrayOf("PINYIN", "T9", "STROKE", "ENGLISH"), true, true))
        assertNull(picker.extend(native().drop(1).toTypedArray(), true, true))
        val unrelated = native().also { it[3] = g6(2, "other", "其他") }
        assertNull(picker.extend(unrelated, true, true))
    }

    @Test fun customChoicesRemainAvailableWhenOtherNativeEnginesAreMissing() {
        val available = native().take(2).toTypedArray()
        val result = picker.extend(available, true, true)!!
        assertEquals(listOf("qwerty_chinese", "t9_chinese", "xatype_shape", "xatype_keyboard18"), result.map { (it as g6).a })
        assertNull(picker.extend(arrayOf(available[0], available[0]), true, true))
        assertNull(picker.extend(emptyArray<Any>(), true, true))
    }

    @Test fun selectionSurvivesRestartAndSwitchesBackToNative() {
        for (choice in KeyboardChoice.entries) {
            assertEquals(choice, KeyboardChoice.resolve(choice.id, true, true, legacyShape = true, show14 = true))
        }
        for (native in listOf("t9_chinese", "stroke_chinese", "handwriting_chinese", "invalid")) {
            assertEquals(KeyboardChoice.PINYIN, KeyboardChoice.resolve(native, true, true))
        }
        assertEquals(KeyboardChoice.PINYIN, KeyboardChoice.resolve(KeyboardChoice.PINYIN.id, true, true,
            legacyShape = true, legacy18 = true))
    }

    @Test fun hidingActiveChoiceFallsBackWithoutAffectingOtherChoice() {
        assertEquals(KeyboardChoice.PINYIN, KeyboardChoice.resolve(KeyboardChoice.SHAPE.id, false, true))
        assertEquals(KeyboardChoice.PINYIN, KeyboardChoice.resolve(KeyboardChoice.KEYBOARD18.id, true, false))
        assertEquals(KeyboardChoice.SHAPE, KeyboardChoice.resolve(KeyboardChoice.SHAPE.id, true, false))
        assertEquals(KeyboardChoice.KEYBOARD18, KeyboardChoice.resolve(KeyboardChoice.KEYBOARD18.id, false, true))
    }

    @Test fun migrateOldEnabledChoiceOnlyWhenNoNewSelectionExists() {
        assertEquals(KeyboardChoice.PINYIN, KeyboardChoice.resolve(null, true, true))
        assertEquals(KeyboardChoice.SHAPE, KeyboardChoice.resolve(null, true, true, legacyShape = true))
        assertEquals(KeyboardChoice.KEYBOARD18, KeyboardChoice.resolve(null, true, true, legacy18 = true))
        assertEquals(KeyboardChoice.PINYIN, KeyboardChoice.resolve(null, false, true, legacyShape = true))
    }
}
