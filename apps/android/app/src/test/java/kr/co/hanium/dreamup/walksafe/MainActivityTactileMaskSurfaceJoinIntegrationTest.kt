package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.navigation.TactileMaskCaptureJoin
import kr.co.hanium.dreamup.walksafe.navigation.TactileMaskCaptureBundle
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import com.google.ar.core.Frame
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.FastSamDecoder
import kr.co.hanium.dreamup.walksafe.navigation.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Main's actual bounded join and composition consumer, with real decoded mask, depth pipeline and
 * Navigator. Pixels/AR receipt/readiness of the capture are controlled inputs, not an Activity/GL
 * or native-camera run. The three private asynchronous callers are checked separately as SOURCE.
 */
class MainActivityTactileMaskSurfaceJoinIntegrationTest {
    @Test fun completedOriginalCaptureReachesMainSurfaceAxisWithoutReprocessingDepth() {
        val f = Fixture()
        val before = f.depthCalls
        val bundle = f.complete()
        val result = f.main.evaluateTactileMaskCaptureBundle(bundle, f.decisionNs)
        assertNotNull(result.observationRejections.toString(), result.observation)
        val observation = requireNotNull(result.observation)
        assertNotNull("Main omitted the original mask surface from completed capture composition", observation.surfaceEvidence)
        val surfaceEvidence = requireNotNull(observation.surfaceEvidence)
        assertEquals(TactileObservedRouteSupport.AXIS_AND_MULTIPOINT_OVERLAP, surfaceEvidence.observedRouteSupport)
        assertSame(f.capture.imageReceipt, surfaceEvidence.surface.candidate.capture.imageReceipt)
        assertEquals(f.token, surfaceEvidence.surface.candidate.capture.token)
        assertEquals(0, surfaceEvidence.surface.candidate.namedDetectionIndex)
        assertSame(f.context, surfaceEvidence.surface.captureContext)
        assertEquals(f.token.cpuImageTimestampNs, surfaceEvidence.surface.depthTimestampNs)
        assertEquals(f.capture.originalDeadlineElapsedNs, surfaceEvidence.surface.candidate.capture.originalDeadlineElapsedNs)
        val output = result.outputs.single()
        assertEquals(f.output.copy(userFacing = output.userFacing), output)
        assertSame(f.output.contactDepth, output.contactDepth)
        assertEquals(before, f.depthCalls)
        assertNull(f.join.claim(f.decisionNs))
        assertEquals(LocalRouteMode.TMAP, result.decision.mode)
        assertNull(result.decision.steering)
        assertNull(result.decision.instruction)
    }

    @Test fun allThreeProducerOrdersWaitForOriginalOutputsAndConsumeOnce() {
        val orders = listOf("NMO", "NOM", "MNO", "MON", "ONM", "OMN")
        orders.forEach { order ->
            val f = Fixture()
            order.forEachIndexed { index, producer ->
                val ready = f.record(producer)
                assertEquals(order, index == 2, ready)
                if (index != 2) assertNull(order, f.join.claim(f.decisionNs))
            }
            val bundle = requireNotNull(f.join.claim(f.decisionNs))
            assertSame(f.snapshot, bundle.snapshot)
            assertSame(f.output, bundle.outputs.single())
            assertNotNull(order, f.main.evaluateTactileMaskCaptureBundle(bundle, f.decisionNs).observation?.surfaceEvidence)
            assertFalse(f.record('N')); assertFalse(f.record('M')); assertFalse(f.record('O'))
            assertNull(f.join.claim(f.decisionNs))
        }
    }

    @Test fun partialNamedResultAndForeignSnapshotCannotCompleteTheJoin() {
        val f = Fixture()
        f.record('M'); f.record('O')
        assertFalse(f.join.recordNamed(f.snapshot.copy(partial = true), f.result.copy(partial = true)))
        assertFalse(f.join.recordNamed(f.snapshot.copy(identity = f.snapshot.identity.copy(captureFrameId = f.token.frameId + 1)), f.result))
        assertFalse(f.join.recordNamed(f.snapshot.copy(capturedAtElapsedRealtimeMs = 100_001L), f.result))
        assertFalse(f.join.recordNamed(f.snapshot.copy(imageWidth = SIZE - 1), f.result))
        assertFalse(f.join.recordNamed(f.snapshot.copy(detections = emptyList()), f.result))
        assertNull(f.join.claim(f.decisionNs))
        assertTrue(f.record('N'))
        assertNotNull(f.main.evaluateTactileMaskCaptureBundle(requireNotNull(f.join.claim(f.decisionNs)), f.decisionNs).observation?.surfaceEvidence)
    }

