package io.mo.xatype.hooks

import java.lang.ref.WeakReference

/** Main-thread ownership of docked glass between hide and the next lifecycle change.
 * Unlike a call-scoped flag, this also covers the host's delayed teardown callback.
 */
internal class HiddenGlassRetention {
    private var helper: WeakReference<Any>? = null
    private var material: WeakReference<Any>? = null

    fun retain(helper: Any, material: Any) {
        this.helper = WeakReference(helper)
        this.material = WeakReference(material)
    }

    fun release() {
        helper = null
        material = null
    }

    fun shouldPreserve(helper: Any, material: Any?, enabled: Boolean,
                       attached: Boolean, owned: Boolean): Boolean {
        // A clipboard/floating helper must not consume the main window's guard.
        if (this.helper?.get() !== helper) return false
        if (!enabled || material == null || this.material?.get() !== material || !attached || !owned) {
            release()
            return false
        }
        return true
    }
}
