package kr.co.hanium.dreamup.walksafe

import java.io.File

import kr.co.hanium.dreamup.walksafe.depth.FrozenImageToTextureCoordinateMapper
import kr.co.hanium.dreamup.walksafe.navigation.TactileSnapshotFrameResult
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.ReportFrameCaptureFacts
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.report.runtime.ReportCandidateController
import java.nio.file.Files
import java.nio.file.Paths
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.device.DeviceGateState
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.report.*
import org.junit.Assert.*
import org.junit.Test

/** Main frozen input builder, then captured facts admitted by the production report Controller. No GL/OS/queue execution. */
class MainActivityFrozenReportCoordinateGateBehaviorTest {
    @Test
    fun missingFrozenCaptureCannotBecomeAPassingCoordinateGate() {
        val f = Fixture()
        val capture = f.stableCapture()
        val output = capture.second.guidance.outputs.single()
        val input = f.input(output, null)
        assertEquals("Missing validation must use the existing coordinate rejection", 
            AndroidReportCandidateResult.Rejected(AndroidReportCandidateRejection.COORDINATE_GATE_NOT_PASSED), f.policy.evaluate(input))
        assertNull(input.coordinateGateStatus)
    }

    @Test
    fun original1050CaptureRemainsValidWhenTheCurrentRenderIs1600ForBothTriggers() {
        val f = Fixture()
        val capture = f.stableCapture()
        val output = capture.second.guidance.outputs.single()
        assertEquals(1_050_000_000L, output.frameId)
        assertEquals(0, output.sourceDetectionIndex)
        assertTrue(output.trackAgeFrames >= 3)
        assertTrue(output.trackStableMs >= 700L)
        val originalBox = output.bboxNorm
        val originalObserved = output.sourceObservedAtElapsedRealtimeMs
        val originalExpiry = output.sourceValidUntilElapsedRealtimeMs
        val current = f.capture(1_600L)
        assertNotEquals(capture.first.captureFrameId, current.first.captureFrameId)
        for (trigger in listOf("auto", "on_screen", "voice")) {
            val input = f.input(output, capture, trigger)
            assertEquals("pass", input.coordinateGateStatus)
            val facts = requireNotNull(capture.first.reportCaptureFacts)
            assertSame(facts.trustedLocation, input.location)
            assertEquals(51_050L, input.capturedAtElapsedRealtimeMs)
            assertEquals("fixture-actor", facts.reporterActorId)
            assertEquals(7L, facts.accountGeneration)
            assertEquals(WalkRuntimeEpoch("report-capture", 0L), facts.walkEpoch)
            val ready = f.policy.evaluate(input) as AndroidReportCandidateResult.Ready
            assertEquals(1_050_000_000L, input.depth.frameId)
            assertEquals(originalBox, input.depth.bboxNorm)
            assertEquals(originalObserved, input.depth.sourceObservedAtElapsedRealtimeMs)
            assertEquals(originalExpiry, input.depth.sourceValidUntilElapsedRealtimeMs)
            assertEquals(51_050L, input.capturedAtMs)
            assertEquals("pass", ready.candidate.metadata.getString("coordinate_gate_status"))
            assertEquals(trigger, ready.candidate.metadata.getString("trigger"))
            assertSame(capture.first.reportImage, f.image)
        }
    }

    @Test
    fun missingOriginalFactsCannotBorrowTheFrozenCoordinatePassOrALaterLocation() {
        val f = Fixture()
        val capture = f.stableCapture()
        val withoutFacts = capture.first.copy(reportCaptureFacts = null) to capture.second
        for (trigger in listOf("auto", "on_screen", "voice")) {
            val input = f.input(capture.second.guidance.outputs.single(), withoutFacts, trigger)
            assertEquals("pass", input.coordinateGateStatus)
            assertNull(input.location)
            assertNull(input.capturedAtElapsedRealtimeMs)
            assertEquals(AndroidReportCandidateResult.Rejected(AndroidReportCandidateRejection.LOCATION_UNTRUSTED),
                f.policy.evaluate(input))
        }
    }

