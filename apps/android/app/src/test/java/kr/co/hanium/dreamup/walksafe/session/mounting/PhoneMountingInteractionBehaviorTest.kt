package kr.co.hanium.dreamup.walksafe.session.mounting

import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

internal inline fun <reified T> interactionPort(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> invoke(method.name, args.orEmpty()) } as T

class PhoneMountingInteractionBehaviorTest {
    @Test fun constructorHasNoLiveQueriesAndReusesActualSessionConfirmationIdentity() {
        val f = MountInteractionFixture(); assertTrue(f.events.isEmpty()); assertEquals(0, f.sessionReads); f.ready()
        f.controller.confirmPhoneMounting(PhoneMountingMethod.CHEST_FORWARD)
        assertSame(f.domain.mount.confirmation, f.domain.mount.explicitConfirmation)
        assertSame(f.domain.mount.confirmation, f.domain.consumed!!.facts.confirmation)
        assertFalse(f.domain.mount.outputsAllowed)
        assertTrue(f.events.indexOf("capture") < f.events.indexOf("consume"))
        assertTrue(f.events.indexOf("consume") < f.events.indexOf("startCamera"))
    }
    @Test fun bypassAndBackgroundStopBeforeConfirmationOrCameraQueries() {
        val f = MountInteractionFixture(); f.bypass = true
        f.controller.confirmPhoneMounting(PhoneMountingMethod.CHEST_FORWARD)
        assertEquals(listOf("developmentBypassEnabled"), f.events)
        assertEquals(0, f.sessionReads)
        f.events.clear(); f.bypass = false; f.foreground = false
        f.controller.confirmPhoneMounting(PhoneMountingMethod.CHEST_FORWARD)
        assertEquals(listOf("developmentBypassEnabled", "isForeground"), f.events)
        assertNull(f.domain.mount.confirmation)
    }
    @Test fun activeWalkWithoutCorrectionCannotAcceptNewMountingConfirmation() {
        val f = MountInteractionFixture()
        f.controller.confirmPhoneMounting(PhoneMountingMethod.CHEST_FORWARD)
        assertTrue(f.statuses.single().first.contains("확인 불가"))
        assertFalse(f.events.contains("officialReadiness")); assertNull(f.domain.mount.confirmation)
    }
    @Test fun environmentFailureFocusesItsCheckWithoutCapturingConfirmation() {
        val f = MountInteractionFixture(); f.ready(); f.officialReady = false
        f.controller.confirmPhoneMounting(PhoneMountingMethod.CHEST_FORWARD)
        assertTrue(f.events.contains("postFocusEnvironmentConfirmation"))
        assertNull(f.domain.mount.confirmation); assertFalse(f.events.contains("cameraActive"))
    }
    @Test fun pendingCameraReleaseReentersAllCurrentGuardsBeforeRecording() {
        val f = MountInteractionFixture(); f.ready(); f.cameraActive = true
        f.controller.confirmPhoneMounting(PhoneMountingMethod.CHEST_FORWARD)
        assertNotNull(f.release); assertNull(f.domain.mount.confirmation)
        f.foreground = false; f.cameraActive = false; f.release!!()
        assertNull(f.domain.mount.confirmation); assertFalse(f.events.contains("startCamera"))
    }
    @Test fun freshPrewalkConfirmationResetsEvidenceBeforeCaptureAndKeepsGpsEvidence() {
        val f = MountInteractionFixture(); f.ready()
        f.controller.confirmPhoneMounting(PhoneMountingMethod.CHEST_FORWARD)
        assertTrue(f.events.indexOf("preflight_reset_locked") < f.events.indexOf("capture"))
        assertTrue(f.events.indexOf("resetReadyGrace") < f.events.indexOf("capture"))
        assertEquals(false, f.gpsClearRequested)
        assertTrue(f.spoken.single().contains("측정 기준도 통과해야"))
    }
    @Test fun failedCameraAdmissionMarksPreflightFailedAndDoesNotInventQualityPass() {
        val f = MountInteractionFixture(); f.ready(); f.cameraStarts = false
        f.controller.confirmPhoneMounting(PhoneMountingMethod.CHEST_FORWARD)
        assertTrue(f.events.contains("markFailed")); assertFalse(f.events.contains("requestGps"))
        assertNotNull(f.domain.mount.confirmation); assertFalse(f.domain.mount.outputsAllowed)
    }
    @Test fun activeCorrectionArmsOneRetryWithoutResettingPrewalkEvidenceOrStartingPreflight() {
        val f = MountInteractionFixture(); f.correction()
        f.controller.confirmPhoneMounting(PhoneMountingMethod.CHEST_FORWARD)
        assertEquals(f.domain.now, f.domain.mount.runtimeRetryArmedAt())
        val confirmed = f.domain.mount.confirmation
        assertTrue(confirmed!!.postFaultCorrectionConfirmed)
        assertFalse(f.events.contains("resetReadyGrace")); assertFalse(f.events.contains("startCamera"))
        assertFalse(f.button.enabled); assertTrue(f.button.text.contains("재검사 중"))
        f.events.clear(); f.domain.now += 100L
        f.controller.confirmPhoneMounting(PhoneMountingMethod.CHEST_FORWARD)
        assertSame(confirmed, f.domain.mount.confirmation); assertFalse(f.events.contains("capture"))
    }

