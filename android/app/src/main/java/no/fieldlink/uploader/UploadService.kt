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
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** User-started foreground uploader. File access is through the folder URI explicitly granted by the operator. */
class UploadService : Service() {
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
        if(prefs.getBoolean("overlay",true)) {
            try { ControllerOverlayService.launch(this) } catch (_:Exception) {}
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
    override fun onDestroy() { running = false; super.onDestroy() }

    private fun notification(message: String): Notification {
        val channel = "fieldlink_upload"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(channel, "OFFSET opplasting", NotificationManager.IMPORTANCE_LOW))
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel) else Notification.Builder(this)
        return b.setContentTitle("OFFSET · automatisk opplasting").setContentText(message).setSmallIcon(android.R.drawable.stat_sys_upload).setOngoing(true).build()
    }
    private fun status(s: String) {
        getSystemService(NotificationManager::class.java).notify(42, notification(s))
        if(prefs.getBoolean("overlay",true)) {
            sendBroadcast(Intent(ControllerOverlayService.ACTION_UPDATE)
                .setPackage(packageName)
                .putExtra(ControllerOverlayService.EXTRA_STATUS,s))
        }
    }

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
                // Explicitly queued older files may be outside the monitored DJI folder.
                // Only the operator can bind them to the selected project.
                val manuallyQueued=ledger.manualPending(sessionProject).mapNotNull { uri ->
                    try { DocumentFile.fromSingleUri(this,Uri.parse(uri)) }catch(_:Exception) { null }
                }.filter { it.isFile && it.length()>0L }
                // Manual selection must appear immediately, even in manual-only mode.
                // Auto mode will publish the complete pending list further below.
                if(prefs.getString("upload_mode","auto")=="manual") publishIncoming(manuallyQueued)
                for(uriString in ledger.manualPending(sessionProject)) {
                    if(!running) break
                    try {
                        val file=DocumentFile.fromSingleUri(this,Uri.parse(uriString)) ?: continue
                        if(!file.isFile || file.length()<=0L) continue
                        if(ledger.observe(file,sessionProject,false)!="pending") continue
                        if(upload(file,file.length())) {
                            ledger.markDone(file,sessionProject)
                            ledger.logEvent(sessionProject,file.name ?: "proxy.mp4","done","Manual upload completed")
                        }
                    }catch(e:Exception) { status("Manual upload waiting: ${e.message?.take(55)}") }
                }
                if (prefs.getString("upload_mode","auto")=="manual") {
                    publishIncoming(ledger.manualPending(sessionProject).mapNotNull { uri ->
                        try { DocumentFile.fromSingleUri(this,Uri.parse(uri)) }catch(_:Exception){null}
                    }.filter { it.isFile && it.length()>0L })
                    status("Manual mode: waiting for selected clips")
                    Thread.sleep(12000)
                    continue
                }
                val candidates = mutableListOf<DocumentFile>()
                collect(root, candidates, 0)
                // DJI filenames encode reel and clip sequence. Always process oldest first;
                // a file still being written must not be overtaken by a later clip.
                val ordered = candidates.sortedWith(
                    compareBy<DocumentFile> { djiSequenceKey(it.name ?: "") }
                        .thenBy { it.lastModified() }
                        .thenBy { it.name ?: "" }
                )
                val eligible=ordered.filter { file ->
                    try { ledger.observe(file,sessionProject,true)=="pending" }catch(_:Exception) { false }
                }
                publishIncoming(eligible + manuallyQueued.filter { doc -> ledger.stateFor(doc.uri.toString(),sessionProject)=="pending" })
                var uploaded = 0
                for (f in ordered) {
                    if (!running) break
                    val key = f.uri.toString(); val stamp = f.lastModified(); val size = f.length()
                    val old = seen[key]
                    val binding=ledger.observe(f,sessionProject,true)
                    if (binding != "pending") continue
                    if (old == null || old.size != size || old.modified != stamp) {
                        seen[key] = FileState(size, stamp, 0)
                        break // Wait for the oldest pending clip before later clips.
                    }
                    val stable = old.stableScans + 1
                    seen[key] = old.copy(stableScans = stable)
                    if (stable < 3 || size <= 0L) break // Require ~36 seconds stable.
                    status("Sender ${f.name ?: "videoklipp"} · ${(size / 1048576)} MB")
                    ledger.logEvent(sessionProject,f.name ?: "videoklipp","started","${size / 1048576} MB")
                    if (upload(f, size)) {
                        ledger.markDone(f,sessionProject)
                        ledger.logEvent(sessionProject,f.name ?: "videoklipp","done","${size / 1048576} MB sendt")
                        uploaded++
                    } else {
                        ledger.logEvent(sessionProject,f.name ?: "videoklipp","waiting","Venter på nytt forsøk / nett")
                        break // Do not let newer clips overtake a failed upload.
                    }
                }
                status(if (uploaded > 0) "$uploaded klipp sendt · overvåker nye filer" else "Overvåker · ${ledger.countQuarantined()} gamle/ukjente filer blokkert")
            } catch (e: Exception) { status("Venter på nett / tilgang · ${e.message?.take(70) ?: "feil"}") }
            try { Thread.sleep(12000) } catch (_: InterruptedException) { }
        }
    }

    private val publishedPreviews=mutableSetOf<String>()
    private val manifestHttp=OkHttpClient.Builder()
        .connectTimeout(8,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS)
        .writeTimeout(8,TimeUnit.SECONDS).build()

    private fun publishIncoming(files:List<DocumentFile>) {
        if(!sessionBase.startsWith("https://") || sessionProject.isBlank()) return
        try {
            val names=HashSet<String>()
            val clips=JSONArray()
            for(doc in files) {
                val name=doc.name ?: continue
                if(!name.endsWith(".mp4",true) || !names.add(name)) continue
                clips.put(JSONObject().put("filename",name).put("size",doc.length()))
                if(clips.length()>=500) break
            }
            val request=Request.Builder()
                .url("$sessionBase/api/device/projects/$sessionProject/incoming")
                .header("X-Device-Token",sessionToken)
                .put(JSONObject().put("clips",clips).toString().toRequestBody("application/json".toMediaType()))
                .build()
            val ok=manifestHttp.newCall(request).execute().use { it.isSuccessful }
            if(ok) publishPreviewImages(files)

        }catch(_:Exception) { }
    }

    private fun publishPreviewImages(files:List<DocumentFile>) {
        val tree=prefs.getString("tree_uri",null)?.let(Uri::parse) ?: return
        val thumbDir=DocumentFile.fromTreeUri(this,tree)?.findFile(".thumbnail") ?: return
        val thumbs=thumbDir.listFiles().filter { it.isFile &&
            ((it.name ?: "").endsWith(".JPEG",true) || (it.name ?: "").endsWith(".JPG",true)) }
            .associateBy { (it.name ?: "").substringBeforeLast(".").uppercase() }
        var sent=0
        for(file in files) {
            if(sent>=3) break // avoid slowing the actual video upload
            val name=file.name ?: continue
            if(name in publishedPreviews) continue
            val thumb=thumbs[name.substringBeforeLast(".").uppercase()] ?: continue
            if(thumb.length() !in 4..(2*1024*1024L)) continue
            try {
                val bytes=contentResolver.openInputStream(thumb.uri)?.use { it.readBytes() } ?: continue
                if(bytes.size<4 || bytes.size>2*1024*1024 ||
                    bytes[0]!=0xff.toByte() || bytes[1]!=0xd8.toByte()) continue
                val jpg=bytes.toRequestBody("image/jpeg".toMediaType())
                val form=MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("filename",name)
                    .addFormDataPart("image","preview.jpg",jpg).build()
                val request=Request.Builder()
                    .url("$sessionBase/api/device/projects/$sessionProject/incoming-thumbnail")
                    .header("X-Device-Token",sessionToken).post(form).build()
                manifestHttp.newCall(request).execute().use { response ->
                    if(response.isSuccessful) publishedPreviews.add(name)
                }
                sent++
            }catch(_:Exception) { }
        }
    }

    private fun djiSequenceKey(filename:String):String {
        val name=filename.uppercase()
        // Match reel and clip numbers, preserving order across different reels.
        val match=Regex("""^D(\d+)C(\d+)""").find(name)
        if(match!=null) {
            val reel=match.groupValues[1].padStart(12,'0')
            val clip=match.groupValues[2].padStart(12,'0')
            return "0-$reel-$clip"
        }
        return "1-$name"
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
        val result=JSONObject(complete)
        if(result.optString("status")=="ready") {
            val clipId=result.optString("clip_id")
            if(clipId.isNotBlank()) sendDjiThumbnail(doc,clipId)
            return true
        }
        return false
    }

    private fun sendDjiThumbnail(video: DocumentFile, clipId: String) {
        try {
            val tree=prefs.getString("tree_uri",null)?.let(Uri::parse) ?: return
            val root=DocumentFile.fromTreeUri(this,tree) ?: return
            val thumbs=root.findFile(".thumbnail") ?: return
            val stem=(video.name ?: return).substringBeforeLast('.')
            val jpeg=thumbs.listFiles().firstOrNull {
                it.isFile && it.name?.substringBeforeLast('.')?.equals(stem,true)==true &&
                    (it.name?.endsWith(".JPEG",true)==true || it.name?.endsWith(".JPG",true)==true)
            } ?: return
            val data=contentResolver.openInputStream(jpeg.uri)?.use { stream ->
                val bytes=stream.readBytes()
                if(bytes.size>2*1024*1024 || bytes.size<4) return
                bytes
            } ?: return
            val body=data.toRequestBody("image/jpeg".toMediaType())
            val multipart=MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("image",jpeg.name ?: "thumbnail.jpg",body).build()
            execute(Request.Builder().url("$sessionBase/api/device/clips/$clipId/thumbnail")
                .header("X-Device-Token",sessionToken).post(multipart).build())
        } catch (_:Exception) {
            // Thumbnail is optional; never mark an otherwise successful video upload failed.
        }
    }

    private fun execute(request: Request): String? {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) { status("Server svarte ${response.code}"); return null }
            return response.body?.string() ?: "{}"
        }
    }
}
