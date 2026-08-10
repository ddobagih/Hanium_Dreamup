package kr.co.hanium.dreamup.walksafe.navigation

import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import kr.co.hanium.dreamup.walksafe.depth.Vec3

internal object ArCoreTactileProjectionContextFactory {
    fun create(
        frame: Frame,
        elapsedRealtimeMs: Long,
        expectedRouteId: String?,
        routeProjection: ActiveRouteProjection?,
        trustedLocation: TrustedLocation?,
        orientation: DeviceEarthOrientation?,
        magneticDeclinationDeg: Float?,
    ): TactileProjectionContext {
        return TactileProjectionContext(
            captureFrameId = frame.timestamp,
            expectedRouteId = expectedRouteId,
            routeProjection = routeProjection,
            trustedLocation = trustedLocation,
            orientation = orientation,
            magneticDeclinationDeg = magneticDeclinationDeg,
            cameraFrame = buildTactileCameraFrame(frame, elapsedRealtimeMs),
            detectionAgeMs = null,
            nowElapsedRealtimeMs = elapsedRealtimeMs,
        )
    }

    private fun buildTactileCameraFrame(frame: Frame, elapsedRealtimeMs: Long): TactileCameraFrame? {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return null
        return try {
            val cameraPose = camera.pose
            val worldToAndroidSensor = frame.androidSensorPose.inverse()
            fun cameraAxisInAndroidSensor(axis: Vec3): Vec3 {
                val world = cameraPose.rotateVector(floatArrayOf(axis.x, axis.y, axis.z))
                val sensor = worldToAndroidSensor.rotateVector(world)
                return Vec3(sensor[0], sensor[1], sensor[2])
            }
            val cameraToAndroidSensor = RotationMatrix3.fromColumns(
                x = cameraAxisInAndroidSensor(Vec3(1f, 0f, 0f)),
                y = cameraAxisInAndroidSensor(Vec3(0f, 1f, 0f)),
                z = cameraAxisInAndroidSensor(Vec3(0f, 0f, 1f)),
            ) ?: return null
            val intrinsics = camera.imageIntrinsics
            val dimensions = intrinsics.imageDimensions
            val focalLength = intrinsics.focalLength
            val principalPoint = intrinsics.principalPoint
            TactileCameraFrame(
                frameId = frame.timestamp,
                cameraToAndroidSensor = cameraToAndroidSensor,
                intrinsics = TactileCameraIntrinsics(
                    widthPx = dimensions[0],
                    heightPx = dimensions[1],
                    focalLengthXPx = focalLength[0],
                    focalLengthYPx = focalLength[1],
                    principalPointXPx = principalPoint[0],
                    principalPointYPx = principalPoint[1],
                ),
                observedAtElapsedRealtimeMs = elapsedRealtimeMs,
                tracking = true,
            )
        } catch (_: RuntimeException) {
            null
        }
    }
}
