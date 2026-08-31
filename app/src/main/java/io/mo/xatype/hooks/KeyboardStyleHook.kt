package io.mo.xatype.hooks

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import io.github.libxposed.api.XposedModule
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.data.LogType
import io.mo.xatype.util.LogBridge
import io.mo.xatype.util.XposedUtils

object KeyboardStyleHook {

    private var cachedBitmap: Bitmap? = null
    private var cachedImageVersion: Long = -1L
    private var lastLoggedTime = 0L

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val imeServiceClass = XposedUtils.findClass("com.mi.ime.MiInputMethodService", classLoader)
        if (imeServiceClass == null) {
            XposedUtils.logError(module, "MiInputMethodService class not found for KeyboardStyleHook", null)
            return
        }

        // 1. Hook onCreateInputView()
        val onCreateInputViewMethod = XposedUtils.findMethodExact(imeServiceClass, "onCreateInputView")
        if (onCreateInputViewMethod != null) {
            module.hook(onCreateInputViewMethod).intercept { chain ->
                val service = chain.thisObject as? android.inputmethodservice.InputMethodService
                if (service != null) {
                    ConfigManager.syncFromProvider(service)
                }
                val resultView = chain.proceed() as? View
                if (resultView != null && ConfigManager.isStyleEnabled() && service != null) {
                    applyStyle(module, service, resultView)
                }
                resultView
            }
            XposedUtils.log(module, "KeyboardStyleHook: Hooked onCreateInputView")
        }

        // 2. Hook onStartInputView(EditorInfo, boolean)
        val onStartInputViewMethod = XposedUtils.findMethodExact(
            imeServiceClass,
            "onStartInputView",
            EditorInfo::class.java,
            Boolean::class.javaPrimitiveType ?: java.lang.Boolean.TYPE
        )
        if (onStartInputViewMethod != null) {
            module.hook(onStartInputViewMethod).intercept { chain ->
                val service = chain.thisObject as? android.inputmethodservice.InputMethodService
                if (service != null) {
                    ConfigManager.syncFromProvider(service)
                }
                val result = chain.proceed()
                if (ConfigManager.isStyleEnabled() && service != null) {
                    val currentImeRootView = XposedUtils.getObjectField(service, "currentImeRootView") as? View
                    if (currentImeRootView != null) {
                        applyStyle(module, service, currentImeRootView)
                    }
                }
                result
            }
            XposedUtils.log(module, "KeyboardStyleHook: Hooked onStartInputView")
        }

        // 3. Hook onWindowShown()
        val onWindowShownMethod = XposedUtils.findMethodExact(imeServiceClass, "onWindowShown")
        if (onWindowShownMethod != null) {
            module.hook(onWindowShownMethod).intercept { chain ->
                val service = chain.thisObject as? android.inputmethodservice.InputMethodService
                if (service != null) {
                    ConfigManager.syncFromProvider(service)
                }
                val result = chain.proceed()
                if (ConfigManager.isStyleEnabled() && service != null) {
                    val currentImeRootView = XposedUtils.getObjectField(service, "currentImeRootView") as? View
                    if (currentImeRootView != null) {
                        applyStyle(module, service, currentImeRootView)
                    }
                }
                result
            }
            XposedUtils.log(module, "KeyboardStyleHook: Hooked onWindowShown")
        }

        // 4. Hook Compose keyboard background color in na.d.h(): return 0L (Color.Unspecified) to eliminate Compose solid grey rectangle
        try {
            val naDClass = XposedUtils.findClass("na.d", classLoader)
            if (naDClass != null) {
                val hMethod = naDClass.declaredMethods.find { it.name == "h" && it.parameterTypes.isEmpty() }
                if (hMethod != null) {
                    val unspecifiedColor = 0L // Color.Unspecified in Compose
                    module.hook(hMethod).intercept { chain ->
                        if (ConfigManager.isStyleEnabled() && (ConfigManager.getOpacity() < 100 || ConfigManager.getBgType() != 0 || ConfigManager.getCornerRadius() > 0)) {
                            unspecifiedColor
                        } else {
                            chain.proceed()
                        }
                    }
                    XposedUtils.log(module, "KeyboardStyleHook: Hooked na.d.h (Compose background color bypass)")
                }
            }
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Error hooking na.d.h", t)
        }

