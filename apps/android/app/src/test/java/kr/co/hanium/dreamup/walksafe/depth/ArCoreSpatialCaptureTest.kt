package kr.co.hanium.dreamup.walksafe.depth

import android.media.Image
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.CameraIntrinsics as ArCameraIntrinsics
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native-free ARCore doubles; Pose math and the production snapshot path are real SDK code. */
class ArCoreSpatialCaptureTest {
    @Test
    fun tiltedAndInvertedInitialAnchorsKeepGravityAndFloorHeightInTheSameReference() {
        listOf(0.0, 45.0, 90.0, 180.0, 270.0).forEach { degrees ->
            listOf(0, 2).forEach { axis ->
                val half = Math.toRadians(degrees) / 2.0
                val q = FloatArray(4).apply { this[axis] = sin(half).toFloat(); this[3] = cos(half).toFloat() }
                val cameraPose = Pose(floatArrayOf(3f, 1.5f, -4f), q)
                val fixture = Fixture(cameraPose)
                fixture.session.planes = listOf(FakePlane(Pose.makeTranslation(3f, 0f, -4f)))
                val captured = requireNotNull(fixture.provider.acquireDepthSnapshot(fixture.frame).cameraPoseEvidence)
                val gravity = requireNotNull(captured.gravityUpInAnchor)
                val floor = captured.horizontalPlaneCandidates.single()
                assertEquals(captured.referenceId, floor.referenceId)
                assertEquals(captured.timestampMs, floor.captureTimestampMs)
                assertEquals(1f, gravity.norm(), 0.0001f)
                floor.polygonInAnchor.forEach {
                    assertEquals(-1.5f, it.dot(gravity), 0.0001f)
                    val worldPoint = cameraPose.transformPoint(floatArrayOf(it.x, it.y, it.z))
                    assertEquals(0f, worldPoint[1], 0.0001f)
                }
                if (degrees == 90.0 && axis == 2) {
                    assertEquals(1f, gravity.x, 0.0001f)
                    assertEquals(0f, gravity.y, 0.0001f)
                }
                if (degrees == 180.0) assertEquals(-1f, gravity.y, 0.0001f)
                fixture.provider.close()
            }
        }
    }

    @Test
    fun planeErrorsAndFilteredTrackablesDoNotClearPoseOrValidBoundary() {
        val fixture = Fixture(Pose.IDENTITY)
        val valid = FakePlane(Pose.makeTranslation(0f, -1.5f, 0f))
        fixture.session.planes = listOf(
            FakePlane(Pose.IDENTITY).apply { state = TrackingState.PAUSED },
            FakePlane(Pose.IDENTITY).apply { typeValue = Plane.Type.VERTICAL },
            FakePlane(Pose.IDENTITY).apply { typeValue = Plane.Type.HORIZONTAL_DOWNWARD_FACING },
            FakePlane(Pose.IDENTITY).apply { subsumed = valid },
            FakePlane(Pose.IDENTITY).apply { failPolygon = true },
            valid,
        )
        val first = requireNotNull(fixture.provider.acquireDepthSnapshot(fixture.frame).cameraPoseEvidence)
        assertEquals(1, first.horizontalPlaneCandidates.size)
        fixture.session.failPlanes = true
        fixture.frame.timeNs += 1_000_000L
        val second = requireNotNull(fixture.provider.acquireDepthSnapshot(fixture.frame).cameraPoseEvidence)
        assertEquals(first.referenceId, second.referenceId)
        assertEquals(0, fixture.session.anchor.detaches)
        assertEquals(Vec3(0f, 1f, 0f), second.gravityUpInAnchor)
        assertTrue(second.horizontalPlaneCandidates.isEmpty())
    }

    @Test
    fun currentAnchorAndPlanePosesAreResolvedAfterWorldRebase() {
        val fixture = Fixture(Pose.makeTranslation(0f, 1.5f, 0f))
        val plane = FakePlane(Pose.IDENTITY)
        fixture.session.planes = listOf(plane)
        val first = requireNotNull(fixture.provider.acquireDepthSnapshot(fixture.frame).cameraPoseEvidence)
        val rebase = Pose.makeTranslation(10f, 0f, -20f).compose(Pose.makeRotation(0f, 0.70710677f, 0f, 0.70710677f))
        fixture.session.anchor.poseValue = rebase.compose(fixture.session.anchor.poseValue)
        fixture.camera.poseValue = rebase.compose(fixture.camera.poseValue)
        plane.poseValue = rebase.compose(plane.poseValue)
        fixture.frame.timeNs += 1_000_000L
        val second = requireNotNull(fixture.provider.acquireDepthSnapshot(fixture.frame).cameraPoseEvidence)
        assertEquals(first.referenceId, second.referenceId)
        first.horizontalPlaneCandidates.single().polygonInAnchor.zip(second.horizontalPlaneCandidates.single().polygonInAnchor)
            .forEach { (before, after) -> assertTrue((before - after).norm() < 0.0001f) }
    }

