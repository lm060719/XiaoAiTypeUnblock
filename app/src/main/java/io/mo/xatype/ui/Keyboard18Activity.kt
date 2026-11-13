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
import io.mo.xatype.keyboard.ShortcutKeyboard
import io.mo.xatype.keyboard.ShuangpinScheme
import io.mo.xatype.keyboard18.Keyboard18Dictionary
import io.mo.xatype.keyboard18.Keyboard18Engine
import io.mo.xatype.keyboard18.GroupedKeys
import io.mo.xatype.keyboard18.Keyboard14Keys
import io.mo.xatype.keyboard18.Keyboard18Keys
import io.mo.xatype.keyboard18.Keyboard18Store
import io.mo.xatype.keyboard18.Keyboard18Style
import java.util.concurrent.Executors

/**
 * Interactive local preview uses the same dictionary and decoder as the host hook.
 * [EXTRA_LAYOUT] selects the 18-key or 14-key grouping; both share the double-pinyin settings.
 */
class Keyboard18Activity : AppCompatActivity() {
    private lateinit var keys: GroupedKeys
    private lateinit var style: ShapeUiStyle
    private lateinit var enabled: SwitchCompat
    private lateinit var restartButton: AppCompatButton
    private lateinit var progress: TextView
    private lateinit var composing: TextView
    private lateinit var output: EditText
    private lateinit var candidateRow: LinearLayout
    private lateinit var languageButton: AppCompatButton
    private lateinit var shiftButton: AppCompatButton
    private lateinit var schemeButton: AppCompatButton
    private lateinit var keypad: LinearLayout
    private val letterButtons = LinkedHashMap<String, AppCompatButton>()
    private val englishButtons = LinkedHashMap<Char, AppCompatButton>()
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
        keys = if (intent.getStringExtra(EXTRA_LAYOUT) == LAYOUT_14) Keyboard14Keys else Keyboard18Keys
        english = savedInstanceState?.getBoolean("english") ?: false
        uppercase = savedInstanceState?.getBoolean("uppercase") ?: false
        pendingRestart = savedInstanceState?.getBoolean("pending_restart") ?: false
        restoreRaw = savedInstanceState?.getString("raw").orEmpty()
        val root = column().apply { setBackgroundColor(style.background) }
        val toolbar = Toolbar(this).apply {
            title = "${keys.title}键盘"
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

        body.addView(text(if (keys === Keyboard14Keys) "字母两两成对，按键更大" else "更宽的按键，更轻松地输入",
            ShapeUiStyle.TITLE, strong = true))
        val settings = card()
        enabled = SwitchCompat(this).apply {
            text = "在键盘选择中显示${keys.title}"
            textSize = ShapeUiStyle.HEADING
            setTextColor(style.primary)
            minHeight = style.dp(ShapeUiStyle.BUTTON)
            isChecked = Keyboard18Store.visible(Keyboard18Store.snapshot(this@Keyboard18Activity), keys)
            setOnCheckedChangeListener { _, checked ->
                runCatching { Keyboard18Store.setVisible(this@Keyboard18Activity, checked, keys) }
                    .onSuccess { pendingRestart = true; performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); updateStatus() }
                    .onFailure { Toast.makeText(this@Keyboard18Activity, it.message, Toast.LENGTH_LONG).show()
                        setOnCheckedChangeListener(null); isChecked = !checked; recreate() }
            }
        }
        settings.addView(enabled, LinearLayout.LayoutParams(-1, -2))
        val initialSettings = Keyboard18Store.snapshot(this)
        val doublePinyin = SwitchCompat(this).apply {
            text = "${keys.title}双拼"
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
            InfoSheet.choose(this, "${keys.title}双拼方案", schemes.map { it.title }, schemes.indexOf(selected)) { index ->
                saveShuangpin(doublePinyin.isChecked, schemes[index])
            }
        }
        settings.addView(schemeButton, spaced())
        body.addView(settings, spaced())

