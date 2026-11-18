package no.fieldlink.uploader

import org.junit.Assert.*
import org.junit.Test

class FrameLineGeometryTest {
    @Test fun scopeOnLetterboxedVideoUsesVideoBounds() {
        val source=FrameLineGeometry.Rect(0f,218.75f,1000f,781.25f)
        val frame=FrameLineGeometry.frame(source,2.39f)!!
        assertEquals(0f,frame.left,.001f)
        assertEquals(1000f,frame.right,.001f)
        assertEquals(2.39f,frame.width/frame.height,.001f)
        assertEquals(500f,(frame.top+frame.bottom)/2f,.001f)
        assertTrue(frame.top>source.top)
    }
    @Test fun narrowerFrameIsPillarboxedWithinVideo() {
        val source=FrameLineGeometry.Rect(120f,0f,1800f,945f)
        val frame=FrameLineGeometry.frame(source,4f/3f)!!
        assertEquals(0f,frame.top,.001f)
        assertEquals(945f,frame.bottom,.001f)
        assertEquals(960f,(frame.left+frame.right)/2f,.001f)
        assertTrue(frame.left>source.left)
    }
    @Test fun invalidAndUnmeasuredVideoDoesNotDraw() {
        assertNull(FrameLineGeometry.frame(FrameLineGeometry.Rect(0f,0f,0f,0f),2.39f))
        assertNull(FrameLineGeometry.frame(FrameLineGeometry.Rect(0f,0f,1920f,1080f),Float.NaN))
        assertNull(FrameLineGeometry.frame(FrameLineGeometry.Rect(0f,0f,1920f,1080f),0f))
    }
}
