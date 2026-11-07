package ka
enum class f { CHAR, BACKSPACE, ENTER, SHIFT, SPACE, SWITCH, FUNCTION, GAP }
class c(
    @JvmField val a: Int, @JvmField val b: String, @JvmField val c: Float,
    @JvmField val d: Float?, @JvmField val e: Float?, @JvmField val f: ka.f,
    @JvmField val g: String?, @JvmField val h: String?, @JvmField val i: List<String>?,
    @JvmField val j: Int?, @JvmField val k: Int?, @JvmField val l: Boolean,
    @JvmField val m: Boolean?, @JvmField val n: Boolean, @JvmField val o: Boolean,
    @JvmField val p: Int?, @JvmField val q: Boolean, @JvmField val r: Int?
) {
    constructor(code: Int, kind: ka.f) : this(code, code.toChar().toString(), 1f, null, null,
        kind, null, null, null, null, null, false, null, false, false, null, false, null)
}
class e(@JvmField val b: Float, @JvmField val a: List<c>, @JvmField val c: List<ka.c>?)
class g(@JvmField val a: String, @JvmField val b: String, @JvmField val c: List<e>,
        @JvmField val d: List<String>, @JvmField val e: Float?, @JvmField val f: Float?,
        @JvmField val g: Boolean, @JvmField val h: Float?, @JvmField val i: Boolean)
