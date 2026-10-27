package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S21-50: every collection path has its own end / withdrawal / full-deletion / release check.
 * Paths: RAW runtime aggregate, server metadata log, frame JPEG, field session log, outdoor raw GNSS.
 */
class CollectionPathLifecycleStaticTest {
    private val main = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private fun ownerFunction(property: String, path: String, name: String): String {
        MainActivityRefactorSource.composition(main, property)
        return MainActivityRefactorSource.member(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), name,
        )
    }

    @Test
    fun releaseDisablesEveryDebugPath() {
        val release = "src/release/java/kr/co/hanium/dreamup/walksafe/debuglog/"
        assertTrue(File(release + "DebugMetadataLogUploaderFactory.kt").readText()
            .contains("fun create(context: Context): MetadataLogUploader = NoopMetadataLogUploader()"))
        assertTrue(File(release + "DebugFrameCaptureUploaderFactory.kt").readText()
            .contains("fun create(context: Context): FrameCaptureUploader = NoopFrameCaptureUploader"))
        assertTrue(main.contains("fieldSessionLog = privacyStartupResources.acquireOnWorker(create = { if (BuildConfig.DEBUG) {"))
        // The outdoor raw GNSS recorder exists only in the debug source set.
        assertTrue(File("src/debug/java/kr/co/hanium/dreamup/walksafe/fieldtest/OutdoorRawGnssRecorder.kt").isFile)
        assertTrue(!File("src/main/java/kr/co/hanium/dreamup/walksafe/fieldtest/OutdoorRawGnssRecorder.kt").exists())
    }

    @Test
    fun walkEndStopsInFlightDebugTransfersAndEndedRawPartials() {
        val cancel = ownerFunction("walkSessionCleanupController",
            "session/runtime/WalkSessionCleanupController.kt", "cancelWalkSessionOutputsInternal")
        assertTrue(cancel.contains("stage(\"activity_original_uploads\") { reports.cancelOriginalUploads() }"))
        val cleanup = MainActivityRefactorSource.composition(main, "walkSessionCleanupController")
        assertTrue(cleanup.contains("override fun cancelOriginalUploads() = cancelActivityOriginalUploads()"))
        assertTrue(MainActivityRefactorSource.member(main, "endCurrentWalk").contains("walkSessionCleanupController.cancelWalkSessionOutputs(reason)"))
        assertTrue(main.contains("endCurrentWalk(\"voice_walk_end_confirmed\""))

        val raw = ownerFunction("rawCollectionTransferController",
            "report/runtime/RawCollectionTransferController.kt", "handleRawCollectionTransition")
        assertTrue(raw.contains("rawCollectionRuntimeCoordinator.discardEndedPartials(context)"))
        val rawBinding = MainActivityRefactorSource.member(main, "initializeRawCollectionTransferController")
        assertTrue(rawBinding.contains("rawCollectionTransferController.initializeCoordinator(object : RawCollectionTransferController.Peer"))
        assertTrue(rawBinding.contains("override fun discardEndedPartials(context: RawCollectionRuntimeContext) { rawCollectionRuntimeCoordinator.discardEndedPartials(context) }"))
        assertTrue(MainActivityRefactorSource.member(main, "onCreate")
            .contains("initializeRawCollectionTransferController()"))
        val transition = MainActivityRefactorSource.function(main, "private fun transitionWalkSession(")
        assertTrue(transition.contains("reports.handleRawCollectionTransition(transition, event)"))
        val transitionBinding = MainActivityRefactorSource.composition(main, "walkSessionTransitionController")
        assertTrue(transitionBinding.contains("this@MainActivity.rawCollectionTransferController.handleRawCollectionTransition(transition, event)"))
        val uploads = MainActivityRefactorSource.member(main, "cancelActivityOriginalUploads")
        assertTrue(uploads.contains("metadataLogUploader.cancelActiveUpload()"))
        assertTrue(uploads.contains("frameCaptureUploader.cancelActiveUpload()"))
    }

    @Test
    fun rawWithdrawalStopsEachPathSeparately() {
        val withdraw = ownerFunction("integratedConsentController",
            "session/consent/IntegratedConsentController.kt", "applyImmediateConsentWithdrawals")
        assertTrue(withdraw.contains("if (IntegratedConsentItem.RAW_SOURCE_COLLECTION in items) raw.cancelOriginalUploads()"))
        assertTrue(withdraw.contains("raw.withdrawReceiptIfReady(previousReceipt)"))
        assertTrue(withdraw.contains("raw.disableMetadataIfReady()"))
        assertTrue(withdraw.contains("raw.blockFieldLogIfReady()"))
        val binding = MainActivityRefactorSource.factory(main, "createPrivacyConsent")
        assertTrue(binding.contains("override fun cancelOriginalUploads() = cancelActivityOriginalUploads()"))
        assertTrue(binding.contains("rawCollectionRuntimeCoordinator.onConsentRevoked(previousReceipt)"))
        assertTrue(binding.contains("metadataLogUploader.setEnabled(false)"))
        assertTrue(binding.contains("fieldSessionLog.blockForRawSourceWithdrawal()"))
    }

    @Test
    fun fullDeletionPurgesEveryLocalStoreAndGatesTheResult() {
        val purge = MainActivityRefactorSource.function(main, "private fun purgeAccountDeletionLocalDataOnCleanupThread(")
        assertTrue(purge.contains("participants.purgeRawCollections()"))
        assertTrue(purge.contains("participants.purgePersistentFieldLogger()"))
        assertTrue(purge.contains("val outdoorPositionTestPurged = runCatching { participants.purgeOutdoorPositionArtifacts() }.getOrDefault(false)"))
        assertTrue(Regex("""preferencesPurged && fieldSessionsPurged && speechOutputPurged &&\s*outdoorPositionTestPurged""").containsMatchIn(purge))
        val purgeBinding = MainActivityRefactorSource.factory(main, "createPrivacyLocalPurge")
        assertTrue(purgeBinding.contains("override fun purgeRawCollections() = rawCollectionRuntimeCoordinator.onAccountDeleted()"))
        assertTrue(purgeBinding.contains("override fun purgePersistentFieldLogger() = fieldSessionLog.purgeAll()"))
        assertTrue(purgeBinding.contains("override fun purgeOutdoorPositionArtifacts() = DebugCollectionArtifacts.purgeOutdoorPositionTest(noBackupFilesDir)"))

        val deletionFence = MainActivityRefactorSource.function(main, "private fun applyAccountDeletionRuntimeFence(")
        assertTrue(deletionFence.contains("reports.cancelOriginalUploads()"))
        val deletionBinding = MainActivityRefactorSource.factory(main, "createPrivacyRuntimeFence")
        assertTrue(deletionBinding.contains("override fun cancelOriginalUploads() { cancelActivityOriginalUploads() }"))
        assertTrue(deletionBinding.contains("rawCollectionRuntimeCoordinator.fenceAccountDeletionAdmission()"))
        assertFalse(deletionBinding.contains("executeRawCollectionTask"))
        assertFalse(deletionBinding.contains("rawCollectionRuntimeCoordinator.onAccountDeleted()"))
        val authorityRefresh = ownerFunction("integratedConsentController",
            "session/consent/IntegratedConsentController.kt", "holdIntegratedConsentAuthorityForRefresh")
        assertTrue(authorityRefresh.contains("integratedConsentSession.invalidateBackendAuthority()"))
        assertTrue(authorityRefresh.contains("raw.cancelOriginalUploads()"))
        val consentBinding = MainActivityRefactorSource.factory(main, "createPrivacyConsent")
        assertTrue(consentBinding.contains("override fun cancelOriginalUploads() = cancelActivityOriginalUploads()"))
        val authorityChange = MainActivityRefactorSource.function(main, "private fun dispatchGatewayProcessSessionChanged(")
        assertTrue(authorityChange.contains("if (consentAuthorityChanged) {"))
        assertTrue(authorityChange.substringBefore("val deliver = Runnable {")
            .contains("runtime.cancelActivityOriginalUploads()"))
        val actorBinding = MainActivityRefactorSource.composition(main, "actorSessionLifecycleCoordinator")
        assertTrue(actorBinding.contains("override fun cancelActivityOriginalUploads() { this@MainActivity.cancelActivityOriginalUploads() }"))
        val walkEnd = ownerFunction("walkSessionCleanupController",
            "session/runtime/WalkSessionCleanupController.kt", "cancelWalkSessionOutputsInternal")
        val rawWithdrawal = ownerFunction("integratedConsentController",
            "session/consent/IntegratedConsentController.kt", "applyImmediateConsentWithdrawals")
        // Count the five executing fences, not the now-shared Main adapter declarations.
        val requiredFences = listOf(walkEnd, rawWithdrawal, deletionFence, authorityRefresh, authorityChange)
        val cancelCall = Regex("""(?:reports|raw|runtime)\.cancel(?:Activity)?OriginalUploads\(\)""")
        requiredFences.forEach { fence ->
            assertEquals("each required fence cancels original uploads exactly once", 1,
                cancelCall.findAll(fence).count())
        }
        assertEquals("walk end, RAW withdrawal, account deletion and both consent-authority fences", 5,
            requiredFences.sumOf { cancelCall.findAll(it).count() })
    }
}
