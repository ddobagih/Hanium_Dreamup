package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityVoicePermissionRecoveryStaticTest {
    private val source = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/HandsFreeRuntimeSession.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/HandsFreeRuntimeSession.kt"),
    ).first { it.isFile }.readText()

    private fun method(name: String): String {
        val owner = if (name == "applyObservedPermissionStateChange") File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/PermissionFlowController.kt").readText() else source
        return MainActivityRefactorSource.member(owner, name)
    }

    @Test
    fun lostMicrophonePermissionUsesObservedRecoveryAfterVoiceEligibility() {
        val start = method("maybeStartHandsFreeVoiceService")
        val eligibility = start.indexOf("!environment.runtimeActive()")
        val missingMicrophone = start.indexOf("if (!environment.microphoneGranted()) {")
        val request = start.indexOf("platform.registerStart(")

        assertTrue(eligibility >= 0 && eligibility < missingMicrophone)
        assertTrue(missingMicrophone < request)
        assertTrue(start.contains(
            "if (!environment.microphoneGranted()) {\n" +
                "            applicationEffects.permissionStateChanged(\"voice_hands_free_microphone_permission_missing\")\n" +
                "            return\n" +
                "        }",
        ))
        assertTrue(start.contains("catch (error: SecurityException)"))
        assertTrue(start.contains("WalkVoiceStartFailure.SECURITY"))
        assertTrue(start.contains("catch (error: IllegalStateException)"))
        assertTrue(start.contains("WalkVoiceStartFailure.FOREGROUND_START_RESTRICTED"))
        assertTrue(start.indexOf("handsFreeVoiceStartRecovery.blocksAutomaticRetry") <
            start.indexOf("platform.registerStart("))
        assertFalse(start.contains("catch (error: RuntimeException)"))
        assertFalse(start.contains("catch (error: Throwable)"))

        val observer = method("applyObservedPermissionStateChange")
        assertTrue(observer.contains("runtime.stopHandsFree()"))
        assertTrue(observer.contains("runtime.cancelVoiceRecognition()"))
        assertTrue(observer.contains("showPermissionDenialPanel(stoppedOnly, reason)"))
        assertTrue(observer.contains("runtime.refreshStartup()"))
    }

    @Test
    fun securityFailureStopsVoiceAndReobservesActualPermissionLoss() {
        val report = method("reportWalkVoiceStartFailure")
        val stop = report.indexOf("stopHandsFreeVoiceService()")
        val securityGuard =
            "if (environment.foreground() && failure == WalkVoiceStartFailure.SECURITY && !microphoneGranted) {"
        val securityStart = report.indexOf(securityGuard)
        val security = report.substringAfter(securityGuard).substringBefore("\n        }")
        val showPending = method("showPendingHandsFreeVoiceStartFailure")

        assertTrue(stop >= 0 && stop < securityStart)
        assertTrue(report.contains(
            "handsFreeVoiceStartRecovery.recordFailure(\n" +
                "            failure,\n" +
                "            microphoneGranted,\n" +
                "            appInForeground = environment.foreground(),\n" +
                "        )",
        ))
        assertTrue(security.contains(
            "applicationEffects.permissionStateChanged(\"voice_hands_free_start_security_permission_missing\")",
        ))
        assertTrue(report.contains("pendingHandsFreeVoiceStartFailure = failurePresentation"))
        assertTrue(report.contains("showPendingHandsFreeVoiceStartFailure()"))
        assertTrue(showPending.contains("val failurePresentation = pendingHandsFreeVoiceStartFailure ?: return"))
        assertTrue(showPending.contains("if (!environment.foreground() || !presentation.canShowStatus()) return"))
        assertTrue(showPending.contains("presentation.navigationStatus(\"voice_hands_free=\${failurePresentation.statusToken}\")"))
        assertTrue(showPending.contains("presentation.status(\"호출어 음성 시작 실패\", failurePresentation.guidance)"))
        assertTrue(source.contains("handsFreeVoiceStartRecovery.onStopped()"))
        assertTrue(source.contains("handsFreeVoiceStartRecovery.onForegroundReturn()"))
    }
}
