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
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object KeyboardStyleHook {

    private var cachedBitmap: Bitmap? = null
    private var cachedImageVersion: Long = -1L
    @Volatile private var activeBottomBarColor: Int = Color.TRANSPARENT
    @Volatile private var activeSteadyBottomBarColor: Int = Color.TRANSPARENT
    @Volatile private var activeImeWindow: Window? = null
    @Volatile private var bottomTransitionGuardUntil: Long = 0L
    @Volatile private var hideMaterialDuringBottomTransition: Boolean = false
    private val bottomDiagSequence = AtomicInteger(0)
    private val toolbarDiagCount = AtomicInteger(0)
    private val decorNavigationGuardDiagnosticCount = AtomicInteger(0)
    private val preserveDynamicGlassCleanup = ThreadLocal<Boolean>()
    private val dynamicGlassSurfaceHoldGeneration = AtomicInteger(0)
    private val dynamicGlassSurfaceHoldDiagnosticCount = AtomicInteger(0)
    @Volatile private var dynamicGlassContentHoldActive = false
    @Volatile private var dynamicGlassHiddenBufferPrimed = false
    private val dynamicGlassHeldContentViews = WeakHashMap<View, Boolean>()
    @Volatile private var dynamicGlassSurfacePrimer: Any? = null
    @Volatile private var dynamicGlassSurfacePrimerParent: Any? = null
    private var dynamicGlassTrackedMaterial: View? = null
    private var dynamicGlassGeometryObserver: ViewTreeObserver? = null
    private var dynamicGlassPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var dynamicGlassDetachListener: View.OnAttachStateChangeListener? = null
    private var dynamicGlassGeometry: GlassGeometry? = null
    private data class GlassGeometry(
        val x: Int, val y: Int, val width: Int, val height: Int,
        val decorHeight: Int, val shown: Boolean
    )
    private const val BOTTOM_TRANSITION_GUARD_MS = 420L
    private const val DYNAMIC_GLASS_FIRST_FRAME_MAX_OPACITY = 10
    private const val NAV_BAR_BACKGROUND_APPEARANCE_MASK = 2 or 64
    // na.d is a data-style class whose hashCode includes these mutable fields,
    // so identity keys are required to keep restoration reliable after patching.
    private val originalAppsPanelColors = IdentityHashMap<Any, Map<String, Long>>()
    private val materialRefreshGenerations = WeakHashMap<View, Int>()
    private val clipboardAdapterHooks = ConcurrentHashMap.newKeySet<Class<*>>()
    private val clipboardAppliedBackgrounds = WeakHashMap<View, AppliedClipboardBackground>()

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val imeServiceClass = XposedUtils.findClass("com.mi.ime.MiInputMethodService", classLoader)
        if (imeServiceClass == null) {
            XposedUtils.logError(module, "MiInputMethodService class not found for KeyboardStyleHook", null)
            return
        }

        installClipboardPopupHook(module)
        installImeWindowTransitionHooks(module)

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

        // Xiaomi tears down both HyperMaterial views from onWindowHidden().
        // Recreating them before the next show is still too late for the
        // already-cached first IME buffer. Keep the live glass layers attached
        // while the window is hidden; the hide transition guard still makes
        // them invisible until the leash has completely left the screen.
        val onWindowHiddenMethod = XposedUtils.findMethodExact(imeServiceClass, "onWindowHidden")
        if (onWindowHiddenMethod != null) {
            module.hook(onWindowHiddenMethod).intercept { chain ->
                val service = chain.thisObject as? android.inputmethodservice.InputMethodService
                if (service != null) ConfigManager.syncFromProvider(service)
                val preserve = ConfigManager.isStyleEnabled() && ConfigManager.getBgType() == 0
                if (preserve) preserveDynamicGlassCleanup.set(true)
                try {
                    chain.proceed()
                } finally {
                    if (preserve) preserveDynamicGlassCleanup.remove()
                }
            }
            XposedUtils.log(module, "KeyboardStyleHook: Hooked onWindowHidden glass retention")
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
            XposedUtils.logError(module, "Error hooking Compose style tokens", t)
        }

        // 6. Hook HyperMaterial Helper support & package whitelist bypass
        val bbUClass = XposedUtils.findClass("bb.u", classLoader)
        if (bbUClass != null) {
            // During onWindowHidden bb.u.f/m/n(false) remove the material
            // views, clear their Surface blur and mark them GONE. Suppress only
            // those cleanup calls for dynamic glass. Calls made while disabling
            // the feature or changing modes retain Xiaomi's normal behavior.
            listOf("f", "m").forEach { name ->
                val cleanupMethod = bbUClass.declaredMethods.find {
                    it.name == name && it.parameterTypes.isEmpty()
                }
                if (cleanupMethod != null) {
                    module.hook(cleanupMethod).intercept { chain ->
                        if (preserveDynamicGlassCleanup.get() == true) {
                            null
                        } else {
                            // m() also runs during compositor takeover; only f()
                            // destroys the keyboard material and its ownership.
                            if (name == "f") removeDynamicGlassSurfacePrimer()
                            chain.proceed()
                        }
                    }
                }
            }

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
                    // bb.u.k() reruns whenever the editor package changes. The
                    // material views are also the host for solid/image drawables,
                    // so keep them alive for every enabled custom background,
                    // not only for dynamic glass.
                    if (ConfigManager.isStyleEnabled()) {
                        forceCurrentPackageIntoMaterialWhitelist(chain.thisObject)
                    }
                    val res = chain.proceed()
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
                    val result = chain.proceed()
                    useCompositorGlassForTransparentMaterial(module, chain.thisObject, chain.getArg(0) as? View)
                    if (isToolbarTransitionGuardActive()) {
                        forceHyperMaterialLayersInvisible(chain.thisObject)
                    }
                    if (ConfigManager.isStyleEnabled() && ConfigManager.getBgType() != 0) {
                        val helper = chain.thisObject
                        val materialView = chain.getArg(0) as? View
                        if (materialView != null) {
                            materialView.post {
                                restoreCustomBackground(helper, materialView)
                            }
                            materialView.postDelayed({
                                restoreCustomBackground(helper, materialView)
                            }, 48L)
                        }
                    }
                    result
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.u.c (Restore custom background)")
            }

            // Hook bb.u.b(View): Synchronize RuntimeShader uRadii corner radius on f3501i
            val bMethod = bbUClass.declaredMethods.find { it.name == "b" && it.parameterTypes.size == 1 && it.parameterTypes[0] == View::class.java }
            if (bMethod != null) {
                module.hook(bMethod).intercept { chain ->
                    val res = chain.proceed()
                    if (isToolbarTransitionGuardActive()) {
                        forceHyperMaterialLayersInvisible(chain.thisObject)
                    }
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

            // Xiaomi toggles both material views back to VISIBLE from bb.u.n(true)
            // while the IME leash is already moving.  That write happens between
            // our scheduled snapshots and exposes the opaque dark fallback for a
            // single frame.  Keep the layers hidden continuously for the short
            // transition guard; the normal state is restored when the guard ends.
            val nMethod = bbUClass.declaredMethods.find {
                it.name == "n" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }
            if (nMethod != null) {
                module.hook(nMethod).intercept { chain ->
                    if (
                        preserveDynamicGlassCleanup.get() == true &&
                        chain.getArg(0) == false
                    ) {
                        null
                    } else if (isToolbarTransitionGuardActive() && chain.getArg(0) == true) {
                        val result = chain.proceed(arrayOf(false))
                        forceHyperMaterialLayersInvisible(chain.thisObject)
                        result
                    } else {
                        chain.proceed()
                    }
                }
                XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.u.n (transition visibility guard)")
            }

            // Hook bb.u.g(boolean, FrameLayout, int): Update views on attach
            val gMethod = bbUClass.declaredMethods.find { it.name == "g" }
            if (gMethod != null) {
                module.hook(gMethod).intercept { chain ->
                    // Floating mode owns a different PopupWindow. Its native
                    // material must not leave the docked IME effect layer alive.
                    if (chain.getArg(0) == true) removeDynamicGlassSurfacePrimer()
                    val res = chain.proceed()
                    if (ConfigManager.isStyleEnabled()) {
                        val helper = chain.thisObject
                        val service = XposedUtils.getObjectField(helper, "a") as? android.inputmethodservice.InputMethodService
                        if (service != null) {
                            ConfigManager.syncFromProvider(service)
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
                    val tracksTransparentGeometry = shouldKeepGlassNavigationTransparent() &&
                        ConfigManager.getOpacity() == 0 && dynamicGlassSurfacePrimer != null &&
                        dynamicGlassTrackedMaterial === XposedUtils.getObjectField(chain.thisObject, "h")
                    // o() runs throughout Compose's translation-card animation.
                    // For fully transparent glass, native layout + pre-draw
                    // geometry sync suffice; do not reapply all material tokens
                    // and enqueue 64/240ms settle passes on every animation frame.
                    if (ConfigManager.isStyleEnabled() && !tracksTransparentGeometry) {
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
                        val g1Obj = chain.thisObject
                        val bField = XposedUtils.getObjectField(g1Obj, "b")
                        val service = bField?.let { XposedUtils.getObjectField(it, "b") } as?
                            android.inputmethodservice.InputMethodService
                        if (service != null) {
                            logBottomSnapshot(
                                module,
                                service,
                                bottomDiagSequence.get(),
                                "bb.g1.S:enter args=${(0 until 3).joinToString { chain.getArg(it).toString() }}"
                            )
                        }
                        val res = chain.proceed()
                        if (ConfigManager.isStyleEnabled()) {
                            if (service != null) {
                                    val window = service.window?.window
                                    activeImeWindow = window
                                    window?.setNavigationBarColor(activeBottomBarColor)
                                    window?.setNavigationBarContrastEnforced(false)
                            }
                        }
                        if (service != null) {
                            logBottomSnapshot(
                                module,
                                service,
                                bottomDiagSequence.get(),
                                "bb.g1.S:exit"
                            )
                        }
                        res
                    }
                    XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.g1.S")
                }

                // Hook bb.g1.s(int i5, int i10, int i11, boolean z2): keep
                // HyperOS' separate bottom view continuous with the glass card.
                val staticSMethod = bbG1Class.declaredMethods.find { it.name == "s" && it.parameterTypes.size == 4 }
                if (staticSMethod != null) {
                    module.hook(staticSMethod).intercept { chain ->
                        if (ConfigManager.isStyleEnabled()) {
                            if (ConfigManager.isVerboseLogEnabled()) {
                                XposedUtils.log(
                                    module,
                                    "[BottomDiag] bb.g1.s requested=${colorHex(chain.getArg(0) as? Int ?: 0)} " +
                                        "icon=${colorHex(chain.getArg(1) as? Int ?: 0)} " +
                                        "ripple=${colorHex(chain.getArg(2) as? Int ?: 0)} " +
                                        "custom=${chain.getArg(3)} configured=${colorHex(activeSteadyBottomBarColor)} " +
                                        "pluginBefore=${readPluginBottomSnapshot()}"
                                )
                            }
                            val iconColor = chain.getArg(1) as? Int ?: 0
                            val rippleColor = chain.getArg(2) as? Int ?: 0
                            try {
                                val injectorClass = Class.forName("android.inputmethodservice.InputMethodServiceInjector")
                                val customizeMethod = injectorClass.declaredMethods.find { it.name == "customizeBottomViewColor" }
                                if (customizeMethod != null) {
                                    customizeMethod.isAccessible = true
                                    // The opaque transition guard belongs only to
                                    // PhoneWindow's navigation surface. Applying it
                                    // to MIUI's in-keyboard bottom view leaves a
                                    // pale band until the next input event redraws
                                    // the plugin.
                                    customizeMethod.invoke(null, true, activeSteadyBottomBarColor, iconColor, rippleColor)
                                }
                            } catch (_: Throwable) {}
                            if (ConfigManager.isVerboseLogEnabled()) {
                                XposedUtils.log(
                                    module,
                                    "[BottomDiag] bb.g1.s pluginAfter=${readPluginBottomSnapshot()}"
                                )
                            }
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                    XposedUtils.log(module, "KeyboardStyleHook: Hooked bb.g1.s (BottomView glass background)")
                }
            }
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Error hooking bb.g1", t)
        }
    }

    /**
     * InputMethodService submits the insets animation before onWindowShown /
     * onWindowHidden are dispatched. Color the system-owned strip on both
     * sides of that submission so WindowManager never snapshots the native
     * black fallback during an IME transition.
     */
    private fun installImeWindowTransitionHooks(module: XposedModule) {
        try {
            val inputMethodServiceClass = android.inputmethodservice.InputMethodService::class.java
            val showWindowMethod = inputMethodServiceClass.getDeclaredMethod(
                "showWindow",
                Boolean::class.javaPrimitiveType ?: java.lang.Boolean.TYPE
            )
            module.hook(showWindowMethod).intercept { chain ->
                val service = chain.thisObject as? android.inputmethodservice.InputMethodService
                val sequence = bottomDiagSequence.incrementAndGet()
                var surfaceHoldGeneration: Int? = null
                if (service != null) {
                    beginBottomTransitionGuard(hideMaterial = false)
                    prepareDynamicGlassBeforeShow(module, service)
                    surfaceHoldGeneration = armDynamicGlassWindowForFirstCommit(module, service)
                    logBottomSnapshot(module, service, sequence, "show:enter")
                    synchronizeBottomBarBeforeTransition(service)
                    logBottomSnapshot(module, service, sequence, "show:after-pre-sync")
                }
                val result = chain.proceed()
                if (service != null) {
                    surfaceHoldGeneration?.let {
                        reassertDynamicGlassWindowHold(module, service, it)
                    }
                    beginBottomTransitionGuard(hideMaterial = false)
                    logBottomSnapshot(module, service, sequence, "show:after-native")
                    synchronizeBottomBarBeforeTransition(service)
                    logBottomSnapshot(module, service, sequence, "show:exit")
                    scheduleBottomSnapshots(module, service, sequence, "show")
                }
                result
            }

            val hideWindowMethod = inputMethodServiceClass.getDeclaredMethod("hideWindow")
            module.hook(hideWindowMethod).intercept { chain ->
                val service = chain.thisObject as? android.inputmethodservice.InputMethodService
                val sequence = bottomDiagSequence.incrementAndGet()
                if (service != null) {
                    beginBottomTransitionGuard(hideMaterial = true)
                    logBottomSnapshot(module, service, sequence, "hide:enter")
                    synchronizeBottomBarBeforeTransition(service)
                    logBottomSnapshot(module, service, sequence, "hide:after-pre-sync")
                }
                val result = chain.proceed()
                if (service != null) {
                    beginBottomTransitionGuard(hideMaterial = true)
                    logBottomSnapshot(module, service, sequence, "hide:after-native")
                    synchronizeBottomBarBeforeTransition(service)
                    logBottomSnapshot(module, service, sequence, "hide:exit")
                    scheduleBottomSnapshots(module, service, sequence, "hide")
                }
                result
            }

            installNavigationBarWriteDiagnostics(module)
            installSystemBarsAppearanceGuard(module)
            installDecorNavigationColorGuard(module)
            XposedUtils.log(module, "KeyboardStyleHook: Hooked IME show/hide transition boundaries")
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Error hooking IME window transitions", t)
        }
    }

    /**
     * Device A/B captures reproduce the moving dark band with Xiaomi material
     * alone, but not with compositor blur alone. Switch only low-opacity glass;
     * keep native material if the replacement surface is not ready.
     */
    private fun useCompositorGlassForTransparentMaterial(
        module: XposedModule,
        helper: Any,
        material: View?
    ) {
        // bb.u.c(View) is also reused by applyClipboardPopupStyle for
        // inside_view in a different PopupWindow. Its (0,0) coordinates must
        // never reposition the IME's compositor layer, nor trigger m(), whose
        // cleanup targets helper.h/i rather than the supplied popup view.
        val keyboardMaterial = XposedUtils.getObjectField(helper, "h") as? View
        if (material == null || material !== keyboardMaterial) {
            if (material != null && ConfigManager.isVerboseLogEnabled()) {
                XposedUtils.log(module, "[BottomDiag] compositor skipped non-keyboard material " +
                    "size=${material.width}x${material.height}")
            }
            return
        }
        if (!ConfigManager.isStyleEnabled() || ConfigManager.getBgType() != 0 ||
            ConfigManager.getOpacity() > DYNAMIC_GLASS_FIRST_FRAME_MAX_OPACITY ||
            ConfigManager.getBlurRadius() <= 0
        ) {
            removeDynamicGlassSurfacePrimer()
            return
        }
        val service = XposedUtils.getObjectField(helper, "a") as?
            android.inputmethodservice.InputMethodService ?: return
        if (!material.isAttachedToWindow || material.width <= 0 || material.height <= 0) return
        try {
            if (ensureDynamicGlassSurfacePrimer(module, service, material)) {
                invokeHelperMethod(helper, "m")
            }
        } catch (t: Throwable) {
            XposedUtils.logError(module, "KeyboardStyleHook: compositor glass fallback failed", t)
        }
    }

    /**
     * Pass-window blur is latched by SurfaceFlinger with the first new IME
     * buffer. The insets animation can expose keyboard content one vsync
     * earlier, so keep content transparent while the material layer primes.
     */
    private fun armDynamicGlassWindowForFirstCommit(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService
    ): Int? {
        val ineligible =
            !ConfigManager.isStyleEnabled() ||
            ConfigManager.getBgType() != 0 ||
            ConfigManager.getOpacity() > DYNAMIC_GLASS_FIRST_FRAME_MAX_OPACITY ||
            ConfigManager.getBlurRadius() <= 0
        if (ineligible) {
            if (dynamicGlassHiddenBufferPrimed) {
                releaseDynamicGlassHeldContent(service)
            }
            removeDynamicGlassSurfacePrimer()
            return null
        }
        val inputRoot = XposedUtils.getObjectField(service, "currentImeRootView") as? View
            ?: return null
        val generation = dynamicGlassSurfaceHoldGeneration.incrementAndGet()
        dynamicGlassHiddenBufferPrimed = false
        dynamicGlassContentHoldActive = true
        holdDynamicGlassContentView(inputRoot)

        if (dynamicGlassSurfaceHoldDiagnosticCount.getAndIncrement() < 8) {
            XposedUtils.log(
                module,
                "[BottomDiag] dynamic glass IME Surface held until first commit gen=$generation"
            )
        }
        return generation
    }

    private fun reassertDynamicGlassWindowHold(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        generation: Int
    ) {
        if (dynamicGlassSurfaceHoldGeneration.get() != generation) return
        val decor = service.window?.window?.decorView ?: return
        val inputRoot = XposedUtils.getObjectField(service, "currentImeRootView") as? View
        if (inputRoot == null) {
            XposedUtils.logError(
                module,
                "KeyboardStyleHook: failed to reassert dynamic glass Surface hold"
            )
        } else {
            holdDynamicGlassContentView(inputRoot)
        }

        // Register only after InputMethodService.showWindow() has completed.
        // Registering during the pre-show material pass can consume the callback
        // on an off-screen warm-up frame and release the content too early.
        try {
            decor.viewTreeObserver.registerFrameCommitCallback {
                decor.postDelayed(
                    {
                        restoreDynamicGlassWindowHold(
                            service,
                            generation,
                            "visible-frame-commit+34ms"
                        )
                    },
                    34L
                )
            }
            decor.postInvalidateOnAnimation()
        } catch (_: Throwable) {}
        decor.postDelayed(
            { restoreDynamicGlassWindowHold(service, generation, "fallback") },
            200L
        )
    }

    private fun restoreDynamicGlassWindowHold(
        service: android.inputmethodservice.InputMethodService,
        generation: Int,
        reason: String
    ) {
        if (dynamicGlassSurfaceHoldGeneration.get() != generation) return
        dynamicGlassContentHoldActive = false
        dynamicGlassHiddenBufferPrimed = false
        releaseDynamicGlassHeldContent(service)
        dynamicGlassSurfaceHoldGeneration.compareAndSet(generation, generation + 1)
        if (dynamicGlassSurfaceHoldDiagnosticCount.getAndIncrement() < 8) {
            Log.i(
                "XiaoAiTypeUnblock",
                "[BottomDiag] dynamic glass IME content restored reason=$reason gen=$generation"
            )
        }
        // Keep the effect layer attached while the IME parent surface is
        // hidden below the display. Recreating it before every show is still
        // one SurfaceFlinger latch too late; a retained layer is already warm
        // when WindowManager exposes the animation leash on the next show.
    }

    private fun releaseDynamicGlassHeldContent(
        service: android.inputmethodservice.InputMethodService
    ) {
        dynamicGlassContentHoldActive = false
        dynamicGlassHiddenBufferPrimed = false
        val currentInputRoot = XposedUtils.getObjectField(service, "currentImeRootView") as? View
        val heldViews = synchronized(dynamicGlassHeldContentViews) {
            dynamicGlassHeldContentViews.keys.toList().also {
                dynamicGlassHeldContentViews.clear()
            }
        }
        (heldViews + listOfNotNull(currentInputRoot))
            .distinct()
            .forEach { it.alpha = 1f }
    }

    private fun holdDynamicGlassContentView(view: View) {
        synchronized(dynamicGlassHeldContentViews) {
            dynamicGlassHeldContentViews[view] = true
        }
        view.alpha = 0f
    }

    private fun trackDynamicGlassGeometry(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        material: View
    ) {
        if (dynamicGlassTrackedMaterial === material) return
        stopTrackingDynamicGlassGeometry()
        dynamicGlassTrackedMaterial = material
        val observer = material.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener {
            val decor = service.window?.window?.decorView
            val helper = XposedUtils.getObjectField(service, "hyperMaterialHelper")
            if (!shouldKeepGlassNavigationTransparent() || !material.isAttachedToWindow ||
                material.rootView !== decor || helper == null || XposedUtils.getObjectField(helper, "h") !== material
            ) {
                removeDynamicGlassSurfacePrimer()
            } else if (decor != null) {
                val position = IntArray(2)
                material.getLocationInWindow(position)
                val geometry = GlassGeometry(position[0], position[1], material.width,
                    material.height, decor.height, material.isShown)
                if (geometry != dynamicGlassGeometry) {
                    ensureDynamicGlassSurfacePrimer(module, service, material, syncWithDraw = true)
                }
            }
            true
        }
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                if (dynamicGlassTrackedMaterial === view) removeDynamicGlassSurfacePrimer()
            }
        }
        dynamicGlassGeometryObserver = observer
        dynamicGlassPreDrawListener = listener
        dynamicGlassDetachListener = detachListener
        observer.addOnPreDrawListener(listener)
        material.addOnAttachStateChangeListener(detachListener)
    }

    private fun stopTrackingDynamicGlassGeometry() {
        val observer = dynamicGlassGeometryObserver
        dynamicGlassPreDrawListener?.let { if (observer?.isAlive == true) observer.removeOnPreDrawListener(it) }
        dynamicGlassDetachListener?.let { dynamicGlassTrackedMaterial?.removeOnAttachStateChangeListener(it) }
        dynamicGlassTrackedMaterial = null
        dynamicGlassGeometryObserver = null
        dynamicGlassPreDrawListener = null
        dynamicGlassDetachListener = null
        dynamicGlassGeometry = null
    }

    private fun ensureDynamicGlassSurfacePrimer(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        material: View,
        syncWithDraw: Boolean = false
    ): Boolean {
        try {
            val decor = service.window?.window?.decorView ?: return false
            // Coordinates below are local to this exact window, not screen
            // coordinates. Reject popup roots even if they share the service.
            if (!material.isAttachedToWindow || material.rootView !== decor) return false
            val getViewRootImpl = View::class.java.getDeclaredMethod("getViewRootImpl").apply {
                isAccessible = true
            }
            val viewRoot = getViewRootImpl.invoke(decor) ?: return false
            val parent = XposedUtils.getObjectField(viewRoot, "mSurfaceControl") ?: return false
            val surfaceClass = Class.forName("android.view.SurfaceControl")
            val validMethod = surfaceClass.getDeclaredMethod("isValid").apply { isAccessible = true }
            if (validMethod.invoke(parent) != true) return false
            var primer = dynamicGlassSurfacePrimer
            if (
                primer == null ||
                dynamicGlassSurfacePrimerParent !== parent ||
                validMethod.invoke(primer) != true
            ) {
                removeDynamicGlassSurfacePrimer()
                val builderClass = Class.forName("android.view.SurfaceControl\$Builder")
                val builder = builderClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                builderClass.getDeclaredMethod("setName", String::class.java)
                    .apply { isAccessible = true }
                    .invoke(builder, "XaTypeGlassPrimer")
                builderClass.getDeclaredMethod("setEffectLayer")
                    .apply { isAccessible = true }
                    .invoke(builder)
                builderClass.getDeclaredMethod("setParent", surfaceClass)
                    .apply { isAccessible = true }
                    .invoke(builder, parent)
                primer = builderClass.getDeclaredMethod("build")
                    .apply { isAccessible = true }
                    .invoke(builder)
                dynamicGlassSurfacePrimer = primer
                dynamicGlassSurfacePrimerParent = parent
            }

            val location = IntArray(2)
            material.getLocationInWindow(location)
            val cornerRadiusPx = ConfigManager.getCornerRadius() * material.resources.displayMetrics.density
            // Docked keyboards only round their top corners. Move the lower
            // corners below the window edge; floating materials keep all four.
            val cropHeight = material.height + if (location[1] + material.height >= decor.height) {
                kotlin.math.ceil(cornerRadiusPx.toDouble()).toInt()
            } else 0
            val transactionClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val transaction = transactionClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            // ViewRootImpl reuses the Java SurfaceControl wrapper when its
            // native surface changes. Object identity cannot detect that; an
            // old child otherwise remains orphaned in SF's offscreen hierarchy.
            transactionClass.getDeclaredMethod("reparent", surfaceClass, surfaceClass)
                .apply { isAccessible = true }
                .invoke(transaction, primer, parent)
            transactionClass.getDeclaredMethod(
                "setLayer",
                surfaceClass,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }.invoke(transaction, primer, -1)
            transactionClass.getDeclaredMethod(
                "setPosition",
                surfaceClass,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            ).apply { isAccessible = true }.invoke(
                transaction,
                primer,
                location[0].toFloat(),
                location[1].toFloat()
            )
            transactionClass.getDeclaredMethod(
                "setWindowCrop",
                surfaceClass,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }.invoke(transaction, primer, material.width, cropHeight)
            transactionClass.getDeclaredMethod(
                "setCornerRadius",
                surfaceClass,
                Float::class.javaPrimitiveType
            ).apply { isAccessible = true }.invoke(
                transaction,
                primer,
                cornerRadiusPx
            )
            transactionClass.getDeclaredMethod(
                "setBackgroundBlurRadius",
                surfaceClass,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }.invoke(
                transaction,
                primer,
                (ConfigManager.getBlurRadius() * material.resources.displayMetrics.density + 0.5f)
                    .toInt().coerceIn(0, 400)
            )
            transactionClass.getDeclaredMethod(
                if (material.isShown && material.width > 0 && material.height > 0) "show" else "hide",
                surfaceClass
            )
                .apply { isAccessible = true }
                .invoke(transaction, primer)
            if (syncWithDraw) {
                // Merge geometry with the buffer from this traversal, rather
                // than moving blur one compositor frame ahead of the card.
                viewRoot.javaClass.getMethod("applyTransactionOnDraw", transactionClass)
                    .invoke(viewRoot, transaction)
            } else {
                transactionClass.getDeclaredMethod("apply").invoke(transaction)
            }
            trackDynamicGlassGeometry(module, service, material)
            val geometry = GlassGeometry(location[0], location[1], material.width,
                material.height, decor.height, material.isShown)
            if (geometry != dynamicGlassGeometry && ConfigManager.isVerboseLogEnabled()) {
                XposedUtils.log(module, "[BottomDiag] glass Surface geometry " +
                    "bounds=${material.width}x${material.height}@${location[0]},${location[1]} " +
                    "shown=${material.isShown} drawSync=$syncWithDraw")
            }
            dynamicGlassGeometry = geometry
            (transaction as? AutoCloseable)?.close()
            return true
        } catch (t: Throwable) {
            XposedUtils.logError(module, "KeyboardStyleHook: glass Surface primer failed", t)
            removeDynamicGlassSurfacePrimer()
            return false
        }
    }

    private fun removeDynamicGlassSurfacePrimer() {
        stopTrackingDynamicGlassGeometry()
        val primer = dynamicGlassSurfacePrimer ?: return
        dynamicGlassSurfacePrimer = null
        dynamicGlassSurfacePrimerParent = null
        try {
            val surfaceClass = Class.forName("android.view.SurfaceControl")
            val transactionClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val transaction = transactionClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            transactionClass.getDeclaredMethod("remove", surfaceClass)
                .apply { isAccessible = true }
                .invoke(transaction, primer)
            transactionClass.getDeclaredMethod("apply")
                .apply { isAccessible = true }
                .invoke(transaction)
            (transaction as? AutoCloseable)?.close()
            surfaceClass.getDeclaredMethod("release").invoke(primer)
        } catch (_: Throwable) {}
    }

    /**
     * The IME surface remains attached and translated below the display after
     * hide. Commit a glass-only buffer there so the next show animation cannot
     * reuse the previous sharp keyboard buffer for its first frame.
     */
    private fun primeDynamicGlassHiddenBuffer(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService
    ) {
        if (
            !ConfigManager.isStyleEnabled() ||
            ConfigManager.getBgType() != 0 ||
            ConfigManager.getOpacity() > DYNAMIC_GLASS_FIRST_FRAME_MAX_OPACITY ||
            ConfigManager.getBlurRadius() <= 0
        ) return
        val inputRoot = XposedUtils.getObjectField(service, "currentImeRootView") as? View
            ?: return
        dynamicGlassContentHoldActive = true
        dynamicGlassHiddenBufferPrimed = true
        holdDynamicGlassContentView(inputRoot)
        inputRoot.invalidate()
        val decor = service.window?.window?.decorView
        decor?.postInvalidateOnAnimation()
        var forcedCommit = false
        if (decor != null) {
            try {
                val getViewRootImpl = View::class.java.getDeclaredMethod("getViewRootImpl").apply {
                    isAccessible = true
                }
                val viewRoot = getViewRootImpl.invoke(decor)
                val performDraw = viewRoot?.javaClass?.declaredMethods?.firstOrNull {
                    it.name == "performDraw" && it.parameterTypes.size == 1
                }?.apply { isAccessible = true }
                forcedCommit = performDraw?.invoke(viewRoot, null) == true
            } catch (t: Throwable) {
                XposedUtils.logError(module, "KeyboardStyleHook: hidden glass buffer commit failed", t)
            }
        }
        XposedUtils.log(
            module,
            "[BottomDiag] dynamic glass hidden buffer draw committed=$forcedCommit"
        )
        if (dynamicGlassSurfaceHoldDiagnosticCount.getAndIncrement() < 8) {
            XposedUtils.log(module, "[BottomDiag] dynamic glass hidden buffer primed")
        }
    }

    private fun synchronizeBottomBarBeforeTransition(
        service: android.inputmethodservice.InputMethodService
    ) {
        ConfigManager.syncFromProvider(service)
        if (!ConfigManager.isStyleEnabled()) return
        applyBottomBarAppearanceImmediately(service)
        applyImeToolbarTransitionGuard(service)
    }

    /**
     * Commit pass-window blur before InputMethodService exposes its surface.
     * The normal style path posts this work behind layout, which is correct for
     * creation but too late for a reused keyboard window: WindowManager can
     * snapshot several raw-transparent animation frames before that post runs.
     */
    private fun prepareDynamicGlassBeforeShow(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService
    ) {
        ConfigManager.syncFromProvider(service)
        if (!ConfigManager.isStyleEnabled() || ConfigManager.getBgType() != 0) return
        val helper = XposedUtils.getObjectField(service, "hyperMaterialHelper") ?: return
        var material = XposedUtils.getObjectField(helper, "h") as? View

        // Xiaomi removes the material view in onWindowHidden(). On the next
        // show, waiting for onWindowShown() to rebuild it leaves the first
        // animated buffer raw-transparent. Recreate the native material layer
        // while the IME window is still hidden so its first visible buffer
        // already contains a live blur region.
        if (material == null || !material.isAttachedToWindow || material.width <= 0 || material.height <= 0) {
            try {
                val reapply = service.javaClass.declaredMethods.firstOrNull {
                    it.name == "reapplyHyperMaterialState" &&
                        it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
                }
                reapply?.isAccessible = true
                reapply?.invoke(service, false)
                material = XposedUtils.getObjectField(helper, "h") as? View
            } catch (t: Throwable) {
                XposedUtils.logError(module, "KeyboardStyleHook: pre-show material rebuild failed", t)
            }
        }

        val readyMaterial = material ?: return
        if (!readyMaterial.isAttachedToWindow || readyMaterial.width <= 0 || readyMaterial.height <= 0) return
        try {
            readyMaterial.setBackgroundColor(Color.TRANSPARENT)
            readyMaterial.alpha = 1f
            readyMaterial.visibility = View.VISIBLE
            readyMaterial.elevation = 0f
            readyMaterial.translationZ = 0f
            updateCachedGlassTokens(
                helper,
                ConfigManager.getBlurRadius(),
                ConfigManager.getOpacity()
            )
            invokeHelperMethod(helper, "c", readyMaterial)
            (XposedUtils.getObjectField(helper, "i") as? View)?.let { rim ->
                invokeHelperMethod(helper, "b", rim)
                rim.visibility = View.VISIBLE
            }
            readyMaterial.invalidate()
            if (
                ConfigManager.getOpacity() <= DYNAMIC_GLASS_FIRST_FRAME_MAX_OPACITY &&
                ConfigManager.getBlurRadius() > 0
            ) {
                ensureDynamicGlassSurfacePrimer(module, service, readyMaterial)
            }
            XposedUtils.log(
                module,
                "[BottomDiag] dynamic glass committed before show " +
                    "size=${readyMaterial.width}x${readyMaterial.height} blur=${ConfigManager.getBlurRadius()}"
            )
        } catch (t: Throwable) {
            XposedUtils.logError(module, "KeyboardStyleHook: pre-show glass commit failed", t)
        }
    }

    /**
     * The leading/trailing edge visible in the Surface animation is Xiaomi's
     * transparent toolbar band, not the system navigation bar.  Fill only that
     * narrow band while the leash moves; filling DecorView would create the
     * large white curtain seen in earlier builds.
     */
    private fun applyImeToolbarTransitionGuard(
        service: android.inputmethodservice.InputMethodService
    ) {
        if (
            !hideMaterialDuringBottomTransition ||
            SystemClock.uptimeMillis() >= bottomTransitionGuardUntil ||
            ConfigManager.getOpacity() >= 100
        ) return
        val root = XposedUtils.getObjectField(service, "currentImeRootView") as? View ?: return
        val target = if (root is ViewGroup && root.childCount > 0) root.getChildAt(0) else root
        val contentTop = resolveKeyboardContentTop(service, target)
        // h/i are background-only layers inserted below the Compose keyboard.
        // h starts life with Xiaomi's opaque #18191B fallback and is resized
        // during the IME leash animation, which is the actual black crescent.
        val helper = XposedUtils.getObjectField(service, "hyperMaterialHelper")
        val material = helper?.let { XposedUtils.getObjectField(it, "h") as? View }
        val rim = helper?.let { XposedUtils.getObjectField(it, "i") as? View }
        material?.visibility = View.INVISIBLE
        rim?.visibility = View.INVISIBLE
        if (toolbarDiagCount.getAndIncrement() < 2) {
            Log.i(
                "XiaoAiTypeUnblock",
                "[BottomDiag][ToolbarGuard] target=${viewSnapshot(target)} " +
                    "contentTop=$contentTop transparent=true material=${viewSnapshot(material)} " +
                    "tree=${compactViewTree(root)}"
            )
        }
    }

    private fun compactViewTree(root: View): String {
        val parts = ArrayList<String>()
        fun visit(view: View, depth: Int) {
            if (parts.size >= 40 || depth > 6) return
            val location = IntArray(2)
            try { view.getLocationOnScreen(location) } catch (_: Throwable) {}
            parts += "${depth}:${view.javaClass.name.substringAfterLast('.')}" +
                "@${location[0]},${location[1]}:${view.width}x${view.height}:" +
                viewSnapshot(view).substringAfter("bg=").substringBefore('}')
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1)
            }
        }
        visit(root, 0)
        return parts.joinToString("|")
    }

    private fun restoreImeToolbarTransitionGuard(
        service: android.inputmethodservice.InputMethodService
    ) {
        if (ConfigManager.getBgType() == 0) {
            val helper = XposedUtils.getObjectField(service, "hyperMaterialHelper")
            (helper?.let { XposedUtils.getObjectField(it, "h") as? View })?.apply {
                visibility = View.VISIBLE
                invalidate()
            }
            (helper?.let { XposedUtils.getObjectField(it, "i") as? View })?.apply {
                visibility = View.VISIBLE
                invalidate()
            }
        }
    }

    private fun beginBottomTransitionGuard(hideMaterial: Boolean) {
        hideMaterialDuringBottomTransition = hideMaterial
        bottomTransitionGuardUntil = SystemClock.uptimeMillis() + BOTTOM_TRANSITION_GUARD_MS
    }

    private fun isToolbarTransitionGuardActive(): Boolean =
        ConfigManager.isStyleEnabled() &&
            hideMaterialDuringBottomTransition &&
            ConfigManager.getOpacity() < 100 &&
            SystemClock.uptimeMillis() < bottomTransitionGuardUntil

    private fun forceHyperMaterialLayersInvisible(helper: Any) {
        (XposedUtils.getObjectField(helper, "h") as? View)?.visibility = View.INVISIBLE
        (XposedUtils.getObjectField(helper, "i") as? View)?.visibility = View.INVISIBLE
    }

    private fun installNavigationBarWriteDiagnostics(module: XposedModule) {
        try {
            val phoneWindowClass = Class.forName("com.android.internal.policy.PhoneWindow")
            val setColorMethod = phoneWindowClass.getDeclaredMethod(
                "setNavigationBarColor",
                Int::class.javaPrimitiveType ?: Integer.TYPE
            )
            module.hook(setColorMethod).intercept { chain ->
                val window = chain.thisObject as? Window ?: return@intercept chain.proceed()
                if (window !== activeImeWindow) {
                    return@intercept chain.proceed()
                }
                val requested = chain.getArg(0) as? Int ?: Color.TRANSPARENT
                val keepGlassNavigation = shouldKeepGlassNavigationTransparent()
                val applied = if (keepGlassNavigation) activeSteadyBottomBarColor else requested
                val flattenAnimationSurface =
                    ConfigManager.isStyleEnabled() &&
                        !keepGlassNavigation &&
                        Color.alpha(activeSteadyBottomBarColor) < 255 &&
                        Color.alpha(requested) == 255 &&
                        SystemClock.uptimeMillis() < bottomTransitionGuardUntil
                val originalEdgeToEdge = if (flattenAnimationSurface) {
                    XposedUtils.getObjectField(window, "mEdgeToEdgeEnforced") as? Boolean
                } else {
                    null
                }
                if (originalEdgeToEdge != null) {
                    // Keep edge-to-edge disabled until PhoneWindow has notified
                    // its navigation-bar callback. Limiting this to DecorView's
                    // nested color calculation leaves that callback skipped and
                    // the animation leash keeps its black fallback.
                    XposedUtils.setObjectField(window, "mEdgeToEdgeEnforced", false)
                }
                try {
                    if (ConfigManager.isVerboseLogEnabled()) {
                        val before = try {
                            window.navigationBarColor
                        } catch (_: Throwable) {
                            Color.TRANSPARENT
                        }
                        val caller = Throwable().stackTrace
                            .drop(1)
                            .take(8)
                            .joinToString(" <- ") {
                                "${it.className}.${it.methodName}:${it.lineNumber}"
                            }
                        XposedUtils.log(
                            module,
                            "[BottomDiag] nav-write requested=${colorHex(requested)} " +
                                "applied=${colorHex(applied)} glassNavigation=$keepGlassNavigation " +
                                "before=${colorHex(before)} fullCallFlatten=$flattenAnimationSurface " +
                                "caller=$caller"
                        )
                    }
                    val result = chain.proceed(arrayOf(applied))
                    if (ConfigManager.isVerboseLogEnabled()) {
                        val after = try {
                            window.navigationBarColor
                        } catch (_: Throwable) {
                            Color.TRANSPARENT
                        }
                        XposedUtils.log(module, "[BottomDiag] nav-write result=${colorHex(after)}")
                    }
                    result
                } finally {
                    if (originalEdgeToEdge != null) {
                        XposedUtils.setObjectField(window, "mEdgeToEdgeEnforced", originalEdgeToEdge)
                    }
                }
            }
            XposedUtils.log(module, "KeyboardStyleHook: BottomDiag PhoneWindow color hook installed")
        } catch (t: Throwable) {
            XposedUtils.logError(module, "KeyboardStyleHook: BottomDiag PhoneWindow hook failed", t)
        }
    }

    private fun installSystemBarsAppearanceGuard(module: XposedModule) {
        try {
            val insetsControllerClass = Class.forName("android.view.InsetsController")
            val setAppearanceMethod = insetsControllerClass.getDeclaredMethod(
                "setSystemBarsAppearance",
                Int::class.javaPrimitiveType ?: Integer.TYPE,
                Int::class.javaPrimitiveType ?: Integer.TYPE
            )
            module.hook(setAppearanceMethod).intercept { chain ->
                val activeController = try { activeImeWindow?.insetsController } catch (_: Throwable) { null }
                if (
                    chain.thisObject !== activeController ||
                    !ConfigManager.isStyleEnabled() ||
                    Color.alpha(activeSteadyBottomBarColor) == 255
                ) {
                    return@intercept chain.proceed()
                }

                val requestedAppearance = chain.getArg(0) as? Int ?: 0
                val requestedMask = chain.getArg(1) as? Int ?: 0
                val guardedAppearance = requestedAppearance and NAV_BAR_BACKGROUND_APPEARANCE_MASK.inv()
                val guardedMask = requestedMask or NAV_BAR_BACKGROUND_APPEARANCE_MASK
                if (ConfigManager.isVerboseLogEnabled()) {
                    XposedUtils.log(
                        module,
                        "[BottomDiag] bars-appearance requested=0x${requestedAppearance.toUInt().toString(16)} " +
                            "mask=0x${requestedMask.toUInt().toString(16)} guarded=" +
                            "0x${guardedAppearance.toUInt().toString(16)}/" +
                            "0x${guardedMask.toUInt().toString(16)}"
                    )
                }
                chain.proceed(arrayOf(guardedAppearance, guardedMask))
            }
            XposedUtils.log(module, "KeyboardStyleHook: BottomDiag InsetsController guard installed")
        } catch (t: Throwable) {
            XposedUtils.logError(module, "KeyboardStyleHook: InsetsController guard failed", t)
        }
    }

    /**
     * Android's edge-to-edge enforcement deliberately makes DecorView ignore
     * PhoneWindow's navigation-bar color and calculate a transparent color
     * view instead. That is correct while the IME is steady, but during the
     * insets animation the transparent strip is composed over the leash's
     * black backing surface. Temporarily disable only the color calculation's
     * edge-to-edge branch while our transition guard is active. The field is
     * restored before returning, so layout/insets remain edge-to-edge and the
     * steady transparent result is restored after the animation.
     */
    private fun installDecorNavigationColorGuard(module: XposedModule) {
        try {
            val decorViewClass = Class.forName("com.android.internal.policy.DecorView")
            XposedUtils.log(
                module,
                "KeyboardStyleHook: probing DecorView navigation color guard " +
                    "methods=${decorViewClass.declaredMethods.count { it.name == "updateColorViews" }}"
            )
            val updateColorViewsMethod = decorViewClass.declaredMethods.firstOrNull {
                it.name == "updateColorViews" && it.parameterTypes.size == 2
            }?.apply { isAccessible = true } ?: run {
                XposedUtils.logError(module, "KeyboardStyleHook: DecorView.updateColorViews not found")
                return
            }
            module.hook(updateColorViewsMethod).intercept { chain ->
                val window = activeImeWindow ?: return@intercept chain.proceed()
                val shouldFlatten =
                    chain.thisObject === window.decorView &&
                        ConfigManager.isStyleEnabled() &&
                        !shouldKeepGlassNavigationTransparent() &&
                        Color.alpha(activeSteadyBottomBarColor) < 255 &&
                        SystemClock.uptimeMillis() < bottomTransitionGuardUntil
                if (!shouldFlatten) return@intercept chain.proceed()

                val originalEdgeToEdge = XposedUtils.getObjectField(
                    window,
                    "mEdgeToEdgeEnforced"
                ) as? Boolean ?: return@intercept chain.proceed()
                XposedUtils.setObjectField(window, "mEdgeToEdgeEnforced", false)
                try {
                    val result = chain.proceed()
                    if (
                        ConfigManager.isVerboseLogEnabled() &&
                        decorNavigationGuardDiagnosticCount.getAndIncrement() < 24
                    ) {
                        val state = XposedUtils.getObjectField(
                            chain.thisObject,
                            "mNavigationColorViewState"
                        )
                        val navView = state?.let { XposedUtils.getObjectField(it, "view") as? View }
                        val storedColor = XposedUtils.getObjectField(window, "mNavigationBarColor") as? Int
                        XposedUtils.log(
                            module,
                            "[BottomDiag] DecorView nav calculation flattened " +
                                "stored=${colorHex(storedColor ?: Color.TRANSPARENT)} " +
                                "view=${viewSnapshot(navView)}"
                        )
                    }
                    result
                } finally {
                    XposedUtils.setObjectField(window, "mEdgeToEdgeEnforced", originalEdgeToEdge)
                }
            }
            XposedUtils.log(module, "KeyboardStyleHook: DecorView navigation color guard installed")
        } catch (t: Throwable) {
            XposedUtils.logError(module, "KeyboardStyleHook: DecorView navigation color guard failed", t)
        }
    }

    private fun scheduleBottomSnapshots(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        sequence: Int,
        phase: String
    ) {
        val decor = service.window?.window?.decorView ?: return
        longArrayOf(0L, 16L, 50L, 100L, 200L, 350L).forEach { delay ->
            decor.postDelayed({ applyImeToolbarTransitionGuard(service) }, delay)
        }
        if (ConfigManager.isVerboseLogEnabled()) {
            longArrayOf(0L, 16L, 50L, 100L, 200L, 350L, 500L).forEach { delay ->
                decor.postDelayed(
                    { logBottomSnapshot(module, service, sequence, "$phase:+${delay}ms") },
                    delay
                )
            }
        }
        decor.postDelayed(
            {
                // A newer show/hide request owns the color now; an older
                // callback must never clear its transition guard.
                if (bottomDiagSequence.get() == sequence) {
                    bottomTransitionGuardUntil = 0L
                    hideMaterialDuringBottomTransition = false
                    restoreImeToolbarTransitionGuard(service)
                    if (ConfigManager.isStyleEnabled()) {
                        applyBottomBarAppearanceImmediately(service)
                    }
                    logBottomSnapshot(module, service, sequence, "$phase:steady-restored")
                    // Sequence ownership above proves no newer show request has
                    // arrived. InputMethodService.isInputViewShown can remain
                    // true briefly after the surface is already hidden, so it
                    // is not a reliable gate for this off-screen prewarm.
                    if (phase == "hide") {
                        prepareDynamicGlassBeforeShow(module, service)
                        primeDynamicGlassHiddenBuffer(module, service)
                    }
                }
            },
            BOTTOM_TRANSITION_GUARD_MS + 32L
        )
    }

    private fun logBottomSnapshot(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        sequence: Int,
        phase: String
    ) {
        if (!ConfigManager.isVerboseLogEnabled()) return
        val now = SystemClock.uptimeMillis()
        val window = service.window?.window
        val decor = window?.decorView
        val root = XposedUtils.getObjectField(service, "currentImeRootView") as? View
        val attrs = window?.attributes
        val plugin = readPluginBottomSnapshot()
        XposedUtils.log(
            module,
            "[BottomDiag][$sequence][$phase] t=$now configured=${colorHex(activeBottomBarColor)} " +
                "bgType=${ConfigManager.getBgType()} opacity=${ConfigManager.getOpacity()} " +
                "guardRemaining=${(bottomTransitionGuardUntil - now).coerceAtLeast(0L)}ms " +
                "windowNav=${colorHex(try { window?.navigationBarColor ?: Color.TRANSPARENT } catch (_: Throwable) { Color.TRANSPARENT })} " +
                "barAppearance=${try { window?.insetsController?.systemBarsAppearance?.let { "0x${it.toUInt().toString(16)}" } } catch (_: Throwable) { null }} " +
                "contrast=${try { window?.isNavigationBarContrastEnforced } catch (_: Throwable) { null }} " +
                "flags=${attrs?.flags?.let { "0x${it.toUInt().toString(16)}" }} format=${attrs?.format} " +
                "decor=${viewSnapshot(decor)} root=${viewSnapshot(root)} plugin=$plugin"
        )
    }

    private fun readPluginBottomSnapshot(): String {
        return try {
            val injectorClass = Class.forName("android.inputmethodservice.InputMethodServiceInjector")
            val loaderField = injectorClass.getDeclaredField("sClassLoader").apply { isAccessible = true }
            val loader = loaderField.get(null) as? ClassLoader ?: return "loader=null"
            val managerClass = Class.forName("com.miui.inputmethod.InputMethodBottomManager", false, loader)
            fun staticField(name: String): Any? = try {
                managerClass.getDeclaredField(name).apply { isAccessible = true }.get(null)
            } catch (_: Throwable) {
                null
            }
            val view = staticField("sBottomView") as? View
            "view=${viewSnapshot(view)},current=${(staticField("currentBottomViewColor") as? Int)?.let(::colorHex)}," +
                "default=${(staticField("sDefBottomViewColor") as? Int)?.let(::colorHex)}"
        } catch (t: Throwable) {
            "unavailable:${t.javaClass.simpleName}"
        }
    }

    private fun viewSnapshot(view: View?): String {
        if (view == null) return "null"
        val background = when (val drawable = view.background) {
            is ColorDrawable -> colorHex(drawable.color)
            null -> "null"
            else -> drawable.javaClass.simpleName
        }
        return "${view.javaClass.simpleName}{vis=${view.visibility},shown=${view.isShown}," +
            "attached=${view.isAttachedToWindow},size=${view.width}x${view.height}," +
            "alpha=${view.alpha},bg=$background}"
    }

    private fun colorHex(color: Int): String = "#%08X".format(color)

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
        val coreFields = arrayOf("a", "b", "d", "e", "h", "i", "k", "l", "m", "u", "w", "x", "z", "A", "B")
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
            // na.d.u is the Compose toolbar row background.  At very low
            // material opacity it must stay transparent so the guarded IME
            // surface underneath remains continuous while the leash moves.
            // Leaving Xiaomi's stock token here creates the dark rounded band
            // that is visible only during show/hide animation.
            if (bgType == 0) {
                replacements["a"] = Color.TRANSPARENT
                replacements["b"] = Color.TRANSPARENT
                replacements["u"] = Color.TRANSPARENT
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
                // translucent neutral tints. Start every tint at zero so 0%
                // leaves only the native blur instead of a permanent gray veil.
                val blendColors = if (index == 0) {
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
        radiusPx: Float
    ) {
        val density = view.resources.displayMetrics.density
        val strength = (opacity.coerceIn(0, 100) / 100.0f).coerceIn(0f, 1f)
        val radii = floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)

        val softLight = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (isDark) {
                intArrayOf(
                    Color.argb((70 * strength).toInt(), 255, 255, 255),
                    Color.TRANSPARENT,
                    Color.argb((35 * strength).toInt(), 111, 151, 205)
                )
            } else {
                intArrayOf(
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
                Color.argb((120 * strength).toInt(), 255, 255, 255)
            )
            cornerRadii = radii
        }

        view.foreground = LayerDrawable(arrayOf(softLight, highlight))
    }

    private fun getImeContentTopInset(
        service: android.inputmethodservice.InputMethodService
    ): Int? = try {
        val field = android.inputmethodservice.InputMethodService::class.java
            .getDeclaredField("mTmpInsets")
        field.isAccessible = true
        val insets = field.get(service)
        val topField = insets.javaClass.getField("contentTopInsets")
        topField.getInt(insets).takeIf { it > 0 }
    } catch (_: Throwable) {
        null
    }

    private fun resolveKeyboardContentTop(
        service: android.inputmethodservice.InputMethodService,
        targetView: View
    ): Int? {
        // mTmpInsets.contentTopInsets is still 0 during the first cold-start
        // frame. Xiaomi's material view has already been laid out at the real
        // keyboard top by bb.u.o, so its screen position is the reliable source.
        val helper = XposedUtils.getObjectField(service, "hyperMaterialHelper")
        val materialView = helper?.let { XposedUtils.getObjectField(it, "h") as? View }
        if (materialView != null && materialView.isAttachedToWindow && materialView.height > 0) {
            val materialLocation = IntArray(2)
            val targetLocation = IntArray(2)
            materialView.getLocationOnScreen(materialLocation)
            targetView.getLocationOnScreen(targetLocation)
            val materialTop = materialLocation[1] - targetLocation[1]
            if (materialTop >= 0 && materialTop < targetView.height) {
                return materialTop
            }
        }

        return getImeContentTopInset(service)?.let { windowTop ->
            val decor = service.window?.window?.decorView
            if (decor != null) {
                val decorLocation = IntArray(2)
                val targetLocation = IntArray(2)
                decor.getLocationOnScreen(decorLocation)
                targetView.getLocationOnScreen(targetLocation)
                windowTop + decorLocation[1] - targetLocation[1]
            } else {
                windowTop
            }
        }
    }

    private fun invokeHelperMethod(helper: Any, name: String, vararg args: Any?): Any? {
        val method = helper.javaClass.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size
        } ?: return null
        method.isAccessible = true
        return method.invoke(helper, *args)
    }

    /** Resolve the configured solid color once so the keyboard card and bottom
     * system strip use exactly the same ARGB value. */
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

    /**
     * HyperOS draws the navigation/accessory strip on a separate system surface.
     * Passing the translucent keyboard color to that surface makes it blend over
     * the system's dark backing (and sometimes through two bottom-bar layers), so
     * it appears much darker than the keyboard card. Flatten it over the light IME
     * backing first; an opaque result also avoids repeated alpha composition.
     */
    private fun resolveSolidBottomBarColor(colorString: String, opacity: Int): Int {
        val color = resolveSolidColor(colorString, opacity)
        val alpha = Color.alpha(color)
        fun compositeOverWhite(channel: Int): Int =
            ((channel * alpha + 255 * (255 - alpha)) / 255).coerceIn(0, 255)
        return Color.rgb(
            compositeOverWhite(Color.red(color)),
            compositeOverWhite(Color.green(color)),
            compositeOverWhite(Color.blue(color))
        )
    }

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
        }
        materialView.foreground = null
        materialView.elevation = 0f
        materialView.translationZ = 0f
        materialView.visibility = View.VISIBLE
        (XposedUtils.getObjectField(helper, "i") as? View)?.visibility = View.GONE
        materialView.invalidate()
    }

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
        val rimView = XposedUtils.getObjectField(helper, "i") as? View
        val dynamicGlass = ConfigManager.getBgType() == 0

        val generation = synchronized(materialRefreshGenerations) {
            val next = (materialRefreshGenerations[materialView] ?: 0) + 1
            materialRefreshGenerations[materialView] = next
            next
        }

        // Hide only Xiaomi's background material views while their height is 0;
        // the keyboard content stays visible over the stable fallback tint.
        if (dynamicGlass && (!materialView.isAttachedToWindow || materialView.width <= 0 || materialView.height <= 0)) {
            materialView.visibility = View.INVISIBLE
            rimView?.visibility = View.INVISIBLE
        }

        val refresh = object : Runnable {
            private var layoutWaitFrames = 0
            private var settlePass = 0

            override fun run() {
                val isCurrent = synchronized(materialRefreshGenerations) {
                    materialRefreshGenerations[materialView] == generation
                }
                if (!isCurrent || !ConfigManager.isStyleEnabled()) return

                if (!materialView.isAttachedToWindow || materialView.width <= 0 || materialView.height <= 0) {
                    if (dynamicGlass) {
                        materialView.visibility = View.INVISIBLE
                        rimView?.visibility = View.INVISIBLE
                    }
                    // bb.u.o normally resolves this on the next layout. Keep the
                    // fallback visible for at most two seconds on unusually slow starts.
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
        materialView.post(refresh)
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
        val opacity = ConfigManager.getOpacity()
        val cornerRadiusDp = ConfigManager.getCornerRadius()
        val blurRadiusDp = ConfigManager.getBlurRadius()

        val density = f3500h.resources.displayMetrics.density
        val radiusPx = cornerRadiusDp * density
        val isDark = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        f3500h.post {
            try {
                // 1. Background Customization on f3500h (the actual keyboard card at bottom)
                when (bgType) {
                    0 -> { // HyperOS Dynamic Liquid Glass (系统通知中心同款动态毛玻璃)
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

                        applySoftGlassForeground(f3500h, isDark, opacity, radiusPx)
                        val guarded = hideMaterialDuringBottomTransition &&
                            SystemClock.uptimeMillis() < bottomTransitionGuardUntil &&
                            opacity < 100
                        f3500h.visibility = if (guarded) View.INVISIBLE else View.VISIBLE

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
                                f3501i.visibility = if (guarded) View.INVISIBLE else View.VISIBLE
                            } catch (_: Throwable) {
                                f3501i.visibility = View.GONE
                            }
                        }
                    }
                    1, 2 -> restoreCustomBackground(helper, f3500h)
                }

                // bb.u adds this surface at index 0 as the keyboard background.
                // Any positive Z elevation makes the solid/image rectangle draw
                // above the Compose key layer and obscures the key labels. Always
                // normalize it, including when the configured radius is zero.
                f3500h.elevation = 0f
                f3500h.translationZ = 0f

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
                    f3500h.invalidateOutline()
                } else {
                    f3500h.clipToOutline = false
                    f3500h.outlineProvider = ViewOutlineProvider.BACKGROUND
                }

            } catch (t: Throwable) {
                XposedUtils.logError(module, "KeyboardStyleHook: failed to update material views", t)
            }
        }
    }

    private fun resolveBottomBarColor(
        service: android.inputmethodservice.InputMethodService,
        bgType: Int,
        opacity: Int,
        bgColor: String
    ): Int {
        if (bgType == 1) return resolveSolidBottomBarColor(bgColor, opacity)
        if (bgType != 0) return Color.TRANSPARENT
        val isDark = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val strength = (opacity.coerceIn(0, 100) / 100.0f).coerceIn(0f, 1f)
        if (strength == 0f) return Color.TRANSPARENT
        val alpha: Int
        val red: Int
        val green: Int
        val blue: Int
        if (isDark) {
            alpha = (85 + 115 * strength).toInt()
            red = 50
            green = 61
            blue = 79
        } else {
            alpha = (135 + 90 * strength).toInt()
            red = 76
            green = 94
            blue = 122
        }
        // The IME's liquid-glass surface remains a light transmissive layer even
        // when the system configuration reports night mode. Precompositing the
        // separate system bottom bar against black turns it nearly pure black.
        // Composite against the same light surface used behind the keyboard.
        val base = 255
        fun composite(channel: Int): Int =
            ((channel * alpha + base * (255 - alpha)) / 255).coerceIn(0, 255)
        // WindowManager snapshots the navigation-bar color when the IME enter
        // animation starts. A translucent color is composited over its black
        // animation leash, so low opacity briefly looks black even though the
        // bottom view later draws the same color over the app. The RGB values
        // above are already flattened onto the keyboard's light backing; keep
        // the system-owned strip opaque to avoid applying alpha a second time.
        return Color.rgb(composite(red), composite(green), composite(blue))
    }

    private fun updateBottomBarAppearance(
        service: android.inputmethodservice.InputMethodService,
        backgroundColor: Int
    ) {
        try {
            val isDark = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val iconColor = if (isDark) Color.parseColor("#9E9E9E") else Color.parseColor("#757575")
            val rippleColor = if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#1F000000")

            // 1. HyperOS renders this accessory/navigation strip separately.
            val injectorClass = Class.forName("android.inputmethodservice.InputMethodServiceInjector")
            val customizeMethod = injectorClass.declaredMethods.find { it.name == "customizeBottomViewColor" }
            if (customizeMethod != null) {
                customizeMethod.isAccessible = true
                if (customizeMethod.parameterTypes.size == 4) {
                    customizeMethod.invoke(null, true, backgroundColor, iconColor, rippleColor)
                } else if (customizeMethod.parameterTypes.size == 2) {
                    customizeMethod.invoke(null, true, backgroundColor)
                }
            }
        } catch (_: Throwable) {}

        try {
            // 2. Match any explicit bottom container in DecorView as well.
            val window = service.window?.window
            val decor = window?.decorView as? ViewGroup
            if (decor != null) {
                for (i in 0 until decor.childCount) {
                    val child = decor.getChildAt(i)
                    val className = child.javaClass.name
                    if (className.contains("Bottom", ignoreCase = true) || className.contains("NavigationBar", ignoreCase = true)) {
                        child.setBackgroundColor(backgroundColor)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    /** The compositor glass already covers the navigation area. Do not place
     * the legacy opaque animation backing over it, even during the first show. */
    private fun shouldKeepGlassNavigationTransparent(): Boolean =
        ConfigManager.isStyleEnabled() && ConfigManager.getBgType() == 0 &&
            ConfigManager.getOpacity() <= DYNAMIC_GLASS_FIRST_FRAME_MAX_OPACITY &&
            ConfigManager.getBlurRadius() > 0

    /** Apply every system/plugin bottom-strip color synchronously. */
    private fun applyBottomBarAppearanceImmediately(
        service: android.inputmethodservice.InputMethodService
    ): Int {
        val steadyColor = resolveBottomBarColor(
            service,
            ConfigManager.getBgType(),
            ConfigManager.getOpacity(),
            ConfigManager.getBgColor()
        )
        activeSteadyBottomBarColor = steadyColor
        val transitionGuarded = SystemClock.uptimeMillis() < bottomTransitionGuardUntil
        val guardTransparentSurface = transitionGuarded && Color.alpha(steadyColor) < 255 &&
            !shouldKeepGlassNavigationTransparent()
        val bottomBarColor = if (guardTransparentSurface) {
            flattenBottomBarForAnimation(service, steadyColor)
        } else {
            steadyColor
        }
        activeBottomBarColor = bottomBarColor

        try {
            service.window?.window?.let { window ->
                activeImeWindow = window
                // Make PhoneWindow actually draw the requested navigation-bar
                // color instead of retaining a translucent black fallback.
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                window.setNavigationBarColor(bottomBarColor)
                window.setNavigationBarContrastEnforced(false)
                applyImeTransitionSurface(window)
                try {
                    val dividerMethod = window.javaClass.methods.find {
                        it.name == "setNavigationBarDividerColor" && it.parameterTypes.size == 1
                    }
                    dividerMethod?.invoke(window, bottomBarColor)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // The MIUI phrase plugin owns a strip inside the keyboard surface. It
        // must always receive the steady material color; bottomBarColor may be
        // an opaque color used only by the system navigation animation leash.
        // Mixing the two made the plugin flash pale and remain that way until a
        // key press caused another draw.
        updateBottomBarAppearance(service, steadyColor)
        return bottomBarColor
    }

    /** Keep the IME request itself in transparent navigation-bar mode. */
    private fun applyImeTransitionSurface(window: Window) {
        try {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.decorView.setBackgroundColor(Color.TRANSPARENT)
            if (Color.alpha(activeSteadyBottomBarColor) < 255) {
                window.insetsController?.setSystemBarsAppearance(
                    0,
                    NAV_BAR_BACKGROUND_APPEARANCE_MASK
                )
            }
        } catch (_: Throwable) {}
    }

    /**
     * The IME animation leash is black and cannot preserve transparent pixels.
     * Flatten only while the leash exists, then restore the real transparent
     * color after the transition guard expires.
     */
    private fun flattenBottomBarForAnimation(
        service: android.inputmethodservice.InputMethodService,
        color: Int
    ): Int {
        val isDark = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val base = if (isDark) 24 else 255
        val alpha = Color.alpha(color)
        fun composite(channel: Int): Int =
            ((channel * alpha + base * (255 - alpha)) / 255).coerceIn(0, 255)
        return Color.rgb(
            composite(Color.red(color)),
            composite(Color.green(color)),
            composite(Color.blue(color))
        )
    }

    fun applyStyle(module: XposedModule, service: android.inputmethodservice.InputMethodService, rootView: View) {
        ConfigManager.syncFromProvider(service)
        if (!ConfigManager.isStyleEnabled()) return

        val cornerRadiusDp = ConfigManager.getCornerRadius()
        val opacity = ConfigManager.getOpacity()
        val bgType = ConfigManager.getBgType()
        applyBottomBarAppearanceImmediately(service)

        val density = service.resources.displayMetrics.density
        rootView.post {
            try {
                // 1. Transparent window & navigation bar (Do NOT add FLAG_BLUR_BEHIND to window as it blurs the entire screen!)
                // Re-resolve at execution time. The post can run after the
                // transition guard has expired; replaying a color captured
                // before posting would reintroduce the stale pale strip.
                val currentBottomBarColor = applyBottomBarAppearanceImmediately(service)
                val steadyBottomBarColor = activeSteadyBottomBarColor
                val window = service.window?.window
                if (window != null) {
                    // applyBottomBarAppearanceImmediately() owns the Window
                    // background/format. Do not clear its temporary opaque
                    // animation guard from this posted styling pass.
                    window.setNavigationBarColor(currentBottomBarColor)
                    window.setNavigationBarContrastEnforced(false)
                    window.setDimAmount(0f)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                }

                // 2. Blend the separately rendered bottom strip into the glass.
                updateBottomBarAppearance(service, activeSteadyBottomBarColor)

                // 3. Clear background on full-screen container views so nothing bleeds to top
                rootView.background = null

                val targetView: View = if (rootView is ViewGroup && rootView.childCount > 0) {
                    rootView.getChildAt(0)
                } else {
                    rootView
                }
                val styledBackground = if (bgType == 0) {
                    val contentTop = resolveKeyboardContentTop(service, targetView)
                    if (contentTop != null && contentTop < targetView.height) {
                        val isDark = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                        val strength = (opacity.coerceIn(0, 100) / 100.0f).coerceIn(0f, 1f)
                        val colors = if (isDark) {
                            intArrayOf(
                                Color.argb((215 * strength).toInt(), 24, 28, 36),
                                Color.argb((210 * strength).toInt(), 35, 41, 52),
                                Color.argb((200 * strength).toInt(), 50, 61, 79)
                            )
                        } else {
                            intArrayOf(
                                Color.argb((235 * strength).toInt(), 135, 148, 168),
                                Color.argb((230 * strength).toInt(), 103, 118, 142),
                                Color.argb((225 * strength).toInt(), 76, 94, 122)
                            )
                        }
                        val tint = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadii = floatArrayOf(
                                cornerRadiusDp * density, cornerRadiusDp * density,
                                cornerRadiusDp * density, cornerRadiusDp * density,
                                0f, 0f, 0f, 0f
                            )
                            setStroke(
                                density.toInt().coerceAtLeast(1),
                                Color.argb((116 * strength).toInt(), 255, 255, 255)
                            )
                        }
                        // The system bottom strip accepts only one solid color,
                        // while the keyboard card above is diagonal. Gradually
                        // converge the lower third of the card to that exact solid
                        // color so there is no visible horizontal join.
                        val seamRed = Color.red(steadyBottomBarColor)
                        val seamGreen = Color.green(steadyBottomBarColor)
                        val seamBlue = Color.blue(steadyBottomBarColor)
                        val bottomBlend = GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(
                                Color.argb(0, seamRed, seamGreen, seamBlue),
                                Color.argb(0, seamRed, seamGreen, seamBlue),
                                Color.argb((56 * strength).toInt(), seamRed, seamGreen, seamBlue),
                                steadyBottomBarColor
                            )
                        )
                        LayerDrawable(arrayOf(tint, bottomBlend)).apply {
                            setLayerInset(0, 0, contentTop, 0, 0)
                            setLayerInset(1, 0, contentTop, 0, 0)
                        }
                    } else {
                        null
                    }
                } else {
                    null
                }
                targetView.background = styledBackground
                applyImeToolbarTransitionGuard(service)

                // Keep the first raw-transparent IME buffer hidden. The hold is
                // cleared immediately after the first blur-bearing frame commits.
                if (dynamicGlassContentHoldActive) {
                    holdDynamicGlassContentView(targetView)
                } else {
                    targetView.alpha = 1f
                }

                // 4. Update HyperMaterialHelper's f3500h view which is the true keyboard bottom card
                val helper = XposedUtils.getObjectField(service, "hyperMaterialHelper")
                scheduleHyperMaterialRefresh(module, service, helper)
            } catch (t: Throwable) {
                XposedUtils.logError(module, "Error applying custom style to keyboard view", t)
            }
        }
    }
}