    @Test fun nativeStartRequestDoesNotHideLaterExplicitCorrectionAndFreshCameraPass() {
        val f = MountInteractionFixture()
        val d = f.domain
        d.nativeCheck = PhoneMountingCheckRequest(d.epoch, 1L, 700L)
        d.makeSuitable()
        val originalRequest = d.nativeCheck
        fun camera(at: Long, reason: CameraFrameQualityReason) {
            d.now = at
            val value = d.camera(at).copy(reason = reason, status =
                if (reason == CameraFrameQualityReason.PASSED) EnvironmentEvidenceStatus.PASS else EnvironmentEvidenceStatus.FAIL)
            val sequence = synchronized(d.lock) {
                d.mount.commitCameraAssessment(d.epoch, value, CameraPreflightStabilityDecision.ACCEPT,
                    reason != CameraFrameQualityReason.PASSED)
            }
            val generation = d.mount.observationGeneration()
            val armedAt = d.mount.runtimeRetryArmedAt()
            val retry = armedAt != null && at > armedAt
            assertTrue("Current camera observation must reach the active mounting policy at $at",
                d.mount.applyRuntimeCameraAssessment(retry, value, sequence, generation,
                    reason != CameraFrameQualityReason.PASSED))
            if (retry) synchronized(d.lock) { d.mount.finishRuntimeRetryIfCurrent(armedAt) }
            if (reason != CameraFrameQualityReason.PASSED) synchronized(d.lock) {
                d.mount.recordAppliedFaultIfCurrent(generation, sequence)
            }
        }
        camera(1_100L, CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE)
        assertTrue(d.mount.runtimeState!!.requiresExplicitCorrection)
        assertFalse(d.mount.outputsAllowed)
        camera(1_150L, CameraFrameQualityReason.PASSED)
        assertFalse("A fresh frame alone cannot clear a real mounting fault", d.mount.outputsAllowed)
        d.now = 1_200L
        f.controller.confirmPhoneMounting(PhoneMountingMethod.CHEST_FORWARD)
        assertTrue(d.mount.confirmation!!.postFaultCorrectionConfirmed)
        camera(1_200L, CameraFrameQualityReason.PASSED)
        assertFalse("A frame at confirmation time is not a post-correction observation", d.mount.outputsAllowed)
        camera(1_201L, CameraFrameQualityReason.PASSED)
        assertTrue("The original native start must accept the user's later correction and new measured PASS", d.mount.outputsAllowed)
        assertNull(d.mount.runtimeState!!.correctionRequiredSinceElapsedRealtimeMs)
        assertSame(originalRequest, d.nativeCheck)
    }
    @Test fun unpublishedStatusStopsBeforeOtherViewOrLifecycleQueries() {
        val f = MountInteractionFixture(); f.published = false
        f.controller.updatePhoneMountingUi()
        assertEquals(listOf("statusPublished"), f.events)
        assertEquals(0, f.sessionReads)
    }
    @Test fun bypassPresentationReadsSnapshotThenHidesWithoutAssessmentClockOrProfileQueries() {
        val f = MountInteractionFixture(); f.bypass = true
        f.controller.updatePhoneMountingUi()
        assertTrue(f.events.indexOf("snapshot") < f.events.indexOf("developmentBypassEnabled"))
        assertFalse(f.events.contains("clock")); assertFalse(f.events.contains("profileAvailable"))
        assertEquals(0, f.sessionReads)
        assertTrue(f.events.containsAll(listOf("hideStatus", "setChestVisible", "hideNecklace")))
    }
    @Test fun unchangedStatusAvoidsTextAndDescriptionWritesButRefreshesButtonState() {
        val f = MountInteractionFixture(); f.ready()
        f.controller.updatePhoneMountingUi(); assertTrue(f.events.contains("applyStatus"))
        f.events.clear(); f.controller.updatePhoneMountingUi()
        assertFalse(f.events.contains("applyStatus")); assertFalse(f.events.contains("setStatusDescription"))
        assertTrue(f.events.contains("chestButton")); assertTrue(f.button.text.contains("확인"))
        assertEquals(f.button.text, f.button.contentDescription)
    }
}

