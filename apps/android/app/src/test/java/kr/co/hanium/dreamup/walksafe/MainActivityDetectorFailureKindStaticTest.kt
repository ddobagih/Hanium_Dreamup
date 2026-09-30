package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** S14-37: runtime failures reach the supervisor with their kind and time. */
class MainActivityDetectorFailureKindStaticTest {
    private val activity = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun runtimeFailureIsClassifiedAndTimedAndCompletedInferenceRecordsItsTime() {
        val failure = activity.substringAfter("private fun handleDetectorRuntimeFailure(")
            .substringBefore("private fun disableDetectorAfterRuntimeFailure(")
        assertTrue(failure.contains("failureKind = detectorRuntimeFailureKind(error),"))
        assertTrue(failure.contains("atElapsedRealtimeMs = SystemClock.elapsedRealtime(),"))
        // S14-36: both completed-inference paths record their completion time through the checked entry point.
        val outputs = activity.split("detectorRuntimeSupervisor.onDetectorOutput(").drop(1)
        assertTrue(outputs.size == 2)
        outputs.forEach { call ->
            assertTrue(call.substringBefore(".action == DetectorRuntimeFailureAction.SAFE_STOP")
                .contains("completedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),"))
        }
    }

    @Test
    fun failureInARunWithoutItsOwnSuccessDisarmsTheOldFreshnessClockBeforeTheSupervisorDecides() {
        // S14-37-a: the success run is (detector generation, walk epoch); a failure of another run
        // must not be timed from the previous run's last output. S14-36 moved the once-per-run re-arm
        // into the supervisor (DetectorRuntimeSupervisorTest.aNewRunReArmsTheClockOnceAndLaterEventsOfTheRunKeepIt),
        // so the failure and every output carry the same run key.
        val failure = activity.substringAfter("private fun handleDetectorRuntimeFailure(")
            .substringBefore("private fun disableDetectorAfterRuntimeFailure(")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                failure,
                "if (!isCurrentFrameGeneration(expectedDetectorGeneration, expectedWalkEpoch)) return",
                "val detectorRun = expectedDetectorGeneration to expectedWalkEpoch",
                "detectorRuntimeSupervisor.onFailure(",
                "run = detectorRun,",
            ),
        )
        assertTrue(
            Regex(
                "DetectorWorkClaim\\(\\s*(detectorFrameGeneration|generation), expectedWalkEpoch, (detectorTicket|pacingTicket),",
            ).findAll(activity).count() == 2,
        )
        // A reset is never a success: stale generations disarm instead of recording onSuccess().
        assertTrue(!activity.contains("detectorRuntimeSupervisor.onSuccess("))
    }
}
