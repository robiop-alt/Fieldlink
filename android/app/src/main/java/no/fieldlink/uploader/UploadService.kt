package no.fieldlink.uploader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.content.Context
import androidx.documentfile.provider.DocumentFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** User-started foreground uploader. File access is through the folder URI explicitly granted by the operator. */
class UploadService : Service() {
    private var overlay: OverlayStatus? = null
    private val ledger by lazy { ProjectLedger(this) }
    private var sessionProject = ""
    private var sessionBase = ""
    private var sessionToken = ""
    private val prefs by lazy { getSharedPreferences("fieldlink", MODE_PRIVATE) }
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
    @Volatile private var running = true
    @Volatile private var loopStarted = false
    private data class FileState(val size: Long, val modified: Long, val stableScans: Int)
    private val seen = mutableMapOf<String, FileState>()

    override fun onCreate() {
        super.onCreate()
        startForeground(42, notification("Starter OFFSET …"))
        if (prefs.getBoolean("overlay",true)) {
            overlay=OverlayStatus(this)
            try { overlay?.show() } catch (_: Exception) { overlay=null }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!loopStarted) {
            sessionProject = prefs.getString("project", "") ?: ""
            sessionBase = prefs.getString("server", "")?.trimEnd('/') ?: ""
            sessionToken = prefs.getString("token", "") ?: ""
            loopStarted = true
            Thread { monitorLoop() }.start()
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { running = false; overlay?.hide(); super.onDestroy() }

    private fun notification(message: String): Notification {
        val channel = "fieldlink_upload"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(channel, "OFFSET opplasting", NotificationManager.IMPORTANCE_LOW))
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel) else Notification.Builder(this)
        return b.setContentTitle("OFFSET · automatisk opplasting").setContentText(message).setSmallIcon(android.R.drawable.stat_sys_upload).setOngoing(true).build()
    }
    private fun status(s: String) { getSystemService(NotificationManager::class.java).notify(42, notification(s)); overlay?.update(s) }

    private fun monitorLoop() {
        val tree = prefs.getString("tree_uri", null)?.let(Uri::parse) ?: run { stopSelf(); return }
        val root = DocumentFile.fromTreeUri(this, tree) ?: run { status("Får ikke tilgang til valgt mappe"); stopSelf(); return }
        // Baseline is recorded before any new media is eligible. On restart, unknown
        // cache files are quarantined rather than assumed to belong to this project.
        try { val baseline=mutableListOf<DocumentFile>(); collect(root,baseline,0); ledger.registerExisting(baseline) }
        catch (e: Exception) { status("Kan ikke sikre cache: ${e.message}"); stopSelf(); return }
        while (running) {
            try {
                if (prefs.getString("project", "") != sessionProject) {
                    status("Prosjekt endret: stopp og start OFFSET på nytt"); stopSelf(); return
                }
                val candidates = mutableListOf<DocumentFile>()
                collect(root, candidates, 0)
                var uploaded = 0
                for (f in candidates) {
                    if (!running) break
                    val key = f.uri.toString(); val stamp = f.lastModified(); val size = f.length()
                    val old = seen[key]
                    if (old == null || old.size != size || old.modified != stamp) { seen[key] = FileState(size, stamp, 0); continue }
                    val stable = old.stableScans + 1
                    seen[key] = old.copy(stableScans = stable)
                    if (stable < 3) continue // Require ~36 seconds with no changes before upload.
                    val binding=ledger.observe(f,sessionProject,true)
                    if (binding != "pending") continue
                    if (size <= 0L) continue
                    status("Sender ${f.name ?: "videoklipp"} · ${(size / 1048576)} MB")
                    if (upload(f, size)) { ledger.markDone(f,sessionProject); uploaded++ }
                }
                status(if (uploaded > 0) "$uploaded klipp sendt · overvåker nye filer" else "Overvåker · ${ledger.countQuarantined()} gamle/ukjente filer blokkert")
            } catch (e: Exception) { status("Venter på nett / tilgang · ${e.message?.take(70) ?: "feil"}") }
            try { Thread.sleep(12000) } catch (_: InterruptedException) { }
        }
    }

    private fun collect(dir: DocumentFile, out: MutableList<DocumentFile>, depth: Int) {
        if (depth > 5) return
        for (f in dir.listFiles()) {
            if (f.isDirectory) collect(f, out, depth + 1)
            else if (f.isFile && (f.name ?: "").endsWith(".mp4", true)) out.add(f)
        }
    }

    private fun upload(doc: DocumentFile, size: Long): Boolean {
        val base = sessionBase
        val project = sessionProject
        val token = sessionToken
        var uploadId = ledger.getUploadId(doc,project)
        if (uploadId == null) {
            val body = JSONObject().put("project_id", project).put("filename", doc.name ?: "drone_clip.mp4").put("total_size", size)
                .toString().toRequestBody("application/json".toMediaType())
            val response = execute(Request.Builder().url("$base/api/upload/init").header("X-Device-Token", token).post(body).build()) ?: return false
            uploadId = JSONObject(response).getString("upload_id")
            ledger.setUploadId(doc,project,uploadId)
        }
        val state = execute(Request.Builder().url("$base/api/upload/$uploadId").header("X-Device-Token", token).get().build()) ?: return false
        var offset = JSONObject(state).getLong("received")
        val chunkSize = 4 * 1024 * 1024
        contentResolver.openInputStream(doc.uri)?.use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val n = input.skip(offset - skipped)
                if (n <= 0) { if (input.read() == -1) return false else skipped++ } else skipped += n
            }
            while (offset < size && running) {
                val len = minOf(chunkSize.toLong(), size - offset).toInt()
                val temp = File(cacheDir, "fl_${uploadId}_$offset.tmp")
                FileOutputStream(temp).use { out ->
                    val buf = ByteArray(64 * 1024); var remaining = len
                    while (remaining > 0) {
                        val n = input.read(buf, 0, minOf(buf.size, remaining))
                        if (n < 0) break
                        out.write(buf, 0, n); remaining -= n
                    }
                }
                if (temp.length() != len.toLong()) { temp.delete(); return false }
                val part = temp.asRequestBody("application/octet-stream".toMediaType())
                val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("chunk", "chunk.bin", part).build()
                val req = Request.Builder().url("$base/api/upload/$uploadId/chunk").header("X-Device-Token", token).header("X-Offset", offset.toString()).put(multipart).build()
                val result = execute(req) ?: run { temp.delete(); return false }
                offset = JSONObject(result).getLong("received"); temp.delete()
                status("Sender ${doc.name} · ${(offset * 100 / size)} %")
            }
        } ?: return false
        if (offset != size) return false
        val complete = execute(Request.Builder().url("$base/api/upload/$uploadId/complete").header("X-Device-Token", token).post("".toRequestBody(null)).build()) ?: return false
        ledger.clearUploadId(doc,project)
        return JSONObject(complete).optString("status") == "ready"
    }

    private fun execute(request: Request): String? {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) { status("Server svarte ${response.code}"); return null }
            return response.body?.string() ?: "{}"
        }
    }
}
