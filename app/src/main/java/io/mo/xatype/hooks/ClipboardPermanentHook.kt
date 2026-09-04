package io.mo.xatype.hooks

import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import org.json.JSONArray
import java.lang.reflect.Method
import java.util.LinkedHashSet

/**
 * Removes MIUIFrequentPhrase's expiry, item-count and text-length limits.
 *
 * com.miui.phrase persists the canonical list, while com.xiaomi.type loads the
 * same APK classes for its clipboard panel. This hook is therefore installed in
 * both processes (when the classes are present).
 */
object ClipboardPermanentHook {
    private const val MANAGER_CLASS = "com.miui.inputmethod.MiuiClipboardManager"
    private const val STORAGE_CLASS = "N1.c"
    private const val POPUP_CLASS = "com.miui.inputmethod.InputMethodClipboardPhrasePopupView"
    private const val HEADER_ADAPTER_CLASS = "com.miui.inputmethod.InputMethodClipboardHeaderAdapter"
    private const val UNLIMITED_TIP = "剪贴板内容永久保存，不限制条数与文字长度"

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        val managerClass = XposedUtils.findClass(MANAGER_CLASS, classLoader)
        if (managerClass == null) {
            XposedUtils.logWarn(module, "[Permanent Clipboard] $MANAGER_CLASS not found in this process")
            return
        }

