package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.depth.BinaryImageMask
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Current-frame warning consolidation. A group is not a physical identity or a tracking ID. */
object UnknownSurfaceGrouping {
    /** Original CPU-image pixel and its actual calibrated, robust-inlier depth measurement. */
    data class Sample(val imageX: Int, val imageY: Int, val pointInAnchor: Vec3, val confidence: Float)

    data class Fragment(
        val id: String,
        val mask: BinaryImageMask,
        val samples: List<Sample>,
        val source: DepthSource,
        val frameTimestampNs: Long,
        val depthTimestampNs: Long,
        val referenceId: Long,
        val confidence: Float,
        val level: MessageLevel,
        val timeToCollisionMs: Long? = null,
        val distanceM: Float? = null,
        val measured: Boolean = true,
    )

    data class Group(val representativeId: String, val memberIds: List<String>)
    data class Result(val groups: List<Group>, val representativeByMember: Map<String, String>)

    private data class Plane(val origin: Vec3, val normal: Vec3)
    private data class Supported(val fragment: Fragment, val samples: List<Sample>, val plane: Plane)

    // These are conservative consolidation allowances, not calibrated depth accuracy claims.
    private const val MIN_CONFIDENCE = .75f
    private const val MIN_SAMPLES = 6
    private const val MIN_CONTACT_SAMPLES = 3
    private const val MAX_IMAGE_CONTACT_SPAN_PX = 4
    private const val MAX_CONTACT_DISTANCE_M = .04f
    private const val MAX_PLANE_RESIDUAL_M = .008f
    private const val MIN_NORMAL_DOT = .995f

    private val priority = compareByDescending<Fragment> { it.level.ordinal }
        .thenBy { it.timeToCollisionMs?.takeIf { value -> value >= 0L } ?: Long.MAX_VALUE }
        .thenBy { it.distanceM?.takeIf { value -> value.isFinite() && value > 0f } ?: Float.POSITIVE_INFINITY }
        .thenBy { it.id }

