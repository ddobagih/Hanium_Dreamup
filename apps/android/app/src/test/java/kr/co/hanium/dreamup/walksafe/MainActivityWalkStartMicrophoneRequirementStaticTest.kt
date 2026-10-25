package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S02-14: FP-014-DETAIL-01 fixes camera, precise location and microphone as the OS permissions of
 * every walk start and resume. The start check, permission recovery gate and settings return use
 * the one WalkStartPermissionPolicy set; revocation during a walk still stops only voice features.
 */
class MainActivityWalkStartMicrophoneRequirementStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun walkStartAndRecoveryUseThePolicyPermissionSet() {
        val required = functionBlock("private fun requiredStartWalkObservedPermissions()")
        val missing = functionBlock("private fun missingRequiredWalkSessionPermissions(")

        assertTrue(
            required.contains(
                "WalkStartPermissionPolicy.required(activityRecognitionRequired = false)",
            ),
        )
        assertFalse(required.contains("cameraAnalysisFeaturesEnabled()"))
        assertFalse(required.contains("postLoginDeviceFeatureEnabled("))
        assertTrue(missing.contains("requiredStartWalkObservedPermissions()"))
        assertTrue(missing.contains("ObservedPermission.MICROPHONE in missing"))
        assertTrue(missing.contains("Manifest.permission.RECORD_AUDIO"))
        assertFalse(missing.contains("cameraAnalysisFeaturesEnabled()"))
    }

    @Test
    fun microphoneRevocationDuringAWalkStillStopsOnlyVoiceFeatures() {
        val observed = functionBlock("private fun applyObservedPermissionStateChange(")
        val microphone = observed.substringAfter("if (!platform.microphoneGranted()) {")
            .substringBefore("}")

        assertTrue(microphone.contains("runtime.stopHandsFree()"))
        assertTrue(microphone.contains("runtime.cancelVoiceRecognition()"))
        assertFalse(microphone.contains("runtime.enterSafetyStop("))
        val binding = MainActivityRefactorSource.composition(source, "permissionFlowController")
        assertTrue(binding.contains("override fun stopHandsFree() { stopHandsFreeVoiceService() }"))
        assertTrue(binding.contains("override fun cancelVoiceRecognition() { cancelVoiceCommandRecognition() }"))
    }

    private fun functionBlock(signature: String): String = MainActivityRefactorSource.function(source, signature)
}
