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
import io.mo.xatype.compat.AdaptationReporter
import io.mo.xatype.compat.LoadedApkSource
import io.mo.xatype.compat.PhraseSymbols
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.io.File
import java.lang.reflect.Method
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

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
    private const val PLUGIN_LOADER_CLASS = "android.inputmethodservice.InputMethodModuleManager"
    private const val POPUP_CLASS = "com.miui.inputmethod.InputMethodClipboardPhrasePopupView"
    private const val POPUP_INIT_TASK_CLASS = "com.miui.inputmethod.InputMethodClipboardPhrasePopupView\$2"
    private const val HEADER_ADAPTER_CLASS = "com.miui.inputmethod.InputMethodClipboardHeaderAdapter"
    private const val UNLIMITED_TIP = "剪贴板内容永久保存，不限制条数与文字长度"
    private val installedManagerClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val cachedManagerClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val activeManagerInstalls = ConcurrentHashMap.newKeySet<Class<*>>()
    @Volatile private var ownCacheDir: File? = null
    private val classLoaderWatcherInstalled = AtomicBoolean(false)
    private val phraseContextLoaderHookInstalled = AtomicBoolean(false)
    private val readHookHitLogged = AtomicBoolean(false)
    private val mergeHookHitLogged = AtomicBoolean(false)
    private val writeHookHitLogged = AtomicBoolean(false)
    private val providerCallHookHitLogged = AtomicBoolean(false)
    private val disabledCallbackLogged = AtomicBoolean(false)
    private val popupInitHookHitLogged = AtomicBoolean(false)
    private val frameworkBridgeInstalled = AtomicBoolean(false)
    @Volatile private var popupInitTaskName = POPUP_INIT_TASK_CLASS

    /**
     * @param phraseApk the com.miui.phrase APK behind [classLoader], when known;
     * @param cacheDir a directory this process may write fingerprint results to.
     */
    fun install(
        module: XposedInterface,
        classLoader: ClassLoader,
        phraseApk: String? = null,
        cacheDir: File? = null
    ) {
        if (cacheDir != null) ownCacheDir = cacheDir
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

        installForManagerClass(module, managerClass, phraseApk, cacheDir)
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

        installPluginLoaderHook(module)

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
                        task.javaClass.name == popupInitTaskName
                    ) {
                        task.javaClass.classLoader?.let { attachPluginLoader(module, it, null) }
                        val args = chain.args.toTypedArray()
                        args[0] = Runnable {
                            // Taken before the host drops expired entries, so the
                            // restore below never depends on winning that write.
                            val snapshot = snapshotHistory(module, task)
                            task.run()
                            restorePopupHistory(module, task, snapshot)
                        }
                        return@intercept chain.proceed(args)
                    }
                    chain.proceed()
                }
            }
    }

    /**
     * MIUI's InputMethodServiceInjector adds the phrase APK to its own
     * createPackageContext(com.miui.phrase, 0) loader and resolves the panel
     * with Class.forName, which neither our package context nor the loadClass
     * watcher sees. Hook that loader directly so the read/write hooks reach
     * the classes the IME panel actually runs.
     */
    private fun installPluginLoaderHook(module: XposedInterface) {
        val bootLoader = android.inputmethodservice.InputMethodService::class.java.classLoader ?: return
        val managerClass = XposedUtils.findClass(PLUGIN_LOADER_CLASS, bootLoader) ?: return
        val method = managerClass.declaredMethods.firstOrNull {
            it.name == "loadDex" && it.parameterTypes.contentEquals(
                arrayOf(ClassLoader::class.java, String::class.java)
            )
        } ?: return XposedUtils.logWarn(module, "[Permanent Clipboard] $PLUGIN_LOADER_CLASS.loadDex not found; plugin loader hook skipped")
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            val loader = chain.getArg(0) as? ClassLoader
            if (loader != null) attachPluginLoader(module, loader, chain.getArg(1) as? String)
            result
        }
    }

    private fun attachPluginLoader(module: XposedInterface, loader: ClassLoader, apkPath: String?) {
        val managerClass = XposedUtils.findClass(MANAGER_CLASS, loader) ?: return
        if (managerClass in installedManagerClasses) return
        try {
            installForManagerClass(
                module,
                managerClass,
                apkPath ?: LoadedApkSource.find(loader, MANAGER_CLASS),
                ownCacheDir ?: applicationCacheDir()
            )
            XposedUtils.log(module, "[Permanent Clipboard] Attached to IME plugin class loader")
        } catch (t: Throwable) {
            XposedUtils.logError(module, "[Permanent Clipboard] Failed to attach to IME plugin class loader", t)
        }
    }

    /** Lets the fingerprint cache be found before the IME service's onCreate runs. */
    private fun applicationCacheDir(): File? = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as? Context
        app?.applicationInfo?.deviceProtectedDataDir?.let { File(it, "cache") }
    }.getOrNull()?.also { ownCacheDir = it }

    private fun snapshotHistory(module: XposedInterface, task: Runnable): List<Any>? = try {
        val popup = XposedUtils.getObjectField(task, "this\$0")
        val context = popup?.let { XposedUtils.getObjectField(it, "mContext") as? Context }
        val loader = task.javaClass.classLoader
        if (context == null || loader == null) null
        else queryModels(context, Class.forName(MANAGER_CLASS, false, loader))
    } catch (t: Throwable) {
        XposedUtils.logError(module, "[Permanent Clipboard] Failed to snapshot history before popup load", t)
        null
    }

    private fun restorePopupHistory(module: XposedInterface, task: Runnable, snapshot: List<Any>?) {
        try {
            val popup = XposedUtils.getObjectField(task, "this\$0") ?: return
            val context = XposedUtils.getObjectField(popup, "mContext") as? Context ?: return
            val loader = task.javaClass.classLoader ?: return
            val managerClass = Class.forName(MANAGER_CLASS, false, loader)
            val loaded = (XposedUtils.getObjectField(popup, "mAllClipboardList") as? Collection<*>)
                ?.filterNotNull().orEmpty()
            val complete = if (snapshot == null) queryModels(context, managerClass) else {
                ArrayList(LinkedHashSet<Any>().apply {
                    addAll(loaded)
                    addAll(snapshot)
                }.sortedByDescending(::modelTime))
            }
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

    @Synchronized
    private fun installForManagerClass(
        module: XposedInterface,
        managerClass: Class<*>,
        phraseApk: String? = null,
        cacheDir: File? = null
    ) {
        val classLoader = managerClass.classLoader ?: return
        // Reflection during scanning can re-enter the global loadClass observer.
        if (!activeManagerInstalls.add(managerClass)) return
        try {
            val apk = phraseApk ?: LoadedApkSource.find(classLoader, MANAGER_CLASS)
            val cache = (cacheDir ?: ownCacheDir)?.let { File(it, "xatype-phrase-symbols.json") }
            val firstInstall = installedManagerClasses.add(managerClass)
            // A class-load observer may run before createPackageContext returns. Allow
            // its later call to supply a persistent cache without installing hooks twice.
            if (!firstInstall && (apk == null || cache == null || managerClass in cachedManagerClasses)) return

            val summary = PhraseSymbols.init(
                classLoader,
                apk,
                cache,
                XposedUtils.moduleStamp(module)
            )
            XposedUtils.log(module, "[Permanent Clipboard] Phrase symbols: $summary")
            if (cache?.isFile == true) cachedManagerClasses.add(managerClass)
            AdaptationReporter.report(module, PHRASE_PACKAGE, PhraseSymbols)
            if (!firstInstall) return
            PhraseSymbols.clazz(PhraseSymbols.POPUP_INIT_TASK)?.let { popupInitTaskName = it.name }

            installTextLengthHooks(module, managerClass)
            installCleanupHooks(module, managerClass)
            installPublicReadHook(module, managerClass)
            installReadHooks(module, managerClass)
            installMergeHooks(module, managerClass)
            installDisplayListWriteHook(module, managerClass)
            installProviderCallHook(module, classLoader, managerClass)
            installProviderEntryHook(module, classLoader, managerClass)
            installProviderWriteHook(module, classLoader, managerClass)
            installPopupHooks(module, classLoader)
            installHeaderHook(module, classLoader)
            deoptimizeCallers(module, managerClass, classLoader)
            XposedUtils.log(
                module,
                "[Permanent Clipboard] Unlimited clipboard hooks installed; enabled=${ConfigManager.isClipboardPermanentEnabled()}"
            )
        } finally {
            activeManagerInstalls.remove(managerClass)
        }
    }

    private fun installPhraseContextLoaderHook(module: XposedInterface, classLoader: ClassLoader) {
        if (!phraseContextLoaderHookInstalled.compareAndSet(false, true)) return
        val serviceClass = XposedUtils.findClass(IME_SERVICE_CLASS, classLoader) ?: return XposedUtils.logWarn(module, "[Permanent Clipboard] $IME_SERVICE_CLASS not found; phrase context loader hook skipped")
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
                            install(
                                module,
                                phraseContext.classLoader,
                                phraseContext.applicationInfo.sourceDir,
                                File(context.applicationInfo.deviceProtectedDataDir, "cache")
                            )
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
                            installForManagerClass(module, loadedClass,
                                LoadedApkSource.find(loadedClass.classLoader ?: return@intercept loadedClass, MANAGER_CLASS),
                                ownCacheDir)
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
        } ?: return XposedUtils.logWarn(module, "[Permanent Clipboard] getNoExpiredClipboardData not found; read hook skipped")
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
        } ?: return XposedUtils.logWarn(module, "[Permanent Clipboard] getClipboardData(Context) not found; public read hook skipped")
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

    private fun installDisplayListWriteHook(
        module: XposedInterface,
        managerClass: Class<*>
    ) {
        val method = managerClass.declaredMethods.firstOrNull {
            it.name == "setClipboardModelList" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Context::class.java &&
                List::class.java.isAssignableFrom(it.parameterTypes[1])
        } ?: return XposedUtils.logWarn(module, "[Permanent Clipboard] setClipboardModelList not found; display list hook skipped")
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
        } ?: return XposedUtils.logWarn(module, "[Permanent Clipboard] addContentListToJsonArray not found; merge hook skipped")
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
        val method = PhraseSymbols.method(PhraseSymbols.STORAGE_WRITE)
            ?: XposedUtils.findClass(STORAGE_CLASS, classLoader)?.declaredMethods?.firstOrNull {
                it.name == "f" && it.returnType == String::class.java &&
                    it.parameterTypes.map(Class<*>::getName) == listOf(
                        "android.content.Context",
                        "android.database.sqlite.SQLiteDatabase",
                        "com.miui.inputmethod.ClipboardContentModel",
                        "java.lang.String"
                    )
            }
            ?: return XposedUtils.logWarn(module, "[Permanent Clipboard] storage write method not found; provider write hook skipped")
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
        val popupClass = XposedUtils.findClass(POPUP_CLASS, classLoader) ?: return XposedUtils.logWarn(module, "[Permanent Clipboard] $POPUP_CLASS not found; popup hooks skipped")
        fun legacy(name: String, paramCount: Int) = popupClass.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.size == paramCount
        }
        listOfNotNull(
            PhraseSymbols.method(PhraseSymbols.POPUP_UPDATE_LAMBDA)
                ?: legacy("lambda\$updateClipboardData\$8", 1),
            PhraseSymbols.method(PhraseSymbols.POPUP_REMOTE_LAMBDA)
                ?: legacy("lambda\$setRemoteDataToView\$5", 0)
        ).forEach { method -> installListPreservationHook(module, method) }
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
        val adapterClass = XposedUtils.findClass(HEADER_ADAPTER_CLASS, classLoader) ?: return XposedUtils.logWarn(module, "[Permanent Clipboard] $HEADER_ADAPTER_CLASS not found; header hook skipped")
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

    private fun installProviderEntryHook(
        module: XposedInterface,
        classLoader: ClassLoader,
        managerClass: Class<*>
    ) {
        val method = PhraseSymbols.method(PhraseSymbols.STORAGE_ENTRY)
            ?: legacyStorageEntries(classLoader).firstOrNull()
            ?: return XposedUtils.logWarn(module, "[Permanent Clipboard] storage entry not found; provider entry hook skipped")
        val modelClass = XposedUtils.findClass(
            "com.miui.inputmethod.ClipboardContentModel",
            classLoader
        ) ?: return XposedUtils.logWarn(module, "[Permanent Clipboard] ClipboardContentModel not found; provider entry hook skipped")
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
        val providerClass = XposedUtils.findClass(PROVIDER_CLASS, classLoader) ?: return XposedUtils.logWarn(module, "[Permanent Clipboard] $PROVIDER_CLASS not found; provider call hook skipped")
        val modelClass = XposedUtils.findClass(
            "com.miui.inputmethod.ClipboardContentModel",
            classLoader
        ) ?: return XposedUtils.logWarn(module, "[Permanent Clipboard] ClipboardContentModel not found; provider call hook skipped")
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

        (PhraseSymbols.method(PhraseSymbols.STORAGE_ENTRY)?.let(::listOf)
            ?: legacyStorageEntries(classLoader))
            .forEach { method ->
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

        val runnables = listOf(popupInitTaskName, "com.miui.inputmethod.b", "A0.d")
            .flatMap { className ->
                XposedUtils.findClass(className, classLoader)?.declaredMethods
                    ?.filter { it.name == "run" }
                    .orEmpty()
            } + PhraseSymbols.methods(PhraseSymbols.MANAGER_RUNNABLES)
        runnables.toSet().forEach { method ->
            try {
                module.deoptimize(method)
            } catch (_: Throwable) {
            }
        }
    }

    /** Verified on com.miui.phrase 5.7.0 and earlier. */
    private fun legacyStorageEntries(classLoader: ClassLoader): List<Method> =
        XposedUtils.findClass(STORAGE_CLASS, classLoader)?.declaredMethods
            ?.filter {
                it.name == "v" && it.returnType == Bundle::class.java &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == Context::class.java &&
                    it.parameterTypes[2] == Bundle::class.java
            }
            .orEmpty()
}
