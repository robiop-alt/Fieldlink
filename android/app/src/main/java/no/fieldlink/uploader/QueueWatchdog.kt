package no.fieldlink.uploader

/** Monotonic warning clock. Retry attempts never count as delivered bytes. */
internal class QueueWatchdog(private val delayMillis:Long=120_000L) {
    private var project=""
    private var progress=0L
    private var since=0L
    private var active=false
    fun update(projectId:String, waiting:Int, progressMark:Long, now:Long, reason:String):String? {
        if(projectId.isBlank() || waiting<=0) {
            active=false
            return null
        }
        if(!active || projectId!=project || progressMark!=progress) {
            active=true;project=projectId;progress=progressMark;since=now
            return null
        }
        if(now-since<delayMillis)return null
        return "Queue waiting · $waiting clips · $reason"
    }
}
