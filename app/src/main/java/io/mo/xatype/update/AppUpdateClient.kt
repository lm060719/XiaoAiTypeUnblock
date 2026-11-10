package io.mo.xatype.update

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** The module's own GitHub Releases: latest version, its notes and the APK asset. Runs on a worker thread. */
object AppUpdateClient {
    const val REPOSITORY = "lm060719/XiaoAiTypeUnblock"
    const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases/latest"
    private const val LATEST_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    private const val USER_AGENT = "XiaoAiTypeUnblock-Updater"
    private const val MAX_APK_BYTES = 64L shl 20

    class Release(
        val tag: String,
        val name: String,
        /** Release notes in GitHub Markdown. */
        val notes: String,
        val publishedAt: String,
        val pageUrl: String,
        val apkName: String,
        val apkUrl: String,
        val apkSize: Long,
        val sha256: String?,
    )

    fun fetchLatest(): Release {
        val connection = open(LATEST_API, json = true)
        try {
            val code = connection.responseCode
            if (code == 403 || code == 429) throw IOException("GitHub 请求过于频繁，请稍后再试")
            if (code == 404) throw IOException("GitHub 上还没有发布版本")
            if (code != 200) throw IOException("检查更新失败（HTTP $code）")
            return parse(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            connection.disconnect()
        }
    }

    /** Reads the GitHub release JSON; the first `.apk` asset is the installer. */
    fun parse(release: JSONObject): Release {
        val assets = release.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: throw IOException("最新发布中没有安装包")
        val tag = release.getString("tag_name")
        return Release(
            tag = tag,
            name = release.optString("name").ifBlank { tag },
            notes = release.optString("body").replace("\r\n", "\n"),
            publishedAt = release.optString("published_at"),
            pageUrl = release.optString("html_url").ifBlank { RELEASES_PAGE },
            apkName = apk.getString("name"),
            apkUrl = apk.getString("browser_download_url"),
            apkSize = apk.optLong("size", -1),
            sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
        )
    }

    /** Numeric version parts of a tag or version name: "v2.2.10-beta" -> [2, 2, 10]. */
    fun versionParts(version: String): List<Int> =
        Regex("""\d+(\.\d+)*""").find(version)?.value?.split('.')?.map { it.toIntOrNull() ?: 0 }.orEmpty()

    /** Whether [remote] is a later version than [current]; unparseable versions never are. */
    fun isNewer(remote: String, current: String): Boolean {
        val a = versionParts(remote)
        val b = versionParts(current)
        if (a.isEmpty() || b.isEmpty()) return false
        for (index in 0 until maxOf(a.size, b.size)) {
            val difference = a.getOrElse(index) { 0 } - b.getOrElse(index) { 0 }
            if (difference != 0) return difference > 0
        }
        return false
    }

    /** SHA-256 of [file] in lowercase hex. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 16).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Downloads to [target] through a temporary file; a failed or cancelled download leaves [target] untouched. */
    fun download(release: Release, target: File, onProgress: (done: Long, total: Long) -> Unit,
                 isCancelled: () -> Boolean) {
        require(release.apkUrl.startsWith("https://")) { "只允许 HTTPS 下载" }
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".part")
        val connection = open(release.apkUrl, json = false)
        try {
            if (connection.responseCode != 200) throw IOException("下载失败（HTTP ${connection.responseCode}）")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            if (total > MAX_APK_BYTES) throw IOException("安装包过大")
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            connection.inputStream.use { input ->
                temp.outputStream().buffered(1 shl 16).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        if (isCancelled()) throw IOException("已取消")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (done > MAX_APK_BYTES) throw IOException("安装包过大")
                        if (done - lastReport >= 64 * 1024) { lastReport = done; onProgress(done, total) }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("下载不完整，请重试")
            release.sha256?.let { expected ->
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) throw IOException("安装包校验失败，已丢弃")
            }
            onProgress(done, total)
            if (target.exists() && !target.delete()) throw IOException("无法替换旧的安装包")
            if (!temp.renameTo(target)) throw IOException("无法保存安装包")
        } finally {
            connection.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    private fun open(url: String, json: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            if (json) setRequestProperty("Accept", "application/vnd.github+json")
        }
}
