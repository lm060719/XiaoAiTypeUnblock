package io.mo.xatype.hooks

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.mo.xatype.compat.ModernKeyboardProfile
import io.mo.xatype.compat.TargetCompatibility
import io.mo.xatype.compat.TargetGeneration
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

object KeyboardStyleV209Hook
{
    private lateinit var profile: ModernKeyboardProfile
    private val originalPaletteColors = IdentityHashMap<Any, Map<String, Long>>()
    private val originalAppsPanelColors = IdentityHashMap<Any, Map<String, Long>>()
    private val clipboardAdapterHooks = ConcurrentHashMap.newKeySet<Class<*>>()
    private val clipboardSwipeHooks = ConcurrentHashMap.newKeySet<Class<*>>()
    private val clipboardAppliedBackgrounds = WeakHashMap<View, AppliedClipboardBackground>()
    private val preserveDynamicGlassCleanup = ThreadLocal<Boolean>()
    private val compositorGlass by lazy { CompositorGlassSurface(profile.helperMaterialField) }
    private val floatingGlass by lazy {
        CompositorGlassSurface(profile.helperMaterialField, popupWindow = true)
    }
    private val clipboardGlass = WeakHashMap<View, CompositorGlassSurface>()
    private data class ClipboardPopupState(val nightMode: Int, val nativeColors: Boolean)
    private val activeClipboardPopups = WeakHashMap<PopupWindow, ClipboardPopupState>()
    private val popupMaterialListeners = WeakHashMap<View, View.OnAttachStateChangeListener>()
    private val pendingMaterialSize = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()

    @Volatile
    private var activePalette: Any? = null

    @Volatile
    private var cachedBitmap: Bitmap? = null

    @Volatile
    private var cachedImageVersion: Long = -1L

    fun install(module: XposedModule, classLoader: ClassLoader)
    {
        profile = TargetCompatibility.modernKeyboardProfile(classLoader) ?: return
        val serviceClass = XposedUtils.findClass("com.mi.ime.MiInputMethodService", classLoader)

        if (serviceClass == null)
        {
            XposedUtils.logError(module, "KeyboardStyleV209Hook: MiInputMethodService not found", null)
            return
        }

        installLifecycleHooks(module, serviceClass)
        AppearanceConfigurationHook.install(module, serviceClass,
            onConfigurationApplied = { service -> refreshClipboardPopups(module, service) }
        ) { service, root ->
            applyStyle(module, service, root)
        }
        installWindowTransitionHooks(module)
        installHyperMaterialHooks(module, classLoader)
        installPaletteHook(module, classLoader)
        PanelExpansionAnimationHook.install(module, classLoader)
        installClipboardPopupHook(module)

        XposedUtils.log(module, "KeyboardStyleV209Hook: ${TargetCompatibility.detect(classLoader)} compatibility hooks installed")
    }

    private fun installLifecycleHooks(module: XposedModule, serviceClass: Class<*>)
    {
        XposedUtils.findMethodExact(serviceClass, "onCreateInputView")?.let { method ->
            module.hook(method).intercept { chain ->
                val service = chain.thisObject as? android.inputmethodservice.InputMethodService
                service?.let(ConfigManager::syncFromProvider)

                val result = chain.proceed() as? View

                if (service != null && result != null && ConfigManager.isStyleEnabled())
                {
                    applyStyle(module, service, result)
                }

                result
            }
        }

        XposedUtils.findMethodExact(
            serviceClass,
            "onStartInputView",
            EditorInfo::class.java,
            Boolean::class.javaPrimitiveType ?: java.lang.Boolean.TYPE
        )?.let { method ->
            module.hook(method).intercept { chain ->
                val service = chain.thisObject as? android.inputmethodservice.InputMethodService
                service?.let(ConfigManager::syncFromProvider)
                val result = chain.proceed()

                if (service != null && ConfigManager.isStyleEnabled())
                {
                    val root = XposedUtils.getObjectField(service, "currentImeRootView") as? View
                    if (root != null)
                    {
                        applyStyle(module, service, root)
                    }
                }

                result
            }
        }

        XposedUtils.findMethodExact(serviceClass, "onWindowShown")?.let { method ->
            module.hook(method).intercept { chain ->
                val service = chain.thisObject as? android.inputmethodservice.InputMethodService
                service?.let(ConfigManager::syncFromProvider)
                val result = chain.proceed()

                if (service != null && ConfigManager.isStyleEnabled())
                {
                    val root = XposedUtils.getObjectField(service, "currentImeRootView") as? View
                    if (root != null)
                    {
                        applyStyle(module, service, root)
                    }
                }

                result
            }
        }

        XposedUtils.findMethodExact(serviceClass, "onWindowHidden")?.let { method ->
            module.hook(method).intercept { chain ->
                val service = chain.thisObject as? android.inputmethodservice.InputMethodService
                service?.let(ConfigManager::syncFromProvider)

                val preserve =
                    ConfigManager.isStyleEnabled() &&
                        ConfigManager.getBgType() == 0

                if (preserve)
                {
                    preserveDynamicGlassCleanup.set(true)
                }

                try
                {
                    chain.proceed()
                }
                finally
                {
                    if (preserve)
                    {
                        preserveDynamicGlassCleanup.remove()
                    }
                }
            }
        }
    }

