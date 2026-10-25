package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityWithdrawalRestartStaticTest {
    private val source =
        ReportStaticSourceInspector.read(MAIN_ACTIVITY_PATH)

    @Test
    fun withdrawalMarkerCommitPrecedesFenceCancelAndHttp() {
        val owner = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/session/consent/IntegratedConsentController.kt").readText()
        val wiring = MainActivityRefactorSource.composition(source, "integratedConsentController")

        assertTrue(MainActivityRefactorSource.member(source, "updateIntegratedConsentDraft").contains("integratedConsentController.updateIntegratedConsentDraft(item, granted)"))
        val composition = MainActivityRefactorSource.member(source, "buildContentView")
        val save = composition.substringAfter("onConsentSave = {").substringBefore("},")
        assertTrue(save.contains("this@MainActivity.integratedConsentController.persistIntegratedConsentDraft(announce = true)"))
        assertTrue(wiring.contains("IntegratedConsentController("))
        assertTrue(wiring.contains("consentClient().saveCall("))
        assertTrue(MainActivityRefactorSource.factory(source, "createPrivacyAuthorityComposition").contains("consentClient = { integratedConsentClient }"))
        val update =
            MainActivityRefactorSource.member(owner, "updateIntegratedConsentDraft")
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
            MainActivityRefactorSource.member(owner, "persistIntegratedConsentDraft")
        assertTrue(
            appearsInOrder(
                persist,
                "integratedConsentHttpBlocked()",
                "remote.saveCall(",
            ),
        )
    }

    @Test
    fun rawWithdrawalBlocksFieldLogAndOnlyExactGrantReleasesIt() {
        val owner = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/session/consent/IntegratedConsentController.kt").readText()
        val wiring = MainActivityRefactorSource.composition(source, "integratedConsentController")

        assertTrue(wiring.contains("raw = ports.raw"))
        val rawPorts = MainActivityRefactorSource.factory(source, "createPrivacyConsent")
        assertTrue(rawPorts.contains("fieldSessionLog.blockForRawSourceWithdrawal()"))
        assertTrue(rawPorts.contains("fieldSessionLog.resetRawSourceAfterConfirmedConsent()"))
        assertTrue(MainActivityRefactorSource.member(owner, "updateIntegratedConsentDraft").contains("applyImmediateConsentWithdrawals(setOf(item))"))
        assertTrue(MainActivityRefactorSource.member(owner, "startIntegratedConsentRequest").contains("applyIntegratedConsentConfirmation("))
        val withdrawal =
            MainActivityRefactorSource.member(owner, "applyImmediateConsentWithdrawals")
        assertTrue(withdrawal.contains("raw.blockFieldLogIfReady()"))
        val confirmation =
            MainActivityRefactorSource.member(owner, "applyIntegratedConsentConfirmation")
        assertTrue(
            confirmation.contains(
                "exactPending && confirmation.selections.rawSourceCollection",
            ),
        )
        assertTrue(
            confirmation.contains(
                "raw.resetFieldLogAfterConfirmedConsentIfActive()",
            ),
        )
    }

    @Test
    fun deletionAndRawMarkersBlockBeforeFieldLogRestore() {
        val creation = MainActivityRefactorSource.member(source, "onCreate")
        assertTrue(creation.contains("initiallyBlockedForAccountDeletion"))
        assertTrue(creation.contains("accountDeletionMarkerPresentAtStartup()"))
        assertTrue(creation.contains("initiallyBlockedForRawSourceCollection"))
        assertTrue(appearsInOrder(creation,
            "readAccountDeletionFallbackMarkerAtStartup()",
            "initiallyBlockedForAccountDeletion",
            "accountDeletionInstallationResetController.restoreStartupHandoffAfterResourcesPrepared()",
            "accountDeletionInstallationResetController.inspectStartupReset(speechOutputPurgeCompleted)",
            "fieldSessionLog.recordEvent(\"app_created\")",
            "reconcileAccountDeletionFallbackMarkerAtStartup()",
        ))
        val inspection = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/PrivacyStartupInspectionController.kt").readText()
        val inspectionWiring = MainActivityRefactorSource.property(source, "privacyStartupInspectionController")
        assertTrue(inspectionWiring.contains("PrivacyStartupInspectionController("))
        assertTrue(inspectionWiring.contains("stateMachine = { accountDeletionStateMachine }"))
        assertTrue(inspectionWiring.contains("activityLease = { accountDeletionActivityLease }"))
        assertTrue(inspectionWiring.contains("capturePrivacyStartupResetPreparation()"))
        assertTrue(MainActivityRefactorSource.member(source, "startPrivacyStartupInspection")
            .contains("privacyStartupInspectionController.startInspection(prepare, inspect, onReady)"))
        assertTrue(creation.contains("startPrivacyStartupInspection("))
        val inspect = MainActivityRefactorSource.member(inspection, "startInspection")
        assertTrue(appearsInOrder(inspect, "prepare()", "resetPreparation.prepare()",
            "preparation.awaitOriginalAcknowledgements()", "!preparation.isCurrent()", "inspect(purgedAndDrained)",
            "if (!stateMachine().activityLeaseIsCurrent(activityLease()))", "if (result != PrivacyStartupInspectionResult.READY)",
            "pendingPrivacyStartupReadyAction = PendingReady(generation, onReady)",
        ))
        assertTrue(inspect.contains("privacyStartupResourcesClosed || !isCurrent(generation)"))
        assertTrue(inspect.contains("generation == privacyStartupInspectionGeneration"))
        assertTrue(MainActivityRefactorSource.member(inspection, "isCurrent", occurrence = 1)
            .contains("stateMachine().activityLeaseIsCurrent(activityLease())"))
        val capture = MainActivityRefactorSource.member(source, "capturePrivacyStartupResetPreparation")
        listOf("prepareSpeech = ::prepareSpeechOutputAccountPurgeOnWorker", "prepareRoute = ::prepareRouteSnapshotAccountPurge",
            "awaitSpeech = ::awaitPreparedSpeechOutputAccountPurge", "awaitRoute = ::awaitRouteSnapshotAccountPurgeOnWorker",
            "speechIsCurrent = ::speechOutputResetPreparationIsCurrent", "routeIsCurrent = ::routeSnapshotResetPreparationIsCurrent",
        ).forEach { assertTrue(capture.contains(it)) }
        val participants = ReportStaticSourceInspector.functionBlock(inspection, "fun <S, R> capture")
        assertTrue(participants.contains("awaitSpeech(speech)"))
        assertTrue(participants.contains("awaitRoute(route)"))
        assertTrue(participants.contains("speechPurgedAndDrained && routePurged"))
        assertTrue(participants.contains("speechIsCurrent(speech) && route?.let(routeIsCurrent) == true"))
        val resetOwner = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/reset/AccountDeletionInstallationResetController.kt").readText()
        val resetWiring = MainActivityRefactorSource.composition(source, "accountDeletionInstallationResetController")
        assertTrue(resetWiring.contains("AccountDeletionInstallationResetController("))
        assertTrue(resetWiring.contains("stateMachine = { stateMachine() }"))
        assertTrue(resetWiring.contains("activityLease = { activityLease() }"))
        val authority = MainActivityRefactorSource.factory(source, "createPrivacyAuthorityComposition")
        assertTrue(authority.contains("stateMachine = { accountDeletionStateMachine }"))
        assertTrue(authority.contains("activityLease = { accountDeletionActivityLease }"))
        assertTrue(resetWiring.contains("storage = accountDeletionResetStorage"))
        assertTrue(resetWiring.contains("fieldLog().blockForAccountDeletion()"))
        assertTrue(authority.contains("fieldLog = { fieldSessionLog }"))
        val reset = MainActivityRefactorSource.member(resetOwner, "reconcileResetAtStartup")
        assertTrue(reset.contains("if (speechOutputPurgeCompleted == false) return AccountDeletionResetResult.BLOCKED"))
        assertTrue(reset.contains("if (speechOutputPurgeCompleted != true) return AccountDeletionResetResult.BLOCKED"))
        val resetInspection = MainActivityRefactorSource.member(resetOwner, "inspectStartupReset")
        assertTrue(resetInspection.contains("AccountDeletionResetResult.BLOCKED -> if (!blockForResetFailure()) return InstallationResetStartupResult.FINISH"))
        assertTrue(resetInspection.contains("if (fresh == null || !fresh.publish()) return InstallationResetStartupResult.FINISH"))
        val resetFailure = MainActivityRefactorSource.member(resetOwner, "blockForResetFailure")
        assertTrue(appearsInOrder(resetFailure, "stateMachine().failClosed(activityLease()", "storage.blockSensitiveAndAuthorityForResetFailure()",
            "actions.blockPrivacyForResetFailure()", "storage.markRawSourceFieldLogBlocked()"))
        val handoff = MainActivityRefactorSource.member(resetOwner, "restoreStartupHandoffAfterResourcesPrepared")
        assertTrue(appearsInOrder(handoff, "stateMachine().activityLeaseIsCurrent(activityLease())",
            "stateMachine().resetForNewEnrollment(activityLease())", "startupHandoff.pending = false"))
        val preparationOwner = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/runtime/AccountDeletionPreparationController.kt").readText()
        val storeOwner = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/runtime/AccountDeletionRecoveryStore.kt").readText()
        val progressOwner = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/runtime/AccountDeletionProgressController.kt").readText()
        val preparationWiring = MainActivityRefactorSource.composition(source, "accountDeletionPreparationController")
        assertTrue(preparationWiring.contains("store = accountDeletionRecoveryStore"))
        assertTrue(preparationWiring.contains("queries = queries"))
        val preparationPorts = MainActivityRefactorSource.factory(source, "createPrivacyPreparation")
        assertTrue(preparationPorts.contains("gatewaySessionOrNull("))
        assertTrue(preparationPorts.contains("sensitivePrefs.isBlocked()"))
        assertTrue(preparationWiring.contains("accountDeletionRuntimeFenceController.value.apply()"))
        assertTrue(MainActivityRefactorSource.member(source, "onAccountDeletionConfirmClicked").contains("accountDeletionPreparationController.onConfirmClicked()"))
        val deletion = MainActivityRefactorSource.member(preparationOwner, "onConfirmClicked")
        val confirmationPreparation = MainActivityRefactorSource.member(preparationOwner, "startConfirmationPreparation")
        val markerPreparation = MainActivityRefactorSource.member(storeOwner, "prepareRecoveryMarker")
        val preparation = MainActivityRefactorSource.member(preparationOwner, "startPreparation")
        assertTrue(appearsInOrder(deletion, "queries.generalSession()", "queries.sensitiveStorageBlocked()",
            "if (!effects.applyRuntimeFence())", "startConfirmationPreparation("))
        assertTrue(appearsInOrder(confirmationPreparation, "cleanupExecutor().execute", "stages.durable(workerAttempt)", "store.prepareRecoveryMarker(") &&
            appearsInOrder(markerPreparation, "Phase.PREPARED", "fallbackMarker.create(record)") &&
            appearsInOrder(preparation, "cleanupExecutor().execute", "stages.durable(workerAttempt)", "store.preparePendingJournal("))
        val read = MainActivityRefactorSource.member(storeOwner, "readFallbackAtStartup")
        assertTrue(appearsInOrder(read, "fallbackMarker.read()", "startupFallbackState = state",
            "state !is AndroidAccountDeletionFallbackMarker.State.Absent"))
        assertTrue(MainActivityRefactorSource.member(source, "readAccountDeletionFallbackMarkerAtStartup").contains("accountDeletionRecoveryStore.readFallbackAtStartup()"))
        val storeWiring = MainActivityRefactorSource.composition(source, "accountDeletionRecoveryStore")
        assertTrue(storeWiring.contains("accountDeletionRuntimeFenceController.value.apply()"))
        val reconcile = MainActivityRefactorSource.member(storeOwner, "reconcileFallbackAtStartup")
        assertTrue(appearsInOrder(reconcile, "startupFallbackState", "fallbackPresentAtStartup", "effects.applyRuntimeFence()", "when (state)"))
        assertTrue(MainActivityRefactorSource.member(source, "reconcileAccountDeletionFallbackMarkerAtStartup").contains("accountDeletionRecoveryStore.reconcileFallbackAtStartup()"))
        val recovery = MainActivityRefactorSource.member(progressOwner, "startFallbackRecoveryAfterRestore")
        assertTrue(appearsInOrder(recovery, "AndroidAccountDeletionFallbackMarker.State.Present", "continueFromMarker(state.record)",
            "AndroidAccountDeletionFallbackMarker.State.Corrupt", "AndroidAccountDeletionFallbackMarker.State.Unavailable",
            "store.setRemoteResumeBlocked(true)", "effects.applyRuntimeFence()", "accountDeletion=recovery_blocked:fallback_marker_storage"))
        assertTrue(!recovery.contains("purgeLocalDataOnWorker"))
        assertTrue(MainActivityRefactorSource.composition(source, "accountDeletionProgressController").contains("accountDeletionRuntimeFenceController.value.apply()"))
        assertTrue(MainActivityRefactorSource.member(source, "startAccountDeletionFallbackRecoveryAfterRestore").contains("accountDeletionProgressController.startFallbackRecoveryAfterRestore()"))
    }

    @Test
    fun durableConfirmationRestoresBeforePendingMutation() {
        val owner = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/session/consent/IntegratedConsentController.kt").readText()
        val wiring = MainActivityRefactorSource.composition(source, "integratedConsentController")

        assertTrue(MainActivityRefactorSource.member(source, "restorePrivacyControlStateFromPrefs").contains("privacyStartupRestoreCoordinator.restore()"))
        val startupWiring = MainActivityRefactorSource.composition(source, "privacyStartupRestoreCoordinator")
        assertTrue(startupWiring.contains("integratedConsentController.value.restoreConsentState()"))
        val startupOwner = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/PrivacyStartupRestoreCoordinator.kt").readText()
        assertTrue(MainActivityRefactorSource.member(startupOwner, "restore").contains("consent.restoreConsentState()"))
        val restore =
            MainActivityRefactorSource.member(owner, "restoreConsentState")
        // Decode the pending/CAS journal for tuple validation; publish it only after confirmation.
        assertTrue(restore.contains("PREF_PENDING_INTEGRATED_CONSENT_MUTATION"))
        assertTrue(
            appearsInOrder(
                restore,
                "PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION",
                "restoreCurrentConfirmation(confirmation)",
                "restorePendingMutation(mutation, retry = true)",
                "PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED",
                "applyImmediateConsentWithdrawals(items)",
            ),
        )
        val pending = restore.substringAfter("val mutation = restoredPending")
            .substringBefore("sensitivePrefs.getString(PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED")
        assertTrue(
            appearsInOrder(
                pending,
                "!persistence.restoredPendingPreservesConfirmation(",
                "integratedConsentSession.failClosed()",
                "restorePendingMutation(mutation, retry = true)",
                "pendingMutationOrNull() != mutation",
                "pendingIntegratedConsentMutation = mutation",
            ),
        )
        assertTrue(!pending.contains("mutation.copy("))

        val confirmation =
            MainActivityRefactorSource.member(owner, "applyIntegratedConsentConfirmation")
        assertTrue(
            confirmation.contains("PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION"),
        )
        assertTrue(confirmation.contains("integratedConsentConfirmationJson(confirmation)"))
    }

    @Test
    fun legacyRevisionFloorRestoresBeforeAnyFullConfirmation() {
        val owner = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/session/consent/IntegratedConsentController.kt").readText()
        val wiring = MainActivityRefactorSource.composition(source, "integratedConsentController")

        assertTrue(MainActivityRefactorSource.member(source, "restorePrivacyControlStateFromPrefs").contains("privacyStartupRestoreCoordinator.restore()"))
        val startupWiring = MainActivityRefactorSource.composition(source, "privacyStartupRestoreCoordinator")
        assertTrue(startupWiring.contains("integratedConsentController.value.restoreConsentState()"))
        val startupOwner = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/PrivacyStartupRestoreCoordinator.kt").readText()
        assertTrue(MainActivityRefactorSource.member(startupOwner, "restore").contains("consent.restoreConsentState()"))
        val restore =
            MainActivityRefactorSource.member(owner, "restoreConsentState")
        assertTrue(
            appearsInOrder(
                restore,
                "restoreLegacyIntegratedConsentRevisionFloor()",
                "PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION",
            ),
        )

        val legacy =
            MainActivityRefactorSource.member(owner, "restoreLegacyIntegratedConsentRevisionFloor")
        assertTrue(legacy.contains("PREF_INTEGRATED_CONSENT_REVISION"))
        assertTrue(legacy.contains("PREF_INTEGRATED_CONSENT_CLIENT_REVISION"))
        assertTrue(legacy.contains("PREF_INTEGRATED_CONSENT_BACKEND_RECEIPT_SHA256"))
        assertTrue(legacy.contains("restoreServerRevisionFloor("))
        assertTrue(!legacy.contains("IntegratedConsentConfirmation("))
    }

    @Test
    fun fullConfirmationMustMatchEveryLegacyScalarCommitTupleField() {
        val owner = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/session/consent/IntegratedConsentController.kt").readText()
        val wiring = MainActivityRefactorSource.composition(source, "integratedConsentController")

        assertTrue(MainActivityRefactorSource.member(source, "restorePrivacyControlStateFromPrefs").contains("privacyStartupRestoreCoordinator.restore()"))
        val startupWiring = MainActivityRefactorSource.composition(source, "privacyStartupRestoreCoordinator")
        assertTrue(startupWiring.contains("integratedConsentController.value.restoreConsentState()"))
        val startupOwner = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/PrivacyStartupRestoreCoordinator.kt").readText()
        assertTrue(MainActivityRefactorSource.member(startupOwner, "restore").contains("consent.restoreConsentState()"))
        val restore =
            MainActivityRefactorSource.member(owner, "restoreConsentState")

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
