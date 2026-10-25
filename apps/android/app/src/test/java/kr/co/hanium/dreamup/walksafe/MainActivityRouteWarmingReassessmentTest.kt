package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.*
import org.junit.Test

/** Actual Main scheduler ownership; the Android queue and elapsed clock are controlled effects. */
class MainActivityRouteWarmingReassessmentTest {
    @Test fun repeatedSensorRefreshDoesNotMoveTheFixedReassessmentOrCreateDuplicatePolls() {
        val f=F();repeat(30){f.schedule()}
        assertEquals(1,f.queue.size);assertEquals(300L,f.queue.single().second)
        f.now=8999L;f.queue.removeAt(0).first.run()
        assertEquals(listOf(8999L),f.observed)
        assertEquals(1L,f.queue.single().second)
        f.now=9000L;f.queue.removeAt(0).first.run()
        assertEquals(listOf(8999L,9000L),f.observed)
        assertEquals(1000L,f.queue.single().second)
        repeat(20){f.schedule()};assertEquals(1,f.queue.size)
        assertEquals(9000L,f.due)
    }
    @Test fun recoveredModelOrRetiredOwnerStopsPollingWithoutAnotherAssessment() {
        for (recover in listOf(true,false)) {
            val f=F();f.schedule();if(recover)f.due=null else f.current=false
            f.queue.removeAt(0).first.run();assertTrue(f.observed.isEmpty());assertTrue(f.queue.isEmpty())
        }
    }
    @Test fun cancellationRejectsAnOldPollAfterANewSourceStarts() {
        val f=F();f.schedule();val old=f.queue.removeAt(0).first
        f.main.cancelRouteWarmingReassessment { f.removed+=it }
        f.schedule();val current=f.queue.single().first
        old.run();assertTrue(f.observed.isEmpty());assertSame(current,f.queue.single().first)
        assertEquals(listOf(old),f.removed)
        f.now=9000L;f.queue.removeAt(0).first.run();assertEquals(listOf(9000L),f.observed)
    }
    private class F {
        val main=MainActivity();var now=0L;var due:Long?=9000L;var current=true
        val queue=mutableListOf<Pair<Runnable,Long>>();val observed=mutableListOf<Long>();val removed=mutableListOf<Runnable>()
        fun schedule()=main.scheduleRouteWarmingReassessment({due},{observed+=it},{now},{current},{r,d->queue+=r to d})
    }
}
