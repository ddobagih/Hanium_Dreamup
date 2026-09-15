package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.depth.BinaryImageMask
import org.junit.Assert.*
import org.junit.Test

class UnknownWalkingObstaclePolicyTest {
    private fun mask(w: Int = 130, h: Int = 80, predicate: (Int, Int) -> Boolean): BinaryImageMask {
        val bytes = ByteArray((w * h + 7) / 8)
        for (y in 0 until h) for (x in 0 until w) if (predicate(x, y)) {
            val i = y * w + x
            bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
        }
        return BinaryImageMask.fromPackedRoi(w, h, 0, 0, w, h, bytes)
    }

    @Test fun rectangleCountsPreserveHolesAndWordBoundaries() {
        val image = mask { x, y -> (x + y) % 3 == 0 }
        for (rect in listOf(intArrayOf(1, 2, 129, 79), intArrayOf(63, 0, 65, 80), intArrayOf(-3, -4, 200, 100))) {
            val expected = (0 until 80).sumOf { y -> (0 until 130).count { x ->
                x >= rect[0] && x < rect[2] && y >= rect[1] && y < rect[3] && (x + y) % 3 == 0
            } }
            assertEquals(expected, image.areaInside(rect[0], rect[1], rect[2], rect[3]))
        }
    }

    @Test fun portraitRotationChangesCorridorAxisInsteadOfRotatingDepthPixels() {
        val topStrip = mask { x, y -> x in 40..90 && y in 0..9 }
        assertEquals("outside_forward_corridor", UnknownWalkingObstaclePolicy.select(topStrip, null, 1).reason)
        val leftStrip = mask { x, y -> x in 16..29 && y in 30..50 }
        assertEquals("outside_forward_corridor", UnknownWalkingObstaclePolicy.select(leftStrip, null, 0).reason)
        // Rotated foreground reaches the corridor; missing range now gates before repetition.
        assertEquals("depth_unconfirmed", UnknownWalkingObstaclePolicy.select(leftStrip, null, 1).reason)
    }

    @Test fun aBoxSpanningCorridorWithOnlySideForegroundIsNotForwardEvidence() {
        val hole = mask { x, _ -> x < 20 || x >= 110 }
        assertEquals("outside_forward_corridor", UnknownWalkingObstaclePolicy.select(hole, null, 0).reason)
    }

    @Test fun allFourRotationsSelectTheSameUprightRegion() {
        fun sensor(x: Float, y: Float, turns: Int): Pair<Float, Float> = when (turns) {
            0 -> x to y
            1 -> y to (1f - x)
            2 -> (1f - x) to (1f - y)
            else -> (1f - y) to x
        }
        for (turns in 0..3) for ((x, expected) in listOf(
            0.1f to "outside_forward_corridor", 0.5f to "depth_unconfirmed")) {
            val center = sensor(x, 0.3f, turns)
            val image = mask { px, py -> kotlin.math.abs((px + .5f) / 130 - center.first) < .04f &&
                kotlin.math.abs((py + .5f) / 80 - center.second) < .04f }
            assertEquals("rotation $turns", expected, UnknownWalkingObstaclePolicy.select(image, null, turns).reason)
        }
    }

    @Test fun uprightCorridorRetainsBothHeadAndFootForegroundAcrossAllRotations() {
        // The old vertical 12%-95% crop could discard head/low hazards; the v6 band spans full height.
        for (turns in 0..3) for (uprightY in listOf(.03f, .97f)) {
            val sensorCenter = when (turns) {
                0 -> .5f to uprightY
                1 -> uprightY to .5f
                2 -> .5f to 1f - uprightY
                else -> 1f - uprightY to .5f
            }
            val image = mask { x, y -> kotlin.math.abs((x + .5f) / 130 - sensorCenter.first) < .02f &&
                kotlin.math.abs((y + .5f) / 80 - sensorCenter.second) < .02f }
            assertTrue("rotation=$turns uprightY=$uprightY", UnknownWalkingObstaclePolicy.hasForwardCorridorSupport(image, turns))
            assertEquals("depth_unconfirmed", UnknownWalkingObstaclePolicy.select(image, null, turns).reason)
        }
    }
}
