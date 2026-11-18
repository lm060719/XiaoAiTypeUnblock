package io.mo.xatype.compat

import android.content.Context
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Use the service's stable named getter, then validate the event dispatcher structurally. */
internal class KeyboardHapticBinding(service: Class<*>) {
    private val manager = service.declaredMethods.single {
        it.name.startsWith("getHapticFeedbackManager\$") && it.parameterCount == 0 &&
            !Modifier.isStatic(it.modifiers)
    }.returnType

    val dispatch: Method = manager.declaredMethods.single {
        !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE && it.parameterCount == 2 &&
            it.parameterTypes[0].isEnum && it.parameterTypes[0].enumConstants.any { event ->
                (event as Enum<*>).name == "KEY_PRESS"
            }
    }.apply { isAccessible = true }

    private val context: Field = manager.declaredFields.single {
        !Modifier.isStatic(it.modifiers) && Context::class.java.isAssignableFrom(it.type)
    }.apply { isAccessible = true }

    fun context(instance: Any): Context = context.get(instance) as Context
}
