package kr.co.hanium.dreamup.walksafe.session

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityUserEducationPresentationTest {
    @Test
    fun screenIncludesTheFullSpokenContentForEachEducationStage() {
        val policy = adultPolicy()
        val safetyText = PriorityUserEducationPresentation.screenText(policy.snapshot())
        assertTrue(safetyText.contains(PriorityUserEducationPresentation.speechText(PriorityUserEducationPlayback.SAFETY_LIMITS)))
        assertTrue(safetyText.contains(PriorityUserEducationPresentation.speechText(PriorityUserEducationPlayback.PRACTICE_NECESSITY)))

        completeSafetyConsent(policy)

        assertEquals(
            PriorityUserEducationPresentation.speechText(PriorityUserEducationPlayback.APP_USAGE),
            PriorityUserEducationPresentation.screenText(policy.snapshot()),
        )
        assertFalse(policy.snapshot().appUsageReviewed)
        assertFalse(policy.snapshot().trainingComplete)
    }

    @Test
    fun usageEducationExplainsImmediateUserActionsAndAutomaticSafetyStopWithoutInventingACommand() {
        val policy = adultPolicy()
        completeSafetyConsent(policy)
        val spoken = PriorityUserEducationPresentation.speechText(PriorityUserEducationPlayback.APP_USAGE)

        assertTrue(spoken.contains("지금 멈춰야 하면 발걸음을 멈추고 주변을 확인한 뒤 보행 일시정지라고 말하세요"))
        assertTrue(spoken.contains("보행을 아예 끝내려면 보행 종료라고 말하고 종료 확인 안내에 응답하세요"))
        assertTrue(spoken.contains("보행 일시정지로 보행 전체를 잠시 멈추고"))
        assertTrue(spoken.contains("보행 종료는 종료 확인 안내를 거쳐 전체 보행을 끝냅니다"))
        assertTrue(spoken.contains("앱이 자동으로 안전정지한 경우 현재 보행 안내와 출력을 중단하며 자동으로 재개하지 않습니다"))
        assertTrue(spoken.contains("실제 보행에서 안전정지를 직접 실행하는 버튼이나 음성 명령은 없습니다"))
        assertFalse(spoken.contains("안전정지라고 말하세요"))
        assertTrue(spoken.contains("안전정지 연습은 실제 보행의 안전정지를 실행하는 명령이 아닙니다"))
        assertTrue(spoken.contains("실제 안전정지 후에는 보행 재개로 이어가지 않습니다"))
        assertTrue(spoken.contains("안전한 곳에서 기기 상태를 확인한 뒤 새 보행을 준비하세요"))
        assertTrue(spoken.contains("혼자 안전하게 이동할 수 없으면 먼저 멈추고 주변 사람에게 직접 도움을 요청하세요"))
        assertEquals(spoken, PriorityUserEducationPresentation.screenText(policy.snapshot()))
    }

    @Test
    fun automaticSafetyStopSummaryMatchesFullResourceCopy() {
        val summary = File("src/main/res/values/strings.xml").readText()
            .substringAfter("<string name=\"walk_safety_stopped\">")
            .substringBefore("</string>")

        assertEquals(
            "자동 안전정지\\n현재 보행 안내와 출력을 중단했습니다. 자동으로 재개하지 않습니다. " +
                "안전한 곳에서 기기 상태를 확인하고 새 보행을 준비하세요. " +
                "혼자 이동하기 어렵다면 주변 사람에게 도움을 요청하세요.",
            summary,
        )
    }

    @Test
    fun safetyNextActionFollowsCompletedPlaybackAndSeparateConsent() {
        val policy = adultPolicy()
        assertTrue(progress(policy).contains("1. 안전 제한 안내 듣기"))

        policy.reviewSafetyEducation(voicePlaybackCompleted = false)
        assertTrue(progress(policy).contains("1. 안전 제한 안내 듣기"))
        policy.reviewSafetyEducation(voicePlaybackCompleted = true)
        assertTrue(progress(policy).contains("2. 연습 필요성 듣기"))
        policy.reviewPracticeNecessity(voicePlaybackCompleted = true)
        assertTrue(progress(policy).contains("안전 교육 동의하고 사전 연습으로"))
        assertFalse(policy.snapshot().educationAccepted)
    }

    @Test
    fun usagePlaybackFirstStillPointsToTheMissingOperatingScopeAcknowledgment() {
        val policy = adultPolicy()
        completeSafetyConsent(policy)
        val token = policy.beginAppUsageEducationPlayback()!!
        policy.finishAppUsageEducationPlayback(token, completed = true)

        assertTrue(progress(policy).contains("이해했습니다 버튼"))
        assertFalse(progress(policy).contains("완료하고 홈으로"))

        policy.acknowledgeUsageConditions()
        assertTrue(progress(policy).contains("안전한 연습 장소 확인"))
        assertFalse(progress(policy).contains("완료하고 홈으로"))
        assertFalse(policy.snapshot().appUsageAccepted)
    }

    @Test
    fun practiceProgressWaitsForTheHazardResponseAndCountsOnlyCompletedActions() {
        val policy = adultPolicy()
        completeSafetyConsent(policy)
        policy.acknowledgeUsageConditions()
        policy.finishAppUsageEducationPlayback(policy.beginAppUsageEducationPlayback()!!, completed = true)
        policy.confirmSafePracticePlace()
        val token = policy.beginPractice(PriorityUserPractice.HAZARD_ALERT)!!
        PriorityUserPracticeDeliverySignal.entries.forEach { policy.recordPracticeDelivery(token, it) }

        val awaitingResponse = PriorityUserEducationPresentation.progressNotice(
            policy.snapshot(),
            practice = PriorityUserPractice.HAZARD_ALERT,
            hazardResponseReady = policy.isHazardResponseReady(token),
        )
        assertTrue(awaitingResponse.contains(PriorityUserEducationPresentation.hazardResponseActionKo))
        assertFalse(awaitingResponse.contains("연습을 완료"))
        assertTrue(policy.snapshot().completedPractices.isEmpty())

        policy.confirmHazardResponse(token)
        assertTrue(progress(policy).contains("1/4 완료"))
        assertTrue(progress(policy).contains("일시정지 연습 버튼"))
        assertFalse(progress(policy).contains("완료하고 홈으로"))
    }

    @Test
    fun failedOrCancelledUsagePlaybackKeepsTheListenActionAndCannotClaimCompletion() {
        val policy = adultPolicy()
        completeSafetyConsent(policy)
        policy.acknowledgeUsageConditions()
        val failed = policy.beginAppUsageEducationPlayback()!!
        policy.finishAppUsageEducationPlayback(failed, completed = false)
        val cancelled = policy.beginAppUsageEducationPlayback()!!
        policy.cancelAppUsageEducationPlayback()
        policy.finishAppUsageEducationPlayback(cancelled, completed = true)

        assertTrue(progress(policy).contains("사전 연습 듣기"))
        assertFalse(progress(policy).contains("완료하고 홈으로"))
        assertFalse(policy.snapshot().nativeEducationComplete)
    }

    @Test
    fun playbackStatusDistinguishesFirstAttemptFromReplayWithoutChangingEvidence() {
        val policy = adultPolicy()
        val initial = policy.snapshot()
        val firstPlayback = PriorityUserEducationPresentation.progressNotice(initial, PriorityUserEducationPlayback.SAFETY_LIMITS)
        assertTrue(firstPlayback.contains("끝까지 재생되어야"))
        assertEquals(initial, policy.snapshot())

        policy.reviewSafetyEducation(voicePlaybackCompleted = true)
        val reviewed = policy.snapshot()
        val replay = PriorityUserEducationPresentation.progressNotice(reviewed, PriorityUserEducationPlayback.SAFETY_LIMITS)
        assertTrue(replay.contains("이전 청취 완료 기록은 유지"))
        assertTrue(replay.contains("2. 연습 필요성 듣기"))
        assertEquals(reviewed, policy.snapshot())
    }

    private fun progress(policy: PriorityUserOnboardingPolicy): String =
        PriorityUserEducationPresentation.progressNotice(policy.snapshot())

    private fun adultPolicy() = PriorityUserOnboardingPolicy().apply {
        selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS)
    }

    private fun completeSafetyConsent(policy: PriorityUserOnboardingPolicy) {
        policy.reviewSafetyEducation(voicePlaybackCompleted = true)
        policy.reviewPracticeNecessity(voicePlaybackCompleted = true)
        policy.acceptEducationConsent()
    }
}
