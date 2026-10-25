package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.DepthAvailability
import kr.co.hanium.dreamup.walksafe.depth.ObjectMovementDirection
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownObjectDepthPipeline
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointTestScene
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

/** Actual Main consumer/producer/pipeline; fake measured flow, not Activity/GL/native LK. */
class MainActivityUnknownMaterialPointIntegrationTest {
    @Test fun actualMainConsumerMustPassMeasuredPointsWithTheOriginalCaptureInsteadOfDefaultEmpty() {
        val main = MainActivity()
        val scene = UnknownMaterialPointTestScene()
        var nowMs = 100_100L
        var offeredUntil = -25L
        val pipeline = UnknownObjectDepthPipeline(elapsedRealtimeMs = { nowMs })
        val results = (0L..1_800L step 200L).map { at ->
            scene.offerThrough(offeredUntil + 25L, at + 100L, 25L)
            offeredUntil = at + 100L
            val capture = scene.capture(at)
            nowMs = capture.token.capturedElapsedNs / 1_000_000L + 100L
            main.processUnknownMaterialPointCapture(pipeline, scene.producer, capture, capture.token,
                listOf(scene.mask()), nowMs, scene.nanoTime, 5_000_000L, { scene.nanoTime })
        }
        val latest = results.last()
        assertTrue("The actual Main call must consume the measured LK producer", scene.flow.seeds > 0)
        assertEquals(ObjectMovementDirection.STATIONARY, latest.objects.single().motionEstimate.direction)
        assertEquals(0f, latest.objects.single().motionEstimate.objectSpeedMps!!, .0001f)
        assertEquals(scene.token(1_800L).frameId, latest.token.frameId)
        assertEquals(scene.token(1_800L).cameraTimestampNs / 1_000_000L,
            latest.objects.single().motionEstimate.observedAtMs)
        assertEquals(2f, latest.objects.single().riskDistanceM!!, .001f)
        assertTrue(results.all { it.objects.single().depthAvailability == DepthAvailability.MEASURED })
    }

    @Test fun firstCaptureWithoutAFlowPointKeepsTheExistingMeasuredScalarAndUnknownMotion() {
        val scene = UnknownMaterialPointTestScene()
        scene.offerThrough(0L, 100L, 25L)
        val capture = scene.capture(0L)
        val nowMs = capture.token.capturedElapsedNs / 1_000_000L + 100L
        val result = MainActivity().processUnknownMaterialPointCapture(
            UnknownObjectDepthPipeline(elapsedRealtimeMs = { nowMs }), scene.producer,
            capture, capture.token, listOf(scene.mask()), nowMs, scene.nanoTime, 5_000_000L,
            { scene.nanoTime },
        )
        assertEquals(DepthAvailability.MEASURED, result.objects.single().depthAvailability)
        assertEquals(2f, result.objects.single().riskDistanceM!!, .001f)
        assertEquals(ObjectMovementDirection.UNKNOWN, result.objects.single().motionEstimate.direction)
        assertNull(result.objects.single().motionEstimate.objectSpeedMps)
    }

    @Test fun callbackAllowanceAlreadySpentCannotStartLKOrGrantAnotherFiveMilliseconds() {
        val scene = UnknownMaterialPointTestScene()
        scene.offerThrough(0L, 100L, 25L)
        scene.nanoTime = 5_000_000L
        val capture = scene.capture(0L)
        val now = capture.token.capturedElapsedNs / 1_000_000L + 100L
        val result = MainActivity().processUnknownMaterialPointCapture(
            UnknownObjectDepthPipeline(elapsedRealtimeMs = { now }), scene.producer,
            capture, capture.token, listOf(scene.mask()), now, 0L, 5_000_000L, { scene.nanoTime },
        )
        assertEquals(0, scene.flow.seeds)
        assertTrue(scene.flow.targets.isEmpty())
        assertEquals(2f, result.objects.single().riskDistanceM!!, .001f)
        assertEquals(ObjectMovementDirection.UNKNOWN, result.objects.single().motionEstimate.direction)
    }

