package kr.co.hanium.dreamup.walksafe.navigation.start

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.runtime.*
import kr.co.hanium.dreamup.walksafe.session.readiness.*
import kr.co.hanium.dreamup.walksafe.startup.capability.*
import kr.co.hanium.dreamup.walksafe.startup.interaction.*
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativePhoneMountingCheckRequest
import kr.co.hanium.dreamup.walksafe.voice.output.*
import kr.co.hanium.dreamup.walksafe.voice.interaction.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.BeforeClass
import java.lang.reflect.Proxy

/**
 * Replay of HOME READY admission through route acquisition, then controlled RouteNavigator arrival;
 * it is not the complete MainActivity HOME-to-arrival path or a physical sensor test.
 * Actual Entry/Prewalk/Calibration/Output/Actuator/Startup/Admission/Gateway/Activation/Workflow run.
 * Android clock, permission, UI queue, native speech and provider responses are controlled leaves.
 * The actual WalkReadinessCoordinator and WalkReadinessProducers combine controlled platform
 * observations and policies; this does not qualify Android GPS/camera/mount sensors or a device.
 * Destination admission uses the real owner and an actual PositioningSessionController with a
 * controlled fused SDK registration port. Its callback traverses FusedLocationUpdateSource and
 * handleLocationUpdate, then RouteGuidanceController and the shared HOME RouteNavigator. SDK
 * registration/removal, platform observations, UI queue, and native speech remain controlled;
 * this is not MainActivity execution or a physical sensor test. No ACTIVE, confirmed-decision,
 * calibration-ready or output-allowed flag is seeded.
 *
 * Uses existing GatewayFixture/ResumeFixture and the existing controlled speech runtime.
 * Resume policy is not exercised; all START state transitions use gateway.lifecycle.
 */
class GuidanceReadyStartReplayTest {
 @After fun resetSharedGatewayCapacity() = GatewayCapacityProcessState.resetForTests()

