package io.mo.xatype.hooks

import io.mo.xatype.util.XposedUtils
import java.util.WeakHashMap

/**
 * Swipe listeners that com.miui.phrase creates inside its clipboard and phrase
 * view holders. They are anonymous classes that javac numbers by position
 * (`ViewHolder$1` in 5.6.9 to 5.7.4), so they are found by the callbacks they
 * declare rather than by that number.
 */
internal object ClipboardSwipeListeners {
    private val HOLDERS = listOf(
        "com.miui.inputmethod.InputMethodClipboardAdapter\$ViewHolder",
        "com.miui.inputmethod.InputMethodPhraseAdapter\$ViewHolder"
    )
    private const val MAX_ANONYMOUS = 16

    val CALLBACKS = setOf("onOpened", "onSlide", "viewSlideRelease")

    // Styling asks again on every popup; probe each phrase class loader once.
    private val found = WeakHashMap<ClassLoader, List<Class<*>>>()

    fun find(classLoader: ClassLoader): List<Class<*>> = synchronized(found) {
        found.getOrPut(classLoader) {
            HOLDERS.flatMap { holder ->
                (1..MAX_ANONYMOUS).mapNotNull { XposedUtils.findClass("$holder\$$it", classLoader) }
                    .filter { type -> type.declaredMethods.any { it.name in CALLBACKS } }
            }
        }
    }
}
