package io.mo.xatype.keyboard

import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiField

/** Reflection only; also exercised against host-shaped fixtures on the desktop. */
class KeyboardPickerBridge(loader: ClassLoader, private val abi: KeyboardAbi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_PICKER)) {
    private val item = abi.type("z9.g6")
    private val constructor = item.getConstructor(Int::class.javaPrimitiveType, String::class.java, String::class.java)
    private val id = item.abiField(abi, "a").apply { isAccessible = true }
    private val icon = item.abiField(abi, "c").apply { isAccessible = true }
    private val nativeIds = NATIVE_IDS

    companion object {
        val NATIVE_IDS = setOf("qwerty_chinese", "t9_chinese", "handwriting_chinese", "stroke_chinese")

        /**
         * The modes the picker may show. The host lists its Chinese modes only once its native core
         * has loaded; when a module engine is the first one created in the process, that core never
         * loads and the picker would filter every row away. Choosing a native mode loads it as usual.
         */
        fun modes(host: Set<*>, selected: KeyboardChoice, showShape: Boolean, show18: Boolean, show14: Boolean): Set<Any?> =
            host.toMutableSet<Any?>().apply {
                if (selected != KeyboardChoice.PINYIN && KeyboardChoice.PINYIN.id !in host) addAll(NATIVE_IDS)
                if (showShape) add(KeyboardChoice.SHAPE.id)
                if (show18) add(KeyboardChoice.KEYBOARD18.id)
                if (show14) add(KeyboardChoice.KEYBOARD14.id)
            }
    }

    fun extend(values: Array<*>, showShape: Boolean, show18: Boolean, show14: Boolean = false): List<Any>? {
        if (values.isEmpty() || values.size > nativeIds.size || values.any { !item.isInstance(it) }) return null
        val ids = values.map { id.get(it) }
        if (ids.distinct().size != values.size || ids.any { it !in nativeIds } || KeyboardChoice.PINYIN.id !in ids) return null
        val qwerty = values.single { id.get(it) == KeyboardChoice.PINYIN.id }
        return values.filterNotNull().toMutableList().apply {
            if (showShape) add(constructor.newInstance(icon.getInt(qwerty), KeyboardChoice.SHAPE.id, "虎码/五笔"))
            if (show18) add(constructor.newInstance(icon.getInt(qwerty), KeyboardChoice.KEYBOARD18.id, "18键"))
            if (show14) add(constructor.newInstance(icon.getInt(qwerty), KeyboardChoice.KEYBOARD14.id, "14键"))
        }
    }
}
