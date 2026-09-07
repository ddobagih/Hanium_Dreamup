package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityInitialPermissionStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun firstEntryRequestsEveryMissingRuntimePermissionInOneBatchOnlyOnce() {
        val resume = functionBlock("private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection()")
        val request = functionBlock("private fun requestInitialAppEntryPermissionsIfNeeded()")
        val required = functionBlock("private fun requiredPostLoginDeviceCheckPermissions()")

        assertTrue(resume.contains("requestInitialAppEntryPermissionsIfNeeded()"))
        assertTrue(request.contains("PREF_INITIAL_APP_PERMISSION_REQUEST_COMPLETED"))
        assertTrue(request.contains("PREF_INITIAL_APP_PERMISSION_REQUEST_STARTED"))
        assertTrue(
            request.indexOf("permissionRequestLeases.values.any") <
                request.indexOf("PREF_INITIAL_APP_PERMISSION_REQUEST_STARTED"),
        )
        assertTrue(request.contains("if (requestCode == null)"))
        assertTrue(request.contains(".remove(PREF_INITIAL_APP_PERMISSION_REQUEST_STARTED)"))
        assertTrue(request.contains("requiredPostLoginDeviceCheckPermissions()"))
        assertTrue(request.contains("missing.toTypedArray()"))
        assertTrue(request.contains("PermissionRequestPurpose.INITIAL_APP_ENTRY"))
        assertTrue(required.contains("Manifest.permission.CAMERA"))
        assertTrue(required.contains("Manifest.permission.ACCESS_FINE_LOCATION"))
        assertTrue(required.contains("Manifest.permission.ACCESS_COARSE_LOCATION"))
        assertTrue(required.contains("Manifest.permission.RECORD_AUDIO"))
        assertTrue(required.contains("Manifest.permission.ACTIVITY_RECOGNITION"))
        assertTrue(required.contains("Manifest.permission.POST_NOTIFICATIONS"))
    }

    @Test
    fun initialDenialIsRememberedAndRequiresExitWhenAnyRequiredPermissionIsMissing() {
        val result = functionBlock("private fun handleInitialAppEntryPermissionResult()")
        val limitations = functionBlock("private fun showInitialAppPermissionLimitations(")
        val exit = functionBlock("private fun showRequiredAppPermissionExit(")
        val postLoginRequest = functionBlock("private fun requestedPostLoginDeviceCheckPermissions()")

        assertTrue(result.contains("PREF_INITIAL_APP_PERMISSION_REQUEST_COMPLETED"))
        assertTrue(result.contains(".remove(PREF_INITIAL_APP_PERMISSION_REQUEST_STARTED)"))
        assertTrue(result.contains("showInitialAppPermissionLimitations"))
        assertTrue(result.contains("if (missingAtResult.isNotEmpty())"))
        assertTrue(limitations.contains("showRequiredAppPermissionExit(missing)"))
        assertTrue(exit.contains("initialAppPermissionExitRequired = true"))
        assertTrue(exit.contains("cancelNativePrewalkPreparation(cancelFeatureEntry = true)"))
        assertTrue(exit.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertTrue(exit.contains("필수 권한이 부족하여 앱을 실행할 수 없습니다"))
        assertTrue(exit.contains(".setCancelable(false)"))
        assertTrue(exit.contains("finishAndRemoveTask()"))
        assertTrue(postLoginRequest.contains("emptyList()"))
        assertTrue(postLoginRequest.contains("PREF_INITIAL_APP_PERMISSION_REQUEST_COMPLETED"))
    }

    @Test
    fun optionalPermissionsAreObservedAfterInitialBatchWithoutFeaturePathRerequests() {
        val walkRequest = functionBlock("private fun maybeRequestMissingWalkSessionPermissions(")
        val navigationRequest = functionBlock("private fun ensureNavigationPermissions(")
        val voiceAction = functionBlock("private fun ensureVoicePermissionThenListen()")
        val voiceControls = functionBlock("private fun updateVoiceCommandButton(")
        val settings = functionBlock("private fun openAppSettings()")
        val observedState = functionBlock("private fun applyObservedPermissionStateChange(")
        val resume = functionBlock("private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection()")

        assertTrue(walkRequest.contains("permission == Manifest.permission.RECORD_AUDIO"))
        assertTrue(walkRequest.contains("permission == Manifest.permission.ACTIVITY_RECOGNITION"))
        assertTrue(walkRequest.contains("requestable.toTypedArray()"))
        assertFalse(navigationRequest.contains("missing += Manifest.permission.ACTIVITY_RECOGNITION"))
        assertTrue(navigationRequest.contains("step_tracking_limited"))
        assertFalse(voiceAction.contains("PermissionRequestPurpose.VOICE_COMMAND"))
        assertTrue(voiceAction.contains("showPermissionDenialPanel("))
        assertTrue(voiceControls.contains("hasRecordAudioPermission()"))
        assertTrue(
            settings.contains(
                "if (missing.isNotEmpty() || permissionRecoveryGate.blocksAutomaticResourceStart)",
            ),
        )
        assertTrue(observedState.contains("WalkSessionState.ACTIVE, WalkSessionState.PAUSED"))
        assertTrue(observedState.contains("permissionDenialPanel.visibility = View.GONE"))
        assertTrue(
            resume.indexOf("applyObservedPermissionStateChange(\"app_resumed\")") <
                resume.indexOf("syncActiveSessionScreenPolicy()"),
        )
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing function: $signature" }
        var depth = 0
        var opened = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> {
                    depth += 1
                    opened = true
                }
                '}' -> {
                    depth -= 1
                    if (opened && depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("unterminated function: $signature")
    }
}
