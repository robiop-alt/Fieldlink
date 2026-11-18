package no.fieldlink.uploader

import org.junit.Assert.*
import org.junit.Test

class FileStabilityTrackerTest {
    @Test fun requiresThreeChecksAndTwelveQuietSeconds() {
        val tracker=FileStabilityTracker()
        assertFalse(tracker.observe("a",100,10,0))
        assertFalse(tracker.observe("a",100,10,4000))
        assertFalse(tracker.observe("a",100,10,8000))
        assertTrue(tracker.observe("a",100,10,12000))
    }
    @Test fun changesResetSettlingAndRapidChecksDoNotCountAsQuietTime() {
        val tracker=FileStabilityTracker()
        tracker.observe("a",100,10,0)
        tracker.observe("a",100,10,1);tracker.observe("a",100,10,2)
        assertFalse(tracker.observe("a",100,10,3))
        assertFalse(tracker.observe("a",200,20,12000))
        tracker.observe("a",200,20,16000);tracker.observe("a",200,20,20000)
        assertTrue(tracker.observe("a",200,20,24000))
        assertFalse(tracker.observe("a",200,21,28000))
    }
    @Test fun oneGrowingFileDoesNotBlockASeparateFinishedFile() {
        val tracker=FileStabilityTracker()
        for(time in listOf(0L,4000L,8000L)) {
            assertFalse(tracker.observe("growing",100+time,time,time))
            assertFalse(tracker.observe("finished",100,10,time))
        }
        assertFalse(tracker.observe("growing",12100,12000,12000))
        assertTrue(tracker.observe("finished",100,10,12000))
    }
    @Test fun emptyFilesAndClockResetStayUnready() {
        val tracker=FileStabilityTracker()
        for(time in listOf(0L,4000L,8000L,12000L))assertFalse(tracker.observe("a",0,10,time))
        tracker.observe("b",100,10,20000)
        assertFalse(tracker.observe("b",100,10,1))
    }
}
