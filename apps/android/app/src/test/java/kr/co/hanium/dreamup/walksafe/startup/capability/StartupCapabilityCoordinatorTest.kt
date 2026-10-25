package kr.co.hanium.dreamup.walksafe.startup.capability

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.readiness.StartupCapabilityReadinessEvidence
import org.junit.Assert.*
import org.junit.Test

/** Actual decision/evidence/lifecycle workflow. SDK and UI effects are controlled ports. */
class StartupCapabilityCoordinatorTest {
    @Test fun constructorAndUnboundUiDoNotQueryCapabilitiesOrLifecycle() {
        val f = Fixture()
        assertTrue(f.events.isEmpty())
        f.uiReady = false
        f.controller.refreshStartupCapabilityUi()
        assertEquals(listOf("ui-ready"), f.events)
    }

    @Test fun refreshRecordsBeforePersistAndRendersBeforeAdvancingTheSameWalk() {
        val f = Fixture()
        f.controller.refreshStartupCapabilityUi()
        assertSame(f.decision, f.evidence.currentDecision)
        assertEquals(1_000L, f.evidence.originalObservedAtMs)
        assertEquals(listOf("ui-ready", "probe", "disabled", "permission", "record-clock", "record-version", "persist",
            "confirmed", "priority", "official-ui", "official-readiness", "mount-readiness", "render",
            "prewalk", "priority-ui", "first-run", "mount-ui", "field-log", "buttons", "advance", "summary"), f.events)
    }

    @Test fun activeFeatureChangeRebindsConfirmationButBlockedChangeRetiresIt() {
        val f = Fixture(active = true)
        val original = f.decision
        f.evidence.record(original); f.confirmed = original; f.pending = true; f.retry = true
        f.decision = original.copy(tier = WalkSafeStartupCapabilityTier.LIMITED,
            unavailableRequirements = listOf(WalkSafeStartupRequirement.CAMERA))
        f.events.clear(); f.controller.refreshStartupCapabilityUi()
        assertSame(f.decision, f.confirmed)
        assertFalse(f.pending); assertFalse(f.retry)
        assertTrue(f.events.indexOf("persist") < f.events.indexOf("capture"))
        f.decision = f.decision.copy(tier = WalkSafeStartupCapabilityTier.BLOCKED,
            unavailableRequirements = listOf(WalkSafeStartupRequirement.ANDROID_VERSION))
        f.controller.refreshStartupCapabilityUi()
        assertNull(f.confirmed)
    }

    @Test fun failedDeviceGateDoesNotReadUnneededPermissionsAndVoiceFailureCannotReopenStart() {
        val f = Fixture()
        f.checkPasses = false
        assertSame(f.decision, f.controller.applyPostLoginDeviceFeatureRestrictions(f.decision))
        assertTrue(f.events.isEmpty())
        f.checkPasses = true; f.disabled = setOf(PostLoginDeviceCheckFeature.VOICE_GUIDANCE)
        val restricted = f.controller.applyPostLoginDeviceFeatureRestrictions(f.decision)
        assertEquals(WalkSafeStartupCapabilityTier.BLOCKED, restricted.tier)
        assertTrue(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS in restricted.unavailableRequirements)
        assertEquals("한국어 음성 제한", restricted.noticeKo)
    }

    @Test fun activeReadinessLossRetainsCleanupOrderAndUsesRealSafeStopTransition() {
        val f = Fixture(active = true); f.unavailable = WalkSessionReadinessRequirement.CAMERA
        f.controller.applyRuntimeReadinessIfActive(f.decision)
        assertEquals(WalkSessionState.SAFE_STOP, f.lifecycle.snapshot().state)
        assertEquals(listOf("capture", "transition", "persist-interruption", "clear-resume", "invalidate-environment",
            "invalidate-mount", "cancel-outputs", "stop-camera", "stop-depth", "navigation-status", "status", "speak"), f.events)
        val count = f.events.size
        f.controller.applyRuntimeReadinessIfActive(f.decision)
        assertEquals(count, f.events.size)
        assertTrue(f.spoken.single().startsWith("보행 기능을 안전 중지했습니다."))
    }

    @Test fun nestedReadinessIsSuppressedAndFailureAlwaysReleasesTheLatch() {
        val f = Fixture(active = true)
        val failure = IllegalStateException("capture failed")
        f.captureAction = {
            f.controller.applyRuntimeReadinessIfActive(f.decision)
            throw failure
        }
        try { f.controller.applyRuntimeReadinessIfActive(f.decision); fail() } catch (caught: IllegalStateException) { assertSame(failure, caught) }
        assertEquals(1, f.events.count { it == "capture" })
        f.captureAction = null
        f.controller.applyRuntimeReadinessIfActive(f.decision)
        assertEquals(2, f.events.count { it == "capture" })
        assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
    }

