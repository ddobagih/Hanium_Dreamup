package kr.co.hanium.dreamup.walksafe.navigation.session
import android.location.Location
import android.os.Build
import com.google.android.gms.location.LocationCallback
import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentEvidenceStore
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
class RouteWarmingProjectionTest {
 private var previousSdkInt:Int?=null
 @Before fun selectTheDeclaredSdkAdapter() {
  val u=unsafe();val sdk=Build.VERSION::class.java.getDeclaredField("SDK_INT")
  val base=u.staticFieldBase(sdk);val offset=u.staticFieldOffset(sdk)
  previousSdkInt=u.getIntVolatile(base,offset)
  u.putIntVolatile(base,offset,36)
  assertEquals(36,u.getIntVolatile(base,offset))
 }
 @After fun restoreTheOriginalSdkAdapter() {
  val previous=previousSdkInt?:return
  val u=unsafe();val sdk=Build.VERSION::class.java.getDeclaredField("SDK_INT")
  val base=u.staticFieldBase(sdk);val offset=u.staticFieldOffset(sdk)
  u.putIntVolatile(base,offset,previous)
  assertEquals(previous,u.getIntVolatile(base,offset))
  previousSdkInt=null
 }

 @Test fun normalGpsMotionAndWatchdogNeverReadTheWarmingPermission() {
  listOf(3f,7f,15f).forEach{accuracy->
   val f=Fixture();f.rejectFineRead=true;f.publish(accuracy)
   assertNotNull(f.controller.currentRouteLocationOrNull())
   val sourceReads=f.gpsSourceReads;f.rejectGpsSourceRead=true
   f.now=1100L
   f.controller.applyPositioningMotionSnapshot(f.controller.coordinator.evaluate(f.now),requireNotNull(f.controller.binding))
   assertFalse(f.controller.handlePositioningUnavailable(f.now))
   assertNotNull(f.controller.currentRouteLocationOrNull())
   assertNull(f.controller.routeWarmingDeadlineElapsedRealtimeMs)
   assertEquals(0,f.fineReads)
   assertEquals(sourceReads,f.gpsSourceReads)
  }
 }
 @Test fun initialAndActiveWarmingStillRequireCurrentFinePermission() {
  val initial=Fixture(weighted=true);initial.fineGranted=false;initial.publish(15f)
  assertNull(initial.controller.currentRouteLocationOrNull())
  assertNull(initial.controller.routeWarmingDeadlineElapsedRealtimeMs)
  assertTrue(initial.fineReads>0)
  val active=Fixture(weighted=true);active.publish(15f)
  assertTrue(requireNotNull(active.controller.currentRouteLocationOrNull()).navigationOnly)
  active.fineGranted=false;active.now=1100L
  active.controller.handlePositioningUnavailable(active.now)
  assertNull(active.controller.currentRouteLocationOrNull())
  assertTrue(active.guide.positionPaused)
 }
 @Test fun initialFresh3_7_15MeterFixesPublishLowOrMediumRoutePositionAndSearchOrigin() {
  listOf(3f,7f,15f).forEach { accuracy ->
   val f=Fixture();f.publish(accuracy)
   val state=f.controller.coordinator.evaluate(f.now)
   val route=f.controller.currentRouteLocationOrNull()
   val search=searchOrigin(accuracy,f.now)
   println("normal raw="+accuracy+" gps="+f.store.gpsEvidence?.status+" quality="+state.quality+" sigma="+state.filtered?.horizontalUncertaintyM+" pause="+state.confidence.guidancePaused+" route="+(route!=null)+" search="+(search!=null))
   assertEquals(EnvironmentEvidenceStatus.PASS,f.store.gpsEvidence?.status)
   assertNotNull(route);assertNotNull(search);assertFalse(state.confidence.guidancePaused)
   assertEquals(1000L,route?.elapsedRealtimeMs);assertEquals(1000L,f.store.gpsEvidence?.observedAtElapsedRealtimeMs)
   assertEquals(0,f.requests)
  }
 }
 @Test fun acceptedRawGpsProjectsLowRouteEvidenceWithoutChangingTheOriginalFilter() {
  val f=Fixture(weighted=true);f.publish(15f)
  val state=f.controller.coordinator.evaluate(f.now)
  val origin=searchOrigin(15f,f.now)
  println("weighted raw=15 gps="+f.store.gpsEvidence?.status+" multiplier="+f.multiplier+" quality="+state.quality+" sigma="+state.filtered?.horizontalUncertaintyM+" pause="+state.confidence.guidancePaused+" route="+(f.controller.currentRouteLocationOrNull()!=null)+" search="+(origin!=null)+" gpsStage="+f.gate.gpsRecoveryStage(f.now))
  assertTrue(f.multiplier>1.0);assertTrue(requireNotNull(state.filtered).horizontalUncertaintyM>15.0)
  assertEquals(EnvironmentEvidenceStatus.PASS,f.store.gpsEvidence?.status)
  assertEquals(PositionQuality.UNAVAILABLE,state.quality);assertTrue(state.confidence.guidancePaused)
  val route=requireNotNull(f.controller.currentRouteLocationOrNull())
  assertTrue(route.navigationOnly);assertEquals(15f,route.accuracyM);assertEquals(1000L,route.elapsedRealtimeMs)
  assertEquals(HorizontalUncertaintyKind.ANDROID_RADIUS_68,route.uncertaintyKind)
  assertNull(f.controller.trustedCovariance)
  val projection=field(f.controller,"routeWarmingProjection") as RouteWarmingProjection
  val selection=requireNotNull(projection.current(requireNotNull(f.controller.binding),requireNotNull(f.gpsSession.currentSource()),state,f.now))
  assertEquals(PositionQuality.LOW,selection.confidence.quality);assertFalse(selection.confidence.guidancePaused)
  assertTrue(state.confidence.guidancePaused);assertEquals(PositionQuality.UNAVAILABLE,state.quality)
  assertFalse(f.guide.applyPositionConfidenceDecision(state,routeConfidence=selection.confidence))
  var dispatched=false
  val precise=f.guide.dispatchAcceptedRouteFix(route,requireNotNull(f.controller.rawRouteLocation),false,null,f.now,
   dispatchGuidance={a,b->assertTrue(a.navigationOnly);assertTrue(b.navigationOnly);dispatched=true})
  assertTrue(dispatched);assertFalse(precise)
  val converted=FilteredRoutePosition(RoutePoint(37.5,127.0),15.0,1000L,
   uncertaintyKind=HorizontalUncertaintyKind.ANDROID_RADIUS_68,navigationOnly=true).toTrustedLocation()
  assertTrue(converted.navigationOnly)
  assertNotNull(origin);assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.FRESH,f.gate.gpsRecoveryStage(f.now))
  assertNull(f.gate.pendingFailureDeadlineElapsedRealtimeMs());assertEquals(0,f.requests)
  assertEquals(1,f.warmingSchedules)
 }
 @Test fun originalTimeFutureMockAndOwnerRejectionStillPreventPositionReuse() {
  val f=Fixture();f.publish(7f)
  f.now=11000L;assertNotNull(f.controller.currentRouteLocationOrNull())
  f.now=11001L;assertNull(f.controller.currentRouteLocationOrNull())
  listOf("future","mock","coordinate").forEach { kind ->
   val rejected=Fixture();rejected.publish(3f,if(kind=="future")1001L else 1000L,kind=="mock",if(kind=="coordinate")91.0 else 37.5)
   assertNull(kind,rejected.controller.currentRouteLocationOrNull())
   assertNotEquals(kind,EnvironmentEvidenceStatus.PASS,rejected.store.gpsEvidence?.status)
  }
  val retired=Fixture();retired.publish(3f);retired.currentOwner=retired.owner.copy(actorId="other")
  assertNull(retired.controller.currentRouteLocationOrNull())
  val denied=Fixture();denied.publish(3f);denied.currentOwner=null
  assertNull(denied.controller.currentRouteLocationOrNull())
  assertNull(DestinationSearchLocationPolicy.originOrNull(DestinationSearchLocationFix(37.5,127.0,7f,1001L),1000L))
  assertNull(DestinationSearchLocationPolicy.originOrNull(DestinationSearchLocationFix(37.5,127.0,7f,1000L,true),1000L))
 }
 @Test fun actualSearchCacheRefusesPermissionDenialAndRetiredActorLateCallbacks() {
  var callback:((DestinationSearchLocationFix?)->Unit)?=null
  var requests=0
  val c=DestinationSearchLocationController(DestinationSearchLocationSource {r->requests++;callback=r;{}},{1000L})
  val old=DestinationSearchLocationLease("old",1L);val new=DestinationSearchLocationLease("new",2L)
  assertFalse(c.acquire(old,{false},{error("denied callback")}));assertEquals(0,requests)
  assertTrue(c.acquire(old,{true},{}));assertNull(c.originOrNull(new))
  requireNotNull(callback)(DestinationSearchLocationFix(37.5,127.0,15f,1000L))
  assertNull(c.originOrNull(new))
 }
 @Test fun warmingDeadlineIsFixedAndOriginalSourceTtlStillWins() {
  val f=Fixture(weighted=true);f.publish(15f)
  val deadline=1000L+GuidanceRecoveryTiming.GPS_GRACE_MS
  assertEquals(19000L,deadline)
  assertEquals(deadline,f.controller.routeWarmingDeadlineElapsedRealtimeMs)
  f.now=11000L;assertNotNull(f.controller.currentRouteLocationOrNull())
  f.now=11001L;assertNull(f.controller.currentRouteLocationOrNull())
  assertEquals(1,f.requests)
  assertTrue(f.guide.positionPaused)
  assertEquals(deadline,f.controller.routeWarmingDeadlineElapsedRealtimeMs)
  f.now=deadline;f.controller.reassessRouteWarming(f.now);assertEquals(2,f.requests)
  f.controller.reassessRouteWarming(f.now);assertEquals(2,f.requests)
  f.now=deadline+3000L;f.controller.reassessRouteWarming(f.now);assertEquals(3,f.requests)
  assertEquals(deadline,f.controller.routeWarmingDeadlineElapsedRealtimeMs)
  val delayed=Fixture(weighted=true);delayed.now=9000L;delayed.publish(15f)
  assertEquals(9000L+GuidanceRecoveryTiming.GPS_GRACE_MS,delayed.controller.routeWarmingDeadlineElapsedRealtimeMs)
  delayed.now=11000L;assertNotNull(delayed.controller.currentRouteLocationOrNull())
  delayed.now=11001L;assertNull(delayed.controller.currentRouteLocationOrNull())
  assertEquals(1000L,delayed.store.gpsEvidence?.observedAtElapsedRealtimeMs)
 }
 @Test fun newerNormalFilteredFixImmediatelyClearsWarmingMarkAndEpisode() {
  val f=Fixture(weighted=true);f.publish(15f)
  assertTrue(requireNotNull(f.controller.currentRouteLocationOrNull()).navigationOnly)
  f.now=2000L;f.publish(3f,2000L)
  val normal=requireNotNull(f.controller.currentRouteLocationOrNull())
  assertFalse(normal.navigationOnly);assertEquals(2000L,normal.elapsedRealtimeMs)
  assertNull(f.controller.routeWarmingDeadlineElapsedRealtimeMs)
  assertNotNull(f.controller.trustedCovariance)
 }
 @Test fun newerBadFixCannotRenewTheWindowAndRetiredSourceCannotKeepItsProjection() {
  val f=Fixture(weighted=true);f.publish(15f)
  f.now=5000L;f.refreshSignalWeight();f.publish(15f,f.now)
  val repeated=requireNotNull(f.controller.currentRouteLocationOrNull())
  assertTrue(repeated.navigationOnly);assertEquals(5000L,repeated.elapsedRealtimeMs)
  assertEquals(1000L+GuidanceRecoveryTiming.GPS_GRACE_MS,f.controller.routeWarmingDeadlineElapsedRealtimeMs)
  f.now=18000L;f.refreshSignalWeight();f.publish(15f,f.now)
  assertTrue(requireNotNull(f.controller.currentRouteLocationOrNull()).navigationOnly)
  assertEquals(1000L+GuidanceRecoveryTiming.GPS_GRACE_MS,f.controller.routeWarmingDeadlineElapsedRealtimeMs)
  f.now=18999L;assertNotNull(f.controller.currentRouteLocationOrNull())
  f.now=19000L;assertNull(f.controller.currentRouteLocationOrNull())
  val retired=Fixture(weighted=true);retired.publish(15f)
  retired.currentOwner=retired.owner.copy(actorId="other")
  assertNull(retired.controller.currentRouteLocationOrNull())
  val denied=Fixture(weighted=true);denied.publish(15f);denied.fineGranted=false
  assertNull(denied.controller.currentRouteLocationOrNull())
 }
 @Test fun firstNormalRecoverySampleKeepsTheExistingRawWindowUntilConfidenceRecovers() {
  val f=Fixture(weighted=true);f.publish(30f)
  f.now=19000L;assertTrue(f.controller.coordinator.evaluate(f.now).confidence.guidancePaused)
  f.now=19100L;f.refreshSignalWeight();f.publish(15f,f.now)
  val deadline=requireNotNull(f.controller.routeWarmingDeadlineElapsedRealtimeMs)
  assertTrue(requireNotNull(f.controller.currentRouteLocationOrNull()).navigationOnly)
  f.now=19200L;f.publish(3f,f.now)
  val admittedConfidence=field(f.controller.coordinator,"lastConfidenceDecision") as PositionConfidenceDecision
  assertEquals(PositionConfidenceTransition.RECOVERY_PENDING,admittedConfidence.transition)
  val pending=f.controller.coordinator.evaluate(f.now)
  assertTrue(pending.confidence.guidancePaused)
  assertTrue(requireNotNull(pending.filtered).horizontalUncertaintyM<=15.0)
  assertTrue(requireNotNull(f.controller.currentRouteLocationOrNull()).navigationOnly)
  assertEquals(deadline,f.controller.routeWarmingDeadlineElapsedRealtimeMs)
  val projection=field(f.controller,"routeWarmingProjection") as RouteWarmingProjection
  val operational=requireNotNull(projection.current(requireNotNull(f.controller.binding),requireNotNull(f.gpsSession.currentSource()),pending,f.now))
  assertFalse(f.guide.applyPositionConfidenceDecision(pending,routeConfidence=operational.confidence))
  f.now=19300L;f.publish(3f,f.now)
  assertFalse(requireNotNull(f.controller.currentRouteLocationOrNull()).navigationOnly)
  assertFalse(f.controller.coordinator.evaluate(f.now).confidence.guidancePaused)
  assertNull(f.controller.routeWarmingDeadlineElapsedRealtimeMs)
 }
 @Test fun hardRejectedFreshInputCannotBorrowARawWarmingPosition() {
  listOf("future","mock","coordinate","jump").forEach{kind->
   val f=Fixture(weighted=true);f.publish(15f);assertNotNull(f.controller.currentRouteLocationOrNull())
   f.now=1100L;f.publish(15f,if(kind=="future")1200L else 1100L,kind=="mock",when(kind){"coordinate"->91.0;"jump"->37.6;else->37.5})
   assertNull(kind,f.controller.currentRouteLocationOrNull())
  }
 }
 @Test fun softRejectedAccuracyKeepsOnlyTheAlreadyAdmittedRawReferenceUntilItsFixedDeadline() {
  val f=Fixture(weighted=true);f.publish(15f)
  val original=requireNotNull(f.controller.currentRouteLocationOrNull())
  f.now=1100L;f.publish(Float.NaN,f.now)
  assertEquals(1,f.requests)
  assertSame(original,f.controller.currentRouteLocationOrNull())
  assertEquals(1000L,original.elapsedRealtimeMs);assertEquals(15f,original.accuracyM)
  assertEquals(1000L+GuidanceRecoveryTiming.GPS_GRACE_MS,f.controller.routeWarmingDeadlineElapsedRealtimeMs)
  assertEquals(1000L,f.store.gpsEvidence?.observedAtElapsedRealtimeMs)
  assertEquals(1100L+GuidanceRecoveryTiming.GPS_GRACE_MS,f.gate.pendingFailureDeadlineElapsedRealtimeMs())
  f.now=11000L;assertSame(original,f.controller.currentRouteLocationOrNull())
  f.now=11001L;assertNull(f.controller.currentRouteLocationOrNull());assertEquals(2,f.requests)
  f.now=13100L;f.publish(Float.NaN,f.now)
  assertEquals(3,f.requests)
  assertNull(f.controller.currentRouteLocationOrNull())
  // A new explicit accuracy refusal after the original reference expired retires its projection.
  assertNull(f.controller.routeWarmingDeadlineElapsedRealtimeMs)
  assertEquals(1100L+GuidanceRecoveryTiming.GPS_GRACE_MS,f.gate.pendingFailureDeadlineElapsedRealtimeMs())
  assertEquals(1000L,original.elapsedRealtimeMs);assertEquals(15f,original.accuracyM)
  assertFalse(f.controller.coordinator.evaluate(f.now).absoluteArrivalConfirmable)
  f.now=16000L;f.controller.reassessRouteWarming(f.now);assertEquals(3,f.requests)
  val initial=Fixture(weighted=true);initial.publish(Float.NaN)
  assertNull(initial.controller.currentRouteLocationOrNull())
  assertNull(initial.controller.routeWarmingDeadlineElapsedRealtimeMs)
 }
 @Test fun missingAccuracyModelUpdatesCannotBecomeNewPreciseRouteEvidence() {
  val f=Fixture(weighted=true);f.publish(15f)
  val original=requireNotNull(f.controller.currentRouteLocationOrNull())
  (1100L..2000L step 100L).forEach{at->
   f.now=at;f.publish(0f,at,hasAccuracy=false)
   assertSame(original,f.controller.currentRouteLocationOrNull())
   assertTrue(requireNotNull(f.controller.currentRouteLocationOrNull()).navigationOnly)
   assertNull(f.controller.trustedCovariance)
  }
  assertEquals(1000L,original.elapsedRealtimeMs);assertEquals(1000L+GuidanceRecoveryTiming.GPS_GRACE_MS,f.controller.routeWarmingDeadlineElapsedRealtimeMs)
  assertEquals(1000L,f.store.gpsEvidence?.observedAtElapsedRealtimeMs)
  assertEquals(1100L+GuidanceRecoveryTiming.GPS_GRACE_MS,f.gate.pendingFailureDeadlineElapsedRealtimeMs())
  f.now=11001L;assertNull(f.controller.currentRouteLocationOrNull());assertTrue(f.guide.positionPaused)
  f.now=11100L;f.publish(0f,f.now,hasAccuracy=false)
  assertNull(f.controller.currentRouteLocationOrNull());assertTrue(f.guide.positionPaused)
  assertEquals(1000L+GuidanceRecoveryTiming.GPS_GRACE_MS,f.controller.routeWarmingDeadlineElapsedRealtimeMs)
  val initial=Fixture(weighted=true);initial.publish(0f,hasAccuracy=false)
  assertNull(initial.controller.currentRouteLocationOrNull());assertNull(initial.controller.routeWarmingDeadlineElapsedRealtimeMs)
 }
 private class FakeLocation(private val a:Float,private val at:Long,private val lat:Double, val mockFix:Boolean,private val accuracyPresent:Boolean=true):Location("fused") {
  override fun getProvider()="fused"
  override fun hasAltitude()=false
  override fun hasSpeed()=false
  override fun hasBearing()=false
  override fun hasVerticalAccuracy()=false
  override fun hasSpeedAccuracy()=false
  override fun hasBearingAccuracy()=false
  override fun hasElapsedRealtimeUncertaintyNanos()=false
  override fun hasMslAltitude()=false
  override fun hasMslAltitudeAccuracy()=false

  override fun getLatitude()=lat
  override fun getLongitude()=127.0
  override fun getAccuracy()=a
  override fun hasAccuracy()=accuracyPresent
  override fun getElapsedRealtimeNanos()=at*1000000L
  override fun getTime()=100000L+at
 }
 private class Fixture(weighted:Boolean=false) {
  var now=1000L
  val owner=LocationCollectionOwner("probe",null,0L)
  var currentOwner:LocationCollectionOwner?=owner
  var fineGranted=true
  var fineReads=0
  var rejectFineRead=false
  val epoch=WalkRuntimeEpoch("usable-probe",0L)
  val profile=requireNotNull(WalkSafeEnvironmentProfiles.active(true)).officialEnvironment
  val gate=RuntimeMeasuredQualityGate(GuidanceRecoveryTiming.GPS_RECHECK_PERIOD_MS,gpsFailureConfirmationPeriods=GuidanceRecoveryTiming.GPS_RECHECK_PERIODS)
  val store=OfficialEnvironmentEvidenceStore(Any(),gate,RuntimeMeasuredQualityGate())
  var requests=0
  var gpsSourceReads=0
  var rejectGpsSourceRead=false
  var warmingSchedules=0
  lateinit var gpsSession:ContinuousGpsSession
  val controller:PositioningSessionController
  val multiplier:Double
  var signalEstimator:GnssQualityEstimator?=null
  val guide:RouteGuidanceController
  init {
   val scope=proxy<PositioningSessionController.Scope>{ name,args ->when(name){"currentOwner"->currentOwner;"reporterId"->"probe";"getProcessInstanceId"->"probe-process";"getFineLocationGranted"->{fineReads++;check(!rejectFineRead){"Unexpected warming permission read"};fineGranted};else->null}}
   val sources=proxy<PositioningSessionController.Sources>{name,args -> if(name=="mock") (args?.get(0) as FakeLocation).mockFix else null}
   guide=unsafe().allocateInstance(RouteGuidanceController::class.java) as RouteGuidanceController
   set(guide,"navigationRunState",NavigationRunState(RouteNavigator()))
   set(guide,"scope",proxy<RouteGuidanceController.ScopeReader>{_,_->null})
   set(guide,"diagnostics",proxy<RouteGuidanceController.Diagnostics>{_,_->null})
   set(guide,"speech",proxy<RouteGuidanceController.SpeechPort>{_,_->null})
   set(guide,"elapsedRealtimeMs",{now})
   val effects=proxy<PositioningSessionController.Effects>{name,_->if(name=="scheduleRouteWarmingReassessment") warmingSchedules++;null}
   controller=PositioningSessionController(NavigationRunState(RouteNavigator()),scope,sources,proxy{_,_->null},effects,proxy{_,_->null},{gpsSession},{error("unused compass")},{guide},{error("unused requests")},{now},{it()})
   set(controller,"locationCollectionOwner",owner);set(controller,"locationCallbackGeneration",1);set(controller,"locationCallback",object:LocationCallback(){})
   val binding=PositioningCollectionBinding(controller.coordinator,controller.coordinator.currentInputGeneration(),owner,1,0L)
   set(controller,"positioningCollectionBinding",binding)
   if(weighted){
    val estimator=GnssQualityEstimator();signalEstimator=estimator
    val signals=(1..4).map{GnssSatelliteSignal(1,it,1575420000.0,0.0)}
    listOf(800000000L,900000000L,1000000000L).forEach{estimator.addEpoch(it,signals)}
    val observer=GnssQualityObserver(proxy{_,_->null},estimator=estimator,onSnapshot={})
    set(controller,"gnssQualityObserver",observer)
    multiplier=observer.snapshot(1000000000L).measurementNoiseMultiplier
   }else multiplier=1.0
   val source=ContinuousGpsSource(owner,epoch,profile)
   gpsSession=ContinuousGpsSession(object:ContinuousGpsSession.SourceReader{
    override fun current():ContinuousGpsSource?{gpsSourceReads++;check(!rejectGpsSourceRead){"Unexpected warming GPS source read"};return source.takeIf{currentOwner==owner}}
    override val latestTrusted get()=controller.trustedLocation
    override val latestRaw get()=controller.rawRouteLocation
    override fun nativeCheckCurrent(epoch:WalkRuntimeEpoch)=true
   },gate,object:ContinuousGpsSession.EvidenceWriter{
    override fun clearGpsEvidence()=store.clearGpsEvidence()
    override fun record(epoch:WalkRuntimeEpoch,observedAtMs:Long,trustedFixAvailable:Boolean?,accuracyM:Double?,elapsedRealtimeMs:()->Long,singleOutlier:Boolean){store.recordGpsObservation(epoch,epoch,observedAtMs,trustedFixAvailable,accuracyM,elapsedRealtimeMs,{profile},{false},{gpsSession.owner?.epoch},{false},{false},singleOutlier,{_,_,_->})}
   },object:ContinuousGpsSession.Actions{
    override fun clearDisconnectedRecovery(){}
    override fun prewalkEvidenceChanged(){}
    override fun requestFresh(result:(Location?)->Unit):()->Unit{requests++;return{}}
    override fun consumeFresh(location:Location,receivedAtMs:Long){}
   },{now})
  }
  fun refreshSignalWeight(){
   val signals=(1..4).map{GnssSatelliteSignal(1,it,1575420000.0,0.0)}
   listOf(now-200L,now-100L,now).forEach{signalEstimator?.addEpoch(it*1000000L,signals)}
  }
  fun publish(a:Float,at:Long=1000L,mock:Boolean=false,lat:Double=37.5,hasAccuracy:Boolean=true){controller.handleLocationUpdate(FakeLocation(a,at,lat,mock,hasAccuracy),owner,1,requireNotNull(controller.binding),now)}
 }
 private companion object {
  fun searchOrigin(accuracy:Float,now:Long):RoutePoint? {
   val c=DestinationSearchLocationController(DestinationSearchLocationSource {r->r(DestinationSearchLocationFix(37.5,127.0,accuracy,1000L));{}},{now})
   val lease=DestinationSearchLocationLease("probe",0L)
   assertTrue(c.acquire(lease,{true},{}))
   return c.originOrNull(lease)
  }
  fun unsafe():sun.misc.Unsafe{val f=sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply{isAccessible=true};return f.get(null) as sun.misc.Unsafe}
  fun field(o:Any,n:String):Any?=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
  fun set(o:Any,n:String,v:Any?){o.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(o,v)}
  inline fun <reified T> proxy(crossinline block:(String,Array<out Any?>?)->Any?):T=Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)){p,m,a->
   if(m.name=="toString") return@newProxyInstance "probe-port"
   if(m.name=="hashCode") return@newProxyInstance System.identityHashCode(p)
   if(m.name=="equals") return@newProxyInstance p===a?.get(0)
   block(m.name,a)?:when(m.returnType){java.lang.Boolean.TYPE->false;java.lang.Integer.TYPE->0;java.lang.Long.TYPE->0L;java.lang.Double.TYPE->0.0;java.lang.Float.TYPE->0f;else->null}
  } as T
 }
}
