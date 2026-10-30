package kr.co.hanium.dreamup.walksafe.device.postlogin

import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.voice.VoskWakePhraseProbeResult
import org.junit.Assert.*
import org.junit.Test

class AndroidPostLoginDeviceProbesTest {
    @Test fun constructionDoesNotReadOwnersOrStartNativeSources() {
        val f = Fixture()
        assertEquals(0, f.ownerReads)
        assertEquals(0, f.support.calls)
        assertTrue(f.location.requests.isEmpty())
        assertTrue(f.camera.requests.isEmpty())
        assertTrue(f.wakes.isEmpty())
        assertTrue(f.haptics.isEmpty())
    }

    @Test fun automaticCapabilityStartDoesNotIntroduceGpsOrMicrophoneSubscriptions() {
        val f = Fixture()
        f.probes.startAutomaticProbes(f.binding)
        assertEquals(1, f.support.calls)
        assertTrue(f.location.requests.isEmpty())
        assertTrue(f.camera.requests.isEmpty())
        assertTrue(f.wakes.isEmpty())
        assertTrue(f.haptics.isEmpty())
    }

    @Test fun freshBatchedGpsUsesOriginalElapsedTimestampAndCancelsRegistration() {
        val f = Fixture()
        f.probes.startLocation(f.binding)
        val run = f.location.requests.single()
        run.locations(listOf(f.sample(observedNs = 95_000_000_123L)))
        assertEquals(PostLoginDeviceCheckSignal.READY, f.probes.signals.locationFix)
        assertEquals(5_000L, f.probes.locationDecision?.ageMs)
        assertEquals(1, run.cancelled)
        assertEquals(listOf("pass:PASS:0_15m:fresh"), f.terminal)
        assertEquals(listOf(f.binding), f.continued)
    }

    @Test fun oneMockInBatchRejectsAnOtherwisePassingFix() {
        val f = Fixture()
        f.probes.startLocation(f.binding)
        f.location.requests.single().locations(listOf(f.sample(), f.sample(mock = true)))
        assertEquals(PostLoginDeviceCheckSignal.UNAVAILABLE, f.probes.signals.locationFix)
        assertEquals(DeviceCheckLocationFixReason.MOCK_LOCATION, f.probes.locationDecision?.reason)
    }

    @Test fun rejectedFixReasonSurvivesTimeoutRatherThanBecomingNoFix() {
        val f = Fixture()
        f.probes.startLocation(f.binding)
        f.location.requests.single().locations(listOf(f.sample(observedNs = 1_000_000_000L)))
        assertEquals(PostLoginDeviceCheckSignal.PENDING, f.probes.signals.locationFix)
        f.ui.fire(32_000L)
        assertEquals(PostLoginLocationFixTerminalFailure.TIMED_OUT, f.probes.locationTerminalFailure)
        assertEquals(DeviceCheckLocationFixReason.STALE, f.probes.locationDecision?.reason)
    }

    @Test fun noFixTimeoutKeepsProviderAvailabilityCause() {
        val f = Fixture()
        f.location.noFix = DeviceCheckLocationFixUnavailability.PROVIDER_DISABLED
        f.probes.startLocation(f.binding)
        f.ui.fire(32_000L)
        assertEquals(DeviceCheckLocationFixReason.PROVIDER_DISABLED, f.probes.locationDecision?.reason)
        assertEquals(PostLoginDeviceCheckSignal.UNAVAILABLE, f.probes.signals.locationFix)
    }

    @Test fun retiredGpsRegistrationAndResultCannotReplaceANewAttempt() {
        val f = Fixture()
        f.probes.startLocation(f.binding)
        val retired = f.location.requests.single()
        f.probes.cancelFunctionalProbes()
        f.binding = f.binding.copy(attemptGeneration = 2)
        f.probes.resetForAttempt(f.binding)
        f.probes.startLocation(f.binding)
        retired.locations(listOf(f.sample(mock = true)))
        retired.registered()
        retired.failed()
        assertEquals(PostLoginDeviceCheckSignal.PENDING, f.probes.signals.locationFix)
        assertNull(f.probes.locationDecision)
        assertTrue(f.continued.isEmpty())
        f.location.requests.last().locations(listOf(f.sample()))
        assertEquals(PostLoginDeviceCheckSignal.READY, f.probes.signals.locationFix)
    }

