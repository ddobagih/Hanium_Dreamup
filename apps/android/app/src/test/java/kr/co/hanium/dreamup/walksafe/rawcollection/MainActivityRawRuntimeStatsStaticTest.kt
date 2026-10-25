package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 16-17 #2, #4, #8, #12 wiring in MainActivity (the JVM behaviour lives in the rawcollection tests). */
class MainActivityRawRuntimeStatsStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/report/runtime/RawCollectionTransferController.kt").readText()
    private val capture = source.substringAfter(
        "fun recordRawCollectionDetectionMetadata(",
    ).substringBefore("fun revalidateRawCollectionRuntime()")

    @Test
    fun frameDurationIsNotTheInferenceAverageAndWindowsUseTheSharedAccumulator() {
        assertFalse(capture.contains("averageFrameDurationMs = averageProcessingFrameMs"))
        assertTrue(capture.contains("rawRuntimeWindow.recordCompletedInference("))
        assertTrue(capture.contains("rawRuntimeWindow.snapshot("))
        assertTrue(capture.contains("samples.detection"))
        assertTrue(capture.contains("samples.performance"))
        assertTrue(capture.contains("rawRuntimeWindow.resetWindow()"))
        assertTrue(capture.contains("busyDroppedSincePrevious = dueButInFlightDropCount"))
    }

    @Test
    fun theMinimumCaptureIntervalUsesTheMonotonicObservationTime() {
        assertTrue(capture.contains("val observedAtElapsedMs = elapsedRealtimeMs()"))
        assertTrue(capture.contains("observedAtElapsedRealtimeMs = observedAtElapsedMs"))
    }

    @Test
    fun outcomeCountersAreOnlyWrittenToTheDebugFieldLogAtWalkEnd() {
        val transition = source.substringAfter("fun handleRawCollectionTransition(")
            .substringBefore("fun recordRawCollectionOutcomesIfChanged()")
        val ended = transition.substringAfter("transition.current.state == WalkSessionState.ENDED ||")
            .substringBefore("transition.current.state != WalkSessionState.PAUSED ->")
        assertTrue(ended.contains("\"raw_collection_outcomes\""))
        assertTrue(ended.contains("outcomeCountersSnapshot()"))
        val rawFunctions = capture + transition + source.substringAfter(
            "private fun scheduleRawCollectionPostWalkUpload()",
        ).substringBefore("fun handleRawCollectionTransition(")
        listOf("speakInteraction(", "updateStatus(", "Toast.", "announceFor").forEach { userOutput ->
            assertFalse(userOutput, rawFunctions.contains(userOutput))
        }
    }
}
