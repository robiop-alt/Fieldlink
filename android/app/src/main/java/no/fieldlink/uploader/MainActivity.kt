package no.fieldlink.uploader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile

class MainActivity : Activity() {
    private val pickFolder = 4101
    private lateinit var server: EditText
    private lateinit var project: EditText
    private lateinit var token: EditText
    private lateinit var folderLabel: TextView
    private val prefs by lazy { getSharedPreferences("fieldlink", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (18 * resources.displayMetrics.density).toInt()
        fun field(hint: String, value: String = "", password: Boolean = false) = EditText(this).apply {
            this.hint = hint; setText(value); textSize = 16f
            if (password) inputType = 129
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        server = field("Serveradresse, f.eks. https://rushes.dittdomene.no", prefs.getString("server", "") ?: "")
        project = field("Prosjekt-ID", prefs.getString("project", "") ?: "")
        token = field("Uploader device token", prefs.getString("token", "") ?: "", true)
        folderLabel = TextView(this).apply { textSize = 14f; setPadding(0, pad, 0, pad) }
        refreshFolderLabel()
        val choose = Button(this).apply { text = "Velg DJI CACHE_VIDEO-mappe"; setOnClickListener { chooseFolder() } }
        val start = Button(this).apply { text = "Start automatisk opplasting"; setOnClickListener { saveAndStart() } }
        val stop = Button(this).apply { text = "Stopp opplasting"; setOnClickListener { stopService(Intent(this@MainActivity, UploadService::class.java)) } }
        val note = TextView(this).apply { text = "FIELDLINK UPLOADER  ·  TESTVERSJON\n\nVelg cache-mappen én gang. Appen overvåker nye MP4-filer og laster opp stabile filer automatisk mens tjenesten kjører. La varslingen være aktiv. Krever nett og gyldig prosjekt-ID/device token."; textSize = 14f; setPadding(0, pad, 0, pad) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            addView(TextView(this@MainActivity).apply { text = "FIELDLINK"; textSize = 30f; setPadding(0,0,0,pad) })
            addView(note); addView(server, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(project); addView(token); addView(choose); addView(folderLabel); addView(start); addView(stop)
        }
        setContentView(layout)
    }

    private fun chooseFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, pickFolder)
    }

    @Deprecated("SAF compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickFolder && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("tree_uri", uri.toString()).apply()
            refreshFolderLabel()
        }
    }

    private fun refreshFolderLabel() {
        val uri = prefs.getString("tree_uri", null)?.let(Uri::parse)
        val name = uri?.let { DocumentFile.fromTreeUri(this, it)?.name }
        folderLabel.text = if (name == null) "Ingen mappe valgt" else "Valgt mappe: $name"
    }

    private fun saveAndStart() {
        val base = server.text.toString().trim().trimEnd('/')
        val pid = project.text.toString().trim()
        val device = token.text.toString().trim()
        val tree = prefs.getString("tree_uri", null)
        if (base.isBlank() || pid.isBlank() || device.isBlank() || tree.isNullOrBlank()) {
            folderLabel.text = "Fyll inn server, prosjekt-ID, device token og velg mappe først."; return
        }
        if (!base.startsWith("https://") && !base.contains("localhost") && !base.contains("10.0.2.2")) {
            folderLabel.text = "Bruk HTTPS for ekstern server."; return
        }
        prefs.edit().putString("server", base).putString("project", pid).putString("token", device).apply()
        val i = Intent(this, UploadService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        folderLabel.text = "Overvåking startet. Se aktiv varsling for status."
    }
}
