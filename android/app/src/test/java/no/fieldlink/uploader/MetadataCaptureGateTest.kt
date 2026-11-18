package no.fieldlink.uploader

import org.junit.Assert.*
import org.junit.Test

class MetadataCaptureGateTest {
    @Test fun rejectsOurScreenAndItsLateOcrResult() {
        MetadataCaptureGate.setOffsetVisible(true,10)
        assertFalse(MetadataCaptureGate.allowFrame(11))
        MetadataCaptureGate.setOffsetVisible(false,20)
        assertFalse(MetadataCaptureGate.allowFrame(19))
        assertTrue(MetadataCaptureGate.allowFrame(21))
    }
    @Test fun openingSettingsPreventsOverwritingPilotReading() {
        MetadataCaptureGate.setOffsetVisible(true,30)
        MetadataCaptureGate.setOffsetVisible(false,40)
        assertTrue(MetadataCaptureGate.allowFrame(41))
        MetadataCaptureGate.setOffsetVisible(true,50)
        assertFalse(MetadataCaptureGate.allowFrame(41))
        assertFalse(MetadataCaptureGate.allowFrame(51))
        MetadataCaptureGate.setOffsetVisible(false,60)
        assertTrue(MetadataCaptureGate.allowFrame(61))
    }
}
