package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.session.PermissionExplanationKind
import kr.co.hanium.dreamup.walksafe.session.PermissionExplanationPolicy
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual Main formatter; Activity launch and Android permission revocation are not simulated. */
class MainActivityPermissionStopExplanationTest {
    private val explanation = PermissionExplanationPolicy.withoutResult(
        setOf(PermissionExplanationKind.CAMERA, PermissionExplanationKind.PRECISE_LOCATION),
        coarseLocationCurrentlyGranted = false,
    )

    @Test
    fun wholeWalkStopCannotPromiseThatTheRemainingWalkContinues() {
        val text = with(MainActivity()) { explanation.messageKo(WalkSessionState.SAFE_STOP) }
        assertFalse(text.contains("나머지 기능은 계속"))
        assertTrue(text.contains("보행 안내가 중지"))
        assertTrue(text.contains("다시 점검"))
        assertTrue(text.contains("직접"))
        assertTrue(text.contains("카메라"))
        assertTrue(text.contains("위치"))
    }

    @Test
    fun activeFeatureLimitationKeepsItsPermissionDetailsAndSettingsRecovery() {
        val microphone = PermissionExplanationPolicy.withoutResult(
            setOf(PermissionExplanationKind.MICROPHONE),
            coarseLocationCurrentlyGranted = false,
        )
        val text = with(MainActivity()) { microphone.messageKo(WalkSessionState.ACTIVE) }
        assertTrue(text.contains("해당 기능만 제한"))
        assertTrue(text.contains("나머지 기능은 계속"))
        assertTrue(text.contains("마이크"))
        assertTrue(text.contains("앱 설정"))
        assertFalse(text.contains("보행 안내가 중지"))
    }

    @Test
    fun permissionPanelUsesTheCurrentWalkStateInBothTextAndAccessibility() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val panel = MainActivityRefactorSource.function(source, "fun showPermissionDenialPanel(")
        assertTrue(panel.contains("authority.lifecycleOrNull()!!.snapshot().state"))
        assertTrue(panel.contains("ui.renderDenial(message)"))
        val ui = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/AndroidPermissionUi.kt").readText()
        assertTrue(MainActivityRefactorSource.member(ui, "renderDenial").contains("onboarding().renderPermissionDenial(message)"))
        val presenter = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/FirstRunOnboardingPresenter.kt").readText()
        val render = MainActivityRefactorSource.member(presenter, "renderPermissionDenial")
        assertTrue(render.contains("permissionDenialSummaryText.text = message"))
        assertTrue(render.contains("permissionDenialSummaryText.contentDescription = message"))
    }

    @Test
    fun safetyStopTransitionCannotLeaveAnEarlierContinuePermissionPanelVisible() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val transition = MainActivityRefactorSource.function(source, "fun transitionWalkSession(")
        val hideStalePanel = "if (transition.changed && transition.current.state == WalkSessionState.SAFE_STOP) { effects.hidePermissionDenial() }"
        assertTrue(transition.replace(Regex("\\s+"), " ").contains(hideStalePanel))
        assertTrue(transition.indexOf("lifecycle().handle(event, effects::beforePublication)") <
            transition.indexOf("effects.hidePermissionDenial()"))
        val binding = MainActivityRefactorSource.composition(source, "walkSessionTransitionController")
        assertTrue(binding.contains("override fun hidePermissionDenial()"))
        assertTrue(binding.contains("if (nativeUiPublication.isPublished(NativeUiPublication.Target.PERMISSION_DENIAL_PANEL))"))
        assertTrue(binding.contains("permissionDenialPanel.visibility = View.GONE"))
    }
}
