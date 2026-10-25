package kr.co.hanium.dreamup.walksafe.depth

import java.util.Collections
import kr.co.hanium.dreamup.walksafe.navigation.FrozenImageToDepthTransform
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Camera and body both use right-handed +X right, +Y up, +Z back (forward is -Z). */
enum class BodySpaceAxes { RIGHT_UP_BACK }
enum class BodyMeasurementClock { UNIX_EPOCH_NANOSECONDS, ELAPSED_REALTIME_NANOSECONDS }
data class BodySpaceOwner(val walkEpoch: Long, val mountIdentity: String, val mountGeneration: Long)
data class BodyMeasurementProvenance(val method: String?, val clock: BodyMeasurementClock?,
    val measuredAtNs: Long?, val axes: BodySpaceAxes?, val bodyOriginDescription: String?, val errorBasis: String?)

/**
 * Optional measured caller input, not a claim that strings prove physical measurement accuracy.
 * Quaternion is [x,y,z,w], translation is metres, pBody = R * pCamera + translation.
 * Body min/max are measured axis-aligned occupied bounds in body coordinates. Front ground is
 * [minX,maxX,minForward,maxForward,planeY] in metres, a registered expected model, not observed
 * physical floor/feet. Errors are REQUIRED explicit bounds, with no default: translation metres,
 * rotation radians, occupied-shape/ground-model Euclidean Hausdorff upper bounds in metres
 * (not independent per-axis bounds). Their basis must include mounting
 * and body/mount motion throughout the registration's usable lifetime, not only initial fitting.
 * Every value uses the same body origin selected and recorded at measurement. Neither a foot
 * location nor groundY=0 is assumed; bodyOriginDescription must identify that measured reference.
 */
data class MeasuredBodySpaceInput(
    val provenance: BodyMeasurementProvenance?,
    val cameraToBodyQuaternion: DoubleArray?, val cameraToBodyTranslationM: DoubleArray?,
    val occupiedBodyMinM: DoubleArray?, val occupiedBodyMaxM: DoubleArray?,
    val expectedFrontGroundM: DoubleArray?,
    val translationErrorM: Double?, val rotationErrorRad: Double?,
    val occupiedBoundsErrorM: Double?, val groundModelErrorM: Double?,
)

/** Original runtime identity and elapsed lease. A new capture/lease must get a new lease identity. */
data class MeasuredBodyCaptureKey(
    val owner: BodySpaceOwner, val leaseIdentity: String,
    val frameTimestampNs: Long, val cameraImageTimestampNs: Long, val poseReferenceId: Long,
    val capturedAtElapsedNs: Long, val validUntilElapsedNs: Long, val geometryVersion: Long,
)
data class BodySpacePoint(val x: Double, val y: Double, val z: Double)
internal data class BodyExpectedGround(val minX: Double, val maxX: Double, val minForward: Double,
    val maxForward: Double, val planeY: Double)
data class BodyPointDistanceInterval(val nominalM: Double, val lowerM: Double, val upperM: Double)
data class BodySemanticPixel(val x: Int, val y: Int, val label: Int, val confidence: Int,
    val labelTimestampNs: Long, val confidenceTimestampNs: Long)

/**
 * Metric position error from depth/K/registration approximation; axial quantization error is an
 * explicit absolute Z error bound in metres. A millimetre grid does not prove nearest rounding.
 */
data class BodyDepthObservationError(val metricPositionErrorM: Double?,
    val axialQuantizationErrorBoundM: Double?, val basis: String?)

/** Point-only distances. No whole-object extent, collision, CONNECTED or walkability authority. */
class MeasuredBodyPointClearance internal constructor(
    val captureKey: MeasuredBodyCaptureKey,
    val source: DepthSource, val depthX: Int, val depthY: Int, val depthTimestampNs: Long,
    val cameraPoint: BodySpacePoint, val bodyPoint: BodySpacePoint,
    val bodyPointClearance: BodyPointDistanceInterval,
    val expectedGroundRegionPointClearance: BodyPointDistanceInterval,
    val heightAboveExpectedGroundM: Double, val expectedGroundHeightErrorM: Double,
    val semanticPixel: BodySemanticPixel?, val depthPixelConfidence: Int?,
)

