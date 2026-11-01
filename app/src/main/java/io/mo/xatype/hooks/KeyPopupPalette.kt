package io.mo.xatype.hooks

import io.mo.xatype.compat.DataClassLabels
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference

/** A popup gets its own palette: its selected item shares the host's enter-key colors. */
internal class KeyPopupPalette(type: Class<*>) {
    private val constructor = type.declaredConstructors.filter { !it.isSynthetic }
        .maxBy { it.parameterCount }.apply { isAccessible = true }
    private val defaults = constructor.parameterTypes.map {
        when (it) {
            java.lang.Long.TYPE -> 0L
            java.lang.Integer.TYPE -> 0
            java.lang.Boolean.TYPE -> false
            java.lang.Float.TYPE -> 0f
            else -> { check(!it.isPrimitive); null }
        }
    }.toTypedArray()
    private val fields = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        .onEach { it.isAccessible = true }
    private val labels = DataClassLabels.resolve(type)
    private fun color(label: String) = fields.single { it.name == labels.getValue(label) }
        .also { check(it.type == java.lang.Long.TYPE) }
    private val background = color("popupSelectorBackgroundColor")
    private val selected = listOf(color("keyBackgroundEnter"), color("keyBackgroundEnterGradientEnd"))
    private val text = listOf(color("keyTextColor"), color("keyTextColorEnter"))
    // Compose restart lambdas capture the replacement palette. Unwrap it on the
    // next call so resetting a color or disabling styling restores the source.
    private data class Copy(val palette: WeakReference<Any>, val source: Any)
    // Identity lookup: nested host colors are mutable and participate in hashCode.
    private val sources = mutableListOf<Copy>()

    init { constructor.newInstance(*defaults) }

    @Synchronized
    fun apply(palette: Any, backgroundColor: Long?, selectedColor: Long?, textColor: Long?): Any {
        sources.removeAll { it.palette.get() == null }
        val source = sources.firstOrNull { it.palette.get() === palette }?.source ?: palette
        if (backgroundColor == null && selectedColor == null && textColor == null) return source
        val copy = constructor.newInstance(*defaults)
        fields.forEach { it.set(copy, it.get(source)) }
        backgroundColor?.let { background.setLong(copy, it) }
        selectedColor?.let { value -> selected.forEach { it.setLong(copy, value) } }
        textColor?.let { value -> text.forEach { it.setLong(copy, value) } }
        sources.add(Copy(WeakReference(copy), source))
        return copy
    }
}
