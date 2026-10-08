package io.mo.xatype.compat

import android.content.Context
import android.os.Bundle
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexClass
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import org.luckypray.dexkit.query.matchers.FieldMatcher
import java.lang.reflect.Modifier

/**
 * Fingerprints anchored on strings, constants and non-obfuscated types that
 * stayed stable across host builds 0.2.701 to 0.2.1053. Each one must match
 * exactly one member; ambiguity is treated as a miss rather than a guess.
 */
internal object HostFingerprints {
    private const val SERVICE = "com.mi.ime.MiInputMethodService"

    fun resolve(bridge: DexKitBridge, loader: ClassLoader): FingerprintResult {
        val collector = FingerprintCollector()
        collector.put(HostSymbols.AI_SAFETY_PARSERS) { listOf(aiSafetyParsers(bridge, loader)) }
        collector.put(HostSymbols.VOICE_MODERATION) { listOf(listOf(voiceModeration(bridge))) }
        collector.put(HostSymbols.ASR_ERROR_MAPPER) { listOf(listOf(asrErrorMapper(bridge, loader))) }
        collector.put(HostSymbols.ASR_ERROR_CALLBACK) { listOf(listOf(asrErrorCallback(bridge))) }
        collector.put(HostSymbols.OS_VERSION_GATE) { listOf(listOf(osVersionGate(bridge, loader))) }
        collector.put(
            HostSymbols.HEIGHT_RECT,
            HostSymbols.HEIGHT_CLAMP,
            HostSymbols.HEIGHT_RECT_GETTER,
            HostSymbols.HEIGHT_LAYOUT,
            HostSymbols.HEIGHT_DRAG,
            HostSymbols.HEIGHT_DRAG_KIND
        ) { keyboardHeight(bridge, loader).map { listOf(it) } }
        collector.put(HostSymbols.MODERN_KEYBOARD) {
            listOf(ModernKeyboardFingerprint.resolve(bridge, loader).toProperties())
        }
        collector.put(HostSymbols.BOTTOM_SPACING, HostSymbols.BOTTOM_SPACING_CONSUME,
            HostSymbols.BOTTOM_SPACING_CALLERS) { bottomSpacing(bridge, loader) }
        return collector.result()
    }

    /** Native margin helper; resource names survive R-field and class renaming. */
    private fun bottomSpacing(bridge: DexKitBridge, loader: ClassLoader): List<List<String>> {
        val gap = single(bridge.findMethod {
            matcher {
                modifiers(Modifier.STATIC)
                returnType("float")
                addUsingField(FieldMatcher().name("keyboard_bottom_margin_with_bottom_view"))
                addUsingField(FieldMatcher().name("keyboard_bottom_margin_three_button"))
            }
        }.filter { it.paramCount in 1..2 }, "keyboard bottom margin")
        val composer = gap.getMethodInstance(loader).parameterTypes.last()
        // Both verified layouts consume floating-mode as their first Boolean
        // local. Capture that native read instead of relying on an obfuscated field.
        val consume = single(gap.invokes.filter {
            it.className == composer.name && it.paramCount == 1 &&
                it.returnTypeName == "java.lang.Object" &&
                it.paramTypeNames.single().let { name ->
                    name != "java.lang.Object" && name != "int" && name != "boolean"
                }
        }.distinctBy { it.descriptor }, "bottom margin Compose consume")
        val callers = gap.callers.map { it.descriptor }.distinct()
        check(callers.isNotEmpty()) { "bottom margin callers" }
        return listOf(listOf(gap.descriptor), listOf(consume.descriptor), callers)
    }

    /** JSON parsers of AI smart replies; their input carries `safety_blocked`. */
    private fun aiSafetyParsers(bridge: DexKitBridge, loader: ClassLoader): List<String> {
        val parsers = bridge.findClass {
            matcher { usingEqStrings("safety_blocked", "peer_summary") }
        }.map { it.getInstance(loader) }
            .map { type ->
                type.declaredMethods.filter {
                    it.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                        isHostType(it.returnType)
                }
            }
            .filter { it.size >= 2 }
        return single(parsers, "safety parser class").map { DexMethod(it).toString() }
    }

    /** MiclawErrorHelper's toast/dialog dispatcher, keyed by error code. */
    private fun voiceModeration(bridge: DexKitBridge): String = single(
        bridge.findMethod {
            matcher {
                paramTypes(Context::class.java, String::class.java, String::class.java)
                returnType("void")
                usingEqStrings("CONTENT_MODERATION", "MiclawErrorHelper")
            }
        }, "Miclaw error helper"
    ).descriptor

