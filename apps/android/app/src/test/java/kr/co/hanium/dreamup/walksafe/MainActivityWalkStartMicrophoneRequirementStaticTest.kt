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
        val microphone = observed.substringAfter("if (!hasRecordAudioPermission()) {")
            .substringBefore("}")

        assertTrue(microphone.contains("stopHandsFreeVoiceService()"))
        assertTrue(microphone.contains("cancelVoiceCommandRecognition()"))
        assertFalse(microphone.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
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
        // Expression-bodied functions end at the next declaration.
        return source.substring(start).substringBefore("\n    private fun ", source.substring(start))
    }
}
