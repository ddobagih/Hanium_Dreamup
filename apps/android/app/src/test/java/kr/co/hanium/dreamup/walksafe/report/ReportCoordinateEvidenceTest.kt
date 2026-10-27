package kr.co.hanium.dreamup.walksafe.report

import java.io.File
import kr.co.hanium.dreamup.walksafe.depth.FrozenImageToTextureCoordinateMapper
import kr.co.hanium.dreamup.walksafe.runtime.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.ReportFrameCaptureFacts
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import org.junit.Assert.*
import org.junit.Test

class ReportCoordinateEvidenceTest {
    @Test fun realDepthPipelineCanSupplyReportEvidenceForRawAndFull() {
        for (raw in listOf(false, true)) {
            val f = fixture(raw)
            val proof = requireNotNull(capture(f))
            val status = proof.statusFor(f.output, f.snapshot.reportImage, CAPTURED, GEOMETRY)
            assertEquals("pass", status)
            val input = AndroidReportCandidateInput(
                depth = f.output, location = proof.captureLocation,
                modelKey = "custom_tactile", sourceModel = "android/custom_tactile", runtimeMode = "android",
                modelConfigSha256 = "a".repeat(64), modelVersion = "test", sourceCommit = "unverified",
                threshold = 0.35f, capturedAtMs = CAPTURED, requestElapsedRealtimeMs = 1_050L,
                trigger = "voice", traceId = "test", deviceGateAllowsReports = true,
                detectionAgeMs = 50L, capturedAtElapsedRealtimeMs = proof.capturedAtElapsedRealtimeMs, coordinateGateStatus = status,
            )
            val policy = AndroidReportCandidatePolicy()
            assertEquals("pass", requireNotNull(policy.prepare(input)) { policy.evaluate(input).toString() }.metadata.getString("coordinate_gate_status"))
            for (missing in listOf(null, "unknown", "fail")) {
                assertNull(policy.prepare(input.copy(coordinateGateStatus = missing)))
            }
        }
    }

    @Test fun wrongFrameMissingMapperAndPartialInferenceCannotSupplyEvidence() {
        val f = fixture()
        assertNull(capture(f.copy(snapshot = f.snapshot.copy(identity = f.snapshot.identity.copy(captureFrameId = FRAME + 1)))))
        assertNull(capture(f.copy(snapshot = f.snapshot.copy(partial = true))))
        assertNull(capture(f.copy(snapshot = f.snapshot.copy(completedAtMs = null))))
        assertNull(capture(f.copy(frame = f.frame.copy(depthProcessingAttempted = false))))
        val evidence = requireNotNull(f.snapshot.frameEvidence)
        for (changed in listOf(
            evidence.copy(depthMapper = null),
            evidence.copy(depthSnapshot = evidence.depthSnapshot.copy(frameTimestampNs = FRAME + 1)),
            evidence.copy(depthMapper = FrozenImageToTextureCoordinateMapper(FRAME + 1, transform(), ImageSize(64, 64))),
            evidence.copy(geometryVersion = GEOMETRY + 1),
        )) {
            assertNull(capture(f.copy(snapshot = f.snapshot.copy(frameEvidence = changed), frame = f.frame.copy(matchedEvidence = changed))))
        }
    }

    @Test fun outputMustIdentifyItsOwnDetectionAndMappedSamplingRegion() {
        val f = fixture()
        for (changed in listOf(
            f.output.copy(sourceDetectionIndex = null), f.output.copy(sourceDetectionIndex = 1),
            f.output.copy(frameId = FRAME + 1), f.output.copy(timestampMs = 999L),
            f.output.copy(className = "person"), f.output.copy(bboxNorm = RectNorm(0f, 0f, 0.1f, 0.1f)),
            f.output.copy(polygonNorm = emptyList()),
        )) {
            assertNull(capture(f.copy(output = changed, frame = f.frame.copy(guidance = f.frame.guidance.copy(outputs = listOf(changed))))))
        }
        // Identical coordinates do not authorize a detection from another capture/list.
        val duplicate = f.snapshot.detections.single().copy()
        assertNull(capture(f.copy(snapshot = f.snapshot.copy(detections = listOf(duplicate)))))
        val outside = requireNotNull(FrozenImageToDepthTransform.create(FRAME,
            listOf(Point2(2f, 2f), Point2(3f, 2f), Point2(2f, 3f), Point2(3f, 3f)), Point2(2.5f, 2.5f)))
        val changed = requireNotNull(f.snapshot.frameEvidence).copy(
            depthMapper = FrozenImageToTextureCoordinateMapper(FRAME, outside, ImageSize(64, 64)))
        assertNull(capture(f.copy(snapshot = f.snapshot.copy(frameEvidence = changed), frame = f.frame.copy(matchedEvidence = changed))))
    }

    @Test fun storedEvidenceCannotBeReusedAfterImageOutputCaptureOrRotationChanges() {
        val f = fixture()
        val proof = requireNotNull(capture(f))
        assertEquals("fail", proof.statusFor(f.output.copy(), f.snapshot.reportImage, CAPTURED, GEOMETRY))
        assertEquals("fail", proof.statusFor(f.output, f.snapshot.reportImage!!.copyOf(), CAPTURED, GEOMETRY))
        assertEquals("fail", proof.statusFor(f.output, f.snapshot.reportImage, CAPTURED + 1, GEOMETRY))
        assertEquals("fail", proof.statusFor(f.output, f.snapshot.reportImage, CAPTURED, GEOMETRY + 1))
    }