    @Test fun compositeCameraKeepsPendingUntilAppliedAndReleasedWithoutStartingLegacyCameraOrTimeout() {
        val f = Fixture()
        f.support.compositeHandled = true
        f.probes.startCamera(f.binding)
        assertTrue(f.camera.requests.isEmpty())
        assertTrue(f.probes.cameraActive)
        assertTrue(f.probes.hasCamera)
        assertEquals(PostLoginDeviceCheckSignal.PENDING, f.probes.signals.cameraPipeline)
        f.ui.fire(15_000L)
        f.ui.drain()
        assertEquals(PostLoginDeviceCheckSignal.PENDING, f.probes.signals.cameraPipeline)
        assertTrue(f.continued.isEmpty())

        f.support.compositeRequests.single().finished(PostLoginCompositeCameraCheckResult(
            true, PostLoginMetricDepthState.SUPPORTED))
        assertEquals(PostLoginDeviceCheckSignal.PENDING, f.probes.signals.cameraPipeline)
        f.ui.drain()
        assertEquals(PostLoginDeviceCheckSignal.READY, f.probes.signals.cameraPipeline)
        assertEquals(PostLoginMetricDepthState.SUPPORTED, f.probes.signals.metricDepth)
        assertFalse(f.probes.cameraActive)
        assertFalse(f.probes.cameraCleanupPending)
        assertFalse(f.probes.hasCamera)
        assertEquals(listOf(f.binding), f.continued)
    }

    @Test fun cancelledCompositeRunWaitsForReleaseAndCannotPublishOldEvidenceOrReleaseANewerRun() {
        val f = Fixture()
        f.support.compositeHandled = true
        f.probes.startCamera(f.binding)
        val old = f.support.compositeRequests.single()
        f.probes.cancelCalibration("new_attempt")
        f.probes.stopCamera()
        f.binding = f.binding.copy(attemptGeneration = 2)
        f.probes.resetForAttempt(f.binding)
        f.probes.startCamera(f.binding)
        assertEquals(listOf("new_attempt"), f.support.compositeCancellations)
        assertTrue(f.probes.cameraCleanupPending)
        assertTrue(f.probes.hasCamera)
        assertEquals(1, f.support.compositeRequests.size)

        old.finished(PostLoginCompositeCameraCheckResult(true, PostLoginMetricDepthState.SUPPORTED))
        f.ui.drain()
        assertEquals(PostLoginDeviceCheckSignal.PENDING, f.probes.signals.cameraPipeline)
        assertEquals(PostLoginMetricDepthState.PENDING, f.probes.signals.metricDepth)
        assertFalse(f.probes.cameraCleanupPending)
        assertFalse(f.probes.hasCamera)
        assertEquals(listOf(f.binding), f.continued)

        f.probes.startCamera(f.binding)
        assertEquals(2, f.support.compositeRequests.size)
        old.finished(PostLoginCompositeCameraCheckResult(false))
        f.ui.drain()
        assertTrue(f.probes.cameraActive)
        assertTrue(f.probes.hasCamera)
        assertEquals(PostLoginDeviceCheckSignal.PENDING, f.probes.signals.cameraPipeline)
        assertEquals(listOf(f.binding), f.continued)
    }

    @Test fun aQueuedCompositeCompletionIsRetiredBeforeItCanPublishReady() {
        val f = Fixture()
        f.support.compositeHandled = true
        f.probes.startCamera(f.binding)
        f.support.compositeRequests.single().finished(PostLoginCompositeCameraCheckResult(
            true, PostLoginMetricDepthState.SUPPORTED))
        f.probes.stopCamera()
        assertTrue(f.probes.cameraCleanupPending)
        f.ui.drain()
        assertEquals(PostLoginDeviceCheckSignal.PENDING, f.probes.signals.cameraPipeline)
        assertEquals(PostLoginMetricDepthState.PENDING, f.probes.signals.metricDepth)
        assertFalse(f.probes.cameraCleanupPending)
        assertFalse(f.probes.hasCamera)
    }

