package io.mo.xatype.compat

import android.content.Context
import android.os.Bundle
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexClass
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod

/**
 * Fingerprints anchored on strings, constants and non-obfuscated types that
 * stayed stable across host builds 0.2.701 to 0.2.1358. Each one must match
 * exactly one member; ambiguity is treated as a miss rather than a guess.
 */
internal object HostFingerprints {
    private const val SERVICE = "com.mi.ime.MiInputMethodService"
    private const val RESUME_BEFORE_INVOKE = "call to 'resume' before 'invoke' with coroutine"

    fun resolve(bridge: DexKitBridge, loader: ClassLoader): FingerprintResult {
        val collector = FingerprintCollector()
        collector.put(HostSymbols.KEYBOARD_KEY_PREVIEW, HostSymbols.KEYBOARD_KEY_PREVIEW_FILL) {
            keyPreview(bridge, loader)
        }
        collector.put(HostSymbols.KOTLIN_COROUTINES) { listOf(coroutines(bridge, loader)) }
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
        collector.put(HostSymbols.CLOUD_BLACKLIST) { listOf(listOf(cloudBlacklistAttach(bridge))) }
        collector.put(HostSymbols.PANEL_EXPANSION, HostSymbols.PANEL_SNAP, HostSymbols.PANEL_ANIMATE,
            HostSymbols.PANEL_SPRING) { panelExpansion(bridge, loader) }
        // One close-handler scan feeds both groups; each still misses independently.
        val panelClose = runCatching { panelCloseAnchor(bridge, loader) }
        collector.put(HostSymbols.UI_MANAGER, HostSymbols.UI_MODE, HostSymbols.UI_FLOATING) {
            panelClose.getOrThrow().let { listOf(listOf(it.manager.descriptor), listOf(it.mode.descriptor),
                listOf(it.floating.descriptor)) }
        }
        collector.put(HostSymbols.UI_CLOSING, HostSymbols.UI_CLOSING_STATE, HostSymbols.UI_AI_MODE,
            HostSymbols.PANEL_CLOSE) { panelCloseState(panelClose.getOrThrow()) }
        val keyboard = KeyboardAbiFingerprints(bridge)
        collector.put(HostSymbols.T9_SHUANGPIN_INIT, HostSymbols.T9_SHUANGPIN_BOOLEAN,
            HostSymbols.T9_SHUANGPIN_STRING) { t9Shuangpin(bridge, loader) }
        KeyboardAbi.groups.forEach { group ->
            collector.put(group) { listOf(keyboard.resolve(group)) }
        }
        return collector.result()
    }

    /**
     * The tap preview bubble's onDraw. Through 0.2.1246 it fills with a second Paint; 0.2.1358
     * draws the fill from a cached bitmap keyed by an int color field, returned second (or empty).
     */
    private fun keyPreview(bridge: DexKitBridge, loader: ClassLoader): List<List<String>> {
        val draw = single(
            bridge.findMethod {
                matcher {
                    name("onDraw")
                    paramTypes("android.graphics.Canvas")
                    returnType("void")
                    usingEqStrings("canvas")
                }
            }.filter { method ->
                val calls = method.invokes.filter { it.className == "android.graphics.Canvas" }.map { it.name }
                if ("drawText" !in calls) return@filter false
                val owner = method.getMethodInstance(loader).declaringClass
                val fields = owner.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
                val paints = fields.count { it.type == android.graphics.Paint::class.java }
                // The cached fill is a bitmap drawn before the label.
                val cached = paints == 1 && "drawBitmap" in calls
                owner.superclass == android.view.View::class.java &&
                    (paints == 2 && "drawRoundRect" in calls || cached) &&
                    fields.count { it.type == String::class.java } == 1 &&
                    fields.any { it.type == android.graphics.RectF::class.java }
            }, "key preview bubble drawing")
        val owner = draw.getMethodInstance(loader).declaringClass
        if (owner.declaredFields.count { it.type == android.graphics.Paint::class.java } == 2) {
            return listOf(listOf(draw.descriptor), emptyList())
        }
        // The fill color is the first mutable int the drawing reads; the inset before it is final.
        val fill = draw.usingFields.map { it.field }.firstOrNull { field ->
            field.className == owner.name && field.typeName == "int" &&
                field.getFieldInstance(loader).modifiers.let { !Modifier.isStatic(it) && !Modifier.isFinal(it) }
        } ?: throw FingerprintMiss("key preview fill color")
        return listOf(listOf(draw.descriptor), listOf(fill.descriptor))
    }

