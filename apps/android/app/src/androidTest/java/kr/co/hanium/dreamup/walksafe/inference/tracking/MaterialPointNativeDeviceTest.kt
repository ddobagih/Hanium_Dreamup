package kr.co.hanium.dreamup.walksafe.inference.tracking

import androidx.test.ext.junit.runners.AndroidJUnit4
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/** Actual OpenCV LK on deterministic image translations. Camera/field accuracy is outside this test. */
@RunWith(AndroidJUnit4::class)
class MaterialPointNativeDeviceTest {
    @Test fun nativePointSurvivesMeasuredHandoffsBetween250MsDetectorSources() {
        val random = Random(417L)
        val pixels = ByteArray(192 * 192) { random.nextInt(256).toByte() }
        val tracker = InterFrameDetectionTracker(VisualTrackingConfig(maxExecutionNs = 100_000_000L))
        assertTrue("OpenCV native initialization", tracker.initialize())
        val rows = mutableListOf<VisualTrackingObservation>()
        for (index in 0..20) {
            val at = 1_000L + index * 50L
            val shifted = ByteArray(pixels.size)
            for (y in 0 until 192) for (x in index until 192) shifted[y * 192 + x] = pixels[y * 192 + x - index]
            val target = key(at)
            assertTrue(tracker.offerFrame(GrayTrackingFrame.copyOf(target, 192, 192, shifted)).accepted)
            if (at < 1_100L) continue
            val sourceAt = 1_000L + ((at - 1_100L) / 250L) * 250L
            val sourceShift = ((sourceAt - 1_000L) / 50L).toFloat() / 192f
            val candidate = DetectionCandidate("car", .95f, RectNorm(.2f + sourceShift, .2f, .6f, .6f))
            val result = tracker.trackFrom(key(sourceAt), listOf(candidate), target,
                executionBudgetNs = 100_000_000L)
            val row = result.observations.single()
            assertEquals("Native geometry at $at", VisualTrackingStatus.TRACKED, row.status)
            assertNotNull("Actual material point at $at", row.materialPoint)
            assertEquals(target, row.materialPoint!!.targetKey)
            assertEquals(39, row.originalFeatureCount)
            rows += row
        }
        assertTrue(rows.size >= 10)
        assertTrue(rows.map { it.detectorSourceKey }.distinct().size >= 3)
        assertEquals("Each source handoff preserves one physically tracked point", 1,
            rows.map { it.materialPoint!!.id }.distinct().size)
        val first = rows.first()
        for (row in rows.drop(1)) {
            val expected = (row.trackedTargetKey.frameId - first.trackedTargetKey.frameId) / 50_000_000f / 192f
            assertEquals(expected, row.materialPoint!!.imagePointNorm.x - first.materialPoint!!.imagePointNorm.x, .003f)
            assertEquals(first.materialPoint!!.imagePointNorm.y, row.materialPoint!!.imagePointNorm.y, .003f)
        }
    }

    private fun key(at: Long) = VisualFrameKey(7L, at * 1_000_000L, at * 1_000_000L + 400_000L, at + 50_000L, 3L)
}
