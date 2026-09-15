package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.depth.BinaryImageMask
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.Vec3
import org.junit.Assert.*
import org.junit.Test

class UnknownSurfaceGroupingTest {
    private fun mask(left: Int, top: Int, right: Int, bottom: Int,
                     foreground: (Int, Int) -> Boolean = { _, _ -> true }): BinaryImageMask {
        val width = right - left; val height = bottom - top
        val bytes = ByteArray((width * height + 7) / 8)
        for (y in top until bottom) for (x in left until right) if (foreground(x, y)) {
            val i = (y - top) * width + x - left
            bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
        }
        return BinaryImageMask.fromPackedRoi(100, 100, left, top, width, height, bytes)
    }

    private fun fragment(id: String, mask: BinaryImageMask = mask(10, 10, 20, 30), z: Float = 2f,
                         level: MessageLevel = MessageLevel.AWARE): UnknownSurfaceGrouping.Fragment {
        val samples = mutableListOf<UnknownSurfaceGrouping.Sample>()
        for (y in mask.top until mask.top + mask.height step 2) for (x in mask.left until mask.left + mask.width step 2) {
            if (mask.contains(x, y)) samples += UnknownSurfaceGrouping.Sample(x, y, Vec3(x * .01f, y * .01f, z), .95f)
        }
        return UnknownSurfaceGrouping.Fragment(id, mask, samples, DepthSource.ARCORE_RAW_DEPTH,
            1_000_000_000L, 990_000_000L, 1L, .95f, level, distanceM = z)
    }

    private fun assertSeparate(a: UnknownSurfaceGrouping.Fragment, b: UnknownSurfaceGrouping.Fragment) {
        val result = UnknownSurfaceGrouping.group(listOf(a, b))
        assertEquals(2, result.groups.size)
        assertEquals(mapOf(a.id to a.id, b.id to b.id), result.representativeByMember)
    }

    @Test fun overlappingMeasuredSurfaceKeepsTheHighestActualWarning() {
        val a = fragment("near", level = MessageLevel.CAUTION).copy(distanceM = 1f, timeToCollisionMs = 50)
        val b = fragment("danger", mask(16, 10, 26, 30), level = MessageLevel.STOP)
            .copy(distanceM = 3f, timeToCollisionMs = 500)
        val result = UnknownSurfaceGrouping.group(listOf(a, b))
        assertEquals(listOf(UnknownSurfaceGrouping.Group("danger", listOf("danger", "near"))), result.groups)
        assertEquals(mapOf("danger" to "danger", "near" to "danger"), result.representativeByMember)
    }

    @Test fun edgeTouchingMeasuredPlanarFragmentsCanConsolidate() {
        val a = fragment("left")
        val b = fragment("right", mask(20, 10, 30, 30))
        assertEquals(1, UnknownSurfaceGrouping.group(listOf(a, b)).groups.size)
    }

    @Test fun boundingBoxOverlapWithAHoleNeverCountsAsSurfaceContact() {
        val ring = mask(10, 10, 40, 40) { x, y -> x < 14 || x >= 36 || y < 14 || y >= 36 }
        assertSeparate(fragment("ring", ring), fragment("inside-hole", mask(18, 18, 28, 28)))
    }

    @Test fun onePixelBackgroundSeamKeepsSameDepthParallelObjectsIndependent() {
        assertSeparate(fragment("left"), fragment("right", mask(21, 10, 31, 30)))
        assertSeparate(fragment("left"), fragment("remote", mask(50, 10, 60, 30)))
    }

    @Test fun touchingForegroundAndBackgroundDepthAreNotOneSurface() {
        assertSeparate(fragment("foreground"), fragment("background", mask(20, 10, 30, 30), z = 3f))
        // A close parallel plane also fails; similar range and normal are insufficient.
        assertSeparate(fragment("foreground"), fragment("parallel", mask(20, 10, 30, 30), z = 2.02f))
    }

    @Test fun distinctNormalsAndNonplanarMeasuredSupportFailClosed() {
        val a = fragment("front")
        val b = fragment("side", mask(20, 10, 30, 30))
        val corner = b.copy(samples = b.samples.map { it.copy(pointInAnchor =
            Vec3(.20f, it.pointInAnchor.y, 2f + (it.imageX - 20) * .01f)) })
        assertSeparate(a, corner)
        val nonplanar = b.copy(samples = b.samples.mapIndexed { index, sample ->
            if (index == b.samples.size / 2) sample.copy(pointInAnchor = sample.pointInAnchor + Vec3(0f, 0f, .08f)) else sample
        })
        assertSeparate(a, nonplanar)
    }

