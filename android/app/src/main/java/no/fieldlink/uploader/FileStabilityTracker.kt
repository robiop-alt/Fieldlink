package no.fieldlink.uploader

/** Confirm unchanged size and modification time across separate scans. */
internal class FileStabilityTracker(private val quietMillis:Long=12_000L) {
    private data class State(val size:Long,val modified:Long,val since:Long,val checks:Int)
    private val files=mutableMapOf<String,State>()
    fun observe(key:String,size:Long,modified:Long,now:Long):Boolean {
        val previous=files[key]
        if(previous==null || previous.size!=size || previous.modified!=modified || size<=0 || now<previous.since) {
            files[key]=State(size,modified,now,0)
            return false
        }
        val next=previous.copy(checks=(previous.checks+1).coerceAtMost(3))
        files[key]=next
        return next.checks>=3 && now-next.since>=quietMillis
    }
}
