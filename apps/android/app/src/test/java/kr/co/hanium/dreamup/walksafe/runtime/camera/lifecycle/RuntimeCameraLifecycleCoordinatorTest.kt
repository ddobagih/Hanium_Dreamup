package kr.co.hanium.dreamup.walksafe.runtime.camera.lifecycle

import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.runtime.camera.CameraFallbackController
import kr.co.hanium.dreamup.walksafe.runtime.camera.CameraFallbackStartReason
import kr.co.hanium.dreamup.walksafe.runtime.vision.ar.RuntimeArSessionOwner
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Real native cleanup ownership and walk reducer; platform doubles do not prove device behavior. */
class RuntimeCameraLifecycleCoordinatorTest {
    @Test fun constructionDoesNotQuerySdkAdmissionOwnerOrClock() {
        val f=Fixture();assertTrue(f.events.isEmpty());assertEquals(0,f.clockReads)
        AndroidRuntimeArPlatform({ error("construction must not request Activity") },object:RuntimeArDisplayGeometry {
            override val textureId:Int get()=error("texture")
            override val width:Int get()=error("width")
            override val height:Int get()=error("height")
            override fun rotation():Int=error("rotation")
        })
    }
    @Test fun firstRunFailureStopsBeforeAnyAvailabilityRequest() {
        val f=Fixture();f.firstRun=false;f.coordinator.startDepthSession();assertTrue(f.events.isEmpty())
    }
    @Test fun invalidatedAvailabilityCallbackCannotConstructAnOldSession() {
        val f=Fixture();f.availability=ArCoreApk.Availability.UNKNOWN_CHECKING;f.coordinator.startDepthSession()
        assertEquals(1,f.callbacks.size);f.coordinator.invalidateArCoreAvailabilityRecheck()
        f.callbacks.single()(ArCoreApk.Availability.SUPPORTED_INSTALLED)
        assertFalse(f.events.contains("create"));assertNull(f.owner.observe().session)
    }
    @Test fun changedFeedbackGenerationRetiresAvailabilityCallbackWithoutNewQueries() {
        val f=Fixture();f.availability=ArCoreApk.Availability.UNKNOWN_CHECKING;f.coordinator.startDepthSession()
        f.feedback++;f.events.clear();f.callbacks.single()(ArCoreApk.Availability.SUPPORTED_INSTALLED)
        assertTrue(f.events.isEmpty());assertNull(f.owner.observe().session)
    }
    @Test fun callbackPermissionRecheckDoesNotStartTheCamera() {
        val f=Fixture();f.availability=ArCoreApk.Availability.UNKNOWN_CHECKING;f.coordinator.startDepthSession()
        f.permission=false;f.callbacks.single()(ArCoreApk.Availability.SUPPORTED_INSTALLED)
        assertEquals("카메라 권한 필요",f.status);assertFalse(f.events.contains("create"))
    }
    @Test fun installRequestPreservesTheStoredSnapshotAndDefersNativeSessionCreation() {
        val f=Fixture();f.installStatus=ArCoreApk.InstallStatus.INSTALL_REQUESTED;f.coordinator.startDepthSession()
        assertEquals(listOf(true),f.installRequests);assertFalse(f.events.contains("create"))
        assertSame(f.storedSnapshot,f.refreshSnapshot)
        assertEquals(listOf("runtime_arcore_install_required"),f.invalidations)
        f.installStatus=ArCoreApk.InstallStatus.INSTALLED;f.coordinator.startDepthSession()
        assertEquals(listOf(true,false),f.installRequests);assertSame(f.session,f.owner.observe().session)
    }
    @Test fun nativePublishKeepsResumeActivationNavigationTimerAndEventOrder() {
        val f=Fixture();f.coordinator.startDepthSession()
        val names=listOf("install","create","provider","configure","load","pause_renderer","resume_native","activation","texture:true","resume_renderer:true","navigation","screen","timer","event:arcore_session_started")
        val at=names.map{f.events.indexOf(it)}
        assertTrue(f.events.toString(),at.all{it>=0});assertTrue(f.events.toString(),at.zipWithNext().all{(a,b)->a<b})
        assertSame(f.session,f.owner.observe().session);assertSame(f.provider,f.owner.observe().provider)
        assertEquals(Purpose.RUNTIME,f.owner.observe().purpose)
        assertEquals(f.owner.observe().generation,f.activation?.generation)
        assertEquals(f.epoch,f.navigationEpoch)
    }
    @Test fun configureFailureClosesTheCandidateBeforeInvalidatingAndNeverPublishesIt() {
        val f=Fixture();f.configureFailure=IllegalStateException("configure");f.coordinator.startDepthSession()
        assertNull(f.owner.observe().session);assertEquals(1,f.sessionCloses)
        assertEquals("ARCore 시작 실패",f.status);assertTrue(f.events.indexOf("close_session")<f.events.indexOf("invalidate:runtime_session_start_failed"))
    }
    @Test fun detectorFailureRetiresCandidateAndNeverResumesOrPublishes() {
        val f=Fixture();f.detector=false;f.coordinator.startDepthSession()
        assertNull(f.owner.observe().session);assertEquals(1,f.sessionCloses)
        assertFalse(f.events.contains("resume_native"));assertEquals(listOf("runtime_detector_start_unavailable"),f.invalidations)
    }
    @Test fun failedCandidateCloseRetainsItsRealBarrierAndBlocksAnotherStartWithoutRepeatingClose() {
        val f=Fixture();f.detector=false;f.closeFailure=IllegalStateException("close");f.coordinator.startDepthSession()
        assertTrue(f.owner.cleanupPending());assertEquals(1,f.sessionCloses)
        f.detector=true;f.coordinator.startDepthSession();assertEquals(1,f.events.count{it=="create"});assertEquals(1,f.sessionCloses)
        assertFalse(f.owner.closeRetained());assertEquals(1,f.sessionCloses)
    }
    @Test fun nativeResumeFailureAlwaysRestoresRendererThenRetiresCandidate() {
        val f=Fixture();f.resumeFailure=IllegalStateException("resume");f.coordinator.startDepthSession()
        assertNull(f.owner.observe().session);assertEquals(1,f.sessionCloses)
        assertTrue(f.events.indexOf("resume_renderer:true")<f.events.indexOf("close_session"))
        assertEquals(listOf("runtime_session_start_failed"),f.invalidations)
    }
    @Test fun exceptionAfterPublishDoesNotCloseTheSameCandidateAgain() {
        val f=Fixture();f.navigationFailure=IllegalStateException("navigation");f.coordinator.startDepthSession()
        assertSame(f.session,f.owner.observe().session);assertEquals(0,f.sessionCloses)
        assertEquals(listOf("runtime_session_start_failed"),f.invalidations);assertTrue(f.timers.isEmpty())
    }
    @Test fun expiredOldActivationCannotSampleClockOrRetireTheNewNativeGeneration() {
        val f=Fixture();f.coordinator.startDepthSession();assertEquals(1,f.timers.size)
        f.owner.publish(Any(),Any(),Purpose.RUNTIME);f.clockReads=0;f.events.clear();f.timers.single()()
        assertEquals(0,f.clockReads);assertFalse(f.events.contains("metric_loss"))
    }
    @Test fun oldActivationReferenceIsNotReplacedWhenOrdinaryLossIsRetained() {
        val f=Fixture();f.coordinator.startDepthSession();val actual=requireNotNull(f.activation)
        f.now=RuntimeMetricPreflightPolicy.MAX_DURATION_MS+1_000L;f.timers.single()()
        // The profile-unavailable evaluator follows the original non-timeout loss branch.
        assertTrue(f.events.contains("metric_loss"));assertSame(actual,f.activation)
    }
    @Test fun stopDetachesBeforeProviderPauseNativeCloseAndKeepsTheOriginalTail() {
        val f=Fixture();f.coordinator.startDepthSession();f.events.clear()
        assertTrue(f.coordinator.stopDepthSession(true));assertNull(f.owner.observe().session)
        val names=listOf("retire_metric","close_provider","pause_renderer","pause_native","close_session","texture:false","retire_generation","screen","visual_reset","clear_snapshot","pacing","clear_metadata","disable_upload","clear_overlay","upload_action","event:arcore_session_stopped","resume_renderer:true")
        val at=names.map{f.events.indexOf(it)};assertTrue(f.events.toString(),at.all{it>=0})
        assertTrue(f.events.toString(),at.zipWithNext().all{(a,b)->a<b});assertFalse(f.owner.cleanupPending())
    }
    @Test fun providerCloseFailureCannotClaimNativeCloseSuccessAndStillRunsTailCleanup() {
        val f=Fixture();f.coordinator.startDepthSession();f.providerFailure=IllegalStateException("provider");f.events.clear()
        assertFalse(f.coordinator.stopDepthSession(true));assertEquals(0,f.sessionCloses)
        assertTrue(f.owner.cleanupPending());assertTrue(f.events.contains("clear_snapshot"));assertTrue(f.events.contains("resume_renderer:false"))
    }
    @Test fun independentTailFailuresAreSuppressedWithoutSkippingRemainingCleanup() {
        val f=Fixture();f.coordinator.startDepthSession();val first=IllegalStateException("screen");val second=IllegalStateException("visual")
        f.screenFailure=first;f.visualFailure=second;f.events.clear()
        assertFalse(f.coordinator.stopDepthSession(true));assertSame(first,f.owner.cleanupFailureOrNull())
        assertEquals(listOf(second),first.suppressed.toList());assertTrue(f.events.contains("disable_upload"));assertTrue(f.events.contains("resume_renderer:true"))
    }
    @Test fun unknownAvailabilityKeepsExplicitRetryInsteadOfConstructingADeviceSession() {
        val f=Fixture();f.coordinator.continueDepthSessionStart(ArCoreApk.Availability.UNKNOWN_ERROR)
        assertFalse(f.events.contains("create"));assertEquals("ARCore 지원 확인 실패",f.status)
        assertEquals(listOf("runtime_arcore_availability_unknown"),f.invalidations)
    }

