package io.mo.xatype.hooks

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import io.mo.xatype.util.HookLifetime
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Change the native layout model, so measured keys, hit targets and popups share its geometry. */
object OneHandHook {
    private var currentService = WeakReference<InputMethodService>(null)
    private var revision: Any? = null
    private var readRevision: Method? = null
    private var writeRevision: Method? = null
    private var revisionNumber = 0
    private var logger: XposedInterface? = null

    /** Shared by menu and swipe: a layout action must never commit pending composition. */
    fun perform(service: InputMethodService, action: String): Boolean {
        val side = OneHandPolicy.actionSide(action)
        if (side == null && action != OneHandPolicy.MENU_ACTION) return false
        Handler(Looper.getMainLooper()).post {
            if (HookLifetime.retired) return@post
            runCatching {
                checkNotNull(revision) { "Native layout refresh unavailable" }
                ConfigManager.syncFromProvider(service)
                val current = ConfigManager.getOneHandSide()
                val target = if (side == null) OneHandPolicy.menuTarget(current, ConfigManager.getOneHandLastSide())
                    else OneHandPolicy.toggle(current, side)
                ConfigManager.setOneHandSide(service, target)
                writeRevision!!.invoke(revision, ++revisionNumber)
            }.onFailure {
                logger?.let { module -> XposedUtils.logError(module, "One-hand switch failed", it) }
                Toast.makeText(service, "单手模式切换失败，请查看模块适配状态", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    internal fun service(): InputMethodService? = currentService.get()

    private fun observeRevision(service: InputMethodService, manager: Method) {
        if (revision == null) {
            // Give this feature its own snapshot state, using the host's existing mutation policy.
            // Reading it in the layout composition lets a change invalidate the native geometry.
            val field = checkNotNull(HostSymbols.field(HostSymbols.UI_CLOSING_STATE)).apply { isAccessible = true }
            val sample = checkNotNull(field.get(manager.invoke(service)))
            val type = sample.javaClass
            val constructor = type.declaredConstructors.single {
                it.parameterCount == 2 && it.parameterTypes[0] == Any::class.java
            }.apply { isAccessible = true }
            val policy = type.declaredFields.single { it.type == constructor.parameterTypes[1] }
                .apply { isAccessible = true }.get(sample)
            revision = constructor.newInstance(0, policy)
            readRevision = type.getMethod("getValue")
            writeRevision = type.getMethod("setValue", Any::class.java)
        }
        readRevision!!.invoke(revision)
    }

    fun install(module: XposedInterface) {
        logger = module
        val dimensions = HostSymbols.clazz(HostSymbols.ONE_HAND_DIMENSIONS)
        val layout = HostSymbols.method(HostSymbols.HEIGHT_LAYOUT)
        val manager = HostSymbols.method(HostSymbols.UI_MANAGER)
        val floating = HostSymbols.method(HostSymbols.UI_FLOATING)
        if (dimensions == null || layout == null || manager == null || floating == null) {
            HostSymbols.markUnavailable(HostSymbols.ONE_HAND_DIMENSIONS,
                IllegalStateException("Native dimensions or UI geometry unavailable"))
            XposedUtils.log(module, "OneHandHook: skipped unsupported native dimensions")
            return
        }
        val int = Int::class.javaPrimitiveType!!
        val constructor = dimensions.getDeclaredConstructor(int, int, int, int, int, int)
        val serviceIndex = layout.parameterTypes.indexOfFirst { InputMethodService::class.java.isAssignableFrom(it) }
        check(serviceIndex >= 0)
        val active = ThreadLocal<InputMethodService>()
        var failureReported = false
        var refreshFailureReported = false
        val flagsIndex = if (layout.parameterTypes.last() == int) layout.parameterCount - 1 else 0
        check(layout.parameterTypes[flagsIndex] == int)
        module.deoptimize(layout)
        module.hook(layout).intercept { chain ->
            val previous = active.get()
            val service = chain.getArg(serviceIndex) as InputMethodService
            currentService = WeakReference(service)
            active.set(service)
            val args = chain.args.toTypedArray()
            runCatching { observeRevision(service, manager) }.onFailure {
                if (!refreshFailureReported) {
                    refreshFailureReported = true
                    XposedUtils.logError(module, "One-hand live refresh unavailable", it)
                }
            }
            // A snapshot read here belongs to the caller's scope; force this child to re-read geometry.
            args[flagsIndex] = (args[flagsIndex] as Int) or 1
            try { chain.proceed(args) } finally {
                if (previous == null) active.remove() else active.set(previous)
            }
        }
        module.hook(constructor).intercept { chain ->
            val args = chain.args.toTypedArray()
            val service = active.get()
            if (service != null && ConfigManager.getOneHandSide() != OneHandPolicy.OFF) {
                // Only the dimension object belonging to this layout, never unrelated constructions.
                active.remove()
                val geometry = runCatching {
                    val config = service.resources.configuration
                    val density = service.resources.displayMetrics.density
                    val windowWidth = service.window?.window?.decorView?.width ?: 0
                    val width = if (windowWidth > 0 && density > 0f) (windowWidth / density).toInt() else config.screenWidthDp
                    OneHandPolicy.geometry(width, ConfigManager.getOneHandWidth(),
                        ConfigManager.getOneHandSide(), config.orientation == Configuration.ORIENTATION_PORTRAIT &&
                            !service.isFullscreenMode, floating.invoke(manager.invoke(service)) != false)
                }.getOrElse { failure ->
                    if (!failureReported) {
                        failureReported = true
                        XposedUtils.logError(module, "One-hand geometry unavailable, retaining native layout", failure)
                    }
                    null
                }
                if (geometry != null) {
                    args[1] = geometry.leftMargin
                    args[2] = geometry.rightMargin
                    args[4] = geometry.offset
                }
            }
            chain.proceed(args)
        }
        XposedUtils.log(module, "OneHandHook: installed native dimension adjustment")
    }
}
