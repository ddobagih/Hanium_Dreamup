package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class InitialRouteRetryEpisodeTest {
    @Test fun retriesKeepOneIntentAndFirstFailureAcrossBoundedFailureBackoff() {
        val f = Fixture()
        val episode = f.fail()
        listOf(2_000L, 4_000L, 8_000L, 10_000L, 10_000L).forEachIndexed { index, delay ->
            assertEquals(delay, episode.delayMs)
            assertEquals(f.now + delay, episode.retryAtMs)
            assertEquals(1_000L, episode.requestedAtMs)
            assertEquals(2_000L, episode.firstFailureAtMs)
            f.now = episode.retryAtMs
            assertTrue(f.owner.eligibleAt(f.now))
            f.owner.bindRequest(index + 2)
            assertSame(episode, f.fail(requestId = index + 2))
        }
    }

    @Test fun pollingRetainsDeadlineAndDoesNotAdvanceTheFailureEpisode() {
        val f = Fixture()
        val episode = f.fail()
        f.owner.schedule(episode)
        assertFalse(f.owner.eligibleAt(3_999L))
        f.fire()
        repeat(3) {
            assertEquals(1_000L, f.scheduler.pending.single().second)
            assertEquals(4_000L, episode.retryAtMs)
            assertEquals(2_000L, episode.firstFailureAtMs)
            assertEquals(2_000L, episode.delayMs)
            f.fire()
        }
        assertEquals(4, f.polls)
    }

    @Test fun aRemovedTimerCannotTouchTheReplacementEpisodeEvenWhenInvokedLate() {
        val f = Fixture()
        val oldEpisode = f.fail()
        f.owner.schedule(oldEpisode)
        val late = f.scheduler.pending.single().first
        f.owner.clear()
        f.now = 3_000L
        val replacement = f.fail(requestedAtMs = 2_500L, requestId = 2)
        f.owner.schedule(replacement)
        val replacementTimer = f.scheduler.pending.single()
        late.run()
        f.owner.schedule(oldEpisode)
        assertEquals(0, f.polls)
        assertSame(replacement, f.owner.current)
        assertEquals(replacementTimer, f.scheduler.pending.single())
    }

    @Test fun bindingANewRequestRetiresThePreviousTimerWithoutReplacingTheEpisode() {
        val f = Fixture()
        val episode = f.fail()
        f.owner.schedule(episode)
        val late = f.scheduler.pending.single().first
        f.owner.bindRequest(2)
        late.run()
        assertEquals(0, f.polls)
        assertTrue(f.scheduler.pending.isEmpty())
        assertSame(episode, f.owner.current)
        assertEquals(2, episode.requestId)
        assertEquals(4_000L, episode.retryAtMs)
    }

    @Test fun replacementDuringRetryCannotBeOverwrittenByTheRetiredPollingCallback() {
        val f = Fixture()
        val episode = f.fail()
        f.owner.schedule(episode)
        lateinit var replacement: InitialRouteRetryEpisode.Episode
        f.onRetry = {
            f.owner.clear()
            replacement = f.fail(requestedAtMs = f.now, requestId = 2)
            f.owner.schedule(replacement)
            true
        }
        f.fire()
        assertSame(replacement, f.owner.current)
        assertEquals(2_000L, f.scheduler.pending.single().second)
        assertEquals(1, f.polls)
    }

    @Test fun staleFailureContextAndBackwardClockCannotMutateAnExistingIntent() {
        val f = Fixture()
        val episode = f.fail()
        assertNull(f.owner.retainFailure(f.context.copy(destination = RoutePoint(38.0, 128.0)), 1_000L, 2))
        assertNull(f.owner.retainFailure(f.context, 1_500L, 2))
        f.now = 1_999L
        assertNull(f.owner.retainFailure(f.context, 1_000L, 2))
        assertSame(episode, f.owner.current)
        assertEquals(1, episode.requestId)
        assertEquals(2_000L, episode.delayMs)
        assertEquals(4_000L, episode.retryAtMs)
    }

    @Test fun consumedIntentDoesNotLeaveAPollAndDuplicateTimerExecutionDoesNothing() {
        val f = Fixture()
        f.onRetry = { false }
        f.owner.schedule(f.fail())
        val late = f.scheduler.pending.single().first
        f.fire(); late.run()
        assertEquals(1, f.polls)
        assertTrue(f.scheduler.pending.isEmpty())
    }

    private class Fixture {
        var now = 2_000L
        var polls = 0
        var onRetry: () -> Boolean = { true }
        val scheduler = Scheduler()
        val context = RouteAcquisitionController.ExplicitRouteStartContext(
            WalkRuntimeEpoch("walk-initial-retry", 0L), 1L, RoutePoint(37.0, 127.0),
        )
        val owner = InitialRouteRetryEpisode(scheduler, { now }) { polls++; onRetry() }
        fun fail(requestedAtMs: Long = 1_000L, requestId: Int = 1) =
            requireNotNull(owner.retainFailure(context, requestedAtMs, requestId))
        fun fire() {
            val (callback, delay) = scheduler.pending.removeAt(0)
            now += delay
            callback.run()
        }
    }

    private class Scheduler : RouteCallbackScheduler {
        val pending = mutableListOf<Pair<Runnable, Long>>()
        override fun post(task: Runnable) = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long) { pending += task to delayMs }
        override fun remove(task: Runnable) { pending.removeAll { it.first === task } }
    }
}
