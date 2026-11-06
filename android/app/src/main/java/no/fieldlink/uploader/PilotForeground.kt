package no.fieldlink.uploader

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Optional foreground-app visibility for the DJI Pilot 2 pill.
 * Does not request accessibility, read screen content, or collect usage data.
 * Deny access => no foreground-only pill (fail closed).
 */
object PilotForeground {
    private val knownPilotPackages=setOf(
        "dji.go.v5", "com.dji.industry.pilot", "dji.pilot", "dji.pilot.pad"
    )

    fun permissionGranted(context:Context):Boolean {
        val ops=context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode=if(Build.VERSION.SDK_INT>=29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),context.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),context.packageName)
        return mode==AppOpsManager.MODE_ALLOWED
    }

    private var foregroundPackage:String?=null
    private var lastScan=0L

    @Synchronized fun isPilotVisible(context:Context):Boolean {
        if(!permissionGranted(context)) return false
        return try {
            val stats=context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now=System.currentTimeMillis()
            // Start with longer history, then scan only new events. Pilot may remain
            // foreground for hours without another activity-resumed event.
            val from=if(lastScan==0L || lastScan>now) now-12*60*60*1000L else lastScan-1500L
            val events=stats.queryEvents(from,now) ?: return false
            val event=UsageEvents.Event()
            var foreground=foregroundPackage
            var lastAt=0L
            while(events.hasNextEvent()) {
                events.getNextEvent(event)
                if(event.timeStamp<lastAt) continue
                if(event.eventType==UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType==UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    foreground=event.packageName
                    lastAt=event.timeStamp
                } else if((event.eventType==UsageEvents.Event.ACTIVITY_PAUSED ||
                    event.eventType==UsageEvents.Event.MOVE_TO_BACKGROUND) &&
                    event.packageName==foreground) {
                    foreground=null
                    lastAt=event.timeStamp
                }
            }
            foregroundPackage=foreground
            lastScan=now
            foreground in knownPilotPackages
        }catch(_:Exception) { false }
    }
}
