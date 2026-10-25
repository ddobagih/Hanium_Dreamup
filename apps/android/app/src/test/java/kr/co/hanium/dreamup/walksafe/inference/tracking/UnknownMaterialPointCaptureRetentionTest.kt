package kr.co.hanium.dreamup.walksafe.inference.tracking

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import org.junit.Assert.*
import org.junit.Test

class UnknownMaterialPointCaptureRetentionTest {
    @Test fun unexpiredSourceIsRetainedDuring612msFastSamFlight() {
        val scene = UnknownMaterialPointTestScene()
        assertTrue(scene.offer(0).accepted)
        val lease = reserve(scene, 0)
        scene.offerThrough(17, 612, 17)
        val result = consume(scene, 0, lease, 612)
        // Same stable-v4 counterexample, with the newly required pre-submission reservation.
        // The first mask legitimately has no LK point; admission is the product assertion.
        assertNull(result.failure)
        assertTrue(result.materialPointsByMaskIndex.isEmpty())
        assertEquals(32, result.retainedFrames)
        assertEquals(32 * 40 * 32, result.retainedImageBytes)
        assertTrue(scene.flow.targets.isEmpty())
        assertTrue(lease.isClosed)
    }

    @Test fun unreservedSourcesStillFollowTheOriginalOldestEvictionOrder() {
        val scene = UnknownMaterialPointTestScene()
        scene.offerThrough(0, 612, 17)
        assertEquals(VisualTrackingFailure.SOURCE_NOT_IN_HISTORY, scene.consume(0, delayMs = 612).failure)
    }

    @Test fun discardOrFailedSubmissionClosesOnlyItsSourceAndMakesItEvictable() {
        val scene = UnknownMaterialPointTestScene()
        scene.offer(0)
        val lease = reserve(scene, 0)
        scene.offerThrough(17, 527, 17)
        lease.close(); lease.close()
        assertTrue(scene.offer(544).accepted)
        assertEquals(VisualTrackingFailure.SOURCE_NOT_IN_HISTORY, consume(scene, 0, lease, 544).failure)
    }

    @Test fun duplicateMissingScopeAndExpiredReservationsRefuseWithoutChangingAnotherOwner() {
        val scene = UnknownMaterialPointTestScene()
        scene.offer(0)
        val lease = reserve(scene, 0)
        assertEquals(VisualTrackingFailure.DETECTION_IDENTITY_CONFLICT,
            scene.producer.reserveCapture(scene.token(0), 0, 100_000).failure)
        assertNull(scene.producer.reserveCapture(scene.token(0), 0, 100_000).lease)
        assertEquals(VisualTrackingFailure.SOURCE_NOT_IN_HISTORY,
            scene.producer.reserveCapture(scene.token(17), 0, 100_017).failure)
        assertEquals(VisualTrackingFailure.EPOCH_OR_GEOMETRY_CHANGED,
            scene.producer.reserveCapture(scene.token(0), 1, 100_000).failure)
        assertEquals(VisualTrackingFailure.EPOCH_OR_GEOMETRY_CHANGED,
            scene.producer.reserveCapture(scene.token(0, epoch = 8), 0, 100_000).failure)
        assertFalse(lease.isClosed)
        assertNull(consume(scene, 0, lease).failure)
        assertEquals(VisualTrackingFailure.DETECTOR_SOURCE_OUT_OF_ORDER,
            scene.producer.reserveCapture(scene.token(0), 0, 100_000).failure)
    }

    @Test fun successOversizeBudgetRefusalAndExceptionAllReleaseTheirExactLease() {
        for (outcome in listOf("success", "oversize", "zero-budget", "invalid-budget")) {
            val scene = UnknownMaterialPointTestScene()
            scene.offer(0)
            val lease = reserve(scene, 0)
            when (outcome) {
                "success" -> assertNull(consume(scene, 0, lease).failure)
                "oversize" -> assertEquals(VisualTrackingFailure.INPUT_BUDGET_EXCEEDED,
                    scene.producer.consume(scene.token(0), 0, List(101) { scene.mask() }, 100_100, 5_000_000, lease).failure)
                "zero-budget" -> assertEquals(VisualTrackingFailure.TIME_BUDGET_EXCEEDED,
                    scene.producer.consume(scene.token(0), 0, listOf(scene.mask()), 100_100, 0, lease).failure)
                else -> {
                    try {
                        scene.producer.consume(scene.token(0), 0, listOf(scene.mask()), 100_100, -1, lease)
                        fail("invalid remaining budget must be refused")
                    } catch (_: IllegalArgumentException) { }
                }
            }
            assertTrue("$outcome must release", lease.isClosed)
        }
    }

