package no.fieldlink.uploader

import android.app.Activity
import android.app.AlertDialog
import java.text.DateFormat
import java.util.Date
import android.content.Context
import android.graphics.Bitmap
import android.widget.ImageView
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.SeekBar
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import android.media.projection.MediaProjectionManager

class MainActivity : Activity() {
    private val pickFolder=4101
    private val pickManual=4102
    private val prefs by lazy { getSharedPreferences("fieldlink",MODE_PRIVATE) }
    private lateinit var server:EditText
    private lateinit var project:EditText
    private lateinit var token:EditText
    private lateinit var folderLabel:TextView
    private val bg=Color.rgb(17,20,22)
    private val panel=Color.rgb(33,39,42)
    private val accent=Color.rgb(183,243,77)
    private val muted=Color.rgb(163,175,178)
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun text(s:String,size:Float=15f,color:Int=Color.WHITE)=TextView(this).apply {
        text=s;textSize=size;setTextColor(color);setPadding(0,dp(5),0,dp(5))
    }
    private fun field(label:String,value:String,secret:Boolean=false):EditText {
        return EditText(this).apply {
            hint=label;setText(value);setTextColor(Color.WHITE);setHintTextColor(muted)
            textSize=15f;setSingleLine(true)
            if(secret) inputType=129
        }
    }
    private fun button(label:String, action:()->Unit)=Button(this).apply {
        text=label;isAllCaps=false;setTextColor(bg);setBackgroundColor(accent)
        textSize=12f;minHeight=0;minimumHeight=0
        setPadding(dp(8),dp(5),dp(8),dp(5))
        setOnClickListener { action() }
    }
    private fun card(title:String):LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(14),dp(18),dp(14));setBackgroundColor(panel)
        addView(text(title,18f))
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg;window.navigationBarColor=bg
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(bg);setPadding(dp(20),dp(20),dp(20),dp(20)) }
        root.addView(text("OFFSET.  /  INSPIRE 3",22f,Color.WHITE))
        root.addView(text("Secure rushes delivery · CONTROLLER",11f,muted))
        val config=card("Connection & project")
        server=field("HTTPS server",prefs.getString("server","")?:"")
        project=field("Project ID",prefs.getString("project","")?:"")
        token=field("Device token",prefs.getString("token","")?:"",true)
        config.addView(server);config.addView(project);config.addView(token)
        config.addView(button("Test connection") { testConnection() })
        val projectSummary=text("PROJECT  ·  ${project.text.toString().ifBlank { "NOT SET" }}",14f,accent)
        root.addView(projectSummary)
        fun compactRow(left:Button,right:Button):LinearLayout = LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            val lp=LinearLayout.LayoutParams(0,dp(43),1f).apply { setMargins(dp(2),dp(2),dp(2),dp(2)) }
            addView(left,LinearLayout.LayoutParams(lp))
            addView(right,LinearLayout.LayoutParams(lp))
        }
        root.addView(compactRow(
            button("START UPLOAD") { saveAndStart() },
            button("STOP UPLOAD") { stopService(Intent(this,UploadService::class.java));folderLabel.text="Uploader stopped" }
        ))
        root.addView(compactRow(
            button("Server history") { showServerUploadHistory() },
            button("Local history") { showUploadHistory() }
        ))
        root.addView(compactRow(
            button("Add proxy files") { chooseManualFiles() },
            button("Camera metadata") { startMetadataCapture() }
        ))
        root.addView(compactRow(
            button("Share / QR") { showProjectShare() },
            button("Test server") { testConnection() }
        ))
        val source=card("DJI cache")
        folderLabel=text("",12f,muted);source.addView(folderLabel)
        source.addView(button("Choose CACHE_VIDEO folder") { chooseFolder() })
        root.addView(source);refreshFolderLabel()
        val connectionButton=button("Connection settings  ▾") {
            val parent=config.parent
            if(parent is ViewGroup) parent.removeView(config)
            AlertDialog.Builder(this).setTitle("Connection settings")
                .setView(config).setPositiveButton("Save") { _,_ ->
                    prefs.edit().putString("server",server.text.toString().trim())
                        .putString("project",project.text.toString().trim())
                        .putString("token",token.text.toString().trim()).apply()
                    projectSummary.text="PROJECT  ·  ${project.text.toString().ifBlank { "NOT SET" }}"
                }.show()
        }
        root.addView(connectionButton)
        val settings=card("Upload preferences")
        val overlay=Switch(this).apply { text="Floating upload status";setTextColor(Color.WHITE);isChecked=prefs.getBoolean("overlay",true)
            setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("overlay",v).apply() } }
        settings.addView(overlay)
        settings.addView(text("Overlay opacity",14f,muted))
        val opacityValue=text("",13f,muted)
        val opacity=SeekBar(this).apply {
            max=80
            progress=prefs.getInt("overlay_opacity",70).coerceIn(20,100)-20
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar?,value:Int,fromUser:Boolean) {
                    val percent=value+20
                    opacityValue.text="Opacity: $percent %"
                    if(fromUser) prefs.edit().putInt("overlay_opacity",percent).apply()
                }
                override fun onStartTrackingTouch(bar:SeekBar?) {}
                override fun onStopTrackingTouch(bar:SeekBar?) {}
            })
        }
        opacityValue.text="Opacity: ${opacity.progress+20} %"
        settings.addView(opacity)
        settings.addView(opacityValue)
        val showStatus=Switch(this).apply {
            text="Show upload status";setTextColor(Color.WHITE)
            isChecked=prefs.getBoolean("overlay_show_status",true)
            setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("overlay_show_status",v).apply() }
        }
        val showProject=Switch(this).apply {
            text="Show project ID";setTextColor(Color.WHITE)
            isChecked=prefs.getBoolean("overlay_show_project",false)
            setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("overlay_show_project",v).apply() }
        }
        settings.addView(showStatus)
        settings.addView(showProject)
        settings.addView(text("Overlay changes apply after restarting the uploader.",12f,muted))
        settings.addView(button("Allow overlay on top of DJI Pilot 2") {
            if(Build.VERSION.SDK_INT>=23) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
        })
        settings.addView(text("Old cache files are quarantined. Switching projects requires stopping and restarting the uploader.",13f,muted))
        root.addView(button("Upload preferences  ▾") {
            val parent=settings.parent
            if(parent is ViewGroup) parent.removeView(settings)
            AlertDialog.Builder(this).setTitle("Upload preferences")
                .setView(ScrollView(this).apply { addView(settings) })
                .setPositiveButton("Close",null).show()
        })
        root.addView(text("OFFSET TEST BUILD · Verify project isolation",11f,muted))
        setContentView(ScrollView(this).apply { addView(root) })
    }
    private fun testConnection() {
        val base=server.text.toString().trim().trimEnd('/')
        val pid=project.text.toString().trim()
        val device=token.text.toString().trim()
        if(!base.startsWith("https://") || pid.isBlank() || device.isBlank()) {
            AlertDialog.Builder(this).setTitle("Connection details required")
                .setMessage("Enter a valid HTTPS server URL, project ID and device token.")
                .setPositiveButton("OK",null).show()
            return
        }
        Thread {
            val result=try {
                val request=Request.Builder()
                    .url("$base/api/device/projects/${Uri.encode(pid)}/share")
                    .header("X-Device-Token",device).build()
                OkHttpClient().newCall(request).execute().use { response ->
                    if(!response.isSuccessful) {
                        when(response.code) {
                            401,403 -> "Device token rejected. Check your token."
                            404 -> "Project not found. Check the project ID."
                            else -> "Server returned HTTP ${response.code}. Please retry."
                        }
                    } else {
                        val data=JSONObject(response.body?.string() ?: "{}")
                        "Connected to project."
                    }
                }
            }catch(e:Exception){"Connection failed: ${e.localizedMessage ?: "Check network and server URL."}"}
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("Connection test")
                    .setMessage(result)
                    .setPositiveButton("OK",null).show()
            }
        }.start()
    }

    private fun showServerUploadHistory() {
        val base=server.text.toString().trim().trimEnd('/')
        val pid=project.text.toString().trim()
        val device=token.text.toString().trim()
        if(!base.startsWith("https://") || pid.isBlank() || device.isBlank()) {
            AlertDialog.Builder(this).setTitle("Server upload history")
                .setMessage("Enter HTTPS server, project ID and device token first.")
                .setPositiveButton("OK",null).show()
            return
        }
        Thread {
            val result=try {
                val request=Request.Builder()
                    .url("$base/api/device/projects/${Uri.encode(pid)}/uploads")
                    .header("X-Device-Token",device).build()
                OkHttpClient().newCall(request).execute().use { response ->
                    if(!response.isSuccessful) {
                        "Could not load server history (HTTP ${response.code}). Check connection, project and device token."
                    } else {
                        val clips=JSONObject(response.body?.string() ?: "{}").optJSONArray("clips")
                        if(clips==null || clips.length()==0) "No completed uploads on the server for project $pid."
                        else {
                            val lines=mutableListOf<String>()
                            for(i in 0 until clips.length()) {
                                val clip=clips.getJSONObject(i)
                                val date=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT)
                                    .format(Date(clip.optLong("created")*1000L))
                                val size=clip.optLong("size")/1048576L
                                lines.add("✓ $date · ${size} MB\n${clip.optString("filename")}")
                            }
                            "${clips.length()} completed server clips · $pid\n\n"+lines.joinToString("\n\n")
                        }
                    }
                }
            }catch(e:Exception) {
                "Could not contact server: ${e.localizedMessage ?: "Check your network."}"
            }
            runOnUiThread {
                val label=TextView(this).apply {
                    text=result;setTextColor(Color.BLACK);textSize=14f
                    setPadding(dp(18),dp(12),dp(18),dp(12))
                }
                AlertDialog.Builder(this).setTitle("Server upload history")
                    .setView(ScrollView(this).apply { addView(label) })
                    .setPositiveButton("Close",null).show()
            }
        }.start()
    }

    private fun showUploadHistory() {
        val pid=project.text.toString().trim()
        val ledger=ProjectLedger(this)
        val events=if(pid.isBlank()) emptyList() else ledger.history(pid)
        val (done,pending,quarantined)=if(pid.isBlank()) Triple(0,0,0) else ledger.counts(pid)
        val summary="Project: ${if(pid.isBlank()) "(not selected)" else pid}\n$done completed · $pending pending · $quarantined quarantined"
        val rows=events.joinToString("\n\n") { event ->
            val label=when(event.status) {
                "done" -> "✓ Completed"
                "started" -> "↑ Starting"
                "waiting" -> "↻ Waiting / retrying"
                else -> event.status
            }
            val date=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT)
                .format(Date(event.created))
            "$label · $date\n${event.filename}\n${event.detail}"
        }
        val explanation=when {
            pid.isBlank() -> "Enter a project ID in Connection & project to view its local history."
            events.isEmpty() && (done+pending+quarantined)>0 ->
                "No upload events recorded for this project yet. The counters above show locally tracked files. Events are saved when the uploader processes files."
            events.isEmpty() ->
                "No local upload events for this project. History is stored on this controller, not downloaded from the server. Confirm the project ID and start an upload to generate events."
            else -> ""
        }
        val message=listOf(summary,rows,explanation).filter { it.isNotBlank() }.joinToString("\n\n")
        val historyText=TextView(this).apply {
            text=message
            setTextColor(Color.BLACK)
            textSize=14f
            setPadding(dp(18),dp(12),dp(18),dp(12))
        }
        val scroll=ScrollView(this).apply { addView(historyText) }
        AlertDialog.Builder(this).setTitle("Upload history · local")
            .setView(scroll).setPositiveButton("Close",null).show()
    }

    private fun showProjectShare(pinless:Boolean=false) {
        val base=server.text.toString().trim().trimEnd('/')
        val pid=project.text.toString().trim()
        val device=token.text.toString().trim()
        if(!base.startsWith("https://") || pid.isBlank() || device.isBlank()) {
            AlertDialog.Builder(this).setMessage("Enter HTTPS server, project ID and device token first.")
                .setPositiveButton("OK",null).show()
            return
        }
        Thread {
            try {
                val request=Request.Builder().url("$base/api/device/projects/$pid/share?pinless=$pinless")
                    .header("X-Device-Token",device).build()
                val response=OkHttpClient().newCall(request).execute().use { res ->
                    if(!res.isSuccessful) throw IllegalStateException("Server responded with ${res.code}")
                    JSONObject(res.body?.string() ?: "{}")
                }
                val url=response.getString("url")
                val projectName=response.optString("name","Project")
                val size=640
                val matrix=MultiFormatWriter().encode(url,BarcodeFormat.QR_CODE,size,size)
                val pixels=IntArray(size*size) { index ->
                    if(matrix[index%size,index/size]) Color.BLACK else Color.WHITE
                }
                val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
                bitmap.setPixels(pixels,0,size,0,0,size,size)
                runOnUiThread {
                    val image=ImageView(this).apply {
                        setImageBitmap(bitmap)
                        setPadding(dp(20),dp(12),dp(20),dp(12))
                        adjustViewBounds=true
                    }
                    var shareDialog:AlertDialog?=null
                    val layout=LinearLayout(this).apply {
                        orientation=LinearLayout.VERTICAL
                        addView(Switch(this@MainActivity).apply {
                            text="Open without PIN (valid for 24 hours)"
                            setTextColor(Color.DKGRAY)
                            isChecked=pinless
                            setPadding(dp(14),dp(8),dp(14),dp(8))
                            setOnCheckedChangeListener { _,enabled ->
                                shareDialog?.dismiss()
                                showProjectShare(enabled)
                            }
                        })
                        addView(image)
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Share $projectName")
                        .setMessage(if(pinless) "Anyone with this link can access without a PIN for 24 hours. Share only with trusted people." else "Recipient must enter the project PIN.")
                        .setView(layout)
                        .setPositiveButton("Share link") { _,_ ->
                            val share=Intent(Intent.ACTION_SEND).apply {
                                type="text/plain"
                                putExtra(Intent.EXTRA_TEXT,url)
                            }
                            startActivity(Intent.createChooser(share,"Share OFFSET project"))
                        }
                        .setNegativeButton("Close",null).create().also { shareDialog=it; it.show() }
                }
            } catch(e:Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Could not retrieve project link")
                        .setMessage(e.message ?: "Unknown error")
                        .setPositiveButton("OK",null).show()
                }
            }
        }.start()
    }

    private fun startMetadataCapture() {
    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    startActivityForResult(manager.createScreenCaptureIntent(), 4321)
}

    private fun chooseManualFiles() {
        val pid=project.text.toString().trim()
        if(pid.isBlank()) {
            AlertDialog.Builder(this).setMessage("Enter the destination project ID first.")
                .setPositiveButton("OK",null).show()
            return
        }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type="video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        },pickManual)
    }
    private fun queueManualFiles(data:Intent?) {
        val pid=project.text.toString().trim()
        if(pid.isBlank() || data==null) return
        val uris=mutableListOf<Uri>()
        data.clipData?.let { clips -> for(i in 0 until clips.itemCount) uris.add(clips.getItemAt(i).uri) }
        data.data?.let { if(!uris.contains(it)) uris.add(it) }
        if(uris.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Add proxy files to project?")
            .setMessage("Queue ${uris.size} selected file(s) for project $pid? Only select clips belonging to this project.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Add to queue") { _,_ ->
                val ledger=ProjectLedger(this)
                var added=0
                val problems=mutableListOf<String>()
                for(uri in uris) {
                    try {
                        contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val file=DocumentFile.fromSingleUri(this,uri)
                        if(file==null || !file.isFile || !(file.name ?: "").endsWith(".mp4",true)) {
                            problems.add("Unsupported file");continue
                        }
                        val result=ledger.enqueueManual(file,pid)
                        if(result=="Queued") added++ else problems.add(result)
                    }catch(e:Exception) { problems.add(e.message ?: "File access denied") }
                }
                AlertDialog.Builder(this).setTitle("Manual upload queue")
                    .setMessage("$added file(s) queued for $pid. Start secure upload to send them. "+
                        if(problems.isEmpty()) "" else "${problems.size} skipped: ${problems.first()}")
                    .setPositiveButton("OK",null).show()
            }.show()
    }
    private fun chooseFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        },pickFolder)
    }
    @Deprecated("SAF compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode == 4321) {
    if (resultCode == RESULT_OK && data != null) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra("projection_result_code", resultCode)
            putExtra("projection_data", data)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }
    return
}
        if(requestCode==pickManual && resultCode==RESULT_OK) { queueManualFiles(data);return }
        if(requestCode==pickFolder && resultCode==RESULT_OK) {
            val uri=data?.data?:return
            contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("tree_uri",uri.toString()).apply();refreshFolderLabel()
        }
    }
    private fun refreshFolderLabel() {
        val uri=prefs.getString("tree_uri",null)?.let(Uri::parse)
        val name=uri?.let { DocumentFile.fromTreeUri(this,it)?.name }
        folderLabel.text=if(name==null) "No cache folder selected" else "Selected folder: $name"
    }
    private fun saveAndStart() {
        val base=server.text.toString().trim().trimEnd('/')
        val pid=project.text.toString().trim();val device=token.text.toString().trim()
        if(base.isBlank()||pid.isBlank()||device.isBlank()||prefs.getString("tree_uri",null).isNullOrBlank()) {
            folderLabel.text="Complete server, project, token and cache folder first";return
        }
        if(!base.startsWith("https://")&&!base.contains("localhost")&&!base.contains("10.0.2.2")) {
            folderLabel.text="HTTPS required";return
        }
        // Never silently change the project of a running service.
        stopService(Intent(this,UploadService::class.java))
        prefs.edit().putString("server",base).putString("project",pid).putString("token",device).apply()
        val i=Intent(this,UploadService::class.java)
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i) else startService(i)
        folderLabel.text="Monitoring started · existing cache files blocked"
    }
}
