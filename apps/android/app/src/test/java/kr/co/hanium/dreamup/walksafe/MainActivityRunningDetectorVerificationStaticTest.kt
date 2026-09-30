package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S14-33: output gates require the verification token of the exact running detector instance;
 * the load Booleans alone (tfliteConfigLoaded/detectorAvailable) no longer open any output.
 */
class MainActivityRunningDetectorVerificationStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun everyDetectorOutputGateUsesTheInstanceToken() {
        listOf(
            "private fun currentArCoreQualitativeFeedbackAllowsWork(",
            "private fun currentFeedbackDeviceGateAllowsAlerts(",
            "private fun currentCameraFallbackAdvisoryGate(",
        ).forEach { signature ->
            val body = functionBlock(signature)
            assertTrue(signature, body.contains("runningDetectorVerifiedForOutput()"))
            assertFalse(signature, Regex("""detectorConfigLoaded\s*&&\s*detectorAvailable""").containsMatchIn(body))
            assertFalse(signature, body.contains("detectorAvailable = detectorAvailable"))
        }
        val deviceGate = source.substringAfter("return DeviceGateState(\n            cameraPermissionGranted = hasCameraPermission(),")
            .substringBefore("arSessionRunning = runtimeReady,")
        assertTrue(deviceGate.contains("detectorAvailable = runningDetectorVerifiedForOutput(),"))
        // Besides the token gate itself, only the pre-walk device-check readiness row reads the pair.
        val pairs = Regex("""detectorConfigLoaded\s*&&\s*detectorAvailable( ->)?""").findAll(source).toList()
        assertEquals(2, pairs.size)
        assertEquals(1, pairs.count { it.value.endsWith("->") })
    }

    @Test
    fun theTokenCheckBindsInstanceGenerationAndConfigBytes() {
        val gate = functionBlock("private fun runningDetectorVerifiedForOutput(")
        assertTrue(gate.contains("detectorConfigLoaded && detectorAvailable &&"))
        assertTrue(gate.contains(
            "runningDetectorVerification.allowsOutput(frameDetector, detectorLoadGeneration, reportModelConfigSha256)",
        ))
    }

    @Test
    fun everyAdoptedDetectorInstanceIsReverifiedAfterTheSwap() {
        val lines = source.lines()
        val adoptions = lines.withIndex().filter { (_, line) ->
            Regex("""^\s*frameDetector = (?!NoopAndroidFrameDetector)""").containsMatchIn(line)
        }
        assertEquals("bootstrap, GPU warm-up, two measured-profile swaps", 4, adoptions.size)
        adoptions.forEach { (index, line) ->
            val window = lines.subList(index, minOf(lines.size, index + 12)).joinToString("\n")
            assertTrue("missing re-verification after: ${line.trim()}", window.contains("scheduleRunningDetectorVerification("))
        }
        val verify = functionBlock("private fun scheduleRunningDetectorVerification(")
        assertTrue(verify.contains("detector.detectCalibrationImage("))
        assertTrue(verify.contains("if (frameDetector !== detector || detectorLoadGeneration != generation || detectorClosing) return@Runnable"))
        assertTrue(verify.contains("sampleInferencePassed = sample?.inferenceCompleted == true"))
        assertTrue(verify.contains("runningDetectorVerification.invalidate()"))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val next = Regex("""\n    (private |internal |override |@VisibleForTesting|/\*\*|fun )""")
            .find(source, start + signature.length)?.range?.first ?: source.length
        return source.substring(start, next)
    }
}
