package kr.co.hanium.dreamup.walksafe.navigation

import java.util.Collections
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy
import kotlin.math.*

/** A measured cell, transformed without substituting the mask median for its own axial depth. */
data class TactileRouteSurfacePoint(
    val sample: TactileSurfaceSample,
    val eastM: Double,
    val northM: Double,
    val alongRouteM: Double,
    val crossRouteM: Double,
    val planeHeightResidualM: Float,
    val corridorErrorAllowanceM: Double,
    val withinObservedCorridor: Boolean,
)

/** Unoriented, derived geometry. Neither end is a newly measured depth sample. */
data class TactileSurfaceAxis(val east: Double, val north: Double, val routeAngleDeg: Double,
                             val majorVarianceM2: Double, val minorVarianceM2: Double)
data class TactileSurfaceTangent(val fromDepthCell: Int, val toDepthCell: Int,
                                val east: Double, val north: Double, val routeAngleDeg: Double)
data class TactileSurfaceComponent(val id: Int, val points: List<TactileRouteSurfacePoint>,
                                   val axis: TactileSurfaceAxis?, val centerlineDepthCells: List<Int>,
                                   val tangents: List<TactileSurfaceTangent>, val branching: Boolean)
/** Exact route intersection with a measured four-cell patch; gaps are never joined by a hull. */
data class TactileSurfaceRouteOverlap(val depthCells: List<Int>, val fromAlongM: Double, val toAlongM: Double)
enum class TactileObservedRouteSupport { AXIS_AND_MULTIPOINT_OVERLAP, TOPOLOGY_UNRESOLVED, NO_MULTIPOINT_OVERLAP }

class TactileSurfaceRouteEvidence internal constructor(
    val surface: TactileMaskSurface,
    val routeId: String,
    val routeRevision: Long,
    val routeSegmentIndex: Int,
    val plane: CapturedHorizontalPlane,
    val originalMaskComponentCount: Int,
    val originalMaskHoleCount: Int,
    val originalMaskBranching: Boolean,
    components: List<TactileSurfaceComponent>,
    overlaps: List<TactileSurfaceRouteOverlap>,
    /** Relative fit to the single captured plane, not an absolute ground acceptance bound. */
    val selectedContact: TactileSurfaceContact? = surface.measuredContact,
) {
    val components: List<TactileSurfaceComponent> = Collections.unmodifiableList(ArrayList(components))
    val overlaps: List<TactileSurfaceRouteOverlap> = Collections.unmodifiableList(ArrayList(overlaps))
    /** Tested by the observation consumer; no angle cutoff or movement authority is implied. */
    val observedRouteSupport: TactileObservedRouteSupport get() {
        if (surface.samplingStatus != kr.co.hanium.dreamup.walksafe.depth.unknown.MaskDepthEstimator.Status.KNOWN) {
            return TactileObservedRouteSupport.TOPOLOGY_UNRESOLVED
        }
        val axial = components.filter { it.axis != null && it.tangents.isNotEmpty() }
        if (axial.isEmpty()) return TactileObservedRouteSupport.TOPOLOGY_UNRESOLVED
        return if (axial.any { c -> val cells = c.points.map { it.sample.depthCellIndex }.toHashSet()
            overlaps.any { patch -> patch.toAlongM > patch.fromAlongM && patch.depthCells.all { it in cells } }
        }) TactileObservedRouteSupport.AXIS_AND_MULTIPOINT_OVERLAP else TactileObservedRouteSupport.NO_MULTIPOINT_OVERLAP
    }
    // This API carries observed surface geometry only. It has no foot seed/body/walkability claim.
}

enum class TactileSurfaceRouteRejection { CAPTURE_MISMATCH, EXPIRED_CAPTURE, ROUTE_UNAVAILABLE,
    LOCATION_UNTRUSTED, ORIENTATION_UNTRUSTED, CAMERA_FRAME_UNTRUSTED, PLANE_UNAVAILABLE, PLANE_AMBIGUOUS }
