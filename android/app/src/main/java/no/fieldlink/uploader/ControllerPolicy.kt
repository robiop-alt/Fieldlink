package no.fieldlink.uploader

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.storage.StorageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/** Persisted operator policies; none of these controls change clip ownership or verification. */
internal object ControllerPolicy {
    fun wifiAvailable(context:Context):Boolean {
        val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network=manager.activeNetwork ?: return false
        val capabilities=manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun charging(context:Context):Boolean {
        val battery=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false // Fail closed when charging-only is enabled.
        val status=battery.getIntExtra(BatteryManager.EXTRA_STATUS,-1)
        return status==BatteryManager.BATTERY_STATUS_CHARGING ||
            status==BatteryManager.BATTERY_STATUS_FULL
    }

    fun pauseReason(context:Context,prefs:android.content.SharedPreferences):String? {
        if(prefs.getBoolean("wifi_only",false) && !wifiAvailable(context))
            return "Wi-Fi only · uploads paused"
        if(prefs.getBoolean("charging_only",false) && !charging(context))
            return "Waiting for charger · uploads paused"
        return null
    }

    /** Small intentional delay between chunks. Never slow DJI Pilot or its network calls. */
    fun throttleMillis(context:Context,prefs:android.content.SharedPreferences):Long {
        if(!prefs.getBoolean("adaptive_speed",false))return 0
        val power=context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val hot=Build.VERSION.SDK_INT>=29 &&
            power.currentThermalStatus>=PowerManager.THERMAL_STATUS_MODERATE
        if(hot)return 1800L
        // Usage access is optional. Without it, stay conservative.
        if(!PilotForeground.permissionGranted(context))return 800L
        return if(PilotForeground.isPilotVisible(context))900L else 0L
    }

    fun sdFreeBytes(context:Context):Long? {
        if(Build.VERSION.SDK_INT<30)return null
        val prefs=context.getSharedPreferences("fieldlink",Context.MODE_PRIVATE)
        val tree=prefs.getString("sd_tree_uri",null) ?: return null
        return try {
            val root=DocumentFile.fromTreeUri(context,Uri.parse(tree)) ?: return null
            if(!root.exists())return null
            val storage=context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val docId=DocumentsContract.getTreeDocumentId(Uri.parse(tree))
            val volumeId=docId.substringBefore(':')
            val volume=storage.storageVolumes.firstOrNull {
                it.uuid?.equals(volumeId,ignoreCase=true)==true
            } ?: return null
            val directory=volume.directory ?: return null
            StatFs(directory.absolutePath).availableBytes
        }catch(_:Exception){null}
    }
}
