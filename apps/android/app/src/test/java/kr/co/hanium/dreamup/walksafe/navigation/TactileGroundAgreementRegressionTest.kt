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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Synthetic same-capture geometry; this does not establish a calibrated ground error bound. */
class TactileGroundAgreementRegressionTest {
    @Test fun differentCapturedHorizontalLayersCannotSelectNormalCandidateInEitherOrder() {
        val scene = Scene()
        val output = scene.produceOutput()
        assertPositive(scene.guide(output, listOf(scene.ground)), scene)
        val otherLayer = scene.ground.copy(polygonInAnchor = scene.ground.polygonInAnchor.map {
            it.copy(y = it.y + scene.cameraHeightM)
        })
        assertEquals(scene.ground.referenceId, otherLayer.referenceId)
        assertEquals(scene.ground.captureTimestampMs, otherLayer.captureTimestampMs)
        assertTrue(otherLayer.polygonInAnchor.all { it.y == scene.cameraHeightM })
        // Both complete production paths run before the rejecting assertions, so an OLD failure
        // records both orders rather than stopping after observing only the first order.
        val results = listOf(listOf(scene.ground, otherLayer), listOf(otherLayer, scene.ground))
            .map { scene.guide(output, it) }
        val diagnostic = results.map { result ->
            val evidence = result.surfaceEvaluations[0]?.evidence
            "admitted=${result.observation != null}, rejection=${result.surfaceEvaluations[0]?.rejection}, " +
                "selectedHeights=${evidence?.plane?.polygonInAnchor?.map { it.y }}, " +
                "residuals=${evidence?.components?.flatMap { it.points }?.map { it.planeHeightResidualM }?.distinct()}"
        }.joinToString(" | ")
        results.forEach { result ->
            val evaluation = requireNotNull(result.surfaceEvaluations[0])
            assertNotNull("different captured layers must reject instead of selecting first: $diagnostic", evaluation.rejection)
            assertNull(evaluation.evidence)
            assertNull(result.observation)
            assertEquals(TactileObservationRejection.SURFACE_UNAVAILABLE, result.observationRejections[output.trackId])
            assertEquals(LocalRouteMode.TMAP, result.decision.mode)
            assertNull(result.decision.instruction)
            assertEquals("tactile_not_visible", result.decision.reason)
        }
    }

    @Test fun singleCapturedPlaneKeepsTypedObservedCandidateWithoutRouteAuthority() {
        val scene = Scene()
        val result = scene.guide(scene.produceOutput(), listOf(scene.ground))
        assertPositive(result, scene)
        assertEquals(scene.ground, result.surfaceEvaluations[0]!!.evidence!!.plane)
    }

    @Test fun exactDuplicateCapturedPlaneKeepsContactAndOriginalMeasurements() {
        val scene = Scene()
        val output = scene.produceOutput()
        val duplicate = scene.ground.copy(polygonInAnchor = scene.ground.polygonInAnchor.toList())
        assertNotSame(scene.ground, duplicate)
        val single = scene.guide(output, listOf(scene.ground))
        val duplicates = listOf(listOf(scene.ground, duplicate), listOf(duplicate, scene.ground))
            .map { scene.guide(output, it) }
        assertPositive(single, scene)
        duplicates.forEach {
            assertPositive(it, scene)
            assertSameMeasurementsAndContact(single, it)
        }
    }

    @Test fun equivalentPolygonWindingAndStartRemainOrderIndependent() {
        val scene = Scene()
        val output = scene.produceOutput()
        val polygon = scene.ground.polygonInAnchor
        val equivalent = scene.ground.copy(polygonInAnchor = (polygon.drop(1) + polygon.take(1)).reversed())
        val single = scene.guide(output, listOf(scene.ground))
        val duplicates = listOf(listOf(scene.ground, equivalent), listOf(equivalent, scene.ground))
            .map { scene.guide(output, it) }
        assertPositive(single, scene)
        duplicates.forEach {
            assertPositive(it, scene)
            assertSameMeasurementsAndContact(single, it)
        }
    }

