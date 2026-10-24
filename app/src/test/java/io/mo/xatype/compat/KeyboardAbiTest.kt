package io.mo.xatype.compat

import org.junit.Assert.assertEquals
import org.junit.Test
import z8.p

class KeyboardAbiTest {
    @Test fun `owner lookup distinguishes reference IDs sharing a renamed host class`() {
        val symbols = mapOf(
            "c:surface" to "Lz8/p;",
            "c:settings" to "Lz8/p;",
            "m:surface#render(C)Lz8/r;" to "Lz8/p;->h(C)Lz8/r;",
            "m:settings#render()Lz8/r;" to "Lz8/p;->c()Lz8/r;"
        )
        val constructor = KeyboardAbi::class.java.getDeclaredConstructor(ClassLoader::class.java, Map::class.java)
            .apply { isAccessible = true }
        val abi = constructor.newInstance(javaClass.classLoader, symbols)
        assertEquals(abi.type("surface"), abi.type("settings"))
        assertEquals(listOf(p::class.java.getMethod("h", Char::class.javaPrimitiveType)),
            abi.namedMethods("surface", "render"))
        assertEquals(listOf(p::class.java.getMethod("c")),
            abi.namedMethods("settings", "render"))
        assertEquals(p::class.java.getMethod("h", Char::class.javaPrimitiveType),
            abi.method("surface", "render", Char::class.javaPrimitiveType!!))
    }
}
