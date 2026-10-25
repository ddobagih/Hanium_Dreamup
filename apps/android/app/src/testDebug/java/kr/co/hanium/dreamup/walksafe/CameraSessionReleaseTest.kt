package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CameraSessionReleaseTest {
    @Test fun failedCloseRetainsTheOwnerAndNeverRepeatsNativeCloseOrAdmitsReplacement() {
        val owner = CameraSessionRelease()
        var calls = 0
        val failure = IllegalStateException("native close")
        val attempt = owner.begin(7L, {}, { calls++; throw failure })
        assertFalse(owner.execute(attempt))
        assertTrue(owner.blocksStart)
        assertSame(failure, owner.failure)
        assertFalse(owner.execute(attempt))
        assertEquals(1, calls)
        assertTrue(runCatching { owner.begin(8L, {}, {}) }.isFailure)
    }

    @Test fun providerFailureStillAttemptsSessionCloseAndKeepsReleaseUnconfirmed() {
        val owner = CameraSessionRelease()
        val events = mutableListOf<String>()
        val attempt = owner.begin(1L,
            { events += "provider"; throw IllegalStateException("provider") },
            { events += "session" })
        assertFalse(owner.execute(attempt))
        assertEquals(listOf("provider", "session"), events)
        assertTrue(owner.blocksStart)
    }

    @Test fun queuedImageReturnsBeforeCloseAndBlockedCloseNeverReopensAdmission() {
        val lane = Executors.newSingleThreadExecutor()
        val imageReturned = CountDownLatch(1)
        val returnImage = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val owner = CameraSessionRelease()
        val attempt = owner.begin(1L, {}, {
            assertEquals(0L, imageReturned.count)
        })
        try {
            lane.execute { returnImage.await(5, TimeUnit.SECONDS); imageReturned.countDown() }
            lane.execute { owner.execute(attempt); finished.countDown() }
            assertTrue(owner.blocksStart)
            returnImage.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertFalse(owner.blocksStart)
        } finally { returnImage.countDown(); lane.shutdownNow() }
    }

    @Test fun oldCompletionCannotClearTheNextReleaseOwner() {
        val owner = CameraSessionRelease()
        val first = owner.begin(1L, {}, {})
        assertTrue(owner.execute(first))
        val second = owner.begin(2L, {}, {})
        assertFalse(owner.execute(first))
        owner.schedulingFailed(first, IllegalStateException("late"))
        assertTrue(owner.blocksStart)
        assertNull(owner.failure)
        assertTrue(owner.execute(second))
    }

    @Test fun rejectedReleaseSchedulingDoesNotPretendTheNativeResourceClosed() {
        val owner = CameraSessionRelease()
        val attempt = owner.begin(1L, { error("not scheduled") }, { error("not scheduled") })
        owner.schedulingFailed(attempt, IllegalStateException("executor rejected"))
        assertTrue(owner.blocksStart)
        assertNotNull(owner.failure)
    }

    @Test fun actualCameraCallsitesUseTheOwnerAndKeepTheInferenceLaneForPublishedImages() {
        val source = File("src/debug/java/kr/co/hanium/dreamup/walksafe/CameraTestActivity.kt").readText()
        assertTrue(source.contains("private val closingCamera get() = cameraRelease.blocksStart"))
        val pause = source.substringAfter("override fun onPause() {").substringBefore("override fun onDestroy()")
        assertTrue(pause.indexOf("surface.onPause()") < pause.indexOf("camera.pause()"))
        assertTrue(pause.indexOf("camera.pause()") < pause.indexOf("queueCameraRelease("))
        assertTrue(pause.contains("queueCameraRelease(camera, source, closingSessionEpoch, worker, retry = true)"))
        val release = source.substringAfter("private fun queueCameraRelease(").substringBefore("override fun onRequestPermissionsResult")
        assertTrue(release.contains("cameraRelease.begin(epoch"))
        assertTrue(release.contains("cameraRelease.execute(attempt)"))
        assertTrue(release.contains("else if (!released)"))
    }
}
