package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import kr.co.hanium.dreamup.walksafe.depth.*
import com.google.ar.core.Frame
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Actual presentation component and existing display policies; views/loop/source effects are probes. */
class FramePresentationControllerTest {
    @Test fun constructorDoesNotReadAnyOwnerWidgetOrClock() {
        val f=Fixture()
        assertTrue(f.events.isEmpty());assertTrue(f.ui.isEmpty());assertTrue(f.statusTasks.isEmpty())
    }
    @Test fun unavailableNativePreparationRemainsAfterTheCameraThrottleAndIsNeverQueued() {
        val f=Fixture();var prepared=0
        f.visible=false
        f.controller.showUnavailable(1_000,1,f.epoch,f.lease){prepared++;f.unavailable()}
        f.visible=true
        f.controller.showUnavailable(249,1,f.epoch,f.lease){prepared++;f.unavailable()}
        assertEquals(0,prepared)
        f.controller.showUnavailable(250,1,f.epoch,f.lease){prepared++;f.unavailable()}
        assertEquals(1,prepared);assertEquals(250L,f.controller.readCameraFrameUiMarker())
        assertEquals(1,f.ui.size);assertTrue(f.cameraText.isEmpty())
        f.drainUi();assertEquals("camera unavailable",f.cameraText);assertEquals(1,prepared)
        assertEquals(59_000L,f.mapped.single().capturedAt)
    }
    @Test fun unavailableRejectsRetiredLeaseAndFreshnessFalseClearsWithoutManufacturingACapture() {
        val f=Fixture()
        f.controller.showUnavailable(250,1,f.epoch,f.lease){f.unavailable()}
        f.leaseCurrent=false;f.drainUi();assertTrue(f.mapped.isEmpty());assertTrue(f.cameraText.isEmpty())
        f.leaseCurrent=true
        f.controller.showUnavailable(500,1,f.epoch,f.lease){f.unavailable().copy(fresh=false)}
        f.drainUi();assertEquals(1,f.clears);assertTrue(f.mapped.isEmpty())
    }
    @Test fun overlayTerminalProducerFailureDoesNotMapPublishOrAdvanceThrottle() {
        val f=Fixture();f.frameCurrent=false;var maps=0
        assertNull(f.showOverlay(f.overlay()){maps++;emptyList()})
        assertEquals(0,maps);assertTrue(f.ui.isEmpty())
        f.frameCurrent=true
        val input=f.overlay().copy(scope=f.scope(10),currentObservationPresent=false)
        assertSame(input.diagnostics,f.showOverlay(input){maps++;emptyList()})
        assertEquals(0,maps)
    }
    @Test fun observationForcesOverlayAndSourceIdentityRetiresAlreadyQueuedOldDisplay() {
        val f=Fixture();var maps=0
        assertNotNull(f.showOverlay(f.overlay()){maps++;emptyList()})
        val next=f.overlay().copy(scope=f.scope(60_001),sourceFrameId=10_001_000_000L,
            bestOutput=output().copy(frameId=10_001_000_000L))
        f.showOverlay(next){maps++;emptyList()}
        f.drainUi()
        assertEquals(2,maps);assertEquals(1,f.mapped.size)
        assertEquals(10_001_000_000L,f.mapped.single().frameId)
        assertEquals(59_000L,f.mapped.single().capturedAt)
    }
    @Test fun overlayWidthRejectionShortCircuitsHeightClockAndBothExpiryPolicies() {
        val f=Fixture();f.showOverlay(f.overlay()){emptyList()}
        f.overlayWidth=999;f.events.clear();f.drainUi()
        assertFalse(f.events.contains("overlay_height"));assertFalse(f.events.contains("clock"))
        assertTrue(f.mapped.isEmpty());assertTrue(f.overlayTasks.isEmpty())
    }
    @Test fun overlayKeepsBothIndependentInclusiveDeadlinesAndDoesNotRetainNativeMapper() {
        val f=Fixture();var mappings=0
        val predicted=output().copy(prediction=PredictedDepthEstimate(1.1f,10_000,0,0.2f,3_000))
        f.showOverlay(f.overlay(predicted)){mappings++;emptyList()}
        assertEquals(2,mappings)
        f.drainUi();assertEquals(listOf(3_001L,1_201L),f.overlayTasks.map{it.delay})
        f.now=61_201;f.overlayTasks[1].action()
        assertEquals(59_000L,f.mapped.last().capturedAt)
        f.now=63_001;f.overlayTasks[0].action()
        assertEquals(2,mappings);assertEquals(59_000L,f.mapped.last().capturedAt)
        assertEquals(10_000_000_000L,f.mapped.last().frameId)
    }
    @Test fun overlayMappingCannotPublishAfterSourceRetirementAndRetireCommandInvalidatesExpiry() {
        val f=Fixture()
        assertNull(f.showOverlay(f.overlay()){f.frameCurrent=false;emptyList()})
        assertTrue(f.ui.isEmpty())
        f.frameCurrent=true
        f.showOverlay(f.overlay()){emptyList()};f.drainUi()
        val written=f.mapped.size
        f.controller.retireOverlayFrame();f.overlayTasks.single().action()
        assertEquals(written,f.mapped.size)
    }
    @Test fun gapPredictionUsesOriginalCaptureAndMetricRecoveryRemovesPredictionAtExpiry() {
        val f=Fixture();f.metric=false
        val predicted=output().copy(prediction=PredictedDepthEstimate(1.1f,10_000,50,0.2f,200))
        f.controller.showGap(FrameGapPresentationInput(f.scope(),60_000,2,listOf(predicted)))
        f.drainUi();assertTrue(f.cameraText.contains("예측 1.10m ±0.20m · 공백 50ms"))
        assertEquals(151L,f.cameraTasks.single().delay)
        f.now=60_151;f.metric=true;f.events.clear();f.cameraTasks.single().action()
        assertTrue(f.cameraText.startsWith("Depth 재수신 · 거리 갱신 대기"));assertFalse(f.cameraText.contains("예측 1.10m"))
        assertFalse(f.events.contains("visual_gate"))
    }
    @Test fun gapNeverOverwritesANewerCameraMessageEvenWhenItsTextOwnerDoesNotUseStatusToken() {
        val f=Fixture();f.metric=false
        val predicted=output().copy(prediction=PredictedDepthEstimate(1.1f,10_000,0,0.2f,200))
        f.controller.showGap(FrameGapPresentationInput(f.scope(),60_000,1,listOf(predicted)));f.drainUi()
        f.cameraText="camera permission warning";f.events.clear();f.cameraTasks.single().action()
        assertEquals("camera permission warning",f.cameraText);assertFalse(f.events.contains("clock"))
    }
    @Test fun missingGapCaptureDoesNotReadClockOrMetricAndNewMarkerRetiresOldGap() {
        val f=Fixture()
        f.controller.showGap(FrameGapPresentationInput(f.scope(),null,1,listOf(output())));f.events.clear();f.drainUi()
        assertFalse(f.events.contains("clock"));assertFalse(f.events.contains("metric_gate"))
        f.metric=false
        val predicted=output().copy(prediction=PredictedDepthEstimate(1.1f,10_000,0,0.2f,200))
        f.controller.showGap(FrameGapPresentationInput(f.scope(60_250),60_000,1,listOf(predicted)));f.drainUi()
        f.controller.showUnavailable(60_500,1,f.epoch,f.lease){f.unavailable()};f.events.clear()
        f.cameraTasks.single().action();assertFalse(f.events.contains("clock"))
    }
    @Test fun statusPreparationIsAfterThrottleClaimAndBeforeRequestedOwnerAndContextReads() {
        val f=Fixture();var preparations=0
        f.controller.showStatus(f.status(elapsed=499)){preparations++;"acquisition"}
        assertEquals(0,preparations);assertTrue(f.ui.isEmpty())
        f.events.clear()
        f.controller.showStatus(f.status(elapsed=500)){preparations++;f.events+="acquisition";"acquisition"}
        assertEquals(1,preparations)
        assertTrue(f.events.indexOf("acquisition")<f.events.indexOf("status_owner"))
        assertTrue(f.events.indexOf("status_owner")<f.events.indexOf("capture_context"))
        f.drainUi();assertEquals(1,preparations)
    }
    @Test fun queuedStatusRejectsActorPageViewportGeometryOrNewerUiWatermarkWithoutDiagnostics() {
        val mutations:List<(Fixture)->Unit> = listOf({it.actor="other"},{it.page=Page.HOME},
            {it.surfaceWidth=999},{it.geometry=2},{it.owner=Any()})
        for(mutate in mutations) {
            val f=Fixture();f.controller.showStatus(f.status()){ "acquisition" };mutate(f);f.events.clear();f.drainUi()
            assertTrue(f.statusText.isEmpty());assertFalse(f.events.contains("diagnostic_parts"));assertFalse(f.events.contains("clock"))
        }
        val f=Fixture();f.controller.showStatus(f.status()){ "old" }
        f.controller.showStatus(f.status(elapsed=60_500)){ "new" };f.drainUi()
        assertEquals(1,f.statusWrites);assertTrue(f.detail.contains("new"));assertFalse(f.detail.contains("old"))
    }
    @Test fun numericStatusUsesOriginalDepthClockAndExpiryCannotRenewCaptureOrRawMeasurements() {
        val f=Fixture();val original=output()
        f.controller.showStatus(f.status(best=original)){"acquisition"};f.drainUi()
        assertTrue(f.detail.contains("1.25m"));assertTrue(f.detail.contains("median=1.40m"))
        assertEquals(1_201L,f.statusTasks.single().delay)
        val diagnostics=f.partsReads
        f.now=61_201;f.statusTasks.single().action()
        assertFalse(f.detail.contains("1.25m"));assertFalse(f.detail.contains("median=1.40m"))
        assertEquals(diagnostics,f.partsReads);assertEquals(1.25f,original.riskDistanceM!!,0f)
        assertEquals(10_000L,original.depthObservedAtMs)
        assertEquals("WalkSafe 상태. 객체별 depth 수신 중. ${f.detail}",f.description)
    }
    @Test fun numericStatusRechecksImmediatelyWhenSchedulingAlreadyMissedItsLastMillisecond() {
        val f=Fixture();f.clockValues.addAll(listOf(61_200L,61_201L))
        f.controller.showStatus(f.status()){ "acquisition" };f.drainUi()
        assertEquals(0L,f.statusTasks.single().delay)
        f.now=61_201;f.statusTasks.single().action()
        assertFalse(f.detail.contains("1.25m"))
    }
    @Test fun newerStatusOrIndependentWidgetEditMakesTheOldExpiryInert() {
        val mutations:List<(Fixture)->Unit> = listOf({it.owner=Any()},{it.statusText="permission warning"},
            {it.detail="other detail"},{it.description="other accessibility text"},{it.cameraText="other camera notice"})
        for(mutate in mutations) {
            val f=Fixture();f.controller.showStatus(f.status()){ "acquisition" };f.drainUi()
            mutate(f);f.events.clear();val writes=f.statusWrites;f.statusTasks.single().action()
            assertEquals(writes,f.statusWrites);assertFalse(f.events.contains("clock"))
        }
    }
    @Test fun noBestOutputKeepsOriginalUnsupportedStatusAndReadsNoMetricExpiryClock() {
        val f=Fixture();f.supported=false
        f.controller.showStatus(f.status(best=null)){ "acquisition" };f.events.clear();f.drainUi()
        assertEquals("Depth 미지원 fallback 대기",f.statusText)
        assertTrue(f.detail.startsWith("no detection\n"));assertTrue(f.detail.contains("delivery\nmotion\npacing\nvisual\nacquisition\ndetector\nunknown\nupload\nhistory"))
        assertFalse(f.events.contains("clock"));assertTrue(f.statusTasks.isEmpty());assertEquals(1,f.uploadRefreshes)
        assertTrue(f.events.indexOf("depth_supported")<f.events.indexOf("no_detection"))
        assertTrue(f.events.indexOf("no_detection")<f.events.indexOf("diagnostic_parts"))
    }
    @Test fun queuedLiveDistanceKeepsItsIndependentDisplayBudgetAfterTheRiskLeaseEnds() {
        val f=Fixture();f.metric=false
        f.controller.showStatus(f.status()){ "acquisition" }
        f.now=60_900;f.events.clear();f.drainUi()
        assertTrue(f.detail.contains("1.25m"));assertEquals(301L,f.statusTasks.single().delay)
        assertFalse(f.events.contains("metric_gate"))
        f.now=61_201;f.statusTasks.single().action();assertFalse(f.detail.contains("1.25m"))
    }
    @Test fun identicalPredictionAndActualDistanceDeadlinesPostOneRecheck() {
        val f=Fixture()
        val predicted=output().copy(prediction=PredictedDepthEstimate(1.1f,10_000,0,0.2f,1_200))
        f.showOverlay(f.overlay(predicted)){emptyList()};f.drainUi()
        assertEquals(1_201L,f.overlayTasks.single().delay)
    }
    @Test fun acquisitionFailureKeepsClaimedUiWatermarkAndOriginalExceptionWithoutQueuedTail() {
        val f=Fixture();val failure=IllegalStateException("provider diagnostic failure")
        try { f.controller.showStatus(f.status(elapsed=500)){throw failure};fail("cause must propagate") }
        catch(seen:IllegalStateException){assertSame(failure,seen)}
        assertTrue(f.ui.isEmpty());var retried=0
        f.controller.showStatus(f.status(elapsed=600)){retried++;"retry"}
        assertEquals(0,retried);assertTrue(f.ui.isEmpty())
        f.controller.resetUiThrottle()
        f.controller.showStatus(f.status(elapsed=600)){retried++;"retry"}
        assertEquals(1,retried)
    }
    @Test fun queuedRetiredStatusNeverQueriesLiveStartupDepthOrDetectorGateText() {
        val f=Fixture()
        f.controller.showStatus(f.status()){ "acquisition" }
        assertEquals(0,f.gateReads)
        f.owner=Any();f.drainUi()
        assertEquals(0,f.gateReads);assertEquals(0,f.partsReads)
        assertTrue(f.statusText.isEmpty())
    }
    @Test fun validQueuedStatusQueriesLiveGateOnceAtUiAdmissionAndKeepsThatCaptureAtExpiry() {
        val f=Fixture()
        f.controller.showStatus(f.status(best=null)){ "acquisition" }
        assertEquals(0,f.gateReads)
        f.gateValue="gate changed before UI consumption";f.events.clear();f.drainUi()
        assertEquals(1,f.gateReads);assertEquals(f.gateValue,f.receivedGateText)
        assertTrue(f.events.indexOf("gate_text")<f.events.indexOf("depth_supported"))
        assertTrue(f.events.indexOf("depth_supported")<f.events.indexOf("diagnostic_parts"))
        val numeric=Fixture()
        numeric.controller.showStatus(numeric.status()){ "acquisition" };numeric.drainUi()
        assertEquals(1,numeric.gateReads);val originalGate=numeric.receivedGateText
        numeric.gateValue="later state";numeric.now=61_201;numeric.statusTasks.single().action()
        assertEquals(1,numeric.gateReads);assertEquals(originalGate,numeric.receivedGateText)
    }
    @Test fun overlayTimestampGetterIsSkippedForBothRetiredGuardsAndThrottle() {
        val f=Fixture();val native=TimestampFrame()
        f.frameCurrent=false
        assertNull(f.controller.showOverlay(f.overlay(),{native.timestamp}){emptyList()})
        f.frameCurrent=true
        val throttled=f.overlay().copy(scope=f.scope(10),currentObservationPresent=false)
        assertSame(throttled.diagnostics,f.controller.showOverlay(throttled,{native.timestamp}){emptyList()})
        assertNull(f.controller.showOverlay(f.overlay(),{native.timestamp}){f.frameCurrent=false;emptyList()})
        assertEquals(0,native.timestampReads);assertTrue(f.ui.isEmpty())
    }
    @Test fun overlayTimestampGetterRunsOnceAfterMappingAndSecondGuardAndQueuesOnlyItsScalar() {
        val f=Fixture();val native=TimestampFrame();native.value=10_000_000_123L
        f.expectedVisualTimestampNs=native.value
        val predicted=output().copy(prediction=PredictedDepthEstimate(1.1f,10_000,0,0.2f,3_000))
        val timestampSupplier:()->Long={f.events+="timestamp_getter";native.timestamp}
        val result=f.controller.showOverlay(f.overlay(predicted),timestampSupplier){f.events+="map_boxes";emptyList()}
        assertNotNull(result);assertEquals(1,native.timestampReads)
        val getter=f.events.indexOf("timestamp_getter")
        assertTrue(getter>f.events.lastIndexOf("map_boxes"))
        assertEquals("frame_current",f.events[getter-1])
        val queued=f.ui.first()
        for(field in queued.javaClass.declaredFields) {
            field.isAccessible=true
            assertNotSame("UI closure must not retain the native getter",timestampSupplier,field.get(queued))
            assertNotSame("UI closure must not retain the native Frame",native,field.get(queued))
        }
        native.forbidTimestamp=true
        f.drainUi();assertEquals(1,native.timestampReads)
        f.now=61_201;f.overlayTasks.single{it.delay==1_201L}.action()
        assertEquals(1,native.timestampReads)
        assertEquals(59_000L,f.mapped.last().capturedAt)
    }
    @Test fun overlayTimestampGetterFailureKeepsClaimedThrottleButDoesNotPublishNewSourceOrUiTail() {
        val f=Fixture();f.showOverlay(f.overlay()){emptyList()}
        assertEquals(1,f.ui.size)
        val native=TimestampFrame();val failure=IllegalStateException("original overlay timestamp failure")
        native.failure=failure
        val newer=f.overlay().copy(scope=f.scope(60_200),sourceFrameId=10_001_000_000L,
            bestOutput=output().copy(frameId=10_001_000_000L))
        try {f.controller.showOverlay(newer,{native.timestamp}){emptyList()};fail("getter cause must propagate")}
        catch(seen:IllegalStateException){assertSame(failure,seen)}
        assertEquals(1,native.timestampReads);assertEquals(1,f.ui.size)
        f.drainUi();assertEquals(10_000_000_000L,f.mapped.single().frameId)
        val throttled=newer.copy(scope=f.scope(60_250),currentObservationPresent=false)
        native.forbidTimestamp=true
        assertSame(throttled.diagnostics,f.controller.showOverlay(throttled,{native.timestamp}){emptyList()})
        assertEquals(1,native.timestampReads)
    }
    @Test fun recognitionDisplayOffDoesNotPrepareOrQueueAnything() {
        val f=Fixture();var prepared=0
        f.controller.showRecognition(f.scope()){prepared++;recognitionFrame()}
        assertEquals(0,prepared);assertTrue(f.ui.isEmpty())
    }
    @Test fun recognitionDisplayReopenRejectsQueuedOldFrameAndResetsThrottle() {
        val f=Fixture();f.recognitionGeneration=1
        f.controller.showRecognition(f.scope()){recognitionFrame()}
        f.recognitionGeneration=2
        f.drainUi();assertTrue(f.recognitionFrames.isEmpty())
        f.controller.showRecognition(f.scope(60_010)){recognitionFrame()}
        f.drainUi();assertEquals(1,f.recognitionFrames.size)
        assertEquals(0,f.statusWrites);assertEquals(0,f.uploadRefreshes)
    }
    @Test fun recognitionDisplayThrottlesAndRejectsRetiredOrRotatedFrames() {
        val f=Fixture();f.recognitionGeneration=1;var prepared=0
        f.controller.showRecognition(f.scope()){prepared++;recognitionFrame()}
        f.controller.showRecognition(f.scope(60_249)){prepared++;recognitionFrame()}
        assertEquals(1,prepared)
        f.geometry=2;f.drainUi();assertTrue(f.recognitionFrames.isEmpty())
        f.controller.showRecognition(f.scope(60_250)){prepared++;recognitionFrame()}
        f.leaseCurrent=false;f.drainUi();assertTrue(f.recognitionFrames.isEmpty())
        assertEquals(2,prepared)
    }
    @Test fun recognitionPresentationFailureDoesNotEscapeIntoGuidance() {
        val f=Fixture();f.recognitionGeneration=1
        f.controller.showRecognition(f.scope()){throw IllegalArgumentException("retired transform")}
        assertTrue(f.ui.isEmpty())
        f.controller.showRecognition(f.scope(60_250)){recognitionFrame()}
        f.drainUi();assertEquals(1,f.recognitionFrames.size)
    }
    @Test fun recognitionDisplayRejectsQueuedResultsAfterCloseAccountPageOrViewportChange() {
        val mutations: List<(Fixture) -> Unit> = listOf(
            { it.recognitionGeneration = null }, { it.actor = "other" }, { it.account++ },
            { it.page = Page.HOME }, { it.surfaceWidth++ }, { it.frameCurrent = false },
        )
        for (mutate in mutations) {
            val f = Fixture(); f.recognitionGeneration = 1
            f.controller.showRecognition(f.scope()) { recognitionFrame() }
            mutate(f); f.drainUi()
            assertTrue(f.recognitionFrames.isEmpty())
        }
    }
    private fun recognitionFrame() = kr.co.hanium.dreamup.walksafe.ui.GuidanceRecognitionDisplay.Frame(60_000,0,emptyList(),"")

