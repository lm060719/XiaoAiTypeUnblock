package io.mo.xatype.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.NestedScrollView
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.keyboard.KeyboardShortcuts
import io.mo.xatype.keyboard.ShuangpinScheme
import io.mo.xatype.keyboard18.Keyboard18Dictionary
import io.mo.xatype.keyboard18.Keyboard18Engine
import io.mo.xatype.keyboard18.Keyboard18Keys
import io.mo.xatype.keyboard18.Keyboard18Store
import io.mo.xatype.keyboard18.Keyboard18Style
import java.util.concurrent.Executors

/** Interactive local preview uses the same dictionary and decoder as the host hook. */
class Keyboard18Activity : AppCompatActivity() {
    private lateinit var style: ShapeUiStyle
    private lateinit var enabled: SwitchCompat
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var composing: TextView
    private lateinit var output: EditText
    private lateinit var candidateRow: LinearLayout
    private lateinit var languageButton: AppCompatButton
    private lateinit var shiftButton: AppCompatButton
    private lateinit var schemeButton: AppCompatButton
    private lateinit var inputHelp: TextView
    private val letterButtons = LinkedHashMap<String, AppCompatButton>()
    private val previewControls = ArrayList<View>()
    private val worker = Executors.newSingleThreadExecutor()
    private var engine: Keyboard18Engine? = null
    private var english = false
    private var uppercase = false
    private var pendingRestart = false
    private var loadGeneration = 0
    private var restoreRaw = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        style = ShapeUiStyle(this)
        english = savedInstanceState?.getBoolean("english") ?: false
        uppercase = savedInstanceState?.getBoolean("uppercase") ?: false
        pendingRestart = savedInstanceState?.getBoolean("pending_restart") ?: false
        restoreRaw = savedInstanceState?.getString("raw").orEmpty()
        val root = column().apply { setBackgroundColor(style.background) }
        val toolbar = Toolbar(this).apply {
            title = "18键键盘"
            setTitleTextColor(style.primary)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationIcon?.setTint(style.primary)
            setNavigationContentDescription("返回")
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, style.dp(ShapeUiStyle.BUTTON)))
        val body = column().apply { setPadding(style.dp(ShapeUiStyle.LG), style.dp(ShapeUiStyle.SM),
            style.dp(ShapeUiStyle.LG), style.dp(ShapeUiStyle.XL)) }
        val scroll = NestedScrollView(this).apply { isFillViewport = true
            addView(body, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL)) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(text("更宽的按键，更轻松地输入", ShapeUiStyle.TITLE, strong = true))
        body.addView(text("按图片的 7 · 6 · 5 布局。中文点按分组键，长按选择准确字母和数字；开启首页「字母下滑快捷操作」后，底排字母下滑渐变为功能图标，轻震后松手执行。",
            ShapeUiStyle.BODY, secondary = true), spaced())
        val settings = card()
        enabled = SwitchCompat(this).apply {
            text = "在键盘选择中显示18键"
            textSize = ShapeUiStyle.HEADING
            setTextColor(style.primary)
            minHeight = style.dp(ShapeUiStyle.BUTTON)
            isChecked = Keyboard18Store.snapshot(this@Keyboard18Activity).getBoolean(Keyboard18Store.SHOW_IN_PICKER)
            setOnCheckedChangeListener { _, checked ->
                runCatching { Keyboard18Store.setVisible(this@Keyboard18Activity, checked) }
                    .onSuccess { pendingRestart = true; performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); updateStatus() }
                    .onFailure { Toast.makeText(this@Keyboard18Activity, it.message, Toast.LENGTH_LONG).show()
                        setOnCheckedChangeListener(null); isChecked = !checked; recreate() }
            }
        }
        settings.addView(enabled, LinearLayout.LayoutParams(-1, -2))
        val initialSettings = Keyboard18Store.snapshot(this)
        val doublePinyin = SwitchCompat(this).apply {
            text = "18键双拼"
            textSize = ShapeUiStyle.HEADING
            setTextColor(style.primary)
            minHeight = style.dp(ShapeUiStyle.BUTTON)
            isChecked = initialSettings.getBoolean(Keyboard18Store.SHUANGPIN)
            setOnCheckedChangeListener { view, checked ->
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                val snapshot = Keyboard18Store.snapshot(this@Keyboard18Activity)
                saveShuangpin(checked, ShuangpinScheme.resolve(snapshot.getString(Keyboard18Store.SCHEME)))
            }
        }
        settings.addView(doublePinyin, spaced())
        schemeButton = button("双拼方案：${ShuangpinScheme.resolve(initialSettings.getString(Keyboard18Store.SCHEME)).title}") {
            val schemes = ShuangpinScheme.entries
            val selected = ShuangpinScheme.resolve(Keyboard18Store.snapshot(this).getString(Keyboard18Store.SCHEME))
            InfoSheet.choose(this, "18 键双拼方案", schemes.map { it.title }, schemes.indexOf(selected)) { index ->
                saveShuangpin(doublePinyin.isChecked, schemes[index])
            }
        }
        settings.addView(schemeButton, spaced())
        settings.addView(text("重启应用设置后，在小爱「键盘选择」中选择「18键」。可直接切换拼音26键、虎码/五笔；此开关只控制入口显示。词库和输入均在本地。",
            ShapeUiStyle.CAPTION, secondary = true), spaced())
        status = text("", ShapeUiStyle.CAPTION, secondary = true)
        settings.addView(status, spaced())
        body.addView(settings, spaced())

        body.addView(text("试试18键", ShapeUiStyle.HEADING, strong = true), spaced())
        inputHelp = text("", ShapeUiStyle.CAPTION, secondary = true)
        body.addView(inputHelp, spaced())
        val preview = card()
        output = EditText(this).apply {
            hint = "输入的文字显示在这里"
            textSize = ShapeUiStyle.BODY
            setTextColor(style.primary); setHintTextColor(style.secondary)
            minHeight = style.dp(PREVIEW_OUTPUT_HEIGHT)
            maxLines = 6
            showSoftInputOnFocus = false
            setText(savedInstanceState?.getString("text").orEmpty())
            setSelection(text.length)
        }
        preview.addView(output, LinearLayout.LayoutParams(-1, -2))
        composing = text("", ShapeUiStyle.BODY)
        preview.addView(composing, spaced())
        candidateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        preview.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false
            addView(candidateRow, android.widget.FrameLayout.LayoutParams(-2, -2)) }, LinearLayout.LayoutParams(-1, -2))
        Keyboard18Keys.rows.forEachIndexed { rowIndex, groups ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
            if (rowIndex == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 0, Keyboard18Style.SIDE_WEIGHT))
            if (rowIndex == 2) {
                shiftButton = keyButton("⇧") { flush(); uppercase = !uppercase; labels() }
                row.addView(shiftButton, keyParams(Keyboard18Style.CONTROL_WEIGHT))
            }
            groups.forEach { group ->
                val button = keyButton(group) {
                    val decoder = engine ?: return@keyButton
                    if (english || uppercase) { flush(); insert(if (uppercase) group.first().uppercaseChar().toString() else group.first().toString()) }
                    else showResult(decoder.input(Keyboard18Keys.code(group).toChar()))
                }
                button.contentDescription = "${group.uppercase()}键，长按选择字母和符号；下滑快捷操作由首页开关控制"
                (button as ShortcutSwipeButton).apply {
                    shortcut = KeyboardShortcuts.groups18[group]
                    shortcutsEnabled = { ConfigManager.getLocalPrefs(this@Keyboard18Activity)
                        .getBoolean(ConfigManager.KEY_KEYBOARD_SHORTCUTS, false) }
                    executeShortcut = { this@Keyboard18Activity.shortcut(it) }
                }
                button.chooseOnLongPress { popup(group) }
                letterButtons[group] = button
                row.addView(button, keyParams(Keyboard18Style.weight(group)))
            }
            if (rowIndex == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 0, Keyboard18Style.SIDE_WEIGHT))
            if (rowIndex == 2) {
                val delete = keyButton("⌫") {
                    val decoder = engine ?: return@keyButton
                    if (decoder.raw.isNotEmpty()) showResult(decoder.backspace()) else {
                        val start = output.selectionStart.coerceAtLeast(0)
                        val end = output.selectionEnd.coerceAtLeast(start)
                        if (end > start) output.text.delete(start, end)
                        else if (start > 0) output.text.delete(Character.offsetByCodePoints(output.text, start, -1), start)
                    }
                }
                delete.contentDescription = "退格，长按清空预览"
                delete.setOnLongClickListener { engine?.clear(); output.text.clear(); showCandidates(); true }
                row.addView(delete, keyParams(Keyboard18Style.CONTROL_WEIGHT))
            }
            preview.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(keyButton("符") { symbols() }, keyParams(1f))
        bottom.addView(keyButton("123") { choices("数字", (0..9).map(Int::toString)) { literal(it) } }, keyParams(1f))
        bottom.addView(keyButton("，") { literal(if (english) "," else "，") }, keyParams(0.7f))
        bottom.addView(keyButton("空格") { val decoder = engine ?: return@keyButton
            if (decoder.raw.isNotEmpty()) showResult(decoder.input(' ')) else insert(" ") }, keyParams(2.5f))
        bottom.addView(keyButton("。") { literal(if (english) "." else "。") }, keyParams(0.7f))
        languageButton = keyButton("中") { flush(); english = !english; labels(); showCandidates() }
        bottom.addView(languageButton, keyParams(1f))
        bottom.addView(keyButton("↵", accent = true) { flush(); insert("\n") }, keyParams(1f))
        preview.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        progress = text("正在准备离线词库…", ShapeUiStyle.CAPTION, secondary = true)
        preview.addView(progress, spaced())
        preview.addView(button("清空预览") { engine?.clear(); output.text.clear(); showCandidates() }, spaced())
        body.addView(preview, spaced())
        body.addView(text("按宿主接口自动适配，具体结果见首页状态。使用独立离线候选；平板分离布局暂保留原布局。",
            ShapeUiStyle.CAPTION, secondary = true), spaced())
        body.addView(button("重新读取词库") { prepareDictionary() }, spaced())

        val footer = column().apply { setPadding(style.dp(ShapeUiStyle.LG), style.dp(ShapeUiStyle.SM),
            style.dp(ShapeUiStyle.LG), style.dp(ShapeUiStyle.SM)); setBackgroundColor(style.surface) }
        footer.addView(button("重启输入法并应用", accent = true) {
            ImeRestarter.restart(this) { if (!isDestroyed && !isFinishing) updateStatus() }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(footer)
        setContentView(root)
        SettingsWindow.apply(window, root, topBar = toolbar, bottomBar = footer)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        labels(); updateStatus(); prepareDictionary()
    }
    override fun onResume() { super.onResume(); if (::status.isInitialized) updateStatus() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("text", output.text.toString()); outState.putString("raw", engine?.raw ?: restoreRaw)
        outState.putBoolean("english", english); outState.putBoolean("uppercase", uppercase)
        outState.putBoolean("pending_restart", pendingRestart); super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
    private fun prepareDictionary() {
        val generation = ++loadGeneration
        val selectedScheme = Keyboard18Store.scheme(Keyboard18Store.snapshot(this))
        previewControls.forEach { it.isEnabled = false }; progress.text = "正在准备离线词库…"
        val raw = if (engine == null || engine?.scheme == selectedScheme) engine?.raw ?: restoreRaw else ""
        restoreRaw = raw
        engine = null
        candidateRow.removeAllViews()
        composing.text = ""
        inputHelp.text = if (selectedScheme == null)
            "支持全拼和首字母简拼，例如「为什么」简拼 wsm：依次点 WE、SD、M。" else
            "${selectedScheme.title}：按双拼编码依次点对应的分组键；长按可选择准确字母。" +
                if (selectedScheme.usesSemicolon) "分号韵母共用 L 键，也可长按 L 选择分号。" else ""
        worker.execute {
            val loaded = runCatching { assets.open("keyboard18/pinyin.table").use { Keyboard18Dictionary.read(it, selectedScheme) } }
            runOnUiThread {
                if (isDestroyed || isFinishing || generation != loadGeneration) return@runOnUiThread
                loaded.onSuccess { dictionary ->
                    engine = Keyboard18Engine(dictionary).apply { restore(raw) }
                    restoreRaw = ""; previewControls.forEach { it.isEnabled = true }
                    progress.text = "离线词库 · ${dictionary.entries.size} 条字词"
                    showCandidates()
                }.onFailure { progress.text = "词库读取失败：${it.message}。可重新读取。" }
            }
        }
    }
    private fun saveShuangpin(enabled: Boolean, scheme: ShuangpinScheme) {
        runCatching { Keyboard18Store.setShuangpin(this, enabled, scheme) }.onSuccess {
            flush()
            restoreRaw = ""
            pendingRestart = true
            schemeButton.text = "双拼方案：${scheme.title}"
            updateStatus()
            prepareDictionary()
        }.onFailure {
            Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
            recreate()
        }
    }
    private fun updateStatus() {
        val snapshot = Keyboard18Store.snapshot(this)
        if (snapshot.getLong(Keyboard18Store.STATUS_TIME) > 0) pendingRestart = false
        status.text = if (pendingRestart) "设置已保存，请重启输入法应用。" else snapshot.getString(Keyboard18Store.STATUS)
    }
    private fun labels() {
        letterButtons.forEach { (group, view) ->
            val index = Keyboard18Keys.groups.indexOf(group)
            val hint = Keyboard18Keys.hints[index]
            view.text = SpannableString(hint + "\n" + Keyboard18Style.label(group, uppercase)).apply {
                setSpan(RelativeSizeSpan(Keyboard18Style.PREVIEW_HINT_SCALE), 0, hint.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(style.secondary), 0, hint.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        languageButton.text = if (english) "英" else "中"
        shiftButton.text = if (uppercase) "⇪" else "⇧"
    }
    private fun showResult(result: io.mo.xatype.keyboard18.Pinyin18Result) { result.commit?.let(::insert); showCandidates() }
    private fun showCandidates() {
        val decoder = engine
        composing.text = decoder?.display?.ifEmpty {
            when {
                english -> "英文：点按首字母，长按选择另一字母"
                decoder.scheme != null -> "${decoder.scheme!!.title}：按双拼编码输入"
                else -> "全拼 / 简拼：为什么 → WE · SD · M"
            }
        }
        candidateRow.removeAllViews()
        decoder?.candidates?.take(40)?.forEachIndexed { index, candidate ->
            candidateRow.addView(button(candidate.text) { decoder.select(index)?.let(::insert); showCandidates() },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = style.dp(ShapeUiStyle.XS) })
        }
    }
    private fun insert(text: String) {
        val start = output.selectionStart.coerceAtLeast(0)
        val end = output.selectionEnd.coerceAtLeast(start)
        output.text.replace(start, end, text); output.setSelection(start + text.length)
    }
    private fun flush() { engine?.commitAll()?.let(::insert); showCandidates() }
    private fun literal(value: String) { flush(); insert(value) }
    private fun popup(group: String) {
        val hint = Keyboard18Keys.hints[Keyboard18Keys.groups.indexOf(group)]
        val values = group.map { if (uppercase) it.uppercaseChar().toString() else it.toString() } +
            listOf(hint).filter { it.length == 1 } +
            if (!english && !uppercase && group == "l" && engine?.scheme?.usesSemicolon == true) listOf(";") else emptyList()
        choices("${group.uppercase()}键", values) { selected ->
            if (!english && !uppercase && selected.length == 1 && (selected[0] in 'a'..'z' ||
                    (selected == ";" && engine?.raw?.isNotEmpty() == true && engine?.scheme?.usesSemicolon == true)))
                engine?.input(selected.single())?.let(::showResult) else literal(selected)
        }
    }
    private fun shortcut(action: String) {
        flush()
        when (action) {
            "全选" -> output.selectAll()
            "复制" -> output.onTextContextMenuItem(android.R.id.copy)
            "粘贴" -> output.onTextContextMenuItem(android.R.id.paste)
            "段首", "段尾" -> output.setSelection(KeyboardShortcuts.paragraphPosition(output.text,
                output.selectionStart.coerceAtLeast(0), 0, action == "段尾"))
        }
    }
    private fun symbols() = choices("符号", listOf("，", "。", "！", "？", "、", "：", "；", "'", "(", ")", "@", "#", "/", "-", "_", "=", "+", "%", "￥")) {
        if (it == "'" && !english) engine?.input('\'')?.let(::showResult) else literal(it)
    }
    private fun choices(title: String, values: List<String>, selected: (String) -> Unit) {
        if (engine == null) return
        InfoSheet.choose(this, title, values) { index -> selected(values[index]) }
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun card() = column().apply { background = style.surface(); setPadding(style.dp(ShapeUiStyle.MD),
        style.dp(ShapeUiStyle.LG), style.dp(ShapeUiStyle.MD), style.dp(ShapeUiStyle.LG)) }
    private fun text(value: String, size: Float, secondary: Boolean = false, strong: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(if (secondary) style.secondary else style.primary)
        if (strong) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(label: String, accent: Boolean = false, target: AppCompatButton = AppCompatButton(this), action: () -> Unit) = target.apply {
        text = label; isAllCaps = false; textSize = ShapeUiStyle.BODY
        setTextColor(if (accent) style.onAccent else style.primary)
        background = style.ripple(if (accent) style.accent else style.surface, ShapeUiStyle.SMALL_RADIUS)
        backgroundTintList = null; minHeight = style.dp(ShapeUiStyle.TOUCH)
        setPadding(style.dp(ShapeUiStyle.SM), style.dp(ShapeUiStyle.SM), style.dp(ShapeUiStyle.SM), style.dp(ShapeUiStyle.SM))
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); action() }
    }
    private fun keyButton(label: String, accent: Boolean = false, action: () -> Unit) = button(label, accent, ShortcutSwipeButton(this), action).apply {
        minWidth = 0; minimumWidth = 0; textSize = Keyboard18Style.PREVIEW_TEXT_SIZE
        includeFontPadding = false; gravity = Gravity.CENTER
        background = style.ripple(if (accent) style.accent else style.background, Keyboard18Style.PREVIEW_RADIUS)
        setPadding(0, style.dp(ShapeUiStyle.XS), 0, style.dp(ShapeUiStyle.XS))
        minHeight = style.dp(ShapeUiStyle.BUTTON); previewControls.add(this)
    }
    private fun keyParams(weight: Float) = LinearLayout.LayoutParams(0, -2, weight).apply {
        setMargins(style.dp(Keyboard18Style.PREVIEW_GAP), style.dp(Keyboard18Style.PREVIEW_GAP),
            style.dp(Keyboard18Style.PREVIEW_GAP), style.dp(Keyboard18Style.PREVIEW_GAP)) }
    private fun spaced() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = style.dp(ShapeUiStyle.MD) }
    companion object {
        private const val PREVIEW_OUTPUT_HEIGHT = 80
    }
}
