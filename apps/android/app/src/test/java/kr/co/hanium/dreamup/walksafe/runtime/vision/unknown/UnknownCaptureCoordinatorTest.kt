package kr.co.hanium.dreamup.walksafe.runtime.vision.unknown

import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedDeque
import java.nio.ByteBuffer
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownObjectDepthPipeline
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointTestScene
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointLumaCapture
import kr.co.hanium.dreamup.walksafe.inference.unknown.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.*
import kr.co.hanium.dreamup.walksafe.navigation.TactileMaskCaptureBundle
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Real lease/tracker/depth consumer. Controlled measured flow does not claim Android/native LK. */
class UnknownCaptureCoordinatorTest {
    @Test fun actualServiceResultCompletesItsOriginalCaptureOnceWithoutDuplicateLoadObservation() {
        val f = Fixture(nativeEnabled = true, allowSubmission = true)
        val service = f.startService()
        try {
            f.queueCapture(service, 0L)
            val result = f.awaitResult()
            f.nowMs += 100L
            f.coordinator.consumeUnknownObjectResult(result)
            assertEquals(1, f.loadCompletions.size)
            assertTrue(f.leaseOf(result).isClosed)
            val status = f.coordinator.status
            f.coordinator.consumeUnknownObjectResult(result)
            f.coordinator.handleUnknownFrameDiscard(result.token, "stale_duplicate")
            assertEquals(1, f.loadCompletions.size)
            assertEquals(status, f.coordinator.status)
            assertTrue(f.coordinator.unknownFrameCaptureIsDue(f.nowMs + 1_000L))
        } finally { f.services.closeActiveForDetector(); service.releasedFuture().get(3, TimeUnit.SECONDS) }
    }

    @Test fun invalidatedActualResultCannotRetireANewerContextsCapture() {
        val f = Fixture(nativeEnabled = true, allowSubmission = true)
        val service = f.startService()
        try {
            f.queueCapture(service, 0L)
            val old = f.awaitResult()
            f.coordinator.invalidateUnknownObjectObservations()
            assertTrue(f.leaseOf(old).isClosed)
            f.queueCapture(service, 25L)
            val newer = f.awaitResult()
            assertFalse(f.leaseOf(newer).isClosed)
            f.coordinator.consumeUnknownObjectResult(old)
            assertFalse(f.leaseOf(newer).isClosed)
            assertFalse(f.coordinator.unknownFrameCaptureIsDue(f.nowMs))
            assertTrue(f.loadCompletions.isEmpty())
            f.coordinator.consumeUnknownObjectResult(newer)
            assertTrue(f.leaseOf(newer).isClosed)
            assertEquals(1, f.loadCompletions.size)
        } finally { f.services.closeActiveForDetector(); service.releasedFuture().get(3, TimeUnit.SECONDS) }
    }

    @Test fun constructionDoesNotReadSdkOrRuntimeAuthority() {
        val f = Fixture()
        assertEquals(0, f.authorityReads)
        assertEquals(0, f.factoryReads)
        assertFalse(f.coordinator.frameCopyInFlight)
        assertTrue(f.coordinator.feedbackTrackIsCurrent("named:1"))
        assertFalse(f.coordinator.feedbackTrackIsCurrent("unknown:1:1"))
    }

    @Test fun actualConsumerRetainsMeasuredPointsOriginalCaptureDistanceAndStationaryMotion() {
        val f = Fixture()
        val scene = UnknownMaterialPointTestScene()
        var offered = -25L
        var lastOriginalToken: FastSamFrameToken? = null
        val pipeline = UnknownObjectDepthPipeline(elapsedRealtimeMs = { f.nowMs })
        val results = (0L..1_800L step 200L).map { at ->
            scene.offerThrough(offered + 25L, at + 100L, 25L)
            offered = at + 100L
            val capture = scene.capture(at)
            lastOriginalToken = capture.token
            f.nowMs = capture.token.capturedElapsedNs / 1_000_000L + 100L
            f.coordinator.processUnknownMaterialPointCapture(pipeline, scene.producer, capture, capture.token,
                listOf(scene.mask()), f.nowMs, scene.nanoTime, 5_000_000L, { scene.nanoTime })
        }
        val latest = results.last()
        assertTrue(scene.flow.seeds > 0)
        assertEquals(ObjectMovementDirection.STATIONARY, latest.objects.single().motionEstimate.direction)
        assertEquals(0f, latest.objects.single().motionEstimate.objectSpeedMps!!, .0001f)
        assertSame(lastOriginalToken, latest.token)
        assertEquals(scene.token(1_800L).frameId, latest.token.frameId)
        assertEquals(scene.token(1_800L).cameraTimestampNs / 1_000_000L,
            latest.objects.single().motionEstimate.observedAtMs)
        assertEquals(2f, latest.objects.single().riskDistanceM!!, .001f)
        assertTrue(results.all { it.objects.single().depthAvailability == DepthAvailability.MEASURED })
    }

