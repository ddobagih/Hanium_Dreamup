package kr.co.hanium.dreamup.walksafe.depth

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S11-18: operational acquisition must keep a bounded reason for every failed image. */
class ArCoreFrameProviderRuntimeFailureStaticTest {
    private val source: String by lazy {
        sequenceOf(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/depth/ArCoreFrameProvider.kt"),
            File("app/src/main/java/kr/co/hanium/dreamup/walksafe/depth/ArCoreFrameProvider.kt"),
        ).first { it.isFile }.readText()
    }

    @Test
    fun operationalAcquisitionNeverDropsTheFailureSource() {
        val operational = source.substringAfter("fun acquireCameraImageOrNull(frame: Frame): Image?")
            .substringBefore("private fun captureCameraPose(frame: Frame)")
        // An unlabeled acquisition collapses "not yet available" and "resource exhausted" into null.
        assertFalse(operational.contains("tryAcquire {"))
        assertTrue(operational.contains("frame.acquireRawDepthImage16Bits()"))
        assertTrue(operational.contains("frame.acquireRawDepthConfidenceImage()"))
        assertTrue(operational.contains("frame.acquireDepthImage16Bits()"))
    }
}