    @Test fun compositeFailurePublishesOnceAndDoesNotForgeMetricSupport() {
        val f = Fixture()
        f.support.compositeHandled = true
        f.probes.startCamera(f.binding)
        val run = f.support.compositeRequests.single()
        run.finished(PostLoginCompositeCameraCheckResult(false, reason = "no_joint_candidate"))
        run.finished(PostLoginCompositeCameraCheckResult(true, PostLoginMetricDepthState.SUPPORTED))
        f.ui.drain()
        assertEquals(PostLoginDeviceCheckSignal.UNAVAILABLE, f.probes.signals.cameraPipeline)
        assertEquals(PostLoginCameraPipelineFailure.COMPOSITE_RUNTIME_UNAVAILABLE, f.probes.cameraFailure)
        assertEquals(PostLoginMetricDepthState.PENDING, f.probes.signals.metricDepth)
        assertFalse(f.probes.hasCamera)
        assertEquals(listOf(f.binding), f.continued)
    }

    @Test fun synchronouslyFinishedCompositeRunStillUsesTheCompositeReleaseContract() {
        val f = Fixture()
        f.support.compositeHandled = true
        f.support.compositeSynchronousResult = PostLoginCompositeCameraCheckResult(true,
            PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED)
        f.probes.startCamera(f.binding)
        assertTrue(f.camera.requests.isEmpty())
        assertEquals(PostLoginDeviceCheckSignal.PENDING, f.probes.signals.cameraPipeline)
        f.ui.drain()
        assertEquals(PostLoginDeviceCheckSignal.READY, f.probes.signals.cameraPipeline)
        assertEquals(PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED, f.probes.signals.metricDepth)
        assertEquals(listOf(f.binding), f.continued)
    }

    @Test fun eightCompletedEmptyInferencesCloseImagesBeforeDetectAndWaitForRelease() {
        val f = Fixture()
        val resources = f.bindCamera()
        repeat(8) {
            val image = FakeImage()
            f.infer = { _, timestamp -> assertTrue(image.closed); assertEquals(812L, timestamp); f.success() }
            f.camera.requests.single().image(image)
            assertEquals(1, image.copies)
            assertEquals(1, image.closes)
        }
        f.ui.drain()
        assertEquals(PostLoginDeviceCheckSignal.READY, f.probes.signals.cameraPipeline)
        assertTrue(f.probes.cameraCleanupPending)
        assertEquals(listOf("clear"), resources.operations)
        assertTrue(f.continued.isEmpty())
        f.detectorQueue.drain()
        f.ui.drain()
        assertEquals(listOf("clear", "unbind", "awaitClosed"), resources.operations)
        assertTrue(f.continued.isEmpty())
        resources.closed(true)
        assertFalse(f.probes.cameraCleanupPending)
        assertEquals(listOf(f.binding), f.continued)
    }

    @Test fun unavailableInferenceFailsAfterThreeSamplesAndKeepsDetectorFailure() {
        val f = Fixture()
        f.bindCamera()
        f.infer = { _, _ -> AndroidDetectionResult.unavailable() }
        repeat(3) { f.camera.requests.single().image(FakeImage()) }
        f.ui.drain()
        assertEquals(PostLoginDeviceCheckSignal.UNAVAILABLE, f.probes.signals.cameraPipeline)
        assertEquals(PostLoginCameraPipelineFailure.DETECTOR_FAILED, f.probes.cameraFailure)
    }

    @Test fun cancelledCameraClosesLateImageWithoutCopyOrInference() {
        val f = Fixture()
        f.bindCamera()
        val old = f.camera.requests.single()
        f.probes.stopCamera()
        val image = FakeImage()
        old.image(image)
        assertFalse(old.current())
        assertEquals(0, image.copies)
        assertEquals(1, image.closes)
        assertEquals(0, f.inferences)
    }

