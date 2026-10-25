package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import android.media.CameraTimestampTestImage
import android.media.FramePipelineTestImage
import android.media.Image
import com.google.ar.core.*
import com.google.ar.core.CameraIntrinsics
import java.nio.ByteBuffer
import kr.co.hanium.dreamup.walksafe.MetadataCaptureLogEntry
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.device.DeviceGateState
import kr.co.hanium.dreamup.walksafe.feedback.TactileDotReferenceDeliveryOwner
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.delivery.FeedbackRejectionProducerScope
import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeAttachment
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Real frame pipeline/provider/tracker/coordinator, with SDK and external owner ports doubled. */
class FramePipelineControllerTest {
    @Test fun constructionDoesNotReadAnOwnerSdkFrameOrClock() {
        val f = Fixture()
        assertTrue(f.trace.isEmpty())
        assertEquals(0, f.frame.timestampReads)
    }

    @Test fun startupRejectionDoesNotRenewRuntimeEvidenceOrScheduleDetectors() {
        val f = Fixture(); f.startup = false
        f.draw()
        assertTrue(f.trace.contains("revoke"))
        assertFalse(f.trace.contains("metric"))
        assertFalse(f.trace.contains("unknown"))
        assertFalse(f.trace.contains("primary"))
        assertEquals(1, f.frame.rgb.closes)
    }

    @Test fun missingWalkEpochRevokesOutputWithoutClaimingTheCameraFrame() {
        val f = Fixture(); f.epochPresent = false
        f.draw()
        assertTrue(f.trace.contains("revoke"))
        assertFalse(f.trace.contains("metric"))
        f.epochPresent = true; f.frame.rgb = FramePipelineTestImage(900_000_000L)
        f.draw()
        assertTrue(f.trace.contains("primary"))
    }

    @Test fun repeatedSourceStillChecksMetricLossButCannotRenewDetectionOrMetadata() {
        val f = Fixture(); f.draw()
        val first = f.trace.count { it == "metadata" }
        f.trace.clear(); f.frame.rgb = FramePipelineTestImage(900_000_000L)
        f.draw()
        assertTrue(f.trace.contains("metric"))
        assertFalse(f.trace.contains("fps"))
        assertFalse(f.trace.contains("unknown"))
        assertFalse(f.trace.contains("primary"))
        assertEquals(1, first)
        assertFalse(f.trace.contains("metadata"))
    }

    @Test fun metricRetirementIsCheckedBeforeReadingTheDuplicateAdmissionTimestamp() {
        val f = Fixture(); f.onMetric = { f.current = false; f.frame.rejectTimestamp = true }
        f.draw()
        assertFalse(f.trace.contains("fps"))
        assertFalse(f.trace.contains("primary"))
        assertFalse(f.trace.contains("metadata"))
    }

    @Test fun unknownAndPrimaryReceiveTheOriginalSameClosedImageAndCaptureTimes() {
        val f = Fixture(); f.draw()
        assertTrue(f.trace.indexOf("metric") < f.trace.indexOf("unknown"))
        assertTrue(f.trace.indexOf("unknown") < f.trace.indexOf("primary"))
        assertSame(f.frame, f.unknownNative)
        assertSame(f.frame, f.primaryNative)
        assertSame(f.unknownInput, f.primaryInput)
        assertSame(f.unknownSnapshot, f.primarySnapshot)
        assertEquals(1_000L, f.primaryTimestampMs)
        assertEquals(7_000L, f.primaryWallMs)
        assertEquals(100_000L, f.primaryReceiptMs)
        assertEquals(1, f.frame.rgb.closes)
    }

    @Test fun lostVisualReadinessRecordsFailureButDoesNotEvaluateTactileOrPublishRisk() {
        val f = Fixture(); f.visual = false; f.cameraVisible = false
        f.draw()
        assertTrue(f.trace.contains("acquisition"))
        assertFalse(f.trace.contains("frozen"))
        assertFalse(f.trace.contains("coordinator"))
        assertFalse(f.trace.contains("consume"))
        assertFalse(f.trace.contains("report"))
    }

