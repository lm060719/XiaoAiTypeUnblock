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

    private fun table(name: String, text: String) = RimeWordExtractor.LocalFile(name) {
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
    }

    @Test fun `local ice tables merge duplicates and apply a shared weighted cap`() {
        val files = listOf(table("base.dict.yaml", "\uFEFF阿爸\ta ba\t10\n阿坝\ta ba\t30\r\n"),
            table("ext.dict.yaml", "阿爸\ta ba\t50\n阿巴桑\ta ba sang\t20\n"))
        val words = RimeWordExtractor.extractLocal(files, OnlineDictSource.RIME_ICE, 2)
        assertEquals(listOf("阿爸" to 50, "阿坝" to 30), words.map { it.word to it.weight })
    }

    @Test fun `local upstream release and repository zip layouts are supported`() {
        OnlineDictSource.entries.forEach { source ->
            val words = RimeWordExtractor.extractLocal(listOf(RimeWordExtractor.LocalFile("词库.ZIP") {
                zip("project/cn_dicts/${source.dictFiles.first()}" to "万象拼音\twan xiang pin yin\t90\n",
                    "project/en_dicts/en.dict.yaml" to "不应导入\t999\n")
            }), source, 5)
            assertEquals(listOf("万象拼音"), words.map { it.word })
        }
    }

    @Test fun `local wanxiang table and mixed archive selections share ranking`() {
        val files = listOf(table("jichu.dict.yaml", "万象拼音\t100\n"),
            RimeWordExtractor.LocalFile("dicts.zip") { zip("dicts/jichu.dict.yaml" to "测试词库\t200\n") })
        assertEquals(listOf("测试词库"), RimeWordExtractor.extractLocal(files, OnlineDictSource.WANXIANG, 1).map { it.word })
    }

    @Test fun `wrong source invalid empty and configuration files fail without a result`() {
        listOf(
            listOf(table("x.txt", "词库\t100")),
            listOf(table("rime_ice.dict.yaml", "import_tables:\n  - cn_dicts/base\n")),
            listOf(RimeWordExtractor.LocalFile("dicts.zip") { zip("jichu.dict.yaml" to "词库\t100") }),
            listOf(table("empty.dict.yaml", "")),
            listOf(table("long.dict.yaml", "一".repeat(16_385)))
        ).forEach { files ->
            assertThrows(IllegalArgumentException::class.java.takeIf { files[0].name == "x.txt" }
                ?: IllegalStateException::class.java) {
                RimeWordExtractor.extractLocal(files, OnlineDictSource.RIME_ICE, 5)
            }
        }
    }

    @Test fun `cancelled import stops before opening local files`() {
        var opened = false
        assertThrows(IllegalStateException::class.java) {
            RimeWordExtractor.extractLocal(listOf(RimeWordExtractor.LocalFile("base.dict.yaml") {
                opened = true
                ByteArrayInputStream(byteArrayOf())
            }), OnlineDictSource.RIME_ICE, 5, isCancelled = { true })
        }
        assertFalse(opened)
    }
}
