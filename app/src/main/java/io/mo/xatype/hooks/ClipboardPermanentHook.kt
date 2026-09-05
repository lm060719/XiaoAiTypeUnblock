package io.mo.xatype.hooks

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.Base64
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    private const val PROVIDER_CLASS = "com.miui.provider.InputProvider"
    private const val CLIPBOARD_URI = "content://com.miui.phrase.input.provider/getClipboardList"
    private const val CLIPBOARD_PROVIDER_URI = "content://com.miui.phrase.input.provider"
    private const val SAVE_CLIPBOARD_METHOD = "saveClipboardCipherText"
    private const val PERMANENT_REQUEST_KEY = "xatype_permanent"
    private const val PHRASE_PACKAGE = "com.miui.phrase"
    private const val IME_SERVICE_CLASS = "com.mi.ime.MiInputMethodService"
    private const val POPUP_CLASS = "com.miui.inputmethod.InputMethodClipboardPhrasePopupView"
    private const val POPUP_INIT_TASK_CLASS = "com.miui.inputmethod.InputMethodClipboardPhrasePopupView\$2"
    private const val HEADER_ADAPTER_CLASS = "com.miui.inputmethod.InputMethodClipboardHeaderAdapter"
    private const val UNLIMITED_TIP = "剪贴板内容永久保存，不限制条数与文字长度"
    private val installedManagerClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val classLoaderWatcherInstalled = AtomicBoolean(false)
    private val phraseContextLoaderHookInstalled = AtomicBoolean(false)
    private val readHookHitLogged = AtomicBoolean(false)
    private val mergeHookHitLogged = AtomicBoolean(false)
    private val writeHookHitLogged = AtomicBoolean(false)
    private val providerCallHookHitLogged = AtomicBoolean(false)
    private val disabledCallbackLogged = AtomicBoolean(false)
    private val popupInitHookHitLogged = AtomicBoolean(false)
    private val frameworkBridgeInstalled = AtomicBoolean(false)

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        installFrameworkBridge(module)
        val managerClass = XposedUtils.findClass(MANAGER_CLASS, classLoader)
        if (managerClass == null) {
            installPhraseContextLoaderHook(module, classLoader)
            installClassLoaderWatcher(module)
            XposedUtils.logWarn(
                module,
                "[Permanent Clipboard] $MANAGER_CLASS is not loaded yet; waiting for dynamic class loader"
            )
            return
        }

        installForManagerClass(module, managerClass)
    }

    private fun installFrameworkBridge(module: XposedInterface) {
        if (!frameworkBridgeInstalled.compareAndSet(false, true)) return

        ContentResolver::class.java.declaredMethods
            .filter {
                it.name == "call" && it.parameterTypes.contentEquals(
                    arrayOf(
                        Uri::class.java,
                        String::class.java,
                        String::class.java,
                        Bundle::class.java
                    )
                )
            }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val uri = chain.getArg(0) as? Uri
                    val callMethod = chain.getArg(1) as? String
                    if (
                        ConfigManager.isClipboardPermanentEnabled() &&
                        uri?.authority == "com.miui.phrase.input.provider" &&
                        callMethod == SAVE_CLIPBOARD_METHOD
                    ) {
                        val args = chain.args.toTypedArray()
                        args[3] = Bundle(chain.getArg(3) as? Bundle ?: Bundle()).apply {
                            putBoolean(PERMANENT_REQUEST_KEY, true)
                        }
                        return@intercept chain.proceed(args)
                    }
                    chain.proceed()
                }
            }

        Handler::class.java.declaredMethods
            .filter {
                it.name == "post" && it.parameterTypes.contentEquals(
                    arrayOf(Runnable::class.java)
                )
            }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val task = chain.getArg(0) as? Runnable ?: return@intercept chain.proceed()
                    if (
                        ConfigManager.isClipboardPermanentEnabled() &&
                        task.javaClass.name == POPUP_INIT_TASK_CLASS
                    ) {
                        val args = chain.args.toTypedArray()
                        args[0] = Runnable {
                            task.run()
                            restorePopupHistory(module, task)
                        }
                        return@intercept chain.proceed(args)
                    }
                    chain.proceed()
                }
            }
    }

    private fun restorePopupHistory(module: XposedInterface, task: Runnable) {
        try {
            val popup = XposedUtils.getObjectField(task, "this\$0") ?: return
            val context = XposedUtils.getObjectField(popup, "mContext") as? Context ?: return
            val loader = task.javaClass.classLoader ?: return
            val managerClass = Class.forName(MANAGER_CLASS, false, loader)
            val complete = queryModels(context, managerClass)
            val display = managerClass.getDeclaredMethod(
                "buildRecyclerViewDisplayList",
                List::class.java
            ).apply { isAccessible = true }.invoke(null, complete) as? List<*> ?: complete
            XposedUtils.setObjectField(popup, "mAllClipboardList", complete)
            XposedUtils.setObjectField(popup, "mCurrentImeClipboardList", display)
            managerClass.getDeclaredMethod(
                "setClipboardModelList",
                Context::class.java,
                List::class.java
            ).apply { isAccessible = true }.invoke(null, context, display)
            if (popupInitHookHitLogged.compareAndSet(false, true)) {
                XposedUtils.log(
                    module,
                    "[Permanent Clipboard] Framework popup bridge active; preservedCount=${display.size}"
                )
            }
        } catch (t: Throwable) {
            XposedUtils.logError(module, "[Permanent Clipboard] Framework popup bridge failed", t)
        }
    }

    private fun installForManagerClass(module: XposedInterface, managerClass: Class<*>) {
        val classLoader = managerClass.classLoader ?: return
        if (!installedManagerClasses.add(managerClass)) return

        installTextLengthHooks(module, managerClass)
        installCleanupHooks(module, managerClass)
        installPublicReadHook(module, managerClass)
        installReadHooks(module, managerClass)
        installMergeHooks(module, managerClass)
        installOutboundSaveHook(module, managerClass)
        installDisplayListWriteHook(module, managerClass)
        installProviderCallHook(module, classLoader, managerClass)
        installProviderEntryHook(module, classLoader, managerClass)
        installProviderWriteHook(module, classLoader, managerClass)
        installPopupHooks(module, classLoader)
        installPopupInitTaskHook(module, classLoader, managerClass)
        installHeaderHook(module, classLoader)
        deoptimizeCallers(module, managerClass, classLoader)
        XposedUtils.log(
            module,
            "[Permanent Clipboard] Unlimited clipboard hooks installed; enabled=${ConfigManager.isClipboardPermanentEnabled()}"
        )
    }

    private fun installPhraseContextLoaderHook(module: XposedInterface, classLoader: ClassLoader) {
        if (!phraseContextLoaderHookInstalled.compareAndSet(false, true)) return
        val serviceClass = XposedUtils.findClass(IME_SERVICE_CLASS, classLoader) ?: return
        serviceClass.declaredMethods
            .filter { it.name == "onCreate" && it.parameterTypes.isEmpty() }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val context = chain.thisObject as? Context
                    if (context != null) {
                        try {
                            refreshConfig(context)
                            val phraseContext = context.createPackageContext(
                                PHRASE_PACKAGE,
                                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
                            )
                            install(module, phraseContext.classLoader)
                        } catch (t: Throwable) {
                            XposedUtils.logError(
                                module,
                                "[Permanent Clipboard] Failed to obtain phrase package class loader",
                                t
                            )
                        }
                    }
                    result
                }
            }
    }

    private fun installClassLoaderWatcher(module: XposedInterface) {
        if (!classLoaderWatcherInstalled.compareAndSet(false, true)) return

        ClassLoader::class.java.declaredMethods
            .filter { method ->
                method.name == "loadClass" && method.parameterTypes.isNotEmpty() &&
                    method.parameterTypes[0] == String::class.java
            }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val loadedClass = chain.proceed()
                    if (chain.getArg(0) == MANAGER_CLASS && loadedClass is Class<*>) {
                        try {
                            installForManagerClass(module, loadedClass)
                        } catch (t: Throwable) {
                            XposedUtils.logError(
                                module,
                                "[Permanent Clipboard] Failed to install hooks for dynamic class loader",
                                t
                            )
                        }
                    }
                    loadedClass
                }
            }
    }

    private fun installTextLengthHooks(module: XposedInterface, managerClass: Class<*>) {
        setUnlimitedTextLength(managerClass)
        managerClass.declaredMethods
            .filter { it.name == "init" && it.parameterTypes.isEmpty() }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    refreshConfig(XposedUtils.getObjectField(chain.thisObject, "mContext") as? Context)
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

    private fun installCleanupHooks(module: XposedInterface, managerClass: Class<*>) {
        managerClass.declaredMethods
            .filter {
                it.name == "clearOldClipboardNew" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Context::class.java
            }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    refreshConfig(chain.getArg(0) as? Context)
                    if (ConfigManager.isClipboardPermanentEnabled()) null else chain.proceed()
                }
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
            refreshConfig(chain.getArg(0) as? Context)
            if (!ConfigManager.isClipboardPermanentEnabled()) return@intercept chain.proceed()
            try {
                val completeHistory = parseModels(managerClass, chain.getArg(1) as? String ?: "")
                if (readHookHitLogged.compareAndSet(false, true)) {
                    XposedUtils.log(
                        module,
                        "[Permanent Clipboard] Read hook active; preservedCount=${completeHistory.size}"
                    )
                }
                completeHistory
            } catch (t: Throwable) {
                XposedUtils.logError(module, "[Permanent Clipboard] Failed to read complete history", t)
                chain.proceed()
            }
        }
    }

    private fun installPublicReadHook(
        module: XposedInterface,
        managerClass: Class<*>
    ) {
        val method = managerClass.declaredMethods.firstOrNull {
            it.name == "getClipboardData" && it.parameterTypes.contentEquals(
                arrayOf(Context::class.java)
            )
        } ?: return
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val context = chain.getArg(0) as? Context ?: return@intercept chain.proceed()
            refreshConfig(context)
            if (!ConfigManager.isClipboardPermanentEnabled()) {
                logDisabledCallback(module, "public read")
                return@intercept chain.proceed()
            }

            try {
                val completeHistory = queryModels(context, managerClass)
                if (readHookHitLogged.compareAndSet(false, true)) {
                    XposedUtils.log(
                        module,
                        "[Permanent Clipboard] Public read hook active; preservedCount=${completeHistory.size}"
                    )
                }
                completeHistory
            } catch (t: Throwable) {
                XposedUtils.logError(module, "[Permanent Clipboard] Failed to query complete history", t)
                chain.proceed()
            }
        }
    }

    private fun installOutboundSaveHook(
        module: XposedInterface,
        managerClass: Class<*>
    ) {
        val method = managerClass.declaredMethods.firstOrNull {
            it.name == "addClipDataToPhrase" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Context::class.java &&
                it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        } ?: return
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val context = chain.getArg(0) as? Context ?: return@intercept chain.proceed()
            refreshConfig(context)
            if (!ConfigManager.isClipboardPermanentEnabled()) return@intercept chain.proceed()

            try {
                val newModel = chain.getArg(2) ?: return@intercept chain.proceed()
                if (chain.getArg(1) == true) {
                    val callback = managerClass.getDeclaredField("mClipBoardDataChangeInterface").apply {
                        isAccessible = true
                    }.get(null)
                    callback?.javaClass?.methods?.firstOrNull {
                        it.name == "updateClipBoardData" && it.parameterTypes.size == 1
                    }?.invoke(callback, newModel)
                }
                val merged = LinkedHashSet<Any>().apply {
                    add(newModel)
                    addAll(queryModels(context, managerClass))
                }.sortedByDescending(::modelTime)
                val extras = Bundle().apply {
                    putString("jsonArray", serializeModels(merged))
                    putBoolean(PERMANENT_REQUEST_KEY, true)
                }
                context.contentResolver.call(
                    Uri.parse(CLIPBOARD_PROVIDER_URI),
                    SAVE_CLIPBOARD_METHOD,
                    null,
                    extras
                )
                null
            } catch (t: Throwable) {
                XposedUtils.logError(module, "[Permanent Clipboard] Failed to send complete history", t)
                chain.proceed()
            }
        }
    }

    private fun installDisplayListWriteHook(
        module: XposedInterface,
        managerClass: Class<*>
    ) {
        val method = managerClass.declaredMethods.firstOrNull {
            it.name == "setClipboardModelList" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Context::class.java &&
                List::class.java.isAssignableFrom(it.parameterTypes[1])
        } ?: return
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val context = chain.getArg(0) as? Context ?: return@intercept chain.proceed()
            refreshConfig(context)
            if (!ConfigManager.isClipboardPermanentEnabled()) {
                logDisabledCallback(module, "display-list write")
                return@intercept chain.proceed()
            }

            try {
                val displayed = (chain.getArg(1) as? Collection<*>)?.filterNotNull().orEmpty()
                val encodedHistory = context.getSharedPreferences("sp_name_clip_board", 0)
                    .getString("clipboard_cipher_list", "").orEmpty()
                val historyJson = if (encodedHistory.isEmpty()) {
                    ""
                } else {
                    String(Base64.decode(encodedHistory, Base64.DEFAULT), Charsets.UTF_8)
                }
                val merged = LinkedHashSet<Any>().apply {
                    addAll(displayed)
                    addAll(parseModels(managerClass, historyJson))
                }.sortedByDescending(::modelTime)
                val args = chain.args.toTypedArray()
                args[1] = merged
                chain.proceed(args)
            } catch (t: Throwable) {
                XposedUtils.logError(
                    module,
                    "[Permanent Clipboard] Failed to preserve history during panel refresh",
                    t
                )
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
                if (mergeHookHitLogged.compareAndSet(false, true)) {
                    XposedUtils.log(
                        module,
                        "[Permanent Clipboard] Merge hook active; preservedCount=${merged.size}"
                    )
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
            refreshConfig(chain.getArg(0) as? Context)
            if (!ConfigManager.isClipboardPermanentEnabled()) return@intercept chain.proceed()
            try {
                val newModel = chain.getArg(2) ?: return@intercept chain.proceed()
                val existing = parseModels(managerClass, chain.getArg(3) as? String ?: "")
                val merged = LinkedHashSet<Any>().apply {
                    add(newModel)
                    addAll(existing)
                }.sortedByDescending(::modelTime)
                if (writeHookHitLogged.compareAndSet(false, true)) {
                    XposedUtils.log(
                        module,
                        "[Permanent Clipboard] Write hook active; preservedCount=${merged.size}"
                    )
                }
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
                (it.name == "lambda\$updateClipboardData\$8" && it.parameterTypes.size == 1) ||
                    (it.name == "lambda\$setRemoteDataToView\$5" && it.parameterTypes.isEmpty())
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

    private fun installPopupInitTaskHook(
        module: XposedInterface,
        classLoader: ClassLoader,
        managerClass: Class<*>
    ) {
        val taskClass = XposedUtils.findClass(POPUP_INIT_TASK_CLASS, classLoader) ?: return
        taskClass.declaredMethods
            .filter { it.name == "run" && it.parameterTypes.isEmpty() }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val popup = XposedUtils.getObjectField(chain.thisObject, "this\$0")
                        ?: return@intercept result
                    val context = XposedUtils.getObjectField(popup, "mContext") as? Context
                        ?: return@intercept result
                    refreshConfig(context)
                    if (!ConfigManager.isClipboardPermanentEnabled()) return@intercept result

                    try {
                        val complete = queryModels(context, managerClass)
                        val buildDisplay = managerClass.getDeclaredMethod(
                            "buildRecyclerViewDisplayList",
                            List::class.java
                        ).apply { isAccessible = true }
                        @Suppress("UNCHECKED_CAST")
                        val display = buildDisplay.invoke(null, complete) as? List<Any> ?: complete
                        XposedUtils.setObjectField(popup, "mAllClipboardList", complete)
                        XposedUtils.setObjectField(popup, "mCurrentImeClipboardList", display)

                        managerClass.getDeclaredMethod(
                            "setClipboardModelList",
                            Context::class.java,
                            List::class.java
                        ).apply { isAccessible = true }.invoke(null, context, display)
                        if (popupInitHookHitLogged.compareAndSet(false, true)) {
                            XposedUtils.log(
                                module,
                                "[Permanent Clipboard] Popup initialization hook active; preservedCount=${display.size}"
                            )
                        }
                    } catch (t: Throwable) {
                        XposedUtils.logError(
                            module,
                            "[Permanent Clipboard] Failed to restore complete popup history",
                            t
                        )
                    }
                    result
                }
            }
    }

    private fun installProviderEntryHook(
        module: XposedInterface,
        classLoader: ClassLoader,
        managerClass: Class<*>
    ) {
        val storageClass = XposedUtils.findClass(STORAGE_CLASS, classLoader) ?: return
        val method = storageClass.declaredMethods.firstOrNull {
            it.name == "v" && it.returnType == Bundle::class.java &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Context::class.java &&
                it.parameterTypes[2] == Bundle::class.java
        } ?: return
        val modelClass = XposedUtils.findClass(
            "com.miui.inputmethod.ClipboardContentModel",
            classLoader
        ) ?: return
        val fromJson = modelClass.getMethod("fromJSONObject", JSONObject::class.java)
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val context = chain.getArg(0) as? Context ?: return@intercept chain.proceed()
            refreshConfig(context)
            if (!ConfigManager.isClipboardPermanentEnabled()) return@intercept chain.proceed()

            try {
                val extras = chain.getArg(2) as? Bundle ?: return@intercept chain.proceed()
                val rewritten = rewriteClipboardWrite(context, extras, managerClass, fromJson)
                    ?: return@intercept chain.proceed()
                val args = chain.args.toTypedArray()
                args[2] = rewritten.first
                if (writeHookHitLogged.compareAndSet(false, true)) {
                    XposedUtils.log(
                        module,
                        "[Permanent Clipboard] Provider entry hook active; preservedCount=${rewritten.second}"
                    )
                }
                chain.proceed(args)
            } catch (t: Throwable) {
                XposedUtils.logError(
                    module,
                    "[Permanent Clipboard] Failed to rewrite provider clipboard write",
                    t
                )
                chain.proceed()
            }
        }
    }

    private fun installProviderCallHook(
        module: XposedInterface,
        classLoader: ClassLoader,
        managerClass: Class<*>
    ) {
        val providerClass = XposedUtils.findClass(PROVIDER_CLASS, classLoader) ?: return
        val modelClass = XposedUtils.findClass(
            "com.miui.inputmethod.ClipboardContentModel",
            classLoader
        ) ?: return
        val fromJson = modelClass.getMethod("fromJSONObject", JSONObject::class.java)
        providerClass.declaredMethods
            .filter {
                it.name == "call" && it.parameterTypes.contentEquals(
                    arrayOf(String::class.java, String::class.java, Bundle::class.java)
                )
            }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    if (chain.getArg(0) != SAVE_CLIPBOARD_METHOD) return@intercept chain.proceed()
                    val context = (chain.thisObject as? ContentProvider)?.context
                        ?: return@intercept chain.proceed()
                    val extras = chain.getArg(2) as? Bundle ?: return@intercept chain.proceed()
                    refreshConfig(context)
                    val permanentRequested = extras.getBoolean(PERMANENT_REQUEST_KEY, false)
                    if (!permanentRequested && !ConfigManager.isClipboardPermanentEnabled()) {
                        logDisabledCallback(module, "provider call")
                        return@intercept chain.proceed()
                    }

                    try {
                        val rewritten = rewriteClipboardWrite(context, extras, managerClass, fromJson)
                        if (rewritten == null) {
                            if (permanentRequested && providerCallHookHitLogged.compareAndSet(false, true)) {
                                XposedUtils.log(
                                    module,
                                    "[Permanent Clipboard] Provider accepted complete-history request"
                                )
                            }
                            return@intercept chain.proceed()
                        }
                        val args = chain.args.toTypedArray()
                        args[2] = rewritten.first
                        if (providerCallHookHitLogged.compareAndSet(false, true)) {
                            XposedUtils.log(
                                module,
                                "[Permanent Clipboard] Provider call hook active; preservedCount=${rewritten.second}"
                            )
                        }
                        chain.proceed(args)
                    } catch (t: Throwable) {
                        XposedUtils.logError(
                            module,
                            "[Permanent Clipboard] Failed to intercept provider clipboard write",
                            t
                        )
                        chain.proceed()
                    }
                }
            }
    }

    private fun rewriteClipboardWrite(
        context: Context,
        extras: Bundle,
        managerClass: Class<*>,
        fromJson: Method
    ): Pair<Bundle, Int>? {
        val singleJson = extras.getString("singleJson")
        if (singleJson.isNullOrEmpty()) return null

        val encodedHistory = context.getSharedPreferences("sp_name_clip_board", 0)
            .getString("clipboard_cipher_list", "").orEmpty()
        val historyJson = if (encodedHistory.isEmpty()) {
            ""
        } else {
            String(Base64.decode(encodedHistory, Base64.DEFAULT), Charsets.UTF_8)
        }
        val newModel = fromJson.invoke(null, JSONObject(singleJson)) ?: return null
        val merged = LinkedHashSet<Any>().apply {
            add(newModel)
            addAll(parseModels(managerClass, historyJson))
        }.sortedByDescending(::modelTime)

        val rewrittenExtras = Bundle(extras).apply {
            remove("singleJson")
            putString("jsonArray", serializeModels(merged))
        }
        return rewrittenExtras to merged.size
    }

    private fun queryModels(context: Context, managerClass: Class<*>): ArrayList<Any> {
        val json = JSONArray()
        context.contentResolver.query(
            Uri.parse(CLIPBOARD_URI),
            null,
            null,
            null,
            null
        )?.use { cursor ->
            val contentColumn = cursor.getColumnIndex("phrase_content")
            if (contentColumn >= 0 && cursor.moveToFirst()) {
                do {
                    json.put(JSONObject(cursor.getString(contentColumn)))
                } while (cursor.moveToNext())
            }
        }
        return parseModels(managerClass, json.toString())
    }

    private fun refreshConfig(context: Context?) {
        if (context != null) ConfigManager.syncFromProvider(context)
    }

    private fun logDisabledCallback(module: XposedInterface, source: String) {
        if (disabledCallbackLogged.compareAndSet(false, true)) {
            XposedUtils.logWarn(
                module,
                "[Permanent Clipboard] $source reached, but the synced feature flag is disabled"
            )
        }
    }

    private fun deoptimizeCallers(
        module: XposedInterface,
        managerClass: Class<*>,
        classLoader: ClassLoader
    ) {
        managerClass.declaredMethods
            .forEach { method ->
                try {
                    module.deoptimize(method)
                } catch (_: Throwable) {
                }
            }

        XposedUtils.findClass(STORAGE_CLASS, classLoader)?.declaredMethods
            ?.filter { it.name == "v" }
            ?.forEach { method ->
                try {
                    module.deoptimize(method)
                } catch (_: Throwable) {
                }
            }

        XposedUtils.findClass(PROVIDER_CLASS, classLoader)?.declaredMethods
            ?.filter { it.name == "call" }
            ?.forEach { method ->
                try {
                    module.deoptimize(method)
                } catch (_: Throwable) {
                }
            }

        listOf(
            POPUP_INIT_TASK_CLASS,
            "com.miui.inputmethod.b",
            "A0.d"
        ).forEach { className ->
            XposedUtils.findClass(className, classLoader)?.declaredMethods
                ?.filter { it.name == "run" }
                ?.forEach { method ->
                    try {
                        module.deoptimize(method)
                    } catch (_: Throwable) {
                    }
                }
        }
    }
}
