package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityRawCollectionRuntimeStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun captureIsLowFrequencyMetadataOnlyAndLeavesTheFrameLockBeforeStorage() {
        val capture = source.substringAfter(
            "private fun recordRawCollectionDetectionMetadata(",
        ).substringBefore("private fun revalidateRawCollectionRuntime()")
        val publishBoundary = source.substringAfter(
            "val published = this@MainActivity.publishDetectionSnapshot(",
        ).substringBefore("if (shouldCaptureFrame)")
        val scheduling = source.substringAfter(
            "private fun scheduleDetectionIfDue(",
        ).substringBefore("private fun encodeDebugFrameJpeg(")

        assertTrue(source.contains("File(noBackupRoot, \"raw_collections\")"))
        assertTrue(source.contains("Thread(runnable, \"walksafe-raw-collection\")"))
        assertTrue(capture.contains("RAW_COLLECTION_CAPTURE_INTERVAL_MS"))
        assertTrue(capture.contains("observedAtEpochMs - elapsedWindowMs"))
        assertFalse(capture.contains("epochWindowMs"))
        assertTrue(capture.contains("rawRuntimeWindow.detectionSample("))
        assertTrue(capture.contains("rawRuntimeWindow.performanceSample("))
        assertTrue(capture.contains("rawDetectionDueButInFlightDropCount.getAndSet(0)"))
        assertTrue(capture.contains("busyDroppedSincePrevious = dueButInFlightDropCount"))
        // 16-17 #4: the frame duration is the completion interval, not the inference average.
        assertFalse(capture.contains("averageFrameDurationMs = averageProcessingFrameMs"))
        assertTrue(capture.contains("thermalThrottled ="))
        assertTrue(capture.contains("captureMetadataBatch("))
        listOf(
            "RawChunkType.VIDEO",
            "RawChunkType.AUDIO",
            "RawChunkType.EXACT_LOCATION",
            "RawChunkType.SENSOR",
            "RawChunkType.ROUTE",
            "RawChunkType.REPORT",
            "reportImageJpeg",
            "captureJpeg",
            "latestTrustedLocation",
            "routeNavigator",
            "earthOrientationTracker",
        ).forEach { forbiddenReference ->
            assertFalse(capture.contains(forbiddenReference))
        }
        assertFalse(capture.contains("frameStateLock"))
        assertTrue(scheduling.contains("tryStartDetectionWork(elapsedRealtimeMs)"))
        val admission = source.substringAfter("private fun tryStartDetectionWork(")
            .substringBefore("private fun finishDetectionWork(")
        assertTrue(admission.contains("rawDetectionDueButInFlightDropCount.incrementAndGet()"))
        assertTrue(publishBoundary.contains("recordRawCollectionDetectionMetadata("))
        assertTrue(
            publishBoundary.indexOf("recordRawCollectionDetectionMetadata(") <
                publishBoundary.indexOf("synchronized(frameStateLock)"),
        )
    }

    @Test
    fun uploadStartsOnlyAfterTheWalkOnWifiWhileChargingAndPausedStartsNone() {
        // Owner decision 3(b): the RAW upload never starts in ACTIVE or PAUSED.
        val postWalkUpload = source.substringAfter(
            "private fun scheduleRawCollectionPostWalkUpload()",
        ).substringBefore("private fun schedulePostWalkTransfers()")
        val window = source.substringAfter("private fun schedulePostWalkTransfers()")
            .substringBefore("private fun onPostWalkChargingMaybeChanged()")
        val transition = source.substringAfter(
            "private fun handleRawCollectionTransition(",
        ).substringBefore("private fun handleWalkSessionForegroundReturn()")
        assertTrue(postWalkUpload.contains("!initial.walk.state.allowsPostWalkTransfer"))
        assertTrue(postWalkUpload.contains("!isActivityForeground"))
        assertTrue(postWalkUpload.contains("ActiveNetworkTransport.WIFI"))
        assertTrue(postWalkUpload.contains("IntegratedConsentNetworkTransport.WIFI"))
        assertTrue(postWalkUpload.contains("!initial.charging"))
        assertTrue(postWalkUpload.contains("initial.gatewayWalkLease != null"))
        assertTrue(postWalkUpload.contains("sealActiveSegment(initial)"))
        assertTrue(postWalkUpload.contains("startNextUpload"))
        assertTrue(window.contains("if (!walk.state.allowsPostWalkTransfer) return"))
        assertTrue(transition.contains("WalkSessionState.ENDED"))
        assertTrue(transition.contains("WalkSessionState.SAFE_STOP"))
        assertTrue(transition.contains("cancelActiveUpload()"))
        assertTrue(transition.contains("onWalkEnded(context)"))
        assertTrue(transition.contains("discardEndedPartials(context)"))
        assertFalse(transition.contains("WalkSessionEvent.RecheckRequested"))
        assertFalse(transition.contains("startNextUpload"))
        assertFalse(source.contains("scheduleRawCollectionPausedUpload"))
        // Every trigger opens the post-walk window through the same gate.
        assertTrue(source.substringAfter("private fun onReportQueueDrainNetworkChanged()")
            .substringBefore("private fun onReportQueueDrainStepSample(")
            .contains("schedulePostWalkTransfers()"))
        assertTrue(source.substringAfter("private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection()")
            .substringBefore("override fun onPause()")
            .contains("schedulePostWalkTransfers()"))
        assertTrue(source.contains("onPostWalkChargingMaybeChanged()\n"))
        assertTrue(source.contains("charging = walk.state.allowsPostWalkTransfer && chargingStateProbe.isCharging()"))
    }
}
