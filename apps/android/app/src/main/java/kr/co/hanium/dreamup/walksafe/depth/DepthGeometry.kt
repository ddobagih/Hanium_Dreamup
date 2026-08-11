package kr.co.hanium.dreamup.walksafe.depth

import kotlin.math.sqrt

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun times(scale: Float): Vec3 = Vec3(x * scale, y * scale, z * scale)
    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z
    fun norm(): Float = sqrt(dot(this))
    fun normalized(): Vec3 {
        val length = norm()
        return if (length <= 1e-6f) Vec3(0f, 0f, 0f) else this * (1f / length)
    }
}

data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
)

data class CameraPoseLike(
    val translation: Vec3,
    val forward: Vec3 = Vec3(0f, 0f, 1f),
    val right: Vec3 = Vec3(1f, 0f, 0f),
)

data class GroundPlane(
    val point: Vec3,
    val normal: Vec3,
)

data class GroundProjection(
    val groundDistanceM: Float,
    val lateralOffsetM: Float,
)

fun depthPixelToCameraPoint(x: Int, y: Int, zDepthM: Float, intrinsics: CameraIntrinsics): Vec3? {
    if (zDepthM <= 0f || !zDepthM.isFinite() || intrinsics.fx == 0f || intrinsics.fy == 0f) return null
    val xCam = (x - intrinsics.cx) / intrinsics.fx * zDepthM
    val yCam = (y - intrinsics.cy) / intrinsics.fy * zDepthM
    return Vec3(xCam, yCam, zDepthM)
}

fun rayDistanceM(cameraPoint: Vec3?): Float? = cameraPoint?.norm()

fun projectToGroundPlane(point: Vec3, plane: GroundPlane): Vec3? {
    val normal = plane.normal.normalized()
    if (normal.norm() <= 1e-6f) return null
    val delta = point - plane.point
    return point - normal * delta.dot(normal)
}

fun groundProjection(
    cameraWorld: Vec3,
    objectWorld: Vec3,
    pose: CameraPoseLike,
    groundPlane: GroundPlane,
): GroundProjection? {
    val userGround = projectToGroundPlane(cameraWorld, groundPlane) ?: return null
    val objectGround = projectToGroundPlane(objectWorld, groundPlane) ?: return null
    val forwardGround = projectToGroundPlane(cameraWorld + pose.forward, groundPlane)?.minus(userGround)?.normalized() ?: return null
    val rightGround = projectToGroundPlane(cameraWorld + pose.right, groundPlane)?.minus(userGround)?.normalized() ?: return null
    if (forwardGround.norm() <= 1e-6f || rightGround.norm() <= 1e-6f) return null
    val delta = objectGround - userGround
    return GroundProjection(
        groundDistanceM = delta.dot(forwardGround),
        lateralOffsetM = delta.dot(rightGround),
    )
}