/** Owner-bound measured model. Every null return means UNKNOWN; no guessed physical defaults. */
class MeasuredBodySpace {
    private var owner: BodySpaceOwner? = null
    private var registration: Registration? = null
    private var registrationReferenceId: Long? = null
    private var activeCapture: Capture? = null

    class Registration internal constructor(
        internal val producer: MeasuredBodySpace, val owner: BodySpaceOwner,
        val provenance: BodyMeasurementProvenance,
        private val rotation: DoubleArray, internal val translation: BodySpacePoint,
        internal val min: BodySpacePoint, internal val max: BodySpacePoint,
        internal val front: BodyExpectedGround, internal val translationError: Double,
        internal val rotationError: Double, internal val bodyError: Double, internal val groundError: Double,
    ) {
        internal fun toBody(camera: BodySpacePoint) = rotation.rotate(camera) + translation
        internal fun toCamera(body: BodySpacePoint) = rotation.inverseRotate(body - translation)
    }

    /** Copied expected geometry in the original captured anchor; no native object is retained. */
    class Capture internal constructor(
        internal val registration: Registration, val key: MeasuredBodyCaptureKey,
        internal val projection: CameraImageProjection,
        internal val pose: CameraPoseEvidence,
        internal val mapper: FrozenImageToDepthTransform,
        private val raw: DepthImage16?, private val full: DepthImage16?,
        private val rawConfidence: ByteArray?,
        internal val rawTimestampNs: Long?, internal val fullTimestampNs: Long?,
        internal val semantic: SemanticFrameSnapshot?,
        occupiedCornersInAnchor: List<BodySpacePoint>, expectedGroundCornersInAnchor: List<BodySpacePoint>,
    ) {
        val occupiedCornersInAnchor: List<BodySpacePoint> = Collections.unmodifiableList(ArrayList(occupiedCornersInAnchor))
        val expectedGroundCornersInAnchor: List<BodySpacePoint> = Collections.unmodifiableList(ArrayList(expectedGroundCornersInAnchor))
        private fun depth(source: DepthSource) = when (source) {
            DepthSource.ARCORE_RAW_DEPTH -> raw
            DepthSource.ARCORE_FULL_DEPTH -> full
            else -> null
        }
        internal fun dimensions(source: DepthSource) = depth(source)?.let { it.width to it.height }
        internal fun axialDepth(source: DepthSource, x: Int, y: Int) = depth(source)?.depthMetersAt(x,y)
        internal fun confidence(source: DepthSource, x: Int, y: Int): Int? =
            raw?.takeIf { source == DepthSource.ARCORE_RAW_DEPTH }?.let { rawConfidence?.get(y*it.width+x)?.toInt()?.and(0xff) }
    }

    /** Call at walk/mount replacement boundaries, before further captures. */
    @Synchronized fun bindOwner(current: BodySpaceOwner?) {
        if (owner != current || current == null) { registration = null; registrationReferenceId = null; activeCapture = null }
        owner = current?.takeIf { it.walkEpoch > 0L && it.mountIdentity.isNotBlank() && it.mountGeneration > 0L }
    }

    @Synchronized fun retire() { owner = null; registration = null; registrationReferenceId = null; activeCapture = null }