    /** Maps a speech-service error code to its enum; 30002 is content moderation. */
    private fun asrErrorMapper(bridge: DexKitBridge, loader: ClassLoader): String = single(
        bridge.findMethod {
            matcher {
                modifiers(Modifier.STATIC)
                paramTypes(Int::class.javaPrimitiveType, String::class.java)
                usingEqStrings("ERR_", "CONTENT_MODERATION")
            }
        }.filter { it.getMethodInstance(loader).returnType != Void.TYPE }, "ASR error mapper"
    ).descriptor

    private fun asrErrorCallback(bridge: DexKitBridge): String = single(
        bridge.findMethod {
            matcher {
                paramTypes(Bundle::class.java)
                returnType("void")
                usingStrings("[Callback] onError")
            }
        }, "ASR onError callback"
    ).descriptor

    /** Holder of `ro.mi.os.version.code < 4`, which blocks the keyboard. */
    private fun osVersionGate(bridge: DexKitBridge, loader: ClassLoader): String {
        val holders = bridge.findMethod {
            matcher {
                name = "<clinit>"
                usingEqStrings("android.os.SystemProperties", "ro.mi.os.version.code")
                usingNumbers(4)
            }
        }.map { it.getClassInstance(loader) }.filter { type ->
            val field = type.declaredFields.singleOrNull()
            field != null && Modifier.isStatic(field.modifiers) &&
                field.type == java.lang.Boolean.TYPE
        }
        return DexClass(single(holders, "OS version gate")).toString()
    }

    /**
     * The keyboard-adjust geometry: rectangle, coerceIn helper, its saved
     * value getter, the dimension layout and the top-handle drag lambda that
     * cap growth at 50dp. Returned in [HostSymbols] HEIGHT_* order.
     */
    private fun keyboardHeight(bridge: DexKitBridge, loader: ClassLoader): List<String> {
        val int = Int::class.javaPrimitiveType!!
        val rect = single(
            bridge.findClass { matcher { usingStrings("AdjustRect(left=") } }, "AdjustRect"
        )
        val rectType = rect.getInstance(loader)
        rectType.getDeclaredConstructor(int, int, int, int)

        val clamp = single(
            bridge.findMethod {
                matcher {
                    modifiers(Modifier.STATIC)
                    paramTypes(int, int, int)
                    returnType("int")
                    usingStrings("Cannot coerce value to an empty range: maximum ")
                }
            }, "coerceIn(int)"
        )

        val service = Class.forName(SERVICE, false, loader)
        val manager = single(
            service.declaredMethods.filter {
                it.name.startsWith("getUiStateManager") && it.parameterTypes.isEmpty()
            }, "UI state manager getter"
        ).returnType
        val getter = single(
            manager.declaredMethods.filter {
                it.parameterTypes.isEmpty() && it.returnType == rectType
            }, "saved AdjustRect getter"
        )
        val getterDescriptor = DexMethod(getter).toString()

        fun invokes(method: MethodData, vararg descriptors: String): Boolean {
            val called = method.invokes.map { it.descriptor }.toSet()
            return descriptors.all { it in called }
        }

        val layout = single(
            bridge.findMethod {
                matcher {
                    modifiers(Modifier.STATIC)
                    paramCount(3)
                    returnType("void")
                    usingEqStrings("qwerty", "number", "rect")
                    usingNumbers(-50, 50)
                }
            }.filter {
                it.paramTypeNames.take(2) == listOf("int", SERVICE) &&
                    invokes(it, clamp.descriptor, getterDescriptor)
            }, "keyboard dimension layout"
        )

        val drag = single(
            bridge.findMethod {
                matcher {
                    paramTypes(Any::class.java, Any::class.java)
                    returnType(Any::class.java)
                    usingEqStrings("rect")
                    usingNumbers(-50, 50)
                }
            }.filter { method ->
                // Dragging copies the rectangle; other "rect" lambdas construct one.
                invokes(method, clamp.descriptor) && method.invokes.any {
                    it.isMethod && it.className == rect.name && it.returnTypeName == rect.name
                }
            }, "adjust drag lambda"
        )
        // The lambda class serves every handle; its only int field picks the branch.
        val kind = single(
            drag.getClassInstance(loader).declaredFields.filter {
                it.type == int && !Modifier.isStatic(it.modifiers)
            }, "drag branch field"
        )

        return listOf(
            rect.descriptor,
            clamp.descriptor,
            getterDescriptor,
            layout.descriptor,
            drag.descriptor,
            DexField(kind).toString()
        )
    }

    private fun isHostType(type: Class<*>): Boolean =
        !type.isPrimitive && !type.isArray &&
            !type.name.startsWith("java.") && !type.name.startsWith("kotlin.")
}
