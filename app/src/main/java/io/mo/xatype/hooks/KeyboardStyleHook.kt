package io.mo.xatype.hooks

import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.data.LogType
import io.mo.xatype.util.LogBridge
import io.mo.xatype.util.XposedUtils
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

object KeyboardStyleHook {

    private var cachedBitmap: Bitmap? = null
    private var cachedImageVersion: Long = -1L
    private var lastLoggedTime = 0L
    private val glassTintViews = WeakHashMap<Any, View>()
    // na.d is a data-style class whose hashCode includes these mutable fields,
    // so identity keys are required to keep restoration reliable after patching.
    private val originalAppsPanelColors = IdentityHashMap<Any, Map<String, Long>>()
    private val materialRefreshGenerations = WeakHashMap<View, Int>()
    private val clipboardAdapterHooks = ConcurrentHashMap.newKeySet<Class<*>>()
    private val clipboardAppliedBackgrounds = WeakHashMap<View, AppliedClipboardBackground>()
    private var isBottomManagerHooked = false
    private val bottomBarResNames = setOf("input_bottom_view", "miui_bottom_area", "bottom_view")
    private val materialBgViews = java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val imeServiceClass = XposedUtils.findClass("com.mi.ime.MiInputMethodService", classLoader)
        if (imeServiceClass == null) {
            XposedUtils.logError(module, "MiInputMethodService class not found for KeyboardStyleHook", null)
            return
        }

        installClipboardPopupHook(module)

        // 0. Hook onCreate()
        val onCreateMethod = XposedUtils.findMethodExact(imeServiceClass, "onCreate")
        if (onCreateMethod != null) {
            module.hook(onCreateMethod).intercept { chain ->
                val res = chain.proceed()
                val service = chain.thisObject as? android.inputmethodservice.InputMethodService
                if (service != null) {
                    ConfigManager.syncFromProvider(service)
                    if (ConfigManager.isStyleEnabled()) {
                        enforceWindowAndBottomBarTransparency(service)
                    }
                }
                res
            }
            XposedUtils.log(module, "KeyboardStyleHook: Hooked onCreate")
        }

        // 0.1 Hook showWindow(boolean)
        val showWindowMethod = XposedUtils.findMethodExact(
            android.inputmethodservice.InputMethodService::class.java,
            "showWindow",
            Boolean::class.javaPrimitiveType ?: java.lang.Boolean.TYPE
        )
        if (showWindowMethod != null) {
            module.hook(showWindowMethod).intercept { chain ->
                val service = chain.thisObject as? android.inputmethodservice.InputMethodService
                if (service != null) {
                    ConfigManager.syncFromProvider(service)
                    if (ConfigManager.isStyleEnabled()) {
                        enforceWindowAndBottomBarTransparency(service)
                    }
                }
                chain.proceed()
            }
            XposedUtils.log(module, "KeyboardStyleHook: Hooked showWindow")
        }

        // 0.2 Hook onShowInputRequested(int, boolean)
        val onShowInputRequestedMethod = XposedUtils.findMethodExact(
            android.inputmethodservice.InputMethodService::class.java,
            "onShowInputRequested",
            Int::class.javaPrimitiveType ?: java.lang.Integer.TYPE,
            Boolean::class.javaPrimitiveType ?: java.lang.Boolean.TYPE
        )
        if (onShowInputRequestedMethod != null) {
            module.hook(onShowInputRequestedMethod).intercept { chain ->
                val service = chain.thisObject as? android.inputmethodservice.InputMethodService
                if (service != null) {
                    ConfigManager.syncFromProvider(service)
                    if (ConfigManager.isStyleEnabled()) {
                        enforceWindowAndBottomBarTransparency(service)
                    }
                }
                chain.proceed()
            }
            XposedUtils.log(module, "KeyboardStyleHook: Hooked onShowInputRequested")
        }

        // 0.3 Universal View Background and Elevation Interceptors
        val setBackgroundColorMethod = XposedUtils.findMethodExact(
            View::class.java,
            "setBackgroundColor",
            Int::class.javaPrimitiveType ?: java.lang.Integer.TYPE
        )
        if (setBackgroundColorMethod != null) {
            module.hook(setBackgroundColorMethod).intercept { chain ->
                if (ConfigManager.isStyleEnabled()) {
                    val view = chain.thisObject as? View
                    if (view != null && (isBottomBarView(view) || (ConfigManager.getBgType() == 0 && materialBgViews.contains(view)))) {
                        return@intercept chain.proceed(arrayOf(Color.TRANSPARENT))
                    }
                }
                chain.proceed()
            }
            XposedUtils.log(module, "KeyboardStyleHook: Hooked View.setBackgroundColor for bottom bar & material view")
        }

        val setElevationMethod = XposedUtils.findMethodExact(
            View::class.java,
            "setElevation",
            Float::class.javaPrimitiveType ?: java.lang.Float.TYPE
        )
        if (setElevationMethod != null) {
            module.hook(setElevationMethod).intercept { chain ->
                if (ConfigManager.isStyleEnabled()) {
                    val view = chain.thisObject as? View
                    if (view != null && (isBottomBarView(view) || materialBgViews.contains(view))) {
                        return@intercept chain.proceed(arrayOf(0.0f))
                    }
                }
                chain.proceed()
            }
            XposedUtils.log(module, "KeyboardStyleHook: Hooked View.setElevation for bottom bar & material view")
        }

        val setTranslationZMethod = XposedUtils.findMethodExact(
            View::class.java,
            "setTranslationZ",
            Float::class.javaPrimitiveType ?: java.lang.Float.TYPE
        )
        if (setTranslationZMethod != null) {
            module.hook(setTranslationZMethod).intercept { chain ->
                if (ConfigManager.isStyleEnabled()) {
                    val view = chain.thisObject as? View
                    if (view != null && (isBottomBarView(view) || materialBgViews.contains(view))) {
                        return@intercept chain.proceed(arrayOf(0.0f))
                    }
                }
                chain.proceed()
            }
            XposedUtils.log(module, "KeyboardStyleHook: Hooked View.setTranslationZ for bottom bar & material view")
        }

        val setBackgroundMethod = XposedUtils.findMethodExact(
            View::class.java,
            "setBackground",
            Drawable::class.java
        )
        if (setBackgroundMethod != null) {
            module.hook(setBackgroundMethod).intercept { chain ->
                if (ConfigManager.isStyleEnabled()) {
                    val view = chain.thisObject as? View
                    if (view != null && isBottomBarView(view)) {
                        return@intercept chain.proceed(arrayOf<Any?>(null))
                    }
                }
                chain.proceed()
            }
            XposedUtils.log(module, "KeyboardStyleHook: Hooked View.setBackground for bottom bar")
        }