    @Test fun spentCallbackBudgetCannotGrantAnotherAllowance() {
        val f = Fixture()
        val scene = UnknownMaterialPointTestScene()
        scene.offerThrough(0L, 100L, 25L)
        scene.nanoTime = 5_000_000L
        val capture = scene.capture(0L)
        f.nowMs = capture.token.capturedElapsedNs / 1_000_000L + 100L
        val result = f.coordinator.processUnknownMaterialPointCapture(
            UnknownObjectDepthPipeline(elapsedRealtimeMs = { f.nowMs }), scene.producer, capture,
            capture.token, listOf(scene.mask()), f.nowMs, 0L, 5_000_000L, { scene.nanoTime })
        assertEquals(0, scene.flow.seeds)
        assertTrue(scene.flow.targets.isEmpty())
        assertEquals(2f, result.objects.single().riskDistanceM!!, .001f)
        assertEquals(ObjectMovementDirection.UNKNOWN, result.objects.single().motionEstimate.direction)
    }

    @Test fun canceledConsumerClosesTheOriginalReservedLeaseBeforeProducerDispatch() {
        val f = Fixture()
        val scene = UnknownMaterialPointTestScene()
        scene.offerThrough(0L, 100L, 25L)
        val capture = scene.capture(0L)
        f.nowMs = capture.token.capturedElapsedNs / 1_000_000L + 100L
        val lease = requireNotNull(scene.producer.reserveCapture(capture.token, 0, f.nowMs).lease)
        val result = f.coordinator.processUnknownMaterialPointCapture(
            UnknownObjectDepthPipeline(elapsedRealtimeMs = { f.nowMs }), scene.producer, capture,
            capture.token, listOf(scene.mask()), f.nowMs, 0L, 5_000_000L, { scene.nanoTime },
            lease, contextIsCurrent = { false })
        assertEquals("capture_context_invalidated", result.rejectionReason)
        assertTrue(lease.isClosed)
        assertEquals(0, scene.flow.seeds)
    }

    @Test fun rejectedOrThrowingSubmissionClosesItsExactLeaseWhileAcceptanceTransfersOwnership() {
        val f = Fixture()
        val scene = UnknownMaterialPointTestScene()
        scene.offerThrough(0L, 100L, 25L)
        val token = scene.token(0L)
        fun reserve() = requireNotNull(scene.producer.reserveCapture(token, 0, 100_100L).lease)
        val rejected = reserve()
        assertEquals(FastSamRuntimeService.Admission.NOT_READY,
            f.coordinator.submitReservedUnknownFrame(rejected) { FastSamRuntimeService.Admission.NOT_READY })
        assertTrue(rejected.isClosed)
        val failed = reserve()
        val marker = IllegalStateException("submission")
        try { f.coordinator.submitReservedUnknownFrame(failed) { throw marker }; fail() }
        catch (error: IllegalStateException) { assertSame(marker, error) }
        assertTrue(failed.isClosed)
        val accepted = reserve()
        assertEquals(FastSamRuntimeService.Admission.ACCEPTED,
            f.coordinator.submitReservedUnknownFrame(accepted) { FastSamRuntimeService.Admission.ACCEPTED })
        assertFalse(accepted.isClosed)
        accepted.close()
    }

