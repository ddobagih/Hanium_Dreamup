package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.DepthPredictionPresentation
import kr.co.hanium.dreamup.walksafe.depth.PredictedDepthEstimate
import kr.co.hanium.dreamup.walksafe.depth.ProximityDepthDisplay
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Owns presentation watermarks and per-render TTL callbacks, never native frame acquisition. */
internal class FramePresentationController<L : Any>(
    private val frameStateLock: Any,
    private val source: FramePresentationSource<L>,
    private val context: FramePresentationContext,
    private val view: FramePresentationView,
    private val diagnostics: FramePresentationDiagnostics,
    private val clock: () -> Long,
    private val overlayIntervalMs: Long,
    private val statusIntervalMs: Long,
    private val maximumOverlaySourceAgeMs: Long,
    private val maximumOverlayFrameDeltaMs: Long,
) {
    private var lastCameraScreenFrameUiMs = 0L
    private var lastOverlayUpdateMs = 0L
    private var lastUiUpdateMs = 0L
    private var latestVisualOverlayFrameId = 0L

    fun resetUiThrottle() = synchronized(frameStateLock) { lastUiUpdateMs = 0L }
    fun resetOverlayThrottle() = synchronized(frameStateLock) { lastOverlayUpdateMs = 0L }
    fun retireOverlayFrame() = synchronized(frameStateLock) { latestVisualOverlayFrameId = 0L }
    fun readCameraFrameUiMarker() = lastCameraScreenFrameUiMs

    /** prepare may borrow native producer state synchronously; it is never retained or queued. */
    fun showUnavailable(elapsedMs: Long, @Suppress("UNUSED_PARAMETER") frameGeneration: Int,
        @Suppress("UNUSED_PARAMETER") epoch: WalkRuntimeEpoch, lease: L,
        prepare: () -> FrameUnavailableProjection) {
        if (context.cameraVisible && elapsedMs - lastCameraScreenFrameUiMs >= 250L) {
            lastCameraScreenFrameUiMs = elapsedMs
            val projection = prepare()
            view.onUi {
                if (context.cameraVisible && source.ownsLease(lease)) {
                    view.cameraStatus = projection.message
                    val sourceAt = projection.sourceCapturedAt
                    if (projection.fresh && sourceAt != null) view.updateMapped(projection.boxes, projection.displayFrameId, sourceAt)
                    else view.clearOverlay()
                }
            }
        }
    }

    /** Null is the original terminal producer currentness failure; throttling preserves diagnostics. */
    fun showOverlay(input: FrameOverlayPresentationInput<L>,
        frameTimestampNs: () -> Long,
        mapBoxes: (TrackedObjectDepth?) -> List<FrameMappedDebugBox>): FrameOverlayDiagnostics? {
        val scope = input.scope
        val shouldUpdate = synchronized(frameStateLock) {
            if (!source.ownsFrame(scope.frameGeneration, scope.walkEpoch)) return null
            input.currentObservationPresent || scope.elapsedMs - lastOverlayUpdateMs >= overlayIntervalMs || input.overlayDetectionsSize == 0
        }
        if (!shouldUpdate) return input.diagnostics
        val width = context.surfaceWidth
        val height = context.surfaceHeight
        val overlayOutput = input.bestOutput?.takeIf { it.frameId == input.sourceFrameId }
        val boxes = mapBoxes(overlayOutput)
        val prediction = overlayOutput?.prediction
        val unpredictedBoxes = if (prediction == null) boxes else mapBoxes(overlayOutput?.copy(prediction = null))
        synchronized(frameStateLock) {
            if (!source.ownsFrame(scope.frameGeneration, scope.walkEpoch)) return null
            lastOverlayUpdateMs = scope.elapsedMs
        }
        val result = input.diagnostics.copy(boxCount = boxes.size)
        val frameId = input.sourceFrameId
        val capturedAt = input.detectorCapturedElapsedMs
        // Read at the original producer point; queue only this scalar, never the supplier.
        val overlayFrameTimestampNs = frameTimestampNs()
        synchronized(frameStateLock) { latestVisualOverlayFrameId = frameId ?: 0L }
        view.onUi {
            if (source.ownsFrame(scope.frameGeneration, scope.walkEpoch) && source.ownsLease(scope.lease)) {
                if (view.overlayWidth != width || view.overlayHeight != height) return@onUi
                if (synchronized(frameStateLock) { latestVisualOverlayFrameId != (frameId ?: 0L) }) return@onUi
                if (frameId == null || capturedAt == null) view.clearOverlay()
                else {
                    fun refreshLabels(): List<Long> {
                        if (!source.ownsFrame(scope.frameGeneration, scope.walkEpoch) || !source.ownsLease(scope.lease) ||
                            view.overlayWidth != width || view.overlayHeight != height ||
                            synchronized(frameStateLock) { latestVisualOverlayFrameId != frameId }
                        ) return emptyList()
                        val displayAtMs = clock()
                        val predictionRemaining = predictionRemainingMs(prediction, input.currentCapturedElapsedMs, displayAtMs)?.takeIf {
                            source.visualDepthAllowsWork(scope.lease, overlayFrameTimestampNs, scope.tracking)
                        }
                        val labelOutput = if (predictionRemaining != null) overlayOutput else overlayOutput?.copy(prediction = null)
                        val displayedBoxes = if (predictionRemaining != null) boxes else unpredictedBoxes
                        val currentLabels = displayedBoxes.map { box ->
                            if (labelOutput != null && box.best && box.trackKey == labelOutput.trackId)
                                box.copy(label = ProximityDepthDisplay.labelFor(labelOutput, input.currentCapturedElapsedMs, displayAtMs))
                            else box
                        }
                        view.updateMapped(currentLabels, frameId, capturedAt)
                        val proximityRemaining = overlayOutput?.let { ProximityDepthDisplay.remainingMs(it, input.currentCapturedElapsedMs, displayAtMs) }
                        return listOfNotNull(predictionRemaining, proximityRemaining)
                    }
                    refreshLabels().distinct().forEach { remaining ->
                        view.postOverlay(remaining + 1L) { refreshLabels() }
                    }
                }
            }
        }
        return result
    }

    fun showGap(input: FrameGapPresentationInput<L>) {
        val scope = input.scope
        if (context.cameraVisible && scope.elapsedMs - lastCameraScreenFrameUiMs >= 250L) {
            lastCameraScreenFrameUiMs = scope.elapsedMs
            var renderedGapStatus: String? = null
            fun renderGapStatus() {
                val capturedAt = input.currentCapturedElapsedMs
                if (context.cameraVisible && source.ownsFrame(scope.frameGeneration, scope.walkEpoch) &&
                    source.ownsLease(scope.lease) && lastCameraScreenFrameUiMs == scope.elapsedMs && capturedAt != null
                ) {
                    if (renderedGapStatus != null && view.cameraStatus != renderedGapStatus) return
                    val displayAtMs = clock()
                    val metricRecovered = source.metricAllowsWork(scope.arGeneration)
                    val predictions = if (metricRecovered || !source.visualDepthAllowsWork(scope.lease, scope.originalFrameTimestampNs, scope.tracking)) emptyList()
                    else input.guidanceOutputs.mapNotNull { output ->
                        val predicted = output.prediction ?: return@mapNotNull null
                        val remaining = predictionRemainingMs(predicted, capturedAt, displayAtMs) ?: return@mapNotNull null
                        remaining to String.format(Locale.US, "%s · 예측 %.2fm ±%.2fm · 공백 %dms",
                            output.className, predicted.distanceM, predicted.errorBoundM, predicted.horizonMs - remaining)
                    }.take(3)
                    val gap = diagnostics.gapParts()
                    val status = (if (metricRecovered) "Depth 재수신 · 거리 갱신 대기\n" else
                        "Depth 일시 공백 · 현재 추적 ${input.overlayCount}개\n") +
                        predictions.joinToString("\n") { it.second }.ifEmpty { "예측 근거 부족 · 거리 확인 대기" } +
                        "\n${gap.visualTracking}\n${gap.unknownRuntime}"
                    view.cameraStatus = status
                    renderedGapStatus = status
                    predictions.minOfOrNull { it.first }?.let { remaining ->
                        view.postCameraStatus(remaining + 1L) { renderGapStatus() }
                    }
                }
            }
            view.onUi { renderGapStatus() }
        }
    }

    /** Acquisition diagnostics are prepared only after the original producer throttle claim. */
    fun showStatus(input: FrameStatusPresentationInput<L>, prepareAcquisitionStatus: () -> String) {
        val scope = input.scope
        val shouldUpdate = synchronized(frameStateLock) {
            if (!source.ownsFrame(scope.frameGeneration, scope.walkEpoch)) return
            if (scope.elapsedMs - lastUiUpdateMs < statusIntervalMs) false
            else { lastUiUpdateMs = scope.elapsedMs; true }
        }
        if (!shouldUpdate) return
        val depthAcquisitionStatus = prepareAcquisitionStatus()
        val requestedOwner = context.statusOwner
        val statusContext = context.captureStatusContext()
        fun liveContextCurrent(): Boolean =
            source.ownsFrame(scope.frameGeneration, scope.walkEpoch) && source.ownsLease(scope.lease) &&
                context.isStatusContextCurrent(statusContext) && synchronized(frameStateLock) {
                    context.geometryVersion() == statusContext.geometryVersion && lastUiUpdateMs == scope.elapsedMs
                }
        view.onUi {
            if (!liveContextCurrent() || context.statusOwner !== requestedOwner) return@onUi
            val gateText = input.gateText()
            val best = input.bestOutput
            if (best == null) {
                val status = if (diagnostics.depthSupported()) "카메라/Depth 수신 중" else "Depth 미지원 fallback 대기"
                val noDetection = diagnostics.noDetectionStatus(input.snapshot)
                val detection = input.detectionSnapshot.debugStatusText(input.wallNowMs, input.timestampMs,
                    maximumOverlaySourceAgeMs, maximumOverlayFrameDeltaMs)
                val parts = diagnostics.statusParts(gateText)
                view.updateStatus(status, "$noDetection\n$detection\n" + suffix(parts, depthAcquisitionStatus))
            } else {
                val detection = input.detectionSnapshot.debugStatusText(input.wallNowMs, input.timestampMs,
                    maximumOverlaySourceAgeMs, maximumOverlayFrameDeltaMs)
                val diagnosticSuffix = suffix(diagnostics.statusParts(gateText), depthAcquisitionStatus)
                var renderedOwner = requestedOwner
                var renderedDetail: String? = null
                var renderedCameraStatus: String? = null
                var displayedNumericDepth = false
                fun refreshStatus() {
                    if (!liveContextCurrent() || context.statusOwner !== renderedOwner) return
                    renderedDetail?.let { previous ->
                        if (view.statusLabel != "객체별 depth 수신 중" || view.detailText != previous ||
                            view.statusDescription != "WalkSafe 상태. 객체별 depth 수신 중. $previous" ||
                            (statusContext.cameraVisible && view.cameraStatus != renderedCameraStatus)
                        ) return
                    }
                    val displayAtMs = clock()
                    val detail = "${ProximityDepthDisplay.summaryFor(best, input.currentCapturedElapsedMs, displayAtMs)}\n" +
                        "$detection\n" + "${ProximityDepthDisplay.geometryFor(best, input.currentCapturedElapsedMs, displayAtMs)}\n" + diagnosticSuffix
                    displayedNumericDepth = ProximityDepthDisplay.distanceFor(best, input.currentCapturedElapsedMs, displayAtMs) != null
                    view.updateStatus("객체별 depth 수신 중", detail)
                    renderedOwner = context.statusOwner
                    renderedDetail = detail
                    renderedCameraStatus = if (statusContext.cameraVisible) view.cameraStatus else null
                }
                refreshStatus()
                if (renderedDetail != null) {
                    val remaining = ProximityDepthDisplay.remainingMs(best, input.currentCapturedElapsedMs, clock())
                    ProximityDepthDisplay.expiryDelayMs(displayedNumericDepth, remaining)?.let { delay ->
                        view.postStatus(delay) { refreshStatus() }
                    }
                }
            }
            view.updateDebugUploadButton()
        }
    }

    private fun suffix(parts: FrameStatusDiagnosticParts, acquisition: String) =
        "${parts.deliveryLine}\n${parts.userMotion}\n${parts.inferencePacing}\n${parts.visualTracking}\n$acquisition\n" +
            "${parts.detector}\n${parts.unknownRuntime}\n${parts.metadataUpload}\n${parts.captureHistory}"

    private fun predictionRemainingMs(prediction: PredictedDepthEstimate?, capturedAt: Long?, displayAt: Long): Long? {
        val predicted = prediction ?: return null
        val originalCapture = capturedAt ?: return null
        if (!DepthPredictionPresentation.isCurrent(predicted, originalCapture, displayAt)) return null
        return DepthPredictionPresentation.validUntilMs(predicted, originalCapture)?.minus(displayAt)
    }
}
