package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 첫 실행 단계 미리보기는 개발 중 4~8단계 화면을 만들기 위한 DEBUG 전용 수단이다. 표시만 바꾸고
 * 진행 상태·증거·신원은 건드리지 않아야 하며 release 빌드에는 존재하면 안 된다.
 */
class MainActivityFirstRunPreviewStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun previewControlExistsOnlyInDebugBuilds() {
        val creation = source.substringBefore("firstRunPreviewStageButton = accessiblePriorityUser")
        assertTrue(creation.trimEnd().endsWith("if (BuildConfig.DEBUG) {"))
        assertTrue(source.contains("if (!BuildConfig.DEBUG) return"))
    }

    @Test
    fun previewChangesWhatIsShownButNeverWhatIsEnabled() {
        val render = source.substringAfter("private fun updateFirstRunOnboardingUi()")
            .substringBefore("private fun linkPriorityUserAccessibilityTraversal")

        // 표시 경로는 renderStage 를 쓴다.
        assertTrue(render.contains("val renderStage = firstRunPreviewStage ?: snapshot.stage"))
        assertTrue(render.contains("val stageNumber = firstRunStageNumber(renderStage)"))
        assertTrue(render.contains("val message = when (renderStage)"))

        // 조작 활성화는 실제 단계로만 판정한다.
        Regex("""isEnabled\s*=\s*\n?\s*(renderStage|firstRunPreviewStage)""")
            .find(render)
            ?.let { throw AssertionError("isEnabled must not follow the preview stage: ${it.value}") }
        assertTrue(
            render.contains(
                "firstRunPurposeButton.isEnabled =\n" +
                    "            snapshot.stage == FirstRunOnboardingStage.PURPOSE_AND_SAFETY",
            ),
        )

        // 보행 진입은 미리보기와 무관하게 실제 완료 상태로만 열린다.
        assertTrue(render.contains("val mayUseWalk = firstRunOnboardingComplete()"))
        assertFalse(render.contains("mayUseWalk = renderStage"))
    }

    @Test
    fun previewNeverWritesOnboardingEvidenceOrIdentity() {
        val cycle = source.substringAfter("private fun cycleFirstRunPreviewStage()")
            .substringBefore("private fun updateFirstRunPreviewStageButton")
        listOf(
            "firstRunOnboardingSnapshot =",
            "FirstRunOnboardingEvidence",
            "reporterUserId",
            "persistIntegratedConsentDraft",
            "gatewaySessionOrNull",
        ).forEach { forbidden ->
            assertFalse("preview must not touch $forbidden", cycle.contains(forbidden))
        }
    }

    @Test
    fun previewOrderCoversTheTwelveStagesWithoutTheBlockedBranch() {
        val order = source.substringAfter("val FIRST_RUN_PREVIEW_STAGES = listOf(")
            .substringBefore(")")
        assertEquals(12, Regex("FirstRunOnboardingStage\\.").findAll(order).count())
        assertFalse(order.contains("BLOCKED_UNDER_14"))
    }
}
