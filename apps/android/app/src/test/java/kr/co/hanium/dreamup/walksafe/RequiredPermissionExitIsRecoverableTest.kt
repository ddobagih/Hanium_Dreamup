package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "이번만 허용" locked the app shut for good. Android revokes a one-time grant when the process
 * ends, and `PREF_INITIAL_APP_PERMISSION_REQUEST_COMPLETED` then suppressed every later request:
 * the app checked the permissions, found them gone, and told the walker to restart and agree —
 * while restarting is exactly what removed the chance to agree.
 *
 * An expired one-time grant is not a denial, so asking once more per launch is all it takes.
 * Where Android really will not ask again, the exit has to name the way out and open it, because
 * a walker who cannot see the screen will not find Android settings from a dialog that only
 * offers 종료.
 */
class RequiredPermissionExitIsRecoverableTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    private val gate = Regex(
        "private fun requestInitialAppEntryPermissionsIfNeeded\\(\\)[\\s\\S]*?\\n    }",
    ).find(source)?.value.orEmpty()

    private val exit = Regex(
        "private fun showRequiredAppPermissionExit\\([\\s\\S]*?\\n    }",
    ).find(source)?.value.orEmpty()

    @Test
    fun theGateAndTheExitWereBothFound() {
        assertTrue(gate.isNotEmpty())
        assertTrue(exit.isNotEmpty())
    }

    @Test
    fun aCompletedRequestStillAsksAgainWhenThePermissionsAreGone() {
        assertTrue(gate, gate.contains("initialAppPermissionReRequestedThisLaunch"))
        assertTrue(gate, gate.contains("launchInitialAppEntryPermissionRequest"))
    }

    @Test
    fun theRetryIsBoundToTheLaunchNotToStorage() {
        // requestInitialAppEntryPermissionsIfNeeded runs on every resume, so a persisted flag
        // would either loop the system dialog or never clear.
        val declaration = Regex(
            "private var initialAppPermissionReRequestedThisLaunch\\s*=\\s*(\\w+)",
        ).find(source)

        assertTrue("메모리 플래그 선언이 없습니다", declaration != null)
        assertFalse(
            "재요청 플래그를 저장하면 안 됩니다",
            source.contains("PREF_INITIAL_APP_PERMISSION_RE_REQUEST"),
        )
    }

    @Test
    fun theExitNoLongerBlamesTheRestart() {
        assertFalse(
            source.contains("앱을 다시 실행한 뒤 모든 필수 권한에 동의해 주세요"),
        )
    }

    @Test
    fun theExitNamesWhatToAllowAndWhere() {
        listOf("설정에서", "카메라", "위치", "마이크").forEach { word ->
            assertTrue(word, exit.contains(word))
        }
    }

    @Test
    fun theExitOpensSettingsInsteadOfOnlyClosing() {
        // 종료 alone leaves a walker who cannot read the screen with nowhere to go.
        assertTrue(exit, exit.contains("설정 열기"))
        assertTrue(exit, exit.contains("openAppSettings()"))
        assertTrue(exit, exit.contains("종료"))
        // The way out is offered before the way that ends the session.
        assertTrue(exit, exit.indexOf("설정 열기") < exit.indexOf("\"종료\""))
    }

    @Test
    fun theSpokenExitIsShortEnoughToHear() {
        val spoken = Regex("\"(필수 권한이[^\"]*)\"").find(exit)?.groupValues?.get(1).orEmpty()

        assertTrue("안내 문구를 찾지 못했습니다", spoken.isNotEmpty())
        assertTrue("$spoken (${spoken.length}자)", spoken.length <= 60)
    }
}
