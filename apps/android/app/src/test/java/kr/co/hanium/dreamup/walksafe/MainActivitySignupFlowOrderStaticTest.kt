package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 가입 화면의 읽는 순서와 한국어 조사.
 */
class MainActivitySignupFlowOrderStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun goingBackSitsAfterTheInputsInsteadOfBetweenThem() {
        // 공용 토글은 로그인 화면에서 「새 계정 만들기」로 쓰이느라 입력칸 사이에 놓인다. 가입에
        // 들어오면 그 자리가 이메일·비밀번호와 비밀번호 확인·인증번호 사이를 갈라 흐름이 끊긴다.
        val controls = source.substringAfter("accountSignupControls = LinearLayout(this).apply")
            .substringBefore("\n        }")
        assertTrue(
            controls.indexOf("addView(accountCreateButton)") <
                controls.indexOf("addView(accountSignupBackButton)"),
        )

        val update = source.substringAfter("private fun updateEmailAccountAccessUi(")
            .substringBefore("\n    private fun ")

        // 둘 다 보이면 같은 말을 두 번 읽는다. 가입 중에는 끝의 버튼만 남는다.
        assertTrue(update.contains("signupOpen -> View.GONE"))
        assertTrue(
            update.contains(
                "accountSignupBackButton.visibility = if (signupOpen) View.VISIBLE else View.GONE",
            ),
        )
    }

    @Test
    fun theParticleFollowsTheWordBeforeIt() {
        // 「보행 화면 로 바꿨습니다」는 화면에서도 어색하고 음성으로는 더 어색하다.
        val particle = source.substringAfter("private fun String.koreanToParticle()")
            .substringBefore("\n    private fun ")
        assertTrue(particle.contains("(last.code - 0xAC00) % 28"))
        // 받침 ㄹ(8)과 받침 없음(0)은 「로」, 나머지는 「으로」.
        assertTrue(particle.contains("if (finalConsonant == 0 || finalConsonant == 8) \"로\" else \"으로\""))
        assertTrue(source.contains("\$label\${label.koreanToParticle()} 바꿨습니다"))
    }
}