    @Test
    fun trackingLossClearsReferenceAndCannotRetainPlaneMetadata() {
        val fixture = Fixture(Pose.IDENTITY)
        fixture.session.planes = listOf(FakePlane(Pose.IDENTITY))
        val first = requireNotNull(fixture.provider.acquireDepthSnapshot(fixture.frame).cameraPoseEvidence)
        fixture.camera.state = TrackingState.PAUSED
        assertNull(fixture.provider.acquireDepthSnapshot(fixture.frame).cameraPoseEvidence)
        assertEquals(1, fixture.session.anchor.detaches)
        fixture.camera.state = TrackingState.TRACKING
        fixture.session.planes = emptyList()
        fixture.frame.timeNs += 1_000_000L
        val second = requireNotNull(fixture.provider.acquireDepthSnapshot(fixture.frame).cameraPoseEvidence)
        assertNotEquals(first.referenceId, second.referenceId)
        assertTrue(second.horizontalPlaneCandidates.isEmpty())
        assertNotNull(second.gravityUpInAnchor)
    }

    private class Fixture(pose: Pose) {
        val camera = FakeCamera(pose)
        val frame = FakeFrame(camera)
        val session = FakeSession()
        val provider = ArCoreFrameProvider(session)
    }

    private class FakeSession : Session() {
        lateinit var anchor: FakeAnchor
        var planes: List<Plane> = emptyList()
        var failPlanes = false
        override fun createAnchor(pose: Pose): Anchor = FakeAnchor(pose).also { anchor = it }
        override fun <T : Trackable> getAllTrackables(type: Class<T>): Collection<T> {
            if (failPlanes) error("planes unavailable")
            return planes.map { requireNotNull(type.cast(it)) }
        }
    }

    private class FakeAnchor(var poseValue: Pose) : Anchor() {
        var detaches = 0
        override fun getPose() = poseValue
        override fun getTrackingState() = TrackingState.TRACKING
        override fun detach() { detaches++ }
    }

    private class FakeCamera(var poseValue: Pose) : Camera() {
        var state = TrackingState.TRACKING
        override fun getPose() = poseValue
        override fun getTrackingState() = state
        override fun getImageIntrinsics(): ArCameraIntrinsics = object : ArCameraIntrinsics() {
            override fun getFocalLength() = floatArrayOf(500f, 500f)
            override fun getPrincipalPoint() = floatArrayOf(500f, 500f)
            override fun getImageDimensions() = intArrayOf(1000, 1000)
        }
    }

    private class FakeFrame(private val cameraValue: Camera) : Frame() {
        var timeNs = 1_000_000_000L
        override fun getCamera() = cameraValue
        override fun getTimestamp() = timeNs
        override fun acquireCameraImage(): Image = error("synthetic frame has no image")
        override fun acquireRawDepthImage16Bits(): Image = error("synthetic frame has no depth")
        override fun acquireRawDepthConfidenceImage(): Image = error("synthetic frame has no depth")
        override fun acquireDepthImage16Bits(): Image = error("synthetic frame has no depth")
    }

    private class FakePlane(var poseValue: Pose) : Plane() {
        var state = TrackingState.TRACKING
        var typeValue = Type.HORIZONTAL_UPWARD_FACING
        var subsumed: Plane? = null
        var failPolygon = false
        override fun getCenterPose() = poseValue
        override fun getTrackingState() = state
        override fun getType() = typeValue
        override fun getSubsumedBy() = subsumed
        override fun getPolygon(): FloatBuffer {
            if (failPolygon) error("polygon unavailable")
            return FloatBuffer.wrap(floatArrayOf(-1f, -2f, 1f, -2f, 1f, 2f, -1f, 2f))
        }
    }
}
