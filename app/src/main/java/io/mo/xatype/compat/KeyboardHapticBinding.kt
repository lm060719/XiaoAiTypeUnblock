package io.mo.xatype.compat

import android.content.Context
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Use the service's stable named getter, then validate the event dispatcher structurally. */
internal class KeyboardHapticBinding(service: Class<*>) {
    private val managerGetter = service.declaredMethods.single {
        it.name.startsWith("getHapticFeedbackManager\$") && it.parameterCount == 0 &&
            !Modifier.isStatic(it.modifiers)
    }.apply { isAccessible = true }
    private val manager = managerGetter.returnType

    val dispatch: Method = manager.declaredMethods.single {
        !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE && it.parameterCount == 2 &&
            it.parameterTypes[0].isEnum && it.parameterTypes[0].enumConstants.any { event ->
                (event as Enum<*>).name == "KEY_PRESS"
            }
    }.apply { isAccessible = true }

    private val context: Field = manager.declaredFields.single {
        !Modifier.isStatic(it.modifiers) && Context::class.java.isAssignableFrom(it.type)
    }.apply { isAccessible = true }

    private val scopeGetter by lazy {
        service.declaredMethods.single {
            it.name.startsWith("getServiceScope\$") && it.parameterCount == 0 &&
                it.returnType == dispatch.parameterTypes[1] && !Modifier.isStatic(it.modifiers)
        }.apply { isAccessible = true }
    }

    fun context(instance: Any): Context = context.get(instance) as Context

    fun keyPressFeedback(service: Any): () -> Unit {
        val manager = checkNotNull(managerGetter.invoke(service))
        val scope = checkNotNull(scopeGetter.invoke(service))
        val event = checkNotNull(dispatch.parameterTypes[0].enumConstants.single { (it as Enum<*>).name == "KEY_PRESS" })
        return { dispatch.invoke(manager, event, scope) }
    }
}
