package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S14-33-F1: MainActivity wires the bounded verification state into the device check, walk entry,
 * the camera status reason and a running walk (FP-039 failure_behavior). The state machine itself is
 * covered by inference.RunningDetectorVerificationRetryTest.
 */
class MainActivityRunningDetectorVerificationFailureStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun everyScheduledVerificationIsBoundedRetriedAndEndsInAnExplicitState() {
        val schedule = functionBlock("private fun scheduleRunningDetectorVerification(")
        val begin = schedule.indexOf("runningDetectorVerification.begin(detector, SystemClock.elapsedRealtime())")
        val deadline = schedule.indexOf("{ expireRunningDetectorVerification(detector) }")
        assertTrue(begin >= 0 && deadline > begin)
        assertTrue(schedule.contains("runningDetectorVerification.pendingDeadlineMs,"))
        assertTrue(schedule.indexOf("if (!retryAttempt) {") in 0 until begin)
        assertTrue(schedule.contains("runningDetectorVerification.recordAttempt("))
        val retry = schedule.substringAfter("RunningDetectorVerificationStep.RETRY ->")
            .substringBefore("RunningDetectorVerificationStep.FAILED ->")
        assertTrue(retry.contains("scheduleRunningDetectorVerification(detector, modelKey, fallbackUsed, retryAttempt = true)"))
        assertTrue(retry.contains("runningDetectorVerification.retryDelayMs,"))
        val failed = schedule.substringAfter("RunningDetectorVerificationStep.FAILED ->")
            .substringBefore("RunningDetectorVerificationStep.VERIFIED ->")
        assertTrue(failed.contains("runOnUiThread { disableDetectorAfterVerificationFailure(detector) }"))
        val verified = schedule.substringAfter("RunningDetectorVerificationStep.VERIFIED ->")
            .substringBefore("RunningDetectorVerificationStep.STALE ->")
        assertTrue(verified.contains("maybeContinuePostLoginDeviceCheck()"))
        val rejected = schedule.substringAfter("catch (_: RejectedExecutionException) {")
        assertTrue(rejected.contains("if (runningDetectorVerification.fail(detector)) {"))
        assertTrue(rejected.contains("disableDetectorAfterVerificationFailure(detector)"))
        val expire = functionBlock("private fun expireRunningDetectorVerification(")
        assertTrue(expire.contains("if (runningDetectorVerification.expire(detector, SystemClock.elapsedRealtime())) {"))
        assertTrue(expire.contains("disableDetectorAfterVerificationFailure(detector)"))
    }

    @Test
    fun aFailedVerificationDisablesTheInstanceAndStopsARunningWalk() {
        val disable = functionBlock("private fun disableDetectorAfterVerificationFailure(")
        val guard = disable.indexOf("if (frameDetector !== detector || detectorClosing) return@synchronized false")
        assertTrue(guard >= 0)
        listOf(
            "frameDetector = NoopAndroidFrameDetector()",
            "detectorLoadGeneration += 1L",
            "detectorAvailable = false",
            "detectorLoadReason = kr.co.hanium.dreamup.walksafe.device.RunningDetectorReadiness.SAMPLE_INFERENCE_UNVERIFIED_REASON",
            "latestDetectionSnapshot = DetectionSnapshot.empty()",
        ).forEach { assertTrue(it, disable.indexOf(it) > guard) }
        assertTrue(disable.contains("detectorExecutor.execute { detector.close() }"))
        assertTrue(disable.contains("runtimeObstacleDetectionCapabilityOverride = false"))
        assertTrue(disable.contains("metricDistanceCapabilityOverride = false"))
        assertTrue(disable.contains(
            "walkSessionLifecycle.snapshot().state in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)",
        ))
        assertTrue(disable.contains("if (walking) enterWalkSessionSafetyStopAndCancelOutputs(\"detector_runtime_failed\")"))
        assertTrue(disable.contains("RunningDetectorReadiness.WALK_BLOCK_NOTICE_KO"))
        assertTrue(disable.contains("maybeContinuePostLoginDeviceCheck()"))
        // The existing device re-check reloads a detector that is unavailable and not closing.
        val primary = functionBlock("private fun handlePostLoginDeviceCheckPrimaryAction()")
        assertTrue(primary.contains("if (!detectorAvailable && !detectorClosing) detectorLoadAttempted = false"))
    }

    @Test
    fun deviceCheckWalkEntryAndCameraReasonUseTheVerificationState() {
        val observation = functionBlock("private fun currentPostLoginDeviceCheckObservation()")
        val row = observation.substringAfter("detector = when {").substringBefore("cameraPipeline = cameraPipeline,")
        assertTrue(row.contains("!detectorLoadAttempted -> PostLoginDeviceCheckSignal.PENDING"))
        assertTrue(row.contains(
            "kr.co.hanium.dreamup.walksafe.device.RunningDetectorReadiness.deviceCheckSignal(\n" +
                "                        runningDetectorVerification.state(frameDetector),",
        ))
        assertTrue(!row.contains("detectorConfigLoaded && detectorAvailable -> PostLoginDeviceCheckSignal.READY"))

        val block = functionBlock("private fun walkSessionReadinessBlockReason(")
        val entry = block.indexOf("if (runningDetectorBlocksWalkEntry()) {")
        assertTrue(entry >= 0 && entry < block.indexOf("if (!cameraAnalysisFeaturesEnabled()) {"))
        assertTrue(block.contains("return kr.co.hanium.dreamup.walksafe.device.RunningDetectorReadiness.WALK_BLOCK_NOTICE_KO"))
        val helper = functionBlock("private fun runningDetectorBlocksWalkEntry()")
        assertTrue(helper.contains("runningDetectorVerification.state(frameDetector), detectorAvailable, detectorLoadReason,"))

        val reasons = source.substringAfter("val cameraReason = when {").substringBefore("else -> null")
        assertTrue(reasons.indexOf("runningDetectorBlocksWalkEntry() ->") in
            0 until reasons.indexOf("!cameraAnalysisFeaturesEnabled() ->"))
        val checking = reasons.indexOf("RunningDetectorReadiness.CHECKING_NOTICE_KO")
        assertTrue(checking in 0 until reasons.indexOf("\"객체인식 엔진이 준비됐지만 실제 안내 출력 조건을 확인하고 있습니다.\""))
    }

    @Test
    fun theOutputGateGenerationIsVolatile() {
        assertEquals(1, Regex("""@Volatile\n    private var detectorLoadGeneration = 0L""").findAll(source).count())
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val next = Regex("""\n    (private |internal |override |@VisibleForTesting|/\*\*|fun )""")
            .find(source, start + signature.length)?.range?.first ?: source.length
        return source.substring(start, next)
    }
}