    @Test fun partialAllowanceIsSharedByAllNativeHopsInsteadOfResetAtTheProducer() {
        val scene = UnknownMaterialPointTestScene()
        scene.offerThrough(0L, 300L, 25L)
        var now = scene.token(0L).capturedElapsedNs / 1_000_000L + 100L
        val pipeline = UnknownObjectDepthPipeline(elapsedRealtimeMs = { now })
        val main = MainActivity()
        val first = scene.capture(0L)
        main.processUnknownMaterialPointCapture(pipeline, scene.producer, first, first.token,
            listOf(scene.mask()), now, 0L, 5_000_000L, { scene.nanoTime })
        scene.nanoTime = 4_000_000L
        scene.flow.advanceDurationNs = 2_000_000L
        val next = scene.capture(200L)
        now = next.token.capturedElapsedNs / 1_000_000L + 100L
        val countBefore = scene.flow.targets.size
        val result = main.processUnknownMaterialPointCapture(pipeline, scene.producer, next, next.token,
            listOf(scene.mask()), now, 0L, 5_000_000L, { scene.nanoTime })
        assertEquals("One completed overrun consumes the remaining allowance", 1,
            scene.flow.targets.size - countBefore)
        assertEquals(6_000_000L, scene.nanoTime)
        assertEquals(ObjectMovementDirection.UNKNOWN, result.objects.single().motionEstimate.direction)
        assertEquals(2f, result.objects.single().riskDistanceM!!, .001f)
    }

    @Test fun producerMonitorWaitUsesTheSameCallbackDeadline() {
        val scene = UnknownMaterialPointTestScene()
        scene.offerThrough(0L, 100L, 25L)
        val capture = scene.capture(0L)
        val now = capture.token.capturedElapsedNs / 1_000_000L + 100L
        val result = blockedConsumer(scene, beforeRelease = { scene.nanoTime = 6_000_000L }) {
            MainActivity().processUnknownMaterialPointCapture(
                UnknownObjectDepthPipeline(elapsedRealtimeMs = { now }), scene.producer,
                capture, capture.token, listOf(scene.mask()), now, 0L, 5_000_000L, { scene.nanoTime },
            )
        }
        assertEquals(0, scene.flow.seeds)
        assertTrue(scene.flow.targets.isEmpty())
        assertEquals(2f, result.objects.single().riskDistanceM!!, .001f)
    }

    @Test fun cancellationDuringMonitorWaitCannotConsumeOrPublishTheOldContext() {
        val scene = UnknownMaterialPointTestScene()
        scene.offerThrough(0L, 100L, 25L)
        val capture = scene.capture(0L)
        val now = capture.token.capturedElapsedNs / 1_000_000L + 100L
        val lease = requireNotNull(scene.producer.reserveCapture(capture.token, 0, now).lease)
        val active = AtomicBoolean(true)
        val result = blockedConsumer(scene, beforeRelease = { active.set(false) }) {
            MainActivity().processUnknownMaterialPointCapture(
                UnknownObjectDepthPipeline(elapsedRealtimeMs = { now }), scene.producer,
                capture, capture.token, listOf(scene.mask()), now, 0L, 5_000_000L, { scene.nanoTime },
                captureLease = lease, contextIsCurrent = active::get,
            )
        }
        assertEquals("capture_context_invalidated", result.rejectionReason)
        assertTrue(result.objects.isEmpty())
        assertEquals(0, scene.flow.seeds)
        assertTrue(lease.isClosed)
    }

    @Test fun foreignResultTokenDoesNotAdvanceTheOriginalCaptureOrRunLK() {
        val scene = UnknownMaterialPointTestScene()
        scene.offerThrough(0L, 100L, 25L)
        val capture = scene.capture(0L)
        val now = capture.token.capturedElapsedNs / 1_000_000L + 100L
        val original = capture.token
        val foreign = FastSamFrameToken(original.source, original.sessionEpoch, original.frameId,
            original.cameraTimestampNs, original.cpuImageTimestampNs + 1L, original.capturedElapsedNs,
            original.geometryId, original.width, original.height)
        val result = MainActivity().processUnknownMaterialPointCapture(
            UnknownObjectDepthPipeline(elapsedRealtimeMs = { now }), scene.producer,
            capture, foreign, listOf(scene.mask()), now, 0L, 5_000_000L, { scene.nanoTime },
        )
        assertEquals("capture_token_mismatch", result.rejectionReason)
        assertTrue(result.objects.isEmpty())
        assertEquals(0, scene.flow.seeds)
    }

    @Test fun realLostMaskSupportRetiresMotionButPreservesMeasuredDistance() {
        val warm = warmTracking()
        val (scene, pipeline, main) = warm
        scene.offerThrough(1_325L, 1_500L, 25L)
        val capture = scene.capture(1_400L)
        val now = capture.token.capturedElapsedNs / 1_000_000L + 100L
        warm.clock.set(now)
        val result = main.processUnknownMaterialPointCapture(pipeline, scene.producer,
            capture, capture.token, listOf(scene.mask(hole = 18 to 15)), now,
            scene.nanoTime, 5_000_000L, { scene.nanoTime })
        assertEquals(ObjectMovementDirection.UNKNOWN, result.objects.single().motionEstimate.direction)
        assertNull(result.objects.single().motionEstimate.objectSpeedMps)
        assertEquals(DepthAvailability.MEASURED, result.objects.single().depthAvailability)
        assertEquals(2f, result.objects.single().riskDistanceM!!, .001f)
    }

