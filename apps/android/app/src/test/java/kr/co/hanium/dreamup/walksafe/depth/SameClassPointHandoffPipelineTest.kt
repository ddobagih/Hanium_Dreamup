package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Deterministic measured-point seam through the real tracker, observation and depth consumers.
 * The separated source boxes already have unique tracker costs. This does not resolve S11-20
 * ambiguous geometry or establish native LK / real-video physical identity accuracy.
 */
class SameClassPointHandoffPipelineTest {
    @Test
    fun uniqueSameClassPointsKeepSpatialHistoryAcrossReorderedDetectorSources() {
        val scene = Scene()
        val seen = scene.run()
        for (actor in 0..1) {
            val rows = seen.filter { it.actor == actor }
            val latest = rows.last().output
            assertEquals(ObjectMovementDirection.RIGHT, latest.motionEstimate.direction)
            assertEquals(3f / .7f * IMAGE_SPEED, latest.motionEstimate.objectSpeedMps!!, .001f)
            assertEquals(1, rows.mapNotNull { it.row.materialPoint?.id }.distinct().size)
            assertEquals(1, rows.map { it.output.trackId }.distinct().size)
            assertTrue(rows.map { it.row.detectorSourceKey }.distinct().size >= 7)
            assertTrue(rows.map { it.row.sourceIndex }.distinct().size == 2)
            assertMeasuredProximity(latest)
        }
        val last = seen.filter { it.at == 3_000L }
        assertNotSame(last[0].row.materialPoint!!.id, last[1].row.materialPoint!!.id)
        assertEquals(2, last.map { it.output.trackId }.distinct().size)
        assertTrue(scene.identities.activeTracks().all { it.stable && it.motionSamples().size >= 4 })
        assertTrue(scene.identities.activeTracks().all { it.distanceHistory.size == 12 })
        assertTrue(scene.flow.inputCounts.all { it <= 40 })
        assertTrue(scene.flow.inputCounts.any { it == 40 })
    }

    @Test
    fun lostPointsRequireNewSpatialWarmupEvenWhenBothObjectIdsAndRawDepthContinue() {
        val scene = Scene(controlledSources = true, pointLostAt = 2_150L)
        val seen = scene.run(endAt = 3_100L)
        for (actor in 0..1) {
            val rows = seen.filter { it.actor == actor }
            val before = rows.single { it.at == 2_100L }
            val lost = rows.single { it.at == 2_150L }
            val fresh = rows.single { it.at == 2_450L }
            assertEquals(ObjectMovementDirection.RIGHT, before.output.motionEstimate.direction)
            assertNull(lost.row.materialPoint)
            assertUnknown(lost.output)
            assertUnknown(rows.single { it.at == 2_200L }.output)
            assertNotSame(before.row.materialPoint!!.id, fresh.row.materialPoint!!.id)
            assertUnknown(fresh.output)
            assertUnknown(rows.single { it.at == 2_500L }.output)
            assertEquals(1, rows.map { it.output.trackId }.distinct().size)
            assertMeasuredProximity(lost.output)
            assertMeasuredProximity(fresh.output)
            assertEquals(ObjectMovementDirection.RIGHT, rows.last().output.motionEstimate.direction)
            assertTrue(rows.filter { it.at >= 2_150L }.none {
                it.row.materialPoint?.id === before.row.materialPoint!!.id
            })
        }
        assertTrue(scene.identities.activeTracks().all { it.distanceHistory.size == 12 })
    }

    @Test
    fun overlappingSourceCannotBorrowEitherPreviousPointOrItsMotionHistory() {
        val scene = Scene(controlledSources = true, overlapSourceAt = 2_100L)
        val seen = scene.run(endAt = 2_500L)
        val before = seen.filter { it.at == 2_100L }
        assertTrue(before.all { it.output.motionEstimate.direction == ObjectMovementDirection.RIGHT })
        val oldPointIds = before.map { it.row.materialPoint!!.id }
        val oldTrackIds = before.map { it.output.trackId }.toSet()
        val overlap = seen.filter { it.at == 2_200L }
        assertEquals(2, overlap.size)
        overlap.forEach {
            assertNull(it.row.materialPoint)
            assertUnknown(it.output)
            assertTrue(it.output.trackId !in oldTrackIds)
            assertEquals(1, it.output.trackAgeFrames)
            assertMeasuredProximity(it.output, expectedTrend = Trend.UNKNOWN)
        }
        val separatedAgain = seen.filter { it.at == 2_450L }
        assertEquals(2, separatedAgain.size)
        separatedAgain.forEach {
            assertNotNull(it.row.materialPoint)
            assertTrue(oldPointIds.none { old -> old === it.row.materialPoint!!.id })
            assertUnknown(it.output)
            assertTrue(it.output.trackId !in oldTrackIds)
        }
    }

