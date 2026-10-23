package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import kr.co.hanium.dreamup.walksafe.depth.Vec3
import kr.co.hanium.dreamup.walksafe.inference.WalkMateClassPolicy
import kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Row-major rotation from one right-handed coordinate frame into another. */
data class RotationMatrix3(
    val m00: Float,
    val m01: Float,
    val m02: Float,
    val m10: Float,
    val m11: Float,
    val m12: Float,
    val m20: Float,
    val m21: Float,
    val m22: Float,
) {
    fun rotate(vector: Vec3): Vec3 = Vec3(
        x = m00 * vector.x + m01 * vector.y + m02 * vector.z,
        y = m10 * vector.x + m11 * vector.y + m12 * vector.z,
        z = m20 * vector.x + m21 * vector.y + m22 * vector.z,
    )

    fun isFinite(): Boolean = listOf(m00, m01, m02, m10, m11, m12, m20, m21, m22).all(Float::isFinite)

    fun isOrthonormal(tolerance: Float = 0.05f): Boolean {
        if (!isFinite()) return false
        val x = Vec3(m00, m10, m20)
        val y = Vec3(m01, m11, m21)
        val z = Vec3(m02, m12, m22)
        val determinant = x.dot(cross(y, z))
        return abs(x.norm() - 1f) <= tolerance &&
            abs(y.norm() - 1f) <= tolerance &&
            abs(z.norm() - 1f) <= tolerance &&
            abs(x.dot(y)) <= tolerance &&
            abs(x.dot(z)) <= tolerance &&
            abs(y.dot(z)) <= tolerance &&
            abs(determinant - 1f) <= tolerance
    }

    companion object {
        fun fromRowMajor(values: FloatArray): RotationMatrix3? {
            if (values.size != 9 || values.any { !it.isFinite() }) return null
            return RotationMatrix3(
                values[0], values[1], values[2],
                values[3], values[4], values[5],
                values[6], values[7], values[8],
            )
        }

        fun fromColumns(x: Vec3, y: Vec3, z: Vec3): RotationMatrix3? {
            return RotationMatrix3(
                x.x, y.x, z.x,
                x.y, y.y, z.y,
                x.z, y.z, z.z,
            ).takeIf(RotationMatrix3::isFinite)
        }
    }
}

enum class EarthOrientationAccuracy {
    UNRELIABLE,
    LOW,
    MEDIUM,
    HIGH,
}

/** Android sensor coordinates transformed into magnetic East-North-Up coordinates. */
data class DeviceEarthOrientation(
    val deviceToMagneticEnu: RotationMatrix3,
    val observedAtElapsedRealtimeMs: Long,
    val headingErrorDeg: Float,
    val accuracy: EarthOrientationAccuracy,
)

data class TactileCameraIntrinsics(
    val widthPx: Int,
    val heightPx: Int,
    val focalLengthXPx: Float,
    val focalLengthYPx: Float,
    val principalPointXPx: Float,
    val principalPointYPx: Float,
)

/** Physical ARCore camera axes transformed into Android sensor axes for one current frame. */
data class TactileCameraFrame(
    val frameId: Long,
    val cameraToAndroidSensor: RotationMatrix3,
    val intrinsics: TactileCameraIntrinsics,
    val observedAtElapsedRealtimeMs: Long,
    val tracking: Boolean,
)

data class TactileProjectionContext(
    val captureFrameId: Long?,
    val expectedRouteId: String?,
    val routeProjection: ActiveRouteProjection?,
    val trustedLocation: TrustedLocation?,
    val orientation: DeviceEarthOrientation?,
    val magneticDeclinationDeg: Float?,
    val cameraFrame: TactileCameraFrame?,
    val detectionAgeMs: Long?,
    val nowElapsedRealtimeMs: Long,
)

