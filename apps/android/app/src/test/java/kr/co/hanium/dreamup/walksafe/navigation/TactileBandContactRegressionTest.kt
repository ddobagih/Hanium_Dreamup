package kr.co.hanium.dreamup.walksafe.navigation

import com.google.ar.core.Frame
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.depth.unknown.MaskDepthEstimator
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.FastSamDecoder
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Controlled decoder/depth input; no learned semantic accuracy or physical route authority claim. */
class TactileBandContactRegressionTest {
    @Test fun oneBottomPixelSpurCannotDisplaceSupportedTypedContactRay() {
        val body = Scene(false)
        val spur = Scene(true)
        assertEquals(SIZE, body.mask.imageWidth())
        assertEquals(SIZE, body.mask.imageHeight())
        assertEquals(SIZE, spur.mask.imageWidth())
        assertEquals(SIZE, spur.mask.imageHeight())
        assertEquals(36 * 72, body.mask.area())
        assertEquals(body.mask.area() + 1, spur.mask.area())
        val a = body.mask.copyPackedBits()
        val b = spur.mask.copyPackedBits()
        assertEquals(a.size, b.size)
        assertEquals(1, a.indices.sumOf { Integer.bitCount((a[it].toInt() xor b[it].toInt()) and 255) })
        assertFalse(body.mask.contains(110, 144))
        assertTrue(spur.mask.contains(110, 144))
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            assertEquals("body foreground at ($x,$y)", x in 78..113 && y in 72..143, body.mask.contains(x, y))
            assertEquals("spur foreground at ($x,$y)", body.mask.contains(x, y) || (x == 110 && y == 144), spur.mask.contains(x, y))
        }
        assertEquals(body.detection.bboxNorm, spur.detection.bboxNorm)
        assertEquals(body.detection.className, spur.detection.className)
        assertEquals(body.token.cameraTimestampNs, spur.token.cameraTimestampNs)
        assertEquals(body.token.cpuImageTimestampNs, spur.token.cpuImageTimestampNs)
        assertEquals(body.token.capturedElapsedNs, spur.token.capturedElapsedNs)
        assertEquals(body.token.geometryId, spur.token.geometryId)
        assertArrayEquals(body.depths, spur.depths)

