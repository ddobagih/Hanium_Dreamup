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
private fun rv(at:Long, heading:Double=0.0, tilt:Double=0.0):RouteFacingObservation = requireNotNull(RouteCompassHeading.evaluate(DeviceEarthOrientation(matrix(heading,tilt),at,12f,EarthOrientationAccuracy.HIGH),0.0,at).observation)
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


/** Actual SensorState -> Heading -> Facing -> Navigator; no Android sensor delivery or audio. */
class RouteCompassRearFacingRateReviewTest {
 private fun state(a:RotationMatrix3,b:RotationMatrix3,dt:Long=20L,error:Float=12f):RouteCompassSensorState = RouteCompassSensorState().apply {
  start(7,900L,setOf(RouteCompassStream.ROTATION_VECTOR,RouteCompassStream.GRAVITY,RouteCompassStream.MAGNETIC_FIELD))
  onRotationVector(7,a,error,1000L,EarthOrientationAccuracy.HIGH,1000L)
  onRotationVector(7,b,error,1000L+dt,EarthOrientationAccuracy.HIGH,1000L+dt)
 }
 private fun observation(s:RouteCompassSensorState,at:Long)=s.evaluate(at,ref).let { assertEquals(RouteCompassHeadingReason.VALID,it.reason); requireNotNull(it.observation) }
 private fun assertNoYawCancellation(b:RotationMatrix3) {
  val s=state(matrix(),b);val first=observation(s,1020L);val nav=navigator();val cue=claimed(nav,1020L,first)
  assertEquals(100.0,s.latestRotationRateDegreesPerSecond()!!,0.01)
  assertEquals(0.0,first.angularRateDegreesPerSecond!!,0.0001)
  val aged=observation(s,1320L);assertEquals(0.0,aged.degreesTrueNorth,0.0001);assertEquals(1020L,aged.observedAtElapsedRealtimeMs)
  assertEquals(RouteFacingDirection.FRONT,RouteFacingGuidance.evaluate(0.0,aged,1320L).direction)
  assertFalse(nav.hasInvalidatedDirectionClaim(1320L,aged));assertTrue(nav.isInstructionCurrent(cue,1320L,aged))
  val playing=nav.retryGuidance(1320L,aged)!!;assertEquals("guidance_in_flight",playing.reason);assertFalse(playing.cancelStaleNavigationSpeech)
  assertTrue(nav.isInstructionCurrent(cue,1520L,observation(s,1520L)))
  assertNull(s.evaluate(1521L,ref).observation);assertFalse(nav.isInstructionCurrent(cue,1521L,rv(1521L)))
  assertEquals(1520L,cue.speechCueToken!!.facingValidUntilElapsedRealtimeMs)
 }
 @Test fun rollTwoDegreesDoesNotBecomeYawWhileFullThreeDimensionalRateRemains(){assertNoYawCancellation(matrix(roll=2.0))}
 @Test fun pitchTwoDegreesDoesNotBecomeYawWhileProjectionErrorRemains(){assertNoYawCancellation(matrix(tilt=2.0))}
 @Test fun realYawTwoDegreesStillAgesToUnknownAndRevokesPlaying(){
  val s=state(matrix(358.0),matrix());val first=observation(s,1020L);val nav=navigator();val cue=claimed(nav,1020L,first)
  assertEquals(100.0,first.angularRateDegreesPerSecond!!,0.01)
  val aged=observation(s,1320L);assertEquals(RouteFacingDirection.UNKNOWN,RouteFacingGuidance.evaluate(0.0,aged,1320L).direction)
  assertTrue(nav.hasInvalidatedDirectionClaim(1320L,aged));assertFalse(nav.retryGuidance(1320L,aged)!!.cancelStaleNavigationSpeech)
  assertEquals(2820L,nav.currentDirectionGraceSnapshot()!!.deadlineElapsedRealtimeMs)
  assertFalse(nav.retryGuidance(2819L,aged)!!.cancelStaleNavigationSpeech)
  assertTrue(nav.retryGuidance(2820L,aged)!!.cancelStaleNavigationSpeech)
  assertFalse(nav.isInstructionCompletionCurrent(cue,2820L))
 }
 @Test fun wrapAcrossNorthUsesTheTwentyDegreeShortArcInBothDirections(){
  for((a,b) in listOf(350.0 to 10.0,10.0 to 350.0)){
   val s=state(matrix(a),matrix(b),100L);assertEquals(200.0,observation(s,1100L).angularRateDegreesPerSecond!!,0.001)
  }
 }
 @Test fun worldAxisPitchThatReallyChangesAzimuthKeepsThatRate(){
  val a=matrix(45.0,20.0);val t=Math.toRadians(10.0);val c=cos(t);val v=sin(t)
  val b=RotationMatrix3(a.m00,a.m01,a.m02,
   (c*a.m10-v*a.m20).toFloat(),(c*a.m11-v*a.m21).toFloat(),(c*a.m12-v*a.m22).toFloat(),
   (v*a.m10+c*a.m20).toFloat(),(v*a.m11+c*a.m21).toFloat(),(v*a.m12+c*a.m22).toFloat())
  val before=RouteCompassHeading.evaluate(DeviceEarthOrientation(a,1000L,12f,EarthOrientationAccuracy.HIGH),0.0,1000L).observation!!
  val s=state(a,b);val after=observation(s,1020L)
  val changed=abs((after.degreesTrueNorth-before.degreesTrueNorth+540.0)%360.0-180.0)
  assertTrue(changed>1.0);assertEquals(changed*50.0,after.angularRateDegreesPerSecond!!,0.001)
  assertEquals(500.0,s.latestRotationRateDegreesPerSecond()!!,0.01)
 }
 @Test fun verticalCurrentSampleIsRejectedAndVerticalPreviousSampleUsesFullRate(){
  val rejected=state(matrix(),matrix(tilt=80.0)).evaluate(1020L,ref)
  assertEquals(RouteCompassHeadingReason.REAR_AXIS_VERTICAL,rejected.reason);assertNull(rejected.observation)
  val recovered=state(matrix(tilt=80.0),matrix());val f=observation(recovered,1020L)
  assertEquals(recovered.latestRotationRateDegreesPerSecond()!!,f.angularRateDegreesPerSecond!!,0.0)
  assertTrue(f.angularRateDegreesPerSecond!!>3000.0)
 }
 @Test fun badMatricesNullAndRestartDoNotReuseTheOldYawRate(){
  val s=state(matrix(358.0),matrix());assertTrue(observation(s,1020L).angularRateDegreesPerSecond!!>99.0)
  s.onRotationVector(7,matrix().copy(m00=Float.NaN),12f,1040L,EarthOrientationAccuracy.HIGH,1040L)
  assertEquals(RouteCompassHeadingReason.INVALID_ROTATION,s.evaluate(1040L,ref).reason)
  s.onRotationVector(7,null,12f,1060L,EarthOrientationAccuracy.HIGH,1060L)
  assertNull(s.latestRotationRateDegreesPerSecond());assertNull(s.evaluate(1060L,ref).observation)
  s.onRotationVector(7,matrix(),12f,1080L,EarthOrientationAccuracy.HIGH,1080L)
  assertNull(observation(s,1080L).angularRateDegreesPerSecond)
  s.stop();assertNull(s.evaluate(1100L,ref).observation)
  s.start(8,1100L,setOf(RouteCompassStream.ROTATION_VECTOR))
  assertNull(s.onRotationVector(7,matrix(180.0),12f,1100L,EarthOrientationAccuracy.HIGH,1100L))
  s.onRotationVector(8,matrix(),12f,1120L,EarthOrientationAccuracy.HIGH,1120L)
  assertNull(observation(s,1120L).angularRateDegreesPerSecond);assertNull(s.latestRotationRateDegreesPerSecond())
 }
 @Test fun rateIntervalAndImpossibleEventTimesKeepTheirOriginalBoundaries(){
  for(dt in listOf(1L,200L)){val s=state(matrix(358.0),matrix(),dt);assertEquals(2000.0/dt,observation(s,1000L+dt).angularRateDegreesPerSecond!!,0.02)}
  for(dt in listOf(0L,201L)){val s=state(matrix(358.0),matrix(),dt);assertNull(observation(s,1000L+dt).angularRateDegreesPerSecond);assertNull(s.latestRotationRateDegreesPerSecond())}
  val s=state(matrix(358.0),matrix());val rate=observation(s,1020L).angularRateDegreesPerSecond
  assertNull(s.onRotationVector(7,matrix(180.0),12f,1041L,EarthOrientationAccuracy.HIGH,1040L))
  assertNull(s.onRotationVector(7,matrix(180.0),12f,-1L,EarthOrientationAccuracy.HIGH,1040L))
  val kept=observation(s,1040L);assertEquals(1020L,kept.observedAtElapsedRealtimeMs);assertEquals(rate,kept.angularRateDegreesPerSecond)
 }
 @Test fun rejectedAccuracyAndSensorQualityAreNotPromoted(){
  val s=state(matrix(),matrix(roll=2.0),error=31f);assertEquals(RouteCompassHeadingReason.HEADING_ACCURACY,s.evaluate(1020L,ref).reason);assertNull(s.evaluate(1020L,ref).observation)
  val low=state(matrix(),matrix());low.onAccuracyChanged(7,RouteCompassStream.ROTATION_VECTOR,EarthOrientationAccuracy.LOW,1020L)
  assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_MISSING,low.evaluate(1020L,ref).reason);assertNull(low.evaluate(1020L,ref).observation)
 }
 @Test fun fallbackKeepsFullThreeDimensionalRateAndSensorSkewVeto(){
  fun fallback(roll:Double,magAt:Long):Pair<RouteCompassSensorState,RouteCompassHeadingResult>{
   val s=state(matrix(),matrix(roll=roll),error=-1f)
   s.onVector(7,RouteCompassStream.GRAVITY,RouteCompassVectorSample(0.0,9.80665,0.0,1020L,EarthOrientationAccuracy.HIGH),magAt)
   s.onVector(7,RouteCompassStream.MAGNETIC_FIELD,RouteCompassVectorSample(0.0,0.0,-50.0,magAt,EarthOrientationAccuracy.HIGH),magAt)
   return s to s.evaluate(magAt,ref)
  }
  val paired=fallback(2.0,1020L);val f=paired.second.observation!!
  assertEquals(RouteFacingSource.GRAVITY_MAGNETIC,f.source);assertEquals(100.0,f.angularRateDegreesPerSecond!!,0.01)
  assertEquals(paired.first.latestRotationRateDegreesPerSecond()!!,f.angularRateDegreesPerSecond!!,0.0)
  val skew=fallback(2.0,1170L);assertEquals(RouteCompassHeadingReason.SENSOR_SKEW,skew.second.reason);assertNull(skew.second.observation)
  assertNotNull(fallback(0.0,1170L).second.observation)
 }
 @Test fun magneticAnomalyAndActualReversalStillRevokePlaying(){
  for(magnetic in listOf(false,true)){
   val s=state(matrix(),matrix());val nav=navigator();val cue=claimed(nav,1020L,observation(s,1020L))
   if(magnetic)s.onVector(7,RouteCompassStream.MAGNETIC_FIELD,RouteCompassVectorSample(0.0,0.0,-100.0,1040L,EarthOrientationAccuracy.HIGH),1040L)
   else s.onRotationVector(7,matrix(180.0),12f,1040L,EarthOrientationAccuracy.HIGH,1040L)
   val f=observation(s,1040L);if(magnetic)assertTrue(f.magneticFieldSuspect)
   assertTrue(nav.hasInvalidatedDirectionClaim(1040L,f))
   val update=nav.retryGuidance(1040L,f)!!
   if(magnetic) {
    assertFalse(update.cancelStaleNavigationSpeech)
    assertEquals(2540L,nav.currentDirectionGraceSnapshot()!!.deadlineElapsedRealtimeMs)
    assertFalse(nav.retryGuidance(2539L,f)!!.cancelStaleNavigationSpeech)
    assertTrue(nav.retryGuidance(2540L,f)!!.cancelStaleNavigationSpeech)
   } else assertTrue(update.cancelStaleNavigationSpeech) // Actual 180-degree reversal is still immediate.
   assertFalse(nav.isInstructionCompletionCurrent(cue,2540L))
  }
 }
 @Test fun freshLargeTiltCoarseUnknownRemainsASeparateUnchangedLimitation(){
  val s=state(matrix(338.0,45.0),matrix(338.0,49.0));val f=observation(s,1020L)
  assertEquals(338.0,f.degreesTrueNorth,0.00001);assertEquals(0.0,f.angularRateDegreesPerSecond!!,0.001)
  assertEquals(18.291037,f.headingAccuracyDegrees!!,0.00001)
  val facing=RouteFacingGuidance.evaluate(0.0,f,1020L);assertEquals(RouteFacingDirection.UNKNOWN,facing.direction);assertFalse(facing.hasClockReference)
 }
}
