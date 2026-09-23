package io.mo.xatype.hooks

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewTreeObserver
import io.github.libxposed.api.XposedModule
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils

/** A retained child surface follows the IME animation without Xiaomi's dark blur fallback. */
internal class CompositorGlassSurface(private val materialField: String) {
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

    fun owns(material: Any?): Boolean = dynamicGlassSurfacePrimer != null &&
        material != null && dynamicGlassTrackedMaterial === material

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
        val listener = ViewTreeObserver.OnPreDrawListener {
            val decor = service.window?.window?.decorView
            val helper = XposedUtils.getObjectField(service, "hyperMaterialHelper")
            if (!isEnabled() || !material.isAttachedToWindow ||
                material.rootView !== decor || helper == null || XposedUtils.getObjectField(helper, materialField) !== material
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
    @SuppressLint("BlockedPrivateApi")
    fun ensure(
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
            XposedUtils.logError(module, "CompositorGlassSurface: failed to update blur", t)
            remove()
            return false
        }
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
