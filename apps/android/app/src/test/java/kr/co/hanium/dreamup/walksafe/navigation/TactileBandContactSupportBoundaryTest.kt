package kr.co.hanium.dreamup.walksafe.navigation

import com.google.ar.core.Frame
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.depth.unknown.MaskDepthEstimator
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.FastSamDecoder
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.sqrt

class TactileBandContactSupportBoundaryTest {
    @Test fun splitBandMissingLocalCoverageAndClippingCannotBecomeMeasuredContact() {
        val split = Fixture(shape = Shape.SPLIT).surface()
        assertEquals(MaskDepthEstimator.Status.KNOWN, split.samplingStatus)
        assertNull(split.measuredContact)
        val sparse = Fixture(sparseBottom = true)
        val surface = sparse.surface()
        assertEquals(MaskDepthEstimator.Status.KNOWN, surface.samplingStatus)
        assertTrue(surface.samples.count { it.depthY == 140 } >= 5)
        // There are five valid samples, but all 25 original mask cells remain eligible.
        // Counting only the retained valid surface list would incorrectly promote ratio 5/5.
        assertEquals(25, (138..142).sumOf { y -> (93..97).count { x -> sparse.foreground(x, y) } })
        assertEquals(5, (138..142).sumOf { y -> (93..97).count { x -> sparse.depths[y * SIZE + x] > 0 } })
        assertNull(surface.measuredContact)
        val clipped = Fixture(shape = Shape.CLIPPED).surface()
        assertEquals(MaskDepthEstimator.Status.KNOWN, clipped.samplingStatus)
        assertNull(clipped.measuredContact)
    }

    @Test fun everyRotationUsesAnActualSensorCellAndOriginalCaptureSource() {
        for (q in 0..3) {
            val fixture = Fixture(q = q)
            val surface = fixture.surface()
            val contact = requireNotNull(surface.measuredContact)
            assertTrue(surface.samples.any { it === contact.sample })
            assertTrue(fixture.foreground(contact.sample.imageX.toInt(), contact.sample.imageY.toInt()))
            assertEquals(fixture.depths[contact.sample.depthCellIndex] / 1000f, contact.depth.distanceM, 0f)
            assertEquals(contact.sample.axialDepthM, contact.depth.distanceM, 0f)
            assertEquals((contact.sample.imageX / SIZE).toFloat(), contact.sensorPointNorm.x, 0f)
            assertEquals((contact.sample.imageY / SIZE).toFloat(), contact.sensorPointNorm.y, 0f)
            val upright = requireNotNull(sensorPointToUpright(contact.sensorPointNorm, q))
            assertTrue(upright.y >= .75f - (.75f - .375f) * .10f)
            val sensor = requireNotNull(uprightPointToSensor(upright, q))
            assertEquals(contact.sensorPointNorm.x, sensor.x, .000001f)
            assertEquals(contact.sensorPointNorm.y, sensor.y, .000001f)
            assertEquals(DepthSource.ARCORE_RAW_DEPTH, surface.source)
            assertEquals(fixture.token.cpuImageTimestampNs, surface.depthTimestampNs)
            assertTrue(surface.depthMatchesCameraImage)
            assertSame(fixture.context, surface.captureContext)
        }
        val full = Fixture(full = true)
        val fullSurface = full.surface()
        val contact = requireNotNull(fullSurface.measuredContact)
        assertEquals(DepthSource.ARCORE_FULL_DEPTH, fullSurface.source)
        assertEquals(full.token.cpuImageTimestampNs, fullSurface.depthTimestampNs)
        assertNull(contact.sample.pixelConfidence)
        assertEquals(full.depths[contact.sample.depthCellIndex] / 1000f, contact.depth.distanceM, 0f)
        // Keep the actual factory surface; the positive output comes from the public pipeline.
        val fullOutput = full.output()
        assertEquals(DepthSource.ARCORE_FULL_DEPTH, fullOutput.source)
        assertEquals(full.token.cameraTimestampNs / 1_000_000L, fullOutput.depthObservedAtMs)
        assertEquals(full.token.capturedElapsedNs / 1_000_000L, fullOutput.sourceObservedAtElapsedRealtimeMs)
        assertEquals(false, fullOutput.depthReprojected)
        assertTrue(requireNotNull(fullOutput.sourceValidUntilElapsedRealtimeMs) >= full.decisionNs / 1_000_000L)
        val fullGuide = full.guide(fullOutput, listOf(TactileMaskSurfaceResult(full.candidate, fullSurface, null)))
        val fullObservation = requireNotNull(fullGuide.observation) { "Full rejected: ${fullGuide.observationRejections}" }
        assertSame(fullSurface, requireNotNull(fullObservation.surfaceEvidence).surface)
        assertEquals(TactileObservedRouteSupport.AXIS_AND_MULTIPOINT_OVERLAP,
            requireNotNull(fullObservation.surfaceEvidence).observedRouteSupport)
        assertEquals(requireNotNull(sensorPointToUpright(contact.sensorPointNorm, full.q)).x,
            fullObservation.centerXNormalized, 0f)
        val fullPublished = fullGuide.outputs.single()
        assertEquals(fullOutput.copy(userFacing = fullPublished.userFacing), fullPublished)
        assertSame(fullOutput.contactDepth, fullPublished.contactDepth)
        assertEquals(LocalRouteMode.TMAP, fullGuide.decision.mode)
        assertNull(fullGuide.decision.steering)
        assertNull(fullGuide.decision.instruction)
        val original = Fixture()
        // Explicit foreign-epoch corruption, not a positive provenance seam.
        val foreign = FastSamFrameToken(original.token.source, 2, original.token.frameId,
            original.token.cameraTimestampNs, original.token.cpuImageTimestampNs, original.token.capturedElapsedNs,
            original.token.geometryId, SIZE, SIZE)
        assertEquals(TactileSurfaceRejection.FOREIGN_CAPTURE, TactileMaskSurfaceProjector().project(
            original.candidate, original.frozen(foreign), original.decisionNs, original.context).rejection)
        assertEquals(TactileSurfaceRejection.EXPIRED_CAPTURE, TactileMaskSurfaceProjector().project(
            original.candidate, original.frozen(), original.capture.originalDeadlineElapsedNs + 1, original.context).rejection)
    }