    @Test fun failedClosedProofBlocksAnotherOwnerAndPreservesReleaseFailure() {
        val f = Fixture()
        val resources = f.bindCamera()
        f.probes.stopCamera()
        f.detectorQueue.drain(); f.ui.drain()
        resources.closed(false)
        assertTrue(f.releaseBlocked)
        assertEquals(PostLoginCameraPipelineFailure.RELEASE_TIMED_OUT, f.probes.cameraFailure)
        f.probes.startCamera(f.binding)
        assertEquals(1, f.camera.requests.size)
        assertEquals(PostLoginDeviceCheckSignal.UNAVAILABLE, f.probes.signals.cameraPipeline)
    }

    @Test fun analyzerDeadlineCannotBecomeSuccessWhenCameraEventuallyCloses() {
        val f = Fixture()
        val resources = f.bindCamera()
        f.probes.stopCamera()
        f.release.fire(2_000L)
        f.detectorQueue.drain(); f.ui.drain()
        assertEquals(1, resources.operations.count { it == "unbind" })
        resources.closed(true)
        assertTrue(f.releaseBlocked)
        assertEquals(PostLoginDeviceCheckSignal.UNAVAILABLE, f.probes.signals.cameraPipeline)
    }

    @Test fun successfulTerminalClaimBeatsTimeoutBeforeUiDelivery() {
        val f = Fixture()
        f.bindCamera()
        repeat(8) { f.camera.requests.single().image(FakeImage()) }
        f.ui.fire(15_000L)
        f.ui.drain()
        // A successful terminal claim owns the outcome even before its UI callback runs.
        assertEquals(PostLoginDeviceCheckSignal.READY, f.probes.signals.cameraPipeline)
    }

    @Test fun canceledWakeIgnoresLateReadyAndSuccessfulResult() {
        val f = Fixture()
        f.probes.beginWakeFromUserAction(f.binding)
        assertTrue(f.probes.wakeStartScheduled)
        f.ui.fire(0L)
        val wake = f.wakes.single()
        f.probes.cancelFunctionalProbes()
        wake.ready()
        wake.result(VoskWakePhraseProbeResult.available())
        assertEquals(1, wake.cancelled)
        assertTrue(f.haptics.isEmpty())
        assertEquals(PostLoginDeviceCheckSignal.PENDING, f.probes.signals.wakePhrase)
        assertTrue(f.continued.isEmpty())
    }

    @Test fun wakeReadyCueIsClosedBeforeSuccessfulResultPublishes() {
        val f = Fixture()
        f.probes.beginWakeFromUserAction(f.binding); f.ui.fire(0L)
        val wake = f.wakes.single()
        wake.ready()
        assertEquals(1, f.haptics.single().requested)
        wake.result(VoskWakePhraseProbeResult.available())
        assertEquals(1, f.haptics.single().closed)
        assertFalse(f.probes.wakeActive)
        assertEquals(PostLoginDeviceCheckSignal.READY, f.probes.signals.wakePhrase)
    }

    @Test fun vibrationRequestAloneIsPendingUntilUserConfirmation() {
        val f = Fixture()
        f.probes.requestHaptic(f.binding)
        assertEquals(PostLoginDeviceCheckSignal.PENDING, f.probes.hapticSignal)
        f.probes.confirmHaptic(f.binding, true)
        assertEquals(PostLoginDeviceCheckSignal.READY, f.probes.hapticSignal)
        assertEquals(1, f.haptics.single().closed)
    }