    @Test fun staleConsumedResultCannotReachOverlayTelemetryReportOrFeedback() {
        val f = Fixture(); f.onSource = { f.current = false }
        f.draw()
        assertTrue(f.trace.contains("consume"))
        assertFalse(f.trace.contains("crosswalk"))
        assertFalse(f.trace.contains("metadata"))
        assertFalse(f.trace.contains("report"))
        assertFalse(f.trace.contains("dispatch"))
    }

    @Test fun metricPublicationKeepsMetadataTelemetryReportAndFeedbackOrder() {
        val f = Fixture(); f.draw()
        val steps = listOf("metadata", "upload", "gate", "location", "heartbeat", "telemetry", "report", "remember", "dispatch", "dot")
        val positions = steps.map { f.trace.indexOf(it) }
        assertTrue("Missing steps: ${f.trace}", positions.all { it >= 0 })
        assertTrue("Changed publication order: ${f.trace}", positions.zipWithNext().all { (a,b) -> a < b })
        assertSame(f.result, f.remembered)
        assertSame(f.result, f.dispatched)
        assertSame(f.receipt, f.dispatchedReceipt)
        assertSame(f.sourceOwner, f.dotOwner)
        assertEquals(f.epoch, requireNotNull(f.report).epoch)
        assertSame(f.primarySnapshot, f.audit?.depth)
    }

    @Test fun reportRejectionTerminatesDeliveryWithoutDiscardingTheOriginalMetadata() {
        val f = Fixture(); f.reportAllowed = false; f.draw()
        assertTrue(f.trace.contains("metadata"))
        assertTrue(f.trace.contains("report"))
        assertFalse(f.trace.contains("remember"))
        assertFalse(f.trace.contains("dispatch"))
        assertFalse(f.trace.contains("dot"))
    }

    @Test fun lossAfterReportPublicationStopsFeedbackDelivery() {
        val f = Fixture(); f.onReport = { f.metric = false }; f.draw()
        assertTrue(f.trace.contains("report"))
        assertFalse(f.trace.contains("remember"))
        assertFalse(f.trace.contains("dispatch"))
    }

    @Test fun depthGapUsesQualitativeDeliveryWithoutMetricReportAndKeepsOriginalFrameScalarForLateUi() {
        val f = Fixture(); f.metric = false; f.cameraVisible = true; f.frame.tracking = true; f.draw()
        assertFalse(f.trace.contains("frozen"))
        assertFalse(f.trace.contains("metadata"))
        assertFalse(f.trace.contains("report"))
        assertTrue(f.qualitativeMode)
        assertTrue(f.trace.contains("dispatch"))
        assertEquals(f.remembered, f.dispatched)
        assertNotSame(f.remembered, f.dispatched)
        val original = f.frame.timeNs
        f.frame.timeNs = 9_000_000_000L; f.frame.rejectTimestamp = true
        f.drainUi()
        assertTrue(f.cameraStatus.contains("Depth 일시 공백"))
        assertEquals(original, f.lastVisualTimestamp)
    }

    @Test fun normalStatusReadsLiveGateTextOnlyAfterItsQueuedCurrentnessFence() {
        val f = Fixture(); f.draw()
        assertEquals(0, f.gateTextReads)
        f.frame.rejectTimestamp = true; f.drainUi()
        assertEquals(1, f.gateTextReads)
        val other = Fixture(); other.draw(); other.current = false; other.drainUi()
        assertEquals(0, other.gateTextReads)
    }

