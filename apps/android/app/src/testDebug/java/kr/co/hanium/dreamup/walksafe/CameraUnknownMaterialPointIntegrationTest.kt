package android.media

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.CameraUnknownMaterialPointOwner
import kr.co.hanium.dreamup.walksafe.CameraUnknownMaterialPointResult
import kr.co.hanium.dreamup.walksafe.CameraUnknownMaterialPointScope
import kr.co.hanium.dreamup.walksafe.CameraUnknownMaterialPointFlight
import kr.co.hanium.dreamup.walksafe.CameraTestFeedbackCoordinator
import kr.co.hanium.dreamup.walksafe.discardRetiredCameraUnknownOverlay
import kr.co.hanium.dreamup.walksafe.offerCameraUnknownFeedbackAfterRender
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.ObjectMovementDirection
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownObjectDepthPipeline
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointTestScene
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointTracker
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingConfig
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import org.junit.Assert.*
import org.junit.Test

/** Native-free Image/Plane constructors require this SDK package; no Activity/GL/native inference runs. */
class CameraUnknownMaterialPointIntegrationTest {
    @Test fun aRenderClearedRetiredOverlayCannotReofferItsOriginalWarning() {
        val f = Fixture()
        f.offerThrough(0L, 0L)
        val flight = f.reserve(0L)
        val row = f.processOffered(0L, flight = flight)
        val feedback = CameraTestFeedbackCoordinator().apply { start(1) }
        // The UI callback entered while current, then GL loss retired its original owner.
        assertTrue(f.scope.accepts && flight.accepts)
        f.owner.invalidate()
        val originalOverlay = Any()
        var overlay: Any? = originalOverlay
        discardRetiredCameraUnknownOverlay(f.scope, { true }) {
            overlay = null
            feedback.clear(CameraTestFeedbackCoordinator.Source.UNKNOWN)
        }
        assertNull(overlay)
        var offers = 0
        val published = offerCameraUnknownFeedbackAfterRender(f.scope, flight,
            { overlay === originalOverlay }, { true }) {
            offers++
            assertTrue(feedback.offer(eligibleUnknownSample(f, row), nowMs = f.nowMs))
        }
        assertNull("The same UI runnable must not reinsert a warning after render cleared its owner", feedback.next(f.nowMs))
        assertEquals(0, offers)
        assertFalse(published)
        f.drainQueue()
    }

    @Test fun aNormalRenderedResultStillPublishesAfterItsGrayLeaseWasReleased() {
        val f = Fixture()
        f.offerThrough(0L, 0L)
        val flight = f.reserve(0L)
        val row = f.processOffered(0L, flight = flight)
        val feedback = CameraTestFeedbackCoordinator().apply { start(1) }
        assertTrue(flight.lease.isClosed)
        assertTrue(flight.accepts)
        assertTrue(offerCameraUnknownFeedbackAfterRender(f.scope, flight, { true }, { true }) {
            assertTrue(feedback.offer(eligibleUnknownSample(f, row), nowMs = f.nowMs))
        })
        val delivery = requireNotNull(feedback.next(f.nowMs))
        assertTrue(feedback.claim(delivery, f.nowMs))
        assertTrue(feedback.isDeliverable(delivery, f.nowMs))
        // The actual actuator's start callback clears this source before rechecking deliverability.
        f.owner.invalidate()
        discardRetiredCameraUnknownOverlay(f.scope, { true }) {
            feedback.clear(CameraTestFeedbackCoordinator.Source.UNKNOWN)
        }
        assertFalse(feedback.isDeliverable(delivery, f.nowMs))
        f.drainQueue()
    }

    @Test fun aClearedOrReplacedOverlayCannotOfferWhileItsScopeAndRequestRemainCurrent() {
        val f = Fixture()
        f.offerThrough(0L, 0L)
        val flight = f.reserve(0L)
        assertTrue(f.scope.accepts && flight.accepts)
        var offers = 0
        assertFalse(offerCameraUnknownFeedbackAfterRender(f.scope, flight, { false }, { true }) { offers++ })
        assertEquals(0, offers)
        assertFalse(flight.lease.isClosed) // This publication boundary never owns gray/Image/native release.
        f.owner.invalidate(); f.drainQueue()
    }

