package io.mo.xatype.hooks

import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.TargetCompatibility
import io.mo.xatype.compat.TargetGeneration
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils

object KeyboardHeightUnblockHook {
    fun install(module: XposedInterface, classLoader: ClassLoader) {
        if (TargetCompatibility.detect(classLoader) != TargetGeneration.V21053) {
            XposedUtils.log(module, "KeyboardHeightUnblockHook: skipped unsupported target")
            return
        }

        // Verified against 21053 DEX and smali. Resolve everything before
        // installing hooks so an incompatible signature leaves native behavior.
        val intType = Int::class.javaPrimitiveType!!
        val service = Class.forName("com.mi.ime.MiInputMethodService", false, classLoader)
        val composer = Class.forName("s0.p", false, classLoader)
        val layout = Class.forName("gb.m0", false, classLoader)
            .getDeclaredMethod("a", intType, service, composer)
        val dragType = Class.forName("z9.e6", false, classLoader)
        val drag = dragType.getDeclaredMethod("m", Any::class.java, Any::class.java)
        val kind = dragType.getDeclaredField("a").apply { isAccessible = true }
        val clamp = Class.forName("ed.a", false, classLoader)
            .getDeclaredMethod("k", intType, intType, intType)
        val rectType = Class.forName("z9.c", false, classLoader)
        val rectGetter = Class.forName("ab.z1", false, classLoader).getDeclaredMethod("l")
        val top = rectType.getDeclaredField("b").apply { isAccessible = true }
        val left = rectType.getDeclaredField("a").apply { isAccessible = true }
        val right = rectType.getDeclaredField("c").apply { isAccessible = true }
        val bottom = rectType.getDeclaredField("d").apply { isAccessible = true }
        val rectConstructor = rectType.getDeclaredConstructor(intType, intType, intType, intType)
        check(layout.returnType == Void.TYPE && clamp.returnType == intType &&
            drag.returnType == Any::class.java && kind.type == intType &&
            rectGetter.returnType == rectType &&
            listOf(top, left, right, bottom).all { it.type == intType })

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
        XposedUtils.log(module, "KeyboardHeightUnblockHook: installed top drag and dimension hooks (21053)")
    }
}
