package no.fieldlink.uploader

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Creates a smaller, separate MP4. The DJI cache is read-only.
 * Completed low-data copies persist across service restarts to support exact-byte resumption.
 * Call only from the upload worker thread, never the UI thread.
 */
@UnstableApi
class LowDataCompressor(private val context:Context) {
    private val main=Handler(Looper.getMainLooper())

    fun fileFor(project:String, source:Uri, size:Long, modified:Long):File {
        val key="$project|$source|$size|$modified"
        val digest=MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val folder=File(context.filesDir,"low-data")
        folder.mkdirs()
        return File(folder,"$digest.mp4")
    }

    fun compress(source:Uri, result:File, originalSize:Long):File? {
        if(result.isFile && result.length() in 1 until (originalSize*9/10)) return result
        result.delete()
        val partial=File(result.parentFile,result.name+".partial")
        partial.delete()
        val latch=CountDownLatch(1)
        var succeeded=false
        var transformer:Transformer?=null
        main.post {
            try {
                val encoder=DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(650_000).build())
                    .build()
                transformer=Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(encoder)
                    .addListener(object:Transformer.Listener {
                        override fun onCompleted(composition:Composition, result:ExportResult) {
                            succeeded=true
                            latch.countDown()
                        }
                        override fun onError(composition:Composition,result:ExportResult,exception:ExportException) {
                            latch.countDown()
                        }
                    }).build()
                val effects=Effects(emptyList(),listOf<Effect>(Presentation.createForHeight(480)))
                val edit=EditedMediaItem.Builder(MediaItem.fromUri(source)).setEffects(effects).build()
                transformer?.start(edit,partial.absolutePath)
            }catch (_:Exception) { latch.countDown() }
        }
        val finished=latch.await(180,TimeUnit.SECONDS)
        if(!finished) main.post { transformer?.cancel() }
        if(!finished || !succeeded || !partial.isFile || partial.length()<=0 ||
            partial.length()>=originalSize*9/10) {
            partial.delete()
            return null
        }
        if(!partial.renameTo(result)) {
            partial.delete()
            return null
        }
        return result
    }
}
