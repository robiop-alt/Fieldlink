package no.fieldlink.uploader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * Privacy-limited, consented OCR readings from DJI Pilot.
 * Does not save images, filenames, screen text, or any uncertain fields.
 * File binding fails closed unless two consistent screen readings were observed
 * within seconds of the clip file's last-write timestamp.
 */
object CameraSettingsStore {
    private const val KEY="camera_pilot_samples"
    private const val MAX_AGE_MS=4*60*60*1000L
    private const val MATCH_WINDOW_MS=8000L
    private val settings=listOf("recording_format","lens","fps","iso","white_balance","shutter")

    fun parse(screen:String):Map<String,String> {
        val text=screen.uppercase(Locale.ROOT)
        val lines=text.lines().map { it.trim().replace(Regex("\\s+")," ") }
        val values=mutableMapOf<String,String>()
        fun first(regex:Regex):String? = lines.firstNotNullOfOrNull { line ->
            regex.find(line)?.groupValues?.getOrNull(1)
        }
        // Only explicitly labelled controls; never infer a camera format from MP4.
        first(Regex("\\b(?:RECORDING FORMAT|RECORD FORMAT|CODEC)\\s*[:=]?\\s*(APPLE PRORES(?:\\s+(?:RAW|422(?: HQ)?))?|PRORES(?:\\s+(?:RAW|422(?: HQ)?))?|CINEDNG|CDNG)\\b"))
            ?.let { values["recording_format"]=it.take(32) }
        first(Regex("\\b(?:LENS|FOCAL(?: LENGTH)?)\\s*[:=]?\\s*(18|24|35|50)\\s*(?:MM)?\\b"))
            ?.let { values["lens"]=it+"mm" }
        first(Regex("\\b(?:FPS|FRAME RATE|FRAMERATE)\\s*[:=]?\\s*(23\\.98|24|25|29\\.97|30|48|50|59\\.94|60|100|120)\\b"))
            ?.let { values["fps"]=it }
        first(Regex("\\bISO\\s*[:=]?\\s*(\\d{2,5})\\b"))
            ?.let { v -> v.toIntOrNull()?.takeIf { it in 50..25600 }?.let { values["iso"]=v } }
        first(Regex("\\b(?:WB|WHITE BALANCE)\\s*[:=]?\\s*(\\d{4,5})\\s*K?\\b"))
            ?.let { v -> v.toIntOrNull()?.takeIf { it in 2000..12000 }?.let { values["white_balance"]=v+"K" } }
        first(Regex("\\b(?:SHUTTER|SHUTTER ANGLE)\\s*[:=]?\\s*(45|90|120|144|172\\.8|180|270|360)\\s*°"))
            ?.let { values["shutter"]=it+"°" }
        first(Regex("\\bSHUTTER(?: SPEED)?\\s*[:=]?\\s*(1/\\d{2,4})\\b"))
            ?.let { if(!values.containsKey("shutter"))values["shutter"]=it }
        // DJI's live camera controls can show the value with its unit but no label.
        first(Regex("\\b(18|24|35|50)\\s*MM\\b"))
            ?.let { values.putIfAbsent("lens",it+"mm") }
        first(Regex("\\b(23\\.98|24|25|29\\.97|30|48|50|59\\.94|60|100|120)\\s*FPS\\b"))
            ?.let { values.putIfAbsent("fps",it) }
        first(Regex("\\b(\\d{4,5})\\s*K\\b"))
            ?.let { v -> v.toIntOrNull()?.takeIf { it in 2000..12000 }?.let {
                values.putIfAbsent("white_balance",v+"K")
            } }
        // The DJI Pilot HUD groups format, resolution and the unlabelled frame rate.
        val hud=Regex("\\b(CINEMA\\s*DNG|CINEDNG|CDNG|(?:APPLE\\s+)?PRORES(?:\\s+(?:RAW(?: HQ)?|422(?: HQ)?))?)\\s*[|·]?\\s*(?:[248](?:\\.\\d+)?\\s*K|1080P?)\\s*[|·]?\\s*(23\\.98|24|25|29\\.97|30|48|50|59\\.94|60|100|120)(?![\\d.:])")
            .find(lines.joinToString(" "))
        if(hud!=null) {
            val format=hud.groupValues[1]
            values.putIfAbsent("recording_format",if(format.replace(" ","") in setOf("CINEMADNG","CINEDNG","CDNG")) "CinemaDNG" else format)
            values.putIfAbsent("fps",hud.groupValues[2])
            first(Regex("\\bEI\\s*[:=]?\\s*(?:N\\s*)?(\\d{2,5})\\b"))
                ?.let { v -> v.toIntOrNull()?.takeIf { it in 50..25600 }?.let {
                    values.putIfAbsent("iso","EI $v")
                } }
            first(Regex("(?<![\\d.])(\\d{2,3}(?:\\.\\d+)?)\\s*°"))
                ?.let { v -> v.toDoubleOrNull()?.takeIf { it in 1.0..360.0 }?.let {
                    values.putIfAbsent("shutter",v.removeSuffix(".0")+"°")
                } }
        }
        // Require at least two independently identifiable settings on the screen.
        return if(values.size>=2)values else emptyMap()
    }

    @Synchronized fun observe(context:Context, screen:String, whenMs:Long=System.currentTimeMillis()) {
        val values=parse(screen)
        if(values.isEmpty())return
        val project=context.getSharedPreferences("fieldlink",Context.MODE_PRIVATE)
            .getString("project","").orEmpty()
        if(project.isBlank())return
        val prefs=context.getSharedPreferences("fieldlink",Context.MODE_PRIVATE)
        val entries=try { JSONArray(prefs.getString(KEY,"[]")) }catch(_:Exception){JSONArray()}
        val retained=JSONArray()
        for(i in 0 until entries.length()){
            val old=entries.optJSONObject(i)?:continue
            if(old.optString("project")==project && whenMs-old.optLong("time") in 0..MAX_AGE_MS)
                retained.put(old)
        }
        retained.put(JSONObject().put("project",project).put("time",whenMs)
            .put("values",JSONObject(values)))
        while(retained.length()>120)retained.remove(0)
        prefs.edit().putString(KEY,retained.toString()).apply()
    }

    @Synchronized fun match(context:Context, project:String, fileModifiedMs:Long):Pair<Map<String,String>,Long>? {
        if(fileModifiedMs<=0L || abs(System.currentTimeMillis()-fileModifiedMs)>MAX_AGE_MS)return null
        val prefs=context.getSharedPreferences("fieldlink",Context.MODE_PRIVATE)
        val entries=try { JSONArray(prefs.getString(KEY,"[]")) }catch(_:Exception){return null}
        val matching=mutableListOf<Pair<Map<String,String>,Long>>()
        for(i in 0 until entries.length()){
            val item=entries.optJSONObject(i)?:continue
            val t=item.optLong("time")
            if(item.optString("project")!=project || abs(t-fileModifiedMs)>MATCH_WINDOW_MS)continue
            val obj=item.optJSONObject("values")?:continue
            val values=mutableMapOf<String,String>()
            for(key in settings) {
                val v=obj.optString(key)
                if(v.isNotBlank())values[key]=v
            }
            if(values.size>=2)matching.add(values to t)
        }
        // Require two consistent observations, and never merge conflicting readings.
        if(matching.size<2)return null
        val base=matching.first().first
        if(matching.any { it.first!=base })return null
        return base to matching.last().second
    }
}