    @Test fun foreignTokenCannotSupplyMasksButOriginalTokenStillCan() {
        val f = Fixture()
        f.record('N'); f.record('O')
        val foreign = listOf(
            copyToken(f.token, sessionEpoch = f.token.sessionEpoch + 1),
            copyToken(f.token, frameId = f.token.frameId + 1),
            copyToken(f.token, cpuNs = f.token.cpuImageTimestampNs + 1),
            copyToken(f.token, geometryId = "other-geometry"),
            copyToken(f.token, capturedNs = f.token.capturedElapsedNs + 1),
        )
        foreign.forEach { assertFalse(f.join.recordMasks(it, listOf(f.mask))) }
        assertNull(f.join.claim(f.decisionNs))
        assertTrue(f.record('M'))
        assertNotNull(f.main.evaluateTactileMaskCaptureBundle(requireNotNull(f.join.claim(f.decisionNs)), f.decisionNs).observation?.surfaceEvidence)
    }

    @Test fun originalDeadlineAndExplicitCancellationCannotBeRefreshedByLateCompletion() {
        val expired = Fixture()
        "NMO".forEach { expired.record(it) }
        assertNull(expired.join.claim(expired.capture.originalDeadlineElapsedNs + 1))
        assertEquals(expired.token.capturedElapsedNs + 800_000_000L, expired.capture.originalDeadlineElapsedNs)
        val cancelled = Fixture()
        cancelled.record('N'); cancelled.record('M'); cancelled.join.cancel()
        assertFalse(cancelled.record('O'))
        assertNull(cancelled.join.claim(cancelled.decisionNs))
        val replacement = Fixture()
        assertNotNull(replacement.main.evaluateTactileMaskCaptureBundle(replacement.complete(), replacement.decisionNs).observation?.surfaceEvidence)
    }

    @Test fun outputsForAnEqualButDifferentSnapshotDoNotJoinTheOriginalNamedPublication() {
        val f = Fixture()
        f.record('N'); f.record('M')
        assertFalse(f.join.recordOutputs(f.snapshot.copy(), listOf(f.output)))
        assertNull(f.join.claim(f.decisionNs))
        // The wrong publication is retained as the first producer value; completion cannot retag it.
        assertFalse(f.record('O'))
        assertNull(f.join.claim(f.decisionNs))
    }

    @Test fun foreignCpuReceiptFailsClosedThroughTheActualMainSelectorAndConsumer() {
        val f = Fixture(receiptCpuNs = 8_900_000_001L)
        val result = f.main.evaluateTactileMaskCaptureBundle(f.complete(), f.decisionNs)
        assertTrue(result.surfaceEvaluations.isEmpty())
        assertNull(result.observation?.surfaceEvidence)
        assertEquals(LocalRouteMode.TMAP, result.decision.mode)
        assertNull(result.decision.instruction)
    }

    @Test fun sourceIndexAndOriginalSourceClocksCannotBeRecoveredWithSurfaceOrLegacyFallback() {
        val mutations: List<(TrackedObjectDepth) -> TrackedObjectDepth> = listOf(
            { it.copy(sourceDetectionIndex = 1) },
            { it.copy(depthObservedAtMs = requireNotNull(it.depthObservedAtMs) + 1) },
            { it.copy(sourceObservedAtElapsedRealtimeMs = requireNotNull(it.sourceObservedAtElapsedRealtimeMs) + 1) },
            { it.copy(depthReprojected = true) },
        )
        mutations.forEach { corrupt ->
            val f = Fixture()
            f.record('N'); f.record('M'); f.join.recordOutputs(f.snapshot, listOf(corrupt(f.output)))
            val result = f.main.evaluateTactileMaskCaptureBundle(requireNotNull(f.join.claim(f.decisionNs)), f.decisionNs)
            assertNull(result.observation)
            assertEquals(LocalRouteMode.TMAP, result.decision.mode)
            assertNull(result.decision.instruction)
            assertEquals(4, f.depthCalls)
        }
    }

