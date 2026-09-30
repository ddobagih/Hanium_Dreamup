package kr.co.hanium.dreamup.walksafe.inference.tracking

import java.util.Random
import kotlin.math.floor
import kotlin.math.roundToInt
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S10-22 / S10-25: a batch's tracking priority is fixed at admission, so degraded tracking must ask
 * for an earlier fresh detection instead of re-ranking or reviving identities inside the batch.
 * Ground truth comes from rendered image motion, never from the production matching algorithm.
 */
class InterFrameTrackingRedetectSignalTest {
    @Test
    fun steadilyTrackedObjectAndExactDetectorFrameDoNotRequestRedetection() {
        val tracker = tracker()
        val candidate = photographDetection()
        val source = offer(tracker, camera(), 0)
        val exact = tracker.trackFrom(source, listOf(candidate), source)
        assertFalse(exact.redetectRequested)
        val target = offer(tracker, translate(camera(), 3.0, -2.0), 1)
        val result = tracker.trackFrom(source, listOf(candidate), target)
        assertEquals(VisualTrackingStatus.TRACKED, result.observations.single().status)
        assertEquals(emptySet<VisualRedetectReason>(), result.redetectReasons)
    }

    @Test
    fun occludedAdmittedObjectIsLostAndRequestsRedetectionWithoutRevival() {
        val tracker = tracker()
        val candidate = photographDetection()
        val source = offer(tracker, camera(), 0)
        tracker.trackFrom(source, listOf(candidate), source)
        val occluded = offer(tracker, image { _, _ -> 128 }, 1)
        val result = tracker.trackFrom(source, listOf(candidate), occluded)
        assertEquals(VisualTrackingStatus.LOST, result.observations.single().status)
        assertTrue(result.redetectRequested)
        assertEquals(setOf(VisualRedetectReason.TRACK_LOST), result.redetectReasons)
        val uncovered = offer(tracker, translate(camera(), 2.0, 0.0), 2)
        val later = tracker.trackFrom(source, listOf(candidate), uncovered)
        assertEquals("A redetect request never revives the lost identity", VisualTrackingStatus.LOST, later.observations.single().status)
        assertTrue(later.redetectRequested)
    }

    @Test
    fun convergingObjectsAreBothLostAsCompetitionAndRequestImmediateRedetection() {
        val tracker = tracker()
        // Source IoU 0.14; the boxes converge onto one region (IoU 0.68 at step 6) without pixel occlusion.
        val candidates = listOf(detection(30, 70, 64, 32), detection(78, 70, 64, 32))
        val source = offer(tracker, patches(30, 110), 0)
        tracker.trackFrom(source, candidates, source)
        for (step in 1..5) {
            val result = tracker.trackFrom(source, candidates, offer(tracker, patches(30 + step * 3, 110 - step * 3), step))
            assertTrue(result.observations.all { it.status == VisualTrackingStatus.TRACKED })
            assertFalse("step $step", result.redetectRequested)
        }
        val converged = tracker.trackFrom(source, candidates, offer(tracker, patches(48, 92), 6))
        assertEquals(listOf(VisualTrackingFailure.OBJECT_COMPETITION, VisualTrackingFailure.OBJECT_COMPETITION),
            converged.observations.map { it.failure })
        assertTrue(converged.observations.all { it.status == VisualTrackingStatus.LOST && it.geometry == null })
        assertTrue(converged.redetectRequested)
        assertTrue(VisualRedetectReason.OCCLUSION_OR_COMPETITION in converged.redetectReasons)
    }

    @Test
    fun trackedSideObjectEnteringTheWalkingZoneRequestsRedetection() {
        val tracker = tracker()
        // Upright turns 0: x + width 52/192 = 0.27 starts outside the central lower zone.
        val scooter = detection(20, 100, 32, 32)
        val source = offer(tracker, patches(20, 150, y = 100), 0)
        tracker.trackFrom(source, listOf(scooter), source)
        val outside = tracker.trackFrom(source, listOf(scooter), offer(tracker, patches(24, 150, y = 100), 1))
        assertEquals(VisualTrackingStatus.TRACKED, outside.observations.single().status)
        assertFalse(outside.redetectRequested)
        var entered: VisualTrackingResult? = null
        for (step in 2..5) {
            val result = tracker.trackFrom(source, listOf(scooter), offer(tracker, patches(20 + step * 4, 150, y = 100), step))
            assertEquals(VisualTrackingStatus.TRACKED, result.observations.single().status)
            if (result.redetectRequested) { entered = result; break }
        }
        assertEquals(setOf(VisualRedetectReason.WALKING_ZONE_ENTRY), checkNotNull(entered).redetectReasons)
    }