    private fun installHyperMaterialHooks(module: XposedModule, classLoader: ClassLoader)
    {
        val helperClass = XposedUtils.findClass(profile.helperClassName, classLoader) ?: return

        listOf(profile.helperDetachMethod, profile.materialCleanupMethod).forEach { methodName ->
            helperClass.declaredMethods.firstOrNull {
                it.name == methodName &&
                    it.parameterTypes.isEmpty()
            }?.let { method ->
                method.isAccessible = true

                module.hook(method).intercept { chain ->
                    if (preserveDynamicGlassCleanup.get() == true)
                    {
                        null
                    }
                    else
                    {
                        if (methodName == profile.helperDetachMethod) removeKeyboardGlass()
                        chain.proceed()
                    }
                }
                XposedUtils.log(module, "KeyboardStyleV209Hook: Hooked ${profile.helperClassName}.$methodName material teardown")
            }
        }

        // Clipboard and floating materials use separate surfaces; they must
        // never take ownership of the main IME window's retained blur layer.
        XposedUtils.findMethodExact(helperClass, profile.helperApplyMethod, View::class.java)?.let { method ->
            module.hook(method).intercept { chain ->
                val popupMaterial = chain.getArg(0) as? View
                val popupSurface = clipboardGlass[popupMaterial]
                val popupService = XposedUtils.getObjectField(chain.thisObject, profile.helperServiceField) as?
                    android.inputmethodservice.InputMethodService
                if (popupMaterial != null && popupSurface != null && popupService != null &&
                    usesCompositorGlass() && popupSurface.ensure(module, popupService, popupMaterial)) {
                    clearPopupNativeMaterial(module, popupService, popupMaterial)
                    return@intercept true
                }
                val result = chain.proceed()
                val helper = chain.thisObject
                val material = chain.getArg(0) as? View
                if (material != null && XposedUtils.getObjectField(helper, profile.helperMaterialField) === material) {
                    val service = XposedUtils.getObjectField(helper, profile.helperServiceField) as?
                        android.inputmethodservice.InputMethodService
                    if (service != null) useCompositorGlass(module, service, helper, material)
                }
                result
            }
        }

        // k() and the shadow view's layout listener can recreate the shader
        // after b(View) returns. Suppress that overlay while compositor blur owns it.
        XposedUtils.findClass(profile.rendererClassName, classLoader)?.let { rendererClass ->
            XposedUtils.findMethodExact(rendererClass, profile.rendererUpdateMethod)?.let { method ->
                module.hook(method).intercept { chain ->
                    val renderer = chain.thisObject
                    val service = XposedUtils.getObjectField(renderer, profile.rendererServiceField)
                    val helper = service?.let { XposedUtils.getObjectField(it, "hyperMaterialHelper") }
                    if (usesCompositorGlass() && helper != null &&
                        ownsKeyboardGlass(XposedUtils.getObjectField(helper, profile.helperMaterialField))) {
                        (XposedUtils.getObjectField(renderer, profile.rendererViewField) as? View)
                            ?.setRenderEffect(null)
                        XposedUtils.setObjectField(renderer, profile.rendererEffectField, null)
                        null
                    } else {
                        chain.proceed()
                    }
                }
            }
        }

        helperClass.declaredMethods.firstOrNull {
            it.name == profile.materialVisibilityMethod &&
                it.parameterTypes.contentEquals(
                    arrayOf(
                        Boolean::class.javaPrimitiveType
                            ?: java.lang.Boolean.TYPE
                    )
                )
        }?.let { method ->
            method.isAccessible = true

            module.hook(method).intercept { chain ->
                val hideRequested = chain.getArg(0) == false

                if (
                    preserveDynamicGlassCleanup.get() == true &&
                    hideRequested
                )
                {
                    null
                }
                else
                {
                    chain.proceed()
                }
            }
        }

        helperClass.declaredMethods.firstOrNull {
            it.name == profile.materialSupportMethod &&
                it.parameterTypes.isEmpty() &&
                it.returnType == Boolean::class.javaPrimitiveType
        }?.let { method ->
            method.isAccessible = true

            module.hook(method).intercept { chain ->
                if (ConfigManager.isStyleEnabled())
                {
                    true
                }
                else
                {
                    chain.proceed()
                }
            }

            XposedUtils.log(module, "KeyboardStyleV209Hook: Hooked ${profile.helperClassName}.${profile.materialSupportMethod} material support")
        }

        helperClass.declaredMethods.firstOrNull {
            it.name == profile.materialUpdateMethod && it.parameterTypes.isEmpty()
        }?.let { method ->
            method.isAccessible = true

            module.hook(method).intercept { chain ->
                val result = chain.proceed()

                if (ConfigManager.isStyleEnabled())
                {
                    forceMaterialStateEnabled(chain.thisObject)
                }

                result
            }
        }

        helperClass.declaredMethods
            .filter {
                (it.name == profile.helperAttachMethod && it.parameterTypes.size == 3) ||
                    (it.name == profile.materialRefreshMethod && it.parameterTypes.isEmpty())
            }
            .forEach { method ->
                method.isAccessible = true

                module.hook(method).intercept { chain ->
                    val result = chain.proceed()

                    if (ConfigManager.isStyleEnabled())
                    {
                        val helper = chain.thisObject
                        val service = XposedUtils.getObjectField(helper, profile.helperServiceField) as?
                            android.inputmethodservice.InputMethodService
                        val material = XposedUtils.getObjectField(helper, profile.helperMaterialField) as? View

                        if (service != null && material != null)
                        {
                            applyMaterialStyle(module, service, helper, material)
                            material.post {
                                applyMaterialStyle(module, service, helper, material)
                            }
                        }
                    }

                    result
                }
            }
    }

    private fun installPaletteHook(module: XposedModule, classLoader: ClassLoader)
    {
        val paletteFactory = XposedUtils.findClass(profile.paletteFactoryClassName, classLoader) ?: return
        val method = paletteFactory.declaredMethods.firstOrNull {
            it.name == profile.paletteFactoryMethod &&
                it.parameterTypes.size == 1 &&
                it.returnType.name == profile.paletteClassName
        } ?: return

        method.isAccessible = true

        module.hook(method).intercept { chain ->
            val palette = chain.proceed()

            if (palette != null)
            {
                synchronized(originalPaletteColors)
                {
                    rememberPalette(palette)
                    restorePalette(palette)

                    if (ConfigManager.isStyleEnabled())
                    {
                        patchPalette(palette)
                    }

                    activePalette = palette
                }
            }

            palette
        }

        XposedUtils.log(module, "KeyboardStyleV209Hook: Hooked ${profile.paletteFactoryClassName}.${profile.paletteFactoryMethod} Compose palette")
    }

    private fun rememberPalette(palette: Any)
    {
        if (!originalPaletteColors.containsKey(palette))
        {
            originalPaletteColors[palette] = snapshotLongFields(palette)
        }

        val appsPanel = XposedUtils.getObjectField(palette, profile.palette("appsPanel"))

        if (appsPanel != null && !originalAppsPanelColors.containsKey(appsPanel))
        {
            originalAppsPanelColors[appsPanel] = snapshotLongFields(appsPanel)
        }
    }

    private fun restorePalette(palette: Any)
    {
        originalPaletteColors[palette]?.forEach { (name, value) ->
            writeLongField(palette, name, value)
        }

        val appsPanel = XposedUtils.getObjectField(palette, profile.palette("appsPanel"))
        if (appsPanel != null)
        {
            originalAppsPanelColors[appsPanel]?.forEach { (name, value) ->
                writeLongField(appsPanel, name, value)
            }
        }
    }

