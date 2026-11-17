package io.mo.xatype.ui

import android.app.Dialog
import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.widget.AppCompatCheckBox
import io.mo.xatype.R
import io.mo.xatype.keyboard.ShortcutKeyChord

internal object ShortcutChordEditor {
    fun show(context: Context, stored: String?, onSave: (String) -> Unit) {
        val kit = SettingsKit(context)
        val initial = stored?.let(ShortcutKeyChord::parse) ?: ShortcutKeyChord("Z", ctrl = true)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(kit.resourceLabel("下滑一次发送完整组合键。目标应用需要支持对应操作；Ctrl、Alt、Shift 会在本次操作结束时释放。",
            R.dimen.settings_description_size, R.color.text_secondary))
        val preview = kit.resourceLabel(initial.label, R.dimen.settings_chord_preview_size, R.color.primary, bold = true)
        val names = ShortcutKeyChord.keys.keys.toList()
        val key = Spinner(context).apply {
            contentDescription = "组合键主键"
            minimumHeight = kit.dimen(R.dimen.settings_touch_height)
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, names).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(names.indexOf(initial.key))
        }
        val modifiers = listOf("Ctrl" to initial.ctrl, "Alt" to initial.alt, "Shift" to initial.shift).map { (name, checked) ->
            AppCompatCheckBox(context).apply {
                text = name
                setTextColor(kit.color(R.color.text_primary))
                minHeight = kit.dimen(R.dimen.settings_touch_height)
                isChecked = checked
                body.addView(this)
            }
        }
        fun chord() = ShortcutKeyChord(names[key.selectedItemPosition], modifiers[0].isChecked,
            modifiers[1].isChecked, modifiers[2].isChecked)
        modifiers.forEach { it.setOnCheckedChangeListener { _, _ -> preview.text = chord().label } }
        key.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                preview.text = chord().label
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        body.addView(kit.resourceLabel("主键", R.dimen.settings_description_size, R.color.text_secondary))
        body.addView(key, LinearLayout.LayoutParams(-1, -2))
        body.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dimen(R.dimen.space_md) })
        lateinit var dialog: Dialog
        dialog = InfoSheet.withAction(context, "自定义组合键", body, "保存", "取消") { button ->
            onSave(chord().encoded)
            SettingsKit.confirmHaptic(button)
            dialog.dismiss()
        }
    }
}
