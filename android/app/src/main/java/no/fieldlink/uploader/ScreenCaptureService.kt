package no.fieldlink.uploader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.graphics.PixelFormat

class ScreenCaptureService : Service() {
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onCreate() {
        super.onCreate()

        val channelId = "offset_metadata"
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "OFFSET kamerametadata",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("OFFSET · Kamerametadata")
            .setContentText("Metadata-tjenesten er startet")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        startForeground(43, notification)
    }
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val resultCode = intent?.getIntExtra("projection_result_code", -1) ?: -1
    val resultData = intent?.getParcelableExtra<Intent>("projection_data")

    if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(
    object : android.media.projection.MediaProjection.Callback() {
        override fun onStop() {
            mediaProjection = null
            stopSelf()
        }
    },
    android.os.Handler(android.os.Looper.getMainLooper())
    
)
startScreenCapture()
        getSharedPreferences("fieldlink",MODE_PRIVATE).edit().putBoolean("metadata_capture_active",true).apply()
    }

    return START_NOT_STICKY
}
private fun startScreenCapture() {
    val projection = mediaProjection ?: return
    val metrics = resources.displayMetrics

    val width = metrics.widthPixels
    val height = metrics.heightPixels
    val density = metrics.densityDpi

    imageReader = ImageReader.newInstance(
        width,
        height,
        PixelFormat.RGBA_8888,
        2
    )

    virtualDisplay = projection.createVirtualDisplay(
        "OFFSET Metadata Capture",
        width,
        height,
        density,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        imageReader!!.surface,
        null,
        null
    )
}
    override fun onBind(intent: Intent?): IBinder? = null

override fun onDestroy() {
    getSharedPreferences("fieldlink",MODE_PRIVATE).edit().putBoolean("metadata_capture_active",false).apply()
    virtualDisplay?.release()
    virtualDisplay = null

    imageReader?.close()
    imageReader = null

    mediaProjection?.stop()
    mediaProjection = null

    super.onDestroy()
}
  }
