package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationGuidancePresentationPolicyTest {
    private fun input(running: Boolean = false, preparing: Boolean = false, reason: String? = null) =
        GuidanceFeatureDisplayInput(running, preparing, reason)

    @Test
    fun noSensorOrRuntimeEvidenceStillProducesGuidancePreparation() {
        val result = DestinationGuidancePresentationPolicy.present(input(), input(), false)
        assertEquals(DestinationGuidanceDisplayStage.PREPARING, result.stage)
        assertEquals("안내 준비 중", result.titleKo)
        assertTrue(result.routeMessageKo.isNotBlank())
        assertTrue(result.cameraMessageKo.isNotBlank())
        assertFalse(result.retryAvailable)
    }

    @Test
    fun groundFacingCameraReasonRemainsVisibleWhileMeasurementsContinue() {
        val reason = "합성 fixture: 카메라가 아래를 향해 전방을 확인할 수 없습니다."
        val result = DestinationGuidancePresentationPolicy.present(
            input(preparing = true, reason = "합성 fixture: 정확한 위치 대기"),
            input(preparing = true, reason = reason),
            false,
        )
        assertEquals(DestinationGuidanceDisplayStage.PREPARING, result.stage)
        assertTrue(result.cameraMessageKo.contains(reason))
        assertFalse(result.cameraMessageKo.contains("실제 객체인식 안내를 사용할 수 있습니다."))
    }

    @Test
    fun oneUnavailableFeatureDoesNotHideTheOtherObservedFeature() {
        val result = DestinationGuidancePresentationPolicy.present(
            input(reason = "합성 fixture: 위치 정확도 부족"), input(running = true), true,
        )
        assertEquals(DestinationGuidanceDisplayStage.PARTIALLY_AVAILABLE, result.stage)
        assertEquals("카메라 안내 중", result.titleKo)
        assertTrue(result.cameraMessageKo.contains("실제 객체인식 안내를 사용할 수 있습니다."))
        assertTrue(result.routeMessageKo.contains("위치 정확도 부족"))
    }

    @Test
    fun actualBlockReasonTakesPrecedenceOverAnOldRunningFlag() {
        val result = DestinationGuidancePresentationPolicy.present(
            input(running = true, reason = "합성 fixture: 위치 만료"),
            input(running = true, reason = "합성 fixture: 카메라 권한 해제"),
            true,
        )
        assertEquals(DestinationGuidanceDisplayStage.BLOCKED, result.stage)
        assertTrue(result.retryAvailable)
        assertFalse(result.routeMessageKo.contains("경로가 준비되었습니다."))
    }

    @Test
    fun onlyBothActualOutputSignalsWithoutBlocksProduceAvailablePresentation() {
        val result = DestinationGuidancePresentationPolicy.present(input(running = true), input(running = true), false)
        assertEquals(DestinationGuidanceDisplayStage.ACTIVE, result.stage)
        assertEquals("경로 안내 중", result.titleKo)
    }

    @Test
    fun actualRouteOutputIsShownWhileCameraPreparationContinues() {
        val result = DestinationGuidancePresentationPolicy.present(
            input(running = true), input(preparing = true), false,
        )
        assertEquals(DestinationGuidanceDisplayStage.PARTIALLY_AVAILABLE, result.stage)
        assertEquals("경로 안내 중", result.titleKo)
    }

    @Test
    fun timeoutAndExplicitRetryArePresentedWithoutCreatingReadyEvidence() {
        val stopped = DestinationGuidancePresentationPolicy.present(
            input(reason = "합성 fixture: 위치 시간 초과"), input(reason = "합성 fixture: 영상 없음"), true,
        )
        val restarted = DestinationGuidancePresentationPolicy.present(
            input(preparing = true), input(preparing = true), false,
        )
        assertEquals(DestinationGuidanceDisplayStage.BLOCKED, stopped.stage)
        assertTrue(stopped.retryAvailable)
        assertEquals(DestinationGuidanceDisplayStage.PREPARING, restarted.stage)
        assertFalse(restarted.retryAvailable)
    }
    @Test fun confirmedArrivalKeepsCameraRestrictionsButNeverClaimsAnotherRouteIsPreparing() {
        val camera = input(preparing = true, reason = "카메라 권한을 확인해 주세요.")
        val completed = DestinationGuidancePresentationPolicy.present(input(preparing = true), camera, true,
            arrivalConfirmed = true)
        assertEquals(DestinationGuidanceDisplayStage.ARRIVED, completed.stage)
        assertEquals("도착했습니다", completed.titleKo)
        assertTrue(completed.routeMessageKo.contains("안내를 종료"))
        assertFalse(completed.routeMessageKo.contains("준비"))
        assertTrue(completed.cameraMessageKo.contains("카메라 권한을 확인"))
        assertFalse(completed.retryAvailable)
        val next = DestinationGuidancePresentationPolicy.present(input(preparing = true), camera, false)
        assertEquals(DestinationGuidanceDisplayStage.PREPARING, next.stage)
        assertTrue(next.routeMessageKo.contains("TMAP 경로를 준비"))
    }

    @Test
    fun arrivalPendingOverridesPreparingAvailableAndBlockedCameraTitlesWithoutHidingReasons() {
        val route = input(running = true, reason = "합성 fixture: 위치 만료")
        val cameras = listOf(
            input(preparing = true),
            input(running = true),
            input(reason = "합성 fixture: 카메라 권한 해제"),
        )
        for (camera in cameras) {
            val previous = DestinationGuidancePresentationPolicy.present(route, camera, true)
            val pending = DestinationGuidancePresentationPolicy.present(
                route, camera, true, arrivalConfirmationPending = true,
            )
            assertEquals(DestinationGuidanceDisplayStage.ARRIVAL_CONFIRMATION_PENDING, pending.stage)
            assertEquals("도착 안내 종료 중", pending.titleKo)
            assertFalse(pending.retryAvailable)
            assertEquals(previous.routeMessageKo, pending.routeMessageKo)
            assertTrue(pending.routeMessageKo.contains("위치 만료"))
            assertEquals(previous.cameraMessageKo, pending.cameraMessageKo)
            assertEquals(DestinationGuidanceResumeAction.NONE, pending.resumeAction)
        }
    }

    @Test
    fun confirmedArrivalWinsOverPendingWithoutChangingItsExistingCameraRestrictions() {
        val route = input(preparing = true, reason = "합성 fixture: 위치 만료")
        val camera = input(preparing = true, reason = "합성 fixture: 카메라 권한 해제")
        val confirmed = DestinationGuidancePresentationPolicy.present(route, camera, true, arrivalConfirmed = true)
        val both = DestinationGuidancePresentationPolicy.present(
            route, camera, true, arrivalConfirmed = true, arrivalConfirmationPending = true,
        )
        assertEquals(DestinationGuidanceDisplayStage.ARRIVED, both.stage)
        assertEquals("도착했습니다", both.titleKo)
        assertFalse(both.retryAvailable)
        assertTrue(both.cameraMessageKo.contains("카메라 권한 해제"))
        assertEquals(confirmed, both)
    }

    @Test
    fun clearingArrivalPendingRestoresTheObservedFeatureStageAndRetryOffer() {
        val cases = listOf(
            Triple(input(), input(preparing = true), DestinationGuidanceDisplayStage.PREPARING),
            Triple(input(reason = "합성 fixture: 위치 만료"), input(running = true), DestinationGuidanceDisplayStage.PARTIALLY_AVAILABLE),
            Triple(input(running = true), input(running = true), DestinationGuidanceDisplayStage.ACTIVE),
            Triple(input(reason = "합성 fixture: 위치 만료"), input(reason = "합성 fixture: 영상 없음"), DestinationGuidanceDisplayStage.BLOCKED),
        )
        for ((route, camera, expectedStage) in cases) {
            val pending = DestinationGuidancePresentationPolicy.present(route, camera, true, arrivalConfirmationPending = true)
            assertEquals(DestinationGuidanceDisplayStage.ARRIVAL_CONFIRMATION_PENDING, pending.stage)
            assertFalse(pending.retryAvailable)
            val cleared = DestinationGuidancePresentationPolicy.present(route, camera, true, arrivalConfirmationPending = false)
            assertEquals(expectedStage, cleared.stage)
            assertTrue(cleared.retryAvailable)
            assertEquals(DestinationGuidancePresentationPolicy.present(route, camera, true), cleared)
        }
    }

}
