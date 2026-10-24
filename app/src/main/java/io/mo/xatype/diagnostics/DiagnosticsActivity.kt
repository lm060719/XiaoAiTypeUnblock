package io.mo.xatype.diagnostics

import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.core.widget.NestedScrollView
import io.mo.xatype.BuildConfig
import io.mo.xatype.R
import io.mo.xatype.ui.SettingsWindow
import io.mo.xatype.ui.ShapeUiStyle
import io.mo.xatype.ui.ShapeUiStyle.Companion.BODY
import io.mo.xatype.ui.ShapeUiStyle.Companion.BUTTON
import io.mo.xatype.ui.ShapeUiStyle.Companion.CAPTION
import io.mo.xatype.ui.ShapeUiStyle.Companion.DISABLED_ALPHA
import io.mo.xatype.ui.ShapeUiStyle.Companion.HEADING
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

/** Same page vocabulary as the shape-input screen: status hero, titled groups and an accent footer action. */
class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var style: ShapeUiStyle
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var badge: TextView
    private lateinit var stateTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var sizeValue: TextView
    private lateinit var remainingValue: TextView
    private lateinit var primary: AppCompatButton
    private lateinit var footerHint: TextView
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
        hero.addView(row().apply {
            addView(text("采集状态", CAPTION, style.secondary), LinearLayout.LayoutParams(0, -2, 1f))
            badge = text("", CAPTION, style.secondary, true).apply {
                setPadding(style.dp(MD), style.dp(XS), style.dp(MD), style.dp(XS))
            }
            addView(badge)
        })
        stateTitle = text("", TITLE, style.primary, true)
        hero.addView(stateTitle, spaced(top = SM))
        statusText = text("", BODY, style.secondary).apply {
            setLineSpacing(style.dp(XS).toFloat(), 1f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        hero.addView(statusText, spaced(top = SM))
        hero.addView(row().apply {
            gravity = Gravity.TOP
            val size = metric("日志大小").also { sizeValue = it.second }
            val remaining = metric("剩余时间").also { remainingValue = it.second }
            addView(size.first, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = style.dp(LG) })
            addView(remaining.first, LinearLayout.LayoutParams(0, -2, 1f))
        }, spaced(top = LG))

        body.addView(row().apply {
            background = style.surface(style.accentSurface)
            setPadding(style.dp(LG), style.dp(MD), style.dp(LG), style.dp(MD))
            addView(icon(R.drawable.ic_info, style.accent), LinearLayout.LayoutParams(style.dp(ICON), style.dp(ICON)))
            addView(text("仅收集小爱输入法和本模块的日志、设备与版本信息。输入法日志可能包含输入的文字，" +
                "请只用测试文字复现，不要输入密码或私人内容。", CAPTION, style.accentText),
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = style.dp(MD) })
        }, spaced(top = LG))

        val steps = group(body, "复现步骤", "按顺序操作，日志更完整")
        steps.addView(step(1, "开始采集", "自动重启小爱输入法，并开始采集"))
        steps.addView(step(2, "复现问题", "开始复现bug场景"))
        steps.addView(step(3, "停止并导出", "回到本页停止采集，再分享或保存日志"))

        val export = group(body, "导出日志", "最长 10 分钟、最多 8 MB；再次开始会覆盖上次日志")
        export.addView(actionRow("分享日志", "通过系统分享发送", R.drawable.ic_share) { export(false) })
        export.addView(actionRow("保存为文件", "选择位置保存为 .txt 文件", R.drawable.ic_shape_import) { export(true) })

        body.addView(text("模块版本 ${BuildConfig.VERSION_NAME}", CAPTION, style.secondary).apply {
            gravity = Gravity.CENTER
        }, spaced(top = LG))

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
            addView(primary, LinearLayout.LayoutParams(-1, -2))
            footerHint = text("", CAPTION, style.secondary).apply { gravity = Gravity.CENTER }
            addView(footerHint, spaced(top = SM))
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
        val (label, title) = when {
            running && started == 0L -> "准备中" to "正在准备采集"
            running -> "采集中" to "正在采集"
            failed -> "失败" to "采集失败"
            hasLog -> "已完成" to "日志已就绪"
            else -> "未开始" to "尚未采集"
        }
        badge.text = label
        val (badgeSurface, badgeText) = when {
            failed -> style.errorSurface to style.errorText
            running || hasLog -> style.accentSurface to style.accentText
            else -> style.background to style.secondary
        }
        badge.setTextColor(badgeText)
        badge.background = style.surface(badgeSurface, SMALL_RADIUS)
        stateTitle.text = title
        statusText.setTextColor(if (failed) style.errorText else style.secondary)
        statusText.text = when {
            DiagnosticLog.status != DiagnosticLog.IDLE_STATUS -> DiagnosticLog.status
            hasLog -> "上次采集的日志仍保存在本机，可直接导出"
            else -> "按下方步骤复现问题，日志只保存在本机，由你决定是否分享"
        }
        sizeValue.text = if (hasLog) Formatter.formatShortFileSize(this, log.length()) else "—"
        remainingValue.text = if (running && started > 0) {
            val seconds = ((DiagnosticsCollector.MAX_MILLIS - (System.currentTimeMillis() - started)) / 1000)
                .coerceAtLeast(0)
            "%d:%02d".format(seconds / 60, seconds % 60)
        } else "—"
        primary.text = if (running) "停止采集" else "开始采集并重启输入法"
        footerHint.text = if (running) "离开本页也会继续采集 · 复现后回来停止" else ""
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

    private fun group(body: LinearLayout, title: String, subtitle: String): LinearLayout {
        body.addView(column().apply {
            setPadding(style.dp(XS), 0, style.dp(XS), 0)
            addView(text(title, HEADING, style.primary, true))
            addView(text(subtitle, CAPTION, style.secondary), spaced(top = XS))
        }, spaced(top = XL, bottom = MD))
        return column().apply {
            background = style.surface()
            setPadding(0, style.dp(XS), 0, style.dp(XS))
            body.addView(this)
        }
    }

    private fun metric(label: String): Pair<LinearLayout, TextView> {
        val value = text("—", METRIC, style.primary, true)
        return column().apply {
            addView(value)
            addView(text(label, CAPTION, style.secondary), spaced(top = XS))
        } to value
    }

    private fun labels(title: String, subtitle: String) = column().apply {
        addView(text(title, BODY, style.primary, true))
        addView(text(subtitle, CAPTION, style.secondary).apply {
            setLineSpacing(style.dp(XS / 2).toFloat(), 1f)
        }, spaced(top = XS))
    }

    /** A numbered tile in the place where action rows show their icon. */
    private fun step(number: Int, title: String, subtitle: String) = row().apply {
        gravity = Gravity.TOP
        setPadding(style.dp(LG), style.dp(MD), style.dp(LG), style.dp(MD))
        addView(text("$number", BODY, style.accentText, true).apply {
            gravity = Gravity.CENTER
            background = style.surface(style.accentSurface, SMALL_RADIUS)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(style.dp(ICON_TILE), style.dp(ICON_TILE)).apply { marginEnd = style.dp(MD) })
        addView(labels(title, subtitle), LinearLayout.LayoutParams(0, -2, 1f))
        contentDescription = "第 $number 步，$title。$subtitle"
    }

    private fun actionRow(title: String, subtitle: String, iconRes: Int, action: () -> Unit): View = row().apply {
        setPadding(style.dp(LG), style.dp(LG), style.dp(LG), style.dp(LG))
        minimumHeight = style.dp(TOUCH)
        background = style.ripple()
        addView(icon(iconRes, style.accent).apply {
            background = style.surface(style.accentSurface, SMALL_RADIUS)
            pad(MD)
        }, LinearLayout.LayoutParams(style.dp(ICON_TILE), style.dp(ICON_TILE)).apply { marginEnd = style.dp(MD) })
        addView(labels(title, subtitle), LinearLayout.LayoutParams(0, -2, 1f))
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
