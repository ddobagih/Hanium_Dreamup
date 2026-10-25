package kr.co.hanium.dreamup.walksafe.inference.tracking

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import org.junit.Assert.*
import org.junit.Test

class UnknownMaterialPointCaptureLeaseOwnershipTest {
    @Test fun ownCloseIsIdempotentAndDoesNotRemoveOrResetItsOwnedGray() {
        val scene = UnknownMaterialPointTestScene()
        scene.offer(0)
        val lease = reserve(scene, 0)
        assertTrue(scene.producer.closeCaptureLeaseIfOwned(lease))
        assertTrue(lease.isClosed)
        assertTrue(scene.producer.closeCaptureLeaseIfOwned(lease))
        // Ring state was not canceled/reset; the original unreserved capture is still consumable.
        val result = scene.consume(0)
        assertNull(result.failure)
        assertEquals(1, result.retainedFrames)
        assertEquals(40 * 32, result.retainedImageBytes)
        assertTrue(scene.flow.targets.isEmpty())
    }

    @Test fun foreignLeaseWithIdenticalTokenRemainsOpenAndConsumableByItsOriginalProducer() {
        val scene = UnknownMaterialPointTestScene()
        val other = UnknownMaterialPointTestScene()
        scene.offer(0); other.offer(0)
        val own = reserve(scene, 0)
        val foreign = reserve(other, 0)
        assertEquals(own.token.frameId, foreign.token.frameId)
        assertEquals(own.token.cpuImageTimestampNs, foreign.token.cpuImageTimestampNs)
        assertFalse(scene.producer.closeCaptureLeaseIfOwned(foreign))
        assertFalse(foreign.isClosed)
        assertFalse(own.isClosed)
        assertNull(other.producer.consume(other.token(0), 0, listOf(other.mask()),
            100_100, 5_000_000, foreign).failure)
        assertTrue(foreign.isClosed)
        assertFalse(own.isClosed)
        assertNull(scene.producer.consume(scene.token(0), 0, listOf(scene.mask()),
            100_100, 5_000_000, own).failure)
    }

    @Test fun alreadyClosedForeignIsStillForeignAndClosedOwnStillAcknowledgesLogicalOwnership() {
        val scene = UnknownMaterialPointTestScene()
        val other = UnknownMaterialPointTestScene()
        other.offer(0)
        val foreign = reserve(other, 0)
        foreign.close()
        assertFalse(scene.producer.closeCaptureLeaseIfOwned(foreign))
        assertTrue(foreign.isClosed)
        assertTrue(other.producer.closeCaptureLeaseIfOwned(foreign))
        assertTrue(other.producer.closeCaptureLeaseIfOwned(foreign))
    }

    @Test fun resetKeepsImmutableOwnershipAndLateOwnCloseCannotAffectTheNewEpochLease() {
        val scene = UnknownMaterialPointTestScene()
        scene.offer(0)
        val old = reserve(scene, 0)
        scene.producer.reset()
        assertTrue(old.isClosed)
        assertTrue(scene.offer(17, epoch = 10, geometry = "next", turns = 1).accepted)
        val token = scene.token(17, epoch = 10, geometry = "next")
        val current = requireNotNull(scene.producer.reserveCapture(token, 1, 100_017).lease)
        assertTrue(scene.producer.closeCaptureLeaseIfOwned(old))
        assertFalse(current.isClosed)
        assertNull(scene.producer.consume(token, 1, listOf(scene.mask()), 100_117, 5_000_000, current).failure)
        assertTrue(current.isClosed)
    }

    @Test fun ownedCloseDoesNotRefreshTheOriginalDeadline() {
        val scene = UnknownMaterialPointTestScene()
        scene.offer(0)
        val lease = reserve(scene, 0)
        assertTrue(scene.producer.closeCaptureLeaseIfOwned(lease))
        val expired = scene.consume(0, delayMs = 801)
        assertEquals(VisualTrackingFailure.SOURCE_EXPIRED, expired.failure)
        assertTrue(expired.materialPointsByMaskIndex.isEmpty())
        assertTrue(scene.flow.targets.isEmpty())
    }

    @Test fun ownAndForeignCloseReturnWithoutWaitingForTheProducersBlockedConsumeMonitor() {
        val scene = UnknownMaterialPointTestScene()
        val other = UnknownMaterialPointTestScene()
        other.offer(0)
        val foreign = reserve(other, 0)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val flow = object : VisualMotionEstimator by scene.flow {
            override fun advance(source: GrayTrackingFrame, previous: GrayTrackingFrame, target: GrayTrackingFrame,
                detection: DetectionCandidate, features: List<VisualMotionFeature>, originalFeatureCount: Int,
                budget: TrackingWorkBudget, materialPoint: VisualMotionPoint?): VisualMotionEstimate {
                entered.countDown()
                check(proceed.await(5, TimeUnit.SECONDS))
                return scene.flow.advance(source, previous, target, detection, features,
                    originalFeatureCount, budget, materialPoint)
            }
        }
        val producer = UnknownMaterialPointTracker(VisualTrackingConfig(), { scene.nanoTime }, flow)
        fun offer(at: Long) {
            val token = scene.token(at)
            check(producer.offerLuma(token, 0, 100_000 + at, token.cpuImageTimestampNs,
                40, 32, ByteBuffer.wrap(ByteArray(40 * 32) { 77 }), 40, 1).accepted)
        }
        offer(0)
        assertNull(producer.consume(scene.token(0), 0, listOf(scene.mask()), 100_100, 5_000_000).failure)
        offer(50)
        val own = requireNotNull(producer.reserveCapture(scene.token(50), 0, 100_050).lease)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val consumed = threads.submit<UnknownMaterialPointTrackingResult> {
                producer.consume(scene.token(50), 0, listOf(scene.mask()), 100_150, 5_000_000, own)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(threads.submit<Boolean> { producer.closeCaptureLeaseIfOwned(foreign) }.get(1, TimeUnit.SECONDS))
            assertFalse(foreign.isClosed)
            assertFalse(own.isClosed)
            assertTrue(threads.submit<Boolean> { producer.closeCaptureLeaseIfOwned(own) }.get(1, TimeUnit.SECONDS))
            assertTrue(own.isClosed)
            assertTrue(threads.submit<Boolean> { producer.closeCaptureLeaseIfOwned(own) }.get(1, TimeUnit.SECONDS))
            // Native work has not finished; the return value acknowledges only Atomic logical close.
            assertEquals(1L, proceed.count)
            proceed.countDown()
            val result = consumed.get(2, TimeUnit.SECONDS)
            assertEquals(VisualTrackingFailure.DETECTION_IDENTITY_CONFLICT, result.failure)
            assertTrue(result.materialPointsByMaskIndex.isEmpty())
            assertTrue(result.trackingMetrics.isNotEmpty())
            assertFalse(foreign.isClosed)
        } finally {
            proceed.countDown()
            threads.shutdownNow()
        }
    }

    private fun reserve(scene: UnknownMaterialPointTestScene, at: Long) =
        requireNotNull(scene.producer.reserveCapture(scene.token(at), 0, 100_000 + at).lease)
}
