package no.fieldlink.uploader

import org.junit.Assert.*
import org.junit.Test

class PilotHudParserTest {
    @Test fun normalCameraViewFromControllerPhoto() {
        val actual=PilotHudParser.readHud("EI 800 N ∠ 180.0° IRIS 3.5 M.M +0.7 WB 5600K Rec LUT D-Log FF CinemaDNG 8K(16:9) | 25 D022C0006 00:19:38 910.3GB Lens 18.mm Time Code 10:44:18:13")
        assertEquals(mapOf("exposure_index" to "800","shutter" to "180°","white_balance" to "5600K","lens" to "18mm","recording_format" to "CinemaDNG","fps" to "25"),actual)
        assertFalse(actual.containsKey("iso"))
    }
    @Test fun fullScreenCameraViewFromControllerPhoto() {
        val actual=PilotHudParser.readHud("EI N 800 ∠ 180.0° IRIS3.5 M.M -2.0 WB5600K REC D-Log MON709 18mm RC 97 94","FF CinemaDNG 8K 25 TC10:44:09:15 19:38 D022C0006 STBY")
        assertEquals("800",actual["exposure_index"])
        assertEquals("25",actual["fps"])
        assertEquals("180°",actual["shutter"])
        assertEquals("18mm",actual["lens"])
    }
    @Test fun coordinatesLimitReadingsToHudAndJoinVerticalLabels() {
        val words=listOf(
            PilotHudParser.Word("EI",.10f,.21f),PilotHudParser.Word("800N",.10f,.24f),
            PilotHudParser.Word("∠",.18f,.21f),PilotHudParser.Word("180.0°",.18f,.24f),
            PilotHudParser.Word("IRIS",.26f,.21f),PilotHudParser.Word("3.5",.26f,.24f),
            PilotHudParser.Word("WB",.38f,.21f),PilotHudParser.Word("5600K",.38f,.24f),
            PilotHudParser.Word("18mm",.70f,.24f),
            PilotHudParser.Word("ISO 400 WB 3200K",.10f,.55f),
            PilotHudParser.Word("CinemaDNG",.15f,.94f),PilotHudParser.Word("8K",.25f,.94f),PilotHudParser.Word("25",.32f,.94f))
        val actual=PilotHudParser.parse(words)
        assertEquals("800",actual["exposure_index"])
        assertEquals("5600K",actual["white_balance"])
        assertEquals("25",actual["fps"])
        assertFalse(actual.containsKey("iso"))
    }
    @Test fun telemetryAndLutsAreNotCameraCodecOrFps() {
        val actual=PilotHudParser.readHud("EI800 ∠180° IRIS3.5 WB5600K REC D-Log MON709 18mm 94% 25.1V","TC10:44:25:15 19:38 25")
        assertFalse(actual.containsKey("recording_format"))
        assertFalse(actual.containsKey("fps"))
    }
    @Test fun conflictingLensAndUnlabelledPictureTextAreRejected() {
        val actual=PilotHudParser.readHud("EI800 ∠180° IRIS3.5 WB5600K 18mm 24mm")
        assertFalse(actual.containsKey("lens"))
        assertTrue(PilotHudParser.readHud("ISO800 WB5600K 18mm 25").isEmpty())
    }
}