        // 5. Hook Compose keyboard container corner radius: na.m.F0(s0.p)
        try {
            val naMClass = XposedUtils.findClass("na.m", classLoader)
            if (naMClass != null) {
                val f0Method = naMClass.declaredMethods.find { it.name == "F0" }
                if (f0Method != null) {
                    module.hook(f0Method).intercept { chain ->
                        if (ConfigManager.isStyleEnabled()) {
                            ConfigManager.getCornerRadius().toFloat()
                        } else {
                            chain.proceed()
                        }
                    }
                    XposedUtils.log(module, "KeyboardStyleHook: Hooked na.m.F0 (Compose corner radius)")
                }
            }
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Error hooking na.m.F0", t)
        }

        // 6. Hook HyperMaterial OutlineProvider: bb.t.getOutline(View, Outline)
        try {
            val bbTClass = XposedUtils.findClass("bb.t", classLoader)
            if (bbTClass != null) {
                val getOutlineMethod = XposedUtils.findMethodExact(bbTClass, "getOutline", View::class.java, Outline::class.java)
                if (getOutlineMethod != null) {
                    module.hook(getOutlineMethod).intercept { chain ->
                        if (ConfigManager.isStyleEnabled()) {
                            val view = chain.getArg(0) as? View
                            val outline = chain.getArg(1) as? Outline
                            if (view != null && outline != null && view.width > 0 && view.height > 0) {
                                val radiusDp = ConfigManager.getCornerRadius()
                                val radiusPx = radiusDp * view.resources.displayMetrics.density
                                val cornerMode = ConfigManager.getCornerMode()
                                if (radiusPx <= 0f) {
                                    outline.setRect(0, 0, view.width, view.height)
                                } else if (cornerMode == 0) {
                                    outline.setRoundRect(0, 0, view.width, view.height + radiusPx.toInt(), radiusPx)
                                } else {
                                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                                }
                                return@intercept null
                            }
                        }
                        chain.proceed()
                    }
                    XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.t.getOutline")
                }
            }
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Error hooking bb.t.getOutline", t)
        }

        // 7. Hook HyperMaterialHelper.g(boolean, FrameLayout, int) to sync material view transparency, background, & margins
        try {
            val bbUClass = XposedUtils.findClass("bb.u", classLoader)
            if (bbUClass != null) {
                val gMethod = bbUClass.declaredMethods.find { it.name == "g" }
                if (gMethod != null) {
                    module.hook(gMethod).intercept { chain ->
                        val res = chain.proceed()
                        if (ConfigManager.isStyleEnabled()) {
                            val helper = chain.thisObject
                            val service = XposedUtils.getObjectField(helper, "f3495a") as? android.inputmethodservice.InputMethodService
                            if (service != null) {
                                updateHyperMaterialViews(service, helper)
                            }
                        }
                        res
                    }
                    XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.u.g")
                }
            }
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Error hooking bb.u.g", t)
        }

