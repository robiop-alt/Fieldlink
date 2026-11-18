package no.fieldlink.uploader

import org.junit.Assert.*
import org.junit.Test

class CameraSettingsStoreTest {
    @Test fun labelsAndValuesCanAppearOnSeparateOcrLines() {
        val result=CameraSettingsStore.parse("ISO\n800\nWB\n5600K\nFPS\n25\nLens\n24mm")
        assertEquals("800",result["iso"])
        assertEquals("5600K",result["white_balance"])
        assertEquals("25",result["fps"])
        assertEquals("24mm",result["lens"])
    }
    @Test fun unrelatedNumbersDoNotBecomeCameraFields() {
        assertTrue(CameraSettingsStore.parse("25 800 5600 24mm MP4").isEmpty())
    }
    @Test fun aConflictingFieldDoesNotDiscardConfirmedOtherFields() {
        val samples=listOf((mapOf("iso" to "400","white_balance" to "5600K") to 1L),
                           (mapOf("iso" to "800","white_balance" to "5600K") to 2L))
        val result=CameraSettingsStore.confirmedSamples(samples)!!
        assertEquals(mapOf("white_balance" to "5600K"),result.first)
    }
    @Test fun missingOcrFieldDoesNotDiscardOtherConfirmedValues() {
        val samples=listOf((mapOf("iso" to "800","white_balance" to "5600K","lens" to "24mm") to 1L),
                           (mapOf("iso" to "800","white_balance" to "5600K") to 2L))
        val result=CameraSettingsStore.confirmedSamples(samples)!!
        assertEquals(mapOf("iso" to "800","white_balance" to "5600K"),result.first)
    }
    @Test fun duplicateTimestampIsNotTwoIndependentReadings() {
        val value=mapOf("iso" to "800","white_balance" to "5600K")
        assertNull(CameraSettingsStore.confirmedSamples(listOf(value to 1L,value to 1L)))
    }
}