/** Immutable CPU-image to depth-texture mapping captured while its ARCore Frame is current. */
class FrozenImageToDepthTransform private constructor(
    val frameId: Long,
    private val topLeft: Point2,
    private val topRight: Point2,
    private val bottomLeft: Point2,
    private val bottomRight: Point2,
) {
    /** Affine pixel-edge mapping, including image portions outside the depth crop. */
    fun imagePixelsToDepthUvMatrix(imageWidth: Int, imageHeight: Int): DoubleArray? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        return doubleArrayOf(
            (topRight.x.toDouble() - topLeft.x) / imageWidth,
            (bottomLeft.x.toDouble() - topLeft.x) / imageHeight,
            topLeft.x.toDouble(),
            (topRight.y.toDouble() - topLeft.y) / imageWidth,
            (bottomLeft.y.toDouble() - topLeft.y) / imageHeight,
            topLeft.y.toDouble(),
            0.0, 0.0, 1.0,
        )
    }

    fun map(imagePoint: Point2): Point2? {
        if (!imagePoint.isNormalized()) {
            return null
        }
        return interpolate(imagePoint).takeIf { it.isNormalized() }
    }

    /** Inverse of the validated capture affine transform, including rotated/cropped textures. */
    fun unmap(depthPoint: Point2): Point2? {
        if (!depthPoint.isNormalized()) return null
        val hx = topRight.x.toDouble() - topLeft.x
        val hy = topRight.y.toDouble() - topLeft.y
        val vx = bottomLeft.x.toDouble() - topLeft.x
        val vy = bottomLeft.y.toDouble() - topLeft.y
        val determinant = hx * vy - hy * vx
        if (!determinant.isFinite() || determinant == 0.0) return null
        val x = depthPoint.x.toDouble() - topLeft.x
        val y = depthPoint.y.toDouble() - topLeft.y
        return Point2(((x * vy - y * vx) / determinant).toFloat(),
            ((y * hx - x * hy) / determinant).toFloat()).takeIf { it.isNormalized() }
    }

    private fun interpolate(imagePoint: Point2): Point2 {
        val top = lerp(topLeft, topRight, imagePoint.x)
        val bottom = lerp(bottomLeft, bottomRight, imagePoint.x)
        return lerp(top, bottom, imagePoint.y)
    }

    companion object {
        fun create(frameId: Long, mappedCorners: List<Point2>, mappedCenter: Point2): FrozenImageToDepthTransform? {
            if (frameId <= 0L || mappedCorners.size != 4 ||
                mappedCorners.any { !it.x.isFinite() || !it.y.isFinite() } ||
                !mappedCenter.x.isFinite() || !mappedCenter.y.isFinite()
            ) {
                return null
            }
            // CPU and depth images may have different crops/aspect ratios. Their complete image
            // corners need not lie inside the depth texture; bounds apply to each sampled point.
            val horizontal = Point2(mappedCorners[1].x - mappedCorners[0].x, mappedCorners[1].y - mappedCorners[0].y)
            val vertical = Point2(mappedCorners[2].x - mappedCorners[0].x, mappedCorners[2].y - mappedCorners[0].y)
            val determinant = horizontal.x * vertical.y - horizontal.y * vertical.x
            if (!determinant.isFinite() || determinant == 0f ||
                abs(mappedCorners[2].x + horizontal.x - mappedCorners[3].x) > MAX_CENTER_ERROR ||
                abs(mappedCorners[2].y + horizontal.y - mappedCorners[3].y) > MAX_CENTER_ERROR
            ) return null
            val transform = FrozenImageToDepthTransform(
                frameId = frameId,
                topLeft = mappedCorners[0],
                topRight = mappedCorners[1],
                bottomLeft = mappedCorners[2],
                bottomRight = mappedCorners[3],
            )
            val interpolatedCenter = transform.interpolate(Point2(0.5f, 0.5f))
            return transform.takeIf {
                abs(interpolatedCenter.x - mappedCenter.x) <= MAX_CENTER_ERROR &&
                    abs(interpolatedCenter.y - mappedCenter.y) <= MAX_CENTER_ERROR
            }
        }

        private const val MAX_CENTER_ERROR = 0.002f
    }
}

/**
 * Developer-facing reason an output yielded no usable route observation (S12-30).
 * It is never spoken; the user-facing result remains the silent TMAP fallback.
 */
enum class TactileObservationRejection {
    CAPTURE_CONTEXT_UNAVAILABLE,
    SUPPLIER_UNAVAILABLE,
    NOT_TACTILE_ROUTE_CLASS,
    FRAME_MISMATCH,
    ROUTE_MISMATCH,
    ROUTE_GEOMETRY_INVALID,
    LOCATION_UNTRUSTED,
    ORIENTATION_UNTRUSTED,
    DECLINATION_UNAVAILABLE,
    CAMERA_FRAME_UNTRUSTED,
    DETECTION_STALE,
    DEPTH_INSUFFICIENT,
    CONTACT_UNAVAILABLE,
    POSE_UNSUPPORTED,
    PROJECTION_OUT_OF_RANGE,
    OUTSIDE_TMAP_CORRIDOR,
    DAMAGE_AREA_OVERLAP,
    BLOCKER_UNPROJECTABLE,
    UNSPECIFIED,
}

data class TactileObservationEvaluation(
    val observation: TactileRouteObservation?,
    /** Null exactly when [observation] is present. */
    val rejection: TactileObservationRejection?,
)

fun interface TactileRouteObservationSupplier {
    fun observationFor(output: TrackedObjectDepth, context: TactileProjectionContext): TactileRouteObservation?

    /** Suppliers without their own diagnostics report a missing observation as UNSPECIFIED. */
    fun evaluateObservation(output: TrackedObjectDepth, context: TactileProjectionContext): TactileObservationEvaluation {
        val observation = observationFor(output, context)
        return TactileObservationEvaluation(
            observation = observation,
            rejection = if (observation == null) TactileObservationRejection.UNSPECIFIED else null,
        )
    }
}

