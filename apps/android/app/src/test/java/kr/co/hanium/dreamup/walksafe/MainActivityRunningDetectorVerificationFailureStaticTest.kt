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
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectorLifecycleController.kt").readText()
        MainActivityRefactorSource.composition(source, "primaryDetectorLifecycleController")
        val schedule = MainActivityRefactorSource.member(owner, "scheduleVerification")
        val begin = schedule.indexOf("beginVerification(instance, elapsedRealtimeMs())")
        val deadline = schedule.indexOf("{ expireVerification(instance) }")
        assertTrue(begin >= 0 && deadline > begin)
        assertTrue(schedule.contains("verification.pendingDeadlineMs)"))
        assertTrue(schedule.indexOf("if (!retryAttempt) {") in 0 until begin)
        val beginBody = MainActivityRefactorSource.member(owner, "beginVerification")
        assertTrue(beginBody.contains("verification.begin(instance, nowMs)"))
        assertTrue(schedule.contains("val step = recordVerification("))
        val record = MainActivityRefactorSource.member(owner, "recordVerification")
        val owned = record.indexOf("if (detector.projection().detector !== instance || detector.projection().loadGeneration != expectedGeneration || detector.projection().closing)")
        val observed = record.indexOf("verification.recordAttempt(instance, identity, evidence, nowMs())")
        assertTrue(owned >= 0 && observed > owned)
        assertTrue(MainActivityRefactorSource.member(source, "recordRunningDetectorVerification").contains("nowElapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime"))
        assertTrue(record.contains("RunningDetectorVerificationStep.STALE"))
        val retry = schedule.substringAfter("RunningDetectorVerificationStep.RETRY ->")
            .substringBefore("RunningDetectorVerificationStep.FAILED ->")
        assertTrue(retry.contains("scheduleVerification(instance, modelKey, fallbackUsed, retryAttempt = true)"))
        assertTrue(retry.contains("verification.retryDelayMs)"))
        val failed = schedule.substringAfter("RunningDetectorVerificationStep.FAILED ->")
            .substringBefore("RunningDetectorVerificationStep.VERIFIED ->")
        assertTrue(failed.contains("presentation.onUi { disableUnverified(instance) }"))
        val verified = schedule.substringAfter("RunningDetectorVerificationStep.VERIFIED ->")
            .substringBefore("RunningDetectorVerificationStep.STALE ->")
        assertTrue(verified.contains("preparation.modelVerified()"))
        val rejected = schedule.substringAfter("catch (_: RejectedExecutionException) {")
        assertTrue(rejected.contains("if (verification.fail(instance)) presentation.onUi {"))
        assertTrue(rejected.contains("disableUnverified(instance)"))
        val expire = MainActivityRefactorSource.member(owner, "expireVerification")
        assertTrue(expire.contains("if (verification.expire(instance, elapsedRealtimeMs())) disableUnverified(instance)"))
        assertTrue(expire.contains("disableUnverified(instance)"))
    }

    @Test
    fun aFailedVerificationDisablesTheInstanceAndStopsARunningWalk() {
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectorLifecycleController.kt").readText()
        val execution = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectorExecutionOwner.kt").readText()
        val disable = MainActivityRefactorSource.member(owner, "disableUnverified")
        val nativeDisable = MainActivityRefactorSource.member(execution, "disableUnverified")
        val binding = MainActivityRefactorSource.composition(source, "primaryDetectorLifecycleController")
        val guard = nativeDisable.indexOf("if (detector !== expectedDetector || closing) return@synchronized false")
        assertTrue(guard >= 0)
        listOf("detector = NoopAndroidFrameDetector()", "loadGeneration += 1L", "available = false")
            .forEach { assertTrue(it, nativeDisable.indexOf(it) > guard) }
        assertTrue(nativeDisable.indexOf("commit()") > nativeDisable.indexOf("available = false"))
        assertTrue(disable.contains("detector.disableUnverified(instance) {"))
        assertTrue(disable.contains("loadReason = RunningDetectorReadiness.SAMPLE_INFERENCE_UNVERIFIED_REASON"))
        assertTrue(disable.contains("frame().clearDetectionSnapshot()"))
        val frameOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FrameSynthesisController.kt").readText()
        assertTrue(MainActivityRefactorSource.member(frameOwner, "clearDetectionSnapshot").contains("latestDetectionSnapshot = DetectionSnapshot.empty()"))
        assertTrue(disable.contains("detector.nativeLane.execute { platform.close(instance) }"))
        assertTrue(binding.contains("runtimeObstacleDetectionCapabilityOverride = false"))
        assertTrue(binding.contains("runtimeMetricSession.restoreCapability(false)"))
        assertTrue(binding.contains(
            "walkSessionLifecycle.snapshot().state in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)",
        ))
        assertTrue(disable.contains("if (walking) failureEffects.enterSafetyStop()"))
        assertTrue(binding.contains("RunningDetectorReadiness.WALK_BLOCK_NOTICE_KO"))
        assertTrue(disable.contains("preparation.modelVerified()"))
        // The existing device re-check reloads a detector that is unavailable and not closing.
        val primary = MainActivityRefactorSource.function(source, "private fun handlePostLoginDeviceCheckPrimaryAction()")
        val retry = MainActivityRefactorSource.member(owner, "allowLoadRetryIfUnavailable")
        assertTrue(primary.contains("effects.prepareDetectorRetry()"))
        assertTrue(MainActivityRefactorSource.member(source, "createPostLoginDeviceCheckController").contains("primaryDetectorLifecycleController.allowLoadRetryIfUnavailable()"))
        assertTrue(retry.contains("!detector.projection().available && !detector.projection().closing && !loadInFlight"))
        assertTrue(retry.contains("loadAttempted = false"))
    }

    @Test
    fun deviceCheckWalkEntryAndCameraReasonUseTheVerificationState() {
        val observation = MainActivityRefactorSource.function(source, "private fun currentPostLoginDeviceCheckObservation()")
        val deviceBinding = MainActivityRefactorSource.member(source, "createPostLoginDeviceCheckController")
        val lifecycle = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectorLifecycleController.kt").readText()
        val row = observation.substringAfter("detector = when {").substringBefore("cameraPipeline = cameraPipeline,")
        assertTrue(row.contains("!detector.loadAttempted || detector.loadInFlight -> PostLoginDeviceCheckSignal.PENDING"))
        assertTrue(row.contains("detector.configLoaded && detector.available -> detector.readiness"))
        assertTrue(deviceBinding.contains("RunningDetectorReadiness.deviceCheckSignal(primaryDetectorLifecycleController.verificationState(primaryDetectorOwner.projection().detector))"))
        assertTrue(MainActivityRefactorSource.member(lifecycle, "verificationState").contains("verification.state(instance)"))
        assertTrue(!row.contains("detector.configLoaded && detector.available -> PostLoginDeviceCheckSignal.READY"))

        val block = MainActivityRefactorSource.function(source, "private fun walkSessionReadinessBlockReason(")
        val readinessBinding = MainActivityRefactorSource.composition(source, "walkReadinessBlockController")
        val entry = block.indexOf("if (sources.runningDetectorBlocksEntry()) {")
        assertTrue(entry >= 0 && entry < block.indexOf("if (!sources.cameraEnabled()) {"))
        assertTrue(block.contains("return kr.co.hanium.dreamup.walksafe.device.RunningDetectorReadiness.WALK_BLOCK_NOTICE_KO"))
        val helper = MainActivityRefactorSource.member(lifecycle, "blocksWalkEntry")
        assertTrue(helper.contains("verification.state(detector.projection().detector),\n        detector.projection().available, loadReason"))

        assertTrue(readinessBinding.contains("override fun runningDetectorBlocksEntry() = runningDetectorBlocksWalkEntry()"))
        val reasons = source.substringAfter("val cameraReason = when {").substringBefore("else -> null")
        assertTrue(reasons.indexOf("runningDetectorBlocksWalkEntry() ->") in
            0 until reasons.indexOf("!cameraAnalysisFeaturesEnabled() ->"))
        val checking = reasons.indexOf("RunningDetectorReadiness.CHECKING_NOTICE_KO")
        assertTrue(checking in 0 until reasons.indexOf("\"객체인식 엔진이 준비됐지만 실제 안내 출력 조건을 확인하고 있습니다.\""))
    }

    @Test
    fun theOutputGateGenerationIsVolatile() {
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectorExecutionOwner.kt").readText()
        val compositionSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/composition/VisionPrimaryComposition.kt").readText()
        assertTrue(MainActivityRefactorSource.property(source, "primaryDetectorOwner").contains("by visionPrimaryComposition.detector"))
        assertTrue(MainActivityRefactorSource.property(source, "visionPrimaryComposition").contains("createVisionPrimaryComposition()"))
        assertTrue(MainActivityRefactorSource.member(source, "createVisionPrimaryComposition").contains("VisionPrimaryComposition("))
        assertTrue(MainActivityRefactorSource.member(source, "createVisionPrimaryComposition").contains("{ frameStateLock }, SystemClock::elapsedRealtime"))
        val actualDetector = MainActivityRefactorSource.property(compositionSource, "detector")
        assertTrue(actualDetector.contains("Lazy<PrimaryDetectorExecutionOwner> = lazy"))
        assertTrue(actualDetector.contains("PrimaryDetectorExecutionOwner(stateLock = frameLock()"))
        assertEquals(1, Regex("""@Volatile\s+private var loadGeneration = 0L""").findAll(owner).count())
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val next = Regex("""\n    (private |internal |override |@VisibleForTesting|/\*\*|fun )""")
            .find(source, start + signature.length)?.range?.first ?: source.length
        return source.substring(start, next)
    }
}
