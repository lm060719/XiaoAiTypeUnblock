package io.mo.xatype.ui

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.mo.xatype.R
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.keyboard.PersonalDictionary

/** Shortcuts and phrases are the content; instructions stay out of the list. */
class PersonalDictionaryActivity : AppCompatActivity() {
    private lateinit var kit: SettingsKit
    private val prefs by lazy { ConfigManager.getLocalPrefs(this) }
    private var dictionary = PersonalDictionary.EMPTY
    private var query = ""
    private lateinit var list: RecyclerView
    private lateinit var empty: android.widget.TextView
    private val rows = Rows()
    private var dialog: AlertDialog? = null
    private var editing: PersonalDictionary.Entry? = null
    private var codeField: AppCompatEditText? = null
    private var phraseField: AppCompatEditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kit = SettingsKit(this)
        dictionary = PersonalDictionary.decode(prefs.getString(ConfigManager.KEY_PERSONAL_DICTIONARY_ENTRIES, null))
        query = savedInstanceState?.getString("query").orEmpty()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(kit.color(R.color.background_light))
        }
        val toolbar = Toolbar(this).apply {
            title = "个人字典"
            setTitleTextColor(kit.color(R.color.text_primary))
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationIcon?.setTint(kit.color(R.color.text_primary))
            setNavigationContentDescription("返回")
            setNavigationOnClickListener { finish() }
            menu.add("重启").apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                setOnMenuItemClickListener { ImeRestarter.restart(this@PersonalDictionaryActivity); true }
            }
            menu.add("添加").apply {
                setIcon(android.R.drawable.ic_input_add)
                icon?.setTint(kit.color(R.color.primary))
                setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                setOnMenuItemClickListener { edit(null); true }
            }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, kit.dp(56)))
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(16), kit.dp(8), kit.dp(16), kit.dp(8))
        }
        kit.group(controls, null, listOf(kit.switchRow("启用个人字典", "26 键全拼 · 重启输入法生效",
            prefs.getBoolean(ConfigManager.KEY_PERSONAL_DICTIONARY, false)) { enabled ->
            prefs.edit().putBoolean(ConfigManager.KEY_PERSONAL_DICTIONARY, enabled).apply()
        }.view))
        controls.addView(field("搜索词条", 0).apply {
            setSingleLine()
            setText(query)
            doAfterTextChanged { query = it.toString(); refresh() }
        }, LinearLayout.LayoutParams(-1, kit.dp(56)).apply { topMargin = kit.dp(8) })
        root.addView(controls)
        val content = FrameLayout(this)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PersonalDictionaryActivity)
            adapter = rows
            setPadding(kit.dp(16), 0, kit.dp(16), kit.dp(16))
            clipToPadding = false
        }
        empty = kit.label("添加词条", 16f, R.color.text_secondary).apply { gravity = Gravity.CENTER }
        content.addView(list, FrameLayout.LayoutParams(-1, -1))
        content.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        SettingsWindow.apply(window, root, topBar = toolbar)
        refresh()
        if (savedInstanceState?.getBoolean("editing") == true) {
            val originalCode = savedInstanceState.getString("original_code")
            val originalPhrase = savedInstanceState.getString("original_phrase")
            val original = dictionary.entries.find { it.shortcut == originalCode && it.phrase == originalPhrase }
            edit(original, savedInstanceState.getString("draft_code"), savedInstanceState.getString("draft_phrase"))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", query)
        if (dialog?.isShowing == true) {
            outState.putBoolean("editing", true)
            outState.putString("original_code", editing?.shortcut)
            outState.putString("original_phrase", editing?.phrase)
            outState.putString("draft_code", codeField?.text.toString())
            outState.putString("draft_phrase", phraseField?.text.toString())
        }
        super.onSaveInstanceState(outState)
    }

    private fun field(hintText: String, limit: Int) = AppCompatEditText(this).apply {
        hint = hintText
        textSize = 16f
        setTextColor(kit.color(R.color.text_primary))
        setHintTextColor(kit.color(R.color.text_secondary))
        if (limit > 0) filters = arrayOf(InputFilter.LengthFilter(limit))
    }

    private fun refresh() {
        if (!::list.isInitialized) return
        val matches = dictionary.entries.filter {
            it.shortcut.contains(query.trim(), ignoreCase = true) || it.phrase.contains(query.trim(), ignoreCase = true)
        }
        rows.submitList(matches)
        empty.text = if (dictionary.entries.isEmpty()) "添加词条" else "没有匹配的词条"
        empty.visibility = if (matches.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun save(entries: List<PersonalDictionary.Entry>): Boolean = try {
        val next = PersonalDictionary.of(entries)
        check(prefs.edit().putString(ConfigManager.KEY_PERSONAL_DICTIONARY_ENTRIES, next.encode()).commit()) {
            "保存失败，请重试"
        }
        dictionary = next
        refresh()
        true
    } catch (failure: Exception) {
        Toast.makeText(this, failure.message ?: "保存失败", Toast.LENGTH_SHORT).show()
        false
    }

    private fun edit(original: PersonalDictionary.Entry?, draftCode: String? = null, draftPhrase: String? = null) {
        if (dialog?.isShowing == true) return
        editing = original
        val code = field("快捷码", PersonalDictionary.MAX_SHORTCUT).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine()
            setText(draftCode ?: original?.shortcut.orEmpty())
        }
        val phrase = field("短语", PersonalDictionary.MAX_PHRASE).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            setText(draftPhrase ?: original?.phrase.orEmpty())
        }
        codeField = code; phraseField = phrase
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(24), kit.dp(8), kit.dp(24), kit.dp(8))
            addView(code, LinearLayout.LayoutParams(-1, kit.dp(56)))
            addView(phrase, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(8) })
        }
        val builder = AlertDialog.Builder(this).setTitle(if (original == null) "添加词条" else "编辑词条")
            .setView(fields).setPositiveButton("保存", null).setNegativeButton("取消", null)
        if (original != null) builder.setNeutralButton("删除") { _, _ -> save(dictionary.entries - original) }
        dialog = builder.create().also { current ->
            current.setOnShowListener {
                current.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val entry = try { PersonalDictionary.entry(code.text.toString(), phrase.text.toString()) }
                    catch (failure: IllegalArgumentException) {
                        val target = if (failure.message?.startsWith("快捷码") == true) code else phrase
                        target.error = failure.message; target.requestFocus()
                        return@setOnClickListener
                    }
                    val entries = dictionary.entries.toMutableList()
                    val index = entries.indexOf(original)
                    if (index >= 0) entries[index] = entry else entries.add(entry)
                    if (save(entries)) current.dismiss()
                }
            }
            current.show()
        }
    }

    private inner class Rows : ListAdapter<PersonalDictionary.Entry, Holder>(object : DiffUtil.ItemCallback<PersonalDictionary.Entry>() {
        override fun areItemsTheSame(oldItem: PersonalDictionary.Entry, newItem: PersonalDictionary.Entry) = oldItem == newItem
        override fun areContentsTheSame(oldItem: PersonalDictionary.Entry, newItem: PersonalDictionary.Entry) = oldItem == newItem
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = kit.row().apply { setBackgroundResource(R.drawable.bg_card) }
            val code = kit.label("", 15f, R.color.primary, bold = true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            val phrase = kit.label("", 16f, R.color.text_primary).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            }
            row.addView(code, LinearLayout.LayoutParams(kit.dp(88), -2).apply { marginEnd = kit.dp(12) })
            row.addView(phrase, LinearLayout.LayoutParams(0, -2, 1f))
            row.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(6) }
            return Holder(row, code, phrase)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = getItem(position)
            holder.code.text = entry.shortcut
            holder.phrase.text = entry.phrase
            holder.itemView.contentDescription = "${entry.shortcut}，${entry.phrase}"
            holder.itemView.setOnClickListener { edit(entry) }
            holder.itemView.setOnLongClickListener {
                InfoSheet.choose(this@PersonalDictionaryActivity, entry.shortcut, listOf("置顶", "删除"), -1) { action ->
                    if (action == 0) save(listOf(entry) + (dictionary.entries - entry))
                    else save(dictionary.entries - entry)
                }
                true
            }
        }
    }

    private class Holder(view: View, val code: android.widget.TextView, val phrase: android.widget.TextView) :
        RecyclerView.ViewHolder(view)
}
