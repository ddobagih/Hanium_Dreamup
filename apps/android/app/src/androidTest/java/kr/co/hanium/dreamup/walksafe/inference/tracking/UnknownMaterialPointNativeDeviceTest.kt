package kr.co.hanium.dreamup.walksafe.inference.tracking

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.util.BitSet
import java.util.Random
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownMaterialPointObservation
import kr.co.hanium.dreamup.walksafe.depth.unknown.sameCapture
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual native LK with owned synthetic CPU luma and immutable masks; no camera/depth claim. */
@RunWith(AndroidJUnit4::class)
class UnknownMaterialPointNativeDeviceTest {
    @Test fun nativeReservedOriginalCapturesPreservePointUntilFrameGap() {
        // Reuse the existing native functional fixture allowance, not the product's 5 ms budget.
        val config = VisualTrackingConfig(maxExecutionNs = FUNCTIONAL_BUDGET_NS)
        assertEquals(VisualTrackingBackend.OPENCV_PYRAMIDAL_LK, config.backend)
        assertTrue("OpenCV native initialization", OpenCvPyramidalMotionEstimator(config.maxFeaturesPerObject).initialize())
        val producer = UnknownMaterialPointTracker(config)
        val random = Random(417L)
        val original = ByteArray(SIDE * SIDE) { random.nextInt(256).toByte() }
        val leases = mutableListOf<UnknownMaterialPointCaptureLease>()
        val measured = mutableListOf<UnknownMaterialPointObservation>()

        fun offer(at: Long) {
            val token = token(at)
            val shift = (at / 50L).toInt()
            val luma = ByteArray(original.size)
            for (y in 0 until SIDE) for (x in shift until SIDE) {
                luma[y * SIDE + x] = original[y * SIDE + x - shift]
            }
            val admitted = producer.offerLuma(token, 0, token.capturedElapsedNs / 1_000_000L,
                token.cpuImageTimestampNs, SIDE, SIDE, ByteBuffer.wrap(luma), SIDE, 1)
            assertTrue("CPU luma admission at $at: ${admitted.failure}", admitted.accepted)
            luma.fill(0) // Native consumption must use the producer's copy, not this caller buffer.
        }

        fun reserve(at: Long): UnknownMaterialPointCaptureLease {
            val token = token(at)
            val reservation = producer.reserveCapture(token, 0, token.capturedElapsedNs / 1_000_000L)
            assertNull("Original reservation at $at", reservation.failure)
            val lease = requireNotNull(reservation.lease)
            assertTrue(sameCapture(token, lease.token))
            assertFalse(lease.isClosed)
            leases += lease
            return lease
        }

        fun consume(at: Long, lease: UnknownMaterialPointCaptureLease): UnknownMaterialPointTrackingResult {
            assertFalse("Pending source remains reserved across later offers", lease.isClosed)
            val result = producer.consume(token(at), 0, listOf(mask((at / 50L).toInt())),
                token(at + 100L).capturedElapsedNs / 1_000_000L, FUNCTIONAL_BUDGET_NS, lease)
            assertTrue("Consume releases its exact source lease", lease.isClosed)
            assertEquals(FUNCTIONAL_BUDGET_NS, result.executionBudgetNs)
            assertFalse("Functional fixture deadline at $at", result.executionBudgetOverrun)
            assertTrue(result.retainedFrames <= config.maxHistoryFrames)
            assertEquals(result.retainedFrames * SIDE * SIDE, result.retainedImageBytes)
            Log.i(TAG, "sourceMs=$at laterOfferMs=${at + 100L} failure=${result.failure} " +
                "points=${result.materialPointsByMaskIndex.size} " +
                "nativeEdges=${result.trackingMetrics.sumOf { it.processedEdges }} leaseClosed=${lease.isClosed}")
            return result
        }

        try {
            var pending: UnknownMaterialPointCaptureLease? = null
            // Mask captures are 250 ms apart, with every intervening CPU capture 50 ms apart.
            // Results arrive 100 ms later but must remain tied to the original mask capture.
            for (index in 0..17) {
                val at = index * 50L
                offer(at)
                if (index % 5 == 0) pending = reserve(at)
                if (index % 5 != 2) continue
                val sourceAt = at - 100L
                val result = consume(sourceAt, requireNotNull(pending))
                assertNull("Native flow/handoff at $sourceAt", result.failure)
                if (sourceAt == 0L) {
                    assertTrue("The first mask cannot invent a point", result.materialPointsByMaskIndex.isEmpty())
                    continue
                }
                assertEquals("Flow plus source handoff", 2, result.trackingMetrics.size)
                assertEquals("Five measured native edges", 5, result.trackingMetrics.first().processedEdges)
                val observation = result.materialPointsByMaskIndex.getValue(0)
                assertOriginalCapture(sourceAt, observation)
                measured += observation
            }
            assertEquals(3, measured.size)
            val first = measured.first()
            for (observation in measured.drop(1)) {
                assertSame("Native source handoff preserves the material point", first.point.id, observation.point.id)
                val delta = (observation.token.frameId - first.token.frameId) / 50_000_000f / SIDE
                assertEquals(delta, observation.point.imagePointNorm.x - first.point.imagePointNorm.x, .003f)
                assertEquals(first.point.imagePointNorm.y, observation.point.imagePointNorm.y, .003f)
            }

            // Last offered frame was 850 ms. The real next frame at 1000 ms leaves a 150 ms gap.
            offer(1_000L)
            val gapLease = reserve(1_000L)
            offer(1_050L); offer(1_100L)
            val lost = consume(1_000L, gapLease)
            assertEquals(VisualTrackingFailure.FRAME_GAP, lost.failure)
            assertTrue("A gap cannot publish the previous point", lost.materialPointsByMaskIndex.isEmpty())

            for (at in 1_150L..1_250L step 50L) offer(at)
            val freshLease = reserve(1_250L)
            offer(1_300L); offer(1_350L)
            val recovered = consume(1_250L, freshLease)
            assertNull("Fresh continuous native flow after the gap", recovered.failure)
            val fresh = recovered.materialPointsByMaskIndex.getValue(0)
            assertOriginalCapture(1_250L, fresh)
            assertNotSame("Recovery cannot revive the pre-gap ID", first.point.id, fresh.point.id)
            assertTrue("Every submitted source lease was released", leases.all { it.isClosed })
        } finally {
            producer.reset()
        }
    }

