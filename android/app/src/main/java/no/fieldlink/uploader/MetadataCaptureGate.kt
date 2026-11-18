package no.fieldlink.uploader

/** Reject our own UI and asynchronous OCR results from before the app switch. */
internal object MetadataCaptureGate {
    private var offsetVisible=false
    private var switchedAt=0L
    @Synchronized fun setOffsetVisible(visible:Boolean,now:Long) {
        if(offsetVisible!=visible) {
            offsetVisible=visible
            switchedAt=now
        }
    }
    @Synchronized fun allowFrame(capturedAt:Long):Boolean = !offsetVisible && capturedAt>switchedAt
}
