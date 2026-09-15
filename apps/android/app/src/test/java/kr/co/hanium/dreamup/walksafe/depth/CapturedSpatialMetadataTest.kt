package kr.co.hanium.dreamup.walksafe.depth

import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturedSpatialMetadataTest {
    @Test
    fun missingOptionalMetadataPreservesCameraPose() {
        val pose = pose()
        val result = pose.withCapturedSpatialMetadata<Int>(
            { error("gravity unavailable") }, { error("planes unavailable") }, { error("not reached") },
        )
        assertEquals(pose, result)
    }

    @Test
    fun oneFailedPlaneDoesNotEraseGravityOrOtherPlanes() {
        val result = pose().withCapturedSpatialMetadata(
            { Vec3(1f, 0f, 0f) }, { listOf(0, 1, 2) }, { if (it == 1) error("unavailable") else square() },
        )
        assertEquals(Vec3(1f, 0f, 0f), result.gravityUpInAnchor)
        assertEquals(2, result.horizontalPlaneCandidates.size)
        result.horizontalPlaneCandidates.forEach {
            assertEquals(result.referenceId, it.referenceId)
            assertEquals(result.timestampMs, it.captureTimestampMs)
        }
    }

    @Test
    fun sourcePolygonMutationCannotChangeCapturedBoundary() {
        val polygon = square().toMutableList()
        val result = pose().withCapturedSpatialMetadata({ Vec3(0f, 1f, 0f) }, { listOf(polygon) }, { it })
        polygon[0] = Vec3(99f, 99f, 99f)
        polygon.clear()
        assertEquals(square(), result.horizontalPlaneCandidates.single().polygonInAnchor)
        assertThrows(UnsupportedOperationException::class.java) {
            (result.horizontalPlaneCandidates as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (result.horizontalPlaneCandidates.single().polygonInAnchor as MutableList).clear()
        }
    }

    @Test
    fun candidateCountAndInspectedCountAreBounded() {
        var inspected = 0
        val valid = pose().withCapturedSpatialMetadata(
            { Vec3(0f, 1f, 0f) }, { List(100) { it } }, { inspected++; square() },
        )
        assertEquals(MAX_CAPTURED_HORIZONTAL_PLANES, valid.horizontalPlaneCandidates.size)
        assertEquals(MAX_CAPTURED_HORIZONTAL_PLANES, inspected)
        inspected = 0
        val invalid = pose().withCapturedSpatialMetadata(
            { Vec3(0f, 1f, 0f) }, { List(100) { it } }, { inspected++; null },
        )
        assertTrue(invalid.horizontalPlaneCandidates.isEmpty())
        assertEquals(MAX_INSPECTED_HORIZONTAL_PLANES, inspected)
    }

    @Test
    fun oversizedOrNonFinitePolygonIsOmittedWithoutTruncation() {
        val polygons = listOf(List(MAX_CAPTURED_PLANE_VERTICES + 1) { Vec3(1f, 0f, 1f) },
            square() + Vec3(Float.NaN, 0f, 0f), square().take(2), square())
        val result = pose().withCapturedSpatialMetadata({ Vec3(0f, 1f, 0f) }, { polygons }, { it })
        assertEquals(listOf(square()), result.horizontalPlaneCandidates.map { it.polygonInAnchor })
    }

    @Test
    fun invalidGravityIsUnknownWhileValidPlaneRemains() {
        listOf(Vec3(0f, 0f, 0f), Vec3(0f, 2f, 0f), Vec3(Float.NaN, 1f, 0f)).forEach { gravity ->
            val result = pose().withCapturedSpatialMetadata({ gravity }, { listOf(square()) }, { it })
            assertNull(result.gravityUpInAnchor)
            assertEquals(1, result.horizontalPlaneCandidates.size)
        }
    }

    @Test
    fun invalidReferenceOrCaptureTimeOmitsOnlyOptionalEvidence() {
        listOf(pose().copy(referenceId = 0L), pose().copy(timestampMs = 0L)).forEach { pose ->
            val result = pose.withCapturedSpatialMetadata<Int>(
                { error("must not read") }, { error("must not read") }, { error("must not read") },
            )
            assertEquals(pose, result)
        }
    }

    @Test
    fun bufferPositionAndSourceBytesAreNotRetained() {
        val coordinates = FloatBuffer.wrap(floatArrayOf(99f, 99f, -1f, -2f, 1f, -2f, 1f, 2f, -1f, 2f))
        coordinates.position(2)
        val result = captureHorizontalPlanePolygon(coordinates) { it }
        assertEquals(2, coordinates.position())
        coordinates.put(2, 50f)
        assertEquals(square(), result)
    }

    @Test
    fun incompleteOversizedAndNonFiniteLocalBuffersAreRejectedBeforeTransform() {
        listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f), FloatArray((MAX_CAPTURED_PLANE_VERTICES + 1) * 2),
            floatArrayOf(Float.NaN, 0f, 1f, 0f, 0f, 1f)).forEach { values ->
            var transformed = 0
            assertNull(captureHorizontalPlanePolygon(FloatBuffer.wrap(values)) { transformed++; it })
            assertEquals(0, transformed)
        }
        assertNotNull(captureHorizontalPlanePolygon(FloatBuffer.wrap(FloatArray(MAX_CAPTURED_PLANE_VERTICES * 2))) { it })
    }

    @Test
    fun malformedOrNonFiniteTransformedPointIsRejected() {
        val coordinates = FloatBuffer.wrap(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f))
        assertNull(captureHorizontalPlanePolygon(coordinates) { floatArrayOf(0f, 1f) })
        assertNull(captureHorizontalPlanePolygon(coordinates) { floatArrayOf(0f, Float.POSITIVE_INFINITY, 0f) })
    }

    private fun pose() = CameraPoseEvidence(7L, 1_000L, 1f, 2f, 3f, 0f, 0f, -1f)
    private fun square() = listOf(Vec3(-1f, 0f, -2f), Vec3(1f, 0f, -2f), Vec3(1f, 0f, 2f), Vec3(-1f, 0f, 2f))
}
