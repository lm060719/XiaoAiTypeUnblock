package io.mo.xatype.compat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * Members of MIUIFrequentPhrase (com.miui.phrase), whose classes also load in
 * the input method for its clipboard panel. Most of its API keeps readable
 * names; these are the obfuscated or compiler-numbered ones.
 */
object PhraseSymbols : SymbolTable(1, PhraseFingerprints::resolve) {
    const val STORAGE_WRITE = "storage.write"
    const val STORAGE_ENTRY = "storage.entry"
    const val POPUP_UPDATE_LAMBDA = "popup.updateLambda"
    const val POPUP_REMOTE_LAMBDA = "popup.remoteLambda"
    const val POPUP_INIT_TASK = "popup.initTask"
    const val MANAGER_RUNNABLES = "manager.runnables"
}

/** Anchors verified on com.miui.phrase 5.6.9 to 5.7.4. */
internal object PhraseFingerprints {
    private const val MANAGER = "com.miui.inputmethod.MiuiClipboardManager"
    private const val MODEL = "com.miui.inputmethod.ClipboardContentModel"
    private const val POPUP = "com.miui.inputmethod.InputMethodClipboardPhrasePopupView"

    fun resolve(bridge: DexKitBridge, loader: ClassLoader): FingerprintResult {
        val collector = FingerprintCollector()
        // InputPhraseUtils merges one new clip into the stored JSON history.
        collector.put(PhraseSymbols.STORAGE_WRITE) {
            listOf(listOf(single(bridge.findMethod {
                matcher {
                    paramTypes(Context::class.java.name, SQLiteDatabase::class.java.name, MODEL, "java.lang.String")
                    returnType("java.lang.String")
                    usingStrings("addContentToJsonArray newList size = ")
                }
            }, "clipboard history merge").descriptor))
        }
        // Provider entry for saveClipboardCipherText (singleJson / jsonArray).
        collector.put(PhraseSymbols.STORAGE_ENTRY) {
            listOf(listOf(single(bridge.findMethod {
                matcher {
                    paramTypes(Context::class.java, SQLiteDatabase::class.java, Bundle::class.java)
                    returnType(Bundle::class.java)
                    usingEqStrings("singleJson", "clipboard_cipher_list")
                }
            }, "clipboard cipher save").descriptor))
        }
        // javac numbers lambdas per class, so the suffix shifts between builds.
        collector.put(PhraseSymbols.POPUP_UPDATE_LAMBDA) {
            listOf(listOf(single(bridge.findMethod {
                matcher {
                    declaredClass(POPUP)
                    name("lambda\$updateClipboardData\$", StringMatchType.StartsWith)
                    paramTypes(MODEL)
                }
            }, "updateClipboardData lambda").descriptor))
        }
        collector.put(PhraseSymbols.POPUP_REMOTE_LAMBDA) {
            listOf(listOf(single(bridge.findMethod {
                matcher {
                    declaredClass(POPUP)
                    name("lambda\$setRemoteDataToView\$", StringMatchType.StartsWith)
                    paramCount(0)
                    usingStrings("Duplicate data")
                }
            }, "setRemoteDataToView lambda").descriptor))
        }
        // The anonymous Runnable that loads clipboard history into the popup.
        collector.put(PhraseSymbols.POPUP_INIT_TASK) {
            val task = single(bridge.findMethod {
                matcher {
                    declaredClass("$POPUP\$", StringMatchType.StartsWith)
                    name = "run"
                    paramCount(0)
                }
            }.filter { run ->
                val called = run.invokes.filter { it.className == MANAGER }.map { it.name }
                called.containsAll(
                    listOf("getClipboardData", "buildRecyclerViewDisplayList", "setClipboardModelList")
                )
            }, "popup history task")
            listOf(listOf(checkNotNull(task.declaredClass).descriptor))
        }
        // Callers that ART may inline manager methods into; deoptimized only.
        collector.put(PhraseSymbols.MANAGER_RUNNABLES) {
            listOf(bridge.findMethod {
                matcher {
                    name = "run"
                    paramCount(0)
                    addInvoke { declaredClass(MANAGER) }
                }
            }.map { it.descriptor })
        }
        return collector.result()
    }
}
