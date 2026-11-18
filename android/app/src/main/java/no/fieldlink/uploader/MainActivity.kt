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
    private val pickSdCard=4103
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
    private var settingsView:View?=null
    private var settingsOpen=false
    private lateinit var startUploadButton:Button
    private lateinit var stopUploadButton:Button
    private lateinit var uploadChoiceLabel:TextView
    private fun refreshUploadChoice() {
        if(!::startUploadButton.isInitialized) return
        val paused=prefs.getBoolean("upload_paused",false)
        fun style(control:Button,selected:Boolean,label:String) {
            control.text=if(selected) "✓ $label" else label
            control.isSelected=selected
            control.setTextColor(if(selected) bg else Color.WHITE)
            control.background=getDrawable(if(selected) R.drawable.offset_button_primary else R.drawable.offset_button_background)
            control.contentDescription="$label, ${if(selected) "selected" else "not selected"}"
        }
        style(startUploadButton,!paused,"Start upload")
        style(stopUploadButton,paused,"Stop upload")
        uploadChoiceLabel.text=if(paused) "UPLOADS OFF · file monitoring continues"
            else "UPLOADS ON · sends when connection and battery allow"
        uploadChoiceLabel.setTextColor(if(paused) muted else accent)
    }
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
        text=label;setTextColor(Color.WHITE)
        OffsetButtons.compact(this)
        elevation=0f
        backgroundTintList=null
        background=getDrawable(R.drawable.offset_button_background)
        setOnClickListener { action() }
    }
    private fun showButtonMenu(title:String,actions:List<Pair<String,()->Unit>>) {
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(4),dp(16),dp(8))
        }
        val dialog=OffsetDialogBuilder(this).setTitle(title)
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton("Close",null).create()
        actions.forEach { (label,action) ->
            body.addView(button(label) { dialog.dismiss();action() }.apply {
                gravity=android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                minHeight=dp(36);minimumHeight=dp(36)
                setPadding(dp(12),dp(6),dp(12),dp(6))
            },LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,dp(4),0,dp(4)) })
        }
        dialog.show()
    }
    private fun primaryButton(label:String, action:()->Unit)=button(label,action).apply {
        setTextColor(bg)
        background=getDrawable(R.drawable.offset_button_primary)
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
        val builder=OffsetDialogBuilder(this)
            .setView(header)
            .setNegativeButton("Close",null)
        if(actionLabel!=null && action!=null) builder.setPositiveButton(actionLabel) { _,_ -> action() }
        val technical=detail?.takeIf { it.isNotBlank() }
        if(technical!=null) builder.setNeutralButton("Details") { _,_ ->
            OffsetDialogBuilder(this).setTitle("Technical details")
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
        OffsetDialogBuilder(this).setView(body)
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
    override fun onStart() {
        MetadataCaptureGate.setOffsetVisible(true,android.os.SystemClock.elapsedRealtime())
        super.onStart()
    }
    override fun onResume() {
        super.onResume()
        refreshUploadChoice()
        // Restore the pill after an in-place upgrade or the first overlay grant.
        // No permission dialog is shown if Android already granted access.
        if(prefs.getBoolean("start_on_boot",true) &&
            prefs.getBoolean("overlay",true) &&
            (Build.VERSION.SDK_INT<23 || Settings.canDrawOverlays(this))) {
            try { ControllerOverlayService.launch(this) }catch(_:Exception) {}
        }
    }

    override fun onStop() {
        MetadataCaptureGate.setOffsetVisible(false,android.os.SystemClock.elapsedRealtime())
        // A manual end-of-day SD copy must never continue when DJI Pilot comes back.
        if(sdBackupRunning)sdBackupCancel=true
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if(settingsOpen) showMediaLibraryPage() else super.onBackPressed()
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        // Seed defaults before creating the fields or starting the uploader.
        // Existing controller-specific overrides always take precedence.
        val initial=prefs.edit()
        if(!prefs.contains("server")) {
            initial.putString("server","https://fieldlink-xrd1.onrender.com")
        }
        if(!prefs.contains("token") && BuildConfig.DEFAULT_DEVICE_TOKEN.isNotBlank()) {
            initial.putString("token",BuildConfig.DEFAULT_DEVICE_TOKEN)
        }
        initial.apply()
        window.statusBarColor=bg;window.navigationBarColor=bg
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(18),dp(12),dp(18),dp(14))
        }
        fun row(vararg views:Button):LinearLayout = LinearLayout(this).apply {
            views.forEachIndexed { index, child ->
                addView(child,LinearLayout.LayoutParams(0,-2,1f).apply {
                    if(index>0) leftMargin=dp(6)
                })
            }
        }
        fun addSection(section:View) {
            root.addView(section,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        }
        val header=LinearLayout(this).apply {
            gravity=android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(18),dp(8),dp(18),dp(4))
            addView(button("‹ Media Library") { showMediaLibraryPage() },
                LinearLayout.LayoutParams(-2,-2))
            addView(text("Settings",19f).apply {
                setTypeface(typeface,android.graphics.Typeface.BOLD)
            },LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(12) })
        }
        server=field("HTTPS server",prefs.getString("server","")?:"")
        project=field("Project ID",prefs.getString("project","")?:"")
        token=field("Device token",prefs.getString("token",null) ?: BuildConfig.DEFAULT_DEVICE_TOKEN,true)

        val upload=card("CONTROLLER STATUS")
        projectSummary=text(
            prefs.getString("project_name","")?.takeIf { it.isNotBlank() }
                ?: project.text.toString().ifBlank { "No project selected" },
            15f
        )
        upload.addView(projectSummary)
        folderLabel=text("",12f,muted)
        upload.addView(folderLabel)
        upload.addView(row(
            button("Camera metadata") { showCameraMetadataStatus() },
            button("Permissions") { showPermissionStatus() }
        ))
        val metadataState=text("",12f,muted)
        upload.addView(metadataState)
        metadataState.post(object:Runnable {
            override fun run() {
                if(!metadataState.isAttachedToWindow)return
                val active=ScreenCaptureService.isReading
                metadataState.text=if(active) "Camera metadata: ON" else "Camera metadata: OFF · tap Camera metadata to enable"
                metadataState.setTextColor(if(active) accent else Color.rgb(255,104,106))
                metadataState.postDelayed(this,1500)
            }
        })
        uploadChoiceLabel=text("",12f,muted)
        upload.addView(uploadChoiceLabel)
        startUploadButton=button("Start upload") { saveAndStart();refreshUploadChoice() }
        stopUploadButton=button("Stop upload") {
            prefs.edit().putBoolean("upload_paused",true).apply()
            // Keep the watcher alive so Media Library sees new DJI files.
            resumeSavedUploader(force=true)
            folderLabel.text="OFFSET off · file monitoring continues"
            refreshUploadChoice()
        }
        upload.addView(row(startUploadButton,stopUploadButton))
        refreshUploadChoice()
        addSection(upload)
        refreshFolderLabel()
        if(server.text.isBlank() || token.text.isBlank()) {
            folderLabel.text="Connection setup needed"
        }

        val setup=card("PROJECT & CONNECTION")
        setup.addView(row(
            button("Choose project") { showProjects() },
            button("Share / QR") { showProjectShare() },
            button("Connection") { showConnectionSettings() }
        ))
        setup.addView(row(
            button("Cache folder") { chooseFolder() },
            button("Add proxy files") { chooseManualFiles() },
            button("Copy to SD") { startManualSdBackup() }
        ).apply { setPadding(0,dp(6),0,0) })
        addSection(setup)

        val tools=card("MORE TOOLS")
        tools.addView(row(
            button("Server history") { showServerUploadHistory() },
            button("Local history") { showUploadHistory() },
            button("Diagnostics") { runDiagnostics() }
        ))
        val moreTools=button("More tools") {
            (tools.parent as? ViewGroup)?.removeView(tools)
            OffsetDialogBuilder(this).setTitle("More tools")
                .setView(ScrollView(this).apply { addView(tools) })
                .setPositiveButton("Close",null).show()
        }
        val settings=card("Upload preferences")
        val lowData=Switch(this).apply {
            text="Low Data Mode"
            textSize=13f
            setTextColor(Color.WHITE)
            isChecked=prefs.getBoolean("low_data_mode",false)
            setOnCheckedChangeListener { _,enabled ->
                prefs.edit().putBoolean("low_data_mode",enabled)
                    .putBoolean("low_data_suggestion_pending",false).apply()
            }
        }
        settings.addView(lowData)
        if(prefs.getBoolean("low_data_suggestion_pending",false) && !prefs.getBoolean("low_data_mode",false)) {
            settings.addView(text("Slow uploads detected. Consider enabling Low Data Mode for upcoming clips. Existing uploads are not changed.",11f,muted))
            settings.addView(button("Enable Low Data Mode") {
                prefs.edit().putBoolean("low_data_mode",true).putBoolean("low_data_suggestion_pending",false).apply()
                lowData.isChecked=true
            })
            settings.addView(button("Not now") {
                prefs.edit().putBoolean("low_data_suggestion_pending",false).apply()
            })
        }
        settings.addView(text(
            "Off (default): send original DJI cache. On: create a smaller 480p copy for new clips, keeping the original untouched. Existing uploads keep their original quality.",
            11f,muted
        ))
        settings.addView(text("SD copy is manual only. Press the backup button when flying is finished; OFFSET never copies to SD automatically.",11f,muted))
        settings.addView(button("Choose SD card / change folder") { chooseSdCard() })
        val lowBattery=Switch(this).apply {
            text="Low Battery Protection · 20%"
            textSize=13f
            setTextColor(Color.WHITE)
            isChecked=prefs.getBoolean("low_battery_protection",true)
            setOnCheckedChangeListener { _,enabled ->
                prefs.edit().putBoolean("low_battery_protection",enabled).apply()
            }
        }
        settings.addView(lowBattery)
        settings.addView(text(
            "On (default): pause uploading below 20% controller battery. Resume automatically at 25%. Queued clips and upload progress are preserved.",
            11f,muted
        ))
        // Small, persisted controls: all evaluated live by the uploader.
        fun operationToggle(title:String,key:String,default:Boolean,help:String) {
            val control=Switch(this).apply {
                text=title
                textSize=13f
                setTextColor(Color.WHITE)
                isChecked=prefs.getBoolean(key,default)
                setOnCheckedChangeListener { _,enabled ->
                    prefs.edit().putBoolean(key,enabled).apply()
                }
            }
            settings.addView(control)
            settings.addView(text(help,11f,muted))
        }
        settings.addView(text("UPLOAD",12f,accent))
        operationToggle("Wi-Fi only","wifi_only",false,
            "Pause cloud uploads when Wi-Fi is unavailable. SD copying still runs offline.")
        operationToggle("Upload only while charging","charging_only",false,
            "Cloud transfers wait for external power. Current upload offset is preserved.")
        operationToggle("Critical error notifications","error_alerts",true,
            "Notify once for important recurring failures; normal clip uploads stay quiet.")

        settings.addView(text("SD CARD",12f,accent))
        operationToggle("Show SD backup status","sd_backup_status",true,
            "Show recent manual SD copies and free space when available.")
        settings.addView(button("SD backup status") { showSdBackupStatus() })

        settings.addView(text("ADVANCED",12f,accent))
        operationToggle("Startup health check","startup_health_check",true,
            "Quietly verify cache, project and server at startup; only warn if action is needed.")
        operationToggle("Adaptive upload speed","adaptive_speed",false,
            "Gently slow upload chunks while DJI Pilot is visible or the controller is warm.")
        operationToggle("Smart recovery","smart_recovery",true,
            "Retry transient errors with backoff and resume from server-confirmed bytes. Never discard originals.")
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
                "Floating status starts at boot. On Android 15+, uploads resume automatically when you open OFFSET; Android does not allow starting a data-sync foreground service directly at boot."
            else
                "Automatically resume the uploader when the controller restarts, if project and cache folder are configured.",
            12f,muted
        ))

        val overlay=Switch(this).apply { text="Floating status";setTextColor(Color.WHITE);isChecked=prefs.getBoolean("overlay",true)
            setOnCheckedChangeListener { _,enabled ->
                prefs.edit().putBoolean("overlay",enabled).apply()
                if(!enabled) stopService(Intent(this@MainActivity,ControllerOverlayService::class.java))
                else if(Build.VERSION.SDK_INT<23 || Settings.canDrawOverlays(this@MainActivity)) {
                    try { ControllerOverlayService.launch(this@MainActivity) }catch(_:Exception){}
                }
            } }
        settings.addView(overlay)
        val pilotOnly=Switch(this).apply {
            text="Show floating status only in DJI Pilot 2"
            textSize=12f
            setTextColor(Color.WHITE)
            isChecked=prefs.getBoolean("overlay_pilot_only",false)
            setOnCheckedChangeListener { _,enabled ->
                prefs.edit().putBoolean("overlay_pilot_only",enabled).apply()
                if(enabled && !PilotForeground.permissionGranted(this@MainActivity)) {
                    OffsetDialogBuilder(this@MainActivity)
                        .setTitle("Allow app visibility")
                        .setMessage("To show floating status only while DJI Pilot 2 is open, Android needs one-time Usage access for OFFSET. No screen content is read. Until access is granted, floating status stays hidden in this mode.")
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
        settings.addView(text("When off, floating status can appear over other apps. When on, it appears only in DJI Pilot 2. Uploads continue in the background either way.",11f,muted))
        settings.addView(text("Camera metadata is expected ON. OFFSET always warns when screen access is missing or stops; tap Enable metadata to restore access.",12f,muted))

        settings.addView(text("FLOATING STATUS STYLE",12f,accent))
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
            "Minimal: edge indicator · Compact: brief progress · Classic: expanded status panel. Drag to either edge, tap to expand.",
            12f,muted
        ))
        settings.addView(text("Status opacity",14f,muted))
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
            isChecked=prefs.getBoolean("overlay_show_project",true)
            setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("overlay_show_project",v).apply() }
        }
        settings.addView(showStatus)
        settings.addView(showProject)
        settings.addView(text("Status settings apply after restarting the uploader.",12f,muted))
        settings.addView(button("Allow floating status over DJI Pilot 2") {
            if(Build.VERSION.SDK_INT>=23 && !Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
            else showPermissionStatus()
        })
        settings.addView(text("Old cache files are quarantined. Switching projects requires stopping and restarting the uploader.",13f,muted))
        val preferences=button("Upload preferences") {
            (settings.parent as? ViewGroup)?.removeView(settings)
            OffsetDialogBuilder(this).setTitle("Upload preferences")
                .setView(ScrollView(this).apply { addView(settings) })
                .setPositiveButton("Close",null).show()
        }
        addSection(card("PREFERENCES & TOOLS").apply {
            addView(row(preferences,moreTools,button("Check for updates") { checkForUpdates() }))
        })
        settingsView=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
            addView(header)
            addView(ScrollView(this@MainActivity).apply { addView(root) },
                LinearLayout.LayoutParams(-1,0,1f))
        }
        showMediaLibraryPage()
        if(intent?.getBooleanExtra("request_metadata_access",false)==true) {
            window.decorView.post { showMetadataPermissionPrompt() }
            intent?.removeExtra("request_metadata_access")
        }
        if(intent?.getBooleanExtra("request_permission_help",false)==true) {
            window.decorView.post { showPermissionStatus() }
            intent?.removeExtra("request_permission_help")
        }
        // No daily "resume" dialog: restore the last approved project and
        // folder automatically when the user opens OFFSET after boot.
        val bootPending=prefs.getBoolean("boot_resume_pending",false)
        if(prefs.getBoolean("start_on_boot",true)) {
            window.decorView.post { resumeSavedUploader() }
        } else if(bootPending) {
            prefs.edit().putBoolean("boot_resume_pending",false).apply()
        }
        if(prefs.getBoolean("startup_health_check",true))window.decorView.post {
            checkStartupHealth()
        }
    }
    private fun checkStartupHealth() {
        val pid=prefs.getString("project","").orEmpty()
        val base=prefs.getString("server","").orEmpty().trimEnd('/')
        val key=prefs.getString("token","").orEmpty()
        // No repeated modal prompts during controller startup or when unconfigured.
        if(pid.isBlank())return
        val problems=mutableListOf<String>()
        if(!cacheAccessOk())problems.add("DJI cache access missing")
        if(!base.startsWith("https://")||key.isBlank())problems.add("Secure server connection not configured")
        if(problems.isNotEmpty()) {
            val now=System.currentTimeMillis()
            val last=prefs.getLong("startup_health_warning_at",0L)
            if(now-last>3600_000L) {
                prefs.edit().putLong("startup_health_warning_at",now).apply()
                showOffsetError("OFFSET startup check",problems.joinToString("\n"),
                    "Uploads cannot start until these settings are corrected.")
            }
            return
        }
        // Network availability can change during flight; an offline startup
        // is only a status hint, never an alert requiring operator action.
        if(!ControllerPolicy.wifiAvailable(this) && prefs.getBoolean("wifi_only",false)) {
            folderLabel.text="Wi-Fi only · uploads will resume on Wi-Fi"
        }
    }

    @Volatile private var sdBackupRunning=false
    @Volatile private var sdBackupCancel=false

    private fun startManualSdBackup() {
        if(sdBackupRunning) {
            OffsetDialogBuilder(this).setMessage("An SD backup is already running.")
                .setPositiveButton("OK",null).show()
            return
        }
        val pid=prefs.getString("project","").orEmpty()
        if(pid.isBlank() || !cacheAccessOk()) {
            OffsetDialogBuilder(this).setMessage("Select a project and grant DJI cache access first.")
                .setPositiveButton("OK",null).show()
            return
        }
        if(!sdCardAccessOk()) {
            OffsetDialogBuilder(this).setTitle("SD card needed")
                .setMessage("Choose the writable offset folder on your SD card, then press Copy rushes to SD card again.")
                .setPositiveButton("Choose SD card") { _,_ -> chooseSdCard() }
                .setNegativeButton("Cancel",null).show()
            return
        }
        OffsetDialogBuilder(this).setTitle("End-of-day SD backup")
            .setMessage("Copy verified, project-owned original rushes to SD now? No automatic SD copying takes place during flight. Leave OFFSET open while copying. DJI originals are never deleted.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Start copying") { _,_ -> runManualSdBackup(pid) }
            .show()
    }

    private fun runManualSdBackup(pid:String) {
        sdBackupRunning=true
        sdBackupCancel=false
        val progress=android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100 }
        val detail=text("Preparing project rushes…",12f,Color.WHITE)
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(14),dp(18),dp(12))
            addView(detail)
            addView(progress)
        }
        val dialog=OffsetDialogBuilder(this).setTitle("Copy rushes to SD card")
            .setView(body)
            .setNegativeButton("Stop copy") { _,_ -> sdBackupCancel=true }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        val projectName=prefs.getString("project_name","Project").orEmpty()
        Thread {
            var copied=0
            var skipped=0
            var failures=0
            var total=0
            var doneBytes=0L
            val ledger=ProjectLedger(this)
            try {
                val files=ledger.sdEligible(pid)
                total=files.size
                val bytesTotal=files.sumOf { it.size.coerceAtLeast(0) }.coerceAtLeast(1)
                val mirror=SdRushesMirror(this)
                for((index,entry) in files.withIndex()) {
                    if(sdBackupCancel || prefs.getString("project","")!=pid)break
                    val document=try { DocumentFile.fromSingleUri(this,Uri.parse(entry.uri)) }catch(_:Exception){null}
                    if(document==null || !document.isFile || document.length()!=entry.size ||
                       document.lastModified()!=entry.modified) {
                        skipped++
                        ledger.logEvent(pid,"unknown","sd_error","Source file missing or changed; not copied")
                        continue
                    }
                    val filename=document.name ?: "clip.mp4"
                    runOnUiThread {
                        detail.text="Clip ${index+1}/$total · $filename"
                        progress.progress=((doneBytes*100)/bytesTotal).toInt().coerceIn(0,100)
                    }
                    try {
                        var transferred=0L
                        var lastTick=0L
                        val result=mirror.copy(document,pid,projectName,{
                            !sdBackupCancel && prefs.getString("project","")==pid
                        },{ delta ->
                            transferred+=delta
                            val now=System.currentTimeMillis()
                            if(now-lastTick>=500L) {
                                lastTick=now
                                val pct=(((doneBytes+transferred).coerceAtMost(bytesTotal))*100/bytesTotal)
                                    .toInt().coerceIn(0,100)
                                runOnUiThread { progress.progress=pct }
                            }
                        })
                        if(result) {
                            copied++
                            ledger.logEvent(pid,filename,"sd_done","Verified manual SD backup")
                        }else skipped++
                    }catch(e:Exception) {
                        if(sdBackupCancel)break
                        failures++
                        ledger.logEvent(pid,filename,"sd_error",e.message ?: "SD copy failed")
                    }
                    doneBytes+=entry.size.coerceAtLeast(0)
                    val pct=((doneBytes*100)/bytesTotal).toInt().coerceIn(0,100)
                    runOnUiThread { progress.progress=pct }
                }
            }catch(e:Exception) {
                failures++
                ledger.logEvent(pid,"SD backup","sd_error",e.message ?: "Could not start SD copy")
            }finally {
                sdBackupRunning=false
                val stopped=sdBackupCancel
                runOnUiThread {
                    dialog.dismiss()
                    OffsetDialogBuilder(this).setTitle(if(stopped) "SD backup stopped" else "SD backup finished")
                        .setMessage("Verified: $copied / $total\nSkipped: $skipped\nErrors: $failures\n\nDJI originals remain untouched. You can run this again to retry.")
                        .setPositiveButton("Close",null).show()
                }
            }
        }.start()
    }

    private fun showSdBackupStatus() {
        val pid=prefs.getString("project","").orEmpty()
        if(!prefs.getBoolean("sd_backup_status",true)) {
            OffsetDialogBuilder(this).setTitle("SD backup status")
                .setMessage("Enable SD backup status in Upload preferences.")
                .setPositiveButton("Close",null).show()
            return
        }
        val events=if(pid.isBlank()) emptyList() else ProjectLedger(this).history(pid,500)
        val copied=events.count { it.status=="sd_done" }
        val failures=events.count { it.status=="sd_error" }
        val access=sdCardAccessOk()
        val free=ControllerPolicy.sdFreeBytes(this)
        val space=if(free!=null) String.format(java.util.Locale.US,"%.1f GB",(free.toDouble()/1073741824.0))
                  else "Unavailable (Android SD volume access)"
        OffsetDialogBuilder(this).setTitle("SD backup status")
            .setMessage("Project: ${if(pid.isBlank()) "Not selected" else pid}\n" +
                "SD copying: Manual only\n" +
                "SD access: ${if(access) "Ready" else "Unavailable"}\n" +
                "Verified copies in recent history: $copied\n" +
                "Recent copy errors: $failures\n" +
                "Free space: $space\n\n" +
                "Counters show recent recorded events, not a full inventory of the SD card.")
            .setPositiveButton("Close",null).show()
    }

    private fun runDiagnostics() {
        val base=normalizedServerUrl(server.text.toString())
        val device=token.text.toString().trim()
        val pid=project.text.toString().trim()
        val local=mutableListOf<String>()
        val folderReady=cacheAccessOk()
        local.add(if(folderReady) "✓ DJI cache access" else "✕ DJI cache permission missing")
        if(prefs.getString("sd_tree_uri",null)!=null) {
            local.add(if(sdCardAccessOk()) "✓ SD backup access ready"
                      else "✕ SD backup selected but card unavailable or read-only")
        } else local.add("· SD backup disabled")

        val auto=prefs.getBoolean("start_on_boot",true)
        local.add(if(auto) "✓ Auto-resume enabled" else "· Auto-resume disabled")
        val overlayEnabled=prefs.getBoolean("overlay",true)
        val overlayAllowed=Build.VERSION.SDK_INT<23 || Settings.canDrawOverlays(this)
        local.add(when {
            !overlayEnabled -> "· Floating status disabled"
            overlayAllowed -> "✓ Floating status permission"
            else -> "✕ Floating status permission missing"
        })
        val space=filesDir.usableSpace
        local.add(if(space >= 150L*1024*1024) "✓ Local working space"
                  else "✕ Local working space low")
        local.add(if(pid.isNotBlank()) "✓ Project selected" else "✕ No project selected")
        val heading="OFFSET diagnostics"
        if(!base.startsWith("https://") || device.isBlank()) {
            showOffsetError(heading,local.joinToString("\n")+
                "\n✕ Connection not configured","Server or device token missing")
            return
        }
        // One read-only network call; never create/upload a test clip in diagnostics.
        Thread {
            val result=try {
                val req=Request.Builder().url("$base/api/device/projects")
                    .header("X-Device-Token",device).get().build()
                OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS)
                    .readTimeout(15,TimeUnit.SECONDS).build().newCall(req).execute().use { response ->
                        when(response.code) {
                            in 200..299 -> "✓ Server reachable · Device authorized"
                            401,403 -> "✕ Device token rejected by server"
                            else -> "✕ Server returned HTTP ${response.code}"
                        }
                    }
            }catch(e:Exception) { "✕ Server unreachable · check network" }
            runOnUiThread {
                OffsetDialogBuilder(this).setTitle(heading)
                    .setMessage((local+result).joinToString("\n")+
                        "\n\nChecks are read-only. No test footage was uploaded.")
                    .setPositiveButton("Close",null).show()
            }
        }.start()
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
        OffsetDialogBuilder(this).setTitle("Connection settings")
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
        // Project selection is the only routine setup step. Persist the
        // already-prefilled connection before the automatic service restart.
        prefs.edit().putString("server",normalizedServerUrl(server.text.toString()))
            .putString("token",token.text.toString().trim())
            .putString("project",id).putString("project_name",name).commit()
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
            OffsetDialogBuilder(this).setTitle("Connection required")
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
                    OffsetDialogBuilder(this).setTitle("Manage projects")
                        .setItems(names) { _,index ->
                            val item=result.getJSONObject(index)
                            activateProject(item.getString("id"),item.optString("name","Project"))
                        }
                        .setNeutralButton("Manage / delete") { _,_ -> showProjectManagement(result) }
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

    private fun showProjectManagement(projects:org.json.JSONArray) {
        val names=Array(projects.length()) { i ->
            val item=projects.getJSONObject(i)
            "${item.optString("name")} · ${item.optInt("clips")} clips"
        }
        OffsetDialogBuilder(this).setTitle("Manage projects")
            .setItems(names) { _,index ->
                val item=projects.getJSONObject(index)
                confirmDeleteProject(item.getString("id"),
                    item.optString("name","Project"),item.optInt("clips"))
            }
            .setNegativeButton("Close",null).show()
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
        val dialog=OffsetDialogBuilder(this).setView(container)
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
        OffsetDialogBuilder(this).setTitle("Create project")
            .setView(container)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Create") { _,_ ->
                val projectName=name.text.toString().trim()
                if(projectName.isBlank()) {
                    OffsetDialogBuilder(this).setMessage("Project name is required.")
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
                                OffsetDialogBuilder(this).setTitle("Project created")
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
            OffsetDialogBuilder(this).setTitle("Check for updates")
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
                    val dialog=OffsetDialogBuilder(this).setTitle("App updates")
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
            OffsetDialogBuilder(this).setTitle("Connection details required")
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
                OffsetDialogBuilder(this).setTitle("Connection test")
                    .setMessage(result).setPositiveButton("OK",null).show()
            }
        }.start()
    }

    private fun showServerUploadHistory() {
        val base=server.text.toString().trim().trimEnd('/')
        val pid=project.text.toString().trim()
        val device=token.text.toString().trim()
        if(!base.startsWith("https://") || pid.isBlank() || device.isBlank()) {
            OffsetDialogBuilder(this).setTitle("Server upload history")
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
                    text=result;setTextColor(Color.rgb(244,247,246));textSize=14f
                    setPadding(dp(18),dp(12),dp(18),dp(12))
                }
                OffsetDialogBuilder(this).setTitle("Server upload history")
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
            setTextColor(Color.rgb(244,247,246))
            textSize=14f
            setPadding(dp(18),dp(12),dp(18),dp(12))
        }
        val scroll=ScrollView(this).apply { addView(historyText) }
        OffsetDialogBuilder(this).setTitle("Upload history · local")
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
        OffsetDialogBuilder(this).setTitle("Share · $projectName")
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
            intent.removeExtra("request_metadata_access")
        }
        if(intent?.getBooleanExtra("request_permission_help",false)==true) {
            showPermissionStatus()
            intent.removeExtra("request_permission_help")
        }
    }

    private fun showMetadataPermissionPrompt() {
        showOffsetConfirm("Camera metadata access",
            "Allow OFFSET to read the DJI Pilot display for camera metadata during this session? Android will ask for screen capture permission. You can stop it at any time.",
            "Continue") { startMetadataCapture() }
    }

    private fun showCameraMetadataStatus() {
        val active=ScreenCaptureService.isReading
        val readAt=prefs.getLong("metadata_last_read_at",0L)
        val fields=prefs.getString("metadata_last_fields","").orEmpty()
        val age=if(readAt>0L)((System.currentTimeMillis()-readAt)/1000).coerceAtLeast(0) else -1L
        val reading=if(age>=0) "Last external screen reading: $age seconds ago" else "No external screen readings recorded"
        val detected=if(fields.isBlank()) "No labelled camera fields recognized. Open the DJI Pilot camera view." else "Recognized: $fields"
        OffsetDialogBuilder(this).setTitle("Camera metadata")
            .setMessage("Screen access: ${if(active) "ON" else "OFF"}\n$reading\n$detected\n\n"+
                prefs.getString("metadata_last_delivery","No camera fields attached yet.").orEmpty()+
                "\n\nReading pauses while OFFSET is open, preserving the last external-screen result. Open DJI Pilot for 10-15 seconds, then return here. Existing clips are not filled with current settings.")
            .setNegativeButton("Close",null)
            .setNeutralButton("Reading details") { _,_ ->
                OffsetDialogBuilder(this).setTitle("HUD reading details")
                    .setMessage(ScreenCaptureService.lastReadingDetails)
                    .setPositiveButton("Close",null).show()
            }
            .setPositiveButton(if(active) "Restart reading" else "Enable reading") { _,_ ->
                stopService(Intent(this,ScreenCaptureService::class.java))
                startMetadataCapture()
            }.show()
    }

    private fun startMetadataCapture() {
    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    startActivityForResult(manager.createScreenCaptureIntent(), 4321)
}



    private data class LibraryClip(val file:DocumentFile)
    private data class CloudClip(val id:String,val filename:String,val bytes:Long)

    /** Operator-only local player. No decoding or playback runs in the background. */
    private fun showLocalClipPreview(file:DocumentFile, playlist:List<DocumentFile>) {
        val files=playlist.filter { it.isFile && it.name.orEmpty().endsWith(".mp4",true) }
        if(files.isEmpty()) {
            showOffsetError("Preview unavailable","No locally accessible MP4 files.")
            return
        }
        var index=files.indexOfFirst { it.uri==file.uri }.coerceAtLeast(0)
        // VideoView draws to a SurfaceView behind the window. An opaque
        // background on the VideoView itself can cover the decoded picture.
        val player=android.widget.VideoView(this)
        val playerStatus=text("Preparing preview…",11f,muted)
        val info=text("Loading clip details…",11f,muted)
        val position=text("00:00 / 00:00",11f,muted).apply {
            typeface=android.graphics.Typeface.MONOSPACE
        }
        val seek=SeekBar(this).apply {
            max=1000
            progressTintList=android.content.res.ColorStateList.valueOf(accent)
            thumbTintList=progressTintList
            contentDescription="Playback position"
        }
        var scrubbing=false
        var durationMs=0
        var playbackInteraction:()->Unit={}
        val playback=primaryButton("▶ Play") {
            if(player.isPlaying) {
                player.pause()
            } else {
                player.start()
            }
            playbackInteraction()
        }
        val handler=android.os.Handler(android.os.Looper.getMainLooper())
        val ticker=object:Runnable {
            override fun run() {
                if(!scrubbing) {
                    try {
                        val total=player.duration.coerceAtLeast(0)
                        val current=player.currentPosition.coerceAtLeast(0)
                        if(total>0) {
                            durationMs=total
                            seek.progress=((current.toLong()*1000)/total).toInt().coerceIn(0,1000)
                        }
                        fun clock(ms:Int):String {
                            val seconds=ms.coerceAtLeast(0)/1000
                            return "%02d:%02d".format(java.util.Locale.US,seconds/60,seconds%60)
                        }
                        position.text="${clock(current)} / ${clock(durationMs)}"
                        playback.text=if(player.isPlaying) "Ⅱ Pause" else "▶ Play"
                    }catch(_:Exception){}
                }
                handler.postDelayed(this,250)
            }
        }
        seek.setOnSeekBarChangeListener(object:android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar:android.widget.SeekBar?) { scrubbing=true;playbackInteraction() }
            override fun onProgressChanged(bar:android.widget.SeekBar?,value:Int,fromUser:Boolean) {
                if(fromUser && durationMs>0) {
                    val seconds=(durationMs.toLong()*value/1000).toInt()/1000
                    position.text="Seek to ${seconds/60}:%02d".format(seconds%60)
                }
            }
            override fun onStopTrackingTouch(bar:android.widget.SeekBar?) {
                if(durationMs>0)try {
                    player.seekTo((durationMs.toLong()*seek.progress/1000).toInt())
                }catch(_:Exception){}
                scrubbing=false;playbackInteraction()
            }
        })
        val previous=button("◀") {}.apply { contentDescription="Previous clip" }
        val next=button("▶") {}.apply { contentDescription="Next clip" }
        val title=text("",16f,Color.WHITE).apply {
            setTypeface(typeface,android.graphics.Typeface.BOLD)
            maxLines=1
            ellipsize=android.text.TextUtils.TruncateAt.MIDDLE
        }
        val clipCount=text("",11f,accent)
        val dialog=android.app.Dialog(this)
        val close=button("✕") { dialog.dismiss() }.apply {
            contentDescription="Close preview"
            textSize=16f
        }
        fun showPlaybackError(message:String) {
            playerStatus.text=message
            playerStatus.visibility=View.VISIBLE
            playback.isEnabled=false
            seek.isEnabled=false
            playbackInteraction()
        }
        // The video occupies the whole window; controls overlay it instead of reserving half the screen.
        val content=android.widget.FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val videoStage=android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(player,android.widget.FrameLayout.LayoutParams(-1,-1,android.view.Gravity.CENTER))
        }
        content.addView(videoStage,android.widget.FrameLayout.LayoutParams(-1,-1))
        val header=LinearLayout(this).apply {
            gravity=android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12),dp(6),dp(12),dp(6))
            setBackgroundColor(Color.argb(205,17,20,22))
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.VERTICAL
                addView(clipCount);addView(title)
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(close,LinearLayout.LayoutParams(dp(44),dp(44)))
        }
        val bottom=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(6),dp(12),dp(10))
            setBackgroundColor(Color.argb(215,17,20,22))
        }
        val timeline=LinearLayout(this).apply {
            gravity=android.view.Gravity.CENTER_VERTICAL
            addView(seek,LinearLayout.LayoutParams(0,dp(44),1f));addView(position)
        }
        bottom.addView(timeline)
        bottom.addView(LinearLayout(this).apply {
            gravity=android.view.Gravity.CENTER
            addView(previous,LinearLayout.LayoutParams(dp(48),dp(44)))
            addView(playback,LinearLayout.LayoutParams(dp(108),dp(44)).apply { setMargins(dp(10),0,dp(10),0) })
            addView(next,LinearLayout.LayoutParams(dp(48),dp(44)))
        })
        bottom.addView(playerStatus)
        info.maxLines=1;info.ellipsize=android.text.TextUtils.TruncateAt.END
        bottom.addView(info)
        content.addView(header,android.widget.FrameLayout.LayoutParams(-1,-2,android.view.Gravity.TOP))
        content.addView(bottom,android.widget.FrameLayout.LayoutParams(-1,-2,android.view.Gravity.BOTTOM))
        var chromeVisible=true
        val hideChrome=Runnable {
            if(player.isPlaying&&!scrubbing){
                header.visibility=View.GONE;bottom.visibility=View.GONE;chromeVisible=false
            }
        }
        fun showChrome(){
            header.visibility=View.VISIBLE;bottom.visibility=View.VISIBLE;chromeVisible=true
            handler.removeCallbacks(hideChrome)
            if(player.isPlaying&&!scrubbing)handler.postDelayed(hideChrome,2800)
        }
        playbackInteraction={ showChrome() }
        videoStage.setOnClickListener {
            if(chromeVisible&&player.isPlaying){
                handler.removeCallbacks(hideChrome)
                header.visibility=View.GONE;bottom.visibility=View.GONE;chromeVisible=false
            }else showChrome()
        }
        // Keep controls clear of display cutouts and the system gesture area.
        content.setOnApplyWindowInsetsListener { _,insets ->
            val cutout=insets.displayCutout
            content.setPadding(maxOf(insets.systemWindowInsetLeft,cutout?.safeInsetLeft?:0),
                maxOf(insets.systemWindowInsetTop,cutout?.safeInsetTop?:0),
                maxOf(insets.systemWindowInsetRight,cutout?.safeInsetRight?:0),
                maxOf(insets.systemWindowInsetBottom,cutout?.safeInsetBottom?:0))
            insets
        }
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        var closed=false
        var requestId=0
        fun loadClip() {
            val current=files[index]
            requestId++
            val thisRequest=requestId
            durationMs=0
            seek.progress=0
            position.text="00:00 / 00:00"
            clipCount.text="LOCAL PREVIEW  ·  ${index+1} / ${files.size}"
            title.text=current.name ?: "Clip"
            playback.text="▶ Play"
            previous.isEnabled=index>0
            next.isEnabled=index<files.lastIndex
            info.text="Reading clip details…"
            playback.isEnabled=false
            seek.isEnabled=false
            playerStatus.text="Preparing preview…"
            playerStatus.visibility=View.VISIBLE
            player.stopPlayback()
            showChrome()
            player.setOnCompletionListener { showChrome() }
            player.setOnPreparedListener { media ->
                durationMs=media.duration.coerceAtLeast(0)
                playback.isEnabled=true
                seek.isEnabled=durationMs>0
                playerStatus.visibility=View.GONE
                // Render the first frame while keeping playback paused.
                if(durationMs>0) player.seekTo(1)
            }
            player.setOnErrorListener { _,_,_ ->
                showPlaybackError("Can't play this local clip. It may still be recording or use an unsupported codec.")
                true
            }
            try {
                player.setVideoURI(current.uri)
            }catch(_:Exception){
                showPlaybackError("Unable to open this local clip. Check the CACHE_VIDEO folder in Settings.")
            }
            Thread {
                val details=ControllerMediaTools.clipInfo(this,current.uri,current.lastModified())
                val duration=details.durationMs?.let {
                    val seconds=it/1000
                    "%02d:%02d".format(java.util.Locale.US,seconds/60,seconds%60)
                } ?: "Unavailable"
                val resolution=if(details.width!=null && details.height!=null)
                    "${details.width} × ${details.height}" else "Unavailable"
                val summary="$resolution  ·  $duration  ·  ${ControllerMediaTools.pretty(current.length())}"+
                    (details.recordedAt?.let { "  ·  $it" } ?: "")
                runOnUiThread {
                    if(!closed && dialog.isShowing && requestId==thisRequest)
                        info.text=summary
                }
            }.start()
        }
        previous.setOnClickListener {
            if(index>0) { index--;loadClip() }
        }
        next.setOnClickListener {
            if(index<files.lastIndex) { index++;loadClip() }
        }
        dialog.setOnDismissListener {
            closed=true
            handler.removeCallbacks(ticker);handler.removeCallbacks(hideChrome)
            try { player.stopPlayback() }catch(_:Exception){}
        }
        dialog.show()
        dialog.window?.apply {
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,android.view.WindowManager.LayoutParams.MATCH_PARENT)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
        loadClip()
        handler.post(ticker)
    }

    private fun transferLabel(file:DocumentFile,pid:String):String {
        val ledger=ProjectLedger(this)
        return try {
            val t=ledger.transferFor(file.uri.toString(),pid)?:return "Queued"
            val percent=if(t.total>0)((t.received*100)/t.total).coerceIn(0,100)else 0
            when {
                t.phase=="ready" -> "Server verified · refresh to check review"
                t.phase=="processing" -> "Received · awaiting server verification"
                prefs.getBoolean("upload_paused",false) -> "Paused · $percent% saved"
                t.phase=="waiting"||System.currentTimeMillis()-t.updated>45000 -> "Waiting to resume · $percent% confirmed"
                else -> "Uploading · $percent% confirmed"
            }
        }finally { ledger.close() }
    }
    private fun showDeliveryCheck(pid:String,files:List<DocumentFile>,remote:List<CloudClip>,available:Boolean) {
        val ledger=ProjectLedger(this)
        val scoped=files.filter { ledger.stateFor(it.uri.toString(),pid) in listOf("pending","done","quarantine") }
        val unassigned=files.count { ledger.stateFor(it.uri.toString(),pid)=="new" }
        val excluded=scoped.filter { ledger.stateFor(it.uri.toString(),pid)=="quarantine" }
        val included=scoped.filter { it !in excluded }
        val names=remote.map { it.filename }.toSet()
        val missing=included.filter { file ->
            val name=file.name.orEmpty()
            name !in names && (name.substringBeforeLast('.')+"_LOW_DATA.mp4") !in names
        }
        val body=if(!available) "Server status unavailable. Refresh the library when connected. No clips are marked delivered from local history alone."
            else "Project: $pid\nAll accessible local clips in this project (not only today's clips).\n\n"+
                "Local: ${scoped.size}\nListed on server: ${included.size-missing.size}\nNot listed on server: ${missing.size}\nExcluded: ${excluded.size}\nNot assigned to a project: $unassigned\n\n"+
                (if(missing.isEmpty())"All included local filenames are listed on the server." else "Not listed:\n"+missing.take(40).joinToString("\n") { it.name.orEmpty() })+
                "\n\nThis compares filenames with the latest library refresh. Excluded files are not counted as missing. Originals are kept on the controller."
        ledger.close()
        OffsetDialogBuilder(this).setTitle("End-of-day delivery check").setMessage(body)
            .setNegativeButton("Close",null).setPositiveButton("Copy to SD") { _,_ -> startManualSdBackup() }.show()
    }
    private fun showMediaStorage(visibleBytes:Long,visibleCount:Int) {
        val data=ControllerMediaTools.inspect(this,visibleBytes,visibleCount)
        val free=data.appFreeBytes?.let(ControllerMediaTools::pretty) ?: "Unavailable"
        val warning=if(data.appFreeBytes!=null && data.appFreeBytes<1024L*1024*1024)
            "\n\nLOW SPACE: Less than 1 GB free in OFFSET internal storage." else ""
        val body="DJI cache · visible MP4 files: ${data.djiVisibleCount}\n"+
            "DJI cache · visible MP4 size: ${ControllerMediaTools.pretty(data.djiVisibleBytes)}\n"+
            "OFFSET temporary chunks: ${ControllerMediaTools.pretty(data.appTempBytes)}\n"+
            "OFFSET low-data copies: ${ControllerMediaTools.pretty(data.lowDataBytes)}\n"+
            "Free internal storage: $free\n"+
            "Old temporary chunks (24h+): ${data.oldTempCount} · ${ControllerMediaTools.pretty(data.oldTempBytes)}"+
            warning+"\n\nDJI cache size includes visible MP4 files only. Android may store "+
            "the selected DJI folder on a different volume. No originals or "+
            "resumable low-data copies are ever deleted."
        val builder=OffsetDialogBuilder(this).setTitle("Media storage").setMessage(body)
            .setNegativeButton("Close",null)
        if(data.oldTempCount>0)builder.setPositiveButton("Clean old temp files") { _,_ ->
            showOffsetConfirm("Clean old OFFSET temp files?",
                "Delete only abandoned OFFSET multipart chunk files older than 24 hours? "+
                "Original DJI clips, active transfers and resumable low-data copies are protected.",
                "Clean",destructive=true) {
                Thread {
                    val (removed,bytes)=ControllerMediaTools.cleanOldTemporaryChunks(this)
                    runOnUiThread {
                        OffsetDialogBuilder(this).setTitle("Cleanup complete")
                            .setMessage("$removed old chunk files removed · "+
                                ControllerMediaTools.pretty(bytes)+" freed. DJI originals untouched.")
                            .setPositiveButton("Close",null).show()
                    }
                }.start()
            }
        }
        builder.show()
    }

    private fun performMediaBatchAction(pid:String,selected:List<DocumentFile>,action:String) {
        if(selected.isEmpty())return
        val title=when(action) {
            "queue" -> "Add selected clips to queue?"
            "priority" -> "Prioritize selected clips?"
            "clear_priority" -> "Remove priority from selected clips?"
            "exclude" -> "Exclude selected clips?"
            else -> return
        }
        val description=when(action) {
            "queue" -> "Add ${selected.size} selected clips to the current project's upload queue. Recently modified files will be skipped."
            "priority" -> "Mark ${selected.size} selected clips as urgent. Only pending, project-owned clips can be prioritized."
            "clear_priority" -> "Return selected urgent clips to normal queue order. Active transfers will not be interrupted."
            else -> "Exclude ${selected.size} selected clips from automatic uploads? "+
                "This does not delete the DJI originals. Active or resumable uploads will be skipped."
        }
        showOffsetConfirm(title,description,
            if(action=="exclude")"Exclude" else "Continue",
            destructive=action=="exclude") {
            Thread {
                val ledger=ProjectLedger(this)
                var changed=0
                var skipped=0
                val current=prefs.getString("project","").orEmpty()
                if(current!=pid){
                    runOnUiThread { showOffsetError("Project changed","Select the project again before changing its media.") }
                    return@Thread
                }
                for(file in selected){
                    if(prefs.getString("project","")!=pid)break
                    if(!file.isFile || file.length()<=0 || !file.name.orEmpty().endsWith(".mp4",true)){
                        skipped++;continue
                    }
                    val uri=file.uri.toString()
                    val state=ledger.stateFor(uri,pid)
                    if(state=="other" || state=="done"){
                        skipped++;continue
                    }
                    try {
                        val ok=when(action) {
                            "queue" -> {
                                // Do not queue an MP4 still being written by DJI.
                                val last=file.lastModified()
                                if(last<=0 || System.currentTimeMillis()-last<45000L)false
                                else ledger.enqueueManual(file,pid)=="Queued"
                            }
                            "priority" -> state=="pending" &&
                                ledger.getUploadId(file,pid)==null &&
                                ledger.prioritizeNext(uri,pid)
                            "clear_priority" -> state=="pending" &&
                                ledger.getUploadId(file,pid)==null &&
                                uri in ledger.urgentUris(pid) &&
                                run { ledger.clearPriority(uri,pid);true }
                            "exclude" -> state=="pending" &&
                                ledger.getUploadId(file,pid)==null &&
                                run { ledger.excludeFromAuto(file,pid);true }
                            else -> false
                        }
                        if(ok)changed++ else skipped++
                    }catch(_:Exception){skipped++}
                }
                runOnUiThread {
                    if(prefs.getString("project","")==pid)showMediaLibraryPage()
                    OffsetDialogBuilder(this).setTitle("Media selection updated")
                        .setMessage("$changed clips updated · $skipped skipped.\n"+
                            "No original DJI media was deleted.")
                        .setPositiveButton("Close",null).show()
                }
            }.start()
        }
    }

    /** Main controller screen. Cloud status is authoritative; local media is never deleted. */
    private fun showMediaLibraryPage() {
        settingsOpen=false
        val pid=project.text.toString().trim()
        val descending=prefs.getBoolean("media_library_descending",false)
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16),dp(12),dp(16),dp(18))
        }
        val header=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=android.view.Gravity.CENTER_VERTICAL
        }
        // Media Library is the actual launcher screen. Keep project, QR and
        // settings immediately available, with no intermediate dashboard.
        header.addView(button(
            (prefs.getString("project_name",null)?.takeIf { it.isNotBlank() } ?: "Select project")+"  ▾"
        ) { showProjects() },LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("▣ QR") { showProjectShare() })
        header.addView(button("⚙ Settings") {
            settingsView?.let {
                settingsOpen=true
                refreshUploadChoice()
                setContentView(it)
            }
        })
        root.addView(header)
        val nav=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=android.view.Gravity.CENTER_VERTICAL
        }
        nav.addView(text("OFFSET.  /  MEDIA LIBRARY",12f,accent).apply {
            setTypeface(typeface,android.graphics.Typeface.BOLD)
        },LinearLayout.LayoutParams(0,-2,1f))
        val sortButton=button(if(descending) "Z–A ▾" else "A–Z ▾") {}
        sortButton.contentDescription="Sort clips by filename"
        sortButton.setOnClickListener {
            showButtonMenu("Sort clips",listOf(
                (if(!descending) "✓  Filename A–Z" else "Filename A–Z") to {
                    prefs.edit().putBoolean("media_library_descending",false).apply()
                    showMediaLibraryPage()
                },
                (if(descending) "✓  Filename Z–A" else "Filename Z–A") to {
                    prefs.edit().putBoolean("media_library_descending",true).apply()
                    showMediaLibraryPage()
                }
            ))
        }
        nav.addView(sortButton)
        nav.addView(button("Queue") { showUploadQueue() })
        nav.addView(button("Refresh") { showMediaLibraryPage() })
        root.addView(nav)
        val sdRow=LinearLayout(this).apply {
            gravity=android.view.Gravity.CENTER_VERTICAL
            setPadding(0,dp(5),0,dp(5))
            addView(text("End-of-day backup",12f,muted),LinearLayout.LayoutParams(0,-2,1f))
            addView(button("SD status") { showSdBackupStatus() })
            addView(primaryButton("Copy to SD") { startManualSdBackup() },
                LinearLayout.LayoutParams(-2,-2).apply { leftMargin=dp(6) })
        }
        root.addView(sdRow)
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
            var visibleMediaBytes=0L
            var visibleMediaCount=0
            var allLocalMedia:List<DocumentFile> = emptyList()
            var thumbnailSources:Map<String,List<DocumentFile>> = emptyMap()
            try {
                val tree=cacheUri?.let { DocumentFile.fromTreeUri(this,it) }
                thumbnailSources=ControllerThumbnailLoader.index(tree)
                val byFilename=compareBy<DocumentFile> { it.name.orEmpty().uppercase() }
                val media=tree?.listFiles()?.filter { it.isFile && (it.name ?: "").endsWith(".mp4",true) }
                    ?.sortedWith(if(descending) byFilename.reversed() else byFilename).orEmpty()
                allLocalMedia=media
                visibleMediaBytes=media.sumOf { it.length().coerceAtLeast(0L) }
                visibleMediaCount=media.size
                for(file in media.take(250)) clips.add(LibraryClip(file))
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
            }else serverError="Configure your server and device token in Settings → Connection"
            runOnUiThread {
                if(project.text.toString().trim()!=pid||root.rootView!==window.decorView) return@runOnUiThread
                val ledger=ProjectLedger(this)
                val queued=ledger.queueUris(pid)
                val priorityUris=ledger.urgentUris(pid).toSet()
                val remoteByName=remote.associateBy { it.filename }
                val localNames=clips.mapNotNull { it.file.name }.toSet()
                val uploaded=clips.count { (it.file.name ?: "") in remoteByName }
                status.text="${clips.size} local  ·  ${uploaded} uploaded  ·  ${ledger.pendingQueue(pid).size} waiting  ·  ${priorityUris.size} priority" +
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
                val storageRow=LinearLayout(this).apply {
                    orientation=LinearLayout.HORIZONTAL
                    gravity=android.view.Gravity.CENTER_VERTICAL
                }
                storageRow.addView(text(
                    "DJI visible MP4s: ${ControllerMediaTools.pretty(visibleMediaBytes)}",
                    11f,muted),LinearLayout.LayoutParams(0,-2,1f))
                storageRow.addView(button("Storage") {
                    showMediaStorage(visibleMediaBytes,visibleMediaCount)
                })
                storageRow.addView(button("Day check") { showDeliveryCheck(pid,allLocalMedia,remote,cloudAvailable) })
                grid.addView(storageRow)

                val selection=LinkedHashSet<String>()
                val selectedCount=text("0 selected",11f,muted)
                val bulk=LinearLayout(this).apply {
                    orientation=LinearLayout.HORIZONTAL
                    gravity=android.view.Gravity.CENTER_VERTICAL
                }
                bulk.addView(selectedCount,LinearLayout.LayoutParams(0,-2,1f))
                val bulkQueue=button("Add to queue") {
                    performMediaBatchAction(pid,clips.filter { it.file.uri.toString() in selection && remoteByName[it.file.name.orEmpty()]==null }
                        .map { it.file },"queue")
                }
                val bulkPriority=button("Priority") {
                    performMediaBatchAction(pid,clips.filter { it.file.uri.toString() in selection && remoteByName[it.file.name.orEmpty()]==null }
                        .map { it.file },"priority")
                }
                val bulkClearPriority=button("Clear ★") {
                    performMediaBatchAction(pid,clips.filter { it.file.uri.toString() in selection && remoteByName[it.file.name.orEmpty()]==null }
                        .map { it.file },"clear_priority")
                }
                val bulkExclude=button("Exclude") {
                    performMediaBatchAction(pid,clips.filter { it.file.uri.toString() in selection && remoteByName[it.file.name.orEmpty()]==null }
                        .map { it.file },"exclude")
                }
                fun refreshBulk() {
                    selectedCount.text="${selection.size} selected"
                    for(control in listOf(bulkQueue,bulkPriority,bulkClearPriority,bulkExclude))
                        control.isEnabled=clips.any { item ->
                            item.file.uri.toString() in selection &&
                            remoteByName[item.file.name.orEmpty()]==null &&
                            ledger.stateFor(item.file.uri.toString(),pid) !in listOf("other","done")
                        }
                }
                bulk.addView(bulkQueue)
                bulk.addView(bulkPriority)
                bulk.addView(bulkClearPriority)
                bulk.addView(bulkExclude)
                grid.addView(bulk)
                refreshBulk()
                if(clips.isEmpty()) grid.addView(text("No local MP4 clips found. Cloud-only clips are listed below.",13f,muted))
                val columns=if(resources.displayMetrics.widthPixels>=dp(760)) 3 else 2
                val gap=dp(6)
                val cardWidth=((resources.displayMetrics.widthPixels-dp(32)-gap*(columns-1))/columns).coerceAtLeast(dp(130))
                val libraryGrid=android.widget.GridLayout(this).apply { columnCount=columns }
                grid.addView(libraryGrid)
                val thumbnailLoader=ControllerThumbnailLoader(this,root,thumbnailSources,cardWidth)
                val transferLabels=mutableListOf<Pair<TextView,DocumentFile>>()
                val deliveryHandler=android.os.Handler(android.os.Looper.getMainLooper())
                val deliveryTick=object:Runnable {
                    override fun run(){
                        if(!root.isAttachedToWindow)return
                        for((view,file) in transferLabels) {
                            if(view.getGlobalVisibleRect(android.graphics.Rect()))
                                view.text=transferLabel(file,pid)+" · "+view.tag
                        }
                        deliveryHandler.postDelayed(this,3000)
                    }
                }
                root.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v:View){}
                    override fun onViewDetachedFromWindow(v:View){deliveryHandler.removeCallbacks(deliveryTick)}
                })
                deliveryHandler.postDelayed(deliveryTick,3000)
                val playlist=clips.map { it.file }
                for((position,item) in clips.withIndex()) {
                    val file=item.file
                    val name=file.name ?: "clip.mp4"
                    val cloud=remoteByName[name]
                    val isQueued=file.uri.toString() in queued
                    val localState=ledger.stateFor(file.uri.toString(),pid)
                    val label=when {
                        cloud!=null -> "READY ON REVIEW"
                        file.uri.toString() in priorityUris -> "★ PRIORITY"
                        isQueued -> transferLabel(file,pid)
                        localState=="pending" -> transferLabel(file,pid)
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
                    }
                    preview.contentDescription="Preview $name"
                    preview.isClickable=true
                    preview.setOnClickListener { showLocalClipPreview(file,playlist) }
                    card.addView(preview,LinearLayout.LayoutParams(-1,(cardWidth-dp(14))*9/16))
                    thumbnailLoader.bind(preview,file)
                    card.addView(button("▶ Preview") { showLocalClipPreview(file,playlist) },
                        LinearLayout.LayoutParams(-1,dp(29)))
                    val selectRow=LinearLayout(this).apply {
                        orientation=LinearLayout.HORIZONTAL
                        gravity=android.view.Gravity.CENTER_VERTICAL
                    }
                    val selectBox=android.widget.CheckBox(this).apply {
                        text=""
                        contentDescription="Select $name"
                        buttonTintList=android.content.res.ColorStateList.valueOf(accent)
                        isEnabled=localState!="other"
                        minWidth=0
                        minHeight=0
                        setPadding(0,0,0,0)
                        setOnCheckedChangeListener { _,checked ->
                            if(checked)selection.add(file.uri.toString())
                            else selection.remove(file.uri.toString())
                            refreshBulk()
                        }
                    }
                    // The full filename row is a selection target, not just the small glyph.
                    selectRow.minimumHeight=dp(40)
                    selectRow.isEnabled=selectBox.isEnabled
                    selectRow.setOnClickListener {
                        if(selectBox.isEnabled)selectBox.toggle()
                    }
                    selectRow.addView(selectBox,LinearLayout.LayoutParams(dp(33),dp(40)))
                    selectRow.addView(text(name,12f,Color.WHITE).apply {
                        maxLines=1
                        ellipsize=android.text.TextUtils.TruncateAt.MIDDLE
                        setTypeface(typeface,android.graphics.Typeface.BOLD)
                    },LinearLayout.LayoutParams(0,-2,1f))
                    card.addView(selectRow)
                    // File timestamp is provided by SAF; thumbnail decoding is limited
                    // to visible cards. Full clip details are read on Preview.
                    val modified=file.lastModified()
                    val modifiedLabel=if(modified>0)
                        java.text.DateFormat.getDateTimeInstance(
                            java.text.DateFormat.SHORT,java.text.DateFormat.SHORT
                        ).format(java.util.Date(modified))
                        else "Unknown"
                    card.addView(text("Modified: $modifiedLabel",10f,muted))
                    val detail=LinearLayout(this).apply {
                        orientation=LinearLayout.HORIZONTAL
                        gravity=android.view.Gravity.CENTER_VERTICAL
                    }
                    val transferText=text("$label · ${file.length()/1048576} MB",10f,if(cloud!=null) accent else muted)
                    detail.addView(transferText,LinearLayout.LayoutParams(0,-2,1f))
                    if(cloud==null&&localState=="pending") {
                        transferText.tag=transferText.text.toString().substringAfterLast(" · ")
                        transferLabels.add(transferText to file)
                    }
                    val actions=mutableListOf<Pair<String,()->Unit>>()
                    if(cloud!=null && cloudAvailable) {
                        actions.add("Delete from review" to { confirmDeleteCloudClip(pid,cloud.id,name) })
                    }else if(isQueued || (localState=="pending" && prefs.getString("upload_mode","auto")=="auto")) {
                        actions.add("Prioritize next" to {
                            if(ledger.prioritizeNext(file.uri.toString(),pid))showMediaLibraryPage()
                            else showOffsetError("Priority unavailable","This clip is no longer waiting.")
                        })
                        if(file.uri.toString() in priorityUris) actions.add("Clear priority" to {
                            ledger.clearPriority(file.uri.toString(),pid)
                            showMediaLibraryPage()
                        })
                        if(isQueued) actions.add("Remove from queue" to {
                            stopService(Intent(this,UploadService::class.java))
                            ledger.removeFromQueue(file.uri.toString(),pid)
                            showMediaLibraryPage()
                        })
                    }else if(localState!="other" && localState!="done") {
                        actions.add("Add to queue" to {
                            val result=ledger.enqueueManual(file,pid)
                            if(result!="Queued") {
                                OffsetDialogBuilder(this).setMessage(result).setPositiveButton("OK",null).show()
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
                        menuButton.setOnClickListener { showButtonMenu("Clip actions",actions) }
                        detail.addView(menuButton,LinearLayout.LayoutParams(dp(38),dp(36)))
                    }
                    card.addView(detail)
                    libraryGrid.addView(card,android.widget.GridLayout.LayoutParams().apply {
                        width=cardWidth-gap/2
                        height=ViewGroup.LayoutParams.WRAP_CONTENT
                        setMargins(if(position%columns==0) 0 else gap/2,dp(5),gap/2,0)
                    })
                }
                val cloudByFilename=compareBy<CloudClip> { it.filename.uppercase() }
                for(cloud in remote.sortedWith(if(descending) cloudByFilename.reversed() else cloudByFilename)) {
                    if(cloud.filename in localNames) continue
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
            OffsetDialogBuilder(this).setTitle("Clip library")
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
            setTextColor(Color.rgb(244,247,246))
            isChecked=prefs.getString("upload_mode","auto")=="auto"
            setOnCheckedChangeListener { _,checked ->
                prefs.edit().putString("upload_mode",if(checked) "auto" else "manual").apply()
            }
        }
        layout.addView(mode)
        layout.addView(text("${files.size} clips · ${queued.size} queued · Select clips to upload",12f,muted))
        val thumbnailLoader=ControllerThumbnailLoader(this,layout,ControllerThumbnailLoader.index(root),dp(320))
        for(file in files.take(200)) {
            val key=file.uri.toString()
            val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL }
            val check=android.widget.CheckBox(this).apply {
                isChecked=key in queued
                isEnabled=key !in queued
                setOnCheckedChangeListener { _,v -> if(v) selected.add(key) else selected.remove(key) }
            }
            line.addView(check)
            val image=ImageView(this).apply {
                scaleType=ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(19,24,26))
            }
            line.addView(image,LinearLayout.LayoutParams(dp(112),dp(63)).apply { rightMargin=dp(10) })
            thumbnailLoader.bind(image,file)
            line.addView(text("${file.name}\n${file.length()/1048576} MB · ${if(key in queued) "QUEUED" else "LOCAL"}",13f,Color.rgb(244,247,246)))
            layout.addView(line)
        }
        val dialog=OffsetDialogBuilder(this).setTitle("Clip library · $pid")
            .setView(ScrollView(this).apply { addView(layout) })
            .setNegativeButton("Close",null)
            .setNeutralButton("Queue selected") { _,_ ->
                var added=0
                for(file in files) if(file.uri.toString() in selected) {
                    if(ledger.enqueueManual(file,pid)=="Queued") added++
                }
                OffsetDialogBuilder(this).setTitle("Upload queue")
                    .setMessage("$added clips added. Start upload to send them.")
                    .setPositiveButton("View queue") { _,_ -> showUploadQueue() }
                    .setNegativeButton("Close",null).show()
            }.create()
        dialog.show()
    }

    private fun showUploadQueue(searchTerm:String="") {
        val pid=prefs.getString("project","").orEmpty()
        if(pid.isBlank()){
            showOffsetError("Upload queue","Choose a project first.")
            return
        }
        val ledger=ProjectLedger(this)
        // Includes both auto-discovered DJI clips and explicitly queued files.
        val queue=ledger.pendingQueue(pid)
        val layout=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(14),dp(8),dp(14),dp(12))
            setBackgroundColor(bg)
        }
        val urgent=queue.count { it.priority }
        val summary=text("${queue.size} waiting · ${urgent} prioritized · active upload finishes first",12f,muted)
        layout.addView(summary)
        data class QueueRow(val item:ProjectLedger.QueueEntry,val status:TextView,
                            val bar:android.widget.ProgressBar,val actions:MutableList<Button>,
                            var available:Boolean,var size:Long)
        val progressRows=mutableListOf<QueueRow>()
        var queueDialog:AlertDialog?=null
        layout.addView(button(if(searchTerm.isBlank())"Find clip by name" else "Filter: $searchTerm · Change") {
            val field=EditText(this).apply {
                hint="DJI filename"
                setSingleLine(true)
                setText(searchTerm)
                setSelectAllOnFocus(true)
            }
            OffsetDialogBuilder(this).setTitle("Find clip in queue")
                .setView(field)
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Filter") { _,_ ->
                    queueDialog?.dismiss()
                    showUploadQueue(field.text.toString().trim())
                }.show()
        })
        if(searchTerm.isNotBlank())layout.addView(button("Show all waiting clips") {
            queueDialog?.dismiss()
            showUploadQueue()
        })
        if(queue.isEmpty())layout.addView(text(
            "No clips waiting yet. New DJI clips appear when OFFSET discovers them.",
            12f,muted))
        val shown=if(searchTerm.isBlank())queue.take(150) else queue.filter { item ->
            val name=try { DocumentFile.fromSingleUri(this,Uri.parse(item.uri))?.name.orEmpty() }
                catch(_:Exception){ "" }
            name.contains(searchTerm,ignoreCase=true)
        }.take(150)
        if(shown.isEmpty() && queue.isNotEmpty())layout.addView(text("No matching clips.",12f,muted))
        for(item in shown){
            val file=try { DocumentFile.fromSingleUri(this,Uri.parse(item.uri)) }catch(_:Exception){null}
            val name=file?.name ?: "Unavailable clip"
            val line=LinearLayout(this).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(10),dp(6),dp(8),dp(6))
                background=rounded(panel,if(item.priority) accent else Color.rgb(57,65,67),10)
            }
            val statusText=text("Queued",11f,muted)
            val progress=android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                max=100
                progressTintList=android.content.res.ColorStateList.valueOf(accent)
                indeterminateTintList=progressTintList
            }
            val rowActions=mutableListOf<Button>()
            line.addView(LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                addView(text(name,13f).apply {
                    setTypeface(typeface,android.graphics.Typeface.BOLD)
                    maxLines=2
                    ellipsize=android.text.TextUtils.TruncateAt.MIDDLE
                })
                val source=if(item.manual) "MANUAL" else "DJI"
                addView(text(if(item.priority) "★ NEXT  ·  $source" else source,
                    10f,if(item.priority) accent else muted))
                addView(statusText)
                addView(progress,LinearLayout.LayoutParams(-1,dp(4)).apply { topMargin=dp(4);bottomMargin=dp(2) })
            },LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(6) })
            line.addView(button(if(item.priority)"★ Next" else "Prioritize") {
                val promoted=ledger.prioritizeNext(item.uri,pid)
                if(!promoted){
                    showOffsetError("Priority unavailable","The clip is no longer pending in this project.")
                }
                queueDialog?.dismiss()
                showUploadQueue(searchTerm)
            })
            line.addView(button("Retry") {
                if(ledger.requestRetry(item.uri,pid)) {
                    prefs.edit().putBoolean("retry_requested",true).apply()
                    showOffsetError("Retry requested","The clip keeps its saved upload position and will be tried next when OFFSET is on and network/battery conditions allow.")
                }
            })
            if(item.priority)line.addView(button("Clear") {
                ledger.clearPriority(item.uri,pid)
                queueDialog?.dismiss()
                showUploadQueue(searchTerm)
            })
            if(item.manual)line.addView(button("Remove") {
                // Removing a manually queued clip still requires operator intent.
                showOffsetConfirm("Remove from queue?",
                    "Remove ${name} from the upload queue? The DJI original is not deleted.",
                    "Remove") {
                    ledger.removeFromQueue(item.uri,pid)
                    queueDialog?.dismiss()
                    showUploadQueue(searchTerm)
                }
            })
            for(i in 0 until line.childCount)(line.getChildAt(i) as? Button)?.let { rowActions.add(it) }
            progressRows.add(QueueRow(item,statusText,progress,rowActions,
                try { file?.exists()==true && file.canRead() }catch(_:Exception){false},
                try { file?.length()?:0L }catch(_:Exception){0L}))
            layout.addView(line,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
        }
        val uiHandler=android.os.Handler(android.os.Looper.getMainLooper())
        var lastFileCheck=0L
        val updater=object:Runnable {
            override fun run() {
                if(queueDialog?.isShowing!=true)return
                if(prefs.getString("project","")!=pid) { queueDialog?.dismiss();return }
                try {
                    val now=System.currentTimeMillis()
                    val pending=ledger.pendingQueue(pid).map { it.uri }.toSet()
                    val transfers=ledger.transfers(pid)
                    val paused=prefs.getBoolean("upload_paused",false)
                    val manual=prefs.getString("upload_mode","auto")=="manual"
                    val eligible=if(manual)(ledger.manualPending(pid)+ledger.urgentUris(pid)).toSet() else pending
                    val policy=ControllerPolicy.pauseReason(this@MainActivity,prefs)
                    summary.text="${pending.size} waiting · ${queue.count { it.priority && it.uri in pending }} prioritized · status updates automatically"
                    val checkFiles=now-lastFileCheck>10000L
                    if(checkFiles)lastFileCheck=now
                    for(row in progressRows) {
                        if(checkFiles) {
                            val document=try { DocumentFile.fromSingleUri(this@MainActivity,Uri.parse(row.item.uri)) }catch(_:Exception){null}
                            row.available=try { document?.exists()==true && document.canRead() }catch(_:Exception){false}
                            row.size=try { document?.length()?:0L }catch(_:Exception){0L}
                        }
                        val transfer=transfers[row.item.uri]
                        val total=transfer?.total?:row.size
                        val state=QueueUploadStatus.describe(row.available,row.item.uri in pending,
                            transfer?.phase=="ready",transfer?.phase,transfer?.received?:0L,total,
                            paused,manual && row.item.uri !in eligible,policy,
                            transfer?.let { (now-it.updated).coerceAtLeast(0L) }?:0L)
                        val bytes=if(total>0L && row.available && transfer!=null)
                            " · "+android.text.format.Formatter.formatShortFileSize(this@MainActivity,transfer.received.coerceIn(0L,total))+" / "+android.text.format.Formatter.formatShortFileSize(this@MainActivity,total) else ""
                        row.status.text=state.label+bytes
                        row.status.setTextColor(when(state.tone) {
                            "error" -> Color.rgb(255,104,106)
                            "waiting" -> Color.rgb(255,194,93)
                            "active","ok" -> accent
                            else -> muted
                        })
                        row.bar.isIndeterminate=state.verifying
                        row.bar.progress=state.percent
                        for(action in row.actions)action.isEnabled=row.item.uri in pending &&
                            (row.available || action.text.toString()=="Remove")
                    }
                }catch(_:Exception) { summary.text="Status temporarily unavailable · retrying" }
                uiHandler.postDelayed(this,1500)
            }
        }
        OffsetDialogBuilder(this).setTitle("Upload queue · ${prefs.getString("project_name",pid)}")
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Close",null).create().also {
                queueDialog=it
                it.setOnDismissListener { uiHandler.removeCallbacks(updater);ledger.close() }
                it.show()
                uiHandler.post(updater)
            }
    }
    private fun chooseManualFiles() {
        val pid=project.text.toString().trim()
        if(pid.isBlank()) {
            OffsetDialogBuilder(this).setMessage("Enter the destination project ID first.")
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
        OffsetDialogBuilder(this)
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
                OffsetDialogBuilder(this).setTitle("Manual upload queue")
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
        val pilotGranted=!prefs.getBoolean("overlay_pilot_only",false) ||
            PilotForeground.permissionGranted(this)
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
        if(!pilotGranted)body.addView(text(
            "●  DJI Pilot visibility: Permission needed",14f,Color.rgb(255,174,106)))
        body.addView(text(
            "These permissions are saved across restarts. Android may require new screen capture permission for each metadata session.",
            12f,muted))
        val dialog=OffsetDialogBuilder(this).setView(body)
            .setNegativeButton("Close",null)
        if(!cacheGranted) dialog.setPositiveButton("Choose folder") { _,_ -> chooseFolder() }
        else if(!overlayGranted) dialog.setPositiveButton("Allow overlay") { _,_ ->
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
        }
        else if(!pilotGranted) dialog.setPositiveButton("Allow app visibility") { _,_ ->
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        else dialog.setNeutralButton("Change folder") { _,_ -> chooseFolder() }
        dialog.show()
    }
    private fun sdCardAccessOk():Boolean {
        val saved=prefs.getString("sd_tree_uri",null)?.let(Uri::parse) ?: return false
        val permission=contentResolver.persistedUriPermissions.any {
            it.uri==saved && it.isReadPermission && it.isWritePermission
        }
        return permission && (DocumentFile.fromTreeUri(this,saved)?.canWrite()==true)
    }

    private fun chooseSdCard() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        },pickSdCard)
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
        if(requestCode==pickSdCard) {
            if(resultCode==RESULT_OK) {
                val uri=data?.data ?: return
                try {
                    val granted=data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    val needed=Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    if(granted!=needed) throw SecurityException("Select the writable offset folder on your SD card")
                    contentResolver.takePersistableUriPermission(uri,needed)
                    val root=DocumentFile.fromTreeUri(this,uri)
                    if(root?.isDirectory!=true || !root.canWrite()) throw SecurityException("SD root is not writable")
                    prefs.edit().putString("sd_tree_uri",uri.toString())
                        .commit()
                    OffsetDialogBuilder(this).setTitle("SD card ready")
                        .setMessage("Choose Copy rushes to SD card after filming. OFFSET will never copy in the background. Originals remain untouched.")
                        .setPositiveButton("OK",null).show()
                } catch(e:Exception) {
                    showOffsetError("SD card access needed",
                        "Choose the offset folder on the SD card (create it in the file picker if needed) and grant both read and write access.",
                        e.localizedMessage,"Choose SD card"){ chooseSdCard() }
                }
            }
            return
        }
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
    private fun resumeSavedUploader(force:Boolean=false) {
        if(!force && !prefs.getBoolean("start_on_boot",true)) return
        val base=prefs.getString("server","").orEmpty()
        val tokenValue=prefs.getString("token","").orEmpty()
        val pid=prefs.getString("project","").orEmpty()
        val paused=prefs.getBoolean("upload_paused",false)
        if(pid.isBlank() || !cacheAccessOk() ||
            (!paused && (!base.startsWith("https://") || tokenValue.isBlank()))) {
            // Do not clear the saved folder or request permission every boot.
            folderLabel.text="Setup needed: check project, connection or CACHE_VIDEO access"
            return
        }
        try {
            val service=Intent(this,UploadService::class.java)
            if(Build.VERSION.SDK_INT>=26) startForegroundService(service)
            else startService(service)
            prefs.edit().putBoolean("boot_resume_pending",false).apply()
            folderLabel.text=if(prefs.getBoolean("upload_paused",false))
                "OFFSET off · file monitoring running" else "Monitoring and uploads resumed"
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
        // Explicit Start turns OFFSET on; this choice persists across reboot.
        prefs.edit().putBoolean("upload_paused",false).apply()
        // Never silently change the project of a running service.
        stopService(Intent(this,UploadService::class.java))
        prefs.edit().putString("server",base).putString("project",pid).putString("token",device).apply()
        val i=Intent(this,UploadService::class.java)
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i) else startService(i)
        folderLabel.text="Monitoring started · existing cache files blocked"
    }
}