 @Test fun readyHomeButtonStartsOnlyAfterNativeDoneThreeSecondsAndGatewayAck() = healthy(true)
 @Test fun coldOfflineEngineReadyCallbackContinuesTheSameHomeRequest() = healthy(false)
 @Test fun homeStartHandsTheSameDestinationEpochAndSessionToProviderAndArchiveAck() {
  var route:RouteInitialConnectionRecoveryTest.Fixture?=null
  var homeGateway:GatewayFixture?=null
  healthy(true) { f,destination ->
   homeGateway=f.gateway
   assertSame(f.destination,destination)
   val journey=RouteInitialConnectionRecoveryTest.Fixture(f.now,3L,f.gateway.account,f.gateway.lifecycle,destination)
   route=journey
   journey.prepareArchive()
   journey.controller.requestRoute(destination.point,"user_destination",true)
   assertEquals(f.walk.epoch,journey.epoch)
   assertSame(f.gateway.account,journey.session)
   assertSame("the provider request must keep the HOME gateway session identity",f.gateway.account,journey.requestedSessions.single())
   assertSame("the provider request must keep the selected HOME destination",destination.point,journey.requestedDestinations.single())
   journey.runWorker();journey.runUi()
   assertFalse("provider success is not installed before encrypted archive ACK",journey.run.navigator.hasRoute())
   assertNull(journey.stored)
   journey.runIo();journey.runUi()
   assertTrue(journey.run.navigator.hasRoute())
   assertTrue(journey.run.active)
   assertNotNull(journey.stored)
   assertTrue(journey.stages.contains("ROUTE_ARCHIVE_COMPLETE"))
   assertTrue(journey.stages.contains("ROUTE_INSTALLED"))
   true
  }
  val installed=requireNotNull(route)
  assertEquals("route install remains in the Home-started walk epoch",installed.epoch,installed.lifecycle.snapshot().epoch)
  val destination=requireNotNull(installed.run.destination)
  val navigator=installed.run.navigator
  val positioning=PositioningSessionControllerTest.Fixture(
   sharedLifecycle=installed.lifecycle,
   initialOwner=LocationCollectionOwner(installed.session.actorId,installed.session,3L),
   sharedRun=installed.run,
   sharedRequests=installed.controller,
  )
  positioning.now=installed.now
  positioning.g.captureStatus=true
  installed.run.recordRouteStartStep(0)
  positioning.start()
  val binding=requireNotNull(positioning.controller.binding)
  val retiredCallback=requireNotNull(positioning.controller.callback)
  val callbackCurrent=positioning.callbackCurrent.single()
  assertSame("the location collection must retain the HOME gateway session",installed.session,binding.owner.gatewaySession)
  var arrivalBegins=0
  var arrivalFinishes=0
  val gateway=requireNotNull(homeGateway)
  val cleanup=WalkSessionCleanupController(
   object:WalkSessionRuntimeAccess {
    override fun available()=true
    override fun snapshot()=installed.lifecycle.snapshot()
    override fun isRuntimeEpochCurrent(epoch:WalkRuntimeEpoch)=installed.lifecycle.isRuntimeEpochCurrent(epoch)
    override fun transition(event:WalkSessionEvent)=installed.lifecycle.handle(event)
   },gateway.authority,gateway.controller,{installed.session},ResumeFixture().controller,
   interfacePort(WalkCleanupAdmission::class.java){name,_->if(name=="persistInterruptionMarker")true else null},
   interfacePort(WalkCleanupNative::class.java){name,_->if(name in setOf("stopVoiceService","stopDepthSession"))true else null},
   interfacePort(WalkCleanupNavigation::class.java){name,_->when(name){
    "cancelRequestsForPause"->installed.controller.cancelNavigationRequestsForPause()
    "cancelWalkSessionRouteState"->{installed.controller.resetRouteState();null}
    "stopPositionFieldSession","locationCleanupSucceeded"->true
    "locationCollectionAllowsWork"->false
    "stopLocationUpdates"->{positioning.controller.stopLocationUpdates();null}
    "startLocationUpdatesIfAllowed"->null // The controlled platform collection remains retired for arrival.
    else->null}},
   interfacePort(WalkCleanupFeedback::class.java){_,_->null},
   interfacePort(WalkCleanupReports::class.java){_,_->null},false)
  positioning.g.beginArrival={token->
   if(installed.controller.completeDetectedArrival(token)==null)null else {
    arrivalBegins++
    positioning.navigationAllowed=false;positioning.collectionAllowed=false
    cleanup.enterWalkSessionForegroundRecheckAndCancelOutputs("arrival_ending")
    val endingEpoch=installed.lifecycle.snapshot().epoch
    object:RouteGuidanceController.ArrivalCompletion {
     override fun isCurrent()=installed.lifecycle.snapshot().let{it.epoch==endingEpoch&&it.state==WalkSessionState.PAUSED}
     override fun finish(){if(isCurrent()){
      arrivalFinishes++;installed.lifecycle.handle(WalkSessionEvent.EndRequested)
      cleanup.cancelWalkSessionOutputs("arrival_completed")
     }}
    }
   }
  }
  fun receiveNorthOffset(metres:Double,at:Long) {
   installed.now=at
   positioning.g.steps=((metres+100.0)/.7).toInt().coerceAtLeast(0)
   val sample=ControlledFusedLocation(destination.latitude+metres/111_194.92664455874,destination.longitude,5f,at)
   positioning.deliver(sample,at)
   if(arrivalBegins==0)requireNotNull(positioning.controller.trustedLocation) { "actual PositioningSessionController did not accept sample at $at" }
  }
  receiveNorthOffset(-84.0,20_000L)
  receiveNorthOffset(-60.0,28_000L)
  receiveNorthOffset(-36.0,36_000L)
  receiveNorthOffset(-12.0,44_000L)
  receiveNorthOffset(-5.0,47_000L)
  receiveNorthOffset(0.0,50_000L)
  assertEquals("the same qualified current-location stream must begin automatic arrival",1,arrivalBegins)
  assertNull(navigator.pendingUserDecision())
  assertFalse(navigator.hasRoute())
  assertNull(installed.run.destination)
  assertFalse(installed.run.active)
  assertEquals("Final speech acceptance alone cannot end this controlled walk",WalkSessionState.PAUSED,installed.lifecycle.snapshot().state)
  assertEquals(0,arrivalFinishes)
  val final=positioning.g.statusSent.single{it.message=="도착했습니다. 안내를 종료합니다"}
  assertTrue(final.current())
  assertFalse(callbackCurrent(retiredCallback))
  assertNull(positioning.controller.callback)
  assertNull(positioning.controller.binding)
  assertEquals(listOf(retiredCallback),positioning.removed)
  installed.runIo();installed.runUi()
  assertNull("the automatically completed route must be purged from the encrypted archive",installed.stored)
  final.done()
  assertEquals(WalkSessionState.ENDED,installed.lifecycle.snapshot().state)
  final.done();final.failed()
  assertEquals(1,arrivalFinishes)
  positioning.deliver(ControlledFusedLocation(destination.latitude,destination.longitude,5f,51_000L),51_000L)
  assertNull("a callback retained past END cannot publish a trusted position",positioning.controller.trustedLocation)
  assertFalse(navigator.hasRoute())
 }
 private fun healthy(ready:Boolean, onDestinationSelected:(Fixture,DestinationSearchResult)->Boolean={_,_->true}) = Fixture(ready).use { f ->
  f.onDestinationSelected={destination->onDestinationSelected(f,destination)}
  assertEquals(WalkSessionState.READY,f.walk.state);assertNull(f.confirm.confirmedDecision());assertNull(f.walk.confirmationToken)
  f.button();assertEquals(NativeUiPage.GUIDANCE,f.workflow.nativeUiPage);assertNotNull(f.workflow.nativePrewalkStartEpoch)
  if(!ready){assertTrue(f.spoken.isEmpty());f.advance(250);f.engineReady()}
  val id=f.promptId();f.nativeStart(id);assertNull(f.calibration.snapshot?.voiceDoneAtMs)
  f.advance(1_000);assertEquals(WalkSessionState.READY,f.walk.state);assertTrue(f.gateway.background.isEmpty())
  f.nativeDone(id);val done=f.now;assertEquals(done+3_000,f.calibration.snapshot?.readyAtMs)
  f.observeAllIndependentReadinessInputs();f.advance(2_999);assertNull(f.confirm.confirmedDecision());assertTrue(f.gateway.background.isEmpty());assertEquals(0,f.navigationStarts)
  f.advance(1);f.drainUi();assertTrue("confirmation blocked by ${f.capture(WalkSessionAction.START_WALK).blockingReasons} / ${f.capture(WalkSessionAction.START_WALK).observations}",f.confirm.isStartupCapabilityConfirmed());assertEquals(WalkSessionState.READY,f.walk.state)
  assertEquals(1,f.gateway.background.size);assertEquals(0,f.navigationStarts);assertTrue(f.destinationHandOff.isEmpty())
  f.gateway.runBackground();f.drainUi()
  assertEquals(WalkSessionState.ACTIVE,f.walk.state);assertEquals(1,f.activations);assertEquals(1,f.navigationStarts)
  assertEquals(listOf(f.destination),f.destinationHandOff);assertNull(f.workflow.pendingNativeUiPage)
  assertTrue("the retained destination owner must recheck its current positioning binding",f.positioningBindingChecks>0)
  assertEquals(1,f.prompts.size)
  println("HEALTHY engineInitiallyReady=$ready DONE=$done confirmed=${f.confirm.isStartupCapabilityConfirmed()} active=${f.walk.state} nav=${f.navigationStarts} destination=${f.destinationHandOff.size}")
 }
 @Test fun duplicateButtonAndRefreshCannotCreateSecondPromptOrGatewayStart() = Fixture().use { f ->
  f.button();f.button();repeat(3){f.refresh()};assertEquals(1,f.prompts.size)
  f.nativeStart(f.promptId());f.nativeDone(f.promptId());f.observeAllIndependentReadinessInputs();f.advance(3_000);f.drainUi()
  repeat(3){f.refresh()};assertEquals(1,f.gateway.background.size)
  f.gateway.runBackground();f.drainUi();repeat(3){f.refresh();f.workflow.renderMainUi()}
  assertEquals(1,f.activations);assertEquals(1,f.destinationHandOff.size)
 }
 @Test fun failedOldPromptDoneCannotSkipNewPromptAndThreeSeconds() = Fixture().use { f ->
  f.button();val old=f.prompts.single();f.nativeStart(f.promptId());f.now+=100
  f.nativeError(f.promptId());f.advance(999);assertEquals(1,f.prompts.size);f.advance(901);assertEquals(2,f.prompts.size)
  old.done();assertNull(f.calibration.snapshot?.voiceDoneAtMs);assertNull(f.confirm.confirmedDecision())
  val current=f.promptId();f.nativeStart(current);f.nativeDone(current);f.observeAllIndependentReadinessInputs();f.advance(2_999)
  assertTrue(f.gateway.background.isEmpty());f.advance(1);f.drainUi();assertEquals(1,f.gateway.background.size)
 }
 @Test fun unavailableLocationExplainsWithoutCalibrationThenRestoreStartsFreshCalibration() = Fixture().use { f ->
  f.permission=false;f.button();assertTrue(f.prompts.isEmpty());assertEquals("LOCATION_ACCESS_UNAVAILABLE",f.stage)
  assertTrue(f.spoken.any{read<String>(it,"message").contains("위치 권한")});assertNull(f.confirm.confirmedDecision());assertTrue(f.gateway.background.isEmpty())
  val failure=f.spoken.last();f.nativeStart(read(failure,"id"));f.nativeDone(read(failure,"id"))
  f.permission=true;f.advance(2_000);assertEquals(1,f.prompts.size);assertNull(f.calibration.snapshot?.voiceDoneAtMs)
  f.nativeStart(f.promptId());f.nativeDone(f.promptId());f.observeAllIndependentReadinessInputs();f.advance(3_000);f.drainUi()
  assertEquals(1,f.gateway.background.size);f.gateway.runBackground();f.drainUi();assertEquals(WalkSessionState.ACTIVE,f.walk.state)
 }
 @Test fun actorRetirementAfterPromptCannotConfirmOrActivate() = Fixture().use { f ->
  f.button();val old=f.prompts.single();f.nativeStart(f.promptId());f.actor="replacement";f.gateway.actorCurrent=false
  old.done();f.observeAllIndependentReadinessInputs();f.advance(4_000);f.drainUi()
  assertNull(f.confirm.confirmedDecision());assertTrue(f.gateway.background.isEmpty());assertEquals(0,f.activations);assertNull(f.workflow.nativePrewalkStartEpoch)
 }
 @Test fun doneAndTimerCannotBypassUnavailableCurrentSensorEvidence() = Fixture().use { f ->
  f.button();f.nativeStart(f.promptId());f.nativeDone(f.promptId());f.advance(3_000);f.drainUi()
  assertEquals(GuidanceStartCalibration.Phase.READY,f.calibration.snapshot?.phase)
  assertEquals("WAIT_ENVIRONMENT_OR_MOUNTING",f.stage);assertNull(f.confirm.confirmedDecision());assertTrue(f.gateway.background.isEmpty())
  f.observeAllIndependentReadinessInputs();f.drainUi();assertEquals(1,f.gateway.background.size)
 }
 @Test fun gpsEvidenceAloneDoesNotAdmitCameraMountingOrModelReadiness() = Fixture().use { f ->
  f.unloadDetector()
  f.observeGpsQuality()
  val gpsOnly=f.capture(WalkSessionAction.START_WALK)
  assertEquals(WalkSessionReadinessStatus.PENDING,gpsOnly.observations.getValue(WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT).status)
  assertEquals(WalkSessionReadinessStatus.PENDING,gpsOnly.observations.getValue(WalkSessionReadinessRequirement.PHONE_MOUNTING).status)
  assertEquals(WalkSessionReadinessStatus.UNAVAILABLE,gpsOnly.observations.getValue(WalkSessionReadinessRequirement.MODEL).status)
  f.confirmOfficialConditions();f.confirmPhoneMounting();f.observeCameraFrame();f.loadDetector()
  val complete=f.capture(WalkSessionAction.START_WALK)
  assertTrue("readiness=${complete.observations}",complete.isReadyAt(f.now))
  assertTrue(f.readinessInputs.containsAll(listOf("gps_quality","camera_frame","official_conditions","phone_mounting","detector")))
 }
 @Test fun capturedHomeDestinationOwnerExpiresWhenItsPositioningBindingRetires() = Fixture().use { f ->
  f.button();f.nativeStart(f.promptId());f.nativeDone(f.promptId());f.observeAllIndependentReadinessInputs()
  f.advance(3_000);f.drainUi();assertEquals(1,f.gateway.background.size)
  f.gateway.runBackground();f.drainUi()
  assertEquals(WalkSessionState.ACTIVE,f.walk.state)
  val owner=requireNotNull(f.nativeDestinationAdmission.capture(f.destination))
  assertTrue(owner())
  f.positioningBindingCurrent=false
  assertFalse("a retained Home destination owner cannot outlive its exact positioning binding",owner())
 }

