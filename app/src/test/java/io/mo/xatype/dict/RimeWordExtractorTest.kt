package io.mo.xatype.dict

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RimeWordExtractorTest {
    private fun zip(vararg files: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            files.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    @Test fun `ice, wanxiang and tencent line layouts keep only word and weight`() {
        assertEquals(27365, RimeWordExtractor.parseLine("啊啊\ta a\t27365")!!.weight)
        assertEquals("阿爸", RimeWordExtractor.parseLine("阿爸\tā bà\t716")!!.word)
        assertEquals(100, RimeWordExtractor.parseLine("〇〇八\t100")!!.weight)
        assertEquals(0, RimeWordExtractor.parseLine("阿爸\ta ba")!!.weight)
    }

    @Test fun `comments headers single characters latin and astral characters are rejected`() {
        listOf("# 注释", "name: ext", "version: \"1\"", "---", "...", "", "啊\ta\t759839", "A股\ta gu\t5",
            "3D打印\t5", "𠀀𠀁\ta b\t5", "一二三四五六七八\tx\t5", "词 词\tx\t5").forEach {
            assertNull(it, RimeWordExtractor.parseLine(it))
        }
    }

    @Test fun `extract reads only named entries, keeps heaviest distinct words in order`() {
        val data = zip(
            "cn_dicts/base.dict.yaml" to "---\nname: base\n...\n阿爸\ta ba\t10\n阿坝\ta ba\t30\n阿爸\ta ba\t50\n",
            "cn_dicts/tencent.dict.yaml" to "腾讯\t999\n",
            "cn_dicts/ext.dict.yaml" to "阿巴桑\ta ba sang\t20\n"
        )
        val words = RimeWordExtractor.extract(data, listOf("base.dict.yaml", "ext.dict.yaml"), 3)
        assertEquals(listOf("阿爸" to 50, "阿坝" to 30, "阿巴桑" to 20), words.map { it.word to it.weight })

    }

    @Test fun `cap holds with heavy weight ties and many words`() {
        val text = buildString {
            for (i in 0 until 3000) append("词${'一' + i}\t100\n")
            append("重要词\t900\n")
        }
        val words = RimeWordExtractor.extract(zip("base.dict.yaml" to text), listOf("base.dict.yaml"), 10)
        assertEquals(10, words.size)
        assertEquals("重要词", words.first().word)
    }

    @Test fun `missing entries fail clearly`() {
        assertThrows(IllegalStateException::class.java) {
            RimeWordExtractor.extract(zip("x.txt" to "a"), listOf("base.dict.yaml"), 5)
        }
    }
}
