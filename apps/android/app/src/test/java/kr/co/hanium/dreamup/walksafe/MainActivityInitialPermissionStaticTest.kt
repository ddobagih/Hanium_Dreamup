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
        val resume = MainActivityRefactorSource.function(source, "private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection()")
        val request = MainActivityRefactorSource.function(source, "private fun requestInitialAppEntryPermissionsIfNeeded()")
        val launch = MainActivityRefactorSource.function(source, "private fun launchInitialAppEntryPermissionRequest(")
        val required = MainActivityRefactorSource.function(source, "private fun requiredPostLoginDeviceCheckPermissions()")

        assertTrue(resume.contains("requestInitialAppEntryPermissionsIfNeeded()"))
        assertTrue(request.contains("preferences.initialCompleted()"))
        assertTrue(request.contains("preferences.initialStarted()"))
        assertTrue(
            request.indexOf("permissionRequestLeases.values.any") <
                request.indexOf("preferences.initialStarted()"),
        )
        assertTrue(request.contains("launchInitialAppEntryPermissionRequest(missing)"))
        assertTrue(launch.contains("if (requestCode == null)"))
        assertTrue(request.contains("preferences.markInitialCompleted()"))
        assertTrue(launch.contains("preferences.clearInitialStarted()"))
        assertTrue(request.contains("requiredPostLoginDeviceCheckPermissions()"))
        assertTrue(launch.contains("missing.toTypedArray()"))
        assertTrue(launch.contains("PermissionRequestPurpose.INITIAL_APP_ENTRY"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            launch,
            "preferences.markInitialStarted()",
            "if (!attemptSaved)",
            "requestPermissionsWithLease(",
            "if (requestCode == null)",
            "preferences.clearInitialStarted()",
        ))
        val preferenceSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/AndroidPermissionPreferences.kt").readText()
        val binding = MainActivityRefactorSource.composition(source, "permissionFlowController")
        assertTrue(binding.contains("PREF_INITIAL_APP_PERMISSION_REQUEST_STARTED, PREF_INITIAL_APP_PERMISSION_REQUEST_COMPLETED"))
        assertTrue(MainActivityRefactorSource.member(preferenceSource, "initialStarted").contains("getBoolean(initialStartedKey, false)"))
        assertTrue(MainActivityRefactorSource.member(preferenceSource, "initialCompleted").contains("getBoolean(initialCompletedKey, false)"))
        assertTrue(MainActivityRefactorSource.member(preferenceSource, "markInitialStarted").contains("putBoolean(initialStartedKey, true).commit()"))
        assertTrue(MainActivityRefactorSource.member(preferenceSource, "clearInitialStarted").contains("remove(initialStartedKey).commit()"))
        assertTrue(MainActivityRefactorSource.member(preferenceSource, "markInitialCompleted").contains("remove(initialStartedKey).commit()"))
        assertTrue(required.contains("Manifest.permission.CAMERA"))
        assertTrue(required.contains("Manifest.permission.ACCESS_FINE_LOCATION"))
        assertTrue(required.contains("Manifest.permission.ACCESS_COARSE_LOCATION"))
        assertTrue(required.contains("Manifest.permission.RECORD_AUDIO"))
        assertTrue(required.contains("Manifest.permission.ACTIVITY_RECOGNITION"))
        assertTrue(required.contains("Manifest.permission.POST_NOTIFICATIONS"))
    }

    @Test
    fun initialDenialIsRememberedAndRequiresExitWhenAnyRequiredPermissionIsMissing() {
        val result = MainActivityRefactorSource.function(source, "private fun handleInitialAppEntryPermissionResult()")
        val limitations = MainActivityRefactorSource.function(source, "private fun showInitialAppPermissionLimitations(")
        val exit = MainActivityRefactorSource.function(source, "private fun showRequiredAppPermissionExit(")
        val postLoginRequest = MainActivityRefactorSource.function(source, "private fun requestedPostLoginDeviceCheckPermissions()")

        val preferenceSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/AndroidPermissionPreferences.kt").readText()
        val dialog = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/AndroidPermissionUi.kt").readText(), "showRequiredExit")
        val binding = MainActivityRefactorSource.composition(source, "permissionFlowController")
        val uiBinding = MainActivityRefactorSource.composition(source, "permissionFlowUi")
        assertTrue(binding.contains("override fun cancelPrewalk() { cancelNativePrewalkPreparation(cancelFeatureEntry = true) }"))
        assertTrue(binding.contains("override fun safetyStop(reason: String) { enterWalkSessionSafetyStopAndCancelOutputs(reason) }"))
        assertTrue(binding.contains("ui = permissionFlowUi"))
        assertTrue(uiBinding.contains("::openAppSettings, ::finishAndRemoveTask"))
        assertTrue(result.contains("preferences.markInitialCompleted()"))
        assertTrue(MainActivityRefactorSource.member(preferenceSource, "markInitialCompleted").contains("remove(initialStartedKey).commit()"))
        assertTrue(result.contains("showInitialAppPermissionLimitations"))
        assertTrue(result.contains("if (missingAtResult.isNotEmpty())"))
        assertTrue(limitations.contains("showRequiredAppPermissionExit(missing)"))
        assertTrue(exit.contains("initialAppPermissionExitRequired = true"))
        assertTrue(exit.contains("runtime.cancelPrewalk()"))
        assertTrue(exit.contains("runtime.safetyStop("))
        assertTrue(dialog.contains("필수 권한이 없어 보행을 시작할 수 없습니다. 설정에서 "))
        assertTrue(dialog.contains("missing.joinToString { PermissionExplanationText.permissionLabelKo(it) }"))
        assertTrue(dialog.contains("setOnClickListener { openSettings() }"))
        assertTrue(dialog.contains(".setCancelable(false)"))
        assertTrue(dialog.contains("setOnClickListener { finishTask() }"))
        assertTrue(postLoginRequest.contains("emptyList()"))
        assertTrue(postLoginRequest.contains("preferences.initialCompleted()"))
    }

    @Test
    fun optionalPermissionsAreObservedAfterInitialBatchWithoutFeaturePathRerequests() {
        val walkRequest = MainActivityRefactorSource.function(source, "private fun maybeRequestMissingWalkSessionPermissions(")
        val navigationRequest = MainActivityRefactorSource.function(source, "private fun ensureNavigationPermissions(")
        val voiceAction = MainActivityRefactorSource.function(source, "private fun ensureVoicePermissionThenListen()")
        val voiceControls = MainActivityRefactorSource.function(source, "private fun updateVoiceCommandButton(")
        val settings = MainActivityRefactorSource.member(source, "openAppSettings")
        val observedState = MainActivityRefactorSource.function(source, "private fun applyObservedPermissionStateChange(")
        val resume = MainActivityRefactorSource.function(source, "private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection()")

        val prepareSettings = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/PermissionFlowController.kt").readText(), "prepareForAppSettings")
        val binding = MainActivityRefactorSource.composition(source, "permissionFlowController")
        assertTrue(binding.contains("requiredWalkPermissions = ::requiredWalkSessionPermissionsForObservedSnapshot"))
        assertTrue(settings.contains("permissionFlowController.prepareForAppSettings()"))
        val voiceBinding = MainActivityRefactorSource.composition(source, "voiceInputController")
        assertTrue(voiceBinding.contains("override fun showMicrophoneDenial()"))
        assertTrue(voiceBinding.contains("showPermissionDenialPanel("))
        val uiSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/AndroidPermissionUi.kt").readText()
        assertTrue(MainActivityRefactorSource.member(uiSource, "hidePanel").contains("permissionDenialPanel.visibility = View.GONE"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            walkRequest,
            "observedPermissions = ::currentObservedPermissionSnapshot",
            "missingPermissionsForObservedSnapshot = requiredWalkPermissions",
        ))
        assertTrue(walkRequest.contains("requestPermissionsWithLease(permissions, PermissionRequestPurpose.WALK_SESSION)"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            MainActivityRefactorSource.function(source, "internal fun requestSelectedWalkSessionPermissions("),
            "if (requestable.isEmpty()) return false",
            "walkSessionPermissionRequestAttempted = true",
            "walkSessionPermissionRequestInFlight = true",
            "requestPermissions(requestable.toTypedArray())",
        ))
        assertFalse(navigationRequest.contains("missing += Manifest.permission.ACTIVITY_RECOGNITION"))
        assertTrue(navigationRequest.contains("step_tracking_limited"))
        assertFalse(voiceAction.contains("PermissionRequestPurpose.VOICE_COMMAND"))
        assertTrue(voiceAction.contains("applicationEffects.showMicrophoneDenial()"))
        assertTrue(voiceControls.contains("hasRecordAudioPermission()"))
        assertTrue(
            prepareSettings.contains(
                "if (missing.isNotEmpty() || permissionRecoveryGate.blocksAutomaticResourceStart)",
            ),
        )
        assertTrue(observedState.contains("WalkSessionState.ACTIVE, WalkSessionState.PAUSED"))
        assertTrue(observedState.contains("ui.hidePanel()"))
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