    @Test fun ageAndGapAreCheckedInsideTheOriginalLockBeforeLazySourceAdmission() {
        val f = Fixture()
        val originalLease = Any()
        val capture = UnknownCaptureProcessingLease(UnknownCameraSourceLease(originalLease, 7L), 10L, 100L, 700L)
        f.nowMs = 700L
        assertTrue(f.coordinator.currentUnknownCaptureProcessingAllowsWork(capture))
        assertSame(originalLease, f.lastLease?.arLease?.originalLease)
        assertEquals(listOf("lock", "clock", "source", "unlock"), f.depthOrder)
        f.depthOrder.clear(); f.nowMs = 701L
        assertFalse(f.coordinator.currentUnknownCaptureProcessingAllowsWork(capture))
        assertEquals(listOf("lock", "clock", "unlock"), f.depthOrder)
        f.depthOrder.clear(); f.nowMs = 901L
        assertFalse(f.coordinator.currentUnknownCaptureProcessingAllowsWork(capture.copy(gapDeadlineElapsedRealtimeMs = null)))
        assertEquals(listOf("lock", "clock", "unlock"), f.depthOrder)
    }

    @Test fun missingTrackingOrRetiredSourceCannotAdmitEvenFreshOriginalCapture() {
        val f = Fixture()
        val capture = UnknownCaptureProcessingLease(UnknownCameraSourceLease(Any(), 7L), 10L, 100L, null)
        f.nowMs = 900L
        f.tracking = false
        assertFalse(f.coordinator.currentUnknownCaptureProcessingAllowsWork(capture))
        assertFalse(f.depthOrder.contains("source"))
        f.tracking = true; f.sourceCurrent = false; f.depthOrder.clear()
        assertFalse(f.coordinator.currentUnknownCaptureProcessingAllowsWork(capture))
        assertEquals(listOf("lock", "clock", "source", "unlock"), f.depthOrder)
    }

    @Test fun immediateInvalidationReleasesActualQueuedCopyBeforeOldDrainRuns() {
        val f = Fixture(nativeEnabled = true, queueFrames = true)
        val service = f.startService()
        try {
            f.queueCapture(service, 0L)
            assertTrue(f.coordinator.frameCopyInFlight)
            assertEquals(1, f.tasks.size)
            assertEquals(1, f.evidenceCopies)
            f.coordinator.invalidateUnknownObjectObservations()
            assertFalse(f.coordinator.frameCopyInFlight)
            while (f.tasks.isNotEmpty()) f.tasks.removeAt(0).run()
            assertEquals(0, f.cameraObservations)
            assertTrue(f.coordinator.freshUnknownFeedbackBatches(f.nowMs).isEmpty())
        } finally { f.services.closeActiveForDetector(); service.releasedFuture().get(3, TimeUnit.SECONDS) }
    }

    @Test fun lateOldDrainAndTrackerResetCannotReleaseTheNewContextsQueuedCopy() {
        val f = Fixture(nativeEnabled = true, queueFrames = true)
        val service = f.startService()
        try {
            f.queueCapture(service, 0L)
            f.coordinator.invalidateUnknownObjectObservations()
            f.queueCapture(service, 25L)
            assertTrue(f.coordinator.frameCopyInFlight)
            assertEquals(2, f.evidenceCopies)
            f.tasks.removeAt(0).run() // retired queue drain
            f.tasks.removeAt(0).run() // exact old tracker reset
            assertTrue("Old callbacks must not clear the fresh copy reservation", f.coordinator.frameCopyInFlight)
            f.tasks.removeAt(0).run()
            assertFalse(f.coordinator.frameCopyInFlight)
            assertEquals(1, f.cameraObservations)
        } finally { f.services.closeActiveForDetector(); service.releasedFuture().get(3, TimeUnit.SECONDS) }
    }

