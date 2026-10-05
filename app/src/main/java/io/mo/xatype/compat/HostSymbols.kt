package io.mo.xatype.compat

/** Members of the input method (com.xiaomi.type), located by [HostFingerprints]. */
object HostSymbols : SymbolTable(1, HostFingerprints::resolve) {
    const val AI_SAFETY_PARSERS = "aiSafety.parsers"
    const val VOICE_MODERATION = "voice.moderation"
    const val ASR_ERROR_MAPPER = "asr.errorMapper"
    const val ASR_ERROR_CALLBACK = "asr.errorCallback"
    const val OS_VERSION_GATE = "os.versionGate"
    const val HEIGHT_RECT = "height.rect"
    const val HEIGHT_CLAMP = "height.clamp"
    const val HEIGHT_RECT_GETTER = "height.rectGetter"
    const val HEIGHT_LAYOUT = "height.layout"
    const val HEIGHT_DRAG = "height.drag"
    const val HEIGHT_DRAG_KIND = "height.dragKind"
}
