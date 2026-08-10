package kr.co.hanium.dreamup.walksafe.depth

import kotlin.math.roundToInt

data class ImageSize(val width: Int, val height: Int)

data class ModelInputTransform(
    val imageWidth: Int,
    val imageHeight: Int,
    val modelWidth: Int,
    val modelHeight: Int,
    val scale: Float,
    val padX: Float,
    val padY: Float,
    val rotationDeg: Int = 0,
    val mirrored: Boolean = false,
)

/** Maps normalized points between model, camera-image and depth-image coordinate spaces. */
interface CoordinateMapper {
    fun modelToImage(point: Point2): Point2
    fun imageToModel(point: Point2): Point2
    fun imageToDepth(point: Point2): Point2?
    fun depthToImage(point: Point2): Point2?
    fun imagePolygonToDepthPolygon(polygon: List<Point2>): List<Point2>
    fun depthPixelToCameraPoint(x: Int, y: Int, zM: Float, intrinsics: CameraIntrinsics): Vec3?
}

/**
 * Reverses model-input letterboxing, rotation and mirroring. Its image/depth mapping assumes those
 * normalized planes are already aligned; ARCore production code must supply its frame transform.
 */
class LetterboxCoordinateMapper(
    private val transform: ModelInputTransform,
    private val depthSize: ImageSize,
) : CoordinateMapper {
    override fun modelToImage(point: Point2): Point2 {
        val modelX = point.x * transform.modelWidth
        val modelY = point.y * transform.modelHeight
        val unrotated = Point2(
            ((modelX - transform.padX) / transform.scale) / transform.imageWidth,
            ((modelY - transform.padY) / transform.scale) / transform.imageHeight,
        )
        return normalize(rotateFromModelInput(Point2(applyMirrorX(unrotated.x), unrotated.y)))
    }

    override fun imageToModel(point: Point2): Point2 {
        val rotated = rotateToModelInput(normalize(point))
        val unmirroredX = applyMirrorX(rotated.x)
        val modelX = unmirroredX * transform.imageWidth * transform.scale + transform.padX
        val modelY = rotated.y * transform.imageHeight * transform.scale + transform.padY
        return normalize(Point2(modelX / transform.modelWidth, modelY / transform.modelHeight))
    }

    override fun imageToDepth(point: Point2): Point2? {
        val image = normalize(point)
        if (depthSize.width <= 0 || depthSize.height <= 0) return null
        return image
    }

    override fun depthToImage(point: Point2): Point2? {
        if (depthSize.width <= 0 || depthSize.height <= 0) return null
        return normalize(point)
    }

    override fun imagePolygonToDepthPolygon(polygon: List<Point2>): List<Point2> {
        return polygon.mapNotNull { imageToDepth(it) }
    }

    override fun depthPixelToCameraPoint(x: Int, y: Int, zM: Float, intrinsics: CameraIntrinsics): Vec3? {
        return kr.co.hanium.dreamup.walksafe.depth.depthPixelToCameraPoint(x, y, zM, intrinsics)
    }

    fun depthPixelToNorm(x: Int, y: Int): Point2 {
        return Point2(
            x.coerceIn(0, depthSize.width - 1) / (depthSize.width - 1).coerceAtLeast(1).toFloat(),
            y.coerceIn(0, depthSize.height - 1) / (depthSize.height - 1).coerceAtLeast(1).toFloat(),
        )
    }

    fun normToDepthPixel(point: Point2): Pair<Int, Int> {
        val normalized = normalize(point)
        return (normalized.x * (depthSize.width - 1)).roundToInt() to (normalized.y * (depthSize.height - 1)).roundToInt()
    }

    private fun applyMirrorX(x: Float): Float = if (transform.mirrored) 1f - x else x

    private fun rotateToModelInput(point: Point2): Point2 {
        return when (normalizedRotation()) {
            90 -> Point2(point.y, 1f - point.x)
            180 -> Point2(1f - point.x, 1f - point.y)
            270 -> Point2(1f - point.y, point.x)
            else -> point
        }
    }

    private fun rotateFromModelInput(point: Point2): Point2 {
        return when (normalizedRotation()) {
            90 -> Point2(1f - point.y, point.x)
            180 -> Point2(1f - point.x, 1f - point.y)
            270 -> Point2(point.y, 1f - point.x)
            else -> point
        }
    }

    private fun normalizedRotation(): Int = ((transform.rotationDeg % 360) + 360) % 360

    private fun normalize(point: Point2): Point2 = Point2(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f))
}
