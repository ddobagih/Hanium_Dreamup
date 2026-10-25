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

/** Actual decoder cells and public production path, without injected axes/tangents or route authority. */
class TactileCenterlineTangentConsumerRegressionTest {
    @Test fun routeOverlapConsumesMeasuredBendTangentInsteadOfWholeComponentPca() {
        val scene = Scene()
        val output = scene.produceOutput()
        val result = scene.guide(output, listOf(scene.ground))
        val evidence = requireNotNull(result.surfaceEvaluations[0]?.evidence) {
            "surface rejected: ${result.surfaceEvaluations}"
        }
        assertEquals(MaskDepthEstimator.Status.KNOWN, evidence.surface.samplingStatus)
        val contact = requireNotNull(evidence.selectedContact)
        assertTrue(contact.depth.validSampleCount >= 5)
        assertEquals(contact.sample.axialDepthM, contact.depth.distanceM, 0f)
        assertEquals(1, evidence.originalMaskComponentCount)
        assertEquals(0, evidence.originalMaskHoleCount)
        // Diagnostic only: original v3 mask/input/assertions are unchanged below.
        val graph = evidence.components.single()
        val graphNodes = graph.centerlineDepthCells.toSet()
        val graphAdjacency = graphNodes.associateWith { mutableSetOf<Int>() }
        graph.tangents.forEach { edge ->
            graphAdjacency.getValue(edge.fromDepthCell).add(edge.toDepthCell)
            graphAdjacency.getValue(edge.toDepthCell).add(edge.fromDepthCell)
        }
        fun coordinates(cell: Int) = "(${cell % evidence.surface.depthWidth},${cell / evidence.surface.depthWidth})"
        fun cardinal(a: Int, b: Int) = abs(a % evidence.surface.depthWidth - b % evidence.surface.depthWidth) +
            abs(a / evidence.surface.depthWidth - b / evidence.surface.depthWidth) == 1
        println("GRAPH originalBranch=${evidence.originalMaskBranching} measuredBranch=${graph.branching} " +
            "nodes=${graphNodes.size} edges=${graph.tangents.size} ends=${graphAdjacency.values.count { it.size == 1 }} " +
            "mode=${result.decision.mode} reason=${result.decision.reason} observation=${result.observation}")
        println("CENTERLINE " + graphNodes.sorted().joinToString { "$it:${coordinates(it)}" })
        println("TANGENTS " + graph.tangents.joinToString { "${coordinates(it.fromDepthCell)}->${coordinates(it.toDepthCell)}:${it.routeAngleDeg}" })
        graphAdjacency.filterValues { it.size > 2 }.toSortedMap().forEach { (cell, neighbors) ->
            println("BRANCH node=$cell:${coordinates(cell)} degree=${neighbors.size} neighbors=" +
                neighbors.sorted().joinToString { "$it:${coordinates(it)}" })
        }
        graph.tangents.filter { !cardinal(it.fromDepthCell, it.toDepthCell) }.forEach { edge ->
            val shared = graphAdjacency.getValue(edge.fromDepthCell).intersect(graphAdjacency.getValue(edge.toDepthCell))
                .filter { cardinal(it, edge.fromDepthCell) && cardinal(it, edge.toDepthCell) }
            if (shared.isNotEmpty()) println("CORNER_SHORTCUT ${coordinates(edge.fromDepthCell)}->${coordinates(edge.toDepthCell)} " +
                "via=" + shared.joinToString { coordinates(it) })
        }
        assertFalse("original branching", evidence.originalMaskBranching)
        val component = evidence.components.single()
        assertFalse("measured branching", component.branching)
        val axis = requireNotNull(component.axis)
        assertTrue("PCA must pass the existing 35-degree direction comparison: ${axis.routeAngleDeg}", axis.routeAngleDeg < 35)
        assertEquals(TactileObservedRouteSupport.AXIS_AND_MULTIPOINT_OVERLAP, evidence.observedRouteSupport)
        assertTrue(evidence.overlaps.size > 1)
        val nodes = component.centerlineDepthCells.toSet()
        val adjacency = nodes.associateWith { mutableSetOf<Int>() }
        component.tangents.forEach { edge ->
            assertTrue(edge.fromDepthCell in nodes && edge.toDepthCell in nodes)
            adjacency.getValue(edge.fromDepthCell).add(edge.toDepthCell)
            adjacency.getValue(edge.toDepthCell).add(edge.fromDepthCell)
        }
        assertEquals("the actual measured centerline must have two ends", 2, adjacency.values.count { it.size == 1 })
        assertTrue(adjacency.values.all { it.size in 1..2 })
        val reached = mutableSetOf<Int>(); val pending = ArrayDeque<Int>(); pending.add(nodes.first())
        while (pending.isNotEmpty()) {
            val cell = pending.removeFirst()
            if (reached.add(cell)) adjacency.getValue(cell).forEach(pending::add)
        }
        assertEquals(nodes, reached)
        assertEquals("all original 110 measured centerline cells must remain", 110, nodes.size)
        val overlapCells = evidence.overlaps.flatMap { it.depthCells }.toSet()
        val localTangents = component.tangents.filter {
            it.fromDepthCell in overlapCells && it.toDepthCell in overlapCells
        }
        assertTrue("route overlap must intersect actual measured centerline edges", localTangents.isNotEmpty())
        assertTrue("the overlapping arm must be perpendicular", localTangents.all { it.routeAngleDeg > 85 })
        val observation = requireNotNull(result.observation) { "observation rejected: ${result.observationRejections}" }
        assertSame(evidence, observation.surfaceEvidence)
        assertEquals(output.bottomContactNorm, result.outputs.single().bottomContactNorm)
        assertEquals(output.contactDepth, result.outputs.single().contactDepth)
        assertEquals(LocalRouteMode.TMAP, result.decision.mode)
        assertNull(result.decision.instruction)
        val diagnostic = "PCA=${axis.routeAngleDeg}, actualOverlapTangents=${localTangents.map { it.routeAngleDeg }}, " +
            "consumed=${observation.routeHeadingDeltaDeg}, decision=${result.decision.reason}"
        println(diagnostic)
        assertEquals("route-overlap tangent must determine eligibility: $diagnostic", "route_heading_mismatch", result.decision.reason)
        assertTrue(requireNotNull(observation.routeHeadingDeltaDeg) > 85f)
    }