    /** Kotlin's createCoroutineFromSuspendFunction wrapper (a ContinuationImpl), Unit and CoroutineSingletons. */
    private fun coroutines(bridge: DexKitBridge, loader: ClassLoader): List<String> {
        val wrapper = single(bridge.findClass {
            matcher { usingEqStrings("This coroutine had already completed") }
        }.map { it.getInstance(loader) }.filter { type ->
            // (completion, context, Function2 block, receiver)
            val parameters = type.declaredConstructors.singleOrNull()?.parameterTypes
            parameters != null && parameters.size == 4 && parameters[0].isInterface &&
                parameters[0] == parameters[3] && parameters[1].isInterface && parameters[2].isInterface
        }, "suspend function wrapper")
        val unit = single(bridge.findClass {
            matcher { usingEqStrings("kotlin.Unit") }
        }.map { it.getInstance(loader) }.filter { type ->
            type.declaredFields.any { Modifier.isStatic(it.modifiers) && it.type == type }
        }, "kotlin.Unit")
        val singletons = single(bridge.findClass {
            matcher { usingEqStrings("COROUTINE_SUSPENDED", "UNDECIDED", "RESUMED") }
        }.map { it.getInstance(loader) }.filter { it.isEnum }, "CoroutineSingletons")
        return listOf(DexClass(wrapper).toString(), DexClass(unit).toString(), DexClass(singletons).toString())
    }