    @Test fun staleLeaseCannotConsumeOrCloseAReplacementReservation() {
        val scene = UnknownMaterialPointTestScene()
        scene.offer(0)
        val old = reserve(scene, 0)
        old.close()
        val replacement = reserve(scene, 0)
        assertNotSame(old, replacement)
        assertEquals(VisualTrackingFailure.DETECTION_IDENTITY_CONFLICT, consume(scene, 0, old).failure)
        old.close()
        assertFalse(replacement.isClosed)
        scene.offerThrough(17, 612, 17)
        assertNull(consume(scene, 0, replacement, 612).failure)
    }

    @Test fun olderDuplicateOfferCannotExpireTheNewerCaptureReservation() {
        val scene = UnknownMaterialPointTestScene()
        scene.offer(0); scene.offer(17)
        val newest = reserve(scene, 17)
        assertTrue(scene.offer(0).accepted) // Existing same-pixels duplicate admission remains valid.
        assertFalse(newest.isClosed)
        assertNull(consume(scene, 17, newest).failure)
    }

    @Test fun missingOrForeignLeaseCannotConsumeAReservedSourceOrReleaseItsOwner() {
        val scene = UnknownMaterialPointTestScene()
        val other = UnknownMaterialPointTestScene()
        scene.offer(0); other.offer(0)
        val own = reserve(scene, 0)
        val foreign = reserve(other, 0)
        assertEquals(VisualTrackingFailure.DETECTION_IDENTITY_CONFLICT, scene.consume(0).failure)
        assertFalse(own.isClosed)
        assertEquals(VisualTrackingFailure.DETECTION_IDENTITY_CONFLICT, consume(scene, 0, foreign).failure)
        assertFalse(own.isClosed)
        assertFalse(foreign.isClosed)
        assertNull(consume(scene, 0, own).failure)
        assertNull(consume(other, 0, foreign).failure)
    }

    @Test fun resetClosesOldLeaseAndItsLateCloseCannotTouchTheNewCapture() {
        val scene = UnknownMaterialPointTestScene()
        scene.offer(0)
        val old = reserve(scene, 0)
        scene.producer.reset()
        assertTrue(old.isClosed)
        assertEquals(VisualTrackingFailure.FRAME_ORDER_INVALID, scene.offer(0).failure)
        assertTrue(scene.offer(17).accepted)
        val current = reserve(scene, 17)
        old.close()
        assertFalse(current.isClosed)
        scene.offerThrough(34, 629, 17)
        assertNull(consume(scene, 17, current, 612).failure)
    }

    @Test fun contextChangeReleasesOldLeaseAndLateOldResultDoesNotReleaseNewContext() {
        val scene = UnknownMaterialPointTestScene()
        scene.offer(0)
        val old = reserve(scene, 0)
        assertTrue(scene.offer(17, epoch = 10, geometry = "new-context", turns = 1).reset)
        val token = scene.token(17, epoch = 10, geometry = "new-context")
        val current = requireNotNull(scene.producer.reserveCapture(token, 1, 100_017).lease)
        assertTrue(old.isClosed)
        assertEquals(VisualTrackingFailure.EPOCH_OR_GEOMETRY_CHANGED, consume(scene, 0, old).failure)
        assertFalse(current.isClosed)
        assertNull(scene.producer.consume(token, 1, listOf(scene.mask()), 100_117, 5_000_000, current).failure)
        assertTrue(current.isClosed)
    }

    @Test fun cancelCaptureClosesOnlyItsMatchingLease() {
        val scene = UnknownMaterialPointTestScene()
        scene.offer(0); scene.offer(17)
        val old = reserve(scene, 0)
        val current = reserve(scene, 17)
        assertTrue(scene.producer.cancelCapture(scene.token(0)))
        assertTrue(old.isClosed)
        assertFalse(current.isClosed)
        assertFalse(scene.producer.cancelCapture(scene.token(0)))
        assertEquals(VisualTrackingFailure.SOURCE_NOT_IN_HISTORY, consume(scene, 0, old).failure)
        assertNull(consume(scene, 17, current).failure)
    }

