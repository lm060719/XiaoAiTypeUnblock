package io.mo.xatype.hooks

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import io.github.libxposed.api.XposedModule
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.util.concurrent.atomic.AtomicInteger

object SystemUiNavigationGuardHook {
    private const val XIAOMI_IME_COMPONENT_PREFIX = "com.xiaomi.type/"
    private const val GUARD_DURATION_MS = 452L
    private const val HIDE_GUARD_DURATION_MS = 80L
    private const val LOW_OPACITY_GUARD_DURATION_MS = 32L
    private const val SURFACE_ANIMATION_MAX_OPACITY = 10
    private const val SURFACE_ANIMATION_ARM_MS = 1_200L
    private const val IME_SHOW_VISUAL_DURATION_MS = 291L
    private val generation = AtomicInteger(0)
    private val animatorDiagnosticCount = AtomicInteger(0)
    private val drawableDiagnosticCount = AtomicInteger(0)
    private val insideImeSurfaceStart = ThreadLocal<Boolean>()
    @Volatile private var systemUiContext: Context? = null
    @Volatile private var surfaceAnimationArmedUntil = 0L
    @Volatile private var imeWasShowing = false
    @Volatile private var suppressImeGuardUntil = 0L
    @Volatile private var screenReceiverRegistered = false
    @Volatile private var blurWarmSentinel: Any? = null
    @Volatile private var blurWarmSentinelParent: Any? = null

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val navigationBarClass = XposedUtils.findClass(
            "com.android.systemui.navigationbar.views.NavigationBar",
            classLoader
        ) ?: run {
            XposedUtils.logError(module, "SystemUiNavigationGuard: NavigationBar class not found")
            return
        }
        val method = navigationBarClass.declaredMethods.firstOrNull {
            it.name == "setImeWindowStatus" && it.parameterTypes.size == 4
        }?.apply { isAccessible = true } ?: run {
            XposedUtils.logError(module, "SystemUiNavigationGuard: setImeWindowStatus not found")
            return
        }

        module.hook(method).intercept { chain ->
            val navigationBar = chain.thisObject
            val view = XposedUtils.getObjectField(navigationBar, "mView") as? View
            if (view != null) {
                val context = view.context.applicationContext ?: view.context
                systemUiContext = context
                ensureScreenStateReceiver(context)
                ConfigManager.syncFromProvider(context)
                if (
                    ConfigManager.isStyleEnabled() &&
                    ConfigManager.getBgType() == 0 &&
                    ConfigManager.getOpacity() <= SURFACE_ANIMATION_MAX_OPACITY &&
                    ConfigManager.getBlurRadius() > 0
                ) {
                    ensureBlurWarmSentinel(module, view)
                } else {
                    removeBlurWarmSentinel()
                }
                val visibility = (chain.getArg(1) as? Number)?.toInt() ?: 0
                val showing = visibility and 0x2 != 0
                val wasShowing = imeWasShowing
                if (showing) {
                    imeWasShowing = true
                } else if (visibility == 0) {
                    imeWasShowing = false
                }
                val genuineImeTransition = showing || wasShowing
                if (shouldGuard(context) && genuineImeTransition) {
                    surfaceAnimationArmedUntil = SystemClock.uptimeMillis() + SURFACE_ANIMATION_ARM_MS
                    val duration = if (!showing) {
                        HIDE_GUARD_DURATION_MS
                    } else if (
                        ConfigManager.getOpacity() <= SURFACE_ANIMATION_MAX_OPACITY
                    ) {
                        IME_SHOW_VISUAL_DURATION_MS
                    } else {
                        GUARD_DURATION_MS
                    }
                    applyGuard(
                        module,
                        view,
                        duration,
                        if (showing) null else resolveAppSurfaceColor()
                    )
                    XposedUtils.log(
                        module,
                        "[BottomDiag][SystemUI] IME status visibility=0x${visibility.toString(16)} " +
                            "show=$showing guard=${duration}ms"
                    )
                } else if (!showing && visibility == 0 && !wasShowing) {
                    XposedUtils.log(
                        module,
                        "[BottomDiag][SystemUI] stale hidden IME status ignored"
                    )
                }
            }
            chain.proceed()
        }
        XposedUtils.log(module, "SystemUiNavigationGuard: setImeWindowStatus hooked")

