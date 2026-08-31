package io.mo.xatype.util

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Method

object XposedUtils {
    private const val TAG = "XiaoAiTypeUnblock"

    fun log(module: XposedInterface, msg: String) {
        module.log(Log.INFO, TAG, msg)
        Log.i(TAG, msg)
    }

    fun logWarn(module: XposedInterface, msg: String) {
        module.log(Log.WARN, TAG, msg)
        Log.w(TAG, msg)
    }

    fun logError(module: XposedInterface, msg: String, tr: Throwable? = null) {
        if (tr != null) {
            module.log(Log.ERROR, TAG, msg, tr)
            Log.e(TAG, msg, tr)
        } else {
            module.log(Log.ERROR, TAG, msg)
            Log.e(TAG, msg)
        }
    }

    fun findClass(className: String, classLoader: ClassLoader): Class<*>? {
        return try {
            Class.forName(className, false, classLoader)
        } catch (t: Throwable) {
            null
        }
    }

    fun findMethodExact(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Method? {
        return try {
            clazz.getDeclaredMethod(methodName, *parameterTypes).apply {
                isAccessible = true
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun findFirstMethodByParamTypes(clazz: Class<*>, returnType: Class<*>?, vararg parameterTypes: Class<*>): Method? {
        for (m in clazz.declaredMethods) {
            if (returnType != null && m.returnType != returnType) continue
            val types = m.parameterTypes
            if (types.size == parameterTypes.size) {
                var match = true
                for (i in types.indices) {
                    if (types[i] != parameterTypes[i]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    m.isAccessible = true
                    return m
                }
            }
        }
        return null
    }

    fun getObjectField(obj: Any, fieldName: String): Any? {
        var currentClass: Class<*>? = obj.javaClass
        while (currentClass != null && currentClass != Any::class.java) {
            try {
                val field = currentClass.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(obj)
            } catch (_: NoSuchFieldException) {
                currentClass = currentClass.superclass
            } catch (t: Throwable) {
                return null
            }
        }
        return null
    }

    fun setObjectField(obj: Any, fieldName: String, value: Any?) {
        var currentClass: Class<*>? = obj.javaClass
        while (currentClass != null && currentClass != Any::class.java) {
            try {
                val field = currentClass.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(obj, value)
                return
            } catch (_: NoSuchFieldException) {
                currentClass = currentClass.superclass
            } catch (_: Throwable) {
                return
            }
        }
    }
}