    private class Fixture {
        var binding = PostLoginDeviceCheckBinding("actor", 1, 1)
        var ownerReads = 0
        var releaseBlocked = false
        var inferences = 0
        val ui = FakeDispatcher()
        val release = FakeDispatcher()
        val detectorQueue = QueueExecutor()
        val location = FakeLocation()
        val camera = FakeCamera()
        val support = FakeSupport()
        val wakes = mutableListOf<FakeWake>()
        val haptics = mutableListOf<FakeHaptic>()
        val continued = mutableListOf<PostLoginDeviceCheckBinding>()
        val terminal = mutableListOf<String>()
        var infer: (OwnedYuvFrame, Long) -> AndroidDetectionResult = { _, _ -> success() }
        private val detector = object : AndroidFrameDetector by NoopAndroidFrameDetector() {
            override fun detect(cameraFrame: OwnedYuvFrame, timestampMs: Long,
                onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult {
                ++inferences
                return infer(cameraFrame, timestampMs)
            }
        }
        val probes = AndroidPostLoginDeviceProbes(location, camera,
            PostLoginWakeProbeFactory { _, ready, result -> FakeWake(ready, result).also(wakes::add) },
            { FakeHaptic().also(haptics::add) }, { detector }, detectorQueue, ui, release, { 100_000L },
            { PostLoginProbeEnvironment(true, true, true, true, false) },
            { binding }, { ++ownerReads; it == binding }, { true }, { releaseBlocked }, { releaseBlocked = true },
            { File("/unused-model") }, { false }, {}, support, object : PostLoginProbeObserver {
                override fun changed() {}
                override fun continueCheck(binding: PostLoginDeviceCheckBinding) { continued += binding }
                override fun evaluateCheck(binding: PostLoginDeviceCheckBinding) {}
                override fun navigationDiagnostic(message: String) {}
                override fun locationDiagnostic(decision: DeviceCheckLocationFixDecision) {}
                override fun locationTerminal(result: String, reason: String, accuracyBucket: String, ageBucket: String) {
                    terminal += "$result:$reason:$accuracyBucket:$ageBucket"
                }
                override fun cameraSamplingFailure(generation: Long, attempts: Int, passes: Int, failure: PostLoginCameraPipelineFailure) {}
                override fun wakeReady() {}
                override fun wakeResult(result: VoskWakePhraseProbeResult) {}
                override fun announceProgress(message: String) {}
            })
        fun sample(observedNs: Long = 100_000_000_000L, mock: Boolean = false) =
            PostLoginLocationSample(37.0, 127.0, 5f, observedNs, mock)
        fun success() = AndroidDetectionResult(emptyList(), AndroidDetectorTiming(
            yuvDecodeMs = 0, modelInferenceMs = 0, totalMs = 0, completedModels = listOf("unified_walksafe")))
        fun bindCamera(): FakeResources {
            probes.startCamera(binding)
            val resources = FakeResources()
            camera.requests.last().prepared(resources)
            return resources
        }
    }

