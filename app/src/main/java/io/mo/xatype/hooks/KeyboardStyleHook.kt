package io.mo.xatype.hooks

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import io.github.libxposed.api.XposedModule
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.util.WeakHashMap

object KeyboardStyleHook {

    private var cachedBitmap: Bitmap? = null
    private var cachedImageVersion: Long = -1L
    private val glassTintViews = WeakHashMap<Any, View>()
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
            }
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Error hooking na.m.F0", t)
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
                        forceCurrentPackageIntoMaterialWhitelist(chain.thisObject)
                    }
                    val res = chain.proceed()
                    if (ConfigManager.isStyleEnabled() && ConfigManager.getBgType() == 0) {
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
                        } catch (_: Throwable) {}
                    }
                    result
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.u.e (Dynamic glass blur tuning)")
            }

            // Hook bb.u.b(View): Synchronize RuntimeShader uRadii corner radius on f3501i
            val bMethod = bbUClass.declaredMethods.find { it.name == "b" && it.parameterTypes.size == 1 && it.parameterTypes[0] == View::class.java }
            if (bMethod != null) {
                module.hook(bMethod).intercept { chain ->
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
                    val res = chain.proceed()
                    if (ConfigManager.isStyleEnabled()) {
                        val helper = chain.thisObject
                        val service = XposedUtils.getObjectField(helper, "a") as? android.inputmethodservice.InputMethodService
                        if (service != null) {
                            ConfigManager.syncFromProvider(service)
                            updateHyperMaterialViews(module, service, helper)
                        }
                    }
                    res
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.u.g")
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

                // Hook bb.g1.s(int i5, int i10, int i11, boolean z2): Force bottom view background color to 0 (TRANSPARENT)
                val staticSMethod = bbG1Class.declaredMethods.find { it.name == "s" && it.parameterTypes.size == 4 }
                if (staticSMethod != null) {
                    module.hook(staticSMethod).intercept { chain ->
                        if (ConfigManager.isStyleEnabled()) {
                            val iconColor = chain.getArg(1) as? Int ?: 0
                            val rippleColor = chain.getArg(2) as? Int ?: 0
                            try {
                                val injectorClass = Class.forName("android.inputmethodservice.InputMethodServiceInjector")
                                val customizeMethod = injectorClass.declaredMethods.find { it.name == "customizeBottomViewColor" }
                                if (customizeMethod != null) {
                                    customizeMethod.isAccessible = true
                                    customizeMethod.invoke(null, true, 0, iconColor, rippleColor)
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
    }

    /**
     * bb.u.k() removes both material views when the current editor package is
     * absent from its downloaded whitelist. Add only the active package before
     * that check so the native method keeps the blur views alive.
     */
    private fun forceCurrentPackageIntoMaterialWhitelist(helper: Any) {
        val packageName = XposedUtils.getObjectField(helper, "u") as? String ?: return

        val versions = LinkedHashMap<Any?, Any?>()
        (XposedUtils.getObjectField(helper, "v") as? Map<*, *>)?.forEach { (key, value) ->
            versions[key] = value
        }
        versions[packageName] = 2
        XposedUtils.setObjectField(helper, "v", versions)

        val primaryPackages = LinkedHashSet<Any?>()
        (XposedUtils.getObjectField(helper, "w") as? Set<*>)?.forEach {
            primaryPackages.add(it)
        }
        primaryPackages.add(packageName)
        XposedUtils.setObjectField(helper, "w", primaryPackages)

        val secondaryPackages = LinkedHashSet<Any?>()
        (XposedUtils.getObjectField(helper, "x") as? Set<*>)?.forEach {
            secondaryPackages.add(it)
        }
        secondaryPackages.add(packageName)
        XposedUtils.setObjectField(helper, "x", secondaryPackages)
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
                val blendColors = if (index == 0) {
                    intArrayOf(
                        Color.argb((4 + 106 * strength).toInt(), 255, 255, 255),
                        Color.argb((2 + 68 * strength).toInt(), 246, 249, 255),
                        Color.argb((1 + 39 * strength).toInt(), 187, 205, 232)
                    )
                } else {
                    intArrayOf(
                        Color.argb((5 + 95 * strength).toInt(), 27, 30, 38),
                        Color.argb((2 + 53 * strength).toInt(), 255, 255, 255),
                        Color.argb((1 + 34 * strength).toInt(), 153, 178, 216)
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
    ) {
        val density = view.resources.displayMetrics.density
        val strength = ((opacity.coerceIn(10, 100) - 10) / 90.0f).coerceIn(0f, 1f)
        val radii = if (floating) {
            floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx)
        } else {
            floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
        }

        val softLight = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (isDark) {
                intArrayOf(
                    Color.argb((3 + 67 * strength).toInt(), 255, 255, 255),
                    Color.TRANSPARENT,
                    Color.argb((1 + 34 * strength).toInt(), 111, 151, 205)
                )
            } else {
                intArrayOf(
                    Color.argb((4 + 76 * strength).toInt(), 255, 255, 255),
                    Color.argb((1 + 31 * strength).toInt(), 255, 255, 255),
                    Color.argb((1 + 39 * strength).toInt(), 184, 205, 232)
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

        fun syncLayout(tint: View) {
            val sourceParams = materialView.layoutParams ?: return
            tint.layoutParams = if (sourceParams is FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams(sourceParams)
            } else {
                ViewGroup.LayoutParams(sourceParams)
            }
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
                }
            }
            glassTintViews[helper] = tintView
        } else {
            syncLayout(tintView)
        }

        val strength = ((opacity.coerceIn(10, 100) - 10) / 90.0f).coerceIn(0f, 1f)
        val radii = if (floating) {
            floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx)
        } else {
            floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
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
                                f3501i.visibility = View.GONE
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
                }

                // 2. Layout Margins on f3500h (aligned to bottom)
                val lp = f3500h.layoutParams as? FrameLayout.LayoutParams
                if (lp != null) {
                    lp.gravity = Gravity.BOTTOM
                    lp.setMargins(hMarginPx, 0, hMarginPx, bMarginPx)
                    f3500h.layoutParams = lp
                }

                // 3. Rounded Corners & Clipping on f3500h (Top corners only)
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
        try {
            val isDark = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val iconColor = if (isDark) Color.parseColor("#9E9E9E") else Color.parseColor("#757575")
            val rippleColor = if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#1F000000")

            // 1. Call InputMethodServiceInjector.customizeBottomViewColor(true, 0, iconColor, rippleColor)
            val injectorClass = Class.forName("android.inputmethodservice.InputMethodServiceInjector")
            val customizeMethod = injectorClass.declaredMethods.find { it.name == "customizeBottomViewColor" }
            if (customizeMethod != null) {
                customizeMethod.isAccessible = true
                if (customizeMethod.parameterTypes.size == 4) {
                    customizeMethod.invoke(null, true, 0, iconColor, rippleColor)
                } else if (customizeMethod.parameterTypes.size == 2) {
                    customizeMethod.invoke(null, true, 0)
                }
            }
        } catch (_: Throwable) {}

        try {
            // 2. Clear background on bottom views in DecorView
            val window = service.window?.window
            val decor = window?.decorView as? ViewGroup
            if (decor != null) {
                for (i in 0 until decor.childCount) {
                    val child = decor.getChildAt(i)
                    val className = child.javaClass.name
                    if (className.contains("Bottom", ignoreCase = true) || className.contains("NavigationBar", ignoreCase = true)) {
                        child.background = null
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    fun applyStyle(module: XposedModule, service: android.inputmethodservice.InputMethodService, rootView: View) {
        ConfigManager.syncFromProvider(service)
        if (!ConfigManager.isStyleEnabled()) return

        val marginTopDp = ConfigManager.getMarginTop()
        val marginBottomDp = ConfigManager.getMarginBottom()
        val marginHorizontalDp = ConfigManager.getMarginHorizontal()

        val density = service.resources.displayMetrics.density
        val topMarginPx = (marginTopDp * density).toInt()
        val bottomMarginPx = (marginBottomDp * density).toInt()
        val horizontalMarginPx = (marginHorizontalDp * density).toInt()

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
            } catch (t: Throwable) {
                XposedUtils.logError(module, "Error applying custom style to keyboard view", t)
            }
        }
    }
}
