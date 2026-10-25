package kr.co.hanium.dreamup.walksafe.report.runtime

import java.util.ArrayDeque
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class PostWalkTransferControllerTest {
    @Test fun constructionDoesNotReadChargingOrRegisterOrCreateRetry() {
        val f = Fixture()
        assertTrue(f.events.isEmpty())
    }
    @Test fun foregroundEndedWalkMarksRawBeforeQueueAndBackgroundStartsNeither() {
        val f = Fixture(); f.controller.schedulePostWalkTransfers()
        assertEquals(listOf("mark:${f.walkId}","raw","queue"),f.events)
        f.events.clear(); f.foreground=false; f.controller.schedulePostWalkTransfers()
        assertTrue(f.events.isEmpty())
    }
    @Test fun unavailableRawStillAllowsExistingReportQueueCheck() {
        val f=Fixture(); f.rawReady=false; f.controller.schedulePostWalkTransfers()
        assertEquals(listOf("queue"),f.events)
    }
    @Test fun delayedRetryCoalescesPerWalkAndUsesOriginal105SecondDelay() {
        val f=Fixture(); f.controller.schedulePostWalkRetryAfterWalkInProgress(); f.controller.schedulePostWalkRetryAfterWalkInProgress()
        assertTrue(f.events.isEmpty()); f.runMain()
        assertEquals(listOf("remove","retry:105000"),f.events)
        f.walkId="new-walk"; f.controller.schedulePostWalkRetryAfterWalkInProgress(); f.runMain()
        assertEquals(2,f.events.count { it=="retry:105000" })
    }
    @Test fun lifecycleChangedBeforeRetryPublicationSuppressesTimer() {
        val f=Fixture(); f.controller.schedulePostWalkRetryAfterWalkInProgress(); f.destroyed=true; f.runMain()
        assertTrue(f.events.isEmpty())
    }
    @Test fun chargingStartsOnlyOnFalseToTrueAndReceiverRegistrationIsIdempotent() {
        val f=Fixture(); f.controller.registerPostWalkPowerConnectedReceiver(); f.controller.registerPostWalkPowerConnectedReceiver()
        assertEquals(1,f.events.count { it=="register" })
        assertFalse(f.events.contains("raw"))
        f.charging=true; f.controller.onPostWalkChargingMaybeChanged(); f.controller.onPostWalkChargingMaybeChanged()
        assertEquals(1,f.events.count { it=="raw" })
        f.controller.unregisterPostWalkPowerConnectedReceiver(); f.controller.unregisterPostWalkPowerConnectedReceiver()
        assertEquals(1,f.events.count { it=="unregister" })
    }
    private class Fixture:PostWalkTransferController.Ports {
        val events=mutableListOf<String>()
        val main=ArrayDeque<()->Unit>()
        var walkId="test-walk"
        var foreground=true
        var rawReady=true
        var destroyed=false
        var charging=false
        val controller=PostWalkTransferController(this)
        fun runMain(){while(main.isNotEmpty())main.removeFirst().invoke()}
        override fun isForeground()=foreground
        override fun isWalkReady()=true
        override fun walkSnapshot()=WalkSessionSnapshot(WalkRuntimeEpoch(walkId,0L),WalkSessionState.ENDED,WalkSessionMode.UNAVAILABLE,null,true,null)
        override fun isRawReady()=rawReady
        override fun markRawUploadWalk(walkId:String){events.add("mark:$walkId")}
        override fun scheduleRawUpload(){events.add("raw")}
        override fun maybeStartReportQueueDrain(){events.add("queue")}
        override fun runOnUiThread(action:()->Unit){main.add(action)}
        override fun isFinishing()=false
        override fun isDestroyed()=destroyed
        override fun removeRetry(task:Runnable){events.add("remove")}
        override fun postRetry(task:Runnable,delayMs:Long){events.add("retry:$delayMs")}
        override fun registerPowerReceiver():Boolean{events.add("register");return true}
        override fun unregisterPowerReceiver(){events.add("unregister")}
        override fun isCharging()=charging
    }
}