    @Test fun groundFittedContactKeepsSelectedCellCoordinatesAndOwnDepthInActualSupplier() {
        // A local depth perturbation is a counterexample stimulus, not a product tolerance.
        val scene = Scene(centerDepthOffsetMm = -50)
        val output = scene.produceOutput()
        val supplied = mutableListOf<TrackedObjectDepth>()
        val delegate = AndroidTactileRouteObservationSupplier()
        val recording = TactileRouteObservationSupplier { view, context ->
            supplied.add(view)
            delegate.observationFor(view, context)
        }
        val result = scene.guide(output, listOf(scene.ground), recording)
        assertPositive(result, scene)
        val evidence = requireNotNull(result.surfaceEvaluations[0]?.evidence)
        val center = requireNotNull(evidence.surface.measuredContact)
        assertEquals(95, center.sample.depthX)
        assertEquals(140, center.sample.depthY)
        assertTrue(center.depth.validSampleCount >= 5)
        assertTrue(center.depth.spreadM <= .35f)
        val points = evidence.components.flatMap { it.points }
        val alternative = points.single { it.sample.depthX == 101 && it.sample.depthY == 140 }
        val alternateNeighborhood = evidence.surface.samples.filter {
            it.depthX in 99..103 && it.depthY in 138..142
        }
        assertEquals(25, alternateNeighborhood.size)
        assertTrue(alternateNeighborhood.all { scene.mask.contains(it.imageX.toInt(), it.imageY.toInt()) })
        val centerResidual = abs(points.single { it.sample.depthCellIndex == center.sample.depthCellIndex }.planeHeightResidualM)
        val alternativeResidual = abs(alternative.planeHeightResidualM)
        assertTrue("the measured alternative must fit the same captured plane better", centerResidual > alternativeResidual)
        val view = supplied.single()
        val selected = points.single { point ->
            val sample = point.sample
            view.bottomContactNorm == Point2((sample.imageX / SIZE).toFloat(), (sample.imageY / SIZE).toFloat())
        }
        assertEquals(selected.sample.axialDepthM, requireNotNull(view.contactDepth).distanceM, 0f)
        assertEquals(scene.depths[selected.sample.depthCellIndex] / 1000f, view.contactDepth!!.distanceM, 0f)
        assertEquals(output.bottomContactNorm, result.outputs.single().bottomContactNorm)
        assertEquals(output.contactDepth, result.outputs.single().contactDepth)
        val diagnostic = "selected=(${selected.sample.depthX},${selected.sample.depthY}) ownZ=${selected.sample.axialDepthM} " +
            "residual=${abs(selected.planeHeightResidualM)} centerResidual=$centerResidual alternativeResidual=$alternativeResidual " +
            "support=${view.contactDepth}"
        assertTrue("actual Supplier must receive a better ground-fitting supported cell: $diagnostic",
            abs(selected.planeHeightResidualM) <= alternativeResidual)
    }

    private fun assertSameMeasurementsAndContact(a: TactileRouteGuidanceResult, b: TactileRouteGuidanceResult) {
        val first = requireNotNull(a.observation)
        val second = requireNotNull(b.observation)
        assertEquals(first.centerXNormalized, second.centerXNormalized, 0f)
        val firstPoints = first.surfaceEvidence!!.components.flatMap { it.points }
        val secondPoints = second.surfaceEvidence!!.components.flatMap { it.points }
        assertEquals(firstPoints.map { it.sample }, secondPoints.map { it.sample })
        assertEquals(firstPoints.map { it.planeHeightResidualM }, secondPoints.map { it.planeHeightResidualM })
    }