    @Test fun exactCurrentTimeoutRetainsTheOriginalActivationRatherThanStoppingTheWalk() {
        val f=Fixture();f.activationProfile=RuntimeMetricPreflightPolicy.testCandidateProfile
        f.coordinator.startDepthSession();val evaluator=requireNotNull(f.activation)
        f.now=100L+RuntimeMetricPreflightPolicy.MAX_DURATION_MS+1L;f.events.clear();f.timers.single()()
        assertTrue(f.events.contains("retain_loss"));assertFalse(f.events.contains("metric_loss"))
        assertSame(evaluator,f.activation);assertSame(f.session,f.owner.observe().session)
    }
    @Test fun failedCloseRetentionLeavesExactPublishedSessionAndGenerationUntouched() {
        val f=Fixture();f.coordinator.startDepthSession();val original=f.owner.observe()
        assertTrue(f.owner.retainClose {});f.events.clear()
        assertFalse(f.coordinator.stopDepthSession(true))
        assertSame(original.session,f.owner.observe().session);assertSame(original.provider,f.owner.observe().provider)
        assertEquals(original.generation,f.owner.observe().generation)
        assertEquals(listOf("revoke"),f.events)
    }
    @Test fun explicitDepthUnsupportedClosesBeforeCameraLessFallbackAndKeepsNoActiveNativeLease() {
        val f=Fixture();f.depthApiSupport=false;f.coordinator.startDepthSession()
        assertEquals(1,f.sessionCloses);assertNull(f.owner.observe().session)
        assertTrue(f.events.contains("without_camera"));assertTrue(f.timers.isEmpty())
    }

