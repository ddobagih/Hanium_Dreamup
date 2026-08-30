package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACCOUNT_CREATED 단계는 인증번호 입력만을 위해 존재한다. 제목이 인증번호를 입력하라고 말하는데
 * 입력칸이 접혀 있으면 그 단계에서 할 수 있는 일이 없다.
 */
class MainActivityAccountStageStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private val accessUi = source.substringAfter("private fun updateEmailAccountAccessUi(")
        .substringBefore("\n    private fun ")

    @Test
    fun theCodeFieldIsOpenOnTheStageThatAsksForTheCode() {
        assertTrue(
            accessUi.contains(
                "(signupVisible || (creating && !accountSignupCollapsedByUser)) &&",
            ),
        )
        assertTrue(accessUi.contains("val creating = renderStage == FirstRunOnboardingStage.ACCOUNT_CREATED"))
    }

    @Test
    fun openingItDoesNotWriteStateAndKeepsTheWayBack() {
        // 펼침은 표시로만 유도한다. 저장된 상태를 쓰면 미리보기가 실제 상태를 바꾸게 된다.
        assertFalse(accessUi.contains("if (creating) accountSignupExpanded = true"))
        assertFalse(accessUi.contains("accountSignupExpanded = creating"))

        // 팀이 고정한 두 줄은 손대지 않았다. 표시는 그 위에 signupOpen 으로 얹는다.
        assertTrue(accessUi.contains("if (verifiedLogin) accountSignupExpanded = false"))
        assertTrue(
            accessUi.contains("val signupVisible = accountSignupExpanded && !verifiedLogin"),
        )

        // 팀이 준 「로그인 화면으로 돌아가기」 길은 그대로 남는다.
        assertTrue(accessUi.contains("signupOpen -> \"로그인 화면으로 돌아가기\""))

        // 사용자가 직접 접으면 그 선택이 그 단계 동안 남는다.
        assertTrue(source.contains("val open = !accountSignupOpenShown"))
        assertTrue(source.contains("accountSignupCollapsedByUser = !open"))
        assertTrue(accessUi.contains("if (!creating) accountSignupCollapsedByUser = false"))
        assertTrue(accessUi.contains("accountSignupOpenShown = signupOpen"))
    }
}
