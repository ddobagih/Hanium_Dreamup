package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken

/** Owned CPU copies from one capture. No ARCore Frame, Image, or mutable mapper survives here. */
class FrozenUnknownDepthCapture private constructor(
    val token: FastSamFrameToken,
    internal val snapshot: DepthFrameSnapshot,
    internal val calibration: MaskDepthEstimator.Calibration?,
    internal val depth: MaskDepthEstimator.DepthFrame?,
    internal val fullDepth: MaskDepthEstimator.DepthFrame?,
    internal val motionContext: MotionContext,
    val imageQuarterTurns: Int,
) {
    internal val identity = identity(token)
    val frameTimestampMs: Long get() = token.cameraTimestampNs / 1_000_000L

    companion object {
        /** H maps original pixel-edge coordinates to depth UV; out-of-texture corners are valid. */
        fun freeze(
            token: FastSamFrameToken,
            snapshot: DepthFrameSnapshot,
            imageToDepthUv: DoubleArray?,
            mapperFrameId: Long,
            motionContext: MotionContext = MotionContext(),
            imageQuarterTurns: Int = 0,
        ): FrozenUnknownDepthCapture? {
            if (imageQuarterTurns !in 0..3) return null
            if (token.source != FastSamFrameToken.Source.LIVE_CAMERA || token.frameId <= 0L || token.cameraTimestampNs <= 0L || token.cpuImageTimestampNs <= 0L ||
                token.capturedElapsedNs < 0L || token.width <= 0 || token.height <= 0 ||
                snapshot.frameTimestampNs != token.cameraTimestampNs ||
                snapshot.cameraImageTimestampNs != token.cpuImageTimestampNs ||
                mapperFrameId != token.frameId ||
                snapshot.cameraPoseEvidence?.timestampMs?.let { it != token.cameraTimestampNs / 1_000_000L } == true
            ) return null
            if (imageToDepthUv != null && (imageToDepthUv.size != 9 || imageToDepthUv.any { !it.isFinite() })) return null
            val raw = snapshot.rawDepth?.let { DepthImage16(it.width, it.height, it.millimeters.copyOf(it.width * it.height)) }
            val confidence = snapshot.rawConfidence?.let {
                ConfidenceImage8(it.width, it.height, it.values.copyOf(it.width * it.height))
            }
            if (raw != null && confidence != null && (raw.width != confidence.width || raw.height != confidence.height)) return null
            val full = snapshot.fullDepth?.takeIf { snapshot.hasFreshFullDepth }?.let {
                DepthImage16(it.width, it.height, it.millimeters.copyOf(it.width * it.height))
            }
            val copied = snapshot.copy(rawDepth = raw, rawConfidence = confidence, fullDepth = full)
            val frame = identity(token)
            val intrinsics = copied.cameraPoseEvidence?.imageProjection?.takeIf {
                it.imageWidth == token.width && it.imageHeight == token.height
            }?.let { MaskDepthEstimator.Intrinsics(it.imageWidth, it.imageHeight, it.fx.toDouble(), it.fy.toDouble(), it.cx.toDouble(), it.cy.toDouble()) }
            val grid = raw ?: full
            val calibration = if (grid != null && imageToDepthUv != null) MaskDepthEstimator.Calibration(
                frame, token.width, token.height, grid.width, grid.height, imageToDepthUv,
                "arcore_transform_coordinates2d", intrinsics,
            ) else null
            val depth = if (raw != null && confidence != null && calibration != null && copied.rawConfidenceMatchesRawDepth) MaskDepthEstimator.DepthFrame.raw(
                frame, copied.rawDepthTimestampNs ?: 0L, CAMERA_IMAGE_CLOCK, raw.millimeters, confidence.values, calibration, frame,
            ) else null
            val fullCalibration = if (full != null && imageToDepthUv != null) MaskDepthEstimator.Calibration(
                frame, token.width, token.height, full.width, full.height, imageToDepthUv,
                "arcore_transform_coordinates2d", intrinsics,
            ) else null
            val fullFrame = if (full != null && fullCalibration != null) MaskDepthEstimator.DepthFrame.full(
                frame, requireNotNull(copied.fullDepthTimestampNs), CAMERA_IMAGE_CLOCK, full.millimeters, fullCalibration,
            ) else null
            return FrozenUnknownDepthCapture(token, copied, calibration, depth, fullFrame,
                motionContext.copy(cameraPoseEvidence = copied.cameraPoseEvidence), imageQuarterTurns)
        }

        internal fun identity(token: FastSamFrameToken) = MaskDepthEstimator.FrameIdentity(
            token.sessionEpoch.toString(), token.frameId.toString(), token.cpuImageTimestampNs, CAMERA_IMAGE_CLOCK,
        )

        private const val CAMERA_IMAGE_CLOCK = "arcore_cpu_image_clock"
    }
}

internal fun sameCapture(a: FastSamFrameToken, b: FastSamFrameToken): Boolean =
    a.source == b.source && a.sessionEpoch == b.sessionEpoch && a.frameId == b.frameId && a.cameraTimestampNs == b.cameraTimestampNs &&
        a.cpuImageTimestampNs == b.cpuImageTimestampNs && a.capturedElapsedNs == b.capturedElapsedNs &&
        a.geometryId == b.geometryId && a.width == b.width && a.height == b.height

/** Exact captured H, with normalized image coordinates at the existing estimator boundary. */
internal class CapturedDepthMapper(calibration: MaskDepthEstimator.Calibration) : CoordinateMapper {
    private val h = calibration.imageToDepthUv()
    private val width = calibration.imageWidth
    private val height = calibration.imageHeight
    fun coversImagePixel(x: Int, y: Int): Boolean = mapped(x + 0.5, y + 0.5) != null
    override fun modelToImage(point: Point2) = point
    override fun imageToModel(point: Point2) = point
    override fun imageToDepth(point: Point2): Point2? {
        val x = point.x * width.toDouble(); val y = point.y * height.toDouble()
        return mapped(x, y)
    }
    private fun mapped(x: Double, y: Double): Point2? {
        val divisor = h[6] * x + h[7] * y + h[8]
        if (!divisor.isFinite() || divisor == 0.0) return null
        val u = (h[0] * x + h[1] * y + h[2]) / divisor
        val v = (h[3] * x + h[4] * y + h[5]) / divisor
        return if (u.isFinite() && v.isFinite() && u in 0.0..1.0 && v in 0.0..1.0) Point2(u.toFloat(), v.toFloat()) else null
    }
    override fun depthToImage(point: Point2): Point2? = null
    override fun imagePolygonToDepthPolygon(polygon: List<Point2>): List<Point2> {
        return polygon.map { imageToDepth(it) ?: return emptyList() }
    }
    override fun depthPixelToCameraPoint(x: Int, y: Int, zM: Float, intrinsics: CameraIntrinsics) =
        kr.co.hanium.dreamup.walksafe.depth.depthPixelToCameraPoint(x, y, zM, intrinsics)
}