    private class TimestampFrame:Frame() {
        var value=10_000_000_000L;var timestampReads=0;var forbidTimestamp=false;var failure:RuntimeException?=null
        override fun getTimestamp():Long {
            timestampReads++;check(!forbidTimestamp){"Overlay must not query native timestamp from a queued callback"}
            failure?.let{throw it};return value
        }
    }
    private enum class Page { HOME,GUIDANCE }
    private class Lease
    private data class Task(val delay:Long,val action:()->Unit)
    private data class Mapped(val frameId:Long,val capturedAt:Long)
    private class Fixture {
        val lock=Any();val epoch=WalkRuntimeEpoch("walk",7L);val lease=Lease()
        var recognitionGeneration:Long?=null
        val recognitionFrames=mutableListOf<kr.co.hanium.dreamup.walksafe.ui.GuidanceRecognitionDisplay.Frame>()
        var frameCurrent=true;var leaseCurrent=true;var visual=true;var metric=true;var visible=true
        var expectedVisualTimestampNs=10_000_000_000L
        var actor:String?="actor";var account=20L;var page=Page.GUIDANCE;var geometry=1L
        var surfaceWidth=640;var surfaceHeight=480;var overlayWidth=640;var overlayHeight=480
        var owner:Any=Any();var cameraText="";var statusText="";var detail="";var description:String?=null
        var now=60_000L;var supported=true;var clears=0;var statusWrites=0;var partsReads=0;var uploadRefreshes=0
        var gateReads=0;var gateValue="gate";var receivedGateText:String?=null
        val events=mutableListOf<String>();val ui=ArrayDeque<()->Unit>();val clockValues=ArrayDeque<Long>()
        val overlayTasks=mutableListOf<Task>();val cameraTasks=mutableListOf<Task>();val statusTasks=mutableListOf<Task>()
        val mapped=mutableListOf<Mapped>()
        private val source=object:FramePresentationSource<Lease> {
            override fun ownsFrame(frameGeneration:Int,epoch:WalkRuntimeEpoch)=frameCurrent.also{events+="frame_current"}
            override fun ownsLease(lease:Lease)= (leaseCurrent&&lease===this@Fixture.lease).also{events+="lease_current"}
            override fun metricAllowsWork(arGeneration:Long)=metric.also{assertEquals(8L,arGeneration);events+="metric_gate"}
            override fun visualDepthAllowsWork(lease:Lease,frameTimestampNs:Long,tracking:Boolean)=visual.also{
                assertSame(this@Fixture.lease,lease);assertEquals(expectedVisualTimestampNs,frameTimestampNs);assertTrue(tracking);events+="visual_gate"}
        }
        private val context=object:FramePresentationContext {
            override val recognitionDisplayGeneration get()=recognitionGeneration
            override val cameraVisible get()=visible.also{events+="camera_visible"}
            override val surfaceWidth get()=this@Fixture.surfaceWidth
            override val surfaceHeight get()=this@Fixture.surfaceHeight
            override val statusOwner get()=owner.also{events+="status_owner"}
            override fun captureStatusContext():FrameStatusUiContext {events+="capture_context";return FrameStatusUiContext(actor,account,page,visible,surfaceWidth,surfaceHeight,geometry)}
            override fun isStatusContextCurrent(context:FrameStatusUiContext)=actor==context.actorId&&account==context.accountGeneration&&page==context.page&&visible==context.cameraVisible&&surfaceWidth==context.viewportWidth&&surfaceHeight==context.viewportHeight
            override fun geometryVersion()=geometry.also{assertTrue(Thread.holdsLock(lock));events+="geometry"}
        }
        private val view=object:FramePresentationView {
            override fun updateRecognition(frame:kr.co.hanium.dreamup.walksafe.ui.GuidanceRecognitionDisplay.Frame, context:FrameStatusUiContext){recognitionFrames+=frame}
            override val overlayWidth get()=this@Fixture.overlayWidth.also{events+="overlay_width"}
            override val overlayHeight get()=this@Fixture.overlayHeight.also{events+="overlay_height"}
            override var cameraStatus:String get()=cameraText;set(value){cameraText=value;events+="camera_write"}
            override val statusLabel get()=statusText
            override val detailText get()=detail
            override val statusDescription get()=description
            override fun updateStatus(status:String,detail:String){statusWrites++;owner=Any();statusText=status;this@Fixture.detail=detail;description="WalkSafe 상태. $status. $detail";if(visible)cameraText="$status\n$detail";events+="status_write"}
            override fun clearOverlay(){clears++}
            override fun updateMapped(boxes:List<FrameMappedDebugBox>,sourceFrameId:Long,sourceCapturedAt:Long){mapped+=Mapped(sourceFrameId,sourceCapturedAt)}
            override fun onUi(action:()->Unit){ui.addLast(action)}
            override fun postOverlay(delayMs:Long,action:()->Unit){overlayTasks+=Task(delayMs,action)}
            override fun postCameraStatus(delayMs:Long,action:()->Unit){cameraTasks+=Task(delayMs,action)}
            override fun postStatus(delayMs:Long,action:()->Unit){statusTasks+=Task(delayMs,action)}
            override fun updateDebugUploadButton(){uploadRefreshes++}
        }
        private val diagnostics=object:FramePresentationDiagnostics {
            override fun depthSupported()=supported.also{events+="depth_supported"}
            override fun noDetectionStatus(snapshot:DepthFrameSnapshot)="no detection".also{events+="no_detection"}
            override fun statusParts(gateText:String)=FrameStatusDiagnosticParts("delivery","motion","pacing","visual","detector","unknown","upload","history").also{assertEquals(gateValue,gateText);receivedGateText=gateText;partsReads++;events+="diagnostic_parts"}
            override fun gapParts()=FrameGapDiagnosticParts("visual","unknown")
        }
        val controller=FramePresentationController(lock,source,context,view,diagnostics,{events+="clock";if(clockValues.isEmpty())now else clockValues.removeFirst()},100L,500L,1_200L,1_200L)
        fun scope(elapsed:Long=60_000L)=FramePresentationScope(1,epoch,lease,8L,elapsed,10_000_000_000L,true)
        fun showOverlay(input:FrameOverlayPresentationInput<Lease>,mapBoxes:(TrackedObjectDepth?)->List<FrameMappedDebugBox>)=
            controller.showOverlay(input,{10_000_000_000L},mapBoxes)
        fun unavailable()=FrameUnavailableProjection(emptyList(),5L,59_000,true,"camera unavailable")
        fun overlay(best:TrackedObjectDepth=output())=FrameOverlayPresentationInput(scope(),true,best.frameId,59_000,60_000,best,
            FrameOverlayDiagnostics("tracked",1,0,holdApplied=false,snapshotPartial=false,sourceAgeMs=null,frameDeltaMs=null,staleReason=null),1)
        fun status(best:TrackedObjectDepth?=output(),elapsed:Long=60_000L)=FrameStatusPresentationInput(scope(elapsed),best,
            DepthFrameSnapshot(10_000_000_000L,null,null,null),DetectionSnapshot.empty(),10_000,60_000,60_000,{gateReads++;events+="gate_text";gateValue})
        fun drainUi(){while(ui.isNotEmpty())ui.removeFirst()()}
    }
    companion object {
        private fun output()=TrackedObjectDepth(10_000_000_000L,10_000,"original-depth","person",0.9f,
            RectNorm(0.1f,0.2f,0.2f,0.5f),emptyList(),0.1f,Point2(0.2f,0.45f),null,DepthSource.ARCORE_RAW_DEPTH,
            1.4f,2f,0.6f,1.25f,80,0.9f,1.4f,1.25f,0.05f,Trend.STABLE,0f,null,null,
            DepthConfidenceBreakdown(0.95f,1f,1f,0.9f,1f,1f,1f,1f),UserFacingDepth(null,MessageLevel.NONE,null),
            depthObservedAtMs=10_000,sourceObservedAtElapsedRealtimeMs=60_000,sourceMaxAgeMs=800,
            sourceValidUntilElapsedRealtimeMs=60_800)
    }
}