data class TactileSurfaceRouteEvaluation(val evidence: TactileSurfaceRouteEvidence?,
                                         val rejection: TactileSurfaceRouteRejection?)

/** Surface observations remain independent of scalar contact association and route authority. */
class TactileSurfaceRouteProjector {
    fun project(surface: TactileMaskSurface, context: TactileProjectionContext,
                decisionElapsedNs: Long): TactileSurfaceRouteEvaluation {
        fun reject(reason: TactileSurfaceRouteRejection) = TactileSurfaceRouteEvaluation(null, reason)
        val token = surface.candidate.capture.token
        val decisionMs = context.decisionElapsedRealtimeMs
        val captured = surface.captureContext ?: return reject(TactileSurfaceRouteRejection.CAPTURE_MISMATCH)
        if (context.copy(detectionAgeMs = null, decisionElapsedRealtimeMs = null) !=
            captured.copy(detectionAgeMs = null, decisionElapsedRealtimeMs = null)) {
            return reject(TactileSurfaceRouteRejection.CAPTURE_MISMATCH)
        }
        if (context.captureFrameId != token.frameId || context.nowElapsedRealtimeMs != token.capturedElapsedNs / 1_000_000L ||
            decisionMs == null || decisionMs != decisionElapsedNs / 1_000_000L) return reject(TactileSurfaceRouteRejection.CAPTURE_MISMATCH)
        if (!tactileCaptureIsLive(surface.candidate, decisionElapsedNs)) return reject(TactileSurfaceRouteRejection.EXPIRED_CAPTURE)
        val route = context.routeProjection?.takeIf { it.routeId == context.expectedRouteId &&
            it.routeRevision != null && it.segmentIndex >= 0 && it.distanceToRouteM.isFinite() &&
            it.distanceToRouteM >= 0.0 && it.bearingDeg.isFinite() }
            ?: return reject(TactileSurfaceRouteRejection.ROUTE_UNAVAILABLE)
        if (!TactileObservationEvidence.hasFreshLocation(context)) return reject(TactileSurfaceRouteRejection.LOCATION_UNTRUSTED)
        val location = context.trustedLocation!!
        if (abs(location.latitude) >= 90.0) return reject(TactileSurfaceRouteRejection.LOCATION_UNTRUSTED)
        val orientation = context.orientation?.takeIf {
            TactileObservationEvidence.freshAtCaptureAndDecision(it.observedAtElapsedRealtimeMs,
                context.nowElapsedRealtimeMs, decisionMs, AndroidTactileRouteObservationSupplier.MAX_ORIENTATION_AGE_MS) &&
                it.accuracy >= EarthOrientationAccuracy.MEDIUM && it.headingErrorDeg.isFinite() &&
                it.headingErrorDeg in 0f..AndroidTactileRouteObservationSupplier.MAX_HEADING_ERROR_DEG &&
                it.deviceToMagneticEnu.isOrthonormal()
        } ?: return reject(TactileSurfaceRouteRejection.ORIENTATION_UNTRUSTED)
        val declination = context.magneticDeclinationDeg?.takeIf { it.isFinite() &&
            abs(it) <= AndroidTactileRouteObservationSupplier.MAX_DECLINATION_DEG }
            ?: return reject(TactileSurfaceRouteRejection.ORIENTATION_UNTRUSTED)
        if (!TactileObservationEvidence.hasFreshCameraFrame(context)) return reject(TactileSurfaceRouteRejection.CAMERA_FRAME_UNTRUSTED)
        val camera = context.cameraFrame!!
        val intrinsics = surface.pose.imageProjection ?: return reject(TactileSurfaceRouteRejection.CAMERA_FRAME_UNTRUSTED)
        if (camera.intrinsics.widthPx != token.width || camera.intrinsics.heightPx != token.height ||
            camera.intrinsics.focalLengthXPx != intrinsics.fx || camera.intrinsics.focalLengthYPx != intrinsics.fy ||
            camera.intrinsics.principalPointXPx != intrinsics.cx || camera.intrinsics.principalPointYPx != intrinsics.cy) {
            return reject(TactileSurfaceRouteRejection.CAMERA_FRAME_UNTRUSTED)
        }
        val up = surface.pose.gravityUpInAnchor?.takeIf { finite(it) && abs(it.norm() - 1f) <= .01f }
            ?: return reject(TactileSurfaceRouteRejection.PLANE_UNAVAILABLE)
        val right = Vec3(intrinsics.rightX, intrinsics.rightY, intrinsics.rightZ)
        val horizontalRight = (right - up * right.dot(up)).normalized()
        if (horizontalRight.norm() <= 1e-6f) return reject(TactileSurfaceRouteRejection.PLANE_UNAVAILABLE)
        val horizontalForward = cross(up, horizontalRight)
        val eligiblePlanes = surface.pose.horizontalPlaneCandidates.filter { p ->
            p.referenceId == surface.pose.referenceId && p.captureTimestampMs == surface.frameTimestampMs &&
                p.polygonInAnchor.size >= 3 && p.polygonInAnchor.all(::finite) &&
                p.polygonInAnchor.map { it.dot(up) }.let { h -> h.all { abs(it - h.average()) <= .015 } } &&
                surface.samples.all { s -> inside(s.pointInAnchor.dot(horizontalRight).toDouble(),
                    s.pointInAnchor.dot(horizontalForward).toDouble(), p.polygonInAnchor.map {
                        it.dot(horizontalRight).toDouble() to it.dot(horizontalForward).toDouble() }) }
        }
        if (eligiblePlanes.isEmpty()) return reject(TactileSurfaceRouteRejection.PLANE_UNAVAILABLE)
        val distinctPlanes = mutableListOf<CapturedHorizontalPlane>()
        eligiblePlanes.forEach { p -> if (distinctPlanes.none { sameCapturedPolygon(it, p) }) distinctPlanes.add(p) }
        // Neither polygon containment nor uncalibrated residuals select one of separate layers.
        if (distinctPlanes.size != 1) return TactileSurfaceRouteEvaluation(null,
            TactileSurfaceRouteRejection.PLANE_AMBIGUOUS)
        val plane = distinctPlanes.single()
        // Existing horizontal-plane coherence tolerance; height residual stays an observation,
        // not a new floor, tactile-height, or walking-space threshold.
        val planeHeight = plane.polygonInAnchor.map { it.dot(up) }.average().toFloat()
        // Candidates are already center-ranked; minBy retains that order on equal fit.
        val contact = surface.contactCandidates.minByOrNull {
            abs(it.sample.pointInAnchor.dot(up) - planeHeight)
        }
        val local = LocalGeodesy.Frame(location.latitude, location.longitude)
        val start = local.toLocal(route.segmentStart.latitude, route.segmentStart.longitude)
        val end = local.toLocal(route.segmentEnd.latitude, route.segmentEnd.longitude)
        val de = end.eastM - start.eastM; val dn = end.northM - start.northM
        val length = hypot(de, dn)
        if (!length.isFinite() || length < AndroidTactileRouteObservationSupplier.MIN_ROUTE_SEGMENT_LENGTH_M) {
            return reject(TactileSurfaceRouteRejection.ROUTE_UNAVAILABLE)
        }
        val re = de / length; val rn = dn / length
        val angle = Math.toRadians(declination.toDouble())
        val points = surface.samples.map { s ->
            val cameraPoint = Vec3(((s.imageX - intrinsics.cx) / intrinsics.fx * s.axialDepthM).toFloat(),
                (- (s.imageY - intrinsics.cy) / intrinsics.fy * s.axialDepthM).toFloat(), -s.axialDepthM)
            val enu = orientation.deviceToMagneticEnu.rotate(camera.cameraToAndroidSensor.rotate(cameraPoint))
            val east = enu.x * cos(angle) + enu.y * sin(angle)
            val north = -enu.x * sin(angle) + enu.y * cos(angle)
            val along = (east - start.eastM) * re + (north - start.northM) * rn
            val across = (east - start.eastM) * rn - (north - start.northM) * re
            val error = location.accuracyM * AndroidTactileRouteObservationSupplier.GPS_ERROR_MULTIPLIER +
                hypot(east, north) * sin(Math.toRadians((orientation.headingErrorDeg +
                    AndroidTactileRouteObservationSupplier.DECLINATION_MODEL_ERROR_DEG).toDouble())) +
                max(s.componentIqrM ?: 0.0, (if (surface.source == DepthSource.ARCORE_FULL_DEPTH)
                    AndroidTactileRouteObservationSupplier.MIN_FULL_DEPTH_ERROR_M
                    else AndroidTactileRouteObservationSupplier.MIN_RAW_DEPTH_ERROR_M).toDouble()) +
                AndroidTactileRouteObservationSupplier.TMAP_GEOMETRY_ERROR_M
            val distance = hypot(across, when { along < 0 -> along; along > length -> along - length; else -> 0.0 })
            TactileRouteSurfacePoint(s, east, north, along, across, s.pointInAnchor.dot(up) - planeHeight,
                error, distance + error <= AndroidTactileRouteObservationSupplier.TMAP_CORRIDOR_RADIUS_M)
        }
        val mask = surface.sensorMask
        val original = Raster(mask.width, mask.height, BooleanArray(mask.width * mask.height) { i ->
            mask.contains(mask.left + i % mask.width, mask.top + i / mask.width) })
        val originalComponents = original.components()
        val holes = original.holes()
        val originalThin = original.thinned()
        val originalBranching = originalThin.bits.indices.any { originalThin.bits[it] && originalThin.centerlineNeighbors(it).size > 2 }
        val byIndex = points.associateBy { it.sample.depthCellIndex }
        val depthWidth = surface.depthWidth
        val depthHeight = surface.depthHeight
        val raster = Raster(depthWidth, depthHeight, BooleanArray(depthWidth * depthHeight) { it in byIndex })
        val measuredComponents = raster.components { i,j ->
            byIndex.getValue(i).sample.componentId == byIndex.getValue(j).sample.componentId &&
                maskLine(mask, byIndex.getValue(i).sample, byIndex.getValue(j).sample) }
        val thin = raster.thinned()
        val components = measuredComponents.mapIndexed { id, indices ->
            val measured = indices.map { byIndex.getValue(it) }
            val center = indices.filter { thin.bits[it] }
            val tangents = center.flatMap { i -> thin.centerlineNeighbors(i) { a,b -> a in indices && b in indices &&
                maskLine(mask, byIndex.getValue(a).sample, byIndex.getValue(b).sample) }.filter { it > i && it in indices }.mapNotNull { j ->
                val a = byIndex.getValue(i); val b = byIndex.getValue(j)
                if (!maskLine(mask, a.sample, b.sample)) return@mapNotNull null
                val e = b.eastM - a.eastM; val n = b.northM - a.northM; val size = hypot(e, n)
                if (size == 0.0) null else TactileSurfaceTangent(i, j, e / size, n / size, axisAngle(e, n, re, rn))
            } }
            val degrees = tangents.flatMap { listOf(it.fromDepthCell, it.toDepthCell) }.groupingBy { it }.eachCount()
            val branching = originalBranching || degrees.values.any { it > 2 }
            TactileSurfaceComponent(id, measured, if (holes > 0 || branching) null else axis(measured, re, rn),
                center, tangents, branching)
        }
        val overlaps = points.mapNotNull { p ->
            val i = p.sample.depthCellIndex; val x = p.sample.depthX
            if (x + 1 >= depthWidth) return@mapNotNull null
            val quad = listOf(i, i + 1, i + depthWidth + 1, i + depthWidth).map { byIndex[it] ?: return@mapNotNull null }
            if (quad.map { it.sample.componentId }.distinct().size != 1 || !wholeMaskPatch(mask, quad)) return@mapNotNull null
            val polygon = quad.map { it.eastM to it.northM }
            val interval = intersectionInterval(start.eastM to start.northM, end.eastM to end.northM, polygon)
                ?: return@mapNotNull null
            TactileSurfaceRouteOverlap(quad.map { it.sample.depthCellIndex }, interval.first * length, interval.second * length)
        }
        return TactileSurfaceRouteEvaluation(TactileSurfaceRouteEvidence(surface, route.routeId, route.routeRevision!!,
            route.segmentIndex, plane, originalComponents.size, holes, originalBranching, components, overlaps, contact), null)
    }

