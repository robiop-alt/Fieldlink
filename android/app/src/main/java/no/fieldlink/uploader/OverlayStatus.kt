package no.fieldlink.uploader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Touch-friendly side pill with optional expanded uploader status. */
class OverlayStatus(private val ctx: Context) {
    private val wm=ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs=ctx.getSharedPreferences("fieldlink",Context.MODE_PRIVATE)
    private val accent=Color.rgb(183,243,77)
    private val dark=Color.rgb(25,30,33)
    private var root:LinearLayout?=null
    private var header:TextView?=null
    private var details:TextView?=null
    private var projectLabel:TextView?=null
    private var metadataTap:TextView?=null
    private var modeLabel:TextView?=null
    private var waitingLabel:TextView?=null
    private var actionLabel:TextView?=null
    private var params:WindowManager.LayoutParams?=null
    private var expanded=false
    private var last="Ready"
    private val style get()=prefs.getString("overlay_style","minimal") ?: "minimal"
    private fun dp(n:Int)=(n*ctx.resources.displayMetrics.density+0.5f).toInt()
    private fun drawable():GradientDrawable=GradientDrawable().apply {
        setColor(Color.argb(prefs.getInt("overlay_opacity",78).coerceIn(20,100)*255/100,25,30,33))
        cornerRadius=dp(18).toFloat()
        setStroke(dp(1),Color.rgb(88,102,91))
    }
    private fun stateColor():Int=when {
        last.contains("failed",true)||last.contains("error",true)||
            last.contains("feil",true)||last.contains("mismatch",true)||
            last.contains("integrity",true) -> Color.rgb(255,104,106)
        last.contains("battery",true)||last.contains("network",true)||last.contains("venter",true)||
            last.contains("waiting",true)||last.contains("blocked",true)||
            last.contains("paused",true) -> Color.rgb(255,194,93)
        else -> accent
    }
    private fun shortText():String {
        val progress=if(last.contains("battery",true)) null else Regex("""(\d{1,3})\s*%""").find(last)?.groupValues?.get(1)
        return when {
            last.contains("battery",true) -> "▰"
            progress!=null -> "↑ $progress%"
            last.contains("compress",true) -> "⇣"
            last.contains("sender",true)||last.contains("upload",true)||
                last.contains("sending",true) -> "↑"
            last.contains("network",true)||last.contains("venter",true)||
                last.contains("waiting",true) -> "…"
            last.contains("failed",true)||last.contains("error",true)||
                last.contains("feil",true)||last.contains("integrity",true) -> "!"
            else -> "●"
        }
    }
    private fun readableStatus():String=when {
        last.contains("battery",true) -> "Low battery · Uploads paused"
        last.contains("compress",true) -> "Compressing · Low Data Mode"
        last.contains("network",true) || last.contains("nett",true) -> "Waiting for network"
        last.contains("integrity",true) || last.contains("mismatch",true) ->
            "File verification needs attention"
        last.contains("Monitoring",true) || last.contains("Overvåker",true) ->
            "Ready · Monitoring"
        else -> last
    }
    private fun paint() {
        val compact=style=="compact"
        header?.apply {
            text=when {
                expanded -> "OFFSET  −"
                compact -> "●  ${shortText()}"
                style=="classic" -> "●  OFFSET  +"
                else -> shortText()
            }
            setTextColor(stateColor())
            textSize=if(expanded) 13f else if(style=="minimal") 12f else 13f
            setPadding(dp(if(!expanded && style=="minimal") 7 else 12),dp(9),
                dp(if(!expanded && style=="minimal") 7 else 12),dp(9))
        }
        details?.apply {
            text=readableStatus()
            visibility=if(expanded || (style=="classic" && prefs.getBoolean("overlay_show_status",true))) View.VISIBLE else View.GONE
        }
        projectLabel?.visibility=if((expanded || style=="classic") && prefs.getBoolean("overlay_show_project",false)) View.VISIBLE else View.GONE
        val isManual=prefs.getString("upload_mode","auto")=="manual"
        modeLabel?.apply {
            text=if(isManual) "Manual mode" else "Auto upload"
            setTextColor(if(isManual) Color.rgb(255,190,103) else accent)
            visibility=if(expanded) View.VISIBLE else View.GONE
        }
        waitingLabel?.apply {
            val project=prefs.getString("project","").orEmpty()
            val waiting=try {
                if(project.isBlank()) 0 else ProjectLedger(ctx).use { ledger ->
                    val counts=ledger.counts(project)
                    // Manual queue is included in pending, so avoid double-counting.
                    counts.second
                }
            }catch(_:Exception) { 0 }
            text="$waiting clips waiting · ${if(isManual) "Only selected clips upload" else "Monitoring new clips"}"
            visibility=if(expanded) View.VISIBLE else View.GONE
        }
        actionLabel?.visibility=if(expanded) View.VISIBLE else View.GONE
        metadataTap?.visibility=if(prefs.getBoolean("metadata_reminder",true) && !prefs.getBoolean("metadata_capture_active",false)) View.VISIBLE else View.GONE
        root?.background=drawable()
        root?.let { view -> params?.let { p ->
            if(expanded) {
                // Make room for the expanded card before placing it against the edge.
                p.width=dp(272)
            } else p.width=WindowManager.LayoutParams.WRAP_CONTENT
            val screenWidth=ctx.resources.displayMetrics.widthPixels
            p.x=if(prefs.getString("overlay_side","right")=="left") 0
                else (screenWidth-if(expanded) dp(272) else dp(if(style=="minimal") 40 else 112)).coerceAtLeast(0)
            try {wm.updateViewLayout(view,p)}catch(_:Exception){}
        } }
    }
    fun show() {
        if(root!=null || Build.VERSION.SDK_INT<23 || !Settings.canDrawOverlays(ctx)) return
        val layout=LinearLayout(ctx).apply {
            orientation=LinearLayout.VERTICAL
            background=drawable()
            elevation=dp(7).toFloat()
        }
        val label=TextView(ctx).apply {
            gravity=Gravity.CENTER
            setTypeface(null,Typeface.BOLD)
        }
        val detail=TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize=12f
            setPadding(dp(12),0,dp(12),dp(10))
            maxWidth=dp(280)
        }
        val project=TextView(ctx).apply {
            setTextColor(Color.LTGRAY)
            text="PROJECT · "+(prefs.getString("project","") ?: "")
            textSize=11f
            setPadding(dp(12),0,dp(12),dp(10))
        }
        val mode=TextView(ctx).apply {
            textSize=13f
            setTypeface(null,Typeface.BOLD)
            setPadding(dp(12),dp(8),dp(12),dp(3))
        }
        val waiting=TextView(ctx).apply {
            textSize=12f
            setTextColor(Color.WHITE)
            setPadding(dp(12),0,dp(12),dp(7))
        }
        val action=TextView(ctx).apply {
            text="Open OFFSET  ›"
            textSize=13f
            setTypeface(null,Typeface.BOLD)
            setTextColor(accent)
            setPadding(dp(12),dp(8),dp(12),dp(12))
            setOnClickListener {
                try {
                    ctx.startActivity(Intent(ctx,MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }catch(_:Exception){}
            }
        }
        val metadata=TextView(ctx).apply {
            text="●  METADATA TAP"
            textSize=10f
            setTypeface(null,Typeface.BOLD)
            setTextColor(Color.rgb(255,183,86))
            gravity=Gravity.CENTER
            setPadding(dp(7),dp(7),dp(7),dp(7))
            setOnClickListener {
                try {
                    ctx.startActivity(Intent(ctx,MainActivity::class.java)
                        .putExtra("request_metadata_access",true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }catch(_:Exception){}
            }
        }
        layout.addView(label);layout.addView(metadata);layout.addView(mode);layout.addView(waiting)
        layout.addView(detail);layout.addView(project);layout.addView(action)
        val display=ctx.resources.displayMetrics
        val savedSide=prefs.getString("overlay_side","right")
        val p=WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity=Gravity.TOP or Gravity.START
            x=if(savedSide=="left") 0 else (display.widthPixels-dp(60)).coerceAtLeast(0)
            y=prefs.getInt("overlay_y",dp(140)).coerceIn(dp(50),(display.heightPixels-dp(80)).coerceAtLeast(dp(50)))
        }
        root=layout;header=label;details=detail;projectLabel=project
        metadataTap=metadata
        modeLabel=mode;waitingLabel=waiting;actionLabel=action;params=p
        expanded=style=="classic"
        var downX=0f;var downY=0f;var originX=0;var originY=0;var moved=false
        label.setOnTouchListener { _,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX=event.rawX;downY=event.rawY;originX=p.x;originY=p.y;moved=false;true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx=event.rawX-downX;val dy=event.rawY-downY
                    if(abs(dx)>dp(6) || abs(dy)>dp(6)) moved=true
                    if(moved) {
                        p.x=(originX+dx.toInt()).coerceIn(0,(display.widthPixels-layout.width).coerceAtLeast(0))
                        p.y=(originY+dy.toInt()).coerceIn(dp(30),(display.heightPixels-layout.height).coerceAtLeast(dp(30)))
                        try { wm.updateViewLayout(layout,p) }catch(_:Exception){}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if(moved) {
                        val left=p.x+layout.width/2<display.widthPixels/2
                        prefs.edit().putString("overlay_side",if(left) "left" else "right")
                            .putInt("overlay_y",p.y).apply()
                        p.x=if(left) 0 else (display.widthPixels-layout.width).coerceAtLeast(0)
                        try { wm.updateViewLayout(layout,p) }catch(_:Exception){}
                    } else {
                        expanded=!expanded
                        paint()
                    }
                    true
                }
                else -> true
            }
        }
        wm.addView(layout,p)
        paint()
    }
    fun update(message:String) {
        last=message
        root?.post { paint() }
    }
    fun hide() {
        val current=root
        root=null;header=null;details=null;projectLabel=null;metadataTap=null
        modeLabel=null;waitingLabel=null;actionLabel=null;params=null
        if(current!=null) try {wm.removeView(current)}catch(_:Exception){}
    }
}