    @Test
    fun budgetExcludedSideScooterRequestsRedetectionAndTheNextBatchTracksItInTheCenter() {
        val tracker = tracker(VisualTrackingConfig(maxTrackedObjects = 1))
        val center = detection(80, 120, 32, 32)
        val sideScooter = detection(10, 100, 32, 32)
        val first = offer(tracker, patches(80, 10, y = 120, y2 = 100), 0)
        tracker.trackFrom(first, listOf(center, sideScooter), first)
        val oldBatch = tracker.trackFrom(first, listOf(center, sideScooter), offer(tracker, patches(80, 14, y = 120, y2 = 100), 1))
        assertEquals(VisualTrackingStatus.TRACKED, oldBatch.observations[0].status)
        assertEquals(VisualTrackingFailure.OBJECT_BUDGET_EXCEEDED, oldBatch.observations[1].failure)
        assertEquals(setOf(VisualRedetectReason.UNTRACKED_CANDIDATES), oldBatch.redetectReasons)
        // The early detection sees the scooter in the central lower region and the pedestrian moved aside.
        val fresh = offer(tracker, patches(150, 76, y = 60, y2 = 130), 2)
        val newCenter = detection(76, 130, 32, 32)
        val newAside = detection(150, 60, 32, 32)
        tracker.trackFrom(fresh, listOf(newAside, newCenter), fresh)
        val next = tracker.trackFrom(fresh, listOf(newAside, newCenter), offer(tracker, patches(150, 78, y = 60, y2 = 130), 3))
        assertEquals("The new batch must not keep following only the initial center object",
            VisualTrackingStatus.TRACKED, next.observations[1].status)
        assertEquals(VisualTrackingFailure.OBJECT_BUDGET_EXCEEDED, next.observations[0].failure)
    }

    private fun patches(p1: Int, p2: Int, y: Int = 70, y2: Int = y): ByteArray {
        val result = image { _, _ -> 128 }
        for ((left, top, seed) in listOf(Triple(p1, y, 0x9a21L), Triple(p2, y2, 0x7bf3L))) {
            val patch = texture(seed)
            for (dy in 0 until 32) for (dx in 0 until 32) {
                val x = left + dx
                val row = top + dy
                if (x in 0 until EDGE && row in 0 until EDGE) result[row * EDGE + x] = patch[dy * EDGE + dx]
            }
        }
        return result
    }

    private fun photographDetection() = detection(54, 48, 76, 76)

    private fun camera(): ByteArray = photograph

    private fun tracker(config: VisualTrackingConfig = VisualTrackingConfig(), clockNanos: () -> Long = { 0L }) =
        InterFrameDetectionTracker(
            config.copy(maxFeaturesPerObject = 8, backend = VisualTrackingBackend.PATCH_DIAGNOSTIC),
            clockNanos = clockNanos,
        )

    private fun offer(tracker: InterFrameDetectionTracker, pixels: ByteArray, index: Int, elapsed: Long = index * 50L): VisualFrameKey {
        val key = key(index, elapsed)
        assertTrue("Frame $index must be admitted", tracker.offerFrame(frame(pixels, key)).accepted)
        return key
    }

    private fun key(index: Int, elapsed: Long = index * 50L) = VisualFrameKey(
        epoch = 1L,
        frameId = index.toLong(),
        cameraTimestampNs = (10_000L + elapsed) * 1_000_000L,
        capturedAtElapsedRealtimeMs = 10_000L + elapsed,
        geometryVersion = 1L,
    )

    private fun frame(pixels: ByteArray, key: VisualFrameKey) = GrayTrackingFrame.copyOf(key, EDGE, EDGE, pixels)

    private fun detection(x: Int, y: Int, width: Int, height: Int): DetectionCandidate {
        val left = x.toFloat() / EDGE
        val top = y.toFloat() / EDGE
        val right = (x + width).toFloat() / EDGE
        val bottom = (y + height).toFloat() / EDGE
        return DetectionCandidate(
            className = "person",
            detectionConfidence = 0.87f,
            bboxNorm = RectNorm(left, top, width.toFloat() / EDGE, height.toFloat() / EDGE),
            polygonNorm = listOf(Point2(left, top), Point2(right, top), Point2(right, bottom), Point2(left, bottom)),
            maskAreaNorm = width.toFloat() * height / (EDGE * EDGE),
        )
    }

    private fun image(pixel: (Int, Int) -> Int) = ByteArray(EDGE * EDGE) { index ->
        pixel(index % EDGE, index / EDGE).coerceIn(0, 255).toByte()
    }

    private fun texture(seed: Long): ByteArray {
        val random = Random(seed)
        return ByteArray(EDGE * EDGE) { (40 + random.nextInt(176)).toByte() }
    }

    /** Inverse image warp with bilinear sampling; blank fill is outside every test's object ROI. */
    private fun translate(source: ByteArray, dx: Double, dy: Double) = image { x, y -> sample(source, x - dx, y - dy) }

    private fun sample(source: ByteArray, x: Double, y: Double): Int {
        if (x < 0.0 || y < 0.0 || x > EDGE - 1.0 || y > EDGE - 1.0) return 128
        val left = floor(x).toInt()
        val top = floor(y).toInt()
        val right = (left + 1).coerceAtMost(EDGE - 1)
        val bottom = (top + 1).coerceAtMost(EDGE - 1)
        val fx = x - left
        val fy = y - top
        return (
            intensity(source, left, top) * (1 - fx) * (1 - fy) +
                intensity(source, right, top) * fx * (1 - fy) +
                intensity(source, left, bottom) * (1 - fx) * fy +
                intensity(source, right, bottom) * fx * fy
            ).roundToInt()
    }

    private fun intensity(source: ByteArray, x: Int, y: Int) = source[y * EDGE + x].toInt() and 0xff

    companion object {
        private const val EDGE = 192
        private val photograph: ByteArray = checkNotNull(InterFrameTrackingRedetectSignalTest::class.java.getResourceAsStream("/tracking/camera-192x192.gray")) {
            "Missing CC0 photograph fixture; see work/heterogeneous-runtime/tracking-fixture-notes.md"
        }.use { it.readBytes() }.also { require(it.size == EDGE * EDGE) }
    }
}