    @Test fun aNewerEmptyMainFrameDoesNotRetagTheLateOriginalBundle() {
        val f = Fixture()
        f.record('M'); f.record('O')
        val newer = f.snapshot.identity.copy(captureFrameId = f.token.frameId + 1, frameTimestampMs = 9_001L)
        f.main.evaluateTactileFrame(AndroidTactileFrameComposition.input(emptyList(), newer, null, null, 0,
            true, true, true, true, f.navigator.currentRouteId(), f.navigator.currentTactileRouteProgress(), 100_040L))
        assertTrue(f.record('N'))
        val bundle = requireNotNull(f.join.claim(f.decisionNs))
        val surface = requireNotNull(f.main.evaluateTactileMaskCaptureBundle(bundle, f.decisionNs).observation?.surfaceEvidence).surface
        assertEquals(f.token, surface.candidate.capture.token)
        assertSame(f.context, surface.captureContext)
        assertEquals(9_000L, surface.frameTimestampMs)
        assertEquals(100_000L, f.output.sourceObservedAtElapsedRealtimeMs)
        assertEquals(4, f.depthCalls)
    }

    @Test fun reinstallOfTheSameRouteIdUsesRealNavigatorRevisionAndRejectsOldAxis() {
        val f = Fixture()
        val bundle = f.complete()
        val originalProgress = f.navigator.currentTactileRouteProgress()
        f.navigator.setRoute(f.route)
        f.navigator.update(f.location, 100_050L, false)
        assertEquals(f.context.expectedRouteId, f.navigator.currentRouteId())
        assertNotEquals(originalProgress.routeVersion, f.navigator.currentTactileRouteProgress().routeVersion)
        val result = f.main.evaluateTactileMaskCaptureBundle(bundle, f.decisionNs)
        assertEquals(LocalRouteMode.TMAP, result.decision.mode)
        assertNull(result.decision.instruction)
        assertEquals("tactile_route_version_mismatch", result.decision.reason)
        assertEquals(4, f.depthCalls)
    }

    @Test fun realNavigatorProgressChangeRejectsTheOriginalSegmentWithoutChangingItsGeometry() {
        val f = Fixture()
        val bundle = f.complete()
        val originalProjection = f.context.routeProjection
        val next = TrustedLocation(37.0015, 127.0, .5f, 100_050L)
        f.navigator.update(next, 100_050L, false)
        assertEquals(1, f.navigator.currentTactileRouteProgress().segmentIndex)
        val result = f.main.evaluateTactileMaskCaptureBundle(bundle, f.decisionNs)
        assertEquals(LocalRouteMode.TMAP, result.decision.mode)
        assertNull(result.decision.instruction)
        assertEquals("tactile_segment_mismatch", result.decision.reason)
        assertSame(originalProjection, f.context.routeProjection)
        assertEquals(4, f.depthCalls)
    }

