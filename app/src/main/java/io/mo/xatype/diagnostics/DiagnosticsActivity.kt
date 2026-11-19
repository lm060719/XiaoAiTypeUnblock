package io.mo.xatype.diagnostics

import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import io.mo.xatype.R
import io.mo.xatype.ui.SettingsWindow
import io.mo.xatype.ui.ShapeUiStyle
import io.mo.xatype.ui.ShapeUiStyle.Companion.BODY
import io.mo.xatype.ui.ShapeUiStyle.Companion.BUTTON
import io.mo.xatype.ui.ShapeUiStyle.Companion.CAPTION
import io.mo.xatype.ui.ShapeUiStyle.Companion.DISABLED_ALPHA
import io.mo.xatype.ui.ShapeUiStyle.Companion.ICON
import io.mo.xatype.ui.ShapeUiStyle.Companion.ICON_TILE
import io.mo.xatype.ui.ShapeUiStyle.Companion.LG
import io.mo.xatype.ui.ShapeUiStyle.Companion.MAX_WIDTH
import io.mo.xatype.ui.ShapeUiStyle.Companion.MD
import io.mo.xatype.ui.ShapeUiStyle.Companion.METRIC
import io.mo.xatype.ui.ShapeUiStyle.Companion.SM
import io.mo.xatype.ui.ShapeUiStyle.Companion.SMALL_RADIUS
import io.mo.xatype.ui.ShapeUiStyle.Companion.TITLE
import io.mo.xatype.ui.ShapeUiStyle.Companion.TOUCH
import io.mo.xatype.ui.ShapeUiStyle.Companion.XL
import io.mo.xatype.ui.ShapeUiStyle.Companion.XS
import java.io.File

class DiagnosticsActivity : AppCompatActivity() {
    private class Metric(val view: LinearLayout, val value: TextView)