        // 8. Hook bb.g1.S (sets navigation bar color) & bb.g1.s (customizeBottomViewColor)
        try {
            val bbG1Class = XposedUtils.findClass("bb.g1", classLoader)
            if (bbG1Class != null) {
                val sMethod = bbG1Class.declaredMethods.find { it.name == "S" }
                if (sMethod != null) {
                    module.hook(sMethod).intercept { chain ->
                        val res = chain.proceed()
                        if (ConfigManager.isStyleEnabled()) {
                            val g1Obj = chain.thisObject
                            val bField = XposedUtils.getObjectField(g1Obj, "b")
                            if (bField != null) {
                                val bInner = XposedUtils.getObjectField(bField, "b") as? android.content.Context
                                if (bInner is android.inputmethodservice.InputMethodService) {
                                    val window = bInner.window?.window
                                    window?.setNavigationBarColor(Color.TRANSPARENT)
                                    window?.setNavigationBarContrastEnforced(false)
                                }
                            }
                        }
                        res
                    }
                    XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.g1.S")
                }

                val staticSMethod = bbG1Class.declaredMethods.find { it.name == "s" }
                if (staticSMethod != null) {
                    module.hook(staticSMethod).intercept { chain ->
                        if (ConfigManager.isStyleEnabled()) {
                            // Suppress drawing solid bottom view color
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                    XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.g1.s")
                }
            }
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Error hooking bb.g1", t)
        }
    }

    private fun getOrLoadBitmap(service: android.content.Context): Bitmap? {
        val currentVersion = ConfigManager.getBgImageVersion()
        if (cachedBitmap != null && cachedImageVersion == currentVersion && !cachedBitmap!!.isRecycled) {
            return cachedBitmap
        }

        try {
            val uri = Uri.parse("content://io.mo.xatype.logprovider/bg_image.png")
            service.contentResolver.openInputStream(uri)?.use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                if (bmp != null) {
                    cachedBitmap?.recycle()
                    cachedBitmap = bmp
                    cachedImageVersion = currentVersion
                    return bmp
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun updateHyperMaterialViews(service: android.inputmethodservice.InputMethodService, helper: Any?) {
        if (helper == null) return
        val f3500h = XposedUtils.getObjectField(helper, "f3500h") as? View ?: return
        val f3501i = XposedUtils.getObjectField(helper, "f3501i") as? View

        if (!ConfigManager.isStyleEnabled()) return

        val bgType = ConfigManager.getBgType()
        val bgColorStr = ConfigManager.getBgColor()
        val opacity = ConfigManager.getOpacity()
        val alpha = (opacity.coerceIn(10, 100)) / 100.0f
        val alphaInt = (alpha * 255).toInt().coerceIn(0, 255)
        val cornerRadiusDp = ConfigManager.getCornerRadius()
        val cornerMode = ConfigManager.getCornerMode()
        val hMarginDp = ConfigManager.getMarginHorizontal()
        val bMarginDp = ConfigManager.getMarginBottom()

        val isDark = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val density = f3500h.resources.displayMetrics.density
        val radiusPx = cornerRadiusDp * density
        val hMarginPx = (hMarginDp * density).toInt()
        val bMarginPx = (bMarginDp * density).toInt()

        f3500h.post {
            try {
                // 1. Transparency on keyboard background view
                f3500h.alpha = alpha

                // 2. Background Customization on f3500h (the actual keyboard card at bottom)
                when (bgType) {
                    1 -> { // Solid / HEX Color
                        val parsedColor = try {
                            Color.parseColor(bgColorStr)
                        } catch (_: Throwable) {
                            Color.parseColor("#1E1E2E")
                        }
                        val r = Color.red(parsedColor)
                        val g = Color.green(parsedColor)
                        val b = Color.blue(parsedColor)
                        f3500h.background = ColorDrawable(Color.argb(alphaInt, r, g, b))
                        f3501i?.visibility = View.GONE
                        f3500h.visibility = View.VISIBLE
                    }
                    2 -> { // Custom Image
                        val bmp = getOrLoadBitmap(service)
                        if (bmp != null && !bmp.isRecycled) {
                            val drawable = BitmapDrawable(service.resources, bmp)
                            drawable.alpha = alphaInt
                            f3500h.background = drawable
                        }
                        f3501i?.visibility = View.GONE
                        f3500h.visibility = View.VISIBLE
                    }
                    else -> { // Default material
                        val baseR = if (isDark) 35 else 245
                        val baseG = if (isDark) 35 else 245
                        val baseB = if (isDark) 35 else 245
                        f3500h.background = ColorDrawable(Color.argb(alphaInt, baseR, baseG, baseB))
                        f3501i?.alpha = alpha
                        f3500h.visibility = View.VISIBLE
                    }
                }

                // 3. Layout Margins on f3500h (aligned to bottom)
                val lp = f3500h.layoutParams as? FrameLayout.LayoutParams
                if (lp != null) {
                    lp.gravity = Gravity.BOTTOM
                    lp.setMargins(hMarginPx, 0, hMarginPx, bMarginPx)
                    f3500h.layoutParams = lp
                }

                // 4. Rounded Corners & Clipping
                if (radiusPx > 0f) {
                    f3500h.clipToOutline = true
                    f3500h.outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, outline: Outline) {
                            val w = v.width
                            val h = v.height
                            if (w <= 0 || h <= 0) return
                            if (cornerMode == 0) {
                                outline.setRoundRect(0, 0, w, h + radiusPx.toInt(), radiusPx)
                            } else {
                                outline.setRoundRect(0, 0, w, h, radiusPx)
                            }
                        }
                    }
                    f3500h.elevation = if (hMarginPx > 0 || bMarginPx > 0 || radiusPx > 0f) 16f else 0f
                    f3500h.invalidateOutline()
                } else {
                    f3500h.clipToOutline = false
                    f3500h.outlineProvider = ViewOutlineProvider.BACKGROUND
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun applyStyle(module: XposedModule, service: android.inputmethodservice.InputMethodService, rootView: View) {
        if (!ConfigManager.isStyleEnabled()) return

        val cornerRadiusDp = ConfigManager.getCornerRadius()
        val cornerMode = ConfigManager.getCornerMode() // 0: TOP_ONLY, 1: ALL_CORNERS
        val opacity = ConfigManager.getOpacity() // 10 to 100
        val bgType = ConfigManager.getBgType() // 0: DEFAULT, 1: COLOR, 2: IMAGE
        val bgColorStr = ConfigManager.getBgColor()
        val marginTopDp = ConfigManager.getMarginTop()
        val marginBottomDp = ConfigManager.getMarginBottom()
        val marginHorizontalDp = ConfigManager.getMarginHorizontal()

        val density = service.resources.displayMetrics.density
        val topMarginPx = (marginTopDp * density).toInt()
        val bottomMarginPx = (marginBottomDp * density).toInt()
        val horizontalMarginPx = (marginHorizontalDp * density).toInt()
        val alpha = (opacity.coerceIn(10, 100)) / 100.0f

        rootView.post {
            try {
                // 1. Transparent window & navigation bar
                val window = service.window?.window
                if (window != null) {
                    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    window.setNavigationBarColor(Color.TRANSPARENT)
                    window.setNavigationBarContrastEnforced(false)
                    window.setDimAmount(0f)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    val decor = window.decorView as? ViewGroup
                    decor?.setBackgroundColor(Color.TRANSPARENT)
                }

                // 2. Clear background on full-screen container views so nothing bleeds to top
                rootView.background = null
                rootView.setPadding(horizontalMarginPx, topMarginPx, horizontalMarginPx, bottomMarginPx)

                val targetView: View = if (rootView is ViewGroup && rootView.childCount > 0) {
                    rootView.getChildAt(0)
                } else {
                    rootView
                }
                targetView.background = null

                // If transparency is enabled, allow keys to be slightly translucent so the see-through background is apparent
                if (opacity < 100) {
                    targetView.alpha = (0.4f + 0.6f * alpha).coerceIn(0.4f, 1.0f)
                } else {
                    targetView.alpha = 1.0f
                }

                // 3. Update HyperMaterialHelper's f3500h view which is the true keyboard bottom card
                val helper = XposedUtils.getObjectField(service, "hyperMaterialHelper")
                updateHyperMaterialViews(service, helper)

                // Log event occasionally (throttle to once per 5 seconds to avoid flooding)
                val now = System.currentTimeMillis()
                if (now - lastLoggedTime > 5000) {
                    lastLoggedTime = now
                    val bgDesc = when (bgType) {
                        1 -> "纯色($bgColorStr)"
                        2 -> "自定义图片"
                        else -> "系统默认"
                    }
                    val cornerDesc = if (cornerMode == 0) "顶部圆角(${cornerRadiusDp}dp)" else "四角全圆角(${cornerRadiusDp}dp)"
                    val marginDesc = "上${marginTopDp}dp / 下${marginBottomDp}dp / 左右${marginHorizontalDp}dp"
                    LogBridge.record(
                        LogType.STYLE,
                        "应用键盘个性化样式",
                        "圆角: $cornerDesc | 透明度: ${opacity}% | 边距: $marginDesc | 背景: $bgDesc"
                    )
                }
            } catch (t: Throwable) {
                XposedUtils.logError(module, "Error applying custom style to keyboard view", t)
            }
        }
    }
}