    private class Fixture {
        val trace = mutableListOf<String>(); val ui = ArrayDeque<() -> Unit>()
        val epoch = WalkRuntimeEpoch("walk", 7L); val lease = Any(); val receipt = Any(); val lock = Any()
        var current = true; var metric = true; var visual = true; var startup = true; var epochPresent = true
        var cameraVisible = false; var reportAllowed = true; var onMetric: () -> Unit = {}; var onSource: () -> Unit = {}; var onReport: () -> Unit = {}
        var gateTextReads = 0; var lastVisualTimestamp: Long? = null
        var unknownNative: Frame? = null; var primaryNative: Frame? = null
        var unknownInput: FrameRecognitionInput? = null; var primaryInput: FrameRecognitionInput? = null
        var unknownSnapshot: DepthFrameSnapshot? = null; var primarySnapshot: DepthFrameSnapshot? = null
        var primaryTimestampMs = 0L; var primaryWallMs = 0L; var primaryReceiptMs = 0L
        var sourceOwner: TactileDotReferenceDeliveryOwner? = null; var dotOwner: TactileDotReferenceDeliveryOwner? = null
        var result: TactileSnapshotFrameResult? = null; var remembered: TactileSnapshotFrameResult? = null
        var dispatched: TactileSnapshotFrameResult? = null; var dispatchedReceipt: Any? = null; var qualitativeMode = false
        var report: FrameReportPublication? = null; var audit: FrameCaptureAudit? = null
        var cameraStatus = ""; var statusLabel = ""; var detail = ""; var statusOwner = Any()
        val frame = FakeFrame()
        val session = object : Session() {
            override fun update(): Frame { trace += "update"; return frame }
            override fun <T: Trackable> getAllTrackables(type: Class<T>): Collection<T> = emptyList()
            override fun createAnchor(pose: Pose): Anchor = object : Anchor() {
                override fun getPose() = pose
                override fun getTrackingState() = TrackingState.TRACKING
                override fun detach() {}
            }
        }
        val provider = ArCoreFrameProvider(session)
        val clock = object : ArFrameClock {
            override fun elapsedRealtimeMs(): Long { trace += "clock"; return 100_000L }
            override fun wallClockMs(): Long { trace += "wall"; return 7_000L }
        }
        val recognition = ArFrameRecognitionCapture(lock, object : FrameRecognitionAuthority {
            override fun obstacleFeatureEnabled() = true
            override fun ownsFrame(generation: Int, epoch: WalkRuntimeEpoch) = current
            override fun detectorAvailable() = true
            override fun geometryVersion() = 0L
            override fun unknownService(): FastSamRuntimeService<UnknownRuntimeAttachment>? = null
            override fun cameraQualityObservation(epoch: WalkRuntimeEpoch, observedAtElapsedRealtimeMs: Long, frameAvailable: Boolean,
                width: Int, height: Int, yPlaneBuffer: ByteBuffer?, yPlaneRowStride: Int?, yPlanePixelStride: Int?) =
                CameraFrameQualityObservation(epoch, observedAtElapsedRealtimeMs, frameAvailable, .5, 0.0, null, null)
        }, false, clock::elapsedRealtimeMs, {})
        val synthesis = FrameSynthesisController(lock, object : FrameSynthesisAuthority {
            override fun ownsFrame(generation: Int, epoch: WalkRuntimeEpoch): Boolean { assertTrue(Thread.holdsLock(lock)); return current }
            override fun userStepLengthM() = .65f
            override fun retireUnstartedFeedback(trackPrefix: String) {}
        }, clock::elapsedRealtimeMs, InterFrameDetectionTracker(VisualTrackingConfig(backend = VisualTrackingBackend.PATCH_DIAGNOSTIC)))
        val runtime = object : FrameRuntimePeer<Any, Boolean> {
            override fun runtimePurpose(purpose: Boolean) = purpose
            override fun startupConfirmed() = startup
            override fun runtimeEpochOrNull() = epoch.takeIf { epochPresent }
            override fun revokeMetricOutput() { trace += "revoke" }
            override fun updateMetricOutput(capture: ScopedArFrameCapture<Any, Boolean>, epoch: WalkRuntimeEpoch) { trace += "metric"; onMetric() }
            override fun ownsFrame(generation: Int, epoch: WalkRuntimeEpoch) = current
            override fun leaseIsCurrent(lease: Any): Boolean { assertSame(this@Fixture.lease, lease); return current }
            override fun metricOutputAllowsWork(arGeneration: Long) = metric
            override fun visualOutputAllowsWork(lease: Any, frameTimestampNs: Long, tracking: Boolean): Boolean {
                assertSame(this@Fixture.lease, lease); lastVisualTimestamp = frameTimestampNs; return visual
            }
            override fun expectedCameraFps(lease: Any): Int { assertSame(this@Fixture.lease, lease); trace += "fps"; return 30 }
            override fun observeRuntimeFrame(expectedFps: Int, tracking: () -> Boolean) { if (metric) tracking(); trace += "observed" }
            override fun recordTrackingLoad(snapshot: DepthFrameSnapshot, frameTimestampNs: () -> Long, depthProcessingMs: Long,
                trackingStartedMs: Long, hasGray: Boolean, trackingBudgetNs: () -> Long) { if (snapshot.cameraImageTimestampNs == null) frameTimestampNs(); trace += "tracking" }
            override fun requestEarlyDetection(atElapsedRealtimeMs: Long) { trace += "early" }
        }
        val coordinator = AndroidTactileFrameCoordinator(AndroidTactileRouteGuidance(), WalkSafeFeedbackPolicy(), TactileFrameFeedbackActuator {})
        val tactile = object : FrameTactileResultPeer<Any, Any, Any> {
            override fun coordinator(): AndroidTactileFrameCoordinator { trace += "coordinator"; return this@Fixture.coordinator }
            override fun prepareFrozen(snapshot: DetectionSnapshot, wallClockMs: Long, frameTimestampMs: Long, decisionElapsedMs: Long,
                processDepth: (DetectionFrameEvidence, List<DetectionCandidate>) -> List<TrackedObjectDepth>): PreparedTactileFrameDispatch {
                trace += "frozen"
                val frozen = this@Fixture.coordinator.processSnapshot(snapshot, wallClockMs, frameTimestampMs, 800L, 800L,
                    snapshot.sourceAgeMs(wallClockMs), false, false, null, decisionElapsedRealtimeMs = decisionElapsedMs, processDepth = processDepth)
                return object : PreparedTactileFrameDispatch {
                    override val frame = frozen
                    override fun dispatchFeedback(stale: Boolean, deviceGateAllowsAlerts: Boolean, nowMs: Long) =
                        this@Fixture.coordinator.dispatchFeedback(frame, stale, deviceGateAllowsAlerts, nowMs)
                }
            }
            override fun recordFrozen(snapshot: DetectionSnapshot, outputs: List<TrackedObjectDepth>) { trace += "recordFrozen" }
            override fun currentSource(epoch: WalkRuntimeEpoch, generation: Int, arGeneration: Long, frameTimestampNs: () -> Long,
                capturedElapsedMs: Long, cameraTimestampNs: Long?, geometryVersion: () -> Long): FrameTactileSource<Any> {
                assertTrue(Thread.holdsLock(lock))
                val owner = TactileDotReferenceDeliveryOwner(epoch, 1, generation, arGeneration); sourceOwner = owner
                assertEquals(frame.timeNs, frameTimestampNs()); val geometry = geometryVersion(); onSource()
                return FrameTactileSource(Any(), owner, geometry, null, null)
            }
            override fun feedbackLifecycleGeneration() = 1
            override fun consume(source: Any, frame: TactileSnapshotFrameResult, admissionIsCurrent: () -> Boolean,
                accepted: (TactileSnapshotFrameResult, Any) -> Unit): Boolean {
                trace += "consume"; if (!admissionIsCurrent()) return false
                result = frame; accepted(frame, receipt); return true
            }
            override fun publishReference(frame: TactileSnapshotFrameResult, owner: TactileDotReferenceDeliveryOwner, receipt: Any) = current
            override fun resultIsCurrent(receipt: Any) = current
            override fun consumeCurrent(receipt: Any, block: () -> Unit) { if (current) block() }
            override fun crosswalkReference(owner: TactileDotReferenceDeliveryOwner, lease: Any, current: () -> Boolean) { trace += "crosswalk" }
            override fun dotReference(owner: TactileDotReferenceDeliveryOwner, lease: Any, current: () -> Boolean) { dotOwner = owner; trace += "dot" }
            override fun dispatchFeedback(receipt: Any, frame: TactileSnapshotFrameResult, stale: Boolean, deviceGateAllowsAlerts: Boolean,
                nowMs: Long, actuator: TactileFrameFeedbackActuator, independentOutputs: List<UnknownObjectFeedbackBatch>, warningContext: WarningRegionContext?) {
                dispatched = frame; dispatchedReceipt = receipt; trace += "dispatch"
            }
        }
        val risk = object : FrameRiskOutputPeer<Any> {
            override fun freshIndependentOutputs(nowElapsedMs: Long) = emptyList<UnknownObjectFeedbackBatch>()
            override fun deviceGate(bestOutput: TrackedObjectDepth?, staleReason: String?, arGeneration: Long,
                qualitativeOutput: TrackedObjectDepth?, qualitativeRuntimeReady: Boolean): DeviceGateState { trace += "gate"; return DeviceGateState() }
            override fun currentAlertsAllowed() = true
            override fun gateText(gate: DeviceGateState): String { gateTextReads++; return "gate" }
            override fun qualitativeAlertsAllowed(nowElapsedMs: Long) = true
            override fun emitFeedback(dispatch: TactileFrameFeedbackDispatch, scope: FeedbackRejectionProducerScope,
                startIsCurrent: () -> Boolean, producerIsCurrent: () -> Boolean) { trace += "emit" }
            override fun rememberFrame(frame: TactileSnapshotFrameResult, stale: Boolean, qualitative: Boolean, producerIsCurrent: () -> Boolean,
                alertsAllowed: (Long) -> Boolean, receipt: Any, warningContext: WarningRegionContext?, actuator: TactileFrameFeedbackActuator?,
                delivery: (TactileFrameFeedbackDispatch, () -> Boolean) -> Unit) { remembered = frame; qualitativeMode = qualitative; trace += "remember" }
        }
        val recording = object : FrameRecordingPeer {
            override fun acquisitionFailures(provider: ArCoreFrameProvider, capturedElapsedMs: Long) { trace += "acquisition" }
            override fun buildCaptureEntry(audit: FrameCaptureAudit): MetadataCaptureLogEntry {
                this@Fixture.audit = audit; trace += "entry"
                return MetadataCaptureLogEntry(frameTimestampMs = audit.timestampMs, detectorFrameTimestampMs = null, detectorAgeMs = null,
                    detectionCount = 0, detectionsUsedForDepth = false, staleReason = null, topDetectionClassName = null,
                    topDetectionConfidence = null, topDetectionBbox = null, bestDepthClassName = null, bestDepthSource = null,
                    bestDepthMedianM = null, bestDepthValidSampleCount = null, bestDepthValidSampleRatio = null, bestDepthBbox = null)
            }
            override fun appendMetadata(entry: MetadataCaptureLogEntry) { assertTrue(Thread.holdsLock(lock)); trace += "metadata" }
            override fun enqueueMetadataIfOriginalUploadAllowed(entry: MetadataCaptureLogEntry) { assertTrue(Thread.holdsLock(lock)); trace += "upload" }
            override fun freshLocation(): TrustedLocation? { trace += "location"; return null }
            override fun frameProduced() { assertTrue(Thread.holdsLock(lock)); trace += "heartbeat" }
            override fun appendTelemetry(entry: MetadataCaptureLogEntry, alertsAllowed: Boolean, reportsAllowed: Boolean,
                freshLocation: TrustedLocation?, locationAgeMs: Long?) { assertTrue(Thread.holdsLock(lock)); trace += "telemetry" }
        }
        val reports = FrameReportPublicationPeer { report = it; trace += "report"; onReport(); reportAllowed }
        val presentation = FramePresentationController(lock, object : FramePresentationSource<Any> {
            override fun ownsFrame(frameGeneration: Int, epoch: WalkRuntimeEpoch) = current
            override fun ownsLease(lease: Any) = current
            override fun metricAllowsWork(arGeneration: Long) = metric
            override fun visualDepthAllowsWork(lease: Any, frameTimestampNs: Long, tracking: Boolean) = runtime.visualOutputAllowsWork(lease, frameTimestampNs, tracking)
        }, object : FramePresentationContext {
            override val cameraVisible get() = this@Fixture.cameraVisible
            override val surfaceWidth = 2; override val surfaceHeight = 2
            override val statusOwner get() = this@Fixture.statusOwner
            override fun captureStatusContext() = FrameStatusUiContext("user", 1L, TrackingState.PAUSED, cameraVisible, 2, 2, 0L)
            override fun isStatusContextCurrent(context: FrameStatusUiContext) = current
            override fun geometryVersion() = 0L
        }, object : FramePresentationView {
            override val overlayWidth = 2; override val overlayHeight = 2
            override var cameraStatus: String
                get() = this@Fixture.cameraStatus
                set(value) { this@Fixture.cameraStatus = value }
            override val statusLabel get() = this@Fixture.statusLabel
            override val detailText get() = detail
            override val statusDescription get() = "WalkSafe 상태. $statusLabel. $detail"
            override fun updateStatus(status: String, detail: String) { this@Fixture.statusLabel = status; this@Fixture.detail = detail; this@Fixture.statusOwner = Any() }
            override fun clearOverlay() { trace += "clearOverlay" }
            override fun updateMapped(boxes: List<FrameMappedDebugBox>, sourceFrameId: Long, sourceCapturedAt: Long) { trace += "boxes" }
            override fun onUi(action: () -> Unit) { ui += action }
            override fun postOverlay(delayMs: Long, action: () -> Unit) { ui += action }
            override fun postCameraStatus(delayMs: Long, action: () -> Unit) { ui += action }
            override fun postStatus(delayMs: Long, action: () -> Unit) { ui += action }
            override fun updateDebugUploadButton() {}
        }, object : FramePresentationDiagnostics {
            override fun depthSupported() = true
            override fun noDetectionStatus(snapshot: DepthFrameSnapshot) = "none"
            override fun statusParts(gateText: String) = FrameStatusDiagnosticParts(gateText, "motion", "pacing", "visual", "detector", "unknown", "upload", "history")
            override fun gapParts() = FrameGapDiagnosticParts("visual", "unknown")
        }, clock::elapsedRealtimeMs, 100L, 100L, 1_200L, 1_200L)
        val render = ArFrameRenderController(lock, object : ArFrameRenderSource<Any, Boolean> {
            override fun foreground() = true
            override fun frameGeneration() = 4
            override fun acquireLease() = FrameArResources(lease, session, provider, true, 5L)
            override fun bindTextureIfReady() {}
            override fun textureBound() = true
            override fun leaseIsCurrent(lease: Any) = true
            override fun currentEpoch() = epoch
            override fun isPreflight(purpose: Boolean) = false
            override fun isRuntime(purpose: Boolean) = true
        }, object : ArFrameRenderDemand {
            override fun normalDetectionDue(nowElapsedRealtimeMs: Long) = false
            override fun unknownCaptureDue(nowElapsedRealtimeMs: Long) = false
            override fun freshVisualSource(nowElapsedRealtimeMs: Long) = false
        }, object : ArFrameGraphics {
            override fun clear() { trace += "clear" }
            override fun drawBackground(frame: Frame) { assertSame(this@Fixture.frame, frame); trace += "background" }
        }, recognition, clock, object : ArFrameRenderObserver<Any, Boolean> {
            override fun onRenderCallback() { trace += "renderHeartbeat" }
            override fun geometryChanged(lease: Any, frameGeneration: Int, epoch: WalkRuntimeEpoch) { error("Unchanged geometry") }
            override fun updateFailed(status: String, detail: String, purpose: Boolean, arGeneration: Long, epoch: WalkRuntimeEpoch,
                diagnosticKind: String, error: Throwable) { throw AssertionError(error) }
            override fun diagnoseRuntimeCapture(capture: ScopedArFrameCapture<Any, Boolean>) {}
            override fun processPreflight(capture: ScopedArFrameCapture<Any, Boolean>) { error("Runtime source") }
        })
        val publication = FramePublicationController(lock, runtime::ownsFrame, runtime::metricOutputAllowsWork,
            runtime::visualOutputAllowsWork, tactile, risk, recording, reports, { presentation }, clock)
        val pipeline = FramePipelineController(lock, render, runtime, object : FrameDetectionSchedulingPeer<Any> {
            override fun scheduleUnknown(input: FrameRecognitionInput?, frame: Frame, snapshot: DepthFrameSnapshot, capturedElapsedMs: Long,
                generation: Int, epoch: WalkRuntimeEpoch, lease: Any) {
                assertEquals(1, this@Fixture.frame.rgb.closes); unknownNative = frame; unknownInput = input; unknownSnapshot = snapshot; trace += "unknown"
            }
            override fun scheduleNamed(input: FrameRecognitionInput?, lease: Any, frame: Frame, timestampMs: Long, wallClockMs: Long,
                capturedElapsedMs: Long, snapshot: DepthFrameSnapshot, generation: Int, epoch: WalkRuntimeEpoch, arGeneration: Long, gray: GrayTrackingFrame?) {
                primaryNative = frame; primaryInput = input; primarySnapshot = snapshot; primaryTimestampMs = timestampMs
                primaryWallMs = wallClockMs; primaryReceiptMs = capturedElapsedMs; trace += "primary"
            }
        }, synthesis, ArFrameGeometryFreezer(clock::elapsedRealtimeMs, { ImageSize(2,2) }, { "percent" }), object : FrameRouteEvidencePeer {
            override fun projectionContext(frame: Frame, capturedElapsedMs: Long): TactileProjectionContext { error("No tracked source") }
            override fun motionContext(capturedElapsedMs: Long): MotionContext { error("No tracked source") }
            override fun navigationActive() = false
            override fun tmapOnRoute() = false
        }, tactile, risk, recording, { presentation }, publication, { "none" }, clock)
        fun draw() = pipeline.draw()
        fun drainUi() { var count = 0; while (ui.isNotEmpty()) { check(count++ < 10); ui.removeFirst().invoke() } }
    }

