package io.mo.xatype.compat

import java.io.File

/** Fingerprint results reported by hooked processes and shown in the module UI. */
object AdaptationStatus {
    const val METHOD_REPORT = "report_adaptation"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_VERSION_CODE = "version_code"
    const val EXTRA_MODULE_STAMP = "module_stamp"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_MISSING = "missing"
    const val PREFS_NAME = "adaptation_status"

    const val IME_PACKAGE = "com.xiaomi.type"
    const val PHRASE_PACKAGE = "com.miui.phrase"

    /** User-facing feature behind each symbol key. */
    private val FEATURES = mapOf(
        HostSymbols.T9_SHUANGPIN_INIT to "九键双拼",
        HostSymbols.T9_SHUANGPIN_BOOLEAN to "九键双拼",
        HostSymbols.T9_SHUANGPIN_STRING to "九键双拼",
        HostSymbols.KEYBOARD_ENGINE to "虎码/五笔与18键引擎",
        HostSymbols.KEYBOARD_EMOJI to "Emoji 候选增强",
        HostSymbols.KEYBOARD_PREDICTION to "上屏后联想",
        HostSymbols.KEYBOARD_LAYOUT to "18键布局",
        HostSymbols.KEYBOARD_ACTIONS to "快捷操作输入收尾",
        HostSymbols.KEYBOARD_PICKER to "独立键盘选择",
        HostSymbols.KEYBOARD_SETTINGS to "原生键盘设置入口",
        HostSymbols.KEYBOARD_SWIPE to "字母下滑快捷操作",
        HostSymbols.KEYBOARD_LETTER_APPEARANCE to "18键字母排版",
        HostSymbols.KEYBOARD_SURFACE_TEXT to "候选栏与剪贴板条字色",
        HostSymbols.AI_SAFETY_PARSERS to "AI 表达安全拦截解除",
        HostSymbols.VOICE_MODERATION to "语音转写风控解除",
        HostSymbols.ASR_ERROR_MAPPER to "语音转写风控解除",
        HostSymbols.ASR_ERROR_CALLBACK to "语音转写风控解除",
        HostSymbols.OS_VERSION_GATE to "系统版本限制解除",
        HostSymbols.HEIGHT_RECT to "键盘高度上限解除",
        HostSymbols.HEIGHT_CLAMP to "键盘高度上限解除",
        HostSymbols.HEIGHT_RECT_GETTER to "键盘高度上限解除",
        HostSymbols.HEIGHT_LAYOUT to "键盘高度上限解除",
        HostSymbols.HEIGHT_DRAG to "键盘高度上限解除",
        HostSymbols.HEIGHT_DRAG_KIND to "键盘高度上限解除",
        HostSymbols.MODERN_KEYBOARD to "键盘外观美化",
        HostSymbols.BOTTOM_SPACING to "键盘底部间距",
        HostSymbols.BOTTOM_SPACING_CONSUME to "键盘底部间距",
        HostSymbols.BOTTOM_SPACING_CALLERS to "键盘底部间距",
        PhraseSymbols.STORAGE_WRITE to "剪贴板永久保存",
        PhraseSymbols.STORAGE_ENTRY to "剪贴板永久保存",
        PhraseSymbols.POPUP_UPDATE_LAMBDA to "剪贴板永久保存",
        PhraseSymbols.POPUP_REMOTE_LAMBDA to "剪贴板永久保存",
        PhraseSymbols.POPUP_INIT_TASK to "剪贴板永久保存",
        PhraseSymbols.MANAGER_RUNNABLES to "剪贴板永久保存"
    )

    fun featureNames(keys: Collection<String>): List<String> =
        keys.map { FEATURES[it] ?: it }.distinct()

    /** Version code plus the module APK's size and mtime; changes on every reinstall. */
    fun moduleStamp(versionCode: Int, moduleApk: String?): String {
        val apk = moduleApk?.let(::File)
        return "$versionCode:${apk?.length() ?: 0}:${apk?.lastModified() ?: 0}"
    }
}