    @Test fun typedSourceClockAndOwnershipCannotFallBackToLegacyOrRetagOutputs() {
        val fixture = Fixture()
        val output = fixture.output()
        val surface = fixture.surfaceResult()
        val typed = fixture.guide(output, listOf(surface))
        assertNotNull(typed.observation)
        assertEquals(TactileObservedRouteSupport.AXIS_AND_MULTIPOINT_OVERLAP,
            requireNotNull(typed.observation).surfaceEvidence!!.observedRouteSupport)
        assertEquals(LocalRouteMode.TMAP, typed.decision.mode)
        assertNull(typed.decision.steering)
        assertNull(typed.decision.instruction)
        val published = typed.outputs.single()
        assertEquals(output.copy(userFacing = published.userFacing), published)
        assertSame(output.contactDepth, published.contactDepth)
        val legacy = fixture.guide(output)
        assertNotNull(legacy.observation)
        assertNull(requireNotNull(legacy.observation).surfaceEvidence)
        assertEquals(requireNotNull(output.uprightBottomContactNorm).x,
            requireNotNull(legacy.observation).centerXNormalized, 0f)
        // Negative corruption of actual public-pipeline output: no positive output is fabricated.
        val bad = listOf(output.copy(source = DepthSource.ARCORE_FULL_DEPTH),
            output.copy(depthObservedAtMs = output.depthObservedAtMs!! + 1),
            output.copy(sourceObservedAtElapsedRealtimeMs = output.sourceObservedAtElapsedRealtimeMs!! + 1),
            output.copy(sourceValidUntilElapsedRealtimeMs = fixture.decisionNs / 1_000_000L - 1),
            output.copy(depthObservedAtMs = null), output.copy(sourceObservedAtElapsedRealtimeMs = null),
            output.copy(depthReprojected = true),
            output.copy(sourceDetectionIndex = 1), output.copy(frameId = output.frameId + 1),
            output.copy(imageQuarterTurns = 1))
        bad.forEach {
            val rejected = fixture.guide(it, listOf(surface))
            assertNull(rejected.observation)
            assertEquals(LocalRouteMode.TMAP, rejected.decision.mode)
            assertNull(rejected.decision.steering)
            assertNull(rejected.decision.instruction)
        }
        val invalid = TactileMaskSurfaceProjector().project(fixture.candidate, fixture.frozen(),
            fixture.capture.originalDeadlineElapsedNs + 1, fixture.context)
        assertNull(fixture.guide(output, listOf(invalid)).observation)
    }

