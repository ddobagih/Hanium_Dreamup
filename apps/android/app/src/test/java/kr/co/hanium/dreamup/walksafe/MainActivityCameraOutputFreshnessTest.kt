package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.device.ApprovedCameraFrameQualityProfile
import kr.co.hanium.dreamup.walksafe.device.CameraDetectorAdmissionPolicy
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityPolicy
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Session continuation grace cannot stand in for a current raw camera output permission. */
class MainActivityCameraOutputFreshnessTest {
    private val epoch = WalkRuntimeEpoch("walk-1", 1L)
    // The existing camera admission fixture, not an approved production profile or a new threshold.
    private val profile = ApprovedCameraFrameQualityProfile("camera-field-approved-r001", 0.4, 0.2, 8.0, 25.0, 65.0, 2_000L)
    private fun observation(at: Long) = CameraFrameQualityObservation(epoch, at, true, 0.4, 0.2, 8.0, 25.0)

    @Test
    fun aTransientOcclusionKeepsSessionGraceButCannotAuthorizeANewCameraOutput() {
        val main = MainActivity()
        var invocations = 0
        val first = CameraDetectorAdmissionPolicy.admit(epoch, 10_000L, observation(10_000L), profile) { invocations++ }
        val grace = RuntimeMeasuredQualityGate()
        val previous = grace.observe(first.frameQuality.asMeasuredEnvironmentEvidence(), 10_000L)
        assertTrue(main.rawCameraAssessmentAllowsNewOutput(first.frameQuality, epoch, profile, 10_000L))
        val covered = CameraDetectorAdmissionPolicy.admit(epoch, 10_100L,
            observation(10_100L).copy(occludedFraction = 0.21), profile) { invocations++ }
        assertFalse(covered.detectorInvocationAllowed)
        assertEquals(EnvironmentEvidenceStatus.FAIL, covered.frameQuality.status)
        assertEquals(previous, grace.observe(covered.frameQuality.asMeasuredEnvironmentEvidence(), 10_100L))
        assertFalse(main.rawCameraAssessmentAllowsNewOutput(covered.frameQuality, epoch, profile, 10_100L))
        assertEquals(1, invocations)
        val recovered = CameraFrameQualityPolicy.assess(epoch, 10_200L, observation(10_200L), profile)
        assertTrue(main.rawCameraAssessmentAllowsNewOutput(recovered, epoch, profile, 10_200L))
    }

    @Test
    fun rawPermissionUsesTheOriginalEpochProfileAndMeasurementExpiry() {
        val main = MainActivity()
        val measured = CameraFrameQualityPolicy.assess(epoch, 10_000L, observation(10_000L), profile)
        assertTrue(main.rawCameraAssessmentAllowsNewOutput(measured, epoch, profile, 12_000L))
        assertFalse(main.rawCameraAssessmentAllowsNewOutput(measured, epoch, profile, 12_001L))
        assertFalse(main.rawCameraAssessmentAllowsNewOutput(measured, epoch, profile, 9_999L))
        assertFalse(main.rawCameraAssessmentAllowsNewOutput(measured, epoch.copy(recoveryGeneration = 2L), profile, 10_000L))
        assertFalse(main.rawCameraAssessmentAllowsNewOutput(measured, epoch, profile.copy(profileId = "different-profile"), 10_000L))
        assertFalse(main.rawCameraAssessmentAllowsNewOutput(measured.copy(maximumEvidenceAgeMs = 20_000L), epoch, profile, 10_000L))
        assertFalse(main.rawCameraAssessmentAllowsNewOutput(null, epoch, profile, 10_000L))
        assertFalse(main.rawCameraAssessmentAllowsNewOutput(measured, epoch, null, 10_000L))
        val instantaneousProfile = profile.copy(maximumEvidenceAgeMs = 0L)
        val instantaneous = CameraFrameQualityPolicy.assess(epoch, 10_000L, observation(10_000L), instantaneousProfile)
        assertTrue(main.rawCameraAssessmentAllowsNewOutput(instantaneous, epoch, instantaneousProfile, 10_000L))
        assertFalse(main.rawCameraAssessmentAllowsNewOutput(instantaneous, epoch, instantaneousProfile, 10_001L))
    }