    /** Exact full boundary equivalence only; no epsilon, point-set, or height-only merging. */
    private fun sameCapturedPolygon(a: CapturedHorizontalPlane, b: CapturedHorizontalPlane): Boolean {
        if (a.referenceId != b.referenceId || a.captureTimestampMs != b.captureTimestampMs ||
            a.polygonInAnchor.size != b.polygonInAnchor.size) return false
        val left = a.polygonInAnchor; val right = b.polygonInAnchor; val size = left.size
        return right.indices.any { start -> left.indices.all { i -> left[i] == right[(start + i) % size] } ||
            left.indices.all { i -> left[i] == right[(start - i + size) % size] } }
    }

    private fun axis(points: List<TactileRouteSurfacePoint>, re: Double, rn: Double): TactileSurfaceAxis? {
        if (points.size < 2) return null
        val e = points.map { it.eastM }.average(); val n = points.map { it.northM }.average()
        val xx = points.sumOf { (it.eastM-e).pow(2) } / points.size
        val yy = points.sumOf { (it.northM-n).pow(2) } / points.size
        val xy = points.sumOf { (it.eastM-e)*(it.northM-n) } / points.size
        val discriminant = hypot(xx-yy, 2*xy)
        if (discriminant == 0.0 || !discriminant.isFinite()) return null
        val theta = .5*atan2(2*xy, xx-yy)
        return TactileSurfaceAxis(cos(theta), sin(theta), axisAngle(cos(theta), sin(theta), re, rn),
            (xx+yy+discriminant)/2, (xx+yy-discriminant)/2)
    }
    private fun axisAngle(e: Double, n: Double, re: Double, rn: Double) =
        Math.toDegrees(acos((abs(e*re+n*rn)/hypot(e,n)).coerceIn(0.0,1.0)))
    private fun wholeMaskPatch(mask: BinaryImageMask, points: List<TactileRouteSurfacePoint>): Boolean {
        val x0 = floor(points.minOf { it.sample.imageX }).toInt(); val x1 = floor(points.maxOf { it.sample.imageX }).toInt()
        val y0 = floor(points.minOf { it.sample.imageY }).toInt(); val y1 = floor(points.maxOf { it.sample.imageY }).toInt()
        return (y0..y1).all { y -> (x0..x1).all { x -> mask.contains(x,y) } }
    }
    private fun maskLine(mask: BinaryImageMask, a: TactileSurfaceSample, b: TactileSurfaceSample): Boolean {
        // Conservative supercover: sparse depth centers cannot step over an original mask gap.
        val x0=floor(minOf(a.imageX,b.imageX)).toInt(); val x1=floor(maxOf(a.imageX,b.imageX)).toInt()
        val y0=floor(minOf(a.imageY,b.imageY)).toInt(); val y1=floor(maxOf(a.imageY,b.imageY)).toInt()
        return (y0..y1).all { y -> (x0..x1).all { x -> mask.contains(x,y) } }
    }
    private fun finite(v: Vec3) = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
    private fun cross(a: Vec3,b: Vec3) = Vec3(a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x)
    private fun inside(x: Double,y: Double,polygon: List<Pair<Double,Double>>): Boolean {
        var yes = false; var previous = polygon.last()
        for (p in polygon) { if ((p.second>y)!=(previous.second>y) &&
            x < (previous.first-p.first)*(y-p.second)/(previous.second-p.second)+p.first) yes = !yes
            previous = p }
        return yes
    }
    private fun intersectionInterval(a: Pair<Double,Double>,b: Pair<Double,Double>,p: List<Pair<Double,Double>>): Pair<Double,Double>? {
        val values = mutableListOf<Double>()
        if (inside(a.first,a.second,p)) values.add(0.0)
        if (inside(b.first,b.second,p)) values.add(1.0)
        val dx=b.first-a.first; val dy=b.second-a.second
        p.indices.forEach { i -> val c=p[i]; val d=p[(i+1)%p.size]; val ex=d.first-c.first; val ey=d.second-c.second
            val determinant=dx*ey-dy*ex
            if (determinant!=0.0) { val cx=c.first-a.first; val cy=c.second-a.second
                val t=(cx*ey-cy*ex)/determinant; val u=(cx*dy-cy*dx)/determinant
                if (t in 0.0..1.0 && u in 0.0..1.0) values.add(t) } }
        return if (values.isEmpty()) null else values.min() to values.max()
    }