        // 0.4 PhoneWindow Navigation Bar & Scrim Interceptors
        try {
            val phoneWindowClass = Class.forName("com.android.internal.policy.PhoneWindow")
            val setNavBarColor = phoneWindowClass.declaredMethods.find {
                it.name == "setNavigationBarColor" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == (Int::class.javaPrimitiveType ?: java.lang.Integer.TYPE)
            }
            if (setNavBarColor != null) {
                module.hook(setNavBarColor).intercept { chain ->
                    if (ConfigManager.isStyleEnabled()) {
                        val window = chain.thisObject as? Window
                        val attrs = window?.attributes
                        if (attrs == null || attrs.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD ||
                            attrs.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG || attrs.type == 0) {
                            return@intercept chain.proceed(arrayOf(Color.TRANSPARENT))
                        }
                    }
                    chain.proceed()
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked PhoneWindow.setNavigationBarColor")
            }

            val setContrastEnforced = phoneWindowClass.declaredMethods.find {
                it.name == "setNavigationBarContrastEnforced" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == (Boolean::class.javaPrimitiveType ?: java.lang.Boolean.TYPE)
            }
            if (setContrastEnforced != null) {
                module.hook(setContrastEnforced).intercept { chain ->
                    if (ConfigManager.isStyleEnabled()) {
                        val window = chain.thisObject as? Window
                        val attrs = window?.attributes
                        if (attrs == null || attrs.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD ||
                            attrs.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG || attrs.type == 0) {
                            return@intercept chain.proceed(arrayOf(false))
                        }
                    }
                    chain.proceed()
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked PhoneWindow.setNavigationBarContrastEnforced")
            }
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Error hooking PhoneWindow navigation bar methods", t)
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

        // 4. Hook Compose keyboard container corner radius: na.m.F0(s0.p)
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

                // Keep Compose foreground tokens readable after replacing the
                // keyboard surface. A custom solid color may have the opposite
                // luminance from the active system theme.
                val colorsMethod = naMClass.declaredMethods.find {
                    it.name == "w" && it.parameterTypes.size == 1 && it.returnType.name == "na.d"
                }
                if (colorsMethod != null) {
                    module.hook(colorsMethod).intercept { chain ->
                        val colors = chain.proceed()
                        if (colors != null) {
                            updateKeyboardContrast(
                                colors,
                                ConfigManager.isStyleEnabled(),
                                ConfigManager.getBgType(),
                                ConfigManager.getBgColor(),
                                ConfigManager.getTextColor(),
                                ConfigManager.getFunctionKeycapColor(),
                                ConfigManager.getMenuCardColor(),
                                ConfigManager.getLetterKeycapColor()
                            )
                        }
                        colors
                    }
                    XposedUtils.log(module, "KeyboardStyleHook: Hooked na.m.w (Keyboard contrast)")
                }
            }

            // In 0.2.599.905736fd the main QWERTY key renderer (aa.s6.a)
            // obtains the normal letter/number keycap color through na.d.d().
            // Hooking this accessor is deliberately narrower than mutating the
            // backing `c` field, which is also reused by cards and voice panels.
            val colorsClass = XposedUtils.findClass("na.d", classLoader)
            val normalKeycapMethod = colorsClass?.declaredMethods?.find {
                it.name == "d" &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType == Long::class.javaPrimitiveType
            }
            if (normalKeycapMethod != null) {
                module.hook(normalKeycapMethod).intercept { chain ->
                    val customColor = parseOptionalColor(ConfigManager.getLetterKeycapColor())
                    if (ConfigManager.isStyleEnabled() && customColor != null) {
                        composeColor(customColor)
                    } else {
                        chain.proceed()
                    }
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked na.d.d (Letter keycap color)")
            } else {
                XposedUtils.logError(module, "na.d.d() not found for letter keycap color", null)
            }
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Error hooking na.m.F0", t)
            XposedUtils.logError(module, "Error hooking Compose style tokens", t)
        }

        // 6. Hook HyperMaterial Helper support & package whitelist bypass
        val bbUClass = XposedUtils.findClass("bb.u", classLoader)
        if (bbUClass != null) {
            // Hook bb.u.h(): Unblock HyperMaterial support check
            val hMethod = bbUClass.declaredMethods.find { it.name == "h" }
            if (hMethod != null) {
                module.hook(hMethod).intercept { chain ->
                    if (ConfigManager.isStyleEnabled() && ConfigManager.getBgType() == 0) {
                        true
                    } else {
                        chain.proceed()
                    }
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.u.h (Global HyperMaterial unblock)")
            }

            // Hook bb.u.k(): Package whitelist update hook
            val kMethod = bbUClass.declaredMethods.find { it.name == "k" }
            if (kMethod != null) {
                module.hook(kMethod).intercept { chain ->
                    if (ConfigManager.isStyleEnabled() && ConfigManager.getBgType() == 0) {
                    // bb.u.k() reruns whenever the editor package changes. The
                    // material views are also the host for solid/image drawables,
                    // so keep them alive for every enabled custom background,
                    // not only for dynamic glass.
                    if (ConfigManager.isStyleEnabled()) {
                        forceCurrentPackageIntoMaterialWhitelist(chain.thisObject)
                    }
                    val res = chain.proceed()
                    if (ConfigManager.isStyleEnabled() && ConfigManager.getBgType() == 0) {
                    if (ConfigManager.isStyleEnabled()) {
                        val helper = chain.thisObject
                        val f3497d = XposedUtils.getObjectField(helper, "d")
                        if (f3497d != null) {
                            try {
                                val setValueMethod = f3497d.javaClass.methods.find { it.name == "setValue" }
                                setValueMethod?.invoke(f3497d, true)
                            } catch (_: Throwable) {}
                        }
                    }
                    res
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.u.k (Whitelist bypass)")
            }

            // Hook bb.u.e(boolean): Custom dynamic glass blur radius & parameters
            val eMethod = bbUClass.declaredMethods.find { it.name == "e" && it.parameterTypes.size == 1 }
            if (eMethod != null) {
                module.hook(eMethod).intercept { chain ->
                    val result = chain.proceed()
                    if (result != null && ConfigManager.isStyleEnabled() && ConfigManager.getBgType() == 0) {
                        try {
                            XposedUtils.setObjectField(
                                result,
                                "p",
                                ConfigManager.getBlurRadius().coerceIn(0, 400)
                            )
                            val isDark = chain.getArg(0) as? Boolean ?: false
                            val blurRadiusDp = ConfigManager.getBlurRadius().coerceIn(0, 400)
                            val opacity = ConfigManager.getOpacity()
                            val strength = (opacity.coerceIn(0, 100) / 100.0f).coerceIn(0f, 1f)
                            val blendColors = if (!isDark) {
                                intArrayOf(
                                    Color.argb((110 * strength).toInt(), 255, 255, 255),
                                    Color.argb((70 * strength).toInt(), 246, 249, 255),
                                    Color.argb((40 * strength).toInt(), 187, 205, 232)
                                )
                            } else {
                                intArrayOf(
                                    Color.argb((100 * strength).toInt(), 27, 30, 38),
                                    Color.argb((55 * strength).toInt(), 255, 255, 255),
                                    Color.argb((35 * strength).toInt(), 153, 178, 216)
                                )
                            }
                            XposedUtils.setObjectField(result, "e", blendColors)
                            XposedUtils.setObjectField(result, "p", blurRadiusDp)
                        } catch (_: Throwable) {}
                    }
                    result
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.u.e (Dynamic glass blur tuning)")
            }

            // bb.u.a() calls c(h) again when the keyboard interaction state
            // changes. Let the native method finish its required view setup,
            // then restore our custom drawable after its delayed background
            // cleanup has also run.
            val cMethod = bbUClass.declaredMethods.find {
                it.name == "c" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == View::class.java &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            if (cMethod != null) {
                module.hook(cMethod).intercept { chain ->
                    val helper = chain.thisObject
                    if (ConfigManager.isStyleEnabled() && ConfigManager.getBgType() == 0) {
                        try {
                            updateCachedGlassTokens(helper, ConfigManager.getBlurRadius(), ConfigManager.getOpacity())
                        } catch (_: Throwable) {}
                    }
                    val result = chain.proceed()
                    if (ConfigManager.isStyleEnabled()) {
                        val materialView = chain.getArg(0) as? View
                        if (materialView != null) {
                            materialBgViews.add(materialView)
                            if (ConfigManager.getBgType() == 0) {
                                materialView.setBackgroundColor(Color.TRANSPARENT)
                                materialView.elevation = 0f
                                materialView.translationZ = 0f
                            } else {
                                materialView.post {
                                    restoreCustomBackground(helper, materialView)
                                }
                                materialView.postDelayed({
                                    restoreCustomBackground(helper, materialView)
                                }, 48L)
                            }
                        }
                    }
                    result
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.u.c (Restore custom background)")
            }

            // Hook bb.u.d(): Prevent adding/rendering f3501i (shadow view) when opacity is 0 or solid/image bg
            val dMethod = bbUClass.declaredMethods.find { it.name == "d" }
            if (dMethod != null) {
                module.hook(dMethod).intercept { chain ->
                    if (ConfigManager.isStyleEnabled() && (ConfigManager.getOpacity() == 0 || ConfigManager.getBgType() != 0)) {
                        val helper = chain.thisObject
                        val f3501i = XposedUtils.getObjectField(helper, "i") as? View
                        f3501i?.visibility = View.GONE
                        f3501i?.setRenderEffect(null)
                        f3501i?.background = null
                        f3501i?.elevation = 0f
                        f3501i?.translationZ = 0f
                        return@intercept null
                    }
                    chain.proceed()
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.u.d (Suppress shadow view)")
            }

            // Hook bb.u.b(View): Synchronize RuntimeShader uRadii corner radius on f3501i
            val bMethod = bbUClass.declaredMethods.find { it.name == "b" && it.parameterTypes.size == 1 && it.parameterTypes[0] == View::class.java }
            if (bMethod != null) {
                module.hook(bMethod).intercept { chain ->
                    if (ConfigManager.isStyleEnabled()) {
                        val view = chain.getArg(0) as? View
                        val opacity = ConfigManager.getOpacity()
                        if (opacity == 0 || ConfigManager.getBgType() != 0) {
                            view?.visibility = View.GONE
                            view?.setRenderEffect(null)
                            view?.background = null
                            view?.elevation = 0f
                            view?.translationZ = 0f
                            return@intercept null
                        }
                    }
                    val res = chain.proceed()
                    if (ConfigManager.isStyleEnabled()) {
                        val helper = chain.thisObject
                        val view = chain.getArg(0) as? View
                        val runtimeShader = XposedUtils.getObjectField(helper, "s") as? android.graphics.RuntimeShader
                        if (view != null && runtimeShader != null) {
                            val radiusDp = ConfigManager.getCornerRadius()
                            val density = view.resources.displayMetrics.density
                            val radiusPx = radiusDp * density
                            runtimeShader.setFloatUniform("uRadii", radiusPx, 0.0f, 0.0f, radiusPx)
                            val opacity = ConfigManager.getOpacity()
                            val shadowScale = (opacity.coerceIn(0, 100) / 100.0f)
                            val isDark = (XposedUtils.getObjectField(helper, "k") as? Boolean) == true
                            val currentAlpha = if (isDark) 0.12f else 0.03f
                            runtimeShader.setFloatUniform("uShadowAlpha", currentAlpha * shadowScale)
                            runtimeShader.setFloatUniform("uStrokeAlphaBottom", 0.0f)
                            try {
                                view.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(runtimeShader, "uInputContent"))
                            } catch (_: Throwable) {}
                        }
                    }
                    res
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.u.b (RuntimeShader uRadii sync)")
            }

            // Hook bb.u.g(boolean, FrameLayout, int): Update views on attach
            val gMethod = bbUClass.declaredMethods.find { it.name == "g" }
            if (gMethod != null) {
                module.hook(gMethod).intercept { chain ->
                    if (ConfigManager.isStyleEnabled()) {
                        val helper = chain.thisObject
                        try {
                            val service = XposedUtils.getObjectField(helper, "a") as? android.inputmethodservice.InputMethodService
                            if (service != null) {
                                ConfigManager.syncFromProvider(service)
                            }
                            updateCachedGlassTokens(helper, ConfigManager.getBlurRadius(), ConfigManager.getOpacity())
                        } catch (_: Throwable) {}
                    }
                    val res = chain.proceed()
                    if (ConfigManager.isStyleEnabled()) {
                        val helper = chain.thisObject
                        val f3500h = XposedUtils.getObjectField(helper, "h") as? View
                        if (f3500h != null) {
                            materialBgViews.add(f3500h)
                            f3500h.background = null
                            f3500h.setBackgroundColor(Color.TRANSPARENT)
                            f3500h.elevation = 0f
                            f3500h.translationZ = 0f
                            f3500h.outlineProvider = ViewOutlineProvider.BACKGROUND
                            f3500h.clipToOutline = false
                        }
                        val f3501i = XposedUtils.getObjectField(helper, "i") as? View
                        if (f3501i != null) {
                            f3501i.elevation = 0f
                            f3501i.translationZ = 0f
                            if (ConfigManager.getBgType() != 0 || ConfigManager.getOpacity() == 0) {
                                f3501i.visibility = View.GONE
                                f3501i.setRenderEffect(null)
                                f3501i.background = null
                            }
                        }
                        val service = XposedUtils.getObjectField(helper, "a") as? android.inputmethodservice.InputMethodService
                        if (service != null) {
                            ConfigManager.syncFromProvider(service)
                            updateHyperMaterialViews(module, service, helper)
                            enforceWindowAndBottomBarTransparency(service)
                            scheduleHyperMaterialRefresh(module, service, helper)
                        }
                    }
                    res
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.u.g")
            }

            // In non-floating mode bb.u.g creates the material view with height=0.
            // bb.u.o later posts the real height after InputMethodService computes
            // contentTopInsets. Applying material before that layout produces the
            // opaque white cold-start frame seen after force-stopping the IME.
            val oMethod = bbUClass.declaredMethods.find {
                it.name == "o" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes.all { type -> type == Int::class.javaPrimitiveType }
            }
            if (oMethod != null) {
                module.hook(oMethod).intercept { chain ->
                    val res = chain.proceed()
                    if (ConfigManager.isStyleEnabled()) {
                        val helper = chain.thisObject
                        val service = XposedUtils.getObjectField(helper, "a") as? android.inputmethodservice.InputMethodService
                        if (service != null) {
                            // bb.u.o posts the final material height before this
                            // callback. Queue the complete style pass behind it so
                            // contentTopInsets and target screen coordinates are
                            // recomputed together, including the toolbar area.
                            val rootView = XposedUtils.getObjectField(service, "currentImeRootView") as? View
                            if (rootView != null) {
                                applyStyle(module, service, rootView)
                            } else {
                                scheduleHyperMaterialRefresh(module, service, helper)
                            }
                        }
                    }
                    res
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.u.o (post-layout material refresh)")
            }
        }

        // 6.2 Hook xe.b: Unblock system background blur capability checks
        val xeBClass = XposedUtils.findClass("xe.b", classLoader)
        if (xeBClass != null) {
            val cMethod = xeBClass.declaredMethods.find { it.name == "c" }
            if (cMethod != null) {
                module.hook(cMethod).intercept { chain ->
                    if (ConfigManager.isStyleEnabled() && ConfigManager.getBgType() == 0) true else chain.proceed()
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked xe.b.c")
            }
            val bMethod = xeBClass.declaredMethods.find { it.name == "b" && it.parameterTypes.size == 1 }
            if (bMethod != null) {
                module.hook(bMethod).intercept { chain ->
                    if (ConfigManager.isStyleEnabled() && ConfigManager.getBgType() == 0) true else chain.proceed()
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked xe.b.b")
            }
        }

        // 7. Hook HyperMaterial OutlineProvider: bb.t.getOutline(View, Outline)
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
                                if (radiusPx <= 0f) {
                                    outline.setRect(0, 0, view.width, view.height)
                                } else {
                                    outline.setRoundRect(0, 0, view.width, view.height + radiusPx.toInt(), radiusPx)
                                }
                                outline.alpha = 0.0f
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

        // 7.1 Hook bb.p (Delayed background and shadow reset)
        try {
            val bbPClass = XposedUtils.findClass("bb.p", classLoader)
            if (bbPClass != null) {
                val runMethod = bbPClass.declaredMethods.find { it.name == "run" && it.parameterTypes.isEmpty() }
                if (runMethod != null) {
                    module.hook(runMethod).intercept { chain ->
                        if (ConfigManager.isStyleEnabled()) {
                            val pObj = chain.thisObject
                            val helper = XposedUtils.getObjectField(pObj, "b")
                            if (helper != null) {
                                val f3500h = XposedUtils.getObjectField(helper, "h") as? View
                                if (f3500h != null) {
                                    materialBgViews.add(f3500h)
                                    if (ConfigManager.getBgType() == 0) {
                                        f3500h.setBackgroundColor(Color.TRANSPARENT)
                                    }
                                    f3500h.elevation = 0f
                                    f3500h.translationZ = 0f
                                }
                                val f3501i = XposedUtils.getObjectField(helper, "i") as? View
                                if (f3501i != null) {
                                    f3501i.elevation = 0f
                                    f3501i.translationZ = 0f
                                    if (ConfigManager.getOpacity() == 0 || ConfigManager.getBgType() != 0) {
                                        f3501i.visibility = View.GONE
                                        f3501i.setRenderEffect(null)
                                        f3501i.background = null
                                    }
                                }
                            }
                            return@intercept null
                        }
                        chain.proceed()
                    }
                    XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.p.run")
                }
            }
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Error hooking bb.p", t)
        }

        // 7.2 Hook l1.i0.u (Theme stock keyboard background color)
        try {
            val l1I0Class = XposedUtils.findClass("l1.i0", classLoader)
            if (l1I0Class != null) {
                val uMethod = l1I0Class.declaredMethods.find { it.name == "u" && it.parameterTypes.isEmpty() && it.returnType == Int::class.javaPrimitiveType }
                if (uMethod != null) {
                    module.hook(uMethod).intercept { chain ->
                        if (ConfigManager.isStyleEnabled() && ConfigManager.getBgType() == 0) {
                            return@intercept Color.TRANSPARENT
                        }
                        chain.proceed()
                    }
                    XposedUtils.log(module, "KeyboardStyleHook: Hooked l1.i0.u")
                }
            }
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Error hooking l1.i0.u", t)
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
                                    updateBottomBarAppearance(bInner)
                                }
                            }
                        }
                        res
                    }
                    XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.g1.S")
                }

                // Hook bb.g1.s(int i5, int i10, int i11, boolean z2): Force bottom view background color to 0 (TRANSPARENT)
                // Hook bb.g1.s(int i5, int i10, int i11, boolean z2): keep
                // HyperOS' separate bottom view transparent and continuous with the glass card.
                val staticSMethod = bbG1Class.declaredMethods.find { it.name == "s" && it.parameterTypes.size == 4 }
                if (staticSMethod != null) {
                    module.hook(staticSMethod).intercept { chain ->
                        if (ConfigManager.isStyleEnabled()) {
                            val iconColor = chain.getArg(1) as? Int ?: 0
                            val rippleColor = chain.getArg(2) as? Int ?: 0
                            val bgType = ConfigManager.getBgType()
                            val customText = parseOptionalColor(ConfigManager.getTextColor())
                            val isDark = when (bgType) {
                                1 -> isDarkColor(parseOptionalColor(ConfigManager.getBgColor()) ?: Color.WHITE)
                                else -> null
                            }
                            val iconColor = customText
                                ?: if (isDark != null) {
                                    if (isDark) Color.parseColor("#E0E0E0") else Color.parseColor("#757575")
                                } else {
                                    chain.getArg(1) as? Int ?: Color.parseColor("#757575")
                                }
                            val rippleColor = if (isDark != null) {
                                if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#1F000000")
                            } else {
                                chain.getArg(2) as? Int ?: Color.parseColor("#1F000000")
                            }
                            try {
                                val injectorClass = Class.forName("android.inputmethodservice.InputMethodServiceInjector")
                                val customizeMethod = injectorClass.declaredMethods.find { it.name == "customizeBottomViewColor" }
                                if (customizeMethod != null) {
                                    customizeMethod.isAccessible = true
                                    customizeMethod.invoke(null, true, 0, iconColor, rippleColor)
                                    customizeMethod.invoke(null, true, Color.TRANSPARENT, iconColor, rippleColor)
                                }
                            } catch (_: Throwable) {}
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                    XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.g1.s (BottomView transparent background)")
                }
            }
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Error hooking bb.g1", t)
        }

        // 9. Hook InputMethodServiceInjector (Framework bottom view bridge)
        try {
            val injectorClass = Class.forName("android.inputmethodservice.InputMethodServiceInjector")
            val addBottomViewMethod = injectorClass.declaredMethods.find { it.name == "addMiuiBottomView" }
            if (addBottomViewMethod != null) {
                module.hook(addBottomViewMethod).intercept { chain ->
                    val service = chain.getArg(6) as? android.inputmethodservice.InputMethodService
                    if (service != null) {
                        ConfigManager.syncFromProvider(service)
                    }
                    val res = chain.proceed()
                    if (ConfigManager.isStyleEnabled()) {
                        val miuiBottomArea = chain.getArg(4) as? ViewGroup
                        miuiBottomArea?.background = null
                        miuiBottomArea?.setBackgroundColor(Color.TRANSPARENT)
                        if (service != null) {
                            enforceWindowAndBottomBarTransparency(service)
                        }
                        tryHookInputMethodBottomManager(module, injectorClass)
                    }
                    res
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked InputMethodServiceInjector.addMiuiBottomView")
            }

            val customizeMethod = injectorClass.declaredMethods.find {
                it.name == "customizeBottomViewColor" && it.parameterTypes.size == 4
            }
            if (customizeMethod != null) {
                module.hook(customizeMethod).intercept { chain ->
                    val res = if (ConfigManager.isStyleEnabled()) {
                        chain.proceed(arrayOf(true, Color.TRANSPARENT, chain.getArg(2), chain.getArg(3)))
                    } else {
                        chain.proceed()
                    }
                    if (ConfigManager.isStyleEnabled()) {
                        tryHookInputMethodBottomManager(module, injectorClass)
                    }
                    res
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked InputMethodServiceInjector.customizeBottomViewColor")
            }

            tryHookInputMethodBottomManager(module, injectorClass)
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Error hooking InputMethodServiceInjector", t)
        }
    }

    /**
     * bb.u.k() removes both material views when the current editor package is
     * absent from its downloaded whitelist. Add only the active package before
     * that check so the native method keeps the blur views alive.
     * absent from its downloaded whitelist. Add only the active package to "v"
     * (packageMaterialVersions) before that check so the native method keeps
     * the blur views alive.
     *
     * IMPORTANT: "w" is hyper_material_force_dark and "x" is hyper_material_force_light!
     * Never add packages to "w" or "x", otherwise bb.u.k() forces dark/light mode
     * regardless of the system UI theme.
     */
    private fun forceCurrentPackageIntoMaterialWhitelist(helper: Any) {
        val packageName = XposedUtils.getObjectField(helper, "u") as? String ?: return

        // 1. Package material versions map: version <= 2 enables hyper material
        val versions = LinkedHashMap<Any?, Any?>()
        (XposedUtils.getObjectField(helper, "v") as? Map<*, *>)?.forEach { (key, value) ->
            versions[key] = value
        }
        versions[packageName] = 2
        XposedUtils.setObjectField(helper, "v", versions)

        val primaryPackages = LinkedHashSet<Any?>()
        // 2. Ensure packageName is NOT in force_dark ("w") or force_light ("x")
        val forceDark = LinkedHashSet<Any?>()
        (XposedUtils.getObjectField(helper, "w") as? Set<*>)?.forEach {
            primaryPackages.add(it)
            if (it != packageName) forceDark.add(it)
        }
        primaryPackages.add(packageName)
        XposedUtils.setObjectField(helper, "w", primaryPackages)
        XposedUtils.setObjectField(helper, "w", forceDark)

        val secondaryPackages = LinkedHashSet<Any?>()
        val forceLight = LinkedHashSet<Any?>()
        (XposedUtils.getObjectField(helper, "x") as? Set<*>)?.forEach {
            secondaryPackages.add(it)
            if (it != packageName) forceLight.add(it)
        }
        secondaryPackages.add(packageName)
        XposedUtils.setObjectField(helper, "x", secondaryPackages)
        XposedUtils.setObjectField(helper, "x", forceLight)

        // 3. Clear f3499g so voiceassist / quicksearchbox forced dark flag does not leak
        try {
            XposedUtils.setObjectField(helper, "g", false)
        } catch (_: Throwable) {}
    }

    /** Compose stores sRGB colors as an unsigned ARGB value in the high 32 bits. */
    private fun composeColor(argb: Int): Long =
        (argb.toLong() and 0xffffffffL) shl 32

    private fun readLongField(instance: Any, name: String): Long? = try {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.getLong(instance)
    } catch (_: Throwable) {
        null
    }

    private fun writeLongField(instance: Any, name: String, value: Long) {
        try {
            instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.setLong(instance, value)
        } catch (_: Throwable) {
        }
    }

    /**
     * na.d core fields used here are h/i/k/l/m (key and mode text/icons),
     * w/x (toolbar icons), z (bottom-bar icon), and A/B (dividers).
     * B0..L0 are the APPS panel colors.
     */
    private fun updateKeyboardContrast(
        colors: Any,
        enabled: Boolean,
        bgType: Int,
        bgColor: String,
        textColor: String,
        functionKeycapColor: String,
        menuCardColor: String,
        letterKeycapColor: String
    ) {
        val coreFields = arrayOf("a", "d", "e", "h", "i", "k", "l", "m", "w", "x", "z", "A", "B")
        val appsPanelFields = arrayOf("B0", "C0", "D0", "E0", "F0", "G0", "H0", "I0", "J0", "K0", "L0")
        val fieldNames = coreFields + appsPanelFields
        synchronized(originalAppsPanelColors) {
            val originals = originalAppsPanelColors.getOrPut(colors) {
                fieldNames.mapNotNull { name -> readLongField(colors, name)?.let { name to it } }.toMap()
            }

            // The same na.d instance can survive a background-type change.
            // Restore it before applying the colors required by the current mode.
            originals.forEach { (name, value) -> writeLongField(colors, name, value) }
            if (!enabled) {
                return
            }

            val systemDark = XposedUtils.getObjectField(colors, "n1") as? Boolean ?: false
            val surfaceDark = if (bgType == 1) {
                val solid = try {
                    Color.parseColor(bgColor)
                } catch (_: Throwable) {
                    Color.parseColor("#1E1E2E")
                }
                // Perceived luminance, scaled to 0..255.
                (299 * Color.red(solid) + 587 * Color.green(solid) + 114 * Color.blue(solid)) / 1000 < 150
            } else {
                systemDark
            }
            val customText = textColor.takeIf { it.isNotBlank() }?.let {
                try {
                    Color.parseColor(it)
                } catch (_: Throwable) {
                    null
                }
            }
            val customFunctionKeycap = parseOptionalColor(functionKeycapColor)
            val customMenuCard = parseOptionalColor(menuCardColor)
            val customLetterKeycap = parseOptionalColor(letterKeycapColor)
            val keySurfaceDark = (customLetterKeycap ?: customFunctionKeycap)?.let {
                (299 * Color.red(it) + 587 * Color.green(it) + 114 * Color.blue(it)) / 1000 < 150
            } ?: surfaceDark
            val keyPrimary = customText
                ?: if (keySurfaceDark) Color.argb(242, 255, 255, 255) else Color.argb(230, 0, 0, 0)
            val keySecondary = customText?.let {
                Color.argb(
                    (Color.alpha(it) * 0.82f).toInt(),
                    Color.red(it),
                    Color.green(it),
                    Color.blue(it)
                )
            } ?: if (keySurfaceDark) Color.argb(217, 255, 255, 255) else Color.argb(178, 0, 0, 0)
            val menuSurfaceDark = customMenuCard?.let {
                (299 * Color.red(it) + 587 * Color.green(it) + 114 * Color.blue(it)) / 1000 < 150
            } ?: surfaceDark
            val menuPrimary = customText
                ?: if (menuSurfaceDark) Color.argb(242, 255, 255, 255) else Color.argb(230, 0, 0, 0)
            val menuSecondary = customText?.let {
                Color.argb(
                    (Color.alpha(it) * 0.82f).toInt(),
                    Color.red(it),
                    Color.green(it),
                    Color.blue(it)
                )
            } ?: if (menuSurfaceDark) Color.argb(217, 255, 255, 255) else Color.argb(178, 0, 0, 0)
            val divider = if (surfaceDark) Color.argb(54, 255, 255, 255) else Color.argb(42, 0, 0, 0)
            val card = if (surfaceDark) Color.argb(46, 255, 255, 255) else Color.argb(105, 255, 255, 255)
            val tooltip = if (surfaceDark) Color.rgb(45, 48, 53) else Color.rgb(250, 250, 250)
            val tooltipBorder = if (surfaceDark) Color.argb(80, 255, 255, 255) else Color.argb(42, 0, 0, 0)
            val tooltipShadow = Color.argb(if (surfaceDark) 110 else 60, 0, 0, 0)
            val accent = Color.rgb(52, 130, 255)

            val replacements = mutableMapOf(
                "a" to Color.TRANSPARENT,
                "B0" to Color.TRANSPARENT,
                "C0" to (customMenuCard ?: card),
                "D0" to menuSecondary,
                "E0" to menuPrimary,
                "F0" to Color.argb(48, 52, 130, 255),
                "G0" to accent,
                "H0" to menuPrimary,
                "I0" to tooltip,
                "J0" to (customText ?: if (surfaceDark) Color.WHITE else Color.BLACK),
                "K0" to tooltipBorder,
                "L0" to tooltipShadow
            )
            if (customFunctionKeycap != null) {
                replacements["e"] = customFunctionKeycap
                replacements["d"] = resolvePressedKeycapColor(customFunctionKeycap, keySurfaceDark)
            }
            if (bgType == 1 || customText != null || customFunctionKeycap != null || customLetterKeycap != null) {
                replacements.putAll(
                    mapOf(
                        "h" to keyPrimary,
                        "i" to keySecondary,
                        "k" to keySecondary,
                        "l" to keyPrimary,
                        "m" to keyPrimary,
                        "w" to keyPrimary,
                        "x" to keyPrimary,
                        "z" to keyPrimary
                    )
                )
            }
            if (bgType == 1) {
                replacements["A"] = divider
                replacements["B"] = divider
            }
            replacements.forEach { (name, value) -> writeLongField(colors, name, composeColor(value)) }
        }
    }

    /**
     * MIUIFrequentPhrase supplies this PopupWindow class, but the Xiaomi IME
     * loads and displays it in its own process. Hooking the framework show call
     * avoids racing the APK's dynamic class loader and runs after all stock
     * panel backgrounds have been assigned.
     */
    private fun installClipboardPopupHook(module: XposedModule) {
        PopupWindow::class.java.declaredMethods
            .filter { it.name == "showAtLocation" && it.parameterTypes.size == 4 }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val popup = chain.thisObject as? PopupWindow
                    if (popup?.javaClass?.name == CLIPBOARD_POPUP_CLASS) {
                        applyClipboardPopupStyle(module, popup)
                    }
                    result
                }
            }
        XposedUtils.log(module, "KeyboardStyleHook: Hooked clipboard PopupWindow glass styling")
    }

    private fun applyClipboardPopupStyle(module: XposedModule, popup: PopupWindow) {
        val service = XposedUtils.getObjectField(popup, "mInputMethodService") as?
            android.inputmethodservice.InputMethodService ?: return
        ConfigManager.syncFromProvider(service)
        if (!ConfigManager.isStyleEnabled()) return

        val root = popup.contentView ?: return
        val inside = findViewByResourceName(root, "inside_view") ?: root
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        root.background = null
        findViewByResourceName(root, "outside_view")?.background = null

        popup.javaClass.classLoader?.let { loader ->
            installClipboardAdapterHooks(module, loader)
        }
        // The popup is already attached when showAtLocation returns, but its
        // first traversal has not drawn yet. Replace every inflated stock card
        // now; the posted block below is reserved for native material setup.
        styleClipboardViewTree(root)
        inside.post {
            try {
                val density = inside.resources.displayMetrics.density
                val radiusPx = ConfigManager.getCornerRadius().coerceAtLeast(0) * density
                when (ConfigManager.getBgType()) {
                    0 -> {
                        inside.setBackgroundColor(Color.TRANSPARENT)
                        val helper = XposedUtils.getObjectField(service, "hyperMaterialHelper")
                        if (helper != null) {
                            updateCachedGlassTokens(
                                helper,
                                ConfigManager.getBlurRadius(),
                                ConfigManager.getOpacity()
                            )
                            try {
                                invokeHelperMethod(helper, "c", inside)
                            } catch (t: Throwable) {
                                XposedUtils.logError(
                                    module,
                                    "KeyboardStyleHook: clipboard native HyperMaterial apply failed",
                                    t
                                )
                            }
                        }
                        val isDark = isDarkSurface(service)
                        applySoftGlassForeground(
                            inside,
                            isDark,
                            ConfigManager.getOpacity(),
                            radiusPx
                        )
                    }
                    1 -> {
                        inside.foreground = null
                        inside.background = ColorDrawable(
                            resolveSolidColor(
                                ConfigManager.getBgColor(),
                                ConfigManager.getOpacity()
                            )
                        )
                    }
                    2 -> {
                        inside.foreground = null
                        getOrLoadBitmap(service)?.takeIf { !it.isRecycled }?.let { bitmap ->
                            inside.background = BitmapDrawable(service.resources, bitmap).apply {
                                alpha = ConfigManager.getOpacity().coerceIn(0, 100) * 255 / 100
                            }
                        }
                    }
                }

                applyTopCornerOutline(inside, radiusPx)
                styleClipboardViewTree(root)
                if (ConfigManager.isVerboseLogEnabled()) {
                    XposedUtils.log(
                        module,
                        "KeyboardStyleHook: clipboard panel synchronized with keyboard background"
                    )
                }
            } catch (t: Throwable) {
                XposedUtils.logError(module, "KeyboardStyleHook: clipboard panel styling failed", t)
            }
        }
    }

    private fun installClipboardAdapterHooks(module: XposedModule, classLoader: ClassLoader) {
        CLIPBOARD_ADAPTER_CLASSES.forEach { className ->
            val adapterClass = XposedUtils.findClass(className, classLoader) ?: return@forEach
            if (!clipboardAdapterHooks.add(adapterClass)) return@forEach

            adapterClass.declaredMethods
                .filter {
                    !it.isBridge && !it.isSynthetic &&
                        ((it.name == "onBindViewHolder" && it.parameterTypes.size >= 2) ||
                            (it.name == "onCreateViewHolder" && it.parameterTypes.size >= 2))
                }
                .forEach { method ->
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        if (ConfigManager.isStyleEnabled()) {
                            val holder = if (method.name == "onCreateViewHolder") {
                                result
                            } else {
                                chain.getArg(0)
                            }
                            val itemView = holder?.let {
                                XposedUtils.getObjectField(it, "itemView") as? View
                            }
                            // RecyclerView may draw the holder immediately after
                            // this callback returns. Style it synchronously so a
                            // stock opaque selector can never reach the first frame.
                            itemView?.let(::styleClipboardViewTree)
                        }
                        result
                    }
                }
        }
    }

    private fun styleClipboardViewTree(view: View) {
        styleClipboardViewTree(view, clipboardPalette(view))
    }

    private fun styleClipboardViewTree(view: View, palette: ClipboardPalette) {
        val name = resourceEntryName(view)
        val density = view.resources.displayMetrics.density
        val itemRadius = 18f * density
        val smallRadius = 12f * density

        when (name) {
            "outside_view", "clipboard_title_bar", "list_view_layout", "recycler_view" -> {
                clearClipboardBackground(view)
            }
            "clipboard_item_layout", "phrase_item_layout" -> {
                applyClipboardBackground(
                    view,
                    clipboardBackgroundSignature(
                        STYLE_ITEM_CARD,
                        palette.card,
                        palette.cardPressed,
                        itemRadius
                    )
                ) {
                    statefulRoundedBackground(
                        palette.card,
                        palette.cardPressed,
                        itemRadius
                    )
                }
                view.elevation = 0f
            }
            "clipboard_loading" -> {
                applyClipboardBackground(
                    view,
                    clipboardBackgroundSignature(STYLE_LOADING_CARD, palette.card, itemRadius)
                ) {
                    roundedBackground(palette.card, itemRadius)
                }
            }
            "clipboard_text", "phrase_text" -> {
                applyClipboardBackground(
                    view,
                    clipboardBackgroundSignature(
                        STYLE_TAB,
                        palette.tabSelected,
                        Color.TRANSPARENT,
                        smallRadius
                    )
                ) {
                    selectedRoundedBackground(
                        palette.tabSelected,
                        Color.TRANSPARENT,
                        smallRadius
                    )
                }
                if (view is TextView) {
                    view.setTextColor(
                        ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                            intArrayOf(palette.primaryText, palette.secondaryText)
                        )
                    )
                }
            }
            "clipboard_text_item_top", "clipboard_text_item_bottom", "image_end_show",
            "phrase_text_item", "text_view" -> (view as? TextView)?.setTextColor(palette.primaryText)
            "clipboard_no_items", "loading_text", "clipboard_across_devices_tip_text" -> {
                (view as? TextView)?.setTextColor(palette.secondaryText)
            }
            "pack_up_view", "delete_and_add_action_button" -> {
                (view as? ImageView)?.setColorFilter(palette.primaryText)
            }
        }

        if (view is ViewGroup) {
            if (containsNamedChildren(view, "clipboard_text", "phrase_text")) {
                applyClipboardBackground(
                    view,
                    clipboardBackgroundSignature(STYLE_TAB_TRACK, palette.tabTrack, smallRadius)
                ) {
                    roundedBackground(palette.tabTrack, smallRadius)
                }
            }
            if (name == "clipboard_tip_view" && view.childCount > 0) {
                val tipCard = view.getChildAt(0)
                applyClipboardBackground(
                    tipCard,
                    clipboardBackgroundSignature(STYLE_TIP_CARD, palette.card, itemRadius)
                ) {
                    roundedBackground(palette.card, itemRadius)
                }
            }
            for (index in 0 until view.childCount) {
                styleClipboardViewTree(view.getChildAt(index), palette)
            }
        }
    }

    private fun clipboardPalette(view: View): ClipboardPalette {
        val opacityStrength = ConfigManager.getOpacity().coerceIn(0, 100) / 100f
        val customCard = parseOptionalColor(ConfigManager.getMenuCardColor())
        val dark = when (ConfigManager.getBgType()) {
            1 -> isDarkColor(parseOptionalColor(ConfigManager.getBgColor()) ?: Color.WHITE)
            else -> (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        val customText = parseOptionalColor(ConfigManager.getTextColor())
        val primary = customText ?: if (dark) Color.rgb(245, 247, 252) else Color.rgb(20, 22, 27)
        val secondary = withAlpha(primary, if (dark) 178 else 150)
        val card = customCard ?: if (dark) {
            Color.argb((92 * opacityStrength).toInt(), 255, 255, 255)
        } else {
            Color.argb((145 * opacityStrength).toInt(), 255, 255, 255)
        }
        val pressed = if (dark) {
            Color.argb((135 * opacityStrength).toInt(), 255, 255, 255)
        } else {
            Color.argb((190 * opacityStrength).toInt(), 255, 255, 255)
        }
        val tabTrack = if (dark) {
            Color.argb((62 * opacityStrength).toInt(), 255, 255, 255)
        } else {
            Color.argb((105 * opacityStrength).toInt(), 255, 255, 255)
        }
        val tabSelected = customCard ?: if (dark) {
            Color.argb((118 * opacityStrength).toInt(), 255, 255, 255)
        } else {
            Color.argb((205 * opacityStrength).toInt(), 255, 255, 255)
        }
        return ClipboardPalette(card, pressed, tabTrack, tabSelected, primary, secondary)
    }

    private fun applyTopCornerOutline(view: View, radiusPx: Float) {
        if (radiusPx <= 0f) {
            view.clipToOutline = false
            return
        }
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                if (target.width > 0 && target.height > 0) {
                    outline.setRoundRect(
                        0,
                        0,
                        target.width,
                        target.height + radiusPx.toInt(),
                        radiusPx
                    )
                }
            }
        }
        view.invalidateOutline()
    }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
    }

    private fun statefulRoundedBackground(normal: Int, pressed: Int, radius: Float) =
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundedBackground(pressed, radius)
            )
            addState(intArrayOf(), roundedBackground(normal, radius))
        }

    private fun selectedRoundedBackground(selected: Int, normal: Int, radius: Float) =
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_selected),
                roundedBackground(selected, radius)
            )
            addState(intArrayOf(), roundedBackground(normal, radius))
        }

    private fun applyClipboardBackground(
        view: View,
        signature: Int,
        create: () -> Drawable
    ) {
        val cached = clipboardAppliedBackgrounds[view]
        if (cached?.signature == signature && view.background === cached.drawable) return

        val drawable = create()
        view.background = drawable
        clipboardAppliedBackgrounds[view] = AppliedClipboardBackground(signature, drawable)
    }

    private fun clearClipboardBackground(view: View) {
        clipboardAppliedBackgrounds.remove(view)
        if (view.background != null) view.background = null
    }

    private fun clipboardBackgroundSignature(kind: Int, vararg values: Any): Int {
        var result = kind
        values.forEach { value -> result = 31 * result + value.hashCode() }
        return result
    }

    private fun containsNamedChildren(group: ViewGroup, vararg names: String): Boolean {
        val found = group.childrenResourceNames().toSet()
        return names.all(found::contains)
    }

    private fun ViewGroup.childrenResourceNames(): List<String> =
        (0 until childCount).mapNotNull { resourceEntryName(getChildAt(it)) }

    private fun findViewByResourceName(view: View, name: String): View? {
        if (resourceEntryName(view) == name) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findViewByResourceName(view.getChildAt(index), name)?.let { return it }
            }
        }
        return null
    }

    private fun resourceEntryName(view: View): String? {
        if (view.id == View.NO_ID) return null
        return try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: Throwable) {
            null
        }
    }

    private fun isDarkSurface(context: android.content.Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun isDarkColor(color: Int): Boolean =
        (299 * Color.red(color) + 587 * Color.green(color) + 114 * Color.blue(color)) / 1000 < 150

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private data class ClipboardPalette(
        val card: Int,
        val cardPressed: Int,
        val tabTrack: Int,
        val tabSelected: Int,
        val primaryText: Int,
        val secondaryText: Int
    )

    private data class AppliedClipboardBackground(
        val signature: Int,
        val drawable: Drawable
    )

    private const val STYLE_ITEM_CARD = 1
    private const val STYLE_LOADING_CARD = 2
    private const val STYLE_TAB = 3
    private const val STYLE_TAB_TRACK = 4
    private const val STYLE_TIP_CARD = 5

    private const val CLIPBOARD_POPUP_CLASS =
        "com.miui.inputmethod.InputMethodClipboardPhrasePopupView"
    private val CLIPBOARD_ADAPTER_CLASSES = arrayOf(
        "com.miui.inputmethod.InputMethodClipboardAdapter",
        "com.miui.inputmethod.InputMethodClipboardHeaderAdapter",
        "com.miui.inputmethod.InputMethodPhraseAdapter"
    )

    private fun parseOptionalColor(value: String): Int? = value.takeIf { it.isNotBlank() }?.let {
        try {
            Color.parseColor(it)
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolvePressedKeycapColor(color: Int, dark: Boolean): Int {
        val target = if (dark) 255 else 0
        val amount = if (dark) 0.18f else 0.14f
        fun blend(channel: Int): Int = (channel + (target - channel) * amount).toInt().coerceIn(0, 255)
        return Color.argb(
            Color.alpha(color),
            blend(Color.red(color)),
            blend(Color.green(color)),
            blend(Color.blue(color))
        )
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

    /**
     * bb.u caches the light and dark material tokens in Kotlin lazy fields.
     * Updating only the bb.u.e(boolean) factory cannot change a token that has
     * already been created, so update both cached tokens before reapplying it.
     */
    private fun updateCachedGlassTokens(helper: Any, blurRadiusDp: Int, opacity: Int): Boolean {
        var updated = false
        val strength = ((opacity.coerceIn(10, 100) - 10) / 90.0f).coerceIn(0f, 1f)
        val strength = (opacity.coerceIn(0, 100) / 100.0f).coerceIn(0f, 1f)

        for ((index, lazyFieldName) in arrayOf("p", "q").withIndex()) {
            try {
                val lazyValue = XposedUtils.getObjectField(helper, lazyFieldName) ?: continue
                val getValue = lazyValue.javaClass.methods.firstOrNull {
                    it.name == "getValue" && it.parameterTypes.isEmpty()
                } ?: continue
                val token = getValue.invoke(lazyValue) ?: continue
                XposedUtils.setObjectField(token, "p", blurRadiusDp.coerceIn(0, 400))

                // Keep Xiaomi's blend modes, but replace its heavy masks with
                // translucent neutral tints. This preserves the full native blur
                // while the slider changes glass clarity instead of View opacity.
                // translucent neutral tints. Start every tint at zero so 0%
                // leaves only the native blur instead of a permanent gray veil.
                val blendColors = if (index == 0) {
                    intArrayOf(
                        Color.argb((4 + 106 * strength).toInt(), 255, 255, 255),
                        Color.argb((2 + 68 * strength).toInt(), 246, 249, 255),
                        Color.argb((1 + 39 * strength).toInt(), 187, 205, 232)
                        Color.argb((110 * strength).toInt(), 255, 255, 255),
                        Color.argb((70 * strength).toInt(), 246, 249, 255),
                        Color.argb((40 * strength).toInt(), 187, 205, 232)
                    )
                } else {
                    intArrayOf(
                        Color.argb((5 + 95 * strength).toInt(), 27, 30, 38),
                        Color.argb((2 + 53 * strength).toInt(), 255, 255, 255),
                        Color.argb((1 + 34 * strength).toInt(), 153, 178, 216)
                        Color.argb((100 * strength).toInt(), 27, 30, 38),
                        Color.argb((55 * strength).toInt(), 255, 255, 255),
                        Color.argb((35 * strength).toInt(), 153, 178, 216)
                    )
                }
                XposedUtils.setObjectField(token, "e", blendColors)
                updated = true
            } catch (_: Throwable) {
            }
        }

        return updated
    }

    /**
     * Keep the native blur view's background transparent. The target APK clears
     * that background 20 ms after applying material, so the outline belongs in
     * the foreground where it does not replace the blur surface.
     */
    private fun applySoftGlassForeground(
        view: View,
        isDark: Boolean,
        opacity: Int,
        radiusPx: Float,
        floating: Boolean
        radiusPx: Float
    ) {
        val density = view.resources.displayMetrics.density
        val strength = ((opacity.coerceIn(10, 100) - 10) / 90.0f).coerceIn(0f, 1f)
        val radii = if (floating) {
            floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx)
        } else {
            floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
        val strength = (opacity.coerceIn(0, 100) / 100.0f).coerceIn(0f, 1f)
        if (strength <= 0f) {
            view.foreground = null
            return
        }
        val radii = floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)

        val softLight = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (isDark) {
                intArrayOf(
                    Color.argb((3 + 67 * strength).toInt(), 255, 255, 255),
                    Color.argb((70 * strength).toInt(), 255, 255, 255),
                    Color.TRANSPARENT,
                    Color.argb((1 + 34 * strength).toInt(), 111, 151, 205)
                    Color.argb((35 * strength).toInt(), 111, 151, 205)
                )
            } else {
                intArrayOf(
                    Color.argb((4 + 76 * strength).toInt(), 255, 255, 255),
                    Color.argb((1 + 31 * strength).toInt(), 255, 255, 255),
                    Color.argb((1 + 39 * strength).toInt(), 184, 205, 232)
                    Color.argb((80 * strength).toInt(), 255, 255, 255),
                    Color.argb((32 * strength).toInt(), 255, 255, 255),
                    Color.argb((40 * strength).toInt(), 184, 205, 232)
                )
            }
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
        }

        val highlight = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke(
                (0.8f * density).toInt().coerceAtLeast(1),
                Color.argb((18 + 102 * strength).toInt(), 255, 255, 255)
                Color.argb((120 * strength).toInt(), 255, 255, 255)
            )
            cornerRadii = radii
        }

        view.foreground = LayerDrawable(arrayOf(softLight, highlight))
    }

    private fun updateGlassTintLayer(
        helper: Any,
        materialView: View,
        isDark: Boolean,
        opacity: Int,
        radiusPx: Float,
        floating: Boolean
    ) {
        val parent = materialView.parent as? ViewGroup ?: return
    private fun invokeHelperMethod(helper: Any, name: String, vararg args: Any?): Any? {
        val method = helper.javaClass.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size
        } ?: return null
        method.isAccessible = true
        return method.invoke(helper, *args)
    }

        fun syncLayout(tint: View) {
            val sourceParams = materialView.layoutParams ?: return
            tint.layoutParams = if (sourceParams is FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams(sourceParams)
            } else {
                ViewGroup.LayoutParams(sourceParams)
            }
    /** Resolve the configured solid color for custom background drawables. */
    private fun resolveSolidColor(colorString: String, opacity: Int): Int {
        val parsedColor = try {
            Color.parseColor(colorString)
        } catch (_: Throwable) {
            Color.parseColor("#1E1E2E")
        }
        val alpha = (Color.alpha(parsedColor) * (opacity.coerceIn(0, 100) / 100.0f))
            .toInt()
            .coerceIn(0, 255)
        return Color.argb(
            alpha,
            Color.red(parsedColor),
            Color.green(parsedColor),
            Color.blue(parsedColor)
        )
    }

        var tintView = glassTintViews[helper]
        if (tintView == null || tintView.parent !== parent) {
            (tintView?.parent as? ViewGroup)?.removeView(tintView)
            tintView = View(materialView.context).apply {
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                elevation = 0f
                translationZ = 0f
    private fun restoreCustomBackground(helper: Any, materialView: View) {
        if (!ConfigManager.isStyleEnabled()) return

        val bgType = ConfigManager.getBgType()
        if (bgType == 0) return

        try {
            // Disable pass-window blur and the rim render effect without
            // skipping bb.u.c(), which is also responsible for view setup.
            invokeHelperMethod(helper, "m")
        } catch (_: Throwable) {
        }

        materialView.alpha = 1.0f
        when (bgType) {
            1 -> {
                materialView.background = ColorDrawable(
                    resolveSolidColor(ConfigManager.getBgColor(), ConfigManager.getOpacity())
                )
            }
            val materialIndex = parent.indexOfChild(materialView).coerceAtLeast(0)
            parent.addView(
                tintView,
                (materialIndex + 1).coerceAtMost(parent.childCount),
                ViewGroup.LayoutParams(0, 0)
            )
            syncLayout(tintView)
            val synchronizedTint = tintView
            materialView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (synchronizedTint.parent === materialView.parent) {
                    syncLayout(synchronizedTint)
            2 -> {
                val service = XposedUtils.getObjectField(helper, "a") as?
                    android.inputmethodservice.InputMethodService
                val bitmap = service?.let { getOrLoadBitmap(it) }
                if (service != null && bitmap != null && !bitmap.isRecycled) {
                    materialView.background = BitmapDrawable(service.resources, bitmap).apply {
                        alpha = (ConfigManager.getOpacity().coerceIn(0, 100) * 255 / 100)
                    }
                }
            }
            glassTintViews[helper] = tintView
        } else {
            syncLayout(tintView)
        }
        materialView.foreground = null
        materialView.elevation = 0f
        materialView.translationZ = 0f
        materialView.visibility = View.VISIBLE
        (XposedUtils.getObjectField(helper, "i") as? View)?.visibility = View.GONE
        materialView.invalidate()
    }

        val strength = ((opacity.coerceIn(10, 100) - 10) / 90.0f).coerceIn(0f, 1f)
        val radii = if (floating) {
            floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx)
        } else {
            floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
    /**
     * Wait until Xiaomi's material view has a real surface size before applying
     * HyperMaterial. Calls from onCreateInputView/onStartInputView/bb.u.g can all
     * arrive while the non-floating glass view is still 0px tall on a cold start.
     * A generation per view coalesces those calls, then two bounded settle passes
     * cover the render-thread hand-off without leaving permanent polling behind.
     */
    private fun scheduleHyperMaterialRefresh(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        helper: Any?
    ) {
        if (helper == null || !ConfigManager.isStyleEnabled()) return
        val materialView = XposedUtils.getObjectField(helper, "h") as? View ?: return
        val dynamicGlass = ConfigManager.getBgType() == 0

        val generation = synchronized(materialRefreshGenerations) {
            val next = (materialRefreshGenerations[materialView] ?: 0) + 1
            materialRefreshGenerations[materialView] = next
            next
        }
        val colors = if (isDark) {
            intArrayOf(
                Color.argb((145 * strength).toInt(), 38, 41, 49),
                Color.argb((105 * strength).toInt(), 25, 28, 35),
                Color.argb((85 * strength).toInt(), 52, 61, 76)
            )
        } else {
            intArrayOf(
                Color.argb((190 * strength).toInt(), 255, 255, 255),
                Color.argb((125 * strength).toInt(), 247, 249, 253),
                Color.argb((145 * strength).toInt(), 218, 229, 244)
            )

        val refresh = object : Runnable {
            private var layoutWaitFrames = 0
            private var settlePass = 0

            override fun run() {
                val isCurrent = synchronized(materialRefreshGenerations) {
                    materialRefreshGenerations[materialView] == generation
                }
                if (!isCurrent || !ConfigManager.isStyleEnabled()) return

                if (!materialView.isAttachedToWindow || materialView.width <= 0 || materialView.height <= 0) {
                    // bb.u.o normally resolves this on the next layout. Wait for
                    // dimensions before applying shaders.
                    if (layoutWaitFrames++ < 120) {
                        materialView.postOnAnimation(this)
                    } else {
                        synchronized(materialRefreshGenerations) {
                            if (materialRefreshGenerations[materialView] == generation) {
                                materialRefreshGenerations.remove(materialView)
                            }
                        }
                        XposedUtils.log(module, "KeyboardStyleHook: skipped zero-size material view after cold-start wait")
                    }
                    return
                }

                updateHyperMaterialViews(module, service, helper)

                if (dynamicGlass && settlePass < 2) {
                    val delayMs = if (settlePass++ == 0) 64L else 240L
                    materialView.postDelayed(this, delayMs)
                } else {
                    synchronized(materialRefreshGenerations) {
                        if (materialRefreshGenerations[materialView] == generation) {
                            materialRefreshGenerations.remove(materialView)
                        }
                    }
                }
            }
        }
        tintView.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setStroke(
                materialView.resources.displayMetrics.density.toInt().coerceAtLeast(1),
                Color.argb((16 + 96 * strength).toInt(), 255, 255, 255)
            )
        }
        tintView.visibility = View.VISIBLE
        materialView.post(refresh)
    }

    private fun invokeHelperMethod(helper: Any, name: String, vararg args: Any?): Any? {
        val method = helper.javaClass.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size
        } ?: return null
        method.isAccessible = true
        return method.invoke(helper, *args)
    }

    private fun updateHyperMaterialViews(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        helper: Any?
    ) {
        if (helper == null) return
        val f3500h = XposedUtils.getObjectField(helper, "h") as? View ?: return
        val f3501i = XposedUtils.getObjectField(helper, "i") as? View

        if (!ConfigManager.isStyleEnabled()) return

        val bgType = ConfigManager.getBgType() // 0: DYNAMIC_GLASS, 1: COLOR, 2: IMAGE
        val bgColorStr = ConfigManager.getBgColor()
        val opacity = ConfigManager.getOpacity()
        val cornerRadiusDp = ConfigManager.getCornerRadius()
        val hMarginDp = ConfigManager.getMarginHorizontal()
        val bMarginDp = ConfigManager.getMarginBottom()
        val blurRadiusDp = ConfigManager.getBlurRadius()

        val density = f3500h.resources.displayMetrics.density
        val radiusPx = cornerRadiusDp * density
        val hMarginPx = (hMarginDp * density).toInt()
        val bMarginPx = (bMarginDp * density).toInt()
        val floating = hMarginPx > 0 || bMarginPx > 0
        val isDark = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        f3500h.post {
            try {
                // 1. Background Customization on f3500h (the actual keyboard card at bottom)
                when (bgType) {
                    0 -> { // HyperOS Dynamic Liquid Glass (系统通知中心同款动态毛玻璃)
                        val glassStrength = ((opacity.coerceIn(10, 100) - 10) / 90.0f).coerceIn(0f, 1f)
                        val glassStrength = (opacity.coerceIn(0, 100) / 100.0f).coerceIn(0f, 1f)
                        // bb.u inserts this view at index 0, behind the keyboard.
                        // Keep full material strength; Z elevation is normalized
                        // below so rounded corners cannot lift it above key content.
                        f3500h.alpha = 1.0f

                        // This APK already ships a complete native material pipeline
                        // in bb.u.c(View). Tune its cached token and let that code
                        // configure pass-window blur, radius, blend colors and bloom.
                        f3500h.setBackgroundColor(Color.TRANSPARENT)
                        val tokenUpdated = updateCachedGlassTokens(helper, blurRadiusDp, opacity)
                        val materialApplied = try {
                            invokeHelperMethod(helper, "c", f3500h) as? Boolean ?: false
                        } catch (t: Throwable) {
                            XposedUtils.logError(module, "KeyboardStyleHook: native HyperMaterial apply failed", t)
                            false
                        }

                        applySoftGlassForeground(f3500h, isDark, opacity, radiusPx, floating)
                        applySoftGlassForeground(f3500h, isDark, opacity, radiusPx)
                        f3500h.visibility = View.VISIBLE

                        if (ConfigManager.isVerboseLogEnabled()) {
                            XposedUtils.log(
                                module,
                                "KeyboardStyleHook: glass tokenUpdated=$tokenUpdated, materialApplied=$materialApplied, blur=${blurRadiusDp}dp"
                            )
                        }

                        // Update f3501i (RuntimeShader Rim Light & Shadow)
                        if (f3501i != null) {
                            try {
                                f3501i.alpha = 0.55f + 0.25f * glassStrength
                                val bMethod = helper.javaClass.declaredMethods.find { it.name == "b" && it.parameterTypes.size == 1 }
                                bMethod?.invoke(helper, f3501i)
                                f3501i.visibility = View.VISIBLE
                            } catch (_: Throwable) {
                            if (opacity == 0) {
                                f3501i.visibility = View.GONE
                            } else {
                                try {
                                    f3501i.alpha = 1.0f
                                    val bMethod = helper.javaClass.declaredMethods.find { it.name == "b" && it.parameterTypes.size == 1 }
                                    bMethod?.invoke(helper, f3501i)
                                    f3501i.visibility = View.VISIBLE
                                } catch (_: Throwable) {
                                    f3501i.visibility = View.GONE
                                }
                            }
                        }
                    }
                    1 -> { // Solid / HEX Color
                        try {
                            invokeHelperMethod(helper, "m")
                        } catch (_: Throwable) {
                        }
                        val alpha = (opacity.coerceIn(10, 100)) / 100.0f
                        val alphaInt = (alpha * 255).toInt().coerceIn(0, 255)
                        f3500h.alpha = 1.0f
                        val parsedColor = try {
                            Color.parseColor(bgColorStr)
                        } catch (_: Throwable) {
                            Color.parseColor("#1E1E2E")
                        }
                        val r = Color.red(parsedColor)
                        val g = Color.green(parsedColor)
                        val b = Color.blue(parsedColor)
                        f3500h.background = ColorDrawable(Color.argb(alphaInt, r, g, b))
                        f3500h.foreground = null
                        f3501i?.visibility = View.GONE
                        f3500h.visibility = View.VISIBLE
                    }
                    2 -> { // Custom Image
                        try {
                            invokeHelperMethod(helper, "m")
                        } catch (_: Throwable) {
                        }
                        val alpha = (opacity.coerceIn(10, 100)) / 100.0f
                        val alphaInt = (alpha * 255).toInt().coerceIn(0, 255)
                        f3500h.alpha = 1.0f
                        val bmp = getOrLoadBitmap(service)
                        if (bmp != null && !bmp.isRecycled) {
                            val drawable = BitmapDrawable(service.resources, bmp)
                            drawable.alpha = alphaInt
                            f3500h.background = drawable
                        }
                        f3500h.foreground = null
                        f3501i?.visibility = View.GONE
                        f3500h.visibility = View.VISIBLE
                    }
                    1, 2 -> restoreCustomBackground(helper, f3500h)
                }

                // 2. Layout Margins on f3500h (aligned to bottom)
                val lp = f3500h.layoutParams as? FrameLayout.LayoutParams
                if (lp != null) {
                    lp.gravity = Gravity.BOTTOM
                    lp.setMargins(hMarginPx, 0, hMarginPx, bMarginPx)
                    f3500h.layoutParams = lp
                }
                // bb.u adds this surface at index 0 as the keyboard background.
                // Any positive Z elevation makes the solid/image rectangle draw
                // above the Compose key layer and obscures the key labels. Always
                // normalize it, including when the configured radius is zero.
                f3500h.elevation = 0f
                f3500h.translationZ = 0f

                // 3. Rounded Corners & Clipping on f3500h (Top corners only)
                // 2. Rounded Corners & Clipping on f3500h (Top corners only)
                if (radiusPx > 0f) {
                    f3500h.clipToOutline = true
                    f3500h.outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, outline: Outline) {
                            val w = v.width
                            val h = v.height
                            if (w <= 0 || h <= 0) return
                            outline.setRoundRect(0, 0, w, h + radiusPx.toInt(), radiusPx)
                        }
                    }
                    f3500h.elevation = if (bgType == 0) 0f else if (hMarginPx > 0 || bMarginPx > 0 || radiusPx > 0f) 16f else 0f
                    f3500h.translationZ = 0f
                    f3500h.invalidateOutline()
                } else {
                    f3500h.clipToOutline = false
                    f3500h.outlineProvider = ViewOutlineProvider.BACKGROUND
                }

                if (bgType == 0) {
                    updateGlassTintLayer(helper, f3500h, isDark, opacity, radiusPx, floating)
                } else {
                    glassTintViews[helper]?.visibility = View.GONE
                }
            } catch (t: Throwable) {
                XposedUtils.logError(module, "KeyboardStyleHook: failed to update material views", t)
            }
        }
    }

    private fun updateBottomBarTransparency(service: android.inputmethodservice.InputMethodService) {
    private fun updateBottomBarAppearance(
        service: android.inputmethodservice.InputMethodService
    ) {
        try {
            val isDark = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val iconColor = if (isDark) Color.parseColor("#9E9E9E") else Color.parseColor("#757575")
            val bgType = ConfigManager.getBgType()
            val customText = parseOptionalColor(ConfigManager.getTextColor())
            val isDark = when (bgType) {
                1 -> isDarkColor(parseOptionalColor(ConfigManager.getBgColor()) ?: Color.WHITE)
                else -> (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
            val iconColor = customText
                ?: if (isDark) Color.parseColor("#E0E0E0") else Color.parseColor("#757575")
            val rippleColor = if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#1F000000")

            // 1. Call InputMethodServiceInjector.customizeBottomViewColor(true, 0, iconColor, rippleColor)
            // 1. HyperOS renders this accessory/navigation strip separately.
            // Setting background to Color.TRANSPARENT allows f3500h (which covers the entire keyboard + bottom bar) to be the unified background.
            val injectorClass = Class.forName("android.inputmethodservice.InputMethodServiceInjector")
            val customizeMethod = injectorClass.declaredMethods.find { it.name == "customizeBottomViewColor" }
            if (customizeMethod != null) {
                customizeMethod.isAccessible = true
                if (customizeMethod.parameterTypes.size == 4) {
                    customizeMethod.invoke(null, true, 0, iconColor, rippleColor)
                    customizeMethod.invoke(null, true, Color.TRANSPARENT, iconColor, rippleColor)
                } else if (customizeMethod.parameterTypes.size == 2) {
                    customizeMethod.invoke(null, true, 0)
                    customizeMethod.invoke(null, true, Color.TRANSPARENT)
                }
            }
        } catch (_: Throwable) {}

        try {
            // 2. Clear background on bottom views in DecorView
            // 2. Recursively clear backgrounds on all bottom container views in DecorView
            val window = service.window?.window
            val decor = window?.decorView as? ViewGroup
            if (decor != null) {
                for (i in 0 until decor.childCount) {
                    val child = decor.getChildAt(i)
                    val className = child.javaClass.name
                    if (className.contains("Bottom", ignoreCase = true) || className.contains("NavigationBar", ignoreCase = true)) {
                        child.background = null
                clearBottomHierarchyBackgrounds(decor)
            }
        } catch (_: Throwable) {}
    }

    private fun clearBottomHierarchyBackgrounds(view: View) {
        val name = try { view.resources.getResourceEntryName(view.id) } catch (_: Throwable) { "" }
        val className = view.javaClass.name
        if (name == "miui_bottom_area" || name == "input_bottom_view" || name == "bottom_view" ||
            name.contains("bottom", ignoreCase = true) ||
            className.contains("Bottom", ignoreCase = true) ||
            className.contains("NavigationBar", ignoreCase = true)
        ) {
            view.background = null
            view.setBackgroundColor(Color.TRANSPARENT)
            view.elevation = 0f
            view.translationZ = 0f
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                clearBottomHierarchyBackgrounds(view.getChildAt(i))
            }
        }
    }

    private fun isBottomBarView(view: View): Boolean {
        val id = view.id
        if (id > 0) {
            val resName = try { view.resources.getResourceEntryName(id) } catch (_: Throwable) { null }
            if (resName != null) {
                if (resName in bottomBarResNames || resName.contains("bottom", ignoreCase = true)) return true
            }
        }
        val clsName = view.javaClass.name
        if (clsName.endsWith("InputMethodBottomView") ||
            clsName.contains("BottomView", ignoreCase = true) ||
            clsName.contains("miui_bottom", ignoreCase = true) ||
            clsName.contains("MiuiBottomArea", ignoreCase = true)
        ) {
            return true
        }
        var current: View? = view.parent as? View
        var depth = 0
        while (current != null && depth < 4) {
            val parentId = current.id
            if (parentId > 0) {
                val parentName = try { current.resources.getResourceEntryName(parentId) } catch (_: Throwable) { null }
                if (parentName != null && (parentName in bottomBarResNames || parentName.contains("bottom", ignoreCase = true))) {
                    return true
                }
            }
            val parentCls = current.javaClass.name
            if (parentCls.endsWith("InputMethodBottomView") ||
                parentCls.contains("BottomView", ignoreCase = true) ||
                parentCls.contains("miui_bottom", ignoreCase = true) ||
                parentCls.contains("MiuiBottomArea", ignoreCase = true)
            ) {
                return true
            }
            current = current.parent as? View
            depth++
        }
        return false
    }

    private fun tryHookInputMethodBottomManager(module: XposedModule, injectorClass: Class<*>) {
        try {
            val sClassLoaderField = injectorClass.getDeclaredField("sClassLoader").apply { isAccessible = true }
            val cl = sClassLoaderField.get(null) as? ClassLoader ?: return
            val bottomManagerClass = try {
                cl.loadClass("com.miui.inputmethod.InputMethodBottomManager")
            } catch (_: Throwable) {
                null
            } ?: return

            makeBottomManagerTransparent(bottomManagerClass)

            if (isBottomManagerHooked) return
            isBottomManagerHooked = true

            val setBottomColorMethod = bottomManagerClass.declaredMethods.find {
                it.name == "setBottomColor" && it.parameterTypes.size == 4
            }
            if (setBottomColorMethod != null) {
                module.hook(setBottomColorMethod).intercept { chain ->
                    val res = if (ConfigManager.isStyleEnabled()) {
                        val iconColor = chain.getArg(2)
                        val rippleColor = chain.getArg(3)
                        val callRes = chain.proceed(arrayOf(true, Color.TRANSPARENT, iconColor, rippleColor))
                        makeBottomManagerTransparent(bottomManagerClass)
                        callRes
                    } else {
                        chain.proceed()
                    }
                    res
                }
            }

            val cancelMecMethod = bottomManagerClass.declaredMethods.find { it.name == "cancelBottomMecSkin" }
            if (cancelMecMethod != null) {
                module.hook(cancelMecMethod).intercept { chain ->
                    val res = chain.proceed()
                    if (ConfigManager.isStyleEnabled()) {
                        makeBottomManagerTransparent(bottomManagerClass)
                    }
                    res
                }
            }

            val refreshSkinMethod = bottomManagerClass.declaredMethods.find { it.name == "refreshBottomSkin" }
            if (refreshSkinMethod != null) {
                module.hook(refreshSkinMethod).intercept { chain ->
                    val res = chain.proceed()
                    if (ConfigManager.isStyleEnabled()) {
                        makeBottomManagerTransparent(bottomManagerClass)
                    }
                    res
                }
            }

            val addInnerMethod = bottomManagerClass.declaredMethods.find { it.name == "addMiuiBottomViewInner" }
            if (addInnerMethod != null) {
                module.hook(addInnerMethod).intercept { chain ->
                    if (ConfigManager.isStyleEnabled()) {
                        makeBottomManagerTransparent(bottomManagerClass)
                    }
                    val res = chain.proceed()
                    if (ConfigManager.isStyleEnabled()) {
                        makeBottomManagerTransparent(bottomManagerClass)
                    }
                    res
                }
            }
            XposedUtils.log(module, "KeyboardStyleHook: Successfully hooked InputMethodBottomManager")
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Failed to hook InputMethodBottomManager", t)
        }
    }

    private fun makeBottomManagerTransparent(bottomManagerClass: Class<*>) {
        try {
            val sBottomViewField = bottomManagerClass.getDeclaredField("sBottomView").apply { isAccessible = true }
            val sBottomView = sBottomViewField.get(null) as? View
            sBottomView?.background = null
            sBottomView?.setBackgroundColor(Color.TRANSPARENT)
            sBottomView?.elevation = 0f
            sBottomView?.translationZ = 0f
        } catch (_: Throwable) {}

        try {
            val sDefBottomViewColorField = bottomManagerClass.getDeclaredField("sDefBottomViewColor").apply { isAccessible = true }
            sDefBottomViewColorField.setInt(null, Color.TRANSPARENT)
        } catch (_: Throwable) {}

        try {
            val currentBottomViewColorField = bottomManagerClass.getDeclaredField("currentBottomViewColor").apply { isAccessible = true }
            currentBottomViewColorField.setInt(null, Color.TRANSPARENT)
        } catch (_: Throwable) {}

        try {
            val helperField = bottomManagerClass.getDeclaredField("sBottomViewHelper").apply { isAccessible = true }
            val helper = helperField.get(null)
            if (helper != null) {
                val areaField = helper.javaClass.getDeclaredField("mMiuiBottomArea").apply { isAccessible = true }
                val area = areaField.get(helper) as? View
                area?.background = null
                area?.setBackgroundColor(Color.TRANSPARENT)
                area?.elevation = 0f
                area?.translationZ = 0f

                val rootField = helper.javaClass.getDeclaredField("mRootView").apply { isAccessible = true }
                val root = rootField.get(helper) as? View
                root?.background = null
                root?.setBackgroundColor(Color.TRANSPARENT)
                root?.elevation = 0f
                root?.translationZ = 0f
            }
        } catch (_: Throwable) {}

        try {
            val sGuideRecImgField = bottomManagerClass.getDeclaredField("sGuideRecImg").apply { isAccessible = true }
            val guideRec = sGuideRecImgField.get(null) as? View
            guideRec?.background = null
            guideRec?.setBackgroundColor(Color.TRANSPARENT)
            guideRec?.elevation = 0f
            guideRec?.translationZ = 0f
        } catch (_: Throwable) {}
    }

    private fun enforceWindowAndBottomBarTransparency(service: android.inputmethodservice.InputMethodService) {
        try {
            val window = service.window?.window
            if (window != null) {
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window.setNavigationBarColor(Color.TRANSPARENT)
                window.setNavigationBarContrastEnforced(false)
                window.setDimAmount(0f)
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val decor = window.decorView as? ViewGroup
                decor?.background = null
                decor?.setBackgroundColor(Color.TRANSPARENT)
                decor?.elevation = 0f
                decor?.translationZ = 0f
            }
        } catch (_: Throwable) {}

        updateBottomBarAppearance(service)

        try {
            val helper = XposedUtils.getObjectField(service, "hyperMaterialHelper")
            if (helper != null) {
                val f3500h = XposedUtils.getObjectField(helper, "h") as? View
                if (f3500h != null) {
                    materialBgViews.add(f3500h)
                    if (ConfigManager.getBgType() == 0) {
                        f3500h.background = null
                        f3500h.setBackgroundColor(Color.TRANSPARENT)
                    }
                    f3500h.elevation = 0f
                    f3500h.translationZ = 0f
                }
                val f3501i = XposedUtils.getObjectField(helper, "i") as? View
                if (f3501i != null) {
                    f3501i.elevation = 0f
                    f3501i.translationZ = 0f
                    if (ConfigManager.getOpacity() == 0 || ConfigManager.getBgType() != 0) {
                        f3501i.visibility = View.GONE
                        f3501i.setRenderEffect(null)
                        f3501i.background = null
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    fun applyStyle(module: XposedModule, service: android.inputmethodservice.InputMethodService, rootView: View) {
        ConfigManager.syncFromProvider(service)
        if (!ConfigManager.isStyleEnabled()) return

        val cornerRadiusDp = ConfigManager.getCornerRadius()
        val opacity = ConfigManager.getOpacity() // 10 to 100
        val bgType = ConfigManager.getBgType() // 0: DYNAMIC_GLASS, 1: COLOR, 2: IMAGE
        val bgColorStr = ConfigManager.getBgColor()
        val marginTopDp = ConfigManager.getMarginTop()
        val marginBottomDp = ConfigManager.getMarginBottom()
        val marginHorizontalDp = ConfigManager.getMarginHorizontal()
        // 1. Synchronous transparent window, decor, and bottom bar pass
        // Executed immediately so no default dark/grey frames are drawn during the slide-up animation.
        try {
            enforceWindowAndBottomBarTransparency(service)

        val density = service.resources.displayMetrics.density
        val topMarginPx = (marginTopDp * density).toInt()
        val bottomMarginPx = (marginBottomDp * density).toInt()
        val horizontalMarginPx = (marginHorizontalDp * density).toInt()
            rootView.background = null
            val targetView: View = if (rootView is ViewGroup && rootView.childCount > 0) {
                rootView.getChildAt(0)
            } else {
                rootView
            }
            targetView.background = null
            targetView.alpha = 1.0f

            val helper = XposedUtils.getObjectField(service, "hyperMaterialHelper")
            if (helper != null) {
                val f3500h = XposedUtils.getObjectField(helper, "h") as? View
                if (f3500h != null) {
                    materialBgViews.add(f3500h)
                    if (ConfigManager.getBgType() == 0) {
                        f3500h.background = null
                        f3500h.setBackgroundColor(Color.TRANSPARENT)
                    }
                    f3500h.elevation = 0f
                    f3500h.translationZ = 0f
                }
                scheduleHyperMaterialRefresh(module, service, helper)
            }
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Error applying synchronous style to keyboard view", t)
        }

        // 2. Post pass for layout settle
        rootView.post {
            try {
                // 1. Transparent window & navigation bar (Do NOT add FLAG_BLUR_BEHIND to window as it blurs the entire screen!)
                val window = service.window?.window
                if (window != null) {
                    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    window.setNavigationBarColor(Color.TRANSPARENT)
                    window.setNavigationBarContrastEnforced(false)
                    window.setDimAmount(0f)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    val decor = window.decorView as? ViewGroup
                    decor?.setBackgroundColor(Color.TRANSPARENT)
                }

                // 2. Make bottom navigation/accessory bar completely transparent
                updateBottomBarTransparency(service)

                // 3. Clear background on full-screen container views so nothing bleeds to top
                enforceWindowAndBottomBarTransparency(service)
                rootView.background = null
                rootView.setPadding(horizontalMarginPx, topMarginPx, horizontalMarginPx, bottomMarginPx)

                val targetView: View = if (rootView is ViewGroup && rootView.childCount > 0) {
                    rootView.getChildAt(0)
                } else {
                    rootView
                }
                targetView.background = null

                // For keyboard foreground keys and text, keep them solid and crisp!
                targetView.alpha = 1.0f

                // 4. Update HyperMaterialHelper's f3500h view which is the true keyboard bottom card
                val helper = XposedUtils.getObjectField(service, "hyperMaterialHelper")
                updateHyperMaterialViews(module, service, helper)

                // Log event occasionally (throttle to once per 5 seconds to avoid flooding)
                val now = System.currentTimeMillis()
                if (now - lastLoggedTime > 5000) {
                    lastLoggedTime = now
                    val bgDesc = when (bgType) {
                        0 -> "动态液态玻璃(HyperOS Glass)"
                        1 -> "纯色($bgColorStr)"
                        2 -> "自定义图片"
                        else -> "动态液态玻璃"
                    }
                    val cornerDesc = "顶部圆角(${cornerRadiusDp}dp)"
                    val marginDesc = "上${marginTopDp}dp / 下${marginBottomDp}dp / 左右${marginHorizontalDp}dp"
                    LogBridge.record(
                        LogType.STYLE,
                        "应用键盘个性化样式",
                        "圆角: $cornerDesc | 透明度: ${opacity}% | 模糊: ${ConfigManager.getBlurRadius()}dp | 边距: $marginDesc | 背景: $bgDesc"
                    )
                }
                scheduleHyperMaterialRefresh(module, service, helper)
            } catch (t: Throwable) {
                XposedUtils.logError(module, "Error applying custom style to keyboard view", t)
                XposedUtils.logError(module, "Error in post style pass", t)
            }
        }
    }
}