    @Synchronized fun register(input: MeasuredBodySpaceInput?): Registration? {
        registration = null; registrationReferenceId = null; activeCapture = null
        val current = owner ?: return null
        val provenance = input?.provenance ?: return null
        if (provenance.method.isNullOrBlank() || provenance.clock == null || provenance.axes == null ||
            provenance.measuredAtNs == null || provenance.measuredAtNs <= 0L || provenance.bodyOriginDescription.isNullOrBlank() ||
            provenance.errorBasis.isNullOrBlank()) return null
        val quaternion = input.cameraToBodyQuaternion?.copyOf()?.takeIf { it.size == 4 && it.all(Double::isFinite) } ?: return null
        val scale = quaternion.maxOf { abs(it) }.takeIf { it > 0.0 } ?: return null
        val normalized = quaternion.map { it / scale }
        val norm = sqrt(normalized.sumOf { it * it })
        val (x, y, z, w) = normalized.map { it / norm }
        val rotation = doubleArrayOf(1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w),
            2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w), 2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y))
        val translation = input.cameraToBodyTranslationM.point() ?: return null
        val min = input.occupiedBodyMinM.point() ?: return null
        val max = input.occupiedBodyMaxM.point() ?: return null
        if (min.x >= max.x || min.y >= max.y || min.z >= max.z) return null
        val front = input.expectedFrontGroundM?.copyOf()?.takeIf { it.size == 5 && it.all(Double::isFinite) } ?: return null
        if (front[0] >= front[1] || front[2] < 0.0 || front[2] >= front[3]) return null
        val translationError = input.translationErrorM.bound() ?: return null
        val rotationError = input.rotationErrorRad.bound()?.takeIf { it <= Math.PI } ?: return null
        val bodyError = input.occupiedBoundsErrorM.bound() ?: return null
        val groundError = input.groundModelErrorM.bound() ?: return null
        return Registration(this, current, provenance.copy(), rotation, translation, min, max,
            BodyExpectedGround(front[0],front[1],front[2],front[3],front[4]),
            translationError, rotationError, bodyError, groundError).also { registration = it }
    }

    /**
     * Main must pass its original current capture/lease, never retag at completion. Configuration
     * replacement calls bindOwner/retire; a new capture attempt invalidates the prior capture.
     * Source arrays are copied here. Measurement clocks remain metadata, never compared to elapsed.
     */
    @Synchronized fun capture(model: Registration?, key: MeasuredBodyCaptureKey?, snapshot: DepthFrameSnapshot?,
        mapper: FrozenImageToDepthTransform?, nowElapsedNs: Long): Capture? {
        activeCapture = null
        if (model == null || model !== registration || model.producer !== this || model.owner != owner ||
            key == null || key.owner != owner || key.leaseIdentity.isBlank() || key.frameTimestampNs <= 0L ||
            key.cameraImageTimestampNs <= 0L || key.poseReferenceId <= 0L || key.capturedAtElapsedNs <= 0L ||
            key.geometryVersion < 0L ||
            key.validUntilElapsedNs < key.capturedAtElapsedNs || nowElapsedNs !in key.capturedAtElapsedNs..key.validUntilElapsedNs ||
            snapshot == null || snapshot.frameTimestampNs != key.frameTimestampNs ||
            snapshot.cameraImageTimestampNs != key.cameraImageTimestampNs || mapper?.frameId != key.frameTimestampNs) return null
        // Only this explicitly same clock can be compared; UNIX epoch metadata is never mixed in.
        if (model.provenance.clock == BodyMeasurementClock.ELAPSED_REALTIME_NANOSECONDS &&
            model.provenance.measuredAtNs!! > key.capturedAtElapsedNs) return null
        if (registrationReferenceId != null && registrationReferenceId != key.poseReferenceId) {
            registration = null; registrationReferenceId = null
            return null
        }
        val pose = snapshot.cameraPoseEvidence?.takeIf { it.referenceId == key.poseReferenceId &&
            it.timestampMs == key.frameTimestampNs / 1_000_000L && it.objectCenterInAnchor(Point2(.5f,.5f),1f) != null } ?: return null
        val projection = pose.imageProjection ?: return null
        val semantic = snapshot.semanticFrame
        if (semantic != null && (semantic.frameTimestampNs != key.frameTimestampNs ||
            semantic.cameraImageTimestampNs != key.cameraImageTimestampNs || semantic.cameraPoseReferenceId != key.poseReferenceId)) return null
        fun anchor(point: BodySpacePoint): BodySpacePoint {
            val camera = model.toCamera(point)
            return BodySpacePoint(pose.positionX + projection.rightX*camera.x + projection.upX*camera.y - pose.forwardX*camera.z,
                pose.positionY + projection.rightY*camera.x + projection.upY*camera.y - pose.forwardY*camera.z,
                pose.positionZ + projection.rightZ*camera.x + projection.upZ*camera.y - pose.forwardZ*camera.z)
        }
        val corners = listOf(model.min.x,model.max.x).flatMap { x -> listOf(model.min.y,model.max.y).flatMap { y ->
            listOf(model.min.z,model.max.z).map { z -> anchor(BodySpacePoint(x,y,z)) } } }
        val front = model.front
        val ground = listOf(front.minX,front.maxX).flatMap { x -> listOf(front.minForward,front.maxForward).map { f -> anchor(BodySpacePoint(x,front.planeY,-f)) } }
        if ((corners + ground).any { !it.finite() }) return null
        val raw = snapshot.rawDepth?.takeIf { snapshot.hasMetricRawDepth }?.ownedCopy()
        val full = snapshot.fullDepth?.takeIf { snapshot.hasFreshFullDepth }?.ownedCopy()
        val copiedPose = pose.copy(imageProjection = projection.copy(),
            horizontalPlaneCandidates = Collections.unmodifiableList(pose.horizontalPlaneCandidates.map { plane ->
                plane.copy(polygonInAnchor = Collections.unmodifiableList(ArrayList(plane.polygonInAnchor)))
            }))
        return Capture(model,key,projection.copy(),copiedPose,mapper,raw,full,snapshot.rawConfidence?.values?.copyOf(),
            snapshot.rawDepthTimestampNs,snapshot.fullDepthTimestampNs,semantic,corners,ground).also {
                registrationReferenceId = key.poseReferenceId
                activeCapture = it
            }
    }

    /**
     * Original depth cell Z and Kdepth = D * H(cpu pixels -> depth UV) * Kcpu, not a supplied 3D
     * point or a width-ratio calibration. Cell-center inverse projection checks the original CPU
     * crop; H is the existing frozen approximation, not proof of physical registration accuracy.
     */
    @Synchronized fun pointClearance(capture: Capture?, currentKey: MeasuredBodyCaptureKey?,
        imagePoint: Point2?, source: DepthSource, error: BodyDepthObservationError?, nowElapsedNs: Long): MeasuredBodyPointClearance? {
        if (capture == null || capture !== activeCapture || capture.registration !== registration || owner != capture.key.owner ||
            currentKey != capture.key || nowElapsedNs !in capture.key.capturedAtElapsedNs..capture.key.validUntilElapsedNs) return null
        val observedError = error ?: return null
        val positionError = observedError.metricPositionErrorM.bound() ?: return null
        val quantizationError = observedError.axialQuantizationErrorBoundM.bound() ?: return null
        if (observedError.basis.isNullOrBlank()) return null
        val point = imagePoint ?: return null
        val uv = capture.mapper.map(point) ?: return null
        val (depthWidth, depthHeight) = capture.dimensions(source) ?: return null
        val dx = floor(uv.x * depthWidth).toInt()
        val dy = floor(uv.y * depthHeight).toInt()
        val axial = capture.axialDepth(source,dx,dy)?.toDouble() ?: return null
        val projection = capture.projection
        val h = capture.mapper.imagePixelsToDepthUvMatrix(projection.imageWidth,projection.imageHeight) ?: return null
        val determinant = h[0]*h[4]-h[1]*h[3]
        if (!determinant.isFinite() || determinant == 0.0) return null
        val u = (dx+.5)/depthWidth
        val v = (dy+.5)/depthHeight
        val cpuX = ((u-h[2])*h[4]-(v-h[5])*h[1])/determinant
        val cpuY = ((v-h[5])*h[0]-(u-h[2])*h[3])/determinant
        if (!cpuX.isFinite() || !cpuY.isFinite() || cpuX !in 0.0..<projection.imageWidth.toDouble() ||
            cpuY !in 0.0..<projection.imageHeight.toDouble()) return null
        // Algebraically invert Kdepth at this actual cell centre through H^-1 and Kcpu^-1.
        val ray = BodySpacePoint((cpuX-projection.cx)/projection.fx,-(cpuY-projection.cy)/projection.fy,-1.0)
        val cameraPoint = ray * axial
        val model = capture.registration
        val bodyPoint = model.toBody(cameraPoint)
        if (!bodyPoint.finite()) return null
        val bodyDistance = BodySpacePoint(gap(bodyPoint.x,model.min.x,model.max.x),gap(bodyPoint.y,model.min.y,model.max.y),
            gap(bodyPoint.z,model.min.z,model.max.z)).norm()
        val front = model.front
        val groundDistance = sqrt(gap(bodyPoint.x,front.minX,front.maxX).let { it*it } +
            gap(-bodyPoint.z,front.minForward,front.maxForward).let { it*it })
        // Metres: observation + ray-length * declared axial quantization error + mount translation + rotational
        // displacement (2*|pCam|*sin(angle/2)), then the relevant measured boundary/model bound.
        val commonError = positionError + ray.norm()*quantizationError + model.translationError +
            2*cameraPoint.norm()*sin(model.rotationError/2)
        val bodyError = commonError + model.bodyError
        val groundError = commonError + model.groundError
        val height = bodyPoint.y-front.planeY
        if (!listOf(bodyDistance,groundDistance,bodyError,groundError,height,bodyDistance+bodyError,
                groundDistance+groundError).all(Double::isFinite)) return null
        val semanticPixel = capture.semantic?.let {
            val sx = floor(u*it.labels.width).toInt(); val sy = floor(v*it.labels.height).toInt()
            BodySemanticPixel(sx,sy,it.labels.valueAt(sx,sy),it.confidence.valueAt(sx,sy),
                it.labels.timestampNs,it.confidence.timestampNs)
        }
        return MeasuredBodyPointClearance(capture.key,source,dx,dy,
            (if (source == DepthSource.ARCORE_RAW_DEPTH) capture.rawTimestampNs else capture.fullTimestampNs) ?: return null,
            cameraPoint,bodyPoint,interval(bodyDistance,bodyError),interval(groundDistance,groundError),
            height,groundError,semanticPixel,capture.confidence(source,dx,dy))
    }
}

