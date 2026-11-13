package io.mo.xatype.hooks

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewTreeObserver
import io.github.libxposed.api.XposedModule
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils

/** A retained child surface follows the IME animation without Xiaomi's dark blur fallback. */
internal class CompositorGlassSurface(
    private val materialField: String?,
    private val popupWindow: Boolean = false,
    private var roundBottom: Boolean = popupWindow
) {
    @Volatile private var dynamicGlassSurfacePrimer: Any? = null
    @Volatile private var dynamicGlassSurfacePrimerParent: Any? = null
    private var blurRegionTemplate: FloatArray? = null
    private var dynamicGlassTrackedMaterial: View? = null
    private var dynamicGlassGeometryObserver: ViewTreeObserver? = null
    private var dynamicGlassPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var dynamicGlassDetachListener: View.OnAttachStateChangeListener? = null
    private var dynamicGlassGeometry: GlassGeometry? = null
    private data class GlassGeometry(
        val x: Int, val y: Int, val width: Int, val height: Int,
        val decorHeight: Int, val shown: Boolean
    )

    fun owns(material: Any?): Boolean = dynamicGlassSurfacePrimer != null &&
        material != null && dynamicGlassTrackedMaterial === material

    fun setRoundBottomCorners(value: Boolean) {
        if (roundBottom != value) {
            roundBottom = value
            dynamicGlassGeometry = null
        }
    }

    private fun isEnabled(): Boolean = GlassTransitionPolicy.usesCompositor(
        ConfigManager.isStyleEnabled(), ConfigManager.getBgType(),
        ConfigManager.getOpacity(), ConfigManager.getBlurRadius()
    )

    private fun trackDynamicGlassGeometry(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        material: View
    ) {
        if (dynamicGlassTrackedMaterial === material) return
        stopTrackingDynamicGlassGeometry()
        dynamicGlassTrackedMaterial = material
        val observer = material.viewTreeObserver
        // Each instance retains its own window. A popup must never reparent
        // the keyboard's layer, even when both use the same material helper.
        val trackedRoot = material.rootView
        val listener = ViewTreeObserver.OnPreDrawListener {
            val decor = if (popupWindow) trackedRoot else service.window?.window?.decorView
            val helper = XposedUtils.getObjectField(service, "hyperMaterialHelper")
            if (!isEnabled() || !material.isAttachedToWindow ||
                material.rootView !== decor || (materialField != null &&
                    (helper == null || XposedUtils.getObjectField(helper, materialField) !== material))
            ) {
                remove()
            } else if (decor != null) {
                val position = IntArray(2)
                material.getLocationInWindow(position)
                val geometry = GlassGeometry(position[0], position[1], material.width,
                    material.height, decor.height, material.isShown)
                if (geometry != dynamicGlassGeometry) {
                    ensure(module, service, material, syncWithDraw = true)
                }
            }
            true
        }
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                if (dynamicGlassTrackedMaterial === view) remove()
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

    // Intentional non-SDK access inside the Xposed-injected IME process.
    // Public SDK APIs cannot configure this child blur surface. Availability
    // is checked at runtime; failures retain the native material fallback.
    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    fun ensure(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        material: View,
        syncWithDraw: Boolean = false
    ): Boolean {
        try {
            val mainDecor = service.window?.window?.decorView
            val decor = if (popupWindow) material.rootView else mainDecor ?: return skip(module, "no decor")
            // Coordinates and SurfaceControl must belong to the same window.
            if (popupWindow && decor === mainDecor) return false
            if (!material.isAttachedToWindow || material.rootView !== decor) {
                return skip(module, "attached=${material.isAttachedToWindow} sameRoot=${material.rootView === decor}")
            }
            val getViewRootImpl = View::class.java.getDeclaredMethod("getViewRootImpl").apply {
                isAccessible = true
            }
            val viewRoot = getViewRootImpl.invoke(decor) ?: return skip(module, "no ViewRootImpl")
            val parent = XposedUtils.getObjectField(viewRoot, "mSurfaceControl") ?: return skip(module, "no surface")
            val surfaceClass = Class.forName("android.view.SurfaceControl")
            val validMethod = surfaceClass.getDeclaredMethod("isValid").apply { isAccessible = true }
            if (validMethod.invoke(parent) != true) return skip(module, "surface invalid")
            var primer = dynamicGlassSurfacePrimer
            if (
                primer == null ||
                dynamicGlassSurfacePrimerParent !== parent ||
                validMethod.invoke(primer) != true
            ) {
                remove()
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
            val docked = !popupWindow && !roundBottom && location[1] + material.height >= decor.height
            val visibleHeight = if (docked) minOf(material.height, decor.height - location[1]).coerceAtLeast(0) else material.height
            val bottomRadius = if (docked) ImeWindowCornerGuard.bottomRadius(decor) else 0f
            val roundDisplayBottom = docked && bottomRadius > 0f
            // Keep the original rendering for popups and floating keyboards.
            val cropHeight = if (roundDisplayBottom) visibleHeight else material.height +
                if (docked) kotlin.math.ceil(cornerRadiusPx.toDouble()).toInt() else 0
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
                if (roundDisplayBottom) 0f else cornerRadiusPx
            )
            transactionClass.getDeclaredMethod(
                "setBackgroundBlurRadius",
                surfaceClass,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }.invoke(
                transaction,
                primer,
                if (roundDisplayBottom) 0 else (ConfigManager.getBlurRadius() * material.resources.displayMetrics.density + 0.5f)
                    .toInt().coerceIn(0, 400)
            )
            val blurRadius = (ConfigManager.getBlurRadius() * material.resources.displayMetrics.density + 0.5f)
                .toInt().coerceIn(0, 400)
            if (roundDisplayBottom) {
                val template = blurRegionTemplate ?: run {
                    val drawable = viewRoot.javaClass.getDeclaredMethod("createBackgroundBlurDrawable").invoke(viewRoot) as Drawable
                    // This is only a serializer template; never register another visible blur region.
                    drawable.setVisible(false, false)
                    drawable.javaClass.getDeclaredMethod("setCornerRadius", Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                        .invoke(drawable, 11f, 22f, 33f, 44f)
                    val regionClass = Class.forName("com.android.internal.graphics.drawable.BackgroundBlurDrawable\$BlurRegion")
                    val region = regionClass.declaredConstructors.first().apply { isAccessible = true }.newInstance(drawable)
                    (regionClass.getDeclaredMethod("toFloatArray").apply { isAccessible = true }
                        .invoke(region) as FloatArray).also { blurRegionTemplate = it }
                }
                // Unlike backgroundBlurRadius, explicit regions keep their four corner shapes
                // when the IME is attached to a shrinking Recents task. Bounds use window coordinates.
                val region = GlassBlurRegion.encode(template, blurRadius, location[0], location[1],
                    material.width, visibleHeight, cornerRadiusPx, bottomRadius)
                transactionClass.getDeclaredMethod("setBlurRegions", surfaceClass, Array<FloatArray>::class.java)
                    .apply { isAccessible = true }.invoke(transaction, primer, arrayOf(region))
            } else if (blurRegionTemplate != null) {
                transactionClass.getDeclaredMethod("setBlurRegions", surfaceClass, Array<FloatArray>::class.java)
                    .apply { isAccessible = true }.invoke(transaction, primer, emptyArray<FloatArray>())
            }
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
            if (geometry != dynamicGlassGeometry) XposedUtils.logDebug(module) {
                "[BottomDiag] glass Surface geometry " +
                    "bounds=${material.width}x${material.height}@${location[0]},${location[1]} " +
                    "shown=${material.isShown} drawSync=$syncWithDraw decor=${decor.width}x${decor.height} " +
                    "visibleHeight=$visibleHeight bottomRadius=$bottomRadius"
            }
            dynamicGlassGeometry = geometry
            (transaction as? AutoCloseable)?.close()
            return true
        } catch (t: Throwable) {
            XposedUtils.logError(module, "CompositorGlassSurface: failed to update blur", t)
            remove()
            return false
        }
    }

    private fun skip(module: XposedModule, reason: String): Boolean {
        XposedUtils.logDebug(module) { "[BottomDiag] glass Surface skipped: $reason" }
        return false
    }

    // Paired cleanup of the non-SDK surface above; reflection failures are caught.
    @SuppressLint("BlockedPrivateApi")
    fun remove() {
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

}