    @Test fun overlappingMasksCannotCreateAReplacementMotionPoint() {
        val warm = warmTracking()
        val (scene, pipeline, main) = warm
        scene.offerThrough(1_325L, 1_500L, 25L)
        val capture = scene.capture(1_400L)
        val now = capture.token.capturedElapsedNs / 1_000_000L + 100L
        warm.clock.set(now)
        val result = main.processUnknownMaterialPointCapture(pipeline, scene.producer,
            capture, capture.token, listOf(scene.mask(), scene.mask()), now,
            scene.nanoTime, 5_000_000L, { scene.nanoTime })
        assertNull(result.rejectionReason)
        assertEquals(2, result.observations.size)
        assertTrue(result.objects.all { it.motionEstimate.direction == ObjectMovementDirection.UNKNOWN })
        assertTrue(result.objects.all { it.motionEstimate.objectSpeedMps == null })
    }

    @Test fun completionAgeIsCheckedAfterMonitorWaitUsingOriginalCaptureElapsed() {
        val scene = UnknownMaterialPointTestScene()
        scene.offerThrough(0L, 100L, 25L)
        val capture = scene.capture(0L)
        val now = capture.token.capturedElapsedNs / 1_000_000L + 100L
        var completed = now
        val result = blockedConsumer(scene, beforeRelease = { completed = now + 1_000L }) {
            MainActivity().processUnknownMaterialPointCapture(
                UnknownObjectDepthPipeline(elapsedRealtimeMs = { completed }), scene.producer,
                capture, capture.token, listOf(scene.mask()), now, 0L, 5_000_000L, { scene.nanoTime },
                elapsedRealtimeMs = { completed },
            )
        }
        assertEquals("source_frame_expired", result.rejectionReason)
        assertTrue(result.objects.isEmpty())
        assertEquals(0, scene.flow.seeds)
        assertEquals(100_000_000_000L, capture.token.capturedElapsedNs)
    }

    @Test fun exactReservedCaptureCanBeConsumedAndClosedWithoutSuppressingNormalOutput() {
        val scene = UnknownMaterialPointTestScene()
        scene.offer(0L)
        val capture = scene.capture(0L)
        val lease = requireNotNull(scene.producer.reserveCapture(capture.token, 0, 100_000L).lease)
        scene.offerThrough(17L, 612L, 17L)
        val now = capture.token.capturedElapsedNs / 1_000_000L + 612L
        val result = MainActivity().processUnknownMaterialPointCapture(
            UnknownObjectDepthPipeline(elapsedRealtimeMs = { now }), scene.producer,
            capture, capture.token, listOf(scene.mask()), now, 0L, 5_000_000L, { scene.nanoTime },
            captureLease = lease,
        )
        assertTrue(lease.isClosed)
        assertNull(result.rejectionReason)
        // This original first batch has no flowed point, even with its source retained.
        assertTrue(scene.flow.targets.isEmpty())
        assertEquals(ObjectMovementDirection.UNKNOWN, result.objects.single().motionEstimate.direction)
        assertEquals(capture.token, result.token)
        assertEquals(2f, result.objects.single().riskDistanceM!!, .001f)
    }

    @Test fun anotherProducerLeaseIsNeitherAcceptedNorClosedByThisConsumer() {
        val scene = UnknownMaterialPointTestScene()
        val foreignScene = UnknownMaterialPointTestScene()
        scene.offerThrough(0L, 100L, 25L)
        foreignScene.offerThrough(0L, 100L, 25L)
        val capture = scene.capture(0L)
        val now = capture.token.capturedElapsedNs / 1_000_000L + 100L
        val foreign = requireNotNull(foreignScene.producer.reserveCapture(capture.token, 0, now).lease)
        try {
            val result = MainActivity().processUnknownMaterialPointCapture(
                UnknownObjectDepthPipeline(elapsedRealtimeMs = { now }), scene.producer,
                capture, capture.token, listOf(scene.mask()), now, 0L, 5_000_000L, { scene.nanoTime },
                captureLease = foreign,
            )
            assertEquals(0, scene.flow.seeds)
            assertEquals(ObjectMovementDirection.UNKNOWN, result.objects.single().motionEstimate.direction)
            assertFalse(foreign.isClosed)
        } finally { foreign.close() }
    }

