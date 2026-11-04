package io.mo.xatype.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.NestedScrollView
import io.mo.xatype.R
import io.mo.xatype.dict.OnlineDictClient
import io.mo.xatype.dict.OnlineDictSource
import io.mo.xatype.dict.OnlineDictStore
import io.mo.xatype.dict.RimeWordExtractor
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Online word list: choose one of two Rime projects, check for updates, download and queue the
 * import. The keyboard applies the queued list the next time its engine starts.
 */
class OnlineDictActivity : AppCompatActivity() {
    private lateinit var kit: SettingsKit
    private lateinit var body: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private var busyMessage: String? = null
    private var checkedThisVisit = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kit = SettingsKit(this)
        val style = ShapeUiStyle(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(kit.color(R.color.background_light))
        }
        val toolbar = Toolbar(this).apply {
            title = "在线词库"
            setTitleTextColor(style.primary)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationIcon?.setTint(style.primary)
            setNavigationContentDescription("返回")
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, style.dp(ShapeUiStyle.BUTTON)))
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = kit.dimen(R.dimen.space_lg)
            setPadding(side, kit.dimen(R.dimen.space_sm), side, kit.dimen(R.dimen.space_xl))
        }
        root.addView(NestedScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        SettingsWindow.apply(window, root, topBar = toolbar)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized && busyMessage == null) render()
        maybeAutoCheck()
    }

    override fun onDestroy() {
        cancelled.set(true)
        worker.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        val state = OnlineDictStore.snapshot(this)
        val current = OnlineDictSource.byId(state.getString(OnlineDictStore.SOURCE))
        body.removeAllViews()

        body.addView(kit.label(
            "从上游 Rime 词库下载常用词，导入小爱输入法的用户词库，作为联想与候选的补充。两个来源只能选一个，切换时会先移除上一来源导入的词，不影响你自己输入过的词。",
            13f, R.color.text_secondary).apply { setLineSpacing(kit.dp(3).toFloat(), 1f)
            setPadding(kit.dimen(R.dimen.space_xs), kit.dimen(R.dimen.space_sm), kit.dimen(R.dimen.space_xs), 0) })

        val busy = busyMessage != null
        kit.group(body, "词库来源", OnlineDictSource.entries.map { source ->
            kit.navRow(source.title + if (source == current) "（当前）" else "", source.summary, R.drawable.ic_text_fields) {
                if (!busy) confirmSwitch(source, current)
            }
        })

        kit.group(body, "状态", listOf(statusRow(state, current)))

        val rows = ArrayList<View>()
        if (current != null) {
            val latest = state.getString(OnlineDictStore.LATEST_VERSION).orEmpty()
            val installed = state.getString(OnlineDictStore.VERSION).orEmpty()
            val hasUpdate = latest.isNotEmpty() && latest != installed
            rows += kit.navRow(if (hasUpdate) "下载并导入更新" else "检查更新",
                if (hasUpdate) "新版本 ${state.getString(OnlineDictStore.LATEST_LABEL)}" else "对比 GitHub 上的最新发布",
                R.drawable.ic_restart) {
                if (busy) return@navRow
                if (hasUpdate) install(current) else checkNow(current, manual = true)
            }
            rows += kit.navRow("清除导入的词", "只删除由本功能导入的词", R.drawable.ic_shape_restore) {
                if (!busy) confirmClear()
            }
        }
        val cap = state.getInt(OnlineDictStore.MAX_WORDS)
        rows += kit.navRow("词条数量上限", "$cap 条 · 高频词优先", R.drawable.ic_shape_import,
            detail = SettingsKit.Detail("小爱输入法的用户词库总共只能存约 1.6 万个词，与你自己输入、学习的词和联系人共用，存满后会挤掉旧词。",
                notes = listOf("导入后用户词库总数不超过 1.2 万，给你自己的新词留出空间；不够时会少导入，不会挤掉原有的词。",
                    "修改后需重新下载并导入才会生效。", "仅保留 2–7 个汉字的词条，单字与带字母数字的词不导入。"))) {
            if (!busy) chooseCap(cap, current)
        }
        rows += kit.switchRow("打开页面时自动更新", "发现新版本时自动下载并排队导入",
            state.getBoolean(OnlineDictStore.AUTO_UPDATE), R.drawable.ic_restart) { value ->
            runCatching { OnlineDictStore.setAutoUpdate(this, value) }
                .onFailure { toast(it.message ?: "无法保存设置") }
            if (value) maybeAutoCheck()
        }.view
        kit.group(body, "更新与管理", rows)

        if (pending(state)) {
            kit.group(body, null, listOf(kit.navRow("重启输入法使其生效", "需要 Root 权限；也可手动重启输入法",
                R.drawable.ic_restart) { ImeRestarter.restart(this) { if (!isDestroyed) render() } }))
        }
    }

    private fun pending(state: Bundle) = state.getLong(OnlineDictStore.REVISION) > 0 &&
        state.getLong(OnlineDictStore.HOST_REVISION) != state.getLong(OnlineDictStore.REVISION)

    private fun statusRow(state: Bundle, current: OnlineDictSource?): View {
        val title: String
        val lines = ArrayList<String>()
        when {
            busyMessage != null -> { title = busyMessage!! }
            current == null && state.getString(OnlineDictStore.ACTION) != OnlineDictStore.ACTION_CLEAR -> {
                title = "尚未导入"
                lines += "选择一个来源开始"
            }
            current == null -> title = if (pending(state)) "已排队清除，重启输入法后生效" else hostLine(state)
            else -> {
                title = "${current.title} ${state.getString(OnlineDictStore.LABEL)}"
                lines += "已下载 ${state.getInt(OnlineDictStore.COUNT)} 条词"
                lines += if (pending(state)) "已排队，重启输入法后导入" else hostLine(state)
            }
        }
        val latestTime = state.getLong(OnlineDictStore.CHECK_TIME)
        if (latestTime > 0 && current != null) lines += "上次检查：" +
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(latestTime))
        val row = kit.row()
        row.isClickable = false
        row.foreground = null
        row.addView(kit.titleBlock(title, lines.joinToString("\n")),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun hostLine(state: Bundle): String {
        val status = state.getString(OnlineDictStore.HOST_STATUS).orEmpty()
        return status.ifEmpty { "输入法尚未回报" }
    }

    // -------------------------------------------------------------------- actions

    private fun confirmSwitch(source: OnlineDictSource, current: OnlineDictSource?) {
        if (source == current) {
            checkNow(source, manual = true)
            return
        }
        AlertDialog.Builder(this).setTitle("使用${source.title}？")
            .setMessage("将下载约 ${if (source == OnlineDictSource.RIME_ICE) 15 else 30} MB 的词库压缩包。" +
                (if (current != null) "上一来源「${current.title}」导入的词会在输入法重启后移除。" else "") +
                "\n\n词库来自 ${source.repository}，版权归原项目所有。")
            .setPositiveButton("下载并导入") { _, _ -> install(source) }
            .setNegativeButton("取消", null).show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setTitle("清除导入的词？")
            .setMessage("输入法重启后，删除由在线词库导入的词。你自己输入或学习过的词不会被删除。")
            .setPositiveButton("清除") { _, _ ->
                runCatching { OnlineDictStore.requestClear(this) }
                    .onFailure { toast(it.message ?: "无法保存清除请求") }
                render()
            }.setNegativeButton("取消", null).show()
    }

    private fun chooseCap(cap: Int, current: OnlineDictSource?) {
        val caps = OnlineDictStore.CAPS
        InfoSheet.choose(this, "词条数量上限", caps.map { "$it 条" }, caps.indexOf(cap)) { index ->
            runCatching { OnlineDictStore.setMaxWords(this, caps[index]) }.onFailure { toast("无法保存设置") }
            render()
            if (current != null) AlertDialog.Builder(this).setTitle("重新导入？")
                .setMessage("按新的数量上限重新下载并导入${current.title}。")
                .setPositiveButton("重新导入") { _, _ -> install(current) }
                .setNegativeButton("稍后", null).show()
        }
    }

    private fun maybeAutoCheck() {
        if (checkedThisVisit || busyMessage != null) return
        val state = OnlineDictStore.snapshot(this)
        val current = OnlineDictSource.byId(state.getString(OnlineDictStore.SOURCE)) ?: return
        if (!state.getBoolean(OnlineDictStore.AUTO_UPDATE)) return
        checkedThisVisit = true
        checkNow(current, manual = false)
    }

    /** Looks up the latest release; with auto update on and a newer version, continues straight into the import. */
    private fun checkNow(source: OnlineDictSource, manual: Boolean) = runTask("正在检查更新…") {
        val release = OnlineDictClient.fetchLatest(source)
        OnlineDictStore.saveCheck(this, release)
        val state = OnlineDictStore.snapshot(this)
        val newer = release.version != state.getString(OnlineDictStore.VERSION)
        when {
            newer && state.getBoolean(OnlineDictStore.AUTO_UPDATE) -> { downloadAndQueue(source, release); null }
            newer -> "发现新版本 ${release.label}"
            manual -> "已是最新版本（${release.label}）"
            else -> null
        }
    }

    private fun install(source: OnlineDictSource) = runTask("正在获取发布信息…") {
        val release = OnlineDictClient.fetchLatest(source)
        OnlineDictStore.saveCheck(this, release)
        downloadAndQueue(source, release)
        null
    }

    private fun downloadAndQueue(source: OnlineDictSource, release: OnlineDictClient.Release) {
        val archive = OnlineDictStore.archiveFile(this).also { it.parentFile?.mkdirs() }
        try {
            OnlineDictClient.download(release, archive, { done, total ->
                val mb = done / 1048576.0
                setBusy(if (total > 0) "正在下载 %.1f / %.1f MB".format(mb, total / 1048576.0) else "正在下载 %.1f MB".format(mb))
            }, cancelled::get)
            setBusy("正在整理词条…")
            val cap = OnlineDictStore.snapshot(this).getInt(OnlineDictStore.MAX_WORDS)
            val words = archive.inputStream().buffered().use {
                RimeWordExtractor.extract(it, source.dictFiles, cap, { n -> setBusy("正在整理词条… $n") }, cancelled::get)
            }
            OnlineDictStore.install(this, source, release, words)
        } finally {
            archive.delete()
        }
    }

    // ------------------------------------------------------------------- plumbing

    private fun setBusy(message: String) = runOnUiThread {
        if (isDestroyed || busyMessage == null) return@runOnUiThread
        busyMessage = message
        render()
    }

    /** Runs [operation] off the main thread; a returned string is shown as a toast. */
    private fun runTask(message: String, operation: () -> String?) {
        if (busyMessage != null) return
        busyMessage = message
        render()
        worker.execute {
            val result = runCatching(operation)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busyMessage = null
                render()
                result.onSuccess { text -> text?.let(::toast) ?: run {
                    if (OnlineDictStore.snapshot(this).let(::pending)) toast("已排队，重启输入法后开始导入")
                } }.onFailure { toast(OnlineDictClient.describe(it)) }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
