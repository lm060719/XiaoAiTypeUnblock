package io.mo.xatype.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.mo.xatype.shape.ShapeStore
import java.util.concurrent.Executors

class ShapeInputActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var page: ShapeSettingsView
    private var busy = false
    private var pendingRestart = false
    private val pendingImportName get() = ShapeStore.prefs(this).getString("pending_import_name", "自定义形码").orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "形码输入"
        pendingRestart = savedInstanceState?.getBoolean(STATE_PENDING_RESTART) ?: false
        page = ShapeSettingsView(this, ShapeSettingsView.Actions(
            back = { finish() }, help = ::showHelp, importTable = ::importDialog,
            restore = ::restoreDialog, maxCode = ::maxCodeDialog,
            restart = { ImeRestarter.restart(this) { if (!isDestroyed && !isFinishing) render() } },
            retry = { prepareTable() }, toggle = ::saveToggle
        ))
        setContentView(page)
        SettingsWindow.apply(window, page, topBar = page.toolbar, bottomBar = page.footer)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        prepareTable()
    }

    private fun prepareTable() = runTask("正在准备内置码表…") { ShapeStore.ensureBuiltin(this); "" }

    override fun onResume() {
        super.onResume()
        if (::page.isInitialized && !busy) render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PENDING_RESTART, pendingRestart)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    private fun render() {
        runCatching { ShapeStore.snapshot(this) }
            .onSuccess { snapshot ->
                // Reports with the current settings revision only arrive after the host reloads it.
                if (snapshot.getLong(ShapeStore.STATUS_TIME) > 0) pendingRestart = false
                page.show(snapshot, pendingRestart)
            }
            .onFailure { page.showError(it.message ?: "请重新读取码表。", "码表读取失败") }
    }

    private fun saveToggle(key: String, checked: Boolean) {
        val result = runCatching { ShapeStore.saveBoolean(this, key, checked) }
        if (result.isSuccess) pendingRestart = true
        render()
        result.onFailure { page.showError(it.message ?: "无法保存设置，请重试。") }
    }

    private fun restoreDialog() {
        AlertDialog.Builder(this).setTitle("恢复内置虎码？")
            .setMessage("当前码表将被替换为内置虎码字词码表，输入规则会保留。完成后需重启输入法。")
            .setPositiveButton("恢复") { _, _ -> runTask("正在恢复虎码…", changed = true) {
                val result = ShapeStore.restoreBuiltin(this)
                "已恢复 ${result.dictionary.entries.size} 条虎码数据。"
            } }.setNegativeButton("取消", null).show()
    }

    private fun maxCodeDialog() {
        val snapshot = runCatching { ShapeStore.snapshot(this) }.getOrElse {
            page.showError(it.message ?: "无法读取当前设置。")
            return
        }
        val minimum = snapshot.getInt(ShapeStore.DICT_MAX, 1).coerceIn(1, 8)
        val values = (minimum..8).toList()
        InfoSheet.choose(this, "最大码长", values.map { "$it 码" },
            values.indexOf(snapshot.getInt(ShapeStore.MAX_CODE, 4))) { index ->
            val result = runCatching {
                val sp = ShapeStore.prefs(this)
                check(sp.edit().putInt(ShapeStore.MAX_CODE, values[index])
                    .putLong(ShapeStore.REVISION, sp.getLong(ShapeStore.REVISION, 0) + 1)
                    .remove(ShapeStore.STATUS).remove(ShapeStore.STATUS_TIME).commit())
                pendingRestart = true
            }
            render()
            result.onFailure { page.showError("无法保存最大码长，请重试。") }
        }
    }

    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("形码输入指南")
            .setMessage("开始使用\n打开「在键盘选择中显示虎码/五笔」并重启输入法，在小爱「键盘选择」中选择「虎码/五笔」。可直接切回拼音26键或18键；插件开关只控制入口显示。此实验版本适配小爱 0.2.1053。\n\n" +
                "导入码表\n选择 UTF-8 文本，每行「文字 TAB 编码」，也支持「编码 TAB 文字」，可带第三列权重。编码限小写 a–z、1–8 码。支持 Rime .dict.yaml 的实际条目和 Fcitx5 [Data] 段，不加载引用码表或执行脚本。\n\n" +
                "上屏与选词\n空格提交首选，没有候选时上屏原编码。数字、半角分号和单引号可快捷选词。关闭对应快捷选词开关后，按普通数字或标点处理。\n\n" +
                "当前限制\n暂不支持拼音反查、自造词和学习调频。「已接入」或「已收到输入」不能代替实际输入验证。\n\n" +
                "内置数据\nlvyww/tiger-code · CC0-1.0\n固定版本 3e1bff2，171,074 条虎码字词数据。")
            .setPositiveButton("知道了", null).show()
    }

    private fun importDialog() {
        val name = EditText(this).apply {
            hint = "例如：我的五笔86"
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(128))
            textSize = ShapeUiStyle.BODY
        }
        val input = LinearLayout(this).apply {
            setPadding(page.style.dp(ShapeUiStyle.XL), page.style.dp(ShapeUiStyle.SM),
                page.style.dp(ShapeUiStyle.XL), 0)
            addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this).setTitle("导入码表").setView(input)
            .setMessage("为方案取一个名字，然后选择 UTF-8 码表文件。导入成功后替换当前码表。")
            .setPositiveButton("选择文件") { _, _ ->
                ShapeStore.prefs(this).edit().putString("pending_import_name",
                    name.text.toString().trim().ifBlank { "自定义形码" }).apply()
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, IMPORT_CODE)
            }.setNegativeButton("取消", null).show()
    }

    @Deprecated("Legacy result API supported on API 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMPORT_CODE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = pendingImportName
        runTask("正在校验并导入码表…", changed = true) {
            val result = checkNotNull(contentResolver.openInputStream(uri)) { "无法读取所选文件" }.use {
                ShapeStore.import(this, it, name)
            }
            "已导入 ${result.dictionary.entries.size} 条，跳过 ${result.skipped} 行、去重 ${result.duplicates} 条。请重启输入法。"
        }
    }

    private fun runTask(message: String, changed: Boolean = false, operation: () -> String) {
        if (busy) return
        busy = true
        page.setBusy(true, message)
        worker.execute {
            val result = runCatching(operation)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                page.setBusy(false)
                if (changed && result.isSuccess) pendingRestart = true
                render()
                result.onSuccess { if (it.isNotEmpty()) Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                    .onFailure { page.showError(it.message ?: "请检查文件格式后重试。") }
            }
        }
    }

    companion object {
        private const val IMPORT_CODE = 310
        private const val STATE_PENDING_RESTART = "pending_restart"
    }
}
