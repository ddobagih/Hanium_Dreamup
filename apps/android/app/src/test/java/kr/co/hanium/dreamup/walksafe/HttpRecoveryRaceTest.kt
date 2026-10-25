package kr.co.hanium.dreamup.walksafe
import java.net.SocketTimeoutException
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
/** Actual current Acq/provider execute callback/archive lanes. Environment decision is a controlled input. */
class HttpRecoveryRaceTest {
 @After fun clear()=GatewayCapacityProcessState.resetForTests()
 @Test fun validProviderResponseDuringGpsRestrictionBreaksTheProviderFailureStreak() {
  val f=F();f.timeout();assertEquals(1,f.guard.failureCount());assertTrue(f.stops.isEmpty())
  f.at(2500);f.setResponse {put(f.raw,"outputsAllowed",false)}
  f.request();f.worker();f.ui()
  assertNotNull("Same initial request should be retained while current GPS output is restricted",f.c.explicitStart)
  assertFalse(f.run.navigator.hasRoute())
  println("VALID_WHILE_GPS_RESTRICTED count=${f.guard.failureCount()} pending=${f.c.explicitStart}")
  f.at(3500);put(f.raw,"outputsAllowed",true);f.setResponse {throw SocketTimeoutException("controlled provider deadline")}
  f.c.continuePendingExplicitRouteStartIfReady();f.worker();f.ui()
  println("SECOND_TIMEOUT_AFTER_VALID count=${f.guard.failureCount()} safety=${f.stops} spoken=${f.spoken}")
  assertEquals("Successful current provider response must break the consecutive provider failure streak",1,f.guard.failureCount())
  assertTrue("A transient sensor refusal is not a second provider failure",f.stops.isEmpty())
 }
 @Test fun validProviderResponseWhoseArchiveAckIsGpsRestrictedAlsoBreaksTheStreak() {
  val f=F();f.timeout();assertEquals(1,f.guard.failureCount())
  f.at(2500);f.setResponse {};f.request();f.worker();f.ui();assertFalse(f.run.navigator.hasRoute())
  f.at(3000);put(f.raw,"outputsAllowed",false);f.io();f.ui()
  assertNotNull(f.c.explicitStart);assertFalse(f.run.navigator.hasRoute())
  f.at(3500);put(f.raw,"outputsAllowed",true);f.setResponse {throw SocketTimeoutException("controlled provider deadline")}
  f.c.continuePendingExplicitRouteStartIfReady();f.worker();f.ui()
  println("ACK_RESTRICTED_THEN_TIMEOUT count=${f.guard.failureCount()} safety=${f.stops}")
  assertEquals(1,f.guard.failureCount());assertTrue(f.stops.isEmpty())
 }
 @Test fun cancelledOldSuccessfulResponseCannotResetCurrentFailureCountOrRestoreIntent() {
  val f=F();f.timeout();assertEquals(1,f.guard.failureCount());val spokenBefore=f.spoken.size
  f.at(2500);f.setResponse {f.c.cancelActiveRouteRequest(false)};f.request();f.worker();f.ui();f.io();f.ui()
  assertEquals(1,f.guard.failureCount());assertTrue(f.stops.isEmpty())
  assertFalse(f.run.navigator.hasRoute());assertNull(f.c.explicitStart);assertEquals(spokenBefore,f.spoken.size)
 }
 @Test fun twoActualCurrentProviderTimeoutsKeepTheOriginalHumanIntentAndBoundedRecovery() {
  val f=F();f.timeout();assertEquals(1,f.guard.failureCount())
  f.at(4000);f.setResponse {throw SocketTimeoutException("second current provider deadline")}
  f.c.continuePendingExplicitRouteStartIfReady();f.worker();f.ui()
  assertEquals(2,f.guard.failureCount());assertTrue(f.stops.isEmpty())
  assertEquals(2,read<Int>(f.raw,"executedCalls"));assertEquals(2,read<List<String>>(f.raw,"stages").count{it=="ROUTE_HTTP_BEGIN"})
  assertFalse(f.run.navigator.hasRoute());assertEquals(2000L,requireNotNull(f.c.explicitStart).requestedAtElapsedRealtimeMs)
  f.at(4500);repeat(3){f.c.continuePendingExplicitRouteStartIfReady()};assertEquals(2,read<Int>(f.raw,"transportCalls"))
  f.at(8000);f.setResponse {};f.c.continuePendingExplicitRouteStartIfReady();f.worker();f.ui()
  assertFalse(f.run.navigator.hasRoute());f.io();f.ui()
  assertEquals(3,read<Int>(f.raw,"transportCalls"));assertTrue(f.run.navigator.hasRoute())
  assertEquals(0,f.guard.failureCount());assertNull(f.c.explicitStart);assertTrue(f.stops.isEmpty())
 }
 @Test fun installedSuccessfulResponseBetweenTimeoutsDoesBreakTheStreak() {
  val f=F();f.timeout();assertEquals(1,f.guard.failureCount())
  f.at(2500);f.setResponse {};f.request();f.worker();f.ui();f.io();f.ui()
  assertTrue(f.run.navigator.hasRoute());assertEquals(0,f.guard.failureCount())
  f.at(3500);f.timeout();assertEquals(1,f.guard.failureCount());assertTrue(f.stops.isEmpty());assertTrue(f.run.navigator.hasRoute())
 }
 @Test fun timeoutCoincidentWithGpsRestrictionDoesNotSilentlyRetireTheInitialIntent() {
  val f=F();f.setResponse {put(f.raw,"outputsAllowed",false);throw SocketTimeoutException("controlled provider deadline")}
  f.request();f.worker();f.ui()
  f.at(3500);put(f.raw,"outputsAllowed",true);f.setResponse {};f.c.continuePendingExplicitRouteStartIfReady();f.worker();f.ui();f.io();f.ui()
  println("OVERLAP_TIMEOUT count=${f.guard.failureCount()} requests=${read<Int>(f.raw,"transportCalls")} active=${f.run.active} pending=${f.c.explicitStart} spoken=${f.spoken} status=${f.status}")
  assertTrue("Initial intent needs either retained recovery or an explicit user-visible terminal outcome",f.c.explicitStart!=null||f.run.navigator.hasRoute()||f.spoken.isNotEmpty()||f.status.isNotEmpty())
 }
 private class F {
  val raw=Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.RouteInitialConnectionRecoveryTest\$Fixture").getDeclaredConstructor().apply{isAccessible=true}.newInstance()
  val c:RouteAcquisitionController=read(raw,"controller");val run:NavigationRunState=read(raw,"run")
  val guard:ConsecutiveTmapFailureGuard=read(c,"tmapFailureGuard")
  val stops=mutableListOf<String>();val status=mutableListOf<String>()
  val spoken get()=read<List<String>>(raw,"spoken")
  init {
   val actual:RoutePresentation=read(c,"presentation")
   put(c,"presentation",object:RoutePresentation by actual {
    override fun safetyStop(reason:String) {stops+=reason}
    override fun status(title:String,detail:String) {status+="$title: $detail";actual.status(title,detail)}
   })
   call<Unit>(raw,"prepareArchive")
  }
  fun at(now:Long) {put(raw,"now",now);put(raw,"location",read<TrustedLocation>(raw,"fix").copy(elapsedRealtimeMs=now-100))}
  fun setResponse(block:()->Unit)=put(raw,"beforeResponse",block)
  fun request()=c.requestRoute(read(raw,"destination"),"user_destination",true)
  fun timeout() {setResponse {throw SocketTimeoutException("controlled provider deadline")};request();worker();ui()}
  fun worker()=call<Unit>(raw,"runWorker");fun ui()=call<Unit>(raw,"runUi");fun io()=call<Unit>(raw,"runIo")
 }
 companion object {
  @Suppress("UNCHECKED_CAST") fun <T> read(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o) as T
  fun put(o:Any,n:String,v:Any?) {o.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(o,v)}
  @Suppress("UNCHECKED_CAST") fun <T> call(o:Any,n:String,vararg a:Any?):T=o.javaClass.declaredMethods.single{it.name==n&&it.parameterCount==a.size}.apply{isAccessible=true}.invoke(o,*a) as T
 }
}
