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
        val process = source.substringAfter("private fun processReportCandidate(")
            .substringBefore("private fun requestExplicitReport(")

        assertTrue(process.contains("reportQueueStore.enqueue("))
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
        val prepare = source.substringAfter("private fun prepareReportCandidate(")
            .substringBefore("private fun processReportCandidate(")

        assertFalse(prepare.contains("gatewaySessionOrNull"))
        assertTrue(prepare.contains("!explicitRequest &&"))
        assertTrue(prepare.contains("GatewayCapacityProcessState.admission("))
        assertTrue(prepare.contains("automaticReportCandidateAllowed"))
        assertTrue(source.contains("AndroidReportQueueStore(applicationContext.filesDir)"))
        assertTrue(source.contains("reportCandidate=queue_disabled_or_rejected"))
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
        assertTrue(source.contains("purgeLegacyPendingReportQueueOnCleanupThread()"))
        assertTrue(source.contains("legacyPendingReportQueuePurgeInFlight"))
    }
}