    fun group(fragments: List<Fragment>): Result {
        require(fragments.map { it.id }.distinct().size == fragments.size) { "Fragment IDs must be unique within a frame" }
        val remaining = fragments.sortedWith(priority).toMutableList()
        val supported = fragments.associate { it.id to support(it) }
        val groups = mutableListOf<Group>()
        while (remaining.isNotEmpty()) {
            val representative = remaining.removeAt(0)
            val members = mutableListOf(representative)
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                // Every member must directly contact every other member. A disconnected or
                // ambiguous bridge fragment must never collapse independent objects transitively.
                if (members.all { member -> sameSurface(supported[member.id], supported[candidate.id]) }) {
                    members += candidate
                    iterator.remove()
                }
            }
            groups += Group(representative.id, members.map { it.id }.sorted())
        }
        return Result(groups, groups.flatMap { group -> group.memberIds.map { it to group.representativeId } }.toMap())
    }

    private fun support(fragment: Fragment): Supported? {
        if (!fragment.measured || !fragment.source.trustedForStepGuidance || !fragment.source.metric ||
            fragment.frameTimestampNs <= 0L || fragment.depthTimestampNs <= 0L || fragment.referenceId < 0L ||
            fragment.frameTimestampNs < fragment.depthTimestampNs ||
            fragment.frameTimestampNs - fragment.depthTimestampNs > 250_000_000L ||
            !fragment.confidence.isFinite() || fragment.confidence < MIN_CONFIDENCE || fragment.mask.area == 0) return null
        val samples = fragment.samples.filter { sample ->
            sample.confidence.isFinite() && sample.confidence >= MIN_CONFIDENCE &&
                finite(sample.pointInAnchor) && fragment.mask.contains(sample.imageX, sample.imageY)
        }.distinctBy { it.imageX to it.imageY }
        if (samples.size < MIN_SAMPLES) return null
        val plane = plane(samples.map { it.pointInAnchor }) ?: return null
        return Supported(fragment, samples, plane)
    }

    private fun sameSurface(a: Supported?, b: Supported?): Boolean {
        if (a == null || b == null) return false
        val af = a.fragment; val bf = b.fragment
        if (af.source != bf.source || af.frameTimestampNs != bf.frameTimestampNs ||
            af.depthTimestampNs != bf.depthTimestampNs || af.referenceId != bf.referenceId ||
            af.mask.originalWidth != bf.mask.originalWidth || af.mask.originalHeight != bf.mask.originalHeight ||
            abs(a.plane.normal.dot(b.plane.normal)) < MIN_NORMAL_DOT) return false
        if (af.mask.left > bf.mask.left + bf.mask.width + MAX_IMAGE_CONTACT_SPAN_PX ||
            bf.mask.left > af.mask.left + af.mask.width + MAX_IMAGE_CONTACT_SPAN_PX ||
            af.mask.top > bf.mask.top + bf.mask.height + MAX_IMAGE_CONTACT_SPAN_PX ||
            bf.mask.top > af.mask.top + af.mask.height + MAX_IMAGE_CONTACT_SPAN_PX) return false
        // Compare all measured support to both planes, not average range or enclosing boxes.
        if (a.samples.any { planeDistance(it.pointInAnchor, b.plane) > MAX_PLANE_RESIDUAL_M } ||
            b.samples.any { planeDistance(it.pointInAnchor, a.plane) > MAX_PLANE_RESIDUAL_M }) return false
        val contactsA = mutableSetOf<Int>()
        val contactsB = mutableSetOf<Int>()
        // Bucket only contact lookup. All bounded support still participates in plane checks.
        val cellSize = MAX_IMAGE_CONTACT_SPAN_PX + 1
        val buckets = b.samples.withIndex().groupBy { it.value.imageX / cellSize to it.value.imageY / cellSize }
        for ((i, sa) in a.samples.withIndex()) {
            val cx = sa.imageX / cellSize; val cy = sa.imageY / cellSize
            for (dx in -1..1) for (dy in -1..1) for ((j, sb) in buckets[cx + dx to cy + dy].orEmpty()) {
            if (abs(sa.imageX - sb.imageX) > MAX_IMAGE_CONTACT_SPAN_PX ||
                abs(sa.imageY - sb.imageY) > MAX_IMAGE_CONTACT_SPAN_PX ||
                (sa.pointInAnchor - sb.pointInAnchor).norm() > MAX_CONTACT_DISTANCE_M) continue
            if (!foregroundContact(sa, sb, af.mask, bf.mask)) continue
            contactsA += i; contactsB += j
            if (contactsA.size >= MIN_CONTACT_SAMPLES && contactsB.size >= MIN_CONTACT_SAMPLES) return true
        }
        }
        return false
    }

    /** No dilation: every crossed pixel must be real foreground, with an actual shared/edge contact. */
    private fun foregroundContact(a: Sample, b: Sample, am: BinaryImageMask, bm: BinaryImageMask): Boolean {
        val dx = b.imageX - a.imageX; val dy = b.imageY - a.imageY
        val steps = max(abs(dx), abs(dy))
        if (steps == 0) return am.contains(a.imageX, a.imageY) && bm.contains(a.imageX, a.imageY)
        var previousX = a.imageX; var previousY = a.imageY
        var contact = false
        for (step in 0..steps) {
            val x = a.imageX + (dx * step.toFloat() / steps).roundToInt()
            val y = a.imageY + (dy * step.toFloat() / steps).roundToInt()
            val inA = am.contains(x, y); val inB = bm.contains(x, y)
            if (!inA && !inB) return false
            if (inA && inB) contact = true
            if (step > 0) {
                // A diagonal-only connection is insufficient. Check both crossed side pixels
                // to preserve narrow holes and background seams between parallel objects.
                if (x != previousX && y != previousY &&
                    (!(am.contains(x, previousY) || bm.contains(x, previousY)) ||
                        !(am.contains(previousX, y) || bm.contains(previousX, y)))) return false
                if ((am.contains(previousX, previousY) && inB) ||
                    (bm.contains(previousX, previousY) && inA)) contact = true
            }
            previousX = x; previousY = y
        }
        return contact
    }

    /** Refuse collinear/thin or nonplanar support rather than infer a shared surface normal. */
    private fun plane(points: List<Vec3>): Plane? {
        val first = points.first()
        val second = points.maxBy { (it - first).norm() }
        val axis = second - first
        if (axis.norm() < .02f) return null
        val third = points.maxBy { cross(axis, it - first).norm() }
        val cross = cross(axis, third - first)
        if (cross.norm() / axis.norm() < .01f) return null
        val plane = Plane(first, cross.normalized())
        return plane.takeIf { points.all { point -> planeDistance(point, plane) <= MAX_PLANE_RESIDUAL_M } }
    }

    private fun planeDistance(point: Vec3, plane: Plane) = abs((point - plane.origin).dot(plane.normal))
    private fun finite(point: Vec3) = point.x.isFinite() && point.y.isFinite() && point.z.isFinite()
    private fun cross(a: Vec3, b: Vec3) = Vec3(a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
}
