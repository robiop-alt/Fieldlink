package no.fieldlink.uploader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** A drawing layer only: never modifies playback or the underlying media. */
internal class FrameLineOverlay(context:Context,private val video:View):View(context) {
    private val prefs=context.getSharedPreferences("fieldlink",Context.MODE_PRIVATE)
    var format=prefs.getString("player_frame_format","off").orEmpty()
    var shade=prefs.getInt("player_frame_shade",75).takeIf { it in listOf(25,50,75,100) } ?: 75
    var outline=prefs.getString("player_frame_outline","off").orEmpty()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        isClickable=false
        isFocusable=false
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        video.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> invalidate() }
    }
    fun save() {
        prefs.edit().putString("player_frame_format",format).putInt("player_frame_shade",shade)
            .putString("player_frame_outline",outline).apply()
        invalidate()
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val ratio=format.toFloatOrNull() ?: return
        val source=FrameLineGeometry.Rect(video.left.toFloat(),video.top.toFloat(),video.right.toFloat(),video.bottom.toFloat())
        val frame=FrameLineGeometry.frame(source,ratio) ?: return
        paint.style=Paint.Style.FILL
        paint.color=Color.argb(shade*255/100,0,0,0)
        canvas.drawRect(source.left,source.top,source.right,frame.top,paint)
        canvas.drawRect(source.left,frame.bottom,source.right,source.bottom,paint)
        canvas.drawRect(source.left,frame.top,frame.left,frame.bottom,paint)
        canvas.drawRect(frame.right,frame.top,source.right,frame.bottom,paint)
        paint.color=when(outline) {
            "white" -> Color.WHITE
            "red" -> Color.rgb(255,83,83)
            "green" -> Color.rgb(120,202,84)
            else -> return
        }
        paint.style=Paint.Style.STROKE
        paint.strokeWidth=resources.displayMetrics.density
        val inset=paint.strokeWidth/2f
        canvas.drawRect(frame.left+inset,frame.top+inset,frame.right-inset,frame.bottom-inset,paint)
    }
}