    @Test fun aCancelledRequestOrLostContextCannotOfferItsStillPresentOverlay() {
        val f = Fixture()
        f.offerThrough(0L, 0L)
        val cancelled = f.reserve(0L)
        f.owner.cancelFlight(f.scope, cancelled) { true }
        var offers = 0
        assertFalse(offerCameraUnknownFeedbackAfterRender(f.scope, cancelled, { true }, { true }) { offers++ })
        val current = f.reserve(0L)
        assertFalse(offerCameraUnknownFeedbackAfterRender(f.scope, current, { true }, { false }) { offers++ })
        assertEquals(0, offers)
        assertFalse(current.lease.isClosed)
        f.owner.invalidate(); f.drainQueue()
    }

    private fun eligibleUnknownSample(f: Fixture, row: CameraUnknownMaterialPointResult): CameraTestFeedbackCoordinator.Sample {
        val warning = row.depth.objects.single().copy(riskDistanceM = 1f, trackAgeFrames = 4, trackStableMs = 900L,
            walkingObstacleCandidate = true, confidence = DepthConfidenceBreakdown(0.95f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
            userFacing = UserFacingDepth(null, MessageLevel.STOP, "전방 물체. 멈추세요."))
        return CameraTestFeedbackCoordinator.Sample(CameraTestFeedbackCoordinator.Source.UNKNOWN,
            1, f.token(0L).frameId, 100_000L, f.nowMs, f.token(0L).geometryId, listOf(warning))
    }

    @Test fun theActualPreSubmissionReservationKeepsItsOriginalGrayThroughThirtySevenAcquiredFrames() {
        val f = Fixture()
        f.offerThrough(0L, 0L)
        val flight = f.reserve(0L)
        f.offerThrough(17L, 612L, 17L)
        val result = f.processOffered(0L, flight = flight, delayMs = 612L)
        assertNull(result.tracking.failure)
        assertEquals(32, result.tracking.retainedFrames)
        assertEquals(32 * 40 * 32, result.tracking.retainedImageBytes)
        assertTrue(result.tracking.materialPointsByMaskIndex.isEmpty())
        assertEquals(2f, result.depth.objects.single().riskDistanceM!!, .001f)
        assertTrue(flight.lease.isClosed)
        assertTrue(flight.accepts) // Normal gray release is not a request cancellation/publication fence.
        assertTrue(f.images.all { it.closeCalls == 1 })
        val unreserved = Fixture()
        unreserved.offerThrough(0L, 612L, 17L)
        assertNull(unreserved.owner.reserveCapture(unreserved.scope, unreserved.token(0L), 0, 100_612L) { true })
    }

    @Test fun rejectedSubmissionAndExactDiscardReleaseOnlyTheirOriginalRequestLeases() {
        val f = Fixture()
        f.offerThrough(0L, 17L, 17L)
        val rejected = f.reserve(0L)
        val remaining = f.reserve(17L)
        f.owner.cancelFlight(f.scope, rejected) { true }
        assertTrue(rejected.lease.isClosed)
        assertFalse(rejected.accepts)
        assertFalse(remaining.lease.isClosed)
        f.owner.cancelCapture(f.token(17L)) { true }
        assertTrue(remaining.lease.isClosed)
        assertFalse(remaining.accepts)
        f.drainQueue()
    }

    @Test fun aForeignAttachmentCannotReleaseTheOtherScopeLease() {
        val f = Fixture()
        val other = Fixture()
        f.offerThrough(0L, 0L); other.offerThrough(0L, 0L)
        val own = f.reserve(0L)
        val foreign = other.reserve(0L)
        assertNull(f.owner.process(f.scope, f.capture(0L), f.token(0L), 0, listOf(f.scene.mask()),
            100_100L, f.scene.nanoTime, foreign) { true })
        assertFalse(own.lease.isClosed)
        assertFalse(foreign.lease.isClosed)
        assertNotNull(f.processOffered(0L, flight = own))
        assertTrue(own.lease.isClosed)
        assertFalse(foreign.lease.isClosed)
        other.owner.invalidate(); other.drainQueue()
    }

    @Test fun aLateCancelledCompletedRequestCannotReleaseOrResetTheNewerBatch() {
        val f = Fixture()
        f.offerThrough(0L, 700L, 50L)
        f.processOffered(0L)
        val old = f.reserve(200L)
        val point = f.processOffered(200L, flight = old).tracking.materialPointsByMaskIndex.getValue(0).point.id
        assertTrue(old.lease.isClosed)
        assertTrue(old.accepts)
        assertSame(point, f.processOffered(400L).tracking.materialPointsByMaskIndex.getValue(0).point.id)
        f.owner.cancelFlight(f.scope, old) { true }
        assertFalse(old.accepts)
        f.drainQueue()
        assertSame(point, f.processOffered(600L).tracking.materialPointsByMaskIndex.getValue(0).point.id)
    }

    @Test fun anAlreadyPublishedOverlayCannotBeSelectedOrWarnAfterSameEpochScopeLoss() {
        val f = Fixture()
        val row = f.process(0L)
        val feedback = CameraTestFeedbackCoordinator().apply { start(1) }
        val warning = row.depth.objects.single().copy(riskDistanceM = 1f, trackAgeFrames = 4, trackStableMs = 900L,
            walkingObstacleCandidate = true, confidence = DepthConfidenceBreakdown(0.95f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
            userFacing = UserFacingDepth(null, MessageLevel.STOP, "전방 물체. 멈추세요."))
        assertTrue(feedback.offer(CameraTestFeedbackCoordinator.Sample(CameraTestFeedbackCoordinator.Source.UNKNOWN,
            1, f.token(0L).frameId, 100_000L, f.nowMs, f.token(0L).geometryId, listOf(warning)), nowMs = f.nowMs))
        assertNotNull(feedback.next(f.nowMs))
        var selectedOverlay: CameraUnknownMaterialPointScope? = f.scope
        var clearCalls = 0
        assertFalse(discardRetiredCameraUnknownOverlay(selectedOverlay, { true }) {
            error("A current original overlay must be kept")
        })
        // Same camera epoch, geometry ID and still-fresh capture: only its tracking owner retires.
        f.owner.invalidate()
        discardRetiredCameraUnknownOverlay(selectedOverlay, { true }) {
            selectedOverlay = null
            clearCalls++
            feedback.clear(CameraTestFeedbackCoordinator.Source.UNKNOWN)
        }
        assertNull("Retired original overlay must not be selected by render or feedback", selectedOverlay)
        assertEquals(1, clearCalls)
        assertNull(feedback.next(f.nowMs))
        f.drainQueue()
    }

    @Test fun actualCameraEmptyMapCounterexampleNowGetsStationaryMotionWithTheSameTwoMeterDepth() {
        val f = Fixture()
        val baseline = UnknownObjectDepthPipeline(elapsedRealtimeMs = { f.nowMs })
        val rows = (0L..1_800L step 200L).map { at ->
            val result = f.process(at)
            val capture = f.capture(at)
            val original = baseline.process(capture, capture.token, listOf(f.scene.mask()), f.nowMs)
            assertEquals(original.objects.single().riskDistanceM!!, result.depth.objects.single().riskDistanceM!!, .001f)
            result
        }
        assertTrue(rows.first().tracking.materialPointsByMaskIndex.isEmpty())
        assertEquals(ObjectMovementDirection.UNKNOWN, rows.first().depth.objects.single().motionEstimate.direction)
        val latest = rows.last().depth.objects.single()
        assertEquals(2f, latest.riskDistanceM!!, .001f)
        assertEquals(ObjectMovementDirection.STATIONARY, latest.motionEstimate.direction)
        assertEquals(0f, latest.motionEstimate.objectSpeedMps!!, .0001f)
        assertEquals(1, rows.mapNotNull { it.tracking.materialPointsByMaskIndex[0]?.point?.id }.distinct().size)
        assertTrue(f.images.all { it.closeCalls == 1 })
    }

    @Test fun allAcquiredImagesFeedTheRingBeforeTwoHundredMillisecondMaskPacing() {
        val f = Fixture()
        f.offerThrough(0L, 300L, 50L)
        assertTrue(f.processOffered(0L).tracking.materialPointsByMaskIndex.isEmpty())
        val tracked = f.processOffered(200L)
        assertNotNull(tracked.tracking.materialPointsByMaskIndex[0])
        assertEquals(7, f.images.size)
        assertEquals(4, f.scene.flow.targets.size)
        assertTrue(f.scene.flow.targets.all { it.frameId <= f.token(200L).frameId })
        val tooSparse = Fixture()
        tooSparse.offerThrough(0L, 0L)
        tooSparse.processOffered(0L)
        tooSparse.offerThrough(200L, 200L)
        assertTrue(tooSparse.processOffered(200L).tracking.materialPointsByMaskIndex.isEmpty())
        assertTrue(tooSparse.scene.flow.targets.isEmpty())
    }

    @Test fun actualPointLossKeepsScalarDepthAndRestartsMotionInsteadOfReturningOldPointId() {
        val f = Fixture()
        val warm = (0L..1_200L step 200L).map { f.process(it) }.last()
        assertEquals(ObjectMovementDirection.STATIONARY, warm.depth.objects.single().motionEstimate.direction)
        val old = warm.tracking.materialPointsByMaskIndex.getValue(0).point.id
        val lost = f.process(1_400L, f.scene.mask(hole = 18 to 15))
        assertTrue(lost.tracking.materialPointsByMaskIndex.isEmpty())
        assertEquals(2f, lost.depth.objects.single().riskDistanceM!!, .001f)
        assertEquals(ObjectMovementDirection.UNKNOWN, lost.depth.objects.single().motionEstimate.direction)
        assertTrue(f.process(1_600L).tracking.materialPointsByMaskIndex.isEmpty())
        val fresh = f.process(1_800L)
        assertNotSame(old, fresh.tracking.materialPointsByMaskIndex.getValue(0).point.id)
        assertEquals(ObjectMovementDirection.UNKNOWN, fresh.depth.objects.single().motionEstimate.direction)
    }

    @Test fun aZeroRemainingCallbackBudgetKeepsScalarDepthWithoutStartingFlow() {
        val f = Fixture()
        f.offerThrough(0L, 300L, 50L)
        f.processOffered(0L)
        val flight = f.reserve(200L)
        val started = f.scene.nanoTime
        f.scene.nanoTime += 5_000_000L
        val result = f.processOffered(200L, callbackStartedNs = started, flight = flight)
        assertEquals(0L, result.tracking.executionBudgetNs)
        assertTrue(result.tracking.materialPointsByMaskIndex.isEmpty())
        assertTrue(result.tracking.trackingMetrics.isEmpty())
        assertTrue(f.scene.flow.targets.isEmpty())
        assertEquals(2f, result.depth.objects.single().riskDistanceM!!, .001f)
        assertTrue(flight.lease.isClosed)
        assertTrue(flight.accepts)
    }

    @Test fun timeAlreadySpentByThisCallbackIsNotReplacedWithASecondDefaultAllowance() {
        val f = Fixture(VisualTrackingConfig(maxExecutionNs = 500L))
        f.offerThrough(0L, 300L, 50L)
        f.processOffered(0L)
        val started = f.scene.nanoTime
        f.scene.nanoTime += 300L
        f.scene.flow.advanceDurationNs = 100L
        val result = f.processOffered(200L, callbackStartedNs = started)
        assertEquals(200L, result.tracking.executionBudgetNs)
        assertTrue(result.tracking.trackingMetrics.all { it.executionBudgetNs <= 200L })
        assertTrue(result.tracking.materialPointsByMaskIndex.isEmpty())
        assertEquals(2f, result.depth.objects.single().riskDistanceM!!, .001f)
    }

    @Test fun lockContentionIsSubtractedFromTheOriginalCallbackDeadline() {
        val f = Fixture()
        f.offerThrough(0L, 300L, 50L)
        f.processOffered(0L)
        val flight = f.reserve(200L)
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val callbackThread = AtomicReference<Thread>()
        try {
            val future = synchronized(f.scope.tracker) {
                val started = f.scene.nanoTime
                val pending = executor.submit<CameraUnknownMaterialPointResult> {
                    callbackThread.set(Thread.currentThread())
                    entered.countDown()
                    f.processOffered(200L, callbackStartedNs = started, flight = flight)
                }
                assertTrue(entered.await(1L, TimeUnit.SECONDS))
                val waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L)
                while (callbackThread.get().state != Thread.State.BLOCKED && System.nanoTime() < waitDeadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, callbackThread.get().state)
                f.scene.nanoTime += 5_000_000L
                pending
            }
            val result = future.get(1L, TimeUnit.SECONDS)
            assertEquals(0L, result.tracking.executionBudgetNs)
            assertTrue(f.scene.flow.targets.isEmpty())
            assertEquals(2f, result.depth.objects.single().riskDistanceM!!, .001f)
        } finally { executor.shutdownNow() }
    }

    @Test fun pauseDeactivatesImmediatelyAndQueuesResetWithoutWaitingForNativeTrackerLock() {
        val f = Fixture()
        f.offerThrough(0L, 300L, 50L)
        f.processOffered(0L)
        val pendingFlight = f.reserve(200L)
        val executor = Executors.newSingleThreadExecutor()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val holder = executor.submit {
                synchronized(f.scope.tracker) { held.countDown(); check(release.await(1L, TimeUnit.SECONDS)) }
            }
            assertTrue(held.await(1L, TimeUnit.SECONDS))
            f.owner.invalidate()
            assertFalse(f.scope.accepts)
            assertTrue(pendingFlight.lease.isClosed)
            assertFalse(pendingFlight.accepts)
            assertEquals(1, f.queued.size)
            val image = TestImage(f.token(350L)).apply { failTimestamp = true }
            assertFalse(f.owner.offerImage(f.scope, f.token(350L), 0, image, 100_350L) { true })
            assertNull(f.owner.process(f.scope, f.capture(200L), f.token(200L), 0,
                listOf(f.scene.mask()), 100_300L, f.scene.nanoTime) { true })
            assertEquals(0, image.closeCalls)
            image.close()
            release.countDown(); holder.get(1L, TimeUnit.SECONDS)
            f.drainQueue()
            assertEquals(1, image.closeCalls)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun unqualifiedRgbFrameRetiresPointsWithoutReadingOrClosingItsImage() {
        val f = Fixture()
        f.offerThrough(0L, 100L)
        f.processOffered(0L)
        val image = TestImage(f.token(200L)).apply { failTimestamp = true }
        assertFalse(f.owner.qualifyFrame(null) { true })
        assertFalse(f.scope.accepts)
        assertEquals(1, f.queued.size)
        assertEquals(0, image.timestampReads)
        assertEquals(0, image.closeCalls)
        assertNotNull(image.planes[0].buffer)
        image.close(); f.drainQueue()
        assertEquals(1, image.closeCalls)
    }

    @Test fun aLateOldDiscardCannotCancelTheNewerConsumedBatch() {
        val f = Fixture()
        f.offerThrough(0L, 700L, 50L)
        f.processOffered(0L)
        val point = f.processOffered(200L).tracking.materialPointsByMaskIndex.getValue(0).point.id
        f.owner.cancelCapture(f.token(0L)) { true }
        assertEquals(1, f.queued.size)
        assertSame(point, f.processOffered(400L).tracking.materialPointsByMaskIndex.getValue(0).point.id)
        f.drainQueue()
        assertSame(point, f.processOffered(600L).tracking.materialPointsByMaskIndex.getValue(0).point.id)
    }

    @Test fun oldQueuedDiscardAndResetCannotTouchTheReplacementServiceScope() {
        val f = Fixture()
        f.offerThrough(0L, 100L)
        f.processOffered(0L)
        f.owner.cancelCapture(f.token(0L)) { true }
        f.owner.invalidate()
        f.replace(epoch = 10L)
        f.offerThrough(200L, 500L, 50L)
        assertTrue(f.processOffered(200L).tracking.materialPointsByMaskIndex.isEmpty())
        f.drainQueue()
        assertNotNull(f.processOffered(400L).tracking.materialPointsByMaskIndex[0])
        assertFalse(f.owner.qualifyFrame(null) { false })
        assertTrue(f.scope.accepts)
    }

    @Test fun geometryReplacementRejectsOldAttachmentAndStartsAFirstEmptyMap() {
        val f = Fixture()
        f.offerThrough(0L, 300L, 50L)
        f.processOffered(0L)
        f.processOffered(200L)
        val old = f.scope
        val oldCapture = f.capture(200L)
        f.replace(geometry = "rotated-geometry", turns = 1, samePipeline = true)
        assertFalse(old.accepts)
        assertNull(f.owner.process(old, oldCapture, oldCapture.token, 0, listOf(f.scene.mask()),
            100_300L, f.scene.nanoTime) { true })
        f.offerThrough(350L, 500L, 50L)
        assertTrue(f.processOffered(350L).tracking.materialPointsByMaskIndex.isEmpty())
        assertEquals(1, f.processOffered(450L).tracking.materialPointsByMaskIndex.getValue(0).imageQuarterTurns)
    }

    private class Fixture(private val config: VisualTrackingConfig = VisualTrackingConfig()) {
        val scene = UnknownMaterialPointTestScene(config)
        val queued = ArrayDeque<() -> Unit>()
        val images = mutableListOf<TestImage>()
        var nowMs = 100_100L
        private var offeredUntil = -25L
        private var epoch = 9L
        private var geometry = "unknown-image-geometry"
        private var turns = 0
        val owner = CameraUnknownMaterialPointOwner({ queued.addLast(it) }, config, { scene.nanoTime }) {
            UnknownMaterialPointTracker(config, { scene.nanoTime }, scene.flow)
        }
        var scope = requireNotNull(owner.acquireScope(1, epoch, 0L, UnknownObjectDepthPipeline(elapsedRealtimeMs = { nowMs }), geometry) { true })
        fun token(at: Long) = scene.token(at, epoch, geometry)
        fun capture(at: Long): FrozenUnknownDepthCapture = requireNotNull(FrozenUnknownDepthCapture.freeze(
            token(at), scene.capture(at).snapshot,
            doubleArrayOf(1.0 / scene.width, 0.0, 0.0, 0.0, 1.0 / scene.height, 0.0, 0.0, 0.0, 1.0),
            token(at).frameId, imageQuarterTurns = turns))
        fun offerThrough(first: Long, last: Long, step: Long = 25L) {
            for (at in first..last step step) {
                val image = TestImage(token(at)).also { images += it }
                image.use { assertTrue(owner.offerImage(scope, token(at), turns, image, 100_000L + at) { true }) }
            }
        }
        fun process(at: Long, mask: InstanceMask = scene.mask()): CameraUnknownMaterialPointResult {
            offerThrough(offeredUntil + 25L, at + 100L)
            offeredUntil = at + 100L
            return processOffered(at, mask)
        }
        fun reserve(at: Long) = requireNotNull(owner.reserveCapture(scope, token(at), turns, 100_000L + at) { true })
        fun processOffered(at: Long, mask: InstanceMask = scene.mask(), callbackStartedNs: Long = scene.nanoTime,
                           flight: CameraUnknownMaterialPointFlight = reserve(at), delayMs: Long = 100L): CameraUnknownMaterialPointResult {
            nowMs = 100_000L + at + delayMs
            return requireNotNull(owner.process(scope, capture(at), token(at), turns, listOf(mask), nowMs, callbackStartedNs, flight) { true })
        }
        fun replace(epoch: Long = this.epoch, geometryGeneration: Long = 0L,
                    geometry: String = this.geometry, turns: Int = this.turns, samePipeline: Boolean = false) {
            this.epoch = epoch; this.geometry = geometry; this.turns = turns
            val pipeline = if (samePipeline) scope.pipeline else UnknownObjectDepthPipeline(elapsedRealtimeMs = { nowMs })
            scope = requireNotNull(owner.acquireScope(1, epoch, geometryGeneration, pipeline, geometry) { true })
        }
        fun drainQueue() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
    }

    private class TestImage(private val token: FastSamFrameToken) : Image() {
        var closeCalls = 0
        var timestampReads = 0
        var failTimestamp = false
        private val pixels = ByteBuffer.wrap(ByteArray(token.width * token.height) { 77 })
        override fun getFormat() = 35
        override fun getWidth() = token.width
        override fun getHeight() = token.height
        override fun getTimestamp(): Long {
            timestampReads++
            check(!failTimestamp) { "unqualified clock must not be read" }
            return token.cpuImageTimestampNs
        }
        override fun close() { closeCalls++ }
        override fun getPlanes(): Array<Plane> = arrayOf(object : Plane() {
            override fun getPixelStride() = 1
            override fun getRowStride() = token.width
            override fun getBuffer(): ByteBuffer = pixels.duplicate()
        })
    }
}
