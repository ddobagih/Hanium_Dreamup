package kr.co.hanium.dreamup.walksafe.rawcollection

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.MainActivity
import org.junit.Assert.*
import org.junit.Test

/** Actual Main executor/admission/main looper with synthetic harmless tasks and a latch stall.
 * No camera, consent, account, walking readiness, storage speed, or production data is fabricated. */
class RawBacklogDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun field(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a)
    private fun call(a:MainActivity,name:String,vararg args:Any?):Any? = MainActivity::class.java.declaredMethods.single {
        it.name==name && it.parameterCount==args.size
    }.apply{isAccessible=true}.invoke(a,*args)
    private fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
    private fun report(message:String)=instrumentation.sendStatus(0,Bundle().apply{putString("stream","RAW_BACKLOG $message\n")})
    private fun launch():MainActivity {
        val a=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val deadline=SystemClock.elapsedRealtime()+5_000
        var ready=false
        while(!ready && SystemClock.elapsedRealtime()<deadline){
            main {ready=a.hasWindowFocus() && field(a,"rawCollectionRuntimeCoordinator")!=null}
            if(!ready) SystemClock.sleep(50)
        }
        assertTrue("real Main window and coordinator must be ready",ready)
        main {
            assertEquals(false,call(a,"isWalkSessionRuntimeActive"))
            assertEquals(false,field(a,"voiceRecognitionActive"))
            assertTrue("do not test inside a preparation or walking screen",
                (field(a,"nativeUiPage") as Enum<*>).name in setOf("HOME","LOGIN"))
        }
        return a
    }

    @Test fun stalledMainWorkerBoundsMetadataButKeepsUiAndControlDispatchAvailable() {
        val a=launch();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val timedOut=java.util.concurrent.atomic.AtomicBoolean(false)
        val events=Collections.synchronizedList(mutableListOf<String>())
        val worker=field(a,"rawCollectionExecutor") as ExecutorService
        val admission=field(a,"rawMetadataTaskAdmission") as RawMetadataTaskAdmission
        try {
            worker.submit{}.get(5,TimeUnit.SECONDS)
            assertTrue(admission.submit(worker,{events.add("reset")}) {
                events.add("before-0");entered.countDown()
                if(!release.await(15,TimeUnit.SECONDS)) timedOut.set(true)
            })
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            for(i in 1..7) assertTrue(admission.submit(worker,{events.add("reset")}){events.add("before-$i")})
            val rejected=AtomicInteger()
            main {
                repeat(1000){if(!admission.submit(worker,{events.add("reset")}){events.add("unexpected")}) rejected.incrementAndGet()}
                call(a,"executeRawCollectionTask",{events.add("control")} as ()->Unit)
            }
            assertEquals(1000,rejected.get())
            val uiTicks=CountDownLatch(10);val handler=Handler(Looper.getMainLooper())
            val tick=object:Runnable {override fun run(){uiTicks.countDown();if(uiTicks.count>0)handler.postDelayed(this,20)}}
            try {
                handler.post(tick);assertTrue("main looper must run while raw worker is stalled",uiTicks.await(3,TimeUnit.SECONDS))
            } finally {handler.removeCallbacks(tick)}
            assertEquals(listOf("before-0"),events.toList())
            release.countDown();worker.submit{}.get(5,TimeUnit.SECONDS)
            assertFalse("the worker must resume by test release, not timeout",timedOut.get())
            assertEquals((0..7).map{"before-$it"}+"control",events.toList())
            assertTrue(admission.submit(worker,{events.add("reset")}){events.add("after-gap")})
            worker.submit{}.get(5,TimeUnit.SECONDS)
            assertEquals((0..7).map{"before-$it"}+listOf("control","reset","after-gap"),events.toList())
            assertTrue(admission.submit(worker,{events.add("reset")}){events.add("following")})
            worker.submit{}.get(5,TimeUnit.SECONDS)
            assertEquals(1,events.count{it=="reset"})
            report("actual_main_worker=true metadata_capacity=8 rejected=1000 main_looper_callbacks=10 control_not_dropped=true reset_after_gap_once=true simulated_stall=true")
        } finally {release.countDown();main{a.finish()}}
    }

    @Test fun mainDestructionInterruptsRunningWorkAndDropsQueuedMetadata() {
        val a=launch();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val interrupted=CountDownLatch(1);val queuedExecuted=AtomicInteger()
        val worker=field(a,"rawCollectionExecutor") as ExecutorService
        val admission=field(a,"rawMetadataTaskAdmission") as RawMetadataTaskAdmission
        try {
            worker.submit{}.get(5,TimeUnit.SECONDS)
            assertTrue(admission.submit(worker,{}) {
                entered.countDown()
                try {release.await(15,TimeUnit.SECONDS)} catch(_:InterruptedException){interrupted.countDown()}
            })
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            repeat(7){assertTrue(admission.submit(worker,{}){queuedExecuted.incrementAndGet()})}
            main{a.finish()}
            assertTrue("onDestroy must interrupt the actual worker",interrupted.await(5,TimeUnit.SECONDS))
            assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));assertTrue(worker.isShutdown)
            assertEquals(0,queuedExecuted.get())
            instrumentation.waitForIdleSync();assertTrue(a.isDestroyed)
            val after=AtomicInteger()
            main{call(a,"executeRawCollectionTask",{after.incrementAndGet()} as ()->Unit)}
            assertEquals(0,after.get())
            report("actual_main_destroyed=true running_task_interrupted=true queued_metadata_dispatched=0 post_destroy_task_dispatched=0 readiness_bypass=false")
        } finally {release.countDown();main{if(!a.isDestroyed)a.finish()}}
    }
}