    /** Discrete topology only; thinning never invents measured cells or fills holes. */
    private class Raster(val width: Int,val height: Int,val bits: BooleanArray) {
        fun neighbors(i: Int,diagonal: Boolean = false): List<Int> = buildList {
            val x=i%width; val y=i/width
            for (dy in -1..1) for (dx in -1..1) if ((dx!=0||dy!=0) && (diagonal||abs(dx)+abs(dy)==1) &&
                x+dx in 0 until width && y+dy in 0 until height) {
                val j=(y+dy)*width+x+dx; if (bits[j]) add(j) }
        }
        /** A cardinal corner path already joins these cells; its diagonal is a duplicate edge.
         * The measured graph also requires both real cardinal links to pass the original mask. */
        fun centerlineNeighbors(i: Int, edge: (Int,Int)->Boolean = { _,_ -> true }): List<Int> =
            neighbors(i, diagonal = true).filter { j ->
                if (!edge(i,j)) return@filter false
                val dx=j%width-i%width; val dy=j/width-i/width
                if (dx==0 || dy==0) return@filter true
                val horizontal=i+dx; val vertical=i+dy*width
                !listOf(horizontal,vertical).any { corner ->
                    bits[corner] && edge(i,corner) && edge(corner,j) }
            }
        fun components(edge: (Int,Int)->Boolean = { _,_ -> true }): List<Set<Int>> {
            val remaining=bits.indices.filter { bits[it] }.toMutableSet(); val out=mutableListOf<Set<Int>>()
            while (remaining.isNotEmpty()) { val seed=remaining.first(); remaining.remove(seed)
                val queue=ArrayDeque<Int>(); queue.add(seed); val component=linkedSetOf<Int>()
                while(queue.isNotEmpty()) { val i=queue.removeFirst(); component.add(i)
                    neighbors(i).forEach { if(edge(i,it) && remaining.remove(it)) queue.add(it) } }
                out.add(component) }
            return out
        }
        fun holes(): Int = Raster(width,height,BooleanArray(bits.size){ !bits[it] }).components().count { component ->
            component.none { it%width==0 || it%width==width-1 || it/width==0 || it/width==height-1 } }
        fun thinned(): Raster {
            val out=Raster(width,height,bits.clone()); var changed: Boolean
            do { changed=false
                for (phase in 0..1) {
                    val remove=mutableListOf<Int>()
                    for (i in out.bits.indices) if(out.bits[i]) {
                        val x=i%width; val y=i/width
                        val offsets=arrayOf(0 to -1,1 to -1,1 to 0,1 to 1,0 to 1,-1 to 1,-1 to 0,-1 to -1)
                        val n=offsets.map { (dx,dy) -> x+dx in 0 until width && y+dy in 0 until height && out.bits[(y+dy)*width+x+dx] }
                        val count=n.count{it}; val transitions=n.indices.count{!n[it]&&n[(it+1)%8]}
                        val clear=if(phase==0) !(n[0]&&n[2]&&n[4])&&!(n[2]&&n[4]&&n[6])
                                  else !(n[0]&&n[2]&&n[6])&&!(n[0]&&n[4]&&n[6])
                        if(count in 2..6 && transitions==1 && clear) remove.add(i)
                    }
                    if(remove.isNotEmpty()) changed=true
                    remove.forEach { out.bits[it]=false }
                }
            } while(changed)
            return out
        }
    }
}
