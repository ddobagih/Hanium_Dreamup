package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityVoicePermissionRecoveryStaticTest {
    private val source = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first { it.isFile }.readText()

    private fun method(name: String): String {
        val start = source.indexOf("    private fun $name(")
        check(start >= 0)
        val end = source.indexOf("\n    private fun ", start + 1).let {
            if (it < 0) source.length else it
        }
        return source.substring(start, end)
    }

    @Test
    fun lostMicrophonePermissionUsesObservedRecoveryAfterVoiceEligibility() {
        val start = method("maybeStartHandsFreeVoiceService")
        val eligibility = start.indexOf("!isWalkSessionRuntimeActive()")
        val missingMicrophone = start.indexOf("if (!hasRecordAudioPermission()) {")
        val request = start.indexOf("app.registerWalkVoiceStartRequest(")

        assertTrue(eligibility >= 0 && eligibility < missingMicrophone)
        assertTrue(missingMicrophone < request)
        assertTrue(start.contains(
            "if (!hasRecordAudioPermission()) {\n" +
                "            applyObservedPermissionStateChange(\"voice_hands_free_microphone_permission_missing\")\n" +
                "            return\n" +
                "        }",
        ))
        assertTrue(start.contains("catch (error: SecurityException)"))
        assertTrue(start.contains("WalkVoiceStartFailure.SECURITY"))
        assertTrue(start.contains("catch (error: IllegalStateException)"))
        assertTrue(start.contains("WalkVoiceStartFailure.FOREGROUND_START_RESTRICTED"))
        assertTrue(start.indexOf("handsFreeVoiceStartRecovery.blocksAutomaticRetry") <
            start.indexOf("app.registerWalkVoiceStartRequest("))
        assertFalse(start.contains("catch (error: RuntimeException)"))
        assertFalse(start.contains("catch (error: Throwable)"))

        val observer = method("applyObservedPermissionStateChange")
        assertTrue(observer.contains("stopHandsFreeVoiceService()"))
        assertTrue(observer.contains("cancelVoiceCommandRecognition()"))
        assertTrue(observer.contains("showPermissionDenialPanel(stoppedOnly, reason)"))
        assertTrue(observer.contains("refreshStartupCapabilityUi()"))
    }

    @Test
    fun securityFailureStopsVoiceAndReobservesActualPermissionLoss() {
        val report = method("reportWalkVoiceStartFailure")
        val stop = report.indexOf("stopHandsFreeVoiceService()")
        val securityGuard =
            "if (isActivityForeground && failure == WalkVoiceStartFailure.SECURITY && !microphoneGranted) {"
        val securityStart = report.indexOf(securityGuard)
        val security = report.substringAfter(securityGuard).substringBefore("\n        }")
        val showPending = method("showPendingHandsFreeVoiceStartFailure")

        assertTrue(stop >= 0 && stop < securityStart)
        assertTrue(report.contains(
            "handsFreeVoiceStartRecovery.recordFailure(\n" +
                "            failure,\n" +
                "            microphoneGranted,\n" +
                "            appInForeground = isActivityForeground,\n" +
                "        )",
        ))
        assertTrue(security.contains(
            "applyObservedPermissionStateChange(\"voice_hands_free_start_security_permission_missing\")",
        ))
        assertTrue(report.contains("pendingHandsFreeVoiceStartFailure = presentation"))
        assertTrue(report.contains("showPendingHandsFreeVoiceStartFailure()"))
        assertTrue(showPending.contains("val presentation = pendingHandsFreeVoiceStartFailure ?: return"))
        assertTrue(showPending.contains("if (!isActivityForeground || !::statusText.isInitialized || !::detailText.isInitialized) return"))
        assertTrue(showPending.contains("updateNavigationStatus(\"voice_hands_free=\${presentation.statusToken}\")"))
        assertTrue(showPending.contains("updateStatus(\"호출어 음성 시작 실패\", presentation.guidance)"))
        assertTrue(source.contains("handsFreeVoiceStartRecovery.onStopped()"))
        assertTrue(source.contains("handsFreeVoiceStartRecovery.onForegroundReturn()"))
    }
}
