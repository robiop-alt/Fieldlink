package no.fieldlink.uploader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile

/**
 * Starts the upload watcher after a controller reboot only when the operator
 * explicitly enabled Start on boot and all project/SAF settings remain valid.
 *
 * Android 15 prohibits launching a dataSync foreground service from
 * BOOT_COMPLETED. On newer devices we notify the operator instead.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs=context.getSharedPreferences("fieldlink",Context.MODE_PRIVATE)
        if (!prefs.getBoolean("start_on_boot",false)) return

        val server=prefs.getString("server","").orEmpty()
        val token=prefs.getString("token","").orEmpty()
        val project=prefs.getString("project","").orEmpty()
        val tree=prefs.getString("tree_uri",null)
        if (!server.startsWith("https://") || token.isBlank() ||
            project.isBlank() || tree.isNullOrBlank()) return

        // Never start a service with a missing/revoked folder permission.
        val treeUri=Uri.parse(tree)
        if (context.contentResolver.persistedUriPermissions.none {
                it.uri==treeUri && it.isReadPermission
            }) return

        if (Build.VERSION.SDK_INT < 35) {
            try {
                context.startForegroundService(Intent(context,UploadService::class.java))
                prefs.edit().putBoolean("boot_resume_pending",false).apply()
                return
            } catch (_:Exception) {
                // Background-start restrictions vary across DJI firmware.
            }
        }

        prefs.edit().putBoolean("boot_resume_pending",true).apply()
        val manager=context.getSystemService(NotificationManager::class.java)
        val channel="offset_boot"
        if (Build.VERSION.SDK_INT>=26) {
            manager.createNotificationChannel(NotificationChannel(
                channel,"OFFSET startup",NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open=Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending=PendingIntent.getActivity(context,1901,open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=Notification.Builder(context,channel)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("OFFSET ready")
            .setContentText("Tap to resume uploads for $project")
            .setContentIntent(pending)
            .setAutoCancel(true)
        manager.notify(1901,builder.build())
    }
}