    private fun assertOriginalCapture(at: Long, observation: UnknownMaterialPointObservation) {
        val source = token(at)
        assertTrue("All source token clocks/geometry match", sameCapture(source, observation.token))
        assertEquals(0, observation.imageQuarterTurns)
        assertEquals(VisualFrameKey(source.sessionEpoch, source.frameId, source.cpuImageTimestampNs,
            source.capturedElapsedNs / 1_000_000L, 1L), observation.point.targetKey)
        val point = observation.point.imagePointNorm
        assertTrue(point.x.isFinite() && point.y.isFinite())
        assertTrue("Measured coordinates belong to the original mask", mask((at / 50L).toInt())
            .contains((point.x * SIDE).toInt(), (point.y * SIDE).toInt()))
        Log.i(TAG, "originalTargetMs=$at x=${point.x} y=${point.y} sourceCpuNs=${source.cpuImageTimestampNs}")
    }

    private fun token(at: Long) = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, 9L,
        (10_000L + at) * 1_000_000L, (10_000L + at) * 1_000_000L,
        (9_980L + at) * 1_000_000L, (100_000L + at) * 1_000_000L,
        "unknown-native-original-capture", SIDE, SIDE)

    private fun mask(shift: Int): InstanceMask {
        val left = 38 + shift
        val right = 154 + shift
        val bits = BitSet((right - left) * 116).also { it.set(0, (right - left) * 116) }
        // The production immutable mask, with the same constructor fixture as existing tests.
        return InstanceMask::class.java.declaredConstructors.single().also { it.isAccessible = true }
            .newInstance(SIDE, SIDE, left, 38, right, 154, bits.cardinality(), 17,
                left.toFloat(), 38f, right.toFloat(), 154f, .99f, bits) as InstanceMask
    }

    companion object {
        private const val SIDE = 192
        private const val FUNCTIONAL_BUDGET_NS = 100_000_000L
        private const val TAG = "UnknownPointNativeTest"
    }
}