    @Test fun cameraReleaseContinuationUsesTheOriginalEpochAndFeedbackGeneration() {
        val f=Fixture();f.fallbackOwned=true;f.coordinator.startDepthSession()
        assertTrue(f.events.contains("stop_fallback"));assertFalse(f.events.contains("create"))
        f.fallbackOwned=false;f.releaseCallbacks.single()()
        assertSame(f.session,f.owner.observe().session)
        val retired=Fixture();retired.fallbackOwned=true;retired.coordinator.startDepthSession()
        retired.fallbackOwned=false;retired.feedback++;retired.releaseCallbacks.single()()
        assertNull(retired.owner.observe().session);assertFalse(retired.events.contains("create"))
    }
    @Test fun cameraReleaseAcknowledgementAfterWalkEpochRetirementDoesNotStartSdk() {
        val f=Fixture();f.fallbackOwned=true;f.coordinator.startDepthSession()
        f.walk.handle(WalkSessionEvent.EnteredBackground);f.fallbackOwned=false;f.releaseCallbacks.single()()
        assertNull(f.owner.observe().session);assertFalse(f.events.contains("create"))
    }

    @Test fun automaticSourceEffectObservesPublishedAndDetachedLeasesOutsideBothLocks() {
        val f = Fixture()
        val leases = mutableListOf<Any?>()
        f.onSourceChanged = {
            assertFalse(Thread.holdsLock(f.lock))
            leases += f.owner.observe().session
            f.events += "source_changed"
        }
        f.coordinator.startDepthSession()
        assertEquals(listOf(f.session), leases)
        assertTrue(f.events.indexOf("resume_renderer:true") < f.events.indexOf("source_changed"))
        assertTrue(f.events.indexOf("source_changed") < f.events.indexOf("navigation"))
        f.events.clear()
        assertTrue(f.coordinator.stopDepthSession(true))
        assertEquals(listOf(f.session, null), leases)
        assertTrue(f.events.indexOf("retire_metric") < f.events.indexOf("source_changed"))
        assertTrue(f.events.indexOf("source_changed") < f.events.indexOf("close_provider"))
    }