private fun Double?.bound(): Double? = this?.takeIf { it.isFinite() && it >= 0.0 }
private fun DoubleArray?.point(): BodySpacePoint? = this?.copyOf()?.takeIf { it.size == 3 && it.all(Double::isFinite) }
    ?.let { BodySpacePoint(it[0],it[1],it[2]) }
private fun BodySpacePoint.finite() = listOf(x,y,z).all(Double::isFinite)
private fun BodySpacePoint.norm() = sqrt(x*x+y*y+z*z)
private operator fun BodySpacePoint.plus(other: BodySpacePoint) = BodySpacePoint(x+other.x,y+other.y,z+other.z)
private operator fun BodySpacePoint.minus(other: BodySpacePoint) = BodySpacePoint(x-other.x,y-other.y,z-other.z)
private operator fun BodySpacePoint.times(scale: Double) = BodySpacePoint(x*scale,y*scale,z*scale)
private fun DoubleArray.rotate(p: BodySpacePoint) = BodySpacePoint(this[0]*p.x+this[1]*p.y+this[2]*p.z,
    this[3]*p.x+this[4]*p.y+this[5]*p.z,this[6]*p.x+this[7]*p.y+this[8]*p.z)
private fun DoubleArray.inverseRotate(p: BodySpacePoint) = BodySpacePoint(this[0]*p.x+this[3]*p.y+this[6]*p.z,
    this[1]*p.x+this[4]*p.y+this[7]*p.z,this[2]*p.x+this[5]*p.y+this[8]*p.z)
private fun DepthImage16.ownedCopy() = DepthImage16(width,height,millimeters.copyOf())
private fun gap(value: Double,min: Double,max: Double) = max(0.0,max(min-value,value-max))
private fun interval(distance: Double,error: Double) = BodyPointDistanceInterval(distance,max(0.0,distance-error),distance+error)
