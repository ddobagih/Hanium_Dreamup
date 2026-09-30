package kr.co.hanium.dreamup.walksafe.device

import com.google.ar.core.ArCoreApk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStartupCapabilityProbeArCoreResultTest {
    @Test
    fun availabilityMappingKeepsExistingNullableDistanceValues() {
        val cases: Map<ArCoreApk.Availability?, Boolean?> = linkedMapOf(
            ArCoreApk.Availability.SUPPORTED_INSTALLED to null,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD to null,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED to null,
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE to false,
            ArCoreApk.Availability.UNKNOWN_CHECKING to null,
            ArCoreApk.Availability.UNKNOWN_ERROR to null,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT to null,
            null to null,
        )

        for ((availability, expected) in cases) {
            assertEquals(
                availability?.name ?: "null",
                expected,
                resolveRuntimeMetricDistanceAvailability(availability),
            )
        }
    }

    @Test
    fun failureMappingDistinguishesArCoreErrorAndTimeout() {
        assertEquals(
            WalkSafeMetricDistanceProbeFailure.ARCORE_ERROR,
            failureFor(ArCoreApk.Availability.UNKNOWN_ERROR),
        )
        assertEquals(
            WalkSafeMetricDistanceProbeFailure.ARCORE_TIMED_OUT,
            failureFor(ArCoreApk.Availability.UNKNOWN_TIMED_OUT),
        )
    }

    @Test
    fun queryExceptionMapsToCategoryWithoutExceptionText() {
        val exceptionText = "private probe exception detail"
        val failure = resolveRuntimeMetricDistanceProbeFailure(
            Result.failure<ArCoreApk.Availability?>(IllegalStateException(exceptionText)),
        )
        val decision = WalkSafeStartupCapabilityResolver.resolve(
            readyInput().copy(metricDistanceAvailable = null, metricDistanceProbeFailure = failure),
        )

        assertEquals(WalkSafeMetricDistanceProbeFailure.QUERY_EXCEPTION, failure)
        assertTrue(decision.noticeKo.contains("지원 조회에 실패했습니다."))
        assertFalse(decision.noticeKo.contains(exceptionText))
    }

    @Test
    fun checkingSupportedUnsupportedAndMissingResultsHaveNoFailureCause() {
        val cases = listOf(
            ArCoreApk.Availability.UNKNOWN_CHECKING,
            ArCoreApk.Availability.SUPPORTED_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE,
            null,
        )

        for (availability in cases) {
            assertNull(failureFor(availability))
        }
    }

    @Test
    fun defaultFailureFieldPreservesLegacyPendingNotice() {
        val input = readyInput().copy(metricDistanceAvailable = null)
        val decision = WalkSafeStartupCapabilityResolver.resolve(input)

        assertNull(input.metricDistanceProbeFailure)
        assertEquals("기기 기능을 확인하는 중입니다: 미터 단위 거리 측정.", decision.noticeKo)
        assertEquals(listOf(WalkSafeStartupRequirement.METRIC_DISTANCE), decision.pendingRequirements)
        assertEquals(WalkSafeStartupCapabilityTier.BLOCKED, decision.tier)
        assertFalse(decision.mayConfirmAndStart)
    }

    @Test
    fun arCoreErrorPendingNoticeNamesError() {
        assertPendingFailureNotice(
            failureFor(ArCoreApk.Availability.UNKNOWN_ERROR),
            "ARCore 지원 조회 오류가 발생했습니다.",
        )
    }

    @Test
    fun timeoutPendingNoticeNamesTimeout() {
        assertPendingFailureNotice(
            failureFor(ArCoreApk.Availability.UNKNOWN_TIMED_OUT),
            "ARCore 지원 조회 시간이 초과되었습니다.",
        )
    }

    @Test
    fun exceptionPendingNoticeNamesQueryFailure() {
        assertPendingFailureNotice(
            resolveRuntimeMetricDistanceProbeFailure(
                Result.failure<ArCoreApk.Availability?>(IllegalArgumentException("not exposed")),
            ),
            "지원 조회에 실패했습니다.",
        )
    }

    @Test
    fun failureNoticePreservesOtherPendingAndUnavailableDetails() {
        val input = readyInput().copy(
            metricDistanceAvailable = null,
            gpsAvailable = null,
            microphoneAvailable = false,
        )
        val baseline = WalkSafeStartupCapabilityResolver.resolve(input)

        for (failure in WalkSafeMetricDistanceProbeFailure.values()) {
            val decision = WalkSafeStartupCapabilityResolver.resolve(
                input.copy(metricDistanceProbeFailure = failure),
            )
            assertSameEligibility(baseline, decision)
            assertTrue(decision.noticeKo.startsWith(baseline.noticeKo))
            assertTrue(decision.noticeKo.contains("GPS 위치, 미터 단위 거리 측정"))
            assertTrue(decision.noticeKo.contains("확인된 미지원 기능: 마이크"))
            assertTrue(decision.noticeKo.length > baseline.noticeKo.length)
        }
    }

    @Test
    fun nonnullEffectiveMetricOverridesSuppressOldFailureDuringOtherPending() {
        for (failure in WalkSafeMetricDistanceProbeFailure.values()) {
            val input = readyInput().copy(
                metricDistanceAvailable = null,
                gpsAvailable = null,
                metricDistanceProbeFailure = failure,
            )
            for (override in listOf(true, false)) {
                val metricDistanceOverride: Boolean? = override
                // The public resolver receives the existing decision() effective-input contract.
                val effectiveInput = input.copy(
                    metricDistanceAvailable = metricDistanceOverride ?: input.metricDistanceAvailable,
                )
                val baseline = WalkSafeStartupCapabilityResolver.resolve(
                    effectiveInput.copy(metricDistanceProbeFailure = null),
                )
                val decision = WalkSafeStartupCapabilityResolver.resolve(effectiveInput)

                assertEquals(baseline, decision)
                assertFalse(decision.pendingRequirements.contains(WalkSafeStartupRequirement.METRIC_DISTANCE))
                assertEquals(
                    !override,
                    decision.unavailableRequirements.contains(WalkSafeStartupRequirement.METRIC_DISTANCE),
                )
            }
        }
    }

    @Test
    fun nullEffectiveMetricOverrideKeepsPendingFailureNotice() {
        val metricDistanceOverride: Boolean? = null
        for (failure in WalkSafeMetricDistanceProbeFailure.values()) {
            val input = readyInput().copy(
                metricDistanceAvailable = null,
                metricDistanceProbeFailure = failure,
            )
            val effectiveInput = input.copy(
                metricDistanceAvailable = metricDistanceOverride ?: input.metricDistanceAvailable,
            )
            val decision = WalkSafeStartupCapabilityResolver.resolve(effectiveInput)

            assertEquals(WalkSafeStartupCapabilityResolver.resolve(input), decision)
            assertTrue(decision.noticeKo.length > LEGACY_DISTANCE_PENDING_NOTICE.length)
        }
    }

    @Test
    fun resolvedMetricStatesPreserveFullAndDistanceOnlyLimitedNotices() {
        for (metricAvailable in listOf(true, false)) {
            val input = readyInput().copy(metricDistanceAvailable = metricAvailable)
            val baseline = WalkSafeStartupCapabilityResolver.resolve(input)
            for (failure in WalkSafeMetricDistanceProbeFailure.values()) {
                val decision = WalkSafeStartupCapabilityResolver.resolve(
                    input.copy(metricDistanceProbeFailure = failure),
                )
                assertEquals(baseline, decision)
                assertEquals(
                    if (metricAvailable) WalkSafeStartupCapabilityTier.FULL else WalkSafeStartupCapabilityTier.LIMITED,
                    decision.tier,
                )
                assertTrue(decision.mayConfirmAndStart)
                if (!metricAvailable) assertEquals(WALKSAFE_LIMITED_DISTANCE_NOTICE_KO, decision.noticeKo)
            }
        }
    }

    @Test
    fun unsupportedAndroidPriorityIgnoresMetricFailureNotice() {
        val input = readyInput().copy(androidVersionSupported = false, metricDistanceAvailable = null)
        val baseline = WalkSafeStartupCapabilityResolver.resolve(input)
        for (failure in WalkSafeMetricDistanceProbeFailure.values()) {
            assertEquals(
                baseline,
                WalkSafeStartupCapabilityResolver.resolve(input.copy(metricDistanceProbeFailure = failure)),
            )
        }
        assertEquals(WalkSafeStartupCapabilityTier.BLOCKED, baseline.tier)
        assertTrue(baseline.noticeKo.startsWith("지원 Android 버전이 아니어서"))
    }

    @Test
    fun unavailableOfflineTtsPriorityIgnoresMetricFailureNotice() {
        val input = readyInput().copy(
            offlineKoreanTextToSpeechAvailable = false,
            metricDistanceAvailable = null,
            gpsAvailable = null,
        )
        val baseline = WalkSafeStartupCapabilityResolver.resolve(input)
        for (failure in WalkSafeMetricDistanceProbeFailure.values()) {
            assertEquals(
                baseline,
                WalkSafeStartupCapabilityResolver.resolve(input.copy(metricDistanceProbeFailure = failure)),
            )
        }
        assertEquals(WalkSafeStartupCapabilityTier.BLOCKED, baseline.tier)
        assertTrue(baseline.noticeKo.startsWith("오프라인 한국어 음성 안내를 사용할 수 없어"))
        assertTrue(baseline.noticeKo.contains("확인 중인 기능: GPS 위치, 미터 단위 거리 측정."))
    }

    @Test
    fun requiredOptionalAndDefaultProfilePoliciesRemainUnchanged() {
        val profileCases: List<Pair<Boolean?, String?>> = listOf(
            true to "test-profile",
            true to null,
            false to null,
            null to null,
        )
        for ((profileApproved, profileVersion) in profileCases) {
            val input = readyInput().copy(
                metricDistanceAvailable = null,
                approvedDesignatedDeviceProfile = profileApproved,
                designatedDeviceProfileVersion = profileVersion,
            )
            val failedInput = input.copy(metricDistanceProbeFailure = WalkSafeMetricDistanceProbeFailure.ARCORE_ERROR)
            assertEquals(
                WalkSafeStartupCapabilityResolver.resolve(failedInput, approvedDeviceProfileRequired = true),
                WalkSafeStartupCapabilityResolver.resolve(failedInput),
            )
            for (profileRequired in listOf(true, false)) {
                val baseline = WalkSafeStartupCapabilityResolver.resolve(input, profileRequired)
                val decision = WalkSafeStartupCapabilityResolver.resolve(failedInput, profileRequired)
                assertSameEligibility(baseline, decision)
                assertTrue(decision.noticeKo.startsWith(baseline.noticeKo))
                assertTrue(decision.noticeKo.endsWith("ARCore 지원 조회 오류가 발생했습니다."))
                if (!profileRequired) {
                    assertFalse(decision.pendingRequirements.contains(WalkSafeStartupRequirement.APPROVED_DEVICE_PROFILE))
                    assertFalse(decision.unavailableRequirements.contains(WalkSafeStartupRequirement.APPROVED_DEVICE_PROFILE))
                }
            }
        }
    }

    private fun failureFor(availability: ArCoreApk.Availability?): WalkSafeMetricDistanceProbeFailure? =
        resolveRuntimeMetricDistanceProbeFailure(Result.success(availability))

    private fun assertPendingFailureNotice(failure: WalkSafeMetricDistanceProbeFailure?, expectedFact: String) {
        val baselineInput = readyInput().copy(metricDistanceAvailable = null)
        val baseline = WalkSafeStartupCapabilityResolver.resolve(baselineInput)
        val decision = WalkSafeStartupCapabilityResolver.resolve(
            baselineInput.copy(metricDistanceProbeFailure = failure),
        )
        assertSameEligibility(baseline, decision)
        assertEquals("$LEGACY_DISTANCE_PENDING_NOTICE 미터 단위 거리 측정: $expectedFact", decision.noticeKo)
    }

    private fun assertSameEligibility(
        expected: WalkSafeStartupCapabilityDecision,
        actual: WalkSafeStartupCapabilityDecision,
    ) {
        assertEquals(expected.tier, actual.tier)
        assertEquals(expected.pendingRequirements, actual.pendingRequirements)
        assertEquals(expected.unavailableRequirements, actual.unavailableRequirements)
        assertEquals(expected.mayConfirmAndStart, actual.mayConfirmAndStart)
    }

    // Synthetic resolver fixture, not an approved operating-device profile.
    private fun readyInput() = WalkSafeStartupCapabilityInput(
        androidVersionSupported = true,
        cameraAvailable = true,
        gpsAvailable = true,
        microphoneAvailable = true,
        vibrationAvailable = true,
        onDeviceSpeechRecognitionAvailable = true,
        offlineKoreanTextToSpeechAvailable = true,
        metricDistanceAvailable = true,
        approvedDesignatedDeviceProfile = true,
        designatedDeviceProfileVersion = "test-profile",
    )

    private companion object {
        const val LEGACY_DISTANCE_PENDING_NOTICE = "기기 기능을 확인하는 중입니다: 미터 단위 거리 측정."
    }
}