    @Test
    fun anUnknownTimestampCannotEraseTheLatestValidRawFailure() = assertUnknownDoesNotEraseFailure(null)

    @Test
    fun aFutureTimestampCannotEraseTheLatestValidRawFailure() = assertUnknownDoesNotEraseFailure(20_000L)

    private fun assertUnknownDoesNotEraseFailure(invalidAt: Long?) {
        val main = MainActivity()
        val failed = CameraFrameQualityPolicy.assess(epoch, 10_100L,
            observation(10_100L).copy(occludedFraction = 0.21), profile)
        val unknown = failed.copy(observedAtElapsedRealtimeMs = invalidAt,
            status = EnvironmentEvidenceStatus.UNKNOWN,
            reason = kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityReason.STALE_OR_INVALID_TIMESTAMP)
        main.observeRuntimeRawCameraAssessment(failed, 10_100L)
        main.observeRuntimeRawCameraAssessment(unknown, 10_120L)
        val late = CameraFrameQualityPolicy.assess(epoch, 10_120L, observation(10_050L), profile)
        main.observeRuntimeRawCameraAssessment(late, 10_120L)
        val field = MainActivityControllerTestFixture.field("latestRuntimeRawCameraAssessment").apply { isAccessible = true }
        assertFalse("Invalid receipt cannot erase a valid source watermark",
            main.rawCameraAssessmentAllowsNewOutput(field.get(main) as kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment, epoch, profile, 10_120L))
        val recovered = CameraFrameQualityPolicy.assess(epoch, 10_200L, observation(10_200L), profile)
        main.observeRuntimeRawCameraAssessment(recovered, 10_200L)
        assertTrue("A valid newer PASS must recover even after a future/null observation",
            main.rawCameraAssessmentAllowsNewOutput(field.get(main) as kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment, epoch, profile, 10_200L))
    }

    @Test
    fun lateOrSameTimestampPassCannotReplaceTheLatestRawFailure() {
        for (lateAt in listOf(10_050L, 10_100L)) {
            val main = MainActivity()
            val first = CameraFrameQualityPolicy.assess(epoch, 10_000L, observation(10_000L), profile)
            val covered = CameraFrameQualityPolicy.assess(epoch, 10_100L,
                observation(10_100L).copy(occludedFraction = 0.21), profile)
            val latePass = CameraFrameQualityPolicy.assess(epoch, 10_120L, observation(lateAt), profile)
            main.observeRuntimeRawCameraAssessment(first, 10_000L)
            main.observeRuntimeRawCameraAssessment(covered, 10_100L)
            main.observeRuntimeRawCameraAssessment(latePass, 10_120L)
            val field = MainActivityControllerTestFixture.field("latestRuntimeRawCameraAssessment").apply { isAccessible = true }
            val latest = field.get(main) as kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment
            assertFalse("Older or same-time PASS cannot undo a newer camera cover",
                main.rawCameraAssessmentAllowsNewOutput(latest, epoch, profile, 10_120L))
            assertEquals(covered, latest)
        }
    }

    @Test
    fun newerRawPassRestoresOutputAndSameTimeFailureCanRevokeIt() {
        val main = MainActivity()
        val failed = CameraFrameQualityPolicy.assess(epoch, 10_100L,
            observation(10_100L).copy(occludedFraction = 0.21), profile)
        val recovered = CameraFrameQualityPolicy.assess(epoch, 10_200L, observation(10_200L), profile)
        main.observeRuntimeRawCameraAssessment(failed, 10_100L)
        assertTrue(main.observeRuntimeRawCameraAssessment(recovered, 10_200L))
        val field = MainActivityControllerTestFixture.field("latestRuntimeRawCameraAssessment").apply { isAccessible = true }
        assertTrue(main.rawCameraAssessmentAllowsNewOutput(field.get(main) as kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment, epoch, profile, 10_200L))
        val sameTimeFailure = recovered.copy(status = EnvironmentEvidenceStatus.FAIL)
        main.observeRuntimeRawCameraAssessment(sameTimeFailure, 10_200L)
        assertFalse(main.rawCameraAssessmentAllowsNewOutput(field.get(main) as kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment, epoch, profile, 10_200L))
    }