    /** T9 already passes digit groups to the native decoder's ShuangPin implementation. */
    private fun t9Shuangpin(bridge: DexKitBridge, loader: ClassLoader): List<List<String>> {
        val initialize = single(bridge.findMethod {
            matcher {
                paramCount(2)
                returnType("void")
                usingEqStrings("IflytekT9Engine initialized", "shuangpin_enabled", "shuangpin_scheme")
            }
        }.filter { method ->
            method.paramTypeNames.firstOrNull() == SERVICE &&
                method.usingFields.any { it.field.name == "INPUT_TYPE_CN_9" } &&
                method.invokes.any { it.name == "updateShuangPinType" &&
                    it.className == "com.iflytek.depend.common.pinyin.IPinYinEngine" }
        }, "T9 ShuangPin initialization")
        fun preference(returnType: String, parameters: List<String>): String = single(
            initialize.invokes.filter { it.returnTypeName == returnType && it.paramTypeNames == parameters &&
                Modifier.isStatic(it.getMethodInstance(loader).modifiers) }.distinctBy { it.descriptor },
            "T9 ShuangPin $returnType preference"
        ).descriptor
        return listOf(listOf(initialize.descriptor),
            listOf(preference("boolean", listOf("java.lang.String", "boolean"))),
            listOf(preference("java.lang.String", listOf("java.lang.String", "java.lang.String"))))
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
                // 0.2.1246 moved the int parameter from first to last.
                it.paramTypeNames.containsAll(listOf("int", SERVICE)) &&
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

    /** The engine's XFInputCoreDelegate implementation; only the interface keeps its name. */
    private fun cloudBlacklistAttach(bridge: DexKitBridge): String = single(
        bridge.findMethod {
            matcher {
                name = "onPyCloudAttachUpdate"
                paramTypes("int", "java.lang.String", "java.lang.String", "java.lang.String", "java.lang.String")
                returnType("void")
            }
        }.filter { !Modifier.isAbstract(it.modifiers) }, "cloud attach update"
    ).descriptor

    /**
     * The extra-height coroutine started beside the "keyboardHeight" animation:
     * its body, Animatable.snapTo, animateTo$default and the spring factory, in
     * [HostSymbols] PANEL_* order. From 0.2.974 the body snaps the expand
     * (label 1) and closing (label 2) targets that earlier builds animated.
     */
    private fun panelExpansion(bridge: DexKitBridge, loader: ClassLoader): List<List<String>> {
        val outer = single(bridge.findMethod {
            matcher { usingEqStrings("keyboardHeight") }
        }.filter { method ->
            method.invokes.any { it.className == SERVICE && it.name.startsWith("getUiStateManager") }
        }, "keyboard height composable")
        // (expanding, closing, Animatable, target height, Continuation)
        val start = single(outer.invokes.filter { invoke ->
            val p = invoke.paramTypeNames
            invoke.isConstructor && p.size == 5 && p[0] == "boolean" && p[1] == "boolean" &&
                p[3] == "float" && '.' in p[2] && '.' in p[4]
        }.distinctBy { it.descriptor }, "panel expansion constructor")
        val animatable = start.paramTypeNames[2]
        val continuation = start.paramTypeNames[4]
        val spring = single(outer.invokes.filter {
            it.isMethod && it.paramTypeNames == listOf("float", "float", "java.lang.Object", "int")
        }.distinctBy { it.descriptor }, "spring factory")
        val body = single(bridge.findMethod {
            matcher {
                declaredClass(start.className)
                usingEqStrings(RESUME_BEFORE_INVOKE)
            }
        }, "panel expansion body")
        // 0.2.1246 swapped snapTo's parameters; the continuation still identifies the owner.
        // 0.2.1358 narrowed it to the coroutine's own base class.
        val continuationType = Class.forName(continuation, false, loader)
        fun isContinuation(name: String) = name != "java.lang.Object" &&
            runCatching { continuationType.isAssignableFrom(Class.forName(name, false, loader)) }.getOrDefault(false)
        val snap = single(body.invokes.filter {
            it.className == animatable && it.paramCount == 2 && it.returnTypeName == "java.lang.Object" &&
                it.paramTypeNames.any(::isContinuation)
        }.distinctBy { it.descriptor }, "Animatable.snapTo")
        // animateTo$default(animatable, target, spec, velocity, continuation, mask) through
        // 0.2.1246; 0.2.1358 keeps only animateTo(animatable, target, spec, continuation).
        val animate = single(checkNotNull(bridge.getClassData(animatable)) { "Animatable class data" }.methods.filter {
            val p = it.paramTypeNames
            Modifier.isStatic(it.modifiers) && it.returnTypeName == "java.lang.Object" && p.firstOrNull() == animatable &&
                (p.size == 6 && p[4] == continuation && p[5] == "int" ||
                    p.size == 4 && p[1] == "java.lang.Object" && p[3] == continuation && '.' in p[2])
        }, "Animatable.animateTo")
        val spec = animate.getMethodInstance(loader).parameterTypes[2]
        if (!spec.isAssignableFrom(spring.getMethodInstance(loader).returnType)) {
            throw FingerprintMiss("spring factory does not build an animateTo spec")
        }
        return listOf(listOf(body.descriptor), listOf(snap.descriptor), listOf(animate.descriptor),
            listOf(spring.descriptor))
    }

    /** The reply panel's close handler and the UI-state members it consults. */
    private class PanelCloseAnchor(
        val close: MethodData,
        val manager: MethodData,
        val mode: MethodData,
        val closing: MethodData,
        val floating: MethodData
    )

    private fun panelCloseAnchor(bridge: DexKitBridge, loader: ClassLoader): PanelCloseAnchor {
        // 0.2.1358 made the handler a suspend function: (service, continuation) -> Object.
        val close = single(bridge.findMethod {
            matcher {
                modifiers(Modifier.STATIC)
                addInvoke { name = "getSmartReplyInputHost\$app_iflytekFullRelease" }
            }
        }.filter { method ->
            val parameters = method.paramTypeNames
            parameters.firstOrNull() == SERVICE && (parameters.size == 1 && method.returnTypeName == "void" ||
                parameters.size == 2 && method.returnTypeName == "java.lang.Object" && '.' in parameters[1])
        }.filter { method ->
            method.invokes.filter { it.className == SERVICE }.map { it.name }.containsAll(listOf(
                "getSmartReplyReplace\$app_iflytekFullRelease", "getFloatingKeyboardManager\$app_iflytekFullRelease"
            ))
        }, "reply panel close")
        val calls = close.invokes
        val manager = single(calls.filter {
            it.className == SERVICE && it.name.startsWith("getUiStateManager") && it.paramCount == 0
        }.distinctBy { it.descriptor }, "UI state manager getter")
        val getters = calls.filter { it.className == manager.returnTypeName && it.paramCount == 0 }
            .distinctBy { it.descriptor }
        val mode = single(getters.filter { '.' in it.returnTypeName }, "UI mode getter")
        if (!Enum::class.java.isAssignableFrom(mode.getReturnTypeInstance(loader))) {
            throw FingerprintMiss("UI mode is not an enum")
        }
        // Every build first checks the closing flag, then whether the keyboard floats.
        val flags = getters.filter { it.returnTypeName == "boolean" }
        if (flags.size != 2) throw FingerprintMiss("UI state flags: ${flags.size}")
        return PanelCloseAnchor(close, manager, mode, flags[0], flags[1])
    }

    /** Returned in [HostSymbols] UI_CLOSING, UI_CLOSING_STATE, UI_AI_MODE, PANEL_CLOSE order. */
    private fun panelCloseState(anchor: PanelCloseAnchor): List<List<String>> {
        val managerType = anchor.manager.returnTypeName
        val modeType = anchor.mode.returnTypeName
        val replyMode = single(anchor.close.usingFields.map { it.field }.filter {
            it.className == modeType && it.typeName == modeType
        }.distinctBy { it.descriptor }, "reply panel mode")
        val state = single(anchor.closing.usingFields.map { it.field }.filter {
            it.className == managerType
        }.distinctBy { it.descriptor }, "closing state")
        // 0.2.910 and earlier enter the closing state themselves and animate it natively.
        val native = anchor.close.invokes.any {
            it.className == managerType && it.paramTypeNames == listOf("boolean") && it.returnTypeName == "void"
        }
        return listOf(listOf(anchor.closing.descriptor), listOf(state.descriptor), listOf(replyMode.descriptor),
            if (native) emptyList() else listOf(anchor.close.descriptor))
    }

    private fun isHostType(type: Class<*>): Boolean =
        !type.isPrimitive && !type.isArray &&
            !type.name.startsWith("java.") && !type.name.startsWith("kotlin.")
}
