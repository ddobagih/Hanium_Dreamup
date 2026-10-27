package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.start.*
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

import kr.co.hanium.dreamup.walksafe.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.location.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.*
import android.location.Location
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.Executor
import java.util.Locale
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kr.co.hanium.dreamup.walksafe.voice.CurrentStateSpeechLease
import kr.co.hanium.dreamup.walksafe.device.environment.*
/** One actual READY controller chain and one production NavigationSessionComposition/run.
 * This is controlled platform replay, not Android View startup, RAW sensor conversion, acoustic
 * output or a field walk. No ACTIVE/confirmed/readiness/match flag is seeded. GPS is fed to the
 * registered Fused callback before the start button and keeps the same binding through END.
 */
 internal class GuidanceFullJourneyReplayHarness(
  initialEngineReady:Boolean=true,
  val caseId:String="healthy",
  initialSynthesisReady:Boolean?=true,
  approvedGpsAccuracyM:Double=10.0,
 ):AutoCloseable {
  val gateway=GatewayFixture().also { fixture ->
   // Controlled account-store input; preserve one valid actor/session before any collection starts.
   put(fixture,"account",GatewayFieldSession.longLivedSession("https://gateway.example.test",
    "actor_"+"a".repeat(32),"device-0001","f".repeat(32),1L,
    "${GatewayFieldSession.COOKIE_NAME}=${"a".repeat(32)}","r".repeat(64),
    Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE))
  };val resume=ResumeFixture()
  val runtime=NativeRuntime()
  private val trace=JSONArray()
  private var gatewayGeneration=0L
  private lateinit var retainedBinding:PositioningCollectionBinding
  private val io=ArrayDeque<Runnable>()
  private val workers=ArrayDeque<Runnable>()
  private var registeredCallback:LocationCallback?=null
  private var sdkCurrent:((LocationCallback)->Boolean)?=null
  private var headingObservation:RouteFacingObservation?=null
  private var headingReason=RouteCompassHeadingReason.ORIENTATION_MISSING
  private var latestGpsEast=0.0
  private var latestGpsNorth=0.0
  private var feedbackGeneration=1
  var providerResponse:(WalkingRouteRequest)->WalkingRoute={ defaultProviderResponse() }
  private var headingListener:((RouteCompassObservationEvent)->Unit)?=null
  private var headingRegistered=false
  private var steps=0
  private var stepSourceStarted=false
  private var rawStepCounterTotal=10_000f
  private val stepTracker by lazy { AndroidStepTracker(
   registration=object:StepSensorRegistration {
    override fun hasSensor(type:Int)=type==android.hardware.Sensor.TYPE_STEP_COUNTER
    override fun register(listener:android.hardware.SensorEventListener,type:Int):Boolean {
     log("STEP_SDK_REGISTER","type" to type);return type==android.hardware.Sensor.TYPE_STEP_COUNTER
    }
    override fun unregister(listener:android.hardware.SensorEventListener?){log("STEP_SDK_UNREGISTER")}
   },onStepCountChanged={count->
    if(gateway.lifecycle.isRuntimeEpochCurrent(walk.epoch)){steps=count;log("STEP_COUNT_INPUT","count" to count,"original_at_ms" to now)}
   },onStepEvent={event->
    val binding=requireNotNull(graph.positioning.value.binding)
    val motionOwner=requireNotNull(binding.coordinator.currentUserMotionOwner())
    log("STEP_EVENT_INPUT","delta_steps" to event.deltaSteps,"source" to event.source.name,
     "confidence" to event.confidence.name,"original_at_ns" to event.measurementElapsedRealtimeNanos,
     "receipt_at_ns" to event.receivedAtElapsedRealtimeNanos,"producer_generation" to event.producerRegistrationGeneration)
    graph.positioning.value.handlePositioningStepEvent(event,binding,now,motionOwner)
   }) }
  fun beginWalkingStepInputs(){
   if(stepSourceStarted)return
   check(walk.state==WalkSessionState.ACTIVE)
   check(stepTracker.start())
   check(stepTracker.onStepCounterTotal(rawStepCounterTotal,now*1_000_000L,now*1_000_000L))
   stepSourceStarted=true
   log("STEP_COUNTER_BASELINE","raw_total" to rawStepCounterTotal,"original_at_ns" to now*1_000_000L)
  }
  /** Controlled native Counter input; the actual tracker owns count normalization and Event creation. */
  fun walkingSteps(delta:Int){
   check(stepSourceStarted){"Deliver a distinct earlier Counter baseline before a walking batch"}
   require(delta in 1..64)
   rawStepCounterTotal+=delta
   check(stepTracker.onStepCounterTotal(rawStepCounterTotal,now*1_000_000L,now*1_000_000L))
   drainUi()
  }
  fun stationaryStepCounterObservation(){
   if(!stepSourceStarted)return
   check(stepTracker.onStepCounterTotal(rawStepCounterTotal,now*1_000_000L,now*1_000_000L))
   drainUi()
  }
  private val keys=mutableMapOf<String,SecretKey>()
  var stored:String?=null;private set
  val providerRequests=mutableListOf<WalkingRouteRequest>()
  val stages=mutableListOf<String>()
  private val permissionPolicy=PermissionSessionPolicy()
  private val deletion=AccountDeletionStateMachine()
  private val deletionLease=deletion.attachActivity()
  private val installedSpeech=InstalledRouteStartSpeechCoordinator()
  private val lane by lazy { RouteSnapshotIoLane(Executor { io+=it }) }
  // Current-policy caller; the v1 sensor replay retains its original independent 9s/4.5s fixture.
  private val gpsGate=RuntimeMeasuredQualityGate(GuidanceRecoveryTiming.GPS_RECHECK_PERIOD_MS,
   gpsFailureConfirmationPeriods=GuidanceRecoveryTiming.GPS_RECHECK_PERIODS)
  private val evidenceStore=OfficialEnvironmentEvidenceStore(Any(),gpsGate,
   RuntimeMeasuredQualityGate(GuidanceRecoveryTiming.CAMERA_GRACE_MS))
  private val callbacks by lazy { object:RouteCallbackScheduler {
   override fun post(task:Runnable){ui+={task.run()}}
   override fun postDelayed(task:Runnable,delayMs:Long){timers+=now+delayMs to task}
   override fun remove(task:Runnable){timers.removeAll{it.second===task}}
  } }
  private fun accountSnapshot()=GatewaySessionProcessCoordinator.snapshot()
  private fun owner()=LocationCollectionOwner(gateway.account.actorId,gateway.account,gatewayGeneration)
   .takeIf{foreground&&permission&&actor==gateway.account.actorId&&accountSnapshot().session===gateway.account&&
    arrivalCompletionOwner?.isCurrent()!=true&&walk.state!=WalkSessionState.ENDED}
  private fun environmentAllowed()=if(walk.state==WalkSessionState.ACTIVE)
   officialRuntime.outputsAllowed&&officialRuntime.guard?.decision()?.navigationOutputsAllowed==true
   else officialAssessment(walk.epoch).canStartWalk
  private val presentation by lazy { port<RoutePresentation>{n,a->when(n){
   "navigationStatus"->{log("STATUS","text" to a[0]);Unit};"speak","speakStatus"->{output.speakCommandResponse(a[0] as String,statusExplanation=true);Unit}
   "safetyStop"->error("Unexpected safety stop: ${a[0]}");else->null}} }
  val guidanceStartChecks=mutableListOf<Map<String,Any?>>()
  val acknowledgedRouteCues=mutableSetOf<RouteSpeechCueToken>()
  private val diagnostics by lazy { port<RouteGuidanceController.Diagnostics>{n,a->when(n){
   "getDebug","getRecording"->true;"record"->{
    if(a[0]=="route_guidance_started") {
     val values=(a[1] as Map<*,*>).entries.associate{it.key as String to it.value}
     guidanceStartChecks+=values
    }
    log("DIAGNOSTIC","event" to a[0],"values" to a[1].toString());Unit}
   "log"->{
    if(a[0]=="WalkSafeNavigation"&&a[1]=="speech=completed") {
     val completed=completedRouteSpeechCue()
     if(completed!=null)acknowledgedRouteCues+=completed
     log("NAVIGATOR_CUE_ACK","cue_id" to completed?.let{"${it.routeRevision}:${it.cueRevision}"})
    }
    log("DEBUG","tag" to a[0],"text" to a[1]);Unit};"confidenceLine"->"controlled-platform-position";else->null}} }
  val graph:NavigationSessionComposition by lazy { NavigationSessionComposition(false,
   PositioningAssemblyBindings(
    {port<PositioningSessionController.Scope>{n,_->when(n){"currentOwner"->owner();"getNavigationAllowed"->navigationAdmitted()
     "getLifecycle"->gateway.lifecycle;"getStepCount"->steps;"getProcessInstanceId"->"full-journey-replay";"reporterId"->actor;else->null}}},
    {object:PositioningSessionController.Sources {
     override val fusedAvailable=true
     override fun fusedSource(current:(LocationCallback)->Boolean,onEvent:(LocationCallback,FusedLocationUpdateEvent)->Unit):FusedLocationUpdateSource {
      sdkCurrent=current
      return FusedLocationUpdateSource(port<FusedLocationProviderClient>(),{now},current,onEvent).also{registeredCallback=it.callback}
     }
     override fun requestUpdates(source:FusedLocationUpdateSource){log("PS_REGISTER","generation" to graph.positioning.value.callbackGeneration)}
     override fun captureRemoval():(LocationCallback)->com.google.android.gms.tasks.Task<Void> = {log("PS_REMOVE");Tasks.forResult(null)}
     override val completionExecutor=Executor{it.run()}
     override fun gnssObserver(onSnapshot:(GnssQualitySnapshot)->Unit):GnssQualityObserver=error("RAW GNSS registration outside this replay")
     override fun pedestrianTracker(onMotion:(PedestrianStationaryDecision)->Unit):AndroidPedestrianMotionTracker=error("PDR registration outside this replay")
     override val profileStoreAvailable=false
     override fun profileStore():AndroidPedestrianProfileStore=error("Profile storage outside this replay")
     override fun mock(location:Location)=false
     override val orientationAvailable=false
     override val orientationCanStop=false
     override fun clearGeomagneticReference()=Unit
     override fun stopOrientation()=Unit
     override fun geomagneticReference(location:Location)=Unit
     override fun chestHeading(timestampMs:Long,maximumAgeMs:Long):HeadingObservation?=null
    }},
    {port<PositioningSessionController.Mounting>{n,_->when(n){"getObservationLock"->this;"getProfile"->mountingProfile
     "getConfirmation","getExplicitConfirmation"->mountingConfirmation;"getCameraAssessment"->cameraAssessment(walk.epoch,cameraObservation?.observedAtElapsedRealtimeMs?:now)
     "getOutputsAllowed"->mountingAssessment(walk.epoch).canStartOrResumeDetection;else->null}}},
    {port<PositioningActivityEffects>{n,a->when(n){"stopFieldSession"->true;"navigationStatus"->{log("POSITION_STATUS","text" to a[0]);Unit}
     "cleanupWarning"->throw(a[0] as RuntimeException);else->null}}}),
   GpsAssemblyBindings({gpsGate},
    {object:ContinuousGpsSession.EvidenceWriter {
     override fun clearGpsEvidence(){gpsObservation=null;evidenceStore.clearGpsEvidence()}
     override fun record(epoch:WalkRuntimeEpoch,observedAtMs:Long,trustedFixAvailable:Boolean?,accuracyM:Double?,elapsedRealtimeMs:()->Long,singleOutlier:Boolean){
      gpsObservation=GpsQualityObservation(epoch,observedAtMs,trustedFixAvailable,accuracyM)
      evidenceStore.recordGpsObservation(walk.epoch,epoch,observedAtMs,trustedFixAvailable,accuracyM,elapsedRealtimeMs,
       {officialProfile},{gateway.lifecycle.isRuntimeEpochCurrent(it)},{graph.gps.value.owner?.epoch},{false},
       {walk.state==WalkSessionState.ACTIVE},singleOutlier,{epoch,previous,current->officialRuntime.reactToAdmittedGpsObservation(epoch,previous,current)})
      readinessInputs+="gps_quality";log("GPS_EVIDENCE","observed_at_ms" to observedAtMs,"accuracy_m" to accuracyM,"trusted" to trustedFixAvailable)
     }
    }},{epoch->workflow.nativePrewalkStartEpoch==epoch},{refresh()},
    {result->ui+={result(null)};{}}),
   GuidanceAssemblyBindings(
    {port<GuidanceActivityScope>{n,a->when(n){"getLifecycle"->gateway.lifecycle;"getForeground"->foreground
     "getRuntimeActive"->walk.state==WalkSessionState.ACTIVE;"getNavigationAllowed"->navigationAdmitted()&&environmentAllowed();"getFeedbackGeneration"->feedbackGeneration
     "feedbackCurrent"->foreground&&a[0]==feedbackGeneration;else->null}}}, {steps},{.7f}, {speechPort()},
    {port<GuidanceActivityEffects>{n,a->when(n){"beginArrival"->beginAutomaticArrival(a[0] as RouteNavigatorDecisionToken)
     "preparationStage"->{stage(a[0] as String);Unit};"navigationStatus","showLastResult"->{log("GUIDE_STATUS","text" to a[0]);Unit}
     "unavailableLocationMessage"->"현재 위치를 확인해 주세요.";"interaction"->{output.speakInteraction(a[0] as String);Unit};else->null}}}),
   CompassAssemblyBindings(
    {port<CompassActivityAuthority>{n,_->when(n){"getForeground"->foreground;"getLifecycleReady"->true;"walkEpoch"->walk.epoch
     "gatewaySnapshot"->accountSnapshot();"getFeedbackGeneration"->feedbackGeneration;"currentCollectionOwner"->owner();"getNavigationAllowed"->navigationAdmitted()&&environmentAllowed();else->null}}},
    {object:RouteCompassSession.Tracker {
     override val available=true
     override fun listener(listener:(RouteCompassObservationEvent)->Unit){headingListener=listener}
     override fun start(){headingRegistered=true}
     override fun stop(){headingRegistered=false}
     override fun current(event:RouteCompassObservationEvent)=headingRegistered&&event.registrationGeneration==1
     override fun heading(nowMs:Long)=RouteCompassHeadingResult(headingObservation,headingReason)
    }},{output.currentActuatorOrNull()?.cancelNavigationSpeech()},{},
    {object:RouteCompassSession.Callbacks {
     override val viewReady=true
     override fun post(task:Runnable)=callbacks.post(task)
     override fun postDelayed(task:Runnable,delayMs:Long):Boolean{callbacks.postDelayed(task,delayMs);return true}
     override fun remove(task:Runnable)=callbacks.remove(task)
    }}),
   {port<ArchiveActivityFacts>{n,_->when(n){"gatewaySnapshot"->accountSnapshot();"lifecycleOrNull"->gateway.lifecycle
    "activityCurrent"->foreground;"deletionActivityLease"->deletionLease;"deletionCoordinator","deletionIntentToken"->error("Account deletion outside this replay");else->null}}},
   AcquisitionAssemblyBindings(
    {port<AcquisitionActivityFacts>{n,_->when(n){"getForeground"->foreground;"getNavigationAllowed"->navigationAdmitted();"getEnvironmentOutputsAllowed"->environmentAllowed()
     "getRuntimeActive"->walk.state==WalkSessionState.ACTIVE;"getLifecycle"->gateway.lifecycle;"getGateway"->accountSnapshot()
     "getRawActorId","getActorId"->actor;"sessionCurrent"->accountSnapshot().session===gateway.account
     "getResources"->WalkSessionDeviceResourceSnapshot(true,true,true);"getLocationPermissionReady"->permission
     "getSelectedDestination"->workflow.pendingUiDestination;"getGuidancePage"->workflow.nativeUiPage==NativeUiPage.GUIDANCE;"getStartWaitViewReady"->true
     "getStepCount"->steps;"getPermissionOwner"->permissionPolicy;"getDeletionOwner"->deletion;"getDeletionActivityLease"->deletionLease;else->null}}},
    {port<AcquisitionActivityEffects>{n,a->when(n){"requireActor","networkAllowed"->actor==gateway.account.actorId&&permission
     "sessionOrNull"->gateway.account.takeIf{accountSnapshot().session===it};"recordStage"->{stage(a[0] as String);Unit}
     "cancelNavigationSpeech"->{output.currentActuatorOrNull()?.cancelNavigationSpeech();Unit};"renderMain"->{workflow.renderMainUi();Unit};"beginInstalledRoute"->{@Suppress("UNCHECKED_CAST") (a[0] as ()->Unit).invoke();{}}
     "resetStartSpeechState"->{@Suppress("UNCHECKED_CAST") (a[0] as ()->Unit).invoke();Unit};"failureAdmission"->({navigationAdmitted()});else->null}}},
    {object:RouteAcquisitionController.Backend {
     override fun fetchRoute(session:GatewayFieldSession,request:WalkingRouteRequest):CancellableNetworkCall<WalkingRoute>{
      check(session===gateway.account);providerRequests+=request;log("PROVIDER_REQUEST","request_count" to providerRequests.size,"session_identity" to System.identityHashCode(session))
      return CancellableNetworkCall(executeBlock={
       log("PROVIDER_RESPONSE")
       providerResponse(request)
      })
     }
     override fun revalidate(session:GatewayFieldSession,actorId:String?,capacitySessionGeneration:Long)=GatewaySessionRevalidation(GatewaySessionRevalidationStatus.READY,"controlled-network")
    }},{AndroidNavigationRequestCoordinator()}, {DestinationSearchController(port(),port(),port(),AndroidNavigationRequestCoordinator(),Executor{workers+=it},{ui+=it},{null})},
    {Executor{workers+=it}},{callbacks}),
   NavigationAssemblyResources({presentation},{callbacks},{installedSpeech},{lane},{diagnostics}),
   port<NavigationGpsActorAuthority>{n,_->when(n){"getForeground"->foreground;"walkSnapshot"->walk;"gatewaySnapshot"->accountSnapshot();"profileOrNull"->officialProfile
    "getRawActorId","verifiedActorId"->actor;"getOnboardingInitialized"->true;else->null}},
   {now},{100_000L+now},{true},{ui+=it}) }

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
  var positioningBindingChecks=0
  val ui=ArrayDeque<()->Unit>();val timers=mutableListOf<Pair<Long,Runnable>>()
  val destination=DestinationSearchResult("selected-place","목적지",point(40.0,60.0),null,null,null,null)
  val destinationHandOff=mutableListOf<DestinationSearchResult>();var onDestinationSelected:((DestinationSearchResult)->Boolean)?=null
  val calibration=GuidanceStartCalibration()
  val permissionRecoveryIdentity: Any = Any()
  val locationGeneration get() = graph.positioning.value.callbackGeneration
  val routeGeneration get() = graph.acquisition.value.requestGeneration
  val nativeDestinationAdmission: NativeDestinationStartAdmission by lazy {
   NativeDestinationStartAdmission(workflow,
    object : NativeDestinationStartAdmission.Scope {
     override fun homeAvailable() = foreground && actor == gateway.account.actorId
     override fun runtimeActive() = walk.state == WalkSessionState.ACTIVE
     override fun epoch() = walk.epoch
     override fun gatewayOwner() = NativeDestinationStartAdmission.GatewayOwner(gatewayGeneration, gateway.account)
     override fun reporterId() = actor
     override fun permissionRecoveryIdentity() = permissionRecoveryIdentity
     override fun routeGeneration() = routeGeneration
  }, object : NativeDestinationStartAdmission.PositioningSource {
     override fun generation() = locationGeneration
     override fun binding() = NativeDestinationStartAdmission.Binding(
      identity=positioningCollectionBinding,actorId=positioningCollectionOwner.actorId,
      gatewaySessionIdentity=gateway.account,gatewayGeneration=gatewayGeneration,
      currentCoordinatorAndCollection={positioningBindingChecks++;
       positioningCoordinator === positioningCollectionBinding.coordinator &&
        positioningCoordinator.currentInputGeneration()==positioningCollectionBinding.inputGeneration &&
        locationCallbackCurrent && positioningCollectionOwner==positioningCollectionBinding.owner})
    })
  }
  private val positioningCoordinator get() = graph.positioning.value.coordinator
  private val positioningCollectionOwner get() = requireNotNull(graph.positioning.value.collectionOwner)
  private val positioningCollectionBinding get() = requireNotNull(graph.positioning.value.binding)
  val locationCallbackCurrent get() = graph.positioning.value.callback != null
  data class Prompt(val lease:GuidanceStartCalibration.PromptLease,val done:()->Unit,val failed:()->Unit)
  val prompts=mutableListOf<Prompt>();var calibrationTimer:Runnable?=null;var prepTimer:Runnable?=null
  val outputFixture=Class.forName("kr.co.hanium.dreamup.walksafe.voice.SpeechOutputControllerBehaviorTest\$Fixture").getDeclaredConstructor().apply{isAccessible=true}.newInstance()
  val output:SpeechOutputController=read(outputFixture,"controller");val outputClock:Any=read(outputFixture,"clock")
  lateinit var nativeEngine:Any
  val spoken get()=read<List<Any>>(nativeEngine,"spoken")
  private val synthesisProbe=ControlledSynthesisProbe(initialSynthesisReady)
  val synthesisProbeAvailable get()=synthesisProbe.state.available
  val synthesisProbeStarts get()=synthesisProbe.starts
  val synthesisProbeReplacements get()=synthesisProbe.replacements
  val synthesisProbeCloses get()=synthesisProbe.closes
  var metricDistanceAvailable=false
  private fun capabilityInput()=WalkSafeStartupCapabilityInput(
   true,true,true,true,true,true,synthesisProbeAvailable,metricDistanceAvailable,true,"replay-capability-r1")
  val decision get()=WalkSafeStartupCapabilityResolver.resolve(capabilityInput())
  private val voiceDataRecovery by lazy { VoiceDataSettingsRecoveryController(synthesisProbe,port(),
   object:VoiceSettingsEffects {
    override var offlineTtsOverride:Boolean?=null
    override fun refresh(){this@GuidanceFullJourneyReplayHarness.refresh()}
    override fun status(title:String,detail:String){log("SYNTHESIS_STATUS","title" to title,"detail" to detail)}
    override fun speak(message:String){output.speakInteraction(message)}
    override fun recoverPassedGate():Boolean=error("HOME probe must not manufacture a device-check receipt")
   },StartupSettingsNavigation(port<StartupSettingsNative<Unit>> {_,_->error("No settings launch in HOME replay")})) }
  private inner class ControlledSynthesisProbe(initialResult:Boolean?):VoiceDataProbePort {
   var state=OfflineKoreanTextToSpeechProbeState();private set
   private var attempt:Int?=null
   private var changed:(()->Unit)?=null
   var starts=0;private set
   var replacements=0;private set
   var closes=0;private set
   override val initialized=true
   override var started=false
   init { if(initialResult!=null)check(state.complete(state.begin(),initialResult)) }
   override fun snapshot()=capabilityInput()
   override fun unavailableCause()=state.unavailableCause
   override fun close(){closes++;state.close();log("SYNTHESIS_PROBE_CLOSE")}
   override fun replace(onChanged:()->Unit){replacements++;state=OfflineKoreanTextToSpeechProbeState();attempt=null;changed=onChanged;log("SYNTHESIS_PROBE_REPLACE")}
   override fun start(){check(started);check(attempt==null);starts++;attempt=state.begin();log("SYNTHESIS_PROBE_START")}
   override fun recheck()=error("HOME calibration does not recheck a running synthesis probe")
   fun complete(available:Boolean){
    check(state.complete(requireNotNull(attempt),available))
    log("SYNTHESIS_PROBE_COMPLETE","available" to available)
    requireNotNull(changed).invoke()
    drainUi()
   }
  }
  fun completeSynthesisProbe(available:Boolean=true)=synthesisProbe.complete(available)
  private val officialProfile=ApprovedOfficialEnvironmentProfile("replay-official","replay-camera",approvedGpsAccuracyM,10_000L,2)
  private val cameraProfile=ApprovedCameraFrameQualityProfile("replay-camera",0.2,0.8,60.0,5.0,85.0,10_000L)
  private val mountingProfile=ApprovedPhoneMountingProfile("replay-mounting","replay-camera",5_000L,2,30_000L)
  var deferPreflightCameraRelease=false
  private val pendingPreflightCameraReleases=ArrayDeque<()->Unit>()
  val pendingPreflightCameraReleaseCount get()=pendingPreflightCameraReleases.size
  fun completePreflightCameraRelease(){pendingPreflightCameraReleases.removeFirst().invoke();drainUi()}
  // The real check/preflight owners reset old observations on retry. Camera resource close and
  // analyzed-frame input remain controlled platform leaves; close can be deliberately delayed.
  private val preflight:OfficialEnvironmentPreflightController by lazy { OfficialEnvironmentPreflightController(evidenceStore,
   port<OfficialEnvironmentPreflightFactsReader>{n,a->when(n){
    "lifecycle"->OfficialEnvironmentPreflightLifecycleFacts(walk.epoch,walk.state,foreground)
    "foreground","uiAlive"->foreground
    "cameraProfilePresent","cameraEnabled","locationServiceEnabled"->true
    "locationPermission"->permission;"profile"->officialProfile
    "gpsOwner"->OfficialEnvironmentGpsOwner(gatewayGeneration,gateway.account)
    "nativeCheckRequested"->workflow.nativePhoneMountingCheckRequest!=null
    "nativeGpsCheckCurrent"->workflow.nativePhoneMountingCheckRequest?.let{requestCurrent(it)&&it.checkRequest.epoch==a[0]}==true
    "walkRuntimeActive"->walk.state==WalkSessionState.ACTIVE;else->null}},
   {port<OfficialEnvironmentCameraSource>{n,a->when(n){
    "start"->{log("PREFLIGHT_CAMERA_REQUEST","generation" to a[1]);Unit}
    "detachOwnedResources"->object:OfficialEnvironmentCameraResources {
     override val hasAnalysis=true;override val hasCamera=true
     override fun clearAnalyzer()=Unit;override fun unbindOwned()=Unit
    }
    "awaitAnalyzerBarrier"->{(a[0] as Runnable).run();Unit}
    "awaitClosed"->{
     @Suppress("UNCHECKED_CAST") val completion=a[1] as (Boolean)->Unit
     val closed={log("PREFLIGHT_CAMERA_RELEASED");completion(true)}
     log("PREFLIGHT_CAMERA_CLOSE_REQUEST")
     if(deferPreflightCameraRelease)pendingPreflightCameraReleases+=closed else closed()
     Unit}
    else->null}}},
   {port<OfficialEnvironmentGpsSource>{_,_->error("A native check must retain its continuous GPS owner")}},
   port<OfficialEnvironmentPreflightEffects>{n,_->when(n){"refreshEnvironmentAndReadiness"->{refresh();Unit};else->null}},
   object:OfficialEnvironmentPreflightQueue {
    override fun uiReady()=true
    override fun removeUi(action:Runnable)=callbacks.remove(action)
    override fun postUi(delayMs:Long,action:Runnable)=callbacks.postDelayed(action,delayMs)
    override fun removeRelease(action:Runnable)=callbacks.remove(action)
    override fun postRelease(delayMs:Long,action:Runnable)=callbacks.postDelayed(action,delayMs)
    override fun runOnUiThread(action:Runnable)=callbacks.post(action)
   },CameraPreflightStabilityGate(3,2),{now},OfficialEnvironmentPreflightController.Config(100L,500L,60_000L,120_000L,2_000L)) }
  private val environmentCheck:OfficialEnvironmentController by lazy { OfficialEnvironmentController(evidenceStore,preflight,officialRuntime,
   port<OfficialEnvironmentCheckFactsReader>{n,a->when(n){
    "foreground"->foreground;"lifecycleInitialized","nativeEducationCurrent","environmentProfilesPresent","locationEnabled","cameraEnabled","locationServiceEnabled"->true
    "lifecycle"->OfficialEnvironmentLifecycleFacts(walk.epoch,walk.state)
    "nativeCheckRequested"->workflow.nativePhoneMountingCheckRequest!=null
    "nativeGpsCheckCurrent"->workflow.nativePhoneMountingCheckRequest?.let{requestCurrent(it)&&it.checkRequest.epoch==a[0]}==true
    "locationPermission"->permission
    "continuousGpsEvidence"->graph.gps.value.currentContinuousGpsEvidence(a[0] as WalkRuntimeEpoch,a[1] as Long)
    "currentAssessment"->officialAssessment(a[0] as WalkRuntimeEpoch);"profile"->officialProfile;else->null}},
   port<OfficialEnvironmentCheckEffects>{n,_->when(n){
    "requireFirstRunOnboardingComplete"->firstRunComplete()
    "resetPrewalkCameraEvidenceForNewAttempt"->{
     cameraObservation=null;evidenceStore.resetRuntimeRawCameraAssessment();preflight.resetCameraObservations();evidenceStore.clearCameraEvidence()
     log("PREFLIGHT_CAMERA_EVIDENCE_RESET");Unit}
    "requestContinuousGpsForNativeCheck"->{graph.positioning.value.startLocationUpdatesIfAllowed();log("PREFLIGHT_CONTINUOUS_GPS_REQUEST");Unit}
    "refreshEnvironmentAndReadiness"->{refresh();Unit};else->null}},
   {now},{false},false,"사용범위 안내") }
  private fun cameraAssessment(epoch:WalkRuntimeEpoch,at:Long)=CameraFrameQualityPolicy.assess(epoch,now,
   cameraObservation?.let{it.copy(epoch=epoch,observedAtElapsedRealtimeMs=at)},cameraProfile)
  private fun officialAssessment(epoch:WalkRuntimeEpoch):OfficialEnvironmentAssessment {
   val gps=graph.gps.value.currentContinuousGpsEvidence(epoch,now)?:evidenceStore.gpsEvidence
   val camera=if(walk.state==WalkSessionState.ACTIVE)evidenceStore.cameraQualityGate.current(now)?:evidenceStore.cameraEvidence
    else cameraAssessment(epoch,cameraObservation?.observedAtElapsedRealtimeMs?:now).asMeasuredEnvironmentEvidence()
   return OfficialEnvironmentPolicy.assess(epoch,now,gps,camera,
    officialConfirmation?.takeIf{it.epoch==epoch},officialProfile,usageLimitsAcknowledged=true).copy(enabledMeasuredFactors=setOf(OfficialEnvironmentFactor.GPS_QUALITY,OfficialEnvironmentFactor.CAMERA_QUALITY))
  }
  private fun mountingAssessment(epoch:WalkRuntimeEpoch):PhoneMountingAssessment = PhoneMountingPolicy.assess(
   PhoneMountingAssessmentPhase.PREFLIGHT,epoch,now,mountingConfirmation?.takeIf{it.epoch==epoch},
   cameraAssessment(epoch,cameraObservation?.observedAtElapsedRealtimeMs?:now),PhoneMountingPolicy.initialState(epoch,mountingProfile),
   checkRequest=workflow.nativePhoneMountingCheckRequest?.checkRequest?.takeIf{it.epoch==epoch})
  private val education by lazy { PriorityUserOnboardingPolicy().apply {
    selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS);reviewSafetyEducation(true);reviewPracticeNecessity(true)
    acceptEducationConsent();acknowledgeUsageConditions()
    finishAppUsageEducationPlayback(requireNotNull(beginAppUsageEducationPlayback()),completed=true)
    confirmSafePracticePlace()
    PriorityUserPractice.entries.forEach{practice->val token=requireNotNull(beginPractice(practice))
     PriorityUserPracticeDeliverySignal.entries.forEach{recordPracticeDelivery(token,it)}
     if(practice==PriorityUserPractice.HAZARD_ALERT)confirmHazardResponse(token)}
    acceptAppUsageEducation()
   } }
  private val officialRuntime by lazy { OfficialEnvironmentRuntimeController(evidenceStore,
   object:OfficialEnvironmentRuntimeFactsReader {
    override fun lifecycle()=OfficialEnvironmentLifecycleFacts(walk.epoch,walk.state)
    override fun currentRuntimeEpoch()=gateway.lifecycle.currentRuntimeEpochOrNull()
    override fun isRuntimeEpochCurrent(epoch:WalkRuntimeEpoch)=gateway.lifecycle.isRuntimeEpochCurrent(epoch)
    override fun profile()=officialProfile
    override fun continuousSourcePresent()=graph.currentContinuousGpsSourceOrNull()!=null
    override fun continuousGpsEvidence(epoch:WalkRuntimeEpoch,nowMs:Long)=graph.gps.value.currentContinuousGpsEvidence(epoch,nowMs)
    override fun latestMountingCameraAssessment()=cameraAssessment(walk.epoch,cameraObservation?.observedAtElapsedRealtimeMs?:now)
    override fun currentAssessment(epoch:WalkRuntimeEpoch,nowMs:Long)=officialAssessment(epoch)
    override fun locationEnabled()=true
    override fun cameraEnabled()=true
    override fun cameraHandoffEpoch()=walk.epoch
   },object:OfficialEnvironmentRuntimeEffects {
    override fun reassessMountingWithoutExplicitRetry()=Unit
    override fun invalidateReportOutput(reason:String){log("ENVIRONMENT_REPORT_INVALIDATED","reason" to reason)}
    override fun restrictGpsNavigationAndCancelFeedback(){
     graph.guidance.value.restrictGpsNavigation {
      feedbackGeneration++
      output.closeActuator()
      log("ENVIRONMENT_GPS_RESTRICTED")
     }
    }
    override fun resumeGpsNavigation(){
     graph.guidance.value.resumeGpsNavigation {
      graph.positioning.value.startLocationUpdatesIfAllowed()
      graph.compass.value.ensureEarthOrientationForLocation()
      output.ensureFeedbackActuator()
      log("ENVIRONMENT_GPS_RESUMED")
     }
    }
    override fun cancelPendingFeedbackDeliveries()=Unit
    override fun cancelVoiceAndFeedbackActuator(){feedbackGeneration++;output.closeActuator()}
    override fun showStatus(title:String,detail:String){log("ENVIRONMENT_STATUS","title" to title,"detail" to detail)}
    override fun speakStatusExplanation(message:String){output.speakCommandResponse(message,statusExplanation=true)}
    override fun speakRecheckExplanation(message:String,current:()->Boolean,delivered:()->Unit){
     log("ENVIRONMENT_RECHECK_NOTICE","text" to message)
     if(graph.run.active&&graph.run.navigator.hasRoute()) {
      graph.guidance.value.offerEnvironmentRecheckNotice(message,current,delivered)
     } else {
      output.speakCommandResponse(message,statusExplanation=true,additionalCurrent=current,onCompleted=delivered)
     }
    }
    override fun failureSpeech(assessment:OfficialEnvironmentAssessment,factors:Set<OfficialEnvironmentFactor>?)="위치와 카메라 상태를 실제로 확인해 주세요."
    override fun locationRecoveryActionMessage()="위치가 확인되면 안내를 자동으로 이어갑니다."
    override fun enterSafetyStop(reason:String){log("ENVIRONMENT_SAFETY_STOP","reason" to reason);transition.transitionWalkSession(WalkSessionEvent.SafetyStopRequested)}
   },{now},{delay,action->callbacks.postDelayed(Runnable(action),delay)},{false}) }
  private val readinessBlocks by lazy { WalkReadinessBlockController(object:WalkReadinessBlockSources {
   override fun onboardingComplete()=firstRunComplete()
   override fun developmentBypassEnabled()=false
   override fun observedPermissions()=ObservedPermissionSnapshot(permission,permission,permission,permission)
   override fun voiceFeatureEnabled()=true
   override fun offlineTtsAllowed(decision:WalkSafeStartupCapabilityDecision)=decision.allowsRequirement(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS)
   override fun priorityOnboarding(decision:WalkSafeStartupCapabilityDecision)=education.evaluate(PriorityUserSupportEnvironment(false,false,false,true,true))
   override fun quickStartEnabled()=false
   override fun reporterUserId()=actor
   override fun gatewayReadyForCurrentActor()=accountSnapshot().session===gateway.account&&actor==gateway.account.actorId&&gateway.actorCurrent
   override fun officialEnvironmentBlockReason()=if(readinessStatus(WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT)==WalkSessionReadinessStatus.READY)null else "실제 환경 측정 결과를 기다리고 있습니다."
   override fun phoneMountingBlockReason()=mountingAssessment(walk.epoch).takeIf{readinessStatus(WalkSessionReadinessRequirement.PHONE_MOUNTING)!=WalkSessionReadinessStatus.READY}?.accessibleReasonKo
   override fun walkState()=walk.state
   override fun missingRequiredPermissions(action:WalkSessionAction)=if(permission)emptyList() else listOf("controlled_location_permission")
   override fun permissionGuidanceLabels(missing:List<String>)=missing.joinToString()
   override fun runningDetectorBlocksEntry()=false
   override fun cameraEnabled()=detectorAvailable
   override fun detectorLoadAttempted()=true
   override fun detectorAvailable()=this@GuidanceFullJourneyReplayHarness.detectorAvailable
   override fun locationFeatureEnabled()=permission
  },"실제 한국어 음성 합성 결과를 확인해 주세요.") }
  private val readinessCoordinator:WalkReadinessCoordinator by lazy {
   val official=object:OfficialReadinessSource {
    override fun assessment(epoch:WalkRuntimeEpoch)=officialAssessment(epoch)
    override fun readiness(epoch:WalkRuntimeEpoch,assessment:OfficialEnvironmentAssessment?):Pair<WalkSessionReadinessStatus,String> {
     officialRuntime.runtimeReadiness(epoch)?.let{return it}
     val current=assessment?:officialAssessment(epoch)
     // Same assessment mapping as OfficialEnvironmentController.readiness; a pending observation
     // is not an unavailable environment and cannot fabricate a pre-walk safety stop.
     return when(current.support) {
      OfficialEnvironmentSupport.SUPPORTED -> WalkSessionReadinessStatus.READY to ""
      OfficialEnvironmentSupport.LIMITED -> WalkSessionReadinessStatus.PENDING to "official_environment:unknown"
      OfficialEnvironmentSupport.UNSUPPORTED -> WalkSessionReadinessStatus.UNAVAILABLE to "official_environment:unsupported"
     }
    }
   }
   val mounting=object:MountingReadinessSource {
    override fun assessment(epoch:WalkRuntimeEpoch)=mountingAssessment(epoch)
    override fun readiness(epoch:WalkRuntimeEpoch,assessment:PhoneMountingAssessment?):Pair<WalkSessionReadinessStatus,String> {
     val current=assessment?:mountingAssessment(epoch)
     // Same preflight mapping as PhoneMountingSessionController.phoneMountingReadiness.
     // Camera at the request's exact timestamp remains CORRECTION_REQUIRED/PENDING.
     return when(current.status) {
      PhoneMountingStatus.SUITABLE -> WalkSessionReadinessStatus.READY to ""
      PhoneMountingStatus.CORRECTION_REQUIRED -> WalkSessionReadinessStatus.PENDING to "phone_mounting:${current.reason.name.lowercase(Locale.US)}"
      PhoneMountingStatus.UNUSABLE -> WalkSessionReadinessStatus.UNAVAILABLE to "phone_mounting:${current.reason.name.lowercase(Locale.US)}"
     }
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
   val producers=WalkReadinessProducers(FirstRunReadinessProducer({firstRunComplete()},{accountSnapshot().restoredFirstRunSnapshot?.stage ?: FirstRunOnboardingStage.VERIFIED_LOGIN}),
    PriorityWalkReadinessProducer({education},{PriorityUserSupportEnvironment(false,false,false,true,true)},
     {gateway.lifecycle.isRuntimeEpochCurrent(walk.epoch)},{false},{actor}),
    PermissionReadinessProducer({ObservedPermissionSnapshot(permission,permission,permission,permission)},{permission}),feature,
    EndpointReadinessProducer(true,"https://gateway.example.test"),GatewayReadinessProducer{actor==gateway.account.actorId&&gateway.actorCurrent},official,mounting)
   WalkReadinessCoordinator({gateway.lifecycle},capability,resource,official,mounting,producers,{false},{now},35)
  }
  fun capture(action:WalkSessionAction,checkEntryPermissions:Boolean=true):WalkSessionReadinessSnapshot = readinessCoordinator.capture(decision,action,checkEntryPermissions=checkEntryPermissions)
  val confirmationFacts=port<StartupConfirmationFacts>{n,a->when(n){
   "getDecision"->decision;"getForeground"->foreground;"getActorId","currentReporter"->actor;"getFeedbackGeneration"->1
    "feedbackCurrent"->foreground&&actor==gateway.account.actorId;"onboardingComplete"->firstRunComplete();else->null}}
  val confirm=StartupConfirmationInteractionController({gateway.lifecycle},confirmationFacts,
   port<StartupConfirmationEffects>{n,a->when(n){"refresh"->{refresh();Unit};"onUi"->{@Suppress("UNCHECKED_CAST") ui+=(a[0] as ()->Unit);Unit};else->null}},StartupDecisionStorage{_,_->})
  val confirmation=object:StartupCapabilityConfirmation {
   override var confirmed:WalkSafeStartupCapabilityDecision? get()=confirm.confirmedDecision();set(v){confirm.writeConfirmedDecision(v)}
   override var pending:Boolean get()=confirm.confirmationPending();set(v){confirm.writeConfirmationPending(v)}
   override var retryRequiresUserAction:Boolean get()=confirm.retryRequired();set(v){confirm.writeRetryRequired(v)}
   override fun isConfirmed()=confirm.isStartupCapabilityConfirmed()
  }
  val readiness=object:WalkResumeReadiness {
   override fun decision()=this@GuidanceFullJourneyReplayHarness.decision
   override fun blockReason(decision:WalkSafeStartupCapabilityDecision):String?=readinessBlocks.walkSessionReadinessBlockReason(decision)
   override fun capture(decision:WalkSafeStartupCapabilityDecision,action:WalkSessionAction,recordResourceDiagnostics:Boolean)=this@GuidanceFullJourneyReplayHarness.capture(action)
   override fun isConfirmed()=confirm.isStartupCapabilityConfirmed()
  }
  val gatewayController:GatewayWalkSessionController by lazy {
   GatewayWalkSessionController(gateway.authority,gateway,object:GatewayWalkRuntime by gateway {
    override fun currentStartReadinessOrNull(token:WalkSessionConfirmationToken)=capture(WalkSessionAction.START_WALK).takeIf{
     permission&&actor==gateway.account.actorId&&walk.confirmationToken==token&&it.isReady&&it.epoch==token.epoch&&
      it.plan.action==token.readiness.plan.action&&it.plan.mode==token.readiness.plan.mode}
    override fun startRequested(token:WalkSessionConfirmationToken,readiness:WalkSessionReadinessSnapshot){
     log("GATEWAY_ACK_START_REQUESTED")
     transition.transitionWalkSession(WalkSessionEvent.StartRequested(token,readiness))
    }
    override fun activate():Boolean {activations++;log("GATEWAY_ACK_RUNTIME_ACTIVATE");return activation.activateWalkSessionRuntime()}
    override fun isForeground()=foreground
    override fun refreshReadinessUi(){refresh()}
   },gateway,object:GatewayWalkOutput by gateway {
    override fun makeStartRetryAvailable(detail:String,spoken:String?){
     gateway.makeStartRetryAvailable(detail,spoken)
     confirm.writeConfirmationPending(false);confirm.writeConfirmedDecision(null);confirm.writeRetryRequired(true)
     refresh();log("GATEWAY_START_RETRY_AVAILABLE","detail" to detail)
     spoken?.let{output.speakInteraction(it)}
    }
   },object:GatewayWalkScheduler by gateway {
    override fun nowMs()=now
    override fun removeCallbacks(runnable:Runnable)=callbacks.remove(runnable)
    override fun post(runnable:Runnable)=callbacks.post(runnable)
    override fun postDelayed(runnable:Runnable,delayMs:Long)=callbacks.postDelayed(runnable,delayMs)
    override fun postDelayed(action:()->Unit,delayMs:Long)=callbacks.postDelayed(Runnable(action),delayMs)
   },30_000L,"restart",false)
  }
  val admission:WalkSessionAdmissionController by lazy {WalkSessionAdmissionController(
   object:WalkSessionRuntimeAccess {override fun available()=true;override fun snapshot()=walk
    override fun isRuntimeEpochCurrent(epoch:WalkRuntimeEpoch)=gateway.lifecycle.isRuntimeEpochCurrent(epoch)
    override fun transition(event:WalkSessionEvent)=transition.transitionWalkSession(event)},gateway.authority,gatewayController,resume.controller,readiness,
   port<WalkAdmissionState>{n,_->when(n){"isForeground"->foreground;"startupConfirmationPending"->confirm.confirmationPending();"startupRetryRequiresUserAction"->confirm.retryRequired();else->null}},
   port<WalkAdmissionEffects>{n,_->when(n){"refreshReadinessUi"->{refresh();Unit};"persistInterruptionMarker"->true;else->null}},setOf(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS),{now})}
  fun startBlockReason():String?=readinessBlocks.walkSessionReadinessBlockReason(decision)
  fun navigationAdmitted()=walk.state==WalkSessionState.ACTIVE&&foreground&&permission&&actor==gateway.account.actorId&&confirm.isStartupCapabilityConfirmed()&&capture(WalkSessionAction.ROUTE_REQUEST).isReadyAt(now)
  val activation:WalkRuntimeActivationController by lazy {WalkRuntimeActivationController(
   port<WalkActivationAdmission>{n,a->when(n){"runtimeActive"->walk.state==WalkSessionState.ACTIVE;"runtimeEpochOrNull"->gateway.lifecycle.currentRuntimeEpochOrNull();"snapshot"->walk
    "isRuntimeEpochCurrent"->gateway.lifecycle.isRuntimeEpochCurrent(a[0] as WalkRuntimeEpoch);"isForeground"->foreground;"navigationOutputsAllowed"->navigationAdmitted()&&environmentAllowed()
    "permissionBlocksAutomaticStart"->!permission;"nowMs"->now;"beginSafetyEpoch"->WalkRuntimeSafetyDecision(WalkRuntimeSafetyDisposition.ALLOW_SAFETY_OUTPUT,a[0] as WalkRuntimeEpoch);else->null}},readiness,
   port<WalkActivationEffects>{n,_->if(n=="persistInterruptionMarker")true else null},
   port<WalkActivationServices>{n,_->when(n){"observeResourceSafety"->foreground;"activateOfficialEnvironment"->officialRuntime.activate()
    "activatePhoneMounting"->readinessStatus(WalkSessionReadinessRequirement.PHONE_MOUNTING)==WalkSessionReadinessStatus.READY
    "startNavigation"->{navigationStarts++;log("ACTIVATION_NAVIGATION");graph.positioning.value.startLocationUpdatesIfAllowed();Unit};"refreshReadinessUi"->{refresh();Unit};else->null}},
   port<WalkActivationCamera>{n,a->when(n){"stopEnvironmentPreflight"->{@Suppress("UNCHECKED_CAST")preflight.stopCamera(a[0] as Boolean,a[1] as ()->Unit);Unit};"postAfterRelease"->{@Suppress("UNCHECKED_CAST")ui+=(a[0] as ()->Unit);Unit};else->null}},gatewayController,resume.controller)}
  val workflow:NativeWorkflowController by lazy {NativeWorkflowController(
   port<NativeWorkflowController.EntryQueries>{n,_->when(n){"shouldShowNativeHome","nativeHomeFeatureContextAvailable"->foreground&&actor==gateway.account.actorId
    "isWalkSessionRuntimeActive"->walk.state==WalkSessionState.ACTIVE;"nativeFeatureAvailable"->navigationAdmitted();else->null}},
   port<NativeWorkflowController.PageUi>{n,_->if(n=="readyForNativeRendering")true else null},
   port<NativeWorkflowController.PreparationActions>{n,_->when(n){"continueNativeFeaturePreparation"->{native.continueFeaturePreparation();Unit};else->null}},
   port<NativeWorkflowController.DestinationTransactions>{n,a->when(n){
    "captureNativeDestinationStartOwner"->nativeDestinationAdmission.capture(a[0] as DestinationSearchResult).also{log("NATIVE_DESTINATION_ADMISSION","captured" to (it!=null),"ps_binding_identity" to graph.positioning.value.binding?.let(System::identityHashCode))}
    "currentDestinationSearchAllowsWork"->foreground&&permission&&actor==gateway.account.actorId
    "postNativeDestinationStart"->{val dest=a[0] as DestinationSearchResult;workflow.postNativeDestinationStart(dest,{ui+={it.run()}},{navigationAdmitted()},{entry.start()});Unit}
    else->null}})}
  val preparation:GuidancePreparationController by lazy {GuidancePreparationController(workflow,
   port<GuidancePreparationController.Scope>{n,_->when(n){"reporterId"->actor;"gatewayGeneration"->gatewayGeneration;"epoch"->walk.epoch;"walkState"->walk.state;"foreground"->foreground;else->null}},port(),
   object:GuidancePreparationController.Callbacks {override fun postDelayed(action:Runnable,delayMs:Long){timers+=now+delayMs to action};override fun remove(action:Runnable){timers.removeAll{it.second===action}}},{now})}
  val native:NativePrewalkController by lazy {NativePrewalkController(workflow,
   port<NativePrewalkController.Scope>{n,_->when(n){"homeAvailable"->foreground&&actor==gateway.account.actorId;"foreground"->foreground;"reporterId"->actor;"gatewayGeneration"->gatewayGeneration;"walk"->walk;"runtimeActive"->walk.state==WalkSessionState.ACTIVE;else->null}},
   port<NativePrewalkController.Checks>{n,_->when(n){"firstRunComplete","environmentProfilesPresent","officialProfilePresent","mountingProfilePresent"->true
    "locationAccessUnavailable"->!permission;"environmentReadiness"->readinessStatus(WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT)
    "mountingReadiness"->readinessStatus(WalkSessionReadinessRequirement.PHONE_MOUNTING);"startupDecision"->decision;"readinessBlockReason"->readiness.blockReason(decision)
    "confirmationPending"->confirm.confirmationPending();"startupConfirmed"->confirm.isStartupCapabilityConfirmed()
    "startupRetryRequired"->confirm.retryRequired();"failureSpeech"->"위치 권한과 현재 점검 상태를 확인해 주세요.";else->null}},
   object:NativePrewalkController.Speech {override fun commandPending()=false;override fun submit(message:String,current:()->Boolean,completed:()->Unit,failed:()->Unit,cancelled:()->Unit,retired:()->Unit)=
    output.speakCommandResponse(message,statusExplanation=true,additionalCurrent=current,onCompleted=completed,onFailed=failed,onCancelled=cancelled,onRetired=retired)},
   port<NativePrewalkController.Actions>{n,a->when(n){
    "beginCalibration"->{val request=a[0] as NativePhoneMountingCheckRequest
     if(requestCurrent(request))voiceDataRecovery.ensurePendingKoreanSpeechProbeStarted()
     advanceCalibration(request);Unit};"calibrationReady"->advanceCalibration(a[0] as NativePhoneMountingCheckRequest)
    "cancelCalibration","cancelCalibrationForLocationAccess"->{calibration.cancel();calibrationTimer?.let{r->timers.removeAll{it.second===r}};calibrationTimer=null;Unit}
    "confirmEnvironment"->{environmentCheck.confirmConditions();Unit}
    "retryPreparation"->{val r=a[0] as NativePhoneMountingCheckRequest;if(prepTimer==null){val t=Runnable{prepTimer=null;if(requestCurrent(r)){
     if(confirm.retryRequired()||preflight.phase in setOf(OfficialEnvironmentPreflightPhase.FAILED,OfficialEnvironmentPreflightPhase.TIMED_OUT)){
      confirm.writeRetryRequired(false);environmentCheck.confirmConditions()
     }
     native.continueIfReady()
    }};prepTimer=t;timers+=now+2_000 to t};Unit}
    "requestStartConfirmation"->{@Suppress("UNCHECKED_CAST") val current=a[0] as ()->Boolean; @Suppress("UNCHECKED_CAST") val accept=a[1] as ()->Unit; if(current())accept();Unit}
    "postStartup"->{@Suppress("UNCHECKED_CAST")ui+=(a[0] as ()->Unit);Unit};"completeStartupConfirmation"->{confirm.completeStartupCapabilityConfirmation(a[0] as WalkSafeStartupCapabilityDecision);Unit}
    "renderMain"->{workflow.renderMainUi();Unit};"clearStartupRetryRequirement"->{confirm.writeRetryRequired(false);Unit}
    "cancelPendingFeatureEntry"->{workflow.pendingNativeUiPage=null;Unit};else->null}},
   object:NativePrewalkController.Diagnostics {override fun enabled()=true;override fun capture(s:String,e:WalkRuntimeEpoch,a:String?,c:Boolean):String {if(stage!=s)log("PREWALK_GATE","stage" to s,"confirmation_posted" to c);stage=s;return s};override fun record(state:String){};override fun directStart(state:WalkSessionState){log("START_CONFIRMATION_REQUESTED")}},{now})}
  val entry:NativeGuidanceEntryController by lazy {NativeGuidanceEntryController(workflow,native,preparation,
   port<NativeGuidanceEntryController.Scope>{n,_->when(n){"runtimeActive"->walk.state==WalkSessionState.ACTIVE;else->null}},
   port<NativeGuidanceEntryController.Actions>{n,a->when(n){"renderMain"->{workflow.renderMainUi();Unit};"handOffDestinationStart"->{val d=a[0] as DestinationSearchResult
    workflow.handOffNativeDestinationStart(d,{}, {destinationHandOff+=it;graph.acquisition.value.onDestinationSelected(it,true)},{workflow.renderMainUi()},{navigationAdmitted()});Unit};else->null}})}
  val evidence=StartupCapabilityReadinessEvidence({decision},{"controlled-platform-v1"},{now})
  val startup:StartupCapabilityCoordinator by lazy {StartupCapabilityCoordinator(port<StartupCapabilityEvaluation>{n,_->when(n){"probeDecision"->decision;else->null}},
   {evidence},{gateway.lifecycle},confirmation,port<StartupCapabilityUi>{n,_->when(n){"ready"->true
    "priority"->PriorityUserOnboardingDecision(PriorityUserSupportPriority.EQUAL_BLIND_AND_LOW_VISION,null,null,emptyList(),false)
    "officialReadiness"->readinessStatus(WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT)
    "mountingReadiness"->readinessStatus(WalkSessionReadinessRequirement.PHONE_MOUNTING);"updatePrewalk"->{native.continueIfReady();Unit};"advanceAfterCheck"->{admission.maybeAdvanceWalkSessionAfterCapabilityCheck();Unit};else->null}},port<ActiveReadinessPort>{n,a->when(n){"capture"->capture(WalkSessionAction.START_WALK,checkEntryPermissions=false);"transition"->transition.transitionWalkSession(a[0] as WalkSessionEvent);else->null}},"voice unavailable")}
  init {gateway.lifecycle.handle(WalkSessionEvent.EnteredForeground);put(outputClock,"now",gateway.now)
   GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
   val operation=requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
   check(GatewaySessionProcessCoordinator.publishVerified(operation,gateway.account,completedOnboarding()))
   gatewayGeneration=GatewaySessionProcessCoordinator.snapshot().generation
   put(graph.run.navigator,"nowElapsedRealtimeMs",{now}) // Android clock leaf, no state publication.
   installOutput()

   val old:CommandSpeechContext=read(outputFixture,"context");put(outputFixture,"context",old.copy(actorId=actor,gatewayGeneration=gatewayGeneration,walkEpoch=walk.epoch,page=NativeUiPage.GUIDANCE,visibleResponse=false,voiceInteractionPage=false))
   output.ensureFeedbackActuator();nativeEngine=runtime
   graph.positioning.value.startLocationUpdatesIfAllowed()
   graph.compass.value.ensureEarthOrientationForLocation()
   retainedBinding=requireNotNull(graph.positioning.value.binding)
   log("HOME_READY", "binding" to System.identityHashCode(retainedBinding))
   if(initialEngineReady)engineReady()
  }
  fun button(){log("START_BUTTON");entry.start();refresh()}
  private fun firstRunComplete()=accountSnapshot().session===gateway.account &&
   accountSnapshot().restoredFirstRunSnapshot?.let{it.mayEnterWalk&&it.verifiedActorBinding?.value==actor}==true
  private fun completedOnboarding():FirstRunOnboardingSnapshot {
   fun receipt(index:Int)=FirstRunReceiptHash.fromSha256Hex(index.toString().repeat(64))
   val storedEvidence=listOf(
    FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(gateway.account.actorId),receipt(1)),
    FirstRunOnboardingEvidence.PurposeAndSafety(receipt(2)),FirstRunOnboardingEvidence.Fp004Training(receipt(3)))
   val restored=FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(1L,storedEvidence,
    FirstRunOnboardingEvidenceVerifier{token,value->value in storedEvidence&&token.stage==value.stage})
   check(restored.fullyRestored)
   val permissionReceipt=FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(restored.snapshot,
    restored.snapshot.epoch,restored.snapshot.revision)
   check(permissionReceipt.accepted)
   return FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(permissionReceipt.current,
    permissionReceipt.current.epoch,permissionReceipt.current.revision).current.also{check(it.mayEnterWalk)}
  }
  fun refresh(){check(++refreshDepth<30){"actual synchronous refresh recursion"};try{startup.refreshStartupCapabilityUi()}finally{refreshDepth--}}
  fun engineReady(){runtime.ready();advance(0)}
  /** Controlled observations only, never an actual GPS/camera/mounting PASS claim. */
  fun readinessStatus(requirement:WalkSessionReadinessRequirement)=capture(WalkSessionAction.START_WALK).observations.getValue(requirement).status
  fun observeCameraFrame(brightness:Double?=0.6,occlusion:Double?=0.0,angularShake:Double?=0.0,
   mountPitch:Double?=45.0,nearBlackFraction:Double?=null){
   cameraObservation=CameraFrameQualityObservation(walk.epoch,now,true,brightness,occlusion,angularShake,mountPitch,nearBlackFraction)
   val measured=cameraAssessment(walk.epoch,now)
   if(walk.state==WalkSessionState.ACTIVE)evidenceStore.observeRuntimeRawCameraAssessment(measured,now)
   evidenceStore.commitCameraEvidence(measured.asMeasuredEnvironmentEvidence())
   readinessInputs+="camera_frame";log("CAMERA_INPUT","original_at_ms" to now,"brightness" to brightness,
    "occlusion" to occlusion,"angular_shake" to angularShake,"mount_pitch" to mountPitch,"near_black" to nearBlackFraction)
   if(walk.state==WalkSessionState.ACTIVE)officialRuntime.applyCurrentAssessment()
   refresh()
  }
  fun confirmOfficialConditions(){officialConfirmation=OfficialEnvironmentUserConfirmation(walk.epoch,
   EnvironmentEvidenceStatus.PASS,EnvironmentEvidenceStatus.PASS,EnvironmentEvidenceStatus.PASS,EnvironmentEvidenceStatus.PASS,
   EnvironmentEvidenceStatus.PASS,EnvironmentEvidenceStatus.PASS,EnvironmentEvidenceStatus.PASS);readinessInputs+="official_conditions";log("ENVIRONMENT_USER_CONFIRMATION");refresh()}
  fun confirmPhoneMounting(){mountingConfirmation=PhoneMountingUserConfirmation(walk.epoch,(now-1L).coerceAtLeast(0L),PhoneMountingMethod.CHEST_FORWARD,false);readinessInputs+="phone_mounting";log("MOUNTING_USER_CONFIRMATION","original_at_ms" to mountingConfirmation?.confirmedAtElapsedRealtimeMs);refresh()}
  fun unloadDetector(){detectorAvailable=false}
  fun loadDetector(){detectorAvailable=true;readinessInputs+="detector";log("MODEL_LOADED");refresh()}
  fun requestCurrent(r:NativePhoneMountingCheckRequest)=workflow.nativePhoneMountingCheckRequest===r&&foreground&&walk.isForeground&&walk.state in setOf(WalkSessionState.READY,WalkSessionState.PAUSED)&&walk.epoch==r.checkRequest.epoch&&actor==r.actorId&&r.gatewayGeneration==gatewayGeneration&&workflow.pendingUiDestination==r.destination
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
  fun advance(delta:Long){val end=now+delta;var count=0;while(true){val next=timers.filter{it.first<=end}.minByOrNull{it.first}?:break;timers.remove(next);now=maxOf(now,next.first);next.second.run();drainUi();check(++count<10_000){"timer spin"}};now=end}
  fun drainUi(){var count=0;while(ui.isNotEmpty()){ui.removeFirst()();check(++count<100){"ui spin"}}}
  fun promptId():String=read(spoken.last{read<String>(it,"message")==GuidanceStartCalibration.PROMPT_KO},"id")
  fun nativeStart(id:String){call(nativeEngine,"start",id)}
  fun nativeDone(id:String){call(nativeEngine,"done",id)}
  fun nativeError(id:String){call(read(nativeEngine,"listener"),"onError",id);call(outputClock,"advanceBy",0L)}
  override fun close(){if(stepSourceStarted)stepTracker.stop();output.closeActuator(); GatewaySessionProcessCoordinator.clear(gatewayGeneration);writeTrace()}

  fun selectDestination() {
   workflow.requestNativeFeature(NativeUiPage.DESTINATION_SEARCH)
   check(workflow.nativeUiPage==NativeUiPage.DESTINATION_SEARCH)
   workflow.openNativeDestinationConfirmation(destination)
   check(workflow.pendingUiDestination===destination)
   log("DESTINATION_SELECTED","object_identity" to System.identityHashCode(destination))
  }
  fun point(east:Double,north:Double)=RoutePoint(37.5+north/111_194.92664455874,
   127.0+east/(111_194.92664455874*kotlin.math.cos(Math.toRadians(37.5))))
  fun gps(east:Double=latestGpsEast,north:Double=latestGpsNorth,accuracy:Float=3f,observedAtMs:Long=now) {
   latestGpsEast=east;latestGpsNorth=north
   val p=point(east,north)
   log("GPS_INPUT","east_m" to east,"north_m" to north,"original_at_ms" to observedAtMs,"accuracy_m" to accuracy)
   requireNotNull(registeredCallback).onLocationResult(LocationResult.create(listOf(ControlledFusedLocation(p.latitude,p.longitude,accuracy,observedAtMs))))
   drainUi()
  }
  fun captureGpsDelivery():(Long)->Unit {
   val callback=requireNotNull(registeredCallback)
   val p=destination.point
   return {at->callback.onLocationResult(LocationResult.create(listOf(ControlledFusedLocation(p.latitude,p.longitude,3f,at))));drainUi()}
  }
  fun heading(degrees:Double=0.0,accuracyDegrees:Double?=5.0,observedAtMs:Long=now,
   reason:RouteCompassHeadingReason=RouteCompassHeadingReason.VALID) {
   headingObservation=RouteFacingObservation(degrees,accuracyDegrees,observedAtMs,RouteFacingSource.ROTATION_VECTOR,
    RouteFacingSensorQuality.REPORTED_ANGULAR_ERROR,reportedHeadingAccuracyDegrees=accuracyDegrees)
   headingReason=reason
   log("HEADING_INPUT","degrees" to degrees,"accuracy_deg" to accuracyDegrees,"original_at_ms" to observedAtMs,"reason" to reason.name)
   headingListener?.invoke(RouteCompassObservationEvent(1,RouteCompassStream.ROTATION_VECTOR,observedAtMs*1_000_000L,now,
    RouteCompassHeadingResult(headingObservation,reason),false))
   drainUi()
  }
  fun headingUnavailable(reason:RouteCompassHeadingReason=RouteCompassHeadingReason.ORIENTATION_MISSING){
   headingObservation=null;headingReason=reason
   log("HEADING_UNAVAILABLE","original_at_ms" to now,"reason" to reason.name)
   headingListener?.invoke(RouteCompassObservationEvent(1,RouteCompassStream.ROTATION_VECTOR,now*1_000_000L,now,
    RouteCompassHeadingResult(null,reason),true))
   drainUi()
  }
  fun defaultProviderResponse()=WalkingRoute(priority="STAIR_AVOID",summary=WalkingRouteSummary(100,100),
   polyline=listOf(point(0.0,0.0),point(0.0,60.0),point(40.0,60.0)),guidePoints=listOf(
    WalkingRouteGuidePoint(0,point(0.0,0.0),"출발",0,100,turnType=200,pointType="SP"),
    WalkingRouteGuidePoint(1,point(0.0,60.0),"우회전",60,40,bearingDeg=90f,turnType=13,pointType="guide")),providerRouteId="FULL-JOURNEY")
  fun straightProviderResponse(request:WalkingRouteRequest):WalkingRoute {
   val distance=haversineMeters(request.origin.latitude,request.origin.longitude,request.destination.latitude,request.destination.longitude).toInt().coerceAtLeast(1)
   return WalkingRoute("STAIR_AVOID",WalkingRouteSummary(distance,distance),listOf(request.origin,request.destination),listOf(
    WalkingRouteGuidePoint(0,request.origin,"출발",0,distance,turnType=200,pointType="SP"),
    WalkingRouteGuidePoint(1,request.destination,"직진",distance,0,turnType=11,pointType="guide")),providerRouteId="FULL-REROUTE")
  }
  fun parallelProviderResponse()=WalkingRoute("STAIR_AVOID",WalkingRouteSummary(220,220),
   listOf(point(0.0,0.0),point(60.0,0.0),point(60.0,6.0),point(0.0,6.0),point(0.0,60.0),point(40.0,60.0)),listOf(
    WalkingRouteGuidePoint(0,point(0.0,0.0),"출발",0,220,turnType=200,pointType="SP"),
    WalkingRouteGuidePoint(1,point(60.0,0.0),"좌회전",60,160,turnType=12,pointType="guide"),
    WalkingRouteGuidePoint(2,point(60.0,6.0),"좌회전",66,154,turnType=12,pointType="guide"),
    WalkingRouteGuidePoint(3,point(0.0,6.0),"우회전",126,94,turnType=13,pointType="guide"),
    WalkingRouteGuidePoint(4,point(0.0,60.0),"우회전",180,40,turnType=13,pointType="guide")),providerRouteId="FULL-PARALLEL")
  data class SensorEvidence(val gpsFirstBadAt:Long?,val gpsDeadline:Long?,val gpsStage:RuntimeMeasuredQualityGate.GpsRecoveryStage,
   val gpsOriginalAt:Long?,val gpsAccuracy:Double?,val trustedAt:Long?,val headingOriginalAt:Long?,
   val directionFirstBadAt:Long?,val directionDeadline:Long?,val directionMode:RouteDirectionGraceMode?)
  fun recordSensorEvidence():SensorEvidence {
   val direction=graph.run.navigator.currentDirectionGraceSnapshot()
   return SensorEvidence(gpsGate.gpsFailureStartedAtElapsedRealtimeMs(),gpsGate.pendingFailureDeadlineElapsedRealtimeMs(),gpsGate.gpsRecoveryStage(now),
    gpsObservation?.observedAtElapsedRealtimeMs,gpsObservation?.horizontalAccuracyMeters,graph.positioning.value.trustedLocation?.elapsedRealtimeMs,
    headingObservation?.observedAtElapsedRealtimeMs,direction?.onsetElapsedRealtimeMs,direction?.deadlineElapsedRealtimeMs,direction?.mode).also{
     log("SENSOR_EVIDENCE","gps_first_bad_at_ms" to it.gpsFirstBadAt,"gps_deadline_ms" to it.gpsDeadline,"gps_stage" to it.gpsStage.name,
      "gps_original_at_ms" to it.gpsOriginalAt,"gps_accuracy_m" to it.gpsAccuracy,"trusted_at_ms" to it.trustedAt,
      "heading_original_at_ms" to it.headingOriginalAt,"direction_first_bad_at_ms" to it.directionFirstBadAt,
      "direction_deadline_ms" to it.directionDeadline,"direction_mode" to it.directionMode?.name)
    }
  }
  fun currentBinding()=graph.positioning.value.binding
  val currentStepCount get()=steps
  val currentFeedbackGeneration get()=feedbackGeneration
  fun navigationSpeechRetryNotBeforeMs():Long?=read(graph.run.navigator,"speechRetryNotBeforeMs")
  fun recordSpeechReadinessEvidence(){
   val readiness=capture(WalkSessionAction.ROUTE_REQUEST)
   log("SPEECH_READINESS_EVIDENCE","route_admitted" to navigationAdmitted(),"environment_allowed" to environmentAllowed(),
    "guide_speech_current" to graph.guidance.value.isRouteSpeechCurrent(walk.epoch,routeGeneration,feedbackGeneration),
    "route_request_ready" to readiness.isReadyAt(now),
    "readiness_statuses" to readiness.observations.mapValues{it.value.status.name}.toString(),
    "command_pending" to output.hasPendingCommandResponse(),"actuator_status" to output.currentActuatorOrNull()?.statusText(),
    "output_active" to output.currentActuatorOrNull()?.isAppSpeechActive(),"route_active" to graph.run.active,
    "request_pending" to graph.acquisition.value.requestPending,"installed_start_pending" to (installedSpeech.pending!=null),
    "instruction_pending" to graph.run.navigator.hasPendingInstruction(),"information_pending" to graph.run.navigator.hasPendingInformation(),
    "position_paused" to graph.guidance.value.positionPaused,"direction_pause" to graph.run.directionPauseReason,
    "retry_not_before_ms" to navigationSpeechRetryNotBeforeMs())
  }
  fun eventCount(event:String)=(0 until trace.length()).count{trace.getJSONObject(it).optString("event")==event}
  fun eventTimes(event:String)=(0 until trace.length()).map{trace.getJSONObject(it)}
   .filter{it.optString("event")==event}.map{it.getLong("at_ms")}
  fun assertSameHomeOwner() {
   if(arrivalStarted){
    assertSame("Arrival retains the original HOME account identity",gateway.account,retainedBinding.owner.gatewaySession)
    assertArrivalInputsRetired()
    return
   }
   assertSame("HOME and route execution must use the same actual PS binding",retainedBinding,currentBinding())
   assertSame(gateway.account,requireNotNull(currentBinding()).owner.gatewaySession)
   assertTrue(requireNotNull(sdkCurrent).invoke(requireNotNull(registeredCallback)))
  }
  fun prepareArchive() {
   permissionPolicy.authenticated(gateway.account.actorId)
   graph.archive.value.initialize(::store)
   runIo();drainUi()
   graph.archive.value.submitRouteSnapshotOwnership(accountSnapshot(),walk)
   runIo();drainUi()
   check(!graph.archive.value.preparationPending)
  }
  /** Controlled account/walk ownership I/O ACK is distinct from route-response storage ACK. */
  fun acknowledgeWalkOwnership(){
   runIo();drainUi()
   check(!graph.archive.value.preparationPending)
   check(graph.archive.value.walkScope?.walkSessionId==walk.epoch.walkSessionId)
   log("WALK_ARCHIVE_OWNERSHIP_ACK","walk_id" to walk.epoch.walkSessionId)
  }
  fun runGatewayRequests(){while(gateway.background.isNotEmpty())gateway.runBackground();drainUi()}
  val currentProgressM:Double? get()=read(graph.run.navigator,"progressDistanceM")
  val latestAcceptedMatch get()=graph.positioning.value.rawRouteLocation?.let{graph.run.navigator.currentAcceptedRouteMatchFor(it.elapsedRealtimeMs)}
  fun runProvider(){while(workers.isNotEmpty())workers.removeFirst().run();drainUi()}
  fun runIo(){while(io.isNotEmpty())io.removeFirst().run()}
  private fun store()=EncryptedRouteSnapshotStore(object:RouteSnapshotStorage {
   override fun read()=stored
   override fun write(envelope:String):Boolean{stored=envelope;log("STORE_WRITE");return true}
   override fun clear():Boolean{stored=null;log("STORE_CLEAR");return true}
  },VersionedLocalAead(AeadKeyPolicy("test.fulljourney.v",1,setOf(1)),object:LocalAeadKeyProvider {
   override fun getOrCreate(alias:String):SecretKey=keys.getOrPut(alias){KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()}
   override fun getExisting(alias:String)=keys[alias]
   override fun delete(alias:String)=true
   override fun createFreshAfterVerifiedPurge(alias:String)=getOrCreate(alias)
  }),testOnly=Unit,elapsedRealtimeMs={now},bootCount={7},processCoordinator=RouteSnapshotProcessCoordinator())
  private fun stage(value:String){stages+=value;log("STAGE","name" to value)}
  private fun log(event:String,vararg values:Pair<String,Any?>) {
   trace.put(JSONObject().put("event",event).put("at_ms",gateway.now)
    .put("walk_state",gateway.lifecycle.snapshot().state.name).put("epoch",gateway.lifecycle.snapshot().epoch.toString()).apply{
     values.forEach{(key,value)->put(key,value?:JSONObject.NULL)}
    })
  }
  private fun writeTrace(){
   val directory=System.getProperty("walksafe.fullJourneyReplay.outputDir")?:"build/reports/guidance-full-journey"
   File(directory).mkdirs()
   File(directory,"$caseId.json").writeText(JSONObject().put("case",caseId).put("scope","READY_CONTROLLERS_SINGLE_NAVIGATION_COMPOSITION")
    .put("android_home_view",false).put("android_handler_registration",false).put("raw_sensor_conversion",false)
    .put("physical_acoustic_field",false).put("trace",trace).toString(2))
  }
  private fun speechPort()=object:RouteGuidanceController.SpeechPort {
   override val commandGeneration get()=output.currentCommandGeneration()
   override val commandPending get()=output.hasPendingCommandResponse()
   override val validatingStarted get()=output.currentActuatorOrNull()?.validatingStartedSpeech==true
   override val statusText get()=output.currentActuatorOrNull()?.statusText()
   override fun busy()=commandPending||outputBusy()
   override fun outputBusy()=output.currentActuatorOrNull()?.isAppSpeechActive()==true
   override fun inputActive()=false
   override fun canSpeakNavigation(nowMs:Long)=!busy()
   override fun cancelNavigation(){output.currentActuatorOrNull()?.cancelNavigationSpeech()}
   override fun cancelCommand()=output.cancelCommandSpeechResponse()
   override fun failCommand(generation:Long)=output.failRegisteredCommandResponse(CommandResponseToken("command-response-$generation",generation))
   override fun submitNavigation(message:String,isInstructionStillCurrent:()->Boolean,onCompleted:()->Unit,onFailed:()->Unit)=
    output.dispatchNavigationSpeech(message,onCompleted,onFailed,isInstructionStillCurrent)
   override fun submitStatus(message:String,current:()->Boolean,completed:()->Unit,failed:(()->Unit)?)=
    if(failed==null)output.speakCommandResponse(message,statusExplanation=true,additionalCurrent=current,onCompleted=completed)
    else output.speakCommandResponse(message,statusExplanation=true,additionalCurrent=current,onCompleted=completed,
     onFailed=failed,onCancelled=failed,onRetired=failed)
   override fun submitInstalledStart(message:String,current:()->Boolean,terminal:()->Unit)=
    output.speakCommandResponse(message,statusExplanation=true,additionalCurrent=current,onCompleted=terminal,onFailed=terminal,onCancelled=terminal,onRetired=terminal)
  }
  private fun installOutput() {
   put(output,"reader",object:SpeechOutputReader {
    override fun lifetime()=SpeechOutputLifetime(feedbackGeneration,foreground)
    override fun commandContext()=CommandSpeechContext(feedbackGeneration,actor,gatewayGeneration,walk.epoch,workflow.nativeUiPage,false,false,false,false)
    override fun navigationOwner()=NavigationSpeechOwner(walk.epoch,graph.run.navigator.currentRouteId(),graph.run.navigator.pendingDecisionToken())
    override fun navigationOwnerIsCurrent(owner:NavigationSpeechOwner)=navigationAdmitted()&&owner.epoch==walk.epoch&&
     owner.routeId==graph.run.navigator.currentRouteId()&&owner.decisionToken==graph.run.navigator.pendingDecisionToken()
    override fun homeFeatureContextAvailable()=foreground&&actor==gateway.account.actorId
    override fun stateSourceIsCurrent(source:CurrentStateSpeechLease)=false
    override fun guidanceVoiceInputActive()=false
    override fun screenReaderActive()=false
   })
   put(output,"scheduler",object:SpeechOutputScheduler {
    override fun elapsedRealtimeMs()=now
    override fun isMainThread()=true
    override fun runOnMain(action:()->Unit)=action()
    override fun postReadiness(task:Runnable,delayMs:Long){timers+=now+delayMs to task}
    override fun removeReadiness(task:Runnable){timers.removeAll{it.second===task}}
    override fun postRetired(action:()->Unit){ui+=action}
   })
   put(output,"readyEffects",object:SpeechOutputReadyEffects {
    override fun refreshReadyUi(){refresh()}
    override fun reevaluateCurrentRisk(actuator:AndroidFeedbackActuator,lifecycleGeneration:Int,nowMs:Long)=Unit
    override fun retryCurrentNavigation(nowMs:Long)=graph.guidance.value.retryCurrentNavigationSpeechAfterReady(nowMs)
    override fun offlineSpeechUnavailable(lifecycleGeneration:Int,actuator:AndroidFeedbackActuator)=Unit
    override fun beforeCommandReplacement(previousGeneration:Long)=graph.guidance.value.failInterruptedRouteDecisionSpeech(previousGeneration)
   })
   put(output,"debugDiagnostic",{line:String->log("OUTPUT_DIAGNOSTIC","text" to line)})
   put(output,"actuatorFactory",SpeechOutputActuatorFactory{unavailable,ready->
    AndroidFeedbackActuator(runtime=runtime,onOfflineKoreanSpeechUnavailable=unavailable,onOfflineKoreanSpeechReady=ready)
   })
  }
  data class NativeSpoken(val message:String,val id:String,var started:Boolean=false,var done:Boolean=false,var cancelled:Boolean=false,
   val guideIndex:Int?=null,val routeSegment:Int?=null,val dispatchCue:RouteSpeechCueToken?=null,
   val requestedAtMs:Long=0L,var startedAtMs:Long?=null,var doneAtMs:Long?=null,
   var scheduledStartAtMs:Long?=null,var scheduledDoneAtMs:Long?=null,
   val callbackOwner:FeedbackSpeechRuntime.Listener?=null)
  inner class NativeRuntime:FeedbackSpeechRuntime,FeedbackSpeechScheduler {
   val spoken=mutableListOf<NativeSpoken>()
   private var initialized:((Int)->Unit)?=null
   private var listener:FeedbackSpeechRuntime.Listener?=null
   private var automaticCallbacks:Pair<Long,Long>?=null
   val automaticCallbacksEnabled get()=automaticCallbacks!=null
   val automaticDoneDelayMs get()=requireNotNull(automaticCallbacks).second
   /** Native timing leaf only: production output guards and Navigator still accept or reject callbacks. */
   fun scheduleNativeCallbacks(startDelayMs:Long=80L,doneDelayMs:Long=3_000L){
    require(startDelayMs>=0L&&doneDelayMs>startDelayMs)
    automaticCallbacks=startDelayMs to doneDelayMs
    spoken.filter{!it.done&&!it.cancelled}.forEach(::scheduleCallbacks)
   }
   private fun scheduleCallbacks(spoken:NativeSpoken){
    val delays=automaticCallbacks?:return
    spoken.scheduledStartAtMs=now+delays.first
    spoken.scheduledDoneAtMs=now+delays.second
    log("TTS_CALLBACK_SCHEDULE","id" to spoken.id,"start_at_ms" to spoken.scheduledStartAtMs,"done_at_ms" to spoken.scheduledDoneAtMs)
    postDelayed(Runnable{if(!spoken.started&&!spoken.done&&!spoken.cancelled)start(spoken.id)},delays.first)
    postDelayed(Runnable{if(spoken.started&&!spoken.done&&!spoken.cancelled)done(spoken.id)},delays.second)
   }
   override val scheduler:FeedbackSpeechScheduler get()=this
   override val engineInitialized get()=initialized!=null
   override fun elapsedRealtime()=now
   override fun initialize(onInitialized:(Int)->Unit){initialized=onInitialized;if(engineIsReady)post(Runnable{ready()})}
   fun ready(){engineIsReady=true;log("TTS_ENGINE_INITIALIZED");requireNotNull(initialized).invoke(TextToSpeech.SUCCESS)}
   override fun setLanguage(language:Locale)=TextToSpeech.LANG_AVAILABLE
   override fun selectOfflineKoreanVoice()=kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection.SELECTED
   override fun configureAudioAttributes()=Unit
   override fun setListener(listener:FeedbackSpeechRuntime.Listener):Int{this.listener=listener;return TextToSpeech.SUCCESS}
   override fun speak(message:String,queueMode:Int,utteranceId:String):Int {
    val cue=if(isClockMetres(message)||utteranceId.startsWith("nav-"))read<RouteSpeechCueToken?>(graph.run.navigator,"pendingSpeechCue") else null
    val segment=graph.run.navigator.currentTactileRouteProgress().segmentIndex
    val request=NativeSpoken(message,utteranceId,guideIndex=cue?.guideIndex,routeSegment=segment,dispatchCue=cue,
     requestedAtMs=now,callbackOwner=listener)
    spoken+=request
    scheduleCallbacks(request)
    log("TTS_REQUEST","id" to utteranceId,"text" to message,"guide_index" to cue?.guideIndex,"route_segment" to segment,"cue_id" to cue?.let{"${it.routeRevision}:${it.cueRevision}"})
    return TextToSpeech.SUCCESS
   }
   fun start(id:String){val request=spoken.single{it.id==id};log("TTS_START","id" to id);request.started=true;request.startedAtMs=now;requireNotNull(request.callbackOwner?:listener).onStart(id)}
   fun done(id:String){val request=spoken.single{it.id==id};log("TTS_DONE","id" to id);request.done=true;request.doneAtMs=now;requireNotNull(request.callbackOwner?:listener).onDone(id);drainUi()}
   fun error(id:String){val request=spoken.single{it.id==id};log("TTS_ERROR","id" to id);requireNotNull(request.callbackOwner?:listener).onError(id,TextToSpeech.ERROR);drainUi()}
   fun stopCallback(id:String){val request=spoken.single{it.id==id};log("TTS_STOP_CALLBACK","id" to id);requireNotNull(request.callbackOwner?:listener).onStop(id,true);drainUi()}
   override fun stop(){spoken.filter{!it.done&&!it.cancelled}.toList().forEach{it.cancelled=true;log("TTS_STOP","id" to it.id);(it.callbackOwner?:listener)?.onStop(it.id,true)}}
   override fun shutdown()=Unit
   override fun requestAudioFocus(isRisk:Boolean,listener:AudioManager.OnAudioFocusChangeListener)=true
   override fun abandonAudioFocus(listener:AudioManager.OnAudioFocusChangeListener)=Unit
   override fun createProgressTone(volumePercent:Int):ProgressToneEffects=kotlin.error("Tone outside replay")
   override fun post(task:Runnable)=postDelayed(task,0L)
   override fun postDelayed(task:Runnable,delayMs:Long):Boolean{timers+=now+delayMs to task;return true}
   override fun removeCallbacks(task:Runnable){timers.removeAll{it.second===task}}
   override fun removeCallbacksAndMessages(token:Any?)=Unit
  }
  fun finishPendingSpeech() {
   runtime.spoken.filter{!it.done&&!it.cancelled&&it.message!="도착했습니다. 안내를 종료합니다"}.toList().forEach{runtime.start(it.id);runtime.done(it.id)}
  }
  fun completedRouteSpeechCue():RouteSpeechCueToken?=read(graph.run.navigator,"completedSpeechCue")
  fun assertNativeRouteStartAccepted(spoken:NativeSpoken){
   val cue=requireNotNull(spoken.dispatchCue){"Actual route dispatch must retain its reserved Navigator cue"}
   val id="${cue.routeRevision}:${cue.cueRevision}"
   assertTrue("Native START must be accepted by the actual current cue guard: $id",
    guidanceStartChecks.any{it["cue_id"]==id&&it["result"]=="started"})
   assertEquals("The accepted native START must retain the same actual reserved cue",cue,
    read<RouteSpeechCueToken?>(graph.run.navigator,"pendingSpeechCue"))
   assertFalse("Accepted route START cannot belong to a cancelled utterance",spoken.cancelled)
   assertTrue("Actual actuator must report current app speech after accepted START",output.currentActuatorOrNull()?.isAppSpeechActive()==true)
  }
  fun assertNativeRouteCompletionAcknowledged(spoken:NativeSpoken){
   val cue=requireNotNull(spoken.dispatchCue)
   assertFalse("Native DONE cannot consume a cancelled route cue",spoken.cancelled)
   assertEquals("Actual Navigator must acknowledge this exact dispatched cue after native DONE",cue,completedRouteSpeechCue())
   assertTrue("Actual guidance completion log must identify the same Navigator cue",cue in acknowledgedRouteCues)
  }
  private fun isClockMetres(message:String)=Regex("[0-9]+시 방향.*[0-9]+미터").containsMatchIn(message)
  fun routeSpeech()=runtime.spoken.filter{isClockMetres(it.message)}
  /** Complete native status/start notices while preserving the actual first route cue for the test. */
  fun finishPendingStatusSpeech(){
   runtime.spoken.filter{!it.done&&!it.cancelled&&!isClockMetres(it.message)&&it.message!="도착했습니다. 안내를 종료합니다"}.toList().forEach{runtime.start(it.id);runtime.done(it.id)}
  }
  data class RouteProgressEvidence(val segment:Int?,val nextGuide:Int,val acceptedTurnProgressM:Double?,
   val acceptedTurnGuideProgressM:Double?,val acceptedTurnRawAtMs:Long?,val acceptedTurnFilteredAtMs:Long?)
  val routeProgressEvidence=mutableListOf<RouteProgressEvidence>()
  fun recordRouteProgress():RouteProgressEvidence {
   val navigator=graph.run.navigator
   return RouteProgressEvidence(navigator.currentTactileRouteProgress().segmentIndex,read(navigator,"nextGuideIndex"),
    read(navigator,"lastPassedImportantTurnProgressM"),read(navigator,"lastPassedImportantTurnGuideProgressM"),
    read(navigator,"lastPassedImportantTurnRawAtMs"),read(navigator,"lastPassedImportantTurnFilteredAtMs")).also {
     routeProgressEvidence+=it
     log("ROUTE_PROGRESS","segment" to it.segment,"next_guide" to it.nextGuide,
      "accepted_turn_progress_m" to it.acceptedTurnProgressM,"accepted_turn_guide_progress_m" to it.acceptedTurnGuideProgressM,
      "accepted_turn_raw_at_ms" to it.acceptedTurnRawAtMs,"accepted_turn_filtered_at_ms" to it.acceptedTurnFilteredAtMs)
    }
  }
  private var arrivalCompletionOwner:RouteGuidanceController.ArrivalCompletion?=null
  var arrivalBegins=0;private set
  var arrivalFinishes=0;private set
  val arrivalStarted get()=arrivalBegins>0
  fun arrivalSpeech()=runtime.spoken.filter{it.message=="도착했습니다. 안내를 종료합니다"}
  fun assertArrivalInputsRetired(){
   assertNull("Arrival must retire the actual positioning binding before final speech",currentBinding())
   assertNull(graph.positioning.value.callback)
   assertFalse(requireNotNull(sdkCurrent).invoke(requireNotNull(registeredCallback)))
   assertNull(graph.gps.value.owner)
   assertNull(officialRuntime.guard)
   assertFalse(officialRuntime.outputsAllowed)
   assertTrue(eventCount("ARRIVAL_CAMERA_STOP")>0)
  }
  fun startFreshWalk()=cleanup.startFreshWalk("replay_new_walk")
  private fun beginAutomaticArrival(token:RouteNavigatorDecisionToken):RouteGuidanceController.ArrivalCompletion? {
   if(arrivalCompletionOwner?.isCurrent()==true||walk.state!=WalkSessionState.ACTIVE)return null
   val activeEpoch=walk.epoch
   val endingEpoch=activeEpoch.copy(recoveryGeneration=activeEpoch.recoveryGeneration+1L)
   val originalAccount=accountSnapshot()
   val originalDestination=workflow.pendingUiDestination
   var prepared=false
   var expectedRequestGeneration:Int?=null
   val completion=object:RouteGuidanceController.ArrivalCompletion {
    override fun isCurrent()=foreground&&actor==gateway.account.actorId&&
     accountSnapshot().generation==originalAccount.generation&&accountSnapshot().session===originalAccount.session&&
     workflow.pendingUiDestination===originalDestination&&
     (walk.epoch==endingEpoch&&walk.state==WalkSessionState.PAUSED||
      !prepared&&walk.epoch==activeEpoch&&walk.state==WalkSessionState.ACTIVE)&&
     (expectedRequestGeneration==null||routeGeneration==expectedRequestGeneration)
    override fun finish(){
     if(arrivalCompletionOwner!==this||!isCurrent())return
     arrivalCompletionOwner=null
     arrivalFinishes++
     transition.transitionWalkSession(WalkSessionEvent.EndRequested)
     cleanup.cancelWalkSessionOutputs("arrival_completed")
     log("ARRIVAL_ENDED")
    }
   }
   arrivalCompletionOwner=completion
   if(graph.acquisition.value.completeDetectedArrival(token)==null){arrivalCompletionOwner=null;return null}
   cleanup.enterWalkSessionForegroundRecheckAndCancelOutputs("arrival_ending")
   expectedRequestGeneration=routeGeneration
   prepared=true
   if(!completion.isCurrent()){arrivalCompletionOwner=null;return null}
   arrivalBegins++
   log("ARRIVAL_ENDING")
   return completion
  }
  private val cleanup by lazy {WalkSessionCleanupController(
   object:WalkSessionRuntimeAccess {
    override fun available()=true
    override fun snapshot()=walk
    override fun isRuntimeEpochCurrent(epoch:WalkRuntimeEpoch)=gateway.lifecycle.isRuntimeEpochCurrent(epoch)
    override fun transition(event:WalkSessionEvent)=transition.transitionWalkSession(event)
   },gateway.authority,gatewayController,{gateway.account},resume.controller,
   port<WalkCleanupAdmission>{n,_->when(n){
    "persistInterruptionMarker"->true
    "invalidateOfficialEnvironment"->{officialRuntime.reset();Unit}
    "invalidatePhoneMounting"->{mountingConfirmation=null;Unit}
    "clearStartupState"->{confirm.writeConfirmedDecision(null);confirm.writeConfirmationPending(false);Unit}
    else->null}},
   port<WalkCleanupNative>{n,_->when(n){
    "stopVoiceService","stopDepthSession"->true
    "stopCameraFallbackSession"->{log("ARRIVAL_CAMERA_STOP");Unit}
    else->null}},
   port<WalkCleanupNavigation>{n,_->when(n){
    "cancelRequestsForPause"->graph.acquisition.value.cancelNavigationRequestsForPause()
    "cancelWalkSessionRouteState"->{graph.acquisition.value.resetRouteState();Unit}
    "locationCollectionAllowsWork"->false
    "stopPositionFieldSession","locationCleanupSucceeded"->true
    "stopLocationUpdates"->{graph.positioning.value.stopLocationUpdates();Unit}
    "stopStepTracking"->{if(stepSourceStarted){stepTracker.stop();stepSourceStarted=false};Unit}
    "retainLocationCompassOrStop"->{graph.compass.value.stopRouteCompassPresentationUpdates();Unit}
    "startLocationUpdatesIfAllowed"->{graph.positioning.value.startLocationUpdatesIfAllowed();Unit}
    else->null}},
   port<WalkCleanupFeedback>{n,_->when(n){
    "advanceGenerationAndResetReferences"->{feedbackGeneration++;Unit}
    "cancelGuidancePreparation"->{graph.guidance.value.clearRecovery();graph.guidance.value.clearResume();Unit}
    "closeActuator"->{output.closeActuator();Unit}
    else->null}},port(),false)}
  private val transition by lazy {WalkSessionTransitionController({gateway.lifecycle},gateway.authority,gatewayController,{gateway.account},
   port<WalkTransitionEffects>{n,a->when(n){"clearResumeActivation"->{graph.acquisition.value.resetRouteState();output.currentActuatorOrNull()?.cancelNavigationSpeech();Unit}
    "updateRouteSnapshotWalkOwnership"->{
     val change=a[0] as WalkSessionTransition
     graph.archive.value.updateRouteSnapshotWalkOwnership(change)
     if(change.changed)log("WALK_ARCHIVE_OWNERSHIP_REQUEST","walk_id" to change.current.epoch.walkSessionId,"state" to change.current.state.name)
     Unit};else->null}},
   port(),port<WalkTransitionDiagnostics>{n,a->when(n){"available"->true;"recordEvent"->{log("TRANSITION","event" to a[0],"values" to a[1].toString());Unit};else->null}})}
  val commands by lazy {VoiceCommandInteractionController({error("No search at arrival")}, {graph.run},
   port<VoiceInteractionWalk>{n,_->when(n){"snapshot"->walk;"runtimeEpochOrNull"->gateway.lifecycle.currentRuntimeEpochOrNull();"runtimeActive"->walk.state==WalkSessionState.ACTIVE
    "confirmWalkEnd"->{transition.transitionWalkSession(WalkSessionEvent.EndRequested);log("END_CONFIRMED");Unit};else->null}},
   port<VoiceInteractionRoute>{n,a->when(n){"confirmArrival"->graph.acquisition.value.confirmArrival(a[0] as RouteNavigatorDecisionToken)
    "resetRouteState"->{graph.acquisition.value.resetRouteState();Unit};"snapshotPurgeFailed"->graph.archive.value.purgeFailed
    "navigationCollectionAllowsWork"->navigationAdmitted();"requestPending"->graph.acquisition.value.requestPending
    "requestGeneration"->routeGeneration;"currentRouteLocationAvailable"->graph.positioning.value.currentRouteLocationOrNull()!=null
    "requestRoute"->{graph.acquisition.value.requestRoute(a[0] as RoutePoint,a[1] as String,destinationCoordinateEvidence=a[2] as DestinationCoordinateEvidence?);Unit}
    "requestLocationRecheck"->graph.guidance.value.requestLocationRecheck(a[0] as RouteNavigatorDecisionToken?) {
     graph.positioning.value.startLocationUpdatesIfAllowed()
     graph.compass.value.ensureEarthOrientationForLocation()
     output.ensureFeedbackActuator()
    }
    "applyRouteDeviationSafetyUpdate"->{graph.guidance.value.applyRouteDeviationSafetyUpdate(a[0] as RouteNavigatorUpdate);Unit}
    "retainRouteAfterRerouteFailure"->{graph.acquisition.value.retainRouteAfterRerouteFailure(a[0] as String);Unit}
    "updateRouteDeviationActions"->Unit;else->null}},
   port<VoiceInteractionIdentity>{n,_->when(n){"feedbackLifetimeGeneration"->feedbackGeneration;"actorId"->actor;"processGeneration"->gatewayGeneration;else->null}},
   port<VoiceInteractionPresentation>{n,a->if(n=="navigationStatus"){log("VOICE_STATUS","text" to a[0]);Unit}else null},
   port<VoiceInteractionInput>{n,_->if(n=="startVoiceCommandRecognition")true else null},
   port<VoiceInteractionOutput>{n,a->when(n){"speakInteraction"->output.speakInteraction(a[0] as String);"speakWalkEndQuestion"->{
    @Suppress("UNCHECKED_CAST") output.speakCommandResponse(a[0] as String,onCompleted=a[1] as ()->Unit,onFailed=a[2] as ()->Unit,statusExplanation=true)};else->null}},
   port<VoiceInteractionScheduler>{n,a->when(n){"nowMs"->now;"runOnUiThread"->{@Suppress("UNCHECKED_CAST")(a[0] as ()->Unit).invoke();Unit};else->null}},
   dialog={error("No destination voice dialog at arrival")})}
 
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