    @Test fun confirmedCameraFallbackRechecksTheSourceBeforePublishingItsUi() {
        val f = Fixture()
        f.onSourceChanged = { assertNull(f.owner.observe().session); f.events += "source_changed" }
        f.coordinator.handleCameraFallbackSourceEvent(kr.co.hanium.dreamup.walksafe.runtime.camera.CameraFallbackEvent.Bound(
            kr.co.hanium.dreamup.walksafe.runtime.camera.CameraFallbackLease(f.epoch, 1),
            CameraFallbackStartReason.ARCORE_DEPTH_UNSUPPORTED, ArCoreApk.Availability.SUPPORTED_INSTALLED))
        assertEquals(1, f.events.count { it == "source_changed" })
        assertTrue(f.events.indexOf("source_changed") < f.events.indexOf("screen"))
        assertFalse(f.events.contains("create"))
    }

    @Test fun failedAutomaticRetirementStillRunsNativeCloseAndItsCleanupTail() {
        val f = Fixture(); f.coordinator.startDepthSession()
        val failure = IllegalStateException("auxiliary retirement")
        f.onSourceChanged = { assertNull(f.owner.observe().session); throw failure }
        assertFalse(f.coordinator.stopDepthSession(true))
        assertEquals(1, f.sessionCloses)
        assertSame(failure, f.owner.cleanupFailureOrNull())
        assertTrue(f.events.contains("clear_metadata"))
        assertTrue(f.events.contains("resume_renderer:true"))
    }

