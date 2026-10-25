package kr.co.hanium.dreamup.walksafe.report

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityReportUploadStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val uploader =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportUploader.kt").readText()

    @Test
    fun activeCandidatePathOnlyEnqueuesFrozenInputAndDoesNotOpenNetwork() {
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/runtime/ReportCandidateController.kt").readText()
        assertTrue(kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.member(source, "publishCurrentFrameReportState").contains("reportCandidateController.publishCurrentFrameReportState("))
        assertTrue(kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.member(owner, "prepareReportCandidate").contains("processReportCandidate("))
        val wiring = kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.composition(source, "reportCandidateController")
        assertTrue(wiring.contains("reportQueueStore.enqueue("))

        val process = kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.member(owner, "processReportCandidate")

        assertTrue(process.contains("queue.enqueue("))
        assertTrue(process.contains("candidate.metadata.toString().toByteArray(Charsets.UTF_8)"))
        assertTrue(process.contains("imageJpeg = imageJpeg"))
        assertTrue(process.contains("walkSessionId = expectedWalkEpoch.walkSessionId"))
        assertTrue(process.contains("consentConfirmation.backendConsentReceiptSha256"))
        assertFalse(process.contains("gatewaySessionOrNull"))
        assertFalse(process.contains("uploadCall"))
        assertFalse(process.contains("isGatewayNetworkAllowed"))
        assertFalse(process.contains("enterWalkSessionSafetyStop"))
    }

    @Test
    fun gatewayCapacityBlocksOnlyAutomaticPreparationWhileEnqueueBoundaryStaysOffline() {
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/runtime/ReportCandidateController.kt").readText()
        assertTrue(kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.member(source, "publishCurrentFrameReportState").contains("reportCandidateController.publishCurrentFrameReportState("))
        assertTrue(kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.member(owner, "prepareReportCandidate").contains("processReportCandidate("))
        val wiring = kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.composition(source, "reportCandidateController")
        assertTrue(wiring.contains("reportQueueStore.enqueue("))

        val prepare = kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.member(owner, "prepareReportCandidate")

        assertFalse(prepare.contains("gatewaySessionOrNull"))
        assertTrue(prepare.contains("!explicitRequest &&"))
        assertTrue(prepare.contains("GatewayCapacityProcessState.admission("))
        assertTrue(prepare.contains("automaticReportCandidateAllowed"))
        // S14-39: same filesDir root; the OS allocatable bytes of that volume gate automatic candidates.
        val queue = kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.property(source, "reportQueueStore")
        assertTrue(queue.contains("val reportFilesDirectory = applicationContext.filesDir.canonicalFile"))
        assertTrue(queue.contains("AndroidReportQueueStore("))
        assertTrue(queue.contains("allocatableBytes = AndroidAllocatableBytes(applicationContext, reportFilesDirectory)"))
        assertTrue(owner.contains("reportCandidate=queue_disabled_or_rejected"))
    }

    @Test
    fun uploaderOwnsR1HeadersStrictReceiptAndStatusEndpoint() {
        assertTrue(uploader.contains("x-walksafe-report-id"))
        assertTrue(uploader.contains("x-walksafe-report-payload-sha256"))
        assertTrue(uploader.contains("x-walksafe-report-payload-bytes"))
        assertTrue(uploader.contains("/api/reports/v2/\${report.payload.reportId}/status"))
        assertTrue(uploader.contains("strictTransportReceipt"))
        assertFalse(uploader.contains("ingested_by_actor_id"))
    }

    @Test
    fun legacyPendingQueuePurgeBoundaryRemainsIntact() {
        assertTrue(source.contains("scheduleLegacyPendingReportQueuePurge()"))
        assertTrue(kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.member(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/runtime/LegacyPendingReportQueuePurgeController.kt").readText(), "schedule",
        ).contains("purgeOnWorker()"))
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/runtime/LegacyPendingReportQueuePurgeController.kt").readText()
        assertTrue(kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.member(owner, "schedule").contains("if (inFlight)"))
        val assembly = kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.composition(source, "legacyPendingReportQueuePurgeController")
        assertTrue(assembly.contains("purgeLegacyStore = purgeLegacyStore"))
        assertTrue(kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.factory(source, "createPrivacyLegacyPurge").contains("purgeAllWithoutLoading"))
        assertTrue(kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.member(source, "scheduleLegacyPendingReportQueuePurge").contains("legacyPendingReportQueuePurgeController.schedule(onComplete)"))
    }
}
