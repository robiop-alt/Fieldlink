package no.fieldlink.uploader

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Read-only space/media inspection. Triggered only when the operator opens Media Library. */
internal object ControllerMediaTools {
    data class StorageInfo(
        val djiVisibleBytes:Long,
        val djiVisibleCount:Int,
        val appTempBytes:Long,
        val lowDataBytes:Long,
        val appFreeBytes:Long?,
        val oldTempBytes:Long,
        val oldTempCount:Int
    )
    data class ClipInfo(
        val durationMs:Long?,
        val width:Int?,
        val height:Int?,
        val recordedAt:String?
    )
    private fun oldChunks(context:Context):List<File> {
        val before=System.currentTimeMillis()-24L*60*60*1000
        return context.cacheDir.listFiles().orEmpty().filter { file ->
            file.isFile && file.name.matches(Regex("fl_[a-zA-Z0-9_-]+_[0-9]+\\.tmp")) &&
                file.lastModified()>0 && file.lastModified()<before
        }
    }
    fun inspect(context:Context,visibleBytes:Long,visibleCount:Int):StorageInfo {
        val temp=context.cacheDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("fl_") && it.name.endsWith(".tmp") }
            .sumOf { it.length().coerceAtLeast(0) }
        val low=File(context.filesDir,"low-data").listFiles().orEmpty()
            .filter { it.isFile }.sumOf { it.length().coerceAtLeast(0) }
        val free=try { StatFs(context.filesDir.absolutePath).availableBytes }catch(_:Exception){null}
        val old=oldChunks(context)
        return StorageInfo(visibleBytes,visibleCount,temp,low,free,
            old.sumOf { it.length().coerceAtLeast(0) },old.size)
    }
    /** Only stale, app-created multipart chunk files, never resumable proxies or DJI files. */
    fun cleanOldTemporaryChunks(context:Context):Pair<Int,Long> {
        var deleted=0
        var bytes=0L
        for(file in oldChunks(context)) {
            val size=file.length().coerceAtLeast(0)
            if(file.delete()){deleted++;bytes+=size}
        }
        return Pair(deleted,bytes)
    }
    fun pretty(bytes:Long):String {
        val size=bytes.coerceAtLeast(0).toDouble()
        return when {
            size<1024 -> "${size.toLong()} B"
            size<1024*1024 -> String.format(Locale.US,"%.1f KB",size/1024)
            size<1024*1024*1024 -> String.format(Locale.US,"%.1f MB",size/1048576)
            else -> String.format(Locale.US,"%.2f GB",size/1073741824.0)
        }
    }
    /** Metadata is read only when a specific clip preview is requested. */
    fun clipInfo(context:Context,uri:Uri,modified:Long):ClipInfo {
        var duration:Long?=null
        var width:Int?=null
        var height:Int?=null
        val reader=MediaMetadataRetriever()
        try {
            reader.setDataSource(context,uri)
            duration=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            width=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            height=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        }catch(_:Exception) {
            // Not every DJI-generated proxy supports Android's local decoder.
        }finally {
            try { reader.release() }catch(_:Exception){}
        }
        val date=if(modified>0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT)
            .format(Date(modified)) else null
        return ClipInfo(duration,width,height,date)
    }
}
