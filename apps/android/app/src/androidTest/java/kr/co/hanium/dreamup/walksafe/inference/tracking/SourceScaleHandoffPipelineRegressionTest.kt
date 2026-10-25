package kr.co.hanium.dreamup.walksafe.inference.tracking

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Random
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kr.co.hanium.dreamup.walksafe.depth.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual native LK -> public observation factory -> depth pipeline, over a controlled gray zoom.
 * The two synthetic person ROIs are not real-video semantic ground truth. No transform, point ID,
 * current geometry or assigned object ID is injected into the tracking result.
 */
@RunWith(AndroidJUnit4::class)
class SourceScaleHandoffPipelineRegressionTest {
    private lateinit var diagnostics: NativeDiagnosticEstimator

    @Test fun nativeMeasuredScaleAcrossSourceHandoffKeepsStableDepthTrackIds() {
        // Named Main uses native LK / 40 features / similarity=true. The existing native fixture's
        // 100 ms allowance isolates functionality; this test does not establish the product's 5 ms.
        val config = VisualTrackingConfig(
            backend = VisualTrackingBackend.OPENCV_PYRAMIDAL_LK,
            maxFeaturesPerObject = 40,
            allowSimilarityTransform = true,
            maxExecutionNs = FUNCTIONAL_BUDGET_NS,
        )
        diagnostics = NativeDiagnosticEstimator(OpenCvPyramidalMotionEstimator(
            config.maxFeaturesPerObject, config.allowSimilarityTransform,
        ))
        val visual = InterFrameDetectionTracker(config, System::nanoTime, diagnostics)
        assertTrue("NATIVE_INIT: actual OpenCV must initialize", visual.initialize())
        val identities = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = identities, rawDepthMotionOnly = true)
        val texture = texture()
        val original = listOf(.37f, .54f).map { left ->
            DetectionCandidate("person", .95f, RectNorm(left, .38f, .09f, .24f))
        }
        var sourceAt = 900L
        var detections = original
        try {
            offer(visual, texture, sourceAt)
            var firstPointIds: Set<VisualMaterialPointId>? = null
            var beforeResult: VisualTrackingResult? = null
            var beforeOutputs: List<TrackedObjectDepth>? = null
            var previousProjection: List<DetectionCandidate>? = null
            // Three detector captures, each measured at its next actual CPU frame, confirm the two
            // stable tracks. Before changing source, complete the old flow at that common frame.
            for (targetAt in listOf(950L, 1_000L, 1_050L)) {
                offer(visual, texture, targetAt)
                val old = tracked(visual, sourceAt, detections, targetAt, "WARM_OLD")
                firstPointIds?.let { assertEquals("WARM: original native point IDs", it, pointIds(old)) }
                    ?: run { firstPointIds = pointIds(old) }
                val currentDetections = if (targetAt == 950L) original else requireNotNull(previousProjection)
                val current = if (targetAt == 950L) old else {
                    // old source's previous target is the new detector capture. Its measured box is
                    // already known there; do not construct a current box from expected motion.
                    tracked(visual, sourceAt + 50L, currentDetections, targetAt, "WARM_HANDOFF")
                }
                assertEquals("WARM: source handoff preserves both native points", firstPointIds, pointIds(current))
                beforeOutputs = consume(pipeline, current, currentDetections)
                beforeResult = current
                sourceAt = current.detectorSourceKey.frameId / 1_000_000L
                detections = currentDetections
                previousProjection = current.observations.map { requireNotNull(it.geometry) }
            }
            assertEquals(1_000L, sourceAt)
            val before = requireNotNull(beforeResult)
            val previousOutputs = requireNotNull(beforeOutputs)
            val previousIds = assignments(before, previousOutputs)
            assertEquals("WARM: two distinct assigned objects", 2, previousIds.values.toSet().size)
            assertEquals("WARM: detector confirmation stays tied to three captures", setOf(3),
                previousOutputs.map { it.trackAgeFrames }.toSet())
            assertEquals("WARM: two stable actual tracks", 2, identities.activeTracks().count { it.stable })
            val extractor = MaskPolygonExtractor()
            for (row in before.observations) {
                val track = identities.activeTracks().single { it.trackId == previousIds.getValue(row.materialPoint!!.id) }
                assertEquals("WARM: assigned current geometry", extractor.extract(requireNotNull(row.geometry)), track.latestGeometry)
                assertEquals("WARM: assigned current clock", 1_050L, track.lastSeenAtMs)
                assertEquals("WARM: original detector clock", 1_000L, track.lastDetectionAtMs)
                assertFalse("WARM: no suspected identity switch", track.idSwitchSuspected)
            }

            var newDetections: List<DetectionCandidate>? = null
            var commonOld: VisualTrackingResult? = null
            // Native receives every measured 50 ms edge, while depth retains its actual 1050 state.
            // The old source's cumulative scale remains below 2 through the common frame 1550.
            for (at in 1_100L..1_550L step 50L) {
                offer(visual, texture, at)
                val measured = tracked(visual, 1_000L, detections, at, "OLD_ZOOM")
                assertEquals("OLD_ZOOM: original native points at $at", previousIds.keys, pointIds(measured))
                for (row in measured.observations) {
                    val affine = requireNotNull(row.transformNorm)
                    assertTrue("OLD_ZOOM: cumulative native source scale cap at $at", affine.areaScale <= 4f)
                }
                if (at == 1_500L) newDetections = measured.observations.map { requireNotNull(it.geometry) }
                if (at == 1_550L) commonOld = measured
            }
            assertTrue("DEPTH: no intermediate visual frame was consumed",
                identities.activeTracks().all { it.lastSeenAtMs == 1_050L && it.lastDetectionAtMs == 1_000L && it.ageFrames == 3 })
            val nextSource = requireNotNull(newDetections)
            val oldAtCommon = requireNotNull(commonOld)
            // New source 1500 uses the exact original producer projection at 1500. At 1550 all new
            // rows complete the same native edge, so the accepted producer can copy the old points.
            val handoff = tracked(visual, 1_500L, nextSource, 1_550L, "COMMON_HANDOFF")
            assertEquals("COMMON_HANDOFF: the same physical point IDs", previousIds.keys, pointIds(handoff))
            for (row in handoff.observations) {
                val oldPoint = oldAtCommon.observations.single { it.materialPoint!!.id === row.materialPoint!!.id }.materialPoint!!
                assertEquals("COMMON_HANDOFF: actual old point's exact frame", oldPoint.targetKey, row.materialPoint!!.targetKey)
                assertEquals("COMMON_HANDOFF: measured point copied at the common frame", oldPoint.imagePointNorm,
                    row.materialPoint!!.imagePointNorm)
            }
            offer(visual, texture, 1_600L)
            val after = tracked(visual, 1_500L, nextSource, 1_600L, "FINAL_NATIVE")
            assertEquals("FINAL_NATIVE: exclusive native points survived the source", previousIds.keys, pointIds(after))
            // Verify the actual full 2x2 source graph before the final association. All four weak
            // candidates survive the existing center/area gates, while no .20 anchor survives.
            for (old in before.observations) for ((sourceIndex, detection) in nextSource.withIndex()) {
                val previousGeometry = extractor.extract(requireNotNull(old.geometry))
                val sourceGeometry = extractor.extract(detection)
                val iou = bboxIoU(previousGeometry.bboxNorm, sourceGeometry.bboxNorm)
                val centerMove = distance(previousGeometry.centerNorm, sourceGeometry.centerNorm)
                val areaRatio = sourceGeometry.maskAreaNorm / previousGeometry.maskAreaNorm
                Log.i(TAG, "SOURCE_GRAPH oldRow=${old.sourceIndex} newRow=$sourceIndex " +
                    "iou=$iou centerMove=$centerMove areaRatio=$areaRatio")
                assertTrue("SOURCE_GRAPH: no existing .20 strong anchor", iou < .20f)
                assertTrue("SOURCE_GRAPH: every weak center candidate remains", centerMove < .25f)
                assertTrue("SOURCE_GRAPH: source itself does not cross the area veto", areaRatio in 1f..4f)
            }
            for (row in after.observations) {
                val old = before.observations.single { it.materialPoint!!.id === row.materialPoint!!.id }
                val previousGeometry = extractor.extract(requireNotNull(old.geometry))
                val currentGeometry = extractor.extract(requireNotNull(row.geometry))
                val sourceGeometry = extractor.extract(nextSource[row.sourceIndex])
                val areaRatio = currentGeometry.maskAreaNorm / previousGeometry.maskAreaNorm
                val centerMove = distance(previousGeometry.centerNorm, currentGeometry.centerNorm)
                val sourceIoU = bboxIoU(previousGeometry.bboxNorm, sourceGeometry.bboxNorm)
                Log.i(TAG, "FINAL_PRECONDITION row=${row.sourceIndex} source=${nextSource[row.sourceIndex].bboxNorm} " +
                    "previous=${old.geometry!!.bboxNorm} current=${row.geometry!!.bboxNorm} " +
                    "areaRatio=$areaRatio centerMove=$centerMove sourceIoU=$sourceIoU " +
                    "oldTrack=${previousIds.getValue(row.materialPoint!!.id)}")
                assertTrue("FINAL_PRECONDITION: actual measured area crosses the existing fourfold veto", areaRatio > 4f)
                assertTrue("FINAL_PRECONDITION: center veto is not the cause", centerMove < .35f)
                assertTrue("FINAL_PRECONDITION: new source native scale remains supported",
                    requireNotNull(row.transformNorm).areaScale <= 4f)
            }
            val finalOutputs = consume(pipeline, after, nextSource)
            val finalIds = assignments(after, finalOutputs)
            Log.i(TAG, "FINAL_ID_ASSERT old=${previousIds.values} actual=${finalIds.values}")
            // Intended regression boundary. Every native/ownership/key/depth prerequisite above
            // must pass before a failure here can be classified as the association counter.
            assertEquals("Measured native points must keep their stable depth track IDs across source scale", previousIds, finalIds)
        } finally {
            visual.clear()
        }
    }

    private fun logNativeDiagnostics(result: VisualTrackingResult,
        detections: List<DetectionCandidate>, phase: String) {
        // Formatting happens after trackFrom. A seed has no edge target, so its index is unknown.
        for (record in diagnostics.records) {
            val index = if (record.sourceKey == result.detectorSourceKey.toString() &&
                record.targetKey == result.trackedTargetKey.toString()) {
                detections.indices.singleOrNull { detections[it].toString() == record.detectionValue } ?: -1
            } else -1
            Log.i(TAG, "$phase NATIVE_DIAGNOSTIC index=$index kind=${record.kind} " +
                "source=${record.sourceKey} previous=${record.previousKey} target=${record.targetKey} " +
                "input=${record.detectionValue} originalGeometryCount=${record.originalCount} " +
                "inputCount=${record.inputCoordinates.size / 4} returnedCount=${record.outputCoordinates.size / 4} " +
                "edgeQuality=${record.quality} edgeResidualPx=${record.residual} fit=${record.fit?.contentToString()} " +
                "auxBefore=${record.auxBefore?.contentToString()} auxAfter=${record.auxAfter?.contentToString()} " +
                "auxSameId=${record.auxSameId} failure=${record.failure} " +
                "fbRaw=${record.medianForwardBackwardErrorPx ?: "not_computed"} " +
                "lkL1Raw=${record.medianLkError ?: "not_computed"} supportRaw=not_exposed")
            fun logCoordinates(label: String, values: FloatArray) {
                for (offset in values.indices step 4) {
                    Log.i(TAG, "$phase NATIVE_DIAGNOSTIC index=$index kind=${record.kind} $label " +
                        "slot=${offset / 4} sourceX=${values[offset]} sourceY=${values[offset + 1]} " +
                        "currentX=${values[offset + 2]} currentY=${values[offset + 3]}")
                }
            }
            logCoordinates("inputFeature", record.inputCoordinates)
            logCoordinates("returnedFeature", record.outputCoordinates)
        }
        diagnostics.records.clear()
    }

    // Only scalar snapshots are buffered; no gray frame, Image, native Mat, point ID or result is retained.
    private data class NativeDiagnosticRecord(
        val kind: String,
        val sourceKey: String,
        val previousKey: String?,
        val targetKey: String?,
        val detectionValue: String,
        val originalCount: Int?,
        val inputCoordinates: FloatArray,
        val outputCoordinates: FloatArray,
        val auxBefore: FloatArray?,
        val auxAfter: FloatArray?,
        val auxSameId: Boolean?,
        val quality: Float?,
        val residual: Float?,
        val fit: FloatArray?,
        val failure: VisualTrackingFailure?,
        val medianForwardBackwardErrorPx: Float? = null,
        val medianLkError: Float? = null,
    )

    private class NativeDiagnosticEstimator(
        private val delegate: OpenCvPyramidalMotionEstimator,
    ) : VisualMotionEstimator {
        val records = mutableListOf<NativeDiagnosticRecord>()
        override val supportsMaterialPointTracking: Boolean get() = delegate.supportsMaterialPointTracking
        override fun initialize(): Boolean = delegate.initialize()

        override fun seed(frame: GrayTrackingFrame, detection: DetectionCandidate,
            budget: TrackingWorkBudget): List<VisualMotionFeature> {
            val returned = delegate.seed(frame, detection, budget)
            records += NativeDiagnosticRecord("SEED", frame.key.toString(), null, null,
                detection.toString(), null, floatArrayOf(), coordinates(returned),
                null, null, null, null, null, null, null)
            return returned
        }

        override fun advance(source: GrayTrackingFrame, previous: GrayTrackingFrame,
            target: GrayTrackingFrame, detection: DetectionCandidate, features: List<VisualMotionFeature>,
            originalFeatureCount: Int, budget: TrackingWorkBudget,
            materialPoint: VisualMotionPoint?): VisualMotionEstimate {
            val returned = delegate.advance(source, previous, target, detection, features,
                originalFeatureCount, budget, materialPoint)
            val affine = returned.transformNorm
            val afterPoint = returned.materialPoint
            records += NativeDiagnosticRecord("ADVANCE", source.key.toString(), previous.key.toString(),
                target.key.toString(), detection.toString(), originalFeatureCount,
                coordinates(features), coordinates(returned.features), pointValues(materialPoint),
                pointValues(afterPoint), if (materialPoint != null && afterPoint != null)
                    materialPoint.id === afterPoint.id else null,
                returned.quality, returned.residual,
                floatArrayOf(affine.a, affine.b, affine.c, affine.d, affine.tx, affine.ty), returned.failure,
                medianForwardBackwardErrorPx = returned.diagnosticMedianForwardBackwardErrorPx,
                medianLkError = returned.diagnosticMedianLkError)
            return returned
        }

        private fun coordinates(features: List<VisualMotionFeature>) = FloatArray(features.size * 4) { index ->
            val feature = features[index / 4]
            when (index % 4) {
                0 -> feature.sourceX.toFloat()
                1 -> feature.sourceY.toFloat()
                2 -> feature.currentX
                else -> feature.currentY
            }
        }

        private fun pointValues(point: VisualMotionPoint?): FloatArray? =
            point?.let { floatArrayOf(it.x, it.y) }
    }

    private fun tracked(visual: InterFrameDetectionTracker, sourceAt: Long,
        detections: List<DetectionCandidate>, targetAt: Long, phase: String): VisualTrackingResult {
        val result = visual.trackFrom(key(sourceAt), detections, key(targetAt))
        Log.i(TAG, "$phase source=$sourceAt target=$targetAt edges=${result.metrics.processedEdges} " +
            "ns=${result.metrics.durationNs} overrun=${result.metrics.executionBudgetOverrun} " +
            result.observations.joinToString { "row=${it.sourceIndex}/${it.status}/${it.failure}/quality=${it.trackingQuality}/features=${it.survivingFeatureCount}/${it.originalFeatureCount}/affine=${it.transformNorm}/residualPx=${it.residualPx}" })
        logNativeDiagnostics(result, detections, phase)
        assertNull("$phase: native batch", result.batchFailure)
        assertEquals("$phase: original full source key", key(sourceAt), result.detectorSourceKey)
        assertEquals("$phase: actual full target key", key(targetAt), result.trackedTargetKey)
        assertEquals("$phase: two actual native image edges", 2, result.metrics.processedEdges)
        assertEquals("$phase: authoritative detector indices", setOf(0, 1), result.observations.map { it.sourceIndex }.toSet())
        for (row in result.observations) {
            assertEquals("$phase: actual native TRACKED", VisualTrackingStatus.TRACKED, row.status)
            assertNull("$phase: native tracking failure", row.failure)
            assertTrue("$phase: existing measured-quality floor", row.trackingQuality >= .65f)
            assertTrue("$phase: native geometry features", row.originalFeatureCount >= 6 && row.survivingFeatureCount >= 6)
            assertEquals("$phase: row source key", key(sourceAt), row.detectorSourceKey)
            assertEquals("$phase: row target key", key(targetAt), row.trackedTargetKey)
            val geometry = requireNotNull(row.geometry)
            assertEquals(detections[row.sourceIndex].className, geometry.className)
            assertEquals(detections[row.sourceIndex].detectionConfidence, geometry.detectionConfidence, 0f)
            val affine = requireNotNull(row.transformNorm)
            val source = detections[row.sourceIndex].bboxNorm
            val corners = listOf(Point2(source.x, source.y), Point2(source.x + source.width, source.y),
                Point2(source.x + source.width, source.y + source.height), Point2(source.x, source.y + source.height)).map(affine::map)
            val left = corners.minOf { it.x }
            val top = corners.minOf { it.y }
            val expectedBox = if (affine.translationOnly) source.copy(x = source.x + affine.tx, y = source.y + affine.ty)
                else RectNorm(left, top, corners.maxOf { it.x } - left, corners.maxOf { it.y } - top)
            assertEquals("$phase: geometry is bound to the measured source affine", expectedBox, geometry.bboxNorm)
            val point = requireNotNull(row.materialPoint)
            assertEquals("$phase: measured point full target key", key(targetAt), point.targetKey)
            assertTrue("$phase: own actual current ROI", contains(geometry.bboxNorm, point.imagePointNorm))
            assertTrue("$phase: no peer owns this point", result.observations.none { peer ->
                peer.sourceIndex != row.sourceIndex && contains(requireNotNull(peer.geometry).bboxNorm, point.imagePointNorm)
            })
        }
        assertEquals("$phase: distinct measured point identities", 2, pointIds(result).size)
        return result
    }

    private fun consume(pipeline: ObjectDepthRuntimePipeline, result: VisualTrackingResult,
        detections: List<DetectionCandidate>): List<TrackedObjectDepth> {
        val target = result.trackedTargetKey
        val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(result, detections,
            true, target.capturedAtElapsedRealtimeMs)) { "FACTORY: public source/target/point provenance" }
        val depth = snapshot(target)
        assertTrue("DEPTH: original source observation is fresh", observation.isFreshAt(target.capturedAtElapsedRealtimeMs))
        assertTrue("DEPTH: original CPU/AR clock joins the target", observation.matchesDepthSnapshot(depth))
        val outputs = pipeline.processTrackedObservation(depth, observation, MAPPER, target.frameId,
            target.capturedAtElapsedRealtimeMs)
        assertEquals("DEPTH: both current measurements exist", 2, outputs.size)
        for (row in result.observations) {
            val output = outputs.single { it.sourceDetectionIndex == row.sourceIndex }
            assertEquals(requireNotNull(row.geometry).bboxNorm, output.bboxNorm)
            assertEquals("person", output.className)
            assertEquals(.95f, output.detectionConfidence, 0f)
            assertEquals(target.frameId, output.frameId)
            assertEquals(target.frameId / 1_000_000L, output.timestampMs)
            assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
            assertEquals(DepthAvailability.MEASURED, output.depthAvailability)
            assertEquals(3f, requireNotNull(output.zDistanceM), .001f)
            assertEquals(target.frameId / 1_000_000L, requireNotNull(output.depthObservedAtMs))
            assertEquals(result.detectorSourceKey.capturedAtElapsedRealtimeMs, requireNotNull(output.sourceObservedAtElapsedRealtimeMs))
            assertTrue("DEPTH: valid actual Raw support", output.validSampleCount >= 5 && output.validSampleRatio >= .6f)
            assertTrue("DEPTH: original Raw confidence is positive", output.confidence.sourceQuality > 0f && output.confidence.depthQuality > 0f)
        }
        return outputs
    }

    private fun assignments(result: VisualTrackingResult, outputs: List<TrackedObjectDepth>) =
        result.observations.associate { row ->
            requireNotNull(row.materialPoint).id to outputs.single { it.sourceDetectionIndex == row.sourceIndex }.trackId
        }

    private fun pointIds(result: VisualTrackingResult) = result.observations.map { requireNotNull(it.materialPoint).id }.toSet()
    private fun contains(box: RectNorm, point: Point2) = point.x in box.x..(box.x + box.width) && point.y in box.y..(box.y + box.height)
    private fun distance(a: Point2, b: Point2) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    private fun offer(visual: InterFrameDetectionTracker, original: ByteArray, at: Long) {
        val scale = if (at <= 1_050L) 1.0 else 1.07.pow((at - 1_050L) / 50.0)
        val center = SIDE / 2.0
        val pixels = ByteArray(original.size)
        for (y in 0 until SIDE) for (x in 0 until SIDE) {
            val sx = (center + (x - center) / scale).coerceIn(0.0, SIDE - 1.0)
            val sy = (center + (y - center) / scale).coerceIn(0.0, SIDE - 1.0)
            val x0 = floor(sx).toInt()
            val y0 = floor(sy).toInt()
            val x1 = (x0 + 1).coerceAtMost(SIDE - 1)
            val y1 = (y0 + 1).coerceAtMost(SIDE - 1)
            fun value(px: Int, py: Int) = original[py * SIDE + px].toInt() and 0xff
            val wx = sx - x0
            val wy = sy - y0
            pixels[y * SIDE + x] = ((value(x0, y0) * (1.0 - wx) + value(x1, y0) * wx) * (1.0 - wy) +
                (value(x0, y1) * (1.0 - wx) + value(x1, y1) * wx) * wy).roundToInt().toByte()
        }
        assertTrue("GRAY: owned CPU frame admission at $at", visual.offerFrame(GrayTrackingFrame.copyOf(key(at), SIDE, SIDE, pixels)).accepted)
    }

    private fun texture(): ByteArray {
        val random = Random(417L)
        val pixels = DoubleArray(SIDE * SIDE) { 112.0 }
        val columns = arrayOf(intArrayOf(73, 80, 86), intArrayOf(105, 112, 118))
        val rows = intArrayOf(76, 85, 93, 103, 112)
        // Bounded L corners are gray input stimuli, not prescribed native features.
        // Unequal spacing and seeded contrast distinguish neighbors under the unchanged zoom.
        for (group in columns) for (y in rows) for (x in group) {
            val contrast = 48 + random.nextInt(17)
            for (dy in -2..2) for (dx in -2..2) {
                if (dx <= 0 || dy <= 0) {
                    pixels[(y + dy) * SIDE + x + dx] += contrast
                }
            }
        }
        return ByteArray(pixels.size) { pixels[it].roundToInt().toByte() }
    }

    private fun snapshot(key: VisualFrameKey) = DepthFrameSnapshot(key.frameId,
        DepthImage16(DEPTH_SIDE, DEPTH_SIDE, IntArray(DEPTH_SIDE * DEPTH_SIDE) { 3_000 }),
        ConfidenceImage8(DEPTH_SIDE, DEPTH_SIDE, ByteArray(DEPTH_SIDE * DEPTH_SIDE) { 255.toByte() }), null,
        rawDepthTimestampNs = key.cameraTimestampNs, rawConfidenceTimestampNs = key.cameraTimestampNs,
        cameraImageTimestampNs = key.cameraTimestampNs,
        cameraPoseEvidence = CameraPoseEvidence(42L, key.frameId / 1_000_000L, 0f, 0f, 0f, 0f, 0f, -1f,
            imageProjection = CameraImageProjection(SIDE, SIDE, SIDE * .7f, SIDE * .7f, SIDE / 2f, SIDE / 2f,
                1f, 0f, 0f, 0f, 1f, 0f)))

    private fun key(at: Long) = VisualFrameKey(7L, at * 1_000_000L,
        at * 1_000_000L + 400_000L, at + 50_000L, 3L)

    private companion object {
        const val SIDE = 192
        const val DEPTH_SIDE = 100
        const val FUNCTIONAL_BUDGET_NS = 100_000_000L
        const val TAG = "SourceScaleHandoffTest"
        val MAPPER = LetterboxCoordinateMapper(ModelInputTransform(SIDE, SIDE, SIDE, SIDE, 1f, 0f, 0f),
            ImageSize(DEPTH_SIDE, DEPTH_SIDE))
    }
}
