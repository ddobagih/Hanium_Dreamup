package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RouteWalkingDirectionPolicyProbeTest {
    private val reference = ChestMountedGeomagneticReference(0.0, Math.toDegrees(kotlin.math.atan2(40.0,30.0)),50.0)
    @Test fun healthyGravityAndMagneticKeepARouteObservationWhenOnlyTheAccelerationMotionCheckFails() {
        val at=1000L; val g=gravity(at)
        val result=RouteCompassHeading.evaluateFallback(g,listOf(g.copy(x=3.1)),listOf(magnetic(at)),reference,at,0.0)
        assertNotNull(result.observation)
        assertEquals("CALIBRATED_DURING_MOTION",result.observation!!.sensorQuality.name)
        assertNull(result.observation!!.reportedHeadingAccuracyDegrees)
        assertNull(result.observation!!.headingAccuracyDegrees)
        assertEquals(at,result.observation!!.observedAtElapsedRealtimeMs)
    }
    @Test fun aHealthySeventyDegreeDownwardPoseStillHasAMeasuredHorizontalAzimuth() {
        val at=1000L; val g=gravity(at,70.0)
        val result=RouteCompassHeading.evaluateFallback(g,listOf(g),listOf(magnetic(at,70.0)),reference,at,0.0)
        assertNotNull(result.observation)
        assertEquals(0.0,result.observation!!.degreesTrueNorth,0.001)
        assertEquals(cos(Math.toRadians(70.0)),result.checkEvidence!!.forwardHorizontal!!,0.0001)
        assertNull(result.observation!!.headingAccuracyDegrees)
        assertEquals(RouteCompassHeadingReason.REAR_AXIS_VERTICAL,
            RouteCompassHeading.evaluateFallback(gravity(at,80.0),listOf(gravity(at,80.0)),listOf(magnetic(at,80.0)),reference,at,0.0).reason)
    }
    @Test fun continuousPoorRvDoesNotEraseIndependentlyFreshQualifiedFallbackOrResetItsSourceEpisode() {
        val state=state(); var result:RouteCompassHeadingResult?=null
        for (now in 1000L..1900L step 20L) result=publish(state,now,rvQuality=EarthOrientationAccuracy.LOW)
        assertNotNull(result!!.observation)
        assertEquals(RouteFacingSource.GRAVITY_MAGNETIC,result!!.observation!!.source)
        assertNull(result!!.observation!!.reportedHeadingAccuracyDegrees)
        val nav=navigator(); val first=nav.update(fix(1900L),1900L,false,facingObservation=result!!.observation)
        assertFalse(first.instruction.orEmpty().contains("시 방향"))
        val fresh=publish(state,1920L,rvQuality=EarthOrientationAccuracy.LOW)
        val action=nav.update(fix(1920L),1920L,false,facingObservation=fresh.observation)
        assertEquals(RouteFacingExpression.CLOCK,action.facingDiagnostic?.expression)
        assertTrue(action.instruction.orEmpty().contains("약 12시 방향"))
        assertTrue(nav.reserveInstruction(action))
        assertTrue(nav.isInstructionCurrent(action,1920L,fresh.observation))
    }
    @Test fun aMotionQualifiedStreamNeedsARealWindowThenProvidesOneClockWithItsOriginalAge() {
        val state=state(); var result:RouteCompassHeadingResult?=null
        for (now in 1000L..1900L step 20L) result=publish(state,now,motion=3.1)
        val observation=result!!.observation!!
        assertEquals("CALIBRATED_DURING_MOTION",observation.sensorQuality.name)
        assertEquals(1860L,observation.observedAtElapsedRealtimeMs)
        assertTrue(observation.headingTrend!!.hasCompleteTimeWindow)
        val nav=navigator();nav.update(fix(1900L),1900L,false,facingObservation=observation)
        val next=publish(state,1920L,motion=3.1)
        val action=nav.update(fix(1920L),1920L,false,facingObservation=next.observation)
        assertEquals(RouteFacingExpression.CLOCK,action.facingDiagnostic?.expression)
        assertFalse(action.instruction.orEmpty().contains("사이 방향"))
        assertNull(next.observation!!.reportedHeadingAccuracyDegrees)
        assertEquals(1880L,action.speechCueToken?.facingObservedAtElapsedRealtimeMs)
    }
    @Test fun oneSoftFailureKeepsItsOriginalOnsetThroughThreeRecoveryWindows() {
        val nav=navigator();val good=RouteCompassHeading.evaluate(DeviceEarthOrientation(matrix(),1000L,3f,EarthOrientationAccuracy.HIGH),0.0,1000L)
        val action=nav.update(fix(1000L),1000L,false,facingObservation=good.observation)
        assertTrue(nav.reserveInstruction(action))
        fun bad(at:Long)=RouteCompassHeading.evaluateFallback(gravity(at).copy(y=13.0),emptyList(),listOf(magnetic(at)),reference,at,0.0)
        val failure=bad(1200L);assertEquals(RouteCompassHeadingReason.GRAVITY_UNSTABLE,failure.reason)
        nav.update(fix(1200L),1200L,false,facingObservation=failure.observation,facingUnavailableReason=failure.reason)
        assertEquals(5700L,nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        for(at in listOf(2700L,4200L,5699L)) {
            val raw=bad(at);val update=nav.update(fix(at),at,false,facingObservation=raw.observation,facingUnavailableReason=raw.reason)
            assertFalse(update.cancelStaleNavigationSpeech)
            assertEquals(RouteDirectionGraceMode.GRACE_RETAINED,nav.currentDirectionGraceSnapshot()?.mode)
            assertTrue(nav.isInstructionCurrent(action,at,raw.observation))
        }
        val raw=bad(5700L);val problem=nav.update(fix(5700L),5700L,false,facingObservation=raw.observation,facingUnavailableReason=raw.reason)
        assertTrue(problem.cancelStaleNavigationSpeech)
        assertEquals(RouteDirectionGraceMode.PROBLEM,nav.currentDirectionGraceSnapshot()?.mode)
        nav.acknowledgeInstruction(action,5700L)
        assertFalse(nav.isInstructionCompletionCurrent(action,5700L))
    }
    @Test fun aSingleSixtyDegreeRawSpikeCannotImmediatelyReverseAPlayingClockCommand() {
        val state=state();val nav=navigator(30.0)
        var good=publish(state,980L,error=12f)
        nav.update(fix(980L,30.0),980L,false,facingObservation=good.observation)
        good=publish(state,1000L,error=12f)
        val action=nav.update(fix(1000L,30.0),1000L,false,facingObservation=good.observation)
        assertEquals(RouteFacingExpression.CLOCK,action.facingDiagnostic?.expression)
        assertTrue(action.instruction.orEmpty().contains("약 1시 방향"))
        assertTrue(nav.reserveInstruction(action))
        val spike=publish(state,1020L,error=12f,heading=60.0)
        assertFalse(nav.hasHardInvalidatedDirectionClaim(1020L,spike.observation))
        val update=nav.update(fix(1020L,30.0),1020L,false,facingObservation=spike.observation)
        assertFalse(update.cancelStaleNavigationSpeech)
        good=publish(state,1040L,error=12f)
        assertFalse(nav.hasHardInvalidatedDirectionClaim(1040L,good.observation))
    }
    @Test fun aSupportedModerateTurnAndASingleStrongReversalStillInvalidateTheOriginalCommand() {
        for(heading in listOf(60.0,180.0)) {
            val state=state();val nav=navigator(30.0)
            nav.update(fix(980L,30.0),980L,false,facingObservation=publish(state,980L,error=12f).observation)
            val action=nav.update(fix(1000L,30.0),1000L,false,facingObservation=publish(state,1000L,error=12f).observation)
            assertTrue(nav.reserveInstruction(action))
            val first=publish(state,1020L,error=12f,heading=heading)
            if(heading==60.0) {
                assertFalse(nav.hasHardInvalidatedDirectionClaim(1020L,first.observation))
                nav.update(fix(1020L,30.0),1020L,false,facingObservation=first.observation)
                val shortSpike=publish(state,1040L,error=12f,heading=heading)
                assertFalse(nav.hasHardInvalidatedDirectionClaim(1040L,shortSpike.observation))
                assertFalse(nav.update(fix(1040L,30.0),1040L,false,facingObservation=shortSpike.observation).cancelStaleNavigationSpeech)
                val supported=publish(state,1320L,error=12f,heading=heading)
                assertTrue(nav.hasHardInvalidatedDirectionClaim(1320L,supported.observation))
                assertTrue(nav.update(fix(1320L,30.0),1320L,false,facingObservation=supported.observation).cancelStaleNavigationSpeech)
            } else {
                assertTrue(nav.hasHardInvalidatedDirectionClaim(1020L,first.observation))
                assertTrue(nav.update(fix(1020L,30.0),1020L,false,facingObservation=first.observation).cancelStaleNavigationSpeech)
            }
            assertFalse(nav.reserveInstruction(action))
        }
    }
    @Test fun motionQualifiedColdPartialOrScatteredHistoryCannotInventAClock() {
        val state=state();var result:RouteCompassHeadingResult?=null
        for(now in 1000L..1300L step 20L) result=publish(state,now,motion=3.1)
        assertNotNull(result!!.observation)
        assertFalse(RouteFacingGuidance.evaluate(0.0,result!!.observation,1300L).hasClockReference)
        val healthy=result!!.observation!!
        val scattered=healthy.copy(headingTrend=RouteHeadingTrendEstimate(0.0,healthy.observedAtElapsedRealtimeMs,
            600L,31,healthy.source,0.0,90.0,hasCompleteTimeWindow=true))
        assertFalse(RouteFacingGuidance.evaluate(0.0,scattered,1300L).hasClockReference)
    }
    @Test fun independentFallbackCannotBypassAnExplicitCurrentInvalidReportedRvError() {
        for(error in listOf(31f,Float.POSITIVE_INFINITY)) {
            val state=state();val result=publish(state,1000L,error=error,rvQuality=EarthOrientationAccuracy.LOW)
            assertNull(result.observation)
            assertEquals(RouteCompassHeadingReason.HEADING_ACCURACY,result.reason)
        }
    }
    @Test fun aCurrentMagneticOrGravityFaultStillRejectsItsOwnBasis() {
        val at=1000L;val g=gravity(at)
        assertEquals(RouteCompassHeadingReason.QUALITY_LOW,
            RouteCompassHeading.evaluateFallback(g,listOf(g),listOf(magnetic(at).copy(accuracy=EarthOrientationAccuracy.LOW)),reference,at,0.0).reason)
        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY,
            RouteCompassHeading.evaluateFallback(g,listOf(g),listOf(magnetic(at).copy(z=-100.0)),reference,at,0.0).reason)
        assertEquals(RouteCompassHeadingReason.GRAVITY_UNSTABLE,
            RouteCompassHeading.evaluateFallback(g.copy(y=13.0),listOf(g),listOf(magnetic(at)),reference,at,0.0).reason)
        assertEquals(RouteCompassHeadingReason.STALE,
            RouteCompassHeading.evaluateFallback(g,listOf(g),listOf(magnetic(at)),reference,1501L,0.0).reason)
    }
    private fun state()=RouteCompassSensorState().apply { start(1,900L,RouteCompassStream.entries.toSet()) }
    private fun publish(state:RouteCompassSensorState,now:Long,error:Float=Float.NaN,heading:Double=0.0,
        rvQuality:EarthOrientationAccuracy=EarthOrientationAccuracy.HIGH,motion:Double=0.0):RouteCompassHeadingResult {
        val at=now-40L
        state.onRotationVector(1,matrix(heading=heading),error,at,rvQuality,now)
        val g=gravity(at)
        state.onVector(1,RouteCompassStream.GRAVITY,g,now)
        state.onVector(1,RouteCompassStream.ACCELEROMETER,g.copy(x=motion),now)
        state.onVector(1,RouteCompassStream.MAGNETIC_FIELD,magnetic(at),now)
        return state.evaluate(now,reference)
    }
    private fun matrix(pitch:Double=0.0,heading:Double=0.0):RotationMatrix3 {
        val p=Math.toRadians(pitch);val h=Math.toRadians(heading)
        return RotationMatrix3(cos(h).toFloat(),(sin(h)*sin(p)).toFloat(),(-sin(h)*cos(p)).toFloat(),
            (-sin(h)).toFloat(),(cos(h)*sin(p)).toFloat(),(-cos(h)*cos(p)).toFloat(),0f,cos(p).toFloat(),sin(p).toFloat())
    }
    private fun gravity(at:Long,pitch:Double=0.0)=RouteCompassVectorSample(0.0,9.80665*cos(Math.toRadians(pitch)),9.80665*sin(Math.toRadians(pitch)),at,EarthOrientationAccuracy.HIGH)
    private fun magnetic(at:Long,pitch:Double=0.0)=RouteCompassVectorSample(0.0,30.0*sin(Math.toRadians(pitch))-40.0*cos(Math.toRadians(pitch)),-30.0*cos(Math.toRadians(pitch))-40.0*sin(Math.toRadians(pitch)),at,EarthOrientationAccuracy.HIGH)
    private fun point(distance:Double,bearing:Double=0.0)=RoutePoint(distance*cos(Math.toRadians(bearing))/111194.92664455874,distance*sin(Math.toRadians(bearing))/111194.92664455874)
    private fun fix(at:Long,bearing:Double=0.0)=point(50.0,bearing).let { TrustedLocation(it.latitude,it.longitude,5f,at) }
    private fun navigator(bearing:Double=0.0)=RouteNavigator(config=RouteNavigatorConfig(directionRecheckGraceMs=4500L)).apply { setRoute(WalkingRoute("FIELD",WalkingRouteSummary(200,200),
        listOf(point(0.0,bearing),point(100.0,bearing),point(200.0,bearing)),listOf(WalkingRouteGuidePoint(0,point(100.0,bearing),null,100,100,turnType=11)))) }
}