    private lateinit var style: ShapeUiStyle
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var stateTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var metrics: View
    private lateinit var size: Metric
    private lateinit var remaining: Metric
    private lateinit var primary: AppCompatButton
    private lateinit var overwriteWarning: TextView
    private val exportRows = ArrayList<View>()
    private val refresh = object : Runnable {
        override fun run() {
            DiagnosticsCollector.refresh(this@DiagnosticsActivity)
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        style = ShapeUiStyle(this)
        val root = column().apply { setBackgroundColor(style.background) }
        val toolbar = Toolbar(this).apply {
            title = "输入法与外观诊断"
            setTitleTextColor(style.primary)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationIcon?.setTint(style.primary)
            navigationContentDescription = "返回"
            setNavigationOnClickListener { finish() }
            contentInsetStartWithNavigation = 0
            minimumHeight = style.dp(BUTTON)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

        val body = column().apply { setPadding(style.dp(LG), style.dp(SM), style.dp(LG), style.dp(XL)) }
        root.addView(NestedScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(centered(body))
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        val hero = column().apply { background = style.surface(); pad(XL) }
        body.addView(hero)
        stateTitle = text("", TITLE, style.primary, true).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        }
        hero.addView(stateTitle)
        statusText = text("", BODY, style.secondary).apply {
            setLineSpacing(style.dp(XS).toFloat(), 1f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        hero.addView(statusText, spaced(top = SM))
        metrics = row().apply {
            gravity = Gravity.TOP
            size = metric("日志大小")
            remaining = metric("剩余时间")
            addView(size.view, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = style.dp(LG) })
            addView(remaining.view, LinearLayout.LayoutParams(0, -2, 1f))
        }
        hero.addView(metrics, spaced(top = LG))

        body.addView(row().apply {
            background = style.surface(style.accentSurface)
            setPadding(style.dp(LG), style.dp(MD), style.dp(LG), style.dp(MD))
            addView(icon(R.drawable.ic_info, style.accent), LinearLayout.LayoutParams(style.dp(ICON), style.dp(ICON)))
            addView(text("日志可能包含输入内容、设备及版本信息。请用测试文字复现。", CAPTION, style.accentText),
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = style.dp(MD) })
        }, spaced(top = LG))

        val exports = column().apply { background = style.surface() }
        exports.addView(actionRow("分享日志", R.drawable.ic_share) { export(false) })
        exports.addView(actionRow("保存日志为 .txt", R.drawable.ic_shape_import) { export(true) })
        body.addView(exports, spaced(top = LG))

        val footerContent = column().apply {
            setPadding(style.dp(LG), style.dp(MD), style.dp(LG), style.dp(MD))
            primary = AppCompatButton(this@DiagnosticsActivity).apply {
                textSize = BODY
                setTextColor(style.onAccent)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                isAllCaps = false
                minHeight = style.dp(BUTTON)
                minimumHeight = style.dp(BUTTON)
                setPadding(style.dp(LG), style.dp(MD), style.dp(LG), style.dp(MD))
                background = style.ripple(style.accent)
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (DiagnosticLog.running) DiagnosticsCollector.stop(this@DiagnosticsActivity)
                    else DiagnosticsCollector.start(this@DiagnosticsActivity)
                    render()
                }
            }
            overwriteWarning = text("再次采集会覆盖当前日志", CAPTION, style.secondary).apply {
                gravity = Gravity.CENTER
            }
            addView(overwriteWarning, spaced(bottom = SM))
            addView(primary, LinearLayout.LayoutParams(-1, -2))
        }
        val footer = FrameLayout(this).apply {
            setBackgroundColor(style.surface)
            addView(footerContent, centeredParams())
        }
        root.addView(footer)
        setContentView(root)
        SettingsWindow.apply(window, root, topBar = toolbar, bottomBar = footer)
        render()
    }

    private fun render() {
        val running = DiagnosticLog.running
        val log = DiagnosticLog.logFile(this)
        val hasLog = log.length() > 0
        val started = DiagnosticsCollector.startedAt(this)
        val failed = !running && DiagnosticLog.failed
        val title = when {
            running && started == 0L -> "正在准备采集"
            running -> "正在采集"
            failed -> "采集失败"
            hasLog -> "日志已就绪"
            else -> "尚未采集"
        }
        if (stateTitle.text != title) stateTitle.text = title
        statusText.setTextColor(if (failed) style.errorText else style.secondary)
        val message = DiagnosticLog.status.takeUnless { it == DiagnosticLog.IDLE_STATUS }.orEmpty()
        if (statusText.text != message) statusText.text = message
        statusText.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        val showRemaining = running && started > 0
        metrics.visibility = if (hasLog || showRemaining) View.VISIBLE else View.GONE
        size.view.visibility = if (hasLog) View.VISIBLE else View.GONE
        remaining.view.visibility = if (showRemaining) View.VISIBLE else View.GONE
        if (hasLog) size.value.text = Formatter.formatShortFileSize(this, log.length())
        if (showRemaining) {
            val seconds = ((DiagnosticsCollector.MAX_MILLIS - (System.currentTimeMillis() - started)) / 1000)
                .coerceAtLeast(0)
            remaining.value.text = DateUtils.formatElapsedTime(seconds)
        }
        primary.text = if (running) "停止采集" else "开始采集并重启输入法"
        overwriteWarning.visibility = if (!running && hasLog) View.VISIBLE else View.GONE
        exportRows.forEach {
            it.isEnabled = hasLog
            it.alpha = if (hasLog) 1f else DISABLED_ALPHA
        }
    }

    private var pendingExport: File? = null

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
                pendingExport = File(cacheDir, "diagnostics/$it")
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

    private fun metric(label: String): Metric {
        val value = text("", METRIC, style.primary, true)
        val view = column().apply {
            addView(value)
            addView(text(label, CAPTION, style.secondary), spaced(top = XS))
        }
        return Metric(view, value)
    }

    private fun actionRow(title: String, iconRes: Int, action: () -> Unit): View = row().apply {
        setPadding(style.dp(LG), style.dp(LG), style.dp(LG), style.dp(LG))
        minimumHeight = style.dp(TOUCH)
        background = style.ripple()
        addView(icon(iconRes, style.accent).apply {
            background = style.surface(style.accentSurface, SMALL_RADIUS)
            pad(MD)
        }, LinearLayout.LayoutParams(style.dp(ICON_TILE), style.dp(ICON_TILE)).apply { marginEnd = style.dp(MD) })
        addView(text(title, BODY, style.primary, true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(icon(R.drawable.ic_shape_chevron, style.secondary),
            LinearLayout.LayoutParams(style.dp(ICON), style.dp(ICON)).apply { marginStart = style.dp(SM) })
        isFocusable = true
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }
        exportRows.add(this)
    }

    private fun text(value: String, size: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
    private fun icon(id: Int, tint: Int) = ImageView(this).apply {
        setImageResource(id)
        imageTintList = ColorStateList.valueOf(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun View.pad(value: Int) = setPadding(style.dp(value), style.dp(value), style.dp(value), style.dp(value))
    private fun spaced(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = style.dp(top); bottomMargin = style.dp(bottom)
    }
    private fun centeredParams() = FrameLayout.LayoutParams(
        minOf(resources.displayMetrics.widthPixels, style.dp(MAX_WIDTH)), -2, Gravity.CENTER_HORIZONTAL)
    private fun centered(child: View) = FrameLayout(this).apply { addView(child, centeredParams()) }
}
