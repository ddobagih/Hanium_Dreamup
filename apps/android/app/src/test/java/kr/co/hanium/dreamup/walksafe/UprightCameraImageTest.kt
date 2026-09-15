package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.inference.ArgbImage
import kr.co.hanium.dreamup.walksafe.inference.LetterboxTransform
import kr.co.hanium.dreamup.walksafe.inference.YoloRawOutputParser
import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class UprightCameraImageTest {
    @Test fun portraitImagePixelsAreUprightAndAllRotationsRoundTrip() {
        val source = ArgbImage(3, 2, intArrayOf(1, 2, 3, 4, 5, 6))
        val portrait = UprightCameraImage.rotate(source, 1)
        assertEquals(2, portrait.width)
        assertEquals(3, portrait.height)
        assertArrayEquals(intArrayOf(4, 1, 5, 2, 6, 3), portrait.pixels)
        for (turns in 0..3) {
            val restored = UprightCameraImage.rotate(UprightCameraImage.rotate(source, turns), (4 - turns) % 4)
            assertEquals(source.width, restored.width)
            assertEquals(source.height, restored.height)
            assertArrayEquals(source.pixels, restored.pixels)
        }
    }

    @Test fun boxesReturnToTheSameSensorRegionForScreenAndDepth() {
        val original = RectNorm(0.1f, 0.2f, 0.3f, 0.4f)
        val rotated = listOf(original, RectNorm(0.4f, 0.1f, 0.4f, 0.3f),
            RectNorm(0.6f, 0.4f, 0.3f, 0.4f), RectNorm(0.2f, 0.6f, 0.4f, 0.3f))
        for (turns in 0..3) {
            val actual = UprightCameraImage.toSensor(rotated[turns], turns)
            assertEquals(original.x, actual.x, 0.00001f)
            assertEquals(original.y, actual.y, 0.00001f)
            assertEquals(original.width, actual.width, 0.00001f)
            assertEquals(original.height, actual.height, 0.00001f)
        }
        assertEquals(1, UprightCameraImage.quarterTurns(floatArrayOf(100f, 0f, 100f, 300f, 0f, 0f)))
        assertEquals(3, UprightCameraImage.quarterTurns(floatArrayOf(0f, 300f, 0f, 0f, 100f, 300f)))
    }

    @Test fun parserLetterboxRightEdgeSweepHasNoNegativeRotatedCoordinates() {
        val anchors = 12_096
        val parser = YoloRawOutputParser(768, 1, { "person" }, { .15f }, normalizedCoordinates = true)
        val transform = LetterboxTransform(480, 640, 768, 1.2f, 96f, 0f)
        for (left in 97..670) {
            val raw = FloatArray(5 * anchors)
            raw[0] = ((left + 672f) / 2f) / 768f
            raw[anchors] = .5f
            raw[2 * anchors] = (672f - left) / 768f
            raw[3 * anchors] = .4f
            raw[4 * anchors] = .9f
            val image = requireNotNull(transform.modelDetectionToImageDetection(parser.parse(raw).single()))
            for (turns in 0..3) {
                val sensor = UprightCameraImage.toSensor(image.bboxNorm, turns)
                assertTrue("left=$left turns=$turns rect=$sensor", sensor.x >= 0f && sensor.y >= 0f &&
                    sensor.x + sensor.width <= 1f && sensor.y + sensor.height <= 1f)
            }
        }
    }

    @Test fun fourImageEdgesRemainValidInTheActualTrackerForEveryQuarterTurn() {
        val pixels = ByteArray(192 * 192).also { Random(0x571a).nextBytes(it) }
        val edgeRects = listOf(RectNorm(0f, .3f, .3f, .4f), RectNorm(.3f, 0f, .4f, .3f),
            RectNorm(.7256944f, .3f, .2743056f, .4f), RectNorm(.3f, .7256944f, .4f, .2743056f))
        for (turns in 0..3) for (rect in edgeRects) {
            val sensor = UprightCameraImage.toSensor(rect, turns)
            val detection = DetectionCandidate("edge", .9f, sensor)
            val tracker = InterFrameDetectionTracker(VisualTrackingConfig(maxFeaturesPerObject = 8,
                backend = VisualTrackingBackend.PATCH_DIAGNOSTIC), clockNanos = { 0L })
            val source = VisualFrameKey(1, 1_000_000_000L, 1_000_000_000L, 1_000L, 1)
            val target = VisualFrameKey(1, 1_050_000_000L, 1_050_000_000L, 1_050L, 1)
            tracker.offerFrame(GrayTrackingFrame.copyOf(source, 192, 192, pixels))
            val direct = tracker.trackFrom(source, listOf(detection), source)
            assertEquals("Direct edge $rect turns=$turns", VisualTrackingStatus.TRACKED, direct.observations.single().status)
            // Move edge objects inward so real subpixel fitting noise cannot imply a genuine exit.
            val dx = if (sensor.x == 0f) 3 else if (sensor.x + sensor.width == 1f) -3 else 0
            val dy = if (sensor.y == 0f) 3 else if (sensor.y + sensor.height == 1f) -3 else 0
            val moved = ByteArray(pixels.size) { index ->
                val x = index % 192 - dx
                val y = index / 192 - dy
                if (x in 0 until 192 && y in 0 until 192) pixels[y * 192 + x] else 128.toByte()
            }
            tracker.offerFrame(GrayTrackingFrame.copyOf(target, 192, 192, moved))
            val tracked = tracker.trackFrom(source, listOf(detection), target).observations.single()
            assertEquals("Flow edge $rect turns=$turns failure=${tracked.failure}", VisualTrackingStatus.TRACKED, tracked.status)
        }
        val invalid = UprightCameraImage.toSensor(RectNorm(.9f, .2f, .2f, .3f), 1)
        assertTrue("Real out-of-frame geometry must not be clamped into validity", invalid.y < -.09f)
    }
}