    @Test fun closeAndReReserveDoesNotRenewTheOriginal800msDeadline() {
        val scene = UnknownMaterialPointTestScene()
        scene.offer(0)
        reserve(scene, 0).close()
        val renewedOwner = requireNotNull(scene.producer.reserveCapture(scene.token(0), 0, 100_799).lease)
        assertEquals(VisualTrackingFailure.SOURCE_EXPIRED, consume(scene, 0, renewedOwner, 801).failure)
        assertTrue(renewedOwner.isClosed)
        assertEquals(VisualTrackingFailure.SOURCE_EXPIRED,
            scene.producer.reserveCapture(scene.token(0), 0, 100_801).failure)
        assertTrue(scene.flow.targets.isEmpty())
    }

    @Test fun liveFrameAgingAndConfiguredHistoryAgeOverrideReservations() {
        val scene = UnknownMaterialPointTestScene()
        scene.offer(0)
        val lease = reserve(scene, 0)
        scene.offer(801)
        assertTrue(lease.isClosed)
        assertEquals(VisualTrackingFailure.SOURCE_EXPIRED, consume(scene, 0, lease, 801).failure)
        val short = UnknownMaterialPointTestScene(VisualTrackingConfig(maxHistoryAgeMs = 100))
        short.offer(0)
        val shortLease = reserve(short, 0)
        short.offer(101)
        assertTrue(shortLease.isClosed)
        assertEquals(VisualTrackingFailure.SOURCE_NOT_IN_HISTORY, consume(short, 0, shortLease, 101).failure)
    }

    @Test fun all32ProtectedSourcesRefuseNewAdmissionWithoutEvictingOneOrAdvancingWatermark() {
        val scene = UnknownMaterialPointTestScene()
        val leases = (0L..527L step 17L).map { at ->
            assertTrue(scene.offer(at).accepted)
            reserve(scene, at)
        }
        assertEquals(32, leases.size)
        val refused = scene.offer(544)
        assertFalse(refused.accepted)
        assertEquals(VisualTrackingFailure.INPUT_BUDGET_EXCEEDED, refused.failure)
        assertTrue(leases.none { it.isClosed })
        val token = scene.token(544)
        assertEquals(VisualTrackingFailure.INPUT_BUDGET_EXCEEDED,
            scene.producer.offerLuma(token, 0, 100_544, token.cpuImageTimestampNs,
                40, 32, ByteBuffer.allocate(0), 40, 1).failure) // No luma copy when all sources are pinned.
        val missing = scene.producer.reserveCapture(scene.token(544), 0, 100_544)
        assertNull(missing.lease)
        assertEquals(VisualTrackingFailure.SOURCE_NOT_IN_HISTORY, missing.failure)
        val result = consume(scene, 0, leases.first(), 544)
        assertNull(result.failure)
        assertEquals(32, result.retainedFrames)
        assertEquals(32 * 40 * 32, result.retainedImageBytes)
        assertTrue(leases.drop(1).none { it.isClosed })
        assertTrue(scene.offer(544).accepted) // Retry is admissible: rejection did not raise watermark.
        val next = reserve(scene, 544)
        leases.first().close()
        assertFalse(next.isClosed)
        assertTrue(leases.drop(1).none { it.isClosed })
    }

    @Test fun failedLumaCopyDoesNotEvictItsPlannedVictimOrReleaseTheOtherOwner() {
        val scene = UnknownMaterialPointTestScene(VisualTrackingConfig(maxHistoryFrames = 2))
        scene.offer(0)
        val lease = reserve(scene, 0)
        scene.offer(17)
        val token = scene.token(34)
        assertEquals(VisualTrackingFailure.INVALID_GEOMETRY,
            scene.producer.offerLuma(token, 0, 100_034, token.cpuImageTimestampNs,
                40, 32, ByteBuffer.allocate(0), 40, 1).failure)
        assertFalse(lease.isClosed)
        val retained = scene.consume(17)
        assertNull(retained.failure)
        assertEquals(2, retained.retainedFrames)
        lease.close()
        assertTrue(scene.offer(34).accepted)
    }