 private class ControlledFusedLocation(
  private val latitudeValue:Double,
  private val longitudeValue:Double,
  private val accuracyValue:Float,
  private val elapsedMs:Long,
 ):android.location.Location("controlled-fused-provider") {
  override fun getProvider()="controlled-fused-provider"
  override fun isFromMockProvider()=false
  override fun getLatitude()=latitudeValue
  override fun getLongitude()=longitudeValue
  override fun hasAccuracy()=true
  override fun getAccuracy()=accuracyValue
  override fun getElapsedRealtimeNanos()=elapsedMs*1_000_000L
  override fun getTime()=elapsedMs
  override fun hasAltitude()=false
  override fun hasVerticalAccuracy()=false
  override fun hasSpeed()=false
  override fun hasSpeedAccuracy()=false
  override fun hasBearing()=false
  override fun hasBearingAccuracy()=false
  override fun hasElapsedRealtimeUncertaintyNanos()=false
  override fun hasMslAltitude()=false
  override fun hasMslAltitudeAccuracy()=false
  override fun getAltitude()=0.0
  override fun getVerticalAccuracyMeters()=0f
  override fun getSpeed()=0f
  override fun getSpeedAccuracyMetersPerSecond()=0f
  override fun getBearing()=0f
  override fun getBearingAccuracyDegrees()=0f
  override fun getElapsedRealtimeUncertaintyNanos()=0.0
  override fun getMslAltitudeMeters()=0.0
  override fun getMslAltitudeAccuracyMeters()=0f
 }

