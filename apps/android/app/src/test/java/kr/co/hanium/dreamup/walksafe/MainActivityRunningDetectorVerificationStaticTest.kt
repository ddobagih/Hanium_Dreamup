package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MainActivityRunningDetectorVerificationStaticTest {
    private fun source(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path.kt").readText()
    private val main = source("MainActivity")
    private val lifecycle = source("runtime/vision/primary/PrimaryDetectorLifecycleController")
    @Test fun everyDetectorOutputGateUsesTheInstanceToken() {
        for (name in listOf("currentArCoreQualitativeFeedbackAllowsWork", "currentFeedbackDeviceGateAllowsAlerts", "currentCameraFallbackAdvisoryGate")) {
            val body = MainActivityRefactorSource.member(main, name)
            assertTrue(name, body.contains("runningDetectorVerifiedForOutput()"))
            assertFalse(body.contains("detectorAvailable = detectorAvailable"))
            assertFalse(Regex("detectorConfigLoaded\\s*&&\\s*detectorAvailable").containsMatchIn(body))
        }
        val gate = MainActivityRefactorSource.member(main, "buildDeviceGateState")
        assertTrue(gate.contains("detectorAvailable = runningDetectorVerifiedForOutput()"))
        assertTrue(MainActivityRefactorSource.member(main, "runningDetectorVerifiedForOutput").contains("primaryDetectorLifecycleController.verifiedForOutput()"))
    }
    @Test fun theTokenCheckBindsInstanceGenerationAndConfigBytes() {
        val gate = MainActivityRefactorSource.member(lifecycle, "verifiedForOutput")
        assertTrue(gate.contains("configLoaded && detector.projection().available && verification.allowsOutput("))
        assertTrue(gate.contains("detector.projection().detector, detector.projection().loadGeneration, configuration.sha256()"))
    }
    @Test fun everyAdoptedDetectorInstanceIsReverifiedAfterTheSwap() {
        val calibration = source("runtime/vision/primary/PrimaryRuntimeCalibrationCoordinator")
        assertEquals(2, Regex("effects\\.verifyRunningDetector\\(").findAll(calibration).count())
        assertTrue(ReportStaticSourceInspector.appearsInOrder(calibration,
            "detector.restoreMeasuredIfCurrent(replacement, current)", "effects.verifyRunningDetector(replacement,"))
        val adopt = MainActivityRefactorSource.member(calibration, "adopt")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(adopt, "detector.adoptMeasuredIfCurrent(",
            "afterGeneration = {", "effects.verifyRunningDetector("))
        assertTrue(main.contains("scheduleRunningDetectorVerification(detector as TfliteAndroidFrameDetector, modelKey, fallbackUsed)"))
        val load = MainActivityRefactorSource.member(lifecycle, "loadForCurrentProcess")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(load, "detector.adoptLoadedOnNativeLane(",
            "detector.admitLoadedOnMain(", "scheduleVerification(instance, load.modelKey, load.fallbackUsed)"))
        assertTrue(load.contains("requestGeneration != loadRequestGeneration"))
        val verify = MainActivityRefactorSource.member(lifecycle, "scheduleVerification")
        assertTrue(verify.contains("platform.sample(instance)"))
        assertTrue(source("runtime/vision/primary/AndroidPrimaryLifecyclePlatform").contains("detector.detectCalibrationImage("))
        assertTrue(verify.contains("val step = recordVerification(instance, identity,"))
        assertTrue(verify.contains("sample?.inferenceCompleted == true), generation, elapsedRealtimeMs)"))
        assertTrue(verify.contains("verification.invalidate()"))
        val record = MainActivityRefactorSource.member(lifecycle, "recordVerification")
        listOf("synchronized(frameStateLock)", "detector.projection().detector !== instance",
            "detector.projection().loadGeneration != expectedGeneration", "detector.projection().closing",
            "return@synchronized RunningDetectorVerificationStep.STALE", "verification.recordAttempt(instance, identity, evidence, nowMs())")
            .forEach { assertTrue(it, record.contains(it)) }
    }
}
