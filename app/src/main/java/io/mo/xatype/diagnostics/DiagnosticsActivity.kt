package io.mo.xatype.diagnostics

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import io.mo.xatype.BuildConfig

class DiagnosticsActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var startButton: Button
    private val refresh = object : Runnable {
        override fun run() {
            statusView.text = DiagnosticLog.status
            startButton.isEnabled = !DiagnosticLog.running
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "输入法与外观诊断 ${BuildConfig.VERSION_NAME}"
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
        layout.addView(TextView(this).apply {
            textSize = 16f
            text = "1. 点击开始，授权 Root，等待显示正在采集。\n" +
                "2. 切到记事本，先呼出普通键盘，再切悬浮键盘、拖动位置、打开剪贴板；尝试横竖屏切换。\n" +
                "   如需排查中文输入，在中文模式输入 nihao，尝试中英文切换。\n" +
                "3. 返回这里，停止采集，再导出分享或保存文件。\n\n" +
                "会自动重启小爱输入法。最长采集 10 分钟，最多 8 MB；再次开始会覆盖上次日志。\n\n" +
                "仅收集输入法和模块进程日志、设备与版本信息。输入法原生日志可能包含输入文字，" +
                "请仅用测试文字复现，不要输入密码或私人内容。\n"
        })
        statusView = TextView(this).apply { textSize = 16f }
        layout.addView(statusView)
        startButton = Button(this).apply {
            text = "开始采集并重启输入法"
            setOnClickListener {
                isEnabled = false
                statusView.text = "正在启动采集…"
                try {
                    startForegroundService(Intent(this@DiagnosticsActivity, DiagnosticsService::class.java))
                } catch (e: Exception) {
                    isEnabled = true
                    showError(e)
                }
            }
        }
        layout.addView(startButton)
        layout.addView(Button(this).apply {
            text = "停止采集"
            setOnClickListener { stopService(Intent(this@DiagnosticsActivity, DiagnosticsService::class.java)) }
        })
        layout.addView(Button(this).apply {
            text = "导出分享日志"
            setOnClickListener { export(false) }
        })
        layout.addView(Button(this).apply {
            text = "保存日志文件"
            setOnClickListener { export(true) }
        })
    }

    private var pendingExport: java.io.File? = null

    private fun export(save: Boolean) {
        Thread {
            try {
                val file = DiagnosticLog.snapshot(this)
                runOnUiThread {
                    try {
                        if (save) {
                            pendingExport = file
                            @Suppress("DEPRECATION")
                            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                type = "text/plain"
                                addCategory(Intent.CATEGORY_OPENABLE)
                                putExtra(Intent.EXTRA_TITLE, file.name)
                            }, 201)
                        } else {
                            val uri = FileProvider.getUriForFile(this, "$packageName.diagnostics.files", file)
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newRawUri("输入诊断日志", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "发送输入诊断日志"))
                        }
                    } catch (e: Exception) { showError(e) }
                }
            } catch (e: Exception) { runOnUiThread { showError(e) } }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingExport", pendingExport?.name)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        savedInstanceState.getString("pendingExport")?.let {
            if (it.startsWith("xiaotype-") && !it.contains('/') && !it.contains('\\')) {
                pendingExport = java.io.File(cacheDir, "diagnostics/$it")
            }
        }
    }

    @Deprecated("Uses the platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 201 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val file = pendingExport ?: return
        Thread {
            try {
                val stream = contentResolver.openOutputStream(uri) ?: error("无法打开保存位置")
                stream.use { output -> file.inputStream().use { it.copyTo(output) } }
                runOnUiThread { Toast.makeText(this, "日志已保存", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { runOnUiThread { showError(e) } }
        }.start()
    }

    private fun showError(e: Exception) {
        Toast.makeText(this, e.message ?: e.javaClass.simpleName, Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
