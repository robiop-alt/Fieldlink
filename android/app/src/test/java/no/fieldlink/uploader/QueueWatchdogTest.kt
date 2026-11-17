package no.fieldlink.uploader

import org.junit.Assert.*
import org.junit.Test

class QueueWatchdogTest {
    @Test fun warnsAfterTwoMinutesWithoutConfirmedProgress() {
        val watch=QueueWatchdog()
        assertNull(watch.update("A",2,0,0,"Waiting for network"))
        assertNull(watch.update("A",2,0,119999,"Waiting for network"))
        assertEquals("Queue waiting · 2 clips · Waiting for network",watch.update("A",2,0,120000,"Waiting for network"))
    }
    @Test fun progressClearsWarningAndStartsNewClock() {
        val watch=QueueWatchdog()
        watch.update("A",1,0,0,"Retrying")
        assertNotNull(watch.update("A",1,0,120000,"Retrying"))
        assertNull(watch.update("A",1,1,120001,"Retrying"))
        assertNull(watch.update("A",1,1,240000,"Retrying"))
    }
    @Test fun emptyQueueAndProjectChangeResetTimer() {
        val watch=QueueWatchdog()
        watch.update("A",1,0,0,"Paused")
        assertNull(watch.update("A",0,0,120000,"Paused"))
        assertNull(watch.update("A",1,0,240000,"Paused"))
        assertNull(watch.update("B",1,0,360000,"Paused"))
    }
    @Test fun reasonCanChangeWithoutHidingStalledQueue() {
        val watch=QueueWatchdog()
        watch.update("A",1,0,0,"Network")
        assertEquals("Queue waiting · 1 clips · Low battery",watch.update("A",1,0,120000,"Low battery"))
    }
}
