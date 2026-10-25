package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MainActivityProximityDepthDisplayStaticTest {
    private fun source(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path.kt").readText()
    private val main = source("MainActivity")
    private val presentation = source("runtime/vision/frame/FramePresentationController")
    @Test fun walkingOverlayPassesItsActualFrameCaptureToTheDistanceSelector() {
        val pipeline = source("runtime/vision/frame/FramePipelineController")
        assertTrue(main.contains("framePipelineController.draw()"))
        assertTrue(pipeline.contains("presentation().showOverlay(FrameOverlayPresentationInput("))
        assertTrue(pipeline.contains("detection.capturedAtElapsedRealtimeMs, q.gray?.key?.capturedAtElapsedRealtimeMs"))
        assertTrue(pipeline.contains("geometry.buildDebugOverlayBoxes(native, overlays, overlayOutput, q.gray?.sourceWidth, q.gray?.sourceHeight,"))
        assertTrue(source("runtime/vision/frame/ArFrameGeometryFreezer").contains("ProximityDepthDisplay.labelFor(output, capturedAtElapsedRealtimeMs, elapsedRealtimeMs())"))
    }
    @Test fun mappedBoxLabelsAreReevaluatedAtUiConsumptionAndBothExpiries() {
        val renderer = MainActivityRefactorSource.member(presentation, "showOverlay")
        assertTrue(renderer.contains("val displayAtMs = clock()"))
        assertTrue(renderer.contains("box.copy(label = ProximityDepthDisplay.labelFor(labelOutput, input.currentCapturedElapsedMs, displayAtMs))"))
        assertTrue(renderer.contains("ProximityDepthDisplay.remainingMs(it, input.currentCapturedElapsedMs, displayAtMs)"))
        assertTrue(renderer.contains("view.updateMapped(currentLabels, frameId, capturedAt)"))
        assertTrue(renderer.contains("listOfNotNull(predictionRemaining, proximityRemaining)"))
        assertTrue(renderer.contains("view.postOverlay(remaining + 1L) { refreshLabels() }"))
        assertTrue(main.contains("debugBboxOverlay.postDelayed({ action() }, delayMs)"))
    }
    @Test fun oldExpiryCallbacksHaveFrameEpochLeaseAndViewportGuardsWithoutRetainingNativeFrame() {
        val renderer = presentation.substringAfter("fun refreshLabels(): List<Long>").substringBefore("refreshLabels().distinct()")
        listOf("!source.ownsFrame(scope.frameGeneration, scope.walkEpoch)", "!source.ownsLease(scope.lease)",
            "latestVisualOverlayFrameId != frameId", "view.overlayWidth != width", "view.overlayHeight != height",
            "overlayFrameTimestampNs").forEach { assertTrue(it, renderer.contains(it)) }
        assertFalse(renderer.contains("frame.timestamp"))
        assertFalse(renderer.contains("frameTimestampNs()"))
    }
    @Test fun liveStateSummaryAndGeometryUseTheSameRealFrameCaptureAsTheOverlay() {
        val live = MainActivityRefactorSource.member(presentation, "showStatus")
        assertTrue(live.contains("val displayAtMs = clock()"))
        assertTrue(live.contains("ProximityDepthDisplay.summaryFor(best, input.currentCapturedElapsedMs, displayAtMs)"))
        assertTrue(live.contains("ProximityDepthDisplay.geometryFor(best, input.currentCapturedElapsedMs, displayAtMs)"))
        val update = MainActivityRefactorSource.member(main, "updateStatus")
        assertTrue(update.contains("detailText.text = detail"))
        assertTrue(update.contains("cameraScreenStatus.text = \"\$status\\n\$detail\""))
        assertTrue(update.contains("statusText.contentDescription = \"WalkSafe 상태. \$status. \$detail\""))
    }
    @Test fun liveStatusExpiryChecksItsActualAccountWalkFrameViewportAndDisplayedOwner() {
        val context = main.substringAfter("override fun isStatusContextCurrent(").substringBefore("override fun geometryVersion()")
        listOf("reporterUserId == context.actorId", "gatewaySessionGeneration == context.accountGeneration",
            "nativeUiPage == context.page", "cameraScreenVisible == context.cameraVisible",
            "this@MainActivity.surfaceWidth == context.viewportWidth", "this@MainActivity.surfaceHeight == context.viewportHeight")
            .forEach { assertTrue(it, context.contains(it)) }
        val live = MainActivityRefactorSource.member(presentation, "showStatus")
        listOf("source.ownsFrame(scope.frameGeneration, scope.walkEpoch)", "source.ownsLease(scope.lease)",
            "context.isStatusContextCurrent(statusContext)", "context.geometryVersion() == statusContext.geometryVersion",
            "lastUiUpdateMs == scope.elapsedMs", "context.statusOwner !== renderedOwner", "view.detailText != previous",
            "view.cameraStatus != renderedCameraStatus", "view.statusDescription !=",
            "displayedNumericDepth = ProximityDepthDisplay.distanceFor(best, input.currentCapturedElapsedMs, displayAtMs) != null",
            "ProximityDepthDisplay.expiryDelayMs(displayedNumericDepth, remaining)", "view.postStatus(delay) { refreshStatus() }")
            .forEach { assertTrue(it, live.contains(it)) }
        assertTrue(main.contains("statusText.postDelayed({ action() }, delayMs)"))
        assertFalse(live.contains("frame.timestamp")); assertFalse(live.contains("captureLog.clear"))
    }
    @Test fun newerStatusAndLeavingThenReturningToAScreenInvalidateTheOldOpaqueOwner() {
        assertTrue(MainActivityRefactorSource.member(main, "updateStatus").contains("statusPresentationOwner = Any()"))
        val workflow = source("ui/workflow/NativeWorkflowController")
        assertTrue(workflow.contains("invalidatePagePresentationOwner()"))
        val workflowBinding = MainActivityRefactorSource.composition(main, "nativeWorkflowController")
        val invalidatePage = ReportStaticSourceInspector.functionBlock(workflowBinding,
            "override fun invalidatePagePresentationOwner")
        assertTrue(invalidatePage.contains("this@MainActivity.statusPresentationOwner = Any()"))
        assertTrue(invalidatePage.contains("this@MainActivity.userReportInteractionOwner.invalidateLocalQuarantineSelection()"))
        val camera = MainActivityRefactorSource.property(main, "cameraScreenVisible")
        assertTrue(camera.contains("if (field != value)")); assertTrue(camera.contains("statusPresentationOwner = Any()"))
        val live = MainActivityRefactorSource.member(presentation, "showStatus")
        assertTrue(live.contains("context.statusOwner !== requestedOwner"))
        assertTrue(live.contains("renderedOwner = context.statusOwner"))
    }
}
