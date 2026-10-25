package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.navigation.positioning.MotionCalibrationRejectionReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.MotionCalibrationStatus
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianMotionCalibrationResult
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingCalibrationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual coordinator/Main callback composition; no sensor, native setter, disk, or UI is run. */
class MainActivityWalkingCalibrationResultRegressionTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun firstAcceptedWarmupKeepsTheDefaultAndDisplaysAnUncalibratedState() {
        val coordinator = PositioningCoordinator()
        val first = coordinator.calibrateProfile(sample())

        assertWarmup(first, coordinator, acceptedSamples = 1L)
        val effects = applyWithActualMain(first, coordinator)

        assertWarmupEffects(effects)
    }

    @Test
    fun secondAcceptedWarmupStillKeepsTheDefaultAndDoesNotClaimAnUpdatedStepLength() {
        val coordinator = PositioningCoordinator()
        val first = coordinator.calibrateProfile(sample())
        assertWarmup(first, coordinator, acceptedSamples = 1L)
        val second = coordinator.calibrateProfile(sample())

        assertWarmup(second, coordinator, acceptedSamples = 2L)
        val effects = applyWithActualMain(second, coordinator)

        assertWarmupEffects(effects)
    }

    @Test
    fun thirdAcceptedSampleAppliesAndPersistsTheActualUpdatedStepLength() {
        val coordinator = PositioningCoordinator()
        repeat(2) { coordinator.calibrateProfile(sample()) }
        val third = coordinator.calibrateProfile(sample())
        assertEquals(MotionCalibrationStatus.UPDATED, third.status)
        assertNull(third.rejectionReason)
        assertEquals(3L, third.snapshot.acceptedSampleCount)
        assertTrue(third.snapshot.isCalibrated)
        assertEquals(0.6875, third.snapshot.stepLengthM, 1e-12)
        assertEquals(third.snapshot, coordinator.currentProfile())

        val effects = applyWithActualMain(third, coordinator)

        assertEquals(1, effects.currentStepLengthReads)
        assertEquals(listOf(0.6875f), effects.appliedStepLengths)
        assertEquals(1, effects.persistCalls)
        assertEquals(listOf("navigation=step_length_updated=0.69m"), effects.statuses)
    }

    @Test
    fun anActuallyRejectedSampleRunsNoneOfTheMainCalibrationEffects() {
        val coordinator = PositioningCoordinator()
        val rejected = coordinator.calibrateProfile(sample().copy(trustedPositionSegment = false))
        assertEquals(MotionCalibrationStatus.REJECTED, rejected.status)
        assertEquals(MotionCalibrationRejectionReason.UNTRUSTED_POSITION, rejected.rejectionReason)
        assertEquals(0L, rejected.snapshot.acceptedSampleCount)
        assertEquals(1L, rejected.snapshot.rejectedSampleCount)
        assertFalse(rejected.snapshot.isCalibrated)
        assertEquals(0.65, rejected.snapshot.stepLengthM, 0.0)

        val effects = applyWithActualMain(rejected, coordinator)

        assertEquals(0, effects.currentStepLengthReads)
        assertTrue(effects.appliedStepLengths.isEmpty())
        assertEquals(0, effects.persistCalls)
        assertTrue(effects.statuses.isEmpty())
    }

    @Test
    fun sourceOnlyTheActualSampleWrapperUsesTheCoordinatorAndSharedEffectsWithAllExistingNativeConsumers() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val wrapper = MainActivityRefactorSource.function(source, "private fun applyWalkingCalibrationSample(")
        val calibration = wrapper.indexOf("positioningCoordinator.calibrateProfile(sample)")
        val applyResult = wrapper.indexOf("applyWalkingCalibrationResult(")
        assertTrue(calibration >= 0)
        assertTrue(applyResult > calibration)
        assertTrue(wrapper.contains("calibration = calibration"))
        assertTrue(wrapper.contains("currentStepLengthM = { positioningCoordinator.currentProfile().stepLengthM.toFloat() }"))
        assertTrue(wrapper.contains("effects.updateStepLength(stepLengthM)"))
        val wiring = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(wiring.contains("stepLengthEstimator.setStepLengthM(stepLengthM)"))
        assertTrue(wiring.contains("frameSynthesisOwner.setUserStepLength(stepLengthM)"))
        MainActivityRefactorSource.composition(source, "frameSynthesisOwner")
        val frame = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FrameSynthesisController.kt").readText()
        val nativeConsumers = MainActivityRefactorSource.member(frame, "setUserStepLength")
        assertTrue(nativeConsumers.contains("objectDepthPipeline.setUserStepLength(value)"))
        assertTrue(nativeConsumers.contains("currentObjectDepthPipeline.setUserStepLength(value)"))
        assertTrue(wrapper.contains("persistProfile = ::persistPositioningProfileForCurrentActor"))
        assertTrue(wrapper.contains("updateStatus = effects::navigationStatus"))
        assertTrue(wiring.contains("override fun navigationStatus(text: String) = updateNavigationStatus(text)"))
    }

    private fun assertWarmup(
        result: PedestrianMotionCalibrationResult,
        coordinator: PositioningCoordinator,
        acceptedSamples: Long,
    ) {
        assertEquals(MotionCalibrationStatus.ACCEPTED_WARMUP, result.status)
        assertNull(result.rejectionReason)
        assertEquals(acceptedSamples, result.snapshot.acceptedSampleCount)
        assertEquals(0L, result.snapshot.rejectedSampleCount)
        assertFalse(result.snapshot.isCalibrated)
        assertEquals(0.65, result.snapshot.stepLengthM, 0.0)
        assertEquals(result.snapshot, coordinator.currentProfile())
    }

    private fun assertWarmupEffects(effects: Effects) {
        assertEquals(1, effects.currentStepLengthReads)
        assertEquals(listOf(0.65f), effects.appliedStepLengths)
        assertEquals(1, effects.persistCalls)
        assertFalse("An accepted warmup has not updated the calibrated step length", effects.statuses.any {
            "step_length_updated" in it
        })
        assertEquals(1, effects.statuses.size)
        assertTrue("The status must identify that the profile remains uncalibrated", effects.statuses.single().contains("uncalibrated"))
    }

    private fun applyWithActualMain(
        result: PedestrianMotionCalibrationResult,
        coordinator: PositioningCoordinator,
    ): Effects {
        val effects = Effects()
        MainActivity().applyWalkingCalibrationResult(
            calibration = result,
            currentStepLengthM = {
                effects.currentStepLengthReads += 1
                coordinator.currentProfile().stepLengthM.toFloat()
            },
            applyStepLength = { effects.appliedStepLengths += it },
            persistProfile = { effects.persistCalls += 1 },
            updateStatus = { effects.statuses += it },
        )
        return effects
    }

    private fun sample() = WalkingCalibrationSample(
        distanceM = 16.0,
        stepCount = 20,
        durationMs = 16_000L,
        trustedPositionSegment = true,
    )

    private data class Effects(
        var currentStepLengthReads: Int = 0,
        val appliedStepLengths: MutableList<Float> = mutableListOf(),
        var persistCalls: Int = 0,
        val statuses: MutableList<String> = mutableListOf(),
    )

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val next = source.indexOf("\n    private fun ", start + signature.length)
        return if (next < 0) source.substring(start) else source.substring(start, next)
    }
}
