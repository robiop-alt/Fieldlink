package no.fieldlink.uploader

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.BaseAdapter
import android.widget.FrameLayout

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

    // Project choices are actions too: give each row the same button surface.
    override fun setItems(items: Array<out CharSequence>?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder {
        val labels = items?.toList().orEmpty()
        return super.setAdapter(object : BaseAdapter() {
            override fun getCount() = labels.size
            override fun getItem(position: Int) = labels[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val frame = (convertView as? FrameLayout) ?: FrameLayout(context).apply {
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    addView(TextView(context).apply {
                        textSize = 11f
                        setTextColor(Color.rgb(244, 247, 246))
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        includeFontPadding = false
                        setPadding(dp(12), dp(7), dp(12), dp(7))
                        minHeight = dp(36)
                        background = context.getDrawable(R.drawable.offset_button_background)
                        // The list owns clicks, focus and accessibility, not a nested button.
                        isDuplicateParentStateEnabled = true
                    }, FrameLayout.LayoutParams(-1, -2))
                }
                (frame.getChildAt(0) as TextView).text = labels[position]
                return frame
            }
        }, listener)
    }

    private fun styleButton(button: Button, primary: Boolean) {
        val quiet = button.text.toString().trim().lowercase() in setOf("close", "cancel", "back", "ok", "done")
        val emphasized = primary && !quiet
        OffsetButtons.compact(button)
        button.setTextColor(if (emphasized) Color.rgb(17, 20, 22) else Color.rgb(244, 247, 246))
        button.elevation = 0f
        button.stateListAnimator = null
        // DJI's framework tint otherwise flattens a custom drawable back to a text-button look.
        button.backgroundTintList = null
        button.background = context.getDrawable(if (emphasized)
            R.drawable.offset_button_primary else R.drawable.offset_button_background)
        (button.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            // DJI's native button bar can be shorter than the stock Android bar.
            // Vertical margins plus a 44dp minimum used to exceed that row.
            it.height = ViewGroup.LayoutParams.WRAP_CONTENT
            it.setMargins(dp(4), 0, dp(4), 0)
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
        // Let the existing native bar measure its compact buttons. Keep the same
        // Button instances so validation listeners and Android accessibility work.
        val panelId = context.resources.getIdentifier("buttonPanel", "id", "android")
        val buttonPanel = if (panelId != 0) dialog.findViewById<ViewGroup>(panelId) else null
        buttonPanel?.let { panel ->
            fun fitBar(group: ViewGroup) {
                group.minimumHeight = 0
                group.layoutParams?.let { params ->
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    group.layoutParams = params
                }
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    if (child is ViewGroup) fitBar(child)
                }
            }
            fitBar(panel)
            panel.requestLayout()
        }
        window.decorView.clipToOutline = true
        val width = minOf(dp(560), context.resources.displayMetrics.widthPixels - dp(32))
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
