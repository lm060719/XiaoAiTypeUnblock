package io.mo.xatype.hooks

import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.data.LogType
import io.mo.xatype.util.LogBridge
import io.mo.xatype.util.XposedUtils

object CloudBlacklistHook {

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        // 1. Hook com.iflytek.inputmethod.smartengine.c1.onPyCloudAttachUpdate
        val c1Class = XposedUtils.findClass("com.iflytek.inputmethod.smartengine.c1", classLoader)
        if (c1Class != null) {
            val methodAttach = XposedUtils.findMethodExact(
                c1Class,
                "onPyCloudAttachUpdate",
                Int::class.javaPrimitiveType ?: Integer.TYPE,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java
            )
            if (methodAttach != null) {
                try {
                    module.hook(methodAttach).intercept { chain ->
                        if (!ConfigManager.isCloudBlacklistEnabled()) return@intercept chain.proceed()
                        val blacklistStr = chain.getArg(1) as? String
                        if (!blacklistStr.isNullOrEmpty()) {
                            if (ConfigManager.isVerboseLogEnabled()) {
                                XposedUtils.log(module, "[Cloud Blacklist] Intercepted and cleared cloud blacklist (len=${blacklistStr.length}) in c1.onPyCloudAttachUpdate")
                            }
                            LogBridge.record(
                                LogType.CLOUD_BLACKLIST,
                                "清空云端联想词黑名单",
                                "来源: c1.onPyCloudAttachUpdate | 下发黑名单词库 (长度: ${blacklistStr.length}) 已被全部清空置空"
                            )
                        }
                        // Replace blacklist string and version with empty strings
                        chain.proceed(arrayOf(chain.getArg(0), "", "", chain.getArg(3), chain.getArg(4)))
                    }
                    XposedUtils.log(module, "[Cloud Blacklist] Hooked c1.onPyCloudAttachUpdate()")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook c1.onPyCloudAttachUpdate", t)
                }
            }
        }

        // 2. Hook com.iflytek.inputmethod.smart.api.entity.PinyinCloudAttachResult
        val attachResultClass = XposedUtils.findClass("com.iflytek.inputmethod.smart.api.entity.PinyinCloudAttachResult", classLoader)
        if (attachResultClass != null) {
            // Hook getBlackListStr() -> return ""
            val methodGet = XposedUtils.findMethodExact(attachResultClass, "getBlackListStr")
            if (methodGet != null) {
                try {
                    module.hook(methodGet).intercept { chain ->
                        if (!ConfigManager.isCloudBlacklistEnabled()) {
                            chain.proceed()
                        } else {
                            "" // Always return empty blacklist
                        }
                    }
                    XposedUtils.log(module, "[Cloud Blacklist] Hooked PinyinCloudAttachResult.getBlackListStr() -> return empty")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook PinyinCloudAttachResult.getBlackListStr", t)
                }
            }

            // Hook setBlackListStr(String) -> set ""
            val methodSet = XposedUtils.findMethodExact(attachResultClass, "setBlackListStr", String::class.java)
            if (methodSet != null) {
                try {
                    module.hook(methodSet).intercept { chain ->
                        if (!ConfigManager.isCloudBlacklistEnabled()) {
                            chain.proceed()
                        } else {
                            val str = chain.getArg(0) as? String
                            if (!str.isNullOrEmpty()) {
                                LogBridge.record(
                                    LogType.CLOUD_BLACKLIST,
                                    "阻断黑名单词库写入",
                                    "来源: PinyinCloudAttachResult.setBlackListStr | 尝试写入黑名单数据被拦截并替换为空"
                                )
                            }
                            chain.proceed(arrayOf(""))
                        }
                    }
                    XposedUtils.log(module, "[Cloud Blacklist] Hooked PinyinCloudAttachResult.setBlackListStr(String)")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook PinyinCloudAttachResult.setBlackListStr", t)
                }
            }

            // Hook fromBundle(Bundle)
            val methodFromBundle = XposedUtils.findMethodExact(attachResultClass, "fromBundle", Bundle::class.java)
            if (methodFromBundle != null) {
                try {
                    module.hook(methodFromBundle).intercept { chain ->
                        val result = chain.proceed()
                        if (ConfigManager.isCloudBlacklistEnabled()) {
                            try {
                                val fieldB = attachResultClass.getDeclaredField("b").apply { isAccessible = true }
                                fieldB.set(chain.getThisObject(), "")
                            } catch (_: Throwable) {}
                        }
                        result
                    }
                    XposedUtils.log(module, "[Cloud Blacklist] Hooked PinyinCloudAttachResult.fromBundle(Bundle)")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook PinyinCloudAttachResult.fromBundle", t)
                }
            }
        }
    }
}
