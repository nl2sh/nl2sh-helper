package ernest.nl2sh.helper

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Native adaptation of the shared nl2sh semantic palette; see UI_DESIGN.md. */
internal class HelperUi(private val context: Context) {
    val background = color(R.color.ui_background)
    val surface = color(R.color.ui_background_alt)
    val border = color(R.color.ui_border)
    val focus = color(R.color.ui_border_focus)
    val primary = color(R.color.ui_text_primary)
    val secondary = color(R.color.ui_text_secondary)
    val accent = color(R.color.ui_accent)
    val cyan = color(R.color.ui_cyan)
    val success = color(R.color.ui_success)
    val warning = color(R.color.ui_warning)
    val error = color(R.color.ui_error)

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    fun margin(top: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    fun label(text: String, size: Int = 14, color: Int = primary, top: Int = 0,
              bold: Boolean = false): TextView = TextView(context).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.START
        setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = margin(top)
    }

    fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = shape(surface, border)
    }

    fun input(view: EditText) = view.apply {
        textSize = 16f
        setTextColor(primary)
        setHintTextColor(secondary)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minHeight = dp(48)
        minimumHeight = dp(48)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(surface, focus, 2))
            addState(intArrayOf(), shape(surface, border))
        }
        backgroundTintList = null
        layoutParams = margin(6)
    }

    fun button(primaryAction: Boolean = false, destructive: Boolean = false): Button = Button(context).apply {
        textSize = 14f
        isAllCaps = false
        gravity = Gravity.CENTER
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setTypeface(typeface, Typeface.BOLD)
        val activeColor = when {
            destructive -> this@HelperUi.error
            primaryAction -> accent
            else -> primary
        }
        setTextColor(ColorStateList(arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_selected),
            intArrayOf()
        ), intArrayOf(secondary, accent, activeColor)))
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(surface, border))
            addState(intArrayOf(android.R.attr.state_selected), shape(this@HelperUi.background, focus, 2))
            addState(intArrayOf(android.R.attr.state_focused), shape(surface, focus, 2))
            addState(intArrayOf(), shape(surface, if (primaryAction) focus else border))
        }
        background = RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 40)), states,
            shape(context.getColor(android.R.color.white), null))
        backgroundTintList = null
        stateListAnimator = null
    }

    fun statusColor(tone: StatusTone): Int = when (tone) {
        StatusTone.IDLE -> secondary
        StatusTone.WORKING -> accent
        StatusTone.SUCCESS -> success
        StatusTone.WARNING -> warning
        StatusTone.ERROR -> error
    }

    fun statusBackground(tone: StatusTone) = shape(surface, statusColor(tone))

    private fun shape(fill: Int, stroke: Int?, width: Int = 1): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(8).toFloat()
        if (stroke != null) setStroke(dp(width), stroke)
    }

    private fun color(id: Int) = context.getColor(id)
    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00ffffff) or (alpha shl 24)
}

/** Display semantics are set by actual operation outcomes, never inferred from message text. */
internal enum class StatusTone(val label: String) {
    IDLE("就绪"), WORKING("处理中"), SUCCESS("已启动"), WARNING("需注意"), ERROR("失败")
}