    @Test
    fun foreignOutputAndReconstructedOutputCannotBorrowTheOriginalValidation() {
        val f = Fixture()
        val capture = f.stableCapture()
        val output = capture.second.guidance.outputs.single()
        val foreign = f.capture(1_600L).second.guidance.outputs.single()
        for (candidate in listOf(foreign, output.copy(), output.copy(sourceDetectionIndex = 8))) {
            f.assertRejected(candidate, capture)
        }
    }

    @Test
    fun frameCpuMapperTransformAndGeometryMismatchesFailClosed() {
        val f = Fixture()
        val capture = f.stableCapture()
        val evidence = requireNotNull(capture.first.frameEvidence)
        val key = requireNotNull(evidence.visualFrameKey)
        val mapper = requireNotNull(evidence.depthMapper)
        val wrongTransform = requireNotNull(FrozenImageToDepthTransform.create(evidence.frameId + 1,
            listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f)), Point2(.5f, .5f)))
        val mismatches = listOf(
            evidence.copy(frameId = evidence.frameId + 1),
            evidence.copy(visualFrameKey = key.copy(cameraTimestampNs = key.cameraTimestampNs + 1)),
            evidence.copy(depthSnapshot = evidence.depthSnapshot.copy(cameraImageTimestampNs = key.cameraTimestampNs + 1)),
            evidence.copy(depthMapper = FrozenImageToTextureCoordinateMapper(evidence.frameId + 1, wrongTransform, ImageSize(100, 100))),
            evidence.copy(depthMapper = FrozenImageToTextureCoordinateMapper(evidence.frameId, wrongTransform, ImageSize(100, 100))),
            evidence.copy(geometryVersion = key.geometryVersion + 1),
            evidence.copy(visualFrameKey = key.copy(frameId = key.frameId + 1)),
            evidence.copy(depthMapper = null),
        )
        for (bad in mismatches) {
            val snapshot = capture.first.copy(frameEvidence = bad)
            val frame = capture.second.copy(matchedEvidence = bad)
            f.assertRejected(capture.second.guidance.outputs.single(), snapshot to frame)
        }
        assertEquals(evidence.frameId, mapper.frameId)
    }

    @Test
    fun missingAttemptForeignMatchedEvidenceAndForeignImageCannotCreatePass() {
        val f = Fixture()
        val capture = f.stableCapture()
        val output = capture.second.guidance.outputs.single()
        for (frame in listOf(capture.second.copy(depthProcessingAttempted = false),
            capture.second.copy(matchedEvidence = null),
            capture.second.copy(matchedEvidence = requireNotNull(capture.first.frameEvidence).copy()))) {
            f.assertRejected(output, capture.first to frame)
        }
        f.assertRejected(output, capture.first.copy(partial = true) to capture.second)
        f.assertRejected(output, capture.first.copy(reportImage = byteArrayOf(9)) to capture.second)
        f.assertRejected(output, capture.first.copy(capturedAtMs = 52_000L) to capture.second)
    }

    @Test
    fun availableSourceIndexMustReferToTheOriginalDepthDetection() {
        val f = Fixture()
        val capture = f.stableCapture()
        val output = capture.second.guidance.outputs.single()
        val changed = capture.second.copy(depthDetections = emptyList())
        f.assertRejected(output, capture.first to changed)
        assertEquals(0, output.sourceDetectionIndex)
        assertEquals(capture.first.detections, capture.second.depthDetections)
    }

    @Test
    fun originalInputBodyPreservesDeviceAndExistingExpiryRejections() {
        val f = Fixture()
        val capture = f.stableCapture()
        val output = capture.second.guidance.outputs.single()
        val input = f.input(output, capture)
        assertEquals(AndroidReportCandidateResult.Rejected(AndroidReportCandidateRejection.DEVICE_GATE_BLOCKED),
            f.policy.evaluate(input.copy(deviceGateAllowsReports = false)))
        val expired = f.policy.evaluate(input.copy(requestElapsedRealtimeMs = requireNotNull(output.sourceValidUntilElapsedRealtimeMs) + 1))
        assertTrue(expired is AndroidReportCandidateResult.Rejected)
        assertSame(output, input.depth)
    }

    @Test
    fun actualClearRetiresTheStoredOutputAndItsValidationTogether() {
        val f = Fixture()
        val capture = f.stableCapture()
        val output = capture.second.guidance.outputs.single()
        fun install(name: String, value: Any?) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(f.main, value)
        install("latestExplicitReportOutput", output)
        install("latestExplicitReportCaptureValidation", capture)
        val owner = MainActivityControllerTestFixture.controller(f.main, "reportCandidateController") as ReportCandidateController
        owner.clearFrameState("retired_fixture_generation")
        assertNull(field(f.main, "latestExplicitReportOutput"))
        assertNull(field(f.main, "latestExplicitReportCaptureValidation"))
        assertEquals("reportCandidate=blocked:retired_fixture_generation", field(f.main, "latestReportCandidateStatus"))
    }

    @Test
    fun changingTheCurrentGeometryCannotUpgradeOrReopenAnOldCapture() {
        val f = Fixture()
        val capture = f.stableCapture()
        val output = capture.second.guidance.outputs.single()
        assertEquals("pass", f.input(output, capture).coordinateGateStatus)
        val geometry = MainActivityControllerTestFixture.field("visualGeometryVersion").apply { isAccessible = true }
        geometry.setLong(f.main, geometry.getLong(f.main) + 1L)
        f.assertRejected(output, capture)
        assertEquals(capture.first.frameEvidence!!.geometryVersion, capture.first.frameEvidence!!.visualFrameKey!!.geometryVersion)
    }

    @Test
    fun productionAutoAndSavedExplicitUseTheSameFrozenValidationAndEveryClearRetiresIt() {
        fun owner(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path.kt").readText()
        val main = mainSource()
        val pipeline = owner("runtime/vision/frame/FramePipelineController")
        val publication = owner("runtime/vision/frame/FramePublicationController")
        val report = owner("report/runtime/ReportCandidateController")
        assertTrue(pipeline.contains("tactile.prepareFrozen(detection,"))
        assertTrue(pipeline.contains("synthesis.processFrozenDepth(detection, evidence, detections, generation, epoch)"))
        assertTrue(main.contains("frozenReportFrameCoordinator, processDepth)"))
        assertTrue(publication.contains("input.frozenPrepared?.let { input.detectionSnapshot to it.frame }"))
        assertTrue(main.contains("reportImage = data.sourceSnapshot.reportImage"))
        assertTrue(main.contains("frozenReportCapture = data.frozenCapture"))
        val prepare = MainActivityRefactorSource.member(report, "prepareReportCandidate")
        assertTrue(prepare.contains("val capture = latestReportCaptureFacts"))
        assertTrue(prepare.contains("capture == null || capture.walkEpoch != expectedWalkEpoch"))
        assertTrue(prepare.contains("capture.reporterActorId != reporterId || capture.accountGeneration != gateway.session?.backendAccountGeneration"))
        assertTrue(prepare.contains("val trustedLocation = capture.trustedLocation"))
        assertTrue(prepare.contains(").copy(capturedAtElapsedRealtimeMs = capture.capturedAtElapsedRealtimeMs)"))
        assertFalse(prepare.contains("currentRouteLocationOrNull()"))
        val publish = MainActivityRefactorSource.member(report, "publishCurrentFrameReportState")
        assertTrue(publish.contains("frozenReportCapture = frozenReportCapture"))
        assertTrue(publish.contains("latestExplicitReportCaptureValidation = frozenReportCapture"))
        assertTrue(MainActivityRefactorSource.member(report, "prepareExplicitReportCandidateIfCurrent")
            .contains("frozenReportCapture = latestExplicitReportCaptureValidation"))
        val clear = MainActivityRefactorSource.member(report, "clearFrameCapture")
        assertTrue(clear.contains("latestExplicitReportOutput = null"))
        assertTrue(clear.contains("latestExplicitReportCaptureValidation = null"))
        assertEquals(1, Regex("latestExplicitReportOutput = null").findAll(report).count())
        assertEquals(3, Regex("reportCandidateController\\.clearFrameCapture\\(\\)").findAll(main).count())
        assertTrue(MainActivityRefactorSource.member(report, "clearFrameState").contains("clearFrameCapture()"))
        assertTrue(publish.contains("synchronized(frameStateLock)"))
        assertTrue(publish.contains("frames.runtimeMetricAllowsWork(expectedRuntimeMetricGeneration)"))
    }

    private class Fixture {
        val main = MainActivity()
        val pipeline = field(main, "objectDepthPipeline") as ObjectDepthRuntimePipeline
        val policy = field(main, "reportCandidatePolicy") as AndroidReportCandidatePolicy
        val coordinator = (field(main, "frozenReportFrameCoordinator\$delegate") as Lazy<*>).value as AndroidTactileFrameCoordinator
        var image: ByteArray? = null
        private var originalCaptureFacts: ReportFrameCaptureFacts? = null

        fun stableCapture(): Pair<DetectionSnapshot, TactileSnapshotFrameResult> {
            capture(350L)
            capture(700L)
            return capture(1_050L).also {
                image = it.first.reportImage
                originalCaptureFacts = requireNotNull(it.first.reportCaptureFacts)
            }
        }

        fun capture(at: Long): Pair<DetectionSnapshot, TactileSnapshotFrameResult> {
            val key = VisualFrameKey(0L, at * 1_000_000L, at * 1_000_000L + 400_000L, at + 50_000L, field(main, "visualGeometryVersion") as Long)
            val values = IntArray(10_000) { 2_000 }
            val depth = DepthFrameSnapshot(key.frameId, DepthImage16(100, 100, values),
                ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }), DepthImage16(100, 100, values.copyOf()),
                rawDepthTimestampNs = key.cameraTimestampNs, fullDepthTimestampNs = key.cameraTimestampNs,
                cameraImageTimestampNs = key.cameraTimestampNs, rawConfidenceTimestampNs = key.cameraTimestampNs)
            val transform = requireNotNull(FrozenImageToDepthTransform.create(key.frameId,
                listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f)), Point2(.5f, .5f)))
            val context = TactileProjectionContext(key.frameId, null, null, null, null, null,
                TactileCameraFrame(key.frameId, RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
                    TactileCameraIntrinsics(100, 100, 70f, 70f, 50f, 50f), key.capturedAtElapsedRealtimeMs, true),
                40L, key.capturedAtElapsedRealtimeMs, key.capturedAtElapsedRealtimeMs + 40L)
            val evidence = DetectionFrameEvidence(key.frameId, at, depth,
                FrozenImageToTextureCoordinateMapper(key.frameId, transform, ImageSize(100, 100)), context,
                MotionContext(), false, false, visualFrameKey = key, geometryVersion = key.geometryVersion)
            val facts = ReportFrameCaptureFacts(key.capturedAtElapsedRealtimeMs,
                TrustedLocation(37.0, 127.0, 8f, key.capturedAtElapsedRealtimeMs),
                "fixture-actor", 7L, WalkRuntimeEpoch("report-capture", 0L))
            val snapshot = DetectionSnapshot(listOf(DetectionCandidate("damaged_tactile_block", .9f, RectNorm(.2f, .3f, .4f, .2f))),
                AndroidDetectionSnapshotFrameIdentity(key.frameId, at), key.capturedAtElapsedRealtimeMs,
                key.capturedAtElapsedRealtimeMs, key.capturedAtElapsedRealtimeMs, 100, 100, 0L, null,
                reportImage = byteArrayOf(1, 2, (at / 10).toByte()), partial = false, frameEvidence = evidence,
                capturedAtElapsedRealtimeMs = key.capturedAtElapsedRealtimeMs, reportCaptureFacts = facts)
            val frame = main.prepareTactileFrameDispatch(snapshot, key.capturedAtElapsedRealtimeMs + 40L,
                at, 800L, 800L, 40L, key.capturedAtElapsedRealtimeMs + 40L, coordinator) { capture, detections ->
                pipeline.process(snapshot = capture.depthSnapshot, frameId = capture.frameId,
                    timestampMs = capture.frameTimestampMs, detections = detections,
                    detectionSequenceId = capture.frameId, detectionCompleted = true,
                    mapper = requireNotNull(capture.depthMapper), mapperFrameId = capture.depthMapper.frameId,
                    motionContext = capture.motionContext, imageQuarterTurns = capture.imageQuarterTurns,
                    capturedAtElapsedRealtimeMs = key.capturedAtElapsedRealtimeMs)
            }.frame
            assertTrue(frame.depthProcessingAttempted)
            assertSame(evidence, frame.matchedEvidence)
            assertEquals(1, frame.guidance.outputs.size)
            return snapshot to frame
        }

        fun input(output: TrackedObjectDepth, capture: Pair<DetectionSnapshot, TactileSnapshotFrameResult>?, trigger: String = "auto"): AndroidReportCandidateInput {
            val facts = capture?.first?.reportCaptureFacts
            val original = requireNotNull(originalCaptureFacts)
            val lookup = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            val built = lookup.findVirtual(MainActivity::class.java, "buildFrozenReportCandidateInput", MethodType.methodType(
                AndroidReportCandidateInput::class.java, TrackedObjectDepth::class.java, TrustedLocation::class.java,
                String::class.java, String::class.java, java.lang.Float.TYPE, java.lang.Long.TYPE, java.lang.Long.TYPE,
                java.lang.Long.TYPE, String::class.java, String::class.java, DeviceGateState::class.java,
                ByteArray::class.java, Pair::class.java)).bindTo(main).invokeWithArguments(
                    output, requireNotNull(original.trustedLocation), "custom_tactile", "android/custom_tactile", .35f,
                    51_050L, 51_600L, 51_600L, trigger, "fixture-actor",
                    DeviceGateState(true, true, true, true, true, true, true), image, capture) as AndroidReportCandidateInput
            // Production prepareReportCandidate adds the admitted original facts after Main builds
            // coordinate/depth evidence. Missing captured facts cannot borrow a request-time fix.
            return built.copy(location = facts?.trustedLocation,
                capturedAtElapsedRealtimeMs = facts?.capturedAtElapsedRealtimeMs)
        }

        fun assertRejected(output: TrackedObjectDepth, capture: Pair<DetectionSnapshot, TactileSnapshotFrameResult>) {
            for (trigger in listOf("auto", "on_screen", "voice")) {
                val input = input(output, capture, trigger)
                assertNull(input.coordinateGateStatus)
                assertEquals(AndroidReportCandidateResult.Rejected(AndroidReportCandidateRejection.COORDINATE_GATE_NOT_PASSED), policy.evaluate(input))
            }
        }
    }

    companion object {
        private fun field(main: MainActivity, name: String): Any? = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(main)
        private fun mainSource(): String {
            val relative = "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"
            val paths = listOf(Paths.get(relative), Paths.get("app", relative), Paths.get("apps/android/app", relative))
            return String(Files.readAllBytes(requireNotNull(paths.firstOrNull { Files.isRegularFile(it) })), Charsets.UTF_8)
        }
    }
}