/**
 * Estimates an observation association using same-frame contact-neighborhood depth and camera pose.
 * This does not prove a tactile centerline or a walkable connection.
 * GPS is only the geodesic origin; ARCore's session-local translation is never treated as GPS.
 */
class AndroidTactileRouteObservationSupplier : TactileRouteObservationSupplier {
    override fun observationFor(
        output: TrackedObjectDepth,
        context: TactileProjectionContext,
    ): TactileRouteObservation? = evaluateObservation(output, context).observation

    /** Same gates as before S12-30; each former `null` now names the first gate that failed. */
    override fun evaluateObservation(
        output: TrackedObjectDepth,
        context: TactileProjectionContext,
    ): TactileObservationEvaluation {
        fun reject(reason: TactileObservationRejection) = TactileObservationEvaluation(null, reason)
        if (!output.isTactileRouteClass()) return reject(TactileObservationRejection.NOT_TACTILE_ROUTE_CLASS)
        val captureFrameId = context.captureFrameId ?: return reject(TactileObservationRejection.FRAME_MISMATCH)
        if (output.frameId != captureFrameId) return reject(TactileObservationRejection.FRAME_MISMATCH)
        val routeId = context.expectedRouteId?.takeIf(String::isNotBlank)
            ?: return reject(TactileObservationRejection.ROUTE_MISMATCH)
        val route = context.routeProjection?.takeIf { it.routeId == routeId }
            ?: return reject(TactileObservationRejection.ROUTE_MISMATCH)
        if (
            route.segmentIndex < 0 ||
            !route.distanceToRouteM.isFinite() ||
            route.distanceToRouteM < 0.0 ||
            !route.bearingDeg.isFinite() ||
            haversineMeters(
                route.segmentStart.latitude,
                route.segmentStart.longitude,
                route.segmentEnd.latitude,
                route.segmentEnd.longitude,
            ) < MIN_ROUTE_SEGMENT_LENGTH_M
        ) {
            return reject(TactileObservationRejection.ROUTE_GEOMETRY_INVALID)
        }

        val location = context.trustedLocation ?: return reject(TactileObservationRejection.LOCATION_UNTRUSTED)
        val locationAgeMs = context.nowElapsedRealtimeMs - location.elapsedRealtimeMs
        if (
            locationAgeMs !in 0L..MAX_LOCATION_AGE_MS ||
            !location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
            !location.longitude.isFinite() || location.longitude !in -180.0..180.0 ||
            !location.accuracyM.isFinite() ||
            location.accuracyM !in 0f..MAX_GPS_ACCURACY_M
        ) {
            return reject(TactileObservationRejection.LOCATION_UNTRUSTED)
        }

        val orientation = context.orientation ?: return reject(TactileObservationRejection.ORIENTATION_UNTRUSTED)
        val orientationAgeMs = context.nowElapsedRealtimeMs - orientation.observedAtElapsedRealtimeMs
        if (
            orientationAgeMs !in 0L..MAX_ORIENTATION_AGE_MS ||
            orientation.accuracy < EarthOrientationAccuracy.MEDIUM ||
            !orientation.headingErrorDeg.isFinite() ||
            orientation.headingErrorDeg !in 0f..MAX_HEADING_ERROR_DEG ||
            !orientation.deviceToMagneticEnu.isOrthonormal()
        ) {
            return reject(TactileObservationRejection.ORIENTATION_UNTRUSTED)
        }
        val declinationDeg = context.magneticDeclinationDeg
            ?.takeIf { it.isFinite() && abs(it) <= MAX_DECLINATION_DEG }
            ?: return reject(TactileObservationRejection.DECLINATION_UNAVAILABLE)

        val frame = context.cameraFrame ?: return reject(TactileObservationRejection.CAMERA_FRAME_UNTRUSTED)
        if (frame.frameId != captureFrameId) return reject(TactileObservationRejection.FRAME_MISMATCH)
        val frameAgeMs = context.nowElapsedRealtimeMs - frame.observedAtElapsedRealtimeMs
        if (!frame.tracking || frameAgeMs !in 0L..MAX_CAMERA_FRAME_AGE_MS || !frame.cameraToAndroidSensor.isOrthonormal()) {
            return reject(TactileObservationRejection.CAMERA_FRAME_UNTRUSTED)
        }
        val intrinsics = frame.intrinsics.takeIf { it.isUsable() }
            ?: return reject(TactileObservationRejection.CAMERA_FRAME_UNTRUSTED)
        val detectionAgeMs = context.detectionAgeMs ?: return reject(TactileObservationRejection.DETECTION_STALE)
        if (detectionAgeMs !in 0L..MAX_DETECTION_AGE_MS) return reject(TactileObservationRejection.DETECTION_STALE)

        val contactDepth = output.contactDepth ?: return reject(TactileObservationRejection.DEPTH_INSUFFICIENT)
        val depthM = contactDepth.distanceM.takeIf { it.isFinite() && it in MIN_DEPTH_M..MAX_DEPTH_M }
            ?: return reject(TactileObservationRejection.DEPTH_INSUFFICIENT)
        if (contactDepth.validSampleCount < MIN_CONTACT_DEPTH_SAMPLES ||
            !contactDepth.spreadM.isFinite() || contactDepth.spreadM !in 0f..MAX_CONTACT_DEPTH_SPREAD_M
        ) return reject(TactileObservationRejection.DEPTH_INSUFFICIENT)
        val depthIqrM = output.depthIqrM?.takeIf { it.isFinite() && it in 0f..MAX_DEPTH_IQR_M }
            ?: return reject(TactileObservationRejection.DEPTH_INSUFFICIENT)
        if (!output.hasTrustedMetricDepth()) return reject(TactileObservationRejection.DEPTH_INSUFFICIENT)
        val contact = output.bottomContactNorm ?: return reject(TactileObservationRejection.CONTACT_UNAVAILABLE)
        val uprightContact = output.uprightBottomContactNorm
            ?: return reject(TactileObservationRejection.CONTACT_UNAVAILABLE)
        if (
            !contact.x.isFinite() || contact.x !in 0f..1f ||
            !contact.y.isFinite() || contact.y !in 0f..1f ||
            !uprightContact.x.isFinite() || uprightContact.x !in 0f..1f ||
            !uprightContact.y.isFinite() || uprightContact.y !in MIN_CONTACT_Y_NORMALIZED..1f ||
            !output.maskAreaNorm.isFinite() || output.maskAreaNorm < MIN_MASK_AREA_NORMALIZED
        ) {
            return reject(TactileObservationRejection.CONTACT_UNAVAILABLE)
        }

        val pixelX = contact.x * intrinsics.widthPx
        val pixelY = contact.y * intrinsics.heightPx
        // The bounded neighborhood supports this contact ray; it is not a walkable-path proof.
        // ARCore Camera.getPose() uses OpenGL camera axes: +X right, +Y up, -Z forward.
        val targetCamera = Vec3(
            x = (pixelX - intrinsics.principalPointXPx) / intrinsics.focalLengthXPx * depthM,
            y = -(pixelY - intrinsics.principalPointYPx) / intrinsics.focalLengthYPx * depthM,
            z = -depthM,
        )
        val cameraForwardSensor = frame.cameraToAndroidSensor.rotate(Vec3(0f, 0f, -1f))
        val targetSensor = frame.cameraToAndroidSensor.rotate(targetCamera)
        val cameraForwardEarth = orientation.deviceToMagneticEnu.rotate(cameraForwardSensor)
        val targetEarth = orientation.deviceToMagneticEnu.rotate(targetSensor)
        if (horizontalNorm(cameraForwardEarth) < MIN_CAMERA_HORIZONTAL_COMPONENT) {
            return reject(TactileObservationRejection.POSE_UNSUPPORTED)
        }
        val cameraBearingMagnetic = horizontalBearingDeg(cameraForwardEarth)
            ?: return reject(TactileObservationRejection.POSE_UNSUPPORTED)
        val targetBearingMagnetic = horizontalBearingDeg(targetEarth)
            ?: return reject(TactileObservationRejection.PROJECTION_OUT_OF_RANGE)
        val cameraBearingTrue = normalizeBearing(cameraBearingMagnetic + declinationDeg)
        val targetBearingTrue = normalizeBearing(targetBearingMagnetic + declinationDeg)
        val horizontalDistanceM = sqrt(targetEarth.x * targetEarth.x + targetEarth.y * targetEarth.y)
        val verticalDropM = -targetEarth.z
        if (
            !horizontalDistanceM.isFinite() || horizontalDistanceM !in MIN_HORIZONTAL_DISTANCE_M..MAX_HORIZONTAL_DISTANCE_M ||
            !verticalDropM.isFinite() || verticalDropM !in MIN_VERTICAL_DROP_M..MAX_VERTICAL_DROP_M
        ) {
            return reject(TactileObservationRejection.PROJECTION_OUT_OF_RANGE)
        }

        val projectedPoint = projectGeodesic(location, targetBearingTrue, horizontalDistanceM)
            ?: return reject(TactileObservationRejection.PROJECTION_OUT_OF_RANGE)
        val projectedDistanceToSegmentM = distancePointToSegmentMeters(
            point = projectedPoint,
            start = route.segmentStart,
            end = route.segmentEnd,
        )
        val headingErrorRad = Math.toRadians((orientation.headingErrorDeg + DECLINATION_MODEL_ERROR_DEG).toDouble())
        val lateralHeadingErrorM = horizontalDistanceM * sin(headingErrorRad).toFloat()
        val depthRangeErrorM = max(max(depthIqrM, contactDepth.spreadM), output.minimumDepthErrorM())
        val totalProjectionErrorM =
            location.accuracyM * GPS_ERROR_MULTIPLIER +
                lateralHeadingErrorM +
                depthRangeErrorM +
                TMAP_GEOMETRY_ERROR_M
        if (
            !projectedDistanceToSegmentM.isFinite() ||
            projectedDistanceToSegmentM + totalProjectionErrorM > TMAP_CORRIDOR_RADIUS_M
        ) {
            return reject(TactileObservationRejection.OUTSIDE_TMAP_CORRIDOR)
        }

        return TactileObservationEvaluation(
            observation = TactileRouteObservation(
                candidateId = output.trackId,
                className = output.className,
                confidence = output.detectionConfidence,
                stableFrames = output.trackAgeFrames,
                stableMs = output.trackStableMs,
                ageMs = detectionAgeMs,
                routeHeadingDeltaDeg = routeHeadingDeltaDegrees(cameraBearingTrue, route.bearingDeg),
                tmapCorridorProjection = TmapCorridorProjectionEvidence.OVERLAPS,
                centerXNormalized = uprightContact.x,
                routeId = routeId,
                routeSegmentIndex = route.segmentIndex,
                routeVersion = route.routeRevision,
            ),
            rejection = null,
        )
    }