        installTextLengthHooks(module, managerClass)
        installReadHooks(module, managerClass)
        installMergeHooks(module, managerClass)
        installProviderWriteHook(module, classLoader, managerClass)
        installPopupHooks(module, classLoader)
        installHeaderHook(module, classLoader)
        XposedUtils.log(module, "[Permanent Clipboard] Unlimited clipboard hooks installed")
    }

    private fun installTextLengthHooks(module: XposedInterface, managerClass: Class<*>) {
        setUnlimitedTextLength(managerClass)
        managerClass.declaredMethods
            .filter { it.name == "init" && it.parameterTypes.isEmpty() }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    if (ConfigManager.isClipboardPermanentEnabled()) {
                        setUnlimitedTextLength(managerClass)
                    }
                    result
                }
            }
    }

    private fun setUnlimitedTextLength(managerClass: Class<*>) {
        if (!ConfigManager.isClipboardPermanentEnabled()) return
        try {
            managerClass.getDeclaredField("MAX_CLIP_CONTENT_SIZE").apply {
                isAccessible = true
                setInt(null, Int.MAX_VALUE)
            }
        } catch (_: Throwable) {
        }
    }

    private fun installReadHooks(
        module: XposedInterface,
        managerClass: Class<*>
    ) {
        val method = managerClass.declaredMethods.firstOrNull {
            it.name == "getNoExpiredClipboardData" && it.parameterTypes.size == 3
        } ?: return
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            if (!ConfigManager.isClipboardPermanentEnabled()) return@intercept chain.proceed()
            try {
                parseModels(managerClass, chain.getArg(1) as? String ?: "")
            } catch (t: Throwable) {
                XposedUtils.logError(module, "[Permanent Clipboard] Failed to read complete history", t)
                chain.proceed()
            }
        }
    }

    private fun installMergeHooks(
        module: XposedInterface,
        managerClass: Class<*>
    ) {
        val method = managerClass.declaredMethods.firstOrNull {
            it.name == "addContentListToJsonArray" && it.parameterTypes.size == 4
        } ?: return
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            if (!ConfigManager.isClipboardPermanentEnabled()) return@intercept chain.proceed()
            try {
                val deviceId = chain.getArg(0) as? String
                val includeTemporary = chain.getArg(1) as? Boolean ?: false
                val incoming = (chain.getArg(2) as? Collection<*>)?.filterNotNull().orEmpty()
                val existing = parseModels(managerClass, chain.getArg(3) as? String ?: "")
                val merged = LinkedHashSet<Any>().apply {
                    addAll(incoming)
                    addAll(existing)
                }.sortedByDescending(::modelTime).filter { model ->
                    includeTemporary || !modelBoolean(model, "isTemp") ||
                        deviceId.isNullOrEmpty() || deviceId != modelString(model, "getDeviceId")
                }
                serializeModels(merged)
            } catch (t: Throwable) {
                XposedUtils.logError(module, "[Permanent Clipboard] Failed to merge complete history", t)
                chain.proceed()
            }
        }
    }

    private fun installProviderWriteHook(
        module: XposedInterface,
        classLoader: ClassLoader,
        managerClass: Class<*>
    ) {
        val storageClass = XposedUtils.findClass(STORAGE_CLASS, classLoader) ?: return
        val method = storageClass.declaredMethods.firstOrNull {
            it.name == "f" && it.returnType == String::class.java &&
                it.parameterTypes.map(Class<*>::getName) == listOf(
                    "android.content.Context",
                    "android.database.sqlite.SQLiteDatabase",
                    "com.miui.inputmethod.ClipboardContentModel",
                    "java.lang.String"
                )
        } ?: return
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            if (!ConfigManager.isClipboardPermanentEnabled()) return@intercept chain.proceed()
            try {
                val newModel = chain.getArg(2) ?: return@intercept chain.proceed()
                val existing = parseModels(managerClass, chain.getArg(3) as? String ?: "")
                val merged = LinkedHashSet<Any>().apply {
                    add(newModel)
                    addAll(existing)
                }.sortedByDescending(::modelTime)
                serializeModels(merged)
            } catch (t: Throwable) {
                XposedUtils.logError(module, "[Permanent Clipboard] Failed to persist complete history", t)
                chain.proceed()
            }
        }
    }

    private fun installPopupHooks(module: XposedInterface, classLoader: ClassLoader) {
        val popupClass = XposedUtils.findClass(POPUP_CLASS, classLoader) ?: return
        popupClass.declaredMethods
            .filter {
                (it.name == "lambda\$updateClipboardData\$8" || it.name == "setRemoteDataToView") &&
                    it.parameterTypes.size == 1
            }
            .forEach { method -> installListPreservationHook(module, method) }
    }

    private fun installListPreservationHook(module: XposedInterface, method: Method) {
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            if (!ConfigManager.isClipboardPermanentEnabled()) return@intercept chain.proceed()
            val popup = chain.thisObject
            val before = adapterList(popup)?.toList().orEmpty()
            val result = chain.proceed()
            val current = adapterList(popup)
            if (current != null) {
                before.forEach { item ->
                    if (!current.contains(item)) current.add(item)
                }
                XposedUtils.setObjectField(popup, "mCurrentImeClipboardList", current)
            }
            result
        }
    }

    private fun installHeaderHook(module: XposedInterface, classLoader: ClassLoader) {
        val adapterClass = XposedUtils.findClass(HEADER_ADAPTER_CLASS, classLoader) ?: return
        adapterClass.declaredMethods
            .filter { it.name == "onBindViewHolder" && it.parameterTypes.size == 2 }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    if (ConfigManager.isClipboardPermanentEnabled()) {
                        val holder = chain.getArg(0)
                        (holder?.let { XposedUtils.getObjectField(it, "tipTextView") } as? TextView)
                            ?.text = UNLIMITED_TIP
                    }
                    result
                }
            }
    }

    private fun parseModels(managerClass: Class<*>, json: String): ArrayList<Any> {
        val method = managerClass.getDeclaredMethod("jsonToBeanList", String::class.java).apply {
            isAccessible = true
        }
        val parsed = method.invoke(null, json) as? Collection<*> ?: return arrayListOf()
        return ArrayList(parsed.filterNotNull())
    }

    private fun serializeModels(models: Collection<Any>): String {
        val array = JSONArray()
        models.forEach { model ->
            val method = model.javaClass.getMethod("toJSONObject")
            array.put(method.invoke(model))
        }
        return array.toString()
    }

    private fun modelTime(model: Any): Long =
        (model.javaClass.getMethod("getTime").invoke(model) as? Number)?.toLong() ?: Long.MIN_VALUE

    private fun modelBoolean(model: Any, methodName: String): Boolean =
        model.javaClass.getMethod(methodName).invoke(model) as? Boolean ?: false

    private fun modelString(model: Any, methodName: String): String? =
        model.javaClass.getMethod(methodName).invoke(model) as? String

    @Suppress("UNCHECKED_CAST")
    private fun adapterList(popup: Any): MutableList<Any>? {
        val adapter = XposedUtils.getObjectField(popup, "mInputMethodClipboardAdapter") ?: return null
        val method = adapter.javaClass.methods.firstOrNull {
            it.name == "getAdapterList" && it.parameterTypes.isEmpty()
        } ?: return null
        return method.invoke(adapter) as? MutableList<Any>
    }
}