    private fun patchPalette(palette: Any)
    {
        val backgroundType = ConfigManager.getBgType()
        val transparent = composeColor(Color.TRANSPARENT)

        // The v209 palette exposes separate keyboard, top and toolbar surfaces.
        // Custom backgrounds are drawn by the retained HyperMaterial view, so
        // these Compose surfaces must stay transparent instead of painting over it.
        listOf("keyboardBackground", "keyboardBackgroundTop", "toolbarBackground").forEach { label ->
            writeLongField(palette, profile.palette(label), transparent)
        }

        val appsPanel = XposedUtils.getObjectField(palette, profile.palette("appsPanel"))
        if (appsPanel != null)
        {
            writeLongField(appsPanel, profile.appsPanel("background"), transparent)
        }

        val solidBackground =
            if (backgroundType == 1)
            {
                parseOptionalColor(ConfigManager.getBgColor())
            }
            else
            {
                null
            }

        val surfaceDark =
            solidBackground?.let(::isDarkColor)
                ?: readBooleanField(palette, profile.palette("isDark"))
                ?: false

        val letterColor = parseOptionalColor(
            ConfigManager.getLetterKeycapColor(),
            ConfigManager.getLetterKeycapOpacity()
        )

        if (letterColor != null)
        {
            writeLongField(palette, profile.palette("keyBackgroundDefault"), composeColor(letterColor))
            writeLongField(
                palette,
                profile.palette("keyBackgroundPressed"),
                composeColor(
                    resolvePressedColor(
                        letterColor,
                        isDarkColor(letterColor)
                    )
                )
            )
        }

        val functionColor = parseOptionalColor(
            ConfigManager.getFunctionKeycapColor(),
            ConfigManager.getFunctionKeycapOpacity()
        )

        if (functionColor != null)
        {
            val value = composeColor(functionColor)
            listOf(
                "keyBackgroundSpecial",
                "keyBackgroundEnter",
                "keyBackgroundEnterGradientEnd"
            ).forEach { label ->
                writeLongField(palette, profile.palette(label), value)
            }
        }

        val customText = parseOptionalColor(ConfigManager.getTextColor())
        val keySurfaceDark =
            (letterColor ?: functionColor)
                ?.let(::isDarkColor)
                ?: surfaceDark

        val shouldAdaptKeyText =
            backgroundType == 1 ||
                customText != null ||
                letterColor != null ||
                functionColor != null

        if (shouldAdaptKeyText)
        {
            val primary =
                customText
                    ?: if (keySurfaceDark)
                    {
                        Color.argb(242, 255, 255, 255)
                    }
                    else
                    {
                        Color.argb(230, 0, 0, 0)
                    }

            val secondary =
                customText?.let {
                    withAlpha(
                        it,
                        (Color.alpha(it) * 0.82f).toInt()
                    )
                } ?: if (keySurfaceDark)
                {
                    Color.argb(217, 255, 255, 255)
                }
                else
                {
                    Color.argb(178, 0, 0, 0)
                }

            val primaryCompose = composeColor(primary)
            val secondaryCompose = composeColor(secondary)

            listOf(
                "keyTextColor",
                "keyTextColorEnter",
                "modeSelectorTitleColor",
                "navBackIconColor",
                "toolbarIconColor",
                "toolbarIconColorCollapse",
                "bottomBarIconColor",
                "candidateExpandIconColor",
                "modeSelectorUnselectedIconColor",
                "modeSelectorUnselectedTextColor"
            ).forEach { label ->
                writeLongField(
                    palette,
                    profile.palette(label),
                    primaryCompose
                )
            }

            listOf(
                "keyTextColorSecondary",
                "keyHintColor",
                "symbolLockIndicatorInactive"
            ).forEach { label ->
                writeLongField(
                    palette,
                    profile.palette(label),
                    secondaryCompose
                )
            }
        }

        val menuCardColor = parseOptionalColor(
            ConfigManager.getMenuCardColor(),
            ConfigManager.getMenuCardOpacity()
        )

        if (appsPanel != null)
        {
            val menuSurfaceDark =
                menuCardColor
                    ?.let(::isDarkColor)
                    ?: surfaceDark

            val menuPrimary =
                customText
                    ?: if (menuSurfaceDark)
                    {
                        Color.argb(242, 255, 255, 255)
                    }
                    else
                    {
                        Color.argb(230, 0, 0, 0)
                    }

            val menuSecondary =
                customText?.let {
                    withAlpha(
                        it,
                        (Color.alpha(it) * 0.82f).toInt()
                    )
                } ?: if (menuSurfaceDark)
                {
                    Color.argb(217, 255, 255, 255)
                }
                else
                {
                    Color.argb(178, 0, 0, 0)
                }

            if (
                backgroundType == 1 ||
                customText != null ||
                menuCardColor != null
            )
            {
                val primaryCompose = composeColor(menuPrimary)
                val secondaryCompose = composeColor(menuSecondary)

                listOf(
                    "cardIconColor",
                    "cardTextColor",
                    "backArrowColor",
                    "tooltipTextColor"
                ).forEach { label ->
                    writeLongField(
                        appsPanel,
                        profile.appsPanel(label),
                        primaryCompose
                    )
                }

                writeLongField(
                    appsPanel,
                    profile.appsPanel("toggleActiveColor"),
                    secondaryCompose
                )
            }

            val resolvedCard =
                menuCardColor
                    ?: if (backgroundType == 1)
                    {
                        if (surfaceDark)
                        {
                            Color.argb(46, 255, 255, 255)
                        }
                        else
                        {
                            Color.argb(105, 255, 255, 255)
                        }
                    }
                    else
                    {
                        null
                    }

            if (resolvedCard != null)
            {
                val value = composeColor(resolvedCard)
                writeLongField(appsPanel, profile.appsPanel("cardBackground"), value)
                writeLongField(appsPanel, profile.appsPanel("tooltipBackground"), value)
            }
        }
    }

    private fun installClipboardPopupHook(module: XposedModule)
    {
        PopupWindow::class.java.declaredMethods
            .filter {
                it.name == "showAtLocation" &&
                    it.parameterTypes.size == 4
            }
            .forEach { method ->
                method.isAccessible = true

                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val popup = chain.thisObject as? PopupWindow

                    if (popup?.javaClass?.name == CLIPBOARD_POPUP_CLASS)
                    {
                        applyClipboardPopupStyle(module, popup)
                    }

                    result
                }
            }

