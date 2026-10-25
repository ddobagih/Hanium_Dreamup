package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.navigation.FrozenImageToDepthTransform

/**
 * Immutable adapter between detector image coordinates and the depth texture copied from the same
 * ARCore frame. No Frame reference crosses the capture boundary.
 */
internal class FrozenImageToTextureCoordinateMapper(
    val frameId: Long,
    private val transform: FrozenImageToDepthTransform,
    private val depthSize: ImageSize,
) : CoordinateMapper {
    fun imagePixelsToDepthUvMatrix(imageWidth: Int, imageHeight: Int): DoubleArray? =
        if (frameId == transform.frameId) transform.imagePixelsToDepthUvMatrix(imageWidth, imageHeight) else null

    override fun modelToImage(point: Point2): Point2 = normalize(point)

    override fun imageToModel(point: Point2): Point2 = normalize(point)

    override fun imageToDepth(point: Point2): Point2? {
        if (frameId != transform.frameId || depthSize.width <= 0 || depthSize.height <= 0) return null
        return transform.map(point)
    }

    override fun depthToImage(point: Point2): Point2? = null

    override fun imagePolygonToDepthPolygon(polygon: List<Point2>): List<Point2> {
        return polygon.map { point ->
            imageToDepth(point) ?: return emptyList()
        }
    }

    override fun depthPixelToCameraPoint(x: Int, y: Int, zM: Float, intrinsics: CameraIntrinsics): Vec3? {
        return kr.co.hanium.dreamup.walksafe.depth.depthPixelToCameraPoint(x, y, zM, intrinsics)
    }

    private fun normalize(point: Point2): Point2 {
        return Point2(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f))
    }
}
