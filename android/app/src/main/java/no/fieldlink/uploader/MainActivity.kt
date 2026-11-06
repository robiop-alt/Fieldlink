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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.content.Intent
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import java.util.concurrent.TimeUnit
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
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
    // Only PIN-protected review links are cached: never store a 24h PIN-free bearer URL.
    private var cachedQrProject=""
    private var cachedQrUrl=""
    private var cachedQrPublic=false
    private var cachedQrBitmap:Bitmap?=null
    @Volatile private var qrFetchInFlight=false
    private fun makeQrBitmap(url:String):Bitmap {
        val size=576
        val matrix=MultiFormatWriter().encode(url,BarcodeFormat.QR_CODE,size,size)
        val pixels=IntArray(size*size) { i ->
            if(matrix[i%size,i/size]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels,0,size,0,0,size,size)
        }
    }
    private fun prefetchReviewQr() {
        val pid=prefs.getString("project","").orEmpty()
        val base=prefs.getString("server","").orEmpty().trimEnd('/')
        val device=prefs.getString("token","").orEmpty()
        if(pid.isBlank() || !base.startsWith("https://") || device.isBlank()) return
        val key="$base|$pid"
        if(key==cachedQrProject && cachedQrBitmap!=null || qrFetchInFlight) return
        val savedKey=prefs.getString("qr_project_key","").orEmpty()
        val savedUrl=prefs.getString("qr_project_url","").orEmpty()
        if(savedKey==key && savedUrl.startsWith("$base/r/") && '#' !in savedUrl) {
            if(cachedQrProject!=key || cachedQrBitmap==null) {
                Thread {
                    try {
                        val bitmap=makeQrBitmap(savedUrl)
                        runOnUiThread {
                            if(prefs.getString("project","")==pid) {
                                cachedQrProject=key;cachedQrUrl=savedUrl;cachedQrBitmap=bitmap
                                cachedQrPublic=prefs.getBoolean("qr_project_public",false)
                            }
                        }
                    }catch(_:Exception){}
                }.start()
            }
            return
        }
        qrFetchInFlight=true
        Thread {
            try {
                val req=Request.Builder()
                    .url("$base/api/device/projects/${Uri.encode(pid)}/share")
                    .header("X-Device-Token",device).get().build()
                val result=OkHttpClient().newCall(req).execute().use { response ->
                    if(!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    JSONObject(response.body?.string() ?: "{}")
                }
                val url=result.getString("url")
                if(!url.startsWith("$base/r/") || '#' in url) return@Thread
                val bitmap=makeQrBitmap(url)
                runOnUiThread {
                    if(prefs.getString("project","")==pid &&
                        prefs.getString("server","").orEmpty().trimEnd('/')==base) {
                        cachedQrProject=key;cachedQrUrl=url;cachedQrBitmap=bitmap
                        cachedQrPublic=result.optBoolean("public_review",false)
                        prefs.edit().putString("qr_project_key",key)
                            .putString("qr_project_url",url)
                            .putBoolean("qr_project_public",cachedQrPublic).apply()
                    }
                }
            }catch(_:Exception){}finally{qrFetchInFlight=false}
        }.start()
    }
    private lateinit var server:EditText
    private lateinit var project:EditText
    private lateinit var token:EditText
    private var dashboardView:View?=null
    private lateinit var folderLabel:TextView
    private lateinit var projectSummary:TextView
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
    private fun rounded(fill:Int,stroke:Int=fill,radius:Int=12):GradientDrawable =
        GradientDrawable().apply {
            shape=GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius=dp(radius).toFloat()
            setStroke(dp(1),stroke)
        }
    private fun button(label:String, action:()->Unit)=Button(this).apply {
        text=label;isAllCaps=false;setTextColor(Color.WHITE)
        textSize=11f;letterSpacing=0.015f
        minHeight=0;minimumHeight=0;minWidth=0;minimumWidth=0
        elevation=0f
        background=rounded(Color.rgb(43,50,52),Color.rgb(70,80,81))
        setPadding(dp(9),dp(5),dp(9),dp(5))
        setOnClickListener { action() }
    }
    private fun primaryButton(label:String, action:()->Unit)=button(label,action).apply {
        setTextColor(bg)
        background=rounded(accent,accent)
        setTypeface(typeface,android.graphics.Typeface.BOLD)
    }
    private fun showOffsetError(title:String, message:String, detail:String?=null, actionLabel:String?=null, action:(()->Unit)?=null) {
        val header=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(20),dp(8),dp(20),dp(4))
        }
        header.addView(text("●  ATTENTION",11f,Color.rgb(255,188,100)).apply {
            letterSpacing=0.10f
            setTypeface(typeface,android.graphics.Typeface.BOLD)
        })
        header.addView(text(title,20f,Color.WHITE).apply {
            setTypeface(typeface,android.graphics.Typeface.BOLD)
        })
        header.addView(text(message,14f,Color.rgb(204,216,215)).apply {
            setLineSpacing(dp(3).toFloat(),1f)
        })
        val builder=AlertDialog.Builder(this)
            .setView(header)
            .setNegativeButton("Close",null)
        if(actionLabel!=null && action!=null) builder.setPositiveButton(actionLabel) { _,_ -> action() }
        val technical=detail?.takeIf { it.isNotBlank() }
        if(technical!=null) builder.setNeutralButton("Details") { _,_ ->
            AlertDialog.Builder(this).setTitle("Technical details")
                .setMessage(technical.take(1500))
                .setPositiveButton("Close",null).show()
        }
        builder.show().also { dialog ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(bg)
                background=rounded(accent,accent,8)
                isAllCaps=false
                setPadding(dp(12),0,dp(12),0)
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isAllCaps=false
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isAllCaps=false
        }
    }
    private fun showOffsetConfirm(
        title:String, message:String, confirmLabel:String, destructive:Boolean=false,
        onConfirm:()->Unit
    ) {
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(20),dp(8),dp(20),dp(6))
        }
        body.addView(text(if(destructive) "●  PERMANENT ACTION" else "●  CONFIRM",11f,
            if(destructive) Color.rgb(255,174,106) else accent).apply {
            letterSpacing=0.08f
            setTypeface(typeface,android.graphics.Typeface.BOLD)
        })
        body.addView(text(title,20f,Color.WHITE).apply {
            setTypeface(typeface,android.graphics.Typeface.BOLD)
        })
        body.addView(text(message,14f,Color.rgb(207,218,218)).apply {
            setLineSpacing(dp(3).toFloat(),1f)
        })
        AlertDialog.Builder(this).setView(body)
            .setNegativeButton("Cancel",null)
            .setPositiveButton(confirmLabel) { _,_ -> onConfirm() }
            .show().also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                    isAllCaps=false
                    setTextColor(if(destructive) Color.WHITE else bg)
                    background=rounded(if(destructive) Color.rgb(159,57,57) else accent,
                        if(destructive) Color.rgb(159,57,57) else accent,8)
                    setPadding(dp(12),0,dp(12),0)
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isAllCaps=false
            }
    }
    private fun card(title:String):LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(14),dp(18),dp(14));background=rounded(panel,Color.rgb(57,65,67))
        addView(text(title,15f))
    }
    override fun onResume() {
        super.onResume()
        // Restore the pill after an in-place upgrade or the first overlay grant.
        // No permission dialog is shown if Android already granted access.
        if(prefs.getBoolean("start_on_boot",true) &&
            prefs.getBoolean("overlay",true) &&
            (Build.VERSION.SDK_INT<23 || Settings.canDrawOverlays(this))) {
            try { ControllerOverlayService.launch(this) }catch(_:Exception) {}
        }
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg;window.navigationBarColor=bg
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(18),dp(12),dp(18),dp(14))
        }
        fun row(vararg views:Button):LinearLayout = LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            views.forEach { child ->
                addView(child,LinearLayout.LayoutParams(0,dp(42),1f).apply {
                    setMargins(dp(3),dp(3),dp(3),dp(3))
                })
            }
        }
        val header=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=android.view.Gravity.CENTER_VERTICAL
        }
        val brand=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        brand.addView(text("OFFSET.",23f,Color.WHITE).apply {
            setTypeface(typeface,android.graphics.Typeface.BOLD)
            letterSpacing=0.05f
        })
        brand.addView(text("INSPIRE 3  /  RUSHES DELIVERY",10f,muted))
        header.addView(brand,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        header.addView(button("Settings") { showConnectionSettings() })
        root.addView(header)
        server=field("HTTPS server",prefs.getString("server","")?:"")
        project=field("Project ID",prefs.getString("project","")?:"")
        token=field("Device token",prefs.getString("token",null) ?: BuildConfig.DEFAULT_DEVICE_TOKEN,true)
        val config=card("CONNECTION")
        config.addView(server)
        config.addView(token)
        config.addView(button("Test connection") { testConnection() })
        val active=card("ACTIVE PROJECT")
        projectSummary=text(
            prefs.getString("project_name","")?.takeIf { it.isNotBlank() }
                ?: project.text.toString().ifBlank { "No project selected" },
            17f,Color.WHITE
        )
        projectSummary.setTypeface(projectSummary.typeface,android.graphics.Typeface.BOLD)
        active.addView(projectSummary)
        active.addView(text(
            project.text.toString().takeIf { it.isNotBlank() }?.let { "PROJECT ID  ·  $it" }
                ?: "Choose or create a project to begin",
            11f,muted
        ))
        active.addView(primaryButton("Manage projects") { showProjects() })
        root.addView(active,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(9) })
        val upload=card("UPLOAD CONTROL")
        folderLabel=text("",12f,muted)
        upload.addView(folderLabel)
        upload.addView(row(
            primaryButton("Start upload") { saveAndStart() },
            button("Stop upload") {
                prefs.edit().putBoolean("upload_paused",true).apply()
                stopService(Intent(this,UploadService::class.java))
                folderLabel.text="Uploader stopped"
            }
        ))
        upload.addView(row(
            primaryButton("Media library") { showMediaLibraryPage() },
            button("Upload queue") { showUploadQueue() }
        ))
        upload.addView(row(
            button("Select cache folder") { chooseFolder() },
            button("Add proxy files") { chooseManualFiles() }
        ))
        root.addView(upload,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(9) })
        refreshFolderLabel()
        val tools=card("PROJECT TOOLS")
        tools.addView(row(
            button("Server history") { showServerUploadHistory() },
            button("Local history") { showUploadHistory() }
        ))
        tools.addView(row(
            button("Share / QR") { showProjectShare() },
            button("Camera metadata") { startMetadataCapture() }
        ))
        root.addView(tools,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(9) })
        if(server.text.isBlank() || token.text.isBlank()) {
            root.post { showConnectionSettings() }
        }
        val settings=card("Upload preferences")
        val lowData=Switch(this).apply {
            text="Low Data Mode"
            textSize=13f
            setTextColor(Color.WHITE)
            isChecked=prefs.getBoolean("low_data_mode",false)
            setOnCheckedChangeListener { _,enabled ->
                prefs.edit().putBoolean("low_data_mode",enabled).apply()
            }
        }
        settings.addView(lowData)
        settings.addView(text(
            "Off (default): send original DJI cache. On: create a smaller 480p copy for new clips, keeping the original untouched. Existing uploads keep their original quality.",
            11f,muted
        ))
        val startOnBoot=Switch(this).apply {
            text="Start on boot"
            setTextColor(Color.WHITE)
            isChecked=prefs.getBoolean("start_on_boot",true)
            setOnCheckedChangeListener { _,enabled ->
                prefs.edit().putBoolean("start_on_boot",enabled).apply()
            }
        }
        settings.addView(startOnBoot)
        settings.addView(text(
            if(Build.VERSION.SDK_INT>=35)
                "Controller pill starts at boot. On Android 15+, uploads resume automatically when you open OFFSET; Android does not allow starting a data-sync foreground service directly at boot."
            else
                "Automatically resume the uploader when the controller restarts, if project and cache folder are configured.",
            12f,muted
        ))

        val overlay=Switch(this).apply { text="Floating upload status";setTextColor(Color.WHITE);isChecked=prefs.getBoolean("overlay",true)
            setOnCheckedChangeListener { _,enabled ->
                prefs.edit().putBoolean("overlay",enabled).apply()
                if(!enabled) stopService(Intent(this@MainActivity,ControllerOverlayService::class.java))
                else if(Build.VERSION.SDK_INT<23 || Settings.canDrawOverlays(this@MainActivity)) {
                    try { ControllerOverlayService.launch(this@MainActivity) }catch(_:Exception){}
                }
            } }
        settings.addView(overlay)
        val pilotOnly=Switch(this).apply {
            text="Show pill only in DJI Pilot 2"
            textSize=12f
            setTextColor(Color.WHITE)
            isChecked=prefs.getBoolean("overlay_pilot_only",false)
            setOnCheckedChangeListener { _,enabled ->
                prefs.edit().putBoolean("overlay_pilot_only",enabled).apply()
                if(enabled && !PilotForeground.permissionGranted(this@MainActivity)) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Allow app visibility")
                        .setMessage("To show the pill only while DJI Pilot 2 is open, Android needs one-time Usage access for OFFSET. No screen content is read. Until access is granted, the pill stays hidden in this mode.")
                        .setNegativeButton("Later",null)
                        .setPositiveButton("Open settings") { _,_ ->
                            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }.show()
                }
                // The background service checks preferences every 1.5 seconds.
                if(prefs.getBoolean("overlay",true) && (Build.VERSION.SDK_INT<23 || Settings.canDrawOverlays(this@MainActivity))) {
                    try { ControllerOverlayService.launch(this@MainActivity) }catch(_:Exception){}
                }
            }
        }
        settings.addView(pilotOnly)
        settings.addView(text("When off, the pill can appear over other apps. When on, it appears only in DJI Pilot 2. Uploads continue in the background either way.",11f,muted))
        val metadataReminder=Switch(this).apply {
            text="Metadata access reminder"
            setTextColor(Color.WHITE)
            isChecked=prefs.getBoolean("metadata_reminder",true)
            setOnCheckedChangeListener { _,checked ->
                prefs.edit().putBoolean("metadata_reminder",checked).apply()
            }
        }
        settings.addView(metadataReminder)
        settings.addView(text("Show a small METADATA TAP prompt over DJI Pilot when screen capture access is inactive.",12f,muted))

        settings.addView(text("OVERLAY STYLE",12f,accent))
        val styles=android.widget.RadioGroup(this).apply {
            orientation=android.widget.RadioGroup.HORIZONTAL
        }
        val options=listOf("minimal" to "Minimal","compact" to "Compact","classic" to "Classic")
        val current=prefs.getString("overlay_style","minimal")
        options.forEachIndexed { i,pair ->
            styles.addView(android.widget.RadioButton(this).apply {
                id=View.generateViewId()
                text=pair.second
                textSize=12f
                setTextColor(Color.WHITE)
                isChecked=pair.first==current
                setOnClickListener { prefs.edit().putString("overlay_style",pair.first).apply() }
            },android.widget.RadioGroup.LayoutParams(0,dp(42),1f))
        }
        settings.addView(styles)
        settings.addView(text(
            "Minimal: edge pill · Compact: brief progress · Classic: expanded status. Drag to either edge, tap to expand.",
            12f,muted
        ))
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
        settings.addView(button("Check permissions") { showPermissionStatus() })
        settings.addView(button("Allow overlay on top of DJI Pilot 2") {
            if(Build.VERSION.SDK_INT>=23 && !Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
            else showPermissionStatus()
        })
        settings.addView(text("Old cache files are quarantined. Switching projects requires stopping and restarting the uploader.",13f,muted))
        val footer=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        footer.addView(button("Upload preferences") {
            val parent=settings.parent
            if(parent is ViewGroup) parent.removeView(settings)
            AlertDialog.Builder(this).setTitle("Upload preferences")
                .setView(ScrollView(this).apply { addView(settings) })
                .setPositiveButton("Close",null).show()
        },LinearLayout.LayoutParams(0,dp(42),1f).apply { rightMargin=dp(5) })
        footer.addView(button("Check for updates") { checkForUpdates() },
            LinearLayout.LayoutParams(0,dp(42),1f))
        root.addView(footer)
        root.addView(text("OFFSET  /  CONTROLLER",10f,muted))
        dashboardView=ScrollView(this).apply { addView(root) }
        setContentView(dashboardView)
        showMediaLibraryPage()
        if(intent?.getBooleanExtra("request_metadata_access",false)==true) {
            showMetadataPermissionPrompt()
            intent?.removeExtra("request_metadata_access")
        }
        // No daily "resume" dialog: restore the last approved project and
        // folder automatically when the user opens OFFSET after boot.
        val bootPending=prefs.getBoolean("boot_resume_pending",false)
        if(prefs.getBoolean("start_on_boot",true)) {
            window.decorView.post { resumeSavedUploader() }
        } else if(bootPending) {
            prefs.edit().putBoolean("boot_resume_pending",false).apply()
        }
    }
    private fun showConnectionSettings() {
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(10),dp(18),dp(10))
            setBackgroundColor(panel)
        }
        for(field in listOf(server,token)) {
            (field.parent as? ViewGroup)?.removeView(field)
            body.addView(field)
        }
        body.addView(button("Test connection") { testConnection() })
        AlertDialog.Builder(this).setTitle("Connection settings")
            .setView(body)
            .setPositiveButton("Save") { _,_ ->
                val normalized=normalizedServerUrl(server.text.toString())
                server.setText(normalized)
                prefs.edit().putString("server",normalized)
                    .putString("token",token.text.toString().trim()).apply()
            }
            .setNegativeButton("Close",null).show()
    }

    private fun activateProject(id:String,name:String) {
        if(id.isBlank()) return
        val change=project.text.toString().trim()!=id
        if(change) stopService(Intent(this,UploadService::class.java))
        project.setText(id)
        prefs.edit().putString("project",id).putString("project_name",name).apply()
        projectSummary.text="$name  ·  $id"
        prefetchReviewQr()
        showMediaLibraryPage()
        if(change) {
            // The project selection was deliberate; resume it without extra taps.
            prefs.edit().putBoolean("upload_paused",false).apply()
            resumeSavedUploader()
        }
    }

    private fun showProjects() {
        val base=normalizedServerUrl(server.text.toString())
        val device=token.text.toString().trim()
        if(!base.startsWith("https://") || device.isBlank()) {
            AlertDialog.Builder(this).setTitle("Connection required")
                .setMessage("Set your HTTPS server and device token in Connection settings first.")
                .setPositiveButton("OK",null).show()
            return
        }
        Thread {
            try {
                val request=Request.Builder().url("$base/api/device/projects")
                    .header("X-Device-Token",device).build()
                val result=OkHttpClient().newCall(request).execute().use { response ->
                    if(!response.isSuccessful) throw IllegalStateException("Server HTTP ${response.code}")
                    org.json.JSONArray(response.body?.string() ?: "[]")
                }
                runOnUiThread {
                    val names=Array(result.length()) { i ->
                        val item=result.getJSONObject(i)
                        "${if(item.optString("id")==project.text.toString().trim()) "● " else ""}${item.optString("name")}  ·  ${item.optString("id")}  ·  ${item.optInt("clips")} clips"
                    }
                    AlertDialog.Builder(this).setTitle("Manage projects")
                        .setItems(names) { _,index ->
                            val item=result.getJSONObject(index)
                            val id=item.getString("id")
                            val name=item.optString("name","Project")
                            val count=item.optInt("clips")
                            AlertDialog.Builder(this).setTitle(name)
                                .setMessage("Project ID: $id\\nStatus: ${if(id==project.text.toString().trim()) "ACTIVE" else "Available"}\\nUploaded clips: $count")
                                .setPositiveButton("Select") { _,_ -> activateProject(id,name) }
                                .setNeutralButton("Delete") { _,_ -> confirmDeleteProject(id,name,count) }
                                .setNegativeButton("Back") { _,_ -> showProjects() }
                                .show()
                        }
                        .setPositiveButton("New project") { _,_ -> createProjectDialog() }
                        .setNegativeButton("Close",null).show()
                }
            }catch(e:Exception) {
                runOnUiThread {
                    showOffsetError("Projects unavailable",
                        "Could not contact the project server. Check Wi-Fi and try again.",
                        e.localizedMessage, "Retry") { showProjects() }
                }
            }
        }.start()
    }

    private fun confirmDeleteProject(id:String,name:String,count:Int) {
        val confirmation=field("Type DELETE","")
        val container=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(20),dp(8),dp(20),dp(6))
        }
        container.addView(text("●  PERMANENT ACTION",11f,Color.rgb(255,174,106)).apply {
            letterSpacing=0.08f
            setTypeface(typeface,android.graphics.Typeface.BOLD)
        })
        container.addView(text("Delete project?",20f,Color.WHITE).apply {
            setTypeface(typeface,android.graphics.Typeface.BOLD)
        })
        container.addView(text("Permanently remove $name ($id) and $count uploaded clips. This cannot be undone.",
            14f,Color.rgb(207,218,218)))
        if(count>0) {
            container.addView(text("Type DELETE to confirm",12f,muted))
            container.addView(confirmation)
        }
        val dialog=AlertDialog.Builder(this).setView(container)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Delete",null)
            .create()
        dialog.setOnShowListener {
            val delete=dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            delete.isAllCaps=false
            delete.setTextColor(Color.WHITE)
            delete.background=rounded(Color.rgb(159,57,57),Color.rgb(159,57,57),8)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isAllCaps=false
            delete.setOnClickListener {
                if(count>0 && confirmation.text.toString().trim()!="DELETE") {
                    confirmation.error="Type DELETE to confirm"
                    return@setOnClickListener
                }
                dialog.dismiss()
                        val base=server.text.toString().trim().trimEnd('/')
                        val device=token.text.toString().trim()
                        Thread {
                            val error=try {
                                val req=Request.Builder()
                                    .url("$base/api/device/projects/${Uri.encode(id)}?confirm_clips=${count>0}")
                                    .header("X-Device-Token",device).delete().build()
                                OkHttpClient().newCall(req).execute().use { response ->
                                    if(response.isSuccessful) null
                                    else "Server HTTP ${response.code}: ${response.body?.string()?.take(200)}"
                                }
                            }catch(e:Exception) { e.localizedMessage ?: "Network error" }
                            runOnUiThread {
                                if(error==null) {
                                    if(project.text.toString().trim()==id) {
                                        stopService(Intent(this,UploadService::class.java))
                                        project.setText("")
                                        prefs.edit().remove("project").remove("project_name").apply()
                                        projectSummary.text="PROJECT  ·  NOT SET"
                                    }
                                    showProjects()
                                } else {
                                    showOffsetError("Couldn't delete project",
                                        "The project was not deleted. Check your connection and try again.",
                                        error)
                                }
                            }
                        }.start()
            }
        }
        dialog.show()
    }

    private fun createProjectDialog() {
        val name=field("Project name","")
        val container=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),0)
            addView(name)
        }
        AlertDialog.Builder(this).setTitle("Create project")
            .setView(container)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Create") { _,_ ->
                val projectName=name.text.toString().trim()
                if(projectName.isBlank()) {
                    AlertDialog.Builder(this).setMessage("Project name is required.")
                        .setPositiveButton("OK",null).show()
                } else {
                    val base=server.text.toString().trim().trimEnd('/')
                    val device=token.text.toString().trim()
                    Thread {
                        try {
                            val body=JSONObject().put("name",projectName).put("public_review",false)
                                .toString().toRequestBody("application/json".toMediaType())
                            val req=Request.Builder().url("$base/api/device/projects")
                                .header("X-Device-Token",device).post(body).build()
                            val result=OkHttpClient().newCall(req).execute().use { response ->
                                if(!response.isSuccessful) throw IllegalStateException("Server HTTP ${response.code}")
                                JSONObject(response.body?.string() ?: "{}")
                            }
                            runOnUiThread {
                                val id=result.getString("id")
                                activateProject(id,projectName)
                                AlertDialog.Builder(this).setTitle("Project created")
                                    .setMessage("Project: $projectName\nID: $id\nReview PIN: ${result.optString("pin")}\n\nSave the PIN now; it is only shown when the project is created.")
                                    .setPositiveButton("OK",null).show()
                            }
                        }catch(e:Exception) {
                            runOnUiThread {
                                showOffsetError("Project not created",
                                    "Check your connection to the server, then try again.",
                                    e.localizedMessage)
                            }
                        }
                    }.start()
                }
            }.show()
    }

    private fun checkForUpdates() {
        val base=server.text.toString().trim().trimEnd('/')
        val device=token.text.toString().trim()
        if(!base.startsWith("https://") || device.isBlank()) {
            AlertDialog.Builder(this).setTitle("Check for updates")
                .setMessage("Configure the server and device token first.")
                .setPositiveButton("OK",null).show()
            return
        }
        Thread {
            try {
                val req=Request.Builder().url("$base/api/device/app-update")
                    .header("X-Device-Token",device).build()
                val info=OkHttpClient().newCall(req).execute().use { response ->
                    if(!response.isSuccessful) throw IllegalStateException("Server HTTP ${response.code}")
                    JSONObject(response.body?.string() ?: "{}")
                }
                val installed=packageManager.getPackageInfo(packageName,0)
                val currentCode=if(Build.VERSION.SDK_INT>=28) installed.longVersionCode else installed.versionCode.toLong()
                val latest=info.optLong("version_code",0L)
                val url=info.optString("download_url")
                runOnUiThread {
                    val updateAvailable=latest>currentCode && url.startsWith("https://")
                    val message=if(updateAvailable)
                        "Installed: ${installed.versionName} ($currentCode)\\nAvailable: ${info.optString("version_name")} ($latest)\\n\\nDownload the signed APK and confirm installation in Android."
                    else "Installed: ${installed.versionName} ($currentCode)\\nNo published update available yet."
                    val dialog=AlertDialog.Builder(this).setTitle("App updates")
                        .setMessage(message).setNegativeButton("Close",null)
                    if(updateAvailable) dialog.setPositiveButton("Download update") { _,_ ->
                        startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))
                    }
                    dialog.show()
                }
            }catch(e:Exception) {
                runOnUiThread {
                    showOffsetError("Couldn't check updates",
                        "Check your internet connection and try again later.",
                        e.localizedMessage)
                }
            }
        }.start()
    }

    private fun normalizedServerUrl(raw:String):String {
        val input=raw.trim().trimEnd('/')
        return try {
            val uri=Uri.parse(input)
            if(uri.scheme!="https" || uri.host.isNullOrBlank()) input
            else "https://${uri.authority}"
        }catch(_:Exception) { input }
    }

    private fun testConnection() {
        val base=normalizedServerUrl(server.text.toString())
        val device=token.text.toString().trim()
        if(!base.startsWith("https://") || device.isBlank()) {
            AlertDialog.Builder(this).setTitle("Connection details required")
                .setMessage("Enter your HTTPS server URL and device token. Select a project separately under Projects.")
                .setPositiveButton("OK",null).show()
            return
        }
        Thread {
            val result=try {
                val request=Request.Builder().url("$base/api/device/projects")
                    .header("X-Device-Token",device).build()
                OkHttpClient.Builder().connectTimeout(45,TimeUnit.SECONDS)
                    .readTimeout(45,TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                    when(response.code) {
                        401,403 -> "Device token rejected. Check your token."
                        in 200..299 -> "Connected successfully. Open Projects to select or create a project."
                        else -> "Server returned HTTP ${response.code}. Please retry."
                    }
                }
            }catch(e:Exception) { "Connection failed: ${e.localizedMessage ?: "Check network and server URL."}" }
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("Connection test")
                    .setMessage(result).setPositiveButton("OK",null).show()
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

    private fun showQrDialog(url:String,isPublic:Boolean,projectName:String,bitmap:Bitmap) {
        val image=ImageView(this).apply {
            setImageBitmap(bitmap)
            setPadding(dp(8),dp(8),dp(8),dp(8))
            adjustViewBounds=true
            setBackgroundColor(Color.WHITE)
        }
        val layout=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(4),dp(18),dp(5))
            addView(text(if(isPublic) "PUBLIC REVIEW · NO PIN" else
                "PRIVATE REVIEW · PROJECT PIN REQUIRED",11f,if(isPublic) accent else muted))
            addView(image,LinearLayout.LayoutParams(dp(280),dp(280)).apply {
                gravity=android.view.Gravity.CENTER_HORIZONTAL
            })
            addView(text("Access follows the project settings. This QR code does not expire.",
                11f,muted))
        }
        AlertDialog.Builder(this).setTitle("Share · $projectName")
            .setView(layout)
            .setPositiveButton("Share link") { _,_ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type="text/plain"
                    putExtra(Intent.EXTRA_TEXT,url)
                },"Share OFFSET project"))
            }
            .setNegativeButton("Close",null)
            .show().also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isAllCaps=false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isAllCaps=false
            }
    }

    private fun showProjectShare() {
        val base=normalizedServerUrl(server.text.toString())
        val pid=project.text.toString().trim()
        val device=token.text.toString().trim()
        if(!base.startsWith("https://") || pid.isBlank() || device.isBlank()) {
            showOffsetError("Project link unavailable",
                "Choose a project and configure your server connection first.")
            return
        }
        val key="$base|$pid"
        val projectName=prefs.getString("project_name","Project").orEmpty()
        val bitmap=if(cachedQrProject==key) cachedQrBitmap else null
        if(bitmap!=null && cachedQrUrl.isNotBlank()) {
            showQrDialog(cachedQrUrl,cachedQrPublic,projectName,bitmap)
            return
        }
        val saved=prefs.getString("qr_project_url","").orEmpty()
        if(prefs.getString("qr_project_key","")==key &&
            saved.startsWith("$base/r/") && '#' !in saved) {
            val ready=makeQrBitmap(saved)
            cachedQrProject=key;cachedQrUrl=saved;cachedQrBitmap=ready
            cachedQrPublic=prefs.getBoolean("qr_project_public",false)
            showQrDialog(saved,cachedQrPublic,projectName,ready)
            return
        }
        Thread {
            try {
                val request=Request.Builder()
                    .url("$base/api/device/projects/${Uri.encode(pid)}/share")
                    .header("X-Device-Token",device).get().build()
                val result=OkHttpClient().newCall(request).execute().use { res ->
                    if(!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                    JSONObject(res.body?.string() ?: "{}")
                }
                val url=result.getString("url")
                val name=result.optString("name",projectName)
                val isPublic=result.optBoolean("public_review",false)
                val ready=makeQrBitmap(url)
                runOnUiThread {
                    if(project.text.toString().trim()!=pid) return@runOnUiThread
                    if(url.startsWith("$base/r/") && '#' !in url) {
                        cachedQrProject=key;cachedQrUrl=url;cachedQrBitmap=ready
                        cachedQrPublic=isPublic
                        prefs.edit().putString("qr_project_key",key)
                            .putString("qr_project_url",url)
                            .putBoolean("qr_project_public",isPublic).apply()
                    }
                    showQrDialog(url,isPublic,name,ready)
                }
            }catch(e:Exception) {
                runOnUiThread {
                    showOffsetError("Share link unavailable",
                        "Check your server connection and try again.",e.localizedMessage)
                }
            }
        }.start()
    }

    override fun onNewIntent(intent:Intent?) {
        super.onNewIntent(intent)
        if(intent?.getBooleanExtra("request_metadata_access",false)==true) {
            showMetadataPermissionPrompt()
        }
    }

    private fun showMetadataPermissionPrompt() {
        showOffsetConfirm("Camera metadata access",
            "Allow OFFSET to read the DJI Pilot display for camera metadata during this session? Android will ask for screen capture permission. You can stop it at any time.",
            "Continue") { startMetadataCapture() }
    }

    private fun startMetadataCapture() {
    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    startActivityForResult(manager.createScreenCaptureIntent(), 4321)
}



    private data class LibraryClip(val file:DocumentFile,val thumb:Bitmap?)
    private data class CloudClip(val id:String,val filename:String,val bytes:Long)

    /** Main controller screen. Cloud status is authoritative; local media is never deleted. */
    private fun showMediaLibraryPage() {
        val pid=project.text.toString().trim()
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16),dp(12),dp(16),dp(18))
        }
        val header=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=android.view.Gravity.CENTER_VERTICAL
        }
        val heading=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        heading.addView(text("OFFSET.   MEDIA LIBRARY",20f,Color.WHITE).apply {
            setTypeface(typeface,android.graphics.Typeface.BOLD)
        })
        heading.addView(text(
            (prefs.getString("project_name",null)?.takeIf { it.isNotBlank() } ?: "Select project")+
                if(pid.isNotBlank()) "  ·  $pid" else "",
            12f,accent
        ))
        header.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("Settings") { dashboardView?.let { setContentView(it) } })
        root.addView(header)
        val nav=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        nav.addView(primaryButton("QR · Share") { showProjectShare() },
            LinearLayout.LayoutParams(0,dp(33),1f).apply { rightMargin=dp(5) })
        nav.addView(button("Projects") { showProjects() },
            LinearLayout.LayoutParams(0,dp(33),1f).apply { rightMargin=dp(5) })
        nav.addView(button("Refresh") { showMediaLibraryPage() },
            LinearLayout.LayoutParams(0,dp(33),1f))
        root.addView(nav)
        val status=text("Loading media…",12f,muted)
        root.addView(status)
        val grid=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(grid)
        setContentView(ScrollView(this).apply { addView(root) })
        prefetchReviewQr()
        val base=normalizedServerUrl(server.text.toString())
        val secret=token.text.toString().trim()
        val cacheUri=savedCacheUri()
        if(pid.isBlank()) {
            status.text="No project selected. Choose or create a project with Manage projects."
            return
        }
        if(cacheUri==null) status.text="No CACHE_VIDEO selected. Showing any available cloud clips."
        Thread {
            val clips=mutableListOf<LibraryClip>()
            val remote=mutableListOf<CloudClip>()
            var cloudAvailable=false
            var serverError=""
            try {
                val tree=cacheUri?.let { DocumentFile.fromTreeUri(this,it) }
                val thumbs=tree?.findFile(".thumbnail")?.listFiles()
                    ?.filter { it.isFile && (it.name ?: "").endsWith(".JPEG",true) || it.isFile && (it.name ?: "").endsWith(".JPG",true) }
                    ?.associateBy { (it.name ?: "").substringBeforeLast(".").uppercase() }
                    ?: emptyMap()
                val media=tree?.listFiles()?.filter { it.isFile && (it.name ?: "").endsWith(".mp4",true) }
                    ?.sortedBy { it.name?.uppercase() ?: "" } ?: emptyList()
                for(file in media.take(250)) {
                    val stem=(file.name ?: "").substringBeforeLast(".").uppercase()
                    val thumb=thumbs[stem]
                    val bitmap=try {
                        thumb?.let { t -> contentResolver.openInputStream(t.uri)?.use { stream ->
                            BitmapFactory.decodeStream(stream,null,BitmapFactory.Options().apply { inSampleSize=4 })
                        } }
                    }catch(_:Exception){null}
                    clips.add(LibraryClip(file,bitmap))
                }
            }catch(e:Exception) {serverError="Could not read CACHE_VIDEO: ${e.localizedMessage}"}
            if(base.startsWith("https://") && secret.isNotBlank()) {
                try {
                    val req=Request.Builder().url("$base/api/device/projects/${Uri.encode(pid)}/uploads")
                        .header("X-Device-Token",secret).get().build()
                    val response=OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS)
                        .readTimeout(45,TimeUnit.SECONDS).build().newCall(req).execute().use { res ->
                            if(!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                            JSONObject(res.body?.string() ?: "{}")
                        }
                    val arr=response.optJSONArray("clips")
                    if(arr!=null) for(i in 0 until arr.length()) {
                        val o=arr.getJSONObject(i)
                        remote.add(CloudClip(o.getString("id"),o.getString("filename"),o.optLong("size")))
                    }
                    cloudAvailable=true
                }catch(e:Exception) {serverError="Cloud status unavailable: ${e.localizedMessage}"}
            }else serverError="Configure your server and device token under Dashboard → Settings"
            runOnUiThread {
                if(project.text.toString().trim()!=pid) return@runOnUiThread
                val ledger=ProjectLedger(this)
                val queued=ledger.queueUris(pid)
                val remoteByName=remote.associateBy { it.filename }
                val localNames=clips.mapNotNull { it.file.name }.toSet()
                val uploaded=clips.count { (it.file.name ?: "") in remoteByName }
                status.text="${clips.size} local  ·  ${uploaded} uploaded  ·  ${queued.size} queued" +
                    if(cloudAvailable) "" else "\n$serverError"
                val mode=Switch(this).apply {
                    text="Auto upload (off = selected clips only)"
                    setTextColor(Color.WHITE);textSize=13f
                    isChecked=prefs.getString("upload_mode","auto")=="auto"
                    setOnCheckedChangeListener { _,auto ->
                        prefs.edit().putString("upload_mode",if(auto) "auto" else "manual").apply()
                    }
                }
                grid.addView(mode)
                if(clips.isEmpty()) grid.addView(text("No local MP4 clips found. Cloud-only clips are listed below.",13f,muted))
                val columns=if(resources.displayMetrics.widthPixels>=dp(760)) 3 else 2
                val gap=dp(6)
                val cardWidth=((resources.displayMetrics.widthPixels-dp(32)-gap*(columns-1))/columns).coerceAtLeast(dp(130))
                val libraryGrid=android.widget.GridLayout(this).apply { columnCount=columns }
                grid.addView(libraryGrid)
                for((position,item) in clips.withIndex()) {
                    val file=item.file
                    val name=file.name ?: "clip.mp4"
                    val cloud=remoteByName[name]
                    val isQueued=file.uri.toString() in queued
                    val localState=ledger.stateFor(file.uri.toString(),pid)
                    val label=when {
                        cloud!=null -> "UPLOADED"
                        isQueued -> "QUEUED"
                        localState=="done" -> if(cloudAvailable) "REMOVED FROM REVIEW" else "PREVIOUSLY UPLOADED"
                        localState=="quarantine" -> "EXCLUDED"
                        localState=="other" -> "OTHER PROJECT"
                        else -> "NOT UPLOADED"
                    }
                    val card=LinearLayout(this).apply {
                        orientation=LinearLayout.VERTICAL
                        setPadding(dp(7),dp(7),dp(7),dp(5))
                        background=rounded(panel,Color.rgb(52,62,64))
                    }
                    val preview=ImageView(this).apply {
                        scaleType=ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(Color.rgb(19,24,26))
                        item.thumb?.let { setImageBitmap(it) }
                    }
                    card.addView(preview,LinearLayout.LayoutParams(-1,dp(95)))
                    card.addView(text(name,12f,Color.WHITE).apply {
                        maxLines=1
                        ellipsize=android.text.TextUtils.TruncateAt.MIDDLE
                        setTypeface(typeface,android.graphics.Typeface.BOLD)
                    })
                    val detail=LinearLayout(this).apply {
                        orientation=LinearLayout.HORIZONTAL
                        gravity=android.view.Gravity.CENTER_VERTICAL
                    }
                    detail.addView(text("$label · ${file.length()/1048576} MB",10f,
                        if(cloud!=null) accent else muted),
                        LinearLayout.LayoutParams(0,-2,1f))
                    val actions=mutableListOf<Pair<String,()->Unit>>()
                    if(cloud!=null && cloudAvailable) {
                        actions.add("Delete from review" to { confirmDeleteCloudClip(pid,cloud.id,name) })
                    }else if(isQueued) {
                        actions.add("Upload next" to { ledger.prioritize(file.uri.toString(),pid);showMediaLibraryPage() })
                        actions.add("Remove from queue" to {
                            stopService(Intent(this,UploadService::class.java))
                            ledger.removeFromQueue(file.uri.toString(),pid)
                            showMediaLibraryPage()
                        })
                    }else if(localState!="other" && localState!="done") {
                        actions.add("Add to queue" to {
                            val result=ledger.enqueueManual(file,pid)
                            if(result!="Queued") {
                                AlertDialog.Builder(this).setMessage(result).setPositiveButton("OK",null).show()
                            } else showMediaLibraryPage()
                        })
                        if(localState!="quarantine") actions.add("Exclude from upload" to {
                            stopService(Intent(this,UploadService::class.java))
                            ledger.excludeFromAuto(file,pid)
                            showMediaLibraryPage()
                        })
                    }
                    if(actions.isNotEmpty()) {
                        val menuButton=button("⋮") {}
                        menuButton.textSize=18f
                        menuButton.minWidth=dp(34)
                        menuButton.minimumWidth=dp(34)
                        menuButton.setPadding(0,0,0,0)
                        menuButton.setOnClickListener { view ->
                            val popup=android.widget.PopupMenu(this,view)
                            actions.forEachIndexed { index,action -> popup.menu.add(0,index,index,action.first) }
                            popup.setOnMenuItemClickListener { selected ->
                                actions.getOrNull(selected.itemId)?.second?.invoke()
                                true
                            }
                            popup.show()
                        }
                        detail.addView(menuButton,LinearLayout.LayoutParams(dp(38),dp(36)))
                    }
                    card.addView(detail)
                    libraryGrid.addView(card,android.widget.GridLayout.LayoutParams().apply {
                        width=cardWidth-gap/2
                        height=ViewGroup.LayoutParams.WRAP_CONTENT
                        setMargins(if(position%columns==0) 0 else gap/2,dp(5),gap/2,0)
                    })
                }
                for(cloud in remote) if(cloud.filename !in localNames) {
                    val line=LinearLayout(this).apply {
                        orientation=LinearLayout.HORIZONTAL
                        gravity=android.view.Gravity.CENTER_VERTICAL
                        setPadding(dp(10),dp(8),dp(10),dp(8))
                        background=rounded(panel,Color.rgb(52,62,64))
                    }
                    line.addView(text("${cloud.filename}\nUPLOADED · CLOUD ONLY",12f,accent),
                        LinearLayout.LayoutParams(0,-2,1f))
                    line.addView(button("Delete") { confirmDeleteCloudClip(pid,cloud.id,cloud.filename) })
                    grid.addView(line,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
                }
                grid.addView(text("Deleting from review removes only the cloud proxy. Original DJI files are never deleted.",11f,muted))
            }
        }.start()
    }

    private fun confirmDeleteCloudClip(pid:String,clipId:String,name:String) {
        showOffsetConfirm("Remove clip from review?",
            "$name will be removed from the review site. The original DJI file stays on the controller.",
            "Remove",destructive=true) {
                Thread {
                    val error=try {
                        val base=normalizedServerUrl(server.text.toString())
                        val req=Request.Builder()
                            .url("$base/api/device/projects/${Uri.encode(pid)}/clips/${Uri.encode(clipId)}")
                            .header("X-Device-Token",token.text.toString().trim())
                            .delete().build()
                        OkHttpClient().newCall(req).execute().use { res ->
                            if(res.isSuccessful) null
                            else "Server HTTP ${res.code}: ${res.body?.string()?.take(150)}"
                        }
                    }catch(e:Exception){e.localizedMessage ?: "Network error"}
                    runOnUiThread {
                        if(error==null) {
                            ProjectLedger(this).logEvent(pid,name,"removed","Deleted cloud proxy from review")
                            showMediaLibraryPage()
                        }else showOffsetError("Clip not removed",
                            "The proxy is still in the review project. Check your connection and try again.",
                            error)
                    }
                }.start()
            }
    }

    private fun showClipLibrary() {
        val pid=project.text.toString().trim()
        val uri=prefs.getString("tree_uri",null)?.let(Uri::parse)
        val root=uri?.let { DocumentFile.fromTreeUri(this,it) }
        if(pid.isBlank() || root==null) {
            AlertDialog.Builder(this).setTitle("Clip library")
                .setMessage("Select a project and CACHE_VIDEO folder first.")
                .setPositiveButton("OK",null).show()
            return
        }
        val ledger=ProjectLedger(this)
        val queued=ledger.queueUris(pid)
        val files=root.listFiles().filter { it.isFile && (it.name ?: "").endsWith(".mp4",true) }
            .sortedWith(compareBy<DocumentFile> { it.name ?: "" }.thenBy { it.lastModified() })
        val selected=mutableSetOf<String>()
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8)) }
        val mode=Switch(this).apply {
            text="Auto upload (off = selected clips only)"
            setTextColor(Color.BLACK)
            isChecked=prefs.getString("upload_mode","auto")=="auto"
            setOnCheckedChangeListener { _,checked ->
                prefs.edit().putString("upload_mode",if(checked) "auto" else "manual").apply()
            }
        }
        layout.addView(mode)
        layout.addView(text("${files.size} clips · ${queued.size} queued · Select clips to upload",12f,Color.DKGRAY))
        val thumbs=root.findFile(".thumbnail")
        val thumbnailFiles=thumbs?.listFiles()?.filter { it.isFile } ?: emptyList()
        for(file in files.take(200)) {
            val key=file.uri.toString()
            val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL }
            val check=android.widget.CheckBox(this).apply {
                isChecked=key in queued
                isEnabled=key !in queued
                setOnCheckedChangeListener { _,v -> if(v) selected.add(key) else selected.remove(key) }
            }
            line.addView(check)
            val stem=(file.name ?: "").substringBeforeLast(".")
            val thumb=thumbnailFiles.firstOrNull {
                it.isFile && it.name?.substringBeforeLast(".")==stem && (it.name?.endsWith(".JPEG",true)==true || it.name?.endsWith(".JPG",true)==true)
            }
            if(thumb!=null) {
                try {
                    contentResolver.openInputStream(thumb.uri)?.use { stream ->
                        val bmp=BitmapFactory.decodeStream(stream)
                        if(bmp!=null) line.addView(ImageView(this).apply {
                            setImageBitmap(bmp);scaleType=ImageView.ScaleType.CENTER_CROP
                        },LinearLayout.LayoutParams(dp(92),dp(56)).apply { rightMargin=dp(10) })
                    }
                }catch(_:Exception) {}
            }
            line.addView(text("${file.name}\n${file.length()/1048576} MB · ${if(key in queued) "QUEUED" else "LOCAL"}",13f,Color.BLACK))
            layout.addView(line)
        }
        val dialog=AlertDialog.Builder(this).setTitle("Clip library · $pid")
            .setView(ScrollView(this).apply { addView(layout) })
            .setNegativeButton("Close",null)
            .setNeutralButton("Queue selected") { _,_ ->
                var added=0
                for(file in files) if(file.uri.toString() in selected) {
                    if(ledger.enqueueManual(file,pid)=="Queued") added++
                }
                AlertDialog.Builder(this).setTitle("Upload queue")
                    .setMessage("$added clips added. Start upload to send them.")
                    .setPositiveButton("View queue") { _,_ -> showUploadQueue() }
                    .setNegativeButton("Close",null).show()
            }.create()
        dialog.show()
    }

    private fun showUploadQueue() {
        val pid=project.text.toString().trim()
        val ledger=ProjectLedger(this)
        val queue=ledger.manualPending(pid)
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(8),dp(14),dp(8)) }
        layout.addView(text("${queue.size} waiting · Priority clips upload first",13f,Color.DKGRAY))
        var queueDialog:AlertDialog?=null
        for(uriString in queue.take(150)) {
            val file=DocumentFile.fromSingleUri(this,Uri.parse(uriString))
            val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL }
            line.addView(text(file?.name ?: "Clip",13f,Color.BLACK),LinearLayout.LayoutParams(0,-2,1f))
            line.addView(button("Next") {
                ledger.prioritize(uriString,pid)
                queueDialog?.dismiss()
                showUploadQueue()
            })
            line.addView(button("Remove") {
                ledger.removeFromQueue(uriString,pid)
                queueDialog?.dismiss()
                showUploadQueue()
            })
            layout.addView(line)
        }
        AlertDialog.Builder(this).setTitle("Upload queue · $pid")
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Close",null).create().also { queueDialog=it;it.show() }
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
    private fun savedCacheUri():Uri? {
        val raw=prefs.getString("tree_uri",null) ?: return null
        return try {
            val uri=Uri.parse(raw)
            val granted=contentResolver.persistedUriPermissions.any { it.uri==uri && it.isReadPermission }
            if(granted) uri else null
        }catch(_:Exception) { null }
    }
    private fun cacheAccessOk():Boolean {
        val uri=savedCacheUri() ?: return false
        return try { DocumentFile.fromTreeUri(this,uri)?.canRead()==true }catch(_:Exception) { false }
    }
    private fun showPermissionStatus() {
        val overlayGranted=Build.VERSION.SDK_INT<23 || Settings.canDrawOverlays(this)
        val cacheGranted=cacheAccessOk()
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(20),dp(8),dp(20),dp(6))
        }
        body.addView(text("ACCESS STATUS",12f,accent).apply {
            letterSpacing=0.08f
            setTypeface(typeface,android.graphics.Typeface.BOLD)
        })
        body.addView(text("Permissions",20f,Color.WHITE).apply {
            setTypeface(typeface,android.graphics.Typeface.BOLD)
        })
        body.addView(text("●  Overlay: ${if(overlayGranted) "Ready" else "Permission needed"}",
            14f,if(overlayGranted) accent else Color.rgb(255,174,106)))
        body.addView(text("●  CACHE_VIDEO: ${if(cacheGranted) "Ready" else "Permission needed"}",
            14f,if(cacheGranted) accent else Color.rgb(255,174,106)))
        body.addView(text(
            "These permissions are saved across restarts. Android may require new screen capture permission for each metadata session.",
            12f,muted))
        val dialog=AlertDialog.Builder(this).setView(body)
            .setNegativeButton("Close",null)
        if(!cacheGranted) dialog.setPositiveButton("Choose folder") { _,_ -> chooseFolder() }
        else if(!overlayGranted) dialog.setPositiveButton("Allow overlay") { _,_ ->
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
        }
        else dialog.setNeutralButton("Change folder") { _,_ -> chooseFolder() }
        dialog.show()
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
            try {
                val flags=(data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if(flags==0) throw SecurityException("Folder picker did not grant read access")
                contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if(contentResolver.persistedUriPermissions.none { it.uri==uri && it.isReadPermission })
                    throw SecurityException("Android did not persist the folder permission")
                prefs.edit().putString("tree_uri",uri.toString()).apply()
                refreshFolderLabel()
                showMediaLibraryPage()
            }catch(e:Exception) {
                showOffsetError("CACHE_VIDEO access needed",
                    "Android didn't remember access to the selected folder. Select CACHE_VIDEO once more.",
                    e.localizedMessage, "Choose folder") { chooseFolder() }
            }
        }
    }
    private fun refreshFolderLabel() {
        val uri=savedCacheUri()
        val name=uri?.let { DocumentFile.fromTreeUri(this,it)?.name }
        folderLabel.text=if(name==null) "CACHE_VIDEO access needed (choose once)" else "CACHE_VIDEO ready: $name"
    }
    private fun resumeSavedUploader() {
        if(!prefs.getBoolean("start_on_boot",true) || prefs.getBoolean("upload_paused",false)) return
        val base=prefs.getString("server","").orEmpty()
        val tokenValue=prefs.getString("token","").orEmpty()
        val pid=prefs.getString("project","").orEmpty()
        if(!base.startsWith("https://") || tokenValue.isBlank() || pid.isBlank() || !cacheAccessOk()) {
            // Do not clear the saved folder or request permission every boot.
            folderLabel.text="Setup needed: check project, connection or CACHE_VIDEO access"
            return
        }
        try {
            val service=Intent(this,UploadService::class.java)
            if(Build.VERSION.SDK_INT>=26) startForegroundService(service)
            else startService(service)
            prefs.edit().putBoolean("boot_resume_pending",false).apply()
            folderLabel.text="Monitoring resumed automatically"
        }catch(e:Exception) {
            folderLabel.text="Auto resume unavailable: ${e.localizedMessage ?: "Android restriction"}"
        }
    }
    private fun saveAndStart() {
        val base=server.text.toString().trim().trimEnd('/')
        val pid=project.text.toString().trim();val device=token.text.toString().trim()
        if(base.isBlank()||pid.isBlank()||device.isBlank()||!cacheAccessOk()) {
            folderLabel.text="Complete server, project, token and cache folder first";return
        }
        if(!base.startsWith("https://")&&!base.contains("localhost")&&!base.contains("10.0.2.2")) {
            folderLabel.text="HTTPS required";return
        }
        // Explicit Start clears the temporary pause until the next shutdown.
        prefs.edit().putBoolean("upload_paused",false).apply()
        // Never silently change the project of a running service.
        stopService(Intent(this,UploadService::class.java))
        prefs.edit().putString("server",base).putString("project",pid).putString("token",device).apply()
        val i=Intent(this,UploadService::class.java)
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i) else startService(i)
        folderLabel.text="Monitoring started · existing cache files blocked"
    }
}