    private fun TrackedObjectDepth.hasTrustedMetricDepth(): Boolean {
        val minimumSamples = when (source) {
            DepthSource.ARCORE_RAW_DEPTH -> MIN_RAW_DEPTH_SAMPLES
            DepthSource.ARCORE_FULL_DEPTH -> MIN_FULL_DEPTH_SAMPLES
            else -> return false
        }
        return validSampleCount >= minimumSamples &&
            validSampleRatio.isFinite() &&
            validSampleRatio in MIN_VALID_SAMPLE_RATIO..1f &&
            confidence.hardGate > 0f &&
            confidence.finalScore >= MIN_DEPTH_CONFIDENCE
    }

    private fun TrackedObjectDepth.minimumDepthErrorM(): Float = when (source) {
        DepthSource.ARCORE_RAW_DEPTH -> MIN_RAW_DEPTH_ERROR_M
        DepthSource.ARCORE_FULL_DEPTH -> MIN_FULL_DEPTH_ERROR_M
        else -> Float.POSITIVE_INFINITY
    }

    private fun TactileCameraIntrinsics.isUsable(): Boolean {
        return widthPx > 0 && heightPx > 0 &&
            focalLengthXPx.isFinite() && focalLengthXPx > 0f &&
            focalLengthYPx.isFinite() && focalLengthYPx > 0f &&
            principalPointXPx.isFinite() && principalPointXPx in 0f..widthPx.toFloat() &&
            principalPointYPx.isFinite() && principalPointYPx in 0f..heightPx.toFloat()
    }