    @Test fun distantNonoverlapBendKeepsTheActualParallelShortSupportEligible() {
        val scene = Scene(routeImageX = 95.5, routeNorthStartM = 1.4, routeNorthEndM = 2.6)
        val output = scene.produceOutput()
        val result = scene.guide(output, listOf(scene.ground))
        val evidence = requireNotNull(result.surfaceEvaluations[0]?.evidence)
        assertEquals(MaskDepthEstimator.Status.KNOWN, evidence.surface.samplingStatus)
        assertFalse(evidence.originalMaskBranching)
        val component = evidence.components.single()
        assertFalse(component.branching)
        assertEquals(110, component.centerlineDepthCells.size)
        assertTrue(component.tangents.any { it.routeAngleDeg > 85 })
        val cells = evidence.overlaps.flatMap { it.depthCells }.toSet()
        val local = component.tangents.filter { it.fromDepthCell in cells && it.toDepthCell in cells }
        assertTrue(local.isNotEmpty())
        assertTrue("only the short overlapping support is parallel", local.all { it.routeAngleDeg < 5 })
        val observation = requireNotNull(result.observation)
        assertSame(evidence, observation.surfaceEvidence)
        assertTrue(requireNotNull(observation.routeHeadingDeltaDeg) < 5f)
        assertEquals("tactile_motion_evidence_unavailable", result.decision.reason)
        assertEquals(LocalRouteMode.TMAP, result.decision.mode)
        assertNull(result.decision.instruction)
        assertEquals(output.bottomContactNorm, result.outputs.single().bottomContactNorm)
        assertEquals(output.contactDepth, result.outputs.single().contactDepth)
        println("REMOTE_CURVE allMax=${component.tangents.maxOf { it.routeAngleDeg }} localMax=${local.maxOf { it.routeAngleDeg }} consumed=${observation.routeHeadingDeltaDeg}")
    }

    @Test fun realThreeArmsHoleAndDisconnectedMasksCannotChooseOneDirection() {
        val shapes: List<Pair<String, (Int, Int) -> Boolean>> = listOf(
            "threeArms" to { x,y -> (x in 90..101 && y in 96..143) || (x in 60..131 && y in 92..103) },
            "hole" to { x,y -> x in 82..109 && y in 102..143 && !(x in 90..101 && y in 116..129) },
            "disconnected" to { x,y -> x in 90..101 && (y in 108..122 || y in 129..143) })
        shapes.forEach { (kind, foreground) ->
            val scene = Scene(routeImageX = 95.5, foreground = foreground)
            val result = scene.guide(scene.produceOutput(), listOf(scene.ground), requireContact = false)
            val evidence = requireNotNull(result.surfaceEvaluations[0]?.evidence)
            assertEquals(MaskDepthEstimator.Status.KNOWN, evidence.surface.samplingStatus)
            when (kind) {
                "threeArms" -> { assertTrue(evidence.originalMaskBranching); assertTrue(evidence.components.single().branching) }
                "hole" -> assertEquals(1, evidence.originalMaskHoleCount)
                "disconnected" -> { assertEquals(2, evidence.originalMaskComponentCount); assertEquals(2, evidence.components.size) }
            }
            assertNull("$kind cannot be promoted to a unique route direction", result.observation)
            assertEquals(LocalRouteMode.TMAP, result.decision.mode)
            assertNull(result.decision.instruction)
            assertEquals("tactile_not_visible", result.decision.reason)
            println("GUARD $kind originalBranch=${evidence.originalMaskBranching} components=${evidence.originalMaskComponentCount} holes=${evidence.originalMaskHoleCount} rejection=${result.observationRejections}")
        }
    }