    private enum class Shape { BODY, SPLIT, CLIPPED }

    /** Actual decoder and depth factory, with controlled source pixels rather than reflection. */
    private class Fixture(val q: Int = 0, val shape: Shape = Shape.BODY,
                          val sparseBottom: Boolean = false, val full: Boolean = false) {
        private val s = 1f / sqrt(2f)
        val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, 1, 7,
            9_000_000_000L, 8_900_000_000L, 100_000_000_000L, "band-control-q$q", SIZE, SIZE)
        val decisionNs = token.capturedElapsedNs + 50_000_000L
        val capture = TactileSemanticCapture(CameraImageTimestampEvidence(Any(), 1, object : Frame() {},
            token.cameraTimestampNs, token.cpuImageTimestampNs), token, q, token.capturedElapsedNs + 800_000_000L)
        val depths = IntArray(SIZE * SIZE) { i ->
            if (sparseBottom && i / SIZE >= 135 && i / SIZE != 140) 0
            else (1.5f / (s + s * ((i / SIZE + .5 - 96) / 288).toFloat()) * 1000).roundToInt()
        }
        private val mask = decodeMask(::foreground)
        private val polygon = (when (shape) {
            Shape.BODY -> listOf(78 to 72, 114 to 72, 114 to 144, 78 to 144)
            Shape.SPLIT -> listOf(78 to 72, 114 to 72, 114 to 144, 104 to 144,
                104 to 137, 88 to 137, 88 to 144, 78 to 144)
            Shape.CLIPPED -> listOf(78 to 130, 114 to 130, 114 to 192, 78 to 192)
        }).map { requireNotNull(uprightPointToSensor(Point2(it.first.toFloat() / SIZE, it.second.toFloat() / SIZE), q)) }
        private val detection = DetectionCandidate(TactileSemanticKind.LINE.className, .9f,
            RectNorm(0f, 0f, 1f, 1f), polygon, mask.area().toFloat() / (SIZE * SIZE))
        private val identity = RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val context = TactileProjectionContext(token.frameId, "band-route",
            ActiveRouteProjection("band-route", 0, RoutePoint(36.9999, 127.0), RoutePoint(37.001, 127.0), 0.0, 0f, 1),
            TrustedLocation(37.0, 127.0, .5f, 100_000L),
            DeviceEarthOrientation(identity, 100_000L, 1f, EarthOrientationAccuracy.HIGH), 0f,
            TactileCameraFrame(token.frameId, RotationMatrix3(1f, 0f, 0f, 0f, s, -s, 0f, s, s),
                TactileCameraIntrinsics(SIZE, SIZE, 288f, 288f, 96f, 96f), 100_000L, true),
            50, 100_000L, decisionNs / 1_000_000L)
        val candidate: TactileSemanticMaskCandidate
        init {
            val classes = TactileSemanticKind.values().map { it.className }
            val model = ModelRuntimeConfig(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, "model/existing.tflite", 768,
                classes, classes.toSet(), classes.associateWith { .25f }, artifactSha256 = "d".repeat(64))
            val named = TactileNamedSemanticEvidence.fromResult(capture,
                AndroidDetectionResult(listOf(detection), timing = AndroidDetectorTiming(modelKey = model.key)),
                TactileSemanticCoordinates.SENSOR_ORIGINAL)
            candidate = TactileSemanticMaskSelector(TwoModelRuntimeConfig("band-control", null, model.key, null,
                model, null, null)).select(named, TactileInstanceMaskEvidence(capture, token, listOf(mask),
                TactileSemanticCoordinates.SENSOR_ORIGINAL), decisionNs).candidates.single()
        }

        fun foreground(x: Int, y: Int): Boolean {
            val p = requireNotNull(sensorPointToUpright(Point2((x + .5f) / SIZE, (y + .5f) / SIZE), q))
            val ux = (p.x * SIZE).toInt(); val uy = (p.y * SIZE).toInt()
            return when (shape) {
                Shape.BODY -> ux in 78..113 && uy in 72..143
                Shape.SPLIT -> ux in 78..113 && uy in 72..143 && (uy < 137 || ux in 78..87 || ux in 104..113)
                Shape.CLIPPED -> ux in 78..113 && uy in 130..191
            }
        }

