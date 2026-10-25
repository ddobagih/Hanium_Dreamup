package kr.co.hanium.dreamup.walksafe
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
/** New near-stale intersections only; actual compiled Main/GPS/Runtime/Guide/Output paths. */
class GpsFreshnessIntersectionReviewTest {
    @After fun clearAccount(){GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)}
    @Test fun nanAtNinePointNineSecondsExpiresAtOriginalGoodAgeNotAReissuedGpsGraceBudget()=nearStale("nan")
    @Test fun coarseAtNinePointNineSecondsExpiresAtOriginalGoodAgeNotAReissuedGpsGraceBudget()=nearStale("coarse")
    @Test fun missingAccuracyAtNinePointNineSecondsExpiresAtOriginalGoodAgeNotAReissuedGpsGraceBudget()=nearStale("null")
    @Test fun missingCallbacksAtNinePointNineSecondsExpireEvenWithoutABadMeasurement()=nearStale("missing")
    @Test fun availabilityFalseRetentionPredicateDoesNotReissueTheOriginalGoodEvidence()=nearStale("availability")
    private fun nearStale(kind:String) {
        val f=Fixture()
        try {
            f.start();f.location(15f)
            val original=requireNotNull(f.position.trustedLocation);val originalPass=requireNotNull(f.evidence())
            val goodAt=original.elapsedRealtimeMs
            assertEquals(goodAt,originalPass.observedAtElapsedRealtimeMs)
            repeat(33){f.step(300)}
            assertEquals(9_900L,f.now-goodAt)
            assertEquals(0,f.stops());assertEquals(EnvironmentEvidenceStatus.PASS,f.evidence()?.status)
            val badAt=f.now
            when(kind){
                "nan"->f.location(Float.NaN)
                "coarse"->f.location(35f)
                "null"->f.location(15f,false)
                "availability"->{
                    val snapshot=f.position.coordinator.evaluate(f.now,requireNotNull(f.position.binding).inputGeneration)
                    assertTrue(f.position.retainFreshPositionOnAvailabilityLoss(f.position.trustedLocation,snapshot.confidence,f.now))
                    // Production AvailabilityChanged returns without GPS publication on this exact predicate.
                    assertSame(originalPass,f.evidence())
                }
            }
            assertSame(original,f.position.trustedLocation);assertEquals(0,f.stops())
            if(kind in listOf("nan","coarse","null")) assertEquals(badAt+GuidanceRecoveryTiming.GPS_GRACE_MS,f.gate().pendingFailureDeadlineElapsedRealtimeMs())
            else assertNull(f.gate().pendingFailureDeadlineElapsedRealtimeMs())
            f.step(100)
            assertEquals(10_000L,f.now-goodAt)
            assertEquals(EnvironmentEvidenceStatus.PASS,f.evidence()?.status);assertEquals(0,f.stops())
            f.step(1)
            println("INTERSECTION kind=$kind oldGood=$goodAt bad=$badAt now=${f.now} age=${f.now-goodAt} sinceBad=${f.now-badAt} gps=${f.evidence()} stops=${f.stops()} nativeStatus=${f.statuses()} gateDeadline=${f.gate().pendingFailureDeadlineElapsedRealtimeMs()}")
            assertNotEquals(EnvironmentEvidenceStatus.PASS,f.evidence()?.status)
            assertEquals(1,f.stops())
            if(kind in listOf("nan","coarse","null")) {
                // Expired PASS restricts output now, while its first BAD's confirmation keeps its clock.
                assertEquals(badAt+GuidanceRecoveryTiming.GPS_GRACE_MS,f.gate().pendingFailureDeadlineElapsedRealtimeMs())
            } else assertNull(f.gate().pendingFailureDeadlineElapsedRealtimeMs())
            assertEquals(goodAt,original.elapsedRealtimeMs)
        } finally {f.close()}
    }
    @Test fun aRealFreshFixBeforeTheOldExpiryStartsItsOwnFreshnessWithoutFakeTime() {
        val f=Fixture()
        try {
            f.start();f.location(15f);val first=requireNotNull(f.position.trustedLocation)
            repeat(33){f.step(300)};f.location(Float.NaN)
            f.step(99);val observed=f.now;f.location(15f)
            val actual=requireNotNull(f.position.trustedLocation)
            println("FRESH_BEFORE_EXPIRY first=${first.elapsedRealtimeMs} actual=$actual evidence=${f.evidence()} stops=${f.stops()}")
            assertEquals(observed,actual.elapsedRealtimeMs);assertNotSame(first,actual)
            assertEquals(observed,f.evidence()?.observedAtElapsedRealtimeMs)
            assertNull(f.gate().pendingFailureDeadlineElapsedRealtimeMs())
            f.step(2);assertEquals(EnvironmentEvidenceStatus.PASS,f.evidence()?.status);assertEquals(0,f.stops())
        } finally {f.close()}
    }
    @Test fun actualFreshRecoveryAfterMissingCallbackExpiryAndExactDuplicateDoNotStayBlocked() {
        val f=Fixture()
        try {
            f.start();f.location(15f);val first=requireNotNull(f.position.trustedLocation)
            repeat(33){f.step(300)};f.step(101)
            assertEquals(1,f.stops());assertNotEquals(EnvironmentEvidenceStatus.PASS,f.evidence()?.status)
            f.finishExplanation()
            withSdk17 {
                f.step(1);val actualAt=f.now;f.location(15f)
                val actual=requireNotNull(f.position.trustedLocation)
                assertEquals(actualAt,actual.elapsedRealtimeMs);assertEquals(EnvironmentEvidenceStatus.PASS,f.evidence()?.status)
                val pass=f.evidence();f.step(100);f.location(15f,measuredAt=actualAt)
                assertSame(actual,f.position.trustedLocation);assertEquals(pass,f.evidence())
                f.location(15f,measuredAt=first.elapsedRealtimeMs+500L)
                assertSame(actual,f.position.trustedLocation);assertEquals(pass,f.evidence())
                f.location(15f)
                println("RECOVERED_AFTER_GAP first=${first.elapsedRealtimeMs} actual=${f.position.trustedLocation} gps=${f.evidence()} pause=${f.guide().positionPaused} oldStops=${f.stops()}")
                assertEquals(EnvironmentEvidenceStatus.PASS,f.evidence()?.status)
                assertFalse(f.guide().positionPaused)
                assertNull(f.gate().pendingFailureDeadlineElapsedRealtimeMs())
            }
        } finally {f.close()}
    }
    @Test fun olderInvalidAccuracyCannotClearTheNewerGoodWhileTheQualityGateIgnoresIt() {
        val f=Fixture()
        try {
            f.start();f.location(15f);val oldAt=requireNotNull(f.position.trustedLocation).elapsedRealtimeMs
            repeat(33){f.step(300)}
            withSdk17 {
                f.location(15f);val current=requireNotNull(f.position.trustedLocation);val pass=f.evidence()
                f.location(Float.NaN,measuredAt=oldAt)
                val cleared=f.position.trustedLocation==null
                println("DELAYED_INVALID oldAt=$oldAt now=${f.now} newestGood=$current currentTrusted=${f.position.trustedLocation} gps=${f.evidence()} raw=${f.gate().latestRawObservation()} gateDeadline=${f.gate().pendingFailureDeadlineElapsedRealtimeMs()} oldStops=${f.stops()} pause=${f.guide().positionPaused}")
                assertEquals(pass,f.evidence())
                f.step(300);f.location(15f)
                println("AFTER_DELAYED_INVALID_FRESH trusted=${f.position.trustedLocation} gps=${f.evidence()} pause=${f.guide().positionPaused} oldStops=${f.stops()}")
                assertNotNull(f.position.trustedLocation);assertEquals(EnvironmentEvidenceStatus.PASS,f.evidence()?.status)
                assertFalse("A delayed measurement quality refusal must not clear the current accepted fix",cleared)
            }
        } finally {f.close()}
    }
    @Test fun wrongEpochReadsAndWrongProfileEvidenceCannotPoisonAValidCurrentRecovery() {
        val f=Fixture()
        try {
            f.start();f.location(15f);repeat(33){f.step(300)}
            val current=f.evidence()
            assertNull(f.base.main.currentContinuousGpsEvidence(f.base.epoch.copy(recoveryGeneration=1L),f.now))
            assertEquals(current,f.evidence());assertEquals(0,f.stops())
        } finally {f.close()}
        val epoch=WalkRuntimeEpoch("intersection",0L)
        val pass=MeasuredEnvironmentEvidence(OfficialEnvironmentFactor.GPS_QUALITY,epoch,1_000L,EnvironmentEvidenceStatus.PASS,"gps",10_000L,"GPS_QUALITY_PASSED")
        listOf(pass.copy(epoch=WalkRuntimeEpoch("other",0L)),pass.copy(measurementProfileId="other")).forEach { context ->
            val gate=RuntimeMeasuredQualityGate(3_000L);gate.observe(pass,1_000L)
            val bad=context.copy(status=EnvironmentEvidenceStatus.FAIL,observedAtElapsedRealtimeMs=10_900L,detail="TRUSTED_FIX_UNAVAILABLE")
            gate.withGpsMeasurementFailure(epoch,10_900L,"gps",RuntimeMeasuredQualityGate.GpsMeasurementFailure.INVALID_ACCURACY) {
                assertEquals(bad,gate.observe(bad,10_900L))
            }
            assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
            val actual=pass.copy(observedAtElapsedRealtimeMs=11_000L)
            assertSame(actual,gate.observe(actual,11_000L));assertSame(actual,gate.current(11_001L))
        }
    }
    private class Fixture {
        private val type=Class.forName(GpsAfterDeadlineRecoveryTraceTest::class.java.name+"\$Fixture")
        private val instance=type.declaredConstructors.single{it.parameterCount==1}.apply{isAccessible=true}.newInstance(true)
        val base=read<MainActivityContinuousLocationStartBehaviorTest.Fixture>(instance,"base")
        val position=read<PositioningSessionController>(instance,"position")
        val now:Long get()=base.now
        init {
            val acquisition=MainActivityControllerTestFixture.controller(base.main,"routeAcquisitionController")
            val actual=read<RoutePresentation>(acquisition,"presentation")
            put(acquisition,"presentation",object:RoutePresentation by actual {
                // Only unsupported Android status/log render; preserve actual interaction speech and pause.
                override fun navigationStatus(text:String){println("ACQUISITION_STATUS $text")}
            })
        }
        fun start(){call<Unit>(instance,"start")}
        fun step(delta:Long){call<Unit>(instance,"step",delta)}
        fun location(accuracy:Float,available:Boolean=true,measuredAt:Long=now){call<Unit>(instance,"location",accuracy,37.5,measuredAt-now,available)}
        fun gate()=base.gate
        fun guide()=read<RouteGuidanceController>(instance,"guide")
        fun finishExplanation(){
            val retired=call<Any>(instance,"newestNative")
            val oldAction=read<List<Any>>(retired,"spoken").last()
            call<Unit>(retired,"stopCallback",read<String>(oldAction,"id"))
            call<Unit>(instance,"runRegisteredRecheckRetry")
            val native=call<Any>(instance,"newestNative")
            assertNotSame("The stopped ACTION owner cannot deliver the recheck notice",retired,native)
            call<Unit>(native,"ready")
            val spoken=read<List<Any>>(native,"spoken").last();val id=read<String>(spoken,"id")
            call<Unit>(native,"start",id);call<Unit>(native,"done",id)
        }
        fun evidence()=base.main.currentContinuousGpsEvidence(base.epoch,now)
        fun stops()=call<Int>(instance,"stops")
        fun statuses()=read<Any>(instance,"statuses")
        fun close(){read<kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputController>(instance,"output").closeActuator()}
    }
    companion object {
        private fun withSdk17(block:()->Unit){
            val unsafe=sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply{isAccessible=true}.get(null) as sun.misc.Unsafe
            val field=android.os.Build.VERSION::class.java.getDeclaredField("SDK_INT");val owner=unsafe.staticFieldBase(field);val offset=unsafe.staticFieldOffset(field)
            val previous=unsafe.getInt(owner,offset);unsafe.putInt(owner,offset,17)
            try{block()}finally{unsafe.putInt(owner,offset,previous)}
        }

        private fun put(owner:Any,name:String,value:Any?){owner.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(owner,value)}
        @Suppress("UNCHECKED_CAST") private fun <T> read(owner:Any,name:String)=owner.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(owner) as T
        @Suppress("UNCHECKED_CAST") private fun <T> call(owner:Any,name:String,vararg args:Any?):T=owner.javaClass.declaredMethods.single{it.name==name&&it.parameterCount==args.size}.apply{isAccessible=true}.invoke(owner,*args) as T
    }
}
