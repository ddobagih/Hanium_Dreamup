package kr.co.hanium.dreamup.walksafe

import android.location.Location
import kr.co.hanium.dreamup.walksafe.device.environment.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputController
import org.junit.Assert.*
import org.junit.Test
import org.junit.After

/** Actual GPS expiry and recovery through Main/Guide/Output; native and named SDK leaves are controlled. */
class GpsAfterDeadlineRecoveryTraceTest {
    @After fun clearGateway() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }
    @Test fun cameraHealthyGpsAloneRecoversThroughNativeStartAndDone() = traceRecovery(true)
    @Test fun cameraUnknownGpsRecoversFromSuppressionThroughNativeStartAndDone() = traceRecovery(false)
    private fun traceRecovery(cameraHealthy:Boolean) {
        val f=Fixture(cameraHealthy)
        try {
            f.start();val originalBinding=f.position.binding;val originalCallback=f.position.callback
            val originalEpoch=f.base.lifecycle.snapshot().epoch;val originalRun=f.runState()
            val originalRoute=originalRun.navigator.currentRouteId();val account=GatewaySessionProcessCoordinator.snapshot()
            val oldNative=f.native
            val oldSpeechId=read<String>(read<List<Any>>(oldNative,"spoken").last(),"id")
            val oldCue:RouteSpeechCueToken=read(originalRun.navigator,"pendingSpeechCue")
            println("BEFORE active=${originalRun.active} state=${f.base.lifecycle.snapshot().state} route=$originalRoute epoch=$originalEpoch")
            f.step(100);val onset=f.base.now;f.location(Float.NaN)
            repeat(9){f.step(300);f.location(Float.NaN)};f.step(299)
            assertEquals(2999L,f.base.now-onset);assertEquals(0,f.stops())
            assertEquals(onset+GuidanceRecoveryTiming.GPS_GRACE_MS,f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
            val originalPassAt=checkNotNull(f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.observedAtElapsedRealtimeMs)
            assertEquals(6000L,originalPassAt)
            val restrictionAt=minOf(onset+GuidanceRecoveryTiming.GPS_GRACE_MS,originalPassAt+10_001L)
            assertTrue(restrictionAt<onset+GuidanceRecoveryTiming.GPS_GRACE_MS)
            while(f.base.now+300L<restrictionAt) { f.step(300);f.location(Float.NaN) }
            f.step(restrictionAt-f.base.now-1L)
            assertEquals(originalPassAt+10_000L,f.base.now)
            assertEquals(EnvironmentEvidenceStatus.PASS,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertEquals(0,f.stops())
            f.step(1)
            assertEquals(EnvironmentEvidenceStatus.FAIL,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertEquals(1,f.stops())
            println("EXPIRED cameraHealthy=$cameraHealthy active=${originalRun.active} state=${f.base.lifecycle.snapshot().state} epoch=${f.base.lifecycle.snapshot().epoch} pause=${originalRun.directionPauseReason} positionPaused=${f.guide.positionPaused} runtime=${f.runtime().guard?.decision()} actor=${f.output.currentActuatorOrNull()} stops=${f.stops()} statuses=${f.statuses} explanations=${f.explanations}")
            assertEquals(if(cameraHealthy) OfficialEnvironmentRuntimeAction.CONTINUE else OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY,f.runtime().guard?.decision()?.action)
            assertSame(originalBinding,f.position.binding);assertSame(originalCallback,f.position.callback)
            assertEquals(originalEpoch,f.base.lifecycle.snapshot().epoch)
            assertEquals(originalRoute,originalRun.navigator.currentRouteId())
            val currentAccount=GatewaySessionProcessCoordinator.snapshot()
            assertSame(account.session,currentAccount.session);assertEquals(account.generation,currentAccount.generation)
            assertEquals(account.storageBlocked,currentAccount.storageBlocked);assertEquals(account.deletionRecoveryOnly,currentAccount.deletionRecoveryOnly)
            // Closing the native actuator requests STOP; the real asynchronous terminal retires
            // the old ACTION before the current environment notice can acquire the idle slot.
            call<Unit>(oldNative,"stopCallback",oldSpeechId)
            // The notice waits for its production retry after the old ACTION's native terminal.
            f.runRegisteredRecheckRetry()
            assertFalse(originalRun.navigator.hasPendingInstruction())
            assertNotEquals(oldCue,read<Any?>(originalRun.navigator,"completedSpeechCue"))
            val explanationNative=f.newestNative()
            assertNotSame(oldNative,explanationNative)
            assertNotNull("Actual status explanation must create its actuator",f.output.currentActuatorOrNull())
            call<Unit>(explanationNative,"ready")
            val explanationSpoken=read<List<Any>>(explanationNative,"spoken").last()
            val explanationId=read<String>(explanationSpoken,"id")
            val explanationText=read<String>(explanationSpoken,"message")
            assertEquals(f.explanations.last(),explanationText)
            call<Unit>(explanationNative,"start",explanationId)
            assertTrue(f.output.currentActuatorOrNull()?.isAppSpeechActive()==true)
            call<Unit>(explanationNative,"done",explanationId)
            f.beginRecoveryStatusCallbacks(explanationId)
            assertFalse(f.output.hasPendingCommandResponse())
            println("EXPLANATION_DONE cameraHealthy=$cameraHealthy actorCreated=${explanationNative!==oldNative} text=${read<String>(explanationSpoken,"message")} pendingCommand=${f.output.hasPendingCommandResponse()}")
            f.step(100);val recoveryAt=f.base.now;f.location(15f)
            println("GOOD cameraHealthy=$cameraHealthy active=${originalRun.active} state=${f.base.lifecycle.snapshot().state} epoch=${f.base.lifecycle.snapshot().epoch} pause=${originalRun.directionPauseReason} positionPaused=${f.guide.positionPaused} runtime=${f.runtime().guard?.decision()} fix=${f.position.trustedLocation} actor=${f.output.currentActuatorOrNull()} nativeChanged=${f.newestNative()!==oldNative}")
            assertEquals(EnvironmentEvidenceStatus.PASS,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertEquals(recoveryAt,f.position.trustedLocation?.elapsedRealtimeMs)
            assertTrue(f.runtime().outputsAllowed);assertTrue(originalRun.active)
            assertEquals(originalEpoch,f.base.lifecycle.snapshot().epoch)
            assertEquals(originalRoute,originalRun.navigator.currentRouteId())
            assertSame(originalBinding,f.position.binding);assertSame(originalCallback,f.position.callback)
            var furtherGoodSamples=0
            fun currentDirection():Any?=read<List<Any>>(f.newestNative(),"spoken").lastOrNull {
                read<String>(it,"message").contains("가장 가까운 경로 지점")
            }
            while(currentDirection()==null && furtherGoodSamples<30) {
                f.step(300);f.location(15f);furtherGoodSamples++
                println("FURTHER_GOOD cameraHealthy=$cameraHealthy count=$furtherGoodSamples at=${f.base.now} fix=${f.position.trustedLocation} positionPaused=${f.guide.positionPaused} directionOffered=${currentDirection()!=null}")
            }
            assertNotNull("Normal GPS observations must offer guidance before any explicit retry",currentDirection())
            repeat(4){f.step(100);f.guide.retryCurrentNavigationSpeechAfterReady(f.base.now)}
            val spoken=read<List<Any>>(f.newestNative(),"spoken")
            println("RECOVERY_NATIVE oldStops=${read<Int>(oldNative,"stops")} newActor=${f.newestNative()!==oldNative} spoken=${spoken.map{read<String>(it,"message")}} pending=${read<Any?>(originalRun.navigator,"pendingSpeechCue")} completed=${read<Any?>(originalRun.navigator,"completedSpeechCue")}")
            println("RECOVERY_GUIDE resumePending=${f.guide.resumePending} pendingRetry=${read<Any?>(f.guide,"navigationSpeechRetryRunnable")} pendingRecovery=${read<Any?>(f.guide,"pendingRouteRecoverySpeech")} trace=${f.traceLines()} offeredFacing=${read<Any?>(originalRun.navigator,"offeredFacing")} context=${read<Any?>(originalRun.navigator,"latestGuidanceContext")}")
            assertNotSame(oldNative,f.newestNative())
            assertNotNull("Recovery should automatically offer a current route instruction",currentDirection())
            val next=requireNotNull(currentDirection());val nextId=read<String>(next,"id")
            call<Unit>(f.newestNative(),"start",nextId)
            println("RECOVERY_START activeSpeech=${f.output.currentActuatorOrNull()?.isAppSpeechActive()} stops=${read<Int>(f.newestNative(),"stops")} pending=${read<Any?>(originalRun.navigator,"pendingSpeechCue")}")
            assertTrue(f.output.currentActuatorOrNull()?.isAppSpeechActive()==true)
            call<Unit>(f.newestNative(),"done",nextId)
            println("RECOVERY_DONE activeSpeech=${f.output.currentActuatorOrNull()?.isAppSpeechActive()} completed=${read<Any?>(originalRun.navigator,"completedSpeechCue")}")
            assertNotNull(read<Any?>(originalRun.navigator,"completedSpeechCue"))
            assertNotEquals(oldCue,read<Any?>(originalRun.navigator,"completedSpeechCue"))
            assertEquals(1,read<Int>(oldNative,"stops"))
            assertEquals(0,read<Int>(f.newestNative(),"stops"))
            val limitedStatus="안내 품질 재확인 중: 품질이 확인되지 않은 기능을 잠시 제한합니다. 새 위치와 영상이 확인되면 자동으로 다시 안내합니다."
            assertEquals(if(cameraHealthy) listOf(limitedStatus, "상태 재확인: 위치가 다시 확인되었습니다.")
                else listOf("환경 품질 재확인 중: 위치와 카메라 품질을 확인할 수 없어 안내를 잠시 멈췄습니다. 새 측정이 확인되면 자동으로 다시 안내합니다.",
                    limitedStatus),f.statuses)
            assertEquals("The current warning must reach native speech exactly once",1,
                spoken.count { read<String>(it,"message")==explanationText })
            if(cameraHealthy) {
                val recoveryStatus=spoken.single { read<String>(it,"message")=="위치가 다시 확인되었습니다." }
                assertTrue("Recovery status must finish before route guidance resumes",f.statusCompleted(read(recoveryStatus,"id")))
            } else {
                assertEquals(setOf(OfficialEnvironmentFactor.CAMERA_QUALITY),
                    f.runtime().guard?.decision()?.unavailableFactors)
                assertTrue(f.runtime().guard?.decision()?.navigationOutputsAllowed==true)
                assertFalse(f.runtime().guard?.decision()?.cameraOutputsAllowed==true)
                assertTrue("The still-unavailable camera must prevent an all-clear notice",
                    spoken.none { read<String>(it,"message").contains("다시 확인되었습니다") ||
                        read<String>(it,"message").contains("품질이 회복되었습니다") })
            }
            assertEquals(0,f.sdkResumeSkips)
            // Requests occur once for each elapsed 3 s period of the same original BAD episode.
            // The original PASS expires before the six-period confirmation deadline.
            val recheckPeriods=(restrictionAt-1L-onset)/GuidanceRecoveryTiming.GPS_RECHECK_PERIOD_MS+1L
            assertEquals(3L,recheckPeriods)
            assertEquals(recheckPeriods.toInt(),f.freshRequests)
            assertEquals(f.freshRequests,f.freshCancels)
            assertEquals(1,f.resumeCalls)
            println("RECOVERY_METRICS cameraHealthy=$cameraHealthy requests=${f.freshRequests} cancels=${f.freshCancels} resumeCalls=${f.resumeCalls} sdkResumeSkips=${f.sdkResumeSkips} statusCount=${f.statuses.size} explanationCount=${f.explanations.size} route=${originalRun.navigator.currentRouteId()} epoch=${f.base.lifecycle.snapshot().epoch}")
        } finally {f.output.closeActuator()}
    }
    private class Fixture(private val cameraHealthy:Boolean) {
        private val type=Class.forName(ClosestPointSpeechReviewTest::class.java.name+"\$Fixture")
        private val fixture=type.declaredConstructors.single{it.parameterCount==4}.apply{isAccessible=true}.newInstance(15.0,12.0,90.0,60.4)
        val base=read<MainActivityContinuousLocationStartBehaviorTest.Fixture>(fixture,"base")
        val guide=read<RouteGuidanceController>(fixture,"guide")
        val position=read<PositioningSessionController>(fixture,"positions")
        val output=read<SpeechOutputController>(fixture,"output")
        val clock=read<Any>(fixture,"clock")
        val pauses=mutableListOf<String>()
        val statuses=mutableListOf<String>()
        val explanations=mutableListOf<String>()
        var freshRequests=0;var freshCancels=0;var resumeCalls=0;var sdkResumeSkips=0
        private var serviceRecoveryStatus = false
        private val completedStatuses = mutableSetOf<String>()
        private var pendingStatus: Triple<Any,String,Long>? = null
        var mock=false
        var startedAt=0L
        lateinit var native:Any
        init {
            assertEquals(18_000L,GuidanceRecoveryTiming.GPS_GRACE_MS)
            val speech=read<RouteGuidanceController.SpeechPort>(guide,"speech")
            val callbacks=read<RouteCallbackScheduler>(guide,"callbacks")
            for(name in listOf("decisionSpeech","recheckNotices")) {
                val child=read<Any>(guide,name)
                put(child,"speech",speech);put(child,"callbacks",callbacks)
                if(name=="recheckNotices") put(child,"elapsedRealtimeMs",{base.now})
            }
            // Controlled SDK monotonic clock only; the gate and evidence owners stay real.
            put(position,"elapsedRealtimeMs",{base.now})
            val gps=MainActivityControllerTestFixture.controller(base.main,"continuousGpsSession")
            put(gps,"clock",{base.now})
            val gpsActions=read<ContinuousGpsSession.Actions>(gps,"actions")
            put(gps,"actions",object:ContinuousGpsSession.Actions by gpsActions {
                override fun requestFresh(result:(Location?)->Unit):()->Unit {
                    freshRequests++;val cancel=gpsActions.requestFresh(result)
                    return {freshCancels++;cancel()}
                }
            })
            val runtime=MainActivityControllerTestFixture.controller(base.main,"officialEnvironmentRuntimeController")
            val actualFacts=read<OfficialEnvironmentRuntimeFactsReader>(runtime,"facts")
            put(runtime,"facts",object:OfficialEnvironmentRuntimeFactsReader by actualFacts {
                override fun cameraEnabled()=true
                override fun locationEnabled()=true
                override fun currentAssessment(epoch:WalkRuntimeEpoch,nowMs:Long):OfficialEnvironmentAssessment {
                    val original=actualFacts.currentAssessment(epoch,nowMs)
                    // Selected fixture conditions: all common conditions admitted and camera raw PASS/UNKNOWN.
                    // Keep actual Main GPS evidence, assessment epoch/profile/time and real runtime guard.
                    return original.copy(
                        factorStatuses=original.factorStatuses.mapValues { (factor,status)-> when(factor) {
                            OfficialEnvironmentFactor.GPS_QUALITY->status
                            OfficialEnvironmentFactor.CAMERA_QUALITY->if(cameraHealthy) EnvironmentEvidenceStatus.PASS else EnvironmentEvidenceStatus.UNKNOWN
                            else->EnvironmentEvidenceStatus.PASS
                        } }, usageLimitsAcknowledged=true,
                        enabledMeasuredFactors=setOf(OfficialEnvironmentFactor.GPS_QUALITY,OfficialEnvironmentFactor.CAMERA_QUALITY),
                        runtimeUnavailableMeasuredFactors=if(cameraHealthy) original.runtimeUnavailableMeasuredFactors-OfficialEnvironmentFactor.CAMERA_QUALITY else original.runtimeUnavailableMeasuredFactors,
                        recoverableRuntimeUnavailableMeasuredFactors=if(cameraHealthy) original.recoverableRuntimeUnavailableMeasuredFactors-OfficialEnvironmentFactor.CAMERA_QUALITY else original.recoverableRuntimeUnavailableMeasuredFactors,
                    )
                }
            })
            val envEffects=read<OfficialEnvironmentRuntimeEffects>(runtime,"effects")
            put(runtime,"effects",object:OfficialEnvironmentRuntimeEffects by envEffects {
                override fun resumeGpsNavigation() {
                    resumeCalls++
                    envEffects.resumeGpsNavigation()
                }
                override fun showStatus(title:String,detail:String) { statuses+="$title: $detail" }
                override fun speakStatusExplanation(message:String) { explanations+=message;envEffects.speakStatusExplanation(message) }
                override fun speakRecheckExplanation(message:String,current:()->Boolean,delivered:()->Unit) {
                    explanations+=message
                    envEffects.speakRecheckExplanation(message,current,delivered)
                }
            })
            val actual=read<PositioningSessionController.Sources>(position,"sources")
            put(position,"sources",object:PositioningSessionController.Sources by actual {
                override val orientationAvailable=false
                override fun mock(location:Location)=this@Fixture.mock
            })
            put(position,"diagnostics",read<RouteGuidanceController.Diagnostics>(guide,"diagnostics"))
            val effects=read<PositioningSessionController.Effects>(position,"effects")
            put(position,"effects",object:PositioningSessionController.Effects by effects {
                override fun navigationStatus(text:String) { println("POSITION_STATUS cameraHealthy=$cameraHealthy $text") }
                override fun appendGnssTrace(snapshot:PositioningSnapshot,location:Location,matchedEvidenceCurrent:Boolean) { } // SDK trace-clock/storage leaf only
                override fun pauseDirection(reason:String,message:String) { pauses+=reason;effects.pauseDirection(reason,message) }
            })
        }
        fun start() {
            val spoken=call<Any>(fixture,"startClosestPoint")
            native=read(fixture,"native")
            call<Unit>(fixture,"connectCompassEvents")
            step(100);call<Unit>(native,"start",read<String>(spoken,"id"));startedAt=base.now
            val facing=read<RouteFacingGuidanceResult>(read<Any>(fixture,"navigator"),"offeredFacing")
            assertEquals(12,facing.clockHour);assertNull(facing.clockRange)
            assertFalse(facing.clockPrecisionAvailable)
            assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK,facing.clockReferenceKind)
        }
        fun runRegisteredRecheckRetry() {
            val notices=read<Any>(guide,"recheckNotices")
            val retry=checkNotNull(read<Runnable?>(notices,"retryTimer"))
            val task=read<List<Any>>(clock,"tasks").single { read<Runnable>(it,"task")===retry }
            val due=read<Long>(task,"due")
            assertTrue("The registered notice retry must not run before its due time",due>=base.now)
            step(due-base.now)
        }
        fun beginRecoveryStatusCallbacks(completedId: String) {
            completedStatuses += completedId
            serviceRecoveryStatus = true
        }
        fun statusCompleted(id: String)=id in completedStatuses
        fun step(delta:Long) {
            call<Unit>(fixture,"face");call<Unit>(clock,"advanceBy",delta);call<Unit>(fixture,"face")
            if (!serviceRecoveryStatus) return
            pendingStatus?.takeIf { base.now >= it.third }?.let {
                pendingStatus = null
                completedStatuses += it.second
                call<Unit>(it.first,"done",it.second)
            }
            if (pendingStatus == null && output.hasPendingCommandResponse()) {
                val actor = newestNative()
                val spoken = read<List<Any>>(actor,"spoken").lastOrNull {
                    read<String>(it,"message")=="위치가 다시 확인되었습니다."
                } ?: return
                val id = read<String>(spoken,"id")
                if (id !in completedStatuses) {
                    call<Unit>(actor,"start",id)
                    pendingStatus = Triple(actor,id,base.now + 3_000L)
                }
            }
        }
        fun location(accuracy:Float, latitude:Double=37.5, measuredOffsetMs:Long=0L, accuracyAvailable:Boolean=true) {
            val unsafe=sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply{isAccessible=true}.get(null) as sun.misc.Unsafe
            val location=unsafe.allocateInstance(LeafLocation::class.java) as LeafLocation
            location.accuracyValue=accuracy;location.accuracyAvailable=accuracyAvailable;location.measuredMs=base.now+measuredOffsetMs;location.latitudeValue=latitude
            val binding=base.binding as PositioningCollectionBinding
            position.handleLocationUpdate(location,binding.owner,binding.locationGeneration,binding,base.now)
            call<Unit>(clock,"advanceBy",0L)
        }
        fun stops()=read<Int>(native,"stops")
        fun spokenTexts()=read<List<Any>>(native,"spoken").map{read<String>(it,"message")}
        fun newestNative():Any=read(fixture,"native")
        fun traceLines():Any=read(fixture,"trace")
        fun runState():NavigationRunState=read(guide,"navigationRunState")
        fun runtime():OfficialEnvironmentRuntimeController=MainActivityControllerTestFixture.controller(base.main,"officialEnvironmentRuntimeController") as OfficialEnvironmentRuntimeController
    }
    private class LeafLocation:Location("fused") {
        var accuracyValue=15f;var measuredMs=0L;var latitudeValue=37.5;var accuracyAvailable=true
        override fun getProvider()="fused"
        override fun getLatitude()=latitudeValue
        override fun getLongitude()=127.0
        override fun hasAccuracy()=accuracyAvailable
        override fun getAccuracy()=accuracyValue
        override fun getElapsedRealtimeNanos()=measuredMs*1_000_000L
        override fun getTime()=0L
        override fun hasAltitude()=false
        override fun hasVerticalAccuracy()=false
        override fun hasSpeedAccuracy()=false
        override fun hasBearingAccuracy()=false
        override fun hasElapsedRealtimeUncertaintyNanos()=false
        override fun hasBearing()=false
        override fun hasSpeed()=false
        override fun getSpeed()=0f
        override fun getBearing()=0f
        override fun getAltitude()=0.0
        override fun getSpeedAccuracyMetersPerSecond()=0f
        override fun getBearingAccuracyDegrees()=0f
        override fun getVerticalAccuracyMeters()=0f
        override fun getElapsedRealtimeUncertaintyNanos()=0.0
    }
    companion object {
        private fun withModernLocationTimestamp(block:()->Unit) {
            val unsafe=sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply{isAccessible=true}.get(null) as sun.misc.Unsafe
            val field=android.os.Build.VERSION::class.java.getDeclaredField("SDK_INT")
            val base=unsafe.staticFieldBase(field);val offset=unsafe.staticFieldOffset(field)
            val previous=unsafe.getInt(base,offset)
            println("FUTURE_TIMESTAMP_SDK previous=$previous test=17")
            unsafe.putInt(base,offset,17)
            try { block() } finally { unsafe.putInt(base,offset,previous) }
        }

        @Suppress("UNCHECKED_CAST") private fun <T> read(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o) as T
        private fun put(o:Any,n:String,v:Any?) {o.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(o,v)}
        @Suppress("UNCHECKED_CAST") private fun <T> call(o:Any,n:String,vararg args:Any?):T {
            val m=o.javaClass.declaredMethods.single{it.name==n&&it.parameterCount==args.size}.apply{isAccessible=true}
            return m.invoke(o,*args) as T
        }
    }
}