        fun surfaceResult() = TactileMaskSurfaceProjector().project(candidate, frozen(), decisionNs, context)
        fun surface(): TactileMaskSurface = surfaceResult().let {
            assertNull(it.rejection); requireNotNull(it.surface)
        }
        fun frozen(t: FastSamFrameToken = token) = requireNotNull(FrozenUnknownDepthCapture.freeze(t,
            snapshot(t.cameraTimestampNs, t.cpuImageTimestampNs),
            doubleArrayOf(1.0 / SIZE, 0.0, 0.0, 0.0, 1.0 / SIZE, 0.0, 0.0, 0.0, 1.0), t.frameId, imageQuarterTurns = q))

        fun output(): TrackedObjectDepth {
            val pipeline = ObjectDepthRuntimePipeline()
            val mapper = LetterboxCoordinateMapper(ModelInputTransform(SIZE, SIZE, SIZE, SIZE, 1f, 0f, 0f), ImageSize(SIZE, SIZE))
            return (4L..7L).map { frame ->
                val offset = (7 - frame) * 300L
                val snapshot = snapshot(token.cameraTimestampNs - offset * 1_000_000L, token.cpuImageTimestampNs - offset * 1_000_000L)
                pipeline.process(snapshot, frame, snapshot.frameTimestampNs / 1_000_000L, listOf(detection),
                    frame, mapper = mapper, mapperFrameId = frame, imageQuarterTurns = q,
                    capturedAtElapsedRealtimeMs = 100_000L - offset).single()
            }.last()
        }

        fun guide(output: TrackedObjectDepth, surfaces: List<TactileMaskSurfaceResult> = emptyList()) =
            AndroidTactileRouteGuidance().apply(listOf(output), context, true, true, "band-route",
                TactileActiveRouteProgress(1, 0), tactileSurfaces = surfaces, decisionElapsedRealtimeNs = decisionNs)

        private fun snapshot(cameraNs: Long, cpuNs: Long): DepthFrameSnapshot {
            val cameraMs = cameraNs / 1_000_000L
            val pose = CameraPoseEvidence(7, cameraMs, 0f, 1.5f, 0f, 0f, -s, -s,
                CameraImageProjection(SIZE, SIZE, 288f, 288f, 96f, 96f, 1f, 0f, 0f, 0f, s, -s), Vec3(0f, 1f, 0f),
                listOf(CapturedHorizontalPlane(7, cameraMs, listOf(Vec3(-5f, 0f, -5f), Vec3(5f, 0f, -5f),
                    Vec3(5f, 0f, 5f), Vec3(-5f, 0f, 5f)))))
            return DepthFrameSnapshot(cameraNs, DepthImage16(SIZE, SIZE, if (full) IntArray(depths.size) else depths),
                ConfidenceImage8(SIZE, SIZE, ByteArray(depths.size) { if (full) 0 else 255.toByte() }),
                if (full) DepthImage16(SIZE, SIZE, depths) else null, rawDepthTimestampNs = cpuNs,
                rawConfidenceTimestampNs = cpuNs, cameraImageTimestampNs = cpuNs,
                fullDepthTimestampNs = if (full) cpuNs else null, cameraPoseEvidence = pose)
        }
    }

    private companion object {
        const val SIZE = FastSamDecoder.PROTO_SIZE
        fun decodeMask(foreground: (Int, Int) -> Boolean) = FastSamDecoder().decode(
            FloatArray(FastSamDecoder.CHANNELS * FastSamDecoder.ANCHORS).also {
                it[0] = .5f; it[FastSamDecoder.ANCHORS] = .5f
                it[2 * FastSamDecoder.ANCHORS] = 1f; it[3 * FastSamDecoder.ANCHORS] = 1f
                it[4 * FastSamDecoder.ANCHORS] = .9f; it[5 * FastSamDecoder.ANCHORS] = 1f
            }, FloatArray(SIZE * SIZE * FastSamDecoder.MASK_CHANNELS).also {
                for (y in 0 until SIZE) for (x in 0 until SIZE)
                    it[(y * SIZE + x) * FastSamDecoder.MASK_CHANNELS] = if (foreground(x, y)) 1f else -1f
            }, SIZE, SIZE).instances().single()
    }
}
