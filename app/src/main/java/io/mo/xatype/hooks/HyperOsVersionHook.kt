package io.mo.xatype.hooks

import android.app.Activity
import android.content.Context
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.lang.reflect.Modifier

object HyperOsVersionHook {

    private var hasLoggedSysProp = false

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        hookSystemProperties(module)
        patchS0Field(module, classLoader)
        hookAiVersion(module, classLoader)
        hookMetadataHelper(module, classLoader)
        hookDialogHostActivity(module, classLoader)
    }

    /**
     * 1. Hook android.os.SystemProperties to report HyperOS 4.0+ environment.
     */
    private fun hookSystemProperties(module: XposedInterface) {
        try {
            val sysPropClass = Class.forName("android.os.SystemProperties")

            // get(String, String)
            val methodGet2 = XposedUtils.findMethodExact(sysPropClass, "get", String::class.java, String::class.java)
            if (methodGet2 != null) {
                module.hook(methodGet2).intercept { chain ->
                    if (!ConfigManager.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                    val key = chain.getArg(0) as? String

                    when (key) {
                        "ro.mi.os.version.code" -> {
                            logPropertyMock(module, key, "4")
                            "4"
                        }
                        "ro.mi.os.version.name" -> {
                            logPropertyMock(module, key, "OS4.0")
                            "OS4.0"
                        }
                        "ro.mi.os.version.incremental" -> {
                            logPropertyMock(module, key, "OS4.0.1.0")
                            "OS4.0.1.0"
                        }
                        "ro.miui.ui.version.code" -> {
                            val orig = chain.proceed() as? String
                            if (orig.isNullOrEmpty() || orig == "0") "15" else orig
                        }
                        "ro.miui.ui.version.name" -> {
                            val orig = chain.proceed() as? String
                            if (orig.isNullOrEmpty() || orig == "unknown") "V15" else orig
                        }
                        else -> chain.proceed()
                    }
                }
                XposedUtils.log(module, "[HyperOS Unblock] Hooked SystemProperties.get(String, String)")
            }

            // get(String)
            val methodGet1 = XposedUtils.findMethodExact(sysPropClass, "get", String::class.java)
            if (methodGet1 != null) {
                module.hook(methodGet1).intercept { chain ->
                    if (!ConfigManager.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                    val key = chain.getArg(0) as? String

                    when (key) {
                        "ro.mi.os.version.code" -> {
                            logPropertyMock(module, key, "4")
                            "4"
                        }
                        "ro.mi.os.version.name" -> {
                            logPropertyMock(module, key, "OS4.0")
                            "OS4.0"
                        }
                        "ro.mi.os.version.incremental" -> {
                            logPropertyMock(module, key, "OS4.0.1.0")
                            "OS4.0.1.0"
                        }
                        else -> chain.proceed()
                    }
                }
                XposedUtils.log(module, "[HyperOS Unblock] Hooked SystemProperties.get(String)")
            }

            // getInt(String, int)
            val methodGetInt = XposedUtils.findMethodExact(
                sysPropClass,
                "getInt",
                String::class.java,
                Int::class.javaPrimitiveType ?: Integer.TYPE
            )
            if (methodGetInt != null) {
                module.hook(methodGetInt).intercept { chain ->
                    if (!ConfigManager.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                    val key = chain.getArg(0) as? String
                    val def = (chain.getArg(1) as? Number)?.toInt() ?: 0

                    when (key) {
                        "ro.mi.os.version.code" -> {
                            logPropertyMock(module, key, "4")
                            4
                        }
                        "ro.miui.ui.version.code" -> {
                            val orig = (chain.proceed() as? Number)?.toInt() ?: def
                            if (orig <= 0) 15 else orig
                        }
                        else -> chain.proceed()
                    }
                }
                XposedUtils.log(module, "[HyperOS Unblock] Hooked SystemProperties.getInt(String, int)")
            }

            // getLong(String, long)
            val methodGetLong = XposedUtils.findMethodExact(
                sysPropClass,
                "getLong",
                String::class.java,
                Long::class.javaPrimitiveType ?: java.lang.Long.TYPE
            )
            if (methodGetLong != null) {
                module.hook(methodGetLong).intercept { chain ->
                    if (!ConfigManager.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                    val key = chain.getArg(0) as? String
                    if ("ro.mi.os.version.code" == key) {
                        4L
                    } else {
                        chain.proceed()
                    }
                }
            }
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Failed to hook SystemProperties", t)
        }
    }

    private fun logPropertyMock(module: XposedInterface, key: String, mockVal: String) {
        if (!hasLoggedSysProp) {
            hasLoggedSysProp = true
            if (ConfigManager.isVerboseLogEnabled()) {
                XposedUtils.log(module, "[HyperOS Unblock] SystemProperties.$key -> mock '$mockVal'")
            }
        }
    }

    /**
     * 2. Reflectively patch z7.s0 static boolean flag (isNotOS4) to false in memory.
     */
    private fun patchS0Field(module: XposedInterface, classLoader: ClassLoader) {
        try {
            val s0Class = XposedUtils.findClass("z7.s0", classLoader)
            if (s0Class != null) {
                for (field in s0Class.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) && (field.type == Boolean::class.javaPrimitiveType || field.type == java.lang.Boolean.TYPE)) {
                        field.isAccessible = true
                        val oldVal = field.getBoolean(null)
                        field.setBoolean(null, false)
                        if (ConfigManager.isVerboseLogEnabled()) {
                            XposedUtils.log(module, "[HyperOS Unblock] Patched z7.s0.${field.name} from $oldVal to false")
                        }
                    }
                }
            } else {
                XposedUtils.logWarn(module, "[HyperOS Unblock] Class z7.s0 not found")
            }
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Failed to patch z7.s0", t)
        }
    }

    /**
     * 3. Hook com.xiaomi.taiyi.sdk.common.AIVersion to ensure AI service compatibility.
     */
    private fun hookAiVersion(module: XposedInterface, classLoader: ClassLoader) {
        val aiVersionClass = XposedUtils.findClass("com.xiaomi.taiyi.sdk.common.AIVersion", classLoader)
        if (aiVersionClass != null) {
            // isOS4Service(Context) -> return true
            val methodIsOS4 = XposedUtils.findMethodExact(aiVersionClass, "isOS4Service", Context::class.java)
            if (methodIsOS4 != null) {
                try {
                    module.hook(methodIsOS4).intercept { chain ->
                        if (!ConfigManager.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                        if (ConfigManager.isVerboseLogEnabled()) {
                            XposedUtils.log(module, "[HyperOS Unblock] AIVersion.isOS4Service() -> true")
                        }
                        true
                    }
                    XposedUtils.log(module, "[HyperOS Unblock] Hooked AIVersion.isOS4Service(Context)")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook AIVersion.isOS4Service", t)
                }
            }

            // isServiceSupport(Context) -> return true
            val methodIsSupport = XposedUtils.findMethodExact(aiVersionClass, "isServiceSupport", Context::class.java)
            if (methodIsSupport != null) {
                try {
                    module.hook(methodIsSupport).intercept { chain ->
                        if (!ConfigManager.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                        if (ConfigManager.isVerboseLogEnabled()) {
                            XposedUtils.log(module, "[HyperOS Unblock] AIVersion.isServiceSupport() -> true")
                        }
                        true
                    }
                    XposedUtils.log(module, "[HyperOS Unblock] Hooked AIVersion.isServiceSupport(Context)")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook AIVersion.isServiceSupport", t)
                }
            }

            // SERVICE_SDK_INT(Context) -> return 200
            val methodSdkInt = XposedUtils.findMethodExact(aiVersionClass, "SERVICE_SDK_INT", Context::class.java)
            if (methodSdkInt != null) {
                try {
                    module.hook(methodSdkInt).intercept { chain ->
                        if (!ConfigManager.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                        val orig = (chain.proceed() as? Number)?.toInt() ?: -1
                        if (orig < 200) 200 else orig
                    }
                    XposedUtils.log(module, "[HyperOS Unblock] Hooked AIVersion.SERVICE_SDK_INT(Context)")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook AIVersion.SERVICE_SDK_INT", t)
                }
            }
        }
    }

    /**
     * 4. Hook nc.a.s(Context, String, String) to provide virtual ai_sdk metadata if missing.
     */
    private fun hookMetadataHelper(module: XposedInterface, classLoader: ClassLoader) {
        val ncClass = XposedUtils.findClass("nc.a", classLoader)
        if (ncClass != null) {
            val methodS = XposedUtils.findMethodExact(
                ncClass,
                "s",
                Context::class.java,
                String::class.java,
                String::class.java
            )
            if (methodS != null) {
                try {
                    module.hook(methodS).intercept { chain ->
                        if (!ConfigManager.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                        val pkgName = chain.getArg(1) as? String
                        val metaKey = chain.getArg(2) as? String

                        if ("com.xiaomi.aiservice" == pkgName) {
                            when (metaKey) {
                                "ai_sdk_code" -> "200"
                                "ai_sdk_name" -> "2.0.0-b2a69a6-260410-SNAPSHOT01"
                                else -> chain.proceed()
                            }
                        } else {
                            chain.proceed()
                        }
                    }
                    XposedUtils.log(module, "[HyperOS Unblock] Hooked nc.a.s(Context, String, String)")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook nc.a.s", t)
                }
            }
        }
    }

    /**
     * 5. Hook DialogHostActivity to block OS_VERSION_UNSUPPORTED dialog if triggered.
     */
    private fun hookDialogHostActivity(module: XposedInterface, classLoader: ClassLoader) {
        val dialogHostClass = XposedUtils.findClass("com.mi.ime.dialog.DialogHostActivity", classLoader)
        if (dialogHostClass != null) {
            val methodOnCreate = XposedUtils.findMethodExact(dialogHostClass, "onCreate", Bundle::class.java)
            if (methodOnCreate != null) {
                try {
                    module.hook(methodOnCreate).intercept { chain ->
                        if (!ConfigManager.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                        val activity = chain.getThisObject() as? Activity
                        val dialogType = activity?.intent?.getStringExtra("dialog_type")
                        if ("OS_VERSION_UNSUPPORTED" == dialogType) {
                            if (ConfigManager.isVerboseLogEnabled()) {
                                XposedUtils.log(module, "[HyperOS Unblock] Suppressed OS_VERSION_UNSUPPORTED DialogHostActivity")
                            }
                            activity.finish()
                            return@intercept null
                        }
                        chain.proceed()
                    }
                    XposedUtils.log(module, "[HyperOS Unblock] Hooked DialogHostActivity.onCreate(Bundle)")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook DialogHostActivity.onCreate", t)
                }
            }
        }
    }
}