        body.addView(text("试试${keys.title}", ShapeUiStyle.HEADING, strong = true), spaced())
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
        composing = text("", ShapeUiStyle.BODY).apply { visibility = View.GONE }
        preview.addView(composing, spaced())
        candidateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        preview.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false
            addView(candidateRow, android.widget.FrameLayout.LayoutParams(-2, -2)) }, LinearLayout.LayoutParams(-1, -2))
        keypad = column()
        preview.addView(keypad, LinearLayout.LayoutParams(-1, -2))
        buildKeypad()
        progress = text("正在准备离线词库…", ShapeUiStyle.CAPTION, secondary = true)
        preview.addView(progress, spaced())
        preview.addView(button("清空预览") { engine?.clear(); output.text.clear(); showCandidates() }, spaced())
        body.addView(preview, spaced())
        body.addView(button("重新读取词库") { prepareDictionary() }, spaced())

        val footer = column().apply { setPadding(style.dp(ShapeUiStyle.LG), style.dp(ShapeUiStyle.SM),
            style.dp(ShapeUiStyle.LG), style.dp(ShapeUiStyle.SM)); setBackgroundColor(style.surface) }
        restartButton = button("重启输入法并应用", accent = true) {
            ImeRestarter.restart(this) { if (!isDestroyed && !isFinishing) updateStatus() }
        }
        footer.addView(restartButton, LinearLayout.LayoutParams(-1, -2))
        root.addView(footer)
        setContentView(root)
        SettingsWindow.apply(window, root, topBar = toolbar, bottomBar = footer)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        labels(); updateStatus(); prepareDictionary()
    }
    override fun onResume() { super.onResume(); if (::restartButton.isInitialized) updateStatus() }
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
        composing.visibility = View.GONE
        worker.execute {
            val loaded = runCatching { Keyboard18Store.dictionaryFile(this).inputStream()
                .use { Keyboard18Dictionary.read(it, selectedScheme, keys) } }
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
        restartButton.text = if (pendingRestart) "重启并应用更改" else "重启输入法并应用"
    }
    private fun buildKeypad() {
        keypad.removeAllViews()
        previewControls.clear()
        letterButtons.clear(); englishButtons.clear()
        // 14-key Chinese has no shift key, so a shift carried over from English is released.
        if (!english && keys.separatorKey) uppercase = false
        if (english) {
            // English mirrors the host: a plain 26-key QWERTY instead of the 18-key grid.
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEachIndexed { index, letters ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
                if (index == 2) {
                    shiftButton = keyButton("⇧") { uppercase = !uppercase; labels() }
                    row.addView(shiftButton, keyParams(Keyboard18Style.CONTROL_WEIGHT))
                }
                letters.forEach { letter ->
                    val button = keyButton(letter.toString()) {
                        flush()
                        insert((if (uppercase) letter.uppercaseChar() else letter).toString())
                    }
                    button.contentDescription = "${letter.uppercaseChar()}键"
                    (button as ShortcutSwipeButton).apply {
                        shortcut = ShortcutMappings.load(this@Keyboard18Activity, ShortcutKeyboard.QWERTY)[letter.toString()]
                        shortcutsEnabled = { ConfigManager.getLocalPrefs(this@Keyboard18Activity)
                            .getBoolean(ConfigManager.KEY_KEYBOARD_SHORTCUTS, false) }
                        executeShortcut = { this@Keyboard18Activity.shortcut(it) }
                    }
                    englishButtons[letter] = button
                    row.addView(button, keyParams(1f))
                }
                if (index == 2) {
                    val delete = keyButton("⌫") { deletePreview() }
                    delete.contentDescription = "退格，长按清空预览"
                    delete.setOnLongClickListener { engine?.clear(); output.text.clear(); showCandidates(); true }
                    row.addView(delete, keyParams(Keyboard18Style.CONTROL_WEIGHT))
                }
                keypad.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        } else {
            keys.rows.forEachIndexed { rowIndex, groups ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
                if (rowIndex == 2 && keys.separatorKey) {
                    val separator = keyButton("'") { separator() }
                    separator.contentDescription = "分隔键，划分音节"
                    row.addView(separator, keyParams(keys.controlWeight))
                    row.addView(android.widget.Space(this), sideGap())
                } else if (rowIndex == 2) {
                    shiftButton = keyButton("⇧") { flush(); uppercase = !uppercase; labels() }
                    row.addView(shiftButton, keyParams(keys.controlWeight))
                }
                groups.forEach { group ->
                    val button = keyButton(group) {
                        val decoder = engine ?: return@keyButton
                        if (uppercase) { flush(); insert(group.first().uppercaseChar().toString()) }
                        else showResult(decoder.input(keys.code(group).toChar()))
                    }
                    button.contentDescription = "${group.uppercase()}键，长按选择字母和符号；下滑快捷操作由首页开关控制"
                    (button as ShortcutSwipeButton).apply {
                        shortcut = ShortcutMappings.load(this@Keyboard18Activity,
                            if (keys === Keyboard14Keys) ShortcutKeyboard.KEYBOARD14 else ShortcutKeyboard.KEYBOARD18)[group]
                        shortcutsEnabled = { ConfigManager.getLocalPrefs(this@Keyboard18Activity)
                            .getBoolean(ConfigManager.KEY_KEYBOARD_SHORTCUTS, false) }
                        executeShortcut = { this@Keyboard18Activity.shortcut(it) }
                    }
                    button.chooseOnLongPress { popup(group) }
                    letterButtons[group] = button
                    row.addView(button, keyParams(Keyboard18Style.weight(group)))
                }
                if (rowIndex == 2 && keys.separatorKey) row.addView(android.widget.Space(this), sideGap())
                if (rowIndex == 2) {
                    val delete = keyButton("⌫") { deletePreview() }
                    delete.contentDescription = "退格，长按清空预览"
                    delete.setOnLongClickListener { engine?.clear(); output.text.clear(); showCandidates(); true }
                    row.addView(delete, keyParams(keys.controlWeight))
                }
                keypad.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(keyButton("符") { symbols() }, keyParams(1f))
        bottom.addView(keyButton("123") { choices("数字", (0..9).map(Int::toString)) { literal(it) } }, keyParams(1f))
        bottom.addView(keyButton("，") { literal(if (english) "," else "，") }, keyParams(0.7f))
        bottom.addView(keyButton("空格") { val decoder = engine
            if (decoder != null && decoder.raw.isNotEmpty()) showResult(decoder.input(' ')) else insert(" ") }, keyParams(2.5f))
        bottom.addView(keyButton("。") { literal(if (english) "." else "。") }, keyParams(0.7f))
        languageButton = keyButton(if (english) "英" else "中") { flush(); english = !english; buildKeypad(); showCandidates() }
        bottom.addView(languageButton, keyParams(1f))
        bottom.addView(keyButton("↵", accent = true) { flush(); insert("\n") }, keyParams(1f))
        keypad.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        labels()
    }
    private fun deletePreview() {
        val decoder = engine
        if (decoder != null && decoder.raw.isNotEmpty()) showResult(decoder.backspace()) else {
            val start = output.selectionStart.coerceAtLeast(0)
            val end = output.selectionEnd.coerceAtLeast(start)
            if (end > start) output.text.delete(start, end)
            else if (start > 0) output.text.delete(Character.offsetByCodePoints(output.text, start, -1), start)
        }
    }
    private fun labels() {
        letterButtons.forEach { (group, view) ->
            val hint = keys.hint(group)
            view.text = SpannableString(hint + "\n" + Keyboard18Style.label(group, true)).apply {
                setSpan(RelativeSizeSpan(Keyboard18Style.PREVIEW_HINT_SCALE), 0, hint.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(style.secondary), 0, hint.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        englishButtons.forEach { (letter, view) ->
            view.text = (if (uppercase) letter.uppercaseChar() else letter).toString()
        }
        languageButton.text = if (english) "英" else "中"
        if (::shiftButton.isInitialized) shiftButton.text = if (uppercase) "⇪" else "⇧"
    }
    private fun showResult(result: io.mo.xatype.keyboard18.Pinyin18Result) { result.commit?.let(::insert); showCandidates() }
    private fun showCandidates() {
        val decoder = engine
        composing.text = decoder?.display.orEmpty()
        composing.visibility = if (composing.text.isEmpty()) View.GONE else View.VISIBLE
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
        val hint = keys.hint(group)
        val values = group.map { if (uppercase) it.uppercaseChar().toString() else it.toString() } +
            listOf(hint).filter { it.length == 1 } +
            if (!english && !uppercase && group == keys.semicolonGroup && engine?.scheme?.usesSemicolon == true) listOf(";") else emptyList()
        choices("${group.uppercase()}键", values) { selected ->
            if (!english && !uppercase && selected.length == 1 && (selected[0] in 'a'..'z' ||
                    (selected == ";" && engine?.raw?.isNotEmpty() == true && engine?.scheme?.usesSemicolon == true)))
                engine?.input(selected.single())?.let(::showResult) else literal(selected)
        }
    }
    private fun separator() {
        val decoder = engine ?: return
        // As on the host's segmentation key, the separator only splits syllables while composing.
        if (!english && decoder.raw.isNotEmpty()) showResult(decoder.input('\''))
    }
    private fun shortcut(action: String) {
        flush()
        when (action) {
            "全选" -> output.selectAll()
            "复制", "剪切" -> {
                if (output.selectionStart == output.selectionEnd) output.selectAll()
                output.onTextContextMenuItem(if (action == "复制") android.R.id.copy else android.R.id.cut)
            }
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
    /** Mirrors the native 26-key side gap that sets the 14-key controls apart from the letters. */
    private fun sideGap() = LinearLayout.LayoutParams(style.dp(Keyboard18Style.PREVIEW_SIDE_GAP), 1)
    private fun spaced() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = style.dp(ShapeUiStyle.MD) }
    companion object {
        private const val PREVIEW_OUTPUT_HEIGHT = 80
        const val EXTRA_LAYOUT = "layout"
        const val LAYOUT_14 = "14"
    }
}
