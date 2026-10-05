package io.mo.xatype.hooks

import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.TargetCompatibility
import io.mo.xatype.compat.TargetGeneration
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.lang.reflect.Field
import java.lang.reflect.Method

object KeyboardHeightUnblockHook {
    private class Targets(
        val layout: Method,
        val drag: Method,
        val kind: Field,
        val clamp: Method,
        val rectGetter: Method
    )

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        val targets = fingerprinted() ?: legacy(classLoader)
        if (targets == null) {
            XposedUtils.log(module, "KeyboardHeightUnblockHook: skipped unsupported target")
            return
        }
        val layout = targets.layout
        val drag = targets.drag
        val kind = targets.kind.apply { isAccessible = true }
        val clamp = targets.clamp
        val rectGetter = targets.rectGetter
        val intType = Int::class.javaPrimitiveType!!
        val rectType = rectGetter.returnType
        val rectConstructor = rectType.getDeclaredConstructor(intType, intType, intType, intType)
        // Map edges by value so field renames cannot swap them. The values keep
        // the derived width (47) and height (207) distinct from every edge.
        val probe = rectConstructor.newInstance(3, -7, 50, 200)
        val edges = rectType.declaredFields.filter { it.type == intType }
            .onEach { it.isAccessible = true }
            .groupBy { it.getInt(probe) }
        fun edge(value: Int) = checkNotNull(edges[value]?.singleOrNull()) { "AdjustRect edge $value" }
        val left = edge(3)
        val top = edge(-7)
        val right = edge(50)
        val bottom = edge(200)
        check(layout.returnType == Void.TYPE && clamp.returnType == intType &&
            drag.returnType == Any::class.java && kind.type == intType)

        // ART may have inlined the small clamp and rectangle getter in these
        // callers. Deoptimize them so the scoped hooks also work on warmed code.
        module.deoptimize(layout)
        module.deoptimize(drag)

        module.hook(clamp).intercept { chain ->
            KeyboardHeightPolicy.overrideClamp(chain.getArg(0) as Int, chain.getArg(1) as Int, chain.getArg(2) as Int)
                ?: chain.proceed()
        }
        module.hook(drag).intercept { chain ->
            // Branch 2 is the top handle. Left/right/bottom and whole-keyboard
            // movement retain their original bounds and haptic callbacks.
            if (ConfigManager.isKeyboardHeightUnblockEnabled() && kind.getInt(chain.thisObject) == 2) {
                KeyboardHeightPolicy.within(KeyboardHeightPolicy.Scope.TOP_DRAG) { chain.proceed() }
            } else {
                chain.proceed()
            }
        }
        module.hook(layout).intercept { chain ->
            if (ConfigManager.isKeyboardHeightUnblockEnabled()) {
                KeyboardHeightPolicy.within(KeyboardHeightPolicy.Scope.DIMENSIONS) { chain.proceed() }
            } else {
                chain.proceed()
            }
        }
        module.hook(rectGetter).intercept { chain ->
            val rect = chain.proceed()
            // Turning the feature off renders a previously saved tall keyboard
            // within native bounds. Keep its saved geometry for re-enabling;
            // native reset/confirm still controls persistent settings.
            if (rect != null && !ConfigManager.isKeyboardHeightUnblockEnabled() && top.getInt(rect) < -50) {
                rectConstructor.newInstance(left.getInt(rect), -50, right.getInt(rect), bottom.getInt(rect))
            } else {
                rect
            }
        }
        XposedUtils.log(module, "KeyboardHeightUnblockHook: installed top drag and dimension hooks")
    }

    private fun fingerprinted(): Targets? {
        return Targets(
            HostSymbols.method(HostSymbols.HEIGHT_LAYOUT) ?: return null,
            HostSymbols.method(HostSymbols.HEIGHT_DRAG) ?: return null,
            HostSymbols.field(HostSymbols.HEIGHT_DRAG_KIND) ?: return null,
            HostSymbols.method(HostSymbols.HEIGHT_CLAMP) ?: return null,
            HostSymbols.method(HostSymbols.HEIGHT_RECT_GETTER) ?: return null
        )
    }

    /** Verified against 21053 DEX and smali. */
    private fun legacy(classLoader: ClassLoader): Targets? {
        if (TargetCompatibility.detect(classLoader) != TargetGeneration.V21053) return null
        val intType = Int::class.javaPrimitiveType!!
        val service = Class.forName("com.mi.ime.MiInputMethodService", false, classLoader)
        val composer = Class.forName("s0.p", false, classLoader)
        val dragType = Class.forName("z9.e6", false, classLoader)
        return Targets(
            Class.forName("gb.m0", false, classLoader)
                .getDeclaredMethod("a", intType, service, composer),
            dragType.getDeclaredMethod("m", Any::class.java, Any::class.java),
            dragType.getDeclaredField("a"),
            Class.forName("ed.a", false, classLoader)
                .getDeclaredMethod("k", intType, intType, intType),
            Class.forName("ab.z1", false, classLoader).getDeclaredMethod("l")
        )
    }
}
