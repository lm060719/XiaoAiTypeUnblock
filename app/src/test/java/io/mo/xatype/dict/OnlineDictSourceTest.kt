package io.mo.xatype.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OnlineDictSourceTest {
    private companion object { val WORDS = listOf("测试甲", "测试乙", "测试丙") }

    /** Same layout as the real archives: a top folder, unrelated tables, then the wanted ones. */
    private fun archive(folder: String, wanted: List<String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            z.putNextEntry(ZipEntry("$folder/")); z.closeEntry()
            z.putNextEntry(ZipEntry("$folder/others.dict.yaml")); z.write("无关词\tx\t999999\n".toByteArray()); z.closeEntry()
            wanted.forEachIndexed { i, name ->
                z.putNextEntry(ZipEntry("$folder/$name"))
                z.write("---\nname: t\n...\n${WORDS[i]}\tce shi\t${100 - i}\n".toByteArray())
                z.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    // Regression: a property named `entries` resolved to Kotlin's enum constant list, so no
    // file in any archive matched and every download failed with "没有找到所需词库文件".
    @Test fun `each source lists dictionary file names, not enum constants`() {
        OnlineDictSource.entries.forEach { source ->
            assertTrue(source.dictFiles.toString(), source.dictFiles.isNotEmpty())
            assertTrue(source.dictFiles.toString(), source.dictFiles.all { it.endsWith(".dict.yaml") })
        }
    }

    @Test fun `each source extracts from an archive shaped like its release`() {
        mapOf(OnlineDictSource.RIME_ICE to "cn_dicts", OnlineDictSource.WANXIANG to "dicts").forEach { (source, folder) ->
            val words = RimeWordExtractor.extract(archive(folder, source.dictFiles), source.dictFiles, 10)
            assertEquals(WORDS.take(source.dictFiles.size), words.map { it.word })
        }
    }
}