    @Test fun cameraAlignedReprojectedRawDepthKeepsItsOriginalQualification() {
        val f = fixture(raw = true, reprojected = true)
        assertEquals(true, f.output.depthReprojected)
        assertNotNull(capture(f))
        val evidence = requireNotNull(f.snapshot.frameEvidence)
        val missingPair = evidence.copy(depthSnapshot = evidence.depthSnapshot.copy(rawConfidence = null))
        assertNull(capture(f.copy(snapshot = f.snapshot.copy(frameEvidence = missingPair),
            frame = f.frame.copy(matchedEvidence = missingPair))))
    }

    @Test fun evidenceRetainsTheLocationAndMonotonicTimeOfItsCapture() {
        val f = fixture()
        val proof = requireNotNull(capture(f))
        assertEquals(1_000L, proof.capturedAtElapsedRealtimeMs)
        assertEquals(TrustedLocation(37.0, 127.0, 5f, 950L), proof.captureLocation)
        val later = requireNotNull(f.snapshot.reportCaptureFacts).copy(
            trustedLocation = TrustedLocation(38.0, 128.0, 2f, 1_025L))
        val nextProof = requireNotNull(capture(f.copy(
            snapshot = f.snapshot.copy(reportCaptureFacts = later))))
        assertEquals(38.0, nextProof.captureLocation!!.latitude, 0.0)
        assertEquals(37.0, proof.captureLocation!!.latitude, 0.0)
    }

    @Test fun mainAndControllerUseFrozenCaptureEvidenceForBothReportTriggers() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val preparation = source.substringAfter("private fun buildFrozenReportCandidateInput(").substringBefore("private fun ")
        assertTrue(preparation.contains("AndroidReportCandidatePolicy.frozenFrameCoordinateGateStatus("))
        assertTrue(preparation.contains("requestElapsedRealtimeMs - it"))
        assertFalse(preparation.contains("freshTrustedLocationOrNull("))
        val controller = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/runtime/ReportCandidateController.kt").readText()
        assertTrue(controller.contains("val trustedLocation = capture.trustedLocation"))
        assertTrue(controller.contains("capture.walkEpoch != expectedWalkEpoch"))
        assertTrue(controller.contains("capture.accountGeneration != gateway.session?.backendAccountGeneration"))
        assertTrue(controller.indexOf("!imageValidator(reportImage)") < controller.indexOf("processReportCandidate("))
    }

    private data class Fixture(val snapshot: DetectionSnapshot,
        val frame: TactileSnapshotFrameResult, val output: TrackedObjectDepth)

    private fun capture(f: Fixture) = ReportCoordinateEvidence.capture(f.snapshot, f.frame, f.output, GEOMETRY)

    private fun transform() = requireNotNull(FrozenImageToDepthTransform.create(FRAME,
        listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f)), Point2(0.5f, 0.5f)))

    private fun fixture(raw: Boolean = false, reprojected: Boolean = false): Fixture {
        val image = DepthImage16(64, 64, IntArray(64 * 64) { 2_000 })
        val rawTime = if (reprojected) FRAME - 50_000_000L else FRAME
        val depth = DepthFrameSnapshot(FRAME, if (raw) image else null,
            if (raw) ConfidenceImage8(64, 64, ByteArray(64 * 64) { -1 }) else null,
            if (raw) null else image, rawDepthTimestampNs = rawTime, rawConfidenceTimestampNs = rawTime,
            fullDepthTimestampNs = FRAME, cameraImageTimestampNs = FRAME)
        val mapper = FrozenImageToTextureCoordinateMapper(FRAME, transform(), ImageSize(64, 64))
        val detections = listOf(DetectionCandidate("damaged_tactile_block", 0.9f, RectNorm(0.2f, 0.2f, 0.6f, 0.6f)))
        val output = ObjectDepthRuntimePipeline().process(depth, FRAME, 1_000L, detections,
            mapper = mapper, mapperFrameId = FRAME, capturedAtElapsedRealtimeMs = 1_000L).single()
        val context = TactileProjectionContext(FRAME, null, null, null, null, null, null, 0L, 1_000L)
        val evidence = DetectionFrameEvidence(FRAME, 1_000L, depth, mapper, context,
            MotionContext(), false, false, geometryVersion = GEOMETRY)
        val snapshot = DetectionSnapshot(detections, AndroidDetectionSnapshotFrameIdentity(FRAME, 1_000L),
            CAPTURED, CAPTURED, CAPTURED + 1, 640, 480, 1, null, byteArrayOf(1, 2, 3), false, evidence, capturedAtElapsedRealtimeMs = 1_000L, reportCaptureFacts = ReportFrameCaptureFacts(1_000L, TrustedLocation(37.0, 127.0, 5f, 950L), "test", 1L, WalkRuntimeEpoch("test", 0)))
        val frame = TactileSnapshotFrameResult(evidence, detections, true,
            TactileRouteGuidanceResult(listOf(output), null, TactileRouteDecision(LocalRouteMode.TMAP, null, "test", null)))
        return Fixture(snapshot, frame, output)
    }

    companion object {
        private const val FRAME = 1_000_000_000L
        private const val CAPTURED = 10_000L
        private const val GEOMETRY = 7L
    }
}