    @Test fun onlyCurrentTrustedMeasuredConfidentMatchingEvidenceCanConsolidate() {
        val a = fragment("a")
        val b = fragment("b", mask(20, 10, 30, 30))
        val rejected = listOf(
            b.copy(measured = false), b.copy(confidence = .5f), b.copy(confidence = Float.NaN),
            b.copy(referenceId = 2), b.copy(referenceId = -1), b.copy(frameTimestampNs = 1_000_000_001L),
            b.copy(depthTimestampNs = 989_000_000L), b.copy(source = DepthSource.ARCORE_FULL_DEPTH),
            b.copy(source = DepthSource.OBJECT_SIZE_PRIOR), b.copy(source = DepthSource.MONOCULAR_METRIC_DEPTH),
            b.copy(depthTimestampNs = 0), b.copy(depthTimestampNs = 1_010_000_000L),
            b.copy(samples = b.samples.map { it.copy(confidence = .3f) }),
            b.copy(samples = b.samples.take(5)), b.copy(samples = emptyList()),
            b.copy(samples = b.samples.map { it.copy(pointInAnchor = Vec3(Float.NaN, 0f, 0f)) }),
        )
        for (candidate in rejected) assertSeparate(a, candidate)
        assertSeparate(a.copy(depthTimestampNs = 500_000_000L), b.copy(depthTimestampNs = 500_000_000L))
    }

    @Test fun contactRequiresMeasurementsAtThatPartOfTheMask() {
        val a = fragment("a", mask(10, 10, 30, 30))
        val b = fragment("b", mask(30, 10, 50, 30))
        // Actual masks touch, but all accepted support lies away from the boundary.
        assertSeparate(a.copy(samples = a.samples.filter { it.imageX <= 20 }),
            b.copy(samples = b.samples.filter { it.imageX >= 40 }))
        assertSeparate(a, b.copy(samples = a.samples)) // Samples outside the member's foreground.
    }

    @Test fun aSingleMeasuredContactDoesNotSuppressAnotherWarning() {
        val a = fragment("a")
        val b = fragment("b", mask(20, 28, 30, 48))
        assertSeparate(a, b)
    }

    @Test fun thinCollinearSupportsRemainIndependent() {
        val a = fragment("a")
        val b = fragment("b", mask(20, 10, 30, 30))
        assertSeparate(a.copy(samples = a.samples.filter { it.imageX == 18 }),
            b.copy(samples = b.samples.filter { it.imageX == 20 }))
    }

    @Test fun representativePriorityIsSeverityThenTtcThenRangeThenStableId() {
        val a = fragment("a").copy(timeToCollisionMs = 300, distanceM = .5f)
        val b = fragment("b").copy(timeToCollisionMs = 200, distanceM = 3f)
        fun representative(vararg fragments: UnknownSurfaceGrouping.Fragment) =
            UnknownSurfaceGrouping.group(fragments.toList()).groups.single().representativeId
        assertEquals("b", representative(a, b))
        assertEquals("a", representative(a.copy(timeToCollisionMs = 200), b))
        assertEquals("a", representative(b.copy(distanceM = .5f), a.copy(timeToCollisionMs = 200)))
        assertEquals("b", representative(a.copy(timeToCollisionMs = -1, distanceM = Float.NaN), b))
        val shuffled = listOf(a, b, fragment("c").copy(timeToCollisionMs = 100))
        assertEquals(UnknownSurfaceGrouping.group(shuffled), UnknownSurfaceGrouping.group(shuffled.reversed()))
    }

    @Test fun aDisconnectedBridgeDoesNotTransitivelyHideAnIndependentObject() {
        val a = fragment("a", mask(10, 10, 20, 30))
        val c = fragment("c", mask(40, 10, 50, 30))
        val bridge = fragment("bridge", mask(10, 10, 50, 30) { x, _ -> x < 20 || x >= 40 },
            level = MessageLevel.STOP)
        val result = UnknownSurfaceGrouping.group(listOf(a, bridge, c))
        assertEquals(2, result.groups.size)
        assertEquals("bridge", result.representativeByMember["a"])
        assertEquals("c", result.representativeByMember["c"])
    }

    @Test fun translationAndCameraRotationDoNotChangeMeasuredContact() {
        fun transform(f: UnknownSurfaceGrouping.Fragment) = f.copy(samples = f.samples.map { sample ->
            val p = sample.pointInAnchor
            sample.copy(pointInAnchor = Vec3(8f + p.z, -3f + p.x, 12f + p.y))
        })
        val a = transform(fragment("a")); val b = transform(fragment("b", mask(20, 10, 30, 30)))
        assertEquals(1, UnknownSurfaceGrouping.group(listOf(a, b)).groups.size)
    }

    @Test fun emptyFrameAndSingletonRequireNoFabricatedPhysicalIdentity() {
        assertEquals(UnknownSurfaceGrouping.Result(emptyList(), emptyMap()), UnknownSurfaceGrouping.group(emptyList()))
        val only = fragment("existing-track").copy(samples = emptyList())
        assertEquals(mapOf("existing-track" to "existing-track"), UnknownSurfaceGrouping.group(listOf(only)).representativeByMember)
    }
}
