package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import java.io.File
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.MotionContext
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.navigation.AndroidDetectionSnapshotFrameIdentity
import kr.co.hanium.dreamup.walksafe.navigation.TactileProjectionContext
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S10-2 (originals/10.txt:40-48): while frame A is being inferred, a rotation, a walk end or a new
 * session must not let A's result attach to the new display geometry or the new walk.
 */
class MainActivityDetectionPublishGuardTest {
    @Test
    fun aResultRequestedBeforeARotationIsNotPublishedIntoTheNewGeometry() {
        val activity = MainActivity()
        val accepted = requireNotNull(publish(activity, frameId = 1L, geometryVersion = 0L))
        // Rotation/display change: resetVisualTrackingState() advances the visual geometry version.
        geometryField().setLong(activity, 1L)
        assertNull(publish(activity, frameId = 2L, geometryVersion = 0L))
        assertSame(accepted, latest(activity))
        // A request made under the new geometry is published.
        assertNotNull(publish(activity, frameId = 3L, geometryVersion = 1L))
    }

    @Test
    fun aResultOfAnEndedWalkOrAnEarlierSessionIsNotPublished() {
        val activity = MainActivity()
        val before = latest(activity)
        // Walk end: no walk runtime is current any more.
        setField(activity, "walkSessionLifecycle", WalkSessionLifecycle())
        assertNull(publish(activity, frameId = 4L, geometryVersion = 0L, expectedWalkEpoch = WalkRuntimeEpoch("walk-a", 0L)))
        // New session: a started walk has its own epoch, so request A's epoch is not current.
        val next = WalkSessionLifecycle().also { it.handle(WalkSessionEvent.EnteredForeground) }
        setField(activity, "walkSessionLifecycle", next)
        assertTrue(next.snapshot().epoch != WalkRuntimeEpoch("walk-a", 0L))
        assertNull(publish(activity, frameId = 5L, geometryVersion = 0L, expectedWalkEpoch = WalkRuntimeEpoch("walk-a", 0L)))
        assertSame(before, latest(activity))
    }

    @Test
    fun theArCoreRequestRecordsItsGeometryUnderTheFrameLock() {
        fun source(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path.kt").readText()
        val main = source("MainActivity")
        assertTrue(main.contains("primaryArSubmissionBridge.schedule("))
        assertTrue(main.contains("primaryDetectionPublisher.publish(PrimarySnapshotPublication("))
        val bridge = source("runtime/vision/primary/AndroidPrimaryArSubmissionBridge")
        val scheduling = MainActivityRefactorSource.member(source("runtime/vision/primary/PrimaryDetectorInvocationWorker"), "commitGeometryIfCurrent")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(scheduling, "synchronized(stateLock)",
            "if (!currentFrame())", "lastCaptureFrameId = frameId()", "currentGeometry()"))
        assertTrue(bridge.contains("expectedGeometry = input.geometryVersion"))
        assertTrue(bridge.contains("currentGeometry = { synthesis().geometryVersion }"))
        assertTrue(bridge.contains("geometryVersion = requestGeometryVersion"))
        val publisher = source("runtime/vision/primary/PrimaryDetectionPublisher")
        val guard = publisher.substringBefore("val snapshot = DetectionSnapshot.published(")
        assertTrue(guard.contains("event.frameEvidence.geometryVersion != null && event.frameEvidence.geometryVersion != frameOwner.geometryVersion"))
    }

    private fun publish(
        activity: MainActivity,
        frameId: Long,
        geometryVersion: Long?,
        expectedWalkEpoch: WalkRuntimeEpoch? = null,
    ): DetectionSnapshot? = activity.publishDetectionSnapshot(
        result = AndroidDetectionResult.empty(),
        generation = 0,
        detectionIdentity = AndroidDetectionSnapshotFrameIdentity(captureFrameId = frameId, frameTimestampMs = 1_000L),
        capturedAtMs = 1_000L,
        startedAtMs = 1_000L,
        completedAtMs = 1_010L,
        imageWidth = 640,
        imageHeight = 480,
        reportImageJpeg = null,
        frameEvidence = evidence(frameId, geometryVersion),
        expectedWalkEpoch = expectedWalkEpoch,
    )

    private fun evidence(frameId: Long, geometryVersion: Long?) = DetectionFrameEvidence(
        frameId = frameId,
        frameTimestampMs = 1_000L,
        depthSnapshot = DepthFrameSnapshot(frameTimestampNs = frameId, rawDepth = null, rawConfidence = null, fullDepth = null),
        depthMapper = null,
        tactileContext = TactileProjectionContext(
            captureFrameId = frameId,
            expectedRouteId = null,
            routeProjection = null,
            trustedLocation = null,
            orientation = null,
            magneticDeclinationDeg = null,
            cameraFrame = null,
            detectionAgeMs = 100L,
            nowElapsedRealtimeMs = 1_000L,
        ),
        motionContext = MotionContext(),
        navigationActive = false,
        tmapOnRoute = false,
        geometryVersion = geometryVersion,
    )

    private fun setField(activity: MainActivity, name: String, value: Any) =
        MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(activity, value)

    private fun geometryField() = MainActivityControllerTestFixture.field("visualGeometryVersion").apply { isAccessible = true }

    private fun latest(activity: MainActivity) = MainActivityControllerTestFixture.field("latestDetectionSnapshot")
        .apply { isAccessible = true }.get(activity)
}