    private enum class Purpose{NONE,RUNTIME}
    private class Fixture {
        var onSourceChanged: () -> Unit = {}
        val events=mutableListOf<String>();val callbacks=mutableListOf<(ArCoreApk.Availability)->Unit>();val timers=mutableListOf<()->Unit>()
        var now=100L;var clockReads=0;var firstRun=true;var runtime=true;var confirmed=true;var permission=true;var feedback=1;var detector=true
        var availability=ArCoreApk.Availability.SUPPORTED_INSTALLED;var installStatus=ArCoreApk.InstallStatus.INSTALLED
        val releaseCallbacks=mutableListOf<()->Unit>();var fallbackOwned=false
        val installRequests=mutableListOf<Boolean>();val invalidations=mutableListOf<String>();var status=""
        var activationProfile:RuntimeMetricPreflightProfile?=null;var depthApiSupport=true
        var configureFailure:RuntimeException?=null;var closeFailure:RuntimeException?=null;var resumeFailure:RuntimeException?=null
        var providerFailure:RuntimeException?=null;var navigationFailure:RuntimeException?=null;var screenFailure:RuntimeException?=null;var visualFailure:RuntimeException?=null
        val session=Any();val provider=Any();var sessionCloses=0;var activation:RuntimeMetricPreflightSession?=null;var navigationEpoch:WalkRuntimeEpoch?=null
        val storedSnapshot=PostLoginDeviceCheckPolicy.initial();var refreshSnapshot:PostLoginDeviceCheckSnapshot?=null
        val lock=Any();val walk=WalkSessionLifecycle(clock={100L}).also{
            it.handle(WalkSessionEvent.EnteredForeground)
            val epoch=it.snapshot().epoch;val collector=WalkSessionReadinessCollector(epoch,WalkSessionReadinessPlan(WalkSessionAction.START_WALK,WalkSessionMode.FULL))
            collector.plan.requiredRequirements.forEach{r->collector.record(WalkSessionReadinessObservation(epoch,r,WalkSessionReadinessStatus.READY))}
            it.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(100L)));it.handle(WalkSessionEvent.StartRequested(requireNotNull(it.snapshot().confirmationToken)))
        }
        val epoch get()=walk.snapshot().epoch
        val platform:RuntimeArPlatform<Any,Any> = object:RuntimeArPlatform<Any,Any>{
            override fun availability()=this@Fixture.availability.also{events+="availability"}
            override fun availabilityAsync(callback:(ArCoreApk.Availability)->Unit){callbacks+=callback;events+="availability_async"}
            override fun requestInstall(userRequested:Boolean)=installStatus.also{events+="install";installRequests+=userRequested}
            override fun consumeForcedCameraFallback()=false
            override fun createSession()=session.also{events+="create"}
            override fun createProvider(session:Any)=provider.also{assertSame(this@Fixture.session,session);events+="provider"}
            override fun configureDepth(provider:Any):Boolean{events+="configure";configureFailure?.let{throw it};return depthApiSupport}
            override fun closeSession(session:Any){assertFalse(Thread.holdsLock(lock));sessionCloses++;events+="close_session";closeFailure?.let{throw it}}
            override fun closeProvider(provider:Any){events+="close_provider";providerFailure?.let{throw it}}
            override fun pauseSession(session:Any){events+="pause_native"}
            override fun resumeBeforePublish(session:Any):Boolean{assertNull(owner.observe().session);events+="resume_native";resumeFailure?.let{throw it};return true}
        }
        val owner:RuntimeArSessionOwner<Any,Any,Purpose> = RuntimeArSessionOwner(lock,Purpose.NONE,platform::closeSession,platform::closeProvider,RuntimeArSessionCleanupOwnership())
        val admission=object:RuntimeCameraAdmission {
            override fun firstRunComplete()=firstRun
            override fun runtimeActive()=runtime
            override fun requireStartupConfirmation()=confirmed
            override fun startupConfirmed()=confirmed
            override fun cameraPermission()=permission
            override fun reporterRequired(reason:String)=true
            override fun finishing()=false
            override fun destroyed()=false
            override fun feedbackGeneration()=feedback
            override fun otherCameraOwnerBlocksStart()=owner.cleanupPending()
            override val fallbackRequested get()=fallbackOwned;override val fallbackRunning=false;override val fallbackCleanupPending=false
            override val fallbackHasCamera=false;override val fallbackReleaseBlocked=false
            override var arCoreSupported=false;override var depthSupported=false
            override fun detectorAvailable()=detector
            override fun loadDetectorAfterCameraGate(){events+="load"}
            override fun loadedModel()="actual-model"
            override fun detectorFallbackUsed()=false
            override fun cameraAnalysisEnabled()=false
        }
        val metric=object:RuntimeCameraMetric {
            override fun restoreCapability(available:Boolean?){events+="capability:$available"}
            override fun invalidate(reason:String){invalidations+=reason;events+="invalidate:$reason"}
            override fun retireRuntimeEvidence(){assertTrue(Thread.holdsLock(lock));assertNull(owner.observe().session);events+="retire_metric"}
            override fun revokeOutput(){events+="revoke"}
            override fun beginActivation(generation:Long,startedAtMs:Long){assertTrue(Thread.holdsLock(lock));assertSame(session,owner.observe().session);events+="activation";activation=RuntimeMetricPreflightSession(generation,startedAtMs,RuntimeMetricDepthSupport.SUPPORTED,activationProfile)}
            override fun activationEvaluator()=activation
            override fun retainOrdinaryLoss(generation:Long,epoch:WalkRuntimeEpoch,observedAtMs:Long,evaluator:RuntimeMetricPreflightSession){assertSame(activation,evaluator);events+="retain_loss"}
            override fun handleLoss(generation:Long){events+="metric_loss"}
            override fun postLoginSnapshot()=storedSnapshot
            override fun deferredCameraChecks()=true
            override fun prepareStoredRefresh(snapshot:PostLoginDeviceCheckSnapshot,deferred:Boolean){refreshSnapshot=snapshot;assertTrue(deferred);events+="stored_refresh"}
        }
        val renderer=object:RuntimeCameraRenderer {
            override fun pauseForClose():Boolean{events+="pause_renderer";return true}
            override fun resumeAfterClose(shouldResume:Boolean){events+="resume_renderer:$shouldResume"}
            override fun textureBound(value:Boolean){events+="texture:$value"}
            override fun postActivationExpiry(task:()->Unit,delayMs:Long){assertEquals(RuntimeMetricPreflightPolicy.MAX_DURATION_MS,delayMs);timers+=task;events+="timer"}
            override fun syncScreenPolicy(){events+="screen";screenFailure?.let{throw it}}
            override fun stopFallback(updateUi:Boolean,onReleased:()->Unit){assertFalse(updateUi);events+="stop_fallback";releaseCallbacks+=onReleased}
            override fun bindFallbackCurrent(){error("fallback not requested")}
        }
        val readiness=object:RuntimeCameraReadiness {
            override fun decision():WalkSafeStartupCapabilityDecision?=null
            override fun allowsMetricDistance(decision:WalkSafeStartupCapabilityDecision)=false
            override fun resolveDistanceLimited()=WalkSafeStartupCapabilityDecision(WalkSafeStartupCapabilityTier.BLOCKED,emptyList(),emptyList(),"")
            override fun confirmDecision(decision:WalkSafeStartupCapabilityDecision){events+="confirmed"}
            override fun enterForegroundRecheckAndCancelOutputs(reason:String){events+="recheck"}
            override fun refreshUi(){events+="refresh"}
            override fun startWithoutCamera(){events+="without_camera"}
            override fun startInitialNavigationIfCurrent(generation:Long,epoch:WalkRuntimeEpoch){navigationEpoch=epoch;events+="navigation";navigationFailure?.let{throw it}}
            override fun startNavigationIfNeeded(){events+="navigation"}
            override fun setObstacleUnavailable(){events+="obstacle_unavailable"}
            override fun activateMountingRuntime(){events+="mounting"}
            override fun applyCurrentEnvironmentAssessment(){events+="environment"}
        }
        val presentation=object:RuntimeCameraPresentation {
            override fun startAction(text:String,enabled:Boolean){events+="action:$text"}
            override fun actionInitialized()=true
            override fun status(status:String,detail:String){this@Fixture.status=status;events+="status:$status"}
            override fun guidanceStage(stage:String){events+="stage:$stage"}
            override fun localRouteState(value:String){events+="local_route"}
            override fun reportStatus(value:String){events+="report_status"}
            override fun recordEvent(name:String,facts:Map<String,Any?>){events+="event:$name"}
            override fun fieldLogInitialized()=true
            override fun previewVisible(visible:Boolean){events+="preview:$visible"}
            override fun previewInitialized()=true
            override fun disableMetadataUploadIfInitialized(){events+="disable_upload"}
            override fun clearOverlayOnUiIfInitialized(){events+="clear_overlay"}
            override fun resetActionIfInitialized(){events+="action"}
            override fun updateUploadActionIfInitialized(){events+="upload_action"}
        }
        val frames=object:RuntimeCameraFrameRetirement {
            override fun clearReportFrameCapture(){events+="clear_report_capture"}
            override fun retireDetectorGeneration(){events+="retire_generation"}
            override fun resetVisualTracking(){events+="visual_reset";visualFailure?.let{throw it}}
            override fun clearDetectionSnapshot(){events+="clear_snapshot"}
            override fun resetPacing(){events+="pacing"}
            override fun clearMetadata(){events+="clear_metadata"}
            override fun resetNonMetricPolicy(){events+="reset_non_metric"}
        }
        val coordinator=RuntimeCameraLifecycleCoordinator(lock,Any(),owner,Purpose.NONE,Purpose.RUNTIME,
            {error("No fallback SDK should be started by this fixture")},{walk},admission,platform,renderer,metric,readiness,presentation,frames,{clockReads++;now}, sourceChanged = { onSourceChanged() })
    }
}
