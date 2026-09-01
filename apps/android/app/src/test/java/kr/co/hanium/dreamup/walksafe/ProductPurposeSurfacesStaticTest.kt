package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.device.WALKSAFE_PRODUCT_PURPOSE_STATEMENT_KO
import kr.co.hanium.dreamup.walksafe.device.WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductPurposeSurfacesStaticTest {
    private val activity = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val disclosure = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/report/ReportPrivacyConsentSession.kt",
    ).readText()
    private val productBoundary = File("../../../configs/walksafe_product_boundary_20260722.json").readText()
    private val userGuide = File("../USER_GUIDE.md").readText()

    @Test
    fun androidPurposeConstantsMatchTheCanonicalProductBoundaryExactly() {
        assertTrue(
            productBoundary.contains(
                "\"canonical_statement\": \"$WALKSAFE_PRODUCT_PURPOSE_STATEMENT_KO\"",
            ),
        )
        assertTrue(
            productBoundary.contains(
                "\"safety_limitation\": \"$WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO\"",
            ),
        )
    }

    @Test
    fun firstScreenAndConsentUseTheSharedPurposeAndSafetyBoundary() {
        assertTrue(activity.contains("text = \"\$WALKSAFE_PRODUCT_PURPOSE_NOTICE_KO\\n앱 버전:"))
        assertTrue(disclosure.contains("WALKSAFE_PRODUCT_PURPOSE_STATEMENT_KO"))
        assertTrue(disclosure.contains("WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO"))
        assertTrue(userGuide.contains(WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO))
    }

    @Test
    fun userGuideKeepsThePhoneMountingAndAssistiveAidBoundary() {
        assertTrue(userGuide.contains("가슴형 또는 목걸이형 거치대"))
        assertTrue(userGuide.contains("손에 들거나 주머니에 넣은 상태는 공식 장착 방식이 아니며"))
        assertTrue(
            userGuide.contains(
                "사용자가 장착 상태를 명시적으로 다시 확인한 뒤에만 재개합니다",
            ),
        )
        assertTrue(userGuide.contains("승인된 장착 근거가 없으면 정상 장착으로 판단하지 않습니다"))
        assertTrue(userGuide.contains("장착 확인을 통과해도 보행 안전이 보장되는 것은 아니며"))
        assertTrue(
            userGuide.contains(
                "흰지팡이·안내견 등 기존 보행 보조수단을 대신하지 않습니다",
            ),
        )
        assertFalse(userGuide.contains("보행 안전을 보장합니다"))
        assertFalse(userGuide.contains("기존 보행 보조수단을 대신합니다"))
    }

    @Test
    fun userGuideUsesTheUnder14EmailEnrollmentBoundary() {
        assertTrue(userGuide.contains("만 14세 미만은 계정과 보행을 시작할 수 없습니다"))
        assertTrue(
            userGuide.contains(
                "만 14세 이상은 보호자 확인 없이 이메일 OTP 인증으로 가입할 수 있습니다",
            ),
        )
        assertFalse(userGuide.contains("만 14세 이상 18세 미만은 보호자 확인이 끝나야"))
    }

    @Test
    fun reportSurfacesNameOnlyDamagedTactileBlockReporting() {
        assertFalse(activity.contains("위험 신고"))
        assertFalse(activity.contains("\"신고 요청\""))
        assertFalse(disclosure.contains("위험 신고"))
        assertFalse(activity.contains("위험물 신고"))
        assertFalse(activity.contains("보호자 추적"))
        assertFalse(activity.contains("넘어짐 탐지"))

        assertTrue(activity.contains("\"손상 점자블록 직접 신고\""))
        assertTrue(activity.contains("REPORT_EXPLICIT_CONFIRMATION_DISCLOSURE_KO"))
        assertTrue(activity.contains("방금 고정한 신고 보내기"))
        assertTrue(disclosure.contains("이번 손상 점자블록 직접 신고"))
        assertTrue(disclosure.contains("WalkSafe 테스트 서버"))
        assertTrue(disclosure.contains("기관으로 자동 전송하지 않습니다"))
        assertFalse(activity.contains("180일"))
        assertFalse(disclosure.contains("180일"))
    }
}
