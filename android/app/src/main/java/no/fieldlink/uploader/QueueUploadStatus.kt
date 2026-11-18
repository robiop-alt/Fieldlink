package no.fieldlink.uploader

/** UI state uses server-confirmed bytes; receiving and verification are separate. */
internal object QueueUploadStatus {
    data class Display(val label:String,val percent:Int,val verifying:Boolean=false,val tone:String="muted")
    fun describe(available:Boolean,pending:Boolean,done:Boolean,phase:String?,received:Long,total:Long,
                 paused:Boolean,manualHeld:Boolean,policy:String?,ageMillis:Long):Display {
        val percent=if(total>0L)(received.coerceIn(0L,total).toDouble()/total*100).toInt().coerceIn(0,100) else 0
        if(done || phase=="ready")return Display("Uploaded · server verified",100,tone="ok")
        if(!pending)return Display("Removed from queue",percent)
        if(!available)return Display("File unavailable · check DJI cache access",percent,tone="error")
        if(paused)return Display("Paused · $percent% saved",percent,tone="waiting")
        if(manualHeld)return Display("Not selected · manual mode",percent,tone="waiting")
        if(!policy.isNullOrBlank())return Display("$policy · $percent% saved",percent,tone="waiting")
        if(phase=="processing")return Display("Verifying on server · 100% received",100,true,"waiting")
        if(phase=="waiting" || phase=="uploading" && ageMillis>300_000L)
            return Display("Waiting to resume · $percent% confirmed",percent,tone="waiting")
        if(phase=="uploading")return Display("Uploading · $percent% confirmed",percent,tone="active")
        return Display("Queued",percent)
    }
}
