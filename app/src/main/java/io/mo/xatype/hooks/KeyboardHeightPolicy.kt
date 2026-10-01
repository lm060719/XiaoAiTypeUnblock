package io.mo.xatype.hooks

/** Only the verified top handle and its dimension conversion may relax a clamp. */
internal object KeyboardHeightPolicy {
    enum class Scope { TOP_DRAG, DIMENSIONS }

    private val scope = ThreadLocal<Scope>()

    fun <T> within(value: Scope, block: () -> T): T {
        val previous = scope.get()
        scope.set(value)
        return try {
            block()
        } finally {
            if (previous == null) scope.remove() else scope.set(previous)
        }
    }

    fun overrideClamp(value: Int, minimum: Int, maximum: Int): Int? {
        val result = when (scope.get()) {
            Scope.TOP_DRAG -> if (minimum == -50) value.coerceAtMost(maximum) else null
            Scope.DIMENSIONS -> if (minimum == -50 && maximum == 50 && value >= 0) value else null
            else -> null
        }
        // Each caller contains exactly one relevant clamp. Stop before any
        // subsequent Compose content can call the same general-purpose helper.
        if (result != null) scope.remove()
        return result
    }
}
