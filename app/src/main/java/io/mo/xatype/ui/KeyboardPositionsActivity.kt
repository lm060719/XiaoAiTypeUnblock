package io.mo.xatype.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.edit
import androidx.core.widget.NestedScrollView
import io.mo.xatype.R
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.keyboard.KeyboardPositions
import io.mo.xatype.keyboard.PositionKey
import java.util.Collections

class KeyboardPositionsActivity : AppCompatActivity() {
    private val prefs by lazy { ConfigManager.getLocalPrefs(this) }
    private lateinit var kit: SettingsKit
    private lateinit var profileValue: TextView
    private lateinit var instructions: TextView
    private lateinit var nineNote: TextView
    private lateinit var keyboard: LinearLayout
    private lateinit var reset: View
    private var profile = KeyboardPositions.QWERTY
    private var selectedSlot = -1
    private var order = mutableListOf<Int>()
    private data class PreviewKey(val button: Button, val hint: TextView?)
    private val buttons = mutableListOf<PreviewKey>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kit = SettingsKit(this)
        profile = KeyboardPositions.entries.firstOrNull { it.name == savedInstanceState?.getString(STATE_PROFILE) }
            ?: KeyboardPositions.QWERTY
        selectedSlot = savedInstanceState?.getInt(STATE_SLOT, -1) ?: -1

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(kit.color(R.color.background_light))
        }
        val toolbar = Toolbar(this).apply {
            setTitle(R.string.key_positions_title)
            val color = kit.color(R.color.text_primary)
            setTitleTextColor(color)
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationIcon?.setTint(color)
            setNavigationContentDescription(R.string.key_positions_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, kit.dp(ShapeUiStyle.BUTTON)))

        val side = kit.dimen(R.dimen.space_lg)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, kit.dimen(R.dimen.space_sm), side, kit.dimen(R.dimen.space_xl))
        }
        root.addView(NestedScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))

        profileValue = kit.rowValue("")
        kit.group(body, null, listOf(kit.navRow(getString(R.string.key_positions_layout), value = profileValue) {
            val profiles = KeyboardPositions.entries
            InfoSheet.choose(this, getString(R.string.key_positions_layout), profiles.map { it.title }, profile.ordinal) {
                profile = profiles[it]
                selectedSlot = -1
                showLayout()
            }
        }))
        instructions = kit.resourceLabel("", R.dimen.settings_title_size, R.color.text_secondary).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(instructions, spaced())
        keyboard = kit.card().apply {
            val padding = kit.dimen(R.dimen.space_xs)
            setPadding(padding, padding, padding, padding)
        }
        body.addView(keyboard, spaced(R.dimen.space_sm))
        nineNote = kit.resourceLabel(getString(R.string.key_positions_nine_note),
            R.dimen.settings_description_size, R.color.text_secondary)
        body.addView(nineNote, spaced(R.dimen.space_sm))
        reset = kit.navRow(getString(R.string.key_positions_reset), R.drawable.ic_shape_restore) {
            selectedSlot = -1
            order = profile.defaultOrder.toMutableList()
            prefs.edit { remove(profile.prefKey) }
            SettingsKit.confirmHaptic(reset)
            updateKeys()
        }
        kit.group(body, null, listOf(reset)).layoutParams = spaced()

        val footer = LinearLayout(this).apply {
            setPadding(side, kit.dimen(R.dimen.space_sm), side, kit.dimen(R.dimen.space_sm))
        }
        footer.addView(Button(this).apply {
            setText(R.string.key_positions_apply)
            isAllCaps = false
            setTextColor(kit.color(R.color.on_primary))
            setBackgroundResource(R.drawable.bg_button_primary)
            stateListAnimator = null
            minHeight = kit.dimen(R.dimen.settings_touch_height)
            setOnClickListener { button ->
                button.isEnabled = false
                ImeRestarter.restart(this@KeyboardPositionsActivity) {
                    if (!isDestroyed) button.isEnabled = true
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(footer)
        setContentView(root)
        SettingsWindow.apply(window, root, topBar = toolbar, bottomBar = footer)
        showLayout()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PROFILE, profile.name)
        outState.putInt(STATE_SLOT, selectedSlot)
        super.onSaveInstanceState(outState)
    }

    private fun showLayout() {
        profileValue.text = profile.title
        nineNote.visibility = if (profile == KeyboardPositions.NINE) View.VISIBLE else View.GONE
        order = profile.decode(prefs.getString(profile.prefKey, null)).toMutableList()
        if (selectedSlot !in order.indices) selectedSlot = -1
        keyboard.removeAllViews()
        buttons.clear()
        val across = profile.rows.maxOf { rowWeight(it) }
        for (row in profile.rows) {
            val line = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                weightSum = if (profile == KeyboardPositions.QWERTY) across else rowWeight(row)
            }
            for (slot in row) {
                val button = if (slot == null) {
                    kit.rowValue("1").apply {
                        gravity = Gravity.CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                } else createPreviewKey(slot)
                val gap = kit.dimen(R.dimen.space_xs) / 2
                line.addView(button, LinearLayout.LayoutParams(0, -2, slot?.weight ?: 1f).apply {
                    setMargins(gap, gap, gap, gap)
                })
            }
            keyboard.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
        updateKeys()
    }

    private fun createPreviewKey(slot: PositionKey): View {
        val index = buttons.size
        val hint = slot.hint?.let {
            kit.resourceLabel(it, R.dimen.settings_description_size, R.color.text_secondary).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        val button = Button(this).apply {
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = kit.dimen(if (hint == null) R.dimen.settings_touch_height else R.dimen.key_positions_hinted_height)
            val hintSpace = hint?.let { resources.getDimensionPixelSize(R.dimen.settings_description_size) } ?: 0
            setPadding(0, hintSpace, 0, 0)
            stateListAnimator = null
            setBackgroundResource(R.drawable.bg_position_key)
            backgroundTintList = null
            setTextColor(kit.color(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.settings_title_size))
            setOnClickListener { select(index, it) }
        }
        buttons += PreviewKey(button, hint)
        if (hint == null) return button
        return FrameLayout(this).apply {
            addView(button, FrameLayout.LayoutParams(-1, -2))
            addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = kit.dimen(R.dimen.space_xs)
                marginEnd = kit.dimen(R.dimen.space_sm)
            })
        }
    }

    private fun select(slot: Int, view: View) {
        val first = selectedSlot
        selectedSlot = if (first < 0) slot else -1
        if (first >= 0 && first != slot) {
            Collections.swap(order, first, slot)
            prefs.edit {
                if (order == profile.defaultOrder) remove(profile.prefKey)
                else putString(profile.prefKey, order.joinToString(","))
            }
            SettingsKit.confirmHaptic(view)
        }
        updateKeys()
    }

    private fun updateKeys() {
        buttons.forEachIndexed { index, (button, hint) ->
            val key = profile.keys.getValue(order[index])
            button.text = key.label
            button.contentDescription = hint?.text?.let {
                getString(R.string.key_positions_key_with_hint, key.spokenLabel, it)
            } ?: key.spokenLabel
            button.isSelected = index == selectedSlot
        }
        instructions.text = order.getOrNull(selectedSlot)?.let { id ->
            getString(R.string.key_positions_selected, profile.keys.getValue(id).spokenLabel)
        } ?: getString(R.string.key_positions_instructions)
        reset.isEnabled = order != profile.defaultOrder
        reset.alpha = if (reset.isEnabled) 1f else DISABLED_ALPHA
    }

    private fun rowWeight(row: List<PositionKey?>) = row.sumOf { (it?.weight ?: 1f).toDouble() }.toFloat()

    private fun spaced(gap: Int = R.dimen.space_lg) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = kit.dimen(gap)
    }

    private companion object {
        const val STATE_PROFILE = "key_positions_profile"
        const val STATE_SLOT = "key_positions_slot"
        const val DISABLED_ALPHA = 0.5f
    }
}