    private fun assertPositive(result: TactileRouteGuidanceResult, scene: Scene) {
        val evaluation = requireNotNull(result.surfaceEvaluations[0])
        assertNull(evaluation.rejection)
        val evidence = requireNotNull(evaluation.evidence)
        assertEquals(TactileObservedRouteSupport.AXIS_AND_MULTIPOINT_OVERLAP, evidence.observedRouteSupport)
        assertEquals(1, evidence.originalMaskComponentCount)
        assertEquals(0, evidence.originalMaskHoleCount)
        assertFalse(evidence.originalMaskBranching)
        assertTrue(evidence.components.single().axis!!.routeAngleDeg < 5)
        assertTrue(evidence.overlaps.size > 1)
        assertSame(scene.capture, evidence.surface.candidate.capture)
        assertEquals(MaskDepthEstimator.Status.KNOWN, evidence.surface.samplingStatus)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, evidence.surface.source)
        assertEquals(scene.token.cpuImageTimestampNs, evidence.surface.depthTimestampNs)
        assertTrue(evidence.surface.depthMatchesCameraImage)
        assertSame(evidence, requireNotNull(result.observation).surfaceEvidence)
        assertEquals(LocalRouteMode.TMAP, result.decision.mode)
        assertNull(result.decision.instruction)
        assertEquals("tactile_motion_evidence_unavailable", result.decision.reason)
        evidence.surface.samples.forEach { sample ->
            assertEquals(scene.depths[sample.depthCellIndex] / 1000f, sample.axialDepthM, 0f)
            assertTrue(scene.mask.contains(sample.imageX.toInt(), sample.imageY.toInt()))
        }
    }

    private class Scene(private val centerDepthOffsetMm: Int = 0) {
        val cameraHeightM = 1.5f
        val mask: InstanceMask = decodeMask()
        val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, 1, 7,
            9_000_000_000L, 8_900_000_000L, 100_000_000_000L, "ground-192-q0", SIZE, SIZE)
        val decisionNs = token.capturedElapsedNs + 50_000_000L
        val capture = TactileSemanticCapture(CameraImageTimestampEvidence(Any(), 1, object : Frame() {},
            token.cameraTimestampNs, token.cpuImageTimestampNs), token, 0, token.capturedElapsedNs + 800_000_000L)
        private val s = 1f / sqrt(2f)
        val depths = IntArray(SIZE * SIZE) { i ->
            (cameraHeightM / (s + s * ((i / SIZE + .5 - 96) / 288).toFloat()) * 1000).roundToInt() +
                if (i % SIZE in 93..97 && i / SIZE in 138..142) centerDepthOffsetMm else 0
        }
        val ground = CapturedHorizontalPlane(7, token.cameraTimestampNs / 1_000_000L,
            listOf(Vec3(-5f, 0f, -5f), Vec3(5f, 0f, -5f), Vec3(5f, 0f, 5f), Vec3(-5f, 0f, 5f)))
        private val detection = DetectionCandidate(TactileSemanticKind.LINE.className, .9f, RectNorm(0f, 0f, 1f, 1f),
            polygonNorm = listOf(78 to 72, 114 to 72, 114 to 144, 78 to 144)
                .map { Point2(it.first.toFloat() / SIZE, it.second.toFloat() / SIZE) },
            maskAreaNorm = mask.area().toFloat() / (SIZE * SIZE))
        private val identity = RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        private val context = TactileProjectionContext(token.frameId, "ground-route",
            ActiveRouteProjection("ground-route", 0, RoutePoint(36.9999, 127.0), RoutePoint(37.001, 127.0), 0.0, 0f, 1),
            TrustedLocation(37.0, 127.0, .5f, 100_000L),
            DeviceEarthOrientation(identity, 100_000L, 1f, EarthOrientationAccuracy.HIGH), 0f,
            TactileCameraFrame(token.frameId, RotationMatrix3(1f, 0f, 0f, 0f, s, -s, 0f, s, s),
                TactileCameraIntrinsics(SIZE, SIZE, 288f, 288f, 96f, 96f), 100_000L, true),
            50, 100_000L, decisionNs / 1_000_000L)

        fun produceOutput(): TrackedObjectDepth {
            val pipeline = ObjectDepthRuntimePipeline()
            val mapper = LetterboxCoordinateMapper(ModelInputTransform(SIZE, SIZE, SIZE, SIZE, 1f, 0f, 0f), ImageSize(SIZE, SIZE))
            var final: TrackedObjectDepth? = null
            for (frame in 4L..7L) {
                val offsetMs = (7 - frame) * 300L
                val snapshot = snapshot(token.cameraTimestampNs - offsetMs * 1_000_000L,
                    token.cpuImageTimestampNs - offsetMs * 1_000_000L, listOf(ground.copy(captureTimestampMs = 9000L - offsetMs)))
                final = pipeline.process(snapshot, frame, snapshot.frameTimestampNs / 1_000_000L,
                    listOf(detection), detectionSequenceId = frame, mapper = mapper, mapperFrameId = frame,
                    imageQuarterTurns = 0, capturedAtElapsedRealtimeMs = 100_000L - offsetMs).single()
            }
            return requireNotNull(final).also {
                assertEquals(token.frameId, it.frameId)
                assertEquals(0, it.sourceDetectionIndex)
                assertEquals(DepthSource.ARCORE_RAW_DEPTH, it.source)
                assertEquals(4, it.trackAgeFrames)
                assertEquals(900L, it.trackStableMs)
                assertTrue(TactileObservationEvidence.hasTrustedMetricDepth(it))
                assertEquals(9000L, requireNotNull(it.depthObservedAtMs))
                assertEquals(100_000L, requireNotNull(it.sourceObservedAtElapsedRealtimeMs))
                assertTrue(requireNotNull(it.sourceValidUntilElapsedRealtimeMs) >= decisionNs / 1_000_000L)
            }
        }

        fun guide(output: TrackedObjectDepth, planes: List<CapturedHorizontalPlane>,
                  supplier: TactileRouteObservationSupplier = AndroidTactileRouteObservationSupplier()): TactileRouteGuidanceResult {
            val classes = TactileSemanticKind.values().map { it.className }
            val model = ModelRuntimeConfig(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, "model/existing.tflite", 768,
                classes, classes.toSet(), classes.associateWith { .25f }, artifactSha256 = "d".repeat(64))
            val config = TwoModelRuntimeConfig("ground-fixture", null, model.key, null, model, null, null)
            val named = TactileNamedSemanticEvidence.fromResult(capture,
                AndroidDetectionResult(listOf(detection), timing = AndroidDetectorTiming(modelKey = model.key)),
                TactileSemanticCoordinates.SENSOR_ORIGINAL)
            val selected = TactileSemanticMaskSelector(config).select(named,
                TactileInstanceMaskEvidence(capture, token, listOf(mask), TactileSemanticCoordinates.SENSOR_ORIGINAL), decisionNs)
            val candidate = selected.candidates.single()
            assertEquals(mask.area(), candidate.sensorMask.area)
            // The complete plane bundle belongs to the pose before freezing; no later retagging.
            val frozen = requireNotNull(FrozenUnknownDepthCapture.freeze(token,
                snapshot(token.cameraTimestampNs, token.cpuImageTimestampNs, planes),
                doubleArrayOf(1.0 / SIZE, 0.0, 0.0, 0.0, 1.0 / SIZE, 0.0, 0.0, 0.0, 1.0), token.frameId))
            val measured = TactileMaskSurfaceProjector().project(candidate, frozen, decisionNs, context)
            assertNull(measured.rejection)
            val surface = requireNotNull(measured.surface)
            assertEquals(planes, surface.pose.horizontalPlaneCandidates)
            assertEquals(MaskDepthEstimator.Status.KNOWN, surface.samplingStatus)
            assertNotNull(surface.measuredContact)
            assertTrue(surface.depthMatchesCameraImage)
            assertEquals(token.cpuImageTimestampNs, surface.depthTimestampNs)
            surface.samples.forEach { sample ->
                assertEquals(depths[sample.depthCellIndex] / 1000f, sample.axialDepthM, 0f)
                assertTrue(mask.contains(sample.imageX.toInt(), sample.imageY.toInt()))
            }
            return AndroidTactileRouteGuidance(observationSupplier = supplier).apply(listOf(output), context, true, true, "ground-route",
                TactileActiveRouteProgress(1, 0), tactileSurfaces = listOf(measured), decisionElapsedRealtimeNs = decisionNs)
        }

        private fun snapshot(cameraNs: Long, cpuNs: Long, planes: List<CapturedHorizontalPlane>): DepthFrameSnapshot {
            val pose = CameraPoseEvidence(7, cameraNs / 1_000_000L, 0f, cameraHeightM, 0f, 0f, -s, -s,
                CameraImageProjection(SIZE, SIZE, 288f, 288f, 96f, 96f, 1f, 0f, 0f, 0f, s, -s), Vec3(0f, 1f, 0f), planes)
            return DepthFrameSnapshot(cameraNs, DepthImage16(SIZE, SIZE, depths),
                ConfidenceImage8(SIZE, SIZE, ByteArray(SIZE * SIZE) { 255.toByte() }), null,
                rawDepthTimestampNs = cpuNs, rawConfidenceTimestampNs = cpuNs,
                cameraImageTimestampNs = cpuNs, cameraPoseEvidence = pose)
        }
    }

    private companion object {
        const val SIZE = FastSamDecoder.PROTO_SIZE
        fun decodeMask(): InstanceMask {
            val detections = FloatArray(FastSamDecoder.CHANNELS * FastSamDecoder.ANCHORS)
            detections[0] = .5f
            detections[FastSamDecoder.ANCHORS] = .5f
            detections[2 * FastSamDecoder.ANCHORS] = 1f
            detections[3 * FastSamDecoder.ANCHORS] = 1f
            detections[4 * FastSamDecoder.ANCHORS] = .9f
            detections[5 * FastSamDecoder.ANCHORS] = 1f
            val prototypes = FloatArray(SIZE * SIZE * FastSamDecoder.MASK_CHANNELS)
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                prototypes[(y * SIZE + x) * FastSamDecoder.MASK_CHANNELS] = if (x in 78..113 && y in 72..143) 1f else -1f
            }
            return FastSamDecoder().decode(detections, prototypes, SIZE, SIZE).instances().single()
        }
    }
}
