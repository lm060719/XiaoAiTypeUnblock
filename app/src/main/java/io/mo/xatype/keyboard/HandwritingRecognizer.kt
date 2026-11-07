package io.mo.xatype.keyboard

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.util.XposedUtils

/**
 * The host's iFlytek handwriting (HCR) engine, driven with the same calls as the host's own
 * handwriting keyboard: points, finishInput, then the decode result.
 *
 * The HCR and pinyin engines share one native decoder, and everything runs on the host's
 * engine thread. A recognition switches the decoder to HCR, then restores the layout the host
 * last selected, so the active Chinese/English engine keeps its mode. All names used here are
 * the SDK's unobfuscated API.
 */
class HandwritingRecognizer(private val loader: ClassLoader) {
    private fun type(name: String) = Class.forName(name, false, loader)
    private val managerType = type("com.iflytek.depend.common.base.SmartEngineManager")
    private val hcrType = type("com.iflytek.depend.common.hcr.IHcrEngine")
    private val pinyinType = type("com.iflytek.depend.common.pinyin.IPinYinEngine")
    private val layoutType = type("com.iflytek.depend.common.pinyin.LayoutType")
    private val recognizeType = type("com.iflytek.depend.common.hcr.RecognizeType")
    private val wordType = type("com.iflytek.inputmethod.smart.api.interfaces.ICandidateWord")
    private val resultType = type("com.iflytek.inputmethod.smart.api.entity.SmartResultElement")

    private val get = managerType.getMethod("get")
    private val hcrField = managerType.declaredFields.single { hcrType.isAssignableFrom(it.type) }
        .apply { isAccessible = true }
    private val pinyinField = managerType.declaredFields.single { pinyinType.isAssignableFrom(it.type) }
        .apply { isAccessible = true }
    private val initHcr = managerType.getMethod("initHcrEngine")
    private val clearInput = managerType.getMethod("clearInput")
    private val decodeResult = managerType.methods.single { it.name == "getDecodeResult" && it.parameterCount == 1 }
    private val candWords = resultType.getField("candWords")
    private val word = wordType.getMethod("getWord")
    private val isHcrInit = hcrType.getMethod("isHcrInit")
    private val switchHcr = hcrType.getMethod("switchHcr")
    private val setRecognize = hcrType.getMethod("setRecognizeType", recognizeType)
    private val setArea = hcrType.getMethod("setWritingArea", Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val inputPoint = hcrType.getMethod("inputPoint", Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val finishInput = hcrType.getMethod("finishInput")
    private val resetHcr = hcrType.getMethod("resetHcr")
    private val switchLayout = pinyinType.getMethod("switchLayout", layoutType)
    /** Overlapped sentences, the host's default handwriting mode: one or more characters. */
    private val overlap = recognizeType.enumConstants.single { (it as Enum<*>).name == "HCR_RECOGNITION_SENT_OVLP" }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastLayout: Any? = null
    @Volatile private var engine: Handler? = null

    /** Remember the layout the host selects, to restore it after each recognition. */
    fun install(module: XposedInterface) {
        val implementation = pinyinField.type.getMethod("switchLayout", layoutType)
        module.hook(implementation).intercept { chain ->
            chain.getArg(0)?.let { lastLayout = it }
            chain.proceed()
        }
        module.deoptimize(implementation)
    }

    /**
     * Recognize [strokes] (x, y pairs in pixels, inside a [width] x [height] area). [done]
     * runs on the main thread with the candidates, most likely first; empty on failure.
     */
    fun recognize(module: XposedInterface, strokes: List<IntArray>, width: Int, height: Int, done: (List<String>) -> Unit) {
        val handler = engineHandler()
        if (handler == null) {
            XposedUtils.log(module, "Handwriting: host engine thread not running")
            return done(emptyList())
        }
        handler.post {
            val result = runCatching { recognizeOnEngine(strokes, width, height) }
            result.exceptionOrNull()?.let { XposedUtils.logError(module, "Handwriting recognition failed", it) }
            main.post { done(result.getOrDefault(emptyList())) }
        }
    }

    private fun recognizeOnEngine(strokes: List<IntArray>, width: Int, height: Int): List<String> {
        val manager = get.invoke(null)!!
        val hcr = hcrField.get(manager)!!
        if (isHcrInit.invoke(hcr) != true && initHcr.invoke(manager) != true) error("HCR engine unavailable")
        try {
            switchHcr.invoke(hcr)
            setRecognize.invoke(hcr, overlap)
            setArea.invoke(hcr, 0, 0, width, height)
            for (stroke in strokes) {
                val count = stroke.size / 2
                if (count == 0) continue
                for (index in 0 until count) {
                    inputPoint.invoke(hcr, stroke[index * 2], stroke[index * 2 + 1], if (index == 0) DOWN else MOVE)
                }
                inputPoint.invoke(hcr, stroke[(count - 1) * 2], stroke[(count - 1) * 2 + 1], UP)
            }
            finishInput.invoke(hcr)
            val words = decodeResult.invoke(manager, null)?.let { candWords.get(it) as? List<*> }.orEmpty()
            return words.mapNotNull { candidate -> candidate?.let { word.invoke(it) as? String } }
                .filter { it.isNotBlank() }.distinct()
        } finally {
            runCatching { resetHcr.invoke(hcr) }
            runCatching { clearInput.invoke(manager) }
            lastLayout?.let { layout -> runCatching { switchLayout.invoke(pinyinField.get(manager), layout) } }
        }
    }

    /** The host's single engine thread ("ime-iflytek-engine", a HandlerThread). */
    private fun engineHandler(): Handler? {
        engine?.takeIf { it.looper.thread.isAlive }?.let { return it }
        val thread = Thread.getAllStackTraces().keys.firstOrNull { it.name == ENGINE_THREAD && it.isAlive } as? HandlerThread
        return thread?.looper?.let(::Handler)?.also { engine = it }
    }

    private companion object {
        const val ENGINE_THREAD = "ime-iflytek-engine"
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
    }
}
