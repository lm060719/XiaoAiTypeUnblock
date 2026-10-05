package io.mo.xatype.hooks

import android.content.Context
import android.os.UserManager
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.util.XposedUtils
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/** Observations only: never replaces input, engine results, flags, or exceptions. */
object InputDiagnosticsHook {
    private val snapshots = AtomicInteger()

    fun install(module: XposedInterface, loader: ClassLoader) {
        val service = XposedUtils.findClass("com.mi.ime.MiInputMethodService", loader)
        if (service != null) {
            listOf("onCreate", "initializeManagers").forEach { name ->
                service.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }?.let { method ->
                    observe(module, method) { instance, _ -> snapshot(module, instance, name) }
                }
            }
            service.declaredMethods.firstOrNull {
                it.name == "onStartInputView" && it.parameterTypes.contentEquals(
                    arrayOf(EditorInfo::class.java, java.lang.Boolean.TYPE))
            }?.let { method ->
                observe(module, method) { instance, _ ->
                    if (snapshots.incrementAndGet() <= 40) snapshot(module, instance, "onStartInputView")
                }
            }
        } else {
            XposedUtils.logWarn(module, "[InputDiag] MiInputMethodService not found")
        }
        val engine = XposedUtils.findClass("com.iflytek.depend.common.base.SmartEngineManager", loader)
        engine?.declaredMethods?.filter {
            it.name == "initSmart" && it.returnType == java.lang.Boolean.TYPE &&
                it.parameterTypes.firstOrNull() == Context::class.java
        }?.forEach { method ->
            observe(module, method) { _, result ->
                XposedUtils.log(module, "[InputDiag] SmartEngineManager.initSmart returned $result")
            }
        }
        if (engine == null) XposedUtils.logWarn(module, "[InputDiag] SmartEngineManager not found")
        XposedUtils.log(module, "[InputDiag] Diagnostic hooks installed; no input text recorded by these hooks")
    }

    private fun observe(module: XposedInterface, method: Method, after: (Any?, Any?) -> Unit) {
        try {
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val result = try {
                    chain.proceed()
                } catch (t: Throwable) {
                    runCatching { XposedUtils.logError(module, "[InputDiag] ${method.name} threw", t) }
                    throw t
                }
                runCatching { after(chain.getThisObject(), result) }.onFailure {
                    runCatching { XposedUtils.logError(module, "[InputDiag] Observation failed: ${method.name}", it) }
                }
                result
            }
            XposedUtils.log(module, "[InputDiag] Observing ${method.declaringClass.name}.${method.name}")
        } catch (t: Throwable) {
            XposedUtils.logError(module, "[InputDiag] Cannot observe ${method.name}", t)
        }
    }

    private fun snapshot(module: XposedInterface, instance: Any?, stage: String) {
        val context = instance as? Context ?: return
        fun read(name: String): Any? = runCatching {
            instance.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(instance)
        }.getOrNull()
        val engine = read("getCurrentEngine")
        val engineType = read("getCurrentEngineType")
        val degraded = instance.javaClass.methods.firstOrNull {
            it.name.startsWith("isKeyboardDegraded") && it.parameterCount == 0
        }?.let { runCatching { it.invoke(instance) }.getOrNull() }
        val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked
        val provisioned = Settings.Global.getInt(context.contentResolver, "device_provisioned", -1)
        val editor = (instance as? android.inputmethodservice.InputMethodService)?.currentInputEditorInfo
        // Only class names / enums / booleans, never engine.toString() or editor text.
        XposedUtils.log(module, "[InputDiag] $stage: unlocked=$unlocked provisioned=$provisioned " +
            "degraded=$degraded engineClass=${engine?.javaClass?.name ?: "null"} " +
            "engineType=${(engineType as? Enum<*>)?.name ?: "unknown"} " +
            "hardwareKeyboard=${context.resources.configuration.keyboard} " +
            "inputType=${editor?.inputType} imeOptions=${editor?.imeOptions}")
        (instance as? android.inputmethodservice.InputMethodService)?.let { service ->
            val helper = XposedUtils.getObjectField(service, "hyperMaterialHelper")
            val material = helper?.let { XposedUtils.getObjectField(it, "i") as? android.view.View }
            AppearanceDiagnostics.record(module, service, stage, material)
        }
    }
}
