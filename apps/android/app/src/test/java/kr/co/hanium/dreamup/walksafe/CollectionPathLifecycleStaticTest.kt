package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S21-50: every collection path has its own end / withdrawal / full-deletion / release check.
 * Paths: RAW runtime aggregate, server metadata log, frame JPEG, field session log, outdoor raw GNSS.
 */
class CollectionPathLifecycleStaticTest {
    private val main = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private fun block(signature: String, end: String): String {
        val start = main.indexOf(signature)
        check(start >= 0) { "missing $signature" }
        return main.substring(start, main.indexOf(end, start).also { check(it > start) { "missing end $end" } })
    }

    @Test
    fun releaseDisablesEveryDebugPath() {
        val release = "src/release/java/kr/co/hanium/dreamup/walksafe/debuglog/"
        assertTrue(File(release + "DebugMetadataLogUploaderFactory.kt").readText()
            .contains("fun create(context: Context): MetadataLogUploader = NoopMetadataLogUploader()"))
        assertTrue(File(release + "DebugFrameCaptureUploaderFactory.kt").readText()
            .contains("fun create(context: Context): FrameCaptureUploader = NoopFrameCaptureUploader"))
        assertTrue(main.contains("fieldSessionLog = if (BuildConfig.DEBUG) {"))
        // The outdoor raw GNSS recorder exists only in the debug source set.
        assertTrue(File("src/debug/java/kr/co/hanium/dreamup/walksafe/fieldtest/OutdoorRawGnssRecorder.kt").isFile)
        assertTrue(!File("src/main/java/kr/co/hanium/dreamup/walksafe/fieldtest/OutdoorRawGnssRecorder.kt").exists())
    }

    @Test
    fun walkEndStopsInFlightDebugTransfersAndEndedRawPartials() {
        val cancel = block("private fun cancelWalkSessionOutputsInternal(", "stage(\"location_restart\")")
        assertTrue(cancel.contains("stage(\"activity_original_uploads\") { cancelActivityOriginalUploads() }"))
        val raw = block("private fun handleRawCollectionTransition(", "private fun handleWalkSessionForegroundReturn(")
        assertTrue(raw.contains("rawCollectionRuntimeCoordinator.discardEndedPartials(context)"))
        val uploads = block("private fun cancelActivityOriginalUploads() {", "private fun sensitiveDebugTransferAllowed(")
        assertTrue(uploads.contains("metadataLogUploader.cancelActiveUpload()"))
        assertTrue(uploads.contains("frameCaptureUploader.cancelActiveUpload()"))
    }

    @Test
    fun rawWithdrawalStopsEachPathSeparately() {
        val withdraw = main.substringAfter("private fun applyImmediateConsentWithdrawals(").substringBefore("\n    private fun ")
        assertTrue(withdraw.contains("if (IntegratedConsentItem.RAW_SOURCE_COLLECTION in items) cancelActivityOriginalUploads()"))
        assertTrue(withdraw.contains("rawCollectionRuntimeCoordinator.onConsentRevoked(previousReceipt)"))
        assertTrue(withdraw.contains("metadataLogUploader.setEnabled(false)"))
        assertTrue(withdraw.contains("fieldSessionLog.blockForRawSourceWithdrawal()"))
    }

    @Test
    fun fullDeletionPurgesEveryLocalStoreAndGatesTheResult() {
        val purge = main.substringAfter("private fun purgeAccountDeletionLocalDataOnCleanupThread(): Boolean {")
            .substringBefore("\n    private fun ")
        assertTrue(purge.contains("rawCollectionRuntimeCoordinator.onAccountDeleted()"))
        assertTrue(purge.contains("fieldSessionLog.purgeAll()"))
        assertTrue(purge.contains("val outdoorPositionTestPurged = DebugCollectionArtifacts.purgeOutdoorPositionTest(noBackupFilesDir)"))
        assertTrue(purge.contains("preferencesPurged && fieldSessionsPurged && outdoorPositionTestPurged"))
        val deletionFence = main.substringAfter("private fun applyAccountDeletionRuntimeFence(): Boolean {")
            .substringBefore("\n    private fun ")
        assertTrue(deletionFence.contains("cancelActivityOriginalUploads()"))
        val authorityRefresh = main.substringAfter("private fun holdIntegratedConsentAuthorityForRefresh() {")
            .substringBefore("\n    private fun ")
        assertTrue(authorityRefresh.contains("integratedConsentSession.invalidateBackendAuthority()"))
        assertTrue(authorityRefresh.contains("cancelActivityOriginalUploads()"))
        val authorityChange = block("private fun dispatchGatewayProcessSessionChanged(",
            "private fun registerGatewayCapacityNetworkObserver()")
        assertTrue(authorityChange.contains("if (consentAuthorityChanged) {"))
        assertTrue(authorityChange.substringBefore("val deliver = Runnable {")
            .contains("cancelActivityOriginalUploads()"))
        assertEquals(
            "walk end, RAW withdrawal, account deletion and both consent-authority fences cancel debug transfers",
            5,
            Regex("""(?<!fun )cancelActivityOriginalUploads\(\)""").findAll(main).count(),
        )
    }
}