    @Test
    fun foreignPointAndCameraCaptureAreRejectedWithoutMutatingTheAcceptedDepthLedger() {
        val scene = Scene(controlledSources = true)
        var checked = false
        val seen = scene.run(endAt = 2_100L) { input ->
            if (input.at == 2_000L) {
                val before = scene.identities.activeTracks().associate {
                    it.trackId to it.distanceHistory.toList()
                }
                val foreign = input.result.copy(observations = input.result.observations.map { row ->
                    row.copy(materialPoint = row.materialPoint!!.copy(
                        targetKey = row.trackedTargetKey.copy(epoch = row.trackedTargetKey.epoch + 1L),
                    ))
                })
                assertNull(CurrentTrackedFrameObservation.fromTrackingResult(
                    foreign, input.detections, true, input.target.capturedAtElapsedRealtimeMs,
                ))
                assertTrue(scene.pipeline.processTrackedObservation(
                    input.snapshot.copy(cameraImageTimestampNs = input.target.cameraTimestampNs - 1L),
                    input.observation, MAPPER, input.target.frameId,
                    input.target.capturedAtElapsedRealtimeMs,
                ).isEmpty())
                assertEquals(before, scene.identities.activeTracks().associate {
                    it.trackId to it.distanceHistory.toList()
                })
                checked = true
            }
        }
        assertTrue(checked)
        seen.filter { it.at == 2_100L }.forEach {
            assertEquals(ObjectMovementDirection.RIGHT, it.output.motionEstimate.direction)
            assertMeasuredProximity(it.output)
        }
    }

    private class Scene(
        private val controlledSources: Boolean = false,
        pointLostAt: Long? = null,
        private val overlapSourceAt: Long? = null,
    ) {
        val flow = Flow(pointLostAt)
        val tracker = InterFrameDetectionTracker(VisualTrackingConfig(), { 0L }, flow)
        val identities = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = identities, rawDepthMotionOnly = true)

