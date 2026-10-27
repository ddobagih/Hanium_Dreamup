package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import org.junit.Assert.*
import org.junit.Test

class AndroidReportFrozenFrameAdmissionTest {
    @Test fun exactFrozenCaptureCanPassAfterAnotherFrameIsRenderedWithoutChangingItsSourceClocks() {
        val f = Fixture(); val original = f.capture; val output = f.output
        val observed = output.sourceObservedAtElapsedRealtimeMs; val expiry = output.sourceValidUntilElapsedRealtimeMs
        val other = f.captureAt(1_600L)
        assertNotEquals(original.first.captureFrameId, other.first.captureFrameId)
        assertEquals("pass", f.gate())
        assertEquals(1, f.geometryReads)
        assertSame(original.first.frameEvidence, original.second.matchedEvidence)
        assertSame(output, original.second.guidance.outputs.single())
        assertEquals(1_050_000_000L, output.frameId); assertEquals(1_050L, output.timestampMs)
        assertEquals(51_050L, original.first.capturedAtMs)
        assertEquals(1_050L, original.first.capturedAtElapsedRealtimeMs)
        assertEquals(observed, output.sourceObservedAtElapsedRealtimeMs); assertEquals(expiry, output.sourceValidUntilElapsedRealtimeMs)
        assertEquals(2L, original.first.frameEvidence!!.geometryVersion)
    }

    @Test fun nullCaptureReturnsNoPassAndDoesNotReadLiveGeometry() {
        val f = Fixture(); assertNull(f.gate(capture = null))
        assertEquals(0, f.geometryReads)
    }

    @Test fun partialMissingEvidenceKeyMapperOrDepthAttemptStopsBeforeLiveGeometry() {
        val f = Fixture(); val evidence = f.capture.first.frameEvidence!!
        val rejected = listOf(
            f.capture.first.copy(partial = true) to f.capture.second,
            f.capture.first to f.capture.second.copy(depthProcessingAttempted = false),
            f.capture.first to f.capture.second.copy(matchedEvidence = null),
            f.replaceEvidence(evidence.copy(visualFrameKey = null)),
            f.replaceEvidence(evidence.copy(depthMapper = null)),
        )
        rejected.forEach { assertNull(f.gate(capture = it)) }
        assertEquals(0, f.geometryReads)
    }

    @Test fun equalReconstructedMatchedEvidenceCannotBorrowTheOriginalSnapshot() {
        val f = Fixture(); val original = f.capture.first.frameEvidence!!; val copy = original.copy()
        assertEquals(original, copy); assertNotSame(original, copy)
        assertNull(f.gate(capture = f.capture.first to f.capture.second.copy(matchedEvidence = copy)))
        assertEquals(0, f.geometryReads)
    }

    @Test fun foreignDetectionIdentityCannotReachGeometryOrReuseTheCapture() {
        val f = Fixture()
        assertNull(f.gate(capture = f.capture.first.copy(identity = f.capture.first.identity.copy(captureFrameId = 3L)) to f.capture.second))
        assertEquals(0, f.geometryReads)
    }

    @Test fun frameCpuDepthAndCaptureElapsedClockMismatchesFailBeforeTheLateGeometryRead() {
        val f = Fixture(); val evidence = f.capture.first.frameEvidence!!; val key = evidence.visualFrameKey!!
        val rejected = listOf(
            evidence.copy(frameId = evidence.frameId + 1),
            evidence.copy(visualFrameKey = key.copy(cameraTimestampNs = key.cameraTimestampNs + 1)),
            evidence.copy(depthSnapshot = evidence.depthSnapshot.copy(cameraImageTimestampNs = key.cameraTimestampNs + 1)),
            evidence.copy(visualFrameKey = key.copy(frameId = key.frameId + 1)),
            evidence.copy(visualFrameKey = key.copy(capturedAtElapsedRealtimeMs = key.capturedAtElapsedRealtimeMs + 1)),
        )
        rejected.forEach { assertNull(f.gate(capture = f.replaceEvidence(it))) }
        assertEquals(0, f.geometryReads)
        assertNull(f.gate(capture = f.capture.first.copy(capturedAtElapsedRealtimeMs = 51_050L) to f.capture.second))
        assertEquals(0, f.geometryReads)
    }

    @Test fun mismatchedFrozenGeometryVersionDoesNotReadTheLiveVersion() {
        val f = Fixture(); val evidence = f.capture.first.frameEvidence!!
        assertNull(f.gate(capture = f.replaceEvidence(evidence.copy(geometryVersion = 3L))))
        assertEquals(0, f.geometryReads)
    }

    @Test fun liveGeometryIsReadOnceAtTheOriginalPointAndCannotReopenAnOldCapture() {
        val f = Fixture(); f.geometry = 3L
        assertNull(f.gate()); assertEquals(1, f.geometryReads)
        f.geometry = 2L; assertEquals("pass", f.gate()); assertEquals(2, f.geometryReads)
    }

