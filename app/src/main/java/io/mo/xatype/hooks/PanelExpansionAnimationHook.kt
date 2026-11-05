package io.mo.xatype.hooks

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * Restore the extra-height spring and the reply-panel slide-out that 0.2.974
 * removed from the Compose layout. Members come from [HostSymbols]; builds that
 * still animate natively (0.2.910 and earlier) leave both hooks inert.
 */
object PanelExpansionAnimationHook {
    private val pendingCloses = IdentityHashMap<InputMethodService, Runnable>()
    private val completingClose = ThreadLocal<Boolean>()

    /** Coroutine labels of the expansion body: expand, slide out while closing, collapse. */
    private const val LABEL_EXPAND = 1
    private const val LABEL_CLOSING = 2

    /** The extra-height coroutine; [label] is its resume point, [animatable] its height. */
    private class Expansion(val body: Method, val label: Field, val animatable: Field)

    fun install(module: XposedModule) {
        val expansion = try {
            installSpring(module)
        } catch (t: Throwable) {
            HostSymbols.markUnavailable(HostSymbols.PANEL_EXPANSION, t)
            XposedUtils.logError(module, "PanelExpansionAnimationHook: unsupported animation structure", t)
            return
        } ?: return
        try {
            installCollapseHook(module, expansion)
        } catch (t: Throwable) {
            HostSymbols.markUnavailable(HostSymbols.PANEL_CLOSE, t)
            XposedUtils.logError(module, "PanelExpansionAnimationHook: unsupported panel close structure", t)
        }
    }

    private fun installSpring(module: XposedModule): Expansion? {
        val body = HostSymbols.method(HostSymbols.PANEL_EXPANSION)
        val snapTo = HostSymbols.method(HostSymbols.PANEL_SNAP)
        val animateTo = HostSymbols.method(HostSymbols.PANEL_ANIMATE)
        val springFactory = HostSymbols.method(HostSymbols.PANEL_SPRING)
        if (body == null || snapTo == null || animateTo == null || springFactory == null) {
            XposedUtils.log(module, "PanelExpansionAnimationHook: skipped unsupported target")
            return null
        }
        val expansion = body.declaringClass
        val animatable = snapTo.declaringClass
        val continuation = animateTo.parameterTypes[4]
        val fields = expansion.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }
        val label = fields.single { it.type == Int::class.javaPrimitiveType }
        val captured = fields.single { it.type == animatable }
        // 0.2.1246 passes the continuation first; earlier builds pass the target first.
        val ownerIndex = snapTo.parameterTypes.indexOf(continuation)
        val valueIndex = 1 - ownerIndex
        check(ownerIndex in 0..1 && snapTo.parameterTypes[valueIndex] == Any::class.java)
        check(animateTo.parameterTypes[0] == animatable &&
            animateTo.parameterTypes[2].isAssignableFrom(springFactory.returnType))
        // spring(dampingRatio = 1f, stiffness = 631f), the legacy extra-height spring.
        val spring = springFactory.invoke(null, 1f, 631f, null, 4)

        module.hook(snapTo).intercept { chain ->
            val owner = chain.getArg(ownerIndex)
            // The coroutine has already set its resume label. Returning animateTo's
            // suspension marker keeps cancellation and resume on the original job.
            // Label 3 is ordinary collapse and must retain the native snap behavior.
            if (ConfigManager.isStyleEnabled() && expansion.isInstance(owner) &&
                captured.get(owner) === chain.thisObject &&
                label.getInt(owner) in LABEL_EXPAND..LABEL_CLOSING
            ) {
                XposedUtils.logDebug(module) { "PanelExpansionAnimationHook: spring label=${label.getInt(owner)}" }
                // animateTo$default: initialVelocity and the per-frame block keep their defaults.
                animateTo.invoke(null, chain.thisObject, chain.getArg(valueIndex), spring, null, owner, 12)
            } else {
                chain.proceed()
            }
        }
        XposedUtils.log(module, "PanelExpansionAnimationHook: panel spring hook installed")
        return Expansion(body, label, captured)
    }

    private fun installCollapseHook(module: XposedModule, expansion: Expansion) {
        if (HostSymbols.has(HostSymbols.PANEL_CLOSE) && HostSymbols.strings(HostSymbols.PANEL_CLOSE).isNullOrEmpty()) {
            XposedUtils.log(module, "PanelExpansionAnimationHook: host animates the reply panel close itself")
            return
        }
        val close = HostSymbols.method(HostSymbols.PANEL_CLOSE)
        val managerGetter = HostSymbols.method(HostSymbols.UI_MANAGER)
        val modeGetter = HostSymbols.method(HostSymbols.UI_MODE)
        val closingGetter = HostSymbols.method(HostSymbols.UI_CLOSING)
        val floatingGetter = HostSymbols.method(HostSymbols.UI_FLOATING)
        val closingState = HostSymbols.field(HostSymbols.UI_CLOSING_STATE)
        val replyMode = HostSymbols.field(HostSymbols.UI_AI_MODE)
        if (close == null || managerGetter == null || modeGetter == null || closingGetter == null ||
            floatingGetter == null || closingState == null || replyMode == null
        ) {
            XposedUtils.log(module, "PanelExpansionAnimationHook: reply panel close not located; collapse skipped")
            return
        }
        val serviceType = managerGetter.declaringClass
        val managerType = managerGetter.returnType
        check(close.returnType == Void.TYPE && close.parameterTypes.contentEquals(arrayOf(serviceType)))
        check(modeGetter.declaringClass.isAssignableFrom(managerType) && modeGetter.returnType == replyMode.type)
        check(closingGetter.returnType == Boolean::class.javaPrimitiveType &&
            floatingGetter.returnType == Boolean::class.javaPrimitiveType)
        val setClosing = closingState.type.getMethod("setValue", Any::class.java)
        val aiMode = replyMode.get(null)
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
                    // The native layout retains the reply content while the closing flag
                    // is set and animates its extra height to zero using coroutine label 2.
                    setClosing.invoke(closingState.get(manager), true)
                    // Covers a detached composition that cannot deliver its effect.
                    handler.postDelayed(timeout, 650L)
                }
                null
            } else {
                chain.proceed()
            }
        }
        module.hook(expansion.body).intercept { chain ->
            // A fresh coroutine starts at label 0, so entering at label 2 is the
            // resume after the slide-out. A cancelled animation resumes with a
            // failure, which the body rethrows before the close completes.
            val resumedFromClosing = expansion.label.getInt(chain.thisObject) == LABEL_CLOSING
            val result = chain.proceed()
            if (resumedFromClosing && pendingCloses.isNotEmpty()) {
                pendingCloses.keys.toList().forEach(::finish)
            }
            result
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
        XposedUtils.log(module, "PanelExpansionAnimationHook: reply panel collapse animation installed")
    }
}
