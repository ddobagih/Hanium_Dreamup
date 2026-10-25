package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.inference.DetectorStallWatchdog
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.inference.OwnedYuvFrame
import kr.co.hanium.dreamup.walksafe.inference.YuvImagePreprocessor
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityDetectorStallRegressionTest {
    @Test
    fun activityConstructionDoesNotRequireAnAndroidMainLooper() {
        assertNotNull(MainActivity())
    }

    @Test
    fun ownedFrameKeepsBothPreprocessingStrategiesIndependentOfTheSourcePlanes() {
        val y = byteArrayOf(30, 60, 0, 90, 120)
        val u = byteArrayOf(110, 0)
        val v = byteArrayOf(170.toByte(), 0)
        val frame = OwnedYuvFrame.copyOf(
            2, 2, ByteBuffer.wrap(y), ByteBuffer.wrap(u), ByteBuffer.wrap(v),
            3, 1, 1, 1,
        )
        val preprocessor = YuvImagePreprocessor()
        val legacyBefore = floats(preprocessor.preprocess(frame.toArgb(), 2).inputBuffer, 12)
        val fusedBefore = floats(frame.preprocessFused(preprocessor, 2).inputBuffer, 12)
        val pixelsBefore = frame.toArgb().pixels

        y.fill(0)
        u.fill(0)
        v.fill(0)

        assertArrayEquals(pixelsBefore, frame.toArgb().pixels)
        assertArrayEquals(legacyBefore, floats(preprocessor.preprocess(frame.toArgb(), 2).inputBuffer, 12), 0f)
        assertArrayEquals(fusedBefore, floats(frame.preprocessFused(preprocessor, 2).inputBuffer, 12), 0f)
    }

    @Test
    fun watchdogStopsAtBudgetWithoutWaitingForTheNativeWorkerAndRejectsLateCompletion() {
        val now = AtomicLong(0L)
        val watchdog = DetectorStallWatchdog(800L, now::get)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        val active = AtomicReference<DetectorStallWatchdog.Flight>()
        val worker = Thread {
            val flight = requireNotNull(watchdog.begin())
            active.set(flight)
            entered.countDown()
            release.await()
            accepted.set(watchdog.complete(flight))
        }
        worker.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        now.set(800L)
        val flight = requireNotNull(active.get())
        assertTrue(watchdog.expire(flight))
        assertTrue(watchdog.blocksNewWork())
        assertFalse(watchdog.expire(flight))
        assertNull(watchdog.begin())
        release.countDown()
        worker.join(1_000L)
        assertFalse(worker.isAlive)
        assertFalse(accepted.get())
    }

    @Test
    fun completedNativeWorkDoesNotFalseStopAtTheDeadline() {
        val now = AtomicLong(0L)
        val watchdog = DetectorStallWatchdog(800L, now::get)
        val flight = watchdog.begin()
        assertNotNull(flight)
        now.set(799L)
        assertTrue(watchdog.complete(requireNotNull(flight)))
        now.set(800L)
        assertFalse(watchdog.expire(flight))
        assertFalse(watchdog.blocksNewWork())
        assertNotNull(watchdog.begin())
    }

    @Test
    fun overdueNativeCompletionFailsClosedBeforeTheHandlerRuns() {
        val now = AtomicLong(0L)
        val watchdog = DetectorStallWatchdog(800L, now::get)
        val flight = requireNotNull(watchdog.begin())
        now.set(801L)

        assertFalse(watchdog.complete(flight))
        assertTrue(watchdog.blocksNewWork())
        assertFalse(watchdog.expire(flight))
        assertNull(watchdog.begin())
    }

    @Test
    fun partialPublicationAt801MsIsRejectedBeforeDelayedTimeoutBut799MsIsAccepted() {
        val now = AtomicLong(0L)
        val watchdog = DetectorStallWatchdog(800L, now::get)
        val flight = requireNotNull(watchdog.begin())
        var publications = 0

        now.set(799L)
        assertTrue(watchdog.publishIfCurrent(flight) { publications += 1 })
        assertEquals(1, publications)

        now.set(801L)
        assertFalse(watchdog.publishIfCurrent(flight) { publications += 1 })
        assertEquals(1, publications)
        assertTrue(watchdog.blocksNewWork())
        assertFalse(watchdog.expire(flight))
        assertFalse(watchdog.complete(flight))
    }

    @Test
    fun postedStallCannotStopNewOrRecoveredWalkButCanStopItsCurrentWalk() {
        val oldEpoch = WalkRuntimeEpoch("old-walk", 0L)
        val newEpoch = WalkRuntimeEpoch("new-walk", 0L)
        val recoveredEpoch = WalkRuntimeEpoch("old-walk", 1L)

        assertFalse(nativeDetectorStallOwnsWalk(oldEpoch, 7, newEpoch, 7, WalkSessionState.ACTIVE))
        assertFalse(nativeDetectorStallOwnsWalk(oldEpoch, 7, newEpoch, 7, WalkSessionState.PAUSED))
        assertFalse(nativeDetectorStallOwnsWalk(oldEpoch, 7, recoveredEpoch, 7, WalkSessionState.ACTIVE))
        assertFalse(nativeDetectorStallOwnsWalk(oldEpoch, 7, oldEpoch, 8, WalkSessionState.ACTIVE))
        assertTrue(nativeDetectorStallOwnsWalk(oldEpoch, 7, oldEpoch, 7, WalkSessionState.ACTIVE))
        assertTrue(nativeDetectorStallOwnsWalk(oldEpoch, 7, oldEpoch, 7, WalkSessionState.PAUSED))
    }

    @Test
    fun oldBlockedFlightRejectsNewActiveWalkAndRequestsItsStopOnlyOnce() {
        val now = AtomicLong(0L)
        val watchdog = DetectorStallWatchdog(800L, now::get)
        val oldFlight = requireNotNull(watchdog.begin())
        val newEpoch = WalkRuntimeEpoch("new-walk", 0L)
        val stopRequests = mutableListOf<WalkRuntimeEpoch>()

        now.set(801L)
        assertTrue(watchdog.expire(oldFlight))
        assertNull(watchdog.begin())
        assertTrue(watchdog.rejectBlockedWork(newEpoch, WalkSessionState.ACTIVE, stopRequests::add))
        assertTrue(watchdog.rejectBlockedWork(newEpoch, WalkSessionState.ACTIVE, stopRequests::add))
        assertEquals(listOf(newEpoch), stopRequests)
        assertTrue(watchdog.blocksNewWork())
    }

    @Test
    fun walkEntryRejectsUnfinishedOldFlightBeforeDetectorExecutorDrainsAndAllowsCompletedNextWalk() {
        val now = AtomicLong(0L)
        val watchdog = DetectorStallWatchdog(800L, now::get)
        val oldEpoch = WalkRuntimeEpoch("old-walk", 0L)
        val newEpoch = WalkRuntimeEpoch("new-walk", 0L)
        val activity = MainActivity()
        val stopRequests = mutableListOf<WalkRuntimeEpoch>()
        val detectorExecutor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queuedWorkRan = AtomicBoolean(false)
        try {
            detectorExecutor.execute {
                val flight = requireNotNull(watchdog.begin(oldEpoch))
                entered.countDown()
                release.await()
                watchdog.complete(flight)
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            detectorExecutor.execute { queuedWorkRan.set(true) }

            assertFalse(watchdog.blocksNewWork())
            assertTrue(activity.rejectUnsafeDetectorAtWalkEntry(
                newEpoch, WalkSessionState.ACTIVE, watchdog, stopRequests::add,
            ))
            assertTrue(activity.rejectUnsafeDetectorAtWalkEntry(
                newEpoch, WalkSessionState.ACTIVE, watchdog, stopRequests::add,
            ))
            assertEquals(listOf(newEpoch), stopRequests)
            assertFalse(queuedWorkRan.get())

            release.countDown()
            detectorExecutor.shutdown()
            assertTrue(detectorExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertFalse(activity.rejectUnsafeDetectorAtWalkEntry(
                newEpoch, WalkSessionState.ACTIVE, watchdog, stopRequests::add,
            ))
            assertNull(watchdog.begin(oldEpoch))
            assertNotNull(watchdog.begin(newEpoch))
            assertEquals(listOf(newEpoch), stopRequests)
        } finally {
            release.countDown()
            detectorExecutor.shutdownNow()
        }
    }

    @Test
    fun newWalkAdmissionRejectsAnOldFrameThatReachesNativeBeginLater() {
        val now = AtomicLong(0L)
        val watchdog = DetectorStallWatchdog(800L, now::get)
        val oldEpoch = WalkRuntimeEpoch("old-walk", 0L)
        val newEpoch = WalkRuntimeEpoch("new-walk", 0L)
        val stopRequests = mutableListOf<WalkRuntimeEpoch>()

        assertFalse(watchdog.rejectUnsafeWalkEntry(
            newEpoch, WalkSessionState.ACTIVE, stopRequests::add,
        ))
        assertNull(watchdog.begin(oldEpoch))
        val currentFlight = requireNotNull(watchdog.begin(newEpoch))
        assertTrue(watchdog.complete(currentFlight))
        assertTrue(stopRequests.isEmpty())
    }

    @Test
    fun normalCompletedFlightAllowsNextWalkWithoutRequestingSafetyStop() {
        val now = AtomicLong(0L)
        val watchdog = DetectorStallWatchdog(800L, now::get)
        val flight = requireNotNull(watchdog.begin())
        val nextEpoch = WalkRuntimeEpoch("next-walk", 0L)
        val stopRequests = mutableListOf<WalkRuntimeEpoch>()

        now.set(799L)
        assertTrue(watchdog.complete(flight))
        assertFalse(watchdog.rejectBlockedWork(nextEpoch, WalkSessionState.ACTIVE, stopRequests::add))
        assertTrue(stopRequests.isEmpty())
        assertNotNull(watchdog.begin())
    }

    @Test
    fun cameraAndArcoreEntrypointsCloseSourceBeforeOwnedNativeDetection() {
        fun owner(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path.kt").readText()
        val main = owner("MainActivity")
        val preflight = owner("device/postlogin/AndroidPostLoginDeviceProbes")
            .substringAfter("val succeeded = try").substringBefore("finally { if (!closed)")
        assertTrue(preflight.indexOf("image.close()") >= 0)
        assertTrue(preflight.indexOf("image.close()") < preflight.indexOf("detector().detect("))
        val fallback = owner("runtime/vision/primary/PrimaryCameraFallbackDetectorWorker")
        assertTrue(fallback.contains("invocationWorker.invoke(prepared, { frameCapture.copyOwnedInputAndClose()?.cameraFrame }"))
        val owned = MainActivityRefactorSource.member(owner("runtime/camera/CameraFallbackController"), "copyOwnedInputAndClose")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(owned, "copyOwnedFrame()", "close()", "return CameraFallbackFrameInput("))
        val recognition = owner("runtime/vision/frame/ArFrameRecognitionCapture")
        assertTrue(recognition.contains("packet?.image?.use(copyInput)"))
        assertTrue(recognition.contains("val ownedFrame = OwnedYuvFrame.copyOf(image)"))
        val invocation = owner("runtime/vision/primary/PrimaryDetectorInvocationWorker")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(invocation, "val ownedFrame = copyOwnedFrame()",
            "detectorOwner.beginNativeFlight(", "detectorOwner.detectOriented(ownedFrame,"))
        val ar = owner("runtime/vision/primary/PrimaryArDetectorWorker")
        assertTrue(ar.contains("invocationWorker.invoke(prepared, { consumeOwnedInput(work.input) }"))
        assertTrue(ar.contains("flight = partial.flight"))
        val execution = owner("runtime/vision/primary/PrimaryDetectorExecutionOwner")
        assertTrue(execution.contains("watchdog.publishIfCurrent(flight.ticket, publish)"))
        assertTrue(execution.contains("rejectBlockedWork()"))
        assertTrue(execution.contains("stopClaim.compareAndSet(false, true)"))
        assertTrue(execution.contains("watchdog.blocksNewWork()"))
        assertTrue(main.contains("detectorWatchdogHandler.postDelayed("))
        assertTrue(main.contains("!rejectBlockedNativeDetectorWork()"))
        assertTrue(main.contains("nativeDetectorStallOwnsWalk(")); assertTrue(main.contains("riskTrusted = false"))
    }

    private fun floats(buffer: ByteBuffer, count: Int): FloatArray = FloatArray(count).also {
        buffer.asFloatBuffer().get(it)
    }
}
