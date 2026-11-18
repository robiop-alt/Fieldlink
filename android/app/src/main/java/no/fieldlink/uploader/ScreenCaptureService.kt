package no.fieldlink.uploader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicitly operator-authorized MediaProjection, OCR only on-device.
 * Images and raw OCR are never written to storage or uploaded. The most recent
 * HUD words can be inspected locally in a transient diagnostic message.
 * Captures a frame at most every 5 seconds; pauses when DJI Pilot is not
 * foreground if usage-access permission makes that detectable.
 */
class ScreenCaptureService : Service() {
    companion object {
        @Volatile var lastReadingDetails="No OCR result in this session."
            private set
        @Volatile var isReading=false
            private set
        fun notifyAccessNeeded(context:android.content.Context) {
            val manager=context.getSystemService(NotificationManager::class.java)
            val channel="offset_metadata_access"
            manager.createNotificationChannel(NotificationChannel(channel,"OFFSET metadata access",NotificationManager.IMPORTANCE_DEFAULT))
            val open=Intent(context,MainActivity::class.java).putExtra("request_metadata_access",true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val tap=android.app.PendingIntent.getActivity(context,4302,open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            manager.notify(4302,Notification.Builder(context,channel)
                .setContentTitle("OFFSET · Camera metadata OFF")
                .setContentText("Screen access required · tap to enable before recording")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentIntent(tap).setOngoing(true).setOnlyAlertOnce(true).build())
        }
    }
    private var projection: MediaProjection?=null
    private var display: VirtualDisplay?=null
    private var reader: ImageReader?=null
    private var worker:HandlerThread?=null
    private val mainHandler=Handler(Looper.getMainLooper())
    private val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val busy=AtomicBoolean(false)
    private var lastAttempt=0L

    override fun onCreate() {
        super.onCreate()
        lastReadingDetails="Waiting for the first external screen reading. Open DJI Pilot for 10-15 seconds."
        getSharedPreferences("fieldlink",MODE_PRIVATE).edit()
            .remove("metadata_last_read_at").remove("metadata_last_fields").apply()
        val channel="offset_metadata"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel,"OFFSET kamerametadata",NotificationManager.IMPORTANCE_LOW))
        val note=Notification.Builder(this,channel)
            .setContentTitle("OFFSET · kamerametadata")
            .setContentText("DJI Pilot screen readings · on-device only")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true).build()
        startForeground(4301,note)
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(projection!=null)return START_NOT_STICKY
        val resultCode=intent?.getIntExtra("projection_result_code",-1)?:-1
        @Suppress("DEPRECATION")
        val data=intent?.getParcelableExtra<Intent>("projection_data")
        if(resultCode!=android.app.Activity.RESULT_OK || data==null) {
            stopSelf();return START_NOT_STICKY
        }
        try {
            val manager=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection=manager.getMediaProjection(resultCode,data)
            projection?.registerCallback(object:MediaProjection.Callback(){
                override fun onStop() { mainHandler.post { stopSelf() } }
            },mainHandler)
            startReading()
            check(display!=null) { "Screen reader did not start" }
            isReading=true
            getSystemService(NotificationManager::class.java).cancel(4302)
            getSharedPreferences("fieldlink",MODE_PRIVATE).edit()
                .putBoolean("metadata_capture_active",true).apply()
        }catch(_:Exception) { stopSelf() }
        return START_NOT_STICKY
    }

    private fun startReading() {
        val proj=projection?:return
        val metrics=resources.displayMetrics
        val width=metrics.widthPixels
        val height=metrics.heightPixels
        if(width<=0||height<=0)return
        worker=HandlerThread("OFFSET-camera-OCR").also { it.start() }
        reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2)
        reader?.setOnImageAvailableListener({ source ->
            val image=try { source.acquireLatestImage() }catch(_:Exception){null}?:return@setOnImageAvailableListener
            try {
                val now=System.currentTimeMillis()
                if(now-lastAttempt<5000L || !busy.compareAndSet(false,true))return@setOnImageAvailableListener
                lastAttempt=now
                val captureTick=android.os.SystemClock.elapsedRealtime()
                if(!MetadataCaptureGate.allowFrame(captureTick)) {
                    busy.set(false)
                    return@setOnImageAvailableListener
                }
                if(PilotForeground.permissionGranted(this) && !PilotForeground.isPilotVisible(this)) {
                    busy.set(false)
                    return@setOnImageAvailableListener
                }
                val plane=image.planes.firstOrNull()?:run {
                    busy.set(false);return@setOnImageAvailableListener
                }
                val stride=plane.pixelStride
                if(stride!=4) { busy.set(false);return@setOnImageAvailableListener }
                val bitmapWidth=width+plane.rowStride/stride-width
                val padded=Bitmap.createBitmap(bitmapWidth,height,Bitmap.Config.ARGB_8888)
                plane.buffer.rewind()
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap=Bitmap.createBitmap(padded,0,0,width,height)
                padded.recycle()
                val capturedAt=System.currentTimeMillis()
                recognizer.process(InputImage.fromBitmap(bitmap,0))
                    .addOnSuccessListener { result ->
                        // Returning to OFFSET must not overwrite the Pilot result
                        // with text from Settings or this diagnostic dialog.
                        if(!MetadataCaptureGate.allowFrame(captureTick))return@addOnSuccessListener
                        val words=result.textBlocks.flatMap { block -> block.lines.flatMap { line ->
                            line.elements.mapNotNull { element -> element.boundingBox?.let { box ->
                                PilotHudParser.Word(element.text,box.centerX().toFloat()/width,box.centerY().toFloat()/height)
                            } }
                        } }
                        val hudWords=words.filter { it.y<=0.35f || it.y>=0.84f }
                        val hud=PilotHudParser.parse(words)
                        lastReadingDetails="Frame: ${width} x ${height}\nOCR words: ${words.size} · HUD words: ${hudWords.size}\n"+
                            "HUD matched fields: "+hud.keys.joinToString(", ").ifBlank { "none" }+
                            "\n\nLatest HUD text (local only):\n"+
                            hudWords.sortedWith(compareBy<PilotHudParser.Word> { it.y }.thenBy { it.x })
                                .take(90).joinToString(" ") { it.text }.take(1600).ifBlank { "No text detected." }
                        // Labelled legacy controls remain a fallback, without inventing fields.
                        val values=if(hud.isNotEmpty())hud else CameraSettingsStore.parse(result.text)
                        CameraSettingsStore.observeValues(this,values,capturedAt)
                    }
                    .addOnCompleteListener {
                        bitmap.recycle()
                        busy.set(false)
                    }
            }catch(_:Exception) { busy.set(false) }
            finally { image.close() }
        },Handler(worker!!.looper))
        display=proj.createVirtualDisplay("OFFSET camera settings",width,height,metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,null)
    }

    override fun onBind(intent:Intent?):IBinder?=null

    override fun onDestroy() {
        isReading=false
        lastReadingDetails="Screen reading stopped. Enable Camera metadata and inspect DJI Pilot again."
        notifyAccessNeeded(this)
        getSharedPreferences("fieldlink",MODE_PRIVATE).edit()
            .putBoolean("metadata_capture_active",false).apply()
        reader?.setOnImageAvailableListener(null,null)
        display?.release();display=null
        reader?.close();reader=null
        projection?.stop();projection=null
        worker?.quitSafely();worker=null
        recognizer.close()
        super.onDestroy()
    }
}
