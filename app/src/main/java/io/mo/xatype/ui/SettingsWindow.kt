package io.mo.xatype.ui

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach

/** Draw settings surfaces behind system bars while keeping controls in the safe area. */
internal object SettingsWindow {
    @Suppress("DEPRECATION") // Compatibility with the supported Android 8+ system bar APIs.
    fun apply(
        window: Window,
        root: View,
        topBar: View? = null,
        bottomBar: View? = null,
        insetTop: Boolean = true
    ) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
        )
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) window.navigationBarDividerColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        val dark = (root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }

        val initialRoot = InitialBounds(root)
        val initialTop = topBar?.let(::InitialBounds)
        val initialBottom = bottomBar?.let(::InitialBounds)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val typing = imeBottom > bars.bottom
            view.setPadding(
                initialRoot.left + bars.left,
                initialRoot.top + if (insetTop && topBar == null) bars.top else 0,
                initialRoot.right + bars.right,
                initialRoot.bottom + when {
                    typing -> imeBottom
                    bottomBar == null -> bars.bottom
                    else -> 0
                }
            )
            topBar?.let { initialTop?.inset(it, top = if (insetTop) bars.top else 0) }
            bottomBar?.let { initialBottom?.inset(it, bottom = if (typing) 0 else bars.bottom) }
            // Descendants still need IME visibility, including the floating appearance preview.
            insets
        }
        root.doOnAttach { ViewCompat.requestApplyInsets(it) }
    }

    private fun isToolbar(view: View) = view is androidx.appcompat.widget.Toolbar || view is android.widget.Toolbar

    /** Insets are always relative to the original padding and height, never to the last dispatch. */
    private class InitialBounds(view: View) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        private val height = view.layoutParams?.height
        private val minimumHeight = view.minimumHeight

        fun inset(view: View, top: Int = 0, bottom: Int = 0) {
            view.setPadding(left, this.top + top, right, this.bottom + bottom)
            // Toolbar centres its navigation button in a box of minimumHeight below the top padding;
            // growing that box by the inset pushed the back arrow below the title.
            view.minimumHeight = if (isToolbar(view) && height != null && height > 0) height
                else minimumHeight + top + bottom
            // Fixed-height toolbars and tab bars must grow, otherwise padding squeezes their controls.
            if (height != null && height >= 0) {
                val targetHeight = height + top + bottom
                if (view.layoutParams.height != targetHeight) {
                    view.layoutParams = view.layoutParams.apply { this.height = targetHeight }
                }
            }
        }
    }
}