    private class Fixture(active: Boolean = false) {
        val events = mutableListOf<String>(); val spoken = mutableListOf<String>()
        var now = 1_000L; var uiReady = true; var checkPasses = true
        var disabled = emptySet<PostLoginDeviceCheckFeature>()
        var confirmed: WalkSafeStartupCapabilityDecision? = null; var pending = false; var retry = false
        var decision = WalkSafeStartupCapabilityDecision(WalkSafeStartupCapabilityTier.FULL, emptyList(), emptyList(), "시작 가능")
        var unavailable: WalkSessionReadinessRequirement? = null; var captureAction: (() -> Unit)? = null
        val lifecycle = WalkSessionLifecycle({ "startup-flow" }, { now }, { now }).apply {
            handle(WalkSessionEvent.EnteredForeground)
            if (active) {
                handle(WalkSessionEvent.InitialCheckCompleted(readiness(this)))
                handle(WalkSessionEvent.StartRequested(requireNotNull(snapshot().confirmationToken)))
            }
        }
        val evidence = StartupCapabilityReadinessEvidence({ decision }, { events += "record-version"; "v1" }, { events += "record-clock"; now })
        val controller = StartupCapabilityCoordinator(object : StartupCapabilityEvaluation {
            override val metricOverride: Boolean? = null
            override val quickStart = false
            override val bypass = false
            override val checkPassesFeatureGate get() = checkPasses
            override fun probeDecision(metricOverride: Boolean?) = decision.also { events += "probe" }
            override fun disabledFeatures() = disabled.also { events += "disabled" }
            override fun activityRecognitionPermission() = true.also { events += "permission" }
            override fun disabledFeatureText() = "제한 기능"
        }, { evidence }, { lifecycle }, object : StartupCapabilityConfirmation {
            override var confirmed: WalkSafeStartupCapabilityDecision?
                get() = this@Fixture.confirmed
                set(value) { this@Fixture.confirmed = value }
            override var pending: Boolean
                get() = this@Fixture.pending
                set(value) { this@Fixture.pending = value }
            override var retryRequiresUserAction: Boolean
                get() = retry
                set(value) { retry = value }
            override fun isConfirmed() = (confirmed == decision).also { events += "confirmed" }
        }, object : StartupCapabilityUi {
            override fun ready() = uiReady.also { events += "ui-ready" }
            override fun persist(decision: WalkSafeStartupCapabilityDecision) { assertSame(decision, evidence.currentDecision); events += "persist" }
            override fun priority(decision: WalkSafeStartupCapabilityDecision) = PriorityUserOnboardingDecision(
                PriorityUserSupportPriority.EQUAL_BLIND_AND_LOW_VISION, null, null, emptyList(), false).also { events += "priority" }
            override fun updateOfficialEnvironment() { events += "official-ui" }
            override fun officialReadiness(epoch: WalkRuntimeEpoch) = WalkSessionReadinessStatus.READY.also { events += "official-readiness" }
            override fun mountingReadiness(epoch: WalkRuntimeEpoch) = WalkSessionReadinessStatus.READY.also { events += "mount-readiness" }
            override fun render(decision: WalkSafeStartupCapabilityDecision, confirmed: Boolean, priority: PriorityUserOnboardingDecision,
                officialReady: Boolean, mountingReady: Boolean) { assertTrue(officialReady); assertTrue(mountingReady); events += "render" }
            override fun updatePrewalk(epoch: WalkRuntimeEpoch, officialReady: Boolean, mountingReady: Boolean) { events += "prewalk" }
            override fun updatePriority(decision: WalkSafeStartupCapabilityDecision) { events += "priority-ui" }
            override fun updateFirstRun() { events += "first-run" }
            override fun updateMounting() { events += "mount-ui" }
            override fun updateFieldLog() { events += "field-log" }
            override fun applyActionButtons() { events += "buttons" }
            override fun advanceAfterCheck() { events += "advance" }
            override fun updateSafetySummary() { events += "summary" }
        }, object : ActiveReadinessPort {
            override val debug = false
            override fun capture(decision: WalkSafeStartupCapabilityDecision): WalkSessionReadinessSnapshot {
                events += "capture"; captureAction?.invoke(); return readiness(lifecycle, unavailable)
            }
            override fun transition(event: WalkSessionEvent) = lifecycle.handle(event).also { events += "transition" }
            override fun diagnostic(message: String) { events += "diagnostic" }
            override fun persistInterruption() { events += "persist-interruption" }
            override fun clearResume() { events += "clear-resume" }
            override fun invalidateEnvironment(reason: String) { assertEquals("runtime_readiness_changed", reason); events += "invalidate-environment" }
            override fun invalidateMounting(reason: String) { events += "invalidate-mount" }
            override fun cancelOutputs(reason: String) { events += "cancel-outputs" }
            override fun stopCameraFallback() { events += "stop-camera" }
            override fun stopDepth() { events += "stop-depth" }
            override fun navigationStatus(message: String) { events += "navigation-status" }
            override fun status(title: String, detail: String) { events += "status" }
            override fun speak(message: String) { spoken += message; events += "speak" }
        }, "한국어 음성 제한")
        companion object {
            fun readiness(lifecycle: WalkSessionLifecycle, unavailable: WalkSessionReadinessRequirement? = null): WalkSessionReadinessSnapshot {
                val collector = WalkSessionReadinessCollector(lifecycle.snapshot().epoch,
                    WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
                collector.plan.requiredRequirements.forEach { requirement -> collector.record(WalkSessionReadinessObservation(
                    lifecycle.snapshot().epoch, requirement,
                    if (requirement == unavailable) WalkSessionReadinessStatus.UNAVAILABLE else WalkSessionReadinessStatus.READY,
                    if (requirement == unavailable) "permission_missing" else "",
                )) }
                return collector.build(1_000L)
            }
        }
    }
}