        fun run(endAt: Long = 3_000L, beforeConsume: (Input) -> Unit = {}): List<Seen> {
            val seen = mutableListOf<Seen>()
            for (at in 1_000L..endAt step 50) {
                val target = key(at)
                assertTrue(tracker.offerFrame(frame(at)).accepted)
                if (at < 1_100L) continue
                val sourceAt = if (controlledSources) {
                    listOf(1_000L, 1_200L, 1_400L, 2_100L, 2_350L).last { it <= at - 100L }
                } else 1_000L + ((at - 1_100L) / 250L) * 250L
                val actors = listOf(0, 1).let {
                    if ((sourceAt / 50L) % 2L == 1L) it.reversed() else it
                }
                val detections = actors.map { actor ->
                    candidate(if (sourceAt == overlapSourceAt) 0 else actor, sourceAt)
                }
                val result = tracker.trackFrom(key(sourceAt), detections, target)
                assertEquals(2, result.observations.size)
                assertTrue(result.observations.all { it.status == VisualTrackingStatus.TRACKED })
                val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(
                    result, detections, true, target.capturedAtElapsedRealtimeMs,
                ))
                val snapshot = snapshot(target, at)
                beforeConsume(Input(at, target, detections, result, observation, snapshot))
                val outputs = pipeline.processTrackedObservation(
                    snapshot, observation, MAPPER, target.frameId, target.capturedAtElapsedRealtimeMs,
                )
                assertEquals(2, outputs.size)
                result.observations.forEach { row ->
                    val output = outputs.single { it.sourceDetectionIndex == row.sourceIndex }
                    seen += Seen(at, actors[row.sourceIndex], row, output)
                }
            }
            return seen
        }
    }

    /** Coordinates model measured feature/point flow; IDs are selected and retained by the tracker. */
    private class Flow(private val pointLostAt: Long?) : VisualMotionEstimator {
        override val supportsMaterialPointTracking: Boolean get() = true
        val inputCounts = mutableListOf<Int>()

        override fun seed(frame: GrayTrackingFrame, detection: DetectionCandidate,
                          budget: TrackingWorkBudget): List<VisualMotionFeature> =
            (0 until 40).map { index ->
                val box = detection.bboxNorm
                VisualMotionFeature(
                    ((box.x + box.width * (.1f + (index % 8) * .8f / 7f)) * frame.width).toInt(),
                    ((box.y + box.height * (.1f + (index / 8) * .8f / 4f)) * frame.height).toInt(),
                )
            }

        override fun advance(source: GrayTrackingFrame, previous: GrayTrackingFrame,
                             target: GrayTrackingFrame, detection: DetectionCandidate,
                             features: List<VisualMotionFeature>, originalFeatureCount: Int,
                             budget: TrackingWorkBudget, materialPoint: VisualMotionPoint?): VisualMotionEstimate {
            inputCounts += features.size + if (materialPoint == null) 0 else 1
            val dx = IMAGE_SPEED * (target.key.frameId - source.key.frameId) / 1_000_000_000f
            val edgeDx = IMAGE_SPEED * (target.key.frameId - previous.key.frameId) / 1_000_000_000f
            val point = materialPoint?.takeUnless {
                target.key.frameId / 1_000_000L == pointLostAt
            }?.let { it.copy(x = it.x + edgeDx * target.width) }
            return VisualMotionEstimate(
                features.map { it.copy(currentX = it.sourceX + dx * target.width) },
                VisualAffineTransform(tx = dx), .95f, 0f, materialPoint = point,
            )
        }
    }

    private data class Input(val at: Long, val target: VisualFrameKey,
                             val detections: List<DetectionCandidate>, val result: VisualTrackingResult,
                             val observation: CurrentTrackedFrameObservation, val snapshot: DepthFrameSnapshot)
    private data class Seen(val at: Long, val actor: Int, val row: VisualTrackingObservation,
                            val output: TrackedObjectDepth)

    private fun assertUnknown(output: TrackedObjectDepth) {
        assertEquals(ObjectMovementDirection.UNKNOWN, output.motionEstimate.direction)
        assertNull(output.motionEstimate.objectVelocityInAnchorMps)
    }

    private fun assertMeasuredProximity(output: TrackedObjectDepth, expectedTrend: Trend = Trend.STABLE) {
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        assertEquals(DepthAvailability.MEASURED, output.depthAvailability)
        assertEquals(3f, output.riskDistanceM!!, .001f)
        assertEquals(expectedTrend, output.trend)
    }

    private companion object {
        const val IMAGE_SPEED = .1f
        val MAPPER = LetterboxCoordinateMapper(
            ModelInputTransform(100, 100, 100, 100, 1f, 0f, 0f), ImageSize(100, 100),
        )
        fun key(at: Long) = VisualFrameKey(
            7L, at * 1_000_000L, 10_000_000_000_000L + at * 1_000_000L, at + 50_000L, 3L,
        )
        fun frame(at: Long) = GrayTrackingFrame.copyOf(key(at), 100, 100, ByteArray(10_000))
        fun candidate(actor: Int, at: Long) = DetectionCandidate("person", .95f,
            RectNorm((if (actor == 0) .08f else .50f) + IMAGE_SPEED * (at - 1_000L) / 1_000f,
                .35f, .24f, .30f))
        fun snapshot(target: VisualFrameKey, at: Long) = DepthFrameSnapshot(
            target.frameId, DepthImage16(100, 100, IntArray(10_000) { 3_000 }),
            ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }), null,
            rawDepthTimestampNs = target.cameraTimestampNs,
            rawConfidenceTimestampNs = target.cameraTimestampNs,
            cameraImageTimestampNs = target.cameraTimestampNs,
            cameraPoseEvidence = CameraPoseEvidence(42L, at, 0f, 0f, 0f, 0f, 0f, -1f,
                CameraImageProjection(100, 100, 70f, 70f, 50f, 50f,
                    1f, 0f, 0f, 0f, 1f, 0f)),
        )
    }
}
