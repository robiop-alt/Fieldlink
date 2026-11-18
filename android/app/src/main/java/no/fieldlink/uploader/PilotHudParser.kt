package no.fieldlink.uploader

import java.util.Locale

/** Only camera HUD bands. Never interpret arbitrary picture/telemetry numbers. */
internal object PilotHudParser {
    data class Word(val text:String,val x:Float,val y:Float)
    private val rates="23\\.98|24|25|29\\.97|30|48|50|59\\.94|60|100|120"
    private fun normalized(s:String)=s.uppercase(Locale.ROOT).replace(Regex("\\s+")," ").trim()
    private fun unique(text:String,pattern:String):String? = Regex(pattern).findAll(text)
        .map { it.groupValues[1] }.distinct().toList().singleOrNull()

    /** Text must already have been restricted to the identified camera HUD. */
    fun readHud(topText:String,bottomText:String=""):Map<String,String> {
        val top=normalized(topText);val bottom=normalized(bottomText)
        if(!Regex("\\bWB(?:\\s|\\d)").containsMatchIn(top) ||
            !Regex("\\b(?:EI|ISO)(?:\\s|\\d)").containsMatchIn(top) ||
            !Regex("\\bIRIS(?:\\s|\\d)").containsMatchIn(top))return emptyMap()
        val values=mutableMapOf<String,String>()
        fun exposure(key:String,label:String) {
            unique(top,"\\b$label\\s*(?:N\\s*)?(\\d{2,5})(?:\\s*N)?(?![\\d.])")
                ?.toIntOrNull()?.takeIf { it in 50..25600 }?.let { values[key]=it.toString() }
        }
        exposure("exposure_index","EI");exposure("iso","ISO")
        unique(top,"\\bWB\\s*(\\d{4,5})\\s*K?\\b")?.toIntOrNull()
            ?.takeIf { it in 2000..12000 }?.let { values["white_balance"]="${it}K" }
        unique(top,"\\b(18|24|35|50)\\s*\\.?\\s*MM\\b")?.let { values["lens"]="${it}mm" }
        // The angle can precede EI in fullscreen mode. Only accept a unique
        // positive degree value inside the verified camera HUD (0° is gimbal).
        val angles=Regex("(?<![\\d.])(\\d{1,3}(?:\\.\\d{1,2})?)\\s*[°º]").findAll(top)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .filter { it>0 && it<=360 }.distinct().toList()
        angles.singleOrNull()?.let {
            values["shutter"]=(if(it%1.0==0.0)it.toInt().toString() else it.toString())+"°"
        }
        // These are explicit codec names; Rec LUT/D-Log and MON709 are not codecs.
        val codecText="$top $bottom"
        val codecs=Regex("\\b(CINEMADNG|CINEDNG|CDNG|(?:APPLE\\s+)?PRORES(?:\\s+(?:RAW|422(?:\\s+HQ)?))?)\\b")
            .findAll(codecText).map { it.groupValues[1] }.distinct().toList()
        if(codecs.size==1)values["recording_format"]=when(codecs[0]) {
            "CINEMADNG","CINEDNG","CDNG" -> "CinemaDNG"
            else -> codecs[0].take(32)
        }
        // An unlabelled frame rate is accepted only after a camera resolution,
        // in the same band as the codec, never from timecode or battery values.
        val frameRates=listOf(top,bottom).filter {
            Regex("\\b(?:CINEMADNG|CINEDNG|CDNG|PRORES)\\b").containsMatchIn(it)
        }.mapNotNull { unique(it,"\\b(?:8K|6K|5\\.5K|4K|2K|1080P)(?:\\s*\\([^)]{1,12}\\))?\\s*[|/]?\\s*($rates)(?![\\d.:])") }.distinct()
        if(frameRates.size==1)values["fps"]=frameRates[0]
        return if(values.size>=2)values else emptyMap()
    }

    fun parse(words:List<Word>):Map<String,String> {
        // ML Kit may merge WB with neighbouring words or split it across
        // elements. Parse the bounded HUD as a whole rather than requiring one
        // exact WB element. Label checks and per-field ambiguity checks remain.
        val top=words.filter { it.y in 0f..0.35f }
            .sortedWith(compareBy<Word> { it.x }.thenBy { it.y }).joinToString(" ") { it.text }
        val bottom=words.filter { it.y in 0.84f..1f }
            .sortedWith(compareBy<Word> { it.x }.thenBy { it.y }).joinToString(" ") { it.text }
        return readHud(top,bottom)
    }
}