    @Test
    fun aNewOwnerProfileOrExplicitResetDoesNotInheritOldRawOrdering() {
        val main = MainActivity()
        val failed = CameraFrameQualityPolicy.assess(epoch, 10_100L,
            observation(10_100L).copy(occludedFraction = 0.21), profile)
        main.observeRuntimeRawCameraAssessment(failed, 10_100L)
        val nextEpoch = epoch.copy(recoveryGeneration = 2L)
        val nextProfile = profile.copy(profileId = "different-profile")
        val replacement = CameraFrameQualityPolicy.assess(nextEpoch, 10_120L,
            observation(10_050L).copy(epoch = nextEpoch), nextProfile)
        main.observeRuntimeRawCameraAssessment(replacement, 10_120L)
        val field = MainActivityControllerTestFixture.field("latestRuntimeRawCameraAssessment").apply { isAccessible = true }
        assertTrue(main.rawCameraAssessmentAllowsNewOutput(field.get(main) as kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment, nextEpoch, nextProfile, 10_120L))
        main.resetRuntimeRawCameraAssessment()
        assertNull(field.get(main))
        val restarted = CameraFrameQualityPolicy.assess(nextEpoch, 10_120L,
            observation(10_040L).copy(epoch = nextEpoch), nextProfile)
        main.observeRuntimeRawCameraAssessment(restarted, 10_120L)
        assertTrue(main.rawCameraAssessmentAllowsNewOutput(field.get(main) as kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment, nextEpoch, nextProfile, 10_120L))
    }

    @Test
    fun distanceLimitedDirectionAdvisoriesUseTheCurrentRawFrameAtStart() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val delivery = MainActivityRefactorSource.property(source, "cameraAdvisoryDelivery")
        assertTrue(delivery.contains("CameraNonMetricAdvisoryDeliveryController("))
        val authority = ReportStaticSourceInspector.blockAfter(delivery, "authority = object : CameraAdvisoryDeliveryAuthority")
        assertTrue("A transient cover must not authorize a queued left/right camera advisory",
            authority.contains("override fun newOutputAllowed(nowMs: Long) =") &&
                authority.contains("!isGuidanceVoiceInputActive() && currentRawCameraFrameAllowsNewOutput(nowMs)"))
        assertTrue(delivery.contains("speechOutputController.speakAdvisory(message, deadline, current, delivered, failed)"))
        assertTrue(MainActivityRefactorSource.factory(source, "createPrimaryCameraFallbackDetectorWorker")
            .contains("cameraAdvisoryDelivery.dispatch(action, epoch, generation, deliveryOwner)"))
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/delivery/CameraNonMetricAdvisoryDeliveryController.kt").readText()
        val current = MainActivityRefactorSource.member(owner, "stillDeliverable")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(current,
            "val nowMs = elapsedRealtimeMs()", "nowMs <= action.validUntilMs", "authority.newOutputAllowed(nowMs)", "authority.ownerCurrent("))
        val dispatch = MainActivityRefactorSource.member(owner, "dispatch")
        val atStart = ReportStaticSourceInspector.blockAfter(dispatch, "current = ")
        assertTrue(atStart.contains("stillDeliverable(action, epoch, fallbackGeneration, lifecycleGeneration) &&"))
        assertTrue(atStart.contains("policy.isDeliveryCurrent(action)"))
    }

    @Test
    fun metricAndQualitativeConsumersCheckTheCurrentRawFrameWithoutBlockingCameraAcquisition() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        fun function(name: String) = source.substringAfter("private fun $name(").substringBefore("private fun ")
        assertTrue(function("currentFeedbackDeviceGateAllowsAlerts").contains("currentRawCameraFrameAllowsNewOutput("))
        assertTrue(function("currentArCoreQualitativeFeedbackAllowsWork").contains("currentRawCameraFrameAllowsNewOutput("))
        assertTrue(function("buildDeviceGateState").contains("currentRawCameraFrameAllowsNewOutput("))
        assertFalse("A current-frame permission must not block acquisition of the first or recovering frame",
            function("currentRuntimeMetricOutputAllowsWork").contains("currentRawCameraFrameAllowsNewOutput("))
    }
}
