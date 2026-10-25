package kr.co.hanium.dreamup.walksafe

import android.location.Location
import kr.co.hanium.dreamup.walksafe.device.environment.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.PriorityUserOnboardingPolicy
import kr.co.hanium.dreamup.walksafe.session.PriorityUserOnboardingSnapshot
import kr.co.hanium.dreamup.walksafe.session.PriorityUserAgeBand
import kr.co.hanium.dreamup.walksafe.session.PriorityUserPractice
import kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputController
import org.junit.Assert.*
import org.junit.Test
import org.junit.After

/** Native TTS/SDK leaves are controlled; actual existing Main->Positioning->Guide->Output owners. */
class GpsMeasuredFailureNativeRegressionTest {
    @After fun clearGateway() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }
    @Test fun aSingleInvalidAccuracyKeepsOriginalPositionAndNativeSpeechInsideTheGpsGrace() {
        val f=Fixture()
        try {
            f.start();val original=checkNotNull(f.position.trustedLocation)
            f.step(100);f.location(Float.NaN)
            println("INVALID_ACC elapsed=${f.base.now-f.startedAt} trusted=${f.position.trustedLocation} pause=${f.pauses} guidePaused=${f.guide.positionPaused} gps=${f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status} stops=${f.stops()}")
            assertSame(original,f.position.trustedLocation)
            assertEquals(0,f.stops())
            assertFalse(f.guide.positionPaused)
            assertEquals(EnvironmentEvidenceStatus.PASS,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertEquals(6000L,original.elapsedRealtimeMs)
            f.step(300)
            println("INVALID_ACC_NATIVE stops=${f.stops()} spoken=${f.spokenTexts()}")
        } finally {f.output.closeActuator()}
    }
    @Test fun coarseAccuracyAloneKeepsPositionAndDoesNotRequestImmediatePause() {
        val f=Fixture()
        try {
            f.start();val original=checkNotNull(f.position.trustedLocation);val before=f.stops()
            f.step(100);f.location(35f)
            assertSame(original,f.position.trustedLocation);assertTrue(f.pauses.isEmpty())
            assertFalse(f.guide.positionPaused)
            assertEquals(EnvironmentEvidenceStatus.PASS,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertEquals(before,f.stops())
        } finally {f.output.closeActuator()}
    }
    @Test fun mockFixStillTakesTheImmediateHardBoundary() {
        val f=Fixture()
        try {
            f.start();f.step(100);f.mock=true;f.location(15f)
            assertNull(f.position.trustedLocation);assertTrue(f.stops()>0)
            assertEquals(EnvironmentEvidenceStatus.FAIL,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
        } finally {f.output.closeActuator()}
    }
    @Test fun ongoingCoarseQualityRestrictsAtOriginalPassExpiryBeforeTheGpsConfirmationLimit() {
        val f=Fixture()
        try {
            f.start();val before=f.stops();f.step(100);val badOnset=f.base.now
            f.location(35f)
            repeat(9){f.step(300);f.location(35f)}
            f.step(299)
            println("COARSE_2999 stops=${f.stops()} gps=${f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status} since=${f.base.now-badOnset}")
            assertEquals(2999L,f.base.now-badOnset)
            assertEquals(EnvironmentEvidenceStatus.PASS,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertEquals(before,f.stops())
            val confirmationDeadline=badOnset+GuidanceRecoveryTiming.GPS_GRACE_MS
            assertEquals(confirmationDeadline,f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
            val originalPassAt=checkNotNull(f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.observedAtElapsedRealtimeMs)
            assertEquals(6000L,originalPassAt)
            val restrictionAt=minOf(confirmationDeadline,originalPassAt+10_001L)
            assertTrue("Original PASS expires before the longer confirmation budget",restrictionAt<confirmationDeadline)
            while(f.base.now+300L<restrictionAt) { f.step(300);f.location(35f) }
            f.step(restrictionAt-f.base.now-1L)
            assertEquals(originalPassAt+10_000L,f.base.now)
            assertEquals(EnvironmentEvidenceStatus.PASS,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertEquals(before,f.stops())
            f.step(1)
            println("COARSE_ORIGINAL_TTL10001 stops=${f.stops()} gps=${f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status} status=${f.statuses}")
            assertEquals(EnvironmentEvidenceStatus.FAIL,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertTrue(f.stops()>before)
        } finally {f.output.closeActuator()}
    }
    @Test fun ongoingInvalidAccuracyRestrictsAtOriginalPassExpiryBeforeTheGpsConfirmationLimit() {
        val f=Fixture()
        try {
            f.start();val before=f.stops();f.step(100);val badOnset=f.base.now
            f.location(Float.NaN)
            repeat(9){f.step(300);f.location(Float.NaN)}
            f.step(299)
            println("NAN_2999 stops=${f.stops()} gps=${f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status} since=${f.base.now-badOnset}")
            assertEquals(2999L,f.base.now-badOnset)
            assertEquals(EnvironmentEvidenceStatus.PASS,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertEquals(before,f.stops())
            val confirmationDeadline=badOnset+GuidanceRecoveryTiming.GPS_GRACE_MS
            assertEquals(confirmationDeadline,f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
            val originalPassAt=checkNotNull(f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.observedAtElapsedRealtimeMs)
            assertEquals(6000L,originalPassAt)
            val restrictionAt=minOf(confirmationDeadline,originalPassAt+10_001L)
            assertTrue("Original PASS expires before the longer confirmation budget",restrictionAt<confirmationDeadline)
            while(f.base.now+300L<restrictionAt) { f.step(300);f.location(Float.NaN) }
            f.step(restrictionAt-f.base.now-1L)
            assertEquals(originalPassAt+10_000L,f.base.now)
            assertEquals(EnvironmentEvidenceStatus.PASS,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertEquals(before,f.stops())
            f.step(1)
            println("NAN_ORIGINAL_TTL10001 stops=${f.stops()} gps=${f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status} status=${f.statuses}")
            assertEquals(EnvironmentEvidenceStatus.FAIL,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertTrue(f.stops()>before)
        } finally {f.output.closeActuator()}
    }
    @Test fun firstSingleOutlierIsRetainedButASecondDistinctLargeJumpStopsSpeechImmediately() {
        val f=Fixture()
        try {
            f.start();val original=checkNotNull(f.position.trustedLocation);val before=f.stops()
            val navigator=read<NavigationRunState>(f.guide,"navigationRunState").navigator
            val oldCue=read<RouteSpeechCueToken>(navigator,"pendingSpeechCue")
            val spoken=read<List<Any>>(f.native,"spoken").last()
            val oldSpeechId=read<String>(spoken,"id")
            f.step(100);f.location(15f,37.6)
            assertSame(original,f.position.trustedLocation);assertEquals(before,f.stops())
            assertEquals(EnvironmentEvidenceStatus.PASS,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertEquals("GPS_SINGLE_OUTLIER_AGAINST_FRESH_ESTIMATE",f.base.gate.latestRawObservation()?.detail)
            f.step(300);f.location(15f,37.6001)
            assertNull(f.position.trustedLocation);assertEquals(before+1,f.stops())
            assertEquals(EnvironmentEvidenceStatus.FAIL,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertEquals("GPS_CORRUPT_JUMP",f.base.gate.latestRawObservation()?.detail)
            assertNull(f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
            call<Unit>(f.native,"done",oldSpeechId)
            assertNotEquals(oldCue,read<RouteSpeechCueToken?>(navigator,"completedSpeechCue"))
            assertEquals(before+1,f.stops())
        } finally {f.output.closeActuator()}
    }
    @Test fun normalObservationAfterOneCoarseSampleResetsGraceAndPublishesItsActualTime() {
        val f=Fixture()
        try {
            f.start();val before=f.stops();f.step(100);f.location(35f)
            f.step(300);val actualObservedAt=f.base.now;f.location(15f)
            println("RECOVERY trusted=${f.position.trustedLocation} stops=${f.stops()} gps=${f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status}")
            assertEquals(actualObservedAt,f.position.trustedLocation?.elapsedRealtimeMs)
            assertEquals(EnvironmentEvidenceStatus.PASS,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertNull(f.base.gate.pendingFailureDeadlineElapsedRealtimeMs());assertEquals(before,f.stops())
        } finally {f.output.closeActuator()}
    }
    @Test fun normalObservationAfterOneInvalidAccuracySampleResetsGraceAndPublishesItsActualTime() {
        val f=Fixture()
        try {
            f.start();val before=f.stops();f.step(100);f.location(Float.NaN)
            f.step(300);val actualObservedAt=f.base.now;f.location(15f)
            println("RECOVERY trusted=${f.position.trustedLocation} stops=${f.stops()} gps=${f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status}")
            assertEquals(actualObservedAt,f.position.trustedLocation?.elapsedRealtimeMs)
            assertEquals(EnvironmentEvidenceStatus.PASS,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertNull(f.base.gate.pendingFailureDeadlineElapsedRealtimeMs());assertEquals(before,f.stops())
        } finally {f.output.closeActuator()}
    }
    @Test fun oneInvalidAccuracyExpiresAtOriginalPassExpiryWithoutAnotherGpsCallback() {
        val f=Fixture()
        try {
            // This GPS-only boundary assumes the user's ordinary conditions and education ACK are complete.
            // Populate the actual evidence store and policy facts; GPS, source, actor and runtime guard remain real.
            val store=MainActivityControllerTestFixture.controller(f.base.main,"officialEnvironmentEvidenceStore") as OfficialEnvironmentEvidenceStore
            val pass=EnvironmentEvidenceStatus.PASS
            put(store,"userConfirmation",kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentUserConfirmation(
                f.base.epoch,pass,pass,pass,pass,pass,pass,pass))
            val education=PriorityUserOnboardingSnapshot(
                ageBand=PriorityUserAgeBand.VERIFIED_14_PLUS,
                educationReviewed=true,safePracticePlaceConfirmed=true,
                completedPractices=PriorityUserPractice.entries.toSet(),
                phonePostureAcknowledged=true,practiceNecessityReviewed=true,
                educationAccepted=true,usageConditionsAcknowledged=true,
                appUsageReviewed=true,appUsageAccepted=true,hazardResponseConfirmed=true)
            assertTrue(education.nativeEducationComplete)
            f.base.set("priorityUserOnboardingPolicy",PriorityUserOnboardingPolicy(education))
            f.base.set("priorityUserOnboardingActorId",f.base.field("reporterUserId"))
            f.start();val original=checkNotNull(f.position.trustedLocation);val before=f.stops()
            f.step(100);val onset=f.base.now;f.location(Float.NaN)
            val deadline=f.base.gate.pendingFailureDeadlineElapsedRealtimeMs()
            repeat(9){f.step(300)};f.step(299)
            assertEquals(2999L,f.base.now-onset)
            assertEquals(deadline,f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
            assertSame(original,f.position.trustedLocation);assertEquals(before,f.stops())
            assertEquals(EnvironmentEvidenceStatus.PASS,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            val confirmationDeadline=onset+GuidanceRecoveryTiming.GPS_GRACE_MS
            assertEquals(confirmationDeadline,f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
            val originalPassAt=checkNotNull(f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.observedAtElapsedRealtimeMs)
            assertEquals(6000L,originalPassAt)
            val restrictionAt=minOf(confirmationDeadline,originalPassAt+10_001L)
            assertTrue("Original PASS expires before the longer confirmation budget",restrictionAt<confirmationDeadline)
            // Only heading ticks continue; no further GPS callback arrives.
            while(f.base.now+300L<restrictionAt) { f.step(300) }
            f.step(restrictionAt-f.base.now-1L)
            assertEquals(originalPassAt+10_000L,f.base.now)
            assertEquals(EnvironmentEvidenceStatus.PASS,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertEquals(before,f.stops())
            f.step(1)
            assertEquals(EnvironmentEvidenceStatus.FAIL,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertEquals("GPS_INVALID_ACCURACY",f.base.gate.latestRawObservation()?.detail)
            assertTrue(f.stops()>before)
        } finally {f.output.closeActuator()}
    }
    @Test fun forgedFutureNumericMeasurementKeepsTheImmediateHardBoundary() {
        val f=Fixture()
        try {
            f.start();f.step(100)
            withModernLocationTimestamp { f.location(Float.NaN,measuredOffsetMs=1L) }
            assertNull(f.position.trustedLocation);assertTrue(f.stops()>0)
            assertEquals(EnvironmentEvidenceStatus.UNKNOWN,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertNull(f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
        } finally {f.output.closeActuator()}
    }
    @Test fun invalidCoordinateKeepsTheImmediateHardBoundaryEvenWithInvalidAccuracy() {
        val f=Fixture()
        try {
            f.start();f.step(100);f.location(Float.NaN,91.0)
            assertNull(f.position.trustedLocation);assertTrue(f.stops()>0)
            assertEquals(EnvironmentEvidenceStatus.FAIL,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertEquals("TRUSTED_FIX_UNAVAILABLE",f.base.gate.latestRawObservation()?.detail)
            assertNull(f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
        } finally {f.output.closeActuator()}
    }
    @Test fun aSingleMissingAccuracyKeepsOriginalPositionAndNativeSpeechInsideTheGpsGrace() {
        val f=Fixture()
        try {
            f.start();val original=checkNotNull(f.position.trustedLocation);val before=f.stops()
            f.step(100);f.location(15f,accuracyAvailable=false)
            println("NO_ACCURACY trusted=${f.position.trustedLocation} gps=${f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status} stops=${f.stops()}")
            assertSame(original,f.position.trustedLocation);assertEquals(before,f.stops())
            assertEquals(EnvironmentEvidenceStatus.PASS,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertEquals(6000L,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.observedAtElapsedRealtimeMs)
            assertEquals(EnvironmentEvidenceStatus.UNKNOWN,f.base.gate.latestRawObservation()?.status)
        } finally {f.output.closeActuator()}
    }
    @Test fun ongoingMissingAccuracyRestrictsAtOriginalPassExpiryBeforeTheGpsConfirmationLimit() {
        val f=Fixture()
        try {
            f.start();val original=checkNotNull(f.position.trustedLocation);val before=f.stops()
            f.step(100);val onset=f.base.now;f.location(15f,accuracyAvailable=false)
            repeat(9){f.step(300);f.location(15f,accuracyAvailable=false)};f.step(299)
            assertEquals(2999L,f.base.now-onset)
            assertSame(original,f.position.trustedLocation);assertEquals(before,f.stops())
            assertEquals(EnvironmentEvidenceStatus.PASS,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            val confirmationDeadline=onset+GuidanceRecoveryTiming.GPS_GRACE_MS
            assertEquals(confirmationDeadline,f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
            val originalPassAt=checkNotNull(f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.observedAtElapsedRealtimeMs)
            assertEquals(6000L,originalPassAt)
            val restrictionAt=minOf(confirmationDeadline,originalPassAt+10_001L)
            assertTrue("Original PASS expires before the longer confirmation budget",restrictionAt<confirmationDeadline)
            while(f.base.now+300L<restrictionAt) { f.step(300);f.location(15f,accuracyAvailable=false) }
            f.step(restrictionAt-f.base.now-1L)
            assertEquals(originalPassAt+10_000L,f.base.now)
            assertEquals(EnvironmentEvidenceStatus.PASS,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertEquals(before,f.stops())
            f.step(1)
            assertEquals(EnvironmentEvidenceStatus.UNKNOWN,f.base.main.currentContinuousGpsEvidence(f.base.epoch,f.base.now)?.status)
            assertEquals("GPS_INVALID_ACCURACY",f.base.gate.latestRawObservation()?.detail)
            assertTrue(f.stops()>before)
        } finally {f.output.closeActuator()}
    }
    private class Fixture {
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
        var mock=false
        var startedAt=0L
        lateinit var native:Any
        init {
            assertEquals(18_000L,GuidanceRecoveryTiming.GPS_GRACE_MS)
            // Bind the child owners to the same actual native-output leaves as their guide.
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
            val runtime=MainActivityControllerTestFixture.controller(base.main,"officialEnvironmentRuntimeController")
            val envEffects=read<OfficialEnvironmentRuntimeEffects>(runtime,"effects")
            put(runtime,"effects",object:OfficialEnvironmentRuntimeEffects by envEffects {
                override fun showStatus(title:String,detail:String) { statuses+="$title: $detail" }
                override fun speakStatusExplanation(message:String) { explanations+=message }
            })
            val actual=read<PositioningSessionController.Sources>(position,"sources")
            put(position,"sources",object:PositioningSessionController.Sources by actual {
                override val orientationAvailable=false
                override fun mock(location:Location)=this@Fixture.mock
            })
            put(position,"diagnostics",read<RouteGuidanceController.Diagnostics>(guide,"diagnostics"))
            val effects=read<PositioningSessionController.Effects>(position,"effects")
            put(position,"effects",object:PositioningSessionController.Effects by effects {
                override fun navigationStatus(text:String) { }
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
        fun step(delta:Long) {call<Unit>(fixture,"face");call<Unit>(clock,"advanceBy",delta);call<Unit>(fixture,"face")}
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
