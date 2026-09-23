package io.mo.xatype.hooks

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import io.github.libxposed.api.XposedModule
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.util.IdentityHashMap

object KeyboardStyleV209Hook
{
    private val originalPaletteColors = IdentityHashMap<Any, Map<String, Long>>()
    private val originalAppsPanelColors = IdentityHashMap<Any, Map<String, Long>>()

    @Volatile
    private var activePalette: Any? = null

    @Volatile
    private var cachedBitmap: Bitmap? = null

    @Volatile
    private var cachedImageVersion: Long = -1L

    fun install(module: XposedModule, classLoader: ClassLoader)
    {
        val serviceClass = XposedUtils.findClass("com.mi.ime.MiInputMethodService", classLoader)

        if (serviceClass == null)
        {
            XposedUtils.logError(module, "KeyboardStyleV209Hook: MiInputMethodService not found", null)
            return
        }

        installLifecycleHooks(module, serviceClass)
        installHyperMaterialHooks(module, classLoader)
        installPaletteHook(module, classLoader)

        XposedUtils.log(module, "KeyboardStyleV209Hook: v209 compatibility hooks installed")
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
    }

    private fun installHyperMaterialHooks(module: XposedModule, classLoader: ClassLoader)
    {
        val helperClass = XposedUtils.findClass("bb.b0", classLoader) ?: return

        helperClass.declaredMethods.firstOrNull {
            it.name == "g" &&
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

            XposedUtils.log(module, "KeyboardStyleV209Hook: Hooked bb.b0.g material support")
        }

        helperClass.declaredMethods.firstOrNull {
            it.name == "j" && it.parameterTypes.isEmpty()
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
                (it.name == "f" && it.parameterTypes.size == 3) ||
                    (it.name == "k" && it.parameterTypes.isEmpty())
            }
            .forEach { method ->
                method.isAccessible = true

                module.hook(method).intercept { chain ->
                    val result = chain.proceed()

                    if (ConfigManager.isStyleEnabled())
                    {
                        val helper = chain.thisObject
                        val service = XposedUtils.getObjectField(helper, "a") as?
                            android.inputmethodservice.InputMethodService
                        val material = XposedUtils.getObjectField(helper, "i") as? View

                        if (service != null && material != null)
                        {
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
        val paletteFactory = XposedUtils.findClass("na.u", classLoader) ?: return
        val method = paletteFactory.declaredMethods.firstOrNull {
            it.name == "z" &&
                it.parameterTypes.size == 1 &&
                it.returnType.name == "na.j"
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

        XposedUtils.log(module, "KeyboardStyleV209Hook: Hooked na.u.z Compose palette")
    }

    private fun rememberPalette(palette: Any)
    {
        if (!originalPaletteColors.containsKey(palette))
        {
            originalPaletteColors[palette] = snapshotLongFields(palette)
        }

        val appsPanel = XposedUtils.getObjectField(palette, "U")

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

        val appsPanel = XposedUtils.getObjectField(palette, "U")
        if (appsPanel != null)
        {
            originalAppsPanelColors[appsPanel]?.forEach { (name, value) ->
                writeLongField(appsPanel, name, value)
            }
        }
    }

    private fun patchPalette(palette: Any)
    {
        val transparent = composeColor(Color.TRANSPARENT)

        listOf("a", "b", "u").forEach { fieldName ->
            writeLongField(palette, fieldName, transparent)
        }

        val appsPanel = XposedUtils.getObjectField(palette, "U")
        if (appsPanel != null)
        {
            writeLongField(appsPanel, "a", transparent)
        }

        val letterColor = parseOptionalColor(
            ConfigManager.getLetterKeycapColor(),
            ConfigManager.getLetterKeycapOpacity()
        )

        if (letterColor != null)
        {
            writeLongField(palette, "c", composeColor(letterColor))
            writeLongField(
                palette,
                "d",
                composeColor(resolvePressedColor(letterColor, isDarkColor(letterColor)))
            )
        }

        val functionColor = parseOptionalColor(
            ConfigManager.getFunctionKeycapColor(),
            ConfigManager.getFunctionKeycapOpacity()
        )

        if (functionColor != null)
        {
            val value = composeColor(functionColor)
            listOf("e", "f", "g").forEach { fieldName ->
                writeLongField(palette, fieldName, value)
            }
        }

        val textColor = parseOptionalColor(ConfigManager.getTextColor())

        if (textColor != null)
        {
            val primary = composeColor(textColor)
            val secondary = composeColor(
                withAlpha(textColor, (Color.alpha(textColor) * 0.82f).toInt())
            )

            listOf("h", "j", "l", "m", "w", "x", "A", "I", "L", "M")
                .forEach { fieldName ->
                    writeLongField(palette, fieldName, primary)
                }

            listOf("i", "k", "y").forEach { fieldName ->
                writeLongField(palette, fieldName, secondary)
            }

            if (appsPanel != null)
            {
                listOf("c", "d", "f", "g", "i").forEach { fieldName ->
                    writeLongField(appsPanel, fieldName, primary)
                }
            }
        }

        val menuCardColor = parseOptionalColor(
            ConfigManager.getMenuCardColor(),
            ConfigManager.getMenuCardOpacity()
        )

        if (menuCardColor != null && appsPanel != null)
        {
            val value = composeColor(menuCardColor)
            writeLongField(appsPanel, "b", value)
            writeLongField(appsPanel, "h", value)
        }
    }

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

        val material = XposedUtils.getObjectField(helper, "i") as? View ?: return

        material.post {
            applyMaterialStyle(module, service, helper, material)
        }
    }

    private fun applyWindowStyle(service: android.inputmethodservice.InputMethodService)
    {
        try
        {
            val window = service.window?.window ?: return
            val color = when (ConfigManager.getBgType())
            {
                0 -> nativeBackgroundColor(service, ConfigManager.getOpacity())
                1 -> resolveSolidColor(ConfigManager.getBgColor(), ConfigManager.getOpacity())
                else -> Color.TRANSPARENT
            }

            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.setDimAmount(0f)
            window.setNavigationBarColor(color)
            window.setNavigationBarContrastEnforced(false)
        }
        catch (_: Throwable)
        {
        }
    }

    private fun applyMaterialStyle(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        helper: Any,
        material: View
    )
    {
        try
        {
            when (ConfigManager.getBgType())
            {
                0 ->
                {
                    updateCachedGlassTokens(helper, ConfigManager.getBlurRadius())
                    material.background = ColorDrawable(
                        nativeBackgroundColor(service, ConfigManager.getOpacity())
                    )
                    material.alpha = 1f
                    invokeHelper(helper, "b", material)
                }

                1 ->
                {
                    invokeHelper(helper, "m")
                    material.background = ColorDrawable(
                        resolveSolidColor(
                            ConfigManager.getBgColor(),
                            ConfigManager.getOpacity()
                        )
                    )
                    material.alpha = 1f
                }

                2 ->
                {
                    invokeHelper(helper, "m")
                    val bitmap = getOrLoadBitmap(service)

                    if (bitmap != null && !bitmap.isRecycled)
                    {
                        material.background = BitmapDrawable(service.resources, bitmap).apply {
                            alpha = ConfigManager.getOpacity().coerceIn(0, 100) * 255 / 100
                        }
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
                    material.resources.displayMetrics.density
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

    private fun forceMaterialStateEnabled(helper: Any)
    {
        try
        {
            val state = XposedUtils.getObjectField(helper, "e") ?: return
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

        listOf("r", "s").forEach { fieldName ->
            try
            {
                val lazyValue = XposedUtils.getObjectField(helper, fieldName) ?: return@forEach
                val getValue = lazyValue.javaClass.methods.firstOrNull {
                    it.name == "getValue" && it.parameterTypes.isEmpty()
                } ?: return@forEach
                val token = getValue.invoke(lazyValue) ?: return@forEach

                XposedUtils.setObjectField(
                    token,
                    "p",
                    blurRadiusDp.coerceIn(0, 400)
                )

                val blends = XposedUtils.getObjectField(token, "e") as? IntArray
                if (blends != null)
                {
                    XposedUtils.setObjectField(token, "e", IntArray(blends.size))
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
        val color = palette
            ?.let { readLongField(it, "a") }
            ?.let { (it ushr 32).toInt() }
            ?: Color.TRANSPARENT

        return BackgroundOpacity.argb(color, opacity)
    }

    private fun staticNativePalette(service: android.inputmethodservice.InputMethodService): Any?
    {
        return try
        {
            val holder = Class.forName("na.x", false, service.classLoader)
            val dark = (
                service.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
                ) == Configuration.UI_MODE_NIGHT_YES

            holder.getDeclaredField(if (dark) "e" else "d").apply {
                isAccessible = true
            }.get(null)
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

    private fun applyTopCornerOutline(view: View, radiusPx: Float)
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
                        target.height + radiusPx.toInt(),
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