    private class FakeFrame : Frame() {
        var timeNs = 1_000_000_000L; var timestampReads = 0; var rejectTimestamp = false
        var rgb = FramePipelineTestImage(900_000_000L)
        var tracking = false
        private val raw = CameraTimestampTestImage(900_000_000L, 2)
        private val confidence = CameraTimestampTestImage(900_000_000L, 1)
        private val cameraValue = object : Camera() {
            override fun getTrackingState() = if (tracking) TrackingState.TRACKING else TrackingState.PAUSED
            override fun getPose() = Pose.IDENTITY
            override fun getImageIntrinsics(): CameraIntrinsics = object : CameraIntrinsics() {
                override fun getFocalLength() = floatArrayOf(500f,500f)
                override fun getPrincipalPoint() = floatArrayOf(1f,1f)
                override fun getImageDimensions() = intArrayOf(2,2)
            }
        }
        override fun getTimestamp(): Long { check(!rejectTimestamp) { "SDK frame is retired" }; timestampReads++; return timeNs }
        override fun getCamera(): Camera = cameraValue
        override fun hasDisplayGeometryChanged() = false
        override fun acquireCameraImage(): Image = rgb
        override fun acquireRawDepthImage16Bits(): Image = raw
        override fun acquireRawDepthConfidenceImage(): Image = confidence
        override fun acquireDepthImage16Bits(): Image { error("Full depth unavailable") }
        override fun transformCoordinates2d(input: Coordinates2d, values: FloatArray, output: Coordinates2d, destination: FloatArray) { values.copyInto(destination) }
    }
}
