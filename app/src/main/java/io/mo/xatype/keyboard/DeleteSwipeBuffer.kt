package io.mo.xatype.keyboard

/**
 * Text removed by a delete-key swipe-up, kept so a swipe-down can put it back.
 *
 * It belongs to the editor it came from and expires quickly: a restore must never paste one
 * app's text into another app or field, and password fields are never retained at all.
 */
class DeleteSwipeBuffer(private val now: () -> Long = { System.nanoTime() / 1_000_000 },
                        private val lifetimeMs: Long = LIFETIME_MS) {
    private var text: String? = null
    private var owner: String? = null
    private var storedAt = 0L

    @Synchronized
    fun store(text: String, owner: String?, sensitive: Boolean) {
        clear()
        if (text.isEmpty() || owner == null || sensitive) return
        this.text = text
        this.owner = owner
        storedAt = now()
    }

    /** Returns the stored text once, only for the same editor and before it expires. */
    @Synchronized
    fun take(owner: String?): String? {
        val value = text
        val from = this.owner
        val age = now() - storedAt
        clear()
        if (value == null || owner == null || owner != from || age > lifetimeMs) return null
        return value
    }

    @Synchronized
    fun clear() {
        text = null
        owner = null
    }

    companion object {
        const val LIFETIME_MS = 60_000L
    }
}