    @Test fun reservationAgesInOriginalCpuAndElapsedClocksWithoutSubstitutingArCameraClock() {
        for (cpuAges in listOf(false, true)) {
            val scene = UnknownMaterialPointTestScene()
            scene.offer(0)
            val lease = reserve(scene, 0)
            val source = scene.token(0)
            val normal = scene.token(17)
            val measured = FastSamFrameToken(normal.source, normal.sessionEpoch, normal.frameId,
                source.cameraTimestampNs + (if (cpuAges) 17 else 2_000) * 1_000_000L,
                source.cpuImageTimestampNs + (if (cpuAges) 850 else 17) * 1_000_000L,
                normal.capturedElapsedNs, normal.geometryId, normal.width, normal.height)
            assertTrue(scene.producer.offerLuma(measured, 0, 100_017, measured.cpuImageTimestampNs,
                40, 32, ByteBuffer.wrap(ByteArray(40 * 32) { 77 }), 40, 1).accepted)
            assertEquals(cpuAges, lease.isClosed)
            val result = consume(scene, 0, lease, 17)
            if (cpuAges) assertEquals(VisualTrackingFailure.SOURCE_EXPIRED, result.failure)
            else assertNull(result.failure)
        }
    }

    @Test fun sourcePinDoesNotRelaxTheExisting120msFlowGap() {
        val scene = UnknownMaterialPointTestScene()
        scene.offer(0)
        val lease = reserve(scene, 0)
        scene.offer(200)
        assertNull(consume(scene, 0, lease, 200).failure)
        val result = scene.consume(200, delayMs = 0)
        assertEquals(VisualTrackingFailure.FRAME_GAP, result.failure)
        assertTrue(result.materialPointsByMaskIndex.isEmpty())
        assertTrue(scene.flow.targets.isEmpty())
    }

    @Test fun atomicCloseDoesNotWaitForBlockedNativeMonitorAndCanceledResultHasNoPoint() {
        val scene = UnknownMaterialPointTestScene()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val flow = object : VisualMotionEstimator by scene.flow {
            override fun advance(source: GrayTrackingFrame, previous: GrayTrackingFrame, target: GrayTrackingFrame,
                detection: DetectionCandidate, features: List<VisualMotionFeature>, originalFeatureCount: Int,
                budget: TrackingWorkBudget, materialPoint: VisualMotionPoint?): VisualMotionEstimate {
                entered.countDown()
                check(proceed.await(3, TimeUnit.SECONDS))
                return scene.flow.advance(source, previous, target, detection, features, originalFeatureCount, budget, materialPoint)
            }
        }
        val producer = UnknownMaterialPointTracker(VisualTrackingConfig(), { scene.nanoTime }, flow)
        fun offer(at: Long) {
            val token = scene.token(at)
            assertTrue(producer.offerLuma(token, 0, 100_000 + at, token.cpuImageTimestampNs,
                40, 32, ByteBuffer.wrap(ByteArray(40 * 32) { 77 }), 40, 1).accepted)
        }
        offer(0)
        assertNull(producer.consume(scene.token(0), 0, listOf(scene.mask()), 100_100, 5_000_000).failure)
        offer(50)
        val lease = requireNotNull(producer.reserveCapture(scene.token(50), 0, 100_050).lease)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val consumed = threads.submit<UnknownMaterialPointTrackingResult> {
                producer.consume(scene.token(50), 0, listOf(scene.mask()), 100_150, 5_000_000, lease)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            threads.submit { lease.close() }.get(1, TimeUnit.SECONDS)
            assertTrue(lease.isClosed)
            proceed.countDown()
            val result = consumed.get(2, TimeUnit.SECONDS)
            assertEquals(VisualTrackingFailure.DETECTION_IDENTITY_CONFLICT, result.failure)
            assertTrue(result.materialPointsByMaskIndex.isEmpty())
            assertTrue(result.trackingMetrics.isNotEmpty())
        } finally {
            proceed.countDown()
            threads.shutdownNow()
        }
    }

    private fun reserve(scene: UnknownMaterialPointTestScene, at: Long): UnknownMaterialPointCaptureLease {
        val result = scene.producer.reserveCapture(scene.token(at), 0, 100_000 + at)
        assertNull(result.failure)
        return requireNotNull(result.lease)
    }
    private fun consume(scene: UnknownMaterialPointTestScene, at: Long, lease: UnknownMaterialPointCaptureLease,
        delay: Long = 100) = scene.producer.consume(scene.token(at), 0, listOf(scene.mask()),
        100_000 + at + delay, 5_000_000, lease)
}