    @Test fun sourceActualThreeProducersAndWorkerKeepOwnershipAndOriginalCapture() {
        val s = source()
        val namedSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryArDetectorWorker.kt").readText()
        val unknownSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/unknown/UnknownCaptureCoordinator.kt").readText()
        val pipelineSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePipelineController.kt").readText()
        val named = MainActivityRefactorSource.member(namedSource, "run")
        val mainNamed = MainActivityRefactorSource.member(s, "createPrimaryArDetectorWorker")
        val pipeline = MainActivityRefactorSource.member(pipelineSource, "synthesize")
        val mainPipeline = MainActivityRefactorSource.property(s, "framePipelineController")
        val tactilePeer = MainActivityRefactorSource.property(s, "frameTactileResults")
        assertTrue(mainPipeline.contains("val tactile = frameTactileResults"))
        assertTrue(mainPipeline.contains("frameRouteEvidence, tactile, risk, recording"))
        MainActivityRefactorSource.composition(s, "unknownCaptureCoordinator")
        val mainUnknown = MainActivityRefactorSource.member(s, "createVisionUnknownComposition")
        assertTrue(named.contains("recording.namedCapture(work.input, published, observed.result)"))
        assertTrue(pipeline.contains("tactile.recordFrozen(detection, frozenOutputs)"))
        assertTrue(mainNamed.contains("recordCompletedNamedTactileCapture(input, published, result)"))
        assertTrue(tactilePeer.contains("recordFrozenTactileCaptureOutputs(snapshot, outputs)"))
        assertTrue(MainActivityRefactorSource.member(unknownSource, "consumeUnknownObjectResult").contains("join.capture.recordMasks(result.token, result.masks)"))
        val worker = MainActivityRefactorSource.member(unknownSource, "scheduleUnknownTactileJoin")
        assertTrue(worker.contains("observer.publishTactile(bundle, surfaces, publication, decisionNs,"))
        assertTrue(worker.contains("join.modelGeneration != detectorLoadGeneration"))
        assertTrue(worker.contains("isUnknownCaptureContextCurrent(join.context, join.processingLease)"))
        assertTrue(worker.contains("isUnknownContextCurrent(join.context)"))
        assertTrue(worker.contains("if (!finished) join.capture.cancel()"))
        assertTrue(worker.contains("if (join.context.tactileJoin === join) join.context.tactileJoin = null"))
        assertTrue(mainUnknown.contains("deliverTactileMaskCaptureBundle(bundle, decisionNs, surfaces, source,"))
        assertTrue(mainUnknown.contains("publishCompletedTactileMaskCapture(publication, frame, receipt, decisionNs, outputIsCurrent)"))
        val captureSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/capture/TactileFrameCaptureController.kt").readText()
        val consumer = MainActivityRefactorSource.member(captureSource, "evaluateTactileMaskCaptureBundle")
        assertTrue(MainActivityRefactorSource.member(s, "evaluateTactileMaskCaptureBundle").contains("tactileCapture.evaluateTactileMaskCaptureBundle("))
        assertTrue(consumer.contains("activeRouteProgress = navigator().currentTactileRouteProgress()"))
        assertTrue(consumer.contains("val evidence = bundle.snapshot.frameEvidence") && consumer.contains("context = evidence?.tactileContext"))
        assertFalse(consumer.contains("processSnapshot(")); assertFalse(consumer.contains("pipeline.process("))
    }

    @Test fun sourceNormalSamCloseIsNotAnEvictionAndLifecycleCancelsExactJoinFirst() {
        val main = source()
        val s = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/unknown/UnknownCaptureCoordinator.kt").readText()
        MainActivityRefactorSource.composition(main, "unknownCaptureCoordinator")
        val cancel = MainActivityRefactorSource.member(s, "cancelUnknownTactileJoin")
        assertTrue(cancel.contains("if (join.context.tactileJoin === join)"))
        val invalidate = MainActivityRefactorSource.member(s, "invalidateUnknownObjectObservations", occurrence = 1)
        assertTrue(invalidate.indexOf("tactileJoin?.let(::cancelUnknownTactileJoin)") < invalidate.indexOf("unknownProcessingContext = null"))
        val consume = MainActivityRefactorSource.member(s, "consumeUnknownObjectResult")
        assertFalse(consume.contains("captureLease.isClosed"))
        val named = MainActivityRefactorSource.member(s, "recordCompletedNamedTactileCapture")
        assertTrue(named.contains("input.timestampEvidence !== join.capture.semanticCapture.imageReceipt"))
        assertTrue(named.contains("input.unknownLuma?.service !== join.context.service"))
    }

