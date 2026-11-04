package io.mo.xatype.dict

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

/** Talks to GitHub Releases for the selected [OnlineDictSource]. Runs on a worker thread. */
object OnlineDictClient {
    class Release(
        /** Compared to detect updates. */
        val version: String,
        /** Shown to the user. */
        val label: String,
        val url: String,
        val size: Long,
        val sha256: String?
    )

    private const val USER_AGENT = "XiaoAiTypeUnblock-OnlineDict"
    private const val MAX_ARCHIVE_BYTES = 120L shl 20

    fun fetchLatest(source: OnlineDictSource): Release {
        val connection = open(source.releaseApi, json = true)
        try {
            val code = connection.responseCode
            if (code == 403 || code == 429) throw IOException("GitHub 请求过于频繁，请稍后再试")
            if (code != 200) throw IOException("检查更新失败（HTTP $code）")
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val assets = release.getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == source.assetName }
                ?: throw IOException("发布页中没有 ${source.assetName}")
            val tag = release.optString("tag_name")
            val updated = asset.optString("updated_at")
            val digest = asset.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
            return Release(
                version = if (source.rolling) "$tag@$updated" else tag,
                label = if (source.rolling) "${updated.take(10)} 每日版" else tag,
                url = asset.getString("browser_download_url"),
                size = asset.optLong("size", -1),
                sha256 = digest
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads to [target] through a temporary file; a failed or cancelled download leaves [target] untouched. */
    fun download(release: Release, target: File, onProgress: (done: Long, total: Long) -> Unit,
                 isCancelled: () -> Boolean) {
        require(release.url.startsWith("https://")) { "只允许 HTTPS 下载" }
        val temp = File(target.path + ".part")
        val connection = open(release.url, json = false)
        try {
            if (connection.responseCode != 200) throw IOException("下载失败（HTTP ${connection.responseCode}）")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
            check(total <= MAX_ARCHIVE_BYTES) { "词库压缩包过大" }
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            connection.inputStream.use { input ->
                temp.outputStream().buffered(1 shl 16).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        check(!isCancelled()) { "已取消" }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        check(done <= MAX_ARCHIVE_BYTES) { "词库压缩包过大" }
                        if (done - lastReport >= 256 * 1024) { lastReport = done; onProgress(done, total) }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("下载不完整，请重试")
            release.sha256?.let { expected ->
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) throw IOException("文件校验失败，已丢弃")
            }
            onProgress(done, total)
            if (target.exists() && !target.delete()) throw IOException("无法替换旧的下载文件")
            if (!temp.renameTo(target)) throw IOException("无法保存下载文件")
        } finally {
            connection.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    /** A short Chinese message for the user; raw socket errors are English and include IP addresses. */
    fun describe(error: Throwable): String = when (error) {
        is UnknownHostException -> "无法解析 GitHub 地址，请检查网络"
        is SocketTimeoutException -> "连接 GitHub 超时，请检查网络后重试"
        is ConnectException, is NoRouteToHostException -> "无法连接 GitHub，请检查网络后重试"
        is SSLException -> "与 GitHub 的安全连接失败，请检查网络后重试"
        is JSONException -> "GitHub 返回的发布信息无法识别"
        else -> error.message?.takeIf { message -> message.any { it in '一'..'鿿' } } ?: "网络异常，请稍后重试"
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
