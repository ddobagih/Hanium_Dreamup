package kr.co.hanium.dreamup.walksafe.session.mounting

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentEvidenceStore
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class PhoneMountingSessionControllerTest {
    @Test fun constructorIsInertAndUsesTheOriginalObservationLock() {
        val f = Fixture()
        assertSame(f.lock, f.mount.observationLock)
        assertSame(f.lock, f.evidence.observationLock)
        assertTrue(f.events.isEmpty())
        assertNull(f.mount.confirmation)
        assertFalse(f.mount.outputsAllowed)
    }

    @Test fun explicitCaptureHasOriginalIdentityAndClockInsideTheLockButConsumeIsOutside() {
        val f = Fixture()
        f.now = 900L
        val (at, receipt) = f.mount.confirmExplicit(f.epoch, PhoneMountingMethod.CHEST_FORWARD, false)
        assertEquals(900L, at)
        assertSame(f.mount.confirmation, f.mount.explicitConfirmation)
        assertSame(f.mount.confirmation, receipt!!.facts.confirmation)
        assertSame(receipt.facts.confirmation, receipt.facts.explicitConfirmation)
        assertEquals(900L, receipt.at)
        assertEquals(listOf("target", "clock_locked", "capture"), f.events)
        f.mount.consumeConfirmation(receipt)
        assertEquals("consume", f.events.last())
        assertEquals(900L, receipt.at)
    }

    @Test fun equalAssumedConfirmationCannotBecomeAnExplicitDeclaration() {
        val f = Fixture()
        f.now = 900L
        f.mount.confirmExplicit(f.epoch, PhoneMountingMethod.CHEST_FORWARD, false)
        val original = f.mount.confirmation
        f.mount.assumeInitialConfirmation(f.epoch)
        assertEquals(original, f.mount.confirmation)
        assertNotSame(original, f.mount.confirmation)
        assertNull(f.mount.explicitConfirmation)
        assertNull(f.consumed!!.facts.explicitConfirmation)
        synchronized(f.lock) { f.mount.retireExplicitForChangedMotionOwner() }
        assertNull(f.mount.explicitConfirmation)
        assertNotNull(f.mount.confirmation)
    }

    @Test fun invalidationPublishesOnlyAnOwnedUnknownAndRetiresDelayedWatchdog() {
        val f = Fixture()
        f.makeSuitable()
        val oldWatchdog = f.mountingTasks.single().second
        val generation = f.mount.observationGeneration()
        f.mount.invalidatePhoneMountingEvidence("logout")
        assertEquals(generation + 1L, f.mount.observationGeneration())
        assertNull(f.mount.confirmation)
        assertNull(f.mount.explicitConfirmation)
        assertNull(f.mount.runtimeState)
        assertFalse(f.mount.outputsAllowed)
        assertNull(f.consumed!!.facts.confirmation)
        f.events.clear()
        oldWatchdog()
        assertTrue(f.events.isEmpty())
    }

    @Test fun actualCameraFaultRevokesOutputBeforePolicyAndOldGenerationCannotRestoreIt() {
        val f = Fixture()
        f.makeSuitable()
        val generation = f.mount.observationGeneration()
        val bad = f.camera(1_100L, EnvironmentEvidenceStatus.FAIL)
        val sequence = synchronized(f.lock) { f.mount.commitCameraAssessment(f.epoch, bad, CameraPreflightStabilityDecision.ACCEPT, true) }
        assertFalse(f.mount.outputsAllowed)
        f.now = 1_100L
        assertTrue(f.mount.applyRuntimeCameraAssessment(false, bad, sequence, generation, true))
        assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, f.mount.currentPhoneMountingAssessment(
            epoch = f.epoch, phase = PhoneMountingAssessmentPhase.ACTIVE, previousState = f.mount.runtimeState).status)
        assertTrue(f.events.indexOf("report_revoke_locked") < f.events.lastIndexOf("consume"))
        assertTrue(f.events.indexOf("report_revoke_locked") < f.events.lastIndexOf("correction:true"))
        f.mount.invalidatePhoneMountingEvidence("new_scope")
        assertFalse(f.mount.applyRuntimeCameraAssessment(false, bad, sequence, generation, true))
        assertFalse(f.mount.outputsAllowed)
    }

    @Test fun queuedPassCannotEraseAnUnappliedNewerFault() {
        val f = Fixture()
        f.makeSuitable()
        val generation = f.mount.observationGeneration()
        val pass = f.camera(1_100L)
        val passSequence = synchronized(f.lock) { f.mount.commitCameraAssessment(f.epoch, pass, CameraPreflightStabilityDecision.ACCEPT, false) }
        synchronized(f.lock) { f.mount.commitCameraAssessment(f.epoch, f.camera(1_101L, EnvironmentEvidenceStatus.FAIL), CameraPreflightStabilityDecision.ACCEPT, true) }
        assertFalse(f.mount.applyRuntimeCameraAssessment(false, pass, passSequence, generation, false))
        assertFalse(f.mount.outputsAllowed)
    }

    @Test fun registeredExpiryWatchdogRestrictsTheOldFrameThenFreshCameraAutomaticallyRestoresOutputs() {
        val f = Fixture()
        f.makeSuitable()
        val (delayMs, watchdog) = f.mountingTasks.single()
        assertEquals(4_901L, delayMs)
        f.now = 5_901L
        watchdog()
        assertFalse(f.mount.outputsAllowed)
        assertEquals(5_901L, f.mount.runtimeState!!.correctionRequiredSinceElapsedRealtimeMs)
        assertEquals(900L, f.mount.latestCameraAssessment()!!.observedAtElapsedRealtimeMs)

        val generation = f.mount.observationGeneration()
        val fresh = f.camera(5_902L)
        f.now = 5_902L
        val sequence = synchronized(f.lock) {
            f.mount.commitCameraAssessment(f.epoch, fresh, CameraPreflightStabilityDecision.ACCEPT, false)
        }
        assertTrue(f.mount.applyRuntimeCameraAssessment(false, fresh, sequence, generation, false))
        assertTrue(f.mount.outputsAllowed)
        assertNull(f.mount.runtimeState!!.correctionRequiredSinceElapsedRealtimeMs)
        assertEquals(800L, f.mount.confirmation!!.confirmedAtElapsedRealtimeMs)
        assertFalse(f.mount.confirmation!!.postFaultCorrectionConfirmed)
    }

    @Test fun handoffCompletionMakesTheOldTimeoutInertAndDoesNotResetGpsFailureHistory() {
        val f = Fixture()
        f.makeSuitable()
        val pass = MeasuredEnvironmentEvidence(factor = OfficialEnvironmentFactor.GPS_QUALITY, epoch = f.epoch,
            observedAtElapsedRealtimeMs = 500L, status = EnvironmentEvidenceStatus.PASS, measurementProfileId = "gps",
            maximumEvidenceAgeMs = 30_000L, detail = "PASSED")
        f.gps.observe(pass, 500L)
        f.gps.observe(pass.copy(status = EnvironmentEvidenceStatus.FAIL, observedAtElapsedRealtimeMs = 600L,
            detail = "GPS_ACCURACY_OUTSIDE_APPROVED_RANGE"), 600L)
        val before = f.gps.latestRawObservation()
        val deadline = f.gps.pendingFailureDeadlineElapsedRealtimeMs()
        assertEquals(3_600L, deadline)
        f.mount.beginRuntimeCameraHandoff(f.epoch)
        assertEquals(listOf("official_watchdog", "official_assess"), f.events.takeLast(2))
        assertEquals(f.epoch, f.mount.handoffEpoch)
        synchronized(f.lock) { f.mount.completeCameraHandoffIfCurrent(f.epoch) }
        f.events.clear()
        f.statusTasks.single().second()
        assertTrue(f.events.isEmpty())
        assertSame(before, f.gps.latestRawObservation())
        assertEquals(deadline, f.gps.pendingFailureDeadlineElapsedRealtimeMs())
    }

    @Test fun bypassIsReadDynamicallyAndDoesNotTouchSourcesDuringTheBypassedOperation() {
        val f = Fixture()
        f.bypass = true
        assertTrue(f.mount.activatePhoneMountingRuntime())
        assertTrue(f.mount.outputsAllowed)
        assertFalse(f.events.contains("sensor_start"))
        f.bypass = false
        f.mount.activatePhoneMountingRuntime()
        assertTrue(f.events.contains("sensor_start"))
        assertFalse(f.mount.outputsAllowed)
    }

    @Test fun lifetimeKeepsInclusiveBoundaryBackwardClockAndOverflowRules() {
        val f = Fixture()
        assertEquals(1L, f.mount.remainingEvidenceLifetimeMs(100L, 100L, 0L))
        assertEquals(2L, f.mount.remainingEvidenceLifetimeMs(100L, 109L, 10L))
        assertEquals(1L, f.mount.remainingEvidenceLifetimeMs(100L, 99L, 10L))
        assertEquals(Long.MAX_VALUE, f.mount.remainingEvidenceLifetimeMs(0L, 0L, Long.MAX_VALUE))
    }

    private data class Receipt(val facts: MountingPositionFacts, val at: Long)
    private class Fixture : MountingEffects, MountingSensorLifecycle, MountingScheduler, PositioningMountBridge<String, Receipt> {
        val lock = Any()
        val gps = RuntimeMeasuredQualityGate(3_000L)
        val evidence = OfficialEnvironmentEvidenceStore(lock, gps, RuntimeMeasuredQualityGate())
        var now = 1_000L
        var bypass = false
        val events = mutableListOf<String>()
        val mountingTasks = mutableListOf<Pair<Long, () -> Unit>>()
        val statusTasks = mutableListOf<Pair<Long, () -> Unit>>()
        val lifecycle = WalkSessionLifecycle({ "walk" }, { 100L }, { now })
        val epoch: WalkRuntimeEpoch
        var consumed: Receipt? = null
        val profile = ApprovedPhoneMountingProfile("mount", "camera", 5_000L, 2, 10_000L)
        val mount: PhoneMountingSessionController<String, Receipt>
        init {
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val p = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val c = WalkSessionReadinessCollector(lifecycle.snapshot().epoch, p)
            p.requiredRequirements.forEach { c.record(WalkSessionReadinessObservation(c.epoch, it, WalkSessionReadinessStatus.READY)) }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(c.build(now)))
            lifecycle.handle(WalkSessionEvent.StartRequested(lifecycle.snapshot().confirmationToken!!))
            epoch = lifecycle.snapshot().epoch
            mount = PhoneMountingSessionController(lock, evidence, MountingPreflightEvidence { events += "preflight_reset_locked" },
                { lifecycle }, { profile }, { true }, { bypass }, { null }, this, this, this, this,
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
            facts: MountingPositionFacts): Receipt { assertTrue(Thread.holdsLock(lock)); events += "capture"; return Receipt(facts, observedAtMs) }
        override fun invalidatedLocked(target: String, generation: Long, observedAtMs: Long): Receipt {
            assertTrue(Thread.holdsLock(lock)); return Receipt(MountingPositionFacts(generation, null, null, null), observedAtMs)
        }
        override fun consume(receipt: Receipt?, receivedAtMs: Long) { assertFalse(Thread.holdsLock(lock)); events += "consume"; consumed = receipt }
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
}
