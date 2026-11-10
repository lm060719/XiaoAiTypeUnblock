package io.mo.xatype.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.FileProvider
import io.mo.xatype.BuildConfig
import io.mo.xatype.R
import io.mo.xatype.dict.OnlineDictClient
import io.mo.xatype.update.AppUpdateClient
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks the module's GitHub Releases, shows the release notes, then downloads, verifies and
 * hands the APK to the system installer. The latest release found is cached for offline display.
 */
internal class AppUpdater(private val activity: Activity, private val onChanged: () -> Unit) {
    private val kit = SettingsKit(activity)
    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private var checking = false
    private var downloading: AtomicBoolean? = null

    var autoCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CHECK, value).apply()

    /** The cached latest release when it is newer than this build. */
    fun available(): AppUpdateClient.Release? {
        val tag = prefs.getString(KEY_TAG, null) ?: return null
        if (!AppUpdateClient.isNewer(tag, BuildConfig.VERSION_NAME)) return null
        return AppUpdateClient.Release(
            tag = tag,
            name = prefs.getString(KEY_NAME, null) ?: tag,
            notes = prefs.getString(KEY_NOTES, null).orEmpty(),
            publishedAt = prefs.getString(KEY_PUBLISHED, null).orEmpty(),
            pageUrl = prefs.getString(KEY_PAGE, null) ?: AppUpdateClient.RELEASES_PAGE,
            apkName = prefs.getString(KEY_APK_NAME, null) ?: return null,
            apkUrl = prefs.getString(KEY_APK_URL, null) ?: return null,
            apkSize = prefs.getLong(KEY_APK_SIZE, -1),
            sha256 = prefs.getString(KEY_SHA256, null),
        )
    }

    /** Checks quietly when enabled and the last check is older than [AUTO_INTERVAL_MS]. */
    fun maybeAutoCheck() {
        if (!autoCheck) return
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last in 0 until AUTO_INTERVAL_MS) return
        check(manual = false)
    }

    /** A manual check reports its outcome and opens the notes when an update exists. */
    fun check(manual: Boolean) {
        if (checking) return
        checking = true
        if (manual) toast("正在检查更新…")
        worker.execute {
            val result = runCatching { AppUpdateClient.fetchLatest() }
            activity.runOnUiThread {
                checking = false
                if (activity.isDestroyed) return@runOnUiThread
                result.onSuccess { release ->
                    store(release)
                    onChanged()
                    if (!manual) return@onSuccess
                    if (AppUpdateClient.isNewer(release.tag, BuildConfig.VERSION_NAME)) show(release)
                    else toast("已是最新版本（${BuildConfig.VERSION_NAME}）")
                }.onFailure { if (manual) toast(OnlineDictClient.describe(it)) }
            }
        }
    }

    fun destroy() {
        downloading?.set(true)
        worker.shutdownNow()
    }

    private fun store(release: AppUpdateClient.Release) {
        prefs.edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .putString(KEY_TAG, release.tag)
            .putString(KEY_NAME, release.name)
            .putString(KEY_NOTES, release.notes)
            .putString(KEY_PUBLISHED, release.publishedAt)
            .putString(KEY_PAGE, release.pageUrl)
            .putString(KEY_APK_NAME, release.apkName)
            .putString(KEY_APK_URL, release.apkUrl)
            .putLong(KEY_APK_SIZE, release.apkSize)
            .putString(KEY_SHA256, release.sha256)
            .apply()
    }

    /** The release notes sheet with the update button. */
    fun show(release: AppUpdateClient.Release) {
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val meta = buildList {
            add("${BuildConfig.VERSION_NAME} → ${release.tag}")
            release.publishedAt.take(10).takeIf { it.length == 10 }?.let { add("发布于 $it") }
            if (release.apkSize > 0) add(size(release.apkSize))
        }.joinToString(" · ")
        body.addView(kit.label(meta, 13f, R.color.text_secondary), spaced(R.dimen.space_sm))
        body.addView(MarkdownText.view(kit, release.notes), spaced(R.dimen.space_lg))
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = android.view.View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(kit.color(R.color.primary))
        }
        body.addView(progress, spaced(R.dimen.space_lg))
        val status = kit.label("", 13f, R.color.text_secondary).apply { visibility = android.view.View.GONE }
        body.addView(status, spaced(R.dimen.space_sm))
        val link = SpannableString("在 GitHub 查看发布页").apply {
            setSpan(URLSpan(release.pageUrl), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        body.addView(kit.label(link, 13f, R.color.primary).apply {
            movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(kit.color(R.color.primary))
        }, spaced(R.dimen.space_md))

        lateinit var dialog: Dialog
        dialog = InfoSheet.withAction(activity, "发现新版本 ${release.tag}", body, "下载并安装", "稍后") { button ->
            if (downloading != null) return@withAction
            fun report(message: String, failed: Boolean) {
                status.visibility = android.view.View.VISIBLE
                status.text = message
                status.setTextColor(kit.color(if (failed) R.color.status_inactive else R.color.text_secondary))
            }
            val file = apkFile(release)
            if (file.isFile && verified(file, release)) {
                install(file, release, ::report)
                return@withAction
            }
            val cancelled = AtomicBoolean(false)
            downloading = cancelled
            button.isEnabled = false
            button.alpha = 0.6f
            button.text = "正在下载…"
            progress.visibility = android.view.View.VISIBLE
            progress.isIndeterminate = true
            report("正在从 GitHub 下载安装包", failed = false)
            file.parentFile?.listFiles()?.forEach { if (it != file) it.delete() }
            worker.execute {
                val result = runCatching {
                    AppUpdateClient.download(release, file, { done, total ->
                        activity.runOnUiThread {
                            if (total > 0) {
                                progress.isIndeterminate = false
                                progress.progress = (done * 1000 / total).toInt()
                                button.text = "正在下载 ${done * 100 / total}%"
                            }
                        }
                    }, cancelled::get)
                }
                activity.runOnUiThread {
                    downloading = null
                    if (activity.isDestroyed) return@runOnUiThread
                    button.isEnabled = true
                    button.alpha = 1f
                    result.onSuccess {
                        button.text = "安装"
                        progress.isIndeterminate = false
                        progress.progress = progress.max
                        report("下载完成，已通过校验", failed = false)
                        if (dialog.isShowing) install(file, release, ::report)
                    }.onFailure {
                        button.text = "重试"
                        progress.visibility = android.view.View.GONE
                        if (!cancelled.get()) report(OnlineDictClient.describe(it), failed = true)
                    }
                }
            }
        }
        dialog.setOnDismissListener { downloading?.set(true) }
    }

    private fun apkFile(release: AppUpdateClient.Release): File {
        val safe = release.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(File(activity.cacheDir, UPDATE_DIR), "XiaoAiTypeUnblock-$safe.apk")
    }

    private fun verified(file: File, release: AppUpdateClient.Release): Boolean =
        release.sha256?.equals(runCatching { AppUpdateClient.sha256(file) }.getOrNull(), ignoreCase = true)
            ?: (release.apkSize <= 0 || file.length() == release.apkSize)

    /** Checks that the APK is this module, newer and signed by the same key, then opens the installer. */
    @Suppress("DEPRECATION")
    private fun install(file: File, release: AppUpdateClient.Release, report: (String, Boolean) -> Unit) {
        val pm = activity.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags)
        if (archive == null || archive.packageName != activity.packageName) {
            file.delete()
            return report("下载的文件不是本模块的安装包，已删除", true)
        }
        if (versionCode(archive) <= BuildConfig.VERSION_CODE) {
            return report("安装包版本不高于当前版本，无需更新", true)
        }
        val installed = runCatching { pm.getPackageInfo(activity.packageName, flags) }.getOrNull()
        val expected = installed?.let(::signers).orEmpty()
        val actual = signers(archive)
        if (expected.isNotEmpty() && actual.isNotEmpty() && expected.intersect(actual).isEmpty()) {
            return report("安装包签名与当前安装的版本不同（例如当前为自行编译版），无法覆盖安装。请卸载后从发布页安装。", true)
        }
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            report("请允许本应用「安装未知应用」，返回后再点「安装」", true)
            toast("请允许安装未知应用")
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")))
            }
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.diagnostics.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }
            .onSuccess { report("已打开系统安装程序；安装完成后重启输入法生效（版本 ${release.tag}）", false) }
            .onFailure { report("无法打开系统安装程序，请从发布页手动下载安装", true) }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    /** SHA-256 digests of the certificates that signed [info], including its rotation history. */
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val certificates = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptySet()
            signing.apkContentsSigners.orEmpty().toList() + signing.signingCertificateHistory.orEmpty().toList()
        } else info.signatures.orEmpty().toList()
        return certificates.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun size(bytes: Long) = if (bytes >= 1 shl 20) "%.1f MB".format(bytes / 1048576.0)
        else "${(bytes + 1023) / 1024} KB"

    private fun spaced(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = kit.dimen(top) }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val PREFS_NAME = "app_update"
        const val UPDATE_DIR = "updates"
        const val AUTO_INTERVAL_MS = 60 * 60 * 1000L
        const val KEY_AUTO_CHECK = "auto_check"
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_TAG = "tag"
        const val KEY_NAME = "name"
        const val KEY_NOTES = "notes"
        const val KEY_PUBLISHED = "published_at"
        const val KEY_PAGE = "page_url"
        const val KEY_APK_NAME = "apk_name"
        const val KEY_APK_URL = "apk_url"
        const val KEY_APK_SIZE = "apk_size"
        const val KEY_SHA256 = "sha256"
    }
}