    private class Scene(val routeImageX: Double = 60.5, routeNorthStartM: Double? = null,
                        routeNorthEndM: Double? = null, foreground: (Int, Int) -> Boolean = ::bentForeground) {
        val cameraHeightM = 1.5f
        val mask: InstanceMask = decodeMask(foreground)
        val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, 1, 7,
            9_000_000_000L, 8_900_000_000L, 100_000_000_000L, "ground-192-q0", SIZE, SIZE)
        val decisionNs = token.capturedElapsedNs + 50_000_000L
        val capture = TactileSemanticCapture(CameraImageTimestampEvidence(Any(), 1, object : Frame() {},
            token.cameraTimestampNs, token.cpuImageTimestampNs), token, 0, token.capturedElapsedNs + 800_000_000L)
        private val s = 1f / sqrt(2f)
        val depths = IntArray(SIZE * SIZE) { i ->
            (cameraHeightM / (s + s * ((i / SIZE + .5 - 96) / 288).toFloat()) * 1000).roundToInt()
        }
        val ground = CapturedHorizontalPlane(7, token.cameraTimestampNs / 1_000_000L,
            listOf(Vec3(-5f, 0f, -5f), Vec3(5f, 0f, -5f), Vec3(5f, 0f, 5f), Vec3(-5f, 0f, 5f)))
        private val detection = DetectionCandidate(TactileSemanticKind.LINE.className, .9f, RectNorm(0f, 0f, 1f, 1f),
            polygonNorm = listOf(42 to 72, 102 to 72, 102 to 144, 42 to 144)
                .map { Point2(it.first.toFloat() / SIZE, it.second.toFloat() / SIZE) },
            maskAreaNorm = mask.area().toFloat() / (SIZE * SIZE))
        private val identity = RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        private val routeEastM = ((routeImageX - 96) / 288 * (depths[140 * SIZE] / 1000f)).toDouble()
        private val routeLongitude = 127.0 + routeEastM /
            kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy.metersPerLongitudeDegree(37.0)
        private val local = kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy.Frame(37.0, 127.0)
        private val routeStart = routeNorthStartM?.let { local.toCoordinate(routeEastM, it) }
        private val routeEnd = routeNorthEndM?.let { local.toCoordinate(routeEastM, it) }
        private val context = TactileProjectionContext(token.frameId, "ground-route",
            ActiveRouteProjection("ground-route", 0, RoutePoint(routeStart?.latitude ?: 36.9999, routeLongitude), RoutePoint(routeEnd?.latitude ?: 37.001, routeLongitude), abs(routeEastM), 0f, 1),
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
                  supplier: TactileRouteObservationSupplier = AndroidTactileRouteObservationSupplier(),
                  requireContact: Boolean = true): TactileRouteGuidanceResult {
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
            if (requireContact) assertNotNull(surface.measuredContact)
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
        fun bentForeground(x: Int, y: Int) =
            ((x in 90..101 && y in 72..143) || (x in 42..101 && y in 138..143)) && x + y <= 235
        fun decodeMask(foreground: (Int, Int) -> Boolean): InstanceMask {
            val detections = FloatArray(FastSamDecoder.CHANNELS * FastSamDecoder.ANCHORS)
            detections[0] = .5f
            detections[FastSamDecoder.ANCHORS] = .5f
            detections[2 * FastSamDecoder.ANCHORS] = 1f
            detections[3 * FastSamDecoder.ANCHORS] = 1f
            detections[4 * FastSamDecoder.ANCHORS] = .9f
            detections[5 * FastSamDecoder.ANCHORS] = 1f
            val prototypes = FloatArray(SIZE * SIZE * FastSamDecoder.MASK_CHANNELS)
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                prototypes[(y * SIZE + x) * FastSamDecoder.MASK_CHANNELS] = if (foreground(x, y)) 1f else -1f
            }
            return FastSamDecoder().decode(detections, prototypes, SIZE, SIZE).instances().single()
        }
    }
}
