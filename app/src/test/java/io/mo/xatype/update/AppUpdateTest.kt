package io.mo.xatype.update

import io.mo.xatype.update.Markdown.Block
import io.mo.xatype.update.Markdown.Inline
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    @Test fun `versions compare numerically and ignore prefixes and suffixes`() {
        assertTrue(AppUpdateClient.isNewer("2.2.3", "2.2.2"))
        assertTrue(AppUpdateClient.isNewer("v2.10.0", "2.9.9"))
        assertTrue(AppUpdateClient.isNewer("2.3", "2.2.9-shape-prototype1"))
        assertFalse(AppUpdateClient.isNewer("2.2.2", "2.2.2"))
        assertFalse(AppUpdateClient.isNewer("2.2", "2.2.0"))
        assertFalse(AppUpdateClient.isNewer("2.2.1", "2.2.2"))
        assertFalse(AppUpdateClient.isNewer("nightly", "2.2.2"))
        assertEquals(listOf(2, 2, 10), AppUpdateClient.versionParts("v2.2.10-beta"))
    }

    @Test fun `release json yields the first apk asset and its digest`() {
        val release = AppUpdateClient.parse(JSONObject("""{
            "tag_name": "2.2.3", "name": "2.2.3", "body": "**新增**\r\n- 一项", "published_at": "2026-11-10T08:00:00Z",
            "html_url": "https://github.com/lm060719/XiaoAiTypeUnblock/releases/tag/2.2.3",
            "assets": [
              {"name": "SHA256SUMS.txt", "browser_download_url": "https://example.invalid/sums", "size": 10},
              {"name": "XiaoAiTypeUnblock-2.2.3.apk", "browser_download_url": "https://github.com/x.apk", "size": 6791581,
               "digest": "sha256:abc123"}
            ]}"""))
        assertEquals("2.2.3", release.tag)
        assertEquals("XiaoAiTypeUnblock-2.2.3.apk", release.apkName)
        assertEquals("https://github.com/x.apk", release.apkUrl)
        assertEquals(6791581L, release.apkSize)
        assertEquals("abc123", release.sha256)
        assertEquals("**新增**\n- 一项", release.notes)
    }

    @Test(expected = java.io.IOException::class)
    fun `release without apk is rejected`() {
        AppUpdateClient.parse(JSONObject("""{"tag_name": "2.2.3", "assets": []}"""))
    }

    @Test fun `release notes parse into headings lists and paragraphs`() {
        val blocks = Markdown.parse("**新增**\r\n- 适配 0.2.1358\r\n- \r\n**修复**\r\n1. 第一项\r\n\r\n## 标题\r\n普通段落\r\n第二行\r\n> 引用\r\n---\r\n```\r\ncode **x**\r\n```")
        assertEquals(listOf(
            Block.Paragraph(listOf(Inline("新增", bold = true))),
            Block.Item("•", 0, listOf(Inline("适配 0.2.1358"))),
            Block.Paragraph(listOf(Inline("修复", bold = true))),
            Block.Item("1.", 0, listOf(Inline("第一项"))),
            Block.Heading(2, listOf(Inline("标题"))),
            Block.Paragraph(listOf(Inline("普通段落\n第二行"))),
            Block.Quote(listOf(Inline("引用"))),
            Block.Rule,
            Block.Code("code **x**"),
        ), blocks)
    }

    @Test fun `inline styles links and code spans`() {
        assertEquals(listOf(
            Inline("a "), Inline("b", bold = true), Inline(" "), Inline("i", italic = true), Inline(" "),
            Inline("s", strike = true), Inline(" "), Inline("x*y", code = true), Inline(" "),
            Inline("链接", url = "https://example.com"), Inline(" 2*3 _ok")
        ), Markdown.inline("a **b** *i* ~~s~~ `x*y` [链接](https://example.com) 2*3 \\_ok"))
        // Unmatched markers stay literal.
        assertEquals(listOf(Inline("**alone and [link](")), Markdown.inline("**alone and [link]("))
    }

    @Test fun `plain text keeps list markers`() {
        assertEquals("新增\n• 一项", Markdown.plain("**新增**\n- 一项\n- "))
    }
}
