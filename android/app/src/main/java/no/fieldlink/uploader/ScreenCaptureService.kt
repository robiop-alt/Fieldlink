package no.fieldlink.uploader

import android.app.Activity
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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Opt-in DJI Pilot OCR sampler. Screen pixels stay in memory and are never uploaded
 * or persisted. Only metadata-looking text is retained locally, project scoped.
 * Requires both Android screen sharing consent and Usage Access to verify Pilot foreground.
 */
class ScreenCaptureService : Service() {
    private var projection:MediaProjection?=null
    private var display:VirtualDisplay?=null
    private var reader:ImageReader?=null
    private val worker=HandlerThread("offset-pilot-ocr")
    private lateinit var handler:Handler
    private val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var busy=false
    private var lastSample=0L
    private var stopping=false
    private val sampleIntervalMs=5000L

    override fun onCreate() {
        super.onCreate()
        worker.start()
        handler=Handler(worker.looper)
        val channel="offset_metadata"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel,"OFFSET camera metadata",NotificationManager.IMPORTANCE_LOW))
        startForeground(43,Notification.Builder(this,channel)
            .setContentTitle("OFFSET · Camera metadata")
            .setContentText("Opt-in DJI Pilot screen reading · stop sharing to end")
            .setSmallIcon(android.R.drawable.ic_menu_camera).build())
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val code=intent?.getIntExtra("projection_result_code",-1) ?: -1
        val data=intent?.getParcelableExtra<Intent>("projection_data")
        if(code!=Activity.RESULT_OK || data==null || !PilotForeground.permissionGranted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection=manager.getMediaProjection(code,data)
            projection?.registerCallback(object:MediaProjection.Callback() {
                override fun onStop(){stopSelf()}
            },Handler(mainLooper))
            startCapture()
            getSharedPreferences("fieldlink",MODE_PRIVATE).edit()
                .putBoolean("metadata_capture_active",true).apply()
        }catch(_:Exception){stopSelf()}
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val active=projection ?: return
        val metrics=resources.displayMetrics
        reader=ImageReader.newInstance(metrics.widthPixels,metrics.heightPixels,
            PixelFormat.RGBA_8888,2)
        reader?.setOnImageAvailableListener({ source ->
            val frame=try {source.acquireLatestImage()}catch(_:Exception){null} ?: return@setOnImageAvailableListener
            try {
                val now=System.currentTimeMillis()
                if(stopping || busy || now-lastSample<sampleIntervalMs ||
                    !PilotForeground.isPilotVisible(this))return@setOnImageAvailableListener
                val project=getSharedPreferences("fieldlink",MODE_PRIVATE)
                    .getString("project","").orEmpty()
                if(project.isBlank())return@setOnImageAvailableListener
                lastSample=now
                val plane=frame.planes.firstOrNull() ?: return@setOnImageAvailableListener
                val width=frame.width
                val height=frame.height
                val pixelStride=plane.pixelStride
                val rowStride=plane.rowStride
                if(pixelStride!=4 || width<=0 || height<=0)return@setOnImageAvailableListener
                val paddedWidth=rowStride/pixelStride
                val bitmap=Bitmap.createBitmap(paddedWidth,height,Bitmap.Config.ARGB_8888)
                plane.buffer.rewind()
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val cropped=Bitmap.createBitmap(bitmap,0,0,width,height)
                bitmap.recycle()
                busy=true
                recognizer.process(InputImage.fromBitmap(cropped,0))
                    .addOnSuccessListener { result ->
                        val candidate=result.textBlocks.flatMap { it.lines }
                            .map { it.text.trim() }
                            .filter(::looksLikeCameraSetting)
                            .distinct()
                            .joinToString(" | ")
                        if(candidate.isNotBlank())
                            PilotMetadataTimeline.record(this,project,candidate,now)
                    }
                    .addOnCompleteListener {
                        cropped.recycle()
                        busy=false
                    }
            }catch(_:Exception){busy=false}
            finally {frame.close()}
        },handler)
        display=active.createVirtualDisplay("OFFSET Metadata Capture",
            metrics.widthPixels,metrics.heightPixels,metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,handler)
    }

    private fun looksLikeCameraSetting(value:String):Boolean {
        // Fail closed: do not retain arbitrary DJI Pilot screen text, map labels or notifications.
        val v=value.uppercase(java.util.Locale.ROOT)
        return listOf("ISO","WB","KELVIN","SHUTTER","FPS","FRAME","APERTURE",
            "F/","EV ","PRORES","H.264","H.265","RESOLUTION","4K","5.1K","6K","8K",
            "1/25","1/50","1/100","1/200").any { v.contains(it) }
    }

    override fun onBind(intent:Intent?):IBinder?=null

    override fun onDestroy() {
        stopping=true
        getSharedPreferences("fieldlink",MODE_PRIVATE).edit()
            .putBoolean("metadata_capture_active",false).apply()
        reader?.setOnImageAvailableListener(null,null)
        display?.release()
        reader?.close()
        projection?.stop()
        recognizer.close()
        worker.quitSafely()
        super.onDestroy()
    }
}
