package io.mo.xatype.hooks

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.util.XposedUtils
import java.util.Collections
import java.util.WeakHashMap

internal object ClipboardSplitHook {
    private const val POPUP_CLASS = "com.miui.inputmethod.InputMethodClipboardPhrasePopupView"
    private const val ADAPTER_CLASS = "com.miui.inputmethod.InputMethodClipboardAdapter"
    private const val MODEL_CLASS = "com.miui.inputmethod.ClipboardContentModel"
    private const val MANAGER_CLASS = "com.miui.inputmethod.MiuiClipboardManager"
    private val textEntryTypes = setOf(2, 3, 10) // Taobao codes, links, and plain text.
    private val adapterClasses = mutableSetOf<Class<*>>()
    private val splitCards = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun install(module: XposedInterface) {
        val show = PopupWindow::class.java.getMethod(
            "showAtLocation", View::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        module.hook(show).intercept { chain ->
            val popup = chain.thisObject as? PopupWindow
            if (popup?.javaClass?.name == POPUP_CLASS) {
                runCatching {
                    popup.javaClass.classLoader?.let { installAdapter(module, it) }
                }.onFailure { XposedUtils.logError(module, "[Clipboard Split] Adapter unavailable", it) }
            }
            chain.proceed()
        }
    }

    // Resolve against the actual plugin loader, including when the framework loads the APK.
    private fun installAdapter(module: XposedInterface, loader: ClassLoader) {
        val adapter = XposedUtils.findClass(ADAPTER_CLASS, loader) ?: return
        if (!adapterClasses.add(adapter)) return
        val holder = loader.loadClass("$ADAPTER_CLASS\$ViewHolder")
        val model = loader.loadClass(MODEL_CLASS)
        val manager = loader.loadClass(MANAGER_CLASS)
        val bind = adapter.getDeclaredMethod("onBindViewHolder", holder, Int::class.javaPrimitiveType)
        val list = adapter.getMethod("getAdapterList")
        val type = model.getMethod("getType")
        val text = manager.getMethod("getFirstTextInClipData", model)
        module.hook(bind).intercept { chain ->
            val result = chain.proceed()
            runCatching {
                val instance = chain.thisObject ?: return@runCatching
                val holderInstance = chain.getArg(0) ?: return@runCatching
                val card = XposedUtils.getObjectField(holderInstance, "itemLayout") as? View
                    ?: return@runCatching
                val entry = (list.invoke(instance) as? List<*>)?.getOrNull(chain.getArg(1) as Int)
                val popup = XposedUtils.getObjectField(instance, "mItemClickListener") as? PopupWindow
                val service = XposedUtils.getObjectField(instance, "mInputMethodService") as? InputMethodService
                val entryType = entry?.let { type.invoke(it) as Int }
                if (entry == null || popup?.javaClass?.name != POPUP_CLASS || service == null ||
                    entryType !in textEntryTypes
                ) {
                    if (splitCards.remove(card)) {
                        card.setOnLongClickListener(null)
                        card.isLongClickable = false
                    }
                    return@runCatching
                }
                if (card.isLongClickable && card !in splitCards) return@runCatching
                card.setOnLongClickListener {
                    runCatching open@{
                        val original = text.invoke(null, entry) as? String ?: return@open false
                        showSplitPanel(module, popup, service, original)
                    }.getOrElse {
                        XposedUtils.logWarn(module, "[Clipboard Split] Could not open the split panel")
                        Toast.makeText(service, "暂时无法拆分文字", Toast.LENGTH_SHORT).show()
                        true
                    }
                }
                splitCards.add(card)
            }.onFailure { XposedUtils.logError(module, "[Clipboard Split] Card binding failed", it) }
            result
        }
    }

    @SuppressLint("DiscouragedApi") // The ID belongs to the separately installed clipboard APK.
    private fun showSplitPanel(
        module: XposedInterface, popup: PopupWindow, service: InputMethodService, text: String
    ): Boolean {
        val root = popup.contentView ?: return false
        val id = root.resources.getIdentifier("inside_view", "id", "com.miui.phrase")
        val inside = root.findViewById<View>(id) ?: return false
        if (!popup.isShowing || inside.visibility != View.VISIBLE || !inside.isAttachedToWindow) return false
        val parent = inside.parent as? FrameLayout ?: return false
        val words = clipboardWords(text)
        if (words.size < 2) return false
        val connection = service.currentInputConnection
        val editor = service.currentInputEditorInfo
        if (connection == null || editor == null) {
            Toast.makeText(service, "请先选中输入框", Toast.LENGTH_SHORT).show()
            return true
        }
        val feedback = KeyboardHapticsHook.keyPressFeedback(module, service)
        ClipboardSplitPanel(inside, parent, words, roundBottom = popup.width > 0, keyPressFeedback = feedback) { selectedText ->
            popup.isShowing && service.currentInputEditorInfo === editor &&
                service.currentInputConnection === connection && connection.commitText(selectedText, 1)
        }.show()
        return true
    }
}
