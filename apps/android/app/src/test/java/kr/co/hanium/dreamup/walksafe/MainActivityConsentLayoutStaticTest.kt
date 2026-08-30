package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 동의 화면 두 곳(계정 가입, FP-013 통합 동의)은 같은 형태를 쓴다 — 맨 위 전체 동의, 항목마다
 * 체크·선택과 그 자리에서 읽는 조항 상자.
 *
 * 조항 문구는 문서가 소유한다. 화면은 원문에서 잘라 쓸 뿐이고 여기서 만들지 않는다.
 */
class MainActivityConsentLayoutStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun clauseTextIsSlicedFromTheDocumentAndNeverWritten() {
        val slicer = source.substringAfter("private fun documentClauseOrNull(")
            .substringBefore("\n    /**")
        assertTrue(slicer.contains("source.indexOf(label)"))
        assertTrue(slicer.contains("return if (next != null)"))

        // 못 찾으면 null 을 돌려 호출부가 원문 전체를 보이게 한다. 지어낸 문구로 때우지 않는다.
        assertTrue(slicer.contains("if (start < 0) return null"))

        // 문서가 쓰는 이름으로 찾아야 한다. 이 이름이 틀리면 그 항목만 문서 전체가 실린다.
        assertTrue(source.contains("IntegratedConsentItem.RAW_SOURCE_COLLECTION -> \"신고·진단용 raw v2\""))
    }

    @Test
    fun everyItemCarriesItsOwnClauseBox() {
        val box = source.substringAfter("private fun applyWsClauseBox(")
            .substringBefore("\n    private fun ")

        // 높이를 제한하고 상자 안에서 스크롤한다. 여섯 항목이 화면을 밀어내면 안 된다.
        assertTrue(box.contains("maxHeight = (WS_CLAUSE_BOX_MAX_HEIGHT_DP"))
        assertTrue(box.contains("movementMethod = ScrollingMovementMethod()"))
        assertTrue(box.contains("requestDisallowInterceptTouchEvent(true)"))

        // 두 화면 모두 같은 상자를 쓴다.
        assertEquals(2, source.split("applyWsClauseBox(this)").size - 1)
    }

    @Test
    fun bothSurfacesOfferAllAtOnceWithoutTakingAwayTheSingleChoice() {
        // 계정 가입: 전체 동의는 여섯 항목을 한 번에 켜고, 개별 체크는 그대로 남는다.
        val all = source.substringAfter("accountConsentAllCheck = CheckBox(this)")
            .substringBefore("accountConsentSummaryText = TextView(this)")
        assertTrue(all.contains("accountConsentChecks.values.forEach { it.isChecked = grantAll }"))

        // FP-013: 네 항목을 한 번에 바꾸되 항목별 버튼은 그대로 둔다.
        val integrated = source.substringAfter("firstRunConsentAllButton = accessiblePriorityUserButton(")
            .substringBefore("firstRunIntegratedConsentSaveButton =")
        assertTrue(integrated.contains("IntegratedConsentItem.entries.forEach { item ->"))
        assertTrue(integrated.contains("updateIntegratedConsentDraft(item, grantAll)"))
    }

    @Test
    fun theRequiredCountIsSpokenInsteadOfLockingTheButton() {
        val summary = source.substringAfter("private fun refreshAccountConsentSummary()")
            .substringBefore("\n    private fun ")

        // 필수 판정은 기존 모델 그대로다. 화면이 따로 세지 않는다.
        assertTrue(summary.contains("selections.requiredGranted"))
        assertTrue(summary.contains("ACCESSIBILITY_LIVE_REGION_POLITE") ||
            source.contains("accountConsentSummaryText = TextView(this).apply"))

        // 잠긴 버튼은 눌러도 아무 말을 하지 않는다. 버튼은 살려 두고 남은 항목을 문장으로 알린다.
        assertFalse(source.contains("accountRequestOtpButton.isEnabled = selections.requiredGranted"))
        assertFalse(source.contains("accountCreateButton.isEnabled = selections.requiredGranted"))
    }
}
