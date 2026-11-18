package no.fieldlink.uploader

import org.junit.Assert.*
import org.junit.Test

class QueueUploadStatusTest {
    private fun state(phase:String?=null,received:Long=0,total:Long=100,paused:Boolean=false,
                      available:Boolean=true,pending:Boolean=true,manual:Boolean=false,policy:String?=null,age:Long=0)=
        QueueUploadStatus.describe(available,pending,false,phase,received,total,paused,manual,policy,age)
    @Test fun reportsOnlyConfirmedBytes() {
        val result=state("uploading",received=37)
        assertEquals(37,result.percent)
        assertEquals("Uploading · 37% confirmed",result.label)
    }
    @Test fun allBytesReceivedIsNotYetVerified() {
        val result=state("processing",received=100)
        assertTrue(result.verifying)
        assertFalse(result.label.contains("verified"))
        assertEquals("Uploaded · server verified",state("ready",received=100).label)
    }
    @Test fun pauseAndManualSelectionAreExplicit() {
        assertTrue(state("uploading",received=20,paused=true).label.startsWith("Paused"))
        assertTrue(state(manual=true).label.startsWith("Not selected"))
        assertTrue(state(policy="Wi-Fi only · uploads paused").label.startsWith("Wi-Fi only"))
    }
    @Test fun unavailableFilesAndRemovedItemsAreNotUploading() {
        assertTrue(state("uploading",available=false).label.startsWith("File unavailable"))
        assertEquals("Removed from queue",state(pending=false).label)
    }
    @Test fun retriesAndStaleTransfersPreserveProgress() {
        assertEquals(80,state("waiting",received=80).percent)
        assertTrue(state("uploading",age=300001).label.startsWith("Waiting"))
    }
    @Test fun invalidByteCountsAreClamped() {
        assertEquals(0,state("uploading",received=-10).percent)
        assertEquals(100,state("uploading",received=120).percent)
        assertEquals(0,state("uploading",received=10,total=0).percent)
    }
}
