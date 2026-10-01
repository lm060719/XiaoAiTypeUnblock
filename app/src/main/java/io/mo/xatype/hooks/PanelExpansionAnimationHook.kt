package io.mo.xatype.hooks

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.mo.xatype.compat.TargetCompatibility
import io.mo.xatype.compat.TargetGeneration
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.util.IdentityHashMap

/** Restore the legacy extra-height spring removed from the 21053 Compose layout. */
object PanelExpansionAnimationHook {
    private val pendingCloses = IdentityHashMap<InputMethodService, Runnable>()
    private val completingClose = ThreadLocal<Boolean>()

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (TargetCompatibility.detect(classLoader) != TargetGeneration.V21053) return
        try {
            val expansion = Class.forName("gb.p", false, classLoader)
            val animatable = Class.forName("w.b", false, classLoader)
            val continuation = Class.forName("rc.c", false, classLoader)
            val spec = Class.forName("w.h", false, classLoader)
            val callback = Class.forName("bd.b", false, classLoader)
            val springType = Class.forName("w.n0", false, classLoader)
            val springFactory = Class.forName("w.c", false, classLoader).getDeclaredMethod(
                "p", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Any::class.java, Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            check(springFactory.returnType == springType && spec.isAssignableFrom(springType))
            val spring = springFactory.invoke(null, 1f, 631f, null, 4)
            val animateTo = animatable.getDeclaredMethod(
                "c", animatable, Any::class.java, spec, callback,
                continuation, Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            val snapTo = animatable.getDeclaredMethod("e", Any::class.java, continuation)
                .apply { isAccessible = true }
            val state = expansion.getDeclaredField("e").apply { isAccessible = true }
            val capturedAnimation = expansion.getDeclaredField("h").apply { isAccessible = true }
            check(state.type == Int::class.javaPrimitiveType && capturedAnimation.type == animatable)
            check(animateTo.returnType == Any::class.java && snapTo.returnType == Any::class.java)

            module.hook(snapTo).intercept { chain ->
                val owner = chain.getArg(1)
                // The coroutine has already set its resume label. Returning animateTo's
                // suspension marker keeps cancellation and resume on the original job.
                // Label 3 is ordinary collapse and must retain the native snap behavior.
                if (ConfigManager.isStyleEnabled() && expansion.isInstance(owner) &&
                    capturedAnimation.get(owner) === chain.thisObject &&
                    state.getInt(owner) in 1..2
                ) {
                    if (ConfigManager.isVerboseLogEnabled()) {
                        XposedUtils.log(module, "PanelExpansionAnimationHook: spring label=${state.getInt(owner)}")
                    }
                    animateTo.invoke(null, chain.thisObject, chain.getArg(0), spring, null, owner, 12)
                } else {
                    chain.proceed()
                }
            }
            XposedUtils.log(module, "PanelExpansionAnimationHook: 21053 panel spring restored")
            installCollapseHook(module, classLoader)
        } catch (t: Throwable) {
            XposedUtils.logError(module, "PanelExpansionAnimationHook: unsupported animation structure", t)
        }
    }

    private fun installCollapseHook(module: XposedModule, classLoader: ClassLoader) {
        val serviceType = Class.forName("com.mi.ime.MiInputMethodService", false, classLoader)
        val managerType = Class.forName("ab.z1", false, classLoader)
        val modeType = Class.forName("y7.l", false, classLoader)
        val managerGetter = serviceType.getDeclaredMethod("getUiStateManager\$app_iflytekFullRelease")
        val modeGetter = managerType.getDeclaredMethod("n")
        val closingGetter = managerType.getDeclaredMethod("A")
        val floatingGetter = managerType.getDeclaredMethod("v")
        val closingState = managerType.getDeclaredField("n")
        val setClosing = closingState.type.getMethod("setValue", Any::class.java)
        val aiMode = modeType.getDeclaredField("k").get(null)
        val close = Class.forName("lb.c", false, classLoader)
            .getDeclaredMethod("d0", serviceType)
        val completion = Class.forName("gb.n", false, classLoader)
        val completionKind = completion.getDeclaredField("e")
        val completionService = completion.getDeclaredField("f")
        val heightIsZero = completion.getDeclaredField("g")
        val onHeightChanged = completion.getDeclaredMethod("r", Any::class.java)
        check(managerGetter.returnType == managerType && modeGetter.returnType == modeType)
        check(closingGetter.returnType == Boolean::class.javaPrimitiveType &&
            floatingGetter.returnType == Boolean::class.javaPrimitiveType)
        check(close.returnType == Void.TYPE && completionKind.type == Int::class.javaPrimitiveType &&
            completionService.type == serviceType && heightIsZero.type == Boolean::class.javaPrimitiveType)
        val handler = Handler(Looper.getMainLooper())

        fun finish(service: InputMethodService) {
            val timeout = pendingCloses.remove(service) ?: return
            handler.removeCallbacks(timeout)
            val manager = managerGetter.invoke(service)
            if (modeGetter.invoke(manager) !== aiMode || closingGetter.invoke(manager) != true) return
            // The original button handler must run once, after the content has slid
            // out. Reset its guard so it still cancels input and clears native state.
            setClosing.invoke(closingState.get(manager), false)
            completingClose.set(true)
            try {
                close.invoke(null, service)
            } finally {
                completingClose.remove()
            }
        }

        module.hook(close).intercept { chain ->
            val service = chain.getArg(0) as? InputMethodService
            val manager = service?.let { managerGetter.invoke(it) }
            if (service != null && manager != null && completingClose.get() != true &&
                ConfigManager.isStyleEnabled() && service.isInputViewShown &&
                floatingGetter.invoke(manager) != true && modeGetter.invoke(manager) === aiMode
            ) {
                if (closingGetter.invoke(manager) != true) {
                    pendingCloses.remove(service)?.let(handler::removeCallbacks)
                    val timeout = Runnable { finish(service) }
                    pendingCloses[service] = timeout
                    // The native layout retains AI content while A() is true and
                    // animates its extra height to zero using coroutine label 2.
                    setClosing.invoke(closingState.get(manager), true)
                    // Covers a detached composition that cannot deliver its effect.
                    handler.postDelayed(timeout, 650L)
                }
                null
            } else {
                chain.proceed()
            }
        }
        module.hook(onHeightChanged).intercept { chain ->
            val owner = chain.thisObject
            if (completionKind.getInt(owner) == 1 && heightIsZero.getBoolean(owner)) {
                (completionService.get(owner) as? InputMethodService)?.let(::finish)
            }
            chain.proceed()
        }
        listOf("onWindowHidden", "onFinishInputView", "onDestroy").forEach { name ->
            serviceType.declaredMethods.firstOrNull { it.name == name }?.let { method ->
                module.hook(method).intercept { chain ->
                    val service = chain.thisObject as? InputMethodService
                    if (service != null) {
                        pendingCloses.remove(service)?.let { timeout ->
                            handler.removeCallbacks(timeout)
                            val manager = managerGetter.invoke(service)
                            setClosing.invoke(closingState.get(manager), false)
                        }
                    }
                    chain.proceed()
                }
            }
        }
        XposedUtils.log(module, "PanelExpansionAnimationHook: AI panel collapse animation installed")
    }
}
