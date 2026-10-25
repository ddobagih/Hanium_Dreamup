package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MainActivityTactileObservationDiagnosticsStaticTest {
    private fun source(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path.kt").readText()
    private val main = source("MainActivity")
    private val pipeline = source("runtime/vision/frame/FramePipelineController")
    private val capture = source("navigation/capture/TactileFrameCaptureController")
    @Test fun legacySnapshotPassesOneElapsedDecisionInstantWithoutReusingTheWallClock() {
        assertTrue(ReportStaticSourceInspector.appearsInOrder(pipeline, "val decision = clock.elapsedRealtimeMs()",
            "tactile.prepareFrozen(detection,", "source.wallClockMs, source.timestampMs, decision)"))
        assertTrue(main.contains("snapshot.sourceAgeMs(wallClockMs), decisionElapsedMs,"))
        for (name in listOf("processTactileSnapshotFrame", "prepareTactileFrameDispatch")) {
            val body = MainActivityRefactorSource.member(capture, name)
            assertTrue(body.contains("decisionElapsedRealtimeMs: Long? = null"))
            assertTrue(body.contains("decisionElapsedRealtimeMs = decisionElapsedRealtimeMs"))
            assertFalse(body.contains("SystemClock.elapsedRealtime()"))
        }
    }
    @Test fun admissionAndDiagnosticsUseTheSameEvaluationInstantAtTheFreshnessBoundary() {
        val current = pipeline.substringAfter("val tactileAt = clock.elapsedRealtimeMs()").substringBefore("val sourceLease = issued")
        assertTrue(current.contains("tactile.coordinator().processTrackedObservation("))
        assertTrue(current.contains("TactileCurrentFrameDiagnostics.fromCapture(detection, q.gray?.key, evidence, tracking, observation, tactileAt)"))
        assertTrue(current.contains("nowElapsedRealtimeMs = tactileAt"))
        assertFalse(current.contains("clock.elapsedRealtimeMs()"))
    }
    @Test fun currentCameraDiagnosticsReceiveInputsBeforeAdmissionAndReachTheDisplayedSummary() {
        assertTrue(pipeline.contains("frame = observation"))
        assertTrue(pipeline.contains("TactileCurrentFrameDiagnostics.fromCapture(detection, q.gray?.key, evidence, tracking, observation, tactileAt)"))
        assertTrue(pipeline.contains("tactile.consume(sourceLease.issued, raw,"))
        assertTrue(pipeline.contains("tactile.publishReference(accepted, sourceLease.owner, token)"))
        assertTrue(pipeline.contains("val result = frame ?: return null"))
        assertTrue(main.contains("consumeTactileFrameResult(source, frame, admissionIsCurrent = admissionIsCurrent, consume = accepted)"))
        assertTrue(MainActivityRefactorSource.member(capture, "consumeTactileFrameResult").contains("frame.copy(guidance = guidance.copy("))
        val publication = source("feedback/delivery/TactileReferenceDeliveryController")
        assertTrue(publication.contains("tactileFrame.guidance.observationDiagnosticsText()"))
    }
}
