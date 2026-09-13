package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidRuntimeLoadObservationSourceTest {
    @Test
    fun cameraTimestampDeltasUseOnlyTheirOwnClockAndTheActualSelectedCadence() {
        val fixture = Fixture()
        fixture.activate()
        fixture.frame(33L, cameraTimestampNs = 1_033_000_000L)
        val thirtyFps = checkNotNull(fixture.source.latestObservation(33L))
        assertEquals(33L, thirtyFps.cameraFrameIntervalMs)
        assertEquals(33L, thirtyFps.expectedCameraFrameIntervalMs)
        fixture.cameraBudgetMs = 16L
        fixture.frame(50L, cameraTimestampNs = 1_050_000_000L)
        assertNull(fixture.source.latestObservation(50L))
        fixture.frame(66L, cameraTimestampNs = 1_066_000_000L)
        val sixtyFps = checkNotNull(fixture.source.latestObservation(66L))
        assertEquals(16L, sixtyFps.cameraFrameIntervalMs)
        assertEquals(16L, sixtyFps.expectedCameraFrameIntervalMs)
    }

    @Test
    fun depthUsesCurrentFrameBudgetAndTrackingUsesItsOwnMeasuredBudget() {
        val fixture = Fixture()
        fixture.activate()
        fixture.frame(33L, depthMs = 12L, trackingMs = 8L, trackingBudgetMs = 10L)
        val observation = checkNotNull(fixture.source.latestObservation(33L))
        assertEquals(12L, observation.depthProcessingMs)
        assertEquals(33L, observation.depthBudgetMs)
        assertEquals(8L, observation.trackingProcessingMs)
        assertEquals(10L, observation.trackingBudgetMs)
    }

    @Test
    fun missingBudgetsAndInvalidDurationsCannotBecomeHealthyMeasurements() {
        val fixture = Fixture()
        fixture.cameraBudgetMs = null
        fixture.uiBudgetMs = null
        fixture.activate()
        fixture.frame(33L, depthMs = 5L, trackingMs = 8L, trackingBudgetMs = null)
        fixture.dispatch(500L)
        assertNull(fixture.source.latestObservation(500L))
        fixture.frame(550L, depthMs = -1L, trackingMs = -1L, trackingBudgetMs = 20L)
        assertNull(fixture.source.latestObservation(550L))
        fixture.frame(580L, trackingMs = 12L, trackingBudgetMs = 20L)
        val trackingOnly = checkNotNull(fixture.source.latestObservation(580L))
        assertEquals(12L, trackingOnly.trackingProcessingMs)
        assertNull(trackingOnly.depthProcessingMs)
        assertNull(trackingOnly.cameraFrameIntervalMs)
    }

    @Test
    fun asynchronousObservationsKeepTheirOwnTimestampAndAreConsumedOnlyOnce() {
        val fixture = Fixture()
        fixture.activate()
        fixture.frame(100L)
        fixture.dispatch(530L)
        val camera = checkNotNull(fixture.source.latestObservation(530L))
        val ui = checkNotNull(fixture.source.latestObservation(530L))
        assertEquals(100L, camera.observedAtElapsedRealtimeMs)
        assertNull(camera.uiDispatchDelayMs)
        assertEquals(530L, ui.observedAtElapsedRealtimeMs)
        assertEquals(30L, ui.uiDispatchDelayMs)
        assertEquals(17L, ui.uiDispatchBudgetMs)
        assertNull(ui.cameraFrameIntervalMs)
        assertNull(fixture.source.latestObservation(530L))
    }

    @Test
    fun onlyLatestCameraSampleIsRetainedAndAnUnconsumedUiProbeIsNotStarved() {
        val fixture = Fixture()
        fixture.activate()
        fixture.frame(100L)
        fixture.frame(200L)
        fixture.frame(300L)
        fixture.dispatch(530L)
        fixture.frame(550L)
        assertEquals(530L, checkNotNull(fixture.source.latestObservation(550L)).observedAtElapsedRealtimeMs)
        assertEquals(550L, checkNotNull(fixture.source.latestObservation(550L)).observedAtElapsedRealtimeMs)
        assertNull(fixture.source.latestObservation(550L))
    }

    @Test
    fun staleAndFutureSamplesAreDiscardedWithoutReStamping() {
        val fixture = Fixture()
        fixture.activate()
        fixture.frame(100L)
        assertEquals(100L, checkNotNull(fixture.source.latestObservation(600L)).observedAtElapsedRealtimeMs)
        fixture.frame(700L)
        assertNull(fixture.source.latestObservation(1_201L))
        fixture.frame(2_000L)
        assertNull(fixture.source.latestObservation(1_999L))
        assertNull(fixture.source.latestObservation(2_000L))
    }

    @Test
    fun repeatingTheSameCameraFrameCannotReStampItsOldTrackingDuration() {
        val fixture = Fixture()
        fixture.activate()
        fixture.frame(100L, cameraTimestampNs = 1_100_000_000L, trackingMs = 10L, trackingBudgetMs = 20L)
        assertNotNull(fixture.source.latestObservation(100L))
        fixture.frame(200L, cameraTimestampNs = 1_100_000_000L, trackingMs = 10L, trackingBudgetMs = 20L)
        assertNull(fixture.source.latestObservation(200L))
    }

    @Test
    fun anOutOfOrderCameraTimestampDoesNotEraseTheDuplicateFrameGuard() {
        val fixture = Fixture()
        fixture.activate()
        fixture.frame(100L, cameraTimestampNs = 1_100_000_000L, trackingMs = 10L, trackingBudgetMs = 20L)
        assertNotNull(fixture.source.latestObservation(100L))
        fixture.frame(200L, cameraTimestampNs = 1_099_000_000L)
        fixture.frame(300L, cameraTimestampNs = 1_100_000_000L, trackingMs = 10L, trackingBudgetMs = 20L)
        assertNull(fixture.source.latestObservation(300L))
    }

    @Test
    fun uiQueueDelayUsesHandlerUptimeAndDoesNotCountDeepSleepAsContention() {
        val fixture = Fixture()
        fixture.activate()
        fixture.dispatch(530L, elapsedMs = 5_530L)
        val ui = checkNotNull(fixture.source.latestObservation(5_530L))
        assertEquals(30L, ui.uiDispatchDelayMs)
        assertEquals(5_530L, ui.observedAtElapsedRealtimeMs)
    }

    @Test
    fun aChangedDisplayModeDoesNotReuseThePreviousUiBudget() {
        val fixture = Fixture()
        fixture.activate()
        fixture.uiBudgetMs = 9L
        fixture.dispatch(530L)
        assertNull(fixture.source.latestObservation(530L))
        fixture.dispatch(1_050L)
        val ui = checkNotNull(fixture.source.latestObservation(1_050L))
        assertEquals(20L, ui.uiDispatchDelayMs)
        assertEquals(9L, ui.uiDispatchBudgetMs)
    }

    @Test
    fun pauseRemovesTheProbeAndRejectsItsLateCallbackAfterResume() {
        val fixture = Fixture()
        fixture.activate()
        val oldCallback = fixture.scheduler.pending.single().task
        fixture.frame(33L)
        fixture.source.pause()
        assertEquals(0, fixture.scheduler.pending.size)
        assertNull(fixture.source.latestObservation(33L))
        fixture.frame(66L)
        assertNull(fixture.source.latestObservation(66L))
        fixture.source.start()
        oldCallback.run()
        assertEquals(1, fixture.scheduler.pending.size)
        assertNull(fixture.source.latestObservation(66L))
        fixture.source.close()
        fixture.source.start()
        assertEquals(0, fixture.scheduler.pending.size)
    }

    @Test
    fun aDelayedFrameMeasuredBeforeResumeCannotPublishOldLoadInTheNewSession() {
        val fixture = Fixture()
        fixture.activate()
        fixture.elapsedMs = 110L
        fixture.source.pause()
        fixture.elapsedMs = 120L
        fixture.source.start()
        fixture.frame(100L, trackingMs = 60L, trackingBudgetMs = 20L)
        assertNull(fixture.source.latestObservation(125L))
        fixture.frame(130L, trackingMs = 10L, trackingBudgetMs = 20L)
        assertEquals(130L, checkNotNull(fixture.source.latestObservation(130L)).observedAtElapsedRealtimeMs)
    }

    @Test
    fun aDelayedOldEnvironmentFrameCannotRollBackTheCurrentObservationScope() {
        val fixture = Fixture()
        fixture.activate()
        fixture.source.recordFrame(2_000_000_000L, 200L, null, 60L, 20L, "CPU:2")
        fixture.frame(100L, trackingMs = 10L, trackingBudgetMs = 20L)
        val current = checkNotNull(fixture.source.latestObservation(210L))
        assertEquals(200L, current.observedAtElapsedRealtimeMs)
        assertEquals(60L, current.trackingProcessingMs)
    }

    @Test
    fun unavailableCameraOrDisplayModeStaysUnknownWithoutBreakingTheCallback() {
        val scheduler = FakeScheduler()
        val source = AndroidRuntimeLoadObservationSource(
            scheduler, { throw IllegalStateException("camera closed") },
            { throw IllegalStateException("display unavailable") }, { 0L }, { 0L },
        )
        source.start()
        source.recordFrame(1L, 0L, 10L, 5L, 20L, "GPU:4")
        scheduler.pending.removeAt(0).task.run()
        val tracking = checkNotNull(source.latestObservation(0L))
        assertEquals(5L, tracking.trackingProcessingMs)
        assertNull(tracking.depthProcessingMs)
        assertNull(tracking.uiDispatchDelayMs)
        assertEquals(1, scheduler.pending.size)
    }

    @Test
    fun environmentChangeDiscardsOldFrameAndAlreadyQueuedUiMeasurements() {
        val fixture = Fixture()
        fixture.activate()
        fixture.frame(33L, trackingMs = 10L, trackingBudgetMs = 20L)
        fixture.source.recordFrame(1_050_000_000L, 50L, null, null, null, "CPU:2")
        assertNull(fixture.source.latestObservation(50L))
        fixture.dispatch(550L)
        assertNull(fixture.source.latestObservation(550L))
    }

    @Test
    fun repeatedStartAndSlowUiKeepOnlyOnePendingProbe() {
        val fixture = Fixture()
        fixture.activate()
        repeat(10) { fixture.source.start() }
        assertEquals(1, fixture.scheduler.pending.size)
        fixture.dispatch(5_000L)
        assertEquals(1, fixture.scheduler.pending.size)
        assertEquals(500L, fixture.scheduler.pending.single().delayMs)
        fixture.source.pause()
        assertEquals(0, fixture.scheduler.pending.size)
    }

    @Test
    fun rejectedUiPostDoesNotProduceAHealthyUiObservationOrStopCameraMeasurements() {
        val fixture = Fixture()
        fixture.scheduler.acceptPosts = false
        fixture.source.start()
        fixture.frame(0L)
        fixture.frame(33L)
        val observation = checkNotNull(fixture.source.latestObservation(33L))
        assertNull(observation.uiDispatchDelayMs)
        assertEquals(0, fixture.scheduler.pending.size)
        assertFalse(fixture.scheduler.acceptPosts)
    }

    private class Fixture {
        var elapsedMs = 0L
        var uptimeMs = 0L
        var cameraBudgetMs: Long? = 33L
        var uiBudgetMs: Long? = 17L
        val scheduler = FakeScheduler()
        val source = AndroidRuntimeLoadObservationSource(
            scheduler, { cameraBudgetMs }, { uiBudgetMs }, { elapsedMs }, { uptimeMs },
        )

        fun activate() {
            source.start()
            frame(0L)
            dispatch(0L)
        }

        fun frame(
            observedAtMs: Long,
            cameraTimestampNs: Long = 1_000_000_000L + observedAtMs * 1_000_000L,
            depthMs: Long? = null,
            trackingMs: Long? = null,
            trackingBudgetMs: Long? = null,
        ) {
            source.recordFrame(cameraTimestampNs, observedAtMs, depthMs, trackingMs, trackingBudgetMs, "GPU:4")
        }

        fun dispatch(uptimeMs: Long, elapsedMs: Long = uptimeMs) {
            this.uptimeMs = uptimeMs
            this.elapsedMs = elapsedMs
            scheduler.pending.removeAt(0).task.run()
        }
    }

    private class FakeScheduler : RuntimeLoadUiScheduler {
        data class Pending(val task: Runnable, val delayMs: Long)
        val pending = ArrayList<Pending>()
        var acceptPosts = true

        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            if (acceptPosts) pending.add(Pending(task, delayMs))
            return acceptPosts
        }

        override fun remove(task: Runnable) {
            pending.removeAll { it.task === task }
        }
    }
}
