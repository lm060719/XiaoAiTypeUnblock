package io.mo.xatype.hooks

import io.mo.xatype.config.ConfigSchema

/** Read floating mode only inside the native top-bar calculation. */
internal object KeyboardToolbarHeightPolicy {
    private class Sample(val nativeOnly: Boolean, var floating: Boolean? = null)
    private val active = ThreadLocal<Sample>()

    fun isNativeRead() = active.get()?.nativeOnly == true

    fun observe(value: Any?) {
        val sample = active.get() ?: return
        if (sample.floating == null && value is Boolean) sample.floating = value
    }

    fun height(requested: Int, original: () -> Float): Float = read(requested, false, original)

    /** The nested top-bar hook leaves its result native so we can measure the exact difference. */
    fun delta(requested: Int, original: () -> Float): Float = read(requested, true, original)

    private fun read(requested: Int, delta: Boolean, original: () -> Float): Float {
        val previous = active.get()
        val sample = Sample(nativeOnly = delta)
        active.set(sample)
        return try {
            val native = original()
            val adjusted = if (sample.floating == false && native.isFinite() && native > 0f) {
                requested.coerceIn(ConfigSchema.TOOLBAR_HEIGHT_RANGE).toFloat()
            } else native
            if (delta) {
                if (native.isFinite()) adjusted - native else 0f
            } else adjusted
        } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }

    /** Called at the source, before the host caches or animates this height. */
    fun total(native: Float, delta: () -> Float): Float =
        if (native.isFinite() && native > 0f) (native + delta()).coerceAtLeast(0f) else native
}
