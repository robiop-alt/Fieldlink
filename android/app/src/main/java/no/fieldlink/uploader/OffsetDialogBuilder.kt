package no.fieldlink.uploader

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

/** One visual style for every app-owned alert, including DJI's Android skin. */
class OffsetDialogBuilder(context: Context) :
    AlertDialog.Builder(ContextThemeWrapper(context, R.style.OffsetDialogTheme)) {

    override fun create(): AlertDialog = super.create().also { dialog ->
        val window = dialog.window ?: return@also
        window.setBackgroundDrawable(context.getDrawable(R.drawable.offset_dialog_background))
        // An attach listener does not replace callers' validation/progress OnShow listeners.
        window.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                styleContents(dialog)
            }
            override fun onViewDetachedFromWindow(view: View) = Unit
        })
    }

    override fun show(): AlertDialog = create().also { it.show() }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun surface(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(8).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun styleButton(button: Button, primary: Boolean) {
        val accent = Color.rgb(183, 243, 77)
        button.isAllCaps = false
        button.textSize = 13f
        button.setTextColor(if (primary) Color.rgb(17, 20, 22) else Color.rgb(244, 247, 246))
        button.minHeight = dp(44)
        button.minimumHeight = dp(44)
        button.setPadding(dp(14), dp(6), dp(14), dp(6))
        button.elevation = 0f
        button.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), surface(
                if (primary) Color.rgb(159, 215, 65) else Color.rgb(57, 67, 70),
                if (primary) accent else Color.rgb(83, 98, 100)))
            addState(intArrayOf(), surface(
                if (primary) accent else Color.rgb(43, 50, 52),
                if (primary) accent else Color.rgb(70, 80, 81)))
        }
        (button.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.setMargins(dp(4), dp(6), dp(4), dp(6))
            button.layoutParams = it
        }
    }

    private fun styleContents(dialog: AlertDialog) {
        val window = dialog.window ?: return
        // Clear vendor-provided rectangular panels without touching custom content or QR images.
        for (name in listOf("parentPanel", "topPanel", "contentPanel", "customPanel", "buttonPanel")) {
            val id = context.resources.getIdentifier(name, "id", "android")
            if (id != 0) dialog.findViewById<View>(id)?.setBackgroundColor(Color.TRANSPARENT)
        }
        val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
        if (titleId != 0) dialog.findViewById<TextView>(titleId)?.apply {
            textSize = 18f
            setTextColor(Color.rgb(244, 247, 246))
            setTypeface(typeface, Typeface.BOLD)
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            textSize = 14f
            setTextColor(Color.rgb(204, 216, 215))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.let { styleButton(it, which == AlertDialog.BUTTON_POSITIVE) }
        }
        window.decorView.clipToOutline = true
        val width = minOf(dp(560), context.resources.displayMetrics.widthPixels - dp(32))
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
