package kr.co.hanium.dreamup.walksafe.device.environment

import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityReason
import kr.co.hanium.dreamup.walksafe.device.CameraPreflightStabilityDecision
import kr.co.hanium.dreamup.walksafe.device.CameraPreflightStabilityGate
import kr.co.hanium.dreamup.walksafe.device.OfficialEnvironmentGpsSample
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class OfficialEnvironmentPreflightControllerTest {
    @Test fun constructorStartsNoSourcesAndReadsNoFacts() {
        val f = Fixture()
        assertEquals(0, f.factReads)
        assertEquals(0, f.cameraStarts)
        assertTrue(f.requests.isEmpty())
        assertTrue(f.ui.isEmpty())
        assertEquals(0, f.cameraFactories)
        assertEquals(0, f.gpsFactories)
    }

    @Test fun readonlyStateQueriesDoNotConstructEitherSdkSourceOrReadLifecycle() {
        val f = Fixture()
        assertFalse(f.controller.separateGpsActive)
        assertFalse(f.controller.cameraActive)
        assertFalse(f.controller.cameraCleanupPending)
        assertFalse(f.controller.isCameraCurrent(f.epoch, 0L))
        assertEquals(100L, f.controller.cameraAnalysisIntervalMs())
        assertEquals(0, f.cameraFactories)
        assertEquals(0, f.gpsFactories)
        assertEquals(0, f.factReads)
    }

    @Test fun idleCleanupStopsMountingSensorWithoutConstructingUnusedSdkSources() {
        val f = Fixture()
        f.controller.stopCamera()
        assertEquals(1, f.sensorStops)
        assertEquals(0, f.cameraFactories)
        assertEquals(0, f.gpsFactories)
    }

    @Test fun gpsAttemptsCreateOneGpsSourceAndNeverConstructTheUnusedCameraSource() {
        val f = Fixture()
        f.controller.requestGps(f.epoch)
        f.controller.requestGps(f.epoch)
        assertEquals(2, f.requests.size)
        assertEquals(1, f.gpsFactories)
        assertEquals(0, f.cameraFactories)
    }

    @Test fun cameraAttemptsReuseOneActualSourceAcrossReleaseAndRestart() {
        val f = Fixture(cameraEnabled = true)
        f.controller.startCamera(f.epoch)
        f.controller.stopCamera()
        f.barrier!!.run()
        f.closed!!.invoke(true)
        f.controller.startCamera(f.epoch)
        assertEquals(2, f.cameraStarts)
        assertEquals(1, f.cameraFactories)
        assertEquals(0, f.gpsFactories)
    }

    @Test fun canceledGpsCallbacksCannotPublishEvidenceOrRefreshUi() {
        val f = Fixture()
        f.controller.requestGps(f.epoch)
        val request = f.requests.single()
        f.controller.stopGps()
        request.emit()
        request.failure()
        assertNull(f.evidence.gpsEvidence)
        assertEquals(0, f.refreshes)
        assertTrue(request.cancellations > 0)
    }

    @Test fun equalLookingNewGatewaySessionCannotReuseAnOldRequest() {
        val f = Fixture()
        f.session = String(charArrayOf('s'))
        f.controller.requestGps(f.epoch)
        val request = f.requests.single()
        f.session = String(charArrayOf('s'))
        request.emit()
        assertNull(f.evidence.gpsEvidence)
        assertEquals(0, f.refreshes)
    }

    @Test fun gpsOnlyPassFinishesWithoutStartingTheCamera() {
        val f = Fixture()
        f.controller.requestGps(f.epoch)
        f.requests.single().emit()
        assertEquals(EnvironmentEvidenceStatus.PASS, f.evidence.gpsEvidence?.status)
        assertEquals(OfficialEnvironmentPreflightPhase.IDLE, f.controller.phase)
        assertFalse(f.controller.separateGpsActive)
        assertEquals(0, f.cameraStarts)
        assertEquals(1, f.refreshes)
    }

    @Test fun canceledTimeoutCannotFailANewGpsAttempt() {
        val f = Fixture()
        f.controller.requestGps(f.epoch)
        val oldTimeout = f.ui.single()
        f.controller.requestGps(f.epoch)
        oldTimeout.run()
        assertEquals(OfficialEnvironmentPreflightPhase.MEASURING, f.controller.phase)
        assertEquals(0, f.refreshes)
        f.ui.last().run()
        assertEquals(OfficialEnvironmentPreflightPhase.TIMED_OUT, f.controller.phase)
        assertEquals(1, f.refreshes)
    }

    @Test fun closedProofInvokesContinuationAfterAnalyzerBarrierOnce() {
        val f = Fixture(cameraEnabled = true)
        assertTrue(f.controller.startCamera(f.epoch))
        var continued = 0
        f.controller.stopCamera(onReleased = { continued++ })
        assertTrue(f.controller.cameraCleanupPending)
        assertEquals(0, continued)
        f.barrier!!.run()
        f.closed!!.invoke(true)
        assertEquals(1, continued)
        assertFalse(f.controller.cameraCleanupPending)
        assertEquals(1, f.unbinds)
        assertFalse(f.blocked)
    }

    @Test fun analyzerBarrierTimeoutCannotBeHiddenByLaterClosedProof() {
        val f = Fixture(cameraEnabled = true)
        f.controller.startCamera(f.epoch)
        var continued = 0
        f.controller.stopCamera(onReleased = { continued++ })
        f.release.single().run()
        f.closed!!.invoke(true)
        f.barrier!!.run()
        assertEquals(0, continued)
        assertTrue(f.blocked)
        assertEquals("CAMERA_RELEASE_TIMED_OUT", f.evidence.cameraDiagnosticReason)
        assertEquals(OfficialEnvironmentPreflightPhase.FAILED, f.controller.phase)
        assertEquals(1, f.unbinds)
    }

    @Test fun cameraClosedFailureCannotAdvanceToAnotherAttempt() {
        val f = Fixture(cameraEnabled = true)
        f.controller.startCamera(f.epoch)
        var continued = 0
        f.controller.stopCamera(onReleased = { continued++ })
        f.barrier!!.run()
        f.closed!!.invoke(false)
        assertEquals(0, continued)
        assertTrue(f.blocked)
        assertEquals(1, f.cancelHandoffs)
    }

    @Test fun staleCameraBindFailureCannotStopTheCurrentAttempt() {
        val f = Fixture(cameraEnabled = true)
        f.controller.startCamera(f.epoch)
        val oldFailure = f.bindFailure!!
        f.controller.stopCamera()
        f.barrier!!.run()
        f.closed!!.invoke(true)
        f.controller.startCamera(f.epoch)
        oldFailure()
        assertTrue(f.controller.cameraActive)
        assertEquals(OfficialEnvironmentPreflightPhase.MEASURING, f.controller.phase)
    }

    @Test fun cameraShakeIsNotAConditionAndOnlyExistingQualityReasonsRetainPass() {
        val f = Fixture(cameraEnabled = true)
        val pass = CameraFrameQualityAssessment(f.epoch, 1_000L, "camera", 30_000L,
            EnvironmentEvidenceStatus.PASS, CameraFrameQualityReason.PASSED)
        assertEquals(CameraPreflightStabilityDecision.STABILIZING, f.controller.observeCameraStability(pass))
        assertEquals(CameraPreflightStabilityDecision.ACCEPT, f.controller.observeCameraStability(pass))
        val bad = pass.copy(status = EnvironmentEvidenceStatus.FAIL, reason = CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE)
        assertEquals(CameraPreflightStabilityDecision.RETAIN_PREVIOUS_PASS, f.controller.observeCameraStability(bad))
        assertEquals(CameraPreflightStabilityDecision.ACCEPT, f.controller.observeCameraStability(bad.copy(reason = CameraFrameQualityReason.SHAKE_OUTSIDE_APPROVED_RANGE)))
    }

    internal class Fixture(private val cameraEnabled: Boolean = false, requiredCameraPasses: Int = 2) : OfficialEnvironmentPreflightFactsReader,
        OfficialEnvironmentPreflightQueue, OfficialEnvironmentCameraSource, OfficialEnvironmentGpsSource, OfficialEnvironmentPreflightEffects {
        val epoch = WalkRuntimeEpoch("walk", 1L)
        private val profile = ApprovedOfficialEnvironmentProfile("gps", "camera", 12.0, 30_000L, 2)
        val evidence = OfficialEnvironmentEvidenceStore(Any(), RuntimeMeasuredQualityGate(3_000L), RuntimeMeasuredQualityGate())
        var factReads = 0
        var cameraStarts = 0
        var cameraFactories = 0
        var gpsFactories = 0
        var sensorStops = 0
        val requests = mutableListOf<Request>()
        val ui = mutableListOf<Runnable>()
        val release = mutableListOf<Runnable>()
        var session: Any = Any()
        var refreshes = 0
        var barrier: Runnable? = null
        var closed: ((Boolean) -> Unit)? = null
        var bindFailure: (() -> Unit)? = null
        var unbinds = 0
        var blocked = false
        var cancelHandoffs = 0
        val controller = OfficialEnvironmentPreflightController(evidence, this, { cameraFactories++; this }, { gpsFactories++; this }, this, this,
            CameraPreflightStabilityGate(requiredCameraPasses, 2), { 1_000L }, OfficialEnvironmentPreflightController.Config(100L, 300L, 10_000L, 10_000L, 2_000L))
        override fun lifecycle(): OfficialEnvironmentPreflightLifecycleFacts { factReads++; return OfficialEnvironmentPreflightLifecycleFacts(epoch, WalkSessionState.READY, true) }
        override fun foreground() = true
        override fun cameraProfilePresent() = true
        override fun cameraEnabled() = cameraEnabled
        override fun locationPermission() = true
        override fun locationServiceEnabled() = true
        override fun profile() = profile
        override fun gpsOwner() = OfficialEnvironmentGpsOwner(1L, session)
        override fun nativeCheckRequested() = false
        override fun nativeGpsCheckCurrent(epoch: WalkRuntimeEpoch) = true
        override fun walkRuntimeActive() = false
        override fun uiAlive() = true
        override fun uiReady() = true
        override fun removeUi(action: Runnable) { ui.remove(action) }
        override fun postUi(delayMs: Long, action: Runnable) { ui += action }
        override fun removeRelease(action: Runnable) { release.remove(action) }
        override fun postRelease(delayMs: Long, action: Runnable) { release += action }
        override fun runOnUiThread(action: Runnable) { action.run() }
        override fun startFailureReason(): String? = null
        override fun resumeLifecycle() {}
        override fun start(epoch: WalkRuntimeEpoch, generation: Long, isCurrent: () -> Boolean, acceptAnalysisAt: (Long) -> Boolean, onBindFailed: () -> Unit) { cameraStarts++; bindFailure = onBindFailed }
        override fun stopMountingSensorIfInitialized() { sensorStops++ }
        override fun detachOwnedResources() = object : OfficialEnvironmentCameraResources {
            override val hasAnalysis = true
            override val hasCamera = true
            override fun clearAnalyzer() {}
            override fun unbindOwned() { unbinds++ }
        }
        override fun moveLifecycleToCreatedIfInitialized() {}
        override fun awaitClosed(resources: OfficialEnvironmentCameraResources?, completion: (Boolean) -> Unit) { closed = completion }
        override fun awaitAnalyzerBarrier(release: Runnable, onRejected: () -> Unit) { barrier = release }
        override fun markSharedReleaseBlocked() { blocked = true }
        override fun createRequest(profile: ApprovedOfficialEnvironmentProfile?, isCurrent: () -> Boolean,
            onSample: (OfficialEnvironmentGpsSample) -> Unit, onTemporarilyUnavailable: () -> Unit, onFailure: () -> Unit) =
            Request(isCurrent, onSample, onFailure).also { requests += it }
        inner class Request(private val current: () -> Boolean, private val sample: (OfficialEnvironmentGpsSample) -> Unit,
            private val onFailure: () -> Unit) : OfficialEnvironmentGpsRequest {
            var cancellations = 0
            override fun start() {}
            override fun cancel() { cancellations++ }
            fun emit() { if (current()) sample(OfficialEnvironmentGpsSample(1_000L, 37.5, 127.0, 4.0, false)) }
            fun failure() { onFailure() }
        }
        override fun observeUnavailableCamera(epoch: WalkRuntimeEpoch, observedAtMs: Long, frameAvailable: Boolean?, reason: String?) {}
        override fun cameraUnavailableDiagnostic(reason: String) {}
        override fun refreshEnvironmentAndReadiness() { refreshes++ }
        override fun showStatus(title: String, detail: String) {}
        override fun cancelRuntimeCameraHandoff() { cancelHandoffs++ }
        override fun enterSafetyStop(reason: String) { fail("not walking") }
        override fun gpsTemporarilyUnavailableDiagnostic(generation: Long) {}
        override fun gpsSampleDiagnostic(sample: OfficialEnvironmentGpsSample, candidate: MeasuredEnvironmentEvidence, retainedRecentPass: Boolean, nowMs: Long) {}
    }
}