    @Test fun foreignLeaseIsNotClosedWhenContextIsRejectedBeforeDispatch() {
        val scene = UnknownMaterialPointTestScene()
        val foreignScene = UnknownMaterialPointTestScene()
        scene.offerThrough(0L, 100L, 25L)
        foreignScene.offerThrough(0L, 100L, 25L)
        val capture = scene.capture(0L)
        val now = capture.token.capturedElapsedNs / 1_000_000L + 100L
        val foreign = requireNotNull(foreignScene.producer.reserveCapture(capture.token, 0, now).lease)
        try {
            val result = MainActivity().processUnknownMaterialPointCapture(
                UnknownObjectDepthPipeline(elapsedRealtimeMs = { now }), scene.producer,
                capture, capture.token, listOf(scene.mask()), now, 0L, 5_000_000L, { scene.nanoTime },
                captureLease = foreign, contextIsCurrent = { false },
            )
            assertEquals("capture_context_invalidated", result.rejectionReason)
            assertTrue(result.objects.isEmpty())
            assertEquals(0, scene.flow.seeds)
            assertFalse("A rejected callback cannot release another producer's lease", foreign.isClosed)
        } finally { foreign.close() }
    }

    @Test fun elapsedClockExceptionBeforeConsumeReleasesItsOwnedLeaseWithoutNativeEntry() {
        val scene = UnknownMaterialPointTestScene()
        scene.offerThrough(0L, 100L, 25L)
        val capture = scene.capture(0L)
        val now = capture.token.capturedElapsedNs / 1_000_000L + 100L
        val own = requireNotNull(scene.producer.reserveCapture(capture.token, 0, now).lease)
        val failure = IllegalStateException("callback elapsed clock unavailable")
        try {
            try {
                MainActivity().processUnknownMaterialPointCapture(
                    UnknownObjectDepthPipeline(elapsedRealtimeMs = { now }), scene.producer,
                    capture, capture.token, listOf(scene.mask()), now, 0L, 5_000_000L, { scene.nanoTime },
                    captureLease = own, elapsedRealtimeMs = { throw failure },
                )
                fail("The original clock failure must propagate")
            } catch (observed: IllegalStateException) { assertSame(failure, observed) }
            assertEquals(0, scene.flow.seeds)
            assertTrue(scene.flow.targets.isEmpty())
            assertTrue("A failure before producer entry still releases its exact owned lease", own.isClosed)
        } finally { own.close() }
    }

    private data class WarmTracking(val scene: UnknownMaterialPointTestScene,
        val pipeline: UnknownObjectDepthPipeline, val main: MainActivity, val clock: AtomicLong)

    private fun warmTracking(): WarmTracking {
        val scene = UnknownMaterialPointTestScene()
        var now = 100_100L
        val clock = AtomicLong(now)
        var offeredUntil = -25L
        val pipeline = UnknownObjectDepthPipeline(elapsedRealtimeMs = clock::get)
        val main = MainActivity()
        for (at in 0L..1_200L step 200L) {
            scene.offerThrough(offeredUntil + 25L, at + 100L, 25L)
            offeredUntil = at + 100L
            val capture = scene.capture(at)
            now = capture.token.capturedElapsedNs / 1_000_000L + 100L
            clock.set(now)
            val result = main.processUnknownMaterialPointCapture(pipeline, scene.producer,
                capture, capture.token, listOf(scene.mask()), now, scene.nanoTime, 5_000_000L, { scene.nanoTime })
            if (at == 1_200L) assertEquals(ObjectMovementDirection.STATIONARY,
                result.objects.single().motionEstimate.direction)
        }
        return WarmTracking(scene, pipeline, main, clock)
    }

    /** Real JVM monitor contention; no Activity, UI/GL scheduling, or native LK is executed. */
    private fun <T : Any> blockedConsumer(scene: UnknownMaterialPointTestScene,
        beforeRelease: () -> Unit, consume: () -> T): T {
        val entered = CountDownLatch(1)
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        val worker = Thread {
            entered.countDown()
            try { result.set(consume()) } catch (failure: Throwable) { error.set(failure) }
        }
        synchronized(scene.producer) {
            worker.start()
            assertTrue(entered.await(5L, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (worker.state != Thread.State.BLOCKED && worker.isAlive && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals("Consumer must actually wait for the producer monitor", Thread.State.BLOCKED, worker.state)
            beforeRelease()
        }
        worker.join(5_000L)
        assertFalse("Consumer did not complete", worker.isAlive)
        error.get()?.let { throw it }
        return requireNotNull(result.get())
    }
}