        val bodyOutput = body.produceOutput()
        val spurOutput = spur.produceOutput()
        // The unchanged polygon extractor really creates the contact displaced by one added bit.
        assertEquals(Point2(.5f, .75f), bodyOutput.bottomContactNorm)
        assertEquals(110.5f / SIZE, requireNotNull(spurOutput.bottomContactNorm).x, .000001f)
        assertEquals(145f / SIZE, requireNotNull(spurOutput.bottomContactNorm).y, .000001f)
        val bodyGuide = body.guide(bodyOutput)
        val spurGuide = spur.guide(spurOutput)
        val bodyObservation = requireNotNull(bodyGuide.observation) { "body rejected: ${bodyGuide.observationRejections}" }
        val spurObservation = requireNotNull(spurGuide.observation) { "spur rejected: ${spurGuide.observationRejections}" }
        assertTypedPositive(bodyGuide, body)
        assertTypedPositive(spurGuide, spur)
        assertEquals(bodyOutput.source, spurOutput.source)
        assertEquals(bodyOutput.sourceDetectionIndex, spurOutput.sourceDetectionIndex)
        assertEquals(bodyOutput.imageQuarterTurns, spurOutput.imageQuarterTurns)
        assertEquals(bodyOutput.depthObservedAtMs, spurOutput.depthObservedAtMs)
        assertEquals(bodyOutput.sourceObservedAtElapsedRealtimeMs, spurOutput.sourceObservedAtElapsedRealtimeMs)
        assertEquals(bodyOutput.sourceValidUntilElapsedRealtimeMs, spurOutput.sourceValidUntilElapsedRealtimeMs)
        // One original sensor pixel allows grid quantization; it is not a product threshold.
        // All typed support prerequisites above must pass before this consumer assertion is reached.
        assertEquals("one bottom pixel must not move the supported typed contact ray by many pixels",
            bodyObservation.centerXNormalized, spurObservation.centerXNormalized, 1f / SIZE)
    }

    private fun assertTypedPositive(result: TactileRouteGuidanceResult, scene: Scene) {
        val evaluation = requireNotNull(result.surfaceEvaluations[0])
        assertNull(evaluation.rejection)
        val evidence = requireNotNull(evaluation.evidence)
        assertEquals(TactileObservedRouteSupport.AXIS_AND_MULTIPOINT_OVERLAP, evidence.observedRouteSupport)
        assertEquals(1, evidence.originalMaskComponentCount)
        assertEquals(0, evidence.originalMaskHoleCount)
        assertFalse(evidence.originalMaskBranching)
        assertEquals(1, evidence.components.size)
        assertTrue(requireNotNull(evidence.components.single().axis).routeAngleDeg < 5)
        assertTrue(evidence.overlaps.size > 1)
        assertSame(scene.capture, evidence.surface.candidate.capture)
        assertEquals(MaskDepthEstimator.Status.KNOWN, evidence.surface.samplingStatus)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, evidence.surface.source)
        assertEquals(scene.token.cpuImageTimestampNs, evidence.surface.depthTimestampNs)
        assertTrue(evidence.surface.depthMatchesCameraImage)
        assertSame(evidence, requireNotNull(result.observation).surfaceEvidence)
        evidence.surface.samples.forEach { sample ->
            assertEquals(scene.depths[sample.depthCellIndex] / 1000f, sample.axialDepthM, 0f)
            assertTrue(scene.mask.contains(sample.imageX.toInt(), sample.imageY.toInt()))
        }
    }

    private class Scene(spur: Boolean) {
        val mask: InstanceMask = decodeMask(spur)
        val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, 1, 7,
            9_000_000_000L, 8_900_000_000L, 100_000_000_000L, "band-192-q0", SIZE, SIZE)
        val decisionNs = token.capturedElapsedNs + 50_000_000L
        val capture = TactileSemanticCapture(CameraImageTimestampEvidence(Any(), 1, object : Frame() {},
            token.cameraTimestampNs, token.cpuImageTimestampNs), token, 0, token.capturedElapsedNs + 800_000_000L)
        private val s = 1f / sqrt(2f)
        val depths = IntArray(SIZE * SIZE) { i ->
            (1.5f / (s + s * ((i / SIZE + .5 - 96) / 288).toFloat()) * 1000).roundToInt()
        }
        val detection = DetectionCandidate(TactileSemanticKind.LINE.className, .9f, RectNorm(0f, 0f, 1f, 1f),
            polygonNorm = (if (spur) listOf(78 to 72, 114 to 72, 114 to 144, 111 to 144,
                111 to 145, 110 to 145, 110 to 144, 78 to 144)
                else listOf(78 to 72, 114 to 72, 114 to 144, 78 to 144)).map { Point2(it.first.toFloat() / SIZE, it.second.toFloat() / SIZE) },
            maskAreaNorm = mask.area().toFloat() / (SIZE * SIZE))
        private val identity = RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val context = TactileProjectionContext(token.frameId, "band-route",
            ActiveRouteProjection("band-route", 0, RoutePoint(36.9999, 127.0), RoutePoint(37.001, 127.0), 0.0, 0f, 1),
            TrustedLocation(37.0, 127.0, .5f, 100_000L),
            DeviceEarthOrientation(identity, 100_000L, 1f, EarthOrientationAccuracy.HIGH), 0f,
            TactileCameraFrame(token.frameId, RotationMatrix3(1f, 0f, 0f, 0f, s, -s, 0f, s, s),
                TactileCameraIntrinsics(SIZE, SIZE, 288f, 288f, 96f, 96f), 100_000L, true),
            50, 100_000L, decisionNs / 1_000_000L)

        fun produceOutput(): TrackedObjectDepth {
            val pipeline = ObjectDepthRuntimePipeline()
            val mapper = LetterboxCoordinateMapper(ModelInputTransform(SIZE, SIZE, SIZE, SIZE, 1f, 0f, 0f), ImageSize(SIZE, SIZE))
            var final: TrackedObjectDepth? = null
            // Four distinct captures advance the original detector confirmation and clock history.
            for (frame in 4L..7L) {
                val offsetMs = (7 - frame) * 300L
                val snapshot = snapshot(token.cameraTimestampNs - offsetMs * 1_000_000L,
                    token.cpuImageTimestampNs - offsetMs * 1_000_000L)
                final = pipeline.process(snapshot, frame, snapshot.frameTimestampNs / 1_000_000L,
                    listOf(detection), detectionSequenceId = frame, mapper = mapper, mapperFrameId = frame,
                    imageQuarterTurns = 0, capturedAtElapsedRealtimeMs = 100_000L - offsetMs).single()
            }
            return requireNotNull(final).also { output ->
                assertEquals(token.frameId, output.frameId)
                assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
                assertEquals(0, output.sourceDetectionIndex)
                assertEquals(0, output.imageQuarterTurns)
                assertEquals(4, output.trackAgeFrames)
                assertEquals(900L, output.trackStableMs)
                assertTrue(TactileObservationEvidence.hasTrustedMetricDepth(output))
                assertTrue(requireNotNull(output.contactDepth).validSampleCount >= 5)
                assertTrue(requireNotNull(output.contactDepth).spreadM <= .35f)
                // CPU Image time and AR frame time remain distinct; the existing clock binds them.
                assertEquals(9_000L, requireNotNull(output.depthObservedAtMs))
                assertEquals(100_000L, requireNotNull(output.sourceObservedAtElapsedRealtimeMs))
                assertTrue(requireNotNull(output.sourceValidUntilElapsedRealtimeMs) >= decisionNs / 1_000_000L)
            }
        }

        fun guide(output: TrackedObjectDepth): TactileRouteGuidanceResult {
            val classes = TactileSemanticKind.values().map { it.className }
            val model = ModelRuntimeConfig(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, "model/existing.tflite", 768,
                classes, classes.toSet(), classes.associateWith { .25f }, artifactSha256 = "d".repeat(64))
            val config = TwoModelRuntimeConfig("band-fixture", null, model.key, null, model, null, null)
            val named = TactileNamedSemanticEvidence.fromResult(capture,
                AndroidDetectionResult(listOf(detection), timing = AndroidDetectorTiming(modelKey = model.key)),
                TactileSemanticCoordinates.SENSOR_ORIGINAL)
            val selection = TactileSemanticMaskSelector(config).select(named,
                TactileInstanceMaskEvidence(capture, token, listOf(mask), TactileSemanticCoordinates.SENSOR_ORIGINAL), decisionNs)
            val candidate = selection.candidates.single()
            assertEquals(0, candidate.namedDetectionIndex)
            assertEquals(mask.area(), candidate.sensorMask.area)
            val frozen = requireNotNull(FrozenUnknownDepthCapture.freeze(token,
                snapshot(token.cameraTimestampNs, token.cpuImageTimestampNs),
                doubleArrayOf(1.0 / SIZE, 0.0, 0.0, 0.0, 1.0 / SIZE, 0.0, 0.0, 0.0, 1.0), token.frameId))
            val measured = TactileMaskSurfaceProjector().project(candidate, frozen, decisionNs, context)
            assertNull(measured.rejection)
            assertNotNull(measured.surface)
            return AndroidTactileRouteGuidance().apply(listOf(output), context, true, true, "band-route",
                TactileActiveRouteProgress(1, 0), tactileSurfaces = listOf(measured), decisionElapsedRealtimeNs = decisionNs)
        }

        private fun snapshot(cameraNs: Long, cpuNs: Long): DepthFrameSnapshot {
            val cameraMs = cameraNs / 1_000_000L
            val pose = CameraPoseEvidence(7, cameraMs, 0f, 1.5f, 0f, 0f, -s, -s,
                CameraImageProjection(SIZE, SIZE, 288f, 288f, 96f, 96f, 1f, 0f, 0f, 0f, s, -s), Vec3(0f, 1f, 0f),
                listOf(CapturedHorizontalPlane(7, cameraMs, listOf(Vec3(-5f, 0f, -5f), Vec3(5f, 0f, -5f),
                    Vec3(5f, 0f, 5f), Vec3(-5f, 0f, 5f)))))
            return DepthFrameSnapshot(cameraNs, DepthImage16(SIZE, SIZE, depths),
                ConfidenceImage8(SIZE, SIZE, ByteArray(SIZE * SIZE) { 255.toByte() }), null,
                rawDepthTimestampNs = cpuNs, rawConfidenceTimestampNs = cpuNs,
                cameraImageTimestampNs = cpuNs, cameraPoseEvidence = pose)
        }
    }

    private companion object {
        const val SIZE = FastSamDecoder.PROTO_SIZE

        fun decodeMask(spur: Boolean): InstanceMask {
            val detections = FloatArray(FastSamDecoder.CHANNELS * FastSamDecoder.ANCHORS)
            detections[0] = .5f
            detections[FastSamDecoder.ANCHORS] = .5f
            detections[2 * FastSamDecoder.ANCHORS] = 1f
            detections[3 * FastSamDecoder.ANCHORS] = 1f
            detections[4 * FastSamDecoder.ANCHORS] = .9f
            detections[5 * FastSamDecoder.ANCHORS] = 1f
            val prototypes = FloatArray(SIZE * SIZE * FastSamDecoder.MASK_CHANNELS)
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                prototypes[(y * SIZE + x) * FastSamDecoder.MASK_CHANNELS] =
                    if ((x in 78..113 && y in 72..143) || (spur && x == 110 && y == 144)) 1f else -1f
            }
            // At original size 192, AxisMap has weight zero and preserves these exact bits.
            return FastSamDecoder().decode(detections, prototypes, SIZE, SIZE).instances().single()
        }
    }
}
