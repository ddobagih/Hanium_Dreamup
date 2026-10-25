package kr.co.hanium.dreamup.walksafe
import java.net.SocketTimeoutException
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
/** Stronger post-fix requirements; original six source/expectations remain frozen separately. */
class HttpRecoveryOwnershipTest {
 @After fun clear()=GatewayCapacityProcessState.resetForTests()
 @Test fun firstTimeoutEnvironmentOverlapKeepsOriginalIntentThenInstallsExactlyOneFreshRetry() {
  val f=F();val raw=f.raw;val c=f.c;val firstEpoch=read<kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle>(raw,"lifecycle").snapshot().epoch
  f.failWithEnvironment();assertEquals(1,f.guard.failureCount())
  assertEquals(1,read<Int>(raw,"executedCalls"));assertEquals(1,read<List<String>>(raw,"stages").count{it=="ROUTE_HTTP_BEGIN"})
  val pending=requireNotNull(c.explicitStart)
  assertEquals(2000L,pending.requestedAtElapsedRealtimeMs);assertEquals(firstEpoch,pending.context.walkEpoch)
  assertSame(read<Any>(raw,"session"),pending.context.gatewaySession)
  assertSame(read<Any>(raw,"selected"),pending.context.selectedDestination)
  assertEquals(1,read<Int>(raw,"transportCalls"));assertFalse(f.run.navigator.hasRoute())
  put(raw,"outputsAllowed",true);put(raw,"now",3000L) // same old observed1900 fix cannot retry
  repeat(3){c.continuePendingExplicitRouteStartIfReady()}
  assertSame(pending,c.explicitStart);assertEquals(1,read<Int>(raw,"transportCalls"))
  f.at(3500L);f.response {};c.continuePendingExplicitRouteStartIfReady()
  assertEquals(1,read<Int>(raw,"transportCalls")) // first backoff is due at4000, not3500
  put(raw,"now",4000L);c.continuePendingExplicitRouteStartIfReady() // original GPS observation stays3400
  assertEquals(2,read<Int>(raw,"transportCalls"));assertNull(c.explicitStart)
  val request:Any=read(c,"activeRouteRequestOwner");val original:Any=read(request,"initialExplicitStart")
  assertEquals(2000L,read<Long>(original,"requestedAtElapsedRealtimeMs"));assertEquals(3400L,read<Long>(original,"originObservedAtMs"))
  f.worker();f.ui();assertFalse("Archive ACK must precede route installation",f.run.navigator.hasRoute())
  f.io();f.ui();assertTrue(f.run.navigator.hasRoute());assertTrue(f.run.active);assertFalse(c.requestPending)
  assertEquals(0,f.guard.failureCount());assertTrue(f.stops.isEmpty());assertNull(c.explicitStart)
  repeat(4){c.continuePendingExplicitRouteStartIfReady()};assertEquals(2,read<Int>(raw,"transportCalls"))
  assertEquals(3400L,read<TrustedLocation>(raw,"location").elapsedRealtimeMs)
  val intro:InstalledRouteStartSpeechCoordinator=read(raw,"startSpeech");var submissions=0
  assertTrue(intro.hold({true},{_,current,terminal->assertTrue(current());submissions++;terminal();true},{},false,{false},{},null))
  assertEquals(1,submissions);assertNull(intro.pending)
 }
 @Test fun secondActualTimeoutWithEnvironmentKeepsBoundedRecoveryAndInstallsOnlyAfterFreshAck() {
  val f=F();f.failWithEnvironment();assertEquals(2000L,requireNotNull(f.c.explicitStart).requestedAtElapsedRealtimeMs)
  f.at(3500);put(f.raw,"outputsAllowed",true)
  f.response {put(f.raw,"outputsAllowed",false);throw SocketTimeoutException("second current failure")}
  f.c.continuePendingExplicitRouteStartIfReady();assertEquals(1,read<Int>(f.raw,"transportCalls"))
  put(f.raw,"now",4000L);f.c.continuePendingExplicitRouteStartIfReady();f.worker();f.ui()
  assertEquals(2,f.guard.failureCount());assertTrue(f.stops.isEmpty())
  assertEquals(2,read<Int>(f.raw,"executedCalls"));assertEquals(2,read<List<String>>(f.raw,"stages").count{it=="ROUTE_HTTP_BEGIN"})
  assertEquals(2000L,requireNotNull(f.c.explicitStart).requestedAtElapsedRealtimeMs)
  assertFalse(f.run.navigator.hasRoute());assertEquals(2,read<Int>(f.raw,"transportCalls"))
  f.at(4500);put(f.raw,"outputsAllowed",true);f.response {};repeat(4){f.c.continuePendingExplicitRouteStartIfReady()}
  f.worker();f.ui();f.io();f.ui()
  assertEquals(2,read<Int>(f.raw,"transportCalls"));assertNotNull(f.c.explicitStart);assertFalse(f.run.navigator.hasRoute())
  put(f.raw,"now",8000L);f.c.continuePendingExplicitRouteStartIfReady();f.worker();f.ui()
  assertFalse("A successful response still needs archive ACK",f.run.navigator.hasRoute())
  f.io();f.ui()
  assertEquals(3,read<Int>(f.raw,"transportCalls"));assertNull(f.c.explicitStart);assertTrue(f.run.navigator.hasRoute())
  assertEquals(0,f.guard.failureCount());assertTrue(f.stops.isEmpty())
 }
 private class F {
  private val existing=Class.forName("kr.co.hanium.dreamup.walksafe.HttpRecoveryRaceTest\$F").getDeclaredConstructor().apply{isAccessible=true}.newInstance()
  val raw:Any=read(existing,"raw");val c:RouteAcquisitionController=read(existing,"c")
  val run:NavigationRunState=read(existing,"run");val guard:ConsecutiveTmapFailureGuard=read(existing,"guard")
  val stops:List<String> get()=read(existing,"stops")
  fun response(block:()->Unit)=call<Unit>(existing,"setResponse",block)
  fun at(now:Long)=call<Unit>(existing,"at",now)
  fun worker()=call<Unit>(existing,"worker");fun ui()=call<Unit>(existing,"ui");fun io()=call<Unit>(existing,"io")
  fun failWithEnvironment() {response {put(raw,"outputsAllowed",false);throw SocketTimeoutException("first current failure")};call<Unit>(existing,"request");worker();ui()}
 }
 companion object {
  @Suppress("UNCHECKED_CAST") fun <T> read(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o) as T
  fun put(o:Any,n:String,v:Any?) {o.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(o,v)}
  @Suppress("UNCHECKED_CAST") fun <T> call(o:Any,n:String,vararg a:Any?):T=o.javaClass.declaredMethods.single{it.name==n&&it.parameterCount==a.size}.apply{isAccessible=true}.invoke(o,*a) as T
 }
}
