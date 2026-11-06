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
        root.addView(text("OFFSET.",30f,Color.WHITE))
        root.addView(text("CONTROLLER  /  INSPIRE 3 · ZENMUSE X9",12f,muted))
        root.addView(text("Secure rushes delivery",16f))
        root.addView(text("ANDROID BUILD · MANUAL PROXIES / SERVER TEST / METADATA",12f,accent))
        val config=card("Connection & project")
        server=field("HTTPS server",prefs.getString("server","")?:"")
        project=field("Project ID",prefs.getString("project","")?:"")
        token=field("Device token",prefs.getString("token","")?:"",true)
        config.addView(server);config.addView(project);config.addView(token)
        config.addView(button("Test connection & project") { testConnection() })
        root.addView(config)
        root.addView(button("Project QR code / Share link") { showProjectShare() })
        root.addView(button("Add proxy files manually") { chooseManualFiles() })
        root.addView(button("Start camera metadata capture") { startMetadataCapture() })
        root.addView(button("Upload history") { showUploadHistory() })
        root.addView(text(""))
        val source=card("DJI cache")
        folderLabel=text("",13f,muted);source.addView(folderLabel)
        source.addView(button("Choose CACHE_VIDEO folder") { chooseFolder() })
        root.addView(source);refreshFolderLabel()
        root.addView(text(""))
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
        root.addView(settings)
        root.addView(text(""))
        root.addView(button("Start secure upload") { saveAndStart() })
        root.addView(text(""))
        root.addView(button("Stop upload") { stopService(Intent(this,UploadService::class.java));folderLabel.text="Uploader stopped" })
        root.addView(text(""))
        root.addView(text("OFFSET TEST BUILD · Verify project isolation before customer use",12f,muted))
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

    private fun showUploadHistory() {
        val pid=project.text.toString().trim()
        if(pid.isBlank()) {
            AlertDialog.Builder(this).setMessage("Enter a project ID first.")
                .setPositiveButton("OK",null).show()
            return
        }
        val ledger=ProjectLedger(this)
        val events=ledger.history(pid)
        val (done,pending,quarantined)=ledger.counts(pid)
        val summary="$done completed · $pending pending · $quarantined quarantined"
        val rows=events.joinToString("\\n\\n") { event ->
            val label=when(event.status) {
                "done" -> "✓ Completed"
                "started" -> "↑ Starting"
                "waiting" -> "↻ Waiting / retrying"
                else -> event.status
            }
            val date=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT)
                .format(Date(event.created))
            "$label · $date\\n${event.filename}\\n${event.detail}"
        }.replace("\\n","\n")
        val message=if(rows.isEmpty()) "$summary\\n\\nNo uploads recorded yet."
            else "$summary\\n\\n$rows"
        val historyText=TextView(this).apply {
            text=message.replace("\\n","\n")
            setTextColor(Color.WHITE)
            textSize=14f
            setPadding(dp(18),dp(12),dp(18),dp(12))
        }
        val scroll=ScrollView(this).apply { addView(historyText) }
        AlertDialog.Builder(this).setTitle("Upload history")
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