    private class FakeDispatcher : PostLoginProbeDispatcher {
        private val posted = ArrayDeque<Runnable>()
        private data class Scheduled(val delay: Long, val action: Runnable, var cancelled: Boolean = false)
        private val scheduled = mutableListOf<Scheduled>()
        override fun post(action: Runnable) { posted += action }
        override fun schedule(delayMs: Long, action: Runnable): DeviceCheckScheduledTask {
            val task = Scheduled(delayMs, action)
            scheduled += task
            return DeviceCheckScheduledTask { task.cancelled = true }
        }
        fun drain() { while (posted.isNotEmpty()) posted.removeFirst().run() }
        fun fire(delay: Long) {
            scheduled.filter { it.delay == delay && !it.cancelled }.toList().forEach { it.cancelled = true; it.action.run() }
        }
    }
    private class QueueExecutor : Executor {
        private val actions = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { actions += command }
        fun drain() { while (actions.isNotEmpty()) actions.removeFirst().run() }
    }
    private class FakeLocation : PostLoginLocationSource {
        data class Request(val locations: (List<PostLoginLocationSample>) -> Unit,
            val registered: () -> Unit, val failed: () -> Unit, var cancelled: Int = 0)
        val requests = mutableListOf<Request>()
        var noFix = DeviceCheckLocationFixUnavailability.NO_FIX_RECEIVED
        override val available = true
        override fun noFixCause() = noFix
        override fun request(onLocations: (List<PostLoginLocationSample>) -> Unit, onRegistered: () -> Unit,
            onFailure: () -> Unit): PostLoginLocationRegistration {
            val request = Request(onLocations, onRegistered, onFailure)
            requests += request
            return PostLoginLocationRegistration { ++request.cancelled; Unit }
        }
    }
    private class FakeCamera : PostLoginCameraSource {
        data class Request(val current: () -> Boolean, val prepared: (PostLoginCameraResources) -> Unit,
            val image: (PostLoginCameraImage) -> Unit, val failed: () -> Unit)
        val requests = mutableListOf<Request>()
        override fun requestBind(isCurrent: () -> Boolean, onPrepared: (PostLoginCameraResources) -> Unit,
            onImage: (PostLoginCameraImage) -> Unit, onFailure: () -> Unit) {
            requests += Request(isCurrent, onPrepared, onImage, onFailure)
        }
    }
    private class FakeResources : PostLoginCameraResources {
        override val hasAnalysis = true
        override val hasCamera = true
        val operations = mutableListOf<String>()
        lateinit var closed: (Boolean) -> Unit
        override fun clearAnalyzer() { operations += "clear" }
        override fun unbindAndDestroy() { operations += "unbind" }
        override fun awaitClosed(onComplete: (Boolean) -> Unit) { operations += "awaitClosed"; closed = onComplete }
    }
    private class FakeImage : PostLoginCameraImage {
        override val timestampNs = 812_000_003L
        override val width = 2
        override val height = 2
        var closed = false
        var closes = 0
        var copies = 0
        override fun hasImage() = true
        override fun copyOwnedFrame(): OwnedYuvFrame {
            check(!closed)
            ++copies
            return OwnedYuvFrame.copyOf(2, 2, ByteBuffer.wrap(ByteArray(4)), ByteBuffer.wrap(ByteArray(1)),
                ByteBuffer.wrap(ByteArray(1)), 2, 1, 1, 1)
        }
        override fun close() { check(!closed); closed = true; ++closes }
    }
    private class FakeWake(val ready: () -> Unit, val result: (VoskWakePhraseProbeResult) -> Unit) : PostLoginWakeProbe {
        var cancelled = 0
        override fun start() = true
        override fun cancel() { ++cancelled }
    }
    private class FakeHaptic : PostLoginHapticProbe {
        var requested = 0
        var closed = 0
        override fun requestTestVibration(): Boolean { ++requested; return true }
        override fun close() { ++closed }
    }
    private class FakeSupport : PostLoginProbeRuntimeSupport {
        data class CompositeRequest(val binding: PostLoginDeviceCheckBinding,
            val finished: (PostLoginCompositeCameraCheckResult) -> Unit)
        var calls = 0
        var compositeHandled = false
        var compositeSynchronousResult: PostLoginCompositeCameraCheckResult? = null
        val compositeRequests = mutableListOf<CompositeRequest>()
        val compositeCancellations = mutableListOf<String>()
        override val capabilityProbeStarted = false
        override fun restartCapabilityAndResources(binding: PostLoginDeviceCheckBinding, onChanged: () -> Unit) { ++calls }
        override fun prepareVoiceModelAndDetector() { ++calls }
        override fun startAutomaticProbes(binding: PostLoginDeviceCheckBinding) { ++calls }
        override fun startMetricPreflight(binding: PostLoginDeviceCheckBinding) { ++calls }
        override fun startCompositeCameraCheck(binding: PostLoginDeviceCheckBinding,
            onFinished: (PostLoginCompositeCameraCheckResult) -> Unit): Boolean {
            if (!compositeHandled) return false
            compositeRequests += CompositeRequest(binding, onFinished)
            compositeSynchronousResult?.let(onFinished)
            return true
        }
        override fun cancelCompositeCameraCheck(reason: String) { compositeCancellations += reason }
        override fun cancelCalibration(reason: String) { ++calls }
        override fun cancelMetricPreflight(reason: String) { ++calls }
        override fun closeCapabilityAndResetResources() { ++calls }
        override fun mayValidateStoredBinding() = true
        override fun startStoredBindingValidation(onChanged: () -> Unit) = true
        override fun mayStartStoredMetricRefresh(binding: PostLoginDeviceCheckBinding) = true
        override fun startStoredMetricRefresh(binding: PostLoginDeviceCheckBinding) { ++calls }
    }
}
