package no.fieldlink.uploader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.documentfile.provider.DocumentFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Decode only visible library images; never retain 250 full-size previews at once. */
class ControllerThumbnailLoader(
    context: Context, private val owner: View,
    private val sources: Map<String, List<DocumentFile>>, private val targetWidth: Int
) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2)
    private val closed = AtomicBoolean(false)
    private data class Binding(val view: ImageView, val file: DocumentFile, var displayed: Boolean = false)
    private val bindings = mutableListOf<Binding>()
    private val wanted = ConcurrentHashMap.newKeySet<String>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val cache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val refresh = Runnable { refreshVisible() }
    private val scroll = ViewTreeObserver.OnScrollChangedListener { scheduleRefresh() }
    private val layout = ViewTreeObserver.OnGlobalLayoutListener { scheduleRefresh() }

    init {
        owner.viewTreeObserver.addOnScrollChangedListener(scroll)
        owner.viewTreeObserver.addOnGlobalLayoutListener(layout)
        owner.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { scheduleRefresh() }
            override fun onViewDetachedFromWindow(v: View) { close() }
        })
    }
    fun bind(view: ImageView, file: DocumentFile) {
        bindings.add(Binding(view, file))
        scheduleRefresh()
    }
    private fun scheduleRefresh() {
        if (closed.get()) return
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 35)
    }
    // Each refresh creates a fresh loader, so URI keys need no repeated storage queries on scroll.
    private fun cacheKey(file: DocumentFile) = file.uri.toString()
    private fun isVisible(view: View) = view.isShown && view.getGlobalVisibleRect(Rect())
    private fun refreshVisible() {
        if (closed.get()) return
        val visible = bindings.filter { isVisible(it.view) }
        val desired = visible.map { cacheKey(it.file) }.toSet()
        wanted.clear(); wanted.addAll(desired)
        for (binding in bindings) {
            if (binding !in visible && binding.displayed) {
                binding.view.setImageDrawable(null); binding.displayed = false
            }
        }
        for (binding in visible) {
            if (binding.displayed) continue
            val key = cacheKey(binding.file)
            val ready = cache.get(key)
            if (ready != null) { binding.view.setImageBitmap(ready); binding.displayed = true; continue }
            if (key in failed || !running.add(key)) continue
            workers.execute {
                try {
                    if (closed.get() || key !in wanted) return@execute
                    val bitmap = load(binding.file)
                    if (closed.get()) return@execute
                    if (bitmap != null) cache.put(key, bitmap) else failed.add(key)
                    handler.post {
                        if (!closed.get() && bitmap != null && key in wanted) {
                            bindings.filter { cacheKey(it.file) == key && isVisible(it.view) }.forEach {
                                it.view.setImageBitmap(bitmap); it.displayed = true
                            }
                        }
                    }
                } finally {
                    running.remove(key)
                    // A fast scroll can bring a skipped item back into view.
                    if (!closed.get()) handler.post { scheduleRefresh() }
                }
            }
        }
    }
    private fun load(file: DocumentFile): Bitmap? {
        for (candidate in sources[stem(file.name.orEmpty())].orEmpty()) {
            if (closed.get()) return null
            decodeImage(candidate)?.let { return it }
            if (candidate.name.orEmpty().endsWith(".mp4", true)) frame(candidate)?.let { return it }
        }
        return if (closed.get()) null else frame(file)
    }
    private fun decodeImage(file: DocumentFile): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(file.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            val width = targetWidth.coerceIn(320, 960)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= width) sample *= 2
            val bitmap = app.contentResolver.openInputStream(file.uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            if (bitmap == null) null else {
                val longEdge = maxOf(bitmap.width, bitmap.height)
                if (longEdge <= width) bitmap else {
                    val scale = width.toFloat() / longEdge
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== bitmap) bitmap.recycle() }
                }
            }
        }
    } catch (_: Exception) { null }

    private fun frame(file: DocumentFile): Bitmap? {
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(app, file.uri)
            val width = targetWidth.coerceIn(320, 960)
            reader.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, width * 9 / 16)
        } catch (_: Exception) { null }
        finally { try { reader.release() } catch (_: Exception) {} }
    }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacks(refresh)
        if (owner.viewTreeObserver.isAlive) {
            owner.viewTreeObserver.removeOnScrollChangedListener(scroll)
            owner.viewTreeObserver.removeOnGlobalLayoutListener(layout)
        }
        workers.shutdownNow(); wanted.clear(); cache.evictAll()
        bindings.forEach { it.view.setImageDrawable(null) }; bindings.clear()
    }
    companion object {
        internal fun stem(name: String): String = name.substringBeforeLast('.').uppercase(Locale.ROOT)
            .removeSuffix(".MP4").removeSuffix(".MOV").removeSuffix("_PREVIEW").removeSuffix("_THUMBNAIL")
        /** Exact clip-name matching; never pick another clip's similarly named image. */
        fun index(root: DocumentFile?): Map<String, List<DocumentFile>> {
            val result = linkedMapOf<String, MutableList<DocumentFile>>()
            for (folder in listOf(".preview", ".thumbnail")) {
                val files = try { root?.findFile(folder)?.listFiles().orEmpty() } catch (_: Exception) { emptyArray() }
                for (file in files.filter { it.isFile }.sortedBy { if (it.name.orEmpty().endsWith(".mp4", true)) 1 else 0 }) {
                    result.getOrPut(stem(file.name.orEmpty())) { mutableListOf() }.add(file)
                }
            }
            return result
        }
    }
}