    private fun TrackedObjectDepth.isTactileRouteClass(): Boolean {
        return WalkMateClassPolicy.isTraversableTactileClass(className) ||
            WalkMateClassPolicy.isTactileRouteBlockingClass(className)
    }

    private companion object {
        const val MAX_LOCATION_AGE_MS = 2_000L
        const val MAX_ORIENTATION_AGE_MS = 150L
        const val MAX_CAMERA_FRAME_AGE_MS = 100L
        const val MAX_DETECTION_AGE_MS = 400L
        const val MAX_GPS_ACCURACY_M = 5f
        const val MAX_HEADING_ERROR_DEG = 15f
        const val MAX_DECLINATION_DEG = 90f
        const val DECLINATION_MODEL_ERROR_DEG = 2f
        const val MIN_DEPTH_M = 0.5f
        const val MAX_DEPTH_M = 5f
        const val MAX_DEPTH_IQR_M = 0.75f
        const val MIN_CONTACT_DEPTH_SAMPLES = 5
        const val MAX_CONTACT_DEPTH_SPREAD_M = 0.35f
        const val MIN_HORIZONTAL_DISTANCE_M = 0.35f
        const val MAX_HORIZONTAL_DISTANCE_M = 5f
        const val MIN_VERTICAL_DROP_M = 0.15f
        const val MAX_VERTICAL_DROP_M = 2.5f
        const val MIN_CONTACT_Y_NORMALIZED = 0.35f
        const val MIN_MASK_AREA_NORMALIZED = 0.003f
        const val MIN_RAW_DEPTH_SAMPLES = 30
        const val MIN_FULL_DEPTH_SAMPLES = 50
        const val MIN_VALID_SAMPLE_RATIO = 0.25f
        const val MIN_DEPTH_CONFIDENCE = 0.55f
        const val MIN_RAW_DEPTH_ERROR_M = 0.25f
        const val MIN_FULL_DEPTH_ERROR_M = 0.50f
        const val GPS_ERROR_MULTIPLIER = 2f
        const val TMAP_GEOMETRY_ERROR_M = 2f
        const val TMAP_CORRIDOR_RADIUS_M = 15f
        const val MIN_ROUTE_SEGMENT_LENGTH_M = 1.0
        const val MIN_CAMERA_HORIZONTAL_COMPONENT = 0.5f
    }
}