        installImeAnimatorDurationGuard(module)
        installMiuiImeDurationProviderGuard(module, classLoader)
        installImeSurfaceAnimationGuard(module, classLoader)
        installImeAnimatorFallback(module)
    }

    private fun ensureScreenStateReceiver(context: Context) {
        if (screenReceiverRegistered) return
        synchronized(this) {
            if (screenReceiverRegistered) return
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
                context.registerReceiver(
                    object : BroadcastReceiver() {
                        override fun onReceive(receiverContext: Context, intent: Intent) {
                            when (intent.action) {
                                Intent.ACTION_SCREEN_OFF -> {
                                    imeWasShowing = false
                                    generation.incrementAndGet()
                                    removeBlurWarmSentinel()
                                }
                                Intent.ACTION_SCREEN_ON -> {
                                    imeWasShowing = false
                                    suppressImeGuardUntil = SystemClock.uptimeMillis() + 1_000L
                                }

                            }
                        }
                    },
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
                screenReceiverRegistered = true
            } catch (_: Throwable) {}
        }
    }

    /** Keep SurfaceFlinger's background-blur path active between IME shows. */
    // Intentional non-SDK access inside the Xposed-injected SystemUI process.
    // The blur warm-up surface has no public SDK equivalent; unsupported
    // implementations are handled by the existing exception fallback.
    @SuppressLint("BlockedPrivateApi")
    private fun ensureBlurWarmSentinel(module: XposedModule, view: View) {
        try {
            val getViewRootImpl = View::class.java.getDeclaredMethod("getViewRootImpl").apply {
                isAccessible = true
            }
            val viewRoot = getViewRootImpl.invoke(view) ?: return
            val parent = XposedUtils.getObjectField(viewRoot, "mSurfaceControl") ?: return
            val surfaceClass = Class.forName("android.view.SurfaceControl")
            val validMethod = surfaceClass.getDeclaredMethod("isValid").apply { isAccessible = true }
            var sentinel = blurWarmSentinel
            if (
                sentinel == null ||
                blurWarmSentinelParent !== parent ||
                validMethod.invoke(sentinel) != true
            ) {
                removeBlurWarmSentinel()
                val builderClass = Class.forName("android.view.SurfaceControl\$Builder")
                val builder = builderClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                builderClass.getDeclaredMethod("setName", String::class.java)
                    .apply { isAccessible = true }
                    .invoke(builder, "XaTypeBlurWarmSentinel")
                builderClass.getDeclaredMethod("setEffectLayer")
                    .apply { isAccessible = true }
                    .invoke(builder)
                builderClass.getDeclaredMethod("setParent", surfaceClass)
                    .apply { isAccessible = true }
                    .invoke(builder, parent)
                sentinel = builderClass.getDeclaredMethod("build")
                    .apply { isAccessible = true }
                    .invoke(builder)
                blurWarmSentinel = sentinel
                blurWarmSentinelParent = parent
            }
            val transactionClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val transaction = transactionClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            transactionClass.getDeclaredMethod(
                "setLayer",
                surfaceClass,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }.invoke(transaction, sentinel, Int.MAX_VALUE)
            transactionClass.getDeclaredMethod(
                "setPosition",
                surfaceClass,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            ).apply { isAccessible = true }.invoke(transaction, sentinel, 0f, 0f)
            transactionClass.getDeclaredMethod(
                "setWindowCrop",
                surfaceClass,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }.invoke(transaction, sentinel, 2, 2)
            transactionClass.getDeclaredMethod(
                "setBackgroundBlurRadius",
                surfaceClass,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }.invoke(
                transaction,
                sentinel,
                ConfigManager.getBlurRadius().coerceIn(1, 400)
            )
            transactionClass.getDeclaredMethod("show", surfaceClass)
                .apply { isAccessible = true }
                .invoke(transaction, sentinel)
            transactionClass.getDeclaredMethod("apply")
                .apply { isAccessible = true }
                .invoke(transaction)
            XposedUtils.log(module, "[BottomDiag][SystemUI] blur warm sentinel active")
        } catch (t: Throwable) {
            XposedUtils.logError(module, "SystemUiNavigationGuard: blur warm sentinel failed", t)
            removeBlurWarmSentinel()
        }
    }

    // Paired cleanup of the non-SDK sentinel above; reflection failures are caught.
    @SuppressLint("BlockedPrivateApi")
    private fun removeBlurWarmSentinel() {
        val sentinel = blurWarmSentinel ?: return
        blurWarmSentinel = null
        blurWarmSentinelParent = null
        try {
            val surfaceClass = Class.forName("android.view.SurfaceControl")
            val transactionClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val transaction = transactionClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            transactionClass.getDeclaredMethod("remove", surfaceClass)
                .apply { isAccessible = true }
                .invoke(transaction, sentinel)
            transactionClass.getDeclaredMethod("apply")
                .apply { isAccessible = true }
                .invoke(transaction)
        } catch (_: Throwable) {}
    }

    private fun installMiuiImeDurationProviderGuard(
        module: XposedModule,
        classLoader: ClassLoader
    ) {
        var hooked = 0
        listOf(
            "com.android.wm.shell.common.split.SplitUtilsStub",
            "com.android.wm.shell.common.split.SplitUtilsImpl"
        ).forEach { className ->
            val type = XposedUtils.findClass(className, classLoader) ?: return@forEach
            val method = type.declaredMethods.firstOrNull {
                it.name == "getImeAnimationDuration" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }?.apply { isAccessible = true } ?: return@forEach
            module.hook(method).intercept { chain ->
                if (insideImeSurfaceStart.get() == true) {
                    val showing = chain.getArg(0) as? Boolean
                    XposedUtils.log(
                        module,
                        "[BottomDiag][SystemUI] MIUI IME duration provider forced " +
                            "show=$showing actual=0ms owner=$className"
                    )
                    0L
                } else {
                    chain.proceed()
                }
            }
            hooked++
        }
        XposedUtils.log(module, "SystemUiNavigationGuard: MIUI duration providers hooked=$hooked")
    }

    private fun installImeAnimatorDurationGuard(module: XposedModule) {
        val setDuration = ValueAnimator::class.java.declaredMethods.firstOrNull {
            it.name == "setDuration" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Long::class.javaPrimitiveType
        }?.apply { isAccessible = true } ?: run {
            XposedUtils.logError(module, "SystemUiNavigationGuard: ValueAnimator.setDuration not found")
            return
        }

        module.hook(setDuration).intercept { chain ->
            if (insideImeSurfaceStart.get() == true) {
                val requested = (chain.getArg(0) as? Number)?.toLong()
                XposedUtils.log(
                    module,
                    "[BottomDiag][SystemUI] IME Surface duration forced requested=${requested}ms actual=0ms"
                )
                chain.proceed(arrayOf(0L))
            } else {
                chain.proceed()
            }
        }
        XposedUtils.log(module, "SystemUiNavigationGuard: IME duration guard hooked")
    }

    private fun installImeSurfaceAnimationGuard(module: XposedModule, classLoader: ClassLoader) {
        val perDisplayClass = XposedUtils.findClass(
            "com.android.wm.shell.common.DisplayImeController\$PerDisplay",
            classLoader
        ) ?: run {
            XposedUtils.logError(module, "SystemUiNavigationGuard: DisplayImeController.PerDisplay not found")
            return
        }
        val startAnimation = perDisplayClass.declaredMethods.firstOrNull { candidate ->
            val parameters = candidate.parameterTypes
            candidate.name == "startAnimation" &&
                parameters.size == 3 &&
                parameters[0] == Boolean::class.javaPrimitiveType &&
                parameters[1] == Boolean::class.javaPrimitiveType &&
                parameters[2].name.contains("ImeTracker")
        }?.apply { isAccessible = true } ?: run {
            XposedUtils.logError(module, "SystemUiNavigationGuard: IME Surface startAnimation not found")
            return
        }

        module.hook(startAnimation).intercept { chain ->
            val context = systemUiContext
            if (context != null) ConfigManager.syncFromProvider(context)
            val showing = chain.getArg(0) == true
            val forceInstant = context != null &&
                shouldGuard(context) &&
                ConfigManager.getBgType() != 0 &&
                ConfigManager.getOpacity() <= SURFACE_ANIMATION_MAX_OPACITY
            if (forceInstant) insideImeSurfaceStart.set(true)
            val result = try {
                chain.proceed()
            } finally {
                if (forceInstant) insideImeSurfaceStart.remove()
            }
            XposedUtils.log(
                module,
                "[BottomDiag][SystemUI] direct IME Surface start intercepted " +
                    "context=${context != null} show=$showing " +
                    "forceInstant=$forceInstant opacity=${ConfigManager.getOpacity()}"
            )
            result
        }
        XposedUtils.log(module, "SystemUiNavigationGuard: IME Surface animation hooked")
    }

    private fun installImeAnimatorFallback(module: XposedModule) {
        val start = ValueAnimator::class.java.declaredMethods.firstOrNull {
            it.name == "start" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true } ?: run {
            XposedUtils.logError(module, "SystemUiNavigationGuard: ValueAnimator.start not found")
            return
        }

        module.hook(start).intercept { chain ->
            val animator = chain.thisObject as? ValueAnimator
            val armed = SystemClock.uptimeMillis() <= surfaceAnimationArmedUntil
            val stack = if (armed) Thread.currentThread().stackTrace else emptyArray()
            val fromImeSurfaceController = armed && stack.any {
                    it.className == "com.android.wm.shell.common.DisplayImeController\$PerDisplay" &&
                        it.methodName == "startAnimation"
                }
            if (
                animator != null &&
                fromImeSurfaceController &&
                ConfigManager.isStyleEnabled() &&
                ConfigManager.getBgType() != 0 &&
                ConfigManager.getOpacity() <= SURFACE_ANIMATION_MAX_OPACITY
            ) {
                animator.duration = 0L
                val result = chain.proceed()
                if (animator.isRunning) animator.end()
                XposedUtils.log(
                    module,
                    "[BottomDiag][SystemUI] IME Surface ValueAnimator forced to end " +
                        "opacity=${ConfigManager.getOpacity()}"
                )
                result
            } else {
                if (
                    armed &&
                    ConfigManager.getOpacity() <= SURFACE_ANIMATION_MAX_OPACITY &&
                    animatorDiagnosticCount.getAndIncrement() < 12
                ) {
                    val relevantStack = stack.filter {
                        it.className.contains("ime", ignoreCase = true) ||
                            it.className.contains("insets", ignoreCase = true)
                    }.take(8).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
                    if (relevantStack.isNotEmpty()) {
                        XposedUtils.log(
                            module,
                            "[BottomDiag][SystemUI] armed ValueAnimator not matched stack=$relevantStack"
                        )
                    }
                }
                chain.proceed()
            }
        }
        XposedUtils.log(module, "SystemUiNavigationGuard: IME ValueAnimator fallback hooked")
    }

    private fun shouldGuard(context: Context): Boolean {
        if (!ConfigManager.isStyleEnabled() || ConfigManager.getOpacity() >= 100) return false
        if (SystemClock.uptimeMillis() < suppressImeGuardUntil) return false
        val interactive = try {
            context.getSystemService(PowerManager::class.java)?.isInteractive == true
        } catch (_: Throwable) {
            true
        }
        val keyguardLocked = try {
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        } catch (_: Throwable) {
            false
        }
        if (!interactive || keyguardLocked) return false
        val currentIme = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
        } catch (_: Throwable) {
            null
        }
        return currentIme?.startsWith(XIAOMI_IME_COMPONENT_PREFIX) == true
    }

    private fun applyGuard(
        module: XposedModule,
        view: View,
        requestedDuration: Long? = null,
        terminalColor: Int? = null
    ) {
        val sequence = generation.incrementAndGet()
        val transitions = XposedUtils.getObjectField(view, "mBarTransitions")
        val original = XposedUtils.getObjectField(transitions ?: return, "mBarBackground") as? Drawable
            ?: return
        if (drawableDiagnosticCount.getAndIncrement() == 0) {
            val fields = generateSequence(original.javaClass as Class<*>?) { it.superclass }
                .takeWhile { it != Any::class.java }
                .flatMap { it.declaredFields.asSequence() }
                .joinToString(",") { "${it.name}:${it.type.simpleName}" }
            XposedUtils.log(
                module,
                "[BottomDiag][SystemUI] nav drawable=${original.javaClass.name} fields=$fields"
            )
        }
        val color = resolveGuardColor(view.context)
        view.setBackgroundColor(color)
        XposedUtils.log(
            module,
            "[BottomDiag][SystemUI][$sequence] nav-guard applied color=${colorHex(color)} " +
                "size=${view.width}x${view.height}"
        )
        val duration = requestedDuration?.coerceAtLeast(1L) ?: if (
            ConfigManager.getOpacity() <= SURFACE_ANIMATION_MAX_OPACITY
        ) LOW_OPACITY_GUARD_DURATION_MS else GUARD_DURATION_MS
        view.postDelayed(
            {
                if (generation.get() == sequence) {
                    if (terminalColor != null) {
                        holdDrawableColorUntil(
                            view,
                            original,
                            terminalColor,
                            sequence,
                            SystemClock.uptimeMillis() + 260L
                        )
                    } else {
                        view.background = original
                        original.invalidateSelf()
                    }
                    XposedUtils.log(
                        module,
                        "[BottomDiag][SystemUI][$sequence] nav-guard restored after=${duration}ms " +
                            "terminal=${terminalColor?.let(::colorHex) ?: "original"}"
                    )
                }
            },
            duration
        )
    }

    private fun holdDrawableColorUntil(
        view: View,
        drawable: Drawable,
        color: Int,
        sequence: Int,
        until: Long
    ) {
        if (generation.get() != sequence) return
        forceDrawableCurrentColor(drawable, color)
        view.background = drawable
        drawable.invalidateSelf()
        if (SystemClock.uptimeMillis() < until) {
            view.postOnAnimation {
                holdDrawableColorUntil(view, drawable, color, sequence, until)
            }
        }
    }

    private fun forceDrawableCurrentColor(drawable: Drawable, color: Int) {
        var type: Class<*>? = drawable.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.forEach { field ->
                try {
                    field.isAccessible = true
                    when {
                        field.type == Int::class.javaPrimitiveType &&
                            field.name in setOf("mColor", "mColorStart", "mStartColor", "mTargetColor") ->
                            field.setInt(drawable, color)
                        field.type == Boolean::class.javaPrimitiveType && field.name == "mAnimating" ->
                            field.setBoolean(drawable, false)
                    }
                } catch (_: Throwable) {}
            }
            type = type.superclass
        }
    }

    private fun resolveAppSurfaceColor(): Int {
        return Color.TRANSPARENT
    }

    private fun resolveGuardColor(context: Context): Int {
        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (ConfigManager.getBgType() == 0) {
            return Color.TRANSPARENT
        }
        val base = if (isDark) 24 else 255
        val source = if (ConfigManager.getBgType() == 1) {
            try { Color.parseColor(ConfigManager.getBgColor()) } catch (_: Throwable) { Color.rgb(base, base, base) }
        } else {
            Color.rgb(base, base, base)
        }
        val alpha = (ConfigManager.getOpacity().coerceIn(0, 100) * 255) / 100
        fun composite(channel: Int): Int =
            ((channel * alpha + base * (255 - alpha)) / 255).coerceIn(0, 255)
        return Color.rgb(composite(Color.red(source)), composite(Color.green(source)), composite(Color.blue(source)))
    }

    private fun colorHex(color: Int): String = "#%08X".format(color)
}
