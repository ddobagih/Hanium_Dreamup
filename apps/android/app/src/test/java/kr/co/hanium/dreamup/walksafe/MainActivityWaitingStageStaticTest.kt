package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5~8단계 공통 대기 화면 계약. 설계 노트(onboarding-spec-4to8)가 고정한 것만 담고, 팀 확인 전인
 * 취소 경로와 갱신 방식 문구는 넣지 않는다.
 */
class MainActivityWaitingStageStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private val waitingTexts = source
        .substringAfter("private fun firstRunWaitingTextOrNull(")
        .substringBefore("/** 대기 점은 시각 보조다")

    @Test
    fun exactlyTheFourWaitingStagesGetAWaitingSentence() {
        listOf(
            "VERIFIED_SMS",
            "GUARDIAN_APPROVAL",
            "ACCOUNT_ACTIVATION",
            "VERIFIED_LOGIN",
        ).forEach { stage ->
            assertTrue(stage, waitingTexts.contains("FirstRunOnboardingStage.$stage ->"))
        }
        // 4단계는 입력 화면이고 발급 주체가 미정이라 대기 화면을 쓰지 않는다.
        assertFalse(waitingTexts.contains("LOCAL_CREDENTIAL_PHONE_SUBMISSION"))
        assertEquals(4, Regex("FirstRunOnboardingStage\\.").findAll(waitingTexts).count())
        assertTrue(waitingTexts.contains("else -> null"))
    }

    @Test
    fun waitingStateIsAlwaysASentenceNotOnlyAnIndicator() {
        // 네 문장 모두 사용자가 할 일이 없다는 사실을 말한다.
        assertEquals(
            4,
            Regex("이 화면에서 아무것도 입력할 필요가 없습니다\\.")
                .findAll(waitingTexts).count(),
        )
        assertTrue(source.contains("firstRunWaitingText = TextView(this).apply"))
        assertTrue(
            source.substringAfter("firstRunWaitingText = TextView(this).apply")
                .substringBefore("firstRunWaitingCard")
                .contains("textSize = 18f"),
        )
    }

    @Test
    fun automaticAdvanceAndDeferredControlsAreNotClaimed() {
        // 갱신 방식이 미정이므로 자동 전환을 단정하지 않는다.
        assertFalse(waitingTexts.contains("자동으로"))
        assertFalse(waitingTexts.contains("완료되면"))
        // 취소 경로와 재전송·타이머는 팀 확인 전까지 구현하지 않는다.
        val card = source.substringAfter("firstRunWaitingCard = LinearLayout(this).apply")
            .substringBefore("addView(firstRunWaitingText)")
        listOf("취소", "재전송", "남은 시간").forEach { deferred ->
            assertFalse(deferred, card.contains(deferred))
        }
        assertFalse(card.contains("Button("))
    }

    @Test
    fun theProgressIndicatorStaysOutOfTheAccessibilityTree() {
        val card = source.substringAfter("firstRunWaitingCard = LinearLayout(this).apply")
            .substringBefore("addView(firstRunWaitingText)")
        assertTrue(card.contains("View.IMPORTANT_FOR_ACCESSIBILITY_NO"))
    }

    @Test
    fun dotsRespectTheSystemAnimationSetting() {
        val dots = source.substringAfter("private fun updateFirstRunWaitingDots(")
            .substringBefore("/** 실제 →")
        assertTrue(dots.contains("Settings.Global.ANIMATOR_DURATION_SCALE"))
        assertTrue(dots.contains("clearAnimation()"))
    }
}
