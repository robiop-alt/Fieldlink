package no.fieldlink.uploader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Short-lived, on-device OCR timeline. Never stores screenshots.
 * Samples are project scoped and capped to the most recent 12 hours / 600 entries.
 * No OCR text is sent to the server until a validated clip association is implemented.
 */
object PilotMetadataTimeline {
    private const val KEY="pilot_ocr_samples_v1"
    private const val MAX_ENTRIES=600
    private const val MAX_AGE_MS=12L*60*60*1000

    @Synchronized fun record(context:Context, project:String, text:String, capturedAt:Long) {
        if(project.isBlank() || text.isBlank())return
        val prefs=context.getSharedPreferences("fieldlink",Context.MODE_PRIVATE)
        val existing=try { JSONArray(prefs.getString(KEY,"[]")) }catch(_:Exception){JSONArray()}
        val kept=JSONArray()
        val cutoff=capturedAt-MAX_AGE_MS
        for(i in 0 until existing.length()) {
            val item=existing.optJSONObject(i) ?: continue
            if(item.optLong("at")>=cutoff)kept.put(item)
        }
        kept.put(JSONObject().put("project",project).put("at",capturedAt)
            .put("text",text.take(1200)))
        // Do not let long-running shoots grow SharedPreferences indefinitely.
        val trimmed=JSONArray()
        for(i in (kept.length()-MAX_ENTRIES).coerceAtLeast(0) until kept.length())
            trimmed.put(kept.getJSONObject(i))
        prefs.edit().putString(KEY,trimmed.toString()).apply()
    }

    @Synchronized fun closest(context:Context,project:String,at:Long,maxDeltaMs:Long=15_000L):JSONObject? {
        val prefs=context.getSharedPreferences("fieldlink",Context.MODE_PRIVATE)
        val samples=try { JSONArray(prefs.getString(KEY,"[]")) }catch(_:Exception){return null}
        var best:JSONObject?=null
        var distance=maxDeltaMs+1
        for(i in 0 until samples.length()) {
            val sample=samples.optJSONObject(i) ?: continue
            if(sample.optString("project")!=project)continue
            val delta=kotlin.math.abs(sample.optLong("at")-at)
            if(delta<distance) {distance=delta;best=sample}
        }
        return best
    }
}