data class TactileRouteGuidanceResult(
    val outputs: List<TrackedObjectDepth>,
    val observation: TactileRouteObservation?,
    val decision: TactileRouteDecision,
    /** Per tactile track, why it supplied no selectable observation. Diagnostics only. */
    val observationRejections: Map<String, TactileObservationRejection> = emptyMap(),
) {
    /** Compact developer tally, e.g. `obs=depth_insufficient:2,pose_unsupported:1`. */
    fun observationDiagnosticsText(): String {
        if (observationRejections.isEmpty()) return "obs=-"
        return observationRejections.values.groupingBy { it }.eachCount().entries
            .sortedBy { it.key.ordinal }
            .joinToString(separator = ",", prefix = "obs=") { "${it.key.name.lowercase()}:${it.value}" }
    }
}

internal data class AndroidDetectionSnapshotFrameIdentity(
    val captureFrameId: Long?,
    val frameTimestampMs: Long?,
)

internal data class AndroidTactileEvidenceFrameIdentity(
    val frameId: Long?,
    val frameTimestampMs: Long?,
    val depthFrameId: Long?,
    val depthMapperFrameId: Long?,
)

internal data class AndroidTactileFrameIdentity(
    val detectionCaptureFrameId: Long?,
    val detectionFrameTimestampMs: Long?,
    val evidenceFrameId: Long?,
    val evidenceFrameTimestampMs: Long?,
    val depthFrameId: Long?,
    val depthMapperFrameId: Long?,
) {
    fun matchesCapture(
        context: TactileProjectionContext?,
        requireDepthMapper: Boolean,
    ): Boolean {
        val frameId = detectionCaptureFrameId ?: return false
        val frameTimestampMs = detectionFrameTimestampMs ?: return false
        val depthMapperMatches = depthMapperFrameId == frameId ||
            (!requireDepthMapper && depthMapperFrameId == null)
        return evidenceFrameId == frameId &&
            evidenceFrameTimestampMs == frameTimestampMs &&
            depthFrameId == frameId &&
            depthMapperMatches &&
            context?.captureFrameId == frameId
    }
}

internal data class AndroidTactileFrameInput(
    val outputs: List<TrackedObjectDepth>,
    val identity: AndroidTactileFrameIdentity,
    val context: TactileProjectionContext?,
    val detectionAgeMs: Long?,
    val navigationActiveAtCapture: Boolean,
    val tmapOnRouteAtCapture: Boolean,
    val navigationActiveNow: Boolean,
    val tmapOnRouteNow: Boolean,
    val activeRouteId: String?,
    val activeRouteProgress: TactileActiveRouteProgress? = null,
)

/** Compiled boundary that keeps snapshot identity independent from capture evidence identity. */
internal object AndroidTactileFrameComposition {
    fun identity(
        detectionIdentity: AndroidDetectionSnapshotFrameIdentity,
        evidenceIdentity: AndroidTactileEvidenceFrameIdentity?,
    ): AndroidTactileFrameIdentity = AndroidTactileFrameIdentity(
        detectionCaptureFrameId = detectionIdentity.captureFrameId,
        detectionFrameTimestampMs = detectionIdentity.frameTimestampMs,
        evidenceFrameId = evidenceIdentity?.frameId,
        evidenceFrameTimestampMs = evidenceIdentity?.frameTimestampMs,
        depthFrameId = evidenceIdentity?.depthFrameId,
        depthMapperFrameId = evidenceIdentity?.depthMapperFrameId,
    )

