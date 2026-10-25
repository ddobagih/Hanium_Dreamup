package kr.co.hanium.dreamup.walksafe
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
/** Actual Acq worker/archive/ACK lanes. SDK facts and environment decision are controlled inputs. */
class ArchiveRecoveryRaceTest {
 @After fun clear()=GatewayCapacityProcessState.resetForTests()
 @Test fun recoveredGpsBeforeArchiveUiCompletionRetainsAndRetriesOneOriginalIntent() {
  val f=fixture(); val c:RouteAcquisitionController=read(f,"controller");val run:NavigationRunState=read(f,"run")
  val destination:RoutePoint=read(f,"destination");val fix:TrustedLocation=read(f,"fix")
  call<Unit>(f,"prepareArchive");c.requestRoute(destination,"user_destination",true);call<Unit>(f,"runWorker");call<Unit>(f,"runUi")
  assertFalse(run.navigator.hasRoute());assertTrue(read<List<Runnable>>(f,"io").isNotEmpty())
  put(f,"now",2500L);put(f,"outputsAllowed",false)
  call<Unit>(f,"runIo") // current ACK admission observes the real closed environment gate
  assertFalse(run.navigator.hasRoute())
  put(f,"now",2700L);put(f,"location",fix.copy(elapsedRealtimeMs=2600L));put(f,"outputsAllowed",true)
  call<Unit>(f,"runUi") // terminal for old response must use recovered newer raw fix once
  assertEquals(2,read<Int>(f,"transportCalls"));assertFalse(run.navigator.hasRoute())
  call<Unit>(f,"runWorker");call<Unit>(f,"runUi");assertFalse(run.navigator.hasRoute())
  call<Unit>(f,"runIo");call<Unit>(f,"runUi")
  assertTrue(run.navigator.hasRoute());assertNull(c.explicitStart);assertFalse(c.requestPending)
  c.continuePendingExplicitRouteStartIfReady();call<Unit>(f,"runUi")
  assertEquals(2,read<Int>(f,"transportCalls"));assertTrue(read<List<String>>(f,"spoken").isEmpty())
  println("ACK_RECOVERY requests=${read<Int>(f,"transportCalls")} route=${run.navigator.currentRouteId()} pending=${c.explicitStart} statuses=${read<List<String>>(f,"statuses")}")
 }
 @Test fun firstInstallAndUnacknowledgedReplacementDoNotCancelNavigation() {
  val f=fixture();val c:RouteAcquisitionController=read(f,"controller");val run:NavigationRunState=read(f,"run")
  val actual:RouteAcquisitionController.Effects=read(c,"effects");var cancels=0
  put(c,"effects",object:RouteAcquisitionController.Effects by actual {
   override fun cancelNavigationSpeech() {cancels++;actual.cancelNavigationSpeech()}
  })
  call<Unit>(f,"prepareArchive");c.requestRoute(read(f,"destination"),"user_destination",true)
  call<Unit>(f,"runWorker");call<Unit>(f,"runUi");assertEquals(0,cancels);assertFalse(run.navigator.hasRoute())
  call<Unit>(f,"runIo");call<Unit>(f,"runUi");assertTrue(run.navigator.hasRoute());assertEquals(0,cancels)
  val oldId=run.navigator.currentRouteId();put(f,"routeOffsetM",30.0)
  c.requestRoute(read(f,"destination"),"user_destination",true);call<Unit>(f,"runWorker");call<Unit>(f,"runUi")
  assertEquals(oldId,run.navigator.currentRouteId());assertEquals(0,cancels)
  assertTrue(c.requestPending);assertTrue(read<List<Runnable>>(f,"io").isNotEmpty())
  c.cancelActiveRouteRequest(true);call<Unit>(f,"runIo");call<Unit>(f,"runUi")
  assertEquals(oldId,run.navigator.currentRouteId());assertEquals(0,cancels)
 }
 @Test fun newSessionBeforeArchiveAckNeverInstallsOrRetriesOldResponse() {
  val f=fixture();val c:RouteAcquisitionController=read(f,"controller");val run:NavigationRunState=read(f,"run")
  call<Unit>(f,"prepareArchive");c.requestRoute(read(f,"destination"),"user_destination",true);call<Unit>(f,"runWorker");call<Unit>(f,"runUi")
  put(f,"generation",read<Long>(f,"generation")+1L)
  call<Unit>(f,"runIo");call<Unit>(f,"runUi")
  assertFalse(run.navigator.hasRoute());assertNull(c.explicitStart);assertEquals(1,read<Int>(f,"transportCalls"))
 }
 companion object {
  fun fixture():Any=Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.RouteInitialConnectionRecoveryTest\$Fixture").getDeclaredConstructor().apply{isAccessible=true}.newInstance()
  @Suppress("UNCHECKED_CAST") fun <T> read(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o) as T
  fun put(o:Any,n:String,v:Any?) {o.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(o,v)}
  @Suppress("UNCHECKED_CAST") fun <T> call(o:Any,n:String,vararg a:Any?):T=o.javaClass.declaredMethods.single{it.name==n&&it.parameterCount==a.size}.apply{isAccessible=true}.invoke(o,*a) as T
 }
}
