package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S10-22 / S10-25: the live frame loop forwards the tracker's redetect hint only to the bounded pacing API. */
class MainActivityTrackingRedetectStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun liveTrackingResultRequestsAnEarlyDetectionThroughThePacingPolicyOnly() {
        val frame = functionBlock("override fun onDrawFrame(")
        val track = frame.indexOf("visualFrameTracker.trackFrom(")
        val request = frame.indexOf("if (trackingResult?.redetectRequested == true) {")
        val early = frame.indexOf("adaptiveInferencePacing.requestEarlyStart(SystemClock.elapsedRealtime())")
        assertTrue(track >= 0)
        assertTrue("redetect hint must be consumed after tracking", request > track)
        assertTrue(early > request)
        assertTrue("diagnostics expose the redetect reasons", frame.contains("redetect=\${result.redetectReasons.joinToString()}"))
        assertEquals("only the frame loop asks for an early start", 1,
            Regex("""requestEarlyStart\(""").findAll(source).count())
        val between = frame.substring(request, frame.indexOf("runtimeLoadObservationSource.recordFrame(", request))
        assertFalse("the hint never starts detector work by itself", between.contains("tryStart"))
        assertFalse(between.contains("detectionInFlight"))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val next = Regex("""\n    (private |internal |override )?fun """).find(source, start + signature.length)
        return if (next == null) source.substring(start) else source.substring(start, next.range.first)
    }
}