    fun input(
        outputs: List<TrackedObjectDepth>,
        detectionIdentity: AndroidDetectionSnapshotFrameIdentity,
        evidenceIdentity: AndroidTactileEvidenceFrameIdentity?,
        context: TactileProjectionContext?,
        detectionAgeMs: Long?,
        navigationActiveAtCapture: Boolean,
        tmapOnRouteAtCapture: Boolean,
        navigationActiveNow: Boolean,
        tmapOnRouteNow: Boolean,
        activeRouteId: String?,
        activeRouteProgress: TactileActiveRouteProgress? = null,
    ): AndroidTactileFrameInput = AndroidTactileFrameInput(
        outputs = outputs,
        identity = identity(detectionIdentity, evidenceIdentity),
        context = context,
        detectionAgeMs = detectionAgeMs,
        navigationActiveAtCapture = navigationActiveAtCapture,
        tmapOnRouteAtCapture = tmapOnRouteAtCapture,
        navigationActiveNow = navigationActiveNow,
        tmapOnRouteNow = tmapOnRouteNow,
        activeRouteId = activeRouteId,
        activeRouteProgress = activeRouteProgress,
    )
}

/** Production composition boundary: removing its supplier makes every case fall back to TMAP. */
class AndroidTactileRouteGuidance(
    private val observationSupplier: TactileRouteObservationSupplier? = AndroidTactileRouteObservationSupplier(),
    private val policy: TactileRoutePolicy = TactileRoutePolicy(),
) {
    fun apply(
        outputs: List<TrackedObjectDepth>,
        context: TactileProjectionContext?,
        navigationActive: Boolean,
        tmapOnRoute: Boolean,
        activeRouteId: String? = context?.expectedRouteId,
        activeRouteProgress: TactileActiveRouteProgress? = null,
    ): TactileRouteGuidanceResult {
        val tactileOutputs = outputs.filter { it.isTactileClass() }
        val rejections = linkedMapOf<String, TactileObservationRejection>()
        val evaluated = when {
            context == null -> {
                tactileOutputs.forEach { rejections[it.trackId] = TactileObservationRejection.CAPTURE_CONTEXT_UNAVAILABLE }
                emptyList()
            }
            observationSupplier == null -> {
                tactileOutputs.forEach { rejections[it.trackId] = TactileObservationRejection.SUPPLIER_UNAVAILABLE }
                emptyList()
            }
            else -> tactileOutputs.map { output -> output to observationSupplier.evaluateObservation(output, context) }
        }
        // S12-16: a legacy tactile_damage_area is not itself a route class, but a normal block whose
        // image box touches it is not selectable. This removes only that candidate, never every block.
        val damageAreas = outputs.filter { it.className.equals(TACTILE_DAMAGE_AREA_CLASS, ignoreCase = true) }
        val observations = evaluated.mapNotNull { (output, evaluation) ->
            val observation = evaluation.observation
            when {
                observation == null -> {
                    rejections[output.trackId] = evaluation.rejection ?: TactileObservationRejection.UNSPECIFIED
                    null
                }
                WalkMateClassPolicy.isTraversableTactileClass(output.className) &&
                    damageAreas.any { it.bboxNorm.mayOverlap(output.bboxNorm) } -> {
                    rejections[output.trackId] = TactileObservationRejection.DAMAGE_AREA_OVERLAP
                    null
                }
                else -> observation
            }
        }
        val hasUnprojectedBlocker = tactileOutputs.any { output ->
            WalkMateClassPolicy.isTactileRouteBlockingClass(output.className) &&
                observations.none {
                    it.candidateId == output.trackId && it.className.equals(output.className, ignoreCase = true)
                }
        }
        if (hasUnprojectedBlocker) {
            observations.filter { WalkMateClassPolicy.isTraversableTactileClass(it.className) }.forEach { observation ->
                observation.candidateId?.let { rejections.putIfAbsent(it, TactileObservationRejection.BLOCKER_UNPROJECTABLE) }
            }
        }
        val selected = if (hasUnprojectedBlocker) null else policy.selectCandidate(observations, activeRouteId, activeRouteProgress)
        val decision = policy.evaluate(
            TactileRoutePolicyInput(
                navigationActive = navigationActive,
                tmapOnRoute = tmapOnRoute,
                gpsAccuracyM = context?.trustedLocation?.accuracyM,
                tactile = selected,
                activeRouteId = activeRouteId,
                activeRouteProgress = activeRouteProgress,
            ),
        )
        val guidedOutputs = outputs.map { output ->
            if (!WalkMateClassPolicy.isTactileClass(output.className)) return@map output
            val useAsLocalPath = WalkMateClassPolicy.isTraversableTactileClass(output.className) &&
                decision.mode == LocalRouteMode.TACTILE_LOCAL && output.trackId == selected?.candidateId
            output.copy(
                userFacing = if (useAsLocalPath) {
                    UserFacingDepth(
                        stepsAhead = output.userFacing.stepsAhead,
                        messageLevel = MessageLevel.INFO,
                        message = decision.instruction,
                    )
                } else {
                    UserFacingDepth(stepsAhead = null, messageLevel = MessageLevel.NONE, message = null)
                },
            )
        }
        return TactileRouteGuidanceResult(guidedOutputs, selected, decision, rejections.toMap())
    }

    private fun TrackedObjectDepth.isTactileClass(): Boolean {
        return WalkMateClassPolicy.isTraversableTactileClass(className) ||
            WalkMateClassPolicy.isTactileRouteBlockingClass(className)
    }
}

