package no.fieldlink.uploader

import android.app.Activity
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
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import android.media.projection.MediaProjectionManager

class MainActivity : Activity() {
    private val pickFolder=4101
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
        root.addView(text("●  OFFSET",30f,accent))
        root.addView(text("CONTROLLER  /  INSPIRE 3 · ZENMUSE X9",12f,muted))
        root.addView(text("Secure rushes delivery",16f))
        val config=card("Connection & project")
        server=field("HTTPS server",prefs.getString("server","")?:"")
        project=field("Project ID",prefs.getString("project","")?:"")
        token=field("Device token",prefs.getString("token","")?:"",true)
        config.addView(server);config.addView(project);config.addView(token)
        root.addView(config)
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
        settings.addView(button("Allow overlay on top of DJI Pilot 2") {
            if(Build.VERSION.SDK_INT>=23) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
        })
        settings.addView(text("Old cache files are quarantined. Switching projects requires stopping and restarting the uploader.",13f,muted))
        root.addView(settings)
        root.addView(text(""))
        root.addView(button("Start kamerametadata") { startMetadataCapture() })
        root.addView(button("Start secure upload") { saveAndStart() })
        root.addView(text(""))
        root.addView(button("Stop upload") { stopService(Intent(this,UploadService::class.java));folderLabel.text="Uploader stopped" })
        root.addView(text(""))
        root.addView(text("OFFSET TEST BUILD · Verify project isolation before customer use",12f,muted))
        setContentView(ScrollView(this).apply { addView(root) })
    }
    private fun startMetadataCapture() {
    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    startActivityForResult(manager.createScreenCaptureIntent(), 4321)
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
