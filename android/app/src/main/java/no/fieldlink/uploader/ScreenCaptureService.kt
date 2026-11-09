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
 * Image pixels and unfiltered screen text are NEVER stored or uploaded.
 * Captures a frame at most every 5 seconds; pauses when DJI Pilot is not
 * foreground if usage-access permission makes that detectable.
 */
class ScreenCaptureService : Service() {
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
        val channel="offset_metadata"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel,"OFFSET kamerametadata",NotificationManager.IMPORTANCE_LOW))
        val note=Notification.Builder(this,channel)
            .setContentTitle("OFFSET · kamerametadata")
            .setContentText("DJI Pilot screen readings · on-device only")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true).build()
        startForeground(43,note)
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
                if(PilotForeground.permissionGranted(this) && !PilotForeground.isPilotVisible(this)) {
                    busy.set(false)
                    return@setOnImageAvailableListener
                }
                val plane=image.planes.firstOrNull()?:run {
                    busy.set(false);return@setOnImageAvailableListener
                }
                val stride=plane.pixelStride
                if(stride!=4) { busy.set(false);return@setOnImageAvailableListener }
                val bitmapWidth=plane.rowStride/stride
                val padded=Bitmap.createBitmap(bitmapWidth,image.height,Bitmap.Config.ARGB_8888)
                val bitmap=try {
                    plane.buffer.rewind()
                    padded.copyPixelsFromBuffer(plane.buffer)
                    if(bitmapWidth==image.width) padded
                    else Bitmap.createBitmap(padded,0,0,image.width,image.height)
                } catch(e:Exception) {
                    padded.recycle()
                    throw e
                }
                // With no row padding, createBitmap can return its input.
                // Keep that bitmap alive until ML Kit has finished reading it.
                if(bitmap!==padded) padded.recycle()
                try {
                    recognizer.process(InputImage.fromBitmap(bitmap,0))
                        .addOnSuccessListener { result ->
                            CameraSettingsStore.observe(this,result.text,now)
                        }
                        .addOnCompleteListener {
                            bitmap.recycle()
                            busy.set(false)
                        }
                } catch(e:Exception) {
                    bitmap.recycle()
                    throw e
                }
            }catch(_:Exception) { busy.set(false) }
            finally { image.close() }
        },Handler(worker!!.looper))
        display=proj.createVirtualDisplay("OFFSET camera settings",width,height,metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,null)
    }

    override fun onBind(intent:Intent?):IBinder?=null

    override fun onDestroy() {
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