internal class MountInteractionFixture {
    val domain = MountInteractionSessionFixture(); val events get() = domain.events
    var bypass = false; var foreground = true; var officialReady = true; var cameraActive = false; var cameraStarts = true
    var published = true; var message = ""; var release: (() -> Unit)? = null; var gpsClearRequested: Boolean? = null
    val spoken = mutableListOf<String>(); val statuses = mutableListOf<Pair<String, String>>()
    val button = object : PhoneMountingButtonView { override var enabled = false; override var text: CharSequence = ""; override var contentDescription: CharSequence = "" }
    var sessionReads = 0
    val controller = PhoneMountingInteractionController({ sessionReads += 1; domain.mount },
        interactionPort<PhoneMountingInteractionAdmission> { name, args -> events += name; when(name) {
            "developmentBypassEnabled" -> bypass
            "isForeground" -> foreground
            "lifecycleAvailable", "requireOnboarding", "cameraEnabled", "locationFeatureEnabled", "profileAvailable", "onboardingComplete" -> true
            "snapshot" -> domain.lifecycle.snapshot()
            "isRuntimeEpochCurrent" -> domain.lifecycle.isRuntimeEpochCurrent(args[0] as WalkRuntimeEpoch)
            "officialReadiness" -> (if (officialReady) WalkSessionReadinessStatus.READY else WalkSessionReadinessStatus.PENDING) to ""
            "quickStartEnabled", "profileApprovedForProduction" -> false
            else -> error(name)
        } },
        interactionPort<PhoneMountingInteractionPreflight> { name, args -> events += name; when(name) {
            "cameraActive" -> cameraActive
            "cameraCleanupPending" -> false
            "stopCamera" -> { @Suppress("UNCHECKED_CAST") val action = args[0] as () -> Unit; release = action; Unit }
            "startCamera" -> cameraStarts
            "requestGps" -> { gpsClearRequested = args[1] as Boolean; Unit }
            "resetReadyGrace", "resetGuidanceProgress", "markFailed" -> Unit
            else -> error(name)
        } },
        interactionPort<PhoneMountingInteractionEffects> { name, args -> events += name; when(name) {
            "updateStatus" -> { statuses += (args[0] as String) to (args[1] as String); Unit }
            "speakInteraction" -> { spoken += args[0] as String; Unit }
            "refreshReadinessUi" -> Unit
            else -> error(name)
        } },
        interactionPort<PhoneMountingInteractionView> { name, args -> events += name; when(name) {
            "statusPublished", "chestPublished", "necklacePublished" -> published
            "statusText" -> message
            "applyStatus" -> { message = args[0] as String; Unit }
            "chestButton" -> button
            "postFocusEnvironmentConfirmation", "hideStatus", "setChestVisible", "hideNecklace", "setStatusDescription" -> Unit
            else -> error(name)
        } },
    )
    fun ready() { domain.lifecycle.handle(WalkSessionEvent.NewWalkRequested) }
    fun correction() {
        domain.makeSuitable(); val epoch = domain.epoch; val generation = domain.mount.observationGeneration()
        val bad = domain.camera(1_100L, EnvironmentEvidenceStatus.FAIL)
        val sequence = synchronized(domain.lock) { domain.mount.commitCameraAssessment(epoch, bad, CameraPreflightStabilityDecision.ACCEPT, true) }
        domain.now = 1_100L
        check(domain.mount.applyRuntimeCameraAssessment(false, bad, sequence, generation, true))
        events.clear()
    }
}
