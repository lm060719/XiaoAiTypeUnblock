package io.mo.xatype.hooks

/** Scope native Compose reads to one margin calculation, including reentrant calls. */
internal object KeyboardBottomSpacingPolicy {
    private class Sample(var floating: Boolean? = null)
    private val active = ThreadLocal<Sample>()

    fun observe(value: Any?) {
        val sample = active.get() ?: return
        if (sample.floating == null && value is Boolean) sample.floating = value
    }

    fun calculate(spacingDp: Int, original: () -> Float): Float {
        val previous = active.get()
        val sample = Sample()
        active.set(sample)
        return try {
            val native = original()
            if (sample.floating == false) spacingDp.coerceIn(0, 100).toFloat() else native
        } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }
}