    private class Fixture(private val nativeEnabled: Boolean = false, queueFrames: Boolean = false,
        private val allowSubmission: Boolean = false,
    ) : UnknownCaptureClock {
        var nowMs = 100_100L
        var authorityReads = 0
        var factoryReads = 0
        var tracking = true
        var sourceCurrent = true
        var underDepthLock = false
        var lastLease: UnknownCaptureProcessingLease? = null
        var latestFrameId = 10L
        var evidenceCopies = 0
        var cameraObservations = 0
        val tasks = mutableListOf<Runnable>()
        private val results = ConcurrentLinkedDeque<FastSamResult<UnknownRuntimeAttachment>>()
        val loadCompletions = mutableListOf<RuntimeLoadCompletion>()
        private val frameExecutor = Executor { if (queueFrames) tasks += it else it.run() }
        private val native = UnknownRuntimeServiceTestFactory()
        private val serviceCallbacks = ConcurrentLinkedDeque<() -> Unit>()
        val depthOrder = mutableListOf<String>()
        override fun elapsedRealtimeMs(): Long {
            if (underDepthLock) depthOrder += "clock"
            return nowMs
        }
        override fun elapsedRealtimeNanos() = nowMs * 1_000_000L
        override fun cpuNanos() = 0L
        val services: UnknownRuntimeServiceOwner = UnknownRuntimeServiceOwner(object : UnknownRuntimeProfileRepository {
            override fun createBinding(config: TwoModelRuntimeConfig, primary: RuntimeCandidate, scope: RuntimeFeatureScope): UnknownRuntimeBinding? = null
            override fun load(binding: UnknownRuntimeBinding): UnknownRuntimeProfile? = null
            override fun needsRecheck(binding: UnknownRuntimeBinding, manual: Boolean) = false
            override fun markPending(binding: UnknownRuntimeBinding, reason: DeviceTuningRecheckReason) = false
        }, object : UnknownRuntimeFactory {
            override fun createService(epoch: Long, options: FastSamRuntimeOptions,
                listener: FastSamRuntimeService.Listener<UnknownRuntimeAttachment>): FastSamRuntimeService<UnknownRuntimeAttachment> {
                factoryReads++
                if (!nativeEnabled) error("No native factory call is expected")
                return native.create(epoch, options, listener, this@Fixture::elapsedRealtimeNanos)
            }
            override fun createCalibration(request: UnknownCalibrationRequest): UnknownCalibrationSession {
                factoryReads++; error("No native factory call is expected")
            }
        }, object : UnknownRuntimeServiceAuthority {
            override fun destroyedOrDetectorClosing(): Boolean { authorityReads++; return false }
            override fun mayStartWalkingService(): Boolean { authorityReads++; return true }
            override fun calibrationCameraAvailable(): Boolean { authorityReads++; return true }
            override fun speechActive(): Boolean { authorityReads++; return false }
        }, this, Executor { it.run() }, { serviceCallbacks.addLast(it) },
            { invalidateCoordinator() }, { results.addLast(it) }, { token, reason -> discardCoordinator(token, reason) }, {})
        val coordinator: UnknownCaptureCoordinator = UnknownCaptureCoordinator(Any(), frameExecutor, { it() },
            object : UnknownCaptureScopeReader {
                override fun runtime(): UnknownCaptureRuntimeScope {
                    authorityReads++
                    return UnknownCaptureRuntimeScope(WalkRuntimeEpoch("walk", 1L), true, false, 4, 7L, 2L, 9L, 3, .65f, null)
                }
                override fun withDepthAuthority(lease: UnknownCaptureProcessingLease,
                    check: (UnknownCaptureDepthAuthority) -> Boolean): Boolean {
                    lastLease = lease
                    kotlin.check(!underDepthLock); underDepthLock = true; depthOrder += "lock"
                    try { return check(UnknownCaptureDepthAuthority(latestFrameId, tracking) {
                        kotlin.check(underDepthLock); depthOrder += "source"; sourceCurrent
                    }) } finally { underDepthLock = false; depthOrder += "unlock" }
                }
                override fun ownsFrame(generation: Int, epoch: WalkRuntimeEpoch) = true
                override fun execution() = UnknownCaptureExecutionObservation(false)
                override fun metricOutputAllowsWork(expectedArGeneration: Long) = true
                override fun cameraProfile(): ApprovedCameraFrameQualityProfile? = if (allowSubmission)
                    ApprovedCameraFrameQualityProfile("test-only", .1, .2, 180.0, -90.0, 90.0, 800L) else null
                override fun powerSaveMode() = false
            }, this, object : UnknownCaptureObserver {
                override fun observeCamera(observation: CameraFrameQualityObservation) { cameraObservations++ }
                override fun projectTactile(bundle: TactileMaskCaptureBundle, atElapsedRealtimeNs: Long): List<TactileMaskSurfaceResult> = emptyList()
                override fun publishTactile(bundle: TactileMaskCaptureBundle, surfaces: List<TactileMaskSurfaceResult>,
                    publication: UnknownTactilePublication, decisionNs: Long,
                    admissionIsCurrent: () -> Boolean, outputIsCurrent: () -> Boolean) = false
                override fun cancelFeedbackForTrackPrefix(prefix: String) {}
                override fun retireTerminalFeedbackForTrackPrefix(prefix: String) {}
                override fun observeLoad(nowMs: Long, normal: Boolean, completion: RuntimeLoadCompletion) { loadCompletions += completion }
            }, services) { false }
        fun startService(): FastSamRuntimeService<UnknownRuntimeAttachment> {
            services.startForWalk(UnknownWalkingSource(Any(), Any(), "runtime", 1L, WalkRuntimeEpoch("walk", 1L)) { true }, { TwoModelRuntimeConfig("test", null, TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,
                null, null, null, null) }, { RuntimeCandidate(RuntimeBackend.CPU, 1) })
            val service = requireNotNull(services.loadingService)
            service.start().get(3, TimeUnit.SECONDS)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (serviceCallbacks.isEmpty() && System.nanoTime() < deadline) Thread.yield()
            assertFalse("Expected the actual service readiness callback", serviceCallbacks.isEmpty())
            requireNotNull(serviceCallbacks.pollFirst())()
            assertSame(service, services.currentService)
            return service
        }
        private fun invalidateCoordinator() { coordinator.invalidateUnknownObjectObservations() }
        private fun discardCoordinator(token: FastSamFrameToken, reason: String) { coordinator.handleUnknownFrameDiscard(token, reason) }
        fun awaitResult(): FastSamResult<UnknownRuntimeAttachment> {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while ((results.isEmpty() || services.currentService?.stats()?.inflight == true) && System.nanoTime() < deadline) Thread.yield()
            assertFalse("Expected a result from the actual submitted service frame", results.isEmpty())
            return requireNotNull(results.pollFirst())
        }
        fun leaseOf(result: FastSamResult<UnknownRuntimeAttachment>) = result.attachment.javaClass
            .getDeclaredField("captureLease").apply { isAccessible = true }.get(result.attachment) as
            kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointCaptureLease
        fun queueCapture(service: FastSamRuntimeService<UnknownRuntimeAttachment>, at: Long) {
            val scene = UnknownMaterialPointTestScene()
            val token = scene.token(at, service.sessionEpoch, "7:2:40x32:q0")
            nowMs = token.capturedElapsedNs / 1_000_000L
            latestFrameId = token.frameId
            val y = ByteBuffer.wrap(ByteArray(40 * 32) { 77 })
            val chroma = ByteBuffer.wrap(ByteArray(20 * 16) { 128.toByte() })
            val owned = OwnedYuvFrame.copyOf(40, 32, y, chroma, chroma, 40, 1, 20, 1)
            val luma = requireNotNull(UnknownMaterialPointLumaCapture.copyFromLuma(token, 0,
                token.cpuImageTimestampNs, 40, 32, y, 40, 1))
            val epoch = WalkRuntimeEpoch("walk", 1L)
            val quality = CameraFrameQualityObservation(epoch, nowMs, true, .5, 0.0, null, 0.0)
            val input = UnknownCaptureFrameInput(owned, token.cpuImageTimestampNs, quality, 0, 2L,
                UnknownCaptureLumaInput(service, luma), null)
            val snapshot = DepthFrameSnapshot(token.frameId, null, null, null,
                cameraImageTimestampNs = token.cpuImageTimestampNs)
            val capture = UnknownCaptureProcessingLease(UnknownCameraSourceLease(Any(), 7L), token.frameId, nowMs, null)
            coordinator.scheduleUnknownDetectionIfDue(input, snapshot, token.frameId, nowMs, 4, epoch, capture) {
                evidenceCopies++
                UnknownCaptureEvidence(token.frameId, snapshot, null, MotionContext(), 0, capture, 9L)
            }
        }
    }
}
