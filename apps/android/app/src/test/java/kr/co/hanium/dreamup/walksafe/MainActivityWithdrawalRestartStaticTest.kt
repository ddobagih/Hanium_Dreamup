package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityWithdrawalRestartStaticTest {
    private val source =
        ReportStaticSourceInspector.read(MAIN_ACTIVITY_PATH)

    @Test
    fun withdrawalMarkerCommitPrecedesFenceCancelAndHttp() {
        val update =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun updateIntegratedConsentDraft",
            )
        assertTrue(
            appearsInOrder(
                update,
                "val rawFencePersisted =",
                "val markerPersisted = rawFencePersisted &&",
                ".putString(PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED, marker)",
                "applyImmediateConsentWithdrawals(setOf(item))",
                "cancelIntegratedConsentControlCall()",
            ),
        )
        assertTrue(update.contains("if (!markerPersisted)"))
        assertTrue(update.contains("integratedConsentSession.failClosed()"))

        val persist =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun persistIntegratedConsentDraft",
            )
        assertTrue(
            appearsInOrder(
                persist,
                "integratedConsentHttpBlocked()",
                "integratedConsentClient.saveCall(",
            ),
        )
    }

    @Test
    fun rawWithdrawalBlocksFieldLogAndOnlyExactGrantReleasesIt() {
        val withdrawal =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun applyImmediateConsentWithdrawals",
            )
        assertTrue(withdrawal.contains("fieldSessionLog.blockForRawSourceWithdrawal()"))
        val confirmation =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun applyIntegratedConsentConfirmation",
            )
        assertTrue(
            confirmation.contains(
                "exactPending && confirmation.selections.rawSourceCollection",
            ),
        )
        assertTrue(
            confirmation.contains(
                "fieldSessionLog.resetRawSourceAfterConfirmedConsent()",
            ),
        )
    }

    @Test
    fun deletionAndRawMarkersBlockBeforeFieldLogRestore() {
        val creation =
            ReportStaticSourceInspector.functionBlock(
                source,
                "override fun onCreate",
            )
        assertTrue(creation.contains("initiallyBlockedForAccountDeletion"))
        assertTrue(creation.contains("accountDeletionMarkerPresentAtStartup()"))
        assertTrue(creation.contains("initiallyBlockedForRawSourceCollection"))
        assertTrue(
            appearsInOrder(
                creation,
                "readAccountDeletionFallbackMarkerAtStartup()",
                "initiallyBlockedForAccountDeletion",
                "reconcileAccountDeletionResetAtStartup()",
                "fieldSessionLog.recordEvent(\"app_created\")",
                "reconcileAccountDeletionFallbackMarkerAtStartup()",
            ),
        )

        val deletion =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun onAccountDeletionConfirmClicked",
            )
        val preparation =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun startAccountDeletionPreparation",
            )
        val confirmationPreparation =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun startAccountDeletionConfirmationPreparation",
            )
        val markerPreparation =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun prepareAccountDeletionRecoveryMarker",
            )
        assertTrue(
            appearsInOrder(
                deletion,
                "gatewaySessionOrNull(",
                "sensitivePrefs.isBlocked()",
                "applyAccountDeletionRuntimeFence()",
                "startAccountDeletionConfirmationPreparation(",
            ),
        )
        assertTrue(
            appearsInOrder(
                confirmationPreparation + "\n" + markerPreparation + "\n" + preparation,
                "accountDeletionCleanupExecutor.execute",
                "Phase.PREPARED",
                "accountDeletionFallbackMarker.create(record)",
                "prepareAccountDeletionPendingJournal(",
            ),
        )

        val read =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun readAccountDeletionFallbackMarkerAtStartup",
            )
        assertTrue(
            appearsInOrder(
                read,
                "accountDeletionFallbackMarker.read()",
                "accountDeletionStartupFallbackState = state",
                "state !is AndroidAccountDeletionFallbackMarker.State.Absent",
            ),
        )
        val reconcile =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun reconcileAccountDeletionFallbackMarkerAtStartup",
            )
        assertTrue(
            appearsInOrder(
                reconcile,
                "accountDeletionStartupFallbackState",
                "accountDeletionFallbackPresentAtStartup",
                "applyAccountDeletionRuntimeFence()",
                "when (state)",
            ),
        )
        val recovery =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun startAccountDeletionFallbackRecoveryAfterRestore",
            )
        assertTrue(
            appearsInOrder(
                recovery,
                "AndroidAccountDeletionFallbackMarker.State.Present",
                "continueAccountDeletionFromMarker(state.record)",
                "AndroidAccountDeletionFallbackMarker.State.Corrupt",
                "AndroidAccountDeletionFallbackMarker.State.Unavailable",
                "accountDeletionRemoteResumeBlocked = true",
                "applyAccountDeletionRuntimeFence()",
                "accountDeletion=recovery_blocked:fallback_marker_storage",
            ),
        )
        assertTrue(!recovery.contains("purgeAccountDeletionLocalDataOnCleanupThread"))
    }

    @Test
    fun durableConfirmationRestoresBeforePendingMutation() {
        val restore =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun restorePrivacyControlStateFromPrefs",
            )
        assertTrue(
            appearsInOrder(
                restore,
                "PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION",
                "PREF_PENDING_INTEGRATED_CONSENT_MUTATION",
            ),
        )
        assertTrue(restore.contains("restoreCurrentConfirmation(confirmation)"))

        val confirmation =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun applyIntegratedConsentConfirmation",
            )
        assertTrue(
            confirmation.contains("PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION"),
        )
        assertTrue(confirmation.contains("integratedConsentConfirmationJson(confirmation)"))
    }

    @Test
    fun legacyRevisionFloorRestoresBeforeAnyFullConfirmation() {
        val restore =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun restorePrivacyControlStateFromPrefs",
            )
        assertTrue(
            appearsInOrder(
                restore,
                "restoreLegacyIntegratedConsentRevisionFloor()",
                "PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION",
            ),
        )

        val legacy =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun restoreLegacyIntegratedConsentRevisionFloor",
            )
        assertTrue(legacy.contains("PREF_INTEGRATED_CONSENT_REVISION"))
        assertTrue(legacy.contains("PREF_INTEGRATED_CONSENT_CLIENT_REVISION"))
        assertTrue(legacy.contains("PREF_INTEGRATED_CONSENT_BACKEND_RECEIPT_SHA256"))
        assertTrue(legacy.contains("restoreServerRevisionFloor("))
        assertTrue(!legacy.contains("IntegratedConsentConfirmation("))
    }

    @Test
    fun fullConfirmationMustMatchEveryLegacyScalarCommitTupleField() {
        val restore =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun restorePrivacyControlStateFromPrefs",
            )

        assertTrue(restore.contains("!legacyFloorRestored"))
        assertTrue(restore.contains("confirmation.installationId != installationId"))
        assertTrue(restore.contains("confirmation.policyVersion != legacyPolicyVersion"))
        assertTrue(restore.contains("confirmation.revision != legacyRevision"))
        assertTrue(restore.contains("confirmation.clientRevision != legacyClientRevision"))
        assertTrue(restore.contains("confirmation.selections != legacySelections"))
        assertTrue(restore.contains("confirmation.backendConsentReceiptSha256 != legacyReceipt"))
        assertTrue(
            restore.contains("confirmation.gatewayAuditRecordSha256 != storedGatewayAudit"),
        )
        assertTrue(restore.contains("confirmation.controlSecret != controlSecret"))
        assertTrue(restore.contains("!integratedConsentSession.restoreCurrentConfirmation(confirmation)"))
    }

    private fun appearsInOrder(source: String, vararg tokens: String): Boolean {
        if (
            source.isEmpty() ||
            tokens.isEmpty() ||
            tokens.any(String::isBlank) ||
            tokens.toSet().size != tokens.size
        ) return false
        var offset = 0
        tokens.forEach { token ->
            val match = source.indexOf(token, offset)
            if (match < 0) return false
            offset = match + token.length
        }
        return true
    }

    private companion object {
        const val MAIN_ACTIVITY_PATH =
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"
    }
}
