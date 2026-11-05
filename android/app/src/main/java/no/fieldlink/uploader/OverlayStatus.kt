package no.fieldlink.uploader

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Optional, draggable, minimizable system overlay; no DJI Pilot SDK dependency. */
class OverlayStatus(private val ctx: Context) {
    private val wm=ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: LinearLayout?=null
    private var detail: TextView?=null
    private var collapsed=false
    private var last="Ready"
    private val prefs=ctx.getSharedPreferences("fieldlink",Context.MODE_PRIVATE)
    private var params: WindowManager.LayoutParams?=null
    fun show() {
        if (view!=null || Build.VERSION.SDK_INT<23 || !Settings.canDrawOverlays(ctx)) return
        val root=LinearLayout(ctx).apply {
            orientation=LinearLayout.VERTICAL
            val opacity=prefs.getInt("overlay_opacity",70).coerceIn(20,100)
            background=GradientDrawable().apply {
                setColor(Color.argb((opacity*255/100),29,34,37))
                cornerRadius=16f
            }
            setPadding(20,12,20,12)
            elevation=10f
        }
        val showStatus=prefs.getBoolean("overlay_show_status",true)
        val showProject=prefs.getBoolean("overlay_show_project",false)
        val projectText=TextView(ctx).apply {
            text="Prosjekt: "+(prefs.getString("project","") ?: "").take(12)
            setTextColor(Color.LTGRAY);textSize=11f
            visibility=if(showProject) View.VISIBLE else View.GONE
        }
        val header=TextView(ctx).apply {
            text="●  OFFSET     −"; setTextColor(Color.rgb(183,243,77)); textSize=15f
            setTypeface(null,Typeface.BOLD)
            setOnClickListener { collapsed=!collapsed; detail?.visibility=if(collapsed) View.GONE else View.VISIBLE; projectText.visibility=if(collapsed || !showProject) View.GONE else View.VISIBLE; text=if(collapsed) "●  OFFSET  +" else "●  OFFSET     −" }
        }
        val sub=TextView(ctx).apply { text=last; setTextColor(Color.WHITE); textSize=12f }
        detail=if(showStatus) sub else null
        root.addView(header)
        if(showStatus) root.addView(sub)
        if(showProject) root.addView(projectText)
        val p=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
            if(Build.VERSION.SDK_INT>=26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.END; x=24; y=120 }
        params=p
        var startX=0; var startY=0; var touchX=0f; var touchY=0f
        root.setOnTouchListener { _,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX=p.x;startY=p.y;touchX=event.rawX;touchY=event.rawY;false }
                MotionEvent.ACTION_MOVE -> {
                    if(kotlin.math.abs(event.rawX-touchX)>12 || kotlin.math.abs(event.rawY-touchY)>12) {
                        p.x=(startX-(event.rawX-touchX)).toInt().coerceAtLeast(0)
                        p.y=(startY+(event.rawY-touchY)).toInt().coerceAtLeast(0)
                        wm.updateViewLayout(root,p);true
                    } else false
                }
                else -> false
            }
        }
        wm.addView(root,p);view=root
    }
    fun update(message:String) { last=message; detail?.post { detail?.text=message } }
    fun hide() { view?.let { wm.removeView(it) }; view=null; detail=null }
}