 @Suppress("UNCHECKED_CAST")
 private fun <T> interfacePort(type:Class<T>, handler:(String,Array<out Any?>?)->Any?):T =
  java.lang.reflect.Proxy.newProxyInstance(type.classLoader,arrayOf(type)) { _,method,args -> handler(method.name,args) } as T

 private class Fixture(initialEngineReady:Boolean=true):AutoCloseable {
  val gateway=GatewayFixture();val resume=ResumeFixture()
  var now:Long get()=gateway.now;set(v){gateway.now=v;put(outputClock,"now",v)}
  val walk get()=gateway.lifecycle.snapshot()
  var actor:String?=gateway.account.actorId;var foreground=true;var permission=true
  val readinessInputs=mutableSetOf<String>()
  private var gpsObservation:GpsQualityObservation?=null
  private var cameraObservation:CameraFrameQualityObservation?=null
  private var officialConfirmation:OfficialEnvironmentUserConfirmation?=null
  private var mountingConfirmation:PhoneMountingUserConfirmation?=null
  private var detectorAvailable=true // The app loads the detector during startup, before Home start.
  var engineIsReady=false;var activations=0;var navigationStarts=0;var stage="";var refreshDepth=0
  var positioningBindingChecks=0;var positioningBindingCurrent=true
  val ui=ArrayDeque<()->Unit>();val timers=mutableListOf<Pair<Long,Runnable>>()
  val destination=DestinationSearchResult("selected-place","목적지",RoutePoint(37.5,127.0),null,null,null,null)
  val destinationHandOff=mutableListOf<DestinationSearchResult>();var onDestinationSelected:((DestinationSearchResult)->Boolean)?=null
  val calibration=GuidanceStartCalibration()
  val permissionRecoveryIdentity: Any = Any()
  val locationGeneration = 1
  val routeGeneration = 0
  val nativeDestinationAdmission: NativeDestinationStartAdmission by lazy {
   NativeDestinationStartAdmission(workflow,
    object : NativeDestinationStartAdmission.Scope {
     override fun homeAvailable() = foreground && actor == gateway.account.actorId
     override fun runtimeActive() = walk.state == WalkSessionState.ACTIVE
     override fun epoch() = walk.epoch
     override fun gatewayOwner() = NativeDestinationStartAdmission.GatewayOwner(3L, gateway.account)
     override fun reporterId() = actor
     override fun permissionRecoveryIdentity() = permissionRecoveryIdentity
     override fun routeGeneration() = routeGeneration
  }, object : NativeDestinationStartAdmission.PositioningSource {
     override fun generation() = locationGeneration
     override fun binding() = NativeDestinationStartAdmission.Binding(
      identity=positioningCollectionBinding,actorId=positioningCollectionOwner.actorId,
      gatewaySessionIdentity=gateway.account,gatewayGeneration=3L,
      currentCoordinatorAndCollection={positioningBindingChecks++;
       positioningBindingCurrent && positioningCoordinator === positioningCollectionBinding.coordinator &&
        positioningCoordinator.currentInputGeneration()==positioningCollectionBinding.inputGeneration &&
        locationCallbackCurrent && positioningCollectionOwner==positioningCollectionBinding.owner})
    })
  }
  private val positioningCoordinator=PositioningCoordinator()
  private val positioningCollectionOwner=LocationCollectionOwner(gateway.account.actorId,gateway.account,3L)
  private val positioningCollectionBinding=PositioningCollectionBinding(positioningCoordinator,
   positioningCoordinator.currentInputGeneration(),positioningCollectionOwner,locationGeneration,now)
  var locationCallbackCurrent=true
  data class Prompt(val lease:GuidanceStartCalibration.PromptLease,val done:()->Unit,val failed:()->Unit)
  val prompts=mutableListOf<Prompt>();var calibrationTimer:Runnable?=null;var prepTimer:Runnable?=null
  val outputFixture=Class.forName("kr.co.hanium.dreamup.walksafe.voice.SpeechOutputControllerBehaviorTest\$Fixture").getDeclaredConstructor().apply{isAccessible=true}.newInstance()
  val output:SpeechOutputController=read(outputFixture,"controller");val outputClock:Any=read(outputFixture,"clock")
  lateinit var nativeEngine:Any
  val spoken get()=read<List<Any>>(nativeEngine,"spoken")
  val decision get()=WalkSafeStartupCapabilityDecision(if(engineIsReady)WalkSafeStartupCapabilityTier.FULL else WalkSafeStartupCapabilityTier.BLOCKED,
   emptyList(),if(engineIsReady)emptyList() else listOf(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS),"기기 기능 확인")
  private val officialProfile=ApprovedOfficialEnvironmentProfile("replay-official","replay-camera",10.0,10_000L,2)
  private val cameraProfile=ApprovedCameraFrameQualityProfile("replay-camera",0.2,0.8,60.0,5.0,85.0,10_000L)
  private val mountingProfile=ApprovedPhoneMountingProfile("replay-mounting","replay-camera",5_000L,2,30_000L)
  private fun cameraAssessment(epoch:WalkRuntimeEpoch,at:Long)=CameraFrameQualityPolicy.assess(epoch,now,
   cameraObservation?.let{it.copy(epoch=epoch,observedAtElapsedRealtimeMs=at)},cameraProfile)
  private fun officialAssessment(epoch:WalkRuntimeEpoch):OfficialEnvironmentAssessment {
   val gps=gpsObservation?.let{OfficialEnvironmentPolicy.assessGpsQuality(epoch,now,it,officialProfile)}
   return OfficialEnvironmentPolicy.assess(epoch,now,gps,cameraAssessment(epoch,cameraObservation?.observedAtElapsedRealtimeMs?:now).asMeasuredEnvironmentEvidence(),
    officialConfirmation?.takeIf{it.epoch==epoch},officialProfile,usageLimitsAcknowledged=true)
  }
  private fun mountingAssessment(epoch:WalkRuntimeEpoch):PhoneMountingAssessment = PhoneMountingPolicy.assess(
   PhoneMountingAssessmentPhase.PREFLIGHT,epoch,now,mountingConfirmation?.takeIf{it.epoch==epoch},
   cameraAssessment(epoch,cameraObservation?.observedAtElapsedRealtimeMs?:now),PhoneMountingPolicy.initialState(epoch,mountingProfile))
  private val readinessCoordinator:WalkReadinessCoordinator by lazy {
   val official=object:OfficialReadinessSource {
    override fun assessment(epoch:WalkRuntimeEpoch)=officialAssessment(epoch)
    override fun readiness(epoch:WalkRuntimeEpoch,assessment:OfficialEnvironmentAssessment?):Pair<WalkSessionReadinessStatus,String> = when {
     assessment?.canStartWalk==true -> WalkSessionReadinessStatus.READY to ""
     gpsObservation==null||cameraObservation==null||officialConfirmation==null -> WalkSessionReadinessStatus.PENDING to "official_environment_evidence_pending"
     else -> WalkSessionReadinessStatus.UNAVAILABLE to "official_environment_not_supported"
    }
   }
   val mounting=object:MountingReadinessSource {
    override fun assessment(epoch:WalkRuntimeEpoch)=mountingAssessment(epoch)
    override fun readiness(epoch:WalkRuntimeEpoch,assessment:PhoneMountingAssessment?):Pair<WalkSessionReadinessStatus,String> = when {
     assessment?.canStartOrResumeDetection==true -> WalkSessionReadinessStatus.READY to ""
     cameraObservation==null||mountingConfirmation==null -> WalkSessionReadinessStatus.PENDING to "mounting_evidence_pending"
     else -> WalkSessionReadinessStatus.UNAVAILABLE to "mounting_not_suitable"
    }
   }
   val feature=FeatureReadinessProducer(object:ReadinessFeatureAdmission {
    override fun cameraEnabled()=true
    override fun cameraPermission()=permission
    override fun locationEnabled()=true
    override fun locationPermission()=permission
    override fun disabledFeatures()=emptySet<PostLoginDeviceCheckFeature>()
    override fun restrict(decision:WalkSafeStartupCapabilityDecision)=decision.copy(unavailableRequirements=decision.unavailableRequirements+WalkSafeStartupRequirement.CAMERA)
   },object:ReadinessDetector {
    override fun ensureLoadedAfterCameraGate() {}
    override fun isAvailable()=detectorAvailable
    override fun loadFailureReason()=if(detectorAvailable)null else "controlled_detector_not_ready"
    override fun modelKey()="replay-model-r1"
   })
   val resource=object:ReadinessResourceSource {
    override fun retryFailedMonitoring() {}
    override fun snapshot()=WalkSessionDeviceResourceSnapshot(true,true,true,checkedAtElapsedRealtimeMs=WalkSessionResourceQuery.entries.associateWith{now})
    override fun recordDiagnostic(source:String,resources:WalkSessionDeviceResourceSnapshot) {}
   }
   val capability=StartupCapabilityReadinessEvidence({decision},{"replay-capability-r1"},{now}).also{it.record(decision)}
   val producers=WalkReadinessProducers(FirstRunReadinessProducer({true},{FirstRunOnboardingStage.VERIFIED_LOGIN}),
    PriorityWalkReadinessProducer({PriorityUserOnboardingPolicy()},{PriorityUserSupportEnvironment(false,false,false,true,true)},
     {gateway.lifecycle.isRuntimeEpochCurrent(walk.epoch)},{true},{actor}),
    PermissionReadinessProducer({ObservedPermissionSnapshot(permission,permission,permission,permission)},{permission}),feature,
    EndpointReadinessProducer(true,"https://gateway.example.test"),GatewayReadinessProducer{actor==gateway.account.actorId&&gateway.actorCurrent},official,mounting)
   WalkReadinessCoordinator({gateway.lifecycle},capability,resource,official,mounting,producers,{false},{now},35)
  }
  fun capture(action:WalkSessionAction,checkEntryPermissions:Boolean=true):WalkSessionReadinessSnapshot = readinessCoordinator.capture(decision,action,checkEntryPermissions=checkEntryPermissions)
  val confirmationFacts=port<StartupConfirmationFacts>{n,a->when(n){
   "getDecision"->decision;"getForeground"->foreground;"getActorId","currentReporter"->actor;"getFeedbackGeneration"->1
   "feedbackCurrent"->foreground&&actor==gateway.account.actorId;"onboardingComplete"->true;else->null}}
  val confirm=StartupConfirmationInteractionController({gateway.lifecycle},confirmationFacts,
   port<StartupConfirmationEffects>{n,a->when(n){"refresh"->{refresh();Unit};"onUi"->{@Suppress("UNCHECKED_CAST") ui+=(a[0] as ()->Unit);Unit};else->null}},StartupDecisionStorage{_,_->})
  val confirmation=object:StartupCapabilityConfirmation {
   override var confirmed:WalkSafeStartupCapabilityDecision? get()=confirm.confirmedDecision();set(v){confirm.writeConfirmedDecision(v)}
   override var pending:Boolean get()=confirm.confirmationPending();set(v){confirm.writeConfirmationPending(v)}
   override var retryRequiresUserAction:Boolean get()=confirm.retryRequired();set(v){confirm.writeRetryRequired(v)}
   override fun isConfirmed()=confirm.isStartupCapabilityConfirmed()
  }
  val readiness=object:WalkResumeReadiness {
   override fun decision()=this@Fixture.decision
   override fun blockReason(decision:WalkSafeStartupCapabilityDecision):String?=if(!permission)"permission" else null
   override fun capture(decision:WalkSafeStartupCapabilityDecision,action:WalkSessionAction,recordResourceDiagnostics:Boolean)=this@Fixture.capture(action)
   override fun isConfirmed()=confirm.isStartupCapabilityConfirmed()
  }
  val gatewayController:GatewayWalkSessionController by lazy {
   GatewayWalkSessionController(gateway.authority,gateway,object:GatewayWalkRuntime by gateway {
    override fun currentStartReadinessOrNull(token:WalkSessionConfirmationToken)=capture(WalkSessionAction.START_WALK).takeIf{permission&&actor==gateway.account.actorId&&walk.confirmationToken==token}
    override fun activate():Boolean {activations++;return activation.activateWalkSessionRuntime()}
    override fun isForeground()=foreground
    override fun refreshReadinessUi(){refresh()}
   },gateway,gateway,gateway,30_000L,"restart",false)
  }
  val admission:WalkSessionAdmissionController by lazy {WalkSessionAdmissionController(
   object:WalkSessionRuntimeAccess {override fun available()=true;override fun snapshot()=walk
    override fun isRuntimeEpochCurrent(epoch:WalkRuntimeEpoch)=gateway.lifecycle.isRuntimeEpochCurrent(epoch)
    override fun transition(event:WalkSessionEvent)=gateway.lifecycle.handle(event)},gateway.authority,gatewayController,resume.controller,readiness,
   port<WalkAdmissionState>{n,_->when(n){"isForeground"->foreground;"startupConfirmationPending"->confirm.confirmationPending();"startupRetryRequiresUserAction"->confirm.retryRequired();else->null}},
   port<WalkAdmissionEffects>{n,_->when(n){"refreshReadinessUi"->{refresh();Unit};"persistInterruptionMarker"->true;else->null}},setOf(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS),{now})}
  fun navigationAdmitted()=walk.state==WalkSessionState.ACTIVE&&foreground&&permission&&actor==gateway.account.actorId&&confirm.isStartupCapabilityConfirmed()&&capture(WalkSessionAction.ROUTE_REQUEST).isReadyAt(now)
  val activation:WalkRuntimeActivationController by lazy {WalkRuntimeActivationController(
   port<WalkActivationAdmission>{n,a->when(n){"runtimeActive"->walk.state==WalkSessionState.ACTIVE;"runtimeEpochOrNull"->gateway.lifecycle.currentRuntimeEpochOrNull();"snapshot"->walk
    "isRuntimeEpochCurrent"->gateway.lifecycle.isRuntimeEpochCurrent(a[0] as WalkRuntimeEpoch);"isForeground"->foreground;"navigationOutputsAllowed"->navigationAdmitted()
    "permissionBlocksAutomaticStart"->!permission;"nowMs"->now;"beginSafetyEpoch"->WalkRuntimeSafetyDecision(WalkRuntimeSafetyDisposition.ALLOW_SAFETY_OUTPUT,a[0] as WalkRuntimeEpoch);else->null}},readiness,
   port<WalkActivationEffects>{n,_->if(n=="persistInterruptionMarker")true else null},
   port<WalkActivationServices>{n,_->when(n){"observeResourceSafety"->foreground;"activateOfficialEnvironment"->readinessStatus(WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT)==WalkSessionReadinessStatus.READY
    "activatePhoneMounting"->readinessStatus(WalkSessionReadinessRequirement.PHONE_MOUNTING)==WalkSessionReadinessStatus.READY
    "startNavigation"->{navigationStarts++;Unit};"refreshReadinessUi"->{refresh();Unit};else->null}},
   port<WalkActivationCamera>{n,a->when(n){"stopEnvironmentPreflight"->{@Suppress("UNCHECKED_CAST")(a[1] as ()->Unit).invoke();Unit};"postAfterRelease"->{@Suppress("UNCHECKED_CAST")ui+=(a[0] as ()->Unit);Unit};else->null}},gatewayController,resume.controller)}
  val workflow:NativeWorkflowController by lazy {NativeWorkflowController(
   port<NativeWorkflowController.EntryQueries>{n,_->when(n){"shouldShowNativeHome","nativeHomeFeatureContextAvailable"->foreground&&actor==gateway.account.actorId
    "isWalkSessionRuntimeActive"->walk.state==WalkSessionState.ACTIVE;"nativeFeatureAvailable"->navigationAdmitted();else->null}},
   port<NativeWorkflowController.PageUi>{n,_->if(n=="readyForNativeRendering")true else null},
   port<NativeWorkflowController.PreparationActions>{n,_->when(n){"continueNativeFeaturePreparation"->{native.continueFeaturePreparation();Unit};else->null}},
   port<NativeWorkflowController.DestinationTransactions>{n,a->when(n){
    "captureNativeDestinationStartOwner"->nativeDestinationAdmission.capture(a[0] as DestinationSearchResult)
    "postNativeDestinationStart"->{val dest=a[0] as DestinationSearchResult;workflow.postNativeDestinationStart(dest,{ui+={it.run()}},{navigationAdmitted()},{entry.start()});Unit}
    else->null}})}
  val preparation:GuidancePreparationController by lazy {GuidancePreparationController(workflow,
   port<GuidancePreparationController.Scope>{n,_->when(n){"reporterId"->actor;"gatewayGeneration"->3L;"epoch"->walk.epoch;"walkState"->walk.state;"foreground"->foreground;else->null}},port(),
   object:GuidancePreparationController.Callbacks {override fun postDelayed(action:Runnable,delayMs:Long){timers+=now+delayMs to action};override fun remove(action:Runnable){timers.removeAll{it.second===action}}},{now})}
  val native:NativePrewalkController by lazy {NativePrewalkController(workflow,
   port<NativePrewalkController.Scope>{n,_->when(n){"homeAvailable"->foreground&&actor==gateway.account.actorId;"foreground"->foreground;"reporterId"->actor;"gatewayGeneration"->3L;"walk"->walk;"runtimeActive"->walk.state==WalkSessionState.ACTIVE;else->null}},
   port<NativePrewalkController.Checks>{n,_->when(n){"firstRunComplete","environmentProfilesPresent","officialProfilePresent","mountingProfilePresent"->true
    "locationAccessUnavailable"->!permission;"environmentReadiness"->readinessStatus(WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT)
    "mountingReadiness"->readinessStatus(WalkSessionReadinessRequirement.PHONE_MOUNTING);"startupDecision"->decision;"readinessBlockReason"->readiness.blockReason(decision)
    "confirmationPending"->confirm.confirmationPending();"startupConfirmed"->confirm.isStartupCapabilityConfirmed();"failureSpeech"->"위치 권한을 확인해 주세요.";else->null}},
   object:NativePrewalkController.Speech {override fun commandPending()=false;override fun submit(message:String,current:()->Boolean,completed:()->Unit,failed:()->Unit,cancelled:()->Unit,retired:()->Unit)=
    output.speakCommandResponse(message,statusExplanation=true,additionalCurrent=current,onCompleted=completed,onFailed=failed,onCancelled=cancelled,onRetired=retired)},
   port<NativePrewalkController.Actions>{n,a->when(n){
    "beginCalibration"->{advanceCalibration(a[0] as NativePhoneMountingCheckRequest);Unit};"calibrationReady"->advanceCalibration(a[0] as NativePhoneMountingCheckRequest)
    "cancelCalibration","cancelCalibrationForLocationAccess"->{calibration.cancel();calibrationTimer?.let{r->timers.removeAll{it.second===r}};calibrationTimer=null;Unit}
    "retryPreparation"->{val r=a[0] as NativePhoneMountingCheckRequest;if(prepTimer==null){val t=Runnable{prepTimer=null;if(requestCurrent(r))native.continueIfReady()};prepTimer=t;timers+=now+2_000 to t};Unit}
    "requestStartConfirmation"->{@Suppress("UNCHECKED_CAST") val current=a[0] as ()->Boolean; @Suppress("UNCHECKED_CAST") val accept=a[1] as ()->Unit; if(current())accept();Unit}
    "postStartup"->{@Suppress("UNCHECKED_CAST")ui+=(a[0] as ()->Unit);Unit};"completeStartupConfirmation"->{confirm.completeStartupCapabilityConfirmation(a[0] as WalkSafeStartupCapabilityDecision);Unit}
    "renderMain"->{workflow.renderMainUi();Unit};"clearStartupRetryRequirement"->{confirm.writeRetryRequired(false);Unit}
    "cancelPendingFeatureEntry"->{workflow.pendingNativeUiPage=null;Unit};else->null}},
   object:NativePrewalkController.Diagnostics {override fun enabled()=true;override fun capture(s:String,e:WalkRuntimeEpoch,a:String?,c:Boolean):String {stage=s;return s};override fun record(state:String){};override fun directStart(state:WalkSessionState){}},{now})}
  val entry:NativeGuidanceEntryController by lazy {NativeGuidanceEntryController(workflow,native,preparation,
   port<NativeGuidanceEntryController.Scope>{n,_->when(n){"runtimeActive"->walk.state==WalkSessionState.ACTIVE;else->null}},
   port<NativeGuidanceEntryController.Actions>{n,a->when(n){"renderMain"->{workflow.renderMainUi();Unit};"handOffDestinationStart"->{val d=a[0] as DestinationSearchResult
    workflow.handOffNativeDestinationStart(d,{}, {destinationHandOff+=it;onDestinationSelected?.invoke(it)?:true},{workflow.renderMainUi()},{navigationAdmitted()});Unit};else->null}})}
  val evidence=StartupCapabilityReadinessEvidence({decision},{"controlled-platform-v1"},{now})
  val startup:StartupCapabilityCoordinator by lazy {StartupCapabilityCoordinator(port<StartupCapabilityEvaluation>{n,_->when(n){"probeDecision"->decision;else->null}},
   {evidence},{gateway.lifecycle},confirmation,port<StartupCapabilityUi>{n,_->when(n){"ready"->true
    "priority"->PriorityUserOnboardingDecision(PriorityUserSupportPriority.EQUAL_BLIND_AND_LOW_VISION,null,null,emptyList(),false)
    "officialReadiness"->readinessStatus(WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT)
    "mountingReadiness"->readinessStatus(WalkSessionReadinessRequirement.PHONE_MOUNTING);"updatePrewalk"->{native.continueIfReady();Unit};"advanceAfterCheck"->{admission.maybeAdvanceWalkSessionAfterCapabilityCheck();Unit};else->null}},port<ActiveReadinessPort>{n,a->when(n){"capture"->capture(WalkSessionAction.START_WALK,checkEntryPermissions=false);"transition"->gateway.lifecycle.handle(a[0] as WalkSessionEvent);else->null}},"voice unavailable")}
  init {gateway.lifecycle.handle(WalkSessionEvent.EnteredForeground);put(outputClock,"now",gateway.now)
   val old:CommandSpeechContext=read(outputFixture,"context");put(outputFixture,"context",old.copy(actorId=actor,gatewayGeneration=3,walkEpoch=walk.epoch,page=NativeUiPage.GUIDANCE,visibleResponse=false,voiceInteractionPage=false))
   output.ensureFeedbackActuator();nativeEngine=read<List<Any>>(outputFixture,"runtimes").single();if(initialEngineReady)engineReady()
   workflow.pendingUiDestination=destination
  }
  fun button(){entry.start();refresh()}
  fun refresh(){check(++refreshDepth<30){"actual synchronous refresh recursion"};try{startup.refreshStartupCapabilityUi()}finally{refreshDepth--}}
  fun engineReady(){engineIsReady=true;call(nativeEngine,"ready")}
  /** Controlled observations only, never an actual GPS/camera/mounting PASS claim. */
  fun readinessStatus(requirement:WalkSessionReadinessRequirement)=capture(WalkSessionAction.START_WALK).observations.getValue(requirement).status
  fun observeGpsQuality(){gpsObservation=GpsQualityObservation(walk.epoch,now,true,2.0);readinessInputs+="gps_quality";refresh()}
  fun observeCameraFrame(){cameraObservation=CameraFrameQualityObservation(walk.epoch,now,true,0.6,0.0,0.0,45.0);readinessInputs+="camera_frame";refresh()}
  fun confirmOfficialConditions(){officialConfirmation=OfficialEnvironmentUserConfirmation(walk.epoch,
   EnvironmentEvidenceStatus.PASS,EnvironmentEvidenceStatus.PASS,EnvironmentEvidenceStatus.PASS,EnvironmentEvidenceStatus.PASS,
   EnvironmentEvidenceStatus.PASS,EnvironmentEvidenceStatus.PASS,EnvironmentEvidenceStatus.PASS);readinessInputs+="official_conditions";refresh()}
  fun confirmPhoneMounting(){mountingConfirmation=PhoneMountingUserConfirmation(walk.epoch,(now-1L).coerceAtLeast(0L),PhoneMountingMethod.CHEST_FORWARD,false);readinessInputs+="phone_mounting";refresh()}
  fun unloadDetector(){detectorAvailable=false}
  fun loadDetector(){detectorAvailable=true;readinessInputs+="detector";refresh()}
  fun observeAllIndependentReadinessInputs(){observeGpsQuality();confirmOfficialConditions();confirmPhoneMounting();observeCameraFrame();loadDetector()}
  fun requestCurrent(r:NativePhoneMountingCheckRequest)=workflow.nativePhoneMountingCheckRequest===r&&foreground&&walk.isForeground&&walk.state in setOf(WalkSessionState.READY,WalkSessionState.PAUSED)&&walk.epoch==r.checkRequest.epoch&&actor==r.actorId&&r.gatewayGeneration==3L&&workflow.pendingUiDestination==r.destination
  fun advanceCalibration(r:NativePhoneMountingCheckRequest):Boolean {
   if(!requestCurrent(r)||!permission){calibration.cancel();return false}
   val attempt=calibration.begin(r,now)?:return false;val s=calibration.evaluate(attempt,now,true)?:return false
   if(s.phase==GuidanceStartCalibration.Phase.READY)return true
   if(s.phase==GuidanceStartCalibration.Phase.OBSERVING){scheduleCalibration(r,(s.readyAtMs!!-now).coerceIn(1,300));return false}
   val lease=calibration.claimPrompt(attempt,now,true,false)
   if(lease!=null){
    val fail:()->Unit={if(calibration.voiceFailed(lease,now,requestCurrent(r)&&permission,1_000))scheduleCalibration(r,1_000)}
    val done:()->Unit={if(calibration.voiceDone(lease,now,now,requestCurrent(r)&&permission))scheduleCalibration(r,300)}
    prompts+=Prompt(lease,done,fail)
    val accepted=output.speakCommandResponse(GuidanceStartCalibration.PROMPT_KO,statusExplanation=true,additionalCurrent={requestCurrent(r)&&permission&&calibration.isPromptCurrent(lease)},onCompleted=done,onFailed=fail,onCancelled=fail,onRetired=fail)
    if(!accepted)fail()
   };scheduleCalibration(r,1_000);return false
  }
  fun scheduleCalibration(r:NativePhoneMountingCheckRequest,delay:Long){if(calibrationTimer!=null)return;val t=Runnable{calibrationTimer=null;if(requestCurrent(r))native.continueIfReady()};calibrationTimer=t;timers+=now+delay to t}
  fun advance(delta:Long){val end=now+delta;var count=0;while(true){val next=timers.filter{it.first<=end}.minByOrNull{it.first}?:break;timers.remove(next);now=maxOf(now,next.first);next.second.run();drainUi();check(++count<100){"timer spin"}};now=end}
  fun drainUi(){var count=0;while(ui.isNotEmpty()){ui.removeFirst()();check(++count<100){"ui spin"}}}
  fun promptId():String=read(spoken.last{read<String>(it,"message")==GuidanceStartCalibration.PROMPT_KO},"id")
  fun nativeStart(id:String){call(nativeEngine,"start",id)}
  fun nativeDone(id:String){call(nativeEngine,"done",id)}
  fun nativeError(id:String){call(read(nativeEngine,"listener"),"onError",id);call(outputClock,"advanceBy",0L)}
  override fun close(){output.closeActuator()}
 }
 companion object {
  /** Accepts the normal Gradle app JAR/classes layout and a script's copied production JAR.
   * Does not use a private machine path or count an expected Android stub failure as success.
   */
  @BeforeClass @JvmStatic fun productionOwnersComeFromTheSameProductionArtifact() {
   val production = NativePrewalkController::class.java.protectionDomain.codeSource.location
   val tests = GuidanceReadyStartReplayTest::class.java.protectionDomain.codeSource.location
   assertNotEquals("Production owners must not be supplied by this test output", tests, production)
   for (type in listOf(
    NativeGuidanceEntryController::class.java, GuidanceStartCalibration::class.java,
    NativeDestinationStartAdmission::class.java, StartupCapabilityCoordinator::class.java,
    StartupConfirmationInteractionController::class.java, WalkSessionAdmissionController::class.java,
    GatewayWalkSessionController::class.java, WalkRuntimeActivationController::class.java,
    NativeWorkflowController::class.java, SpeechOutputController::class.java,
   )) assertEquals("Mixed production definitions: ${type.name}", production, type.protectionDomain.codeSource.location)
   println("READY_START_REPLAY production=$production tests=$tests; controller-chain with controlled platform observations")
  }
  private inline fun <reified T> port(noinline respond: (String, Array<out Any?>) -> Any? = { _, _ -> null }): T =
   Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
    respond(method.name, args ?: emptyArray()) ?: when (method.returnType) {
     java.lang.Boolean.TYPE -> false
     java.lang.Integer.TYPE -> 0
     java.lang.Long.TYPE -> 0L
     else -> null
    }
   } as T
  @Suppress("UNCHECKED_CAST") private fun <T> read(o:Any,n:String):T=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o) as T
  private fun put(o:Any,n:String,v:Any?){o.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(o,v)}
  private fun call(o:Any,n:String,vararg a:Any?){o.javaClass.declaredMethods.single{it.name==n&&it.parameterCount==a.size}.apply{isAccessible=true}.invoke(o,*a)}
 }
}
