package no.fieldlink.uploader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Long-lived user-enabled DJI controller status overlay, separate from the
 * dataSync uploader. Boots as specialUse even where Android 15 blocks a
 * BOOT_COMPLETED dataSync service. Never records the screen.
 */
class ControllerOverlayService : Service() {
    companion object {
        const val ACTION_UPDATE="no.fieldlink.uploader.OVERLAY_UPDATE"
        const val EXTRA_STATUS="status"
        fun launch(context:Context, status:String?=null) {
            val intent=Intent(context,ControllerOverlayService::class.java)
            if(status!=null) {
                intent.action=ACTION_UPDATE
                intent.putExtra(EXTRA_STATUS,status)
            }
            if(Build.VERSION.SDK_INT>=26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
    private var overlay:OverlayStatus?=null
    private val handler=Handler(Looper.getMainLooper())
    private var latestStatus="Ready"
    private val visibilityCheck=object:Runnable {
        override fun run() {
            syncVisibility()
            handler.postDelayed(this,1500)
        }
    }
    private fun syncVisibility() {
        val allowed=prefs.getBoolean("offset_enabled",true) && prefs.getBoolean("overlay",true) &&
            (Build.VERSION.SDK_INT<23 || Settings.canDrawOverlays(this))
        val onlyPilot=prefs.getBoolean("overlay_pilot_only",false)
        val shouldShow=allowed && (!onlyPilot || PilotForeground.isPilotVisible(this))
        if(shouldShow && overlay==null) {
            try {
                overlay=OverlayStatus(this).also { it.show(); it.update(latestStatus) }
            }catch(_:Exception) { overlay=null }
        } else if(!shouldShow && overlay!=null) {
            overlay?.hide()
            overlay=null
        }
    }
    private val updates=object:BroadcastReceiver() {
        override fun onReceive(context:Context?,intent:Intent?) {
            if(intent?.action==ACTION_UPDATE)
                latestStatus=intent.getStringExtra(EXTRA_STATUS) ?: "Ready"
                overlay?.update(latestStatus)
        }
    }
    private val prefs by lazy {getSharedPreferences("fieldlink",MODE_PRIVATE)}
    override fun onCreate() {
        super.onCreate()
        val channel="offset_controller_status"
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            channel,"OFFSET controller status",NotificationManager.IMPORTANCE_LOW))
        val pending=PendingIntent.getActivity(this,3091,
            Intent(this,MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,channel)
            .setContentTitle("OFFSET controller ready")
            .setContentText("Upload status and camera metadata access")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        startForeground(3091,notification)
        val filter=IntentFilter(ACTION_UPDATE)
        if(Build.VERSION.SDK_INT>=33) registerReceiver(updates,filter,Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(updates,filter)
        handler.post(visibilityCheck)
    }
    override fun onStartCommand(intent:Intent?, flags:Int, startId:Int):Int {
        if(!prefs.getBoolean("overlay",true) || Build.VERSION.SDK_INT<23 ||
            !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if(intent?.action==ACTION_UPDATE) {
            latestStatus=intent.getStringExtra(EXTRA_STATUS) ?: "Ready"
        }
        syncVisibility()
        return START_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacks(visibilityCheck)
        try {unregisterReceiver(updates)}catch(_:Exception){}
        overlay?.hide()
        overlay=null
        super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
