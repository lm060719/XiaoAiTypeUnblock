package z8

// Minimal ABI doubles copied from 21053 interface descriptors; no host implementation is bundled.
enum class g { PINYIN, SHUANGPIN, T9, HANDWRITING, STROKE, ENGLISH, VOICE }
enum class b { SYSTEM, USER }
class e
open class u
object t : u()
class s(val mask: Int, val raw: String, val display: String, val errors: List<Any>) : u()
class a(val text: String, val annotation: String?, val frequency: Int, val source: b, val display: String?)
class r(val consumed: Boolean, val code: String, val candidates: List<a>, val commit: String?,
        val delete: Int, val errors: List<Any>, val acceptedRaw: String?)
interface q {
    fun a(): g
    fun b(index: Int): String
    fun clear()
    fun d(text: String): r
    fun destroy()
    fun e(): Boolean
    fun f(): u
    fun g(list: List<Any>): r
    fun i(code: Int): r
    fun j(): String
    fun k(): r
    fun o(text: String): r
    fun q(service: com.mi.ime.MiInputMethodService, callbacks: e)
    fun s(): List<a>
    fun t()
    fun u(dictionary: y8.b)
}
interface p : q {
    fun c(): r
    fun h(key: Char): r
    fun l(rules: Int)
    fun m(start: Int, count: Int): List<a>
    fun n(index: Int, fallback: String): nc.f<String, Boolean>
    fun p(case: Int)
    fun r(segment: Int): r
    fun v(): List<Any>
}

// The raw cursor added in 0.2.1448 shares its host name with another callback.
interface EngineWithRawCursor : p {
    fun r(): Int
}