    @Test fun supplierExceptionKeepsItsOriginalReachabilityAndIsNeverConvertedToAPassOrNull() {
        val f = Fixture(); val failure = IllegalStateException("live geometry"); f.geometryFailure = failure
        assertNull(f.gate(capture = null)); assertEquals(0, f.geometryReads)
        assertSame(failure, runCatching { f.gate() }.exceptionOrNull())
        assertEquals(1, f.geometryReads)
    }

    @Test fun geometryReadRunsUnderBothOriginalCallerLocksWithoutTakingAReplacementLock() {
        val f = Fixture(); val frameLock = Any(); val metricLock = Any()
        synchronized(frameLock) { synchronized(metricLock) {
            assertEquals("pass", AndroidReportCandidatePolicy.frozenFrameCoordinateGateStatus(
                f.output, 51_050L, f.image, f.capture,
                currentGeometryVersion = { assertTrue(Thread.holdsLock(frameLock)); assertTrue(Thread.holdsLock(metricLock)); 2L },
            ))
        } }
    }

    @Test fun mapperFrameTransformDimensionsAndImagePointMismatchesNeverReachTheOutputList() {
        val f = Fixture(); val e = f.capture.first.frameEvidence!!
        val wrong = requireNotNull(FrozenImageToDepthTransform.create(e.frameId + 1,
            listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f)), Point2(.5f, .5f)))
        assertNull(f.gate(capture = f.replaceEvidence(e.copy(depthMapper = FrozenImageToTextureCoordinateMapper(e.frameId, wrong, ImageSize(100, 100))))))
        assertEquals(1, f.geometryReads)
        val throwing = f.capture.second.copy(guidance = f.capture.second.guidance.copy(outputs = ThrowingOutputs()))
        assertNull(f.gate(capture = f.capture.first.copy(imageWidth = 0) to throwing))
        assertNull(f.gate(output = f.output.copy(centerNorm = Point2(Float.NaN, .5f)), capture = f.capture.first to throwing))
    }

    @Test fun outputEnumerationExceptionOccursAfterGeometryAndMapperAndKeepsTheOriginalFailure() {
        val f = Fixture(); val failure = IllegalStateException("output iterator")
        val changed = f.capture.second.copy(guidance = f.capture.second.guidance.copy(outputs = ThrowingOutputs(failure)))
        assertSame(failure, runCatching { f.gate(capture = f.capture.first to changed) }.exceptionOrNull())
        assertEquals(1, f.geometryReads)
    }

    @Test fun equalReconstructedOrForeignOutputCannotBorrowOriginalOutputMembership() {
        val f = Fixture(); val copied = f.output.copy(); assertEquals(f.output, copied)
        assertNull(f.gate(output = copied)); assertNull(f.gate(output = f.captureAt(1_600L).second.guidance.outputs.single()))
        assertEquals(2, f.geometryReads)
    }

    @Test fun outputFrameAndTimestampMustStillMatchEvenWhenTheExactObjectIsListed() {
        val f = Fixture()
        for (output in listOf(f.output.copy(frameId = 7L), f.output.copy(timestampMs = 1_051L))) {
            val changed = f.capture.second.copy(guidance = f.capture.second.guidance.copy(outputs = listOf(output)))
            assertNull(f.gate(output = output, capture = f.capture.first to changed))
        }
    }

    @Test fun sourceIndexAndClassChangesCannotBorrowAnUnrelatedDepthDetection() {
        val f = Fixture()
        for (output in listOf(f.output.copy(sourceDetectionIndex = 8), f.output.copy(className = "person"))) {
            val changed = f.capture.second.copy(guidance = f.capture.second.guidance.copy(outputs = listOf(output)))
            assertNull(f.gate(output = output, capture = f.capture.first to changed))
        }
        assertNull(f.gate(capture = f.capture.first to f.capture.second.copy(depthDetections = emptyList())))
    }

    @Test fun missingSourceIndexCannotProveTheReportCoordinates() {
        val f = Fixture(); val output = f.output.copy(sourceDetectionIndex = null)
        val changed = f.capture.second.copy(depthDetections = emptyList(), guidance = f.capture.second.guidance.copy(outputs = listOf(output)))
        assertNull(f.gate(output = output, capture = f.capture.first to changed))
    }

    @Test fun copiedOrReplacedImageAndWallCaptureClockCannotCreatePass() {
        val f = Fixture()
        assertArrayEquals(f.image, f.image.copyOf())
        assertNull(f.gate(image = f.image.copyOf()))
        assertNull(f.gate(capture = f.capture.first.copy(reportImage = f.image.copyOf()) to f.capture.second))
        assertNull(f.gate(capture = f.capture.first.copy(reportImage = null) to f.capture.second))
        assertNull(f.gate(capturedAtMs = 1_050L))
        assertEquals(4, f.geometryReads)
    }

    @Test fun coordinatePassPreservesExistingCoordinateDeviceAndExpiredDepthRejectionPriority() {
        val f = Fixture(); val policy = AndroidReportCandidatePolicy(); val input = f.input()
        assertEquals("pass", input.coordinateGateStatus)
        for (trigger in listOf("auto", "on_screen", "voice")) {
            assertTrue(policy.evaluate(input.copy(trigger = trigger)) is AndroidReportCandidateResult.Ready)
        }
        assertEquals(AndroidReportCandidateResult.Rejected(AndroidReportCandidateRejection.COORDINATE_GATE_NOT_PASSED),
            policy.evaluate(input.copy(coordinateGateStatus = null, deviceGateAllowsReports = false)))
        assertEquals(AndroidReportCandidateResult.Rejected(AndroidReportCandidateRejection.DEVICE_GATE_BLOCKED),
            policy.evaluate(input.copy(deviceGateAllowsReports = false)))
        assertEquals(AndroidReportCandidateResult.Rejected(AndroidReportCandidateRejection.DEPTH_EXPIRED),
            policy.evaluate(input.copy(requestElapsedRealtimeMs = requireNotNull(f.output.sourceValidUntilElapsedRealtimeMs) + 1, deviceGateAllowsReports = false)))
    }

    private class ThrowingOutputs(private val failure: RuntimeException = IllegalStateException("output must not be read")) : AbstractList<TrackedObjectDepth>() {
        override val size: Int get() = throw failure
        override fun get(index: Int): TrackedObjectDepth = throw failure
    }

    private class Fixture {
        val pipeline = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true)
        var geometry = 2L; var geometryReads = 0; var geometryFailure: RuntimeException? = null
        val capture = run { captureAt(350L); captureAt(700L); captureAt(1_050L) }
        val output = capture.second.guidance.outputs.single()
        val image = requireNotNull(capture.first.reportImage)
        fun captureAt(at: Long): Pair<DetectionSnapshot, TactileSnapshotFrameResult> {
            val key = VisualFrameKey(0L, at * 1_000_000L, at * 1_000_000L + 400_000L, at, 2L)
            val values = IntArray(10_000) { 2_000 }
            val depth = DepthFrameSnapshot(key.frameId, DepthImage16(100, 100, values),
                ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }), DepthImage16(100, 100, values.copyOf()),
                rawDepthTimestampNs = key.cameraTimestampNs, fullDepthTimestampNs = key.cameraTimestampNs,
                cameraImageTimestampNs = key.cameraTimestampNs, rawConfidenceTimestampNs = key.cameraTimestampNs)
            val transform = requireNotNull(FrozenImageToDepthTransform.create(key.frameId,
                listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f)), Point2(.5f, .5f)))
            val context = TactileProjectionContext(key.frameId, null, null, null, null, null,
                TactileCameraFrame(key.frameId, RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
                    TactileCameraIntrinsics(100, 100, 70f, 70f, 50f, 50f), at, true), 40L, at, at + 40L)
            val evidence = DetectionFrameEvidence(key.frameId, at, depth,
                FrozenImageToTextureCoordinateMapper(key.frameId, transform, ImageSize(100, 100)), context,
                MotionContext(), false, false, visualFrameKey = key, geometryVersion = 2L)
            val snapshot = DetectionSnapshot(listOf(DetectionCandidate("damaged_tactile_block", .9f, RectNorm(.2f, .3f, .4f, .2f))),
                AndroidDetectionSnapshotFrameIdentity(key.frameId, at), at + 50_000L, at + 50_000L, at + 50_000L,
                100, 100, 0L, null, reportImage = byteArrayOf(1, 2, (at / 10).toByte()), partial = false,
                frameEvidence = evidence, capturedAtElapsedRealtimeMs = at)
            val outputs = pipeline.process(snapshot = depth, frameId = key.frameId, timestampMs = at,
                detections = snapshot.detections, detectionSequenceId = key.frameId, detectionCompleted = true,
                mapper = requireNotNull(evidence.depthMapper), mapperFrameId = key.frameId,
                motionContext = evidence.motionContext, imageQuarterTurns = evidence.imageQuarterTurns,
                capturedAtElapsedRealtimeMs = at)
            return snapshot to TactileSnapshotFrameResult(evidence, snapshot.detections, true,
                TactileRouteGuidanceResult(outputs, null, TactileRouteDecision(LocalRouteMode.TMAP, null, "fixture", null)))
        }
        fun replaceEvidence(e: DetectionFrameEvidence) = capture.first.copy(frameEvidence = e) to capture.second.copy(matchedEvidence = e)
        fun gate(output: TrackedObjectDepth = this.output, capturedAtMs: Long = 51_050L, image: ByteArray? = this.image,
            capture: Pair<DetectionSnapshot, TactileSnapshotFrameResult>? = this.capture) =
            AndroidReportCandidatePolicy.frozenFrameCoordinateGateStatus(output, capturedAtMs, image, capture,
                currentGeometryVersion = { geometryReads++; geometryFailure?.let { throw it }; geometry })
        fun input() = AndroidReportCandidateInput(output, TrustedLocation(37.0, 127.0, 8f, 1_050L),
            "custom_tactile", "android/custom_tactile", "android", null, "fixture", "unverified", .35f,
            51_050L, 1_600L, "auto", "fixture-actor", "frozen-frame", true,
            depthSampleCount = output.validSampleCount, depthValidSampleRatio = output.validSampleRatio,
            detectionAgeMs = 550L, coordinateGateStatus = gate(), capturedAtElapsedRealtimeMs = 1_050L)
    }
}
