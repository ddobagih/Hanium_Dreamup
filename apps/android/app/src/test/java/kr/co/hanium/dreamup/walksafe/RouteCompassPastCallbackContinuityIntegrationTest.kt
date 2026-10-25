package kr.co.hanium.dreamup.walksafe
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.session.RouteGuidanceController
import kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputController
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kotlin.math.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
class RouteCompassPastCallbackContinuityIntegrationTest {
 @After fun clearFixture(){GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)}
 @Test fun rejectedOldAndDuplicateRawCallbacksDoNotOverwriteTheLatestAcceptedOrientation() {
  val losses=mutableListOf<String>()
  for(kind in listOf("null","NaN","wide","duplicateWide")) {
   val state=state(500L);val p=RouteCompassObservationPublisher().apply{reset(1)}
   state.onRotationVector(1,matrix(0.0),12f,1000L,EarthOrientationAccuracy.HIGH,1000L);p.sample(1,RouteCompassStream.ROTATION_VECTOR,1000000000L,1000000000L,state.evaluate(1000L,ref))
   state.onRotationVector(1,matrix(0.0),12f,1100L,EarthOrientationAccuracy.HIGH,1100L);val accepted=state.evaluate(1100L,ref);assertEquals(1100L,accepted.observation!!.observedAtElapsedRealtimeMs);p.sample(1,RouteCompassStream.ROTATION_VECTOR,1100000000L,1100000000L,accepted)
   val at=if(kind=="duplicateWide")1100L else 900L
   state.onRotationVector(1,if(kind=="null")null else matrix(0.0),if(kind=="NaN")Float.NaN else 31f,at,EarthOrientationAccuracy.HIGH,1200L)
   assertNull(p.sample(1,RouteCompassStream.ROTATION_VECTOR,at*1000000L,1200000000L,state.evaluate(1200L,ref)))
   val live=state.evaluate(1200L,ref)
   if(live.observation?.observedAtElapsedRealtimeMs!=1100L)losses+="$kind rejected event, live=${live.reason}/${live.observation?.observedAtElapsedRealtimeMs}"
  }
  println(losses.joinToString(" | "));assertTrue("Old source state poisoned: $losses",losses.isEmpty())
 }
 @Test fun anUnpublishedOldBadSampleCannotStartANewGraceOrPrematurelyStopNativePlaying() {
  val f=fixture();val out=read<SpeechOutputController>(f,"output")
  try {
   val action=invoke(f,"startWithNativeIntro")!!;val id=read<String>(action,"id");val nav=read<RouteNavigator>(f,"navigator");val guide=read<RouteGuidanceController>(f,"guide")
   val old=read<RouteSpeechCueToken>(nav,"pendingSpeechCue");val st=state(now(f)-1000);val p=RouteCompassObservationPublisher().apply{reset(1)}
   feed(st,p,now(f),now(f),matrix(0.0),12f);advance(f,100);feed(st,p,now(f),now(f),matrix(0.0),12f)
   val latest=st.evaluate(now(f),ref);assertTrue(nav.isInstructionCurrent(update(old,action),now(f),latest.observation));nav.retryGuidance(now(f),latest.observation)
   val acceptedAt=latest.observation!!.observedAtElapsedRealtimeMs;installReader(guide,st)
   advance(f,100);assertNull(feed(st,p,acceptedAt-200,now(f),null,12f));native(f,"start",id)
   val fake=nav.currentDirectionGraceSnapshot();val firstStops=read<Int>(read<Any>(f,"native"),"stops")
   val fakeDeadline=fake?.deadlineElapsedRealtimeMs?:now(f)+1500L
   advance(f,fakeDeadline-now(f));guide.retryCurrentNavigationSpeechAfterReady(now(f))
   val stops=read<Int>(read<Any>(f,"native"),"stops")-firstStops
   println("accepted=$acceptedAt NORMALuntil=${acceptedAt+500}; discarded BADsample=${acceptedAt-200}, fake=${fake?.mode}/${fake?.onsetElapsedRealtimeMs}/${fake?.deadlineElapsedRealtimeMs}; nativeSTOP=$stops at ${now(f)}, legitimate gap deadline=${acceptedAt+2001}")
   assertEquals(RouteDirectionGraceMode.NORMAL,fake?.mode)
   assertEquals(0,stops)
  } finally {out.closeActuator()}
 }
 @Test fun anUnpublishedPastValidReversalCannotStopNativeStartAgainstANewerAcceptedGood() {
  val f=fixture();val out=read<SpeechOutputController>(f,"output")
  try {
   val action=invoke(f,"startWithNativeIntro")!!;val id=read<String>(action,"id");val nav=read<RouteNavigator>(f,"navigator");val guide=read<RouteGuidanceController>(f,"guide");val old=read<RouteSpeechCueToken>(nav,"pendingSpeechCue")
   val st=state(now(f)-1000);val p=RouteCompassObservationPublisher().apply{reset(1)};feed(st,p,now(f),now(f),matrix(0.0),12f);advance(f,100);feed(st,p,now(f),now(f),matrix(0.0),12f)
   val good=st.evaluate(now(f),ref);nav.retryGuidance(now(f),good.observation);installReader(guide,st);val acceptedAt=good.observation!!.observedAtElapsedRealtimeMs
   advance(f,100);val pastAt=acceptedAt-50;assertTrue(pastAt>old.facingObservedAtElapsedRealtimeMs!!);assertNull(feed(st,p,pastAt,now(f),matrix(180.0),12f))
   val past=st.evaluate(now(f),ref);val hard=nav.hasHardInvalidatedDirectionClaim(now(f),past.observation);val before=read<Int>(read<Any>(f,"native"),"stops");native(f,"start",id);val stops=read<Int>(read<Any>(f,"native"),"stops")-before
   println("newestGood=$acceptedAt/pending=${old.facingObservedAtElapsedRealtimeMs}; unpublished past=$pastAt/${past.observation?.degreesTrueNorth}; hard=$hard nativeSTARTstop=$stops")
   assertFalse(hard);assertEquals(0,stops)
  } finally {out.closeActuator()}
 }
 private fun update(token:RouteSpeechCueToken,action:Any)=RouteNavigatorUpdate(read<String>(action,"message"),false,false,false,"route_guidance",speechCueToken=token)
 private fun installReader(g:RouteGuidanceController,s:RouteCompassSensorState){val old=read<RouteGuidanceController.CompassReader>(g,"compassInput");put(g,"compassInput",object:RouteGuidanceController.CompassReader by old {override fun heading(nowMs:Long)=s.evaluate(nowMs,ref);override fun facing(nowMs:Long)=heading(nowMs).observation})}
 private fun state(start:Long)=RouteCompassSensorState().apply{start(1,start,setOf(RouteCompassStream.ROTATION_VECTOR))}
 private fun feed(s:RouteCompassSensorState,p:RouteCompassObservationPublisher,at:Long,receipt:Long,m:RotationMatrix3?,error:Float):RouteCompassObservationEvent? {s.onRotationVector(1,m,error,at,EarthOrientationAccuracy.HIGH,receipt);return p.sample(1,RouteCompassStream.ROTATION_VECTOR,at*1000000L,receipt*1000000L,s.evaluate(receipt,ref))}
 private val ref=ChestMountedGeomagneticReference(0.0,0.0,50.0)
 private fun matrix(heading:Double):RotationMatrix3 {val h=Math.toRadians(heading);return RotationMatrix3(cos(h).toFloat(),0f,(-sin(h)).toFloat(),(-sin(h)).toFloat(),0f,(-cos(h)).toFloat(),0f,1f,0f)}
 private fun fixture():Any=Class.forName(WalkingSpeechContinuityReviewTest::class.java.name+"$"+"Fixture").getDeclaredConstructor(Double::class.javaPrimitiveType,Double::class.javaObjectType,Double::class.javaPrimitiveType).apply{isAccessible=true}.newInstance(15.0,12.0,0.0)
 private fun now(f:Any)=read<Long>(read<Any>(read<Any>(f,"clock"),"base"),"now")
 private fun advance(f:Any,ms:Long)=invoke(read<Any>(f,"clock"),"advanceBy",Long::class.javaPrimitiveType!!,ms)
 private fun native(f:Any,n:String,id:String)=invoke(read<Any>(f,"native"),n,String::class.java,id)
 @Suppress("UNCHECKED_CAST") private fun <T> read(o:Any,n:String):T=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o) as T
 private fun put(o:Any,n:String,v:Any?)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(o,v)
 private fun invoke(o:Any,n:String):Any?=o.javaClass.getDeclaredMethod(n).apply{isAccessible=true}.invoke(o)
 private fun invoke(o:Any,n:String,t:Class<*>,v:Any):Any?=o.javaClass.getDeclaredMethod(n,t).apply{isAccessible=true}.invoke(o,v)
}