    internal class Fixture(receiptCpuNs: Long = 8_900_000_000L) {
        val main = MainActivity()
        val navigator = MainActivityControllerTestFixture.field("routeNavigator").apply { isAccessible = true }.get(main) as RouteNavigator
        val route = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(230, 190),
            listOf(RoutePoint(36.9999, 127.0), RoutePoint(37.001, 127.0), RoutePoint(37.002, 127.0)),
            emptyList(), providerRouteId = "band-route")
        val location = TrustedLocation(37.0, 127.0, .5f, 100_000L)
        private val s = 1f / sqrt(2f)
        // Source identity is consistent from creation: Main uses original AR Frame ns as frame ID.
        val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, 1, 9_000_000_000L,
            9_000_000_000L, 8_900_000_000L, 100_000_000_000L, "band-control-q0", SIZE, SIZE)
        val decisionNs = token.capturedElapsedNs + 50_000_000L
        val capture = TactileSemanticCapture(CameraImageTimestampEvidence(Any(), 1, object : Frame() {},
            token.cameraTimestampNs, receiptCpuNs), token, 0, token.capturedElapsedNs + 800_000_000L)
        val mask = decodeMask { x, y -> x in 78..113 && y in 72..143 }
        val detection = DetectionCandidate(TactileSemanticKind.LINE.className, .9f, RectNorm(0f, 0f, 1f, 1f),
            listOf(Point2(78f / SIZE, 72f / SIZE), Point2(114f / SIZE, 72f / SIZE),
                Point2(114f / SIZE, 144f / SIZE), Point2(78f / SIZE, 144f / SIZE)), mask.area().toFloat() / (SIZE * SIZE))
        private val classes = TactileSemanticKind.values().map { it.className }
        private val model = ModelRuntimeConfig(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, "model/existing.tflite", 768,
            classes, classes.toSet(), classes.associateWith { .25f }, artifactSha256 = "d".repeat(64))
        val config = TwoModelRuntimeConfig("band-control", null, model.key, null, model, null, null)
        val result = AndroidDetectionResult(listOf(detection), timing = AndroidDetectorTiming(modelKey = model.key))
        val context: TactileProjectionContext
        val snapshot: DetectionSnapshot
        val frozen: FrozenUnknownDepthCapture
        val output: TrackedObjectDepth
        val join: TactileMaskCaptureJoin
        var depthCalls = 0
        init {
            navigator.setRoute(route)
            val navigation = navigator.update(location, 100_000L, false)
            val projection = requireNotNull(navigator.currentProjection(location))
            assertEquals(0, navigator.currentTactileRouteProgress().segmentIndex)
            MainActivityControllerTestFixture.field("isRouteActive").apply { isAccessible = true }.setBoolean(main, navigator.currentRouteId() != null)
            MainActivityControllerTestFixture.field("latestTmapOnRoute").apply { isAccessible = true }.setBoolean(main, !navigation.offRoute)
            val identity = RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            context = TactileProjectionContext(token.frameId, navigator.currentRouteId(), projection, location,
                DeviceEarthOrientation(identity, 100_000L, 1f, EarthOrientationAccuracy.HIGH), 0f,
                TactileCameraFrame(token.frameId, RotationMatrix3(1f, 0f, 0f, 0f, s, -s, 0f, s, s),
                    TactileCameraIntrinsics(SIZE, SIZE, 288f, 288f, 96f, 96f), 100_000L, true),
                50, 100_000L, decisionNs / 1_000_000L)
            val depth = depthSnapshot(token.cameraTimestampNs, token.cpuImageTimestampNs)
            frozen = requireNotNull(FrozenUnknownDepthCapture.freeze(token, depth,
                doubleArrayOf(1.0 / SIZE, 0.0, 0.0, 0.0, 1.0 / SIZE, 0.0, 0.0, 0.0, 1.0), token.frameId, imageQuarterTurns = 0))
            val transform = requireNotNull(FrozenImageToDepthTransform.create(token.frameId,
                listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f)), Point2(.5f, .5f)))
            val evidence = DetectionFrameEvidence(token.frameId, 9_000L, depth,
                FrozenImageToTextureCoordinateMapper(token.frameId, transform, ImageSize(SIZE, SIZE)), context,
                MotionContext(), true, true, imageQuarterTurns = 0)
            snapshot = DetectionSnapshot(listOf(detection), AndroidDetectionSnapshotFrameIdentity(token.frameId, 9_000L),
                100_000L, 100_000L, 100_010L, SIZE, SIZE, 10L, result.timing, partial = false,
                frameEvidence = evidence, capturedAtElapsedRealtimeMs = 100_000L)
            // Same measured 4→7-frame pattern as BandContactSupportBoundaryTest; no hand-made output.
            val pipeline = ObjectDepthRuntimePipeline()
            val mapper = LetterboxCoordinateMapper(ModelInputTransform(SIZE, SIZE, SIZE, SIZE, 1f, 0f, 0f), ImageSize(SIZE, SIZE))
            output = (4L..7L).map { frame ->
                val offset = (7 - frame) * 300L
                val d = depthSnapshot(token.cameraTimestampNs - offset * 1_000_000L, token.cpuImageTimestampNs - offset * 1_000_000L)
                depthCalls++
                pipeline.process(d, d.frameTimestampNs, d.frameTimestampNs / 1_000_000L, listOf(detection),
                    d.frameTimestampNs, mapper = mapper, mapperFrameId = d.frameTimestampNs, imageQuarterTurns = 0,
                    capturedAtElapsedRealtimeMs = 100_000L - offset).single()
            }.last()
            join = TactileMaskCaptureJoin(capture, frozen, config)
        }
        fun record(producer: Char): Boolean = when (producer) {
            'N' -> join.recordNamed(snapshot, result)
            'M' -> join.recordMasks(token, listOf(mask))
            'O' -> join.recordOutputs(snapshot, listOf(output))
            else -> error("unknown producer")
        }
        fun complete(): TactileMaskCaptureBundle {
            "NMO".forEach { record(it) }
            return requireNotNull(join.claim(decisionNs))
        }
        private fun depthSnapshot(cameraNs: Long, cpuNs: Long): DepthFrameSnapshot {
            val cameraMs = cameraNs / 1_000_000L
            val depths = IntArray(SIZE * SIZE) { (1.5f / (s + s * ((it / SIZE + .5 - 96) / 288).toFloat()) * 1000).roundToInt() }
            val pose = CameraPoseEvidence(7, cameraMs, 0f, 1.5f, 0f, 0f, -s, -s,
                CameraImageProjection(SIZE, SIZE, 288f, 288f, 96f, 96f, 1f, 0f, 0f, 0f, s, -s), Vec3(0f, 1f, 0f),
                listOf(CapturedHorizontalPlane(7, cameraMs, listOf(Vec3(-5f, 0f, -5f), Vec3(5f, 0f, -5f),
                    Vec3(5f, 0f, 5f), Vec3(-5f, 0f, 5f)))))
            return DepthFrameSnapshot(cameraNs, DepthImage16(SIZE, SIZE, depths),
                ConfidenceImage8(SIZE, SIZE, ByteArray(depths.size) { 255.toByte() }), null,
                rawDepthTimestampNs = cpuNs, rawConfidenceTimestampNs = cpuNs, cameraImageTimestampNs = cpuNs,
                cameraPoseEvidence = pose)
        }
    }

    private companion object {
        const val SIZE = FastSamDecoder.PROTO_SIZE
        fun copyToken(t: FastSamFrameToken, sessionEpoch: Long = t.sessionEpoch, frameId: Long = t.frameId,
            cpuNs: Long = t.cpuImageTimestampNs, capturedNs: Long = t.capturedElapsedNs,
            geometryId: String = t.geometryId) = FastSamFrameToken(t.source, sessionEpoch, frameId,
                t.cameraTimestampNs, cpuNs, capturedNs, geometryId, t.width, t.height)
        fun decodeMask(foreground: (Int, Int) -> Boolean) = FastSamDecoder().decode(
            FloatArray(FastSamDecoder.CHANNELS * FastSamDecoder.ANCHORS).also {
                it[0] = .5f; it[FastSamDecoder.ANCHORS] = .5f
                it[2 * FastSamDecoder.ANCHORS] = 1f; it[3 * FastSamDecoder.ANCHORS] = 1f
                it[4 * FastSamDecoder.ANCHORS] = .9f; it[5 * FastSamDecoder.ANCHORS] = 1f
            }, FloatArray(SIZE * SIZE * FastSamDecoder.MASK_CHANNELS).also {
                for (y in 0 until SIZE) for (x in 0 until SIZE)
                    it[(y * SIZE + x) * FastSamDecoder.MASK_CHANNELS] = if (foreground(x, y)) 1f else -1f
            }, SIZE, SIZE).instances().single()
        fun source(): String = sequenceOf(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
            File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        ).first { it.isFile }.readText()
    }
}
