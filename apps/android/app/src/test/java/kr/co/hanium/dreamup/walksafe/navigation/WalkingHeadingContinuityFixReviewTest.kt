package kr.co.hanium.dreamup.walksafe.navigation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kotlin.math.*
import org.junit.Test
import org.junit.Assert.*
private fun matrix(heading:Double=0.0, tilt:Double=0.0, roll:Double=0.0):RotationMatrix3 {
 val h=Math.toRadians(heading); val t=Math.toRadians(tilt);val r=Math.toRadians(roll)
 val x=doubleArrayOf(cos(h),-sin(h),0.0)
 val y=doubleArrayOf(-sin(h)*sin(t),-cos(h)*sin(t),cos(t))
 val z=doubleArrayOf(-sin(h)*cos(t),-cos(h)*cos(t),-sin(t))
 val rx=DoubleArray(3){x[it]*cos(r)+y[it]*sin(r)}
 val ry=DoubleArray(3){-x[it]*sin(r)+y[it]*cos(r)}
 return RotationMatrix3(rx[0].toFloat(),ry[0].toFloat(),z[0].toFloat(),rx[1].toFloat(),ry[1].toFloat(),z[1].toFloat(),rx[2].toFloat(),ry[2].toFloat(),z[2].toFloat())
}
private val ref=ChestMountedGeomagneticReference(0.0,0.0,50.0)
private fun rv(at:Long, heading:Double=0.0, tilt:Double=0.0, error:Float=12f):RouteFacingObservation = requireNotNull(RouteCompassHeading.evaluate(DeviceEarthOrientation(matrix(heading,tilt),at,error,EarthOrientationAccuracy.HIGH),0.0,at).observation)
private fun point(north:Double)=RoutePoint(north/111194.92664455874,0.0)
private fun navigator()=RouteNavigator().apply {setRoute(WalkingRoute("WALK",WalkingRouteSummary(300,300),listOf(point(0.0),point(300.0)),emptyList()))}
private fun update(nav:RouteNavigator, at:Long, facing:RouteFacingObservation, north:Double=50.0):RouteNavigatorUpdate {val p=point(north);return nav.update(TrustedLocation(p.latitude,p.longitude,8f,at),at,false,filteredPosition=FilteredRoutePosition(p,6.0,at),facingObservation=facing)}
private fun summarize(f:RouteFacingObservation,at:Long):String {val v=RouteFacingGuidance.evaluate(0.0,f,at);return "heading=${f.degreesTrueNorth} error=${f.headingAccuracyDegrees} rate=${f.angularRateDegreesPerSecond} age=${at-f.observedAtElapsedRealtimeMs} direction=${v.direction} bins=${v.clockRange?.hours} clock=${v.clockHour}"}
private fun claimed(nav:RouteNavigator, at:Long, facing:RouteFacingObservation):RouteNavigatorUpdate {
 if(RouteFacingGuidance.evaluate(0.0,facing,at).clockReferenceKind==RouteClockReferenceKind.NOMINAL_TREND_CLOCK) {
  // Independent earlier measurement warms only nominal confirmation; the tested cue keeps its original sample/lease.
  val first=RouteFacingObservation(facing.degreesTrueNorth,facing.headingAccuracyDegrees,at-1L,
   facing.source,facing.sensorQuality,facing.reportedHeadingAccuracyDegrees,facing.angularRateDegreesPerSecond,
   facing.magneticFieldSuspect,facing.fallbackRecoveryEpisode)
  val waiting=update(nav,at-1L,first);check(waiting.instruction==null)
 }
 val u=update(nav,at,facing);check(u.instruction!=null);check(nav.reserveInstruction(u));check(nav.isInstructionCurrent(u,at,facing));return u
}

