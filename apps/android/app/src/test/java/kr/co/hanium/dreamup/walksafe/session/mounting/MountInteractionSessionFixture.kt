package kr.co.hanium.dreamup.walksafe.session.mounting

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentEvidenceStore
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*

internal data class MountInteractionReceipt(val facts: MountingPositionFacts, val at: Long)
internal class MountInteractionSessionFixture : MountingEffects, MountingSensorLifecycle, MountingScheduler, PositioningMountBridge<String, MountInteractionReceipt> {
    val lock = Any()
    val gps = RuntimeMeasuredQualityGate(3_000L)
    val evidence = OfficialEnvironmentEvidenceStore(lock, gps, RuntimeMeasuredQualityGate())
    var now = 1_000L
    var bypass = false
    var nativeCheck: PhoneMountingCheckRequest? = null
    val events = mutableListOf<String>()
    val mountingTasks = mutableListOf<Pair<Long, () -> Unit>>()
    val statusTasks = mutableListOf<Pair<Long, () -> Unit>>()
    val lifecycle = WalkSessionLifecycle({ "walk" }, { 100L }, { now })
    val epoch: WalkRuntimeEpoch get() = lifecycle.snapshot().epoch
    var consumed: MountInteractionReceipt? = null
    val profile = ApprovedPhoneMountingProfile("mount", "camera", 5_000L, 2, 10_000L)
    val mount: PhoneMountingSessionController<String, MountInteractionReceipt>
    init {
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val p = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
        val c = WalkSessionReadinessCollector(lifecycle.snapshot().epoch, p)
        p.requiredRequirements.forEach { c.record(WalkSessionReadinessObservation(c.epoch, it, WalkSessionReadinessStatus.READY)) }
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(c.build(now)))
        lifecycle.handle(WalkSessionEvent.StartRequested(lifecycle.snapshot().confirmationToken!!))
        mount = PhoneMountingSessionController(lock, evidence, MountingPreflightEvidence { events += "preflight_reset_locked" },
            { lifecycle }, { profile }, { true }, { bypass }, { nativeCheck }, this, this, this, this,
            { events += if (Thread.holdsLock(lock)) "clock_locked" else "clock"; now }, 4_000L)
    }
    fun camera(at: Long, status: EnvironmentEvidenceStatus = EnvironmentEvidenceStatus.PASS) =
        CameraFrameQualityAssessment(epoch, at, "camera", 5_000L, status,
            if (status == EnvironmentEvidenceStatus.PASS) CameraFrameQualityReason.PASSED else CameraFrameQualityReason.FRAME_UNAVAILABLE)
    fun makeSuitable() {
        now = 800L
        val (_, r) = mount.confirmExplicit(epoch, PhoneMountingMethod.CHEST_FORWARD, false)
        mount.consumeConfirmation(r)
        synchronized(lock) { mount.commitCameraAssessment(epoch, camera(900L), CameraPreflightStabilityDecision.ACCEPT, false) }
        now = 1_000L
        assertTrue(mount.activatePhoneMountingRuntime())
        assertTrue(mount.outputsAllowed)
        events.clear()
    }
    override fun captureTarget(): String { assertFalse(Thread.holdsLock(lock)); events += "target"; return "target" }
    override fun captureLocked(target: String?, epoch: WalkRuntimeEpoch, observedAtMs: Long, phase: PhoneMountingAssessmentPhase,
        previousState: PhoneMountingRuntimeState?, cameraOverride: CameraFrameQualityAssessment?, retryRequested: Boolean,
        facts: MountingPositionFacts): MountInteractionReceipt { assertTrue(Thread.holdsLock(lock)); events += "capture"; return MountInteractionReceipt(facts, observedAtMs) }
    override fun invalidatedLocked(target: String, generation: Long, observedAtMs: Long): MountInteractionReceipt {
        assertTrue(Thread.holdsLock(lock)); return MountInteractionReceipt(MountingPositionFacts(generation, null, null, null), observedAtMs)
    }
    override fun consume(receipt: MountInteractionReceipt?, receivedAtMs: Long) { assertFalse(Thread.holdsLock(lock)); events += "consume"; consumed = receipt }
    override fun start(): Boolean { events += "sensor_start"; return true }
    override fun stop() { events += "sensor_stop" }
    override fun postMounting(action: () -> Unit, delayMs: Long): Boolean { mountingTasks += delayMs to action; return true }
    override fun postStatus(delayMs: Long, action: () -> Unit): Boolean { statusTasks += delayMs to action; return true }
    override fun revokeReportOutputs() { assertTrue(Thread.holdsLock(lock)); events += "report_revoke_locked" }
    override fun updateMountingUi() { events += "mount_ui" }
    override fun observeUnavailableCamera(epoch: WalkRuntimeEpoch, observedAtMs: Long) { events += "camera_unavailable" }
    override fun sensorStartFailed() { events += "sensor_failed" }
    override fun cameraHandoffStarted() { events += "official_watchdog" }
    override fun reassessOfficialEnvironment() { events += "official_assess" }
    override fun cameraHandoffTimedOut() { events += "handoff_timeout" }
    override fun correction(assessment: PhoneMountingAssessment, announce: Boolean) { events += "correction:$announce" }
    override fun unusable(assessment: PhoneMountingAssessment, announce: Boolean) { events += "unusable:$announce" }
    override fun invalidated(reason: String) { events += "invalidated:$reason" }
}
