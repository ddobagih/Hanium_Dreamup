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
        assertTrue(render.contains("val message = when (renderStage)"))
        // 진행 번호는 미리보기를 따르지 않는다. 실제 단계 그대로다.
        assertTrue(render.contains("val stageNumber = firstRunStageNumber(snapshot)"))

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
    fun completedSurfacesArePreviewableButWalkOutputStaysOnTheRealState() {
        val render = source.substringAfter("private fun updateFirstRunOnboardingUi()")
            .substringBefore("private fun linkPriorityUserAccessibilityTraversal")

        // 인증 이후 화면은 미리보기로 볼 수 있다.
        assertTrue(
            render.contains(
                "mayUseWalk || renderStage == FirstRunOnboardingStage.COMPLETE",
            ),
        )

        // 보행 출력 자체는 미리보기를 타지 않는다. 이 판정은 첫 실행 완료를 먼저 요구한다.
        val outputs = source.substringAfter("private fun walkSafetyOutputsAllowed()")
            .substringBefore("private fun ")
        assertTrue(outputs.contains("firstRunOnboardingComplete()"))
        listOf("renderStage", "firstRunPreviewStage", "showVerifiedSurfaces").forEach { leak ->
            assertFalse(leak, outputs.contains(leak))
        }

        // 보행 화면도 완료 단계 미리보기로 볼 수 있다. 다만 그것은 표시 전용 분기여야 하고,
        // 실제로 패널을 여는 조건은 여전히 살아있는 보행 세션이어야 한다.
        // 값이 다음 줄에 오는 형태는 라이브 런타임 패널 한 곳뿐이다.
        val runtimePanel = source
            .substringAfter("runtimeControls.visibility =\n")
            .take(700)
        val realCondition = runtimePanel.substringBefore("} else if")
        assertTrue(realCondition.contains("firstRunOnboardingComplete()"))
        assertTrue(realCondition.contains("isWalkSessionRuntimeActive()"))
        assertFalse(realCondition.contains("firstRunPreviewStage"))
        assertTrue(
            runtimePanel.contains(
                "} else if (firstRunPreviewStage == FirstRunOnboardingStage.COMPLETE)",
            ),
        )
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
    fun previewOrderWalksTheEmailFlowWithoutTheBlockedBranch() {
        val order = source.substringAfter("val FIRST_RUN_PREVIEW_STAGES = listOf(")
            .substringBefore(")")

        // 제품이 실제로 걷는 EMAIL_ACCOUNT_V4 6단계와 완료 화면. SMS 시절 12단계는 돌지 않는다.
        assertEquals(10, Regex("FirstRunOnboardingStage\\.").findAll(order).count())
        assertFalse(order.contains("BLOCKED_UNDER_14"))
        assertFalse(order.contains("LOCAL_CREDENTIAL_PHONE_SUBMISSION"))
        assertFalse(order.contains("VERIFIED_SMS"))
        assertFalse(order.contains("GUARDIAN_APPROVAL"))
        assertTrue(order.contains("EMAIL_OTP_ENROLLMENT"))
        assertTrue(order.contains("ACCOUNT_CREATED"))
    }

    @Test
    fun theCompletedScreenAndTheWalkScreenAreSeparatePreviewSlots() {
        val cycle = source.substringAfter("private fun cycleFirstRunPreviewStage()")
            .substringBefore("\n    private fun ")

        // 완료 다음 한 자리가 보행 화면이고, 그 다음에 실제 단계로 돌아간다.
        assertTrue(cycle.contains("current == order.last() ->"))
        assertTrue(cycle.contains("firstRunPreviewWalkScreenOnly = true"))
        assertTrue(cycle.contains("firstRunPreviewWalkScreenOnly -> {"))

        val render = source.substringAfter("private fun updateFirstRunOnboardingUi()")
            .substringBefore("private fun linkPriorityUserAccessibilityTraversal")

        // 그 자리에서는 온보딩이 남긴 표면을 내린다. 완료 화면과 겹치면 둘 다 못 본다.
        assertTrue(render.contains("if (previewWalkOnly) {"))
        assertTrue(render.contains("firstRunOnboardingStatusText.visibility = View.GONE"))
        assertTrue(
            render.contains("if (firstRunOnboardingComplete() || previewWalkOnly) View.GONE"),
        )
        assertTrue(render.contains("previewWalkOnly ||"))
    }
}
