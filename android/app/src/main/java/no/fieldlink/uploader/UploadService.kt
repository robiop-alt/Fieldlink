package no.fieldlink.uploader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
    private var batteryPaused=false
    private var recoveryFailures=0
    private var recoveryUntil=0L
    private var lastAlertKey=""
    private var lastAlertAt=0L

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

    private fun alertProblem(key:String, message:String) {
        if(!prefs.getBoolean("error_alerts",true))return
        val now=System.currentTimeMillis()
        if(key==lastAlertKey && now-lastAlertAt<10*60*1000)return
        lastAlertKey=key;lastAlertAt=now
        val manager=getSystemService(NotificationManager::class.java)
        val channel="offset_critical_errors_v1"
        if(Build.VERSION.SDK_INT>=26) {
            manager.createNotificationChannel(NotificationChannel(
                channel,"OFFSET important errors",NotificationManager.IMPORTANCE_DEFAULT))
        }
        val note=if(Build.VERSION.SDK_INT>=26)Notification.Builder(this,channel)
                 else Notification.Builder(this)
        manager.notify(43,note.setContentTitle("OFFSET · action may be needed")
            .setContentText(message.take(110))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true).build())
        ledger.logEvent(sessionProject,"OFFSET","error",message)
    }

    private fun recoveryFailure() {
        if(!prefs.getBoolean("smart_recovery",true))return
        recoveryFailures=(recoveryFailures+1).coerceAtMost(6)
        // Gradual retries, not an endless tight retry loop.
        recoveryUntil=System.currentTimeMillis()+
            minOf(180000L,12000L*(1L shl (recoveryFailures-1)))
        if(recoveryFailures>=3)alertProblem("network","Repeated upload errors · retrying safely")
    }

    private fun recoverySucceeded() {
        recoveryFailures=0
        recoveryUntil=0L
    }


    private fun monitorLoop() {
        val tree = prefs.getString("tree_uri", null)?.let(Uri::parse) ?: run { alertProblem("cache","DJI cache folder not configured");stopSelf(); return }
        val root = DocumentFile.fromTreeUri(this, tree) ?: run { status("Får ikke tilgang til valgt mappe");alertProblem("cache","DJI cache access unavailable"); stopSelf(); return }
        // Baseline is recorded before any new media is eligible. On restart, unknown
        // cache files are quarantined rather than assumed to belong to this project.
        try { val baseline=mutableListOf<DocumentFile>(); collect(root,baseline,0); ledger.registerExisting(baseline) }
        catch (e: Exception) { status("Kan ikke sikre cache: ${e.message}");alertProblem("cache","DJI cache inspection failed"); stopSelf(); return }
        while (running) {
            try {
                if (lowBatteryPause()) {
                    lowBatteryStatus()
                    Thread.sleep(12000)
                    continue
                }
                if (prefs.getString("project", "") != sessionProject) {
                    status("Prosjekt endret: stopp og start OFFSET på nytt"); stopSelf(); return
                }
                val policyPause=ControllerPolicy.pauseReason(this,prefs)
                if(policyPause!=null) {
                    status(policyPause)
                    Thread.sleep(12000)
                    continue
                }
                if(System.currentTimeMillis()<recoveryUntil &&
                   prefs.getBoolean("smart_recovery",true)) {
                    status("Waiting to retry · upload progress saved")
                    Thread.sleep(12000)
                    continue
                }
                if (!hasNetwork()) {
                    status("Waiting for network · uploads paused")
                    Thread.sleep(12000)
                    continue
                }
                // Priority is persisted independently of the manual queue. The
                // current HTTP upload is never interrupted: order is checked
                // again only after its verified completion.
                val manualMode=prefs.getString("upload_mode","auto")=="manual"
                val manualUris=ledger.manualPending(sessionProject)
                val manualSet=manualUris.toSet()
                val manualFiles=manualUris.mapNotNull { uri ->
                    try { DocumentFile.fromSingleUri(this,Uri.parse(uri)) }catch(_:Exception){null}
                }.filter { it.isFile && it.length()>0L }
                val ordered=if(manualMode) emptyList<DocumentFile>() else {
                    val candidates=mutableListOf<DocumentFile>()
                    collect(root,candidates,0)
                    candidates.sortedWith(compareBy<DocumentFile> { djiSequenceKey(it.name ?: "") }
                        .thenBy { it.lastModified() }.thenBy { it.name ?: "" })
                }
                val eligible=ordered.filter { file ->
                    try { ledger.observe(file,sessionProject,true)=="pending" }catch(_:Exception){false}
                }
                publishIncoming(eligible + manualFiles.filter {
                    ledger.stateFor(it.uri.toString(),sessionProject)=="pending"
                })
                val urgent=ledger.urgentUris(sessionProject).mapNotNull { uri ->
                    // Explicitly marking a discovered clip urgent also opts that
                    // clip into upload when the operator selected manual mode.
                    val file=try { DocumentFile.fromSingleUri(this,Uri.parse(uri)) }catch(_:Exception){null}
                    if(file?.isFile==true && file.length()>0L)file
                    else { ledger.clearPriority(uri,sessionProject);null }
                }
                // Highest priority first, then explicit/manual queue, then the
                // normal DJI sequence. A file appears once, regardless of source.
                val unique=HashSet<String>()
                val work=(urgent+manualFiles+ordered).filter { unique.add(it.uri.toString()) }
                var uploaded=0
                for((index,f) in work.withIndex()) {
                    if(!running || lowBatteryPause()) {
                        if(running) lowBatteryStatus()
                        break
                    }
                    val policyReason=ControllerPolicy.pauseReason(this,prefs)
                    if(policyReason!=null) { status(policyReason);break }
                    val key=f.uri.toString()
                    val manual=key in manualSet
                    val binding=ledger.observe(f,sessionProject,!manual)
                    if(binding!="pending")continue
                    val stamp=f.lastModified()
                    val size=f.length()
                    if(!manual) {
                        // A prioritized DJI cache file must obey the same
                        // stability/quarantine rules as any automatic file.
                        val old=seen[key]
                        if(old==null || old.size!=size || old.modified!=stamp) {
                            seen[key]=FileState(size,stamp,0)
                            break
                        }
                        val stable=old.stableScans+1
                        seen[key]=old.copy(stableScans=stable)
                        if(stable<3 || size<=0L)break
                    }
                    status("Sender ${f.name ?: "videoklipp"} · ${size/1048576} MB"+
                        if(key in ledger.urgentUris(sessionProject))" · PRIORITY" else "")
                    ledger.logEvent(sessionProject,f.name ?: "clip.mp4","started",
                        if(key in ledger.urgentUris(sessionProject))"Priority upload" else "${size/1048576} MB")
                    try {
                        if(upload(f,size)) {
                            recoverySucceeded()
                            ledger.markDone(f,sessionProject)
                            ledger.logEvent(sessionProject,f.name ?: "clip.mp4","done",
                                "Upload verified · ${size/1048576} MB")
                            uploaded++
                            // Re-evaluate after each verified clip. Re-scan only
                            // if the operator picked a different next clip mid-transfer.
                            val nextUrgent=ledger.urgentUris(sessionProject).firstOrNull()
                            if(nextUrgent!=null && work.getOrNull(index+1)?.uri?.toString()!=nextUrgent)break
                        }else{
                            ledger.logEvent(sessionProject,f.name ?: "clip.mp4","waiting","Waiting for safe retry")
                            recoveryFailure()
                            break
                        }
                    }catch(e:Exception) {
                        status("Upload waiting · ${e.message?.take(50)}")
                        recoveryFailure()
                        break
                    }
                }
                if(manualMode && uploaded==0)status("Manual mode · waiting for selected clips")
                if(lowBatteryPause()) lowBatteryStatus()
                else status(if (uploaded > 0) "$uploaded klipp sendt · overvåker nye filer" else "Overvåker · ${ledger.countQuarantined()} gamle/ukjente filer blokkert")
            } catch (e: Exception) { status("Venter på nett / tilgang · ${e.message?.take(70) ?: "feil"}");recoveryFailure() }
            try { Thread.sleep(12000) } catch (_: InterruptedException) { }
        }
    }

    /**
     * Pause new upload work below 20%. Resume at 25% to prevent repeatedly
     * starting and stopping near the threshold. Keep all upload IDs and bytes.
     * An unavailable battery reading must not falsely indicate a low battery.
     */
    private fun lowBatteryPause():Boolean {
        if(!prefs.getBoolean("low_battery_protection",true)) {
            batteryPaused=false
            return false
        }
        val battery=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1
        val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,-1) ?: -1
        if(level<0 || scale<=0) return batteryPaused
        val percent=(level*100)/scale
        if(batteryPaused) {
            if(percent>=25) batteryPaused=false
        }else if(percent<20) {
            batteryPaused=true
        }
        return batteryPaused
    }

    private fun lowBatteryStatus() {
        status("Low battery · uploads paused below 20% · resumes at 25%")
    }

    private fun hasNetwork(): Boolean {
        val manager=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active=manager.activeNetwork ?: return false
        val capabilities=manager.getNetworkCapabilities(active) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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

    @androidx.media3.common.util.UnstableApi
    private fun upload(doc: DocumentFile, size: Long): Boolean {
        if(lowBatteryPause()) { lowBatteryStatus(); return false }
        ControllerPolicy.pauseReason(this,prefs)?.let { status(it);return false }
        val base = sessionBase
        val project = sessionProject
        val token = sessionToken
        val originalName = doc.name ?: "drone_clip.mp4"
        var uploadId = ledger.getUploadId(doc,project)
        // Freeze the encoding choice at upload/init. Never switch bytes on resume.
        val lowMode = if(uploadId!=null) prefs.getBoolean("upload_low_$uploadId",false)
                      else prefs.getBoolean("low_data_mode",false)
        val compressor=LowDataCompressor(this)
        val lowFile=if(lowMode) compressor.fileFor(project,doc.uri,size,doc.lastModified()) else null
        val sourceUri:Uri
        val uploadSize:Long
        val uploadName:String
        if(lowFile!=null) {
            val prepared=if(uploadId!=null) {
                if(lowFile.isFile && lowFile.length()>0) lowFile else null
            } else {
                // Low-data transcoding must not compete with DJI Pilot when
                // the controller is hot or almost out of working space.
                val power=getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                val hot=Build.VERSION.SDK_INT>=29 &&
                    power.currentThermalStatus>=android.os.PowerManager.THERMAL_STATUS_SEVERE
                val free=filesDir.usableSpace
                if(hot || free < size.coerceAtMost(512L*1024*1024)) {
                    status(if(hot) "Waiting for controller to cool · Low Data Mode"
                        else "Low Data Mode waiting for storage space")
                    return false
                }
                status("Compressing $originalName · Low Data Mode")
                compressor.compress(doc.uri,lowFile,size)
            }
            if(prepared==null) {
                status(if(uploadId!=null) "Low Data copy missing · keep upload queued"
                    else "Low Data compression unavailable · original kept safe")
                return false
            }
            sourceUri=Uri.fromFile(prepared)
            uploadSize=prepared.length()
            uploadName=originalName.substringBeforeLast('.')+"_LOW_DATA.mp4"
        } else {
            sourceUri=doc.uri
            uploadSize=size
            uploadName=originalName
        }
        if (uploadId == null) {
            val body = JSONObject().put("project_id",project).put("filename",uploadName)
                .put("total_size",uploadSize)
                .toString().toRequestBody("application/json".toMediaType())
            val response = execute(Request.Builder().url("$base/api/upload/init")
                .header("X-Device-Token",token).post(body).build()) ?: return false
            uploadId = JSONObject(response).getString("upload_id")
            // commit() persists before any chunk is transferred.
            prefs.edit().putBoolean("upload_low_$uploadId",lowMode).commit()
            ledger.setUploadId(doc,project,uploadId)
        }
        val state = execute(Request.Builder().url("$base/api/upload/$uploadId")
            .header("X-Device-Token",token).get().build()) ?: return false
        val initialState=JSONObject(state)
        if(initialState.optString("status")=="ready") {
            return verifyCompleted(doc,sourceUri,uploadSize,initialState,uploadId,lowFile)
        }
        var offset=initialState.getLong("received")
        if(offset<0 || offset>uploadSize || initialState.optLong("total",uploadSize)!=uploadSize) {
            status("Upload state mismatch · keeping clip queued")
            return false
        }
        val chunkSize=4*1024*1024
        contentResolver.openInputStream(sourceUri)?.use { input ->
            var skipped=0L
            while(skipped<offset) {
                val n=input.skip(offset-skipped)
                if(n<=0) { if(input.read()==-1) return false else skipped++ } else skipped+=n
            }
            while(offset<uploadSize && running) {
                // Finish the current HTTP chunk, then pause before the next one.
                if(lowBatteryPause()) { lowBatteryStatus(); return false }
                ControllerPolicy.pauseReason(this,prefs)?.let { status(it);return false }
                val len=minOf(chunkSize.toLong(),uploadSize-offset).toInt()
                val temp=File(cacheDir,"fl_${uploadId}_$offset.tmp")
                try {
                    FileOutputStream(temp).use { out ->
                        val buf=ByteArray(64*1024);var remaining=len
                        while(remaining>0) {
                            val n=input.read(buf,0,minOf(buf.size,remaining))
                            if(n<0) break
                            out.write(buf,0,n);remaining-=n
                        }
                    }
                    if(temp.length()!=len.toLong()) return false
                    val part=temp.asRequestBody("application/octet-stream".toMediaType())
                    val multipart=MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("chunk","chunk.bin",part).build()
                    val req=Request.Builder().url("$base/api/upload/$uploadId/chunk")
                        .header("X-Device-Token",token).header("X-Offset",offset.toString())
                        .put(multipart).build()
                    val response=execute(req) ?: return false
                    val received=JSONObject(response).getLong("received")
                    if(received!=offset+len) {
                        status("Unexpected upload offset · rechecking on next attempt")
                        return false
                    }
                    offset=received
                    val delay=ControllerPolicy.throttleMillis(this,prefs)
                    if(delay>0 && running)Thread.sleep(delay)
                    status("Sending $uploadName · ${(offset*100/uploadSize)} %")
                } finally { temp.delete() }
            }
        } ?: return false
        if(offset!=uploadSize) return false
        if(lowBatteryPause()) { lowBatteryStatus(); return false }
        ControllerPolicy.pauseReason(this,prefs)?.let { status(it);return false }
        val localDigest=sha256(sourceUri) ?: return false
        val complete=execute(Request.Builder().url("$base/api/upload/$uploadId/complete")
            .header("X-Device-Token",token).header("X-Content-SHA256",localDigest)
            .post("".toRequestBody(null)).build()) ?: return false
        val result=JSONObject(complete)
        if(result.optString("status")!="ready" || result.optLong("size",-1)!=uploadSize ||
            !result.optString("sha256").equals(localDigest,true)) {
            status("Integrity check failed · keeping upload queued")
            return false
        }
        finishVerifiedUpload(doc,uploadId,lowFile,result)
        return true
    }

    private fun finishVerifiedUpload(doc:DocumentFile,uploadId:String,lowFile:File?,result:JSONObject) {
        ledger.clearUploadId(doc,sessionProject)
        prefs.edit().remove("upload_low_$uploadId").apply()
        lowFile?.delete()
        val clipId=result.optString("clip_id")
        if(clipId.isNotBlank()) sendDjiThumbnail(doc,clipId)
    }

    private fun sha256(uri:Uri):String? = try {
        val hash=MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(uri)?.use { input ->
            val bytes=ByteArray(1024*1024)
            while(true) {
                val n=input.read(bytes)
                if(n<0) break
                if(n>0) hash.update(bytes,0,n)
            }
            hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }catch(_:Exception) { null }

    private fun verifyCompleted(doc:DocumentFile,sourceUri:Uri,size:Long,
        result:JSONObject,uploadId:String,lowFile:File?):Boolean {
        val localDigest=sha256(sourceUri) ?: return false
        val safe=result.optString("status")=="ready" && result.optLong("total",-1)==size &&
            result.optString("sha256").equals(localDigest,true)
        if(safe) finishVerifiedUpload(doc,uploadId,lowFile,result)
        else status("Integrity check failed · keeping upload queued")
        return safe
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
