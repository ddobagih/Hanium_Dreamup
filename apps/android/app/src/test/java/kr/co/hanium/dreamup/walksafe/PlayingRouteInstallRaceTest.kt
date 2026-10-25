package kr.co.hanium.dreamup.walksafe
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputController
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
/** Actual Main/Guide/Output install handoff. Assumes admitted response and archive ACK; no transport replay. */
class PlayingRouteInstallRaceTest {
 @After fun clear() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }
 @Test fun playingOldGeometryMustStopAtNewGeometryInstall()=probe(false)
 @Test fun unchangedRouteDuringDirectionGracePreservesThePlayingSentence()=probe(true,false)
 @Test fun queuedOldRouteDuringDirectionGraceIsRejectedAfterNewGeometryInstall()=probe(true,true,false)
 @Test fun playingDirectionGraceMustNotSurviveNewGeometryInstall()=probe(true)
 private fun probe(grace:Boolean,replace:Boolean=true,started:Boolean=true) {
  val cls=Class.forName(ClosestPointSpeechReviewTest::class.java.name+"\$Fixture")
  val f=cls.declaredConstructors.single{it.parameterCount==4}.apply{isAccessible=true}.newInstance(15.0,12.0,90.0,60.4)
  val output:SpeechOutputController=read(f,"output")
  try {
   val action=call<Any>(f,"startClosestPoint");call<Unit>(f,"connectCompassEvents")
   val clock:Any=read(f,"clock");val native:Any=read(f,"native")
   val nav:RouteNavigator=read(f,"navigator");val guide:RouteGuidanceController=read(f,"guide")
   val positions:PositioningSessionController=read(f,"positions")
   val run:NavigationRunState=read(guide,"navigationRunState")
   val acq:RouteAcquisitionController=read(read<Any>(f,"cold"),"acquisition")
   val effects:RouteAcquisitionController.Effects=read(acq,"effects")
   val intro:InstalledRouteStartSpeechCoordinator=read(f,"intro")
   val original:RouteSpeechCueToken=read(nav,"pendingSpeechCue")
   call<Unit>(clock,"advanceBy",100L);call<Unit>(f,"face");if(started) call<Unit>(native,"start",read<String>(action,"id"))
   assertTrue(requireNotNull(output.currentActuatorOrNull()).isAppSpeechActive())
   if(grace) {call<Unit>(clock,"advanceBy",300L);call<Unit>(f,"headingTick",340.0,30.0);assertNotNull(nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)}
   val before:Int=read(native,"stops")
   val route:WalkingRoute=read(nav,"route")
   val replacement=route.copy(polyline=route.polyline.map{it.copy(longitude=254.0-it.longitude)},guidePoints=route.guidePoints.map{it.copy(point=it.point.copy(longitude=254.0-it.point.longitude))})
   val fix:TrustedLocation=requireNotNull(positions.trustedLocation)
   if (!replace) {
    call<Unit>(native,"done",read<String>(action,"id"));assertEquals(original,read<RouteSpeechCueToken?>(nav,"completedSpeechCue"));assertEquals(before,read<Int>(native,"stops"));return
   }
   val finish=effects.beginInstalledRoute()
   acq.installRequestedRoute(replacement,replacement.polyline.last(),fix,null,"user_destination",true)
   run.selectDestination(replacement.polyline.last())
   val facts:RouteAcquisitionController.Facts=read(acq,"facts")
   val generation=acq.requestGeneration;val session=facts.gateway.session;val routeId=nav.currentRouteId()
   intro.install("새 경로 안내 시작",{facts.foreground&&facts.runtimeActive&&facts.gateway.session===session&&facts.environmentOutputsAllowed&&run.active&&acq.requestGeneration==generation&&nav.currentRouteId()==routeId}) // accepted response seam; actual live facts, not a true admission override
   finish();effects.updateGuidance(fix,requireNotNull(positions.rawRouteLocation))
   call<Unit>(clock,"advanceBy",0L)
   if(!started) call<Unit>(native,"start",read<String>(action,"id"))
   println("INSTALL grace=$grace old=$original stopBefore=$before stopAfter=${read<Int>(native,"stops")} active=${output.currentActuatorOrNull()?.isAppSpeechActive()} spoken=${read<List<Any>>(native,"spoken").map{read<String>(it,"message")}}")
   call<Unit>(native,"done",read<String>(action,"id"))
   assertNotEquals("Late old DONE must not acknowledge the new route",original,read<RouteSpeechCueToken?>(nav,"completedSpeechCue"))
   assertTrue("An already-playing old route must be stopped once replacement geometry is installed",read<Int>(native,"stops")>before)
  } finally {output.closeActuator()}
 }
 companion object {
  @Suppress("UNCHECKED_CAST") fun <T> read(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o) as T
  @Suppress("UNCHECKED_CAST") fun <T> call(o:Any,n:String,vararg a:Any?):T=o.javaClass.declaredMethods.single{it.name==n&&it.parameterCount==a.size}.apply{isAccessible=true}.invoke(o,*a) as T
 }
}