private const val TACTILE_DAMAGE_AREA_CLASS = "tactile_damage_area"

/** Box overlap over-approximates both masks; invalid geometry is an unknown relation (fail closed). */
private fun RectNorm.mayOverlap(other: RectNorm): Boolean {
    if (listOf(x, y, width, height, other.x, other.y, other.width, other.height).any { !it.isFinite() }) return true
    // Degenerate boxes carry no usable relation either; treat them as possibly overlapping.
    if (width <= 0f || height <= 0f || other.width <= 0f || other.height <= 0f) return true
    val overlapWidth = minOf(x + width, other.x + other.width) - maxOf(x, other.x)
    val overlapHeight = minOf(y + height, other.y + other.height) - maxOf(y, other.y)
    return overlapWidth > 0f && overlapHeight > 0f
}

private fun horizontalBearingDeg(vector: Vec3): Float? {
    if (!vector.x.isFinite() || !vector.y.isFinite() || !vector.z.isFinite()) return null
    val horizontalNorm = sqrt(vector.x * vector.x + vector.y * vector.y)
    if (!horizontalNorm.isFinite() || horizontalNorm <= 1e-4f) return null
    return normalizeBearing(Math.toDegrees(atan2(vector.x, vector.y).toDouble()).toFloat())
}

private fun horizontalNorm(vector: Vec3): Float = sqrt(vector.x * vector.x + vector.y * vector.y)

private fun cross(left: Vec3, right: Vec3): Vec3 = Vec3(
    x = left.y * right.z - left.z * right.y,
    y = left.z * right.x - left.x * right.z,
    z = left.x * right.y - left.y * right.x,
)

private fun lerp(start: Point2, end: Point2, fraction: Float): Point2 = Point2(
    x = start.x + (end.x - start.x) * fraction,
    y = start.y + (end.y - start.y) * fraction,
)

private fun Point2.isNormalized(): Boolean = x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f

private fun normalizeBearing(value: Float): Float = ((value % 360f) + 360f) % 360f

private fun projectGeodesic(origin: TrustedLocation, bearingDeg: Float, distanceM: Float): RoutePoint? {
    if (!bearingDeg.isFinite() || !distanceM.isFinite() || distanceM < 0f) return null
    val angularDistance = distanceM.toDouble() / EARTH_RADIUS_M
    val bearingRad = Math.toRadians(bearingDeg.toDouble())
    val latitudeRad = Math.toRadians(origin.latitude)
    val longitudeRad = Math.toRadians(origin.longitude)
    val projectedLatitude = asin(
        sin(latitudeRad) * cos(angularDistance) +
            cos(latitudeRad) * sin(angularDistance) * cos(bearingRad),
    )
    val projectedLongitude = longitudeRad + atan2(
        sin(bearingRad) * sin(angularDistance) * cos(latitudeRad),
        cos(angularDistance) - sin(latitudeRad) * sin(projectedLatitude),
    )
    val latitude = Math.toDegrees(projectedLatitude)
    val longitude = ((Math.toDegrees(projectedLongitude) + 540.0) % 360.0) - 180.0
    return RoutePoint(latitude, longitude).takeIf {
        it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
            it.longitude.isFinite() && it.longitude in -180.0..180.0
    }
}

/**
 * S09-52 (w25 review pos-F11): longitude differences use the shared [LocalGeodesy] wrap, so a segment across
 * ±180° is measured by its short way round. The 111 320 m/degree scale is still the route-projection scale
 * (the shared radius is S09-52 remaining work).
 */
private fun distancePointToSegmentMeters(point: RoutePoint, start: RoutePoint, end: RoutePoint): Float {
    val latScale = 111_320.0
    val lonScale = 111_320.0 * cos(Math.toRadians(point.latitude))
    val ax = LocalGeodesy.wrapLongitudeDegrees(start.longitude - point.longitude) * lonScale
    val ay = (start.latitude - point.latitude) * latScale
    val bx = LocalGeodesy.wrapLongitudeDegrees(end.longitude - point.longitude) * lonScale
    val by = (end.latitude - point.latitude) * latScale
    val abx = bx - ax
    val aby = by - ay
    val denominator = abx * abx + aby * aby
    val fraction = if (denominator <= 1e-6) 0.0 else (-(ax * abx + ay * aby) / denominator).coerceIn(0.0, 1.0)
    return kotlin.math.hypot(ax + abx * fraction, ay + aby * fraction).toFloat()
}

private const val EARTH_RADIUS_M = 6_371_000.0