        XposedUtils.log(
            module,
            "KeyboardStyleV209Hook: Hooked clipboard popup styling"
        )
    }

    private fun applyClipboardPopupStyle(
        module: XposedModule,
        popup: PopupWindow
    )
    {
        val service = XposedUtils.getObjectField(
            popup,
            "mInputMethodService"
        ) as? android.inputmethodservice.InputMethodService ?: return

        ConfigManager.syncFromProvider(service)
        if (!ConfigManager.isStyleEnabled()) return

        val root = popup.contentView ?: return
        val inside = findViewByResourceName(root, "inside_view") ?: root
        activeClipboardPopups[popup] = ClipboardPopupState(
            service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
            usesNativeClipboardColors()
        )
        // Native docked mode uses MATCH_PARENT; floating modes specify a
        // finite width. Window size is reliable even in a narrow split screen.
        val roundBottom = popup.width > 0
        val surface = clipboardGlass.getOrPut(inside) {
            CompositorGlassSurface(null, popupWindow = true,
                roundBottom = roundBottom).also { retained ->
                inside.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) = Unit
                    override fun onViewDetachedFromWindow(view: View) {
                        retained.remove()
                        clipboardGlass.remove(view)
                        activeClipboardPopups.remove(popup)
                        view.removeOnAttachStateChangeListener(this)
                    }
                })
            }
        }
        surface.setRoundBottomCorners(roundBottom)

        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        root.background = null
        findViewByResourceName(root, "outside_view")?.background = null

        popup.javaClass.classLoader?.let { loader ->
            installClipboardAdapterHooks(module, loader)
        }

        inside.post {
            if (!ConfigManager.isStyleEnabled() || !popup.isShowing || !inside.isAttachedToWindow) {
                surface.remove()
                return@post
            }
            try
            {
                when (ConfigManager.getBgType())
                {
                    0 ->
                    {
                        val helper = XposedUtils.getObjectField(
                            service,
                            "hyperMaterialHelper"
                        )

                        if (helper != null)
                        {
                            updateCachedGlassTokens(
                                helper,
                                ConfigManager.getBlurRadius()
                            )

                            inside.background = ColorDrawable(
                                nativeBackgroundColor(
                                    service,
                                    ConfigManager.getOpacity()
                                )
                            )

                            if (usesCompositorGlass() && surface.ensure(module, service, inside)) {
                                clearPopupNativeMaterial(module, service, inside)
                            } else {
                                surface.remove()
                                invokeHelper(helper, profile.helperApplyMethod, inside)
                            }
                        }
                    }

                    1 ->
                    {
                        surface.remove()
                        inside.background = ColorDrawable(
                            ReadabilityScrim.apply(
                                service,
                                resolveSolidColor(
                                    ConfigManager.getBgColor(),
                                    ConfigManager.getOpacity()
                                )
                            )
                        )
                    }

                    2 ->
                    {
                        surface.remove()
                        val bitmap = getOrLoadBitmap(service)

                        if (bitmap != null && !bitmap.isRecycled)
                        {
                            inside.background = ReadabilityScrim.behind(
                                service,
                                BitmapDrawable(
                                    service.resources,
                                    bitmap
                                ).apply {
                                    alpha =
                                        ConfigManager.getOpacity()
                                            .coerceIn(0, 100) *
                                            255 /
                                            100
                                }
                            )
                        }
                    }
                }

                inside.foreground = null
                applyTopCornerOutline(
                    inside,
                    ConfigManager.getCornerRadius().coerceAtLeast(0) *
                        inside.resources.displayMetrics.density,
                    roundBottom = roundBottom
                )

                styleClipboardViewTree(root)
                AppearanceDiagnostics.record(module, service, "clipboard-popup", inside, surface.owns(inside))

                if (ConfigManager.isVerboseLogEnabled())
                {
                    XposedUtils.log(
                        module,
                        "KeyboardStyleV209Hook: clipboard panel synchronized"
                    )
                }
            }
            catch (t: Throwable)
            {
                XposedUtils.logError(
                    module,
                    "KeyboardStyleV209Hook: clipboard styling failed",
                    t
                )
            }
        }
    }

    private fun refreshClipboardPopups(module: XposedModule,
        service: android.inputmethodservice.InputMethodService) {
        activeClipboardPopups.keys.toList().forEach { popup ->
            if (popup.isShowing && XposedUtils.getObjectField(popup, "mInputMethodService") === service) {
                val previous = activeClipboardPopups[popup]
                val nativeColors = usesNativeClipboardColors()
                val nightMode = service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (!ConfigManager.isStyleEnabled() || previous?.nativeColors != nativeColors ||
                    (nativeColors && previous.nightMode != nightMode)) {
                    // Native cards, text and icons are styled during inflation.
                    // Reopen with a fresh native tree instead of retaining old
                    // custom colors or stale light/dark resource drawables.
                    popup.dismiss()
                } else {
                    applyClipboardPopupStyle(module, popup)
                }
            }
        }
    }

    private fun installClipboardAdapterHooks(
        module: XposedModule,
        classLoader: ClassLoader
    )
    {
        installClipboardSwipeBackgroundHooks(
            module,
            classLoader
        )

        CLIPBOARD_ADAPTER_CLASSES.forEach { className ->
            val adapterClass =
                XposedUtils.findClass(className, classLoader)
                    ?: return@forEach

            if (!clipboardAdapterHooks.add(adapterClass))
            {
                return@forEach
            }

            adapterClass.declaredMethods
                .filter {
                    !it.isBridge &&
                        !it.isSynthetic &&
                        (
                            (
                                it.name == "onBindViewHolder" &&
                                    it.parameterTypes.size >= 2
                                ) ||
                                (
                                    it.name == "onCreateViewHolder" &&
                                        it.parameterTypes.size >= 2
                                    )
                            )
                }
                .forEach { method ->
                    method.isAccessible = true

                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()

                        if (ConfigManager.isStyleEnabled())
                        {
                            val holder =
                                if (method.name == "onCreateViewHolder")
                                {
                                    result
                                }
                                else
                                {
                                    chain.getArg(0)
                                }

                            val itemView = holder?.let {
                                XposedUtils.getObjectField(
                                    it,
                                    "itemView"
                                ) as? View
                            }

                            itemView?.let(::styleClipboardViewTree)
                        }

                        result
                    }
                }
        }
    }

    private fun installClipboardSwipeBackgroundHooks(
        module: XposedModule,
        classLoader: ClassLoader
    )
    {
        CLIPBOARD_SWIPE_LISTENER_CLASSES.forEach { className ->
            val listenerClass =
                XposedUtils.findClass(
                    className,
                    classLoader
                ) ?: return@forEach

            if (!clipboardSwipeHooks.add(listenerClass))
            {
                return@forEach
            }

            listenerClass.declaredMethods
                .filter {
                    it.name == "onOpened" ||
                        it.name == "onSlide" ||
                        it.name == "viewSlideRelease"
                }
                .forEach { method ->
                    method.isAccessible = true

                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()

                        if (
                            ConfigManager.isStyleEnabled() &&
                            !usesNativeClipboardColors()
                        )
                        {
                            val holder =
                                XposedUtils.getObjectField(
                                    chain.thisObject,
                                    "this\$0"
                                )
                            val itemLayout = holder?.let {
                                XposedUtils.getObjectField(
                                    it,
                                    "itemLayout"
                                ) as? View
                            }

                            itemLayout?.let(
                                ::styleClipboardViewTree
                            )
                        }

                        result
                    }
                }
        }
    }

    private fun styleClipboardViewTree(view: View)
    {
        if (usesNativeClipboardColors()) return

        val palette = clipboardPalette(view)
        styleClipboardViewTree(view, palette)
    }

    private fun usesNativeClipboardColors(): Boolean
    {
        return ConfigManager.getBgType() == 0 &&
            parseOptionalColor(ConfigManager.getTextColor()) == null &&
            parseOptionalColor(ConfigManager.getClipboardCardColor()) == null
    }

    private fun styleClipboardViewTree(
        view: View,
        palette: ClipboardPalette
    )
    {
        val name = resourceEntryName(view)
        val density = view.resources.displayMetrics.density
        val itemRadius = 18f * density
        val smallRadius = 12f * density

        when (name)
        {
            "outside_view",
            "clipboard_title_bar",
            "list_view_layout",
            "recycler_view" ->
            {
                clearClipboardBackground(view)
            }

            "clipboard_item_layout",
            "phrase_item_layout" ->
            {
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

            "clipboard_loading" ->
            {
                applyClipboardBackground(
                    view,
                    clipboardBackgroundSignature(
                        STYLE_LOADING_CARD,
                        palette.card,
                        itemRadius
                    )
                ) {
                    roundedBackground(
                        palette.card,
                        itemRadius
                    )
                }
            }

            "clipboard_text",
            "phrase_text" ->
            {
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

                if (view is TextView)
                {
                    view.setTextColor(
                        ColorStateList(
                            arrayOf(
                                intArrayOf(android.R.attr.state_selected),
                                intArrayOf()
                            ),
                            intArrayOf(
                                palette.primaryText,
                                palette.secondaryText
                            )
                        )
                    )
                }
            }

            "clipboard_text_item_top",
            "clipboard_text_item_bottom",
            "image_end_show",
            "phrase_text_item",
            "text_view" ->
            {
                (view as? TextView)?.setTextColor(
                    palette.primaryText
                )
            }

            "clipboard_no_items",
            "loading_text",
            "clipboard_across_devices_tip_text" ->
            {
                (view as? TextView)?.setTextColor(
                    palette.secondaryText
                )
            }

            "pack_up_view",
            "delete_and_add_action_button" ->
            {
                (view as? ImageView)?.setColorFilter(
                    palette.primaryText
                )
            }
        }

        if (view is ViewGroup)
        {
            if (
                containsNamedChildren(
                    view,
                    "clipboard_text",
                    "phrase_text"
                )
            )
            {
                applyClipboardBackground(
                    view,
                    clipboardBackgroundSignature(
                        STYLE_TAB_TRACK,
                        palette.tabTrack,
                        smallRadius
                    )
                ) {
                    roundedBackground(
                        palette.tabTrack,
                        smallRadius
                    )
                }
            }

            if (
                name == "clipboard_tip_view" &&
                view.childCount > 0
            )
            {
                val tipCard = view.getChildAt(0)

                applyClipboardBackground(
                    tipCard,
                    clipboardBackgroundSignature(
                        STYLE_TIP_CARD,
                        palette.card,
                        itemRadius
                    )
                ) {
                    roundedBackground(
                        palette.card,
                        itemRadius
                    )
                }
            }

            for (index in 0 until view.childCount)
            {
                styleClipboardViewTree(
                    view.getChildAt(index),
                    palette
                )
            }
        }
    }

    private fun clipboardPalette(view: View): ClipboardPalette
    {
        val strength =
            ConfigManager.getOpacity()
                .coerceIn(0, 100) /
                100f

        val customCard = parseOptionalColor(
            ConfigManager.getClipboardCardColor(),
            ConfigManager.getClipboardCardOpacity()
        )

        val dark = when (ConfigManager.getBgType())
        {
            1 ->
            {
                isDarkColor(
                    parseOptionalColor(
                        ConfigManager.getBgColor()
                    ) ?: Color.WHITE
                )
            }

            else ->
            {
                (
                    view.resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK
                    ) == Configuration.UI_MODE_NIGHT_YES
            }
        }

        val customText =
            parseOptionalColor(
                ConfigManager.getTextColor()
            )

        val primary =
            customText
                ?: if (dark)
                {
                    Color.rgb(245, 247, 252)
                }
                else
                {
                    Color.rgb(20, 22, 27)
                }

        val secondary =
            withAlpha(
                primary,
                if (dark) 178 else 150
            )

        val card =
            customCard
                ?: if (dark)
                {
                    Color.argb(
                        (92 * strength).toInt(),
                        255,
                        255,
                        255
                    )
                }
                else
                {
                    Color.argb(
                        (145 * strength).toInt(),
                        255,
                        255,
                        255
                    )
                }

        val pressed =
            customCard?.let {
                resolvePressedColor(
                    it,
                    dark
                )
            } ?: if (dark)
            {
                Color.argb(
                    (135 * strength).toInt(),
                    255,
                    255,
                    255
                )
            }
            else
            {
                Color.argb(
                    (190 * strength).toInt(),
                    255,
                    255,
                    255
                )
            }

        val tabTrack =
            if (dark)
            {
                Color.argb(
                    (62 * strength).toInt(),
                    255,
                    255,
                    255
                )
            }
            else
            {
                Color.argb(
                    (105 * strength).toInt(),
                    255,
                    255,
                    255
                )
            }

        val tabSelected =
            customCard
                ?: if (dark)
                {
                    Color.argb(
                        (118 * strength).toInt(),
                        255,
                        255,
                        255
                    )
                }
                else
                {
                    Color.argb(
                        (205 * strength).toInt(),
                        255,
                        255,
                        255
                    )
                }

        return ClipboardPalette(
            card,
            pressed,
            tabTrack,
            tabSelected,
            primary,
            secondary
        )
    }

    private fun roundedBackground(
        color: Int,
        radius: Float
    ): Drawable
    {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun statefulRoundedBackground(
        normal: Int,
        pressed: Int,
        radius: Float
    ): Drawable
    {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundedBackground(
                    pressed,
                    radius
                )
            )
            addState(
                intArrayOf(),
                roundedBackground(
                    normal,
                    radius
                )
            )
        }
    }

    private fun selectedRoundedBackground(
        selected: Int,
        normal: Int,
        radius: Float
    ): Drawable
    {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_selected),
                roundedBackground(
                    selected,
                    radius
                )
            )
            addState(
                intArrayOf(),
                roundedBackground(
                    normal,
                    radius
                )
            )
        }
    }

    private fun applyClipboardBackground(
        view: View,
        signature: Int,
        create: () -> Drawable
    )
    {
        val cached = clipboardAppliedBackgrounds[view]

        if (cached?.signature == signature)
        {
            if (view.background !== cached.drawable)
            {
                view.background = cached.drawable
            }
            return
        }

        val drawable = create()
        view.background = drawable

        clipboardAppliedBackgrounds[view] =
            AppliedClipboardBackground(
                signature,
                drawable
            )
    }

    private fun clearClipboardBackground(view: View)
    {
        clipboardAppliedBackgrounds.remove(view)

        if (view.background != null)
        {
            view.background = null
        }
    }

    private fun clipboardBackgroundSignature(
        kind: Int,
        vararg values: Any
    ): Int
    {
        var result = kind

        values.forEach { value ->
            result =
                31 *
                    result +
                    value.hashCode()
        }

        return result
    }

    private fun containsNamedChildren(
        group: ViewGroup,
        vararg names: String
    ): Boolean
    {
        val found = mutableSetOf<String>()

        for (index in 0 until group.childCount)
        {
            resourceEntryName(
                group.getChildAt(index)
            )?.let(found::add)
        }

        return names.all(found::contains)
    }

    private fun findViewByResourceName(
        view: View,
        name: String
    ): View?
    {
        if (resourceEntryName(view) == name)
        {
            return view
        }

        if (view is ViewGroup)
        {
            for (index in 0 until view.childCount)
            {
                val found = findViewByResourceName(
                    view.getChildAt(index),
                    name
                )

                if (found != null)
                {
                    return found
                }
            }
        }

        return null
    }

    private fun resourceEntryName(view: View): String?
    {
        if (view.id == View.NO_ID)
        {
            return null
        }

        return try
        {
            view.resources.getResourceEntryName(view.id)
        }
        catch (_: Throwable)
        {
            null
        }
    }

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

    private const val CLIPBOARD_POPUP_CLASS =
        "com.miui.inputmethod.InputMethodClipboardPhrasePopupView"

    private val CLIPBOARD_ADAPTER_CLASSES = arrayOf(
        "com.miui.inputmethod.InputMethodClipboardAdapter",
        "com.miui.inputmethod.InputMethodClipboardHeaderAdapter",
        "com.miui.inputmethod.InputMethodPhraseAdapter"
    )

    private val CLIPBOARD_SWIPE_LISTENER_CLASSES = arrayOf(
        "com.miui.inputmethod.InputMethodClipboardAdapter\$ViewHolder\$1",
        "com.miui.inputmethod.InputMethodPhraseAdapter\$ViewHolder\$1"
    )

    private const val STYLE_ITEM_CARD = 1
    private const val STYLE_LOADING_CARD = 2
    private const val STYLE_TAB = 3
    private const val STYLE_TAB_TRACK = 4
    private const val STYLE_TIP_CARD = 5

    private fun applyStyle(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        rootView: View
    )
    {
        ConfigManager.syncFromProvider(service)
        if (!ConfigManager.isStyleEnabled()) return

        applyWindowStyle(service)
        rootView.background = null

        val target = if (rootView is ViewGroup && rootView.childCount > 0)
        {
            rootView.getChildAt(0)
        }
        else
        {
            rootView
        }

        target.background = null

        val helper = XposedUtils.getObjectField(service, "hyperMaterialHelper") ?: return
        forceMaterialStateEnabled(helper)

        val material = XposedUtils.getObjectField(helper, profile.helperMaterialField) as? View ?: return

        applyMaterialStyle(module, service, helper, material)
        material.post {
            applyMaterialStyle(module, service, helper, material)
        }
    }

    private fun usesCompositorGlass(): Boolean = GlassTransitionPolicy.usesCompositor(
        ConfigManager.isStyleEnabled(), ConfigManager.getBgType(),
        ConfigManager.getOpacity(), ConfigManager.getBlurRadius()
    )

    private fun ownsKeyboardGlass(material: Any?): Boolean =
        compositorGlass.owns(material) || floatingGlass.owns(material)

    private fun removeKeyboardGlass() {
        compositorGlass.remove()
        floatingGlass.remove()
    }

    private fun isPopupMaterial(service: android.inputmethodservice.InputMethodService, material: View): Boolean =
        material.rootView !== service.window?.window?.decorView

    // Cleanup on helper.i would clear the keyboard, not the clipboard panel.
    // Invoke the verified material API on this popup view only.
    private fun clearPopupNativeMaterial(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        material: View
    ) {
        try {
            val api = Class.forName(profile.materialApiClassName, false, service.classLoader)
            val clear = api.declaredMethods.first {
                it.name == profile.materialApiClearMethod && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == View::class.java
            }
            clear.isAccessible = true
            clear.invoke(null, material, null)
            View::class.java.getMethod("setPassWindowBlurEnabled", java.lang.Boolean.TYPE)
                .invoke(material, false)
        } catch (t: Throwable) {
            XposedUtils.logError(module, "KeyboardStyleV209Hook: popup native cleanup failed", t)
        }
    }

    private fun useCompositorGlass(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        helper: Any,
        material: View,
        syncWithDraw: Boolean = false
    ) {
        if (!usesCompositorGlass()) {
            removeKeyboardGlass()
            return
        }
        if (material.width <= 0 || material.height <= 0 || !material.isAttachedToWindow) {
            awaitMaterialSize(module, service, helper, material)
            return
        }
        val popup = isPopupMaterial(service, material)
        val surface = if (popup) floatingGlass else compositorGlass
        if (surface.ensure(module, service, material, syncWithDraw)) {
            if (popup) compositorGlass.remove() else floatingGlass.remove()
            // Clear both pass-window blur and the inner-shadow shader only
            // after the replacement is ready; retain native rendering on failure.
            invokeHelper(helper, profile.materialCleanupMethod)
        }
        if (popup) AppearanceDiagnostics.record(module, service, "floating-keyboard", material, surface.owns(material))
    }

    // After a cold start the Compose material is still 0px tall at showWindow
    // and b(View). Without a retry the whole first show uses Xiaomi's pass-window
    // blur, whose dark edge sweeps up with the IME. Attach on the first sized draw.
    private fun awaitMaterialSize(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        helper: Any,
        material: View
    ) {
        if (pendingMaterialSize.containsKey(material)) return
        val observer = material.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val current = XposedUtils.getObjectField(helper, profile.helperMaterialField) === material
                if (current && (material.width <= 0 || material.height <= 0 || !material.isAttachedToWindow)) {
                    return true
                }
                pendingMaterialSize.remove(material)
                material.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                if (current) useCompositorGlass(module, service, helper, material, syncWithDraw = true)
                return true
            }
        }
        pendingMaterialSize[material] = listener
        observer.addOnPreDrawListener(listener)
    }

    private fun installWindowTransitionHooks(module: XposedModule) {
        val serviceClass = android.inputmethodservice.InputMethodService::class.java
        listOfNotNull(
            XposedUtils.findMethodExact(serviceClass, "showWindow", java.lang.Boolean.TYPE),
            XposedUtils.findMethodExact(serviceClass, "hideWindow")
        ).forEach { method ->
            module.hook(method).intercept { chain ->
                val service = chain.thisObject as? android.inputmethodservice.InputMethodService
                fun prepare() {
                    if (service == null) return
                    ConfigManager.syncFromProvider(service)
                    if (!ConfigManager.isStyleEnabled()) {
                        removeKeyboardGlass()
                        clipboardGlass.values.toList().forEach { it.remove() }
                        return
                    }
                    applyWindowStyle(service)
                    val helper = XposedUtils.getObjectField(service, "hyperMaterialHelper") ?: return
                    val material = XposedUtils.getObjectField(helper, profile.helperMaterialField) as? View ?: return
                    useCompositorGlass(module, service, helper, material)
                }
                // Insets animation is submitted before onWindowShown/Hidden.
                prepare()
                val result = chain.proceed()
                prepare()
                result
            }
        }
        XposedUtils.findMethodExact(serviceClass, "onDestroy")?.let { method ->
            module.hook(method).intercept { chain ->
                removeKeyboardGlass()
                clipboardGlass.values.toList().forEach { it.remove() }
                clipboardGlass.clear()
                activeClipboardPopups.clear()
                chain.proceed()
            }
        }
        XposedUtils.log(module, "KeyboardStyleV209Hook: compositor show/hide protection installed")
    }

    private fun applyWindowStyle(service: android.inputmethodservice.InputMethodService)
    {
        try
        {
            val window = service.window?.window ?: return
            val color = if (hasUndockedKeyboard(service)) Color.TRANSPARENT else when (ConfigManager.getBgType())
            {
                0 -> nativeBackgroundColor(service, ConfigManager.getOpacity())
                1 -> ReadabilityScrim.apply(
                    service,
                    resolveSolidColor(ConfigManager.getBgColor(), ConfigManager.getOpacity())
                )
                else -> Color.TRANSPARENT
            }

            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.setDimAmount(0f)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.decorView.setBackgroundColor(Color.TRANSPARENT)
            window.setNavigationBarColor(color)
            window.setNavigationBarContrastEnforced(false)
            window.navigationBarDividerColor = Color.TRANSPARENT
            if (usesCompositorGlass()) {
                // OPAQUE_NAVIGATION_BAR | SEMI_TRANSPARENT_NAVIGATION_BAR.
                // Leave light/dark navigation icon appearance untouched.
                window.insetsController?.setSystemBarsAppearance(0, 2 or 64)
            }
        }
        catch (_: Throwable)
        {
        }
    }

    private fun hasUndockedKeyboard(service: android.inputmethodservice.InputMethodService): Boolean {
        val material = XposedUtils.getObjectField(service, "hyperMaterialHelper")?.let {
            XposedUtils.getObjectField(it, profile.helperMaterialField) as? View
        }
        if (material != null && isPopupMaterial(service, material)) return true
        // Verified 21053 UI state: v() is floating; n()==EXTERNAL_HW uses a
        // compact hardware-keyboard toolbar rather than the docked background.
        if (TargetCompatibility.detect(service.classLoader) != TargetGeneration.V21053) return false
        return runCatching {
            val manager = service.javaClass.getMethod("getUiStateManager\$app_iflytekFullRelease").invoke(service)
            manager.javaClass.getMethod("v").invoke(manager) == true ||
                (manager.javaClass.getMethod("n").invoke(manager) as? Enum<*>)?.name == "EXTERNAL_HW"
        }.getOrDefault(false)
    }

    private fun applyMaterialStyle(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        helper: Any,
        material: View
    )
    {
        if (!ConfigManager.isStyleEnabled() || XposedUtils.getObjectField(helper, profile.helperMaterialField) !== material) return
        try
        {
            if (isPopupMaterial(service, material)) watchPopupMaterial(module, service, helper, material)
            applyWindowStyle(service)
            when (ConfigManager.getBgType())
            {
                0 ->
                {
                    updateCachedGlassTokens(helper, ConfigManager.getBlurRadius())
                    material.background = ColorDrawable(
                        nativeBackgroundColor(service, ConfigManager.getOpacity())
                    )
                    material.alpha = 1f
                    invokeHelper(helper, profile.helperApplyMethod, material)
                }

                1 ->
                {
                    removeKeyboardGlass()
                    invokeHelper(helper, profile.materialCleanupMethod)
                    material.background = ColorDrawable(
                        ReadabilityScrim.apply(
                            service,
                            resolveSolidColor(
                                ConfigManager.getBgColor(),
                                ConfigManager.getOpacity()
                            )
                        )
                    )
                    material.alpha = 1f
                }

                2 ->
                {
                    removeKeyboardGlass()
                    invokeHelper(helper, profile.materialCleanupMethod)
                    val bitmap = getOrLoadBitmap(service)

                    if (bitmap != null && !bitmap.isRecycled)
                    {
                        material.background = ReadabilityScrim.behind(
                            service,
                            BitmapDrawable(service.resources, bitmap).apply {
                                alpha = ConfigManager.getOpacity().coerceIn(0, 100) * 255 / 100
                            }
                        )
                    }

                    material.alpha = 1f
                }
            }

            material.elevation = 0f
            material.translationZ = 0f
            material.visibility = View.VISIBLE

            applyTopCornerOutline(
                material,
                ConfigManager.getCornerRadius().coerceAtLeast(0) *
                    material.resources.displayMetrics.density,
                roundBottom = isPopupMaterial(service, material)
            )

            if (ConfigManager.isVerboseLogEnabled())
            {
                XposedUtils.log(
                    module,
                    "KeyboardStyleV209Hook: material refreshed, type=" +
                        ConfigManager.getBgType() +
                        ", opacity=" +
                        ConfigManager.getOpacity() +
                        ", blur=" +
                        ConfigManager.getBlurRadius()
                )
            }
        }
        catch (t: Throwable)
        {
            XposedUtils.logError(
                module,
                "KeyboardStyleV209Hook: failed to refresh material",
                t
            )
        }
    }

    private fun watchPopupMaterial(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        helper: Any,
        material: View
    ) {
        if (popupMaterialListeners.containsKey(material)) return
        val layoutListener = View.OnLayoutChangeListener { view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                applyMaterialStyle(module, service, helper, view)
            }
        }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.post { applyMaterialStyle(module, service, helper, view) }
            }
            override fun onViewDetachedFromWindow(view: View) {
                view.removeOnLayoutChangeListener(layoutListener)
                popupMaterialListeners.remove(view)
                view.removeOnAttachStateChangeListener(this)
            }
        }
        popupMaterialListeners[material] = attachListener
        material.addOnLayoutChangeListener(layoutListener)
        material.addOnAttachStateChangeListener(attachListener)
    }

    private fun forceMaterialStateEnabled(helper: Any)
    {
        try
        {
            val state = XposedUtils.getObjectField(helper, profile.helperStateField) ?: return
            val setValue = state.javaClass.methods.firstOrNull {
                it.name == "setValue" && it.parameterTypes.size == 1
            } ?: return

            setValue.invoke(state, true)
        }
        catch (_: Throwable)
        {
        }
    }

    private fun updateCachedGlassTokens(helper: Any, blurRadiusDp: Int): Boolean
    {
        var updated = false

        profile.helperTokenFields.forEach { fieldName ->
            try
            {
                val lazyValue = XposedUtils.getObjectField(helper, fieldName) ?: return@forEach
                val getValue = lazyValue.javaClass.methods.firstOrNull {
                    it.name == "getValue" && it.parameterTypes.isEmpty()
                } ?: return@forEach
                val token = getValue.invoke(lazyValue) ?: return@forEach

                XposedUtils.setObjectField(
                    token,
                    profile.tokenBlurField,
                    blurRadiusDp.coerceIn(0, 400)
                )

                val blends = XposedUtils.getObjectField(token, profile.tokenBlendField) as? IntArray
                if (blends != null)
                {
                    XposedUtils.setObjectField(token, profile.tokenBlendField, IntArray(blends.size))
                }

                updated = true
            }
            catch (_: Throwable)
            {
            }
        }

        return updated
    }

    private fun nativeBackgroundColor(
        service: android.inputmethodservice.InputMethodService,
        opacity: Int
    ): Int
    {
        val palette = activePalette ?: staticNativePalette(service)
        val originalColor = palette?.let { current ->
            synchronized(originalPaletteColors)
            {
                val background = profile.palette("keyboardBackground")
                originalPaletteColors[current]?.get(background)
                    ?: readLongField(current, background)
            }
        }
        val color = originalColor
            ?.let { (it ushr 32).toInt() }
            ?: Color.TRANSPARENT

        return ReadabilityScrim.apply(service, BackgroundOpacity.argb(color, opacity))
    }

    private fun staticNativePalette(service: android.inputmethodservice.InputMethodService): Any?
    {
        return try
        {
            val holder = Class.forName(profile.paletteHolderClassName, false, service.classLoader)
            val dark = (
                service.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
                ) == Configuration.UI_MODE_NIGHT_YES

            profile.paletteHolderFields
                .map { holder.getDeclaredField(it).apply { isAccessible = true }.get(null) }
                .firstOrNull { readBooleanField(it, profile.palette("isDark")) == dark }
        }
        catch (_: Throwable)
        {
            null
        }
    }

    private fun getOrLoadBitmap(context: android.content.Context): Bitmap?
    {
        val version = ConfigManager.getBgImageVersion()
        val current = cachedBitmap

        if (current != null && !current.isRecycled && cachedImageVersion == version)
        {
            return current
        }

        return try
        {
            val uri = Uri.parse("content://io.mo.xatype.logprovider/bg_image.png")
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }?.also { bitmap ->
                cachedBitmap?.takeIf { !it.isRecycled }?.recycle()
                cachedBitmap = bitmap
                cachedImageVersion = version
            }
        }
        catch (_: Throwable)
        {
            null
        }
    }

    private fun applyTopCornerOutline(view: View, radiusPx: Float, roundBottom: Boolean = false)
    {
        if (radiusPx <= 0f)
        {
            view.clipToOutline = false
            return
        }

        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider()
        {
            override fun getOutline(target: View, outline: Outline)
            {
                if (target.width > 0 && target.height > 0)
                {
                    outline.setRoundRect(
                        0,
                        0,
                        target.width,
                        target.height + if (roundBottom) 0 else radiusPx.toInt(),
                        radiusPx
                    )
                }
            }
        }

        view.invalidateOutline()
    }

    private fun snapshotLongFields(instance: Any): Map<String, Long>
    {
        val result = LinkedHashMap<String, Long>()

        instance.javaClass.declaredFields
            .filter { it.type == Long::class.javaPrimitiveType }
            .forEach { field ->
                try
                {
                    field.isAccessible = true
                    result[field.name] = field.getLong(instance)
                }
                catch (_: Throwable)
                {
                }
            }

        return result
    }

    private fun readBooleanField(instance: Any, fieldName: String): Boolean?
    {
        return try
        {
            instance.javaClass.getDeclaredField(fieldName).apply {
                isAccessible = true
            }.getBoolean(instance)
        }
        catch (_: Throwable)
        {
            null
        }
    }

    private fun readLongField(instance: Any, fieldName: String): Long?
    {
        return try
        {
            instance.javaClass.getDeclaredField(fieldName).apply {
                isAccessible = true
            }.getLong(instance)
        }
        catch (_: Throwable)
        {
            null
        }
    }

    private fun writeLongField(instance: Any, fieldName: String, value: Long)
    {
        try
        {
            instance.javaClass.getDeclaredField(fieldName).apply {
                isAccessible = true
            }.setLong(instance, value)
        }
        catch (_: Throwable)
        {
        }
    }

    private fun invokeHelper(helper: Any, methodName: String, vararg args: Any?): Any?
    {
        val method = helper.javaClass.declaredMethods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == args.size
        } ?: return null

        method.isAccessible = true
        return method.invoke(helper, *args)
    }

    private fun resolveSolidColor(value: String, opacity: Int): Int
    {
        val parsed = try
        {
            Color.parseColor(value)
        }
        catch (_: Throwable)
        {
            Color.parseColor("#1E1E2E")
        }

        return BackgroundOpacity.argb(parsed, opacity)
    }

    private fun parseOptionalColor(value: String, opacity: Int = 100): Int?
    {
        if (value.isBlank()) return null

        return try
        {
            BackgroundOpacity.argb(Color.parseColor(value), opacity)
        }
        catch (_: Throwable)
        {
            null
        }
    }

    private fun resolvePressedColor(color: Int, dark: Boolean): Int
    {
        val target = if (dark) 255 else 0
        val amount = if (dark) 0.18f else 0.14f

        fun blend(channel: Int): Int
        {
            return (channel + (target - channel) * amount)
                .toInt()
                .coerceIn(0, 255)
        }

        return Color.argb(
            Color.alpha(color),
            blend(Color.red(color)),
            blend(Color.green(color)),
            blend(Color.blue(color))
        )
    }

    private fun isDarkColor(color: Int): Boolean
    {
        return (
            299 * Color.red(color) +
                587 * Color.green(color) +
                114 * Color.blue(color)
            ) / 1000 < 150
    }

    private fun withAlpha(color: Int, alpha: Int): Int
    {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun composeColor(color: Int): Long
    {
        return (color.toLong() and 0xffffffffL) shl 32
    }
}
