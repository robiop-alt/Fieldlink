package no.fieldlink.uploader

import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button

/** Compact labels with room for font metrics on DJI's Android skin. */
object OffsetButtons {
    fun compact(button: Button) {
        val density=button.resources.displayMetrics.density
        fun dp(value:Int)=(value*density).toInt()
        button.isAllCaps=false
        button.includeFontPadding=false
        button.letterSpacing=0f
        button.textSize=10f
        button.minHeight=dp(32)
        button.minimumHeight=dp(32)
        button.minWidth=0
        button.minimumWidth=0
        button.setSingleLine(false)
        button.maxLines=2
        button.ellipsize=null
        button.gravity=Gravity.CENTER
        button.setLineSpacing(0f,1f)
        button.setPadding(dp(8),dp(4),dp(8),dp(4))
        if(Build.VERSION.SDK_INT>=26) {
            button.setAutoSizeTextTypeUniformWithConfiguration(9,10,1,TypedValue.COMPLEX_UNIT_SP)
        }
    }
}