class WalkingHeadingContinuityFixReviewTest {
 @Test fun rollWithoutAzimuthChangeKeepsItsOriginalClockSupportAndStartLease(){
  val s=RouteCompassSensorState();s.start(7,900L,setOf(RouteCompassStream.ROTATION_VECTOR,RouteCompassStream.MAGNETIC_FIELD))
  s.onRotationVector(7,matrix(357.5),12f,1000L,EarthOrientationAccuracy.HIGH,1000L)
  s.onRotationVector(7,matrix(357.5,roll=1.0),12f,1020L,EarthOrientationAccuracy.HIGH,1020L)
  val first=s.evaluate(1020L,ref).observation!!;val nav=navigator();val cue=claimed(nav,1020L,first)
  s.onVector(7,RouteCompassStream.MAGNETIC_FIELD,RouteCompassVectorSample(0.0,0.0,-50.0,1040L,EarthOrientationAccuracy.HIGH),1040L)
  val result=s.evaluate(1040L,ref);val next=result.observation!!
  assertEquals(RouteCompassHeadingReason.VALID,result.reason)
  assertEquals(first.degreesTrueNorth,next.degreesTrueNorth,0.000001)
  assertEquals(12,RouteFacingGuidance.evaluate(0.0,first,1020L).clockHour);assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK,RouteFacingGuidance.evaluate(0.0,first,1020L).clockReferenceKind);assertNull(RouteFacingGuidance.evaluate(0.0,first,1020L).clockRange)
  assertEquals(12,RouteFacingGuidance.evaluate(0.0,next,1040L).clockHour);assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK,RouteFacingGuidance.evaluate(0.0,next,1040L).clockReferenceKind);assertNull(RouteFacingGuidance.evaluate(0.0,next,1040L).clockRange)
  assertEquals(50.0,s.latestRotationRateDegreesPerSecond()!!,0.01)
  assertEquals(0.0,next.angularRateDegreesPerSecond!!,0.0001)
  assertFalse(nav.hasInvalidatedDirectionClaim(1040L,next));assertTrue(nav.isInstructionCurrent(cue,1040L,next))
  val u=nav.retryGuidance(1040L,next)!!;assertEquals("guidance_in_flight",u.reason);assertFalse(u.cancelStaleNavigationSpeech)
  assertEquals(1520L,cue.speechCueToken!!.facingValidUntilElapsedRealtimeMs)
 }
 @Test fun halfDegreeYawWithTwelveDegreeErrorKeepsPlaybackAndSameNominalStart(){
  val nav=navigator();val cue=claimed(nav,1000L,rv(1000L,357.5));val f=rv(1020L,358.0)
  assertFalse(nav.hasInvalidatedDirectionClaim(1020L,f));assertTrue(nav.isInstructionCurrent(cue,1020L,f))
  val u=nav.retryGuidance(1020L,f)!!;assertEquals("guidance_in_flight",u.reason);assertFalse(u.cancelStaleNavigationSpeech)
 }
 @Test fun anExactHourStillSupportedByConfirmedNominalClockKeepsPlaybackAndQueuedStart(){
  val nav=navigator();val first=rv(1000L,error=8f);val cue=claimed(nav,1000L,first)
  assertEquals(12,RouteFacingGuidance.evaluate(0.0,first,1000L).clockHour)
  val f=rv(1020L);assertEquals(12,RouteFacingGuidance.evaluate(0.0,f,1020L).clockHour);assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK,RouteFacingGuidance.evaluate(0.0,f,1020L).clockReferenceKind);assertNull(RouteFacingGuidance.evaluate(0.0,f,1020L).clockRange)
  assertFalse(nav.isInstructionCurrent(cue,1020L,f))
  assertFalse(nav.retryGuidance(1020L,f)!!.cancelStaleNavigationSpeech)
  val confirmed=rv(1030L)
  assertFalse(nav.retryGuidance(1030L,confirmed)!!.cancelStaleNavigationSpeech)
  assertFalse(nav.hasInvalidatedDirectionClaim(1030L,confirmed));assertTrue(nav.isInstructionCurrent(cue,1030L,confirmed))
   assertEquals(1000L,cue.speechCueToken!!.facingObservedAtElapsedRealtimeMs);assertEquals(1500L,cue.speechCueToken!!.facingValidUntilElapsedRealtimeMs)
  assertTrue(nav.isInstructionCompletionCurrent(cue,1030L))
 }
 @Test fun disjointNominalClockStillCannotAuthorizeTheOldQueuedClaim(){
  val nav=navigator();val cue=claimed(nav,1000L,rv(1000L,357.5));val f=rv(1020L,270.0)
  assertEquals(3,RouteFacingGuidance.evaluate(0.0,f,1020L).clockHour);assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK,RouteFacingGuidance.evaluate(0.0,f,1020L).clockReferenceKind);assertNull(RouteFacingGuidance.evaluate(0.0,f,1020L).clockRange)
   assertTrue(nav.hasInvalidatedDirectionClaim(1020L,f));assertFalse(nav.isInstructionCurrent(cue,1020L,f))
   assertFalse(nav.retryGuidance(1020L,f)!!.cancelStaleNavigationSpeech)
   val confirmed=rv(1030L,270.0)
   assertFalse(nav.retryGuidance(1030L,confirmed)!!.cancelStaleNavigationSpeech)
   assertFalse(nav.isInstructionCurrent(cue,1030L,confirmed))
   assertEquals(1000L,cue.speechCueToken!!.facingObservedAtElapsedRealtimeMs);assertEquals(1500L,cue.speechCueToken!!.facingValidUntilElapsedRealtimeMs)
 }
 @Test fun nullStaleFutureMagneticAndSourceLossCannotBypassThePlaybackGuard(){
  val primary=RouteCompassHeading.evaluate(DeviceEarthOrientation(matrix(),1020L,12f,EarthOrientationAccuracy.HIGH),0.0,1020L)
  val mag=RouteCompassHeading.crossCheckMagneticField(primary,RouteCompassVectorSample(0.0,0.0,-100.0,1020L,EarthOrientationAccuracy.HIGH),ref,1020L).observation!!
  val gravity=RouteCompassVectorSample(0.0,9.80665,0.0,1020L,EarthOrientationAccuracy.HIGH)
  val fallback=RouteCompassHeading.evaluateFallback(gravity,gravity,RouteCompassVectorSample(0.0,0.0,-50.0,1020L,EarthOrientationAccuracy.HIGH),ref,1020L).observation!!
  assertTrue(mag.magneticFieldSuspect);assertEquals(RouteFacingSource.GRAVITY_MAGNETIC,fallback.source)
  for(f in listOf(null,rv(519L),rv(1021L),mag,fallback)) {
   val nav=navigator();val cue=claimed(nav,1000L,rv(1000L))
   assertTrue("loss=$f",nav.hasInvalidatedDirectionClaim(1020L,f));assertFalse(nav.isInstructionCurrent(cue,1020L,f))
   val retained=nav.retryGuidance(1020L,f)!!
   assertFalse(retained.cancelStaleNavigationSpeech)
   assertEquals("guidance_in_flight",retained.reason)
   assertEquals(2520L,nav.currentDirectionGraceSnapshot()!!.deadlineElapsedRealtimeMs)
   assertEquals(1000L,cue.speechCueToken!!.facingObservedAtElapsedRealtimeMs)
   assertEquals(1500L,cue.speechCueToken!!.facingValidUntilElapsedRealtimeMs)
   // Future/stale input is never a new GOOD or a normal-fresh grant. The held real GOOD alone owns grace.
   assertFalse(nav.prepareFreshDirectionStart(cue,1020L,f))
   assertFalse(nav.retryGuidance(2519L,f)!!.cancelStaleNavigationSpeech)
   assertTrue(nav.retryGuidance(2520L,f)!!.cancelStaleNavigationSpeech)
   assertFalse(nav.isInstructionCompletionCurrent(cue,2520L))
  }
 }
 @Test fun strongReversalStillCancelsImmediately(){
  val nav=navigator();val cue=claimed(nav,1000L,rv(1000L));val f=rv(1020L,180.0)
  assertTrue(nav.hasInvalidatedDirectionClaim(1020L,f));assertFalse(nav.isInstructionCurrent(cue,1020L,f));assertTrue(nav.retryGuidance(1020L,f)!!.cancelStaleNavigationSpeech)
 }
 @Test fun originalStartDeadlineIsNotRenewedBySupportingFreshObservations(){
  val nav=navigator();val cue=claimed(nav,1000L,rv(1000L));assertTrue(nav.isInstructionCurrent(cue,1500L,rv(1500L)))
  assertFalse(nav.isInstructionCurrent(cue,1501L,rv(1501L)));assertFalse(nav.hasInvalidatedDirectionClaim(1501L,rv(1501L)))
  assertEquals(1500L,cue.speechCueToken!!.facingValidUntilElapsedRealtimeMs);assertFalse(nav.retryGuidance(1501L,rv(1501L))!!.cancelStaleNavigationSpeech)
 }
 @Test fun overlapNeedsAnActualProducedClockReferenceNotJustAUsableObservation(){
  val range=RouteFacingGuidance.evaluate(0.0,rv(1000L),1000L);val exact=RouteFacingGuidance.evaluate(0.0,rv(1000L,error=8f),1000L)
  assertTrue(range.clockReferenceOverlaps(exact));assertTrue(exact.clockReferenceOverlaps(range))
  val tooWide=rv(1000L,315.0).copy(angularRateDegreesPerSecond=50.0)
  assertTrue(RouteFacingGuidance.isUsableObservation(tooWide,1500L))
  val none=RouteFacingGuidance.evaluate(0.0,tooWide,1500L)
  assertEquals(RouteFacingDirection.RIGHT,none.direction);assertEquals(2,none.clockHour);assertNull(none.clockRange)
  assertFalse(none.clockPrecisionAvailable);assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK,none.clockReferenceKind)
  assertFalse(range.clockReferenceOverlaps(none));assertFalse(none.clockReferenceOverlaps(range))
  val unknown=RouteFacingGuidance.evaluate(0.0,null,1500L);assertFalse(range.clockReferenceOverlaps(unknown))
 }
 @Test fun existingPreciseAdjacentSectorPlaybackExceptionIsUnchanged(){
  val nav=navigator();val cue=claimed(nav,1000L,rv(1000L,error=8f))
  val edge=rv(1020L,315.0,error=8f);val nominal=RouteFacingGuidance.evaluate(0.0,edge,1020L)
  assertEquals(2,nominal.clockHour);assertFalse(nominal.clockPrecisionAvailable);assertNull(nominal.clockRange)
  // A true reported adjacent sector is centred at60deg; the original45deg edge is nominal under the new policy.
  val f=rv(1040L,300.0,error=8f);val measured=RouteFacingGuidance.evaluate(0.0,f,1040L)
  assertTrue(measured.clockPrecisionAvailable);assertEquals(2,measured.clockHour)
  assertEquals(RouteClockReferenceKind.REPORTED_ERROR,measured.clockReferenceKind)
  assertFalse(RouteFacingGuidance.evaluate(0.0,rv(1000L,error=8f),1000L).clockReferenceOverlaps(measured))
  assertFalse(nav.hasInvalidatedDirectionClaim(1040L,f));assertFalse(nav.isInstructionCurrent(cue,1040L,f))
  assertEquals("guidance_in_flight",nav.retryGuidance(1040L,f)!!.reason)
 }
}
