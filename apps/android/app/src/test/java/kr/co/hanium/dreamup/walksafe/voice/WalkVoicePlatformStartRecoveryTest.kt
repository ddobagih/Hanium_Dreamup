package kr.co.hanium.dreamup.walksafe.voice

import java.io.File
import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WalkVoicePlatformStartRecoveryTest {
    private fun source(relativePath: String): String = sequenceOf(
        File("src/main/java/$relativePath"),
        File("app/src/main/java/$relativePath"),
    ).first { it.isFile }.readText()

    private fun method(source: String, name: String): String = MainActivityRefactorSource.member(source, name)

    @Test
    fun expectedPlatformFailuresCleanUpBeforeReporting() {
        val events = mutableListOf<String>()
        val cleanup = { events += "cleanup" }
        val report = { failure: WalkVoiceStartFailure -> events += failure.name }

        handleWalkVoiceServiceStartException(SecurityException(), false, cleanup, report)
        assertEquals(listOf("cleanup", "SECURITY"), events)

        events.clear()
        handleWalkVoiceServiceStartException(
            IllegalStateException("foreground start denied"), true, cleanup, report,
        )
        assertEquals(listOf("cleanup", "FOREGROUND_START_RESTRICTED"), events)
    }

    @Test
    fun unexpectedControllerErrorIsCleanedUpAndRethrown() {
        val error = IllegalStateException("controller bug")
        val events = mutableListOf<String>()
        try {
            handleWalkVoiceServiceStartException(
                error,
                foregroundStartRestricted = false,
                cleanup = { events += "cleanup" },
                reportFailure = { events += "reported" },
            )
            fail("Unexpected controller exception must propagate")
        } catch (caught: IllegalStateException) {
            assertSame(error, caught)
        }
        assertEquals(listOf("cleanup"), events)
    }

    @Test
    fun cleanupRuntimeFailureDoesNotSuppressExpectedStartFailureReport() {
        val cleanupFailure = IllegalStateException("stop failed")
        val events = mutableListOf<String>()
        try {
            handleWalkVoiceServiceStartException(
                SecurityException("start denied"),
                foregroundStartRestricted = false,
                cleanup = {
                    events += "cleanup"
                    throw cleanupFailure
                },
                reportFailure = { events += it.name },
            )
            fail("Cleanup failure should remain visible")
        } catch (caught: IllegalStateException) {
            assertSame(cleanupFailure, caught)
        }
        assertEquals(listOf("cleanup", "SECURITY"), events)
    }

    @Test
    fun grantedSecurityAndForegroundRestrictionRequireAStoppedForegroundReturn() {
        val recovery = WalkVoiceStartRecovery()
        val security = recovery.recordFailure(WalkVoiceStartFailure.SECURITY, microphoneGranted = true)
        assertEquals("microphone_access_restricted_retry_required", security.statusToken)
        assertTrue(security.guidance.contains("마이크 권한은 허용"))
        assertFalse(security.guidance.contains("마이크 권한이 없어"))
        assertTrue(recovery.blocksAutomaticRetry)
        recovery.onForegroundReturn()
        assertTrue("An incidental resume must not unlock retry", recovery.blocksAutomaticRetry)
        recovery.onStopped()
        recovery.onForegroundReturn()
        assertFalse(recovery.blocksAutomaticRetry)

        val restricted = recovery.recordFailure(
            WalkVoiceStartFailure.FOREGROUND_START_RESTRICTED,
            microphoneGranted = true,
        )
        assertEquals("foreground_start_restricted_retry_required", restricted.statusToken)
        assertTrue(recovery.blocksAutomaticRetry)
        recovery.onStopped()
        recovery.onForegroundReturn()
        assertFalse(recovery.blocksAutomaticRetry)
    }

    @Test
    fun missingMicrophoneUsesPermissionRecoveryNotPlatformRetryLatch() {
        val recovery = WalkVoiceStartRecovery()
        val presentation = recovery.recordFailure(
            WalkVoiceStartFailure.SECURITY,
            microphoneGranted = false,
        )
        assertEquals("microphone_permission_missing", presentation.statusToken)
        assertFalse(recovery.blocksAutomaticRetry)
    }

    @Test
    fun serviceAndCallerKeepCleanupAndPermissionRecoveryWired() {
        val service = source("kr/co/hanium/dreamup/walksafe/voice/WalkVoiceForegroundService.kt")
        val activity = source("kr/co/hanium/dreamup/walksafe/voice/input/HandsFreeRuntimeSession.kt")
        val serviceStart = method(service, "startVoiceSession")
        val callerStart = method(activity, "maybeStartHandsFreeVoiceService")
        val failureUi = method(activity, "reportWalkVoiceStartFailure")
        val pendingFailureUi = method(activity, "showPendingHandsFreeVoiceStartFailure")
        val permissionRecovery = method(source("kr/co/hanium/dreamup/walksafe/session/permission/PermissionFlowController.kt"), "applyObservedPermissionStateChange")

        assertTrue(serviceStart.indexOf("try {") < serviceStart.indexOf("ensureForegroundStarted()"))
        assertTrue(serviceStart.contains("handleWalkVoiceServiceStartException("))
        assertTrue(serviceStart.contains("stopVoiceSession()"))
        assertTrue(serviceStart.contains("stopSelf()"))
        assertTrue(serviceStart.contains("app.reportWalkVoiceStartFailure(requestId, controller, failure)"))
        assertFalse(service.contains("foregroundStartOverride"))
        assertTrue(method(service, "ensureForegroundStarted").contains("startForeground("))
        assertFalse(method(service, "stopVoiceSession").contains("runCatching"))

        assertTrue(callerStart.contains("platform.startService(requestId)"))
        assertTrue(callerStart.contains("catch (error: SecurityException)"))
        assertTrue(callerStart.contains("catch (error: IllegalStateException)"))
        assertTrue(callerStart.contains("WalkVoiceStartFailure.SECURITY"))
        assertTrue(callerStart.contains("WalkVoiceStartFailure.FOREGROUND_START_RESTRICTED"))
        assertTrue(callerStart.contains("platform.reportStartFailure("))

        val securityGuard =
            "if (environment.foreground() && failure == WalkVoiceStartFailure.SECURITY && !microphoneGranted) {"
        val securityStart = failureUi.indexOf(securityGuard)
        val security = failureUi.substringAfter(securityGuard).substringBefore("\n        }")
        val stop = failureUi.indexOf("stopHandsFreeVoiceService()")
        assertTrue(stop >= 0 && stop < securityStart)
        assertTrue(security.contains("applicationEffects.permissionStateChanged("))
        assertTrue(failureUi.contains("showPendingHandsFreeVoiceStartFailure()"))
        assertTrue(pendingFailureUi.contains("presentation.navigationStatus("))
        assertTrue(pendingFailureUi.contains("presentation.status("))
        assertTrue(permissionRecovery.contains("runtime.stopHandsFree()"))
        assertTrue(permissionRecovery.contains("runtime.cancelVoiceRecognition()"))
        assertTrue(permissionRecovery.contains("showPermissionDenialPanel(stoppedOnly, reason)"))
    }
}
